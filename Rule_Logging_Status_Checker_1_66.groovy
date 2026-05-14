/*
 *  Rule Logging and State Checker
 *
 *  Scans Rule Machine and Button Controller child apps and reports which rules appear to have
 *  Actions, Events, and/or Triggers logging enabled, plus Disabled, Paused, and Private Boolean states.
 *
 *  Designed initially by John Land, built by Claude AI with an assist by ChatGPT, then revised
 *  to incorporate the excellent work of and feedback from hubitrep (the clickable cells are genius).
 *
 *  Notes:
 *  - Uses Hubitat local/internal JSON endpoints:
 *      /hub2/appsList
 *      /installedapp/statusJson/{appId}
 *      /installedapp/configure/json/{appId}  (logging toggle feature)
 *      /installedapp/update/json             (logging toggle feature)
 *      /apps/api/{thisAppId}/setPB           (Private Boolean toggle — this app's OAuth endpoint)
 *  - Some of these endpoints are not a formal public API and could change in a
 *    future Hubitat platform release.
 *  - Private Boolean toggling uses RMUtils.sendAction() with RM version "5.0".
 *    Rules from earlier RM versions will display PB state but the toggle may not work.
 *
 *  See README.md for version history.
 */

import hubitat.helper.RMUtils
import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String  RM_BASE_URL        = "http://127.0.0.1:8080"
@Field static final String  RM_VERSION          = "5.0"
@Field static final int     SCAN_TIMEOUT_SECS   = 360   // max seconds before scan is force-finalized
@Field static final int     LOGS_OFF_DELAY_SECS = 1800  // seconds before debug logging auto-disables

// Transient scan state lives in @Field static to avoid database writes during a scan.
// If the app class is reloaded during a scan (e.g. on code save or hub restart),
// the scan is abandoned and can be run again.
@Field static String    currentScanId      = null
@Field static Long      scanStartMs        = 0L
@Field static List<Map> scanRuleQueue      = null
@Field static Map       scanPartialResults = null   // keyed by ruleId String; holds both RM/BC and builtin rows

definition(
    name:           "Rule Logging and State Checker 1.66",
    namespace:      "John Land",
    author:         "John Land & AI",
    description:    "Reports logging status and Disabled, Paused, and Private Boolean states for Hubitat rules.",
    category:       "Utility",
    singleInstance: true,
    installOnOpen:  true,
    oauth:          true,
    iconUrl:        '',
    iconX2Url:      '',
    importUrl:      "https://raw.githubusercontent.com/JohnFLand/Rule-Logging-and-State-Checker/refs/heads/main/Rule_Logging_Status_Checker.groovy"
)

preferences {
    page(name: "mainPage")
}

// ── OAuth endpoint mapping ────────────────────────────────────────────────────
// Called by the browser JS click handler to toggle a rule's Private Boolean.
// GET /apps/api/{thisAppId}/setPB?id={ruleId}&value=true|false&access_token={token}

mappings {
    path("/setPB")                  { action: [GET: "handleSetPBEndpoint"] }
    path("/setpref")                { action: [GET: "handleSetPrefEndpoint"] }
    path("/report")                 { action: [GET: "handleReportEndpoint"] }
    path("/RM-BC_Rules.csv")        { action: [GET: "handleRmCsvEndpoint"] }
    path("/Built-In_Rules.csv")     { action: [GET: "handleBuiltinCsvEndpoint"] }
}

// ============================================================
// Lifecycle
// ============================================================

void installed() {
    checkOAuth()           // auto-enable OAuth and create token on first install
    initialize()
    runIn(10, "findLoggingRules")
}

void updated() {
    boolean scanWasActive = (currentScanId != null)
    initialize()
    if (scanWasActive) {
        state.scanStatus  = "<i>Scan was cancelled because app settings were saved. Click <b>Scan All Rules</b> to run again.</i>"
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

    if (debugEnable) {
        runIn(LOGS_OFF_DELAY_SECS, "logsOff")
    }
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// Re-render the report HTML using rows cached in state.scanRowsJson.
// Called from updated() so display-setting changes apply on Done without a rescan.
void reRenderReportIfCached() {
    if (!state.scanRowsJson && !state.builtinRowsJson) return
    try {
        if (state.scanRowsJson) {
            List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson) as List<Map>
            state.reportHtml = buildReportHtml(rows)
            log.info "RM/BC report re-rendered from cached scan data (${rows.size()} rules)"
        }
        if (state.builtinRowsJson) {
            List<Map> rows = new groovy.json.JsonSlurper().parseText(state.builtinRowsJson) as List<Map>
            state.builtinReportHtml = buildBuiltinReportHtml(rows)
            log.info "Built-in app report re-rendered from cached data (${rows.size()} apps)"
        }
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
            log.info "Rule Logging and State Checker: OAuth token created"
            return true
        }
    } catch (Exception e) {
        log.debug "checkOAuth: OAuth not yet enabled — attempting auto-enable via hub API..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                if (state.accessToken) {
                    log.info "Rule Logging and State Checker: OAuth auto-enabled and token created successfully"
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
        if (debugEnable) log.debug "setPB: rule ${ruleId} → ${action}"
        return renderJson([status: "success"])
    } catch (Exception e) {
        log.warn "setPB failed for rule ${ruleId} (${action}): ${e.message}"
        return renderJson([status: "error", message: e.message ?: "Unknown error"])
    }
}

// ============================================================
// UI
// ============================================================

