package com.tuandev.fbsbarcode.shared;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;

/** Creates verified SQLite snapshots and crash-recoverable offline restores. */
public final class LocalDataSnapshotService {
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Pattern REASON_PATTERN = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final Pattern SNAPSHOT_ID_PATTERN =
            Pattern.compile("[0-9]{13}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MARKER_CHECKSUM =
            Pattern.compile("(?m)^snapshotSha256=([0-9a-f]{64})$");
    private static final int ROLLBACK_WINDOW_DAYS = 30;
    private static final int FALLBACKS_PER_REASON = 2;
    private static final String DATABASE_NAME = "database.db";
    private static final String WAL_NAME = DATABASE_NAME + "-wal";
    private static final String SHM_NAME = DATABASE_NAME + "-shm";
    private static final String SNAPSHOT_METADATA = "snapshot.properties";
    private static final String BUNDLE_METADATA = "bundle.properties";
    private static final String PENDING_JOURNAL = ".wcode-pending-restore.properties";
    private static final String NONE = "none";
    private static final long SNAPSHOT_MINIMUM_HEADROOM_BYTES = 64L * 1024 * 1024;

    private final RecoveryHooks hooks;
    private final InstantSource timeSource;
    private final UsableSpaceProbe usableSpaceProbe;

    public LocalDataSnapshotService() {
        this(point -> {}, InstantSource.system(), path -> Files.getFileStore(path).getUsableSpace());
    }

    LocalDataSnapshotService(RecoveryHooks hooks) {
        this(hooks, InstantSource.system(), path -> Files.getFileStore(path).getUsableSpace());
    }

    LocalDataSnapshotService(InstantSource timeSource) {
        this(point -> {}, timeSource, path -> Files.getFileStore(path).getUsableSpace());
    }

    LocalDataSnapshotService(UsableSpaceProbe usableSpaceProbe) {
        this(point -> {}, InstantSource.system(), usableSpaceProbe);
    }

    private LocalDataSnapshotService(
            RecoveryHooks hooks, InstantSource timeSource, UsableSpaceProbe usableSpaceProbe) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.usableSpaceProbe = Objects.requireNonNull(usableSpaceProbe, "usableSpaceProbe");
    }

    public Snapshot create(AppDataLock ownership, String appVersion, int schemaVersion, String reason)
            throws IOException, SQLException {
        Objects.requireNonNull(ownership, "ownership");
        requireMatch(appVersion, VERSION_PATTERN, "appVersion");
        requireMatch(reason, REASON_PATTERN, "reason");
        if (schemaVersion < 0) {
            throw new IllegalArgumentException("schemaVersion must not be negative");
        }

        Path appDataDir = ownership.requireOwnedAppDataDir();
        Path liveDatabase = appDataDir.resolve(DATABASE_NAME);
        requireRegularFileNoLinks(liveDatabase, "The live WCode database does not exist.");
        requireSnapshotCapacity(appDataDir, liveDatabase);

        Instant createdAt = timeSource.instant();
        String snapshotId = newId(createdAt);
        Path snapshotsDir = appDataDir.resolve("snapshots");
        requireSafeDirectory(snapshotsDir, true, "The WCode snapshot directory is unsafe.");
        Path snapshotDir = snapshotsDir.resolve(snapshotId);
        Path snapshotDatabase = snapshotDir.resolve(DATABASE_NAME);
        Path metadata = snapshotDir.resolve(SNAPSHOT_METADATA);
        Files.createDirectory(snapshotDir);
        forceDirectory(snapshotsDir);

        try {
            backup(liveDatabase, snapshotDatabase);
            forceFile(snapshotDatabase);
            assertIntegrity(snapshotDatabase);
            String checksum = sha256(snapshotDatabase);
            writeAtomic(metadata, snapshotMetadata(appVersion, schemaVersion, reason, createdAt, checksum));

            Snapshot snapshot = new Snapshot(
                    snapshotDatabase, metadata, checksum, appVersion, schemaVersion, reason, createdAt);
            if (!verify(snapshot)) {
                throw new IOException("The new WCode data snapshot failed verification.");
            }
            return snapshot;
        } catch (IOException | SQLException exception) {
            bestEffortDelete(metadata, exception);
            bestEffortDelete(snapshotDatabase, exception);
            bestEffortDelete(snapshotDir, exception);
            throw exception;
        }
    }

