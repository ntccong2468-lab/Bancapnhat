# Update handoff regression in VNCode 1.4.8

Owner report from 1.4.4: after download reaches 100%, `HomeController$6.call` invokes `Task.getValue()` on the updater worker and throws `IllegalStateException: Task must only be used from the FX Application Thread`. The same source pattern remained in 1.4.7. Earlier verification covered release discovery, download/hash validation and native MSI upgrades separately, and did not cover this controller handoff.

`UpdateHandoffUiTest` loads the actual Home FXML/controller, completes an actual JavaFX download Task at 100%, passes through the real controller callbacks and executes the preparation Task on the actual updater executor. Installer service and exit action are injected so tests do not execute an installer or terminate the test JVM. The pre-fix test failed with the same exception/stack location as the owner report. After the fix, success reaches the installer service with the exact Path off FX, then invokes the exit action on FX. The failure case keeps the app open, restores controls and reports the preparation failure separately.

Fix: capture the completed Path within `onSucceeded` on FX; the next worker captures that immutable value and never reads the download Task's FX properties. Preserve the original snapshot exception as the cause for diagnostics. No checksum, licensing or snapshot gate is removed.

Local validation: expected pre-fix failure (1 test), then 23/23 focused update tests passed, including both handoff cases; 53/53 Node release-contract tests passed. Full Windows results and release bindings are recorded in the 1.4.8 status/download record after CI. The cloud display was restored with a locally unpacked Xvfb package verified through Debian's signed APT metadata.

Old installed code cannot use this fix before being replaced: 1.4.4–1.4.7 users need the download-only button or direct public EXE, then close VNCode and install once manually. No app-data deletion or fresh license issuance is requested.
