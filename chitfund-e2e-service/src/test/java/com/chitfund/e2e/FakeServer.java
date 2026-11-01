package com.chitfund.e2e;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A tiny stand-in for chit-service's business-date and batch endpoints, recording every call it gets. */
final class FakeServer {
    final HttpServer server;
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    volatile LocalDate date;
    volatile String mode = "MANAGED";
    volatile boolean resetEnabled = true;
    final AtomicInteger flaky = new AtomicInteger(0);   // answers 503 this many times first
    final AtomicInteger limited = new AtomicInteger(0); // answers 429 this many times first

    FakeServer(LocalDate start) throws IOException {
        this.date = start;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            String q = ex.getRequestURI().getQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(key + (q == null ? "" : "?" + q));
            int status = 200;
            String out;
            if (flaky.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
                status = 503;
                out = "{}";
            } else if (limited.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
                status = 429;
                out = "{}";
            } else if (key.equals("GET /api/v1/chit/business-date")) {
                out = "{\"mode\":\"" + mode + "\",\"businessDate\":\"" + date + "\"}";
            } else if (key.equals("PUT /api/v1/chit/business-date")) {
                date = LocalDate.parse(Json.text(Json.read(body), "businessDate"));
                out = "{\"mode\":\"MANAGED\",\"businessDate\":\"" + date + "\"}";
            } else if (key.equals("POST /api/v1/chit/business-date/eod/start")) {
                out = "{}";
            } else if (key.equals("POST /api/v1/chit/business-date/advance")) {
                LocalDate expected = LocalDate.parse(Json.text(Json.read(body), "expectedBusinessDate"));
                if (!expected.equals(date)) {
                    status = 409;
                    out = "{\"code\":\"BUSINESS_DATE_MISMATCH\"}";
                } else {
                    date = date.plusDays(1);
                    out = "{\"mode\":\"MANAGED\",\"businessDate\":\"" + date + "\"}";
                }
            } else if (key.equals("GET /api/v1/chit/maintenance/status")) {
                out = "{\"resetEnabled\":" + resetEnabled + ",\"rowCounts\":{}}";
            } else if (key.startsWith("POST /api/v1/chit/batch/")) {
                out = "{}";
            } else if (key.equals("POST /api/v1/portal/auth/google")) {
                String tok = Json.text(Json.read(body), "idToken");
                if (tok != null && tok.startsWith("e2e|")) {
                    ex.getResponseHeaders().add("Set-Cookie", "portal_session=abc; Path=/; HttpOnly");
                    out = "{\"id\":7,\"email\":\"x@gmail.com\"}";
                } else {
                    status = 401;
                    out = "{\"code\":\"INVALID_ID_TOKEN\"}";
                }
            } else {
                status = 404;
                out = "{}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    static Config config(String url) {
        return new Config(java.util.Map.of("CHIT_SERVICE_URL", url, "CHIT_SERVICE_PASSWORD", "p", "PORTAL_SERVICE_URL", url,
                "PORTAL_ADMIN_PASSWORD", "p", "E2E_UI_PASSWORD", "ui", "E2E_TEST_ENV", "true"));
    }

    static Http fastHttp(List<Long> sleeps) {
        return new Http(java.time.Duration.ofSeconds(5), 3, 10, 10, sleeps::add);
    }
}
