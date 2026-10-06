package com.tuandev.fbsbarcode.integration.wb.finance;

import com.tuandev.fbsbarcode.features.finance.AdvertisingRawRow;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WbAdvertisingApiClientTest {
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
    void loadsActualCostsWithIndependentDateWindow() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                [{"updNum":11,"updTime":"2026-08-20T10:15:00+03:00","updSum":24.5,
                  "advertId":3355881,"campName":"Campaign","advertType":6,"paymentType":"Баланс"}]
                """));
        WbAdvertisingApiClient client = new WbAdvertisingApiClient(new OkHttpClient(), server.url("/adv/v1/upd").toString());
        List<AdvertisingRawRow> rows = client.loadCosts("promotion-token",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 25));
        assertEquals(1, rows.size());
        assertEquals("2026-08-20", rows.get(0).businessDate());
        assertEquals(24.5, rows.get(0).amount(), 0.001);
        RecordedRequest request = server.takeRequest();
        assertEquals("2026-08-01", request.getRequestUrl().queryParameter("from"));
        assertEquals("2026-08-25", request.getRequestUrl().queryParameter("to"));
        assertEquals("promotion-token", request.getHeader("Authorization"));
    }

    @Test
    void rejectsIntervalsLongerThanOfficial31DayMaximumBeforeCallingApi() {
        WbAdvertisingApiClient client = new WbAdvertisingApiClient(new OkHttpClient(), server.url("/adv/v1/upd").toString());
        assertThrows(IllegalArgumentException.class, () -> client.loadCosts("token",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)));
        assertEquals(0, server.getRequestCount());
    }
}
