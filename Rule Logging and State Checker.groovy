/*
 *  Private Boolean Manager
 *
 *  Scans Rule Machine and Button Controller child apps, reports each rule's
 *  Private Boolean state and Last Run time, and provides bulk plus in-table
 *  Private Boolean controls.
 *
 *  Designed initially by John Land, built by Claude AI with an assist by ChatGPT,
 *  then revised to incorporate the excellent work of and feedback from hubitrep
 *  (the clickable cells are genius).
 *
 *  Notes:
 *  - Uses Hubitat local/internal JSON endpoints:
 *      /hub2/appsList
 *      /installedapp/statusJson/{appId}
 *      /installedapp/configure/json/{appId}   (PB use detection)
 *      /apps/api/{thisAppId}/setPB?id={ruleId}&value=true|false
 *      /apps/api/{thisAppId}/setpref?key={prefKey}&value=true|false
 *      /apps/api/{thisAppId}/report
 *      /apps/api/{thisAppId}/RM-BC_Rules.csv
 *  - Some of these endpoints are not a formal public API and could change in a
 *    future Hubitat platform release.
 *  - Private Boolean toggling uses RMUtils.sendAction() with RM version "5.0".
 *    Rules from earlier RM versions will display PB state but the toggle may not work.
 *
 */

import hubitat.helper.RMUtils
import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String RM_BASE_URL                    = "http://127.0.0.1:8080"
@Field static final String RM_VERSION                     = "5.0"
@Field static final int    SCAN_TIMEOUT_SECS              = 360   // max seconds before Phase 1 scan is force-finalized
@Field static final int    LOGS_OFF_DELAY_SECS            = 1800  // seconds before debug logging auto-disables
@Field static final int    CONFIGURE_MAX_IN_FLIGHT        = 3     // max simultaneous configure/json requests during PB-use scan
@Field static final int    CONFIGURE_REQUEST_TIMEOUT_SECS = 20    // per-rule watchdog timeout for configure/json callbacks
@Field static final int    CONFIGURE_TOTAL_TIMEOUT_SECS   = 600   // max seconds before Phase 2 PB-use scan is force-finalized
@Field static final int    CONFIGURE_HEARTBEAT_SECS       = 30    // silence timeout for Phase 2 progress

// Transient scan state lives in @Field static to avoid database writes during a scan.
// If the app class is reloaded during a scan (e.g. on code save or hub restart),
// the scan is abandoned and can be run again.
@Field static String    currentScanId           = null
@Field static Long      scanStartMs             = 0L
@Field static List<Map> scanRuleQueue           = null
@Field static Map       scanPartialResults      = null   // keyed by ruleId String; holds RM/BC rule scan rows
@Field static String    configureScanId         = null   // Phase 2 scan ID, separate from Phase 1
@Field static List<Map> configureQueue          = null   // rule list for Phase 2 configure/json queue
@Field static Map       configureResults        = [:]    // ruleId -> Boolean pbUsed, accumulated in Phase 2
@Field static Integer   configureNextIdx        = 0      // next configureQueue index to launch
@Field static Integer   configureInFlight       = 0      // number of configure/json requests currently in flight
@Field static Integer   configureTotalRules     = 0      // total number of rules expected in Phase 2
@Field static Map       configureInflight       = [:]    // ruleId -> [startedMs, name], for dropped-response watchdog
@Field static Long      configureLastProgressMs = 0L

definition(
    name:        "Private Boolean Manager v. 1.47",
    namespace:   "johnland",
    author:      "John Land & AI",
    description: "Scans RM/BC rules, reports Private Boolean state and Last Run time, and sets Private Boolean values in bulk or from the report table.",
    category:    "Utility",
    singleInstance: false,
    installOnOpen:  true,
    oauth:          true,
    iconUrl:   '',
    iconX2Url: '',
    importUrl: "https://raw.githubusercontent.com/JohnFLand/Private-Boolean-Manager/refs/heads/main/Private_Boolean_Manager.groovy"    
)

preferences {
    page(name: "mainPage")
}

// ── OAuth endpoint mapping ────────────────────────────────────────────────────
// Called by the browser JS click handler to toggle a rule's Private Boolean.
// GET /apps/api/{thisAppId}/setPB?id={ruleId}&value=true|false&access_token={token}

mappings {
    path("/setPB")           { action: [GET: "handleSetPBEndpoint"] }
    path("/setpref")         { action: [GET: "handleSetPrefEndpoint"] }
    path("/report")          { action: [GET: "handleReportEndpoint"] }
    path("/RM-BC_Rules.csv") { action: [GET: "handleRmCsvEndpoint"] }
    path("/bulkPB")          { action: [GET: "handleBulkPBEndpoint"] }
}

// ============================================================
// Lifecycle
// ============================================================

void installed() {
    if (debugEnable) log.debug "PBM: installed — ${app.name}"
    checkOAuth()           // auto-enable OAuth and create token on first install
    initialize()
    // No auto-scan on install: user must click a scan button.
}

void updated() {
    if (debugEnable) log.debug "PBM: updated — label: '${app.label}', scan active: ${currentScanId != null || configureScanId != null}"
    // Apply any rename entered in the Controls section.
    // Must happen before initialize() so the new label is visible immediately.
    String newLabel = settings.vAppLabel?.trim()
    if (newLabel && newLabel != app.label) {
        app.updateLabel(newLabel)
        log.info "PBM: app label updated to '${newLabel}'"
    }

    boolean scanWasActive = (currentScanId != null || configureScanId != null)
    initialize()
    if (scanWasActive) {
        state.scanStatus  = "<i>Scan was cancelled because app settings were saved. Click a scan button to run again.</i>"
    } else {
        reRenderReportIfCached()
    }
}

void initialize() {
    if (currentScanId != null) {
        log.warn "initialize: aborting in-progress scan (scanId: ${currentScanId}) — re-scan when ready"
    }
    currentScanId      = null
    scanStartMs        = 0L
    scanRuleQueue      = null
    scanPartialResults = null

    unschedule("finalizeScanTimeout")
    unschedule("finalizeUsageScan")
    unschedule("configurePump")
    unschedule("configureHeartbeat")

    state.remove("scanRuleQueue")   // legacy cleanup: was persisted to state in older versions
    configureScanId         = null
    configureQueue          = null
    configureResults        = [:]
    configureNextIdx        = 0
    configureInFlight       = 0
    configureTotalRules     = 0
    configureInflight       = [:]
    configureLastProgressMs = 0L

    // Set or clear the PB apply schedules (daily and/or interval).
    unschedule("scheduledApplyPB")
    unschedule("scheduledApplyPBInterval")

    if (settings.vScheduleTime) {
        try {
            def t = timeToday(settings.vScheduleTime, location.timeZone)
            String h = t.format("HH", location.timeZone)
            String m = t.format("mm", location.timeZone)
            schedule("0 ${m} ${h} * * ?", "scheduledApplyPB")
            if (debugEnable) log.debug "PBM: Daily PB apply scheduled at ${h}:${m}"
        } catch (Exception e) {
            log.warn "PBM: Could not create daily schedule — ${e.message}"
        }
    }

    if (settings.vScheduleIntervalMins) {
        try {
            int mins = settings.vScheduleIntervalMins as int
            schedule("0 0/${mins} * * * ?", "scheduledApplyPBInterval")
            if (debugEnable) log.debug "PBM: Interval PB apply scheduled every ${mins} minute(s)"
        } catch (Exception e) {
            log.warn "PBM: Could not create interval schedule — ${e.message}"
        }
    }

    // Subscribe to hub reboot event so we can apply PB changes after restart.
    unsubscribe()
    if (settings.vRebootApplyEnabled) {
        subscribe(location, "systemStart", handleSystemStart)
        if (debugEnable) log.debug "PBM: Subscribed to systemStart for post-reboot PB apply"
    }

    if (debugEnable) {
        runIn(LOGS_OFF_DELAY_SECS, "logsOff")
    }
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// ============================================================
// Scheduled daily PB apply
// ============================================================
// Reads the checkbox state persisted by the table UI and applies it
// identically to "Apply selected PB changes". Runs at the time set
// in the Controls section; scheduled/unscheduled via initialize().

void scheduledApplyPB() {
    log.info "PBM: Scheduled PB apply triggered."

    String cbJson = getPrefString("pbCheckboxState", "{}")
    if (!cbJson || cbJson == "{}") {
        log.info "PBM: Scheduled apply — no checkbox state saved; nothing to do."
        state.scheduledApplyLastRun = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        state.scheduledApplyResult  = "No rules selected"
        return
    }

    List<String> trueIds  = []
    List<String> falseIds = []
    try {
        Map cbState = new groovy.json.JsonSlurper().parseText(cbJson) as Map
        trueIds  = (cbState?.get("true")  ?: []).collect { it.toString() }
        falseIds = (cbState?.get("false") ?: []).collect { it.toString() }
        // TRUE wins when a rule ID appears in both lists
        Set<String> trueSet = trueIds as Set<String>
        falseIds = falseIds.findAll { !(it in trueSet) }
    } catch (Exception e) {
        log.warn "PBM: scheduledApplyPB: could not parse checkbox state — ${e.message}"
        state.scheduledApplyLastRun = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        state.scheduledApplyResult  = "Error reading checkbox state"
        return
    }

    trueIds.each  { id -> RMUtils.sendAction([id as Long], "setRuleBooleanTrue",  app.label, RM_VERSION) }
    falseIds.each { id -> RMUtils.sendAction([id as Long], "setRuleBooleanFalse", app.label, RM_VERSION) }

    updateCachedPbStatesAfterBulkSet(trueIds, falseIds)

    state.scheduledApplyLastRun = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    state.scheduledApplyResult  = "TRUE: ${trueIds.size()}, FALSE: ${falseIds.size()}"
    log.info "PBM: Scheduled PB apply complete — TRUE: ${trueIds.size()}, FALSE: ${falseIds.size()}"
}

// Interval-triggered wrapper — delegates to scheduledApplyPB() so both schedule
// types share the same logic and update the same last-run state.
void scheduledApplyPBInterval() { scheduledApplyPB() }

// Fires when the hub finishes booting.  Applies the saved PB checkbox state
// after the configured delay (0 = immediately, i.e. as soon as the app runs).
void handleSystemStart(evt) {
    int delaySecs = ((settings.vRebootApplyDelayMins ?: 0) as int) * 60
    if (delaySecs > 0) {
        log.info "PBM: Hub reboot detected — PB apply in ${settings.vRebootApplyDelayMins} minute(s)"
        runIn(delaySecs, "scheduledApplyPB")
    } else {
        log.info "PBM: Hub reboot detected — applying PB changes now"
        scheduledApplyPB()
    }
}

// Re-render the report HTML using rows cached in state.scanRowsJson.
// Called from updated() so display-setting changes apply on Done without a rescan.
void reRenderReportIfCached() {
    if (!state.scanRowsJson) return
    try {
        List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson) as List<Map>
        state.reportHtml = buildReportHtml(rows)
        if (debugEnable) log.debug "PBM: report re-rendered from cached scan data (${rows.size()} rules)"
    } catch (Exception e) {
        log.warn "reRenderReportIfCached: could not re-render — ${e.message}"
    }
}

// ── OAuth token management ────────────────────────────────────────────────────
// Self-enabling OAuth: on first install or page open, the app uses the hub's
// internal loopback API to enable OAuth on its own app code, then creates the
// access token — so the user never needs to visit Apps Code manually.

// Step 1: look up this app's type ID from /hub2/userAppTypes.
// The name must exactly match the name: field in definition().
private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: RM_BASE_URL, path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == app.name }
            if (match) typeId = match.id?.toString()
        }
    } catch (Exception e) {
        log.debug "getAppTypeId: could not fetch user app types — ${e.message}"
    }
    return typeId
}

