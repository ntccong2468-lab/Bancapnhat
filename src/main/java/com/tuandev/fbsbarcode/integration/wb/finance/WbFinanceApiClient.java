package com.tuandev.fbsbarcode.integration.wb.finance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tuandev.fbsbarcode.features.finance.FinanceRawRow;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WbFinanceApiClient {
    // Keep heap/transaction pressure bounded so order and KIZ workflows retain priority.
    public static final int PAGE_LIMIT = 20_000;
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String DEFAULT_URL = "https://finance-api.wildberries.ru/api/finance/v1/sales-reports/detailed";
    private final OkHttpClient client;
    private final String endpoint;

    public WbFinanceApiClient() {
        this(defaultClient(), DEFAULT_URL);
    }

    public WbFinanceApiClient(OkHttpClient client, String endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    public WbFinancePage loadPage(String apiKey, LocalDate from, LocalDate to, String cursor) {
        return loadPage(apiKey, from, to, cursor, "daily");
    }

    public WbFinancePage loadPage(String apiKey, LocalDate from, LocalDate to, String cursor, String period) {
        if (!"daily".equals(period) && !"weekly".equals(period)) {
            throw new IllegalArgumentException("Unsupported WB finance period");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFrom", from.toString());
        payload.addProperty("dateTo", to.toString());
        payload.addProperty("limit", PAGE_LIMIT);
        payload.add("rrdId", integer(cursor));
        payload.addProperty("period", period);
        JsonArray fields = new JsonArray();
        for (String field : List.of(
                "rrdId", "reportId", "currency", "nmId", "vendorCode", "sku",
                "docTypeName", "sellerOperName", "quantity", "retailAmount", "forPay",
                "ppvzSalesCommission", "acquiringFee", "deliveryService", "paidStorage",
                "paidAcceptance", "penalty", "deduction", "additionalPayment", "orderId",
                "orderUid", "srid", "saleDt", "rrDate")) {
            fields.add(field);
        }
        payload.add("fields", fields);
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", apiKey)
                .header("Accept", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 204) {
                return new WbFinancePage(List.of(), cursor, true);
            }
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw apiError(response, body, "WB Finance");
            }
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) {
                throw new WbAnalyticsApiException(response.code(), "WB Finance trả dữ liệu không hợp lệ", null);
            }
            JsonArray array = root.getAsJsonArray();
            List<FinanceRawRow> rows = new ArrayList<>(array.size());
            String nextCursor = cursor;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                FinanceRawRow row = parseRow(element.getAsJsonObject(), from);
                rows.add(row);
                nextCursor = row.rrdId();
            }
            return new WbFinancePage(rows, nextCursor, array.isEmpty());
        } catch (WbAnalyticsApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new WbAnalyticsApiException("Không thể gọi WB Finance: " + exception.getMessage(), exception);
        }
    }

    static FinanceRawRow parseRow(JsonObject object, LocalDate fallbackDate) {
        String rrdId = firstText(object, "rrdId", "rrd_id");
        if (rrdId == null || rrdId.isBlank()) {
            throw new IllegalArgumentException("WB Finance row không có rrdId");
        }
        String docType = firstText(object, "docTypeName", "doc_type_name");
        String operation = firstText(object, "sellerOperName", "supplierOperName", "supplier_oper_name");
        String returnProbe = ((docType == null ? "" : docType) + " " + (operation == null ? "" : operation))
                .toLowerCase(Locale.ROOT);
        boolean returned = returnProbe.contains("возврат") || returnProbe.contains("return");
        String orderId = firstText(object, "orderId", "orderUid", "srid", "order_id", "order_uid");
        return new FinanceRawRow(
                rrdId,
                firstText(object, "reportId", "realizationreport_id"),
                firstDate(object, fallbackDate, "rrDate", "saleDt", "saleDate", "orderDt", "orderDate",
                        "createDate", "rr_dt", "sale_dt", "order_dt"),
                defaultText(firstText(object, "currency", "currencyName", "currency_name"), "RUB"),
                docType,
                operation,
                orderId,
                firstText(object, "nmId", "nm_id"),
                firstText(object, "vendorCode", "sa_name"),
                firstText(object, "sku", "barcode"),
                number(object, "quantity"),
                returned,
                number(object, "retailAmount", "retail_amount"),
                number(object, "forPay", "ppvzForPay", "ppvz_for_pay"),
                number(object, "ppvzSalesCommission", "ppvz_sales_commission"),
                number(object, "acquiringFee", "acquiring_fee"),
                number(object, "deliveryService", "deliveryRub", "delivery_rub"),
                number(object, "paidStorage", "storage_fee"),
                number(object, "paidAcceptance", "acceptance"),
                number(object, "penalty"),
                number(object, "deduction"),
                number(object, "additionalPayment", "additional_payment"),
                0,
                0,
                "{}");
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

    static WbAnalyticsApiException apiError(Response response, String body, String apiName) {
        Duration retryAfter = null;
        String header = response.header("Retry-After");
        if (header != null) {
            try {
                retryAfter = Duration.ofSeconds(Math.max(1, Long.parseLong(header.strip())));
            } catch (NumberFormatException ignored) {
                // Scheduler uses its conservative fallback.
            }
        }
        String detail = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        if (detail.length() > 240) detail = detail.substring(0, 240);
        return new WbAnalyticsApiException(response.code(),
                apiName + " HTTP " + response.code() + (detail.isBlank() ? "" : ": " + detail), retryAfter);
    }

    private static JsonPrimitive integer(String value) {
        try {
            return new JsonPrimitive(new BigInteger(value == null || value.isBlank() ? "0" : value));
        } catch (NumberFormatException ignored) {
            return new JsonPrimitive(BigInteger.ZERO);
        }
    }

    private static String firstDate(JsonObject object, LocalDate fallback, String... names) {
        for (String name : names) {
            String text = text(object, name);
            if (text != null && text.length() >= 10) {
                try {
                    return LocalDate.parse(text.substring(0, 10)).toString();
                } catch (RuntimeException ignored) {
                    // Try another date field.
                }
            }
        }
        return fallback.toString();
    }

    private static double number(JsonObject object, String... names) {
        for (String name : names) {
            String text = text(object, name);
            if (text == null || text.isBlank()) continue;
            try {
                return Double.parseDouble(text.replace(',', '.'));
            } catch (NumberFormatException ignored) {
                // Try another alias.
            }
        }
        return 0;
    }

    private static String firstText(JsonObject object, String... names) {
        for (String name : names) {
            String value = text(object, name);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
