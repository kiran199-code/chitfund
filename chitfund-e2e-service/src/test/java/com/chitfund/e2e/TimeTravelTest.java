package com.chitfund.e2e;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeTravelTest {

    private TimeTravel make(FakeServer f, TimeTravel.Mode mode, Report r) {
        ChitApi chit = new ChitApi(FakeServer.config(f.url()), FakeServer.fastHttp(new ArrayList<>()));
        return new TimeTravel(chit, r, mode);
    }

    private long count(FakeServer f, String prefix) {
        return f.calls.stream().filter(c -> c.startsWith(prefix)).count();
    }

    @Test
    void dayByDayRunsEveryNightAndEndsOnTheTarget() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        try {
            Report r = new Report("t");
            TimeTravel t = make(f, TimeTravel.Mode.DAY_BY_DAY, r);
            t.jumpTo(LocalDate.of(2026, 1, 4));
            assertEquals(LocalDate.of(2026, 1, 4), f.date);
            assertEquals(LocalDate.of(2026, 1, 4), t.now());
            assertEquals(3L, count(f, "POST /api/v1/chit/business-date/advance"));
            assertEquals(3L, count(f, "POST /api/v1/chit/batch/demand-run"));
            assertEquals(3L, count(f, "POST /api/v1/chit/batch/default-run"));
            assertEquals(0L, count(f, "PUT "));
        } finally {
            f.stop();
        }
    }

    @Test
    void leapSetsTheDateOnceAndRunsTheJobsOnce() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        try {
            TimeTravel t = make(f, TimeTravel.Mode.LEAP, new Report("t"));
            t.jumpTo(LocalDate.of(2026, 3, 1));
            assertEquals(LocalDate.of(2026, 3, 1), f.date);
            assertEquals(1L, count(f, "PUT /api/v1/chit/business-date"));
            assertEquals(1L, count(f, "POST /api/v1/chit/batch/demand-run"));
            assertEquals(1L, count(f, "POST /api/v1/chit/batch/late-fee-run"));
            assertEquals(0L, count(f, "POST /api/v1/chit/business-date/advance"));
        } finally {
            f.stop();
        }
    }

    @Test
    void neverGoesBackwardsAndSameDayDoesNothing() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 10));
        try {
            TimeTravel t = make(f, TimeTravel.Mode.LEAP, new Report("t"));
            t.sync();
            int before = f.calls.size();
            t.jumpTo(LocalDate.of(2026, 1, 10));
            assertEquals(before, f.calls.size());
            assertThrows(IllegalArgumentException.class, () -> t.jumpTo(LocalDate.of(2026, 1, 9)));
        } finally {
            f.stop();
        }
    }

    @Test
    void refusesAPlatformThatFollowsTheWallClock() throws Exception {
        FakeServer f = new FakeServer(LocalDate.of(2026, 1, 1));
        f.mode = "SYSTEM";
        try {
            TimeTravel t = make(f, TimeTravel.Mode.LEAP, new Report("t"));
            IllegalStateException e = assertThrows(IllegalStateException.class, t::sync);
            assertTrue(e.getMessage().contains("MANAGED"));
        } finally {
            f.stop();
        }
    }

    @Test
    void nextEventIsTheEarliestDateAfterToday() {
        var cycles = Json.arr();
        cycles.addObject().put("dueDate", "2026-02-05").put("auctionDate", "2026-02-10");
        cycles.addObject().put("dueDate", "2026-03-05").put("auctionDate", "2026-03-10");
        assertEquals(LocalDate.of(2026, 2, 5), TimeTravel.nextEvent(cycles, LocalDate.of(2026, 1, 1)));
        assertEquals(LocalDate.of(2026, 2, 10), TimeTravel.nextEvent(cycles, LocalDate.of(2026, 2, 5)));
        assertNull(TimeTravel.nextEvent(cycles, LocalDate.of(2026, 3, 10)));
    }
}