// Step 2: POST to /app/edit/update to enable OAuth on this app's code.
// Requires the current internal version number from /app/ajax/code as a
// concurrency guard — the POST is silently rejected without it.
private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) {
        log.warn "autoEnableOAuth: could not determine app type ID — OAuth must be enabled manually in Apps Code"
        return false
    }

    String internalVer = null
    try {
        httpGet([uri: RM_BASE_URL, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (Exception e) {
        log.error "autoEnableOAuth: could not fetch app code version — ${e.message}"
        return false
    }
    if (!internalVer) {
        log.error "autoEnableOAuth: app code version was null — cannot proceed"
        return false
    }

    boolean success = false
    try {
        httpPost([
            uri                : RM_BASE_URL,
            path               : "/app/edit/update",
            requestContentType : "application/x-www-form-urlencoded",
            body               : [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout            : 20
        ]) { resp ->
            success = true
        }
        if (success) log.info "autoEnableOAuth: OAuth successfully enabled on app code (typeId: ${typeId})"
    } catch (Exception e) {
        log.error "autoEnableOAuth: POST to /app/edit/update failed — ${e.message}"
    }
    return success
}

// Step 3: called from installed(), updated(), and mainPage().
// Returns true when a token is available. On first call, tries createAccessToken();
// if that throws (OAuth not yet enabled), calls autoEnableOAuth() then retries.
boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        if (state.accessToken) {
            log.info "Private Boolean Manager: OAuth token created"
            return true
        }
    } catch (Exception e) {
        log.debug "checkOAuth: OAuth not yet enabled — attempting auto-enable via hub API..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                if (state.accessToken) {
                    log.info "Private Boolean Manager: OAuth auto-enabled and token created successfully"
                    return true
                }
            } catch (Exception e2) {
                log.error "checkOAuth: OAuth was enabled but token creation still failed — ${e2.message}"
            }
        }
        // Auto-enable failed — red warning shown in mainPage
    }
    return false
}

// ── Private Boolean OAuth endpoint ───────────────────────────────────────────

// Returns the render result so Hubitat's OAuth dispatcher receives a response body.
// Declared as def (not void) for this reason — a void helper causes an empty HTTP response.
def renderJson(Map m) {
    return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(m))
}

def handleSetPBEndpoint() {
    String ruleId  = params?.id
    String pbValue = params?.value

    if (!ruleId || pbValue == null) {
        log.warn "setPB endpoint called with missing params: id=${ruleId} value=${pbValue}"
        return renderJson([status: "error", message: "Missing id or value parameter"])
    }

    if (!(ruleId ==~ /\d+/)) {
        log.warn "setPB endpoint: invalid rule id '${ruleId}'"
        return renderJson([status: "error", message: "Invalid rule id"])
    }

    if (!(pbValue in ["true", "false"])) {
        log.warn "setPB endpoint: invalid value '${pbValue}'"
        return renderJson([status: "error", message: "Invalid value parameter — must be 'true' or 'false'"])
    }

    String action = (pbValue == "true") ? "setRuleBooleanTrue" : "setRuleBooleanFalse"

    try {
        RMUtils.sendAction([ruleId as Long], action, app.label, RM_VERSION)

        if (pbValue == "true") {
            updateCachedPbStatesAfterBulkSet([ruleId], [])
        } else {
            updateCachedPbStatesAfterBulkSet([], [ruleId])
        }

        if (debugEnable) log.debug "setPB: rule ${ruleId} → ${action}"
        return renderJson([status: "success"])
    } catch (Exception e) {
        log.warn "setPB failed for rule ${ruleId} (${action}): ${e.message}"
        return renderJson([status: "error", message: e.message ?: "Unknown error"])
    }
}

// ── Bulk Private Boolean OAuth endpoint ──────────────────────────────────────
// GET /apps/api/{id}/bulkPB?trueIds=53,17&falseIds=125&access_token={token}
// Comma-separated rule IDs in trueIds and falseIds; TRUE wins when a rule appears in both.
// Returns JSON {status, trueCount, falseCount}.
def handleBulkPBEndpoint() {
    if (!state.accessToken) {
        return renderJson([status: "error", message: "OAuth not active"])
    }

    String trueParam  = params?.trueIds  ?: ""
    String falseParam = params?.falseIds ?: ""

    List<String> trueIds  = trueParam  ? trueParam.tokenize(",").collect  { it.trim() }.findAll { it ==~ /\d+/ } : []
    List<String> falseIds = falseParam ? falseParam.tokenize(",").collect { it.trim() }.findAll { it ==~ /\d+/ } : []

    // TRUE wins: remove IDs from falseIds that also appear in trueIds
    Set<String> trueSet = trueIds as Set<String>
    falseIds = falseIds.findAll { !(it in trueSet) }

    trueIds.each  { id -> RMUtils.sendAction([id as Long], "setRuleBooleanTrue",  app.label, RM_VERSION) }
    falseIds.each { id -> RMUtils.sendAction([id as Long], "setRuleBooleanFalse", app.label, RM_VERSION) }

    updateCachedPbStatesAfterBulkSet(trueIds, falseIds)

    log.info "PBM bulkPB: TRUE=${trueIds.size()} FALSE=${falseIds.size()}"
    return renderJson([status: "success", trueCount: trueIds.size(), falseCount: falseIds.size()])
}

// ============================================================
// UI
// ============================================================

