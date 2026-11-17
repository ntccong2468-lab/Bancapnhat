# Purchase entrypoints and persistence compatibility — read-only audit

17.11.2026. Target `/workspace/vncode-ios-motion-20261114`; source was read only. No app/test/config edits, runtime tests, purchases, signing or API writes.

## Shared boundary

All actual SUZ paid POST calls are already centralized: **Coordinator → KizOrderService.buy → ApiClient.createOrder**. No separate GS1 purchase producer was found. Preserve the public Coordinator API as the compatibility facade while replacing its internals; changing every FBS UI handler is unnecessary.

| Entry | Actual caller | Shared route |
| --- | --- | --- |
| Manual GTIN purchase | `ui/kizmapping/KizMappingController.java:322-343` | ZnackPurchaseCoordinator.create → enqueueForCirculation → enqueue → advance → ZnackKizOrderService.buy → ZnackApiClient.createOrder |
| WB shipment GTIN card | `ui/supply/SupplyDetailController.java:845-878` | ZnackPurchaseCoordinator.create → enqueue → enqueueForCirculation → advance → ZnackKizOrderService.buy |
| Ozon FBS GTIN card | `ui/ozon/OzonDashboardController.java:1610-1630` | ZnackPurchaseCoordinator.create → enqueue → enqueueForCirculation → advance → ZnackKizOrderService.buy |
| WB print shortage | `ui/workspace/HomeController.java:1147-1179 -> features/print/OrderExportWorkflow.java:64-73` | KizPrintPurchaseSession.preflight/run → KizStockReplenisher.ensure/ensurePurchased → KizPurchaseMappingProof.require → validatePrerequisites → enqueueForPrint/enqueueForCirculation → resumeAsync |
| Ozon print shortage | `ui/ozon/OzonDashboardController.java:856 -> integration/ozon/OzonStockReplenishment.java:46-55` | KizPrintPurchaseSession.run → KizStockReplenisher.ensure/ensurePurchased → KizPurchaseMappingProof.require → validatePrerequisites → enqueueForPrint/enqueueForCirculation → resumeAsync |
| Ozon acceptance shortage | `integration/ozon/OzonAcceptanceStockReplenishment.java:22-27,102-108` | KizPrintPurchaseSession.run → KizStockReplenisher.ensurePurchased → KizPurchaseMappingProof.require → validatePrerequisites → enqueueForPrint/enqueueForCirculation → resumeAsync |
| FBO confirmed shortage | `ui/workspace/HomeController.java:759 -> features/fbo/FboPurchaseService.java:10-29` | FboPreflightService.inspect → validatePrerequisites(all GTINs) → enqueue(per-GTIN deterministic requestKey) → resumeAsync |
| Startup/background recovery | `ui/workspace/HomeController.java:1353-1354,1401-1427` | authorizeInitialZnackShop → ZnackPurchaseCoordinator.resumeAllPersisted → resume → resumeEligibleIntroductions |
| Explicit shop selection recovery | `ui/workspace/HomeController.java:1378-1398` | ZnackSigningSession.authorizeShop → ZnackPurchaseCoordinator.resume → resumeEligibleIntroductions |
| Paused paid lot resume | `ui/kizmapping/KizMappingController.java:365-372; ui/supply/SupplyDetailController.java:81; ui/ozon/OzonDashboardController.java:146` | isSigningConfirmationPending → resumeSigning(existing pipelineId) → boundPipelineSettings → authorizePipelineResumption → advance → schedule |
| Rejected introduction retry | `ui/kizmapping/KizMappingController.java:352-355; ui/supply/SupplyDetailController.java:879-895; ui/ozon/OzonDashboardController.java:1631-1643` | retryIntroduction(existing pipeline) → advance → schedule |
| Insufficient funds retry | `ui/znack/ZnackInsufficientFundsDialogService.java:75-76` | retryInsufficientFunds(existing pipeline) → same requestKey and pipeline → advance |
| Document configuration/sync resumes | `ui/kizmapping/KizMappingController.java:195,605; ui/supply/SupplyDetailController.java:1020; ui/ozon/OzonDashboardController.java:1728; ui/znack/ZnackAutomationController.java:1618` | resumeEligibleIntroductionsAsync → same original profile and codes |

