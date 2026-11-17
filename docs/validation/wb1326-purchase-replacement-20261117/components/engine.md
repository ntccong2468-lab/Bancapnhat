# Owner B — common WBCode 1.3.26 purchase orchestration

Implemented independently against the observed 1.3.26 behavior; no decompiled source was copied wholesale. No real purchase, signature, activation, release, version bump or private-profile mutation was performed.

## Production scope

- `ZnackPurchaseCoordinator`: every current manual/FBS/FBO producer enters one durable batch admission. Snapshot method before preflight, validate the complete approved list before enqueue, preserve request replay and original settings. No global full-Catalog revision requirement at purchase admission; production requires a complete local goods document.
- `ZnackKizOrderService`: one canonical signed payload for up to ten compatible distinct GTINs, one paid POST, all-member signing permits at the HTTP boundary, atomic shared order projection. Direct `buy` is a one-member compatibility facade through the same admission policy; it returns its own GTIN and schedules every grouped member.
- Strict definitive quota3350 produces a durable shared-account hold. Local prewire pacing and actual429 keep the original approved batch pending. Strict1090 no-order proof creates single-GTIN recovery children with the original approval lineage and bounded 0/5/15-minute timing. Unknown transport/408/5xx/malformed200 responses enter reconciliation and cannot trigger a blind paid retry.
- Shared status reads remain scoped to the exact account/order/app-data profile and distinguish every GTIN's buffer. Fresh code downloads use the available amount. Persisted issuance targets and uncertain receipts recover already-issued blocks even when a status refresh erased a display message.
- `ZnackPipelineQueueExecutor`: CREATE4 / RETRIEVE2 / INTRODUCE2 per shop, FIFO across stages for the same GTIN, independent GTIN progress, cleanup after failed work and shutdown-generation admission.
- Timers, signing pauses and license pauses project to all batch members. Production runtimes retain token caches while their original settings and signing generation remain valid; changed original profiles recreate the whole signer/auth graph.
- `ZnackPurchasePreflight`: local permit admission only; immutable native variant proof, strong full-CIS/live-owner readback and Ozon acceptance remain separate protections.

## Verification

- Queue baseline: 2 tests compiled and executed against the original queue; one assertion failure showed unrelated GTINs blocked behind one worker. Current queue: **11/11 PASS**. `queue-tests/SUMMARY.json`.
- Coordinator regression proof: two identical tests against pre-fix checkpoint2 compiled then failed **2 assertions, 0 errors**. Fixed source: **4/4 PASS**. `coordinator-tests/pre-fix-checkpoint2-red/RESULT.json` and `coordinator-tests/RESULT.json`.
- Legacy download/circulation safety suites: **63/63 PASS**, retaining full-CIS, owner, consumed-stock and no-rebuy assertions. `legacy-fixture-tests/RESULT.json`.
- Batch engine branch tests: **21/21 PASS**, final refreshed run3 with every recorded source/test hash matching live. `batch-tests/run-3/RESULT.json`.

The first attempted Coordinator control snapshot already contained the fix and passed; its receipt explicitly says it is not RED evidence. Three first-run batch-test failures were incomplete fixture expectations and are not claimed as production RED.

Final combined frozen checkpoint: **99/99 PASS**, zero failures/errors/skips; all recorded current integration source and five test-file hashes match live. `final-99/RESULT.json` contains exact javac/offline Surefire commands and source hashes. This total includes 11 queue + 4 Coordinator + 21 batch + 63 legacy cases; do not add earlier receipts again.

## Intentional old-fixture changes

`ZnackAmbiguousCodeDownloadTest` now saves an explicit original profile, seeds a genuine pre-replacement download-only snapshot, and supplies an explicit status-availability seam. `ZnackCirculationPolicyTest` does the same for historical rows, injects capped retrieval, expects the newly mandatory global circulation flag while comparing every other setting unchanged, and pins the runtime generation when explicitly injecting a service graph. No test was skipped or disabled.

## Limits

The focused receipts execute synthetic APIs and temporary SQLite profiles. They do not prove an actual paid SUZ purchase, Windows CryptoPro hardware behavior or marketplace acceptance. Complete frozen app regression and the consolidated release inventory belong to the parent integration run.

## Integration follow-up

The parent full regression found 16 obsolete assertions in `KizPurchasePermitEvidenceTest`: native variant proof formerly performed the global Catalog permit gate. The same 21 cases now verify independent native identity, wrong-SKU rejection, local Production permit protection, Commission's metadata exemption and retained rich Catalog diagnostics. **21/21 PASS** with a matching test hash; no production change was needed for these assertions. `permit-policy-21/RESULT.json`.

A separate genuine legacy readback regression discarded explicit caller settings for imported orders without a pinned pipeline. The authorized one-line fix uses `contextForOrder(id, settings)`: pinned paid profiles still take precedence, while legacy read-only callers retain their explicit endpoint. D owns the strengthened HTTP request-identity/pinned-context tests. After this production fix the complete focused B set was rerun: **99/99 PASS**, all 55 recorded hashes match live. `final-99-v2/RESULT.json` and `B-FREEZE-v2.json` supersede the earlier freeze for this one-line change. The 99 + 21 classes are disjoint, totaling 120 focused cases; earlier runs are repeated evidence, not additional tests.

The original one-case queue regression expected all GTINs in a shop to run serially. Its test now keeps deterministic assertions for same-GTIN FIFO and exception cleanup, while proving another GTIN can progress during a held task. No production queue change was needed. The modernized old case plus the existing 11 new queue cases pass **12/12**, with a matching test hash (`queue-compat-12/RESULT.json`). This adds one distinct case to the earlier120, totaling **121 distinct focused cases**; do not count the repeated eleven twice. `B-FREEZE-v3.json` adds only that test file.