def mainPage() {
    // Attempt to create the OAuth token on every page open — covers the case where the
    // user has just enabled OAuth in Apps Code and re-opened the app.
    checkOAuth()

    int pollInterval = (currentScanId || configureScanId) ? 5 : 0
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true, refreshInterval: pollInterval) {

        section("") {
            paragraph "<b style='font-size:1.1em;'>${app.name}</b>"
        }

        section("NOTE: Scanning may take a while, be patient!") {
            input name: "btnScan",     type: "button", title: "Scan All Rules for Current PB State", width: 7
            input name: "btnScanFull", type: "button", title: "Scan for Actual PB Use",              width: 5
            if (state.lastScan) {
                String scanTimeHtml = ""

                if (state.pbUseScanned == true && state.phase2ScanDuration) {
                    scanTimeHtml = "Phase 1 scan time: ${state.phase1ScanDuration ?: '00:00'}; " +
                                   "Phase 2 scan time: ${state.phase2ScanDuration ?: '00:00'}; " +
                                   "total scan time: ${state.totalScanDuration    ?: state.scanDuration ?: '00:00'}"
                } else {
                    scanTimeHtml = "Phase 1 scan time: ${state.phase1ScanDuration ?: state.scanDuration ?: '00:00'}"
                }

                paragraph "<b>Last scan:</b> ${state.lastScan} (${scanTimeHtml})"
            } else {
                paragraph "No scan has been run yet."
            }
            if (state.scanStatus) {
                paragraph state.scanStatus
            }
            if (state.lastError) {
                paragraph "<span style='color:red'><b>Last error:</b> ${htmlEncode(state.lastError.toString())}</span>"
            }
        }

        // ── Private Boolean toggle status ─────────────────────────────────────
        // Shown only when OAuth auto-enable failed — the normal (success) case
        // produces no UI noise since self-enabling OAuth is now the default.
        section("") {
            if (!state.accessToken) {
                paragraph "<span style='color:red;font-weight:bold;'>✗ PB toggle NOT active</span> — automatic OAuth setup failed.<br>" +
                          "Please enable it manually as a fallback:<br>" +
                          "1. Go to <b>Apps Code</b>, open this app, click the <b>three-dot menu</b>, select <b>OAuth</b>, and press <b>Enable OAuth in Smartapp</b>.<br>" +
                          "2. Return here and re-open the app — the token will be created automatically."
            }
        }

        section("Rule Machine and Button Controller Rule State", hideable: true, hidden: false) {
            if (state.scannedCount != null) {
                String summaryHtml = "<div id='rm-summary' style='margin:0;padding:0;line-height:1.5;font-size:1em;'>" +
                                    "<b>Rules scanned:</b> ${state.scannedCount ?: 0}; " +
                                    "<b>PB TRUE:</b> <span id='rm-summary-pb-true'>${state.privateBoolOnCount ?: 0}</span>; " +
                                    "<b>PB FALSE:</b> <span id='rm-summary-pb-false'>${state.privateBoolOffCount ?: 0}</span>; " +
                                    "<b>PB Use Detected:</b> " + (
                                        (state.pbUseScanned == true)
                                            ? "${state.pbUsedDetectedCount ?: 0}"
                                            : ((state.stalePbUsedDetectedCount ?: 0) > 0)
                                                ? "${state.stalePbUsedDetectedCount} (stale)"
                                                : "0"
                                    )

                if (state.pbUseScanned == true && (state.pbUseUnknownCount ?: 0) > 0) {
                    summaryHtml += "; <b>PB Use Unknown:</b> ${state.pbUseUnknownCount}"
                }

                summaryHtml += "<br><br></div>"

                paragraph summaryHtml
            }

            paragraph(state.reportHtml ?: "Click <b>Scan All Rules for Current PB State</b> to begin.")
        }

        section("Controls", hideable: true, hidden: true) {
            // ── App instance rename ───────────────────────────────────────
            // input "label" does not update app.label reliably in a dynamicPage;
            // updated() reads settings.vAppLabel and calls app.updateLabel() explicitly.
            input "vAppLabel", "text", title: "<b>App instance name</b>", defaultValue: app.label

            // ── Report links — only available after a scan with a token ───
            if (state.accessToken) {
                String base = "/apps/api/${app.id}/report?access_token=${state.accessToken}"
                if (state.scanRowsJson) {
                    String rmCsvUrl = "/apps/api/${app.id}/RM-BC_Rules.csv?access_token=${state.accessToken}"
                    paragraph "<br><b>RM/BC Rule State Table</b> &nbsp;" +
                        "<a href='${base}' target='_blank'>" +
                        "&#128196; Open Printable Report</a>" +
                        " &nbsp;|&nbsp; " +
                        "<a href='${rmCsvUrl}'>&#11015; Download CSV</a>"
                } else {
                    paragraph "<small>Run <b>Scan All Rules for Current PB State</b> to enable RM/BC reports.</small>"
                }
            } else {
                paragraph "<small>OAuth setup required before reports are available.</small>"
            }

            // ── Debug logging (last) ──────────────────────────────────────
            // ── Scheduled PB apply ───────────────────────────────────────
            paragraph "<br><b>Scheduled PB Apply</b>"
            paragraph "<small>Applies the current Set TRUE / Set FALSE checkbox selections on the chosen schedule(s). Both options can be active simultaneously. Clear a field and press Done to disable that schedule.</small>"
            input "vScheduleTime",        "time", title: "Apply PB changes daily at:",  required: false
            paragraph "<br>"
            input "vScheduleIntervalMins", "enum", title: "Apply PB changes every:",     required: false,
                options: ["1": "Every minute", "2": "Every 2 minutes", "5": "Every 5 minutes",
                          "10": "Every 10 minutes", "15": "Every 15 minutes",
                          "20": "Every 20 minutes", "30": "Every 30 minutes", "60": "Every hour"]
            paragraph "<br>"
            input "vRebootApplyEnabled",    "bool",   title: "Apply PB changes after hub reboot", defaultValue: false
            input "vRebootApplyDelayMins",  "number", title: "Delay after reboot (minutes, 0 = immediately)",
                required: false, defaultValue: 0, range: "0..60"
            if (state.scheduledApplyLastRun) {
                String schedResult = state.scheduledApplyResult ?: ""
                paragraph "<small><i>Last scheduled run: ${state.scheduledApplyLastRun}${schedResult ? " — ${schedResult}" : ""}</i></small>"
            } else if (settings.vScheduleTime || settings.vScheduleIntervalMins || settings.vRebootApplyEnabled) {
                paragraph "<small><i>Scheduled — has not run yet.</i></small>"
            }

            // ── Debug logging (last) ────────────────────────────────────
            paragraph "<br><br>"
            input "debugEnable", "bool",
                title: "<b>Enable debug logging</b>",
                defaultValue:   false,
                submitOnChange: true
        }

        section("Notes", hideable: true, hidden: true) {
            paragraph """
                <b>Overview</b><br>
                This app has two functions: 

                (1) it scans Rule Machine (<b>RM</b>) and Button Controller (<b>BC</b>) rules and
                displays their Private Boolean state and Last Run time in a table; and 

                (2) it lets you bulk-set Private Boolean values across multiple rules at once.
                <br>
                <b>Scanning</b><br>
                Two scan modes are available:
                
                <b>Scan All Rules for Current PB State</b> fetches only the runtime status of 
                each rule (Private Boolean value and Last Run time) and is reasonably fast 
                (but may take a <b>minute or more for hundreds of rules</b>). 
                
                <b>Scan for Actual PB Use</b> additionally fetches each rule's configuration
                JSON to detect whether the rule references its own PB in a condition, action, or
                trigger. This scan adds a queued configure/json pass and may take <b>5-10 minutes
                or more for hundreds of rules</b>, especially for very large rules. 
                
                Before a PB usage scan runs, every cell shows a dash ("—").
                After a PB usage scan, cells show ✓ (use detected) or "—" (not detected).

                Clicking <b>Done</b> and reopening re-renders the table from cached data,
                so no rescan is needed for display-only changes (but the data is stale).
                <br>
                <b>Table</b><br>
                Shows Rule ID, Rule name (linked to its config page), App Type (RM or BC),
                Current PB State, PB Use Detected, and Last Run. Column headers are clickable to sort.

                The <b>Hide rows without PB use</b> button hides all rows where PB Use Detected is "—" 
                (including stale scans where the stale red ✓ is absent). Clicking the 
                <b>Show all rows</b> button restores those rows.
                
                The <b>All TRUE</b>, <b>All FALSE</b>, and <b>All Clear</b> buttons skip hidden rows. 
                
                The states of the <b>Hide columns</b> buttons and wildcard name filter above the 
                table persist without clicking <b>Done</b>.
                <br>
                <b>Setting Private Booleans (checkbox columns)</b><br>
                Each table row has a <b>Set TRUE</b> and <b>Set FALSE</b> checkbox.
                Checking one automatically clears the other for that row (mutual exclusion).
                Leaving both unchecked means no change for that rule.
                Use the <b>All TRUE</b>, <b>All FALSE</b>, or <b>All Clear</b> buttons to bulk-select 
                and set the state of visible rows (filtered rows are skipped). 
                
                Click the <b>Apply selected PB changes</b> button to send all pending changes 
                to the hub. Changes are immediately reflected in the table, but are not verified 
                by re-reading the affected rules. If a rule's PB value appears unchanged after 
                a rescan, the hub may have silently rejected the action (e.g., wrong RM version, 
                rule deleted since last scan).
                <br>
                <b>Current PB State column (in-table toggle)</b><br>
                Click any <b>Current PB State</b> cell to toggle a rule's value in-place.
                TRUE is shown in bold blue; FALSE in grey. A cell showing "—" means the
                state could not be read and is not clickable.

                As with the bulk apply, the toggle updates the table immediately, and does not 
                re-read the rule to confirm the change took effect.
                <br>
                <b>PB Use Detected column</b><br>
                Shows whether the rule references its own Private Boolean in a condition, action,
                or trigger. Each cell shows "—" before any usage scan. 
                
                After a <b>Scan for Actual PB Use</b> scan [this is a combined Phase 1 and 2 scan], 
                cells show a green ✓ (use detected) or "—" (not detected).

                After a Phase 2 scan, running a <b>Scan All Rules for Current PB State</b> scan
                [i.e, a Phase 1 scan only] causes prior PB Use detections to be shown as a red ✓ 
                (meaning stale). A subsequent <b>Scan for Actual PB Use</b> scan [i.e., 
                a Phase 2 scan] refreshes "PB Used" cells to a green ✓.

                This column is populated by reading each rule's internal configuration JSON
                (<code>/installedapp/configure/json/{id}</code>) and searching for two 
                confirmed RM 5.0 patterns in the rule settings JSON:
                (1) <code>"getSetPrivateBoolean"</code> (the <code>actSubType</code> value when a rule
                action sets the PB TRUE or FALSE); and 
                (2) <code>:"Private Boolean"</code> (the value of <code>rCapab_N</code> or
                <code>tCapab_N</code> when the PB is referenced in a condition or trigger).
                <br>
                <b>Last Run column</b><br>
                The date and time of the most recent trigger event for each rule (yyyy-MM-dd HH:mm).
                A blank cell means the rule has never been triggered since last installed.
                <br>
                <b>Controls section</b><br>
                App instance rename, printable HTML report, CSV export, debug logging toggle,
                and scheduled PB apply (daily, interval, and/or post-reboot).
                <br>
                <b>Scheduled PB Apply</b><br>
                Three independent schedule options are available in the Controls section:
                <b>daily at a specific time</b>, <b>every N minutes</b> (1, 2, 5, 10, 15, 20, 30,
                or 60), and <b>after hub reboot</b> (with an optional delay of 0–60 minutes).
                Any combination can be active at the same time. Each applies whichever
                Set TRUE / Set FALSE checkboxes are currently saved — the same state used by
                <b>Apply selected PB changes</b>. The last run timestamp and TRUE/FALSE counts
                are shown after the first run. Clear or disable a schedule and click <b>Done</b>
                to remove it. Scheduled changes are fire-and-forget (not verified by
                re-reading the affected rules).
                <br>
                <b>Debug logging</b><br>
                When enabled, logs per-rule Phase 1 scan results (rule name, ID, app type,
                Private Boolean value), per-rule Phase 2 PB-use detection results, Phase 2
                heartbeat resets, single-rule PB toggle confirmations, install/updated
                lifecycle events, rule-discovery count from <code>/hub2/appsList</code>,
                schedule and subscription setup confirmations on each Done press, and
                re-render-from-cache confirmations. Auto-disables after 30 minutes.
                <br>
                <b>WARNING</b><br>
                This app uses Hubitat local/internal JSON endpoints that are not a formal public
                API and could change in a future platform update.
                <br>
            """
        }
    }
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnScan":
            scanRules(false)
            break
        case "btnScanFull":
            scanRules(true)
            break
        default:
            log.warn "Unknown button: ${btn}"
            break
    }
}


// ============================================================
// Scanning — async sequential chain
// ============================================================

void scanRules(boolean doUsage = false) {
    state.scanDoUsage         = doUsage // read by finalizeScan() and Phase 2 launch to determine scan mode

    // For Phase 1-only scans: snapshot previously detected PB use (pbUsed==true) from the
    // last Phase 2 scan so finalizeScan() can overlay them as stale red ✓ marks.
    // For Phase 2 scans: clear the snapshot so fresh results replace everything.
    if (!doUsage && state.scanRowsJson) {
        try {
            List<Map> prev = new groovy.json.JsonSlurper().parseText(state.scanRowsJson) as List<Map>
            Map stale = [:]
            prev.each { Map r ->
                if (r.id && r.pbUsed == true) stale[r.id.toString()] = true
            }
            state.stalePbUsed = stale ? groovy.json.JsonOutput.toJson(stale) : null
        } catch (Exception e) {
            state.stalePbUsed = null
            log.warn "scanRules: could not snapshot stale PB data — ${e.message}"
        }
    } else {
        state.stalePbUsed = null
    }

    state.pbUseScanned        = false   // will be set true in finalizeScan() if doUsage
    state.lastError           = null
    state.pbUsedDetectedCount = null
    state.pbUseUnknownCount   = null

    state.scanStartedMs       = null
    state.phase1EndedMs       = null
    state.phase1ScanDuration  = null
    state.phase2ScanDuration  = null
    state.totalScanDuration   = null
    state.scanDuration        = null   // legacy fallback

    state.scanStatus          = "<i>Scan in progress…</i>"
    state.reportHtml          = null
    state.scanRowsJson        = null   // clear cache so updated() won't re-render stale data mid-scan

    // Cancel any prior scheduled timeout before setting a new one so a stale timer
    // from a previous scan can never fire against the current one.
    unschedule("finalizeScanTimeout")
    runIn(SCAN_TIMEOUT_SECS, "finalizeScanTimeout")

    List<Map> ruleApps = getRuleMachineRuleApps()

    if (ruleApps.isEmpty()) {
        unschedule("finalizeScanTimeout")
        state.scannedCount             = 0
        state.privateBoolOnCount       = 0
        state.privateBoolOffCount      = 0
        state.pbUsedDetectedCount      = 0
        state.stalePbUsedDetectedCount = 0
        state.pbUseUnknownCount        = 0
        state.scanStartedMs            = null
        state.phase1EndedMs            = null
        state.lastScan                 = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
     
        state.phase1ScanDuration       = "00:00"
        state.phase2ScanDuration       = null
        state.totalScanDuration        = "00:00"
        state.scanDuration             = "00:00"   // legacy fallback
     
        state.reportHtml               = "<p>No Rule Machine or Button Controller rules found.</p>"
        return
    }

    Long   nowMs         = now()
    String scanId        = nowMs.toString()
    state.scanStartedMs  = nowMs
    String scanStartTime = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    List<Map> queue = ruleApps.collect { Map r ->
        [id       : r.id                 as String,
         name     : r.name               as String,
         appType  : (r.appType ?: "RM")  as String,
         disabled : r.disabled           as Boolean,
         paused   : r.paused             as Boolean]
    }

    state.scanStatus = "<i>Scan started: ${scanStartTime} — scanning ${queue.size()} rules…</i>"

    // Assign all transient scan state to @Field statics — zero DB writes during the scan
    scanRuleQueue      = queue
    scanPartialResults = [:]
    currentScanId      = scanId
    scanStartMs        = nowMs

    log.info "Scan started — ${queue.size()} rules (scanId: ${scanId})"

    Map first = queue[0]
    asynchttpGet("handleStatusResponse",
        [uri: RM_BASE_URL, path: "/installedapp/statusJson/${first.id}", timeout: 60],
        [scanId     : scanId,
         ruleId     : first.id,
         ruleName   : first.name,
         appType    : first.appType,
         disabled   : first.disabled,
         paused     : first.paused,
         nextIdx    : 1,
         totalRules : queue.size()]
    )
}

