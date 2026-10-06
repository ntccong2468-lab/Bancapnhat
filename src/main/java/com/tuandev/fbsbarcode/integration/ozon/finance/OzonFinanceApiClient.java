package com.tuandev.fbsbarcode.integration.ozon.finance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.features.finance.FinanceRawRow;
import com.tuandev.fbsbarcode.integration.ozon.OzonCredentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Ozon transaction reader with scheduler-owned rate limiting and no automatic retries. */
public final class OzonFinanceApiClient {
    public static final int PAGE_SIZE = 1_000;
    private static final long MAX_RESPONSE_BYTES = 20L * 1024 * 1024;
    private static final MediaType JSON = Objects.requireNonNull(MediaType.parse("application/json"));
    private static final String DEFAULT_URL = "https://api-seller.ozon.ru/v3/finance/transaction/list";

    private final OkHttpClient client;
    private final String endpoint;

    public OzonFinanceApiClient() {
        this(defaultClient(), DEFAULT_URL);
    }

    public OzonFinanceApiClient(OkHttpClient client, String endpoint) {
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    public OzonFinancePage loadPage(OzonCredentials credentials, LocalDate from, LocalDate to, String cursor) {
        if (from == null || to == null || from.isAfter(to) || from.plusDays(27).isBefore(to)) {
            throw new IllegalArgumentException("Ozon finance requires a safe calendar interval of at most 28 days");
        }
        int page = positivePage(cursor);
        JsonObject dates = new JsonObject();
        dates.addProperty("from", from.atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        dates.addProperty("to", to.plusDays(1).atStartOfDay().minusNanos(1).toInstant(ZoneOffset.UTC).toString());
        JsonObject filter = new JsonObject();
        filter.add("date", dates);
        filter.add("operation_type", new JsonArray());
        filter.addProperty("posting_number", "");
        filter.addProperty("transaction_type", "all");
        JsonObject payload = new JsonObject();
        payload.add("filter", filter);
        payload.addProperty("page", page);
        payload.addProperty("page_size", PAGE_SIZE);

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Client-Id", credentials.clientId())
                .header("Api-Key", credentials.apiKey())
                .header("Accept", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null || responseBody.contentLength() > MAX_RESPONSE_BYTES) {
                throw new OzonFinanceApiException(response.code(), "Ozon Finance trả dữ liệu không hợp lệ", null);
            }
            String body = responseBody.string();
            if (body.length() > MAX_RESPONSE_BYTES) {
                throw new OzonFinanceApiException(response.code(), "Ozon Finance trả dữ liệu quá lớn", null);
            }
            if (!response.isSuccessful()) {
                throw apiError(response, body);
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject result = object(root, "result");
            JsonArray operations = array(result, "operations");
            int pageCount = integer(result, "page_count");
            List<FinanceRawRow> rows = new ArrayList<>(operations.size());
            for (JsonElement element : operations) {
                if (element.isJsonObject()) {
                    rows.add(parseRow(element.getAsJsonObject(), from));
                }
            }
            boolean end = operations.isEmpty() || (pageCount > 0 ? page >= pageCount : operations.size() < PAGE_SIZE);
            return new OzonFinancePage(rows, Integer.toString(page + 1), end);
        } catch (OzonFinanceApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new OzonFinanceApiException("Không thể gọi Ozon Finance: " + exception.getMessage(), exception);
        }
    }

    static FinanceRawRow parseRow(JsonObject operation, LocalDate fallbackDate) {
        String operationId = text(operation, "operation_id");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("Ozon Finance row không có operation_id");
        }
        String operationType = text(operation, "operation_type");
        String operationName = text(operation, "operation_type_name");
        String transactionType = text(operation, "type");
        String probe = normalize(operationType, operationName, transactionType);
        boolean returned = containsAny(probe, "возврат", "refund", "return", "отмена", "cancel");
        boolean penaltyOperation = containsAny(probe, "штраф", "penalty", "fine");
        boolean advertisingOperation = containsAny(probe, "реклам", "advert", "marketing", "продвиж");
        boolean logisticsOperation = containsAny(probe, "достав", "delivery", "логист", "logistic");

        JsonObject posting = object(operation, "posting");
        String postingNumber = firstText(posting, "posting_number", "order_id");
        JsonArray items = array(operation, "items");
        JsonObject firstItem = items.isEmpty() || !items.get(0).isJsonObject()
                ? new JsonObject() : items.get(0).getAsJsonObject();
        double quantity = 0;
        for (JsonElement item : items) {
            if (!item.isJsonObject()) continue;
            double itemQuantity = number(item.getAsJsonObject(), "quantity");
            quantity += itemQuantity == 0 ? 1 : Math.abs(itemQuantity);
        }

        double accrual = number(operation, "accruals_for_sale");
        double amount = number(operation, "amount");
        double signedCommission = number(operation, "sale_commission");
        double commission = Math.abs(signedCommission);
        double logistics = Math.abs(number(operation, "delivery_charge"))
                + Math.abs(number(operation, "return_delivery_charge"));
        double penalty = 0;
        double advertising = 0;
        double other = 0;
        JsonArray services = array(operation, "services");
        for (JsonElement serviceElement : services) {
            if (!serviceElement.isJsonObject()) continue;
            JsonObject service = serviceElement.getAsJsonObject();
            double cost = Math.abs(number(service, "price"));
            String serviceProbe = normalize(text(service, "name"));
            if (containsAny(serviceProbe, "штраф", "penalty", "fine")) penalty += cost;
            else if (containsAny(serviceProbe, "реклам", "advert", "marketing", "продвиж")) advertising += cost;
            else if (containsAny(serviceProbe, "достав", "delivery", "логист", "logistic")) logistics += cost;
            else other += cost;
        }

        boolean merchandiseOperation = Math.abs(accrual) > 0.000001 || !items.isEmpty();
        if (!merchandiseOperation && amount < 0) {
            double operationCost = Math.abs(amount);
            if (penaltyOperation) penalty = Math.max(penalty, operationCost);
            else if (advertisingOperation) advertising = Math.max(advertising, operationCost);
            else if (logisticsOperation) logistics = Math.max(logistics, operationCost);
            else other = Math.max(other, operationCost);
        }
        double additional = !merchandiseOperation && amount > 0 ? amount : 0;
        double forPay = merchandiseOperation
                ? (Math.abs(accrual) > 0.000001 ? accrual + signedCommission : amount)
                : 0;
        if (returned && forPay > 0) forPay = -forPay;
        return new FinanceRawRow(
                "ozon:" + operationId,
                operationId,
                date(operation, fallbackDate),
                "RUB",
                transactionType,
                operationName == null ? operationType : operationName,
                postingNumber,
                text(firstItem, "sku"),
                firstText(firstItem, "offer_id", "name"),
                text(firstItem, "sku"),
                quantity,
                returned,
                Math.abs(accrual),
                forPay,
                commission,
                0,
                logistics,
                0,
                0,
                penalty,
                0,
                additional,
                other,
                advertising,
                "{}");
    }

    private static OzonFinanceApiException apiError(Response response, String body) {
        Duration retryAfter = null;
        String header = response.header("Retry-After");
        if (header != null) {
            try {
                retryAfter = Duration.ofSeconds(Math.max(1, Long.parseLong(header.strip())));
            } catch (NumberFormatException ignored) {
                // The scheduler uses a conservative fallback.
            }
        }
        String detail = body.replaceAll("\\s+", " ").strip();
        if (detail.length() > 240) detail = detail.substring(0, 240);
        return new OzonFinanceApiException(response.code(),
                "Ozon Finance HTTP " + response.code() + (detail.isBlank() ? "" : ": " + detail), retryAfter);
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(45))
                .writeTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(60))
                .retryOnConnectionFailure(false)
                .build();
    }

    private static int positivePage(String cursor) {
        try {
            return Math.max(1, Integer.parseInt(cursor == null || cursor.isBlank() || "0".equals(cursor) ? "1" : cursor));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String date(JsonObject object, LocalDate fallback) {
        String value = text(object, "operation_date");
        if (value != null && value.length() >= 10) {
            try {
                return LocalDate.parse(value.substring(0, 10)).toString();
            } catch (RuntimeException ignored) {
                // Use the bounded request date.
            }
        }
        return fallback.toString();
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent == null ? null : parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement value = parent == null ? null : parent.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static int integer(JsonObject object, String name) {
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static double number(JsonObject object, String name) {
        String value = text(object, name);
        if (value == null || value.isBlank()) return 0;
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String firstText(JsonObject object, String... names) {
        for (String name : names) {
            String value = text(object, name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String normalize(String... values) {
        return String.join(" ", java.util.Arrays.stream(values)
                .filter(Objects::nonNull).toList()).toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... probes) {
        for (String probe : probes) {
            if (value.contains(probe)) return true;
        }
        return false;
    }
}