    public boolean verify(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            Path snapshotDir = snapshot.database().getParent();
            if (snapshotDir == null
                    || snapshot.metadata().getParent() == null
                    || !snapshotDir.equals(snapshot.metadata().getParent())) {
                return false;
            }
            Path snapshotsDir = snapshotDir.getParent();
            validateContainedDirectory(snapshotsDir, snapshotDir, "The WCode snapshot is unsafe.");
            validateContainedRegularFile(snapshotsDir, snapshot.database(), "The WCode snapshot is unsafe.");
            validateContainedRegularFile(snapshotsDir, snapshot.metadata(), "The WCode snapshot is unsafe.");
            Snapshot parsed = readSnapshot(snapshotsDir, snapshotDir);
            if (!parsed.equals(snapshot) || !constantTimeEquals(snapshot.sha256(), sha256(snapshot.database()))) {
                return false;
            }
            assertIntegrity(snapshot.database());
            return true;
        } catch (IOException | SQLException | RuntimeException exception) {
            return false;
        }
    }

    public boolean verify(RecoveryBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        try {
            Path bundlesDir = bundle.directory().getParent();
            validateContainedDirectory(bundlesDir, bundle.directory(), "The WCode recovery bundle is unsafe.");
            validateContainedRegularFile(bundlesDir, bundle.database(), "The WCode recovery bundle is unsafe.");
            validateBundleSidecar(bundlesDir, bundle.wal(), bundle.walSha256());
            validateBundleSidecar(bundlesDir, bundle.shm(), bundle.shmSha256());
            validateContainedRegularFile(bundlesDir, bundle.metadata(), "The WCode recovery bundle is unsafe.");
            RecoveryBundle parsed = readBundle(bundlesDir, bundle.directory());
            if (!parsed.equals(bundle)
                    || !constantTimeEquals(bundle.databaseSha256(), sha256(bundle.database()))
                    || !matchesOptionalChecksum(bundle.wal(), bundle.walSha256())
                    || !matchesOptionalChecksum(bundle.shm(), bundle.shmSha256())) {
                return false;
            }
            if (bundle.integrityStatus() == IntegrityStatus.VERIFIED
                    && sqliteIntegrityStatus(bundle.database()) != IntegrityStatus.VERIFIED) {
                return false;
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    public List<Snapshot> list(AppDataLock ownership) throws IOException {
        Path snapshotsDir = ownership.requireOwnedAppDataDir().resolve("snapshots");
        if (Files.notExists(snapshotsDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireSafeDirectory(snapshotsDir, false, "The WCode snapshot directory is unsafe.");
        try (var entries = Files.list(snapshotsDir)) {
            return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> readSnapshotOrNull(snapshotsDir, path))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Snapshot::createdAt).reversed())
                    .toList();
        }
    }

    /** Removes only expired, verified snapshots while preserving active rollback and forensic evidence. */
    public RetentionResult retainRollbackWindow(AppDataLock ownership) throws IOException {
        Objects.requireNonNull(ownership, "ownership");
        Path appDataDir = ownership.requireOwnedAppDataDir();
        Path snapshotsDir = appDataDir.resolve("snapshots");
        if (Files.notExists(snapshotsDir, LinkOption.NOFOLLOW_LINKS)) {
            return new RetentionResult(0, 0);
        }
        requireSafeDirectory(snapshotsDir, false, "The WCode snapshot directory is unsafe.");

        List<Snapshot> verified = list(ownership).stream().filter(this::verify).toList();
        Set<String> markerChecksums = referencedSnapshotChecksums(appDataDir);
        Set<Snapshot> markerProtected = new HashSet<>();
        Set<String> unmatchedMarkerChecksums = new HashSet<>(markerChecksums);
        for (Snapshot snapshot : verified) {
            if (unmatchedMarkerChecksums.remove(snapshot.sha256())) {
                markerProtected.add(snapshot);
            }
        }
        Set<Snapshot> fallbacks = new HashSet<>();
        Map<String, Integer> fallbackCount = new HashMap<>();
        for (Snapshot snapshot : verified) {
            int count = fallbackCount.getOrDefault(snapshot.reason(), 0);
            if (count < FALLBACKS_PER_REASON) {
                fallbacks.add(snapshot);
                fallbackCount.put(snapshot.reason(), count + 1);
            }
        }

        Instant cutoff = timeSource.instant().minus(ROLLBACK_WINDOW_DAYS, ChronoUnit.DAYS);
        int deleted = 0;
        int retained = 0;
        for (Snapshot snapshot : verified) {
            if (!snapshot.createdAt().isBefore(cutoff)
                    || markerProtected.contains(snapshot)
                    || fallbacks.contains(snapshot)) {
                retained++;
                continue;
            }
            if (deleteExpiredSnapshot(snapshotsDir, snapshot)) {
                deleted++;
            } else {
                retained++;
            }
        }
        return new RetentionResult(deleted, retained);
    }

    public Snapshot load(AppDataLock ownership, String snapshotId) throws IOException {
        try {
            requireMatch(snapshotId, SNAPSHOT_ID_PATTERN, "snapshotId");
        } catch (IllegalArgumentException exception) {
            throw new IOException("The requested WCode snapshot id is invalid.", exception);
        }
        Path snapshotsDir = ownership.requireOwnedAppDataDir().resolve("snapshots").toAbsolutePath().normalize();
        requireSafeDirectory(snapshotsDir, false, "The WCode snapshot directory is unsafe.");
        Path snapshotDir = snapshotsDir.resolve(snapshotId).normalize();
        validateContainedDirectory(snapshotsDir, snapshotDir, "The requested WCode snapshot does not exist.");
        return readSnapshot(snapshotsDir, snapshotDir);
    }

    public RestoreResult restore(AppDataLock ownership, Snapshot snapshot, String appVersion)
            throws IOException, SQLException {
        Objects.requireNonNull(snapshot, "snapshot");
        requireMatch(appVersion, VERSION_PATTERN, "appVersion");
        Path appDataDir = ownership.requireOwnedAppDataDir();
        recoverInterrupted(ownership);

        String snapshotId = snapshot.database().getParent().getFileName().toString();
        Snapshot trustedSnapshot = load(ownership, snapshotId);
        if (!trustedSnapshot.equals(snapshot) || !verify(trustedSnapshot)) {
            throw new IOException("The requested WCode snapshot failed verification.");
        }

        Path liveDatabase = appDataDir.resolve(DATABASE_NAME);
        requireRegularFileNoLinks(liveDatabase, "The live WCode database does not exist.");
        String operationId = newId(Instant.now());
        Path temporaryDatabase = appDataDir.resolve("." + DATABASE_NAME + ".restore-" + operationId + ".tmp");
        RestoreJournal journal = null;
        boolean targetInstalled = false;

        try {
            hooks.at(RecoveryPoint.BEFORE_TARGET_COPY);
            copyNoLinks(trustedSnapshot.database(), temporaryDatabase);
            forceFile(temporaryDatabase);
            if (!constantTimeEquals(trustedSnapshot.sha256(), sha256(temporaryDatabase))) {
                throw new IOException("The copied WCode restore target failed checksum verification.");
            }
            assertIntegrity(temporaryDatabase);
            probeAtomicReplacement(appDataDir, operationId);

            RecoveryBundle recoveryBundle = createRecoveryBundle(
                    appDataDir, liveDatabase, operationId, appVersion, trustedSnapshot);
            journal = new RestoreJournal(
                    operationId,
                    snapshotId,
                    recoveryBundle.id(),
                    recoveryBundle.databaseSha256(),
                    trustedSnapshot.sha256(),
                    temporaryDatabase.getFileName().toString(),
                    appVersion,
                    Instant.now());
            writeJournal(appDataDir, journal);
            hooks.at(RecoveryPoint.AFTER_JOURNAL_PUBLISHED);

            removeLiveSidecar(liveDatabase.resolveSibling(WAL_NAME));
            hooks.at(RecoveryPoint.AFTER_WAL_REMOVED);
            removeLiveSidecar(liveDatabase.resolveSibling(SHM_NAME));
            hooks.at(RecoveryPoint.AFTER_SHM_REMOVED);
            forceDirectory(appDataDir);

            atomicReplace(temporaryDatabase, liveDatabase);
            forceDirectory(appDataDir);
            hooks.at(RecoveryPoint.AFTER_DATABASE_REPLACED);
            verifyInstalledTarget(liveDatabase, journal.targetSha256());
            targetInstalled = true;
            hooks.at(RecoveryPoint.BEFORE_MARKER_PUBLISHED);
            writeCompletedMarker(appDataDir, journal);
            removeJournalAfterResolution(appDataDir);
            return new RestoreResult(trustedSnapshot, recoveryBundle, Instant.now());
        } catch (IOException | SQLException exception) {
            if (journal != null && !targetInstalled) {
                try {
                    rollbackToBundle(appDataDir, journal);
                    removeJournalAfterResolution(appDataDir);
                } catch (IOException | SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            bestEffortCleanupTemporary(temporaryDatabase, exception);
            throw exception;
        }
    }

    /** Resolves a journal left by process or machine termination before normal DB bootstrap. */
    public RecoveryOutcome recoverInterrupted(AppDataLock ownership) throws IOException, SQLException {
        Path appDataDir = ownership.requireOwnedAppDataDir();
        Path pending = pendingJournal(appDataDir);
        if (Files.notExists(pending, LinkOption.NOFOLLOW_LINKS)) {
            return RecoveryOutcome.NONE;
        }
        requireSafeDirectory(pending.getParent(), false, "The WCode recovery state directory is unsafe.");
        validateContainedRegularFile(appDataDir, pending, "The WCode recovery journal is unsafe.");
        RestoreJournal journal = readJournal(pending);
        RecoveryBundle bundle = loadBundle(appDataDir, journal.bundleId());
        if (!verify(bundle) || !bundle.databaseSha256().equals(journal.originalSha256())) {
            throw new IOException("The WCode forensic recovery bundle failed verification.");
        }

        Path liveDatabase = appDataDir.resolve(DATABASE_NAME);
        if (matchesCompleteLiveBundle(liveDatabase, bundle)) {
            removeJournalAfterResolution(appDataDir);
            return RecoveryOutcome.ROLLED_BACK;
        }
        String liveChecksum = Files.isRegularFile(liveDatabase, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(liveDatabase)
                ? sha256(liveDatabase)
                : "unavailable";
        if (constantTimeEquals(journal.targetSha256(), liveChecksum)) {
            removeLiveSidecar(liveDatabase.resolveSibling(WAL_NAME));
            removeLiveSidecar(liveDatabase.resolveSibling(SHM_NAME));
            verifyInstalledTarget(liveDatabase, journal.targetSha256());
            writeCompletedMarker(appDataDir, journal);
            bestEffortDelete(journal.temporaryDatabase(appDataDir), null);
            removeJournalAfterResolution(appDataDir);
            return RecoveryOutcome.COMPLETED;
        }

        rollbackToBundle(appDataDir, journal);
        bestEffortDelete(journal.temporaryDatabase(appDataDir), null);
        removeJournalAfterResolution(appDataDir);
        return RecoveryOutcome.ROLLED_BACK;
    }

    private RecoveryBundle createRecoveryBundle(
            Path appDataDir,
            Path liveDatabase,
            String operationId,
            String appVersion,
            Snapshot target) throws IOException {
        Path bundlesDir = appDataDir.resolve("recovery-bundles");
        requireSafeDirectory(bundlesDir, true, "The WCode recovery bundle directory is unsafe.");
        Path bundleDir = bundlesDir.resolve(operationId);
        Files.createDirectory(bundleDir);
        forceDirectory(bundlesDir);

        Path bundleDatabase = bundleDir.resolve(DATABASE_NAME);
        Path bundleWal = bundleDir.resolve(WAL_NAME);
        Path bundleShm = bundleDir.resolve(SHM_NAME);
        Path metadata = bundleDir.resolve(BUNDLE_METADATA);
        try {
            String databaseChecksum = copyExact(liveDatabase, bundleDatabase);
            String walChecksum = copyExactIfPresent(liveDatabase.resolveSibling(WAL_NAME), bundleWal);
            String shmChecksum = copyExactIfPresent(liveDatabase.resolveSibling(SHM_NAME), bundleShm);
            IntegrityStatus status = classifyBundleIntegrity(bundleDatabase, walChecksum, shmChecksum);
            Instant createdAt = Instant.now();
            String content = "formatVersion=1" + System.lineSeparator()
                    + "bundleId=" + operationId + System.lineSeparator()
                    + "createdAt=" + createdAt + System.lineSeparator()
                    + "appVersion=" + appVersion + System.lineSeparator()
                    + "restoreTarget=" + target.database().getParent().getFileName() + System.lineSeparator()
                    + "integrityStatus=" + status + System.lineSeparator()
                    + "databaseSha256=" + databaseChecksum + System.lineSeparator()
                    + "walSha256=" + walChecksum + System.lineSeparator()
                    + "shmSha256=" + shmChecksum + System.lineSeparator();
            writeAtomic(metadata, content);
            forceDirectory(bundleDir);
            RecoveryBundle bundle = new RecoveryBundle(
                    bundleDir,
                    operationId,
                    bundleDatabase,
                    bundleWal,
                    bundleShm,
                    metadata,
                    databaseChecksum,
                    walChecksum,
                    shmChecksum,
                    status,
                    createdAt,
                    appVersion,
                    target.database().getParent().getFileName().toString());
            if (!verify(bundle)) {
                throw new IOException("The WCode forensic recovery bundle failed verification.");
            }
            return bundle;
        } catch (IOException exception) {
            bestEffortDelete(metadata, exception);
            bestEffortDelete(bundleShm, exception);
            bestEffortDelete(bundleWal, exception);
            bestEffortDelete(bundleDatabase, exception);
            bestEffortDelete(bundleDir, exception);
            throw exception;
        }
    }

    private RecoveryBundle loadBundle(Path appDataDir, String bundleId) throws IOException {
        try {
            requireMatch(bundleId, SNAPSHOT_ID_PATTERN, "bundleId");
        } catch (IllegalArgumentException exception) {
            throw new IOException("The WCode recovery bundle id is invalid.", exception);
        }
        Path bundlesDir = appDataDir.resolve("recovery-bundles");
        requireSafeDirectory(bundlesDir, false, "The WCode recovery bundle directory is unsafe.");
        Path bundleDir = bundlesDir.resolve(bundleId).normalize();
        validateContainedDirectory(bundlesDir, bundleDir, "The WCode recovery bundle does not exist.");
        return readBundle(bundlesDir, bundleDir);
    }

    private static RecoveryBundle readBundle(Path bundlesDir, Path bundleDir) throws IOException {
        validateContainedDirectory(bundlesDir, bundleDir, "The WCode recovery bundle is unsafe.");
        Path database = bundleDir.resolve(DATABASE_NAME);
        Path wal = bundleDir.resolve(WAL_NAME);
        Path shm = bundleDir.resolve(SHM_NAME);
        Path metadata = bundleDir.resolve(BUNDLE_METADATA);
        validateContainedRegularFile(bundlesDir, database, "The WCode recovery bundle is incomplete.");
        validateContainedRegularFile(bundlesDir, metadata, "The WCode recovery bundle is incomplete.");
        Properties properties = readProperties(metadata);
        try {
            if (!"1".equals(properties.getProperty("formatVersion"))) {
                throw new IllegalArgumentException("unsupported bundle format");
            }
            String id = properties.getProperty("bundleId");
            String appVersion = properties.getProperty("appVersion");
            String restoreTarget = properties.getProperty("restoreTarget");
            String databaseChecksum = properties.getProperty("databaseSha256");
            String walChecksum = properties.getProperty("walSha256");
            String shmChecksum = properties.getProperty("shmSha256");
            requireMatch(id, SNAPSHOT_ID_PATTERN, "bundleId");
            requireMatch(appVersion, VERSION_PATTERN, "appVersion");
            requireMatch(restoreTarget, SNAPSHOT_ID_PATTERN, "restoreTarget");
            requireMatch(databaseChecksum, CHECKSUM_PATTERN, "databaseSha256");
            requireOptionalChecksum(walChecksum, "walSha256");
            requireOptionalChecksum(shmChecksum, "shmSha256");
            if (!bundleDir.getFileName().toString().equals(id)) {
                throw new IllegalArgumentException("bundle id mismatch");
            }
            validateBundleSidecar(bundlesDir, wal, walChecksum);
            validateBundleSidecar(bundlesDir, shm, shmChecksum);
            return new RecoveryBundle(
                    bundleDir,
                    id,
                    database,
                    wal,
                    shm,
                    metadata,
                    databaseChecksum,
                    walChecksum,
                    shmChecksum,
                    IntegrityStatus.valueOf(properties.getProperty("integrityStatus")),
                    Instant.parse(properties.getProperty("createdAt")),
                    appVersion,
                    restoreTarget);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IOException("The WCode recovery bundle metadata is invalid.", exception);
        }
    }

    private void rollbackToBundle(Path appDataDir, RestoreJournal journal) throws IOException, SQLException {
        RecoveryBundle bundle = loadBundle(appDataDir, journal.bundleId());
        if (!verify(bundle) || !bundle.databaseSha256().equals(journal.originalSha256())) {
            throw new IOException("The WCode forensic recovery bundle failed verification.");
        }
        Path liveDatabase = appDataDir.resolve(DATABASE_NAME);
        Path rollbackDatabase = appDataDir.resolve("." + DATABASE_NAME + ".rollback-" + journal.operationId() + ".tmp");
        try {
            copyNoLinks(bundle.database(), rollbackDatabase);
            forceFile(rollbackDatabase);
            requireChecksum(rollbackDatabase, bundle.databaseSha256());
            removeLiveSidecar(liveDatabase.resolveSibling(WAL_NAME));
            removeLiveSidecar(liveDatabase.resolveSibling(SHM_NAME));
            atomicReplace(rollbackDatabase, liveDatabase);
            forceDirectory(appDataDir);
            requireChecksum(liveDatabase, bundle.databaseSha256());
            restoreBundleSidecar(bundle.wal(), liveDatabase.resolveSibling(WAL_NAME), bundle.walSha256(), journal.operationId());
            restoreBundleSidecar(bundle.shm(), liveDatabase.resolveSibling(SHM_NAME), bundle.shmSha256(), journal.operationId());
            requireOptionalLiveChecksum(liveDatabase.resolveSibling(WAL_NAME), bundle.walSha256());
            requireOptionalLiveChecksum(liveDatabase.resolveSibling(SHM_NAME), bundle.shmSha256());
            forceDirectory(appDataDir);
        } finally {
            bestEffortDelete(rollbackDatabase, null);
        }
    }

    private static void restoreBundleSidecar(Path source, Path destination, String checksum, String operationId)
            throws IOException {
        if (NONE.equals(checksum)) {
            Files.deleteIfExists(destination);
            return;
        }
        Path temporary = destination.resolveSibling("." + destination.getFileName() + ".restore-" + operationId + ".tmp");
        try {
            copyNoLinks(source, temporary);
            forceFile(temporary);
            requireChecksum(temporary, checksum);
            atomicReplace(temporary, destination);
        } finally {
            bestEffortDelete(temporary, null);
        }
    }

    private static void verifyInstalledTarget(Path liveDatabase, String expectedChecksum)
            throws IOException, SQLException {
        requireRegularFileNoLinks(liveDatabase, "The restored WCode database is missing.");
        requireChecksum(liveDatabase, expectedChecksum);
        assertIntegrity(liveDatabase);
    }

    private void bestEffortCleanupTemporary(Path temporary, Exception original) {
        try {
            hooks.at(RecoveryPoint.BEFORE_TEMP_CLEANUP);
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private static void writeJournal(Path appDataDir, RestoreJournal journal) throws IOException {
        requireSafeDirectory(appDataDir, false, "The WCode app-data directory is unsafe.");
        if (Files.exists(pendingJournal(appDataDir), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("A WCode recovery operation is already pending.");
        }
        String content = "formatVersion=1" + System.lineSeparator()
                + "phase=SWAP_PENDING" + System.lineSeparator()
                + "operationId=" + journal.operationId() + System.lineSeparator()
                + "snapshotId=" + journal.snapshotId() + System.lineSeparator()
                + "bundleId=" + journal.bundleId() + System.lineSeparator()
                + "originalSha256=" + journal.originalSha256() + System.lineSeparator()
                + "targetSha256=" + journal.targetSha256() + System.lineSeparator()
                + "temporaryDatabase=" + journal.temporaryDatabaseName() + System.lineSeparator()
                + "appVersion=" + journal.appVersion() + System.lineSeparator()
                + "createdAt=" + journal.createdAt() + System.lineSeparator();
        writeAtomic(pendingJournal(appDataDir), content);
        forceDirectory(appDataDir);
    }

    private static RestoreJournal readJournal(Path journalPath) throws IOException {
        Properties properties = readProperties(journalPath);
        try {
            if (!"1".equals(properties.getProperty("formatVersion"))
                    || !"SWAP_PENDING".equals(properties.getProperty("phase"))) {
                throw new IllegalArgumentException("unsupported recovery journal");
            }
            String operationId = properties.getProperty("operationId");
            String snapshotId = properties.getProperty("snapshotId");
            String bundleId = properties.getProperty("bundleId");
            String originalChecksum = properties.getProperty("originalSha256");
            String targetChecksum = properties.getProperty("targetSha256");
            String temporaryDatabase = properties.getProperty("temporaryDatabase");
            String appVersion = properties.getProperty("appVersion");
            requireMatch(operationId, SNAPSHOT_ID_PATTERN, "operationId");
            requireMatch(snapshotId, SNAPSHOT_ID_PATTERN, "snapshotId");
            requireMatch(bundleId, SNAPSHOT_ID_PATTERN, "bundleId");
            requireMatch(originalChecksum, CHECKSUM_PATTERN, "originalSha256");
            requireMatch(targetChecksum, CHECKSUM_PATTERN, "targetSha256");
            requireMatch(appVersion, VERSION_PATTERN, "appVersion");
            String expectedTemporary = "." + DATABASE_NAME + ".restore-" + operationId + ".tmp";
            if (!expectedTemporary.equals(temporaryDatabase)) {
                throw new IllegalArgumentException("temporary database mismatch");
            }
            return new RestoreJournal(
                    operationId,
                    snapshotId,
                    bundleId,
                    originalChecksum,
                    targetChecksum,
                    temporaryDatabase,
                    appVersion,
                    Instant.parse(properties.getProperty("createdAt")));
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IOException("The WCode recovery journal is invalid.", exception);
        }
    }

    private static void writeCompletedMarker(Path appDataDir, RestoreJournal journal) throws IOException {
        requireSafeDirectory(appDataDir, false, "The WCode app-data directory is unsafe.");
        String content = "formatVersion=1" + System.lineSeparator()
                + "operationId=" + journal.operationId() + System.lineSeparator()
                + "restoredSnapshot=" + journal.snapshotId() + System.lineSeparator()
                + "restoredSha256=" + journal.targetSha256() + System.lineSeparator()
                + "recoveryBundle=" + journal.bundleId() + System.lineSeparator()
                + "restoredAt=" + Instant.now() + System.lineSeparator();
        writeAtomic(appDataDir.resolve(".wcode-restore-" + journal.operationId() + ".properties"), content);
        forceDirectory(appDataDir);
    }

    private void removeJournalAfterResolution(Path appDataDir) {
        try {
            hooks.at(RecoveryPoint.BEFORE_JOURNAL_REMOVAL);
            Files.deleteIfExists(pendingJournal(appDataDir));
            hooks.at(RecoveryPoint.AFTER_JOURNAL_DELETED);
            forceDirectory(appDataDir);
        } catch (IOException ignored) {
            // The database is already in a fully resolved original or target state. A journal
            // left behind is intentionally idempotent and will be resolved on the next startup.
        }
    }

    private Snapshot readSnapshotOrNull(Path snapshotsDir, Path snapshotDir) {
        try {
            return readSnapshot(snapshotsDir, snapshotDir);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static Snapshot readSnapshot(Path snapshotsDir, Path snapshotDir) throws IOException {
        validateContainedDirectory(snapshotsDir, snapshotDir, "The WCode snapshot is unsafe.");
        Path database = snapshotDir.resolve(DATABASE_NAME);
        Path metadata = snapshotDir.resolve(SNAPSHOT_METADATA);
        validateContainedRegularFile(snapshotsDir, database, "The WCode snapshot is incomplete.");
        validateContainedRegularFile(snapshotsDir, metadata, "The WCode snapshot is incomplete.");
        Properties properties = readProperties(metadata);
        try {
            String appVersion = properties.getProperty("appVersion");
            String reason = properties.getProperty("reason");
            String checksum = properties.getProperty("sha256");
            requireMatch(appVersion, VERSION_PATTERN, "appVersion");
            requireMatch(reason, REASON_PATTERN, "reason");
            requireMatch(checksum, CHECKSUM_PATTERN, "sha256");
            int schemaVersion = Integer.parseInt(properties.getProperty("schemaVersion"));
            if (schemaVersion < 0) {
                throw new IllegalArgumentException("schemaVersion must not be negative");
            }
            Instant createdAt = Instant.parse(properties.getProperty("createdAt"));
            return new Snapshot(database, metadata, checksum, appVersion, schemaVersion, reason, createdAt);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IOException("The WCode snapshot metadata is invalid.", exception);
        }
    }

    private static Properties readProperties(Path metadata) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> referencedSnapshotChecksums(Path appDataDir) throws IOException {
        Path writerState = appDataDir.resolve("writer-state");
        if (Files.notExists(writerState, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        requireSafeDirectory(writerState, false, "The WCode writer-state directory is unsafe.");
        Set<String> checksums = new HashSet<>();
        try (var markers = Files.list(writerState)) {
            for (Path marker : markers.toList()) {
                if (Files.isSymbolicLink(marker)
                        || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(marker) > 4096) {
                    continue;
                }
                Matcher matcher = MARKER_CHECKSUM.matcher(Files.readString(marker, StandardCharsets.UTF_8));
                while (matcher.find()) checksums.add(matcher.group(1));
            }
        }
        return Set.copyOf(checksums);
    }

    private boolean deleteExpiredSnapshot(Path snapshotsDir, Snapshot snapshot) {
        Path directory = snapshot.database().getParent();
        try {
            if (directory == null
                    || !SNAPSHOT_ID_PATTERN.matcher(directory.getFileName().toString()).matches()
                    || !verify(snapshot)) {
                return false;
            }
            validateContainedDirectory(snapshotsDir, directory, "The WCode snapshot is unsafe.");
            Set<String> entries;
            try (var files = Files.list(directory)) {
                entries = files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
            }
            if (!entries.equals(Set.of(DATABASE_NAME, SNAPSHOT_METADATA))) {
                return false;
            }
            Files.delete(snapshot.metadata());
            Files.delete(snapshot.database());
            Files.delete(directory);
            forceDirectory(snapshotsDir);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static void validateBundleSidecar(Path root, Path sidecar, String checksum) throws IOException {
        requireOptionalChecksum(checksum, sidecar.getFileName().toString());
        if (NONE.equals(checksum)) {
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("The WCode recovery bundle has unexpected sidecar data.");
            }
        } else {
            validateContainedRegularFile(root, sidecar, "The WCode recovery bundle is incomplete.");
        }
    }

    private static void requireSafeDirectory(Path directory, boolean create, String message) throws IOException {
        if (create && Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory);
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(message);
        }
    }

    private static void validateContainedDirectory(Path root, Path directory, String message) throws IOException {
        requireSafeDirectory(root, false, message);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(message);
        }
        Path rootReal = root.toRealPath();
        Path directoryReal = directory.toRealPath();
        if (!directoryReal.startsWith(rootReal) || !directory.getParent().toAbsolutePath().normalize()
                .equals(root.toAbsolutePath().normalize())) {
            throw new IOException(message);
        }
    }

    private static void validateContainedRegularFile(Path root, Path file, String message) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(message);
        }
        Path rootReal = root.toRealPath();
        Path fileReal = file.toRealPath();
        if (!fileReal.startsWith(rootReal)) {
            throw new IOException(message);
        }
    }

    private static void requireRegularFileNoLinks(Path file, String message) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(message);
        }
    }

    private void requireSnapshotCapacity(Path appDataDir, Path liveDatabase) throws IOException {
        long sourceBytes = Files.size(liveDatabase);
        Path wal = appDataDir.resolve(WAL_NAME);
        if (Files.exists(wal, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFileNoLinks(wal, "The WCode database WAL is unsafe.");
            sourceBytes = saturatedAdd(sourceBytes, Files.size(wal));
        }
        long percentageHeadroom = sourceBytes / 10;
        long headroomBytes = Math.max(SNAPSHOT_MINIMUM_HEADROOM_BYTES, percentageHeadroom);
        long requiredBytes = saturatedAdd(sourceBytes, headroomBytes);
        long availableBytes = usableSpaceProbe.usableBytes(appDataDir);
        if (availableBytes < 0) {
            throw new IOException("WCode could not determine the free disk space safely.");
        }
        if (availableBytes < requiredBytes) {
            throw new InsufficientDiskSpaceException(requiredBytes, availableBytes);
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("Byte counts must not be negative");
        }
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static void removeLiveSidecar(Path sidecar) throws IOException {
        if (Files.isSymbolicLink(sidecar)) {
            throw new IOException("A WCode database sidecar path is unsafe.");
        }
        Files.deleteIfExists(sidecar);
    }

    private static String copyExact(Path source, Path destination) throws IOException {
        requireRegularFileNoLinks(source, "A WCode recovery source file is missing or unsafe.");
        String before = sha256(source);
        copyNoLinks(source, destination);
        forceFile(destination);
        String after = sha256(source);
        String copied = sha256(destination);
        if (!constantTimeEquals(before, after) || !constantTimeEquals(before, copied)) {
            throw new IOException("WCode recovery source bytes changed while they were copied.");
        }
        return copied;
    }

    private static String copyExactIfPresent(Path source, Path destination) throws IOException {
        if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) {
            return NONE;
        }
        return copyExact(source, destination);
    }

    private static void copyNoLinks(Path source, Path destination) throws IOException {
        requireRegularFileNoLinks(source, "A WCode recovery source file is missing or unsafe.");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("A WCode recovery destination path is unsafe.");
            }
            Files.delete(destination);
        }
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                FileChannel output = FileChannel.open(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
            output.force(true);
        }
    }

    private static void probeAtomicReplacement(Path appDataDir, String operationId) throws IOException {
        Path source = appDataDir.resolve(".atomic-probe-" + operationId + ".source");
        Path destination = appDataDir.resolve(".atomic-probe-" + operationId + ".destination");
        try {
            Files.write(source, new byte[] {0x31}, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.write(destination, new byte[] {0x30}, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            forceFile(source);
            forceFile(destination);
            atomicReplace(source, destination);
            if (!MessageDigest.isEqual(new byte[] {0x31}, Files.readAllBytes(destination))) {
                throw new IOException("Atomic WCode database replacement probe failed.");
            }
            forceDirectory(appDataDir);
        } finally {
            bestEffortDelete(source, null);
            bestEffortDelete(destination, null);
        }
    }

    private static void atomicReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("This filesystem cannot atomically replace the WCode database.", exception);
        }
    }

    private static void writeAtomic(Path destination, String content) throws IOException {
        Path parent = destination.getParent();
        requireSafeDirectory(parent, true, "A WCode recovery metadata directory is unsafe.");
        Path temporary = destination.resolveSibling("." + destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            atomicReplace(temporary, destination);
            forceDirectory(parent);
        } finally {
            bestEffortDelete(temporary, null);
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException exception) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
                throw exception;
            }
            // Windows does not allow Java FileChannel handles for directories. Atomic file replacement
            // still provides the strongest durability primitive exposed by the portable JDK there.
        }
    }

    private static void bestEffortDelete(Path path, Exception original) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            if (original != null) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private static IntegrityStatus sqliteIntegrityStatus(Path database) throws IOException {
        Path probe = database.resolveSibling(".integrity-probe-" + UUID.randomUUID() + ".db");
        try {
            copyNoLinks(database, probe);
            assertIntegrity(probe);
            return IntegrityStatus.VERIFIED;
        } catch (SQLException exception) {
            return IntegrityStatus.CORRUPT;
        } finally {
            bestEffortDelete(probe.resolveSibling(probe.getFileName() + "-wal"), null);
            bestEffortDelete(probe.resolveSibling(probe.getFileName() + "-shm"), null);
            bestEffortDelete(probe, null);
        }
    }

    private static IntegrityStatus classifyBundleIntegrity(Path database, String walChecksum, String shmChecksum)
            throws IOException {
        IntegrityStatus mainFileStatus = sqliteIntegrityStatus(database);
        if (mainFileStatus == IntegrityStatus.CORRUPT) {
            return IntegrityStatus.CORRUPT;
        }
        return NONE.equals(walChecksum) && NONE.equals(shmChecksum)
                ? IntegrityStatus.VERIFIED
                : IntegrityStatus.UNVERIFIED;
    }

    private static void backup(Path source, Path destination) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath())) {
            SQLiteConnection sqliteConnection = connection.unwrap(SQLiteConnection.class);
            int result = sqliteConnection
                    .getDatabase()
                    .backup("main", destination.toAbsolutePath().toString(), (remaining, total) -> {}, 100, 100, 100);
            if (result != SQLiteErrorCode.SQLITE_OK.code) {
                throw new SQLException("SQLite online backup failed with result code " + result);
            }
        }
    }

    private static void assertIntegrity(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1)) || result.next()) {
                throw new SQLException("SQLite integrity check failed for WCode data.");
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static boolean matchesOptionalChecksum(Path file, String checksum) throws IOException {
        if (NONE.equals(checksum)) {
            return Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
        }
        return !Files.isSymbolicLink(file)
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && constantTimeEquals(checksum, sha256(file));
    }

    private static boolean matchesCompleteLiveBundle(Path liveDatabase, RecoveryBundle bundle) throws IOException {
        if (Files.isSymbolicLink(liveDatabase)
                || !Files.isRegularFile(liveDatabase, LinkOption.NOFOLLOW_LINKS)
                || !constantTimeEquals(bundle.databaseSha256(), sha256(liveDatabase))) {
            return false;
        }
        return matchesOptionalChecksum(liveDatabase.resolveSibling(WAL_NAME), bundle.walSha256())
                && matchesOptionalChecksum(liveDatabase.resolveSibling(SHM_NAME), bundle.shmSha256());
    }

    private static void requireChecksum(Path file, String checksum) throws IOException {
        if (!constantTimeEquals(checksum, sha256(file))) {
            throw new IOException("WCode recovery checksum verification failed.");
        }
    }

    private static void requireOptionalLiveChecksum(Path file, String checksum) throws IOException {
        if (!matchesOptionalChecksum(file, checksum)) {
            throw new IOException("WCode recovery sidecar checksum verification failed.");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireOptionalChecksum(String value, String field) {
        if (!NONE.equals(value)) {
            requireMatch(value, CHECKSUM_PATTERN, field);
        }
    }

    private static void requireMatch(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has an invalid format");
        }
    }

    private static String newId(Instant instant) {
        return instant.toEpochMilli() + "-" + UUID.randomUUID();
    }

    private static String snapshotMetadata(
            String appVersion, int schemaVersion, String reason, Instant createdAt, String checksum) {
        return "appVersion=" + appVersion + System.lineSeparator()
                + "schemaVersion=" + schemaVersion + System.lineSeparator()
                + "reason=" + reason + System.lineSeparator()
                + "createdAt=" + createdAt + System.lineSeparator()
                + "sha256=" + checksum + System.lineSeparator();
    }

    private static Path pendingJournal(Path appDataDir) {
        return appDataDir.resolve(PENDING_JOURNAL);
    }

    enum RecoveryPoint {
        BEFORE_TARGET_COPY,
        AFTER_JOURNAL_PUBLISHED,
        AFTER_WAL_REMOVED,
        AFTER_SHM_REMOVED,
        AFTER_DATABASE_REPLACED,
        BEFORE_MARKER_PUBLISHED,
        BEFORE_JOURNAL_REMOVAL,
        AFTER_JOURNAL_DELETED,
        BEFORE_TEMP_CLEANUP
    }

    @FunctionalInterface
    interface RecoveryHooks {
        void at(RecoveryPoint point) throws IOException;
    }

    @FunctionalInterface
    interface UsableSpaceProbe {
        long usableBytes(Path path) throws IOException;
    }

    public static final class InsufficientDiskSpaceException extends IOException {
        private final long requiredBytes;
        private final long availableBytes;

        private InsufficientDiskSpaceException(long requiredBytes, long availableBytes) {
            super("Not enough free disk space to protect WCode data before the upgrade. Required: "
                    + roundedUpMiB(requiredBytes)
                    + " MiB; available: "
                    + roundedDownMiB(availableBytes)
                    + " MiB. No data was changed. / Không đủ dung lượng trống để bảo vệ dữ liệu "
                    + "WCode trước khi nâng cấp; chưa có dữ liệu nào bị thay đổi.");
            this.requiredBytes = requiredBytes;
            this.availableBytes = availableBytes;
        }

        public long requiredBytes() {
            return requiredBytes;
        }

        public long availableBytes() {
            return availableBytes;
        }

        private static long roundedUpMiB(long bytes) {
            long unit = 1024L * 1024;
            return bytes / unit + (bytes % unit == 0 ? 0 : 1);
        }

        private static long roundedDownMiB(long bytes) {
            return bytes / (1024L * 1024);
        }
    }

    public enum IntegrityStatus {
        VERIFIED,
        UNVERIFIED,
        CORRUPT
    }

    public enum RecoveryOutcome {
        NONE,
        COMPLETED,
        ROLLED_BACK
    }

    public record Snapshot(
            Path database,
            Path metadata,
            String sha256,
            String appVersion,
            int schemaVersion,
            String reason,
            Instant createdAt) {
        public Snapshot {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(appVersion, "appVersion");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record RecoveryBundle(
            Path directory,
            String id,
            Path database,
            Path wal,
            Path shm,
            Path metadata,
            String databaseSha256,
            String walSha256,
            String shmSha256,
            IntegrityStatus integrityStatus,
            Instant createdAt,
            String appVersion,
            String restoreTarget) {
        public RecoveryBundle {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(wal, "wal");
            Objects.requireNonNull(shm, "shm");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(databaseSha256, "databaseSha256");
            Objects.requireNonNull(walSha256, "walSha256");
            Objects.requireNonNull(shmSha256, "shmSha256");
            Objects.requireNonNull(integrityStatus, "integrityStatus");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(appVersion, "appVersion");
            Objects.requireNonNull(restoreTarget, "restoreTarget");
        }
    }

    public record RestoreResult(
            Snapshot restoredSnapshot, RecoveryBundle recoveryBundle, Instant restoredAt) {
        public RestoreResult {
            Objects.requireNonNull(restoredSnapshot, "restoredSnapshot");
            Objects.requireNonNull(recoveryBundle, "recoveryBundle");
            Objects.requireNonNull(restoredAt, "restoredAt");
        }
    }

    public record RetentionResult(int deleted, int retained) {
        public RetentionResult {
            if (deleted < 0 || retained < 0) {
                throw new IllegalArgumentException("Retention counts must not be negative");
            }
        }
    }

    private record RestoreJournal(
            String operationId,
            String snapshotId,
            String bundleId,
            String originalSha256,
            String targetSha256,
            String temporaryDatabaseName,
            String appVersion,
            Instant createdAt) {
        private Path temporaryDatabase(Path appDataDir) {
            return appDataDir.resolve(temporaryDatabaseName);
        }
    }
}