void handleStatusResponse(resp, data) {
    String scanId = data.scanId as String
    if (currentScanId != scanId) return   // stale callback from a cancelled scan

    String ruleId = data.ruleId as String

    try {
        Map status = [:]
        boolean statusOk = false   // true only when statusJson returned HTTP 200
        try {
            int httpStatus = resp.getStatus() as int
            if (httpStatus == 200) {
                Object raw = resp.getData()
                if (raw instanceof Map) {
                    status = raw as Map
                } else if (raw != null) {
                    status = new groovy.json.JsonSlurper().parseText(raw.toString()) as Map ?: [:]
                }
                statusOk = true
            } else {
                log.warn "HTTP ${httpStatus} for rule ${ruleId} (${data.ruleName})"
            }
        } catch (Exception e) {
            log.warn "Error parsing statusJson for rule ${ruleId}: ${e.message}"
        }

        if (scanPartialResults == null) scanPartialResults = [:]

        // knownRead=statusOk: if HTTP 200 but "private" absent, returns false (RM default).
        // If HTTP error/parse failure, statusOk=false and null is returned (renders as —).
        Boolean privateBool = extractPrivateBool(status, statusOk)

        if (debugEnable) {
            log.debug "Scanned: ${data.ruleName} (${ruleId}, ${data.appType}) PrivateBool=${privateBool}"
        }

        scanPartialResults[ruleId] = [
            id          : ruleId,
            name        : data.ruleName,
            appType     : data.appType,
            disabled    : data.disabled,
            paused      : data.paused,
            lastRun     : extractLastRun(status),
            privateBool : privateBool       // null = HTTP error; false = unset (RM default); true = set
        ]

    } catch (Exception e) {
        log.warn "handleStatusResponse error for rule ${ruleId} (${data.ruleName}): ${e.message}"
        if (scanPartialResults == null) scanPartialResults = [:]
        scanPartialResults[ruleId] = [
            id          : ruleId,
            name        : data.ruleName as String,
            appType     : (data.appType ?: "RM") as String,
            disabled    : data.disabled as Boolean,
            paused      : data.paused as Boolean,
            lastRun     : "",
            privateBool : null,
            pbUsed      : null
        ]
    } finally {
        if (currentScanId != scanId) return

        int nextIdx    = (data.nextIdx    ?: 0) as int
        int totalRules = (data.totalRules ?: 0) as int

        if (debugEnable) log.debug "Completed statusJson ${nextIdx}/${totalRules}: ${data.ruleName} (${ruleId})"

        // Both modes advance directly — Phase 2 (configure/json) starts from
        // finalizeScan() so a dropped large-rule response cannot stall statusJson chain.
        if (nextIdx < totalRules) {
            Map nextRule = scanRuleQueue[nextIdx]
            asynchttpGet("handleStatusResponse",
                [uri: RM_BASE_URL, path: "/installedapp/statusJson/${nextRule.id}", timeout: 60],
                [scanId     : currentScanId,
                 ruleId     : nextRule.id                 as String,
                 ruleName   : nextRule.name               as String,
                 appType    : (nextRule.appType ?: "RM")  as String,
                 disabled   : nextRule.disabled           as Boolean,
                 paused     : nextRule.paused             as Boolean,
                 nextIdx    : nextIdx + 1,
                 totalRules : totalRules]
            )
        } else {
            finalizeScan()
        }
    }
}

void resetConfigureHeartbeat(String reason = "") {
    if (configureScanId == null) return

    configureLastProgressMs = now() as Long

    unschedule("configureHeartbeat")
    runIn(CONFIGURE_HEARTBEAT_SECS, "configureHeartbeat")

    if (debugEnable && reason) {
        log.debug "PBM: Phase 2 heartbeat reset (${reason})"
    }
}

void configureHeartbeat() {
    if (configureScanId == null) return

    Integer done   = (configureResults?.size() ?: 0) as Integer
    Integer total  = (configureTotalRules ?: 0) as Integer
    Integer active = (configureInFlight ?: 0) as Integer
    Integer next   = (configureNextIdx ?: 0) as Integer

    log.warn "PBM: Phase 2 heartbeat timeout — no configure/json progress for ${CONFIGURE_HEARTBEAT_SECS}s; finalizing partial results. Done=${done}/${total}, active=${active}, nextIdx=${next}"

    finalizeUsageScan()
}

// Phase 2 — configure/json queued worker pool.
// Hubitat appears to impose practical limits on how many simultaneous
// asynchttpGet calls an app can launch.  This queue keeps only a small number
// of configure/json requests active at a time, marks oversized/dropped rules as
// unknown (pbUsed = null; rendered as "—"), and continues with the remaining rules.
void configurePump() {
    if (configureScanId == null) return

    unschedule("configurePump")

    boolean madeProgress = false

    Long nowMs = now() as Long
    if (configureResults  == null) configureResults  = [:]
    if (configureInflight == null) configureInflight = [:]

    // Reclaim requests whose callback never arrived. This is what lets a
    // very large rule show — (unknown) without stalling the rest of the PB-use scan.
    List<String> expiredIds = []
    configureInflight.each { String rid, Object infoObj ->
        Map info = (infoObj instanceof Map) ? (Map) infoObj : [:]
        Long startedMs = (info.startedMs ?: 0L) as Long
        if (startedMs && (nowMs - startedMs) > (CONFIGURE_REQUEST_TIMEOUT_SECS * 1000L)) {
            expiredIds << rid
        }
    }

    expiredIds.each { String rid ->
        Map info = (configureInflight[rid] instanceof Map) ? (Map) configureInflight[rid] : [:]
        log.warn "PBM: configure/json timeout for rule ${rid} (${info.name ?: ''}) — PB Use marked unknown"
        configureResults[rid] = null
        configureInflight.remove(rid)
        configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)
        madeProgress = true
    }

    // Launch up to CONFIGURE_MAX_IN_FLIGHT requests. Every launch is protected
    // so a rejected request marks only that rule unknown and cannot abort the queue.
    while ((configureInFlight ?: 0) < CONFIGURE_MAX_IN_FLIGHT &&
           (configureNextIdx  ?: 0) < (configureQueue?.size() ?: 0)) {

        Map rule = configureQueue[configureNextIdx] as Map
        configureNextIdx = (configureNextIdx ?: 0) + 1

        String rid   = rule.id as String
        String nm    = rule.name as String
        String cfgId = configureScanId

        configureInflight[rid] = [startedMs: nowMs, name: nm]
        configureInFlight = (configureInFlight ?: 0) + 1
        madeProgress = true

        try {
            asynchttpGet(
                "handleConfigureResponse",
                [
                    uri     : RM_BASE_URL,
                    path    : "/installedapp/configure/json/${rid}",
                    timeout : CONFIGURE_REQUEST_TIMEOUT_SECS
                ],
                [
                    cfgScanId : cfgId,
                    ruleId    : rid,
                    ruleName  : nm
                ]
            )
        } catch (Exception e) {
            log.warn "PBM: could not start configure/json for rule ${rid} (${nm}) — PB Use marked unknown: ${e.message}"
            configureResults[rid] = null
            configureInflight.remove(rid)
            configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)
            madeProgress = true
        }
    }

    Integer done   = (configureResults?.size() ?: 0) as Integer
    Integer total  = (configureTotalRules ?: 0) as Integer
    Integer active = (configureInFlight ?: 0) as Integer

    if (total > 0) {
        state.scanStatus = "<i>Phase 2: checking configure/json for PB use… ${done}/${total} complete, ${active} active</i>"
    }

    if (madeProgress) {
        resetConfigureHeartbeat("pump progress")
    }

    if (total <= 0 || done >= total) {
        finalizeUsageScan()
        return
    }

    // Watchdog tick: continues the queue and skips requests whose callback never arrives.
    runIn(5, "configurePump")
}

void handleConfigureResponse(resp, data) {
    String cfgScanId     = data.cfgScanId as String
    if (configureScanId != cfgScanId) return

    String ruleId   = data.ruleId as String
    String ruleName = data.ruleName ?: ""

    // If the watchdog already marked this rule unknown, ignore a late callback.
    if (configureResults?.containsKey(ruleId) && !configureInflight?.containsKey(ruleId)) {
        return
    }

    Boolean pbUsed = null

    try {
        int httpStatus = resp.getStatus() as int
        if (httpStatus == 200) {
            Object raw = resp.getData()
            pbUsed = detectPbUsageFromRaw(raw)
            if (debugEnable) log.debug "configure/json ${ruleId}: pbUsed=${pbUsed}"
        } else {
            log.warn "configure/json HTTP ${httpStatus} for rule ${ruleId} (${ruleName})"
        }
    } catch (Exception e) {
        log.warn "handleConfigureResponse ${ruleId} (${ruleName}): ${e.message}"
        pbUsed = null
    }

    if (configureScanId != cfgScanId) return

    if (configureResults == null)  configureResults  = [:]
    if (configureInflight == null) configureInflight = [:]

    configureResults[ruleId] = pbUsed
    configureInflight.remove(ruleId)
    configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)

    resetConfigureHeartbeat("callback")

    configurePump()
}

// Returns true if the rule's configure/json settings indicate that the rule
// references its Private Boolean in a condition, trigger, or action.
//
// We intentionally scan the raw JSON text instead of parsing the whole response
// into a Map. Large RM rules are exactly where configure/json can be expensive,
// and these two confirmed RM 5.0 patterns are simple string searches:
//   "getSetPrivateBoolean" — action that sets PB TRUE/FALSE
//   :"Private Boolean"     — PB used as condition/predicate/trigger capability
//
// Returns false if the JSON was read but no reference found; null if unreadable.
private Boolean detectPbUsageFromRaw(Object raw) {
    try {
        if (raw == null) return false

        String jsonText
        if (raw instanceof CharSequence) {
            jsonText = raw.toString()
        } else {
            jsonText = groovy.json.JsonOutput.toJson(raw)
        }

        if (jsonText.contains('"getSetPrivateBoolean"')) return true
        if (jsonText.contains(':"Private Boolean"')) return true
        return false
    } catch (Exception e) {
        log.warn "detectPbUsageFromRaw: ${e.message}"
        return null
    }
}

// Merges Phase 2 results into cached rows and rebuilds reportHtml.
// Called directly when the queue completes, or by runIn() if Phase 2 times out.
void finalizeUsageScan() {
    unschedule("finalizeUsageScan")
    unschedule("configurePump")
    unschedule("configureHeartbeat")

    state.scanStatus = null   // always clear, even if configureScanId already null
    if (configureScanId == null) return
    log.info "PBM: Phase 2 finalizing"
    try {
        List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map>
        Map cfgRes = configureResults ?: [:]
        rows = rows.collect { Map r ->
            String id = r.id?.toString()
            r.pbUsed      = cfgRes.containsKey(id) ? cfgRes[id] : null
            r.pbUsedStale = null   // Phase 2 always produces fresh data; clear stale flag
            return r
        }
        Integer detected               = rows.count { it.pbUsed == true } as Integer
        Integer unknown                = rows.count { it.pbUsed == null } as Integer
        state.pbUsedDetectedCount      = detected
        state.stalePbUsedDetectedCount = null   // Phase 2 data is fresh; no stale count
        state.pbUseUnknownCount        = unknown

        Long phase2EndMs               = now() as Long
        Long startedMs                 = (state.scanStartedMs ?: scanStartMs ?: phase2EndMs) as Long
        Long phase1EndMs               = (state.phase1EndedMs ?: startedMs) as Long

        state.phase1ScanDuration       = state.phase1ScanDuration ?: formatScanDuration(phase1EndMs - startedMs)
        state.phase2ScanDuration       = formatScanDuration(phase2EndMs - phase1EndMs)
        state.totalScanDuration        = formatScanDuration(phase2EndMs - startedMs)
        state.scanDuration             = state.totalScanDuration   // legacy fallback
        state.lastScan                 = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

        state.scanRowsJson             = groovy.json.JsonOutput.toJson(rows)
        state.reportHtml               = buildReportHtml(rows)

        log.info "PBM: Phase 2 complete in ${state.phase2ScanDuration}; total scan time ${state.totalScanDuration} — ${detected} of ${rows.size()} rules with detectable PB usage; ${unknown} unknown/skipped"

    } catch (Exception e) {
        log.warn "finalizeUsageScan: ${e.message}"
    } finally {
        configureScanId         = null
        configureQueue          = null
        configureResults        = null
        configureNextIdx        = 0
        configureInFlight       = 0
        configureTotalRules     = 0
        configureInflight       = [:]
        configureLastProgressMs = 0L
        state.scanStartedMs     = null
        state.phase1EndedMs     = null
    }
}

