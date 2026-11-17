# Shared KIZ purchase repair — source frozen

Target `/workspace/vncode-ios-motion-20261114`, base HEAD a7c4d008 plus preserved dirty ECC/UI/Ozon work. New-task baseline is the parent's verified3767-file snapshot `/workspace/kiz-first-release-audit-20261116/baseline-source-manifest.json`, not Git HEAD alone. Parent assigned these production boundaries after the read-only audit and meaningful RED.

## Confirmed diagnosis

1. `ZnackApiClient.codes` explicitly disables automatic HTTP retries because GET/codes consumes a paid buffer. Normal `ZnackKizCodeService.download` previously had no durable uncertain-delivery receipt; a lost response could lead the next service/coordinator invocation to issue another block, rather than replaying the already-issued package. The independent WAL test owner reproduced this for WB and Ozon; a direct manual download shared the same gap.
2. Ordinary download stored its response immediately, while recovery already checked supplied OMS/order/GTIN metadata. Foreign raw GTIN, too many distinct new codes and conflicting logical CIS payloads could poison durable stock. The coordinator later requires exact quantity and request identity, producing an indefinite readiness wait rather than a correct complete purchase. HTTP200 bodies carrying business errors could also store stock and clear the uncertainty receipt.
3. The print-purchase session checked only the latest pipeline stage. A signature pause remains an active stage but deliberately has no automatic signer poll. The session kept waiting every2seconds forever; an older paused lot could be hidden behind a queued/newer active lot. Existing central signing-resume logic was not reached while the UI remained busy.

These are concrete local protocol/state defects, not proof that any specific historic seller failure had exactly these causes. Original-public-source identification and before/after comparison are owned by the separate historical researcher.

## Eight production files

- `integration/znack/ZnackKizCodeService.java`: normal and issued-block package validation before stock insertion; exact stored/request/rawGTIN/logicalCIS/cardinality checks, string-only code entries, business-error rejection, full original GS1/crypto retained. Opaque legacy **strings**, exact repeats and valid partial deliveries remain supported. Existing code/consumption/legal states are not overwritten.
- The same service writes `SUZ_CODE_DOWNLOAD_UNCERTAIN` to the original order after authentication succeeds and immediately before the consuming request. A successful validated durable store clears it. Definite4xx excluding408 restores the old state; IO/5xx/408/invalid-success/persistence uncertainty leaves the receipt. Reentry with the receipt only reads/replays issued packages; empty/partial/unavailable replay cannot authorize another ordinary issuance or paid order. Full original quantity clears it. Recovery401/403 surfaces the original authentication error while retaining the receipt; signature deferral is not wrapped. No schema or API-wire change.
- `features/kiz/KizPrintPurchaseSession.java`: inspect all same-GTIN active signing pauses, not just latest stage; unwind through `PurchasePaused` with original pipeline ID/GTIN and existing localized diagnostic/action. Retain original request, journal and stock, do not authorize signing/enqueue/print. Normal network-pending waiting still works; sufficient stock still returns before checking unrelated pauses.
- `integration/znack/ZnackErrorMessages.java`, `shared/FriendlyErrorService.java` and four `messages_{vi,en,ru,zh}.properties`: one recovery message per locale and exact-marker formatting. A nested old SocketTimeout cannot hide the durable recovery reason behind generic VN_NETWORK. No unrelated formatter changes.

No Coordinator mutation, dependency, POM, migration/schema, CSS, WB/Ozon API endpoint or seller-import policy changes were made by this owner. Existing Ozon exact accepted-code proof and paid allocation safeguards remain.

## Tests and actual execution

Three new owned test classes: `ZnackNormalDownloadIdentityTest`18, `KizPrintPurchasePauseTest`12, `ZnackDownloadRecoveryMessagesTest`4. No existing test modified by this owner.

- Normal-identity RED13cases:9assertionfailures,0errors; GREEN46/46 before WAL/business expansion. Evidence `download-review/red-result.json`, `green-result.json`.
- Business-error RED5cases:4assertionfailures,0errors; valid zero-error/empty-errors control passed. Final **GREEN51/51** =newnormal18 +old purchase-readback7 +EXHAUSTEDrecovery26; exit0,0failure/error/skip. Evidence `download-review/business-green-result.json`, command and XML hashes beside it. The first positive null-vs-empty fixture mismatch is retained separately and is not counted as feature RED.
- Session/presentation RED14cases:12assertionfailures,0errors (8paused-hang assertions and4missing-localization assertions),2ordinary-network controls passed. Added2older-active cases afterwards. Final **GREEN35/35** =newpause12 +newlocale4 +existing session12 +availability7; exit0,0failure/error/skip. Evidence `pause-red-result.json`, `pause-green-result.json`. The first build failed before tests because a concurrently captured unrelated new fixture called nonexistent findPipelines; corrected fixture only, retained `pause-red-fixture-compile-failure.log`, not feature RED.
- Parent-assigned independent WAL owner separately added36cases for WB/Ozon transport ambiguity, process interruption, manual download, replay identity/cardinality/types, auth-before-request and definite client rejection. Their RED/GREEN receipts are in sibling `uncertain-download/`; their final receipt confirms **36/36 PASS**, exit0,0failure/error/skip and the same frozen CodeService/test hashes. Do not count that as this owner's execution or add overlapping run counts to the parent's final combined suite.

All runs used cached JDK25/Maven3.8.5, offline dependencies, isolated source/data and shared `/workspace/kiz-first-release-audit-20261116/maven.lock`. No real SUZ/Catalog/Ozon seller request, paid purchase, signing or license activation. Localhost HTTP and synthetic SQLite establish local protocol behavior, not commercial acceptance or native Windows/CryptoPro behavior.

## Frozen inputs

`final-source-sha256.json` enumerates11 owned files (8production/resources +3newtests). Current CodeService SHA256 `5a421a3edf2865a5fab9f939243a41eab80b298759c585672e5420da5bf8a42f`. All recorded hashes match target at handoff; gitdiffcheck passed. No further edits planned unless the final combined regression finds a concrete failure. Parent owns final broad regression, preservation/report/memory and any later publication decision. No commit, push, installer or release by this owner.