def mainPage() {
    // Attempt to create the OAuth token on every page open — covers the case where the
    // user has just enabled OAuth in Apps Code and re-opened the app.
    checkOAuth()

    int pollInterval = currentScanId ? 5 : 0
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true, refreshInterval: pollInterval) {

        section("") {
            paragraph "<b style='font-size:1.1em;'>${app.name}</b>"
        }

        section("NOTE: Scanning may take a while, be patient!") {
            input "btnScan", "button", title: "Scan All Rules"
            if (state.lastScan) {
                paragraph "<b>Last scan:</b> ${state.lastScan} (Scan time: ${state.scanDuration ?: '00:00'})"
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

        boolean rmHidden = (settings.tableRmHidden != null) ? (settings.tableRmHidden as boolean) : false
        section("Rule Machine and Button Controller Rule State", hideable: true, hidden: rmHidden) {
            if (state.scannedCount != null) {
                paragraph "<div id='rmstats' style='margin:0;padding:0;line-height:1.5;font-size:1em;'>" +
                          "<b>Rules scanned:</b> <span id='rmstat-scanned'>${state.scannedCount ?: 0}</span>; " +
                          "<b>Any logging ON:</b> <span id='rmstat-anylogging'>${state.anyLoggingOnCount ?: 0}</span>; " +
                          "<b>Events:</b> <span id='rmstat-events'>${state.eventsOnCount ?: 0}</span>; " +
                          "<b>Triggers:</b> <span id='rmstat-triggers'>${state.triggersOnCount ?: 0}</span>; " +
                          "<b>Actions:</b> <span id='rmstat-actions'>${state.actionsOnCount ?: 0}</span>; " +
                          "<b>Private Bool TRUE:</b> <span id='rmstat-pb'>${state.privateBoolOnCount ?: 0}</span>; " +
                          "<b>Disabled:</b> <span id='rmstat-disabled'>${state.disabledCount ?: 0}</span>; " +
                          "<b>Paused:</b> <span id='rmstat-paused'>${state.pausedCount ?: 0}</span>" +
                          "<br><br></div>"
            }
            paragraph(state.reportHtml ?: "Click <b>Scan All Rules</b> to begin.")
        }

        section("") {
            input "tableRmHidden", "bool", title: "Hide Rule Machine/Button Controller Rule State table", defaultValue: false, submitOnChange: true
        }

        section("") { paragraph "" }   // spacer between RM/BC and Built-in sections

        boolean biHidden = (settings.tableBiHidden != null) ? (settings.tableBiHidden as boolean) : false
        section("Built-in App Rule State", hideable: true, hidden: biHidden) {
            paragraph "<small style='color:#555;'>for Hubitat built-in apps (Notifications, Basic Rules, Simple Automation Rules, Basic Button Controller, Room Lighting, Motion Lighting) that support a Logging setting</small>"
            if (state.biScannedCount != null) {
                String unknownStyle = (state.biLogUnknownCount ?: 0) > 0 ? '' : " style='display:none'"
                String biStats = "<div id='bistats' style='margin:0;padding:0;line-height:1.5;font-size:1em;'>" +
                    "<b>Rules scanned:</b> <span id='bistat-scanned'>${state.biScannedCount}</span>; " +
                    "<b>Logging ON:</b> <span id='bistat-logon' style='color:red;font-weight:bold;'>${state.biLogOnCount ?: 0}</span>; " +
                    "<b>Logging OFF:</b> <span id='bistat-logoff' style='color:green;'>${state.biLogOffCount ?: 0}</span>" +
                    "<span id='bistat-unknown-wrap'${unknownStyle}>" +
                    "; <b>Unknown:</b> <span id='bistat-unknown' style='color:#999;'>${state.biLogUnknownCount ?: 0}</span>" +
                    "</span>; " +
                    "<b>Disabled:</b> <span id='bistat-disabled'>${state.biDisabledCount ?: 0}</span>; " +
                    "<b>Paused:</b> <span id='bistat-paused'>${state.biPausedCount ?: 0}</span>" +
                    "<br><br></div>"
                paragraph biStats
            }
            if (state.builtinReportHtml) paragraph(state.builtinReportHtml)
        }

        section("") {
            input "tableBiHidden", "bool", title: "Hide Built-in App Rule State table", defaultValue: false, submitOnChange: true
        }

        section("") { paragraph "" }   // spacer between Built-in and Notes sections

        section("Controls", hideable: true, hidden: true) {
            // ── App instance rename ───────────────────────────────────────
            input "label", "text", title: "<b>App instance name</b>", defaultValue: app.name, submitOnChange: true

            // ── Report links — only available after a scan with a token ───
            if (state.accessToken) {
                String base = "/apps/api/${app.id}/report?access_token=${state.accessToken}"
                if (state.scanRowsJson) {
                    String rmCsvUrl = "/apps/api/${app.id}/RM-BC_Rules.csv?access_token=${state.accessToken}"
                    paragraph "<b>RM/BC Rule State Table</b> &nbsp;" +
                        "<a href='${base}&table=rm&format=html' target='_blank'>" +
                        "&#128196; Open Printable Report</a>" +
                        " &nbsp;|&nbsp; " +
                        "<a href='${rmCsvUrl}'>&#11015; Download CSV</a>"
                } else {
                    paragraph "<small>Run <b>Scan All Rules</b> to enable RM/BC reports.</small>"
                }
                if (state.builtinRowsJson) {
                    String biCsvUrl = "/apps/api/${app.id}/Built-In_Rules.csv?access_token=${state.accessToken}"
                    paragraph "<b>Built-in App Rule State Table</b> &nbsp;" +
                        "<a href='${base}&table=builtin&format=html' target='_blank'>" +
                        "&#128196; Open Printable Report</a>" +
                        " &nbsp;|&nbsp; " +
                        "<a href='${biCsvUrl}'>&#11015; Download CSV</a>"
                } else {
                    paragraph "<small>Run <b>Scan All Rules</b> to enable Built-in App reports.</small>"
                }
            } else {
                paragraph "<small>OAuth setup required before reports are available.</small>"
            }

            // ── Debug logging (last) ──────────────────────────────────────
            input "debugEnable", "bool",
                title: "<b>Enable debug logging</b>",
                defaultValue:   false,
                submitOnChange: true
        }

        section("Notes", hideable: true, hidden: true) {
            paragraph """
                <b>Overview</b><br>
                This app scans Rule Machine (<b>RM</b>) and Button Controller (<b>BC</b>) rules and
                reports their logging status (Events, Triggers, Actions), Disabled and Paused states,
                and Private Boolean value in a first table. It also scans rules of supported Hubitat built-in apps
                (Notifications, Basic Rules, Simple Automation Rules, Basic Button Controller,
                Room Lighting, Motion Lighting) and reports their Logging setting and Disabled and Paused
                states in a second table. The two tables each have their own filter, sort, and hide controls.

                Button Controller rules show "<b>—</b>" in the Events column because BC rules have no
                Events logging option.

                Rule types that expose only one broad logging toggle (rather than separate
                Events, Triggers, and Actions controls) appear in the Built-in App Logging table.
                <br>
                <b>Scanning</b><br>
                Click <b>Scan All Rules</b> to start a scan. Both tables update automatically when the scan
                finishes — no manual refresh needed. Clicking <b>Done</b> and reopening the app
                re-renders both tables instantly from cached data, so display setting changes take effect
                without a rescan (but use data from the previous scan). If you install a new version,
                run a fresh scan once to regenerate the tables with any new columns or buttons.
                <br>
                <b>Row filters</b><br>
                Each table has its own row filter buttons. In the RM/BC table, <b>No logging ON</b>
                is active by default, hiding rules where all logging is off; click it to show all rules.
                In the Built-in App Logging table, <b>Logging OFF</b> is active by default.
                Multiple row filters evaluate together — a row stays hidden if <i>any</i> active filter
                applies to it.
                <br>
                <b>Name filter</b><br>
                Each table has a name filter field. Plain text performs a case-insensitive substring match,
                so partial searches do not require wildcards. Optional wildcard patterns are also supported:
                use <b>*</b> to match any sequence of characters and <b>?</b> to match any single character,
                e.g. <code>Contact*TU</code> or <code>*Motion*</code>. Filtering combines with the row filter
                buttons — a row must pass both to be visible.
                <br>
                <b>Table Visibility</b><br>
                A <b>Hide</b> toggle immediately below each table hides or shows that entire table.
                The setting takes effect immediately and persists across page opens.
                Each table's section heading is also clickable to collapse or expand it temporarily.
                <br>
                <b>Row and column toggle buttons</b><br>
                Each table has its own hide-row and hide-column buttons above it.
                Clicking any button saves the preference automatically via the app's local OAuth
                endpoint — no "Done" press needed and the change persists across page opens.
                <br>
                <b>Sorting</b><br>
                Click any column header to sort by that column; clicking the same header again 
                reverses the sort direction. The default sort is by <b>Rule</b> name.
                <br>
                <b>Clickable cells — RM/BC table</b><br>
                Click any <b>Events</b>, <b>Triggers</b>, <b>Actions</b>, <b>Disabled</b>, <b>Paused</b>,
                or <b>Private Boolean</b> cell to toggle that rule's setting in-place.
                The table cell updates immediately if successful.
                
                Cells where the field name could not be determined are not clickable.
                <br>
                <b>Clickable cells — Built-in App Logging table</b><br>
                Click any <b>Logging</b>, <b>Disabled</b>, or <b>Paused</b> cell to toggle that setting in-place.
                After toggling <b>Paused</b> in the Built-in App Logging table, the rule is
                correctly paused immediately, but the <b>(Paused)</b> label on the Automations
                page may require a browser page refresh to appear.
                <br>
                <b>Private Boolean (RM/BC table)</b><br>
                Click any <b>Private Bool</b> cell to toggle a rule's Private Boolean between TRUE and FALSE.
                TRUE is displayed in bold blue; FALSE in grey. Cells showing "<b>—</b>" mean the PB
                state could not be read and are not clickable.
                
                The toggle calls <code>RMUtils.sendAction()</code> via this app's local OAuth endpoint,
                targeting RM version ${RM_VERSION} rules.

                OAuth is enabled automatically on first install — no manual setup required.
                If the PB toggle ever shows inactive, re-open the app to retry; if it still fails,
                enable OAuth manually via the three-dot menu in Apps Code, then re-open.
                The token persists across hub reboots and app updates.
                <br>
                <b>Last Run column</b><br>
                Shows the date and time of the most recent trigger event for each rule, normalised to
                24-hour <b>HH:mm</b> format regardless of how individual rules store the time. A blank
                cell means the rule has never been triggered since it was last installed. Last Run
                reflects when the rule was <i>triggered</i>, not necessarily when its actions completed.
                <br>
                <b>Summary counts</b><br>
                Shown as part of each table's heading area. Counts are computed from the most recent
                scan. Toggling any cell in-place updates both the cell and the relevant summary
                count immediately — Events, Triggers, Actions, Private Boolean, Disabled, and Paused
                all reflect in-place changes without a rescan. Run <b>Scan All Rules</b> again to
                recompute all counts from a fresh scan.
                <br>
                <b>Controls section</b><br>
                The collapsible <b>Controls</b> section (above Notes) provides four functions:<br>
                &bull; <b>App instance name</b> — type a custom name for this app instance; the name
                appears in the Hubitat Apps list and logs.<br>
                &bull; <b>Printable HTML reports</b> — opens a clean, print-optimised version of each
                table in a new browser tab. All rows are shown regardless of current filter state.
                Use the browser's Print or Save as PDF function from that tab.<br>
                &bull; <b>CSV export</b> — downloads the table data as a CSV file
                (<i>RM-BC_Rules.csv</i> or <i>Built-In_Rules.csv</i>) for use in a spreadsheet.<br>
                &bull; <b>Enable debug logging</b> — turns on verbose logging to the Hubitat log
                for 30 minutes, then disables itself automatically.
                <br>
                <b>WARNING</b><br>
                This app uses Hubitat local/internal JSON endpoints. Those endpoints and Rule Machine /
                Button Controller / built-in app internal setting names are not a formal public API,
                so the detection logic may need to be adjusted if Hubitat changes the JSON format in a
                future platform update.
                <br>
            """
        }
    }
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnScan":
            findLoggingRules()
            break
        default:
            log.warn "Unknown button: ${btn}"
            break
    }
}

// ============================================================
// Scanning — async sequential chain
// ============================================================

