# Existing unified-engine fixture modernization

Owner C changed only `ZnackGtinWorkflowTest.java`, `ZnackModuleTest.java`, and `ZnackLicenseBoundaryTest.java` in this follow-up. Production source was not changed by C. B supplied the explicit protected `ZnackKizOrderService.createAdmissionCoordinator()` extension point, used only by the local transport test fixture to substitute native CryptoPro preflight while preserving signedness, quantities, GTIN and local-permit checks. There is no test detection or production legacy engine fallback.

## Reason for fixture updates

The replacement coordinator calls `advanceBatch`, not anonymous test overrides of the former one-GTIN `buy` path. New purchases also always retain circulation intent, pin their original profile, and use explicit signing admission. Old fixtures missing these contracts could fail before reaching the behavior they intended to test. These compatibility failures are not evidence of 24 new product defects.

The initial real test command executed 132 tests: 10 failures, 14 errors, 0 skips (`baseline.log`, `baseline-result.json`). The first updated run executed the same 132: 4 failures, 0 errors (`modernized-first.log`). The 76 Module and 3 License assertions all passed at that checkpoint; the remaining four workflow fixtures were corrected precisely, without changing production logic.

## Changes

- Workflow submission doubles now override the explicit batch seam. They use real `ZnackPurchaseBatchStore` canonical payload validation, beginSubmission CAS, persisted acceptance/reconciliation, and the actual member order ids. No extra synthetic legacy local draft is created in the new batch path.
- Retrieval doubles implement the explicit available-code and capped-download contracts, preserving the closed/exhausted recovery and per-order assertions.
- Synthetic reviewed profiles are saved before creating the test's paid/document pipeline. Signing consent is explicit in direct document fixtures. Mutable now-timestamps are not used as supposedly identical approval profiles.
- Exact header/signed-payload transport checks still send through the actual API client to an in-process localhost HTTP server. They do not contact a marketplace or CRPT account.
- Confirmed missing documents clear the stale local permit snapshot in the fixture, matching the real registry adapter. Under the new mandatory circulation intent, the actual coordinator schedules a bounded fresh registry read, without resubmitting a document or buying another order.
- The active-session signing test uses a valid synthetic full CIS and the actual fixture certificate fingerprint so identity validation and certificate-bound grants reach the signing guard. Its exact one-signature and no-wait assertions remain intact.
- Remote batch reconciliation is observed as its own persisted step before the later order poll. An additional intermediate POLLING_ORDER assertion was added, retaining the final stored-code/inventory assertions.

## Deliberate safety-policy updates

1. A lost create response followed by an empty remote list, even after an elapsed window, is insufficient evidence to authorize another purchase. The former test expecting the original job to fail and its sibling to buy was strengthened: original remains RECONCILING, sibling stays QUEUED, buy count stays one, circulated stock remains zero.
2. A known pre-HTTP runtime failure retains the approved request for safe retry. A further explicit request remains queued behind it; the fixture no longer expects the app to discard two approved intents as FAILED. This does not mark an order as sent or turn any stock available.

3. B confirmed the new mandatory-introduction policy retries fresh registry evidence after 30 seconds. The former no-timer assertion now verifies a check at least 25 seconds in the future, the same paid order, exactly one stored order, preserved codes and no document. This replaces manual-local-refresh dependence with bounded automatic recovery; it does not permit an immediate retry loop or duplicate submission.

No old safety test is skipped, no assertion suppression, no native/private profile, activation, real API account, paid order or signed legal document is used. `git diff --check` for the three changed files passed. Exact final test file hashes are in `frozen-test-hashes.json`. The parent broader snapshot initially captured the first updated four-failure fixture version. The next private checkpoint had 130/132 passing with only the fingerprint/timer issues; its receipt is retained as `modernized-final.log`. Parent was notified before each correction and should retain its initial broad receipt and refresh affected test sources explicitly.

Final focused runtime: **132/132 PASS, 3 suites, 0 failure/error/skip**, actual process46.601seconds (`modernized-final-v2.log`, `modernized-final-v2-result.json`, `test-summary.json`). This is76Module+53GtinWorkflow+3LicenseBoundary cases on the test hashes in `frozen-test-hashes.json`. Earlier attempts overlap and must not be added. Parent's all-application acceptance is separate.
