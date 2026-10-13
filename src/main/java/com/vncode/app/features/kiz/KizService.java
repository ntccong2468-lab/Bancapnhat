package com.vncode.app.features.kiz;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.models.Kiz;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.shared.MarketplaceRequestBudget;
import com.vncode.app.config.Database;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class KizService {
    private static final Logger LOGGER = LoggerFactory.getLogger(KizService.class);
    private static final char GS = 0x1D;
    private static final int METADATA_BATCH_SIZE = 100;
    private static final long ATTACH_REQUEST_SPACING_MS = 150L;
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private static final Gson gson = new Gson();

    public static String extractGtin(String code) {
        String safeCode = scannerSafeCode(code);
        if (safeCode == null || safeCode.length() < 16 || !safeCode.startsWith("01")) {
            return "";
        }
        return safeCode.substring(2, 16);
    }

    public static void deleteKizs(int shopId, List<Kiz> kizList) {
        new ZnackGtinInventoryService().consume(shopId, kizList);
    }

    public static void releaseKizs(int shopId, List<Kiz> kizList) {
        new ZnackGtinInventoryService().release(shopId, kizList);
    }

    public static AttachCodeResult addDataMatrixCodeToOrder(String apiKey, Long orderId, String code) throws IOException {
        LicenseRuntime.require(Permission.KIZ);
        return addDataMatrixCodeToOrder(resolveShopId(apiKey),apiKey,orderId,code);
    }
    public static AttachCodeResult addDataMatrixCodeToOrder(int shopId,String apiKey,Long orderId,String code)throws IOException {
        return addDataMatrixCodeToOrder(shopId,apiKey,orderId,code,()->false);
    }
    public static AttachCodeResult addDataMatrixCodeToOrder(int shopId,String apiKey,Long orderId,String code,BooleanSupplier cancelled)throws IOException {
        LicenseRuntime.require(Permission.KIZ);
        requireCurrentShop(shopId,apiKey);
        if(orderId==null||orderId<=0||code==null||scannerSafeCode(code).isBlank())throw new IOException("KIZ_REQUEST_INVALID");
        // A timeout/5xx may have applied the write. The coordinator checkpoints and reconciles;
        // neither this layer nor OkHttp automatically replays destructive requests.
        return sendAttachCodeRequest(shopId,apiKey, orderId, code,cancelled);
    }

    public static RemoveMetaResult removeSgtinFromOrder(String apiKey, Long orderId) throws IOException {
        LicenseRuntime.require(Permission.KIZ);
        return removeSgtinFromOrder(resolveShopId(apiKey),apiKey,orderId);
    }
    public static RemoveMetaResult removeSgtinFromOrder(int shopId,String apiKey,Long orderId)throws IOException {
        return removeSgtinFromOrder(shopId,apiKey,orderId,()->false);
    }
    public static RemoveMetaResult removeSgtinFromOrder(int shopId,String apiKey,Long orderId,BooleanSupplier cancelled)throws IOException {
        LicenseRuntime.require(Permission.KIZ);
        requireCurrentShop(shopId,apiKey);
        if(orderId==null||orderId<=0)throw new IOException("KIZ_REQUEST_INVALID");
        return sendRemoveSgtinRequest(shopId,apiKey,orderId,cancelled);
    }

    public static String scannerSafeCode(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        int index = 0;
        while (index < code.length() && code.charAt(index) == GS) {
            index++;
        }
        return index == 0 ? code : code.substring(index);
    }

    public static Map<Long, SgtinMetadata> getSgtinMetadata(String apiKey, List<Long> orderIds) throws IOException {
        return getSgtinMetadata(orderIds,batch->requestSgtinMetadataBatch(apiKey,batch));
    }

    static Map<Long,SgtinMetadata> getSgtinMetadata(List<Long> orderIds,MetadataReader reader)throws IOException {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids=orderIds.stream().filter(id->id!=null&&id>0).distinct().toList();
        Map<Long, SgtinMetadata> metadataByOrderId = new HashMap<>();
        for (int start = 0; start < ids.size(); start += METADATA_BATCH_SIZE) {
            List<Long> batch = ids.subList(start, Math.min(start + METADATA_BATCH_SIZE, ids.size()));
            Map<Long,SgtinMetadata> response=reader.read(List.copyOf(batch));
            for(Long id:batch)if(response.containsKey(id))metadataByOrderId.put(id,response.get(id));
        }
        return metadataByOrderId;
    }

    private static Map<Long, SgtinMetadata> requestSgtinMetadataBatch(String apiKey, List<Long> orderIds) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/marketplace/v3/orders/meta";
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("orders", orderIds);

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), gson.toJson(bodyMap));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        WbLabelIdentifierRateLimiter.awaitTurn(apiKey);
        try (var slot=MarketplaceRequestBudget.acquire(); Response response = client.newCall(request).execute()) {
            String responseBody = boundedBody(response);
            WbLabelIdentifierRateLimiter.registerResponse(apiKey, response.code(), response.headers());
            if (!response.isSuccessful()) {
                LOGGER.warn("WB metadata request failed for {} orders with status {}", orderIds.size(), response.code());
                throw new IOException("WB_METADATA_HTTP_" + response.code());
            }
            try{return parseSgtinMetadata(responseBody);}catch(RuntimeException malformed){throw new IOException("WB_METADATA_INVALID");}
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }

    private static Map<Long, SgtinMetadata> parseSgtinMetadata(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        Map<Long, SgtinMetadata> metadataByOrderId = new HashMap<>();
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray orders = root.has("orders") && root.get("orders").isJsonArray()
                ? root.getAsJsonArray("orders")
                : new JsonArray();
        for (JsonElement orderElement : orders) {
            if (!orderElement.isJsonObject()) {
                continue;
            }
            JsonObject orderObject = orderElement.getAsJsonObject();
            if (!orderObject.has("id") || orderObject.get("id").isJsonNull()) {
                continue;
            }
            long orderId = orderObject.get("id").getAsLong();
            boolean available = hasSgtinMetaObject(orderObject) || hasSgtinMetaDetail(orderObject);
            String appliedValue = firstSgtinValue(orderObject);
            metadataByOrderId.put(orderId, new SgtinMetadata(available, appliedValue));
        }
        return metadataByOrderId;
    }

    private static boolean hasSgtinMetaObject(JsonObject orderObject) {
        if (!orderObject.has("meta") || !orderObject.get("meta").isJsonObject()) {
            return false;
        }
        JsonObject meta = orderObject.getAsJsonObject("meta");
        return meta.has("sgtin") && meta.get("sgtin").isJsonObject();
    }

    private static boolean hasSgtinMetaDetail(JsonObject orderObject) {
        if (!orderObject.has("metaDetails") || !orderObject.get("metaDetails").isJsonArray()) {
            return false;
        }
        for (JsonElement detailElement : orderObject.getAsJsonArray("metaDetails")) {
            if (!detailElement.isJsonObject()) {
                continue;
            }
            JsonObject detail = detailElement.getAsJsonObject();
            if (detail.has("key") && "sgtin".equalsIgnoreCase(detail.get("key").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String firstSgtinValue(JsonObject orderObject) {
        if (!orderObject.has("meta") || !orderObject.get("meta").isJsonObject()) {
            return "";
        }
        JsonObject meta = orderObject.getAsJsonObject("meta");
        if (!meta.has("sgtin") || !meta.get("sgtin").isJsonObject()) {
            return "";
        }
        JsonObject sgtin = meta.getAsJsonObject("sgtin");
        if (!sgtin.has("value") || sgtin.get("value").isJsonNull()) {
            return "";
        }
        JsonElement value = sgtin.get("value");
        if (value.isJsonArray()) {
            JsonArray values = value.getAsJsonArray();
            if (values.isEmpty() || values.get(0).isJsonNull()) {
                return "";
            }
            return values.get(0).getAsString();
        }
        return value.getAsString();
    }

    private static AttachCodeResult sendAttachCodeRequest(int shopId,String apiKey, Long orderId, String code,BooleanSupplier cancelled) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/" + orderId + "/meta/sgtin";

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("sgtins", List.of(scannerSafeCode(code)));

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), gson.toJson(bodyMap));

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .put(body)
                .build();

        sleepBeforeWrite();
        WbLabelIdentifierRateLimiter.awaitTurn(apiKey);
        try (var slot=MarketplaceRequestBudget.acquire()) {
        return withWriteScope(shopId,apiKey,cancelled,()->{
        try(Response response = client.newCall(request).execute()) {
            String responseBody = boundedBody(response);
            WbLabelIdentifierRateLimiter.registerResponse(apiKey, response.code(), response.headers());
            if (!response.isSuccessful()) {
                if (response.code() == 409 && responseBody.contains("FailedToUpdateMeta")) {
                    LOGGER.info("WB skipped KIZ attach for order {} because it is not in Processing status", orderId);
                } else {
                    LOGGER.warn("WB attach KIZ failed for order {} with status {}", orderId, response.code());
                }
            }
            return new AttachCodeResult(response.isSuccessful(), response.code(), responseBody);
        }
        });
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }

    private static RemoveMetaResult sendRemoveSgtinRequest(int shopId,String apiKey, Long orderId,BooleanSupplier cancelled) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse("https://marketplace-api.wildberries.ru/api/v3/orders/" + orderId + "/meta"))
                .newBuilder()
                .addQueryParameter("key", "sgtin")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .delete()
                .build();

        sleepBeforeWrite();
        WbLabelIdentifierRateLimiter.awaitTurn(apiKey);
        try (var slot=MarketplaceRequestBudget.acquire()) {
        return withWriteScope(shopId,apiKey,cancelled,()->{
        try(Response response = client.newCall(request).execute()) {
            String responseBody = boundedBody(response);
            WbLabelIdentifierRateLimiter.registerResponse(apiKey, response.code(), response.headers());
            if (!response.isSuccessful()) {
                LOGGER.warn("WB remove KIZ failed for order {} with status {}", orderId, response.code());
            }
            return new RemoveMetaResult(response.isSuccessful(), response.code(), responseBody);
        }
        });
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }

    private static void sleepBeforeWrite()throws IOException {
        try {
            Thread.sleep(ATTACH_REQUEST_SPACING_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("KIZ_REQUEST_CANCELLED");
        }
        LicenseRuntime.require(Permission.KIZ);
    }
    private static String boundedBody(Response response)throws IOException {
        if(response.body()==null)return "";
        if(response.body().contentLength()>1024*1024)throw new IOException("WB_METADATA_BODY_TOO_LARGE");
        byte[] bytes=response.body().byteStream().readNBytes(1024*1024+1);
        if(bytes.length>1024*1024)throw new IOException("WB_METADATA_BODY_TOO_LARGE");
        return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
    }
    interface MetadataReader {Map<Long,SgtinMetadata> read(List<Long> ids)throws IOException;}
    /** Exact persisted shop/token ownership, with no secret values in failure messages. */
    public static void requireCurrentShop(int shopId,String apiKey)throws IOException {
        if(shopId<=0||apiKey==null||apiKey.isBlank())throw new IOException("SHOP_CREDENTIALS_CHANGED");
        try(var c=Database.getConnection();var ps=c.prepareStatement("SELECT 1 FROM shops WHERE id=? AND marketplace='WILDBERRIES' AND api_key=?")){
            ps.setInt(1,shopId);ps.setString(2,apiKey);
            try(var rs=ps.executeQuery()){if(!rs.next())throw new IOException("SHOP_CREDENTIALS_CHANGED");}
        }catch(java.sql.SQLException|RuntimeException unavailable){throw new IOException("SHOP_CREDENTIALS_UNAVAILABLE");}
    }
    private static int resolveShopId(String apiKey)throws IOException {
        try(var c=Database.getConnection();var ps=c.prepareStatement("SELECT id FROM shops WHERE marketplace='WILDBERRIES' AND api_key=? LIMIT 2")){
            ps.setString(1,apiKey);try(var rs=ps.executeQuery()){if(!rs.next())throw new IOException("SHOP_CREDENTIALS_CHANGED");int id=rs.getInt(1);if(rs.next())throw new IOException("SHOP_CREDENTIALS_AMBIGUOUS");return id;}
        }catch(java.sql.SQLException|RuntimeException unavailable){throw new IOException("SHOP_CREDENTIALS_UNAVAILABLE");}
    }
    static <T> T withWriteScope(int shopId,String apiKey,WriteAttempt<T> attempt)throws IOException {
        return withWriteScope(shopId,apiKey,()->false,attempt);
    }
    static <T> T withWriteScope(int shopId,String apiKey,BooleanSupplier cancelled,WriteAttempt<T> attempt)throws IOException {
        return ShopOperationCoordinator.withActiveShop(shopId,()->{requireCurrentShop(shopId,apiKey);LicenseRuntime.require(Permission.KIZ);if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("KIZ_REQUEST_CANCELLED");return attempt.execute();});
    }
    interface WriteAttempt<T>{T execute()throws IOException;}

    public record AttachCodeResult(boolean success, int statusCode, String responseBody) {
        @Override public String toString(){return "AttachCodeResult[success="+success+", statusCode="+statusCode+"]";}
    }

    public record RemoveMetaResult(boolean success, int statusCode, String responseBody) {
        @Override public String toString(){return "RemoveMetaResult[success="+success+", statusCode="+statusCode+"]";}
    }

    public record SgtinMetadata(boolean available, String appliedValue) {
        @Override public String toString(){return "SgtinMetadata[available="+available+"]";}
        public boolean hasAppliedValue() {
            return appliedValue != null && !appliedValue.isBlank();
        }
    }
}
