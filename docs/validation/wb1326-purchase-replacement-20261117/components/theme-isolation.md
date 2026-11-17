# ThemeServiceTest read-only isolation

No source, resource, test, assertion or build configuration was changed.

The root broad run recorded two popup-isShowing errors among 11 ThemeServiceTest cases. Its XML is preserved as `root-full-run-failure.xml`. Both fail before palette/contrast checks because the popup is no longer showing. These errors must remain visible in the broad FAIL receipt.

The entire class was run in a fresh private Xvfb using exact byte-for-byte copies of the root broader snapshot's compiled main and test classes, through direct Maven surefire:test. No source recompilation or fixture adaptation was involved. `compiled-inputs.json` records matching ThemeService.class and ThemeServiceTest.class hashes; all target/classes and target/test-classes were copied from the same frozen root target.

**Isolated result: 11/11 PASS, 0 failure/error/skip** (`isolated.log`, `result.json`, `test-summary.json`, `command.json`). Both previously failing real popup scenarios passed unchanged, including retained skin, palette, ink, material and contrast checks.

`baseline-source-comparison.json` proves ThemeService.java, ThemeServiceTest.java, WorkspaceHeaderController.java, workspace-header-view.fxml and every production CSS file are byte-identical both to the parent's verified pre-port backup and to its current frozen source snapshot. This purchase-flow task introduced no change to those inputs.

Classification: the popup failures did not reproduce when the unchanged complete class was isolated; broad-run UI context/focus interference is a plausible explanation, especially when multiple JavaFX forks share one display, but its exact cause was not established. This is not evidence of a new purchase-flow regression and is not a claim that the broader run passed. Root should report the original broad FAIL and this exact isolated PASS separately. No baseline binary probe was needed because the failure was not reproduced in isolation. No native Windows, CryptoPro, seller API or paid operation was performed.
