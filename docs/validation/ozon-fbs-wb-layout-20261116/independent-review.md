# Ozon FBS appearance parity — preservation contract before implementation

16.11.2026. Read-only review; no target source edits, tests, seller calls, signing, purchase, activation or release by this reviewer. Target `/workspace/vncode-ios-motion-20261114`, dirty feature branch including all prior ECC/UI/Ozon/KIZ work. Controller baseline SHA `c4819446b4e50b7d7481912eb64feae39212a781737cae5fc5ed2b741c2deae8`.

## Scope and actual reference

Reference is `ui/packing/PackingController` and `ui/packing/packing-view.fxml`. `SupplyDetailController` is shipment detail and is not the requested WB FBS reference. Existing Ozon table explicitly adds `supply-orders` plus `ozon-posting-table`, then inserts the STT/barcode columns; those classes and extra narrow columns explain the shipment-detail appearance.

User requested the Ozon screen's appearance and preservation of all other functionality. Reuse WB page gutters/title/refresh/tab placement/filter geometry/table typography and density. Preserve Ozon's four status queues and KIZ tab, native labels, per-order receive/print semantics, source policy and data. WB shipment creation/debt groups cannot replace Ozon posting actions or state model merely to match labels.

## Immutable business boundary

`pre-implementation-contract.json` records all 77 FXML IDs and handlers, a lexical top-level method-like unit inventory with exact bytes/hashes, and 323 protected production file hashes. This is a scoped static preservation aid, not a Java AST or proof of rendered behavior. The prior controller/FXML and all lexical chunks are retained outside the repo for later comparison.

The 141 protected lexical units must be byte-identical unless an explicitly reviewed presentation exception is needed. In particular:

- Incoming batch selection, freeze/filter queries beyond visible100 rows, 1,000/10,000-order selection, cancellation, and selected-set counters.
- `onMoveToPacking`, exact Ozon source snapshot, replenishment/Apply consent, retry/pending/children handling, shop credential changes and per-order reporting.
- `onPrintAll`, `downloadLabel`, preflight/cross-mapping, `runPrintTask` overloads, official batch downloads/combined labels, durable print receipts, success-only tab transitions, result reports and picking file targets.
- `loadLocal`, `pageQuery`, `activeStatus`, offsets/totals/status queue distinctions and 100-order paging. Source Ozon statuses remain `awaiting_packaging`, unprinted `awaiting_deliver`, printed `awaiting_deliver`, and `delivering`.
- Sort preference/defaults/listeners, source selection, busy/owner/cancel guards, current-shop callbacks, row print availability and all disposal logic.
- KIZ category/import/purchase controls, GTIN mapping, Znack registration CTA, and the new exact-pipeline signing-resume behavior.
- All marketplace/SUZ/Catalog services, shared purchase/stock/mapping, WB packing, other UI routes, schemas, dependencies, global styles and release configuration.

Presentation allowlist for later review: `initialize`, `applyTranslations`, `setupTables`, `showOrderDetails`, `configureOrderColumns`, `configureReferenceTable`, `resizePostingTable`, `configureImageColumn`, `configurePriceColumn`, `orderRowHeight`, `labelCell`; and display-only inner cells/metrics. Being on this list is not permission to change callback behavior or remove data. Any changed protected chunk is a review trigger, not an automatic allegation.

## Preserve data during column changes

Old `ozonRowNumber` and `ozonBarcodes` may move out of visible columns to reach WB's five-column silhouette, but the data must remain discoverable. Current `showOrderDetails` has article/color/size/SKU/quantity/GTIN and shipping time, **but lacks Ozon product barcodes**. Hiding the old barcode column without adding its values to an actual rendered row, tooltip/accessibility or the real detail drawer loses information.

Retain both order number and posting number when different, all products in a multi-product posting, each quantity and price/currency, seller article/color/size, KN3/mandatory-marking state, and the Ozon offset of shipping cutoff. Existing `shipmentText` has a direct date/offset test. Existing row-height76/144 tests describe multi-item containment and cannot be deleted just for cosmetic parity; preserve adequate height or replace with stronger rendered containment when the requested metrics deliberately change.

## Avoid an unsupported category filter

WB filter row includes a200px category menu. OzonFbsQuery has no category criterion; filtering only the currently rendered100 rows would silently corrupt cross-page filtered selection/paging. Do not add a dead category dropdown or implement a current-page-only filter. A presentation-safe option is to use the same200px slot for Ozon's existing four sort controls in a correctly named menu, preserving the same CheckBox nodes/defaults/listeners and their query semantics. Backend category filtering would be a separate feature extension requiring deliberate scope and tests.

