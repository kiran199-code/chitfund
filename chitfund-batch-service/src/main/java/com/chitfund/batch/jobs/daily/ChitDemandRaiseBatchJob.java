package com.chitfund.batch.jobs.daily;

import com.chitfund.batch.chit.ChitServiceClient;
import com.chitfund.batch.chit.ChitServiceClient.RunResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Daily EOD (tasklet): opens every chit cycle whose instalment date has been reached and raises each subscriber's
 * instalment, using any advance money held. Calls {@code POST /api/v1/chit/batch/demand-run}. A missed day is caught up
 * by the next run; a repeated run changes nothing. Runs first (priority 10), before the
 * late-fee and default jobs.
 */
@Component
public class ChitDemandRaiseBatchJob extends AbstractChitDailyJob {

    public ChitDemandRaiseBatchJob(ChitServiceClient chit) {
        super(chit);
    }

    @Override
    public String getJobName() {
        return "CHIT_DEMAND_RAISE_DAILY";
    }

    @Override
    RunResult call(ChitServiceClient chit, LocalDate businessDate) {
        return chit.runDemands(businessDate);
    }
}
