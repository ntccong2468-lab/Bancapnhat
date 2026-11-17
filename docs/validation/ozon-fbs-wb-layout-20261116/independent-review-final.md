# Independent preservation review — v2

Status: **BOUNDED_PASS** for the combined source frozen in `layout-audit/final-ui-source-freeze-iteration3.json`. This supplements v1; no v1 artifact was overwritten.

All nine live hashes and the native-run input hashes match. None of the original 3,807 baseline files is missing. Outside the approved source/test/resource files, only the parent's three memory documents changed. The sole added source file is the native parity test already covered in v1.

Of 141 protected lexical units, **140 remain byte-identical**. The remaining `setBusy(boolean,boolean)` unit matches the baseline bytes after exactly one approved presentation replacement: `loadingIndicator.setManaged(busy)` becomes `loadingIndicator.setManaged(true)`. The whole method is not exempted. Visibility still follows busy; shop/batch locks, callbacks, selection, cancellation and registration statements remain identical. All **323 protected production files**, **77 original fx:id/type/onAction contracts** and **13 declarative callbacks** survive; current FXML has79 unique IDs.

Relative to v1, the only other controller change is the allowed thumbnail alignment TOP_CENTER → TOP_LEFT; dimensions and asynchronous generation/owner guards remain unchanged. FXML removes the unnamed wrapper and uses the same direct native ProgressIndicator pref/max18 as WB, with no hardcoded native min36 or arbitrary2px padding. Original data/paging/sort/full filtered selection/acceptance/printing/KIZ/purchase signing resume/Znack contracts reviewed in v1 remain intact.

I parsed three fresh owner JUnit XML files: **23 tests (14 native parity +4 existing UI +5 controller),0 failures/errors/skips**. I did not run Maven or the app. The photo test now compares position as well as dimensions. The added14th native case compares absolute Y/height for root/header/title/refresh/tabs/search/filter/table in dark/light and idle/busy. Every recorded comparison matches within0.5px; header44 and tableY191/height508 remain stable while spinner visibility follows busy. Both photos measure36×48, cell offset(10,7), row offset(284,7). Column widths remain[46,228,127,594,159]. Evidence/XML copied into `v2-evidence/`.

No concrete preservation blocker found. This is bounded source/evidence review; parent fresh v2 broad regression, package, Launcher and screenshots remain separate gates. Earlier393/64 and22-case results remain v1 history and must not be counted as execution on this final source. Windows/live seller API acceptance was not performed.
