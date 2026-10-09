# TN VED EAEU implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add the approved TN VED EAEU module inside the existing Windows VN code update.
**Architecture:** A separate SQLite database in the owned app-data directory stores versioned catalog data, reviews and mappings. Existing marketplace databases remain read-only to this module; remote mutations require verified contracts and reconciliation.
**Tech Stack:** Java 25, JavaFX, SQLite JDBC, Gson; existing Maven and Windows CI.
**Spec:** `docs/superpowers/specs/2026-10-09-tnved-eaeu-design.md`.

## Global Constraints

- Same VN code identity, UpgradeCode and data directory; do not modify original Vncode repository.
- Codes are TEXT; 21 sections I–XXI; chapter 77 is not active.
- No invented catalog codes, AI classification or marking conclusions.
- AI provider and Yandex connectivity remain explicit capabilities; not simulated production features.
- Missing source data displays “Chưa tải dữ liệu”; missing legal conditions returns “Cần xác minh”.
- No automatic GTIN allocation on section change without an independently verified business rule.

## Review Focus

- Older catalog mappings retain original version when a new version becomes active.
- Wrong shop or stale UI callback cannot change another product's mapping.
- Interrupted import must not partially activate a catalog.
- Snapshot must include module database with consistent WAL contents.
- Unknown AI codes, missing legal conditions and expired codes never become confirmed classifications.

### Task 1: Restore Windows test isolation

Files: `src/test/java/com/vncode/app/shared/FriendlyErrorCodeTest.java`.
- [ ] Reproduce creation of default app-data by this test with a fresh test `user.home`.
- [ ] Set/restore `vncode.appdata.dir` to JUnit TempDir for every test; prove the default folder remains absent.
- [ ] Run targeted tests, commit; preserve strict co-install probe rather than deleting existing data.

### Task 2: Catalog storage and validated import

Files: `features/tnved/TnvedModels.java`, `TnvedCatalog.java`, `TnvedImporter.java`; test `features/tnved/TnvedCatalogTest.java`.
Interfaces: `TnvedCatalog(Path database)`, `initialize()`, `children(String parentCode)`, `search(String query,int offset,int limit,LocalDate date)`, `importVersion(Version,List<Node>)`.
- [ ] Tests: 21 roots; no active 77; zero prefixes preserved; duplicate/cycle/missing parent rejected; malformed dates; failed import leaves previous active version; Russian/Vi search; expire codes.
- [ ] Observe RED; implement transaction-based schema and immutable versions, source/date validation; observe GREEN.
- [ ] Define CSV/JSON manifest format and parse bounded files without executing or fetching content; test malformed headers and types; commit.

### Task 3: Windows catalog screen

Files: `ui/tnved/TnvedPane.java`, Home/ShopSidebar controllers and sidebar FXML; all four resource bundles; FXML tests.
Consumes catalog methods from Task 2. Produces sidebar action `setOnTnved(Runnable)` and a three-pane searchable catalog view.
- [ ] Test sidebar opens module, root count, unknown children message, stale search callbacks and clipboard code values.
- [ ] Observe RED; implement lazy tree, 100-result pagination, background import/search and detail/source/effectivity display; observe GREEN.
- [ ] Verify light/dark, narrow layout and independent scrolling; commit.

### Task 4: Product mappings and marketplace capabilities

Files: `features/tnved/TnvedProductService.java`, `TnvedProductAdapter.java`, WB/Ozon adapters; Yandex API adapter after contract verification; product picker UI; tests.
Interface: `ProductKey(marketplace,shopId,productId)`, `confirm(ProductKey,String nodeCode,LocalDate date,boolean confirmed)`.
- [ ] Tests: product ownership, leaf/current-version validation, one primary mapping, history preserved across reassignment/version activation, stale/removed product rejection.
- [ ] Implement local mapping transactions and read-only WB/Ozon access; surface missing Yandex connection explicitly.
- [ ] Verify Yandex official read contract and add adapter with credential isolation and mock contract tests; implement any remote-write adapter only after its field/permissions contract is verified.
- [ ] Run tests, commit. Unsupported remote writes remain disabled, not treated as success.

### Task 5: AI and marking assessments

Files: `features/tnved/TnvedSuggestionService.java`, `TnvedMarkingService.java`, provider configuration UI and review detail UI; tests.
Interfaces: provider `suggest(ProductFacts,List<Node>)`; `validateSuggestions(...)`; marking `assess(ProductFacts,LocalDate)` with UNKNOWN/REQUIRED/NOT_REQUIRED and cited reasons.
- [ ] Tests reject non-catalog/parent/expired AI candidates, enforce maximum three, require facts, preserve reviews; missing rule or input yields UNKNOWN.
- [ ] Implement provider abstraction with explicit missing configuration and user consent, no seller secrets sent; rule import/evaluation with date/scope/exceptions.
- [ ] Test rules overlapping/conflicting and certificate scope; no automatic GTIN writes; commit.

### Task 6: Complete remaining 1.2 update and publish

Files: verified WB shipping contract/API/UI/preferences; Ozon selection and verified transition; snapshots, native upgrade probe, release readiness, docs.
- [ ] WB form saves method/city/point per shop, reselects date, reconciles per-supply mutation and handles unknown country; test rejected HTTP 200 and interrupted writes.
- [ ] Verify Ozon standard-FBS workflow; add per-order/all selection and label reminder without using rFBS endpoint for standard FBS.
- [ ] Include tnved.sqlite in snapshots/native upgrade data-preservation probe; test original database unchanged.
- [ ] Full Maven/Node verification; one fresh independent review; fix important findings with regression tests.
- [ ] Push branch and run Windows CI for the exact source SHA; validate installer, same app identity, baseline upgrade, rollback and offline catalog.
- [ ] Publish only a truthful release whose readiness matches implemented and tested capabilities; no full-release claim while required features/data are missing.
