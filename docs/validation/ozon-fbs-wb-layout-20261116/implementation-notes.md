# Ozon FBS presentation parity with WB packing

Reference: native `PackingController` + `packing-view.fxml`. The previous Ozon implementation attached WB **shipment detail** classes and inserted separate STT/barcode columns; that was the source of the mismatch with WB's primary FBS packing view.

Changes are confined to Ozon presentation:

- Same 16 px page inset, 12 px root rhythm, title/refresh composition, 36×48 thumbnails and WB five-column order-table composition. Ozon received/printed rows keep their own existing per-row print action; handed-over rows retain their own provider status column.
- Shared search/filter toolbar follows the active order tab without replacing its `TextField`, changing query parameters or introducing a current-page category filter. All five original Ozon state tabs and paging/select-filtered actions remain.
- Existing four sorting checkboxes move into a real localized Sort menu, retaining their original nodes, defaults, preferences and listeners.
- STT, full order/posting IDs, shipment deadline, all Ozon barcodes, verified GTIN, SKU, variant article/color/size, item quantity, category and actual KN3/KIZ rule remain in row labels, tooltips/accessibility and/or the existing detail drawer. Barcode details move into a compact third product line; no read is performed while rendering.
- Business methods for reception, batch printing, labels, selection, paging, sort persistence, purchased KIZ, signature/purchase resumption and Ozon Znack registration are unchanged. WB, CSS, API contracts and other pages are untouched.

Test plan: preserve native baseline RED evidence, run rendered JavaFX WB/Ozon comparison GREEN plus current Ozon UI/action/paging/row-containment tests; independently hash protected business chunks against the task baseline. Linux synthetic UI tests do not establish live seller API or Windows/CryptoPro behavior.

## Verification findings during implementation

The initial UI verification compiled 766 production and 625 test sources. Existing Ozon UI/controller tests passed (9 cases), and 12 of 13 expanded native parity cases passed. One strict rendered-width assertion found a new presentation allocation issue before handoff: with both table widths 1158 px, native WB rendered `[46, 228, 127, 594, 159]`, whereas the first Ozon patch rendered only the minima `[46, 160, 70, 300, 100]`, leaving unused width. Diagnostic min/pref/resizable values matched, but two explicit column max values were `Double.MAX_VALUE` versus WB's finite default `5000`. The patch now uses WB's exact finite bounds and preserves the geometry assertion; an additional width-zero lifecycle guard avoids switching resize policy before layout. Final rendered result is recorded in the test agent's GREEN evidence.

The original meaningful RED remains archived separately: nine native tests ran against the 3807-file baseline, seven geometry assertions failed and two preservation/action checks passed. A transient later diagnostic attempt could not open DISPLAY; the corrected cached Xvfb runner executed the real diagnostic. No product claim is based on that harness error.

Final native geometry receipt confirms both WB and Ozon table widths 1158 px, identical constrained-resize policy and exact actual columns `[46, 228, 127, 594, 159]`; all finite maxima now `[46, 5000, 5000, 5000, 5000]`.

## Final scoped result

PASS: 22 executed tests, 0 failures, 0 errors, 0 skipped; Maven exit 0. Native `OzonWbPackingParityTest`: 13; existing `OzonFbsUiTest`: 4; `OzonDashboardControllerTest`: 5. Coverage includes actual WB/Ozon page geometry and full-width columns, 70 px single-item rows and 36×48 thumbnails, all four order-state tabs, preserved IDs/date-offset/variant/barcodes/GTIN/SKU/quantity/KN3/category/rule/STT through rendered labels/tooltips/accessibility, keyboard-opened sorting popup and theme, unchanged sorting preferences/listeners, one shared search with preserved text across tab reparenting, multi-item row containment, action wiring and stale-shop protection. Existing bounds matrix covers four languages, two themes and 824/1140 windows.

The nine approved production/resource/test input hashes remained identical after execution. Full source/input receipts, JUnit XML, logs and before/after width evidence: `../parity-tests/final-green-evidence/`; machine-readable summary: `../parity-tests/SUMMARY.json`. Final scoped freeze: `final-ui-source-freeze.json`.

Parent remains responsible for the broader 64-class verification, fresh current-source JAR and after screenshots, independent preservation audit and final memory/report. No live API write, purchase, signature, license activation, version update or release was performed in this presentation task.
