# Ozon order KIZ implementation (source frozen for combined review)

Backup was verified by parent before any feature edits. This work is on feature/ozon-znack-order-kiz-20261116 and preserves the existing dirty ECC/UI changes.

## Implemented

- New purchased Ozon bindings require exact native SKU/offer, resolved Catalog GTIN matching raw AI01, owned pool, circulation and the strict Ozon exemplar validate/set/status protocol. HTTP 200 and local PDF ACCEPTED are not acceptance proof. Full GS1, internal GS and crypto are preserved.
- Production standalone, batch cache, and already-printed print routes verify authoritative exact paid acceptance before a combined label can publish. A durable full-binding/owner receipt permits later exact reprints. Later exact rejection invalidates it.
- Paid candidate rejection rotates only the rejected exact slot. Actual downloaded provenance includes block, pipeline or an owned non-imported SUZ external order. Unknown submissions remain reserved/pending and reconcile before another write or charge. Terminal historical paid jobs are read-only.
- Acceptance and print replenishment include exact rejected bound deficits, omit accepted/pending/terminal/active replacement slots, preserve native split scoping, subtract eligible pool stock, and recheck the frozen demand before enqueue. Existing Apply consent, budget and pending pipeline logic is retained.
- New opt-in shared availability policy keeps Ozon counting and wait readiness aligned with its allocator. Default/WB overloads retain the prior count behavior. Strict Ozon allocation skips malformed/untyped stock without modifying unrelated stock. Imported/category stock retains the seller-approved GTIN-independent rule. Genuine local legacy entry points remain compatible.

## Files

- `src/main/java/com/vncode/app/integration/ozon/OzonAcceptanceKizReplacement.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonAcceptanceKizService.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonAcceptanceStockReplenishment.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonExemplarJobRepository.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonExemplarService.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonPrintBundleService.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonStockReplenishment.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonPurchasedKizAssignment.java`
- `src/main/java/com/vncode/app/integration/ozon/OzonPurchasedKizStock.java`
- `src/main/java/com/vncode/app/features/kiz/KizPurchaseAvailability.java`
- `src/main/java/com/vncode/app/features/kiz/KizStockReplenisher.java`
- `src/main/java/com/vncode/app/features/kiz/KizPurchasePendingException.java`
- `src/main/java/com/vncode/app/features/kiz/KizPrintPurchaseSession.java`
- `src/test/java/com/vncode/app/integration/ozon/OzonPurchasedKizReplacementTest.java`
- `src/test/java/com/vncode/app/integration/ozon/OzonPurchasedPrintAssignmentTest.java`
- `src/test/java/com/vncode/app/integration/ozon/OzonRejectedPurchasedDemandTest.java`
- `src/test/java/com/vncode/app/integration/ozon/OzonPurchasedStockPolicyTest.java`
- `src/test/java/com/vncode/app/features/kiz/KizPurchaseAvailabilityPolicyTest.java`

## Executed verification

- Strict print behavioral RED: 10/10 failures on original local-only preparation, then GREEN 10/10. Expanded print suite now 18/18.
- Manual SUZ null-block/no-pipeline origin RED 1 failure/0 errors, then fixed using owned external order provenance.
- Terminal real bundle repair regression RED (set count 2 instead of 1), then paid terminal guard fixed it.
- Exact bound-slot demand RED 10 cases/7 failures/0 errors, GREEN 10/10.
- Ozon stock counter/allocator RED 6 cases/5 failures/0 errors, GREEN 6/6.
- Shared availability RED 7 cases/3 failures/0 errors, GREEN 29/29 including unchanged replenisher/session suites.
- Integrated focused current source: 96/96 passed with no failures/errors/skips, covering all new feature suites and existing exemplar/readiness/shared suites. Two final replacement legacy/provenance-ordering tests were subsequently added; replacement target 13/13 passed.
- Earlier combined acceptance/bundle/shared/print regression: 178/178 passed before the final eligibility-policy additions. Existing bulk fixture retained batch official label downloads for 100 and 1000 orders. Parent will run the final combined branch regression after independent review.
- git diff --check passed. Exact source hashes and current XML evidence are stored beside this report in purchase-tests/final-evidence.

## Limits

All tests used synthetic isolated SQLite, local HTTP servers or existing deterministic fixtures. No seller API request, paid purchase, signing, real order mutation, installer build, commit, push or release was performed by this agent. Real Catalog publication and paid SUZ/Ozon acceptance still require the real runtime and authorized configured credentials; no claim of live commercial acceptance is made.
