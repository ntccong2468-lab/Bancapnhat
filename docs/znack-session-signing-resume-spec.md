# Znack session-scoped signing resume

## Objective

Prevent persisted Znack purchase/introduction pipelines from repeatedly opening the CryptoPro certificate or token dialog after WCode starts, while preserving automatic continuation for work that the user started during the current app session.

## Approved behavior

- A pipeline created by a user action in the current process is authorized to request signatures for the rest of that process. It remains authorized if the user switches to another shop.
- A pipeline restored from SQLite after app startup may continue steps that do not need a new signature. At the first signing boundary it waits without invoking CryptoPro.
- After the startup shop list is loaded, the shop restored as active is authorized once and its persisted pipelines resume automatically. This covers cases where the user closed WCode to top up the Znack balance and then reopened it.
- Selecting a shop from the shop dropdown is explicit session authorization for that shop. Waiting restored pipelines for that shop resume in the background.
- Restored pipelines belonging to other shops remain paused at the signing boundary until the user selects their shop.
- Authorization and waiting state are in memory only. No database migration or persisted consent flag is introduced.
- If CryptoPro reports cancellation, missing token/certificate, unavailable private key, or an expired certificate, automatic signing retries stop for that pipeline. Its existing order, downloaded KIZ, document, and idempotency state remain unchanged and retryable.
- An already-submitted ambiguous mutation is still reconciled or polled through the existing pipeline stages; the change must not submit duplicate purchases or introduction documents.

## Technical approach

- Java 25, JavaFX, Maven, SQLite; no new dependency.
- Add a thread-safe session signing gate keyed by `shopId:pipelineId`.
- Execute every coordinator advance inside a pipeline context. A guarded signature provider checks that context immediately before CryptoPro is invoked.
- Mark newly enqueued pipelines and explicit introduction retries as authorized.
- Mark a pipeline as waiting when signing lacks session authorization or CryptoPro returns a human-action error. The poll scheduler does not reschedule a waiting pipeline.
- Explicit shop selection authorizes the shop, clears its in-memory waits, and invokes persisted pipeline resume on the existing background executor.
- Suppress the shop dropdown callback while its items/initial value are populated. The startup coordinator separately authorizes only the resolved active shop and starts the one-time persisted-pipeline recovery after that selection is known.

## Code boundaries

- Signing policy belongs to `integration/znack`; JavaFX only reports explicit shop selection.
- Existing Znack pipeline stages and database schema remain unchanged.
- Existing Ozon/WB order, KIZ, finance, and release behavior is out of scope.
- Existing user changes in the working tree are preserved.

## Testing strategy

- Unit-test the signing gate: recovered pipeline is deferred without invoking the delegate; current-session pipeline remains allowed; explicit shop activation releases recovered work; human-action CryptoPro errors enter waiting state.
- Coordinator regression-test that a waiting-for-signature pipeline is not scheduled repeatedly and is rescheduled after explicit shop activation/resume.
- Header regression-test programmatic population versus a real user selection where practical without relying on timing.
- Run targeted Znack tests, the full Maven test suite, package build, and `git diff --check`.

## Success criteria

- Closing the CryptoPro dialog cannot cause the same background pipeline to reopen it every 30 seconds.
- New purchases keep progressing after changing the selected shop.
- Old work for the startup active shop resumes automatically; old work for every other shop resumes signing only after the user deliberately selects that shop.
- No paid KIZ, pipeline, order, or document state is deleted or duplicated.