void finalizeScan() {
    unschedule("finalizeScanTimeout")

    List<Map> asyncRules     = scanRuleQueue      ?: []
    Map       partialResults = scanPartialResults ?: [:]

    List<Map> rmRows = asyncRules.collect { Map rule ->
        Map row = partialResults[rule.id as String] as Map
        if (row) return row
        log.warn "No statusJson response for ${rule.id} (${rule.name}) — Private Boolean state is unknown"
        return [id: rule.id as String, name: rule.name as String,
                appType: (rule.appType ?: "RM") as String,
                disabled: rule.disabled as Boolean, paused: rule.paused as Boolean,
                lastRun: "", privateBool: null, pbUsed: null]
    }

    Integer privateBoolOnCount  = rmRows.count { it.privateBool == true }  as Integer
    Integer privateBoolOffCount = rmRows.count { it.privateBool == false } as Integer
    Long phase1EndMs            = now() as Long
    Long startedMs              = (state.scanStartedMs ?: scanStartMs ?: phase1EndMs) as Long
    state.scannedCount          = rmRows.size()
    state.privateBoolOnCount    = privateBoolOnCount
    state.privateBoolOffCount   = privateBoolOffCount
    state.lastScan              = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    state.phase1EndedMs         = phase1EndMs
    state.phase1ScanDuration    = formatScanDuration(phase1EndMs - startedMs)
    state.scanDuration          = state.phase1ScanDuration   // legacy fallback

    // For Phase 1-only scans, overlay stale detected-PB data (red ✓) from the prior
    // Phase 2 scan.  This is done before writing scanRowsJson so cached rows carry
    // the stale flag and a re-render from Done will also show the red ✓ marks.
    if (state.scanDoUsage != true && state.stalePbUsed) {
        try {
            Map stale = new groovy.json.JsonSlurper().parseText(state.stalePbUsed) as Map
            rmRows.each { Map r ->
                if (r.pbUsed == null && stale[r.id?.toString()] == true) {
                    r.pbUsed      = true
                    r.pbUsedStale = true
                }
            }
        } catch (Exception e) {
            log.warn "finalizeScan: stale PB overlay failed — ${e.message}"
        }
    }
    state.stalePbUsedDetectedCount = rmRows.count { it.pbUsedStale == true } as Integer

    // Cache row data so updated() can re-render on settings change without rescan.
    try {
        state.scanRowsJson = groovy.json.JsonOutput.toJson(rmRows)
    } catch (Exception e) {
        log.warn "finalizeScan: could not cache scan rows — ${e.message}"
        state.scanRowsJson = null
    }


    // For usage scans, set pbUseScanned BEFORE building Phase 1 reportHtml so the
    // PB Use Detected column (showing — for all rows) appears immediately.
    if (state.scanDoUsage == true) state.pbUseScanned = true

    state.reportHtml = buildReportHtml(rmRows)   // Phase 1 complete: PB states correct; pbUsed shows stale red ✓ where available, — otherwise
    // Keep scanStatus non-null during Phase 2 so the page keeps auto-refreshing.
    // It is cleared by finalizeUsageScan() when Phase 2 completes (or times out).
    state.scanStatus = (state.scanDoUsage == true) ? "<i>Phase 2: checking configure/json for PB use…</i>" : null

    // Phase 2: queued configure/json scan.  Only a few requests run at a time,
    // so a very large rule or Hubitat's async-call limit should mark that rule with "—" (unknown)
    // instead of stopping PB-use detection for the remaining rules.
    if (state.scanDoUsage == true && !asyncRules.isEmpty()) {
        configureScanId     = (currentScanId ?: "scan") + "_cfg"
        configureQueue      = asyncRules
        configureResults    = [:]
        configureInflight   = [:]
        configureNextIdx    = 0
        configureInFlight   = 0
        configureTotalRules = asyncRules.size()

        runIn(CONFIGURE_TOTAL_TIMEOUT_SECS, "finalizeUsageScan")  // absolute Phase 2 cap
        resetConfigureHeartbeat("start")
        configurePump()

        log.info "PBM: Phase 2 started — ${asyncRules.size()} configure/json requests queued; max in flight: ${CONFIGURE_MAX_IN_FLIGHT}; heartbeat: ${CONFIGURE_HEARTBEAT_SECS}s"
    }

    // Release Phase 1 @Field memory.
    currentScanId      = null
    scanPartialResults = null
    scanRuleQueue      = null
    if (state.scanDoUsage != true) {
        state.phase2ScanDuration = null
        state.totalScanDuration  = state.phase1ScanDuration
        state.scanStartedMs      = null
        state.phase1EndedMs      = null
    }

    log.info "Phase 1 scan complete in ${state.phase1ScanDuration}: ${rmRows.size()} RM/BC rules (PB TRUE: ${privateBoolOnCount})"
}

void finalizeScanTimeout() {
    if (currentScanId != null) {
        int total = scanRuleQueue?.size() ?: 0
        log.warn "Scan timeout: finalizing with partial results (${total} rules in queue)"
        finalizeScan()
    }
}

// ============================================================
// Rule discovery
// ============================================================

List<Map> getRuleMachineRuleApps() {
    List<Map> rules = []
    Set<String> seenIds = [] as Set

    Map params = [
        uri         : RM_BASE_URL,
        path        : "/hub2/appsList",
        contentType : "application/json"
    ]

    try {
        httpGet(params) { resp ->
            resp.data?.apps?.each { parentApp ->
                def pd = parentApp?.data
                String parentType  = pd?.type?.toString()  ?: ""
                String parentName  = pd?.name?.toString()  ?: ""
                String parentLabel = pd?.label?.toString() ?: ""
                String appType     = getSupportedAutomationAppType(parentType, parentName, parentLabel)

                if (appType) {
                    parentApp?.children?.each { child ->
                        collectRmLeafRules(child, appType, rules, seenIds, 0)
                    }
                }
            }
        }
    } catch (Exception e) {
        state.lastError = "Unable to read /hub2/appsList. This may be temporary; try Scan again. Error: ${e.message}"
        log.warn state.lastError
    }

    if (debugEnable) log.debug "PBM: discovered ${rules.size()} RM/BC rules from /hub2/appsList"
    return rules.sort { it.name?.toLowerCase() ?: "" }
}

// Recursively collect leaf nodes from the RM/BC app tree.
// Preserves the BC type-detection logic needed to correctly label Button
// Controller rules that appear within an RM parent.
private void collectRmLeafRules(Object node, String parentAppType, List<Map> rules, Set<String> seenIds, int depth) {
    if (depth > 6) return
    List children = (node?.children ?: []) as List
    if (children.isEmpty()) {
        def d = node?.data
        if (d?.id && d?.name) {
            String id = d.id.toString()
            if (!seenIds.contains(id)) {
                String childType         = d?.type?.toString()    ?: ""
                String childAppName      = d?.appName?.toString() ?: ""
                String childDetectedType = getSupportedAutomationAppType(childType, childAppName)
                String finalAppType      = (parentAppType == "BC" || childDetectedType == "BC") ? "BC" : (childDetectedType ?: parentAppType)

                seenIds << id
                String ruleName = d.name.toString()
                rules << [
                    id       : id,
                    name     : ruleName,
                    appType  : finalAppType,
                    disabled : asBooleanLoose(d.disabled),
                    paused   : ruleName.contains("(Paused)")
                ]
            }
        }
    } else {
        children.each { child -> collectRmLeafRules(child, parentAppType, rules, seenIds, depth + 1) }
    }
}

String getSupportedAutomationAppType(String type, String name, String label = "") {
    String combined = [type, name, label].findAll { it }.join(" ").toLowerCase()

    if (!combined) return null

    /*
     * Basic Button Controller is intentionally excluded because this app targets
     * RM/BC child rules with Private Boolean state and RM-compatible PB actions.
     */
    if (combined.contains("basic button controller") || combined.contains("basicbuttoncontroller")) {
        return null
    }

    if (combined.contains("button controller") || combined.contains("buttoncontroller")) {
        return "BC"
    }

    /*
     * Keep Rule Machine matching specific to avoid false positives from other apps
     * whose names happen to contain the word "rule".
     */
    if (combined.contains("rule machine") || combined.contains("rulemachine")) {
        return "RM"
    }

    return null
}

// ── Preference persistence endpoint ─────────────────────────────────────────
// Called by toggle-bar buttons via fetch() to persist their state without a
// page reload. Values are stored in state.userPrefs and read via getPref().
def handleSetPrefEndpoint() {
    if (!state.accessToken) { render contentType: "application/json", data: '{"status":"error","message":"OAuth not active"}'; return }
    String key   = params?.key?.toString()
    String value = params?.value?.toString()
    if (!key) { render contentType: "application/json", data: '{"status":"error","message":"missing key"}'; return }
    Map prefs = (state.userPrefs ?: [:]) as Map
    prefs[key] = value
    state.userPrefs = prefs
    render contentType: "application/json", data: '{"status":"success"}'
}

// Read a toggle-bar preference. Priority: state.userPrefs (set by JS click) →
// settings.* (legacy Done-saved value) → defaultVal.
boolean getPref(String key, boolean defaultVal = false) {
    Map prefs = (state.userPrefs ?: [:]) as Map
    if (prefs.containsKey(key)) return prefs[key]?.toString() == "true"
    return defaultVal
}

// Read a preference value as a raw String (used for JSON-encoded prefs).
String getPrefString(String key, String defaultVal = "") {
    Map prefs = (state.userPrefs ?: [:]) as Map
    if (prefs.containsKey(key)) return prefs[key]?.toString() ?: defaultVal
    return defaultVal
}

// ============================================================
// Report endpoints — printable HTML and CSV export
// ============================================================
// GET /apps/api/{id}/report?access_token={token}
// Opens a self-contained printable HTML page using the cached scan rows in state.
// GET /apps/api/{id}/RM-BC_Rules.csv?access_token={token}
// Returns a CSV export using the cached scan rows in state.

def handleReportEndpoint() {
    if (!state.accessToken) {
        render contentType: "text/plain", data: "OAuth not active — re-open the app to retry."
        return
    }
    render contentType: "text/html; charset=UTF-8", data: buildRmPrintHtml()
}

// Dedicated CSV download endpoint — named path gives browsers the correct filename.
def handleRmCsvEndpoint() {
    if (!state.accessToken) { render contentType: "text/plain", data: "OAuth not active."; return }
    render contentType: "text/csv; charset=UTF-8", data: buildRmCsv()
}

// ── Shared print HTML shell ───────────────────────────────────────────────────
private String printHtmlShell(String title, String subtitle, String tableHtml) {
    return """<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8">
<title>${htmlEncode(title)}</title>
<style>
  body { font-family: Arial, sans-serif; font-size: 12px; margin: 16px; }
  h2   { font-size: 16px; margin-bottom: 2px; }
  p.sub { font-size: 11px; color: #555; margin: 0 0 12px; }
  table { border-collapse: collapse; width: 100%; }
  th, td { border: 1px solid #bbb; padding: 4px 8px; text-align: left; vertical-align: top; }
  th { background: #e8e8e8; font-weight: bold; }
  tr:nth-child(even) { background: #f7f7f7; }
  .c { text-align: center; }
  @media print {
    body { margin: 6mm; font-size: 11px; }
    a { text-decoration: none; color: inherit; }
    thead { display: table-header-group; }
    tr { page-break-inside: avoid; }
  }
</style>
</head><body>
<h2>${htmlEncode(title)}</h2>
<p class="sub">${htmlEncode(subtitle)}</p>
${tableHtml}
</body></html>"""
}

// ── RM/BC printable HTML ──────────────────────────────────────────────────────
@CompileStatic
private String pbFmt(Object v) {
    if (v == null) return "—"
    return (v as Boolean) ? "<span style='color:blue;font-weight:bold'>TRUE</span>" : "<span style='color:#aaa'>FALSE</span>"
}
@CompileStatic
private String escapeCsv(Object v) {
    if (v == null) return ""
    String s = v.toString().replace('"', '""')
    return (s.contains(",") || s.contains('"') || s.contains("\n")) ? "\"${s}\"" : s
}

