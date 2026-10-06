package com.tuandev.fbsbarcode.features.shop;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopOperationCoordinatorTest {
    @TempDir Path appData;
    private ExecutorService executor;
    private ShopRepository shops;

    @BeforeEach
    void setUp() {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        shops = new ShopRepository();
        shops.insert(new Shop("First", "token-1"));
        shops.insert(new Shop("Second", "token-2"));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void deletionWaitsForRunningShopWorkAndRejectsQueuedStaleWork() throws Exception {
        int shopId = shops.findAll().getFirst().getId();
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        Future<Void> operation = executor.submit(() -> ShopOperationCoordinator.withActiveShop(shopId, () -> {
            operationEntered.countDown();
            assertTrue(releaseOperation.await(5, TimeUnit.SECONDS));
            return null;
        }));
        assertTrue(operationEntered.await(5, TimeUnit.SECONDS));

        Future<Integer> deletion = executor.submit(() -> shops.delete(shopId));
        assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));
        releaseOperation.countDown();

        operation.get(5, TimeUnit.SECONDS);
        assertEquals(1, deletion.get(5, TimeUnit.SECONDS));
        assertThrows(ShopOperationCoordinator.ShopUnavailableException.class,
                () -> ShopOperationCoordinator.withActiveShop(shopId, () -> null));
        assertFalse(shops.findAll().stream().anyMatch(shop -> shop.getId() == shopId));
        assertTrue(foreignKeyCheckIsClean());
    }

    private boolean foreignKeyCheckIsClean() throws Exception {
        try (Connection connection = Database.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            return !result.next();
        }
    }
}
