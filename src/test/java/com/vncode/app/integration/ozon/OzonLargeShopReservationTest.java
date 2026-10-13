package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;

import com.vncode.app.config.Database;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline scale and ownership checks; these make no marketplace requests or timing guarantees. */
class OzonLargeShopReservationTest {
    private static final String GTIN = "04600000000001";
    @TempDir Path directory;

    @BeforeEach void setUp() {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
    }
    @AfterEach void tearDown() { System.clearProperty("vncode.appdata.dir"); }

    @Test void tenShopsWithOneThousandOrdersUseScopedBulkReadsAndOneKizPerUnit() throws Exception {
        seed(10, 1000);
        List<String> numbers = IntStream.range(0, 1000).mapToObj(OzonLargeShopReservationTest::number).toList();
        OzonPostingRepository postings = new OzonPostingRepository();
        OzonExemplarJobRepository jobs = new OzonExemplarJobRepository();
        AtomicInteger statements = new AtomicInteger();
        try (Connection connection = Database.getConnection()) {
            Connection counted = counted(connection, statements);
            for (int shop = 1; shop <= 10; shop++) {
                Map<String, OzonPostingDto> values = postings.findMany(counted, shop, numbers);
                assertEquals(1000, values.size());
                assertEquals(numbers, List.copyOf(values.keySet()));
                for (OzonPostingDto value : values.values()) {
                    assertEquals("shop-" + shop, value.items().getFirst().name());
                    assertEquals(1, value.items().size());
                }
            }
        }
        assertEquals(40, statements.get(), "Four SELECTs per 1,000-order shop, not 1,001 per shop");
        for (int shop = 1; shop <= 10; shop++) {
            for (String number : numbers) {
                var reserved = jobs.stageLocalForPrint(shop, plan(number), () -> { });
                assertTrue(reserved.ready());
                assertEquals(OzonExemplarJobStage.VALIDATED, reserved.job().stage());
            }
        }
        assertEquals(10_000, scalar("SELECT COUNT(*) FROM ozon_exemplars WHERE kiz_id IS NOT NULL"));
        assertEquals(10_000, scalar("SELECT COUNT(DISTINCT kiz_id) FROM ozon_exemplars"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ozon_exemplars e JOIN kiz_codes k ON k.id=e.kiz_id WHERE e.shop_id<>k.shop_id"));
        // Repeating a 1,000-order selection reuses exactly its existing reservations.
        for (String number : numbers) assertTrue(jobs.stageLocalForPrint(1, plan(number), () -> { }).ready());
        assertEquals(10_000, scalar("SELECT COUNT(*) FROM kiz_codes WHERE status='RESERVED'"));
        Map<String, OzonExemplarJob> selected = jobs.findMany(1, numbers);
        assertEquals(1000, selected.size());
        assertEquals(1000, jobs.bindingCounts(1, selected.values().stream().map(OzonExemplarJob::id).toList()).size());
        assertEquals(Map.of(), jobs.bindingCounts(2, selected.values().stream().map(OzonExemplarJob::id).toList()));
        jobs.consumePrintedBatch(1, numbers);
        jobs.consumePrintedBatch(1, numbers);
        assertEquals(1000, scalar("SELECT COUNT(*) FROM kiz_codes WHERE shop_id=1 AND status='CONSUMED'"));
        assertEquals(9000, scalar("SELECT COUNT(*) FROM kiz_codes WHERE shop_id<>1 AND status='RESERVED'"));
        assertEquals(1000, scalar("SELECT COUNT(*) FROM ozon_exemplar_jobs WHERE shop_id=1 AND stage='ACCEPTED'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ozon_exemplar_jobs WHERE shop_id<>1 AND stage='ACCEPTED'"));
        assertDatabaseIntegrity();
    }

    @Test void secondChunkOwnershipFailureRollsBackTheWholePrintedBatch() throws Exception {
        seed(1, 1000);
        OzonExemplarJobRepository jobs = new OzonExemplarJobRepository();
        List<String> numbers = IntStream.range(0, 1000).mapToObj(OzonLargeShopReservationTest::number).toList();
        for (String number : numbers) jobs.stageLocalForPrint(1, plan(number), () -> { });
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE kiz_codes SET status='AVAILABLE',reservation_token=NULL WHERE id=800");
        }
        assertThrows(IllegalStateException.class, () -> jobs.consumePrintedBatch(1, numbers));
        assertEquals(0, scalar("SELECT COUNT(*) FROM kiz_codes WHERE status='CONSUMED'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ozon_exemplar_jobs WHERE stage='ACCEPTED'"));
        assertEquals(1000, scalar("SELECT COUNT(*) FROM ozon_exemplar_jobs WHERE stage='VALIDATED'"));
    }

    @Test void licenseRevokedBeforeCommitRollsBackNewSlotsAndAllocation() throws Exception {
        seed(1, 2);
        AtomicInteger authorization = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new OzonExemplarJobRepository().stageLocalForPrint(
                1, plan(number(0)), () -> {
                    if (authorization.incrementAndGet() == 3) throw new IllegalStateException("rights revoked");
                }));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ozon_exemplar_jobs"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM kiz_codes WHERE status='AVAILABLE'"));
    }

    @Test void insufficientSecondItemLeavesNoPartialReservationAndKeepsRetryableJob() throws Exception {
        seed(1, 2);
        OzonPostingDto posting = new OzonPostingRepository().find(1, number(0));
        new OzonPostingRepository().upsertDetail(1, new OzonPostingDto(posting.postingNumber(), posting.orderId(),
                posting.orderNumber(), posting.status(), posting.substatus(), posting.warehouseId(), posting.shipmentAt(),
                posting.inProcessAt(), posting.lowerBarcode(), posting.upperBarcode(), posting.requirements(),
                posting.availableActions(), posting.shipAvailable(), List.of(posting.items().getFirst(),
                new OzonPostingItemDto(1, "product-2", "sku-2", "offer-2", "second", 1, "RUB", "1"))));
        var plan = new OzonRequirementGuard.PreparationPlan(number(0), List.of(
                new OzonRequirementGuard.RequiredItem(0, "product", "sku", GTIN, 1, true),
                new OzonRequirementGuard.RequiredItem(1, "product-2", "sku-2", "04600000000018", 1, true)));
        var reserved = new OzonExemplarJobRepository().stageLocalForPrint(1, plan, () -> { });
        assertFalse(reserved.ready());
        assertEquals("kiz_unavailable", reserved.job().safeErrorCode());
        assertEquals(OzonExemplarJobStage.CREATED, reserved.job().stage());
        assertEquals(2, scalar("SELECT COUNT(*) FROM kiz_codes WHERE status='AVAILABLE'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ozon_exemplars WHERE kiz_id IS NOT NULL"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM ozon_exemplars"));
    }

    private static OzonRequirementGuard.PreparationPlan plan(String number) {
        return new OzonRequirementGuard.PreparationPlan(number, List.of(
                new OzonRequirementGuard.RequiredItem(0, "product", "sku", GTIN, 1, true)));
    }
    private static String number(int index) { return "POST-" + String.format(java.util.Locale.ROOT, "%04d", index); }

    private void seed(int shops, int orders) throws Exception {
        String now = "2026-10-13T00:00:00Z";
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement shop = connection.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(?,?,'OZON','offline','fixture')");
                    PreparedStatement product = connection.prepareStatement("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(?,?,?,?)");
                    PreparedStatement order = connection.prepareStatement("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(?,?,?,?, 'COMPLETED',?,?)");
                    PreparedStatement posting = connection.prepareStatement("INSERT INTO ozon_postings(shop_id,posting_number,order_id,order_number,status,substatus,warehouse_id,shipment_at,in_process_at,lower_barcode,upper_barcode,mandatory_mark_product_ids,optional_mark_product_ids,unsupported_requirements,available_actions,ship_available,synced_at) VALUES(?,?,?,?,'awaiting_packaging','','warehouse',?,'','','','[\"product\"]','[]','[]','[]',0,?)");
                    PreparedStatement item = connection.prepareStatement("INSERT INTO ozon_posting_items(shop_id,posting_number,item_index,product_id,sku,offer_id,name,quantity,currency_code,price) VALUES(?,?,0,'product','sku','offer',?,1,'RUB','1')");
                    PreparedStatement kiz = connection.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(?,?,?,?,?,?,'AVAILABLE','IN_CIRCULATION',?,?)")) {
                for (int owner = 1; owner <= shops; owner++) {
                    shop.setInt(1, owner); shop.setString(2, "shop-" + owner); shop.executeUpdate();
                    product.setInt(1, owner); product.setString(2, GTIN); product.setString(3, "offline"); product.setString(4, now); product.executeUpdate();
                    order.setInt(1, owner); order.setInt(2, owner); order.setString(3, GTIN); order.setInt(4, orders); order.setString(5, now); order.setString(6, now); order.executeUpdate();
                    for (int index = 0; index < orders; index++) {
                        String number = number(index);
                        posting.setInt(1, owner); posting.setString(2, number); posting.setString(3, number); posting.setString(4, number); posting.setString(5, now); posting.setString(6, now); posting.addBatch();
                        item.setInt(1, owner); item.setString(2, number); item.setString(3, "shop-" + owner); item.addBatch();
                        String code = "010460000000000121OFFLINE-" + owner + "-" + index;
                        kiz.setLong(1, (long) (owner - 1) * orders + index + 1); kiz.setInt(2, owner); kiz.setInt(3, owner); kiz.setString(4, code); kiz.setString(5, code); kiz.setString(6, GTIN); kiz.setString(7, now); kiz.setString(8, now); kiz.addBatch();
                    }
                    posting.executeBatch(); item.executeBatch(); kiz.executeBatch();
                }
                connection.commit();
            }
        }
    }
    private static Connection counted(Connection delegate, AtomicInteger count) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) count.incrementAndGet();
                    try { return method.invoke(delegate, arguments); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
    }
    private static int scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) { return result.getInt(1); }
    }
    private static void assertDatabaseIntegrity() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(foreignKeys.next()); }
            try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) { assertEquals("ok", integrity.getString(1)); }
        }
    }
}
