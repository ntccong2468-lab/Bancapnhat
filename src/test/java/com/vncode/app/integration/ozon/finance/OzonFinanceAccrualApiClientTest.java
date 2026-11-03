package com.vncode.app.integration.ozon.finance;

import com.google.gson.JsonParser;
import com.vncode.app.integration.ozon.OzonCredentials;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class OzonFinanceAccrualApiClientTest {
    @Test void readsTypeDictionaryAndOneDailyPageWithoutInventingIdentityOrReason() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("""
                    {"accrual_types":[{"id":12,"name":"Штраф","description":"Описание по данным Ozon"}]}
                    """));
            server.enqueue(new MockResponse().setBody("""
                    {"accruals":[{"accrual_id":987,"date":"2026-10-01","unit_number":"101-1",
                     "accrued_category":"ITEM","item_fees":{"fees":[{"sku":555,"fees":[
                     {"type_id":12,"accrued":{"amount":"-75.50","currency":"RUB"}}]}]}}],"last_id":"next-token"}
                    """));
            var client = new OzonFinanceAccrualApiClient(new OkHttpClient(),
                    server.url("/v1/finance/accrual/types").toString(),
                    server.url("/v1/finance/accrual/by-day").toString());
            var credentials = new OzonCredentials("fixture-client", "fixture-key");
            var types = client.loadTypes(credentials);
            assertEquals("Штраф", types.get(12).name());
            assertEquals("Описание по данным Ozon", types.get(12).description());
            var firstRequest = server.takeRequest();
            assertEquals("POST", firstRequest.getMethod());
            assertEquals("/v1/finance/accrual/types", firstRequest.getPath());
            assertEquals("fixture-client", firstRequest.getHeader("Client-Id"));

            var page = client.loadDay(credentials, LocalDate.of(2026, 10, 1), "");
            assertEquals(LocalDate.of(2026, 10, 1), page.date());
            assertEquals("next-token", page.lastId());
            assertEquals(1, page.accruals().size());
            assertEquals("987", page.accruals().getFirst().get("accrual_id").getAsString());
            assertEquals("-75.50", page.accruals().getFirst().getAsJsonObject("item_fees")
                    .getAsJsonArray("fees").get(0).getAsJsonObject().getAsJsonArray("fees").get(0)
                    .getAsJsonObject().getAsJsonObject("accrued").get("amount").getAsString());
            page.accruals().getFirst().addProperty("accrual_id", "modified");
            assertEquals("987", page.accruals().getFirst().get("accrual_id").getAsString());
            var request = server.takeRequest();
            assertEquals("/v1/finance/accrual/by-day", request.getPath());
            var body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
            assertEquals("2026-10-01", body.get("date").getAsString());
            assertEquals("", body.get("last_id").getAsString());
        }
    }

    @Test void rejectsMalformedPagesAndAmbiguousTypeIdsWithoutExposingResponseContents() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var client = new OzonFinanceAccrualApiClient(new OkHttpClient(), server.url("/types").toString(), server.url("/day").toString());
            var credentials = new OzonCredentials("client", "fixture-key");
            server.enqueue(new MockResponse().setBody("{\"accrual_types\":[{\"id\":1},{\"id\":1}]}"));
            assertThrows(OzonFinanceApiException.class, () -> client.loadTypes(credentials));
            server.enqueue(new MockResponse().setBody("{\"accrual_types\":[{\"id\":1.5}]}"));
            assertThrows(OzonFinanceApiException.class, () -> client.loadTypes(credentials));
            server.enqueue(new MockResponse().setBody("{\"accruals\":[],\"last_id\":7}"));
            assertThrows(OzonFinanceApiException.class, () -> client.loadDay(credentials, LocalDate.of(2026, 10, 1), ""));
            server.enqueue(new MockResponse().setResponseCode(500).setBody("synthetic-sensitive-response"));
            var error = assertThrows(OzonFinanceApiException.class, () -> client.loadTypes(credentials));
            assertEquals(500, error.statusCode());
            assertFalse(error.getMessage().contains("synthetic-sensitive-response"));
        }
    }

    @Test void preservesCursorAndStopsOnRateLimitWithoutRetry() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "70").setBody("rate limit"));
            var client = new OzonFinanceAccrualApiClient(new OkHttpClient(), server.url("/types").toString(), server.url("/day").toString());
            var error = assertThrows(OzonFinanceApiException.class,
                    () -> client.loadDay(new OzonCredentials("client", "fixture-key"), LocalDate.of(2026, 10, 1), "cursor-x"));
            assertEquals(429, error.statusCode());
            assertEquals(70, error.retryAfter().toSeconds());
            assertEquals(1, server.getRequestCount());
            var body = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
            assertEquals("cursor-x", body.get("last_id").getAsString());
            assertThrows(IllegalArgumentException.class,
                    () -> client.loadDay(new OzonCredentials("client", "fixture-key"), LocalDate.of(2021, 12, 31), ""));
            assertEquals(1, server.getRequestCount());
        }
    }
}
