# The chit jobs

Three DAILY tasklet jobs call the chitfund service once each for the business date. The chit service does the work group by group
and answers with a summary; a job fails, and so holds end of day on that date, if any group could not be finished (the message
names the groups). Running it again finishes only what is left: every job is safe to repeat.

| Job | Priority | Chit endpoint | What it does |
|---|---|---|---|
| `CHIT_DEMAND_RAISE_DAILY` | 10 | `POST /api/v1/chit/batch/demand-run` | Opens every cycle whose instalment date has been reached and raises each subscriber's instalment, using advance money held. Catches up missed days. |
| `CHIT_LATE_FEE_DAILY` | 20 | `POST /api/v1/chit/batch/late-fee-run` | Charges the late fee, one day at a time, on each overdue instalment up to the date. Never charges a day twice. |
| `CHIT_DEFAULT_CHECK_DAILY` | 30 | `POST /api/v1/chit/batch/default-run` | Flags tickets that breached their group's default threshold. Only flags; recovery is manual. |

All three are seeded **enabled** (`V3__seed_chit_jobs.sql`) and run, in this order, inside the nightly end of day (`0 0 1 * * ?`,
Asia/Kolkata). Before relying on them, run each once by hand: the admin console's Chit funds screens have a button for each, or call
the three endpoints on the chit service directly, or `POST /api/v1/batch-admin/jobs/{name}/trigger` here.

The chit service refuses a run whose `businessDate` is not its own current business date (409 `BUSINESS_DATE_MISMATCH`), so a stale
or misconfigured batch cannot book a wrong day. The settings (`CHIT_SERVICE_*`) are in the README.

A job with no `CHIT_SERVICE_URL` fails with a message saying so; in the `prod` profile the service does not start without it.
