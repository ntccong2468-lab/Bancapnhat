package com.tuandev.fbsbarcode.integration.wb.finance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.features.finance.FinanceRawRow;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbFinanceApiClientTest {
    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void postsDailyWindowAndCarriesRrdCursorWithoutLosingBigIntegers() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""
                        [{
                          "reportId": "123456789012345678901",
                          "rrdId": 9876543210123,
                          "currency": "RUB",
                          "nmId": 123,
                          "vendorCode": "A-1",
                          "sku": "4600000000000",
                          "docTypeName": "Продажа",
                          "supplierOperName": "Продажа",
                          "quantity": 2,
                          "retailAmount": "1250.50",
                          "forPay": "900.25",
                          "ppvzSalesCommission": "120.10",
                          "deliveryService": "30",
                          "saleDate": "2026-08-24T12:00:00+03:00",
                          "orderUid": "order-1"
                        }]
                        """));
        WbFinanceApiClient client = new WbFinanceApiClient(new OkHttpClient.Builder()
                .retryOnConnectionFailure(false).build(), server.url("/api/finance/v1/sales-reports/detailed").toString());

        WbFinancePage page = client.loadPage("token", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 25), "12345678901234567890");
        assertFalse(page.endOfReport());
        assertEquals("9876543210123", page.nextCursor());
        FinanceRawRow row = page.rows().get(0);
        assertEquals("2026-08-24", row.businessDate());
        assertEquals(1250.50, row.retailAmount(), 0.001);
        assertEquals(900.25, row.forPay(), 0.001);

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("token", request.getHeader("Authorization"));
        JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("daily", body.get("period").getAsString());
        assertEquals("12345678901234567890", body.get("rrdId").getAsString());
        assertEquals(WbFinanceApiClient.PAGE_LIMIT, body.get("limit").getAsInt());
        assertTrue(body.getAsJsonArray("fields").asList().stream()
                .noneMatch(field -> "kiz".equals(field.getAsString())));
        assertTrue(body.getAsJsonArray("fields").asList().stream()
                .anyMatch(field -> "sellerOperName".equals(field.getAsString())));
    }

    @Test
    void treats204AsCheckpointCompletionAndNeverRetries429() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        WbFinanceApiClient client = new WbFinanceApiClient(new OkHttpClient.Builder()
                .retryOnConnectionFailure(false).build(), server.url("/finance").toString());
        assertTrue(client.loadPage("token", LocalDate.now(), LocalDate.now(), "42").endOfReport());
        assertEquals(1, server.getRequestCount());

        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "75").setBody("rate limit"));
        WbAnalyticsApiException error = assertThrows(WbAnalyticsApiException.class,
                () -> client.loadPage("token", LocalDate.now(), LocalDate.now(), "0"));
        assertEquals(429, error.statusCode());
        assertEquals(75, error.retryAfter().toSeconds());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void treatsAnEmptySuccessfulPageAsCompletionInsteadOfPollingTheSameCursorForever() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody("[]"));
        WbFinanceApiClient client = new WbFinanceApiClient(new OkHttpClient.Builder()
                .retryOnConnectionFailure(false).build(), server.url("/finance").toString());

        WbFinancePage page = client.loadPage(
                "token", LocalDate.now(), LocalDate.now(), "42");

        assertTrue(page.endOfReport());
        assertEquals("42", page.nextCursor());
        assertEquals(1, server.getRequestCount());
    }
}
