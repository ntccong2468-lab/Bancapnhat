package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorDetails;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSignatureProvider;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import com.tuandev.fbsbarcode.models.Shop;

import java.time.Duration;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** Durable, one-SKU-at-a-time test workflow. A checkpoint is written before every remote transition. */
public final class ZnackCardRegistrationWorkflow {
    private static final int POLL_ATTEMPTS = 40;
    private static final long POLL_DELAY_MS = 15_000L;
    private static final Object GTIN_CHECKPOINT_LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "znack-card-registration");
        thread.setDaemon(true);
        return thread;
    });

    private final ZnackCardRegistrationRepository registrations;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public ZnackCardRegistrationWorkflow(ZnackCardRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    public boolean start(Shop shop, Sku sku, Draft draft, BiConsumer<Status, String> listener) {
        String key = shop.getId() + ":" + sku.chrtId();
        if (!running.add(key)) return false;
        EXECUTOR.execute(() -> {
            try {
                execute(shop, sku, draft, listener);
            } catch (Exception error) {
                fail(shop, sku, error, listener);
            } finally {
                running.remove(key);
            }
        });
        return true;
    }

    public boolean resume(Shop shop, Sku sku, BiConsumer<Status, String> listener) {
        if (sku.gtin() == null || sku.gtin().isBlank()) return false;
        String stored = registrations.payload(shop.getId(), sku.chrtId());
        if (stored.isBlank()) return false;
        JsonObject payload = JsonParser.parseString(stored).getAsJsonObject();
        return start(shop, sku, draftFromPayload(payload), listener);
    }

    static Draft draftFromPayload(JsonObject payload) {
        var categoryValue = payload.getAsJsonArray("categories").get(0);
        long categoryId = categoryValue.isJsonObject()
                ? categoryValue.getAsJsonObject().get("cat_id").getAsLong()
                : categoryValue.getAsLong();
        Map<Long, String> attributes = new LinkedHashMap<>();
        Map<Long, String> types = new LinkedHashMap<>();
        if (payload.has("good_attrs")) payload.getAsJsonArray("good_attrs").forEach(element -> {
            JsonObject attribute = element.getAsJsonObject();
            attributes.put(attribute.get("attr_id").getAsLong(), attribute.get("attr_value").getAsString());
            if (attribute.has("attr_value_type") && !attribute.get("attr_value_type").isJsonNull()) {
                types.put(attribute.get("attr_id").getAsLong(), attribute.get("attr_value_type").getAsString());
            }
        });
        String feedTnved = payload.get("tnved").getAsString();
        return new Draft(attributes.getOrDefault(13933L, feedTnved), feedTnved, categoryId,
                payload.get("good_name").getAsString(), payload.get("brand").getAsString(), attributes, types);
    }

    private void execute(Shop shop, Sku sku, Draft draft, BiConsumer<Status, String> listener) throws Exception {
        ZnackModels.ShopContext context = new ZnackModels.ShopContext(shop.getId(), shop.getName());
        ZnackModels.Settings settings = new ZnackRepository(context).getSettings();
        ZnackSignatureProvider signer = new CryptoProSignatureProvider(settings.cryptcpPath(),
                settings.signerCertificate(), Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
        ZnackApiClient api = new ZnackApiClient();
        ZnackAuthService auth = new ZnackAuthService(api, signer);
        ZnackNationalCatalogService catalog = new ZnackNationalCatalogService(api, auth, signer, settings);
        String token = auth.trueApiToken(settings);

        String gtin = sku.gtin();
        String feedId = sku.feedId();
        String stored = registrations.payload(shop.getId(), sku.chrtId());
        boolean rebuildPayload = gtin == null || gtin.isBlank() || sku.status() == Status.ERROR
                || sku.status() == Status.GTIN_GENERATED || feedId == null || feedId.isBlank() || stored.isBlank();
        if (rebuildPayload) {
            draft = withSchemaTypes(draft, catalog.requiredAttributes(draft.categoryId(), token), sku.wbSize());
        }
        String imageUrl = retryImageUrl(sku.imageUrl(), sku.errorMessage());
        JsonObject payload;
        if (gtin == null || gtin.isBlank()) {
            update(shop, sku, Status.CHECKING, null, null, listener);
            ZnackNationalCatalogService.Preflight preflight = catalog.preflight(draft.tnved());
            // Keep selection and the durable local checkpoint atomic across the two workflow
            // workers. Otherwise both can observe the same reusable catalog draft GTIN.
            synchronized (GTIN_CHECKPOINT_LOCK) {
                gtin = catalog.generateOne(preflight.token(), registrations.claimedGtins(shop.getId()));
                payload = ZnackNationalCatalogService.buildPayload(gtin, draft, imageUrl);
                registrations.saveGenerated(shop.getId(), sku, gtin, draft.tnved(), draft.categoryId(),
                        draft.goodName(), payload.toString());
            }
            notify(listener, Status.GTIN_GENERATED, gtin);
        } else {
            if (rebuildPayload) {
                payload = ZnackNationalCatalogService.buildPayload(gtin, draft, imageUrl);
                registrations.saveGenerated(shop.getId(), sku, gtin, draft.tnved(), draft.categoryId(),
                        draft.goodName(), payload.toString());
                notify(listener, Status.GTIN_GENERATED, gtin);
            } else {
                payload = JsonParser.parseString(stored).getAsJsonObject();
            }
        }

        if (feedId == null || feedId.isBlank() || sku.status() == Status.ERROR || sku.status() == Status.GTIN_GENERATED) {
            feedId = catalog.submit(token, payload);
            registrations.updateProgress(shop.getId(), sku.chrtId(), Status.FEED_SUBMITTED, feedId,
                    null, null, null);
            notify(listener, Status.FEED_SUBMITTED, feedId);
        }

        Long goodId = sku.goodId();
        boolean published = sku.status() == Status.PUBLISHED;
        boolean retriedWithoutImage = false;
        for (int attempt = 0; !published && attempt < POLL_ATTEMPTS; attempt++) {
            ZnackNationalCatalogService.FeedProgress progress = catalog.progress(token, feedId, gtin);
            if (progress.goodId() != null) goodId = progress.goodId();
            if (progress.failed()) {
                if (!retriedWithoutImage && payload.has("good_images") && onlyImageErrors(progress.errors())) {
                    retriedWithoutImage = true;
                    payload.remove("good_images");
                    registrations.saveGenerated(shop.getId(), sku, gtin, draft.tnved(), draft.categoryId(),
                            draft.goodName(), payload.toString());
                    feedId = catalog.submit(token, payload);
                    registrations.updateProgress(shop.getId(), sku.chrtId(), Status.FEED_SUBMITTED, feedId,
                            null, null, null);
                    notify(listener, Status.FEED_SUBMITTED, feedId);
                    attempt = -1;
                    continue;
                }
                throw new IllegalStateException(progress.errorMessage().isBlank()
                        ? "National Catalog rejected feed " + feedId : progress.errorMessage());
            }
            if (progress.signed()) {
                published = true;
                update(shop, sku, Status.PUBLISHED, feedId, goodId, listener);
                break;
            }
            if (progress.readyToSign()) {
                update(shop, sku, Status.READY_TO_SIGN, feedId, goodId, listener);
                update(shop, sku, Status.SIGNING, feedId, goodId, listener);
                goodId = catalog.sign(token, gtin);
                update(shop, sku, Status.PUBLISHED, feedId, goodId, listener);
                published = true;
                break;
            }
            update(shop, sku, Status.PROCESSING, feedId, goodId, listener);
            Thread.sleep(POLL_DELAY_MS);
        }

        if (!published) {
            notify(listener, Status.PROCESSING, "The card is still being moderated. Use Refresh later to continue.");
            return;
        }

        // Test scope ends here. GTIN is deliberately NOT written back to Wildberries.
        registrations.updateProgress(shop.getId(), sku.chrtId(), Status.PUBLISHED,
                feedId, goodId, null, false);
        notify(listener, Status.PUBLISHED, gtin);
    }

    private void update(Shop shop, Sku sku, Status status, String feedId, Long goodId,
                        BiConsumer<Status, String> listener) {
        registrations.updateProgress(shop.getId(), sku.chrtId(), status, feedId, goodId, null, null);
        notify(listener, status, "");
    }

    static Draft withSchemaTypes(Draft draft,
            java.util.List<ZnackCardRegistrationModels.Attribute> schema, String wbSize) {
        Map<Long, String> types = new LinkedHashMap<>(draft.attributeTypes());
        for (var attribute : schema) {
            String value = draft.attributes().get(attribute.id());
            if (value == null || value.isBlank()) continue;
            String type = types.get(attribute.id());
            if (type == null || (!attribute.valueTypes().isEmpty() && !attribute.valueTypes().contains(type))) {
                type = ZnackWbAttributeMapper.resolveValueType(attribute, value, wbSize);
            }
            if (type == null) {
                throw new IllegalArgumentException("Không xác định được hệ/loại giá trị cho "
                        + attribute.name() + " [" + attribute.id() + "]: " + value
                        + ". Znack cho phép: " + attribute.valueTypes());
            }
            types.put(attribute.id(), type);
        }
        return new Draft(draft.tnved(), draft.feedTnved(), draft.categoryId(), draft.goodName(),
                draft.brand(), draft.attributes(), types);
    }

    // Photos are optional in /v3/feed. A WB CDN URL rejected by the catalog must not
    // be reintroduced when retrying a feed that also had attribute errors.
    static String retryImageUrl(String imageUrl, String previousError) {
        String error = previousError == null ? "" : previousError.toLowerCase(java.util.Locale.ROOT);
        boolean unavailableImage = error.contains("изображение не доступно по url")
                || error.contains("изображение недоступно по url");
        return unavailableImage ? "" : imageUrl;
    }

    private void fail(Shop shop, Sku sku, Exception error, BiConsumer<Status, String> listener) {
        String message = ZnackErrorDetails.summary(error);
        registrations.updateProgress(shop.getId(), sku.chrtId(), Status.ERROR, null, null, message, null);
        notify(listener, Status.ERROR, ZnackErrorDetails.format(error));
    }

    private static void notify(BiConsumer<Status, String> listener, Status status, String detail) {
        if (listener != null) listener.accept(status, detail == null ? "" : detail);
    }

    private static boolean onlyImageErrors(java.util.List<String> errors) {
        return errors != null && !errors.isEmpty() && errors.stream().allMatch(error -> {
            String normalized = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("изображен") || normalized.contains("photo")
                    || normalized.contains("image") || normalized.contains("url");
        });
    }

}