## Actual JavaFX verification recommended

1. Load both production WB Packing and Ozon FXML in equal scene/shell dimensions, production theme/motion settled, populated synthetic data. Check root16px gutters/12px stack spacing, adjacent title-refresh position, tab-to-filter order,5-column primary silhouette, column boundaries/widths, font/row/photo metrics and maximum list area. Check dark/light, four locales and at least1024x600 plus1480x900.
2. Existing `OzonFbsUiTest:40` asserts visible STT/barcode columns from the old design. Update this *visual* expectation only as required by the new reference, replacing it with proof of identifier visibility/discovery, not simply dropping assertions.
3. Verify all four Ozon tables, KIZ tab and original77fxids/onAction callbacks still load. Exercise search debounce, next/previous, select-filtered/clear/cancel, sorting preferences, keyboard Enter and double-click detail opening, row print action, batch receive, batch print and error reports using current fake callbacks.
4. Reuse existing strong behavior suites: OzonBulkUiTest(1000/10000/cancel/429+unknown/connection-change/no-double-purchase/Fx-liveness), OzonPrintedQueueUiTest(success-only printed transition/failedPDF/subsetdurablereport), OzonShipmentSourcesUiTest(category-upload/purchased controls), OzonManualSyncUiTest, OzonFbsEnrichmentUiTest(live attribute/thumbnail), OzonRegistrationEntryUiTest and navigation, FbsKizSigningResumeUiTest, WorkspaceListAreaTest/PagesLayoutTest/JavaPagesLayoutTest and WB FbsResponsiveActionsTest. Do not duplicate their business tests or alter them merely to force a green cosmetic change.
5. After freeze, compare protected file/chunk hashes and run the selected current-source regression/build. Linux JavaFX output and fixture APIs are not Windows/customer or live commercial acceptance.

No concrete additional functional bug is alleged by this planning audit. The missing reference parity and possible data loss from future column removal are scope boundaries to verify during implementation.

## Independent final source review

Final read-only source review is **BOUNDED_PASS** against controller `c1b3030d8a13d4f6bcd35db0147dcfb4332853b48dd72e1d04a6461fea21e8aa` and FXML `35d39c4a08873999fbaa24e2891be2e14081c42cb18453f1a8db4635b125bab0`. All141 protected lexical chunks and323 protected production files remain byte-identical. All77 oldFXMLIDs, node types andonAction handlers are preserved,79total/0duplicates. No baseline3,807file missing, no unexpected source/test delta. Four locale files only append one Sort label each; old translation values remain unchanged.

Read actual current presentation, callback and test diffs. Shared original controls reparent without replacing query/batch state; no category filter was introduced. The old orderText and shipment-offset formatting remain. Canonical posting number becomes the visible main ID while full order/posting text remains in value factory/tooltip/drawer. Barcodes previously in a separate column now survive in the visible compact code line, full tooltips/accessibility and the real detail drawer. KN3 state, rule, variant, price and quantity remain. New row-number helper uses only currentTableView lists. Render code adds no seller/DB mutation/read.

Old test edits are justified requested-visual changes: remove the shipment-detail style/STT-column expectation, assert main five-column appearance while retaining the existing actual identifier/KN3 checks; move paging expectation above table and add usable-height check; update single/multi-item row metrics70/132. No business test or acceptance policy was weakened. New native parity test remains a separate execution gate.

Intermediate max-width review: a price-cap suggestion from an earlier captured diff is **withdrawn as a final finding** after fresh file verification. The owner reported real native vectors failing underDouble.MAX_VALUE order/image bounds and fixed those to WB's5000default; all four current price columns are uncapped. I did not edit source, run Maven, or call that provisional candidate a proved root cause. The rendered RED/GREEN evidence belongs to the native-test owner.

Receipt: `final-review-receipt.json`; exact current modified-source/test hashes: `final-source-sha256.json`. Native-focused and fresh combined verification remain parent/owner gates. No additional concrete blocker found in this bounded review; this is not certification of Windows/live services or all future states.

Final canonical recheck: all9files in `layout-audit/final-ui-source-freeze.json` match current source, including new parity-test SHA `ad91c7814357089beb6bce6e6c2251b2d6a9567449eafe915f4fc8b3c5efa1a8`. Read and parsed the owner's3freshJUnit reports:22tests,0failure/error/skip; these were executed by the owner, not this reviewer. Owner's rendered width vectors at1200×720/table1158 are exactly WB=Ozon `[46,228,127,594,159]`, with70px single-item row and36×48 photo checks included. Receipt records JSON/XML hashes and the verified9sourcehashes. Parent's fresh64class build/verification and final image capture remain separate gates.
