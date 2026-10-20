# Reopening VNCode while a process owns its data

## Seller evidence and limits

The seller's current Windows startup log records three `AppDataLock.AlreadyRunningException` failures on 2026-10-20 at 22:10 local time. An existing process owns the profile. The seller reports no visible VNCode tray icon, so a usable hidden workspace cannot be assumed.

The log is append-only. A separately supplied `unregistered_view` stack is an older TN VED click, dated 2026-10-09: `WorkspaceNavigator.showView` → `HomeController.setDynamicContent` → `HomeController.showTnved`. It does not establish today's initial startup failure. Current production code has no calls to `showView`; navigation uses registered route names. Unknown views remain rejected, and existing actual Home FXML tests cover the initial welcome route.

No seller profile, database, credentials or raw user path was copied into this report.

## Changes

- A process that holds the profile lock publishes a bounded, profile-local activation listener before data preparation. A second launch requests window restoration on its startup worker. The original process restores its JavaFX window, including the existing registration tray lifecycle; only successful window restoration is acknowledged.
- Only an acknowledged second launch closes its secondary startup window. A missing listener, failed restore or older application keeps an actionable already-running message. The original lock, registration work and database are not released or replaced by a second launch.
- Listener shutdown does not wait for FX callbacks. The listener closes before profile ownership is released. Request/ack identities are scoped to the profile and listener generation, with bounded signal sizes and path validation.
- Startup failures show a localized reason and code plus a copy-diagnostics button. Copied reports include version, phase, exception types and application frames; arbitrary messages, credentials and private paths are excluded. Startup presentation remains independent of SQL, remote APIs and license initialization.
- The persisted startup log now includes application version and process ID, so old appended failures can be distinguished from a current attempt. Window restore waits for native deiconification asynchronously before acknowledging it, with a two-second bound.
- An additional recovery defect was found during inspection: SQL/read failures could be mistaken for an empty database. Inspection now uses read-only SQLite access, preserves unreadable current DB/WAL, and stops automatic replacement. Failed recovery retains its pending marker; confirmed-empty recovery still works. This defect is not claimed as the cause of the seller's screenshot.

## Validation

Final regression passed: **455 related Java tests**, **55 actual FXML smoke tests in a separate JVM**, and **10 entrypoint contracts**, with zero failures/errors/skips. This includes eight activation protocol cases, three tests using a real hidden JavaFX window, early-startup activation/recovery cleanup, eight startup UI cases, and eight recovery data-preservation cases. [Machine-readable results](2026-10-20-catalog-startup-regression.json).

No native Windows installer has been built or published for these source changes yet. Linux JavaFX/SQLite tests do not prove acceptance on the seller's Windows process. The published 1.5.4 installer remains unchanged.