void findLoggingRules() {
    state.lastError         = null
    state.scanStatus        = "<i>Scan in progress…</i>"
    state.reportHtml        = null
    state.builtinReportHtml = null
    state.scanRowsJson      = null   // clear cache so updated() won't re-render stale data mid-scan
    state.builtinRowsJson   = null

    // Cancel any prior scheduled timeout before setting a new one so a stale timer
    // from a previous scan can never fire against the current one.
    unschedule("finalizeScanTimeout")
    runIn(SCAN_TIMEOUT_SECS, "finalizeScanTimeout")

    List<Map> ruleApps    = getRuleMachineRuleApps()
    List<Map> builtinApps = getBuiltinAppInstances()
    List<Map> combined    = ruleApps + builtinApps

    if (combined.isEmpty()) {
        unschedule("finalizeScanTimeout")
        state.scannedCount       = 0
        state.actionsOnCount     = 0
        state.eventsOnCount      = 0
        state.triggersOnCount    = 0
        state.anyLoggingOnCount  = 0
        state.privateBoolOnCount = 0
        state.disabledCount      = 0
        state.pausedCount        = 0
        state.lastScan           = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        state.scanDuration       = "00:00"
        state.biScannedCount     = 0
        state.biLogOnCount       = 0
        state.biLogOffCount      = 0
        state.biLogUnknownCount  = 0
        state.biDisabledCount    = 0
        state.biPausedCount      = 0
        state.builtinReportHtml  = ""
        state.reportHtml         = "<p>No Rule Machine, Button Controller, or supported built-in apps found.</p>"
        state.scanStatus         = null
        return
    }

    Long   nowMs         = now()
    String scanId        = nowMs.toString()
    String scanStartTime = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    List<Map> queue = combined.collect { Map r ->
        [id       : r.id                 as String,
         name     : r.name               as String,
         appType  : (r.appType ?: "RM")  as String,
         appClass : (r.appClass ?: "rm") as String,
         disabled : r.disabled           as Boolean,
         paused   : r.paused             as Boolean]
    }

    state.scanStatus = "<i>Scan started: ${scanStartTime} — scanning ${queue.size()} apps…</i>"

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
         appClass   : (first.appClass ?: "rm"),
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
        try {
            int httpStatus = resp.getStatus() as int
            if (httpStatus == 200) {
                Object raw = resp.getData()
                if (raw instanceof Map) {
                    status = raw as Map
                } else if (raw != null) {
                    status = new groovy.json.JsonSlurper().parseText(raw.toString()) as Map ?: [:]
                }
            } else {
                log.warn "HTTP ${httpStatus} for rule ${ruleId} (${data.ruleName})"
            }
        } catch (Exception e) {
            log.warn "Error parsing statusJson for rule ${ruleId}: ${e.message}"
        }

        if (scanPartialResults == null) scanPartialResults = [:]

        if ((data.appClass ?: "rm") == "builtin") {
            // Built-in app (Notifications, Basic Rules, Room Lighting, etc.)
            Boolean loggingOn = extractBuiltinLogging(status)
            if (debugEnable) log.debug "Builtin: ${data.ruleName} (${ruleId}, ${data.appType}) logging=${loggingOn}"
            scanPartialResults[ruleId] = [
                id       : ruleId,
                name     : data.ruleName,
                appType  : data.appType,
                appClass : "builtin",
                disabled : data.disabled,
                paused   : data.paused,
                logging  : loggingOn,
                lastRun  : extractLastRun(status)
            ]
        } else {
            // RM / BC rule
            Map     logging     = detectRuleLogging(status)
            Boolean actionsOn   = logging.actionsOn  as Boolean
            Boolean eventsOn    = logging.eventsOn   as Boolean
            Boolean triggersOn  = logging.triggersOn as Boolean
            // null = field absent (render as —); true/false = known state
            Boolean privateBool = extractPrivateBool(status)

            if (debugEnable && (actionsOn || eventsOn || triggersOn)) {
                log.debug "Logging ON: ${data.ruleName} (${ruleId}, ${data.appType}) Actions=${actionsOn}, Events=${eventsOn}, Triggers=${triggersOn}, PrivateBool=${privateBool}"
            } else if (debugEnable) {
                log.debug "Logging off: ${data.ruleName} (${ruleId}) PrivateBool=${privateBool}"
            }

            scanPartialResults[ruleId] = [
                id              : ruleId,
                name            : data.ruleName,
                appType         : data.appType,
                appClass        : "rm",
                disabled        : data.disabled,
                paused          : data.paused,
                actionsOn       : actionsOn,
                eventsOn        : eventsOn,
                triggersOn      : triggersOn,
                actionsField    : logging.actionsField,
                eventsField     : logging.eventsField,
                triggersField   : logging.triggersField,
                allLoggingField : logging.allLoggingField,
                lastRun         : extractLastRun(status),
                privateBool     : privateBool       // may be null
            ]
        }

    } catch (Exception e) {
        log.warn "handleStatusResponse error for rule ${ruleId} (${data.ruleName}): ${e.message}"
        if (scanPartialResults == null) scanPartialResults = [:]
        String errClass = (data.appClass ?: "rm") as String
        if (errClass == "builtin") {
            scanPartialResults[ruleId] = [
                id: ruleId, name: data.ruleName as String, appType: (data.appType ?: "") as String,
                appClass: "builtin", disabled: data.disabled as Boolean, paused: data.paused as Boolean,
                logging: null, lastRun: ""
            ]
        } else {
            scanPartialResults[ruleId] = [
                id: ruleId, name: data.ruleName as String, appType: (data.appType ?: "RM") as String,
                appClass: "rm", disabled: data.disabled as Boolean, paused: data.paused as Boolean,
                actionsOn: false, eventsOn: false, triggersOn: false,
                actionsField: null, eventsField: null, triggersField: null, allLoggingField: null,
                lastRun: "", privateBool: null
            ]
        }
    } finally {
        if (currentScanId != scanId) return   // scan was cancelled while we were processing

        int nextIdx    = (data.nextIdx    ?: 0) as int
        int totalRules = (data.totalRules ?: 0) as int

        if (debugEnable) log.debug "Completed ${nextIdx}/${totalRules}: ${data.ruleName} (${ruleId})"

        if (nextIdx < totalRules) {
            Map nextRule = scanRuleQueue[nextIdx]
            asynchttpGet("handleStatusResponse",
                [uri: RM_BASE_URL, path: "/installedapp/statusJson/${nextRule.id}", timeout: 60],
                [scanId     : currentScanId,
                 ruleId     : nextRule.id                 as String,
                 ruleName   : nextRule.name               as String,
                 appType    : (nextRule.appType ?: "RM")  as String,
                 appClass   : (nextRule.appClass ?: "rm") as String,
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

void finalizeScan() {
    unschedule("finalizeScanTimeout")

    List<Map> asyncRules     = scanRuleQueue      ?: []
    Map       partialResults = scanPartialResults ?: [:]

    List<Map> allRows = asyncRules.collect { Map rule ->
        Map row = partialResults[rule.id as String] as Map
        if (row) return row
        log.warn "No response for ${rule.id} (${rule.name}) — reporting as no logging"
        String ac = (rule.appClass ?: "rm") as String
        if (ac == "builtin") {
            return [id: rule.id as String, name: rule.name as String,
                    appType: (rule.appType ?: "") as String, appClass: "builtin",
                    disabled: rule.disabled as Boolean, paused: rule.paused as Boolean,
                    logging: null, lastRun: ""]
        }
        return [id: rule.id as String, name: rule.name as String,
                appType: (rule.appType ?: "RM") as String, appClass: "rm",
                disabled: rule.disabled as Boolean, paused: rule.paused as Boolean,
                actionsOn: false, eventsOn: false, triggersOn: false,
                actionsField: null, eventsField: null, triggersField: null, allLoggingField: null,
                lastRun: "", privateBool: null]
    }

    // Split into RM/BC rows and built-in app rows
    List<Map> rmRows           = allRows.findAll { (it.appClass ?: "rm") != "builtin" }
    List<Map> builtinRows      = allRows.findAll { (it.appClass ?: "rm") == "builtin" }

    Integer actionsOnCount     = rmRows.count { it.actionsOn   } as Integer
    Integer eventsOnCount      = rmRows.count { it.eventsOn    } as Integer
    Integer triggersOnCount    = rmRows.count { it.triggersOn  } as Integer
    Integer anyLoggingOnCount  = rmRows.count { (it.actionsOn || it.eventsOn || it.triggersOn) } as Integer
    Integer privateBoolOnCount = rmRows.count { it.privateBool == true } as Integer
    Integer disabledCount      = rmRows.count { it.disabled == true } as Integer
    Integer pausedCount        = rmRows.count { it.paused   == true } as Integer

    state.scannedCount        = rmRows.size()
    state.actionsOnCount      = actionsOnCount
    state.eventsOnCount       = eventsOnCount
    state.triggersOnCount     = triggersOnCount
    state.anyLoggingOnCount   = anyLoggingOnCount
    state.privateBoolOnCount  = privateBoolOnCount
    state.disabledCount       = disabledCount
    state.pausedCount         = pausedCount
    state.lastScan            = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    state.scanDuration        = formatScanDuration((now() as Long) - (scanStartMs ?: now() as Long))

    // Cache row data for both tables so updated() can re-render on settings change without rescan.
    try {
        state.scanRowsJson    = groovy.json.JsonOutput.toJson(rmRows)
        state.builtinRowsJson = groovy.json.JsonOutput.toJson(builtinRows)
    } catch (Exception e) {
        log.warn "finalizeScan: could not cache scan rows — ${e.message}"
        state.scanRowsJson    = null
        state.builtinRowsJson = null
    }

    state.biScannedCount      = builtinRows.size()
    state.biLogOnCount        = builtinRows.count { it.logging == true  } as Integer
    state.biLogOffCount       = builtinRows.count { it.logging == false } as Integer
    state.biLogUnknownCount   = builtinRows.count { it.logging == null  } as Integer
    state.biDisabledCount     = builtinRows.count { it.disabled == true } as Integer
    state.biPausedCount       = builtinRows.count { it.paused   == true } as Integer

    state.reportHtml          = buildReportHtml(rmRows)
    state.builtinReportHtml   = buildBuiltinReportHtml(builtinRows)
    state.scanStatus          = null

    // Release @Field memory and mark scan complete — cleared AFTER reportHtml is written
    // so the page keeps auto-refreshing until the report is ready.
    currentScanId      = null
    scanPartialResults = null
    scanRuleQueue      = null

    log.info "Scan complete in ${state.scanDuration}: ${rmRows.size()} RM/BC rules (any logging ON: ${anyLoggingOnCount}, Events: ${eventsOnCount}, Triggers: ${triggersOnCount}, Actions: ${actionsOnCount}, PB TRUE: ${privateBoolOnCount}); ${builtinRows.size()} built-in apps"
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

    return rules.sort { it.name?.toLowerCase() ?: "" }
}

// Recursively collect leaf nodes from the RM/BC app tree.
// Mirrors collectBuiltinLeafRules() but preserves the BC type-detection logic
// needed to correctly label Button Controller rules within an RM parent.
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
     * Basic Button Controller is intentionally excluded. It exposes only one broad
     * logging toggle rather than separate controls for Actions, Events, and/or Triggers.
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

// ============================================================
// Built-in app discovery
// ============================================================
// Detects top-level Hubitat built-in apps (Notifications, Basic Rules, Simple Automation Rules,
// Basic Button Controller, Room Lighting, Motion Lighting) that support a boolean "logging"
// setting. Unlike RM/BC rules these are parent apps, not children of Rule Machine, so they
// appear at the top level in /hub2/appsList.

List<Map> getBuiltinAppInstances() {
    List<Map> apps = []
    Set<String> seenIds = [] as Set

    try {
        httpGet([uri: RM_BASE_URL, path: "/hub2/appsList", contentType: "application/json"]) { resp ->
            resp.data?.apps?.each { parentApp ->
                def pd = parentApp?.data
                if (!pd) return
                String type    = pd?.type?.toString()  ?: ""
                String name    = pd?.name?.toString()  ?: ""
                String label   = pd?.label?.toString() ?: ""
                String appType = getBuiltinAppType(type, name, label)

                // Found a supported built-in parent — recursively collect leaf nodes.
                // Leaf nodes (no children) are the actual rules; intermediate nodes
                // (with children) are containers or groups to descend through.
                // This handles variable nesting depth across different app types —
                // e.g. Basic Button Controller has more tiers than Notifications.
                if (appType) {
                    parentApp?.children?.each { child ->
                        collectBuiltinLeafRules(child, appType, apps, seenIds, 0)
                    }
                }
            }
        }
    } catch (Exception e) {
        log.warn "getBuiltinAppInstances: could not read /hub2/appsList — ${e.message}"
    }

    return apps.sort { it.name?.toLowerCase() ?: "" }
}

// Recursively descend the app tree, collecting only leaf nodes (nodes with no children)
// as the actual rules. Intermediate container/group nodes are skipped.
// depth guard prevents runaway recursion on unexpectedly deep structures.
private void collectBuiltinLeafRules(Object node, String appType, List<Map> apps, Set<String> seenIds, int depth) {
    if (depth > 6) return
    List children = (node?.children ?: []) as List
    if (children.isEmpty()) {
        // Leaf — this is an actual rule
        def d = node?.data
        if (d?.id && d?.name) {
            String id = d.id.toString()
            if (!seenIds.contains(id)) {
                seenIds << id
                String ruleName = d.name.toString()
                apps << [
                    id       : id,
                    name     : ruleName,
                    appType  : appType,
                    appClass : "builtin",
                    disabled : asBooleanLoose(d.disabled),
                    paused   : ruleName.contains("(Paused)")
                ]
            }
        }
    } else {
        // Intermediate container — recurse into children
        children.each { child -> collectBuiltinLeafRules(child, appType, apps, seenIds, depth + 1) }
    }
}

// Returns a display-friendly app type name for supported built-in apps, or null if not recognised.
String getBuiltinAppType(String type, String name, String label = "") {
    String combined = [type, name, label].findAll { it }.join(" ").toLowerCase()
    if (!combined) return null
    // Avoid false positives — check more specific strings first
    if (combined.contains("room lighting")            || combined.contains("roomlighting"))          return "Room Lighting"
    // "Motion and Mode Lighting Apps" is the umbrella container in the hub's app list;
    // "motion lighting" catches any directly-named Motion Lighting instances.
    // Both map to the same appType since we only want Motion Lighting children.
    if (combined.contains("motion and mode lighting") || combined.contains("motionandmodelighting") ||
        combined.contains("motion lighting")          || combined.contains("motionlighting"))        return "Motion Lighting"
    if (combined.contains("simple automation")        || combined.contains("simpleautomation"))      return "Simple Automation Rules"
    if (combined.contains("basic button controller")  || combined.contains("basicbuttoncontroller")) return "Basic Button Controller"
    if ((combined.contains("basic rule")              || combined.contains("basicroomrule")) &&
        !combined.contains("button"))                                                                return "Basic Rule"
    if (combined.contains("notification")             && !combined.contains("button")        &&
        !combined.contains("rule"))                                                                  return "Notifications"
    return null
}

// ── Preference persistence endpoint ─────────────────────────────────────────
// Called by toggle-bar buttons via fetch() to persist their state without a
// page reload. Values are stored in state.userPrefs and read via getPref().
def handleSetPrefEndpoint() {
    if (!state.accessToken) { return renderJson([status: "error", message: "OAuth not active"]) }
    String key   = params?.key?.toString()
    String value = params?.value?.toString()
    if (!key) { return renderJson([status: "error", message: "missing key"]) }
    Map prefs = (state.userPrefs ?: [:]) as Map
    prefs[key] = value
    state.userPrefs = prefs
    return renderJson([status: "success"])
}

// Read a toggle-bar preference from state.userPrefs; returns defaultVal if not set.
boolean getPref(String key, boolean defaultVal = false) {
    Map prefs = (state.userPrefs ?: [:]) as Map
    if (prefs.containsKey(key)) return prefs[key]?.toString() == "true"
    return defaultVal
}


// ============================================================
// Report endpoint — printable HTML and CSV exports
// ============================================================
// GET /apps/api/{id}/report?access_token={token}&table={rm|builtin}
// Opens a self-contained printable HTML page using cached scan rows.
// CSV downloads use the dedicated /RM-BC_Rules.csv and /Built-In_Rules.csv endpoints.

def handleReportEndpoint() {
    if (!state.accessToken) {
        render contentType: "text/plain", data: "OAuth not active — re-open the app to retry."
        return
    }
    String table = (params?.table ?: "rm").toString().toLowerCase()
    String html = (table == "builtin") ? buildBuiltinPrintHtml() : buildRmPrintHtml()
    render contentType: "text/html; charset=UTF-8", data: html
}

// Dedicated CSV download endpoints — named paths give browsers the correct filename.
def handleRmCsvEndpoint() {
    if (!state.accessToken) { render contentType: "text/plain", data: "OAuth not active."; return }
    render contentType: "text/csv; charset=UTF-8", data: buildRmCsv()
}

def handleBuiltinCsvEndpoint() {
    if (!state.accessToken) { render contentType: "text/plain", data: "OAuth not active."; return }
    render contentType: "text/csv; charset=UTF-8", data: buildBuiltinCsv()
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
private String onOff(Boolean v) {
    if (v == null) return "—"
    return v ? "<span style='color:red;font-weight:bold'>ON</span>" : "<span style='color:green'>OFF</span>"
}
@CompileStatic
private String yesNo(Boolean v) {
    if (v == null) return "—"
    return v ? "<span style='color:red;font-weight:bold'>Yes</span>" : "<span style='color:green'>No</span>"
}
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
    ["Rule ID","Rule","App Type","Disabled","Paused","Events","Triggers","Actions","Private Bool","Last Run"].each {
        sb << "<th>${it}</th>"
    }
    sb << "</tr></thead><tbody>"
    rows.each { Map r ->
        sb << "<tr>"
        sb << "<td class='c'>${htmlEncode(r.id)}</td>"
        sb << "<td>${htmlEncode(r.name)}</td>"
        sb << "<td class='c'>${htmlEncode(r.appType ?: "")}</td>"
        sb << "<td class='c'>${yesNo(r.disabled as Boolean)}</td>"
        sb << "<td class='c'>${yesNo(r.paused   as Boolean)}</td>"
        String evCell = (r.appType?.toString() == "BC") ? "—" : onOff(r.eventsOn   as Boolean)
        sb << "<td class='c'>${evCell}</td>"
        sb << "<td class='c'>${onOff(r.triggersOn as Boolean)}</td>"
        sb << "<td class='c'>${onOff(r.actionsOn  as Boolean)}</td>"
        sb << "<td class='c'>${pbFmt(r.privateBool)}</td>"
        sb << "<td class='c'>${htmlEncode(r.lastRun ?: "")}</td>"
        sb << "</tr>"
    }
    sb << "</tbody></table>"

    String subtitle = "Last scan: ${state.lastScan ?: "never"} — ${rows.size()} rules"
    return printHtmlShell("Rule Machine and Button Controller Logging and State", subtitle, sb.toString())
}

// ── Built-in printable HTML ───────────────────────────────────────────────────
String buildBuiltinPrintHtml() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.builtinRowsJson ?: "[]") as List<Map> } catch (e) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "<table><thead><tr>"
    ["Rule ID","Rule","App Type","Disabled","Paused","Logging","Last Run"].each {
        sb << "<th>${it}</th>"
    }
    sb << "</tr></thead><tbody>"
    rows.each { Map r ->
        Boolean logVal = r.logging == null ? null : (r.logging as Boolean)
        String logFmt  = (logVal == null) ? "—" : logVal ? "<span style='color:red;font-weight:bold'>ON</span>" : "<span style='color:green'>OFF</span>"
        sb << "<tr>"
        sb << "<td class='c'>${htmlEncode(r.id)}</td>"
        sb << "<td>${htmlEncode(r.name)}</td>"
        sb << "<td class='c'>${htmlEncode(r.appType ?: "")}</td>"
        sb << "<td class='c'>${yesNo(r.disabled as Boolean)}</td>"
        sb << "<td class='c'>${yesNo(r.paused   as Boolean)}</td>"
        sb << "<td class='c'>${logFmt}</td>"
        sb << "<td class='c'>${htmlEncode(r.lastRun ?: "")}</td>"
        sb << "</tr>"
    }
    sb << "</tbody></table>"

    String subtitle = "Last scan: ${state.lastScan ?: "never"} — ${rows.size()} apps"
    return printHtmlShell("Built-in App Logging", subtitle, sb.toString())
}

// ── RM/BC CSV ─────────────────────────────────────────────────────────────────
String buildRmCsv() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (e) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "Rule ID,Rule,App Type,Disabled,Paused,Events,Triggers,Actions,Private Bool,Last Run\n"
    rows.each { Map r ->
        String ev = (r.appType?.toString() == "BC") ? "—" : (r.eventsOn == null ? "—" : (r.eventsOn as Boolean) ? "ON" : "OFF")
        sb << "${escapeCsv(r.id)},${escapeCsv(r.name)},${escapeCsv(r.appType)}"
        sb << ",${r.disabled ? "Yes" : "No"},${r.paused ? "Yes" : "No"}"
        sb << ",${ev}"
        sb << ",${r.triggersOn == null ? "—" : (r.triggersOn as Boolean) ? "ON" : "OFF"}"
        sb << ",${r.actionsOn  == null ? "—" : (r.actionsOn  as Boolean) ? "ON" : "OFF"}"
        sb << ",${r.privateBool == null ? "—" : (r.privateBool as Boolean) ? "TRUE" : "FALSE"}"
        sb << ",${escapeCsv(r.lastRun)}\n"
    }
    return sb.toString()
}

