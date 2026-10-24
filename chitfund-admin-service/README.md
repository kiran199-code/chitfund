# chitfund-admin-service

The operations console for the chit fund: a React single-page app and the small Spring Boot service that serves it and
forwards its calls. It has **no database**. Everything it shows or does goes through two other services:

| It talks to | For | Address setting |
|---|---|---|
| `chitfund-service` | groups, tickets, dues, payments, the business date and holiday list, clearing chit data | `CHIT_SERVICE_URL` |
| `chitfund-batch-service` | the job list and their last runs, end of day, clearing the job history | `BATCH_SERVICE_URL` |

It does not depend on the lending platform in any way.

## Screens

| Menu | Screen | Who |
|---|---|---|
| Overview | Business date, active groups, shortcuts | everyone |
| Chit funds | Groups, group detail (enrol, activate, cancel), ticket statement and late-fee preview | everyone reads; `CHIT_OPS` changes |
| Chit funds | New group (rules, preview, create) | `CHIT_OPS` |
| Chit funds | Dues & payments: overdue, upcoming and open instalments; record a payment; run the daily jobs now | everyone reads; `CHIT_OPS` pays; `CHIT_OPS` or `BATCH_OPS` run the jobs |
| Calendar | Business date and holidays: the date, working-day check, add or remove holidays, set the date by hand | everyone reads; `CHIT_OPS` edits holidays; `ADMIN` sets the date |
| Batch | Batch jobs: settings, last outcome, next run, recent runs | `BATCH_OPS` |
| Batch | End of day: start or resume, live progress | `BATCH_OPS` |
| Administration | Maintenance: clear all data (test environments) | `ADMIN` |

The server enforces this in `AccessPolicy.java` (first matching rule wins, anything unlisted is `ADMIN` only); `frontend/src/access.ts`
only keeps the menu and buttons tidy. `AccessPolicyTest` checks the rules.

## Logins

HTTP Basic, stateless. Three logins, each existing only when its password is set (the admin one always exists):

| Role | Username setting | Password setting |
|---|---|---|
| `ADMIN` (can do everything) | `ADMIN_USERNAME` (default `admin`) | `ADMIN_PASSWORD` (default `changeme`: **change it**) |
| `CHIT_OPS` | `CHIT_OPS_USERNAME` (default `chit-ops`) | `CHIT_OPS_PASSWORD` |
| `BATCH_OPS` | `BATCH_OPS_USERNAME` (default `batch-ops`) | `BATCH_OPS_PASSWORD` |

The "Acting as" box on the chit screens sends the typed name as `X-Acting-As`; the console passes it on as the chit service's
`X-Actor`, so audit rows show who did it. The names are typed, not verified, until sign-in by person (IAM) is added.

## Settings (environment)

| Variable | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8088` | Port (Render sets it through the platform) |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | `admin`, `changeme` | Administrator login |
| `CHIT_OPS_USERNAME`, `CHIT_OPS_PASSWORD` | `chit-ops`, empty (off) | Chit operations login |
| `BATCH_OPS_USERNAME`, `BATCH_OPS_PASSWORD` | `batch-ops`, empty (off) | Batch operations login |
| `ADMIN_TIMEZONE` | `Asia/Kolkata` | Shown to the UI |
| `CHIT_SERVICE_URL` | empty | Public address of `chitfund-service`. Empty: the chit screens say they are not set up |
| `CHIT_SERVICE_USERNAME`, `CHIT_SERVICE_PASSWORD` | `chit-service`, empty | Must equal the same two settings on `chitfund-service` |
| `BATCH_SERVICE_URL` | empty | Public address of `chitfund-batch-service`. Empty: batch screens and end of day say they are not set up |
| `BATCH_ADMIN_USERNAME`, `BATCH_ADMIN_PASSWORD` | `batch-admin`, empty | Must equal the same two settings on `chitfund-batch-service` |

## Run

```
# back end (needs Java 17 and Maven)
mvn spring-boot:run

# front end with hot reload on :5173, proxying /api to :8088
cd frontend && npm install && npm run dev
```

`mvn package` alone builds only the back end; the `Dockerfile` builds the UI (`npm run build`) first and copies it into
`src/main/resources/static`, so the single jar serves both. API documentation: `/swagger-ui.html`.

## API

All under `/api/admin`: `GET /me`, `GET /business-date`, `/calendar/*` (business date, working day, holidays), `/chit/*`
(groups, tickets, dues, payments, statements, the cycle auction and its bids, close, payout request / approve / disburse / float, dividends, draws, foreman covers, the pool's liquidity position, and the three `batch/*-run` buttons), `/batch/jobs` and
`/batch/jobs/{job}/executions`, `/eod/status` and `/eod/run`, `/maintenance/status` and `/maintenance/clear-data`. Messages from
the two services come back unchanged, so a refusal ("business date mismatch", "group not full") reads the same here as there.

## Tests

48 unit tests (`mvn test`): `AccessPolicyTest` (who may call what), the controllers against fake chit and batch services
(`FakeChit`, `FakeBatch`), and `MaintenanceServiceTest` (the order of the two-service clear: nothing is deleted unless the batch
service allows it and is idle). The UI is checked with `npm run build` (`tsc --noEmit` and `vite build`).
