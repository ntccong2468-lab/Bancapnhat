# GTIN Sync Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add shop-scoped GTIN matching, supported marketplace updates and durable reconciliation without removing existing WCode functionality.

**Architecture:** Keep the existing JavaFX application and marketplace adapters. Add a focused `features/gtinsync` package; mutations pass through capability checks, confirmed previews and a persistent queue. Unsupported barcode replacement remains explicitly blocked.

**Tech Stack:** Java 25, JavaFX 25, Maven, SQLite, Gson, OkHttp, JUnit 5, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-10-06-windows-wcode-gtin-design.md`

## Global Constraints

- Windows x64; preserve existing WB/Ozon/Znack/finance/printing/licensing/update workflows.
- Keep GTIN as a string with leading zeros; distinguish trade-unit GTIN from KIZ.
- Check shop ownership and marketplace on every repository/API operation.
- Never log/return credentials, raw KIZ, raw upstream responses or PII.
- Tests use temporary `wcode.appdata.dir`; never use a seller database.
- Migration must be additive, with verified snapshots and clean integrity/foreign-key checks.
- Live marketplace mutation requires approval of the exact fixture; mock results do not prove live behavior.
- No new runtime, dependency upgrades or worktree without a demonstrated need or user request.

## Review Focus

1. A numeric-looking GTIN with leading zeros must survive import, SQLite and export (Tasks 1–2).
2. An unrelated valid barcode must not be classified or removed as incorrect (Tasks 1, 4).
3. Switching shops during background work must never redirect the queued operation (Tasks 2, 5).
4. A lost response after upstream acceptance must not cause duplicate mutation on restart (Task 5).
5. Capability or product state changes after preview must invalidate the confirmation (Tasks 4–5).

## Shared Type Contract

Create `features/gtinsync/GtinSyncModels.java` with nested immutable records/enums:

- `ProductKey(int shopId, Marketplace marketplace, String productId, String variantId)`.
- `RegisteredGtin(String gtin, String article, String color, String size, boolean tradeUnit, boolean published, String source, Instant syncedAt)`.
- `ProductSnapshot(ProductKey key, String article, String color, String size, List<String> barcodes, String fingerprint, Instant fetchedAt)`.
- `Operation`: `ADD`, `REPLACE`.
- `Status`: `QUEUED`, `VALIDATING`, `SENDING`, `AWAITING_VERIFICATION`, `SUCCEEDED`, `FAILED`, `RECONCILE_REQUIRED`, `CANCELLED`.
- `Capability(boolean add, boolean replace, String reason)`.
- `MatchResult(ProductKey key, List<RegisteredGtin> candidates, String reason)`; exact means one eligible candidate and a blank reason.
- `Preview(ProductSnapshot before, Operation operation, String oldGtin, String newGtin, Capability capability)`.
- `Submission(String remoteTaskId)` and `Verification`: `PENDING`, `APPLIED`, `REJECTED`, `UNKNOWN`.
- `JobItem(UUID id, UUID jobId, Preview preview, Status status, String remoteTaskId)`.

Collections are defensive copies; IDs/GTIN are strings. Validate positive shop ID and complete product identity. Marketplace comes from existing models, not a duplicate enum.

### Task 1: GTIN validation and deterministic matching

**Files:** Create `features/gtinsync/GtinSyncModels.java`, `RegisteredGtinValidator.java`, `GtinMatchingService.java`; create matching tests under `src/test/java/com/vncode/app/features/gtinsync/`.

**Interfaces:** `boolean RegisteredGtinValidator.isValid(String gtin)`; `MatchResult GtinMatchingService.match(ProductSnapshot product, List<RegisteredGtin> catalog)`.

- [ ] Write failing tests: `assertTrue(isValid("00000000000017"))`, `assertFalse(isValid("00000000000018"))`; preserve 8/12/13/14-digit valid strings. Exact article/color/size yields one candidate; punctuation in article remains significant; blank attributes, draft/packaging GTIN or multiple eligible candidates block automatic selection. Product barcodes do not imply that a nonmatching barcode is wrong.
- [ ] Run `./mvnw -B -Dtest=GtinMatchingServiceTest,RegisteredGtinValidatorTest test`; confirm intended failures.
- [ ] Implement validation using ASCII digits and GS1 alternating-weight checksum. Matching trims/case-normalizes human color/size without implicit size conversion or article punctuation removal.
- [ ] Run targeted tests, then commit models/validator/matcher and tests.

Task setup: install a checksum-verified JDK 25 under `/workspace/tools`, export JAVA_HOME/PATH, run baseline `node --test tools/*.test.mjs` and `./mvnw -B clean verify`. If Linux needs JavaFX display prerequisites, use Xvfb and native libraries, preserve test assertions and distinguish platform failures. Record baseline evidence under `docs/validation/` without secrets.

### Task 2: Additive schema and repository

**Files:** Create `features/gtinsync/GtinSyncSchema.java`, `GtinSyncRepository.java`; modify `config/Database.java`; test `GtinSyncRepositoryTest.java` and `config/DatabaseMigrationCompatibilityTest.java`.

**Interfaces:** `void GtinSyncSchema.initialize(Connection c)`; repository `saveCatalog(int shopId, Marketplace marketplace, List<RegisteredGtin> rows)`, `List<RegisteredGtin> catalog(int shopId, Marketplace marketplace)`, `UUID createJob(List<Preview> previews)`, `List<JobItem> pending(int shopId, Marketplace marketplace)`, `boolean transition(UUID itemId, Status expected, Status next, String remoteTaskId)`, `void recoverInterrupted()`.

- [ ] Write failing tests for baseline schema 2→3 migration, verified snapshot via LocalDataMigrationGate, GTIN round-trip, cross-shop rejection, repeated migration, compare-and-set transitions, and one active item per product. Assert integrity_check=`ok` and foreign_key_check has zero rows.
- [ ] Run `./mvnw -B -Dtest=GtinSyncRepositoryTest,DatabaseMigrationCompatibilityTest test`; verify failures.
- [ ] Add `gtin_sync_catalog`, `gtin_sync_mappings`, `gtin_sync_jobs`, `gtin_sync_items`, `gtin_sync_events`; include shop/marketplace ownership, timestamps, preview JSON, status, remote task ID and deduplication key. Reject mixed-shop jobs. Update the one schema constant to 3 and preserve the migration gate's snapshot path. Recover SENDING as RECONCILE_REQUIRED and interrupted VALIDATING as QUEUED.
- [ ] Run migration/repository tests plus existing data recovery tests; commit only relevant files.

### Task 3: Registered GTIN source and product readers

**Files:** Create `features/gtinsync/RegisteredGtinSource.java`, `RegisteredGtinSyncService.java`, `integration/znack/registration/NationalCatalogGtinSource.java`, `integration/wb/WbGtinProductReader.java`, `integration/ozon/OzonGtinProductReader.java`; source/reader tests in matching packages.

**Interfaces:** `List<RegisteredGtin> RegisteredGtinSource.read(int shopId)`; `void RegisteredGtinSyncService.refresh(int shopId, Marketplace marketplace)`; each reader provides `ProductSnapshot read(ProductKey key)`.

- [ ] Write failing mock tests for pagination, empty catalog, partial failure, stale/unknown publication state, leading zeros and preservation of shop/product/variant identities.
- [ ] Run selected source and reader tests; confirm intended failures.
- [ ] Reuse existing National Catalog authentication and WB/Ozon product-reading APIs. Persist a refreshed catalog only after complete read; mark unknown/incomplete trade-unit metadata ineligible. Fingerprint stable identity, relevant attributes and ordered-normalized barcode set; exclude credentials and volatile timestamps.
- [ ] Run new reader tests and existing National Catalog/WB/Ozon API tests; commit.

### Task 4: Capability-checked adapters and previews

**Files:** Create `features/gtinsync/GtinMarketplaceAdapter.java`, `GtinPreviewService.java`, `integration/wb/WbGtinAdapter.java`, `integration/ozon/OzonGtinAdapter.java`; modify existing API clients only for documented methods; create adapter and preview tests; create `docs/gtin-marketplace-api-contracts.md`.

**Interfaces:** adapter `Capability capability(ProductKey key)`, `ProductSnapshot read(ProductKey key)`, `Submission submit(Preview preview)`, `Verification verify(Preview preview, Submission submission)`; preview service `Preview create(ProductKey key, Operation operation, String oldGtin, String newGtin)`.

- [ ] Verify authoritative current API docs and record exact URLs/date, routes, scopes, payload/error schemas, product restrictions and async status behavior. Unverifiable or disallowed operations return capability=false with a reason; no guessed endpoints.
- [ ] Write failing tests for unsupported replacement, chosen old code absent, identical old/new code, existing new code, cross-product GTIN conflict, stale fingerprint and unrelated barcodes retained. Mock successful/async/error responses against the recorded contract.
- [ ] Run `./mvnw -B -Dtest=GtinPreviewServiceTest,WbGtinAdapterTest,OzonGtinAdapterTest test`; confirm failures.
- [ ] Implement payloads retaining required product fields from a fresh response; never replay raw responses wholesale or use a minimal destructive product import. WB targets the exact chrtID; Ozon uses verified product identity. Replacement success requires old absent/new present; adding while old remains never satisfies REPLACE.
- [ ] Run adapters/preview and existing API tests; commit. If docs block mutation, complete the explicit unsupported path and report missing capability; do not claim that requested remote operation works.

### Task 5: Durable execution and reconciliation

**Files:** Create `features/gtinsync/GtinSyncCoordinator.java`; create `GtinSyncCoordinatorTest.java`.

**Interfaces:** `UUID confirm(List<Preview> previews)`, `void runPending(int shopId, Marketplace marketplace)`, `void pause(UUID jobId)`, `void cancelQueued(UUID jobId)`, `void reconcile(UUID itemId)`. Inject repository, adapter resolver, Clock and bounded polling policy for deterministic tests.

- [ ] Write failing tests for changed data/capability after preview, exact preservation of original shop after UI switching, duplicate confirmation, concurrent product updates, crash before/after submit, async rejection, timeout after acceptance and pause/cancel of only unsent items. Verify submit call count is one after restart and timeout.
- [ ] Run `./mvnw -B -Dtest=GtinSyncCoordinatorTest test`; confirm failures.
- [ ] Implement guarded state transitions, revalidation before SENDING, durable state before I/O and persisted submission IDs. Poll with bounded elapsed time and backoff; pending/unknown remains reconciliation-required after the bound. Background work uses existing app lifecycle/executor; no work on FX thread. Explicitly do not automatically retry a mutation whose acceptance is ambiguous.
- [ ] Run all new GTIN tests then `./mvnw -B clean verify` and Node contracts; record actual counts/outcomes and commit.

## Completion and next plan

Core is complete only when verified supported operations execute and reconcile, unsupported operations are explicit, migration is safe and baseline functionality still passes. Continue with `2026-10-06-windows-ui-release.md`; UI/release work is required for the user's final app. Do not describe a core-only build as the completed application.
