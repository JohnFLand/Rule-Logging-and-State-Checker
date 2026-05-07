# Rule-Logging-and-State-Checker
Scans Rule Machine and Button Controller child apps and reports which rules appear to have  Actions, Events, and/or Triggers logging enabled, plus Disabled, Paused, and Private Boolean states.
# Rule Logging and State Checker

A Hubitat app that scans Rule Machine (RM) and Button Controller (BC) rules and reports their logging status, Disabled and Paused state, and Private Boolean value. It also scans supported Hubitat built-in apps (Notifications, Basic Rules, Room Lighting) and reports their Logging setting. Results appear in two separate tables, each with its own filter, sort, and hide controls.

## Installation

1. In Hubitat, go to **Apps Code → New App**
2. Click **Import** and paste the following URL:
   ```
   https://raw.githubusercontent.com/JohnFLand/Rule-Logging-and-State-Checker/refs/heads/main/Rule%20Logging%20and%20State%20Checker.groovy
   ```
3. Click **Save**
4. Go to **Apps → Add User App** and select **Rule Logging and State Checker**

---

## Overview

Basic Button Controller is intentionally not included — it exposes only one broad logging toggle rather than separate Actions, Events, and Triggers controls. Button Controller rules show **—** in the Events column because BC rules have no Events logging option.

---

## Usage

### Scanning

Click **Scan Rules** to start a scan. Both tables update automatically when the scan finishes — no manual refresh needed. Clicking **Done** and reopening the app re-renders both tables instantly from cached data, so display setting changes take effect without a rescan.

> **Note:** If you install a new version, run a fresh scan once to regenerate the tables with any new columns or buttons.

---

### Row Filters

Each table has its own row filter buttons.

- In the RM/BC table, **No logging ON** is active by default, hiding rules where all logging is off; click it to show all rules.
- In the Built-in App Logging table, **Logging OFF** is active by default.

Multiple row filters evaluate together — a row stays hidden if *any* active filter applies to it.

---

### Rule Name Filter

Each table has a wildcard filter field. Use `*` to match any sequence of characters and `?` to match any single character, e.g. `Contact*TU` or `*Motion*`. Filtering is case-insensitive and combines with the row filter buttons — a row must pass both to be visible.

---

### Column Buttons and Custom Settings

Each table has its own set of hide-column buttons. Persistent defaults for both tables can be set in the **Custom Row and Column Settings** sections in the app; changes take effect after clicking Done — no rescan needed.

---

### Sorting

Click any column header to sort by that column; clicking the same header again reverses the sort direction. The default sort is by **Rule** name.

---

### Clickable Cells — RM/BC Table

Click any **Actions**, **Events**, **Triggers**, **Disabled**, or **Paused** cell to toggle that rule's setting in-place. The cell updates immediately if successful. Cells where the field name could not be determined are not clickable.

---

### Clickable Cells — Built-in App Logging Table

Click any **Logging**, **Disabled**, or **Paused** cell to toggle that setting in-place.

---

### Private Boolean (RM/BC Table)

Click any **Private Bool** cell to toggle a rule's Private Boolean between TRUE and false. The toggle calls `RMUtils.sendAction()` via this app's local OAuth endpoint, targeting RM 5.0 rules. Cells showing **—** mean the PB state could not be read and are not clickable.

OAuth is enabled automatically on first install — no manual setup required. If the PB toggle ever shows inactive, re-open the app to retry; if it still fails, enable OAuth manually via the three-dot menu in Apps Code, then re-open. The token persists across hub reboots and app updates.

---

### Last Run Column

Shows the date and time of the most recent trigger event for each rule, normalised to 24-hour **HH:mm** format regardless of how individual rules store the time. A blank cell means the rule has never been triggered since it was last installed. Last Run reflects when the rule was *triggered*, not necessarily when its actions completed.

---

### Summary Counts

Shown as part of each table's heading area. Counts are computed from the most recent scan. Toggling cells in-place updates cells immediately but does not refresh the summary — run **Scan Rules** again to update counts and cached row data.

---

## Warning

This app uses Hubitat local/internal JSON endpoints. Those endpoints and Rule Machine / Button Controller / built-in app internal setting names are not a formal public API, so the detection logic may need to be adjusted if Hubitat changes the JSON format in a future platform update.

---

## Version History

| Version | Changes |
|---------|---------|
| 1.49 | Fixed a regression to once again make Paused cells in the 2nd table clickable. *
| 1.48 | Self-enabling OAuth via hub internal API — no manual Apps Code step needed |
| 1.47 | Built-in app Paused cells made clickable |
| 1.46 | Two-table layout; RM/BC and Built-in App stats moved into table headings; Scan Rules button |
| 1.45 | Hide rows/columns and wildcard filter added to Built-in App Logging table |
| 1.44 | Wildcard rule name filter added to RM/BC table; second table for built-in apps |
| 1.43 | App Type column hideable |
| 1.42 | OAuth token created automatically on page open |
| 1.39 | Row filter overlap fix; Last Run date sort fix; PB sort three-way |
| 1.38 | Robustness fixes: endpoint validation, relative URLs, PB null for unknown |
| 1.35 | Custom Row and Column Settings section with persistent hide preferences |
| 1.34 | OAuth setup documentation |
| 1.32 | Private Boolean column clickable via local OAuth endpoint |
| 1.31 | Private Boolean column added |
                
