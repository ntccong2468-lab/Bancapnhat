package com.tuandev.fbsbarcode.features.shop;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Prevents a shop deletion from racing background work that still writes with that shop id. */
public final class ShopOperationCoordinator {
    private static final ConcurrentHashMap<Integer, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    private ShopOperationCoordinator() {
    }

    public static <T, E extends Exception> T withActiveShop(int shopId, Operation<T, E> operation) throws E {
        ReentrantReadWriteLock.ReadLock lock = lock(shopId).readLock();
        lock.lock();
        try {
            if (!shopExists(shopId)) {
                throw new ShopUnavailableException(shopId);
            }
            return operation.run();
        } finally {
            lock.unlock();
        }
    }

    public static <T, E extends Exception> T withExclusiveShop(int shopId, Operation<T, E> operation) throws E {
        ReentrantReadWriteLock.WriteLock lock = lock(shopId).writeLock();
        lock.lock();
        try {
            return operation.run();
        } finally {
            lock.unlock();
        }
    }

    public static boolean isShopUnavailable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ShopUnavailableException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static ReentrantReadWriteLock lock(int shopId) {
        if (shopId <= 0) throw new IllegalArgumentException("Invalid shop id.");
        return LOCKS.computeIfAbsent(shopId, ignored -> new ReentrantReadWriteLock(true));
    }

    private static boolean shopExists(int shopId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM shops WHERE id=?")) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    @FunctionalInterface
    public interface Operation<T, E extends Exception> {
        T run() throws E;
    }

    public static final class ShopUnavailableException extends IllegalStateException {
        ShopUnavailableException(int shopId) {
            super("Shop " + shopId + " is no longer available.");
        }
    }
}
