package com.chitfund.batch.jobs.daily;

import com.chitfund.batch.chit.ChitServiceClient;
import com.chitfund.batch.chit.ChitServiceClient.RunResult;
import com.chitfund.batch.core.BatchJobContext;
import com.chitfund.batch.core.BusinessBatchTasklet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.LocalDate;

/**
 * Shape shared by the three chit jobs: one call to the chit service for the run's business date, which does the work
 * group by group and answers with what it did. The job fails (and so holds end of day on this date) when any group
 * could not be finished, naming the groups; running it again finishes only what is left, because every chit job is
 * safe to repeat. It also fails, rather than skipping quietly, when {@code CHIT_SERVICE_URL} is not set: this service
 * has nothing else to do.
 */
abstract class AbstractChitDailyJob implements BusinessBatchTasklet {

    private static final Logger log = LoggerFactory.getLogger(AbstractChitDailyJob.class);

    private final ChitServiceClient chit;

    AbstractChitDailyJob(ChitServiceClient chit) {
        this.chit = chit;
    }

    /** The call to make for the date. */
    abstract RunResult call(ChitServiceClient chit, LocalDate businessDate);

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            LocalDate day = BatchJobContext.businessDate();
            if (!chit.configured()) {
                throw new IllegalStateException(getJobName() + " has nothing to call: CHIT_SERVICE_URL is not set");
            }
            RunResult result = call(chit, day);
            if (!result.clean()) {
                throw new IllegalStateException(getJobName() + " for " + day + ": " + result.groupsFailed() + " group(s) failed, "
                        + result.groupsProcessed() + " done. " + result.failuresText());
            }
            log.info("{} for {}: {} group(s), {}", getJobName(), day, result.groupsProcessed(), result.figuresText());
            return RepeatStatus.FINISHED;
        };
    }
}