// ── Built-in CSV ──────────────────────────────────────────────────────────────
String buildBuiltinCsv() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.builtinRowsJson ?: "[]") as List<Map> } catch (e) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "Rule ID,Rule,App Type,Disabled,Paused,Logging,Last Run\n"
    rows.each { Map r ->
        Boolean logVal = r.logging == null ? null : (r.logging as Boolean)
        String  logStr = logVal == null ? "—" : logVal ? "ON" : "OFF"
        sb << "${escapeCsv(r.id)},${escapeCsv(r.name)},${escapeCsv(r.appType)}"
        sb << ",${r.disabled ? "Yes" : "No"},${r.paused ? "Yes" : "No"}"
        sb << ",${logStr},${escapeCsv(r.lastRun)}\n"
    }
    return sb.toString()
}

// ============================================================
// Logging detection
// ============================================================

Map detectRuleLogging(Map status) {
    /*
     * We check multiple places because Hubitat/RM internals can vary by version.
     * Common field names are expected to be things like:
     *   logActions      = true
     *   logEvents       = true
     *   logTriggers     = true
     *
     * This also tries to catch variants such as:
     *   actionsLogging  = true
     *   eventsLogging   = true
     *   triggersLogging = true
     *   logging         = ["Actions", "Events", "Triggers"]
     *   logAll          = true
     */

    List<Map> candidates = []

    collectCandidatesFromObject("appSettings", status?.appSettings, candidates)
    collectCandidatesFromObject("settings",    status?.settings,    candidates)

    Map allResult        = detectAllLogging(candidates)
    Boolean allLoggingOn = allResult.detected as Boolean

    Map actionsResult    = detectSpecificLogging(candidates, "actions",  ["action",  "actions"])
    Map eventsResult     = detectSpecificLogging(candidates, "events",   ["event",   "events"])
    Map triggersResult   = detectSpecificLogging(candidates, "triggers", ["trigger", "triggers"])

    Map result = [
        actionsOn       : allLoggingOn || (actionsResult.matched  as Boolean),
        eventsOn        : allLoggingOn || (eventsResult.matched   as Boolean),
        triggersOn      : allLoggingOn || (triggersResult.matched as Boolean),
        actionsField    : actionsResult.fieldName  as String,
        eventsField     : eventsResult.fieldName   as String,
        triggersField   : triggersResult.fieldName as String,
        allLoggingField : allResult.fieldName      as String
    ]

    // When no field names were resolved, log candidates (only when debug logging is enabled)
    if (debugEnable && !result.actionsField && !result.eventsField && !result.triggersField && !result.allLoggingField) {
        List<String> logCandidates = candidates
            .findAll { String k = it.name?.toString()?.toLowerCase() ?: ""; k.contains("log") || k.contains("debug") }
            .collect { "${it.source}/${it.name}=${it.value}" }
        if (logCandidates) {
            log.debug "detectRuleLogging: no logging fields found — log-related candidates: ${logCandidates}"
        } else {
            log.debug "detectRuleLogging: no logging fields found — all candidates: ${candidates.collect { "${it.source}/${it.name}=${it.value}" }}"
        }
    }

    return result
}

Map detectAllLogging(List<Map> candidates) {
    Set<String> exactMatches = ["alllogging", "logall", "logsall"] as Set
    String disabledFieldName = null
    for (Map c : candidates) {
        String key = c.name?.toString() ?: ""
        String k   = key.toLowerCase()
        if (k in exactMatches || (k.contains("log") && k.contains("all"))) {
            String fieldName = key.contains(".") ? key.tokenize(".").last() : key
            if (valueLooksEnabled(c.value)) return [detected: true, fieldName: fieldName]
            if (!disabledFieldName) disabledFieldName = fieldName
        }
    }
    return [detected: false, fieldName: disabledFieldName]
}

Map detectSpecificLogging(List<Map> candidates, String canonicalName, List<String> needles) {
    Set<String> exactKeys    = ["log${canonicalName}", "${canonicalName}log", "${canonicalName}logging", "logging${canonicalName}"] as Set
    Set<String> generalKeys  = ["logging", "logs", "log", "logoptions", "loggingoptions", "logsettings", "logsetting"] as Set
    String disabledFieldName = null

    for (Map c : candidates) {
        String key = c.name?.toString() ?: ""
        String k   = key.toLowerCase()
        String v   = c.value?.toString()?.toLowerCase() ?: ""

        Boolean keyNamesThisLogging = (k in exactKeys) || needles.any { String n ->
            (k.contains("log") && k.contains(n)) || (k.contains(n) && k.contains("logging"))
        }

        if (keyNamesThisLogging) {
            String fieldName = key.contains(".") ? key.tokenize(".").last() : key
            if (valueLooksEnabled(c.value)) return [matched: true, fieldName: fieldName]
            if (!disabledFieldName) disabledFieldName = fieldName
        }

        if (k in generalKeys) {
            String fieldName = key.contains(".") ? key.tokenize(".").last() : key
            if (!disabledFieldName) disabledFieldName = fieldName
            if (needles.any { String n -> v.contains(n) } && !valueLooksDisabled(c.value)) {
                return [matched: true, fieldName: fieldName]
            }
        }
    }

    return [matched: false, fieldName: disabledFieldName]
}

void collectCandidatesFromObject(String source, Object obj, List<Map> candidates) {
    collectCandidatesFromObject(source, obj, candidates, "", 0)
}

void collectCandidatesFromObject(String source, Object obj, List<Map> candidates, String prefix, int depth) {
    // Depth cap: RM/BC statusJson nesting is typically ≤ 3 levels; 4 is a safe ceiling that prevents
    // runaway recursion on unexpectedly deep structures.
    if (obj == null || depth > 4) return

    if (obj instanceof Map) {
        obj.each { k, v ->
            String name = prefix ? "${prefix}.${k?.toString()}" : k?.toString()
            candidates << [source: source, name: name, value: v]
            if (v instanceof Map || v instanceof Collection) {
                collectCandidatesFromObject(source, v, candidates, name, depth + 1)
            }
        }
        return
    }

    if (obj instanceof Collection) {
        Integer idx = 0
        obj.each { row ->
            String name = prefix ? "${prefix}[${idx}]" : "[${idx}]"
            if (row instanceof Map) {
                Object rowName = row.name ?: row.key ?: row.id ?: row.label ?: name
                candidates << [source: source, name: rowName?.toString(), value: row.value]
                collectCandidatesFromObject(source, row, candidates, name, depth + 1)
            } else {
                candidates << [source: source, name: name, value: row]
            }
            idx++
        }
    }
}

