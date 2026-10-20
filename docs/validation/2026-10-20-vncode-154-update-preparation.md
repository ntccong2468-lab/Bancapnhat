# VNCode 1.5.4 update preparation

The published 1.5.3 updater could keep restarting its SQLite online backup while continuous FBS synchronization committed new WAL pages. Its native backup observer reported no progress and the copy had no fixed source read transaction. This could leave the update dialog preparing data until writes became quiet.

The backup now starts an explicit read transaction and reads the source before copying. SQLite therefore copies one consistent committed database/WAL view while ordinary WAL writers continue. The updater still hashes the live database and WAL for exact handoff reuse and fully verifies the rollback checksum and SQLite integrity. Changed data invalidates reuse and causes a fresh rollback on the next start.

Actual copied and hashed bytes now reach the update dialog through the existing background worker. UI updates are throttled, integrity verification resets the progress bar to indeterminate, and installation is not reported complete before handoff. Native callback exceptions and `sqlite3_interrupt` do not stop this driver's backup loop; cancellation is therefore honored after the finite native copy returns and before hashing or publishing a rollback. Ownership remains held through cleanup.

## Local mechanism probe

The standalone probe used SQLite JDBC 3.45.3.0, JDK 25 and a synthetic 69,218,304-byte database with an external writer committing every millisecond for two seconds. No seller data or production API was used.

| Copy mode | Native copy time | Progress callbacks | Nondecreasing remaining pages | Consistent marker pair |
| --- | ---: | ---: | ---: | --- |
| Existing online backup | 2,071 ms | 8,976 | 1,639 | 1,650 / 1,650 |
| Pinned source read transaction | 170 ms | 169 | 0 | 0 / 0 |

Both copies returned SQLite success, and external WAL commits continued without writer failure. The pinned copy retained the committed state observed at its start. These timings establish the restart mechanism; they are not a speed guarantee for seller hardware. Sanitized aggregates are in `/tmp/vncode-154-update-backup-benchmark.json`, and probe source is under `/tmp/vncode-snapshot-review/`.

## Regression scope and limits

New real-SQLite regressions cover concurrent WAL commits, monotonic bounded copy steps, consistent snapshot contents, full integrity verification and cancellation cleanup with retained ownership. The JavaFX regression covers actual byte progress, a responsive window, reset before integrity verification and absence of a premature 100% status. Existing handoff tests continue to require exact database/WAL identity and preserved synced data.

The release owner ran the combined focused Linux suite on 20 October 2026: **193 tests passed, zero failures, errors or skips**, including both `UpdateSnapshotProgressTest` cases, all 15 snapshot-service tests, six handoff tests and five update-dialog handoff tests. The run also covered startup, installer/download verification, FXML and the associated KIZ regressions. Local log: `/tmp/vncode-154-updater-kiz-focused.log`. Windows passive-upgrade contracts passed **14/14** Node checks. The final source `688bd01982898e18c9770300fa20cfe29bde1245` subsequently passed fresh full Java on Windows: **2,088 tests, 2,086 passed, zero failures/errors and two platform skips** in [run 39415265129](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39415265129). All 24 native proof files and 19 data-preserving upgrade probes passed, including the actual published 1.5.3 installer and the exact 1.5.4 EXE `/passive /norestart` handoff. The public 146,868,224-byte installer has SHA-256 `e7079a4aca07c1ae2fec26e46c4e3a3d77096a14b1c0485a88cefe95a3f7a18b`. A download after publication verified these bytes, and the actual application updater offered 1.5.4 over 1.5.3 with the matching URL/hash/size. [Recorded Windows/native/public evidence](2026-10-20-vncode-154-windows-native.json). No seller-desktop notification or seller-hardware timing was observed.

Full database/WAL hash scans, snapshot checksum verification and SQLite integrity checks still take time on large databases or slow disks. Startup retention may also verify old snapshots when a snapshot older than 30 days is eligible for deletion; this separate housekeeping case was not changed. This update does not promise instantaneous installation or startup on all hardware.