String buildRmPrintHtml() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (e) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "<table><thead><tr>"
    ["Rule ID","Rule","App Type","Current PB State","PB Use Detected","Last Run"].each {
        sb << "<th>${it}</th>"
    }
    sb << "</tr></thead><tbody>"
    rows.each { Map r ->
        sb << "<tr>"
        sb << "<td class='c'>${htmlEncode(r.id)}</td>"
        sb << "<td>${htmlEncode(r.name)}</td>"
        sb << "<td class='c'>${htmlEncode(r.appType ?: "")}</td>"
        sb << "<td class='c'>${pbFmt(r.privateBool)}</td>"
        Boolean pbUsedVal = r.pbUsed == null ? null : (r.pbUsed as Boolean)
        String  pbUsedPrint = (pbUsedVal == null) ? "—" : pbUsedVal ? ((r.pbUsedStale == true) ? "Yes (stale)" : "Yes") : "No"
        sb << "<td class='c'>${pbUsedPrint}</td>"
        sb << "<td class='c'>${htmlEncode(r.lastRun ?: "")}</td>"
        sb << "</tr>"
    }
    sb << "</tbody></table>"

    String subtitle = "Last scan: ${state.lastScan ?: "never"} — ${rows.size()} rules"
    return printHtmlShell("Rule Machine and Button Controller Rule State", subtitle, sb.toString())
}

// ── RM/BC CSV ─────────────────────────────────────────────────────────────────
String buildRmCsv() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (e) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "Rule ID,Rule,App Type,Private Bool,PB Use Detected,Last Run\n"
    rows.each { Map r ->
        sb << "${escapeCsv(r.id)},${escapeCsv(r.name)},${escapeCsv(r.appType)}"
        sb << ",${r.privateBool == null ? "—" : (r.privateBool as Boolean) ? "TRUE" : "FALSE"}"
        String pbUseCsv = (r.pbUsed == null) ? "—" :
                          ((r.pbUsed as Boolean) ? ((r.pbUsedStale == true) ? "Yes (stale)" : "Yes") : "No")
        sb << ",${escapeCsv(pbUseCsv)}"
        sb << ",${escapeCsv(r.lastRun)}\n"
    }
    return sb.toString()
}

@CompileStatic
Boolean asBooleanLoose(Object value) {
    if (value == null) return false
    if (value instanceof Boolean) return value
    return value.toString().equalsIgnoreCase("true")
}

// ============================================================
// Private Boolean extraction
// ============================================================

// Returns the Private Boolean value from appState.
//
// The boolean flag "knownRead" distinguishes two null cases:
//   knownRead = false  statusJson failed (HTTP error / parse error)
//                      → return null  → renders as — ("couldn't read")
//   knownRead = true   statusJson succeeded but "private" is absent from appState
//                      → return false → RM's default for a PB that has never been set
Boolean extractPrivateBool(Map status, boolean knownRead = false) {
    for (Map item : (status?.appState ?: [])) {
        if (item?.name?.toString() == "private") {
            return asBooleanLoose(item?.value)
        }
    }
    return knownRead ? false : null
}

// ============================================================
// Last Run extraction
// ============================================================

String extractLastRun(Map status) {
    String lastEvtDate = ""
    String lastEvtTime = ""
    String timeFormat  = ""
    String dateFormat  = ""

    status?.appState?.each { item ->
        String n = item?.name?.toString() ?: ""
        if (n == "lastEvtDate") lastEvtDate = item?.value?.toString() ?: ""
        if (n == "lastEvtTime") lastEvtTime = item?.value?.toString() ?: ""
        if (n == "timeFormat")  timeFormat  = item?.value?.toString() ?: ""
        if (n == "dateFormat")  dateFormat  = item?.value?.toString() ?: ""
    }

    if (!lastEvtDate) return ""

    java.text.SimpleDateFormat outDateTimeFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
    java.text.SimpleDateFormat outDateFmt     = new java.text.SimpleDateFormat("yyyy-MM-dd")
    java.text.SimpleDateFormat outTimeFmt     = new java.text.SimpleDateFormat("HH:mm")

    // Determine whether lastEvtDate contains a time component.
    // A time component is indicated by a colon after the first 6 characters (to skip
    // over dd-MMM-yyyy's absence of colons) or by AM/PM anywhere in the string.
    boolean hasTimeComponent = lastEvtDate.toUpperCase().contains("AM") ||
                               lastEvtDate.toUpperCase().contains("PM") ||
                               lastEvtDate.indexOf(":", 6) >= 0

    if (hasTimeComponent) {
        List<String> fullDateFmts = [
            "dd-MMM-yyyy hh:mm:ss a",
            "dd-MMM-yyyy HH:mm:ss",
            "dd-MMM-yyyy hh:mm a",
            "dd-MMM-yyyy HH:mm",
            "MM/dd/yyyy hh:mm:ss a",
            "MM/dd/yyyy HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd hh:mm:ss a"
        ]
        for (String fmt : fullDateFmts) {
            try {
                return outDateTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate))
            } catch (Exception ignored) {}
        }
        log.warn "extractLastRun: unrecognized full datetime '${lastEvtDate}' — add format to extractLastRun if needed"
        return "* ${lastEvtDate}"
    }

    if (!lastEvtDate.matches(/\d{4}-\d{2}-\d{2}/)) {
        // Put hub's own dateFormat first so locale-specific formats are tried before fallbacks
        List<String> dateFmts = (dateFormat ? [dateFormat] : []) + ["dd-MMM-yyyy", "MM/dd/yyyy", "dd/MM/yyyy", "MMM dd, yyyy"]
        String normalizedDate = null
        for (String fmt : dateFmts) {
            try {
                normalizedDate = outDateFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate))
                break
            } catch (Exception ignored) {}
        }
        if (normalizedDate) {
            lastEvtDate = normalizedDate
        } else {
            log.warn "extractLastRun: unrecognized date format '${lastEvtDate}' — add format to extractLastRun if needed"
            lastEvtDate = "* ${lastEvtDate}"
        }
    }

    if (!lastEvtTime) return lastEvtDate

    List<String> timeFmts = timeFormat ? [timeFormat] : []
    timeFmts += ["hh:mm:ss a", "h:mm:ss a", "HH:mm:ss", "hh:mm a", "h:mm a", "HH:mm", "h:mm"]
    for (String fmt : timeFmts) {
        try {
            return "${lastEvtDate} ${outTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtTime))}"
        } catch (Exception ignored) {}
    }

    log.warn "extractLastRun: could not parse time '${lastEvtTime}' (timeFormat='${timeFormat}') — add format to extractLastRun if needed"
    return "* ${lastEvtDate} ${lastEvtTime}"
}

