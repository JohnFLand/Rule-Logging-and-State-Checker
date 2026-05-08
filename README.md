# Rule Logging and State Checker

This app scans Rule Machine (**RM**) and Button Controller (**BC**) rules and reports their logging status (Events, Triggers, Actions), Disabled and Paused states, and Private Boolean value in a first table. It also scans rules of supported Hubitat built-in apps (Notifications, Basic Rules, Simple Automation Rules, Basic Button Controller, Room Lighting, Motion Lighting) and reports their Logging setting and Disabled and Paused states in a second table. The two tables each have their own filter, sort, and hide controls.

Button Controller rules show **—** in the Events column because BC rules have no Events logging option.

Rule types that expose only one broad logging toggle (rather than separate Events, Triggers, and Actions controls) appear in the Built-in App Logging table.

---

## Installation

1. In Hubitat, go to **Apps Code → New App**
2. Click **Import** and paste the following URL:
   ```
   https://raw.githubusercontent.com/JohnFLand/Rule-Logging-and-State-Checker/refs/heads/main/Rule%20Logging%20and%20State%20Checker.groovy
   ```
3. Click **Save**
4. Go to **Apps → Add User App** and select **Rule Logging and State Checker**

---

## Screenshot

![Rule Logging and State Checker screenshot](Screenshot%202026-05-07%20231828.png)

---

## Scanning

Click **Scan All Rules** to start a scan. Both tables update automatically when the scan finishes — no manual refresh needed. Clicking **Done** and reopening the app re-renders both tables instantly from cached data, so display setting changes take effect without a rescan (but use data from the previous scan). If you install a new version, run a fresh scan once to regenerate the tables with any new columns or buttons.

---

## Row Filters

Each table has its own row filter buttons. In the RM/BC table, **No logging ON** is active by default, hiding rules where all logging is off; click it to show all rules. In the Built-in App Logging table, **Logging OFF** is active by default. Multiple row filters evaluate together — a row stays hidden if *any* active filter applies to it.

---

## Name Filter

Each table has a wildcard filter field. Use **\*** to match any sequence of characters and **?** to match any single character, e.g. `Contact*TU` or `*Motion*`. Filtering is case-insensitive and combines with the row filter buttons — a row must pass both to be visible.

---

## Table Visibility

A **Hide** toggle immediately below each table hides or shows that entire table. The setting takes effect immediately and persists across page opens. Each table's section heading is also clickable to collapse or expand it temporarily.

---

## Row and Column Toggle Buttons

Each table has its own hide-row and hide-column buttons above it. Clicking any button saves the preference automatically via the app's local OAuth endpoint — no "Done" press needed and the change persists across page opens.

---

## Sorting

Click any column header to sort by that column; clicking the same header again reverses the sort direction. The default sort is by **Rule** name.

---

## Clickable Cells — RM/BC Table

Click any **Events**, **Triggers**, **Actions**, **Disabled**, **Paused**, or **Private Boolean** cell to toggle that rule's setting in-place. The table cell updates immediately if successful. Cells where the field name could not be determined are not clickable.

---

## Clickable Cells — Built-in App Logging Table

Click any **Logging**, **Disabled**, or **Paused** cell to toggle that setting in-place. After toggling **Paused** in the Built-in App Logging table, the rule is correctly paused immediately, but the **(Paused)** label on the Automations page may require a browser page refresh to appear.

---

## Private Boolean (RM/BC Table)

Click any **Private Bool** cell to toggle a rule's Private Boolean between TRUE and FALSE. TRUE is displayed in bold blue; FALSE in grey. Cells showing **—** mean the PB state could not be read and are not clickable.

The toggle calls `RMUtils.sendAction()` via this app's local OAuth endpoint, targeting RM 5.0 rules.

OAuth is enabled automatically on first install — no manual setup required. If the PB toggle ever shows inactive, re-open the app to retry; if it still fails, enable OAuth manually via the three-dot menu in Apps Code, then re-open. The token persists across hub reboots and app updates.

