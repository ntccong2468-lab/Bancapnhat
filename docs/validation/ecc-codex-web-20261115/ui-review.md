# Independent integration and JavaFX UI review — 15.11.2026

## Scope and integrity

Reviewed base `a7c4d008ae450a82b6cfbfbb79e5532eca6bfa1e` plus the approved ECC additions and the separately authorized UI capture-helper correction. The existing AGENTS content is an exact byte prefix of the updated file. Existing skills/configurations, POM, CSS, architecture, marketplace contracts and security gates were not changed by this reviewer. After a confirmed rendered finding and explicit parent authorization, only one UI controller and its FXML were changed to remove a redundant empty-state overlay; their exact hashes are recorded below.

The three new namespaced skills and ECC engineering documents preserve Code Doctor, OpenQodex, existing UI skills and Git Markdown memory as the authoritative project workflows. They do not register hooks/agents/plugins or claim Codex Web native discovery, automatic enforcement, real API acceptance or a complete security pass. No integration content defect requiring correction was found in the reviewed scope.

Independently SHA-256 checked all ten selected canonical ECC source documents and LICENSE against the isolated pinned upstream snapshot at `d2ef2680594f9a17526a6e1a2e18b3d242c5d17b`: 11/11 match. The MIT notice is byte-identical to upstream LICENSE. This was direct byte verification, not execution of upstream helpers/installers.

## UI review method

Read Taste and its app-screen guidance, Impeccable and its audit/Operate guidance, existing PRODUCT/DESIGN and design sidecar, and the ECC UI evidence checklist. The Impeccable launcher context was not run; used its documented direct-context fallback without downloading an engine. The approved native floating-glass brief overrides generic web-style recommendations. Browser/React/iOS/Android-specific checks are not passed off as JavaFX/Windows tests.

The original capture run produced 288 geometry records and 72 Vietnamese PNGs, but its helper added only base theme.css, omitted ThemeService.applyTheme, and captured immediately after route changes. Actual pixels showed previous-page labels and clipped WB category text. These images do not establish production-theme visual acceptance.

An external settle-only reproduction removed cross-route ghosting, proving those frames sampled the disposable departure bitmap before navigation completed. A second external run applying the actual ThemeService APIs removed the cropped category label, proving the clipping was an incomplete-harness stylesheet issue. No production ghosting/cropping fix was justified.

## Authorized capture-helper fix

Only `tools/UiLayoutProbe.java` changed: use production ThemeService scene/theme APIs; bound motion settling to five seconds with three quiet FX samples; assert workspace-theme plus workspace-controls.css; reject active navigation/departure motion at snapshot time. Keep all 18 routes, four languages, two themes, two sizes, original geometry overflow checks, 72 Vietnamese screenshots, synthetic fixture/license isolation, disabled background sync, no mutation actions and cleanup.

Current helper SHA-256: `feeedec4b088456ab6d03257738581ed494eb2c4027dbaee65f7ffa24f9d2c91`. It compiles without changing main/test application classes. Negative-control code lives outside the repo and invokes the current helper contract using a real JavaFX Stage: missing production theme is rejected; a correctly themed settled scene is accepted; actual WorkspaceMotion.navigation is rejected while active; completed motion is accepted. Final control process exit 0. A first control setup prematurely checked the themed Stage during its appearance animation and failed; preserved in `runtime/ui-current-helper/first-negative-failure.*`, not counted as a production failure or hidden.

Canonical corrected-helper execution completed: compile/control/capture exit 0, 288 geometry records, 72 Vietnamese PNGs, zero horizontal button overflow. Both production-theme and settled-capture assertions passed. Receipt: `runtime/ui-current-helper/process-receipt.json`. This process loaded the already-compiled BASE application classes; it is current-helper acceptance, not proof of the subsequently edited controller. The separate current-source overlay run below supplies UI evidence for that delta.

## Confirmed UI finding and minimal fix

P3, pre-existing cosmetic empty state: the large Znack registration/GTIN views rendered the same empty message twice. The original controller installed the TableView placeholder while also showing a separate FXML emptyLabel over the table. Actual production-theme settled RED screenshot `runtime/ui-settled-review/theme-evidence/znack-registration-vi-theme-light-1366x768.png` shows both messages. This was not a Catalog/GTIN API failure or an ECC regression.

