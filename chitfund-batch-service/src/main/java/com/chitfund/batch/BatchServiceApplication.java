package com.chitfund.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Standalone deployable for the chit fund's batch jobs (the daily end-of-day chain) and the end-of-day run that moves the
 * chit business date forward. Deliberately NOT part of the chitfund-service web app: it runs as its own JAR/process so
 * batch load never competes with, or is coupled to the deploy cadence of, the chit API.
 *
 * <p>Job execution itself is never triggered by Spring Boot's default "run every discovered
 * Job at startup" behavior (disabled via spring.batch.job.enabled=false) — jobs are launched
 * exclusively via {@link com.chitfund.batch.core.quartz.QuartzJobBridge} on their configured
 * cron schedule, or on-demand via {@link com.chitfund.batch.web.BatchAdminController}.
 */
@SpringBootApplication
@EnableScheduling
public class BatchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BatchServiceApplication.class, args);
    }
}
