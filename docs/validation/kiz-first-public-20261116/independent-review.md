# Independent final purchase-flow review — 16.11.2026

**Bounded read-only pass. No additional concrete blocker found in the reviewed frozen change.** No source edits, tests/Maven, live API, purchase, signing, license activation or release were performed by this reviewer in this task. Parent's fresh combined build/146-class run and JAR/Launcher validation are separate gates; previous1,954 feature results are not evidence for this new shared fix.

## Diagnosis and scope

Read Code Doctor, the three adapted ECC skills, AGENTS/current memory, the new3,767-file baseline and the historical research report. The first currently reachable public release was verified by the research owner as Vncode1.1.33/source3b5c725; it also contained the old partial-delivery/owner shortcomings. This review does not recommend restoring old modules.

Reviewed the exact new-task diff against the3,767-file backup, including untracked tests. The canonical freeze contains24 files:14 existing production/resource files,3 existing test fixtures and7 new tests. All24 hashes match canonical source-freeze.json. No missing baseline file or unexpected source/test delta was found. Prior ECC/AGENTS/Znack UI/probe, Ozon exact acceptance/import/allocator/stock policy, shared availability/replenisher/pending exception, API transport/CIS helper/signing policy and POM remain byte-identical to the new-task baseline. Parent's intentional docs/memory additions are outside source freeze.

## Reviewed protocol and callers

- Normal downloaded packages and issued-block replays now validate the full batch before inserting any code: supplied OMS/order/GTIN identity, expected original order, remaining cardinality, logical CIS uniqueness, unchanged full payload for repeats, string-only code entries and success/business-error shape. Whole-batch checks prevent a later malformed entry from leaving an earlier entry partially persisted. Existing raw GS/crypto is retained; logical normalization is comparison-only. Opaque legacy strings remain supported and are not certified as valid commercial codes.
- The order-local uncertain-delivery marker is written after authentication succeeds and immediately before the consuming GET/codes. Existing API transport already disables retry for that endpoint. Timeout/408/5xx/lost or malformed success/storage uncertainty retains the marker. Reentry reads/replays issued blocks only; empty, partial or unavailable block lists never permit another ordinary issue or new paid order. Definite4xx restores the prior unissued checkpoint. Recovery401/403 retains the marker and surfaces auth; signing deferral is not hidden. Coordinator normal exception paths do not clear the order marker. CLOSED/EXHAUSTED paths retain their independent inactive-state fence.
- Automatic circulation and document confirmation now require exact per-CIS/GTIN and original-owner readback. Expected owner derives from original bound settings/participant/certificate, never current shop settings or a remote claimed owner. Missing/foreign/numeric/mixed owner keeps pending; APPLIED/product-card behavior and legacy inspect semantics are unchanged. The Coordinator's one pending-line change preserves the diagnostic instead of inventing completion.
- FBS waiting uses the existing scoped availability provider. It unwinds on an actual signing pause with original pipeline ID/GTIN, releasing the busy UI without authorizing a signer, creating a buy or printing prematurely. Summary scanning exposes an older active paused lot hidden by a newer queued/active row. Both WB and Ozon cards route the explicit resume click to existing resumeSigning(repository, exactPipelineId), retaining original settings/checkpoints. Normal FIFO buy and failed-introduction retry remain separate.
- Prior strict Ozon paid assignment logic is unchanged. Full owner/posting/product/exemplar/code readback is still required; no HTTP200/global-count/local-printed bypass was added. Imported seller category/GTIN policy remains unchanged. No dependency, schema/DDL, design, version or authorization gate was weakened.

## Concurrency question examined without a fabricated finding

CodeService's public method itself has no CAS, but exhaustive src/main caller search found only Coordinator.codes.download at684, with construction only in its factory at84. Scheduler, card/FBS resume and foreground/background flows all enter advance, protected by static RUNNING keyed shopId:pipelineId. Queued same-GTIN mutation is activated in a BEGIN IMMEDIATE transaction only when no active mutation stage exists; order recovery lookup excludes already-linked orders. There is no separate manual-download UI/production producer. Direct service invocations in synthetic tests are not evidence of a reachable app race. No speculative lock/CAS edit or extra concurrency test was requested.

## Tests, fixtures and selector evidence

Independently recalculated the prepared selector from current file paths and rules: exactly146 test classes, no duplicate simple class names, no omitted changed/new test. The actual fresh verifier selector matches all146 paths and includes all10 changed/new tests. It covers shared purchase/circulation/signing/readback/transport, FBS WB/Ozon consumers, the previous Ozon paid/print/import suites and current owner-aware UI navigation. No additional critical omitted consumer was identified from the traced delta.

Three existing fixture diffs provide legitimate synthetic original owner and matching healthy INTRODUCED ownerInn. All assertion kinds/order remain unchanged:239 in ZnackGtinWorkflowTest,37 in ZnackIntroductionIdentityTest and386 in ZnackModuleTest. Negative payloads are preserved; one negative parent/child-status fixture now has valid expected owner so it cannot pass merely from missing configuration. No assertion, parameterized case, expected outcome, disable or assumption bypass was removed/added to force green.

Read recorded focused RED/GREEN receipts (295circulation,98UI,97summary,51normal/readback,35pause/presentation,36WAL/replay), all actual exit0 at final owner handoff. These runs overlap and were executed by their owners, not by this reviewer; they are not added together or substituted for the pending fresh combined run. Hashes in owner/canonical manifests match the reviewed source.

## Remaining limits

No further concrete blocker found in this bounded review. Final clean verify/package/actual Launcher and its artifact identity are the verifier's work. Real SUZ issuance, original-owner circulation, Catalog/seller acceptance, native Windows/CryptoPro/DPAPI and recovery after upstream closure/lock are not established by synthetic fixtures or this source review. Existing security debt and unrelated app functions are not certified bug-free.

Evidence: final-review-receipt.json, final-source-sha256.json, current-vs-task-baseline.patch; original provenance/official SUZ contract evidence remains in ../original.
