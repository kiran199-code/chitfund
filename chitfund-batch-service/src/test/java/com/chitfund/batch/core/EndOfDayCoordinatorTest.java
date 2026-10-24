package com.chitfund.batch.core;

import com.chitfund.batch.core.eod.EndOfDayCoordinator;
import com.chitfund.batch.core.eod.EodProgress;
import com.chitfund.batch.chit.ChitServiceClient;
import com.chitfund.batch.chit.ChitServiceException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The coordinator's two guards: no start on a stale date, and a status read that never waits for the chit service. */
class EndOfDayCoordinatorTest {

    // The job gateway is never reached in these cases, so null is fine.
    private static EndOfDayCoordinator coordinator(ChitServiceClient chit) {
        return new EndOfDayCoordinator(chit, null);
    }

    @Test
    void aStartForADateThatIsNotTheCurrentBusinessDateIsRefusedAndNothingStarts() {
        EndOfDayServiceTest.FakeChit chit = new EndOfDayServiceTest.FakeChit();
        EndOfDayCoordinator c = coordinator(chit);

        try {
            c.trigger("batch-admin", chit.date.plusDays(1));
            assertTrue(false, "expected a stale-date refusal");
        } catch (EndOfDayCoordinator.StaleBusinessDateException e) {
            assertTrue(e.getMessage().contains(chit.date.toString()));
        }

        assertFalse(c.isRunning());
        assertEquals(EodProgress.State.IDLE, c.progress().state());
        assertFalse(chit.calls.stream().anyMatch(x -> x.startsWith("start")));
    }

    @Test
    void theStatusDateIsReadInTheBackgroundAndNeverBlocksOnChitService() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        EndOfDayServiceTest.FakeChit chit = new EndOfDayServiceTest.FakeChit() {
            @Override
            public BusinessDateInfo businessDateOnce() {
                try {
                    release.await(10, TimeUnit.SECONDS); // a chit service that is still waking up
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return businessDate();
            }
        };
        EndOfDayCoordinator c = coordinator(chit);

        long t0 = System.nanoTime();
        assertNull(c.businessDate()); // returns at once with nothing yet
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(2));

        release.countDown();
        ChitServiceClient.BusinessDateInfo seen = null;
        for (int i = 0; i < 100 && seen == null; i++) {
            Thread.sleep(50);
            seen = c.businessDate();
        }
        assertNotNull(seen);
        assertEquals(chit.date.toString(), seen.businessDate());
    }

    @Test
    void whenChitServiceCannotBeReachedTheStatusDateStaysEmptyInsteadOfFailing() throws Exception {
        EndOfDayServiceTest.FakeChit chit = new EndOfDayServiceTest.FakeChit() {
            @Override
            public BusinessDateInfo businessDateOnce() {
                throw new ChitServiceException("GET /api/v1/business-date -> HTTP 502", 502, null);
            }
        };
        EndOfDayCoordinator c = coordinator(chit);

        assertNull(c.businessDate());
        Thread.sleep(200);
        assertNull(c.businessDate());
    }
}
