package com.chitfund.e2e;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OptionsReportConfigTest {

    @Test
    void defaultsAreAllScenariosDayByDayWithReset() {
        RunOptions o = RunOptions.from(Json.obj());
        assertEquals(RunOptions.ALL, o.scenarios());
        assertEquals(TimeTravel.Mode.DAY_BY_DAY, o.dateMode());
        assertEquals(10, o.tickets());
        assertEquals(6, o.customers());
        assertTrue(o.reset());
        assertTrue(o.runId().matches("[a-z0-9]{1,12}"));
    }

    @Test
    void badRequestsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RunOptions.from(Json.read("{\"scenarios\":[\"Z\"]}")));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.from(Json.read("{\"tickets\":3}")));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.from(Json.read("{\"tickets\":12,\"customers\":3}")));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.from(Json.read("{\"tickets\":7,\"chitValue\":\"100000\"}")));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.from(Json.read("{\"runId\":\"Bad Id\"}")));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.from(Json.read("{\"dateMode\":\"SIDEWAYS\"}")));
    }

    @Test
    void leapAndNoResetAreHonoured() {
        RunOptions o = RunOptions.from(Json.read("{\"scenarios\":[\"a\",\"d\"],\"dateMode\":\"leap\",\"reset\":false,\"runId\":\"abc1\"}"));
        assertEquals(java.util.List.of("A", "D"), o.scenarios());
        assertEquals(TimeTravel.Mode.LEAP, o.dateMode());
        assertFalse(o.reset());
        assertEquals("abc1", o.runId());
    }

    @Test
    void reportCountsAndEscapes() {
        Report r = new Report("x1");
        r.scenario("A");
        r.add(Report.Status.PASS, "ok", "fine");
        r.add(Report.Status.FAIL, "bad <b>", "oops & more");
        r.timeline("2026-01-01 -> 2026-01-02");
        r.finish(false);
        assertEquals("FAILED", r.state());
        assertEquals(1, r.count(Report.Status.FAIL));
        String html = r.toHtml();
        assertTrue(html.contains("bad &lt;b&gt;") && html.contains("oops &amp; more"));
        assertEquals(2, r.toJson().path("steps").size());
        Report ok = new Report("x2");
        ok.add(Report.Status.WARN, "w", "");
        ok.finish(false);
        assertEquals("PASSED", ok.state());
        Report ab = new Report("x3");
        ab.finish(true);
        assertEquals("ABORTED", ab.state());
    }

    @Test
    void blankSettingsTakeTheDefaults() {
        Config c = new Config(Map.of("PORTAL_SERVICE_URL", "http://b", "PORTAL_ORIGIN", " ", "PORT", "", "E2E_UI_USERNAME", ""));
        assertEquals("http://b", c.portalOrigin);
        assertEquals(8090, c.port);
        assertEquals("e2e", c.uiUser);
    }

    @Test
    void configRefusesAnUnsafeOrIncompleteSetup() {
        Config c = new Config(Map.of());
        String all = String.join(" ", c.problems());
        assertTrue(all.contains("E2E_TEST_ENV") && all.contains("CHIT_SERVICE_URL") && all.contains("E2E_UI_PASSWORD"));
        Config ok = new Config(Map.of("CHIT_SERVICE_URL", "http://a/", "CHIT_SERVICE_PASSWORD", "sekrit1", "PORTAL_SERVICE_URL", "http://b",
                "PORTAL_ADMIN_PASSWORD", "sekrit2", "E2E_UI_PASSWORD", "sekrit3", "E2E_TEST_ENV", "TRUE"));
        assertTrue(ok.problems().isEmpty());
        assertEquals("http://a", ok.chitUrl);
        assertEquals("http://b", ok.portalOrigin);
        assertFalse(ok.toString().contains("sekrit"));
    }
}
