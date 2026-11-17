# Owner C — WBCode 1.3.26 signing, recovery and purchase preferences

Status: source implemented and frozen for parent integration. No commit, version change, installer, release, private profile, CryptoPro signing, paid order or seller API write performed by this owner.

## Verified source and intended behavior

The behavior model is the authenticated WBCode 1.3.26 JAR `d10efde1c0ef6e6f8cf3e850e1dd7615966b0aa1e83082181f6c8dd26380f1b6`, reviewed using the earlier `wbcode/ui-signing/REPORT.md`, independent CFR observations and `javap`; `wb-key-presence-javap.txt` records the additional public-key presence reads. Implementation is VNCode-owned code, not a wholesale copy of decompiled source.

The parent verified the complete 3,947-file source backup, SHA256 `8e73de42982b9e50bc13e9776a4dd4ef4e8074deb11f5b6d177b8faf5e360533`, before source edits. Existing dirty work was preserved. The signedness and owner boundary of old paid jobs remains the original stored Settings; a current global configuration cannot silently retarget an old paid job.

## Implemented

- `ZnackSigningSession`: admit all verified, unpaused WB/Ozon shops at startup; explicit shop selection and successful certificate verification can clear a durable shop pause. Explicit pipeline approval/resumption is certificate-bound and scoped so a paused sibling cannot piggyback.
- `ZnackSigningPauseStore`: versioned SQLite app_config state for generation, pause, reconfiguration and typed cause. It stores an identity hash, never a token, PIN, certificate value or raw private failure text. Cancellation survives restart. Reconfiguration invalidates old grants before settings SQL begins; it stays paused until a reviewed explicit continuation.
- `MutationPermit`: captures original pipeline, signer generation, certificate and profile epoch before authentication/signing; validates again at the actual order/document HTTP mutation boundary. Nested permits validate every approved member of a grouped purchase. The shared gate prevents configuration from changing during an admitted mutation; a concurrent settings edit fails promptly instead of waiting on network I/O.
- Profile epochs: fresh profiles receive no old process grant. Old signers and mutation permits remain invalid even when a caller later returns to the same original profile path. Durable pauses remain in their original profile database. This also preserves existing isolated database fixtures without a test-only bypass.
- Key presence: background/automatic signing reads the public cert/container data before signing, with bounded silent commands and caches. Only a positively identified missing removable token yields KEY_NOT_CONNECTED. It opens no signing command in that case. Unavailable public reads are UNKNOWN, matching the observed WB fallback; this is not a guarantee against all PIN prompts on an unverified Windows configuration.
- Transient missing-key waits remain distinct from permanent CryptoPro failures. A persisted exact transient marker may retry after restart/reconnect; it cannot clear a durable PIN/cancellation pause or grant business signing.
- `ZnackAuthService`: session cache keys now include the relevant endpoint/OMS/certificate/participant role. A rejected token invalidates only that matching account/session; old responses cannot invalidate a replacement token or another shop/True API role. Read-only SUZ/True API helpers retry authentication once for HTTP 401 only. They must never wrap paid creation, document submission or consuming GET codes.
- `HomeController`: initial visual selection no longer grants untested signing. Background recovery first reads and admits every verified saved WB/Ozon shop; explicit user selection follows the explicit admission path. Other navigation/layout behavior is untouched.
- `ZnackAutomationController`: the existing purchase-method combo now projects and saves PRODUCTION/COMMISSION through the atomic A-owned repository API. New normal preferences always enable introduction; the hidden opt-out remains hidden. Per-shop drafts and translations preserve the method. Four locales add the commission label. Legacy raw circulation intent is A-owned and preserved for old jobs.
- Existing `CertificateSigningQueue` serialization across shops using the same certificate and GS1 challenge-only read authentication are retained.

## Public contracts for B, D and A

- `registerRepository(repository)`
- `authorizeVerifiedShop(shop, settings, repository)`; rejects mismatched Shop/repository ids
- `authorizeShopExplicitly(repository)`
- typed `authorizePipeline(shopId, pipelineId, originalSettings)` and `authorizePipelineResumption(...)`
- `captureMutationPermit(shopId, originalSettings)` → `requireCurrent()` / `execute(MutationWork<T>)`
- `beginReconfiguration(shopId, previous, next)` → AutoCloseable scope with no checked close exception; call outside settings SQL
- `retryDisconnectedKey(shopId, pipelineId, persistedWaitMessage)`
- `invalidateSuzToken(settings, rejectedToken)` / `invalidateTrueApiToken(...)`
- `readWithSuzRecovery(settings, read)` / `readWithTrueApiRecovery(settings, read)`; read-only contract

## Actual verification

All commands use the cached JDK25/Maven offline dependencies and a separate source/target workspace. The shared repository target was never used.

1. Baseline startup RED: 1 test, 1 assertion failure, 0 errors/skips. Old initial selection permitted an untested recovered signer. `red-command-corrected.json`, `red-result-corrected.json`, `red-corrected.log`. The earlier launcher permission failure is preserved separately and is not counted as behavioral RED.
2. Baseline auth isolation RED: 2 tests, 2 assertion failures, 0 errors/skips. Old code reused an OMS token across different connection/certificate settings and borrowed another profile's authenticated INN. `red-auth-command.json`, `red-auth-result.json`, `red-auth.log`.
3. First narrow GREEN attempt: 54 of 55 passed; an existing sibling-wait assertion found overly broad compatibility-grant clearing. It was fixed without weakening that assertion. Repeated narrow subset: 55/55 PASS; `green-final.log`.
4. Broader current-source snapshot first compiled all main classes but test compilation captured a D-owned in-flight fixture/API mismatch. Refreshed those D files after coordination. The following 130-case runtime then found 7 profile-scope errors in existing GS1 fixtures; fixed the actual scope isolation as described above, with new negative tests.
5. Final Owner C integrated subset: **132/132 PASS, 14 suites, 0 failure/error/skip**, actual process 75.208 seconds. `integrated-profile-scoped.log`, `integrated-profile-scoped-result.json`, `integrated-final-command.json`, `integrated-test-summary.json`. This includes 13 new signing admission/generation/profile/pause cases, 6 auth isolation/read recovery cases, 5 key-presence cases, a real FXML method/locale projection case, and existing signing, Home startup, GS1, CryptoPro-provider and Znack UI/error regressions. Earlier runs overlap; do not add their counts.
6. `git diff --check` on modified tracked C files passed. `owned-files.json` contains exact current hashes for 21 production/resource/test files; `owned-changes.diff` compares those files to the parent's backed-up source. Six new test classes and two deliberately updated prior-policy tests are included. Existing enum parameter coverage excludes only the newly added transient key enum, which has dedicated tests; no old permanent error case was removed.

## Boundaries and remaining parent work

- The 132-case result is an Owner C snapshot, not the final all-owner application acceptance. B/D/A continued unrelated implementation/fixture work; parent must verify the final combined source and preserve the source manifest.
- No physical USB key, Windows/CryptoPro PIN behavior, seller OMS token, paid purchase, remote document publication, installer or full-app acceptance was performed.
- The temporary HOME JavaFX cache/dconf read-only warnings did not fail the Xvfb tests. They are not native Windows evidence.
- Parent was notified that `ZnackErrorMessages.display()` can provide a more specific localized connect-USB-and-retry message for KEY_NOT_CONNECTED. Its existing exact WAITING marker recognition is preserved; no out-of-scope change was made to that file.
- No dependency, FBS main layout, global glass CSS, license, version or release configuration changed by C. The source list and exact changes, rather than whole Git-vs-HEAD output containing earlier user work, define this owner's scope.
