package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.znack.ZnackApiClient;
import com.tuandev.fbsbarcode.integration.znack.ZnackAuthService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Category;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.Gs1Status;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** National Catalog card workflow primitives. All methods are blocking and must run off the FX thread. */
public final class ZnackNationalCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackNationalCatalogService.class);
    private static final Object GTIN_ALLOCATION_LOCK = new Object();
    public static final long DECLARATION_ATTRIBUTE_ID = 23_557L;
    public static final long CERTIFICATE_ATTRIBUTE_ID = 23_561L;

    private final ZnackApiClient api;
    private final ZnackAuthService auth;
    private final ZnackSignatureProvider signer;
    private final ZnackModels.Settings settings;

    public ZnackNationalCatalogService(ZnackApiClient api, ZnackAuthService auth,
                                       ZnackSignatureProvider signer, ZnackModels.Settings settings) {
        this.api = api;
        this.auth = auth;
        this.signer = signer;
        this.settings = settings;
    }

    public Preflight preflight(String tnved) throws Exception {
        String normalized = digits(tnved);
        if (normalized.length() < 4 || normalized.length() > 10) {
            throw new IllegalArgumentException("TN VED must contain 4 to 10 digits.");
        }
        String token = auth.trueApiToken(settings);
        Gs1Status gs1 = parseGs1(api.generatedGtins(settings.resolvedTrueApiBaseUrl(), token));
        if (gs1.quotaKnown() && !gs1.canGenerate() && gs1.existingDrafts() == 0) {
            throw new IllegalStateException("GS1/GTIN quota is unavailable or exhausted (" + gs1.usage()
                    + "/" + gs1.limit() + "). Check the active GS1 RUS membership in National Catalog.");
        }
        List<Category> categories = List.of();
        String categoryTnved = normalized;
        for (String lookupTnved : categoryLookupCodes(normalized)) {
            categories = parseCategories(api.nationalCatalogCategories(
                    settings.resolvedTrueApiBaseUrl(), token, lookupTnved));
            if (!categories.isEmpty()) {
                categoryTnved = lookupTnved;
                break;
            }
        }
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("No active National Catalog category was found for TN VED "
                    + normalized + (normalized.length() == 10 ? " or its four-digit group "
                    + normalized.substring(0, 4) : "") + ".");
        }
        return new Preflight(normalized, categoryTnved, gs1, categories, token);
    }

    public List<Attribute> requiredAttributes(long categoryId, String token) throws Exception {
        List<Attribute> attributes = parseAttributes(api.nationalCatalogAttributes(
                settings.resolvedTrueApiBaseUrl(), token, categoryId));
        if (attributes.isEmpty()) {
            throw new IllegalStateException("National Catalog returned no mandatory attribute model for category "
                    + categoryId + ". Check that the category is active and try again.");
        }
        return attributes;
    }

    public String generateOne(String token) throws Exception {
        return generateOne(token, Set.of());
    }

    /**
     * Allocates one unclaimed National Catalog draft GTIN.
     *
     * <p>The catalog may already contain unused codes from an earlier interrupted request. Reuse
     * those first. If a quantity request succeeds without returning {@code result.drafts}, read
     * the draft list back instead of issuing the state-changing request again.</p>
     */
    public String generateOne(String token, Set<String> claimedGtins) throws Exception {
        Set<String> claimed = normalizedGtins(claimedGtins);
        synchronized (GTIN_ALLOCATION_LOCK) {
            JsonElement beforeResponse = api.generatedGtins(settings.resolvedTrueApiBaseUrl(), token);
            List<String> before = draftGtins(beforeResponse);
            String reusable = firstUnclaimed(before, claimed);
            if (!reusable.isBlank()) {
                LOGGER.info("Reusing an existing unclaimed National Catalog draft GTIN.");
                return reusable;
            }

            JsonElement generatedResponse = api.generateGtins(
                    settings.resolvedTrueApiBaseUrl(), token, 1);
            String generated = firstUnclaimed(draftGtins(generatedResponse), claimed);
            if (!generated.isBlank()) return generated;

            // A successful allocation response without drafts is ambiguous. The allocation call
            // must never be repeated automatically because it can consume another GS1 number.
            JsonElement readBackResponse = api.generatedGtins(
                    settings.resolvedTrueApiBaseUrl(), token);
            List<String> readBack = draftGtins(readBackResponse);
            Set<String> previous = new LinkedHashSet<>(before);
            String reconciled = readBack.stream()
                    .filter(value -> !claimed.contains(value))
                    .filter(value -> !previous.contains(value))
                    .findFirst()
                    .orElseGet(() -> firstUnclaimed(readBack, claimed));
            if (!reconciled.isBlank()) {
                LOGGER.warn("National Catalog omitted drafts from the allocation response; "
                        + "recovered the allocated GTIN through exist=1.");
                return reconciled;
            }

            String apiError = firstApiError(generatedResponse);
            String response = ZnackSanitizer.message(
                    generatedResponse == null ? "null" : generatedResponse.toString());
            throw new IllegalStateException(apiError.isBlank()
                    ? "National Catalog accepted the GTIN allocation request but returned no draft GTIN. "
                    + "WCode did not repeat the allocation request to avoid consuming a second number. "
                    + "Response: " + response
                    : apiError + " Response: " + response);
        }
    }

    public String submit(String token, JsonObject payload) throws Exception {
        JsonObject result = resultObject(api.submitNationalCatalogFeed(
                settings.resolvedTrueApiBaseUrl(), token, payload));
        String feedId = string(result, "feed_id");
        if (feedId.isBlank()) throw new IllegalStateException("National Catalog did not return feed_id.");
        return feedId;
    }

    public FeedProgress progress(String token, String feedId, String gtin) throws Exception {
        JsonObject result = resultObject(api.nationalCatalogFeedStatus(
                settings.resolvedTrueApiBaseUrl(), token, feedId));
        String status = string(result, "status");
        List<String> errors = new ArrayList<>();
        Long goodId = null;
        for (JsonElement element : array(result.get("item"))) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String itemGtin = string(item, "gtin");
            if (!itemGtin.isBlank() && !itemGtin.equals(gtin)) continue;
            if (item.has("good_id") && !item.get("good_id").isJsonNull()) goodId = item.get("good_id").getAsLong();
            String message = first(string(item, "message"), string(item, "status_message"));
            int code = item.has("status_code") && !item.get("status_code").isJsonNull()
                    ? item.get("status_code").getAsInt() : 0;
            if (code != 0 && !message.isBlank()) errors.add(message);
        }
        JsonObject details = object(result.get("error_details"));
        for (JsonElement element : array(details.get("items"))) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            for (JsonElement error : array(item.get("errors"))) {
                if (error.isJsonObject()) {
                    String text = string(error.getAsJsonObject(), "text");
                    if (!text.isBlank()) errors.add(text);
                }
            }
        }
        return new FeedProgress(status, goodId, List.copyOf(errors));
    }

    public long sign(String token, String gtin) throws Exception {
        JsonObject request = new JsonObject();
        JsonArray gtins = new JsonArray();
        gtins.add(gtin);
        request.add("gtins", gtins);
        request.addProperty("publicationAgreement", true);
        JsonObject document = resultObject(api.nationalCatalogSigningDocument(
                settings.resolvedTrueApiBaseUrl(), token, request));
        JsonArray xmls = array(document.get("xmls"));
        if (xmls.isEmpty()) {
            String error = firstError(document);
            throw new IllegalStateException(error.isBlank() ? "The card is not ready for signing." : error);
        }
        JsonObject xmlItem = xmls.get(0).getAsJsonObject();
        long goodId = xmlItem.get("goodId").getAsLong();
        String xml = string(xmlItem, "xml");
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(xmlBytes, ZnackSignatureContext.TRUE_API_DOCUMENT).cms();
        JsonObject signed = new JsonObject();
        signed.addProperty("goodId", goodId);
        signed.addProperty("base64Xml", Base64.getEncoder().encodeToString(xmlBytes));
        signed.addProperty("signature", Base64.getEncoder().encodeToString(signature));
        JsonArray batch = new JsonArray();
        batch.add(signed);
        JsonObject response = resultObject(api.signNationalCatalogProduct(
                settings.resolvedTrueApiBaseUrl(), token, batch));
        for (JsonElement id : array(response.get("signed"))) if (id.getAsLong() == goodId) return goodId;
        String error = firstError(response);
        throw new IllegalStateException(error.isBlank() ? "National Catalog did not confirm the signature." : error);
    }

    public static JsonObject buildPayload(String gtin, Draft draft, String imageUrl) {
        JsonObject payload = new JsonObject();
        payload.addProperty("gtin", gtin);
        payload.addProperty("tnved", draft.feedTnved());
        payload.addProperty("moderation", 1);
        payload.addProperty("brand", draft.brand());
        payload.addProperty("good_name", draft.goodName());

        JsonObject identity = new JsonObject();
        identity.addProperty("value", gtin);
        identity.addProperty("type", "gtin");
        identity.addProperty("multiplier", 1);
        identity.addProperty("level", "trade-unit");
        identity.addProperty("unit", "шт");
        JsonArray identities = new JsonArray();
        identities.add(identity);
        payload.add("identified_by", identities);

        JsonArray categories = new JsonArray();
        categories.add(draft.categoryId());
        payload.add("categories", categories);

        if (imageUrl != null && !imageUrl.isBlank()) {
            JsonObject image = new JsonObject();
            image.addProperty("photo_type", "default");
            image.addProperty("photo_url", imageUrl);
            image.addProperty("identifier", gtin);
            image.addProperty("identifier_type", "gtin");
            JsonArray images = new JsonArray();
            images.add(image);
            payload.add("good_images", images);
        }

        JsonArray attributes = new JsonArray();
        draft.attributes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() == null || entry.getValue().isBlank()) return;
            JsonObject attribute = new JsonObject();
            attribute.addProperty("attr_id", entry.getKey());
            attribute.addProperty("attr_value", entry.getValue().trim());
            attribute.addProperty("attr_value_type", draft.attributeTypes().getOrDefault(entry.getKey(), ""));
            attributes.add(attribute);
        });
        payload.add("good_attrs", attributes);
        return payload;
    }

    /** Picks the closest active light-industry catalog leaf without asking on every SKU. */
    public static Category selectLightIndustryCategory(List<Category> categories, String wbSubject) {
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException("No National Catalog category is available.");
        }
        if (categories.size() == 1) return categories.get(0);
        Set<String> subjectWords = words(wbSubject);
        return categories.stream()
                .max(Comparator.comparingInt(category -> categoryScore(category.name(), subjectWords)))
                .orElse(categories.get(0));
    }

    private static int categoryScore(String categoryName, Set<String> subjectWords) {
        String normalized = normalize(categoryName);
        int score = 0;
        for (String keyword : List.of("легк", "одежд", "бель", "трикотаж", "текстил", "обув",
                "брюк", "юбк", "плать", "куртк", "носк", "чул", "головн", "перчат")) {
            if (normalized.contains(keyword)) score += 10;
        }
        for (String word : subjectWords) if (word.length() > 3 && normalized.contains(word)) score += 100;
        return score;
    }

    private static Set<String> words(String value) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : normalize(value).split("[^\\p{L}\\p{N}]+")) if (!word.isBlank()) words.add(word);
        return words;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }

    static Gs1Status parseGs1(JsonElement response) {
        JsonObject result = resultObject(response);
        JsonObject monthly = object(result.get("monthly-limit"));
        long limit = number(monthly, "limit");
        long usage = number(monthly, "usage");
        boolean quotaKnown = monthly.has("limit") && monthly.has("usage");
        return new Gs1Status(limit, usage, draftGtins(response).size(), quotaKnown);
    }

    static List<String> draftGtins(JsonElement response) {
        JsonElement unwrapped = unwrap(response);
        List<String> values = new ArrayList<>();
        if (unwrapped != null && unwrapped.isJsonObject()) {
            collectGtins(unwrapped.getAsJsonObject().get("drafts"), values);
            // Be tolerant of gateway adapters that expose the generated values as gtins.
            if (values.isEmpty()) collectGtins(unwrapped.getAsJsonObject().get("gtins"), values);
        } else if (unwrapped != null && unwrapped.isJsonArray()) {
            collectGtins(unwrapped, values);
        }
        return values.stream().distinct().toList();
    }

    private static void collectGtins(JsonElement source, List<String> target) {
        if (source == null || source.isJsonNull()) return;
        if (source.isJsonArray()) {
            for (JsonElement item : source.getAsJsonArray()) collectGtins(item, target);
            return;
        }
        String value = "";
        if (source.isJsonObject()) {
            JsonElement gtin = source.getAsJsonObject().get("gtin");
            if (gtin != null && (gtin.isJsonArray() || gtin.isJsonObject())) {
                collectGtins(gtin, target);
                return;
            }
            if (gtin != null && gtin.isJsonPrimitive()) value = gtin.getAsString();
        } else if (source.isJsonPrimitive()) value = source.getAsString();
        String normalized = normalizeGtin(value);
        if (!normalized.isBlank()) target.add(normalized);
    }

    private static Set<String> normalizedGtins(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            String normalized = normalizeGtin(value);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return result;
    }

    private static String firstUnclaimed(List<String> candidates, Set<String> claimed) {
        if (candidates == null) return "";
        return candidates.stream().filter(value -> !claimed.contains(value)).findFirst().orElse("");
    }

    private static String normalizeGtin(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("\\d{8,14}") ? normalized : "";
    }

    static List<Category> parseCategories(JsonElement response) {
        List<Category> active = new ArrayList<>();
        List<Category> all = new ArrayList<>();
        for (JsonElement element : resultArray(response)) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            long id = number(value, "cat_id");
            if (id <= 0) continue;
            Category category = new Category(id, string(value, "cat_name"));
            all.add(category);
            if (!value.has("category_active") || value.get("category_active").getAsBoolean()) active.add(category);
        }
        return active.isEmpty() ? all : active;
    }

    static List<String> categoryLookupCodes(String tnved) {
        if (tnved != null && tnved.length() == 10) {
            // National Catalog API v5.62 allows a product with a ten-digit TN VED to use the
            // registered four-digit group. During /v3/feed it stores the group in attribute
            // 3959 and the original ten-digit value in attribute 13933.
            return List.of(tnved, tnved.substring(0, 4));
        }
        return List.of(tnved);
    }

    static List<Attribute> parseAttributes(JsonElement response) {
        List<Attribute> values = new ArrayList<>();
        for (JsonElement element : resultArray(response)) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            long id = number(value, "attr_id");
            if (id <= 0) continue;
            List<String> presets = new ArrayList<>();
            for (JsonElement preset : array(value.get("attr_preset"))) {
                if (preset.isJsonPrimitive()) presets.add(preset.getAsString());
            }
            List<String> valueTypes = new ArrayList<>();
            for (JsonElement type : array(value.get("attr_value_type"))) {
                if (type.isJsonPrimitive()) valueTypes.add(type.getAsString());
            }
            values.add(new Attribute(id, string(value, "attr_name"), string(value, "attr_field_type"),
                    bool(value, "attr_preset_only"), bool(value, "attr_multiplicity"),
                    bool(value, "first_layer"), bool(value, "second_layer"), presets, valueTypes));
        }
        return List.copyOf(values);
    }

    private static JsonObject resultObject(JsonElement response) {
        JsonElement result = unwrap(response);
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray resultArray(JsonElement response) {
        JsonElement result = unwrap(response);
        return result != null && result.isJsonArray() ? result.getAsJsonArray() : new JsonArray();
    }

    private static JsonElement unwrap(JsonElement response) {
        if (response == null || response.isJsonNull()) return null;
        if (response.isJsonObject() && response.getAsJsonObject().has("result")) return response.getAsJsonObject().get("result");
        return response;
    }

    private static JsonArray array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private static long number(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) return 0L;
        try { return value.get(key).getAsLong(); } catch (RuntimeException ignored) { return 0L; }
    }

    private static boolean bool(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean();
    }

    private static String firstError(JsonObject result) {
        for (JsonElement value : array(result.get("errors"))) {
            if (value.isJsonObject()) {
                String message = first(string(value.getAsJsonObject(), "message"),
                        string(value.getAsJsonObject(), "error"),
                        string(value.getAsJsonObject(), "text"));
                if (!message.isBlank()) return message;
            }
        }
        for (JsonElement value : array(result.get("globalErrors"))) {
            if (value.isJsonObject()) {
                String message = first(string(value.getAsJsonObject(), "message"),
                        string(value.getAsJsonObject(), "error"),
                        string(value.getAsJsonObject(), "text"));
                if (!message.isBlank()) return message;
            }
        }
        return "";
    }

    private static String firstApiError(JsonElement response) {
        JsonObject root = response != null && response.isJsonObject()
                ? response.getAsJsonObject() : new JsonObject();
        JsonObject result = resultObject(response);
        String error = firstError(result);
        if (!error.isBlank()) return error;
        error = firstError(root);
        if (!error.isBlank()) return error;
        return first(string(result, "message"), string(result, "error"),
                string(root, "message"), string(root, "error"));
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    public record Preflight(String tnved, String categoryTnved, Gs1Status gs1,
                            List<Category> categories, String token) { }
    public record FeedProgress(String status, Long goodId, List<String> errors) {
        public boolean failed() { return "Rejected".equalsIgnoreCase(status) || !errors.isEmpty(); }
        public boolean readyToSign() { return "Moderated".equalsIgnoreCase(status) && errors.isEmpty(); }
        public boolean signed() { return "Signed".equalsIgnoreCase(status); }
        public String errorMessage() { return String.join("; ", errors); }
    }
}
