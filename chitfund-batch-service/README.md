# chitfund-batch-service

Standalone Spring Batch application that runs the chit fund's nightly work: the three daily chit jobs and the end-of-day run
that moves the chit business date forward. It is deployed as its own JAR, independently of `chitfund-service`. It never
touches the chit tables: every change to chit data goes through `chitfund-service`'s REST API. It keeps its own job
configuration, Spring Batch history and Quartz tables in a dedicated `batch` schema of the chitfund database.

## What runs

| Job | Priority | Chit endpoint | What it does |
|---|---|---|---|
| `CHIT_DEMAND_RAISE_DAILY` | 10 | `POST /api/v1/chit/batch/demand-run` | Opens every cycle whose instalment date has been reached and raises each subscriber's instalment, using advance money held. Catches up missed days. |
| `CHIT_LATE_FEE_DAILY` | 20 | `POST /api/v1/chit/batch/late-fee-run` | Charges the late fee, one day at a time, on each overdue instalment up to the date. Never charges a day twice. |
| `CHIT_DEFAULT_CHECK_DAILY` | 30 | `POST /api/v1/chit/batch/default-run` | Flags tickets that breached their group's default threshold. Only flags; recovery is manual. |

Each is a single-threaded tasklet that makes one call for the business date. The chit service does the work group by group and
answers with a summary; a job fails, and so holds end of day on that date, if any group could not be finished (the message names
the groups). Running it again finishes only what is left: every job is safe to repeat. See `CHIT_JOBS.md`.

## Business date and end of day

`chitfund-service` owns the business date (see its README: `MANAGED` or `SYSTEM` mode, and the holiday calendar). This service
reads it (`BusinessDateProvider`, `batch.business-date.source=CHIT`; `SYSTEM` = wall clock, standalone use only).
`BatchJobContext.businessDate()` throws if no date was set; there is no silent fallback to the system clock.

**End of day** (`core/eod`): `EndOfDayService` asks the chit service to start end of day (`OPEN` to `EOD_RUNNING`; if it is already
`EOD_RUNNING` this is a resume), runs every enabled `DAILY` job for that date in priority order, skipping any that already
completed for it, stops at the first job that does not complete, and only if all did calls `advance` so the date moves to the
next calendar day. (In the chit service's `SYSTEM` mode the date follows the clock, and the start and advance calls are accepted
but move nothing.) It runs nightly (`batch.eod.cron`, default `0 0 1 * * ?`, zone `batch.eod.zone`) from `EndOfDayScheduler`, and
on demand via `POST /api/v1/batch-admin/eod/run` (background thread; poll `GET /eod/status`). One run at a time per instance;
across instances the chit service refuses a second start and Spring Batch refuses a second concurrent run of the same job. With
`batch.eod.enabled=true` (default) the `DAILY` jobs' own crons are ignored; with `false` they run on their own crons.

If a night fails the business date stays on that day in `EOD_RUNNING`. Fix the cause (see `GET /jobs/{job}/errors` and the job's
failure message) and run `POST /eod/run` again. On the Render free plan the service sleeps, so the nightly cron may be missed; run
it on demand.

## Settings (environment)

