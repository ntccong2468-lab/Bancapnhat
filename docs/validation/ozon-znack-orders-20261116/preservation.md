# Final source preservation audit — 16.11.2026

Compared the current source with the verified pre-task manifest of 3,740 files. Current source has 3,757 files. No baseline file is missing.

Exactly **24 source/test files** differ:14 existing production files modified,3 production files added and7 tests added. All existing tests remain byte-identical.

All32 prior dirty ECC/UI files remain present:30 are byte-identical, including AGENTS, the three ECC skills, prior Znack controller/FXML and UiLayoutProbe. The two changed files are the intended memory extensions (`docs/PROJECT_MEMORY.md`, `docs/memory/CURRENT.md`); earlier content remains.

The only shared production delta is the approved four-file availability extension:

- `src/main/java/com/vncode/app/features/kiz/KizPurchaseAvailability.java`
- `src/main/java/com/vncode/app/features/kiz/KizPrintPurchaseSession.java`
- `src/main/java/com/vncode/app/features/kiz/KizPurchasePendingException.java`
- `src/main/java/com/vncode/app/features/kiz/KizStockReplenisher.java`

No POM/dependency/build, schema/migration/Database, CSS, CI, WB-specific production or release-metadata file changed. No source file was deleted. Purchase-owner18 and registration-owner6 file hashes still match their final receipts. The reviewer-only ticket fixture is absent from the target.

## Exact feature delta

| Change | File |
|---|---|
| modified | `src/main/java/com/vncode/app/features/kiz/KizPrintPurchaseSession.java` |
| added | `src/main/java/com/vncode/app/features/kiz/KizPurchaseAvailability.java` |
| modified | `src/main/java/com/vncode/app/features/kiz/KizPurchasePendingException.java` |
| modified | `src/main/java/com/vncode/app/features/kiz/KizStockReplenisher.java` |
| modified | `src/main/java/com/vncode/app/integration/ozon/OzonAcceptanceKizReplacement.java` |
| modified | `src/main/java/com/vncode/app/integration/ozon/OzonAcceptanceKizService.java` |
| modified | `src/main/java/com/vncode/app/integration/ozon/OzonAcceptanceStockReplenishment.java` |
| modified | `src/main/java/com/vncode/app/integration/ozon/OzonExemplarJobRepository.java` |
| modified | `src/main/java/com/vncode/app/integration/ozon/OzonExemplarService.java` |
| modified | `src/main/java/com/vncode/app/integration/ozon/OzonPrintBundleService.java` |
| added | `src/main/java/com/vncode/app/integration/ozon/OzonPurchasedKizAssignment.java` |
| added | `src/main/java/com/vncode/app/integration/ozon/OzonPurchasedKizStock.java` |
| modified | `src/main/java/com/vncode/app/integration/ozon/OzonStockReplenishment.java` |
| modified | `src/main/java/com/vncode/app/ui/ozon/OzonDashboardController.java` |
| modified | `src/main/java/com/vncode/app/ui/shop/ShopSidebarController.java` |
| modified | `src/main/java/com/vncode/app/ui/workspace/HomeController.java` |
| modified | `src/main/resources/com/vncode/app/ui/ozon/ozon-dashboard-view.fxml` |
| added | `src/test/java/com/vncode/app/features/kiz/KizPurchaseAvailabilityPolicyTest.java` |
| added | `src/test/java/com/vncode/app/integration/ozon/OzonPurchasedKizReplacementTest.java` |
| added | `src/test/java/com/vncode/app/integration/ozon/OzonPurchasedPrintAssignmentTest.java` |
| added | `src/test/java/com/vncode/app/integration/ozon/OzonPurchasedStockPolicyTest.java` |
| added | `src/test/java/com/vncode/app/integration/ozon/OzonRejectedPurchasedDemandTest.java` |
| added | `src/test/java/com/vncode/app/ui/ozon/OzonRegistrationEntryUiTest.java` |
| added | `src/test/java/com/vncode/app/ui/workspace/OzonRegistrationNavigationTest.java` |

Detailed before/after SHA-256 and owner checks: `preservation.json`. This audit did not run Maven or change the repository. Parent may update memory documentation after this capture.