---

## Last Run Column

Shows the date and time of the most recent trigger event for each rule, normalised to 24-hour **HH:mm** format regardless of how individual rules store the time. A blank cell means the rule has never been triggered since it was last installed. Last Run reflects when the rule was *triggered*, not necessarily when its actions completed.

---

## Summary Counts

Shown as part of each table's heading area. Counts are computed from the most recent scan. Toggling cells in-place updates cells immediately but does not refresh the summary — run **Scan All Rules** again to update counts and cached row data.

---

## Controls Section

The collapsible **Controls** section provides four functions:

- **App instance name** — type a custom name for this app instance; the name appears in the Hubitat Apps list and logs.
- **Printable HTML reports** — opens a clean, print-optimised version of each table in a new browser tab. All rows are shown regardless of current filter state. Use the browser's Print or Save as PDF function from that tab.
- **CSV export** — downloads the table data as a CSV file (*RM-BC_Rules.csv* or *Built-In_Rules.csv*) for use in a spreadsheet.
- **Enable debug logging** — turns on verbose logging to the Hubitat log for 30 minutes, then disables itself automatically.

---

## Warning

This app uses Hubitat local/internal JSON endpoints. Those endpoints and Rule Machine / Button Controller / built-in app internal setting names are not a formal public API, so the detection logic may need to be adjusted if Hubitat changes the JSON format in a future platform update.

---

## Version History

| Version | Changes |
|---------|---------|
| 1.60 | Controls section: app rename, printable HTML reports, CSV export per table, debug toggle; Private Bool TRUE now bold blue, FALSE capitalised |
| 1.59 | Events/Triggers/Actions column order throughout to match RM UI; persistent Hide table toggles below each table |
| 1.58 | Row/column toggle buttons auto-persist via /setpref OAuth endpoint — no Done press needed; Custom Settings sections removed |
| 1.57 | buildSharedReportAssets() extracted so built-in table works when RM/BC list is empty; PB data-sort corrected; empty-scan resets bi* counts |
| 1.56 | Code review fixes: false-positive contains() removed; fd.append(deviceList) + sentinel; null→'' for non-collection fields; dateFormat in extractLastRun; @CompileStatic; named constants |
| 1.55 | Table show/hide toggles moved into Custom Settings sections; spacing added between sections |
| 1.54 | Both tables now collapsible sections with persistent show/hide state |
| 1.53 | No-op save after built-in app pause toggle forces Automations page label update after browser refresh |
| 1.52 | Pause toggle for built-in app rows discovers correct button name per app type |
| 1.51 | Motion Lighting (Motion and Mode Lighting Apps umbrella) added to built-in app detection |
| 1.50 | Recursive leaf-finding replaces hardcoded grandchildren pattern in both rule discovery functions |
| 1.49 | Simple Automation Rules, Basic Button Controller, and Motion Lighting added to Built-in App Logging table |
| 1.48 | Self-enabling OAuth via hub internal API — no manual Apps Code step needed |
| 1.47 | Built-in app Paused cells made clickable |
| 1.46 | Two-table layout; RM/BC and Built-in App stats moved into table headings; Scan All Rules button |
| 1.45 | Hide rows/columns and wildcard filter added to Built-in App Logging table |
| 1.44 | Wildcard rule name filter added to RM/BC table; second table for built-in apps added |
| 1.43 | App Type column hideable |
| 1.42 | OAuth token created automatically on page open |
| 1.39 | Row filter overlap fix; Last Run date sort fix; PB sort three-way |
| 1.38 | Robustness fixes: endpoint validation, relative URLs, PB null for unknown |
| 1.35 | Custom Row and Column Settings section with persistent hide preferences |
| 1.34 | OAuth setup documentation |
| 1.32 | Private Boolean column clickable via local OAuth endpoint |
| 1.31 | Private Boolean column added |