With explicit parent authorization, removed only the redundant emptyLabel FXML node, its injected field/text assignment, four updateEmpty calls and the now-unused updateEmpty method. Existing table empty/loading placeholders, LoadingSkeleton, loadingLabel, business state, API paths and batch handlers remain intact. No new JUnit was added for this small cosmetic change; parent owns fresh existing UI/controller checks and Maven compilation/package after its baseline suite.

Current source hashes:

- `ZnackCardRegistrationController.java`: `cd3ac4934302ee52b6a551c295496bc3ffaab365a8492035551ae38e199fae12`
- `znack-card-registration-view.fxml`: `61cb9499ebfdd84119c48e3246c94c7c8d5ee2b0a0c2f64fafa3955dca843ce9`

Compiled the current controller to an OUTSIDE-repository overlay and copied its exact FXML there, with overlay first on the classpath before baseline application classes. No target/classes or target/test-classes were hot-patched while the parent's full baseline suite ran. This is a disclosed test overlay, not a production package. Compiler and current-overlay capture each returned exit 0; source hashes still match. Receipt: `runtime/ui-green-current/receipt.json`.

Current-overlay run: 18 routes × two themes × two sizes = 72 geometry records/72 Vietnamese PNGs, zero horizontal button overflow. Eight runtime assertions across Znack-registration and GTIN routes/theme/size combinations found exactly one visible rendered empty-message Label. The large light GREEN screenshot `runtime/ui-green-current/evidence/znack-registration-vi-theme-light-1366x768.png` visually confirms the duplicate is gone. The graph/logic beyond that presentation change was not altered.

## Final manual screenshot review

Opened the original pixels of the following 18 current-overlay PNGs using tools.view_image. Both actual sizes (1024×600 and 1366×768) and both themes are covered. These are representative pages, not a manual review of all 72 images or all interaction states. No further concrete visual blocker was found within this bounded set. Machine-readable file hashes/dimensions and scope are in `runtime/ui-green-current/manual-review.json`.

- `products-vi-theme-dark-1024x600.png`
- `packing-vi-theme-dark-1024x600.png`
- `ozon-vi-theme-dark-1024x600.png`
- `znack-registration-vi-theme-dark-1024x600.png`
- `kiz-mapping-vi-theme-dark-1366x768.png`
- `kiz-mapping-vi-theme-light-1024x600.png`
- `znack-vi-theme-dark-1366x768.png`
- `znack-vi-theme-light-1024x600.png`
- `finance-vi-theme-dark-1366x768.png`
- `finance-vi-theme-light-1024x600.png`
- `gtin-sync-vi-theme-dark-1366x768.png`
- `gtin-sync-vi-theme-light-1024x600.png`
- `license-vi-theme-dark-1366x768.png`
- `license-vi-theme-light-1024x600.png`
- `products-vi-theme-light-1366x768.png`
- `packing-vi-theme-light-1366x768.png`
- `ozon-vi-theme-light-1366x768.png`
- `znack-registration-vi-theme-light-1366x768.png`

Also viewed `runtime/launcher-smoke/actual-launcher-workspace.png` from the runtime agent's genuine Linux Launcher/MainApplication execution. It confirms that the normal entry point installs the production chrome; its receipt and startup/runtime conclusions belong to that agent. It is not a Windows capture or an extra current-delta acceptance case.

## Limits

Linux/Xvfb Java25/JavaFX25.0.2, synthetic license/profile and unselected shop controllers. Although synthetic shops appear in the header, these are predominantly empty-state routes; fixture database size does not prove populated order/product lists rendered. No Windows seller desktop, real seller API write, purchase, signing, license activation or Gmail action was performed by this review. Static screenshots and settled-state assertions do not establish keyboard traversal, screen-reader behavior, measured contrast, long-content behavior, animation timing/smoothness, FPS, Windows DPI or live marketplace acceptance. Existing eight Java CodeQL findings/high gate and GS1 extraction limits remain separate.
