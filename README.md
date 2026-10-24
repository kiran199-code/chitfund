# Chitfund

A chit fund platform: groups of subscribers pay a fixed instalment each cycle, one subscriber takes the pot each cycle, and
everything is kept in an append-only double-entry ledger. It started inside the lending platform and is now a project of its
own; it shares no code, database or service with lending.

| Module | What it is | Port (local / Render) |
|---|---|---|
| `chitfund-service` | The chit API: groups, tickets, cycles, enrolment, collection (demands, payments, late fees, defaults), the ledger, the **business date** and the working-day calendar. Java 17, Spring Boot 3.3.2, JDBC, Flyway | 8086 / 10000 |
| `chitfund-batch-service` | Spring Batch + Quartz: the three daily chit jobs and the nightly **end of day** that moves the business date forward. Calls the chit API; never touches chit tables | 8087 / 10000 |
| `chitfund-admin-service` | The operations console: Spring Boot API + React UI in one container, no database. Roles `ADMIN`, `CHIT_OPS`, `BATCH_OPS` | 8088 / 10000 |
| PostgreSQL 16 | One database `chitfund`: schema `chit` (chit service) and `batch` (batch service) | 5432 |

Each module has its own README with its settings and API: [`chitfund-service`](chitfund-service/README.md),
[`chitfund-batch-service`](chitfund-batch-service/README.md), [`chitfund-admin-service`](chitfund-admin-service/README.md).

## Where it stands

Phases 1 to 5 of the 10-phase plan in [`docs/Chit_Fund_Specification_Prompt_v2.md`](docs/Chit_Fund_Specification_Prompt_v2.md)
are built: group set-up and rules, enrolment and activation, the cycle calendar, demands, payments, late fees, default
detection, reversal, statements and dues, the daily jobs and end of day, and (Phase 5) auctions of every type, winner selection
with recorded draws, prize payout with maker-checker, dividends, the last cycle and the liquidity rule. **Not built yet:**
transfers and recovery of foreman covers (Phase 6), closure and leftover cash (Phase 7 onward), the customer portal. See
[`chitfund-service/BUILD_STATUS.md`](chitfund-service/BUILD_STATUS.md) for assumptions, open questions and exactly what has and
has not been verified (a real Maven build and a deployment have not been run yet).

## Quick start

```bash
# everything locally: PostgreSQL and the three services
docker compose up --build
#   chit API          http://localhost:8086/swagger-ui.html   (chit-service / changeme)
#   batch admin API   http://localhost:8087/swagger-ui.html   (batch-admin / changeme)
#   admin console     http://localhost:8088                   (admin / changeme)

# build and test all Java modules (the admin UI is built by its Dockerfile, or `npm run build` in chitfund-admin-service/frontend)
mvn clean package
```

## The business date, end of day and holidays

The chit service owns the business date and the calendar. In `MANAGED` mode (default) the date is stored and only moves when
end of day closes it, so a test environment can run ahead of the wall clock; in `SYSTEM` mode it is the wall-clock date in
`CHIT_TIMEZONE`. Weekend days are `CHIT_WEEKEND_DAYS` and holidays are rows edited on the console's *Business date & holidays* screen.
**End of day** (batch service, nightly at 01:00 India time and on demand from the console) marks the date `EOD_RUNNING`, runs the
enabled daily jobs in order (raise demands, charge late fees, check defaults), and only when all complete moves to the next day.
If a job fails the date stays put; fix the cause and start end of day again: finished jobs are skipped.

## Deploy to Render

**New > Blueprint** on this repository uses `render.yaml`: three web services and no database. Render allows one free Postgres
per account and the lending platform already uses it, so create an empty PostgreSQL database named `chitfund` somewhere else (a free
Neon or Supabase database works; use the direct, not the pooled, connection) and enter its details as `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER` and `DB_PASSWORD` (the batch service calls the user `DB_USERNAME`) when Render asks for the values marked
`sync: false`. Do not share the lending database: its batch service already owns a schema called `batch` there. The services create
their own schemas, `chit` and `batch`, on first start.

1. Create the Blueprint. `chitfund-service` starts first; the other two ask for values marked `sync: false`.
2. Copy `chitfund-service`'s public URL from the dashboard into `CHIT_SERVICE_URL` on `chitfund-batch-service` and on `chitfund-admin-service`.
3. Copy `chitfund-batch-service`'s public URL into `BATCH_SERVICE_URL` on `chitfund-admin-service`.
4. Read the generated `ADMIN_PASSWORD` (and `CHIT_OPS_PASSWORD`, `BATCH_OPS_PASSWORD`) from `chitfund-admin-service` > Environment and sign in.

The service credentials are wired by the Blueprint (`CHIT_SERVICE_PASSWORD` and `BATCH_ADMIN_PASSWORD` are generated once and read by
the services that need them). `CHIT_RESET_ENABLED` and `BATCH_RESET_ENABLED` are on in the sandbox blueprint so the Maintenance
screen can start over; remove them where the data matters. Free services sleep when idle, so the first request after a pause takes
about a minute, and the nightly end of day does not fire while the batch service sleeps: start it from the console.

## Security

Every service credential is HTTP Basic today (one for the chit API, one for the batch admin API, three console logins). The chit
service is written so the credential can be replaced by the IAM JWT resource server later in one class (`config/SecurityConfig`);
permission codes for that are in [`chitfund-service/IAM_PERMISSIONS.txt`](chitfund-service/IAM_PERMISSIONS.txt). Change every
default password (`changeme`) in any real environment.

## Coming from the lending platform

This project was cut out of the lending repository. Nothing here calls lending-service, lending-batch-service or the lending
database. If you ran the chit service there, its old `chit` schema is still in the lending database: this project starts a fresh
database, so any test groups created there are not carried over (they were sandbox data; export them first if you need them).
