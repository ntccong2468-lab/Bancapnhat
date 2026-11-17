# Owner D: WBCode 1.3.26 protocol and circulation port

Scope: VN-owned API routing, SUZ issuance/retrieval, introduction readiness, document creation/confirmation. No version bump, release, production credentials, paid SUZ purchase, real signing, license activation or marketplace mutation performed.

## Implemented behavior

- Original approved purchase context determines account, group and method. `lp`/template 10 and `shoes`/template 1 survive later current-product/profile changes. Old APIs default to LP and dispatch old virtual adapters for compatibility with registration/GTIN integrations.
- Production creates `LP_INTRODUCE_GOODS` with verified current permit documents. Commission creates `LK_INDI_COMMISSIONING`, preserves paid receiving date and does not invent production declarations or TN VED.
- Read-only owner registry lookup tries configured owner, authenticated participant and unfiltered results. Pending, confirmed empty, malformed and transport failures stay distinct. Paperwork is read after downloaded codes, not used to repeat issuance.
- One precise-account token refresh is allowed for true read-only status, card, registry and issued-block reads. Consuming GET `/codes`, order POST and document POST are never implicitly replayed for expired sessions or 1090-shaped responses.
- Per-OMS request pacing and Retry-After hold requests without globally blocking Catalog or independent accounts. Interruption before transport is typed known-unsent admission deferral.
- Normal issuance requests use the lesser of durable remainder and currently available buffer count. A durable per-download target plus the existing write-ahead marker identifies a lost capped package. Only exact recovery, successful persistence, or a definitive known-unsent/rejected request clears that attempt; unknown outcomes use issued-block replay and never buy or issue again.
- Original VN safeguards remain: whole-package identity/cardinality, full CIS/GTIN/owner proof, raw GS/crypto strings, duplicate and consumed-code preservation, and no false stock before authoritative introduction confirmation.
- Signing admission is captured before authentication/signing and checked around the actual document HTTP send. Known local admission failures are `NOT_SUBMITTED`; ambiguous remote outcomes remain reconcilable against their original receipt without duplicate document submission.

## Source ownership

Modified: `ZnackApiClient`, `ZnackKizCodeService`, `ZnackIntroductionService`, `ZnackIntroductionReadinessService`.
Created: `SuzRequestPacing`, `ZnackPurchaseProtocolContext`, `ZnackPurchasePermitResolver`.
Owner A supplies schema/repository target/context and document-type storage; B supplies batch engine and buffer availability; C supplies original-profile session/auth/signing admission.

## Focused tests

Six D classes: `Wb1326ProtocolApiRoutingTest`, `Wb1326SuzPacingTest`, `Wb1326PermitResolverTest`, `Wb1326DownloadAvailabilityTest`, `Wb1326IntroductionMethodTest`, `Wb1326SharedOrderDownloadTest` (60 invocations).

Actual defect evidence:

1. Checkpoint 3 found repository Gson reflection failure serializing a non-null `Settings.createdAt` Instant under Java 25. Owner A fixed the serializer; no `--add-opens` workaround.
2. Checkpoint 4 reproduced lost capped delivery 3/5 remaining stuck in uncertainty after exact issued-block recovery. Added durable per-download target and fixed the service.
3. Checkpoint 5: 57/59 passed; two wire-level tests proved generic low-level 1090 handling repeated the paid order POST. Order creation now uses a non-repeating transport; durable batch engine owns retry evidence and schedule.

Final green verification: checkpoint 7 compiled all 778 main and 646 test sources, then passed 181/181 tests (60 new D cases plus 121 existing protocol regression cases), zero failures/errors/skips, Maven BUILD SUCCESS in 51.985 seconds. All 15 D-owned production/test files match the verified snapshot; see `D-FREEZE.json`. The additional durable-target case proves recovery still works when a later status refresh clears the displayed error marker. Historical component passes are retained as evidence and are not substituted for this integrated snapshot.

Cloud limits: all protocol endpoints in these tests are loopback or synthetic adapters with isolated SQLite. Real paid SUZ success, physical Windows CryptoPro token/PIN and actual True API legal circulation are not claimed.

## Final display polish

Only the exact transient `CRYPTOPRO_KEY_NOT_CONNECTED` deferred marker now tells the user to connect the USB signing key and await automatic retry. Every other durable signing pause retains the explicit resume instruction. Dedicated EN/VI/RU/ZH translations and exact-marker regressions were added. A private component run reproduced four wrong-message failures (one per locale), then passed all 30 message tests, zero failures/errors/skips. Existing signing-confirmation and recorded-error-code assertions remain intact. See `KEY-MESSAGE-FREEZE.json` and `key-message-{red,green}/RESULT.json`. These extra six files are presentation-only and were verified separately after the 181-case protocol snapshot.

## Historical order readback integration fix

The first broad integration run exposed a genuine routing regression: `OrderService.refresh(Settings,id)` discarded the explicit snapshot for an imported order without a persisted pipeline and used the default SUZ host. B changed exactly the context lookup to use the explicit snapshot as the legacy fallback; persisted paid context still wins. The original seven-case failure XML is archived unchanged in `readback-integration/INITIAL-FAIL.xml`. The seven existing GTIN/buffer/quantity/checkpoint assertions remain, with two additional historical/pinned-profile cases and actual loopback HTTP proof of original OMS/order/GTIN/token. Final private proof passed **9/9**, zero failures/errors/skips. See `readback-integration/READBACK-FREEZE.json`. No production changes were made by D in this pass.

## Exhausted/closed legacy recovery fixture modernization

Two old regression fixtures were adapted to the new explicit availability seam and to pin the correct tested original profile before enqueue. No production change was needed. Every prior ordinary-once, no-rebuy, restart replay-only, foreign OMS/order/GTIN/block, full-quantity, whole-list atomic validation, and consumed-stock assertion remains. Added CLOSED status recovery and exact raw GS/crypto payload preservation. Initial broad failures are archived, and the final private run passed **28/28** (27 recovery cases plus one workflow regression), zero failures/errors/skips. See `exhausted-integration/EXHAUSTED-FREEZE.json`.