## Required compatibility boundaries

- Keep all existing Coordinator public methods, especially `enqueue`, `enqueueForPrint`, `enqueueForCirculation`, `validatePrerequisites`, `advance`, resume/retry methods, `nextCheckAt`, `isSigningConfirmationPending`, and factory/constructor test seams. Twenty test source files directly use Coordinator/OrderService/queue contracts.
- `KizStockReplenisher.Purchases` uses validate/enqueue/resume. FBO validates all rows before creating first checkpoint. FBS uses `KizPrintPurchaseSession` approval totals and spent-per-GTIN limits; batching must never exceed the approved intent.
- Keep persisted PurchaseStage string values (or adapt every reader); UI resume switches, inventory summaries, pending purchase suppression and allocator SQL reference them directly. Preserve same-GTIN FIFO/idempotent request keys.
- Preserve the existing original-profile snapshot: pipeline ID, request key, GTIN, quantity, local order ID, remote order ID, OMS, certificate/owner, raw codes and legal/inventory statuses. Recovered legacy opt-out consent is a data-compatibility branch, not permission to create fresh orders/documents.
- Existing `CONSUMED`/reserved codes cannot become AVAILABLE after recovery; duplicate/unknown-result reconciliation must remain attached to the original paid order.

## Hidden old prerequisite outside Coordinator

`KizPurchaseMappingProof.require` invokes `requireKnownPermit` after variant identity checks. WB print (`OrderExportWorkflow:70`), Ozon print (`OzonStockReplenishment:49`) and Ozon acceptance (`OzonAcceptanceStockReplenishment:104`) all use it before paid enqueue. **Removing only the Coordinator global Catalog-revision gate does not replace the FBS gate.** Split paid local-permit validation from variant/source identity; keep `resolve()` and source-change guards used by mapping, printing and GTIN paths.

## Multi-GTIN batching: important storage constraint

`ZnackSchemaSupport:171–189`: `kiz_orders` is one GTIN per local row and has `UNIQUE(shop_id,external_order_id)`. A single SUZ order containing multiple GTINs cannot be attached to N such rows unchanged.

A parent/sidecar batch store is possible, but **leaving member external IDs NULL breaks existing Ozon paid-proof SQL**:

- `OzonPurchasedKizStock:62`.
- `OzonPurchasedKizAssignment:35`.
- `OzonAcceptanceKizReplacement:159,189`.

Those statements read `kiz_orders.external_order_id` directly. There is no purchase repository find-by-external-order query elsewhere: direct field update is Repository702, mapper917. Print-history external IDs are marketplace order IDs and unrelated.

A reviewed migration to uniqueness `(shop, external_order_id, gtin)` plus a batch-parent/member sidecar could retain real external IDs and existing Ozon SQL. Only one active GTIN request may join a remote batch; additional same-GTIN requests keep FIFO or must have explicit allocation bookkeeping. Preserve every local order/CIS/document ID and FK. Existing schema import migration (`ZnackSchemaSupport:114`, positional INSERT SELECT) needs care; test old databases, backups/restore, `integrity_check`, `foreign_key_check` and consumed/reserved stock.

`znack_documents` already allows several local rows with the same external document ID, so an introduction batch can preserve per-order local document rows while submitting once. The new batch store must map them; existing `findLatestDocument(orderId)` consumers need a coherent result.

## Keep outside replacement scope

- WB/Ozon product/card sync, Znack registration, GTIN/model/size mapping rules and registry readback not specific to a purchase.
- Ozon exact paid-code acceptance per posting/product/exemplar; imported seller KIZ category policy; actual label/picking/PDF generation.
- FBS receipt/batch pagination/status transitions, FBO preview/print, approved UI and motion, license/updater and credential storage.
- Existing cash/consent/licensing scope. This replacement changes execution architecture, not entitlement or merchant identity.

Machine-readable contracts: `ENTRYPOINTS.json`.
