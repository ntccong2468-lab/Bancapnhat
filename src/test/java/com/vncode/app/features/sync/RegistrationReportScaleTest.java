package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.ProgressHandler;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic SQLite reporting acceptance; no Catalog requests or marketplace changes. */
class RegistrationReportScaleTest {
    @TempDir Path directory;
    private final Shop shop = new Shop(1, "Fixture WB", "fixture-report-owner");
    private final SyncRunRepository reports = new SyncRunRepository();

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture WB','WILDBERRIES','fixture-report-owner')");
        }
    }
    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }

    @ParameterizedTest @ValueSource(ints = {3000, 10_000})
    void onePendingCardAmongThousandsUsesBoundedIndexWorkInsteadOfCountingTheWholeRun(int size) throws Exception {
        var selection = selection(size);
        String run = new RegistrationAttemptReports(shop, selection).queue();
        try (var connection = Database.getConnection(); var statement = connection.prepareStatement("UPDATE sync_run_items SET state='SUCCEEDED' WHERE run_id=?")) {
            statement.setString(1, run); assertEquals(size, statement.executeUpdate());
        }
        var last = reports.items(run, size - 1, 1).getFirst();
        reports.update(shop, last, "WAITING_REMOTE", "FEED_SUBMITTED", "", null);

        try (var connection = Database.getConnection()) {
            // Warm the connection's schema before counting application-query VM instructions.
            try (var statement = connection.createStatement(); var ignored = statement.executeQuery("SELECT 1 FROM sync_run_items LIMIT 1")) { assertTrue(ignored.next()); }
            var probes = new AtomicInteger();
            ProgressHandler.setHandler(connection, 100, bounded(probes));
            try {
                assertEquals("WAITING_REMOTE", SyncRunRepository.registrationState(connection, run));
                assertTrue(probes.get() <= 50, "Status existence checks must stay within a fixed VM-instruction budget");
            } finally { ProgressHandler.clearHandler(connection); }

            // Negative control: the old full-run aggregate exceeds the same deterministic
            // instruction budget. This measures work, not an OS-specific wall-clock deadline.
            probes.set(0); ProgressHandler.setHandler(connection, 100, bounded(probes));
            try {
                var interrupted = assertThrows(SQLException.class, () -> {
                    try (var statement = connection.prepareStatement("SELECT state,count(*) FROM sync_run_items WHERE run_id=? GROUP BY state")) {
                        statement.setString(1, run); try (var rows = statement.executeQuery()) { while (rows.next()) rows.getLong(2); }
                    }
                });
                assertEquals(9, interrupted.getErrorCode() & 0xff, "The negative control stops through SQLITE_INTERRUPT");
            } finally { ProgressHandler.clearHandler(connection); }
        }
        reports.registrationStage(shop, run, "FEED_SUBMITTED");
        assertEquals("WAITING_REMOTE", reports.find(run).orElseThrow().state());
        assertEquals(size, reports.find(run).orElseThrow().total());
        assertEquals(1L, reports.counts(run).get("WAITING_REMOTE"));
    }

    @Test void reopenedProgressPreservesStatePrecedenceAndNewlyAppendedCardsWithoutCounterCaches() throws Exception {
        var selection = selection(10_000);
        var progress = new RegistrationAttemptReports(shop, selection);
        String run = progress.queue();
        List<String> oldIds = new ArrayList<>();
        for (int offset = 0; offset < selection.size(); offset += 1000)
            oldIds.addAll(reports.items(run, offset, 1000).stream().map(SyncRunRepository.Item::id).toList());
        try (var connection = Database.getConnection(); var statement = connection.prepareStatement("UPDATE sync_run_items SET state='SUCCEEDED' WHERE run_id=?")) {
            statement.setString(1, run); assertEquals(selection.size(), statement.executeUpdate());
        }
        var first = reports.items(run, 0, 5);
        String[] states = {"CANCELLED", "NEEDS_ACTION", "WAITING_REMOTE", "QUEUED", "RUNNING"};
        for (int index = 0; index < states.length; index++) reports.update(shop, first.get(index), states[index], "PREFLIGHT", "", null);
        assertStage(run, "RUNNING");
        reports.update(shop, first.get(4), "SUCCEEDED", "CARD_PUBLISHED", "", null); assertStage(run, "RUNNING");
        reports.update(shop, first.get(3), "SUCCEEDED", "CARD_PUBLISHED", "", null); assertStage(run, "WAITING_REMOTE");
        reports.update(shop, first.get(2), "SUCCEEDED", "CARD_PUBLISHED", "", null); assertStage(run, "PARTIAL");
        assertEquals("ZNACK_REGISTRATION_NEEDS_ACTION", reports.find(run).orElseThrow().error());
        reports.update(shop, first.get(1), "SUCCEEDED", "CARD_PUBLISHED", "", null); assertStage(run, "CANCELLED");
        reports.update(shop, first.get(0), "SUCCEEDED", "CARD_PUBLISHED", "", null); assertStage(run, "SUCCEEDED");
        assertFalse(reports.find(run).orElseThrow().error().contains("NEEDS_ACTION"));

        Sku appended = sku(10_000);
        var added = reports.add(shop, run, Long.toString(appended.nmId()), Long.toString(appended.chrtId()), "", appended.vendorCode(), "Blue", "XL", "", "QUEUED", "", "{}");
        assertStage(run, "RUNNING");
        var resumed = RegistrationAttemptReports.attach(shop, run);
        resumed.begin(appended).blocked("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
        assertEquals("PARTIAL", reports.find(run).orElseThrow().state());
        assertEquals(10_001, reports.find(run).orElseThrow().total());
        assertEquals(added.id(), reports.registrationItem(shop, run, Long.toString(appended.nmId()), Long.toString(appended.chrtId())).orElseThrow().id());

        // Reopening an existing queued identity must retain its row, all peers and total.
        resumed.begin(selection.getLast()).blocked("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
        assertEquals(10_001, reports.find(run).orElseThrow().total());
        var current = new ArrayList<String>();
        for (int offset = 0; offset < selection.size(); offset += 1000)
            current.addAll(reports.items(run, offset, 1000).stream().map(SyncRunRepository.Item::id).toList());
        assertEquals(oldIds, current);
        var foreign = new Shop(1, "Fixture WB", "changed-owner");
        assertThrows(SecurityException.class, () -> reports.registrationStage(foreign, run, "PREFLIGHT"));
        assertThrows(SecurityException.class, () -> reports.registrationItem(foreign, run, "100000", "200000"));
        assertEquals("PARTIAL", reports.find(run).orElseThrow().state());
    }

    private void assertStage(String run, String expected) {
        new SyncRunRepository().registrationStage(shop, run, "PREFLIGHT");
        assertEquals(expected, reports.find(run).orElseThrow().state());
    }
    private static ProgressHandler bounded(AtomicInteger calls) {
        return new ProgressHandler() { @Override protected int progress() { return calls.incrementAndGet() > 50 ? 1 : 0; } };
    }
    private static List<Sku> selection(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(RegistrationReportScaleTest::sku).toList();
    }
    private static Sku sku(int index) {
        return new Sku(100_000L + index, 200_000L + index, 1, "Fixture-" + index, "Category", "Brand", "Fixture", "Blue", "XL", List.of(), "", true, "", null, "", Status.NOT_CREATED, "", false, "52");
    }
}
