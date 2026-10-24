-- V3: the three daily jobs of the chit fund. Each is a single-threaded tasklet that makes one call to the chitfund service
-- for the business date; the chitfund service does the work group by group.
--   CHIT_DEMAND_RAISE_DAILY  (priority 10)  opens due cycles and raises instalments
--   CHIT_LATE_FEE_DAILY      (priority 20)  charges late fees, after the new instalments are on the books
--   CHIT_DEFAULT_CHECK_DAILY (priority 30)  flags defaulters, after the late fees
-- End of day (batch.eod.enabled) runs the DAILY jobs in priority order for the current business date and, only when all of
-- them completed, moves the business date forward; the cron expressions below only matter when end of day is switched off.
-- The retry limit is low because the chitfund service already retries calls itself and every job is safe to repeat by hand.
INSERT INTO batch.batch_job_config
    (job_name, job_category, job_type, cron_expression, enabled, restartable,
     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
VALUES
    ('CHIT_DEMAND_RAISE_DAILY', 'DAILY', 'TASKLET', '0 10 1 * * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 10, 30),

    ('CHIT_LATE_FEE_DAILY', 'DAILY', 'TASKLET', '0 20 1 * * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 20, 30),

    ('CHIT_DEFAULT_CHECK_DAILY', 'DAILY', 'TASKLET', '0 30 1 * * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 30, 30)
ON CONFLICT (job_name) DO NOTHING;