| Variable | Default | Meaning |
|---|---|---|
| `CHIT_SERVICE_URL` | `http://localhost:8086` (required in `prod`) | Base URL of `chitfund-service`. |
| `CHIT_SERVICE_USERNAME` `CHIT_SERVICE_PASSWORD` | empty | The chit service's Basic credential. Sent only when a username is set. |
| `CHIT_SERVICE_REQUEST_TIMEOUT_SECONDS` | 240 | Long, because a run touches every group and a free-plan service can take a minute to wake. |
| `CHIT_SERVICE_MAX_ATTEMPTS` | 4 | Attempts on I/O errors and 429/502/503/504. Anything else (including 409 `BUSINESS_DATE_MISMATCH`) fails at once. The business-date calls (read, start, advance) retry for about two minutes, so a cold-starting chit service does not fail the night. |
| `CHIT_SERVICE_CONNECT_TIMEOUT_SECONDS` `CHIT_SERVICE_RETRY_BACKOFF_MILLIS` | 5, 500 | |
| `BATCH_BUSINESS_DATE_SOURCE` | `CHIT` | `CHIT` or `SYSTEM`. |
| `BATCH_EOD_ENABLED` `BATCH_EOD_CRON` `BATCH_EOD_ZONE` | `true`, `0 0 1 * * ?`, `Asia/Kolkata` | The nightly end of day. |
| `BATCH_ADMIN_USERNAME` `BATCH_ADMIN_PASSWORD` | `batch-admin`, `changeme` | HTTP Basic login of `/api/v1/batch-admin`. **Set the password in every real environment.** |
| `BATCH_RESET_ENABLED` | `false` | Lets `POST /api/v1/batch-admin/maintenance/clear-data` (body `{"confirm":"RESET"}`) empty the job history. Sandbox and test only. |
| `BATCH_KAFKA_ENABLED` | `false` locally, `true` in `prod` | Publish job-completion events to `chitfund.batch.job-events`. A missing broker never fails a job. |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` | localhost, 5432, `chitfund`, `chitfund`, `chitfund` (no defaults in `prod`) | The chitfund database. This service owns only the `batch` schema. |
| `SERVER_PORT` | `8087` | |
| `BATCH_SWAGGER_ENABLED` | on locally, off in `prod` | Swagger UI at `/swagger-ui.html` behind the same Basic login. |

## Running locally

Prerequisites: PostgreSQL 16 with the `chitfund` database (the chit service creates its own `chit` schema; this service creates
`batch`), and the chit service running on `localhost:8086`. Kafka is optional.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway runs at startup: `V1__batch_framework_schema.sql` creates the `batch` schema and the framework tables,
`V2__quartz_schema.sql` Quartz's `QRTZ_*` tables, `V3__seed_chit_jobs.sql` the three chit jobs. Spring Batch's own `BATCH_*`
tables are created by Boot's schema initializer into the same schema (`currentSchema=batch,public` in the JDBC URL).

For production see `application-prod.yml`: every connection value comes from the environment, the service refuses to start
without `CHIT_SERVICE_URL`, and the same JAR can run as several clustered instances against one database (Quartz
`isClustered=true`, and `JobExplorer.findRunningJobExecutions` refuses a double launch).

## Admin API

All under `/api/v1/batch-admin`, HTTP Basic (`batch.admin.username` / `batch.admin.password`, role `BATCH_ADMIN`):

| Method & path | Purpose |
|---|---|
| `GET /jobs`, `GET /jobs/{jobName}` | The configured jobs and their settings. |
| `PUT /jobs/{jobName}` | Change a job's cron, enabled flag, retry and timeout settings. |
| `POST /jobs/{jobName}/trigger` | Launch a job now. Optional body `{"businessDate":"yyyy-MM-dd"}` for a backdated run; without it the run is for the current business date. Disabled jobs cannot be launched. |
| `GET /jobs/{jobName}/executions?limit=` | Recent `JobExecution`s. |
| `GET /jobs/{jobName}/errors?limit=` `GET /executions/{id}/errors` | Dead-letter records. |
| `POST /config/reload` | Re-read `batch_job_config` and rebuild and reschedule every job. |
| `GET /status/today` | Per job: last outcome and next Quartz fire time. |
| `POST /eod/run` | Run end of day for the current business date, or resume a failed one: 202 started, 409 if already running or if `expectedBusinessDate` is given and is not the current date. |
| `GET /eod/status` | `{running, businessDate, progress, lastRun}`; `progress` is live while a run is going (one row per daily job). Kept in memory; resets on restart. |
| `GET /maintenance/status` `POST /maintenance/clear-data` | Empty the job execution history (see below). |

**Clearing the job history.** Spring Batch refuses to start a job a second time for a business date it already completed. After the chit
service's *Clear all data* moves the business date back, end of day meets such dates again, so `POST /maintenance/clear-data` truncates
`batch_job_instance`, `batch_job_execution` (with parameters and context), `batch_step_execution` (with context), `batch_job_audit_log` and
`batch_job_error_records`; the job settings, the Quartz tables and the id sequences are kept. It answers 403 `RESET_DISABLED` unless
`BATCH_RESET_ENABLED=true`, 400 without the confirmation word, and 409 `BATCH_BUSY` while end of day runs or a job started in the last 30
minutes is unfinished.

## How the framework works

- **Config-driven job registry** (`core/BatchJobRegistry`): every job is one row in `batch.batch_job_config` plus one Spring bean
  implementing `BusinessBatchTasklet` (single-operation jobs, which is all the chit jobs) or `BusinessChunkJob<I,O>` (partitioned scans;
  none today). The registry assembles a Spring Batch `Job` for every config row at startup and on `POST /config/reload`.
- **Scheduling**: `core/quartz/QuartzJobBridge` schedules every enabled row that has a cron expression, on startup and on reload.
- **Cross-cutting concerns** are shared listeners: `MetricsJobExecutionListener` (Micrometer + `KafkaJobEventBridge`),
  `AuditStepExecutionListener` (`batch.batch_job_audit_log`), `DeadLetterSkipListener` (`batch.batch_job_error_records`),
  `BatchJobContextStepListener` (business date and job parameters for the running job).
- **Idempotency and restart**: restartable jobs use just `businessDate` as their JobParameters key, so a failed run is resumed by
  triggering again with the same date.

## Adding a job

1. Write a `@Component` implementing `BusinessBatchTasklet` (or extend `AbstractChitDailyJob` for another chit-service call).
   `getJobName()` must equal the row's `job_name`.
2. Add a new Flyway migration inserting its `batch_job_config` row (`V4__...sql`; never edit a migration that has shipped).
   Category `DAILY` jobs join the end-of-day chain in `priority` order.
3. Deploy, or restart, or `POST /config/reload`.

## Tests

`mvn test` runs the unit tests (Spring-free: the client against an in-JVM HTTP server, end-of-day against fakes, the three jobs,
business-date source, progress, maintenance). The Spring Batch gateway and partition reader tests need the real Spring Batch jars.