// ============================================================
// Shared report assets (CSS + JS)
// ============================================================
// Always called from buildReportHtml() — even when rows is empty — so the
// table has sortRmLogTable, wildcardToRegex, rmTogglePB, etc. available
// regardless of whether there are any RM/BC rules to display.
String buildSharedReportAssets(String pbEndpoint, String prefEndpoint = "", String bulkPbEndpoint = "") {
    StringBuilder sb = new StringBuilder()
    sb << "<style>"
    sb << "table.rmlogcheck{border-collapse:collapse;width:100%;}"
    sb << "table.rmlogcheck th,table.rmlogcheck td{border:1px solid #ccc;padding:4px 7px;text-align:left;vertical-align:middle;}"
    sb << "table.rmlogcheck th{background-color:#FFD700;color:#000;cursor:pointer;font-weight:bold;user-select:none;white-space:nowrap;}"
    sb << "table.rmlogcheck th:hover{background-color:#FFC700;}"
    sb << "table.rmlogcheck th.sort-asc::after{content:' ▲';font-size:0.8em;}"
    sb << "table.rmlogcheck th.sort-desc::after{content:' ▼';font-size:0.8em;}"
    sb << "table.rmlogcheck td.center,table.rmlogcheck th.center{text-align:center;}"
    sb << "table.rmlogcheck td.rmcol-lastrun{white-space:nowrap;}"
    sb << ".rmcol-toggle-bar{margin-bottom:8px;font-size:0.9em;}"
    sb << ".rmcol-btn{display:inline-block;cursor:pointer;padding:2px 8px;margin-right:6px;"
    sb << "border:1px solid #aaa;border-radius:3px;background:#e8e8e8;user-select:none;}"
    sb << ".rmcol-btn.hidden-col{text-decoration:line-through;opacity:0.45;background:#ccc;}"
    sb << "table.rmlogcheck td.rmlog-clickable{cursor:pointer;}"
    sb << "table.rmlogcheck td.rmlog-clickable:hover{filter:brightness(0.82);}"
    sb << "table.rmlogcheck td.rmlog-toggling{opacity:0.45;cursor:wait;pointer-events:none;}"
    sb << ".rmname-filter{padding:2px 6px;font-size:0.9em;border:1px solid #aaa;border-radius:3px;vertical-align:middle;}"
    sb << ".rmcheck-action-btn{display:inline-block;cursor:pointer;padding:2px 9px;margin-right:4px;border:1px solid #888;border-radius:3px;background:#d0e8ff;font-weight:bold;user-select:none;}"
    sb << ".rmcheck-action-false{background:#FFE6FF;}"
    sb << ".rmcheck-action-clear{background:transparent;}"
    sb << ".rmcheck-action-hide{background:#f0f0f0;color:#333;}"
    sb << ".rm-apply-bar{margin:4px 0 8px;}"
    sb << ".rm-apply-btn{cursor:pointer;padding:4px 14px;background:#1E88E5;color:#fff;border:none;border-radius:4px;font-size:0.9em;font-weight:bold;}"
    sb << ".rm-apply-btn:hover:not(:disabled){background:#1565C0;}"
    sb << ".rm-bulk-status{font-size:0.9em;color:#555;margin-left:10px;vertical-align:middle;}"
    sb << "table.rmlogcheck td.rmcol-cb-true,table.rmlogcheck th.rmcol-cb-true,table.rmlogcheck td.rmcol-cb-false,table.rmlogcheck th.rmcol-cb-false{width:54px;min-width:54px;text-align:center;padding:3px 2px;}"
    sb << "table.rmlogcheck td.rmcol-pbused,table.rmlogcheck th.rmcol-pbused{width:90px;min-width:90px;}"
    sb << "</style>"
    // Embed both endpoint URLs as JS variables using JsonOutput for safe token escaping.
    sb << "<script>var rmPbEndpoint = ${groovy.json.JsonOutput.toJson(pbEndpoint ?: null)}; var rmPrefEndpoint = ${groovy.json.JsonOutput.toJson(prefEndpoint ?: null)}; var rmBulkPbEndpoint = ${groovy.json.JsonOutput.toJson(bulkPbEndpoint ?: null)};</script>"

    sb << '''<script>
    
function sortRmLogTable(tableId, columnIndex) {
    const table = document.getElementById(tableId);
    if (!table) return;
    const tbody = table.querySelector('tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const headers = table.querySelectorAll('th');
    if (!window.rmLogTableSorts) window.rmLogTableSorts = {};
    if (!window.rmLogTableSorts[tableId]) window.rmLogTableSorts[tableId] = {};
    const currentDirection = window.rmLogTableSorts[tableId][columnIndex] || 'asc';
    const newDirection = currentDirection === 'asc' ? 'desc' : 'asc';
    window.rmLogTableSorts[tableId][columnIndex] = newDirection;
    headers.forEach(header => { header.classList.remove('sort-asc', 'sort-desc'); });
    if (headers[columnIndex]) headers[columnIndex].classList.add('sort-' + newDirection);
    rows.sort((a, b) => {
        const aCell = a.querySelectorAll('td')[columnIndex];
        const bCell = b.querySelectorAll('td')[columnIndex];
        let aText = aCell ? (aCell.getAttribute('data-sort') || aCell.textContent || '').trim() : '';
        let bText = bCell ? (bCell.getAttribute('data-sort') || bCell.textContent || '').trim() : '';
        const aNum = parseFloat(aText);
        const bNum = parseFloat(bText);
        let comparison = 0;
        const numericPattern = /^-?\\d+(\\.\\d+)?$/;
        if (numericPattern.test(aText) && numericPattern.test(bText)) {
            comparison = aNum - bNum;
        } else {
            comparison = aText.toLowerCase().localeCompare(bText.toLowerCase());
        }
        return newDirection === 'asc' ? comparison : -comparison;
    });
    rows.forEach(row => tbody.appendChild(row));
}

// Column hide toggle
function persistPref(key, value) {
    if (!key || !rmPrefEndpoint) return;
    fetch(rmPrefEndpoint + '&key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value))
        .catch(function(e) { console.warn('persistPref failed:', e.message); });
}

function toggleRmCol(cls, btn) {
    var hiding = btn.className.indexOf('hidden-col') === -1;
    document.querySelectorAll('.' + cls).forEach(function(el) { el.style.display = hiding ? 'none' : ''; });
    btn.className = hiding ? 'rmcol-btn hidden-col' : 'rmcol-btn';
    persistPref(btn.dataset.prefKey, String(hiding));
}

// Convert a wildcard pattern (* = any chars, ? = any single char) to a RegExp.
function wildcardToRegex(pattern) {
    var result = '';
    for (var i = 0; i < pattern.length; i++) {
        var ch = pattern[i];
        if (ch === '*') { result += '.*'; }
        else if (ch === '?') { result += '.'; }
        else if ('.+^${}()|[]\\\\'.indexOf(ch) >= 0) { result += '\\\\' + ch; }
        else { result += ch; }
    }
    return new RegExp('^' + result + '$', 'i');
}

var rmPbUsedFilterActive = false;

function rmTogglePbUsedFilter() {
    var btn = document.getElementById('rm-hide-no-pb');
    rmPbUsedFilterActive = !rmPbUsedFilterActive;
    if (btn) btn.textContent = rmPbUsedFilterActive ? 'Show all rows' : 'Hide rows without PB use';
    applyRmRowFilters();
}

function applyRmRowFilters() {
    var filterEl  = document.getElementById('rmname-filter');
    var filterVal = filterEl ? filterEl.value.trim() : '';
    var filterRe  = null;
    var hasWild   = filterVal.indexOf('*') >= 0 || filterVal.indexOf('?') >= 0;
    var lowerSub  = '';
    if (filterVal) {
        if (hasWild) {
            // Wildcard pattern — full-string match with * and ? expansion
            try { filterRe = wildcardToRegex(filterVal); } catch(e) { filterRe = null; }
        } else {
            // Plain text — case-insensitive substring match (indexOf avoids regex escaping)
            lowerSub = filterVal.toLowerCase();
        }
    }
    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
        var hide = false;
        if (!hide && filterVal) {
            var nameCell = tr.querySelectorAll('td')[3];
            var nm = nameCell ? (nameCell.getAttribute('data-sort') || nameCell.textContent || '').trim() : '';
            if      (filterRe) { hide = !filterRe.test(nm); }
            else if (lowerSub) { hide = nm.toLowerCase().indexOf(lowerSub) < 0; }
        }
        if (!hide && rmPbUsedFilterActive) {
            var pbTd = tr.querySelector('.rmcol-pbused');
            var sort = pbTd ? (pbTd.getAttribute('data-sort') || '0') : '0';
            if (sort !== '2') hide = true;
        }
        tr.style.display = hide ? 'none' : '';
    });
}


// ── Persist checkbox state (all rows, not just visible) ─────────────────
// Called after every checkbox change so selections survive page refreshes.
// Saves a single JSON blob {true:[ids], false:[ids]} via the /setpref endpoint.
function persistCheckboxState() {
    var trueIds = [], falseIds = [];
    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
        var t = tr.querySelector('.rm-cb-true');
        var f = tr.querySelector('.rm-cb-false');
        if (!t) return;
        if (t.checked) trueIds.push(t.dataset.ruleId);
        else if (f && f.checked) falseIds.push(f.dataset.ruleId);
    });
    persistPref('pbCheckboxState', JSON.stringify({true: trueIds, false: falseIds}));
}

// ── Checkbox mutual exclusion ──────────────────────────────────────────────
function rmCbTrue(cb) {
    if (cb.checked) {
        var row = cb.closest('tr');
        if (row) { var f = row.querySelector('.rm-cb-false'); if (f) f.checked = false; }
    }
    persistCheckboxState();
}
function rmCbFalse(cb) {
    if (cb.checked) {
        var row = cb.closest('tr');
        if (row) { var t = row.querySelector('.rm-cb-true'); if (t) t.checked = false; }
    }
    persistCheckboxState();
}

// ── Bulk checkbox helpers ─────────────────────────────────────────────────
function rmCheckAllTrue() {
    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
        if (tr.style.display === 'none') return;
        var t = tr.querySelector('.rm-cb-true');  if (t) t.checked = true;
        var f = tr.querySelector('.rm-cb-false'); if (f) f.checked = false;
    });
    persistCheckboxState();
}

function rmCheckAllFalse() {
    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
        if (tr.style.display === 'none') return;
        var t = tr.querySelector('.rm-cb-true');  if (t) t.checked = false;
        var f = tr.querySelector('.rm-cb-false'); if (f) f.checked = true;
    });
    persistCheckboxState();
}

function rmCheckAllClear() {
    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
        if (tr.style.display === 'none') return;
        var t = tr.querySelector('.rm-cb-true');  if (t) t.checked = false;
        var f = tr.querySelector('.rm-cb-false'); if (f) f.checked = false;
    });
    persistCheckboxState();
}

// ── Refresh visible summary counts after client-side PB changes ───────────
// The server-side state is updated by /setPB and /bulkPB, but this updates the
// currently-rendered dynamic page immediately without requiring a refresh.
function rmRefreshPbSummaryCounts() {
    var trueCount = 0;
    var falseCount = 0;

    document.querySelectorAll('#rmlog_table td.rmcol-pb').forEach(function(td) {
        var v = (td.getAttribute('data-sort') || '').trim();
        if (v === '2') trueCount++;
        else if (v === '1') falseCount++;
    });

    var trueEl = document.getElementById('rm-summary-pb-true');
    var falseEl = document.getElementById('rm-summary-pb-false');
    if (trueEl) trueEl.textContent = String(trueCount);
    if (falseEl) falseEl.textContent = String(falseCount);
}

// ── Apply bulk PB changes via /bulkPB endpoint ────────────────────────────
// Collects checked rule IDs, calls the endpoint once, updates PB cells in-page,
// clears checkboxes, and shows a brief status message.
async function rmApplyBulkPB() {
    if (!rmBulkPbEndpoint) { alert('Bulk PB endpoint not available. Re-save the app.'); return; }

    var trueIds = [], falseIds = [];
    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
        var t = tr.querySelector('.rm-cb-true');
        var f = tr.querySelector('.rm-cb-false');
        if (!t) return;
        var id = t.dataset.ruleId;
        if (t.checked) trueIds.push(id);
        else if (f && f.checked) falseIds.push(id);
    });

    if (trueIds.length === 0 && falseIds.length === 0) {
        alert('No rules selected. Check at least one Set TRUE or Set FALSE checkbox.');
        return;
    }

    var statusEl = document.getElementById('rm-bulk-status');
    var btn      = document.getElementById('rm-apply-pb-btn');
    if (statusEl) statusEl.textContent = 'Applying\u2026';
    if (btn) { btn.disabled = true; btn.style.opacity = '0.6'; }

    try {
        var url = rmBulkPbEndpoint
            + '&trueIds='  + encodeURIComponent(trueIds.join(','))
            + '&falseIds=' + encodeURIComponent(falseIds.join(','));
        var resp = await fetch(url);
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        var text = await resp.text();
        var result;
        try { result = JSON.parse(text); } catch(e) { throw new Error('Non-JSON: ' + text.substring(0,120)); }
        if (result.status !== 'success') throw new Error(result.message || JSON.stringify(result));

        // Update Current PB State cells for changed rows
        document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
            var t = tr.querySelector('.rm-cb-true');
            var f = tr.querySelector('.rm-cb-false');
            if (!t) return;
            var ruleId = t.dataset.ruleId;
            var newVal = t.checked ? true : (f && f.checked ? false : null);
            if (newVal === null) return;

            var pbTd = tr.querySelector('.rmcol-pb');
            if (!pbTd) return;

            pbTd.dataset.on = String(newVal);
            pbTd.setAttribute('data-sort', newVal ? '2' : '1');
            pbTd.innerHTML = newVal
                ? "<span style='color:blue;font-weight:bold;'>TRUE</span>"
                : "<span style='color:#aaa;'>FALSE</span>";

            // Ensure cell is clickable (it may have been unknown — non-clickable — before)
            if (rmPbEndpoint && !pbTd.classList.contains('rmlog-clickable')) {
                pbTd.dataset.ruleId = ruleId;
                pbTd.setAttribute('onclick', 'rmTogglePB(this)');
                pbTd.classList.add('rmlog-clickable');
            }
        });

        rmRefreshPbSummaryCounts();

        var total = trueIds.length + falseIds.length;
        if (statusEl) {
            statusEl.textContent = 'Done \u2014 ' + total + ' rule' + (total !== 1 ? 's' : '') + ' updated.';
            setTimeout(function() { if (statusEl) statusEl.textContent = ''; }, 5000);
        }
    } catch(e) {
        alert('Apply PB changes failed: ' + e.message);
        if (statusEl) statusEl.textContent = '';
    } finally {
        if (btn) { btn.disabled = false; btn.style.opacity = '1'; }
    }
}

async function rmTogglePB(td) {
    if (!rmPbEndpoint) { alert('Private Boolean endpoint not available. Re-save the app and scan again.'); return; }
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var ruleId = td.dataset.ruleId;
    var newOn  = td.dataset.on !== 'true';
    try {
        var url  = rmPbEndpoint + '&id=' + encodeURIComponent(ruleId) + '&value=' + String(newOn);
        var resp = await fetch(url);
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        var text = await resp.text();
        if (!text || !text.trim()) throw new Error('Empty response from PB endpoint — check app logs');
        var result;
        try { result = JSON.parse(text); } catch(e) { throw new Error('Non-JSON response: ' + text.substring(0, 100)); }
        if (result.status !== 'success') throw new Error(result.message || JSON.stringify(result));
        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '2' : '1');   // three-way: unknown=0, false=1, true=2
        td.innerHTML = newOn ? "<span style='color:blue;font-weight:bold;'>TRUE</span>"
                             : "<span style='color:#aaa;'>FALSE</span>";
        rmRefreshPbSummaryCounts();
    } catch(e) {
        alert('Toggle Private Boolean failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}
</script>'''
    return sb.toString()
}

// ============================================================
// RM/BC table HTML
// ============================================================

