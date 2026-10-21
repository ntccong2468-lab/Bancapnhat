# Windows exclusive-lock fixture correction

Full run 39330524258 (source b2ad06aa0b495932dd0e80ce90fa1c8f544a64f1) executed 1,942 tests: zero failures, one error, two platform skips. The error was SQLITE_BUSY in FxmlSmokeTest.wbSupplyAndFboFiltersReturnBeforeSlowSqlite, where the fixture changes journal mode and takes an exclusive lock.

The test left Home's real automatic scheduler running against synthetic shop credentials while changing SQLite's journal mode. It also restored WAL immediately after releasing its lock, racing the read workers just unblocked by that release. The correction closes and drains this fixture's scheduler before changing journal mode and waits for its read workers before restoring WAL. The UI responsiveness assertion remains 300 ms. Production synchronization and the SQLite write queue are unchanged by this fixture correction.

The 55-case FxmlSmokeTest suite passed locally after this correction. The Windows focused stage now includes the same suite, then a new complete Windows suite and all installer/native/upgrade checks must pass. No failed test is treated as passing or reused for release acceptance.
