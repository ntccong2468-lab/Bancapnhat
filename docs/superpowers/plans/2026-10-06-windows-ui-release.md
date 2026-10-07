# Windows WCode UI and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the GTIN module into an improved Windows desktop UI while retaining all WCode workflows, then validate and package the app.

**Architecture:** Reuse existing feature screens and services. Extract workspace navigation from HomeController, add a real GTIN screen wired to the core, and preserve license/update/credential boundaries. Build Windows packages through existing Maven/jpackage workflows.

**Tech Stack:** Java 25, JavaFX 25/FXML, SQLite, Maven, Node contract tests, Windows jpackage.

**Spec:** `docs/superpowers/specs/2026-10-06-windows-wcode-gtin-design.md`

## Global Constraints

- Complete the interfaces in `2026-10-06-gtin-sync-core.md` before integrating GTIN UI.
- Windows x64; no replacement frontend/runtime and no removal of existing feature screens.
- User-facing copy must be synchronized RU/EN/VI/ZH.
- Tests use temporary `wcode.appdata.dir`; mutations need preview/confirmation and exact shop ownership.
- Preserve app-data lock, snapshots, license checks and signed update verification.
- Windows GUI, CryptoPro and installer claims require Windows evidence; mock tests are not live API validation.

## Review Focus

1. A background refresh completing after shop selection changes must not populate the wrong shop (Task 1).
2. A user double-clicking confirmation must create one job (Task 1).
3. Long product names and barcode strings must remain readable at Windows 125% scaling (Task 2).
4. Closing with an active mutation must preserve recovery and avoid misleading cancellation (Tasks 1–2).
5. Installer upgrades must not overwrite the user's database or legacy rollback snapshot (Task 3).

### Task 1: GTIN screen, confirmation and history

**Files:** Create `ui/gtinsync/GtinSyncController.java`, `GtinSyncViewModel.java`, `GtinSyncHistoryExporter.java`, and `src/main/resources/com/vncode/app/ui/gtinsync/gtin-sync-view.fxml`; modify `ui/workspace/HomeController.java`, `ui/shop/ShopSidebarController.java`, sidebar FXML and four `i18n/messages_*.properties`; add controller/export tests and extend `ui/FxmlSmokeTest.java`.

**Interfaces:** `void GtinSyncController.setShop(Shop shop)`, `void dispose()`; view model `void load(Shop shop)`, `List<Preview> previewSelection()`, `UUID confirm(List<Preview> previews)`; exporter `void writeCsv(List<JobItem> items, Path file)`, `void writeXlsx(List<JobItem> items, Path file)`. Use core types and interfaces verbatim.

- [ ] Write failing tests for row identity, unsupported operations disabled with reason, unconfirmed internal mapping never sent, double-confirm once, stale async response ignored after shop switch, no automatic replacement selection, and CSV/XLSX preserving leading zeros. Escape formula-like free-text in exports and retain GTIN as string cells.
- [ ] Run targeted controller/export/FXML tests; confirm intended failures.
- [ ] Build table/filter/action controls with before→after preview, selected-row summary and a separate confirm button. Show exact/ambiguous/stale/unsupported states and per-item progress/history. Add refresh, pause and cancel-unsent using core coordinator. Use existing AppTaskExecutor and lifecycle tracking for close behavior.
- [ ] Add sidebar entry and translated copy; run targeted tests and FxmlSmokeTest; commit.

### Task 2: Workspace structure and full-feature preservation

**Files:** Create `ui/workspace/WorkspaceNavigator.java`, `WorkspaceNavigationTest.java`, `docs/validation/vn-code-feature-parity.md`; modify `HomeController.java`, `home-view.fxml`, `theme.css` and sidebar layout as needed.

**Interfaces:** navigator `void register(String route, Supplier<Node> view)`, `void show(String route)`, `String currentRoute()`. HomeController retains shop state/lifecycle but delegates screen selection; existing feature controllers and license restrictions remain active.

- [ ] Inventory all current navigation commands and dialogs, including finance, WB/Ozon, FBO, Znack registration, signatures, printing/history, settings, license and updates; map each to a post-change route/check in the parity document.
- [ ] Write failing navigation tests proving every existing route remains reachable, unauthorized routes retain license checks and repeated navigation does not create duplicate background schedulers. Add long labels/barcodes and locale-switch fixtures to FXML smoke coverage.
- [ ] Run navigation/FXML/startup recovery tests; confirm failures.
- [ ] Extract route registration and view selection without unrelated service refactors. Improve spacing, hierarchy, selected-shop visibility and table resizing; preserve existing business forms. Test 100%/125% actual Windows scaling and record layout evidence.
- [ ] Run targeted tests, full Maven verify and Node contracts; update parity outcomes truthfully and commit.

### Task 3: Windows artifacts and acceptance

**Files:** Modify `build.bat` or `.github/workflows/build-java.yml` only if packaging integration requires it; create `docs/validation/windows-gtin-acceptance.md`; extend `tools/javafx-production-entrypoint.test.mjs` only if packaging contracts change.

**Interfaces:** Use existing `build.bat app-image`, `build.bat exe`, `build.bat msi` and existing release identity/update signing settings. No new publishing destination or automatic release.

- [ ] If packaging needs changes, first write/run failing Node contract tests that pin the changed entrypoint/input/runtime requirements; otherwise keep packaging scripts unchanged.
- [ ] On Windows JDK 25, run `node --test tools/*.test.mjs`, `.\mvnw.cmd -B clean verify`, and all three packaging commands. Record actual test counts, exit codes, SHA-256 and artifact paths.
- [ ] Smoke-test portable and installer with isolated app-data, launcher, shop selection, GTIN screen and a mock-backed complete add/reconcile workflow. Verify upgrade fixture preserves SQLite/GTIN history and integrity checks. Exercise recovery after an interrupted update; no seller mutation.
- [ ] Check CryptoPro with a test certificate when available and report unrun certificate/live API checks separately. Signing depends on configured signing credentials; unsigned builds must be labeled unsigned.
- [ ] Update README with tested Windows instructions, supported GTIN operations per sàn and precise blockers; commit docs and any justified packaging changes.

## Completion

Deliver source changes and verified Windows artifacts, full-feature parity evidence, tests, migration evidence and a list of unsupported/API-blocked operations. Linux-only compilation is not evidence of installer or Windows UI success. Missing Windows runner, seller credentials or CryptoPro must be reported as concrete limitations; no automatic CI dispatch, release publication or live mutation is authorized by this plan.
