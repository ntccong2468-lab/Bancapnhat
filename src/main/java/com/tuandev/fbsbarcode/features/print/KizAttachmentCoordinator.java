package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class KizAttachmentCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(KizAttachmentCoordinator.class);
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual()
            .name("wcode-kiz-attachment")
            .start(command);
    private static final KizAttachmentCoordinator INSTANCE = new KizAttachmentCoordinator();

    private final Map<String, KizAttachmentProgress> activeJobs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<KizAttachmentProgress>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, String> failedAttachments = new ConcurrentHashMap<>();
    private final Executor executor;
    private final AttachmentClient client;

    private KizAttachmentCoordinator() {
        this(VIRTUAL_EXECUTOR, new AttachmentClient() {
            @Override
            public KizService.RemoveMetaResult remove(String token, long orderId) throws IOException {
                return KizService.removeSgtinFromOrder(token, orderId);
            }

            @Override
            public KizService.AttachCodeResult attach(String token, long orderId, String code) throws IOException {
                return KizService.addDataMatrixCodeToOrder(token, orderId, code);
            }
        });
    }

    KizAttachmentCoordinator(Executor executor, AttachmentClient client) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.client = Objects.requireNonNull(client, "client");
    }

    public static KizAttachmentCoordinator getInstance() {
        return INSTANCE;
    }

    public String getAttachmentError(long orderId) {
        return failedAttachments.get(orderId);
    }

    public void clearErrors() {
        failedAttachments.clear();
    }

    public void clearError(long orderId) {
        failedAttachments.remove(orderId);
    }

    public void addListener(Consumer<KizAttachmentProgress> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<KizAttachmentProgress> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public boolean hasActiveJobs() {
        return !activeJobs.isEmpty();
    }

    public boolean hasActiveJobForShop(int shopId) {
        return activeJobs.values().stream().anyMatch(progress -> progress.shopId() == shopId && progress.active());
    }

    public boolean hasActiveJobForSupply(int shopId, String supplyId) {
        return findActiveJobForSupply(shopId, supplyId).isPresent();
    }

    public Optional<KizAttachmentProgress> findActiveJobForSupply(int shopId, String supplyId) {
        if (supplyId == null || supplyId.isBlank()) {
            return Optional.empty();
        }
        return activeJobs.values().stream()
                .filter(progress -> progress.shopId() == shopId)
                .filter(progress -> Objects.equals(progress.supplyId(), supplyId))
                .filter(KizAttachmentProgress::active)
                .findFirst();
    }

    public void enqueue(Shop shop,
                        String supplyId,
                        String supplyName,
                        List<OrderExportWorkflow.KizAttachmentAssignment> assignments) {
        if (shop == null || assignments == null || assignments.isEmpty()) {
            return;
        }
        MarketplaceGuard.requireWildberries(shop);

        String safeSupplyId = supplyId == null ? "" : supplyId;
        String safeSupplyName = supplyName == null ? "" : supplyName;
        String key = buildKey(shop.getId(), safeSupplyId);
        KizAttachmentProgress initialProgress = new KizAttachmentProgress(
                shop.getId(),
                shop.getName(),
                safeSupplyId,
                safeSupplyName,
                0,
                assignments.size(),
                true,
                "Отправка KIZ в WB 0/" + assignments.size(),
                List.of(),
                List.of()
        );
        activeJobs.put(key, initialProgress);
        notifyListeners(initialProgress);

        executor.execute(() -> {
            try {
                runAttachmentJob(key, shop, safeSupplyId, safeSupplyName, assignments);
            } catch (IOException | RuntimeException error) {
                LOGGER.warn("Background KIZ attachment stopped. shopId={}, supplyId={}, errorType={}",
                        shop.getId(), safeSupplyId, error.getClass().getSimpleName());
            }
        });
    }

    private void runAttachmentJob(String key,
                                  Shop shop,
                                  String supplyId,
                                  String supplyName,
                                  List<OrderExportWorkflow.KizAttachmentAssignment> assignments) throws IOException {
        List<Kiz> successfulKizs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int completed = 0;
        int skipped = 0;

        try {
            for (OrderExportWorkflow.KizAttachmentAssignment assignment : assignments) {
                if (assignment == null || assignment.orderId() == null || assignment.orderId() <= 0) {
                    continue;
                }
                if (assignment.replaceExisting()) {
                    KizService.RemoveMetaResult removeResult = client.remove(
                            shop.getApiKey(),
                            assignment.orderId()
                    );
                    if (!removeResult.success()) {
                        if (isSkippedNonProcessingOrder(removeResult.statusCode(), removeResult.responseBody())) {
                            skipped++;
                            notifyProgress(key, shop, supplyId, supplyName, completed, assignments.size(), true,
                                    "Отправка KIZ в WB " + completed + "/" + assignments.size()
                                            + " (пропущено " + skipped + ")", failures, successfulKizs);
                            continue;
                        }

                        String message = "Не удалось удалить старый KIZ для order " + assignment.orderId()
                                + " (HTTP " + removeResult.statusCode() + ")";
                        failures.add(message);
                        failedAttachments.put(assignment.orderId(), message);
                        LOGGER.warn("Background remove KIZ failed for shop {}, supply {}, order {}: {}",
                                shop.getId(), supplyId, assignment.orderId(), message);
                        notifyProgress(key, shop, supplyId, supplyName, completed, assignments.size(), true,
                                "Отправка KIZ в WB " + completed + "/" + assignments.size(), failures, successfulKizs);
                        continue;
                    }
                }

                KizService.AttachCodeResult result = client.attach(
                        shop.getApiKey(),
                        assignment.orderId(),
                        assignment.kizCode()
                );

                if (result.success()) {
                    if (assignment.sourceKiz() != null) {
                        successfulKizs.add(assignment.sourceKiz());
                    }
                    completed++;
                    failedAttachments.remove(assignment.orderId());
                    notifyProgress(key, shop, supplyId, supplyName, completed, assignments.size(), true,
                            "Отправка KIZ в WB " + completed + "/" + assignments.size(), failures, successfulKizs);
                    continue;
                }

                if (isSkippedNonProcessingOrder(result.statusCode(), result.responseBody())) {
                    skipped++;
                    notifyProgress(key, shop, supplyId, supplyName, completed, assignments.size(), true,
                            "Отправка KIZ в WB " + completed + "/" + assignments.size()
                                    + " (пропущено " + skipped + ")", failures, successfulKizs);
                    continue;
                }
                String message;
                if (result.statusCode() == 429) {
                    message = "WB ограничил отправку KIZ для order " + assignment.orderId() + " (HTTP 429)";
                } else {
                    message = "Не удалось отправить KIZ для order " + assignment.orderId() + " (HTTP " + result.statusCode() + ")";
                }
                failures.add(message);
                failedAttachments.put(assignment.orderId(), message);
                LOGGER.warn("Background attach KIZ failed for shop {}, supply {}, order {}: {}",
                        shop.getId(), supplyId, assignment.orderId(), message);
                notifyProgress(key, shop, supplyId, supplyName, completed, assignments.size(), true,
                        "Отправка KIZ в WB " + completed + "/" + assignments.size(), failures, successfulKizs);
            }

            String finalMessage = failures.isEmpty()
                    ? "KIZ отправлены в WB: " + completed + "/" + assignments.size()
                            + (skipped > 0 ? ", пропущено " + skipped : "")
                    : "KIZ отправлены частично: " + completed + "/" + assignments.size()
                            + (skipped > 0 ? ", пропущено " + skipped : "");
            KizAttachmentProgress completedProgress = new KizAttachmentProgress(
                    shop.getId(),
                    shop.getName(),
                    supplyId,
                    supplyName,
                    completed,
                    assignments.size(),
                    false,
                    finalMessage,
                    List.copyOf(failures),
                    successfulKizs.stream().map(Kiz::getCode).filter(Objects::nonNull).toList()
            );
            activeJobs.remove(key);
            notifyListeners(completedProgress);
        } catch (IOException | RuntimeException error) {
            String message = "KIZ attachment request failed (" + error.getClass().getSimpleName() + ")";
            failures.add(message);
            notifyListeners(new KizAttachmentProgress(shop.getId(), shop.getName(), supplyId, supplyName,
                    completed, assignments.size(), false, "KIZ attachment failed", List.copyOf(failures),
                    successfulKizs.stream().map(Kiz::getCode).filter(Objects::nonNull).toList()));
            throw error;
        } finally {
            activeJobs.remove(key);
        }
    }

    private void notifyProgress(String key,
                                Shop shop,
                                String supplyId,
                                String supplyName,
                                int completed,
                                int total,
                                boolean active,
                                String message,
                                List<String> failures,
                                List<Kiz> successfulKizs) {
        KizAttachmentProgress progress = new KizAttachmentProgress(
                shop.getId(),
                shop.getName(),
                supplyId,
                supplyName,
                completed,
                total,
                active,
                message,
                List.copyOf(failures),
                successfulKizs.stream().map(Kiz::getCode).filter(Objects::nonNull).toList()
        );
        activeJobs.put(key, progress);
        notifyListeners(progress);
    }

    private boolean isSkippedNonProcessingOrder(int statusCode, String responseBody) {
        if (statusCode != 409) {
            return false;
        }
        String body = responseBody == null ? "" : responseBody;
        return body.contains("FailedToUpdateMeta") || body.contains("Processing status");
    }

    private void notifyListeners(KizAttachmentProgress progress) {
        for (Consumer<KizAttachmentProgress> listener : listeners) {
            try {
                listener.accept(progress);
            } catch (RuntimeException ex) {
                LOGGER.warn("KIZ attachment progress listener failed", ex);
            }
        }
    }

    private static String buildKey(int shopId, String supplyId) {
        return shopId + ":" + (supplyId == null ? "" : supplyId);
    }

    interface AttachmentClient {
        KizService.RemoveMetaResult remove(String token, long orderId) throws IOException;

        KizService.AttachCodeResult attach(String token, long orderId, String code) throws IOException;
    }

    public record KizAttachmentProgress(
            int shopId,
            String shopName,
            String supplyId,
            String supplyName,
            int completed,
            int total,
            boolean active,
            String message,
            List<String> failures,
            List<String> successfulKizCodes
    ) {
    }
}
