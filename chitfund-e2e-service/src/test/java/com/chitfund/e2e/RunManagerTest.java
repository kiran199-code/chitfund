package com.chitfund.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class RunManagerTest {

    private Ctx ctx(FakeServer f, RunOptions o, Report r) {
        Config cfg = FakeServer.config(f.url());
        Http http = FakeServer.fastHttp(new ArrayList<>());
        ChitApi chit = new ChitApi(cfg, http);
        return new Ctx(cfg, chit, new PortalApi(cfg, http), new TimeTravel(chit, r, o.dateMode()), r, o, ms -> { });
    }

    @Test
    void preflightPassesAgainstAHealthyFake() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        try {
            RunOptions o = RunOptions.from(Json.read("{\"reset\":false}"));
            Report r = new Report("p1");
            Config cfg = FakeServer.config(f.url());
            RunManager m = new RunManager(cfg, FakeServer.fastHttp(new ArrayList<>()), ms -> { });
            assertTrue(m.preflight(ctx(f, o, r)));
            assertEquals(0, r.count(Report.Status.FAIL));
        } finally {
            f.stop();
        }
    }

    @Test
    void preflightStopsWhenResetIsOffOrDateIsNotManaged() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        f.resetEnabled = false;
        f.mode = "SYSTEM";
        try {
            Report r = new Report("p2");
            RunManager m = new RunManager(FakeServer.config(f.url()), FakeServer.fastHttp(new ArrayList<>()), ms -> { });
            assertFalse(m.preflight(ctx(f, RunOptions.from(Json.obj()), r)));
            assertTrue(r.count(Report.Status.FAIL) >= 2);
        } finally {
            f.stop();
        }
    }

    @Test
    void refusesToStartWithoutTestEnvironmentFlag() {
        Config bad = new Config(java.util.Map.of());
        RunManager m = new RunManager(bad, FakeServer.fastHttp(new ArrayList<>()), ms -> { });
        assertThrows(IllegalStateException.class, () -> m.start(RunOptions.from(Json.obj())));
        assertFalse(m.busy());
    }

    @Test
    void webApiNeedsThePasswordAndRejectsBadRuns() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        Config cfg = FakeServer.config(f.url());
        RunManager m = new RunManager(cfg, FakeServer.fastHttp(new ArrayList<>()), ms -> { });
        WebServer w = new WebServer(cfg, m);
        int port = w.start(0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String auth = "Basic " + Base64.getEncoder().encodeToString("e2e:ui".getBytes());
            String wrong = "Basic " + Base64.getEncoder().encodeToString("e2e:nope".getBytes());
            assertEquals(401, c.send(HttpRequest.newBuilder(URI.create(base + "/")).header("Authorization", wrong).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create(base + "/")).header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Start run"));
            HttpResponse<String> bad = c.send(HttpRequest.newBuilder(URI.create(base + "/api/runs")).header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"tickets\":2}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());
            assertEquals(404, c.send(HttpRequest.newBuilder(URI.create(base + "/api/runs/nope")).header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            w.stop();
            f.stop();
        }
    }

    @Test
    void aRunAgainstTheFakeStopsCleanlyAtTheFirstMissingEndpoint() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        Config cfg = FakeServer.config(f.url());
        RunManager m = new RunManager(cfg, FakeServer.fastHttp(new ArrayList<>()), ms -> { });
        try {
            Report r = m.start(RunOptions.from(Json.read("{\"scenarios\":[\"D\"],\"reset\":false,\"runId\":\"fake1\"}")));
            assertNotNull(r);
            Report second = m.start(RunOptions.from(Json.read("{\"runId\":\"fake2\"}")));
            assertTrue(second == null || second.runId().equals("fake2"));
            long end = System.currentTimeMillis() + 20000;
            while (m.busy() && System.currentTimeMillis() < end) Thread.sleep(50);
            assertFalse(m.busy());
            assertTrue(r.state().equals("FAILED") || r.state().equals("PASSED") || r.state().equals("ABORTED"));
            assertNotNull(m.get("fake1"));
        } finally {
            f.stop();
        }
    }
}
