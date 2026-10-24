package com.chitfund.batch.core;

import com.chitfund.batch.core.eod.EndOfDayService;
import com.chitfund.batch.core.eod.EodJobGateway;
import com.chitfund.batch.core.eod.EodJobResult;
import com.chitfund.batch.core.eod.EodRunSummary;
import com.chitfund.batch.chit.ChitServiceClient;
import com.chitfund.batch.chit.ChitServiceException;
import com.chitfund.batch.chit.ChitServiceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring-free: the end-of-day run against a fake chit service and a fake job gateway. */
class EndOfDayServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 12);

    /** Behaves like the chit service's business-date endpoints. */
    public static class FakeChit extends ChitServiceClient {
        LocalDate date = D;
        String status = "OPEN";
        final List<String> calls = new ArrayList<>();
        boolean failStart;

        FakeChit() {
            super(new ChitServiceSettings("http://unused", null, null, null, null, 1, Duration.ofMillis(1)),
                    new ObjectMapper());
        }

        private BusinessDateInfo info() {
            return new BusinessDateInfo(date.toString(), null, date.plusDays(1).toString(), status, true, date.toString(), "MANAGED");
        }

        @Override
        public BusinessDateInfo businessDate() {
            calls.add("get");
            return info();
        }

        @Override
        public BusinessDateInfo startEndOfDay(String actor) {
            calls.add("start " + actor);
            if (failStart) {
                throw new ChitServiceException("409 already running", 409, null);
            }
            status = "EOD_RUNNING";
            return info();
        }

        @Override
        public BusinessDateInfo advanceBusinessDate(LocalDate expected, String actor) {
            calls.add("advance " + expected + " " + actor);
            date = date.plusDays(1);
            status = "OPEN";
            return info();
        }
    }

    public static class FakeJobs implements EodJobGateway {
        final List<String> names;
        final Set<String> alreadyDone = new HashSet<>();
        final Map<String, String> outcomes;
        final List<String> ran = new ArrayList<>();

        FakeJobs(List<String> names, Map<String, String> outcomes) {
            this.names = names;
            this.outcomes = outcomes;
        }

        @Override
        public List<String> dailyJobNames() {
            return names;
        }

        @Override
        public boolean alreadyCompleted(String jobName, LocalDate businessDate) {
            return alreadyDone.contains(jobName);
        }

        @Override
        public EodJobResult run(String jobName, LocalDate businessDate) {
            ran.add(jobName + "@" + businessDate);
            return new EodJobResult(jobName, outcomes.getOrDefault(jobName, "COMPLETED"), "");
        }
    }

    @Test
    void aCleanRunExecutesTheJobsInOrderForTheBusinessDateThenAdvancesIt() {
        FakeChit chit = new FakeChit();
        FakeJobs jobs = new FakeJobs(List.of("A", "B", "C"), Map.of());

        EodRunSummary r = new EndOfDayService(chit, jobs).run("scheduler");

        assertEquals(EodRunSummary.Outcome.ADVANCED, r.outcome());
        assertEquals("2026-10-12", r.businessDate());
        assertEquals("2026-10-13", r.newBusinessDate());
        assertEquals(List.of("A@2026-10-12", "B@2026-10-12", "C@2026-10-12"), jobs.ran);
        assertEquals(List.of("get", "start scheduler", "advance 2026-10-12 scheduler"), chit.calls);
        assertEquals(D.plusDays(1), chit.date);
    }

    @Test
    void aFailedJobStopsTheChainAndLeavesTheBusinessDateWhereItIs() {
        FakeChit chit = new FakeChit();
        FakeJobs jobs = new FakeJobs(List.of("A", "B", "C"), Map.of("B", "FAILED"));

        EodRunSummary r = new EndOfDayService(chit, jobs).run("scheduler");

        assertEquals(EodRunSummary.Outcome.FAILED, r.outcome());
        assertEquals(List.of("A@2026-10-12", "B@2026-10-12"), jobs.ran); // C never ran
        assertTrue(r.error().contains("B"));
        assertEquals(D, chit.date);
        assertEquals("EOD_RUNNING", chit.status);
        assertFalse(chit.calls.stream().anyMatch(c -> c.startsWith("advance")));
    }

    @Test
    void rerunningAfterAFailureResumesWithoutRepeatingFinishedJobsOrRestartingTheDay() {
        FakeChit chit = new FakeChit();
        chit.status = "EOD_RUNNING"; // an earlier run died on job B
        FakeJobs jobs = new FakeJobs(List.of("A", "B", "C"), Map.of());
        jobs.alreadyDone.add("A");

        EodRunSummary r = new EndOfDayService(chit, jobs).run("batch-admin");

        assertEquals(EodRunSummary.Outcome.ADVANCED, r.outcome());
        assertEquals(List.of("B@2026-10-12", "C@2026-10-12"), jobs.ran);
        assertEquals("SKIPPED", r.jobs().get(0).status());
        assertFalse(chit.calls.stream().anyMatch(c -> c.startsWith("start")));
    }

    @Test
    void ifEndOfDayCannotBeStartedNothingRuns() {
        FakeChit chit = new FakeChit();
        chit.failStart = true;
        FakeJobs jobs = new FakeJobs(List.of("A"), Map.of());

        EodRunSummary r = new EndOfDayService(chit, jobs).run("scheduler");

        assertEquals(EodRunSummary.Outcome.FAILED, r.outcome());
        assertTrue(jobs.ran.isEmpty());
        assertTrue(r.error().contains("409"));
    }

    @Test
    void withNoDailyJobsTheDateStillAdvances() {
        FakeChit chit = new FakeChit();
        EodRunSummary r = new EndOfDayService(chit, new FakeJobs(List.of(), Map.of())).run("scheduler");
        assertEquals(EodRunSummary.Outcome.ADVANCED, r.outcome());
        assertEquals(D.plusDays(1), chit.date);
    }

    @Test
    void successiveRunsWalkTheDateForwardOneDayEach() {
        FakeChit chit = new FakeChit();
        FakeJobs jobs = new FakeJobs(List.of("A"), Map.of());
        EndOfDayService eod = new EndOfDayService(chit, jobs);
        eod.run("t");
        eod.run("t");
        eod.run("t");
        assertEquals(D.plusDays(3), chit.date);
        assertEquals(List.of("A@2026-10-12", "A@2026-10-13", "A@2026-10-14"), jobs.ran);
    }
}
