# chitfund-e2e-service

A separate, deployable test service. It drives the **real** chit, portal and admin-facing APIs over HTTP: it makes mock Gmail customers, has them
ask for seats on the portal, approves the requests, activates a group, jumps the business date, takes payments, runs auctions with several
bidders and pays out, then checks the books. It is not part of the three services' build.

**Test environments only.** A run can clear all chit data and moves the business date forward. The service will not start unless
`E2E_TEST_ENV=true`.

## What a run does

| Scenario | What it covers |
|---|---|
| A: full cycle | Group, plan listing, portal plan list, mock sign-ins, seat requests (approve, decline, withdraw, GROUP_FULL), activation, every cycle: due date, payments (full, two parts, over, replay), portal bids with refusals, close, settlement arithmetic, maker-checker payout, ledger |
| B: late and default | Grace days, late fee, missed instalments, default case, cure |
| C: bidding rules | Staff bids, refusals, idempotent bid, withdrawal, no-bid draw |
| D: sign-in and limits | Gmail variants are one member, account mismatch refused, bad tokens, request limits, idempotent request, isolation between members |

Dates: `DAY_BY_DAY` runs the end-of-day cycle for each day (start, demand run, late-fee run, default run, advance). `LEAP` sets the date once and runs the
jobs once, so a year takes seconds. The date never goes backwards (clear the data to start again).

## Settings (environment variables)

| Variable | Meaning |
|---|---|
| `E2E_TEST_ENV` | must be `true` |
| `E2E_UI_USERNAME` / `E2E_UI_PASSWORD` | sign-in for this service's page and API (default user `e2e`; password required) |
| `CHIT_SERVICE_URL`, `CHIT_SERVICE_USERNAME`, `CHIT_SERVICE_PASSWORD` | chitfund-service (default user `chit-service`) |
| `PORTAL_SERVICE_URL`, `PORTAL_ORIGIN` | chitfund-portal-service and the browser origin it allows (default: the same URL) |
| `PORTAL_ADMIN_USERNAME`, `PORTAL_ADMIN_PASSWORD` | the portal's admin (service) login (default user `portal-admin`) |
| `PORT` | default 8090 |

## What the other services need

* **chitfund-service:** `CHIT_BUSINESS_DATE_MODE=MANAGED` and `CHIT_RESET_ENABLED=true` (the preflight checks both).
* **chitfund-portal-service:** `SPRING_PROFILES_ACTIVE=e2e` (turns on the mock sign-in `e2e|<subject>|<email>|<name>` and turns **off** real Google
  sign-in, so use a separate portal deployment, never your live one), `PORTAL_ALLOWED_ORIGINS` including `PORTAL_ORIGIN`, and raised rate limits
  (a run signs in and requests far more often than a person; the service waits and retries on HTTP 429, but a run is slow if the limits are tight).
* Free Render services sleep; the service waits and retries while they wake.

## Running

    mvn -B package            # runs the unit tests and builds target/chitfund-e2e-service.jar
    export E2E_TEST_ENV=true E2E_UI_PASSWORD=... CHIT_SERVICE_URL=... CHIT_SERVICE_PASSWORD=... \
           PORTAL_SERVICE_URL=... PORTAL_ADMIN_PASSWORD=...
    java -jar target/chitfund-e2e-service.jar

Open `/` for the form and the list of runs, or use the API (Basic auth):

    POST /api/runs          {"scenarios":["A","B","C","D"],"dateMode":"LEAP","tickets":10,"customers":6,"chitValue":100000,"reset":true}
    GET  /api/runs          recent runs (the last 10 are kept in memory; they are lost on restart)
    GET  /api/runs/{id}     the report as JSON
    GET  /runs/{id}         the report as a page
    GET  /health            no password

One run at a time (a second start gets HTTP 409). Reports contain no passwords or tokens.

## Render

Docker web service, `dockerfilePath: ./chitfund-e2e-service/Dockerfile`, `dockerContext: ./chitfund-e2e-service`, health check `/health`, and the
variables above. Set `JAVA_TOOL_OPTIONS=-Xmx256m` on the free plan.

## Honest limits

The scenarios were written against the services' source and API descriptions and have been compiled and unit-tested against a fake server only. The
first real run is the real test; expect to adjust field names or expected values where a service answers differently from what was assumed.
The ledger check assumes the first group after a reset has id 1, so it is skipped when a run does not reset.
