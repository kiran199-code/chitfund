package com.chitfund.e2e;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpTest {

    @Test
    void waitsAndRetriesWhileTheServiceWakes() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        f.flaky.set(2);
        try {
            List<Long> sleeps = new ArrayList<>();
            Http h = FakeServer.fastHttp(sleeps);
            Http.Resp r = h.send("GET", f.url() + "/api/v1/chit/business-date", Map.of(), null);
            assertEquals(200, r.status());
            assertEquals(2, sleeps.size());
            assertTrue(sleeps.get(1) > sleeps.get(0), "the wait grows");
        } finally {
            f.stop();
        }
    }

    @Test
    void waitsOutARateLimit() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        f.limited.set(1);
        try {
            List<Long> sleeps = new ArrayList<>();
            Http.Resp r = FakeServer.fastHttp(sleeps).send("GET", f.url() + "/api/v1/chit/business-date", Map.of(), null);
            assertEquals(200, r.status());
            assertEquals(1, sleeps.size());
        } finally {
            f.stop();
        }
    }

    @Test
    void givesUpAfterTheRetriesAndReturnsTheLastAnswer() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        f.flaky.set(100);
        try {
            Http.Resp r = FakeServer.fastHttp(new ArrayList<>()).send("GET", f.url() + "/x", Map.of(), null);
            assertEquals(503, r.status());
        } finally {
            f.stop();
        }
    }

    @Test
    void anUnreachableServerIsReportedWithoutSecrets() {
        Http h = FakeServer.fastHttp(new ArrayList<>());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> h.send("GET", "http://127.0.0.1:1/x", Map.of("Authorization", Http.basic("u", "secretpw")), null));
        assertFalse(e.getMessage().contains("secretpw"));
        assertFalse(e.getMessage().contains(Http.basic("u", "secretpw")));
    }

    @Test
    void apiExceptionCarriesTheServiceErrorCode() {
        Http.ApiException e = new Http.ApiException(409, "POST", "/x", "{\"code\":\"GROUP_FULL\",\"message\":\"full\"}");
        assertEquals("GROUP_FULL", e.code());
        assertEquals(409, e.status);
        assertNull(new Http.ApiException(500, "GET", "/x", "<html>oops").code());
    }
}
