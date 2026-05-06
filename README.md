# Rule-Logging-and-State-Checker
Scans Rule Machine and Button Controller child apps and reports which rules appear to have  Actions, Events, and/or Triggers logging enabled, plus Disabled, Paused, and Private Boolean states.

                This app scans Rule Machine (RM) and Button Controller (BC) child apps and
                checks their status JSON for logging settings that appear to correspond to
                Actions, Events, and Triggers logging. A table cell marked
                ON means that the corresponding logging option appears to be enabled for that rule.
                

                Basic Button Controller is intentionally not included because it appears to expose only
                a single broad logging toggle rather than separate Actions, Events, and Triggers controls.
                

                Clicking Scan RM / BC Rules replaces the results table with a progress message
                while the scan runs. The completed results will appear automatically when the scan
                finishes — no manual refresh needed.
                

                The row filter buttons hide or show rows by category. No logging ON is active
                by default, hiding rules where all logging is off; click it to show all scanned rules.
                The column filter buttons hide or show individual columns. Multiple row filters work
                independently — a row stays hidden if any active filter applies to it.
                

                Click any table header to sort by that column; clicking the same header again reverses
                the sort direction. The default sort is by Rule name.
                

                Click any Actions, Events, Triggers, Disabled, or Paused
                cell to toggle that rule's setting in-place. The cell updates immediately if successful.
                Actions, Events, and Triggers cells where the field name could not be determined are not clickable.
                

                Click any Private Bool cell to toggle that rule's Private Boolean between TRUE and false.
                The toggle calls RMUtils.sendAction() via this app's local OAuth endpoint and
                targets RM version 5.0 rules. The cell updates immediately if the call succeeds.
                Cells showing — mean the Private Boolean state could not be read for that rule and
                are not clickable. One-time setup required: go to Apps Code, open this app, click
                OAuth, and press Enable OAuth in Smartapp. Then return here and press Generate Token —
                the status section will confirm active status. This step only needs to be done once;
                the token persists across hub reboots and app updates.
                

                The Last Run column shows the date and time of the most recent trigger event for
                each rule, sourced from the lastEvtDate, lastEvtTime, and
                timeFormat fields in the rule's internal state. Times are normalised to
                24-hour HH:mm regardless of how individual rules store them. A blank cell means
                the rule has never been triggered, or has not been triggered since it was last installed.
                Last Run reflects when the rule was triggered, not necessarily when its actions
                completed.
                

                Summary counts reflect the state at the time of the most recent scan. Clicking
                table cells to change rule settings updates the cell immediately but does not update
                the summary counts — run Scan again to refresh counts and cached row data.
                

                WARNING: This app uses Hubitat local/internal JSON endpoints. Those endpoints and Rule Machine /
                Button Controller internal setting names are not a formal public API, so the detection
                logic may need to be adjusted if Hubitat changes the JSON format in a future platform update.