@CompileStatic
Boolean valueLooksEnabled(Object value) {
    if (value == null) return false
    if (value instanceof Boolean) return (Boolean) value
    if (value instanceof Collection) return !(value as Collection).isEmpty()
    String v = value.toString().trim().toLowerCase()
    return v in ["true", "on", "yes", "enabled", "enable", "1"]
}

@CompileStatic
Boolean valueLooksDisabled(Object value) {
    if (value == null) return true
    if (value instanceof Boolean) return !(Boolean) value
    if (value instanceof Collection) return (value as Collection).isEmpty()
    String v = value.toString().trim().toLowerCase()
    return v in ["false", "off", "no", "disabled", "disable", "0", "null", ""]
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

// Returns true/false when the "private" field is present in appState,
// or null when the field is absent (status unreadable or rule returned no appState).
// Callers should treat null as unknown, not as false.
Boolean extractPrivateBool(Map status) {
    for (Map item : (status?.appState ?: [])) {
        if (item?.name?.toString() == "private") {
            return asBooleanLoose(item?.value)
        }
    }
    return null
}

// Reads the "logging" boolean setting from built-in apps (Notifications, Basic Rules,
// Simple Automation Rules, Basic Button Controller, Room Lighting, Motion Lighting).
// These apps use a simple boolean field named "logging" in their settings.
// Returns true/false when the field is found, null when absent or status unreadable.
Boolean extractBuiltinLogging(Map status) {
    // Iterate appSettings first then settings — two heterogeneous sources, same field shape
    for (Object source : [status?.appSettings, status?.settings]) {
        if (source instanceof Map) {
            if (source.containsKey("logging")) {
                return asBooleanLoose(source.logging)
            }
        } else if (source instanceof Collection) {
            for (Map item : source) {
                if (item?.name?.toString() == "logging") {
                    return asBooleanLoose(item?.value)
                }
            }
        }
    }
    return null
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
// built-in table has sortRmLogTable, wildcardToRegex, rmToggle*, etc. available
// regardless of whether there are any RM/BC rules to display.
String buildSharedReportAssets(String pbEndpoint, String prefEndpoint = "") {
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
    sb << "</style>"

    // Embed both endpoint URLs as JS variables using JsonOutput for safe token escaping.
    sb << "<script>var rmPbEndpoint = ${groovy.json.JsonOutput.toJson(pbEndpoint ?: null)}; var rmPrefEndpoint = ${groovy.json.JsonOutput.toJson(prefEndpoint ?: null)};</script>"

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

// Column hide toggle — operates only on column elements, not rows.
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

// Row filter helpers — evaluate ALL active row filters together so that a row
// belonging to multiple hidden categories (e.g. disabled AND no-logging) stays
// hidden when only one of those filters is toggled off.
function isHiddenButton(id) {
    var b = document.getElementById(id);
    return b && b.className.indexOf('hidden-col') !== -1;
}

// Convert a wildcard pattern (* = any chars, ? = any single char) to a RegExp.
// Plain filter text is handled separately as a case-insensitive substring match.
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

function applyRmRowFilters() {
    var hideDisabled = isHiddenButton('rmtoggle-rmrow-disabled');
    var hidePaused   = isHiddenButton('rmtoggle-rmrow-paused');
    var hideLogOff   = isHiddenButton('rmtoggle-rmrow-logoff');
    var filterEl  = document.getElementById('rmname-filter');
    var filterVal = filterEl ? filterEl.value.trim() : '';
    var filterRe  = null;
    var hasWild   = filterVal.indexOf('*') >= 0 || filterVal.indexOf('?') >= 0;
    var lowerSub  = '';
    if (filterVal) {
        if (hasWild) {
            // Wildcard pattern — full-string match with * and ? expansion.
            try { filterRe = wildcardToRegex(filterVal); } catch(e) { filterRe = null; }
        } else {
            // Plain text — case-insensitive substring match; no wildcards needed.
            lowerSub = filterVal.toLowerCase();
        }
    }
    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(tr) {
        var hide =
            (hideDisabled && tr.classList.contains('rmrow-disabled')) ||
            (hidePaused   && tr.classList.contains('rmrow-paused'))   ||
            (hideLogOff   && tr.classList.contains('rmrow-logoff'));
        if (!hide && filterVal) {
            var nameCell = tr.querySelectorAll('td')[1];
            var nm = nameCell ? (nameCell.getAttribute('data-sort') || nameCell.textContent || '').trim() : '';
            if      (filterRe) { hide = !filterRe.test(nm); }
            else if (lowerSub) { hide = nm.toLowerCase().indexOf(lowerSub) < 0; }
        }
        tr.style.display = hide ? 'none' : '';
    });
}

// Recalculate whether a row belongs to the "no logging ON" category after a
// logging cell has been toggled in-place. Checks the current data-sort value of
// each logging column cell (1 = ON, anything else = OFF).
function updateRmLogOffClass(tr) {
    if (!tr) return;
    function colOn(cls) {
        var cell = tr.querySelector('.' + cls);
        return cell && cell.getAttribute('data-sort') === '1';
    }
    var anyLoggingOn =
        colOn('rmcol-actions') ||
        colOn('rmcol-events')  ||
        colOn('rmcol-triggers');
    if (anyLoggingOn) tr.classList.remove('rmrow-logoff');
    else              tr.classList.add('rmrow-logoff');
}

// Generic helper: adjust a stats span by +1 (increment=true) or -1 (increment=false).
function adjustStatEl(id, increment) {
    var el = document.getElementById(id);
    if (el) el.textContent = (parseInt(el.textContent, 10) || 0) + (increment ? 1 : -1);
}

// Called after a successful RM/BC logging toggle.
// Column counts (Events/Triggers/Actions) are adjusted by ±1.
// anyLogging is recounted directly from the DOM: all tbody cells in the three
// logging columns that have data-sort="1" are collected, their parent rows
// deduplicated via a Set, and the resulting count written to the span.
// At call time td.setAttribute('data-sort', ...) has already been called, so
// the toggled cell already reflects its new state.
function updateRmStatsForLogging(tr, td, newOn) {
    var cls = td.className || '';
    var isEvents   = cls.indexOf('rmcol-events')   >= 0;
    var isTriggers = cls.indexOf('rmcol-triggers') >= 0;
    var isActions  = cls.indexOf('rmcol-actions')  >= 0;
    if (isEvents)   adjustStatEl('rmstat-events',   newOn);
    if (isTriggers) adjustStatEl('rmstat-triggers', newOn);
    if (isActions)  adjustStatEl('rmstat-actions',  newOn);
    // Update this row's rmrow-logoff class from its current cell state.
    function cellIsOn(row, colCls) {
        var c = row.querySelector('.' + colCls);
        return c && c.getAttribute('data-sort') === '1';
    }
    var anyNow = cellIsOn(tr, 'rmcol-events') || cellIsOn(tr, 'rmcol-triggers') || cellIsOn(tr, 'rmcol-actions');
    if (anyNow) tr.classList.remove('rmrow-logoff');
    else        tr.classList.add('rmrow-logoff');
    // Recount anyLogging: unique tbody rows that have at least one logging cell ON.
    var el = document.getElementById('rmstat-anylogging');
    if (!el) return;
    var rowsWithLogging = new Set();
    ['rmcol-events', 'rmcol-triggers', 'rmcol-actions'].forEach(function(colCls) {
        document.querySelectorAll('#rmlog_table tbody .' + colCls + '[data-sort="1"]').forEach(function(cell) {
            var row = cell.closest('tr');
            if (row) rowsWithLogging.add(row);
        });
    });
    el.textContent = rowsWithLogging.size;
}

// Called after a successful PB toggle.
function updateRmStatsForPb(newOn) {
    adjustStatEl('rmstat-pb', newOn);
}

function toggleRmRowFilter(btn) {
    var hiding = btn.className.indexOf('hidden-col') === -1;
    btn.className = hiding ? 'rmcol-btn hidden-col' : 'rmcol-btn';
    applyRmRowFilters();
    persistPref(btn.dataset.prefKey, String(hiding));
}

async function rmToggleLogging(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var ruleId = td.dataset.ruleId, fieldName = td.dataset.field, fieldType = td.dataset.fieldType,
        enumOption = td.dataset.enumOption, currentOn = td.dataset.on === 'true', newOn = !currentOn;
    try {
        var cfgResp = await fetch('/installedapp/configure/json/' + ruleId);
        if (!cfgResp.ok) throw new Error('configure/json HTTP ' + cfgResp.status);
        var config = await cfgResp.json();
        var appInfo = config.app || {}, configPage = config.configPage || {}, settings = config.settings || {};
        var pageName = configPage.name || 'mainPage', appLabel = appInfo.label || '';
        var sections = configPage.sections || [];
        var fd = new URLSearchParams();
        fd.set('_action_update', 'Done');
        fd.set('formAction', 'update');
        fd.set('id', ruleId);
        fd.set('version', String(appInfo.version || '1'));
        fd.set('appTypeId', '');
        fd.set('appTypeName', '');
        fd.set('currentPage', pageName);
        fd.set('pageBreadcrumbs', '[]');
        sections.forEach(function(sec) {
            (sec.body || []).forEach(function(elem) {
                if (elem.element === 'label') {
                    var ln = elem.name || 'label';
                    fd.set(ln + '.type', 'text');
                    fd.set(ln, appLabel);
                }
            });
        });
        sections.forEach(function(sec) {
            (sec.input || []).forEach(function(inp) {
                var name = inp.name, type = inp.type || '', multiple = !!inp.multiple;
                fd.set(name + '.type', type);
                fd.set(name + '.multiple', String(multiple));
                if (name === fieldName) {
                    if (fieldType === 'bool') {
                        if (newOn) fd.set('checkbox[' + name + ']', 'on');
                        fd.set('settings[' + name + ']', String(newOn));
                    } else {
                        // enum-multiple: add or remove just this option from the current list
                        var arr = settings[name];
                        if (typeof arr === 'string') { try { arr = JSON.parse(arr); } catch(e) { arr = []; } }
                        if (!Array.isArray(arr)) arr = arr ? [String(arr)] : [];
                        if (newOn) { if (!arr.includes(enumOption)) arr.push(enumOption); }
                        else { arr = arr.filter(function(x) { return x !== enumOption; }); }
                        fd.set('settings[' + name + ']', JSON.stringify(arr));
                    }
                } else {
                    var cur = settings[name];
                    if (type === 'bool') {
                        var bv = cur === true || cur === 'true';
                        if (bv) fd.set('checkbox[' + name + ']', 'on');
                        fd.set('settings[' + name + ']', String(bv));
                    } else if (type.startsWith('capability.')) {
                        var ids = (cur && typeof cur === 'object' && !Array.isArray(cur))
                            ? Object.keys(cur).join(',')
                            : (cur != null ? String(cur) : '');
                        fd.append('settings[' + name + ']', ids);
                        fd.append('deviceList', name);
                        fd.append('', '');   // Hubitat sentinel to delimit device-list entries
                    } else {
                        var sv = cur == null ? '' : (typeof cur === 'object' ? JSON.stringify(cur) : String(cur));
                        fd.set('settings[' + name + ']', sv);
                    }
                }
            });
        });
        fd.set('referrer', window.location.origin + '/installedapp/list');
        fd.set('url', window.location.origin + '/installedapp/configure/' + ruleId + '/' + pageName);
        fd.set('_cancellable', 'false');
        var postResp = await fetch('/installedapp/update/json', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: fd.toString()
        });
        if (!postResp.ok) throw new Error('update/json HTTP ' + postResp.status);
        var result = await postResp.json();
        if (result.status !== 'success') throw new Error(result.message || JSON.stringify(result));
        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn ? "<span style='color:red;font-weight:bold;'>ON</span>"
                             : "<span style='color:green;font-weight:bold;'>OFF</span>";
        var tr = td.closest('tr');
        if (tr && tr.closest('#builtin_table')) {
            updateBiLogOffClass(tr);
            applyBiRowFilters();
            adjustStatEl('bistat-logon',  newOn);
            adjustStatEl('bistat-logoff', !newOn);
        } else {
            updateRmStatsForLogging(tr, td, newOn);
            applyRmRowFilters();
        }
    } catch(e) {
        alert('Toggle failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}

async function rmToggleDisabled(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var ruleId = td.dataset.ruleId, newOn = td.dataset.on !== 'true';
    try {
        var resp = await fetch('/installedapp/disable', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({ id: ruleId, disable: String(newOn) }).toString()
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        var result = await resp.json();
        if (result.result !== newOn) throw new Error(JSON.stringify(result));
        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn ? "<span style='color:red;font-weight:bold;'>Yes</span>"
                             : "<span style='color:green;font-weight:bold;'>No</span>";
        var tr = td.closest('tr');
        if (tr && tr.closest('#builtin_table')) {
            if (newOn) tr.classList.add('birow-disabled'); else tr.classList.remove('birow-disabled');
            applyBiRowFilters();
            adjustStatEl('bistat-disabled', newOn);
        } else {
            if (newOn) tr.classList.add('rmrow-disabled'); else tr.classList.remove('rmrow-disabled');
            applyRmRowFilters();
            adjustStatEl('rmstat-disabled', newOn);
        }
    } catch(e) {
        alert('Toggle disabled failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}

async function rmTogglePaused(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var ruleId = td.dataset.ruleId, newOn = td.dataset.on !== 'true';
    var tr = td.closest('tr');
    var isBuiltin = tr && tr.closest('#builtin_table');
    try {
        var btnName = 'pausRule';   // default for RM/BC rules
        var cfg = null;             // will hold configure/json response for builtin rows

        if (isBuiltin) {
            // Built-in apps use a button name that varies by app type — discover it
            // from configure/json. We also keep cfg for the no-op save below.
            var cfgResp = await fetch('/installedapp/configure/json/' + ruleId);
            if (!cfgResp.ok) throw new Error('configure/json HTTP ' + cfgResp.status);
            cfg = await cfgResp.json();
            var foundBtn = null;
            (cfg.configPage?.sections || []).forEach(function(sec) {
                (sec.input || []).forEach(function(inp) {
                    if (!foundBtn && inp.type === 'button') {
                        var t = (inp.title || '').toLowerCase();
                        if (t.indexOf('pause') >= 0 || t.indexOf('resume') >= 0) {
                            foundBtn = inp.name;
                        }
                    }
                });
            });
            if (!foundBtn) throw new Error('Could not find pause button in configure/json for app ' + ruleId);
            btnName = foundBtn;
        }

        var fd = new URLSearchParams();
        fd.set('id', ruleId);
        fd.set('name', btnName);
        fd.set('settings[' + btnName + ']', 'clicked');
        fd.set(btnName + '.type', 'button');
        var resp = await fetch('/installedapp/btn', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'XMLHttpRequest' },
            body: fd.toString()
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        var result = await resp.json();
        if (result.status !== 'success') throw new Error(result.message || JSON.stringify(result));

        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn ? "<span style='color:red;font-weight:bold;'>Yes</span>"
                             : "<span style='color:green;font-weight:bold;'>No</span>";

        if (isBuiltin) {
            if (newOn) tr.classList.add('birow-paused'); else tr.classList.remove('birow-paused');
            applyBiRowFilters();
            adjustStatEl('bistat-paused', newOn);

            // No-op save: re-POST existing settings unchanged so Hubitat updates
            // the app label server-side (appending/removing "(Paused)"), making the
            // change visible on the Automations page without opening the rule.
            // Uses cfg already fetched above — no extra round-trip.
            // Wrapped in its own try-catch: if this fails the pause already succeeded.
            if (cfg) {
                try {
                    var appInfo    = cfg.app        || {};
                    var configPage = cfg.configPage || {};
                    var settings   = cfg.settings   || {};
                    var pageName   = configPage.name || 'mainPage';
                    var sections   = configPage.sections || [];
                    var nfd = new URLSearchParams();
                    nfd.set('_action_update', 'Done');
                    nfd.set('formAction', 'update');
                    nfd.set('id', ruleId);
                    nfd.set('version', String(appInfo.version || '1'));
                    nfd.set('appTypeId', '');
                    nfd.set('appTypeName', '');
                    nfd.set('currentPage', pageName);
                    nfd.set('pageBreadcrumbs', '[]');
                    nfd.set('_cancellable', 'false');
                    nfd.set('referrer', window.location.origin + '/installedapp/list');
                    nfd.set('url', window.location.origin + '/installedapp/configure/' + ruleId + '/' + pageName);
                    sections.forEach(function(sec) {
                        (sec.body || []).forEach(function(elem) {
                            if (elem.element === 'label') {
                                var ln = elem.name || 'label';
                                nfd.set(ln + '.type', 'text');
                                nfd.set(ln, appInfo.label || '');
                            }
                        });
                    });
                    sections.forEach(function(sec) {
                        (sec.input || []).forEach(function(inp) {
                            var name = inp.name, type = inp.type || '', multiple = !!inp.multiple;
                            if (type === 'button') return;   // skip button inputs
                            nfd.set(name + '.type', type);
                            nfd.set(name + '.multiple', String(multiple));
                            var cur = settings[name];
                            if (type === 'bool') {
                                var bv = cur === true || cur === 'true';
                                if (bv) nfd.set('checkbox[' + name + ']', 'on');
                                nfd.set('settings[' + name + ']', String(bv));
                            } else if (type.startsWith('capability.')) {
                                var ids = (cur && typeof cur === 'object' && !Array.isArray(cur))
                                    ? Object.keys(cur).join(',') : (cur != null ? String(cur) : '');
                                nfd.set('settings[' + name + ']', ids);
                                nfd.set('deviceList', name);
                            } else {
                                var sv = cur == null ? '' : (typeof cur === 'object' ? JSON.stringify(cur) : String(cur));
                                nfd.set('settings[' + name + ']', sv);
                            }
                        });
                    });
                    await fetch('/installedapp/update/json', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                        body: nfd.toString()
                    });
                    // Result not checked — label update is cosmetic; pause already succeeded.
                } catch(noopErr) {
                    console.warn('No-op save for label update failed (non-critical):', noopErr.message);
                }
            }
        } else {
            if (newOn) tr.classList.add('rmrow-paused'); else tr.classList.remove('rmrow-paused');
            applyRmRowFilters();
            adjustStatEl('rmstat-paused', newOn);
        }
    } catch(e) {
        alert('Toggle paused failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}

// Stubs for built-in table functions — real implementations emitted by buildBuiltinReportHtml.
// Defined here so rmToggleLogging/rmToggleDisabled can call them before the builtin HTML loads.
function updateBiLogOffClass(tr) {}
function applyBiRowFilters() {}

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
        updateRmStatsForPb(newOn);
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
    // Compute pbEndpoint before the early-return check so buildSharedReportAssets
    // always receives it even when rows is empty.
    // Build the local OAuth endpoint URL for PB toggling (relative — no hub IP).
    // The access token is embedded in the rendered HTML so the JS click handler can call it.
    // The token is already scoped to this app and only works on the local network.
    String pbEndpoint   = ""
    String prefEndpoint = ""
    if (state.accessToken) {
        pbEndpoint   = "/apps/api/${app.id}/setPB?access_token=${state.accessToken}"
        prefEndpoint = "/apps/api/${app.id}/setpref?access_token=${state.accessToken}"
    } else {
        log.warn "buildReportHtml: no access token — PB cells will render as non-clickable. Re-save the app to generate a token."
    }

    StringBuilder sb = new StringBuilder()
    sb << buildSharedReportAssets(pbEndpoint, prefEndpoint)

    if (!rows) {
        sb << "<p>No rules found. Click <b>Scan All Rules</b> to begin.</p>"
        return sb.toString()
    }


    // Derive initial button classes from settings
    // Read custom visibility settings — defaults match original behaviour (only No logging ON hidden)
    boolean cfgHideRowDisabled = getPref("hideRowDisabled", false)
    boolean cfgHideRowPaused   = getPref("hideRowPaused",   false)
    boolean cfgHideRowLogOff   = getPref("hideRowLogOff",   true)
    boolean cfgHideColRuleId   = getPref("hideColRuleId",   false)
    boolean cfgHideColAppType  = getPref("hideColAppType",  false)
    boolean cfgHideColDisabled = getPref("hideColDisabled", false)
    boolean cfgHideColPaused   = getPref("hideColPaused",   false)
    boolean cfgHideColActions  = getPref("hideColActions",  false)
    boolean cfgHideColEvents   = getPref("hideColEvents",   false)
    boolean cfgHideColTriggers = getPref("hideColTriggers", false)
    boolean cfgHideColPB       = getPref("hideColPB",       false)
    boolean cfgHideColLastRun  = getPref("hideColLastRun",  false)

    String btnRowDisabled = cfgHideRowDisabled  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnRowPaused   = cfgHideRowPaused    ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnRowLogOff   = cfgHideRowLogOff    ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColRuleId   = cfgHideColRuleId    ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColAppType  = cfgHideColAppType   ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColDisabled = cfgHideColDisabled  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColPaused   = cfgHideColPaused    ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColActions  = cfgHideColActions   ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColEvents   = cfgHideColEvents    ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColTriggers = cfgHideColTriggers  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColPB       = cfgHideColPB        ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColLastRun  = cfgHideColLastRun   ? "rmcol-btn hidden-col" : "rmcol-btn"

    sb << "<div class='rmcol-toggle-bar'>"
    sb << "<b>Hide rows:</b>&nbsp;"
    // Row buttons use toggleRmRowFilter() so multiple active filters evaluate together
    sb << "<span id='rmtoggle-rmrow-disabled' class='${btnRowDisabled}' data-pref-key='hideRowDisabled' onclick=\"toggleRmRowFilter(this)\">Disabled rules</span>"
    sb << "<span id='rmtoggle-rmrow-paused'   class='${btnRowPaused}'   data-pref-key='hideRowPaused' onclick=\"toggleRmRowFilter(this)\">Paused rules</span>"
    sb << "<span id='rmtoggle-rmrow-logoff'   class='${btnRowLogOff}'   data-pref-key='hideRowLogOff' onclick=\"toggleRmRowFilter(this)\">No logging ON</span>"
    sb << "&nbsp;&nbsp;<b>Hide columns:</b>&nbsp;"
    sb << "<span id='rmtoggle-rmcol-ruleid'   class='${btnColRuleId}'   data-pref-key='hideColRuleId' onclick=\"toggleRmCol('rmcol-ruleid',this)\">Rule ID</span>"
    sb << "<span id='rmtoggle-rmcol-apptype'  class='${btnColAppType}'  data-pref-key='hideColAppType' onclick=\"toggleRmCol('rmcol-apptype',this)\">App Type</span>"
    sb << "<span id='rmtoggle-rmcol-disabled' class='${btnColDisabled}' data-pref-key='hideColDisabled' onclick=\"toggleRmCol('rmcol-disabled',this)\">Disabled</span>"
    sb << "<span id='rmtoggle-rmcol-paused'   class='${btnColPaused}'   data-pref-key='hideColPaused' onclick=\"toggleRmCol('rmcol-paused',this)\">Paused</span>"
    sb << "<span id='rmtoggle-rmcol-events'   class='${btnColEvents}'   data-pref-key='hideColEvents' onclick=\"toggleRmCol('rmcol-events',this)\">Events</span>"
    sb << "<span id='rmtoggle-rmcol-triggers' class='${btnColTriggers}' data-pref-key='hideColTriggers' onclick=\"toggleRmCol('rmcol-triggers',this)\">Triggers</span>"
    sb << "<span id='rmtoggle-rmcol-actions'  class='${btnColActions}'  data-pref-key='hideColActions' onclick=\"toggleRmCol('rmcol-actions',this)\">Actions</span>"
    sb << "<span id='rmtoggle-rmcol-pb'       class='${btnColPB}'       data-pref-key='hideColPB' onclick=\"toggleRmCol('rmcol-pb',this)\">Private Bool</span>"
    sb << "<span id='rmtoggle-rmcol-lastrun'  class='${btnColLastRun}'  data-pref-key='hideColLastRun' onclick=\"toggleRmCol('rmcol-lastrun',this)\">Last Run</span>"
    sb << "&nbsp;&nbsp;<b>Filter:</b>&nbsp;"
    sb << "<input id='rmname-filter' type='text' class='rmname-filter' placeholder='Rule name (substring or * ? wildcards)' oninput='applyRmRowFilters()' style='width:300px;'>"
    sb << "</div>"

    sb << "<table id='rmlog_table' class='rmlogcheck'><thead><tr>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',0)\" class='center rmcol-ruleid'>Rule ID</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',1)\" class='sort-asc'>Rule</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',2)\" class='center rmcol-apptype'>App Type</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',3)\" class='center rmcol-disabled'>Disabled</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',4)\" class='center rmcol-paused'>Paused</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',5)\" class='center rmcol-events'>Events</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',6)\" class='center rmcol-triggers'>Triggers</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',7)\" class='center rmcol-actions'>Actions</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',8)\" class='center rmcol-pb'>Private Bool</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',9)\" class='center rmcol-lastrun'>Last Run</th>"
    sb << "</tr></thead><tbody>"

    rows.each { Map r ->
        String id          = htmlEncode(r.id)
        String nameSort    = htmlEncode(r.name?.toString()?.replaceAll(/<[^>]+>/, '') ?: "")
        String nameHtml    = renderNameHtml(r.name)
        String appType     = htmlEncode(r.appType ?: "RM")
        boolean isBC       = (r.appType == "BC")
        String disabledFmt = formatYesNo(r.disabled   as Boolean)
        String pausedFmt   = formatYesNo(r.paused     as Boolean)
        String actionsFmt  = formatOnOff(r.actionsOn  as Boolean)
        String eventsFmt   = formatOnOff(r.eventsOn   as Boolean)
        String triggersFmt = formatOnOff(r.triggersOn as Boolean)

        // BC rules have no Events logging option — override cell to non-clickable "—"
        // and exclude eventsOn from anyOn so the No logging ON filter is not confused.
        Boolean eventsApplicable = !isBC
        Boolean anyOn  = (r.actionsOn as Boolean) ||
                         (eventsApplicable && (r.eventsOn as Boolean)) ||
                         (r.triggersOn as Boolean)

        // privateBool: null = unknown (field absent), true/false = known state
        Boolean pbVal  = r.privateBool == null ? null : (r.privateBool as Boolean)
        String  pbFmt  = (pbVal == null)  ? "<span style='color:#999;'>—</span>"
                       : pbVal            ? "<span style='color:blue;font-weight:bold;'>TRUE</span>"
                                          : "<span style='color:#aaa;'>FALSE</span>"
        String pbSort  = (pbVal == true) ? "2" : (pbVal == false) ? "1" : "0"

        String lastRun = htmlEncode(r.lastRun ?: "")

        List<String> trClasses = []
        if (r.disabled as Boolean) trClasses << "rmrow-disabled"
        if (r.paused   as Boolean) trClasses << "rmrow-paused"
        if (!anyOn)                trClasses << "rmrow-logoff"
        String trAttr = trClasses
            ? " class='${trClasses.join(' ')}'" + (
                  (cfgHideRowLogOff   && !anyOn)                  ||
                  (cfgHideRowDisabled && (r.disabled as Boolean))  ||
                  (cfgHideRowPaused   && (r.paused   as Boolean))
                  ? " style='display:none'" : "")
            : ""

        // When all three share the same field name it's an enum-multiple — JS adds/removes the
        // specific option rather than flipping the whole field.
        boolean sharedField      = r.actionsField && r.actionsField == r.eventsField && r.actionsField == r.triggersField
        String actionsFieldType  = sharedField ? "enum-multiple" : "bool"
        String eventsFieldType   = sharedField ? "enum-multiple" : "bool"
        String triggersFieldType = sharedField ? "enum-multiple" : "bool"

        // Returns a clickable <td> when the field name is known; plain <td> otherwise
        Closure<String> clickableTd = { String colClass, String field, String fType, String enumOpt, Boolean isOn, String sortVal, String displayHtml ->
            if (!field) {
                return "<td class='center ${colClass}' data-sort='${sortVal}'>${displayHtml}</td>"
            }
            String escapedField = htmlEncode(field)
            String escapedOpt   = htmlEncode(enumOpt)
            return "<td class='center ${colClass} rmlog-clickable' data-sort='${sortVal}'" +
                " data-rule-id='${id}' data-field='${escapedField}' data-field-type='${fType}'" +
                " data-enum-option='${escapedOpt}' data-on='${isOn}'" +
                " onclick='rmToggleLogging(this)'>${displayHtml}</td>"
        }

        // PB cell: clickable only when endpoint is available AND state is known (not null)
        String pbTd
        if (pbEndpoint && pbVal != null) {
            pbTd = "<td class='center rmcol-pb rmlog-clickable' data-sort='${pbSort}'" +
                   " data-rule-id='${id}' data-on='${pbVal}'" +
                   " onclick='rmTogglePB(this)'>${pbFmt}</td>"
        } else {
            pbTd = "<td class='center rmcol-pb' data-sort='${pbSort}'>${pbFmt}</td>"
        }

        sb << "<tr${trAttr}>"
        sb << "<td class='center rmcol-ruleid' data-sort='${id}'>${id}</td>"
        sb << "<td data-sort='${nameSort}'><a href='/installedapp/configure/${id}' target='_blank'>${nameHtml}</a></td>"
        sb << "<td class='center rmcol-apptype' data-sort='${appType}'>${appType}</td>"
        sb << "<td class='center rmcol-disabled rmlog-clickable' data-sort='${r.disabled ? '1' : '0'}' data-rule-id='${id}' data-on='${r.disabled as Boolean}' onclick='rmToggleDisabled(this)'>${disabledFmt}</td>"
        sb << "<td class='center rmcol-paused rmlog-clickable'   data-sort='${r.paused   ? '1' : '0'}' data-rule-id='${id}' data-on='${r.paused   as Boolean}' onclick='rmTogglePaused(this)'>${pausedFmt}</td>"
        // BC rules have no Events logging — render a non-clickable "—" cell instead
        if (isBC) {
            sb << "<td class='center rmcol-events' data-sort=''><span style='color:#999;'>—</span></td>"
        } else {
            sb << clickableTd("rmcol-events", r.eventsField as String, eventsFieldType, "Events", r.eventsOn as Boolean, r.eventsOn ? "1" : "0", eventsFmt)
        }
        sb << clickableTd("rmcol-triggers", r.triggersField as String, triggersFieldType, "Triggers", r.triggersOn as Boolean, r.triggersOn ? "1" : "0", triggersFmt)
        sb << clickableTd("rmcol-actions",  r.actionsField  as String, actionsFieldType,  "Actions",  r.actionsOn  as Boolean, r.actionsOn  ? "1" : "0", actionsFmt)
        sb << pbTd
        sb << "<td class='center rmcol-lastrun' data-sort='${lastRun}'>${lastRun}</td>"
        sb << "</tr>"
    }

    sb << "</tbody></table>"

    // Emit a deferred init script to apply initial column-hide settings.
    // Rows are hidden server-side (trAttr above). Columns can't be hidden server-side without
    // adding inline styles to every cell, so we do it client-side here by setting display:none
    // directly on each element that carries the column's CSS class.
    // NOTE: do NOT simulate b.click() here — the toggle bar buttons are already rendered with
    // the correct hidden-col class server-side, and clicking them would reverse the state.
    List<String> colClassesToHide = []
    if (cfgHideColRuleId)   colClassesToHide << "'rmcol-ruleid'"
    if (cfgHideColAppType)  colClassesToHide << "'rmcol-apptype'"
    if (cfgHideColDisabled) colClassesToHide << "'rmcol-disabled'"
    if (cfgHideColPaused)   colClassesToHide << "'rmcol-paused'"
    if (cfgHideColActions)  colClassesToHide << "'rmcol-actions'"
    if (cfgHideColEvents)   colClassesToHide << "'rmcol-events'"
    if (cfgHideColTriggers) colClassesToHide << "'rmcol-triggers'"
    if (cfgHideColPB)       colClassesToHide << "'rmcol-pb'"
    if (cfgHideColLastRun)  colClassesToHide << "'rmcol-lastrun'"
    if (colClassesToHide) {
        sb << "<script>setTimeout(function(){[${colClassesToHide.join(',')}].forEach(function(cls){document.querySelectorAll('.'+cls).forEach(function(el){el.style.display='none';});});},0);</script>"
    }

    return sb.toString()
}

// ============================================================
// Built-in app report table
// ============================================================
// Columns: Rule ID | Name | App Type | Disabled | Paused | Logging | Last Run
// Row classes use bi* prefix to stay independent of the RM/BC table's rm* classes.
// Column classes use bi* prefix for the same reason.
// applyBiRowFilters / updateBiLogOffClass are defined in the <script> block below;
// stubs in buildReportHtml ensure rmToggle* can dispatch before this script loads.

String buildBuiltinReportHtml(List<Map> rows) {
    if (!rows) return "<p>No supported built-in apps found.</p>"

    // Read builtin-specific visibility settings
    boolean cfgHideBiRowDisabled = getPref("hideBiRowDisabled", false)
    boolean cfgHideBiRowPaused   = getPref("hideBiRowPaused",   false)
    boolean cfgHideBiRowLogOff   = getPref("hideBiRowLogOff",   true)
    boolean cfgHideBiColRuleId   = getPref("hideBiColRuleId",   false)
    boolean cfgHideBiColAppType  = getPref("hideBiColAppType",  false)
    boolean cfgHideBiColDisabled = getPref("hideBiColDisabled", false)
    boolean cfgHideBiColPaused   = getPref("hideBiColPaused",   false)
    boolean cfgHideBiColLogging  = getPref("hideBiColLogging",  false)
    boolean cfgHideBiColLastRun  = getPref("hideBiColLastRun",  false)

    StringBuilder sb = new StringBuilder()

    // Real implementations of the bi-table functions (override the stubs in buildReportHtml).
    // wildcardToRegex and isHiddenButton are already defined by buildReportHtml's JS block.
    sb << '''<script>
function updateBiLogOffClass(tr) {
    if (!tr) return;
    var cell = tr.querySelector('.bicol-logging');
    var logOn = cell && cell.getAttribute('data-sort') === '1';
    if (logOn) tr.classList.remove('birow-logoff');
    else       tr.classList.add('birow-logoff');
}
function applyBiRowFilters() {
    var hideDisabled = isHiddenButton('bitoggle-birow-disabled');
    var hidePaused   = isHiddenButton('bitoggle-birow-paused');
    var hideLogOff   = isHiddenButton('bitoggle-birow-logoff');
    var filterEl  = document.getElementById('biname-filter');
    var filterVal = filterEl ? filterEl.value.trim() : '';
    var filterRe  = null;
    var hasWild   = filterVal.indexOf('*') >= 0 || filterVal.indexOf('?') >= 0;
    var lowerSub  = '';
    if (filterVal) {
        if (hasWild) {
            // Wildcard pattern — full-string match with * and ? expansion.
            try { filterRe = wildcardToRegex(filterVal); } catch(e) { filterRe = null; }
        } else {
            // Plain text — case-insensitive substring match; no wildcards needed.
            lowerSub = filterVal.toLowerCase();
        }
    }
    document.querySelectorAll('#builtin_table tbody tr').forEach(function(tr) {
        var hide =
            (hideDisabled && tr.classList.contains('birow-disabled')) ||
            (hidePaused   && tr.classList.contains('birow-paused'))   ||
            (hideLogOff   && tr.classList.contains('birow-logoff'));
        if (!hide && filterVal) {
            var nameCell = tr.querySelectorAll('td')[1];
            var nm = nameCell ? (nameCell.getAttribute('data-sort') || nameCell.textContent || '').trim() : '';
            if      (filterRe) { hide = !filterRe.test(nm); }
            else if (lowerSub) { hide = nm.toLowerCase().indexOf(lowerSub) < 0; }
        }
        tr.style.display = hide ? 'none' : '';
    });
}
function toggleBiRowFilter(btn) {
    var hiding = btn.className.indexOf('hidden-col') === -1;
    btn.className = hiding ? 'rmcol-btn hidden-col' : 'rmcol-btn';
    applyBiRowFilters();
    persistPref(btn.dataset.prefKey, String(hiding));
}
</script>'''

    // Derive initial button classes from settings
    String btnBiRowDisabled = cfgHideBiRowDisabled ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiRowPaused   = cfgHideBiRowPaused   ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiRowLogOff   = cfgHideBiRowLogOff   ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiColRuleId   = cfgHideBiColRuleId   ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiColAppType  = cfgHideBiColAppType  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiColDisabled = cfgHideBiColDisabled ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiColPaused   = cfgHideBiColPaused   ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiColLogging  = cfgHideBiColLogging  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnBiColLastRun  = cfgHideBiColLastRun  ? "rmcol-btn hidden-col" : "rmcol-btn"

    sb << "<div class='rmcol-toggle-bar'>"
    sb << "<b>Hide rows:</b>&nbsp;"
    sb << "<span id='bitoggle-birow-disabled' class='${btnBiRowDisabled}' data-pref-key='hideBiRowDisabled' onclick=\"toggleBiRowFilter(this)\">Disabled rules</span>"
    sb << "<span id='bitoggle-birow-paused'   class='${btnBiRowPaused}'   data-pref-key='hideBiRowPaused' onclick=\"toggleBiRowFilter(this)\">Paused rules</span>"
    sb << "<span id='bitoggle-birow-logoff'   class='${btnBiRowLogOff}'   data-pref-key='hideBiRowLogOff' onclick=\"toggleBiRowFilter(this)\">Logging OFF</span>"
    sb << "&nbsp;&nbsp;<b>Hide columns:</b>&nbsp;"
    sb << "<span id='bitoggle-bicol-ruleid'   class='${btnBiColRuleId}'   data-pref-key='hideBiColRuleId' onclick=\"toggleRmCol('bicol-ruleid',this)\">Rule ID</span>"
    sb << "<span id='bitoggle-bicol-apptype'  class='${btnBiColAppType}'  data-pref-key='hideBiColAppType' onclick=\"toggleRmCol('bicol-apptype',this)\">App Type</span>"
    sb << "<span id='bitoggle-bicol-disabled' class='${btnBiColDisabled}' data-pref-key='hideBiColDisabled' onclick=\"toggleRmCol('bicol-disabled',this)\">Disabled</span>"
    sb << "<span id='bitoggle-bicol-paused'   class='${btnBiColPaused}'   data-pref-key='hideBiColPaused' onclick=\"toggleRmCol('bicol-paused',this)\">Paused</span>"
    sb << "<span id='bitoggle-bicol-logging'  class='${btnBiColLogging}'  data-pref-key='hideBiColLogging' onclick=\"toggleRmCol('bicol-logging',this)\">Logging</span>"
    sb << "<span id='bitoggle-bicol-lastrun'  class='${btnBiColLastRun}'  data-pref-key='hideBiColLastRun' onclick=\"toggleRmCol('bicol-lastrun',this)\">Last Run</span>"
    sb << "&nbsp;&nbsp;<b>Filter:</b>&nbsp;"
    sb << "<input id='biname-filter' type='text' class='rmname-filter' placeholder='Rule name (substring or * ? wildcards)' oninput='applyBiRowFilters()' style='width:300px;'>"
    sb << "</div>"

    sb << "<table id='builtin_table' class='rmlogcheck'><thead><tr>"
    sb << "<th onclick=\"sortRmLogTable('builtin_table',0)\" class='center bicol-ruleid'>Rule ID</th>"
    sb << "<th onclick=\"sortRmLogTable('builtin_table',1)\" class='sort-asc'>Name</th>"
    sb << "<th onclick=\"sortRmLogTable('builtin_table',2)\" class='center bicol-apptype'>App Type</th>"
    sb << "<th onclick=\"sortRmLogTable('builtin_table',3)\" class='center bicol-disabled'>Disabled</th>"
    sb << "<th onclick=\"sortRmLogTable('builtin_table',4)\" class='center bicol-paused'>Paused</th>"
    sb << "<th onclick=\"sortRmLogTable('builtin_table',5)\" class='center bicol-logging'>Logging</th>"
    sb << "<th onclick=\"sortRmLogTable('builtin_table',6)\" class='center bicol-lastrun'>Last Run</th>"
    sb << "</tr></thead><tbody>"

    rows.each { Map r ->  // already sorted by getBuiltinAppInstances()
        String id          = htmlEncode(r.id)
        String appType     = htmlEncode(r.appType ?: "")
        String nameHtml    = renderNameHtml(r.name)
        String nameSort    = htmlEncode(r.name?.toString()?.replaceAll(/<[^>]+>/, '') ?: "")
        String disabledFmt = formatYesNo(r.disabled as Boolean)
        String pausedFmt   = formatYesNo(r.paused   as Boolean)
        String lastRun     = htmlEncode(r.lastRun ?: "")

        Boolean logVal = r.logging == null ? null : (r.logging as Boolean)
        String  logFmt = (logVal == null)  ? "<span style='color:#999;'>—</span>"
                       : logVal            ? "<span style='color:red;font-weight:bold;'>ON</span>"
                                           : "<span style='color:green;font-weight:bold;'>OFF</span>"
        String logSort = (logVal == true)  ? "1" : "0"

        String logTd
        if (logVal != null) {
            logTd = "<td class='center bicol-logging rmlog-clickable' data-sort='${logSort}'" +
                    " data-rule-id='${id}' data-field='logging' data-field-type='bool'" +
                    " data-enum-option='' data-on='${logVal}'" +
                    " onclick='rmToggleLogging(this)'>${logFmt}</td>"
        } else {
            logTd = "<td class='center bicol-logging' data-sort='${logSort}'>${logFmt}</td>"
        }

        // Assign birow-* classes for the row filters; server-side hide if setting active
        List<String> biTrClasses = []
        if (r.disabled as Boolean)       biTrClasses << "birow-disabled"
        if (r.paused   as Boolean)       biTrClasses << "birow-paused"
        if (logVal != true)              biTrClasses << "birow-logoff"
        String biTrAttr = biTrClasses
            ? " class='${biTrClasses.join(' ')}'" + (
                  (cfgHideBiRowLogOff   && logVal != true)              ||
                  (cfgHideBiRowDisabled && (r.disabled as Boolean))     ||
                  (cfgHideBiRowPaused   && (r.paused   as Boolean))
                  ? " style='display:none'" : "")
            : ""

        sb << "<tr${biTrAttr}>"
        sb << "<td class='center bicol-ruleid' data-sort='${id}'>${id}</td>"
        sb << "<td data-sort='${nameSort}'><a href='/installedapp/configure/${id}' target='_blank'>${nameHtml}</a></td>"
        sb << "<td class='center bicol-apptype' data-sort='${appType}'>${appType}</td>"
        sb << "<td class='center bicol-disabled rmlog-clickable' data-sort='${r.disabled ? '1' : '0'}' data-rule-id='${id}' data-on='${r.disabled as Boolean}' onclick='rmToggleDisabled(this)'>${disabledFmt}</td>"
        sb << "<td class='center bicol-paused rmlog-clickable' data-sort='${r.paused ? '1' : '0'}' data-rule-id='${id}' data-on='${r.paused as Boolean}' onclick='rmTogglePaused(this)'>${pausedFmt}</td>"
        sb << logTd
        sb << "<td class='center bicol-lastrun' data-sort='${lastRun}' style='white-space:nowrap;'>${lastRun}</td>"
        sb << "</tr>"
    }

    sb << "</tbody></table>"

    // Column init script — mirrors the RM table approach
    List<String> biColsToHide = []
    if (cfgHideBiColRuleId)   biColsToHide << "'bicol-ruleid'"
    if (cfgHideBiColAppType)  biColsToHide << "'bicol-apptype'"
    if (cfgHideBiColDisabled) biColsToHide << "'bicol-disabled'"
    if (cfgHideBiColPaused)   biColsToHide << "'bicol-paused'"
    if (cfgHideBiColLogging)  biColsToHide << "'bicol-logging'"
    if (cfgHideBiColLastRun)  biColsToHide << "'bicol-lastrun'"
    if (biColsToHide) {
        sb << "<script>setTimeout(function(){[${biColsToHide.join(',')}].forEach(function(cls){document.querySelectorAll('.'+cls).forEach(function(el){el.style.display='none';});});},0);</script>"
    }

    return sb.toString()
}

// ============================================================
// Formatting helpers
// ============================================================

@CompileStatic
String formatOnOff(Boolean value) {
    return value ? "<span style='color:red;font-weight:bold;'>ON</span>" : "<span style='color:green;font-weight:bold;'>OFF</span>"
}

@CompileStatic
String formatYesNo(Boolean value) {
    return value ? "<span style='color:red;font-weight:bold;'>Yes</span>" : "<span style='color:green;font-weight:bold;'>No</span>"
}

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
