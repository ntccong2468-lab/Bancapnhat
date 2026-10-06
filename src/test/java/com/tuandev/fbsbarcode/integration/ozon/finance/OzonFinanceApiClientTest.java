package com.tuandev.fbsbarcode.integration.ozon.finance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.features.finance.FinanceRawRow;
import com.tuandev.fbsbarcode.integration.ozon.OzonCredentials;
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

class OzonFinanceApiClientTest {
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
    void loadsTransactionPageWithCredentialsAndClassifiesCosts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"result":{"page_count":2,"row_count":2,"operations":[
                  {"operation_id":123,"operation_type":"OperationAgentDeliveredToCustomer",
                   "operation_date":"2026-08-20T10:15:00Z","operation_type_name":"Продажа",
                   "accruals_for_sale":1000,"sale_commission":-150,"amount":820,
                   "delivery_charge":-30,"return_delivery_charge":0,"type":"orders",
                   "posting":{"posting_number":"100-1"},
                   "items":[{"name":"Shirt","sku":555,"offer_id":"ART-1"}],
                   "services":[{"name":"ServiceMarketplaceServiceItemDirectFlowLogistic","price":-20}]},
                  {"operation_id":124,"operation_type":"OperationMarketplaceServiceItemPenalty",
                   "operation_date":"2026-08-20T11:00:00Z","operation_type_name":"Штраф",
                   "amount":-75,"type":"services","items":[],"services":[]}
                ]}}
                """));
        OzonFinanceApiClient client = new OzonFinanceApiClient(
                new OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                server.url("/v3/finance/transaction/list").toString());
        OzonFinancePage page = client.loadPage(new OzonCredentials("client-1", "secret-api-key-1"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20), "1");

        assertFalse(page.endOfReport());
        assertEquals("2", page.nextCursor());
        FinanceRawRow sale = page.rows().get(0);
        assertEquals("ozon:123", sale.rrdId());
        assertEquals(1000, sale.retailAmount(), 0.001);
        assertEquals(850, sale.forPay(), 0.001);
        assertEquals(50, sale.logisticsCost(), 0.001);
        assertEquals("ART-1", sale.vendorCode());
        assertEquals(75, page.rows().get(1).penaltyCost(), 0.001);

        RecordedRequest request = server.takeRequest();
        assertEquals("client-1", request.getHeader("Client-Id"));
        assertEquals("secret-api-key-1", request.getHeader("Api-Key"));
        JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals(1, body.get("page").getAsInt());
        assertEquals(OzonFinanceApiClient.PAGE_SIZE, body.get("page_size").getAsInt());
        assertEquals("all", body.getAsJsonObject("filter").get("transaction_type").getAsString());
    }

    @Test
    void enforcesOneMonthWindowAndDoesNotRetryRateLimit() {
        OzonFinanceApiClient client = new OzonFinanceApiClient(
                new OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                server.url("/finance").toString());
        OzonCredentials credentials = new OzonCredentials("client", "secret-api-key");
        assertThrows(IllegalArgumentException.class, () -> client.loadPage(credentials,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 29), "1"));
        assertEquals(0, server.getRequestCount());

        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "70")
                .setBody("rate limit"));
        OzonFinanceApiException error = assertThrows(OzonFinanceApiException.class,
                () -> client.loadPage(credentials, LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 28), "1"));
        assertEquals(429, error.statusCode());
        assertEquals(70, error.retryAfter().toSeconds());
        assertEquals(1, server.getRequestCount());
    }
}
