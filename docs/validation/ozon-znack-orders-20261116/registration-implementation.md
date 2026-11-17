# Ozon Znack registration entry — completed after verified backup

Branch: `feature/ozon-znack-order-kiz-20261116`. Parent backup receipt was `ALL_AVAILABLE_APP_BACKUPS_VERIFIED` before the first source/test edit. Existing ECC/UI changes were preserved; no Catalog or WB registration engine was replaced.

## Confirmed missing behavior

Ozon already had a complete native Catalog registration pane/workflow, but `ShopSidebarController.setMarketplace` explicitly exposed its registration navigation only for WB. The Ozon FBS header also had no direct registration entry. The first meaningful RED loaded the real FXML and asserted that the requested entry existed; it failed. A separate RED confirmed the Ozon sidebar entry was invisible. A combined rendered sidebar/FBS scene caught the initial new header ID collision, which was corrected before final acceptance.

## Four production files changed

- `src/main/java/com/vncode/app/ui/shop/ShopSidebarController.java`: the registration availability predicate now permits WB **or** Ozon. All other availability calls and WB behavior are unchanged.
- `src/main/resources/com/vncode/app/ui/ozon/ozon-dashboard-view.fxml`: add a native secondary action, `ozonZnackRegistrationButton`, using the existing localized registration label. Its ID differs from the existing sidebar navigation ID.
- `src/main/java/com/vncode/app/ui/ozon/OzonDashboardController.java`: add the registration callback/action. It copies the current Ozon shop, validates current persisted owner credentials before dispatch, rejects deleted/rotated owners without exposing values, and disables the entry with no shop, no host callback or while busy. Disposed views cannot dispatch. No acceptance, printing or purchase flow was edited.
- `src/main/java/com/vncode/app/ui/workspace/HomeController.java`: wire the new callback into the existing central registration route; verify that the requested Ozon identity matches the current selected shop before opening. The original WB/sidebar registration method is unchanged.

Opening registration does not itself create a Catalog card, synchronize remotely, buy KIZ or sign anything. The existing seller-reviewed registration flow remains responsible for those actions. Existing same-shop pane caching preserves its batch/selection context when reopened.

## New meaningful UI/scope tests

`OzonRegistrationEntryUiTest` has six cases: current frozen shop and zero remote work; no-shop/WB rejection; deleted/rotated credentials; missing host/busy/disposed rejection; unique original sidebar selector in a real combined scene; Ozon sidebar access while retaining WB and other navigation availability.

`OzonRegistrationNavigationTest` has two cases: existing native Ozon pane/cached route reuse, and rejection of null/cross-shop/rotated-credential/WB navigation requests. Tests load actual JavaFX FXML and native Ozon registration controls with synthetic local data.

## Executed verification

Final Maven run used the shared flock, cached JDK25/Maven, Xvfb and offline dependency resolution. **100/100 cases passed across eight suites, no failure/error/skip.** The suites include the two new classes, Ozon FBS UI, existing Ozon registration (40), WB registration controller (44), Home page transitions/navigation context and the full FXML layout matrix. Matrix output: **16 pages ×4 locales ×2 themes ×2 sizes; 912 fixed-action checks; clipped={}**.

All six owned production/test file hashes stayed unchanged during the final run. Exact source hashes, command, counts, XML hashes and archived reports are in `registration-tests/final-receipt.json` and `registration-tests/final-junit/`. `git diff --check` passed. Source is stable for the parent’s combined regression.

The first environment attempt lacked the extracted Xvfb directory on PATH and could not open DISPLAY; it is explicitly classified as an environment failure, not feature RED. One temporary fixture generic-inference compile error and one pre-skin selector setup error were corrected and their logs retained. Only the actual missing-entry, hidden-sidebar and rendered-ID-collision assertions count as meaningful RED evidence.

No dependencies installed, no app data schema changes, no live seller/Catalog requests, no KIZ purchase/signing/license activation, and no commit/push/release. Linux UI/fixture acceptance does not prove Windows/CryptoPro or real Catalog acceptance. Purchase/replacement logic belongs to the separate parent-assigned agent.
