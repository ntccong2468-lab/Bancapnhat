package com.tuandev.fbsbarcode.integration.wb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tuandev.fbsbarcode.models.Sticker;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WbStickerServiceTest {
    private static final String TOKEN = "test-token-never-log";
    private static final String UPSTREAM_SECRET = "untrusted-upstream-body-never-log-or-store";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesStickerResponseAndUsesTheOfficialBatchRequestShape() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"orders\":[101,102]"));
            respond(exchange, 200, """
                    {"stickers":[{"orderId":101,"partA":12,"partB":34,
                    "barcode":"STICKER-101","file":"base64"}]}
                    """);
        });

        List<Sticker> stickers = service().getStickers(TOKEN, List.of(101L, 102L));

        assertEquals(1, requests.get());
        assertEquals(1, stickers.size());
        assertEquals(101L, stickers.getFirst().getOrderId());
        assertEquals("STICKER-101", stickers.getFirst().getBarcode());
    }

    @Test
    void throwsAStatusAwareExceptionWithoutRetainingTheUpstreamBody() throws Exception {
        start(exchange -> respond(exchange, 429, "{\"message\":\"" + UPSTREAM_SECRET + "\"}"));

        WbApiException error = assertThrows(
                WbApiException.class,
                () -> service().getStickers(TOKEN, List.of(101L)));

        assertEquals(429, error.getStatusCode());
        assertTrue(error.isRateLimited());
        assertEquals("", error.getResponseBody());
        assertFalse(error.getMessage().contains(UPSTREAM_SECRET));
    }

    @Test
    void skipsTheNetworkForAnEmptyOrderList() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "unexpected");
        });

        assertTrue(service().getStickers(TOKEN, List.of()).isEmpty());
        assertEquals(0, requests.get());
    }

    private WbStickerService service() {
        return new WbStickerService(new OkHttpClient(), baseUrl());
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/orders/stickers", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