String buildReportHtml(List<Map> rows) {
    String pbEndpoint      = ""
    String prefEndpoint    = ""
    String bulkPbEndpoint  = ""
    if (state.accessToken) {
        pbEndpoint     = "/apps/api/${app.id}/setPB?access_token=${state.accessToken}"
        prefEndpoint   = "/apps/api/${app.id}/setpref?access_token=${state.accessToken}"
        bulkPbEndpoint = "/apps/api/${app.id}/bulkPB?access_token=${state.accessToken}"
    } else {
        log.warn "buildReportHtml: no access token — PB cells will render as non-clickable. Re-save the app to generate a token."
    }

    StringBuilder sb = new StringBuilder()
    sb << buildSharedReportAssets(pbEndpoint, prefEndpoint, bulkPbEndpoint)

    if (!rows) {
        sb << "<p>No rules found. Click <b>Scan All Rules for Current PB State</b> to begin.</p>"
        return sb.toString()
    }

    // PB Use Detected column is always visible: shows — before any usage scan,
    // fresh green ✓ / — after Phase 2, or stale red ✓ after Phase 1 with prior Phase 2 data.

    // Read custom column visibility settings
    boolean cfgHideColRuleId  = getPref("hideColRuleId",  false)
    boolean cfgHideColAppType = getPref("hideColAppType", false)
    boolean cfgHideColPB      = getPref("hideColPB",      false)
    boolean cfgHideColLastRun = getPref("hideColLastRun", false)
    boolean cfgHideColPbUsed  = getPref("hideColPbUsed", false)

    String btnColRuleId  = cfgHideColRuleId  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColAppType = cfgHideColAppType ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColPB      = cfgHideColPB      ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColLastRun = cfgHideColLastRun ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColPbUsed  = cfgHideColPbUsed  ? "rmcol-btn hidden-col" : "rmcol-btn"

    // Restore checkbox selections that were saved during the last user interaction.
    // Stored as a single JSON blob {true:[ids], false:[ids]} under the "pbCheckboxState" pref.
    Set<String> cbTrueIds  = [] as Set<String>
    Set<String> cbFalseIds = [] as Set<String>
    try {
        String cbJson = getPrefString("pbCheckboxState", "{}")
        if (cbJson && cbJson != "{}") {
            Map cbState = new groovy.json.JsonSlurper().parseText(cbJson) as Map
            cbTrueIds  = (cbState?.get("true")  ?: []).collect { it.toString() } as Set<String>
            cbFalseIds = (cbState?.get("false") ?: []).collect { it.toString() } as Set<String>
            // mutual-exclusion guard: if an id is in both, TRUE wins
            cbFalseIds = cbFalseIds.findAll { !(it in cbTrueIds) } as Set<String>
        }
    } catch (Exception ignored) {}

    sb << "<div class='rmcol-toggle-bar'>"
    sb << "<span class='rmcheck-action-btn' onclick='rmCheckAllTrue()'>All TRUE</span>"
    sb << "<span class='rmcheck-action-btn rmcheck-action-false' onclick='rmCheckAllFalse()'>All FALSE</span>"
    sb << "<span class='rmcheck-action-btn rmcheck-action-clear' onclick='rmCheckAllClear()'>All Clear</span>"
    sb << "</div>"
    sb << "<div class='rm-apply-bar'>"
    sb << "<button id='rm-apply-pb-btn' class='rm-apply-btn' onclick='rmApplyBulkPB()'>Apply selected PB changes</button>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
    sb << "<span id='rm-bulk-status' class='rm-bulk-status'></span>"
    sb << "&nbsp;&nbsp;<span id='rm-hide-no-pb' class='rmcheck-action-btn rmcheck-action-hide' onclick='rmTogglePbUsedFilter()'>Hide rows without PB use</span>"
    sb << "<span style='display:inline-block;width:1in;'></span>"
    sb << "<b>Hide columns:</b>&nbsp;"
    sb << "<span id='rmtoggle-rmcol-ruleid'  class='${btnColRuleId}'  data-pref-key='hideColRuleId'  onclick=\"toggleRmCol('rmcol-ruleid',this)\">Rule ID</span>"
    sb << "<span id='rmtoggle-rmcol-apptype' class='${btnColAppType}' data-pref-key='hideColAppType' onclick=\"toggleRmCol('rmcol-apptype',this)\">App Type</span>"
    sb << "<span id='rmtoggle-rmcol-pb'      class='${btnColPB}'      data-pref-key='hideColPB'      onclick=\"toggleRmCol('rmcol-pb',this)\">Current PB State</span>"
    sb << "<span id='rmtoggle-rmcol-pbused' class='${btnColPbUsed}' data-pref-key='hideColPbUsed' onclick=\"toggleRmCol('rmcol-pbused',this)\">PB Use Detected</span>"
    sb << "<span id='rmtoggle-rmcol-lastrun' class='${btnColLastRun}' data-pref-key='hideColLastRun' onclick=\"toggleRmCol('rmcol-lastrun',this)\">Last Run</span>"
    sb << "&nbsp;&nbsp;<b>Filter:</b>&nbsp;"
    sb << "<input id='rmname-filter' type='text' class='rmname-filter' placeholder='Filter rule name (substring or * ? wildcards)' oninput='applyRmRowFilters()' style='width:330px;'>"
    sb << "</div>"

    sb << "<table id='rmlog_table' class='rmlogcheck'><thead><tr>"
    sb << "<th class='center rmcol-cb-true'>Set TRUE</th>"
    sb << "<th class='center rmcol-cb-false'>Set FALSE</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',2)\" class='center rmcol-ruleid'>Rule ID</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',3)\" class='sort-asc'>Rule</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',4)\" class='center rmcol-apptype'>App Type</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',5)\" class='center rmcol-pb'>Current PB State</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',6)\" class='center rmcol-pbused'>PB Use Detected</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',7)\" class='center rmcol-lastrun'>Last Run</th>"
    sb << "</tr></thead><tbody>"

    rows.each { Map r ->
        String id       = htmlEncode(r.id)
        String nameSort = htmlEncode(r.name?.toString()?.replaceAll(/<[^>]+>/, '') ?: "")
        String nameHtml = renderNameHtml(r.name)
        String appType  = htmlEncode(r.appType ?: "RM")

        // privateBool: null = unknown (field absent), true/false = known state
        Boolean pbVal   = r.privateBool == null ? null : (r.privateBool as Boolean)
        String  pbFmt   = (pbVal == null)  ? "<span style='color:#999;'>—</span>"
                        : pbVal            ? "<span style='color:blue;font-weight:bold;'>TRUE</span>"
                                           : "<span style='color:#aaa;'>FALSE</span>"
        String pbSort   = (pbVal == true)  ? "2" : (pbVal == false) ? "1" : "0"

        String lastRun  = htmlEncode(r.lastRun ?: "")

        // PB cell: clickable only when endpoint is available AND state is known (not null)
        String pbTd
        if (pbEndpoint && pbVal != null) {
            pbTd = "<td class='center rmcol-pb rmlog-clickable' data-sort='${pbSort}'" +
                   " data-rule-id='${id}' data-on='${pbVal}'" +
                   " onclick='rmTogglePB(this)'>${pbFmt}</td>"
        } else {
            pbTd = "<td class='center rmcol-pb' data-sort='${pbSort}'>${pbFmt}</td>"
        }

        sb << "<tr>"
        String cbTrueAttr  = cbTrueIds.contains(id)  ? " checked" : ""
        String cbFalseAttr = cbFalseIds.contains(id) ? " checked" : ""
        sb << "<td class='center rmcol-cb-true'><input type='checkbox' class='rm-cb-true' data-rule-id='${id}'${cbTrueAttr} onchange='rmCbTrue(this)'></td>"
        sb << "<td class='center rmcol-cb-false'><input type='checkbox' class='rm-cb-false' data-rule-id='${id}'${cbFalseAttr} onchange='rmCbFalse(this)'></td>"
        sb << "<td class='center rmcol-ruleid' data-sort='${id}'>${id}</td>"
        sb << "<td data-sort='${nameSort}'><a href='/installedapp/configure/${id}' target='_blank'>${nameHtml}</a></td>"
        sb << "<td class='center rmcol-apptype' data-sort='${appType}'>${appType}</td>"
        sb << pbTd
        // PB Use Detected cell: always rendered.
        // Fresh ✓ (Phase 2 current): green.  Stale ✓ (Phase 2 prior, Phase 1 scan since): red.
        Boolean pbUsedVal   = r.pbUsed == null ? null : (r.pbUsed as Boolean)
        boolean pbUsedStale = r.pbUsedStale == true
        String  pbUsedColor = pbUsedStale ? "#c00" : "green"
        String  pbUsedDisp  = (pbUsedVal == true)
            ? "<span style='color:${pbUsedColor};font-weight:bold;'>&#10003;</span>"
            : "<span style='color:#aaa;'>—</span>"
        String  pbUsedSort  = (pbUsedVal == true) ? "2" : "0"
        sb << "<td class='center rmcol-pbused' data-sort='${pbUsedSort}'>${pbUsedDisp}</td>"
        sb << "<td class='center rmcol-lastrun' data-sort='${lastRun}'>${lastRun}</td>"
        sb << "</tr>"
    }

    sb << "</tbody></table>"

    // Column init script — apply initial column-hide settings client-side
    List<String> colClassesToHide = []
    if (cfgHideColRuleId)  colClassesToHide << "'rmcol-ruleid'"
    if (cfgHideColAppType) colClassesToHide << "'rmcol-apptype'"
    if (cfgHideColPB)      colClassesToHide << "'rmcol-pb'"
    if (cfgHideColPbUsed) colClassesToHide << "'rmcol-pbused'"
    if (cfgHideColLastRun) colClassesToHide << "'rmcol-lastrun'"
    if (colClassesToHide) {
        sb << "<script>setTimeout(function(){[${colClassesToHide.join(',')}].forEach(function(cls){document.querySelectorAll('.'+cls).forEach(function(el){el.style.display='none';});});},0);</script>"
    }

    return sb.toString()
}

// ============================================================
// Formatting helpers
// ============================================================

@CompileStatic
String formatScanDuration(Long elapsedMs) {
    Long safeMs = elapsedMs ?: 0L
    if (safeMs < 0L) safeMs = 0L
    Long totalSeconds = Math.round(safeMs / 1000.0D) as Long
    Long minutes      = Math.floor(totalSeconds / 60.0D) as Long
    Long seconds      = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

@CompileStatic
String htmlEncode(Object value) {
    if (value == null) return ""
    return value.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#39;")
}

// Encode all HTML, then selectively restore safe color spans so names like
// "<span style='color:red'>TEXT</span>" render as colored text rather than
// raw markup. Color values are restricted to [a-zA-Z#0-9]+ to prevent injection.
@CompileStatic
String renderNameHtml(Object value) {
    if (value == null) return ""
    String encoded = htmlEncode(value)
    return encoded.replaceAll(
        /&lt;span style=(?:&#39;|&quot;)color:([a-zA-Z#0-9]+)(?:&#39;|&quot;)&gt;(.*?)&lt;\/span&gt;/,
        "<span style='color:\$1'>\$2</span>"
    )
}

private void updateCachedPbStatesAfterBulkSet(List trueRuleIds, List falseRuleIds) {
    if (!state.scanRowsJson) return

    Set<String> trueIds  = (trueRuleIds  ?: []).collect { it.toString() } as Set<String>
    Set<String> falseIds = (falseRuleIds ?: []).collect { it.toString() } as Set<String>

    try {
        List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map>

        rows.each { Map r ->
            String id = r.id?.toString()
            if (trueIds.contains(id)) {
                r.privateBool = true
            } else if (falseIds.contains(id)) {
                r.privateBool = false
            }
        }

        state.privateBoolOnCount  = rows.count { it.privateBool == true }  as Integer
        state.privateBoolOffCount = rows.count { it.privateBool == false } as Integer

        state.scanRowsJson = groovy.json.JsonOutput.toJson(rows)
        state.reportHtml   = buildReportHtml(rows)

    } catch (Exception e) {
        log.warn "updateCachedPbStatesAfterBulkSet: could not update cached PB states — ${e.message}"
    }
}
