# FBS KIZ signing recovery — WB and Ozon

## Diagnosis and root cause

Central KizMappingController already exposed the explicit `resumeSigning(existingPipelineId)` action. Both WB SupplyDetailController and OzonDashboardController still rendered only the plus/buy action for a paid lot paused during download or introduction-readiness signing. Their existing retry action applied only to INTRODUCTION_FAILED. Therefore a seller could not continue that existing lot from the FBS inventory card; clicking plus proposed another paid intent instead. Actual JavaFX RED confirmed the missing action in both marketplaces and all four app locales.

First published-source provenance is owned by the separate historical researcher. This finding is a current consumer-parity gap, not evidence that copying the old purchase modules would solve the current flow.

## Changes

Only two production controllers and one new test file were edited by this agent. A paused card now exposes the existing localized resume action, shows the signing-paused status and hides/disables the buy action. The click delegates to the existing coordinator's resumeSigning with the frozen repository and exact existing pipeline id. It does not enqueue another purchase or change signer authorization. A small package-private injectable resumer is used by the action regression; its production default calls only the existing coordinator method. Rapid duplicate clicks and disposed views are guarded. Normal FIFO buy behavior and existing rejected-introduction retry behavior remain.

All prior Ozon registration navigation and strict purchased-KIZ assignment changes were retained. No Catalog, SUZ, Ozon exemplar, owner-auth policy, dependency, schema, style or release code was changed.

## Tests actually run

- Pre-fix real JavaFX RED:10 cases,8 assertion failures,0 errors,0 skips. The two unaffected normal-buy/introduction-retry cases passed. The first log wrapped the same assertion failures in FutureTask ExecutionException; that initial log remains. The classified RED helper unwraps assertion roots and records the correct JUnit failure category.
- GREEN:98/98 across11 suites,0 failures/errors/skips, offline cached JDK25/Maven/Xvfb with the shared Maven lock. New test class has14 cases: WB/Ozon×four-locales presentation, normal action preservation, exact repository/pipeline click routing with double-click debounce, and disposed-card rejection.
- Existing signing-session32 and signing-recovery26 cases, FBS source selection, prior Ozon registration entry, dashboard/cache isolation and list-area regressions passed in the same run.
- Source hashes stayed stable in the target and isolated source copy. git diff --check passed.

See green-command.json, green-process.json, green-result.json, owned-before.json, owned-after.json, red-result.json and archived JUnit for exact commands, counts and hashes. All test data is synthetic. The resume click fixture injects a callback to demonstrate which existing lot the UI delegates; existing coordinator signing/recovery tests establish the lower-layer contract separately. No fee, seller API write, real CryptoPro, Windows, activation, publication or live acceptance was performed or claimed.

## Rejected hypothesis

An early import-barrier mismatch candidate required an untagged IMPORTED order. The first published source contains no such importer and current import commit writes the imported order and its entry atomically. This agent did not establish an actual production producer for that candidate state, did not report it as a confirmed bug, and did not change stock queries for it.

## Remaining risk

The historical-source comparison and broader combined regression belong to the parent. Another agent owns summary selection when an older signing-paused pipeline is hidden by a newer queued/active row; this agent did not modify that repository. Real Windows/CryptoPro and paid SUZ/Ozon acceptance remain untested here.
