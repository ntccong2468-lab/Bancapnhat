# UI expectation updates for approved policies

Changes are confined to two test files: `KizPurchaseStatusPresentationTest` and `FxmlSmokeTest`. No JavaFX production controller, FXML, CSS, handler or runtime behavior changed.

Root's original broad run had one error in each class. Those XML receipts are copied here as `root-initial-TEST-*.xml`. They are policy/smoke expectation mismatches, not newly demonstrated production UI defects.

## Mandatory new intent and historical consent

The old presentation test called normal saveSettings(false), then expected the hidden control to remain false. The reviewed replacement policy makes normal new preferences and effective settings true, while preserving historical paid-job raw intent independently.

The updated parameterized on/off case now seeds an actual legacy raw preference and constructs a historical pipeline through the existing legacy createPipeline API. That records its original snapshot without treating it as a newly approved mandatory-introduction enqueue. The canonical context adapter and legacy bound snapshot must both retain the old on/off value after all display operations. Effective normal settings and the hidden field must be true. The checkbox stays invisible/unmanaged and there is no title-menu opt-out. Language changes and opening/canceling settings must not save the raw preference, alter the original pipeline snapshot, or change its old database timestamp. No other false-legacy consent test changed.

The first updated run passed FXML58/58 but correctly failed this new negative fixture when it accidentally used new enqueuePipeline, which always pins true. That checkpoint is preserved (`verification.log`, `result.json`); the fixture was corrected to the actual historical API rather than weakening the false-intent assertion or downgrading new purchase consent.

## Approved Ozon/WB table parity

The old FXML smoke assertion required separate dynamically added `ozonBarcodes` and `ozonRowNumber` columns. Earlier approved Ozon=WB UI work removes them; current OzonFbsUiTest explicitly forbids both and verifies five visible columns plus barcode/GTIN/KN3 content inside product details. The smoke test now agrees with that approved layout. All existing tab, selection, receive-button, label, inventory, footer and width assertions remain. Keyboard and row-detail handler presence checks were added. Production UI was not changed to resurrect obsolete columns.

Exact final test file hashes are in `source-hashes.json`; `git diff --check` passed. Private Maven/Xvfb execution uses cached dependencies, synthetic local SQLite and existing fixture adapters, without seller API writes or signing. Linux/Xvfb is not a native Windows visual acceptance claim. Final focused execution: **64/64 PASS (58 FXML smoke +6 KIZ presentation), 0 failure/error/skip**. Actual command `command.json`, process result `result-final.json`, Maven log `verification-final.log`, suite totals `test-summary.json`. Root should refresh only these exact two test files in its frozen production snapshot for the final affected-suite rerun; earlier overlapping attempts are not additional coverage.
