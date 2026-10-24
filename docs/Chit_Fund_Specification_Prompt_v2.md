# Prompt v2: Chit Fund microservice for the Lending platform

Version 2, 23 Oct 2026. Replaces the module-level prompt (`Chit_Fund_Specification_Prompt.md`), which is kept unchanged beside this file.

## Notes for Kiran (not part of the prompt)

**What changed from v1**

1. It is now a **separate microservice**, `lending-chit-service`, built the way `lending-lcm-service` was built (own Maven project, own schema `chit`, own Dockerfile, own Render service, service login, pull-based event feed, Maintenance clear). The prompt tells the assistant to read the real files first and lists every change to an existing module (root pom, `render.yaml`, batch jobs, admin console, Maintenance, E2E suite, IAM catalogue, docs).
2. **Facts from the repo replace assumptions.** Java 17 and Spring Boot 3.3.2, React/Vite admin console (v1 said Angular), HTTP Basic service credentials with an `X-Actor` header, no broker (v1 said Kafka), no customer master (a customer is a `customerId` number), business date owned by lending-service.
3. **Three design flaws in v1 are fixed.** (a) With `ADJUST_NEXT_INSTALMENT`, the cash collected in a cycle is `P - dividend pool of the previous cycle`, so the prize of cycle k+1 can exceed the cash in the pool. v1's conservation check only holds on a gross basis. v2 separates the gross identity from the cash identity and adds a payout liquidity rule. (b) The last cycle has no discount, so a commission taken from the discount is zero. v2 adds a `last_cycle_commission_rule`. (c) The "uneven dividend" fixture in v1 (`15,001` over 20 tickets) divides exactly to paise, so it would not prove the rounding rule. v2 uses `15,000.07`.
4. **New sections:** idempotency and error contract, EOD integration (batch jobs), Maintenance integration, journal templates to review, quality gates and "what must be reported as not verified", a consolidated `TODO-VERIFY` register.

**Defaults I chose (change any you disagree with, before pasting)**

| # | Decision | Default in this prompt | Why |
|---|---|---|---|
| D1 | Own double-entry ledger or reuse the lending accounting engine | Own append-only journal in schema `chit` | The accounting engine in lending-service is built around `loan_account`; reusing it would couple the two. LCM took the same route with its own ledger |
| D2 | Events | Event table plus pull feed; Kafka publisher optional behind a flag, off by default | The platform has no broker and runs on Render's free plan. You have an interest in Kafka, so the publisher is a clearly marked extension, not the base |
| D3 | Admin UI | React/Vite screens inside `lending-admin-service` | That is what the console is. Subscriber screens wait for the customer portal (BFF) |
| D4 | Security | Basic service credential now, IAM swap point and `chit.*` permission file | Same as LCM; IAM is built but nothing uses it yet |
| D5 | Scheduling | Platform EOD drives it through new batch jobs (`CHIT_*_DAILY`), no timers by default | The business date moves only at end of day |
| D6 | Collateral for prized-ticket security | Deposit and guarantor held inside the chit service; LCM link is an optional later phase | Keeps phase 1 to 6 independent of LCM |
| D7 | Port | 8086 (8080 lending, 8081 batch, 8082 admin, 8084 lcm, 8085 iam, 8090 e2e; 8083 looks unused but check) | Avoids a clash |

**Not verified by me:** the legal limits and the chit variants (marked `TODO-VERIFY`), and nothing was built or compiled; this is a prompt. The repo facts in section 3 come from reading the files on 23 Oct 2026.

---

## Prompt (paste everything below this line into the coding session, opened in the Lending repo root)

### 1. Role and goal

You are a senior backend engineer working inside my existing **Lending platform** repository. Build a new, separately deployable **Chit Fund microservice**, `lending-chit-service`, that covers end to end: group creation and enrolment, periodic subscription collection, the monthly auction and prize payout, dividends, defaults and recovery, ticket transfer and exit, and final closure. Wire it into the platform (batch end of day, admin console, Maintenance, E2E suite, docs) as listed in section 14, changing existing modules as little as possible.

This is a **personal training project in a sandbox**, not a regulated product. Money movement is simulated. Real-world rules are configurable and marked `TODO-VERIFY`.

### 2. Working rules

1. **Read before you write.** First read the files listed in section 3 and report back, in a short note: (a) the assumptions you are making, (b) open questions, (c) the proposed package structure, (d) any place where my description of the platform does not match the code. Then wait for my go-ahead before Phase 1.
2. **Work in phases (section 18) and stop after each one** with: what was built, how it was verified, what was **not** verified, and the exact commands I should run.
3. **Do not invent library methods, annotations or APIs.** If you are unsure something exists in the version the repo uses (check the poms, do not assume), say so and tell me to verify it.
4. **Say plainly what was not run.** If you cannot run Maven, Docker or Testcontainers, say so, and state what you checked instead (for example compiling with javac against stubs, or plain-Java harnesses for the pure calculation code). Never describe untested code as tested.
5. **No commit and no push** unless I ask. Do not touch git config or remotes. Never put credentials, tokens or passwords into files, logs or examples.
6. **Do not edit existing modules beyond the integration list in section 14.** For each existing file you must change, show the change as a small diff and explain it in one line. Do not reformat or refactor existing code.
7. **Every rule is data, not code.** Keep every real-world assumption configurable and mark it `TODO-VERIFY`.
8. If a requirement is ambiguous, state your assumption explicitly instead of silently choosing.

### 3. What already exists (read these first, then treat them as the conventions to follow)

Repo root: `pom.xml` (aggregator; each module keeps its own Spring Boot parent), `render.yaml`, `docker-compose.yml`, `CHANGELOG.md`, `README.md`, `docs/`.

| Service | Port | Schema | Notes |
|---|---|---|---|
| `lending-service-db-layer/lending-service` | 8080 | public | Loans, products, **business date** (`GET /api/v1/business-date`, `/working-day`, `/holidays`), accounting engine, ledger outbox, maintenance |
| `lending-batch-service` | 8081 | `batch` | Spring Batch 5.1 + Quartz. End of day runs the enabled DAILY jobs in priority order |
| `lending-admin-service` | 8082 | none (read-only) | Spring API + **React 18 / Vite / TypeScript** console; HTTP Basic logins; forwards to the services with service credentials |
| `lending-lcm-service` | 8084 | `lcm` | **Your template.** Separate Maven project |
| `lending-iam-service` | 8085 | `iam` | Spring Authorization Server; built, not yet adopted by any service |
| `lending-e2e-test-service` | 8090 | none | End-to-end suite and hosted console |

Stack: Java 17, Spring Boot 3.3.2, PostgreSQL 16 (one shared instance, `lending-db`), Flyway per schema, Lombok, springdoc OpenAPI. Deployed on Render's free plan (services sleep and take about a minute to wake) with a Docker image per service.

**Read `lending-lcm-service` closely; the chit service copies its structure, not its business code.** In particular:

- `pom.xml`, `Dockerfile` (two-stage, non-root, `EXPOSE <port>`), `src/main/resources/application.yml` (own schema via `spring.datasource.hikari.schema` and `spring.flyway.schemas`, own history table `lcm_schema_history`, `create-schemas: true`).
- `config/SecurityConfig` (one Basic service credential, stateless, `/api/**` authenticated, JSON 401), `config/ApiExceptionHandler` (error body `{status, code, message}`), `common/Actor` (`X-Actor` header), `common/Money`, `common/Audit`, `common/Hashing` and the idempotency handling (same key and same body returns the first result with `replayed=true`; same key with a different body returns `409 IDEMPOTENCY_KEY_REUSED`).
- `events/EventFeed` (pull feed with a gap-free cursor; no broker), `maintenance/` (status and clear-data, off unless a switch is on), `portal/` (customer-scoped read models for the future portal back end), `IAM_PERMISSIONS.txt` (one line per endpoint: `code|description|risk`), `INTEGRATION_SNIPPETS.md` (the shared-file edits, kept as snippets), `README.md` (variables table, API summary, rules).
- Maker-checker: the maker and checker must be different `X-Actor` values; controlled by a setting like `LCM_MAKER_CHECKER_ENFORCE`.

Also read: `lending-admin-service/.../service/LcmClient.java` (how the console calls a service: base URL, service login, 90 second read timeout for a sleeping service), `.../config/AccessPolicy.java` (roles and rules, one list, first match wins), `.../service/MaintenanceService.java` and `frontend/src/pages/Maintenance.tsx` (the Clear-all-data chain across services), `lending-batch-service/.../jobs/daily/DemandRaiseBatchJob.java`, `.../lending/LendingServiceClient.java` and `src/main/resources/db/migration/V6__demand_raise_daily_job.sql` (how a daily job is written, seeded and ordered), `lending-e2e-test-service/README.md` and `.../run/Simulation.java` and `.../teardown/Teardown.java`, and `docs/Accounting_Phase_A_Notes.md` if you want to see how the lending accounting engine thinks (you will not reuse it, see D1).

Facts that shape the design:

- There is **no customer master**. A customer is a `customerId` (BIGINT) that the other services store as a plain reference. Do the same; do not build customer management.
- The business date belongs to lending-service. End of day sets it to `EOD_RUNNING`, runs the jobs, then advances it. A job must be **idempotent per business date** and must catch up any missed dates, because a failed end of day is rerun and a completed job instance is skipped.
- Maintenance's Clear all data now chains lending-service, LCM and the batch service. The chit service must join that chain.
- The lending API is open; the console is its only caller. Do not assume per-customer scoping exists anywhere except in the `portal` read models you build.

### 4. Architecture decisions (defaults; follow them unless I say otherwise)

- **D1 Separate ledger.** The chit service owns an append-only double-entry journal in schema `chit`. It never reads or writes another service's tables and has no foreign keys across schemas. Journals are never edited or deleted; corrections are reversals.
- **D2 Events.** Write events to a `chit_event` table in the same transaction as the change (outbox style) and expose a pull feed like LCM's. A Kafka publisher is an optional extension behind `CHIT_KAFKA_ENABLED=false`; if you build it, say which client library and version you used and tell me to verify it, and keep the service fully working without it.
- **D3 UI.** Operator screens go into the existing React console. There is no separate Angular app. Subscriber-facing screens are not built here; instead expose `portal` read models (by `customerId`) for the planned customer-portal back end.
- **D4 Security.** One Basic service credential (`CHIT_SERVICE_USERNAME` default `chit-service`, `CHIT_SERVICE_PASSWORD`, no usable default in production), plus `X-Actor` for people. Keep `SecurityConfig` as the single swap point for IAM, and deliver `IAM_PERMISSIONS.txt` with `chit.*` codes.
- **D5 Time.** Cycle dates, demands, late fees and defaults follow the **platform business date** read from lending-service (`GET /api/v1/business-date`, `/working-day`, `/holidays`), not the machine clock. If lending-service is unreachable, calls that need the date fail with a clear code; they never fall back to the system date. Only audit timestamps and bid timestamps use the server clock (zone Asia/Kolkata).
- **D6 Scheduling.** The batch service drives the daily work through new jobs that call the chit service with the business date (section 14). The chit service has no cron of its own by default (an optional sweep timer may exist, off by default, like `LCM_SWEEP_CRON`).
- **D7 Security for prized tickets** (deposit, guarantor, lien on deposit) is held inside the chit service. A link to LCM collateral is a later, optional phase.
- **D8 Port and names.** Service `lending-chit-service`, port 8086, schema `chit`, history table `chit_schema_history`, package `com.bank.lending.chit`, variables prefixed `CHIT_`, API root `/api/v1/chit`. Check the port is free.

### 5. Scope

In scope: group configuration and versioned rules, enrolment (multiple tickets per customer), cycle calendar, demand generation, payment collection and allocation, late fees, auction (several types), prize payout, dividends, defaults and recovery, ticket transfer and exit, pool ledger and reconciliation, closure, REST APIs, events, the integration points in section 14, operator screens, tests, docs.

Out of scope: real payment gateways (simulate channels), real KYC (reference a `customerId`), regulatory filings, tax, building the customer portal, building a customer master.

### 6. Glossary

- **Chit value / pot (P):** total amount drawn each cycle.
- **Tickets (N):** number of tickets; equals the number of cycles for a standard chit. One customer may hold several.
- **Subscription (S):** per-ticket contribution each cycle, normally `S = P / N`.
- **Foreman:** the organiser who runs the chit and earns a commission.
- **Discount (D):** the amount by which the winner's prize is reduced (the winning bid).
- **Prize (W):** `W = P - D`.
- **Commission (C):** foreman's fee, a percentage of P (`TODO-VERIFY` legal cap).
- **Dividend pool (DP):** `DP = D - C`, shared among tickets by configuration.
- **Prized / non-prized ticket:** whether the ticket has already won.
- **Gross** means subscriptions due before dividend adjustments; **cash** means money actually received.

### 7. Configuration (all rules data-driven, never hard-coded)

| Parameter | Notes |
|---|---|
| name, code | unique |
| foreman_ref | text reference to the organiser; no foreman master |
| chit_value P, ticket_count N, cycle_count | cycle_count defaults to N |
| subscription S | default P/N; arithmetic validated |
| frequency | MONTHLY default; WEEKLY, FORTNIGHTLY allowed |
| start_date, instalment_due_day, auction_day | business-day aware (holidays from lending-service), Asia/Kolkata |
| commission_pct, max_commission_pct | `TODO-VERIFY` legal cap |
| min_discount_pct, max_discount_pct | of P; `TODO-VERIFY` legal limits |
| auction_type | OPEN_ASCENDING_DISCOUNT, SEALED_BID, LOTTERY, FIXED_ORDER |
| tie_break_rule | EARLIEST_BID_TIME, LOTTERY, LOWEST_TICKET_NO |
| no_bid_rule | LOTTERY, FOREMAN_DEFAULT_DISCOUNT, ROLL_OVER_CYCLE |
| dividend_beneficiaries | ALL_TICKETS or NON_PRIZED_ONLY (variants differ) |
| dividend_mode | ADJUST_NEXT_INSTALMENT, PAY_OUT, CREDIT_SUBSCRIBER_BALANCE |
| prized_subscription_rule | FULL_SUBSCRIPTION, or a configurable schedule after winning |
| last_cycle_commission_rule | NONE, or DEDUCT_FROM_PRIZE (the last cycle has no discount to take commission from) `TODO-VERIFY` |
| payout_liquidity_rule | BLOCK_UNTIL_FUNDED, FOREMAN_FLOATS, PAY_AVAILABLE_AND_BALANCE_LATER (see section 10) |
| grace_days, late_fee_rule | FLAT or PERCENT_PER_DAY, with a cap |
| default_threshold | missed instalments or days that mark a ticket DEFAULTED |
| auction_quorum_rule | ALL_PAID, MIN_COLLECTION_PCT, FOREMAN_COVERS_SHORTFALL |
| security_rule | NONE, GUARANTOR, DEPOSIT, LIEN_ON_DEPOSIT (applies to prized tickets) |
| payout_hold_pct | optional retention until security and arrears are cleared |
| enrolment_fee, registration_fee | optional |
| foreman_guarantee_mode | who covers a shortfall so the winner is paid on time |
| allocation_order | default: late fees, oldest arrears, current instalment, advance |

**Validation (reject with a specific error code each):** `S x N != P`; commission above the cap; discount bounds inconsistent; **`min_discount_pct` below `commission_pct`** (it would make the dividend pool negative); auction day before due day; unsupported enum combination; a rule change on an ACTIVE group. Rules are stored as versions and the snapshot is frozen at activation.

### 8. State machines (transitions explicit, validated, audited; illegal jumps rejected)

- **Group:** `DRAFT -> OPEN_FOR_ENROLMENT -> FULL -> ACTIVE -> CLOSING -> CLOSED`, and `CANCELLED` (only before ACTIVE, with a refund of fees and deposits). Activation needs all tickets enrolled, security collected where required, and the calendar generated.
- **Ticket:** `ENROLLED -> ACTIVE -> PRIZED -> COMPLETED`, side states `DEFAULTED`, `TRANSFERRED`, `EXITED`.
- **Cycle:** `SCHEDULED -> COLLECTION_OPEN -> AUCTION_OPEN -> AUCTION_CLOSED -> PAYOUT_PENDING -> PAYOUT_DONE -> SETTLED`. A cycle under ROLL_OVER_CYCLE returns to `COLLECTION_OPEN` with the calendar extended; show how the calendar and the "every ticket wins once" rule stay consistent.

### 9. Functional requirements

**9.1 Creation and enrolment**

1. Create a group in DRAFT; validate; save versioned rules.
2. Open enrolment. Subscribers enrol with an existing `customerId`; a customer may hold several tickets; ticket numbers are assigned in order.
3. Collect security and fees at enrolment per `security_rule`; post them to the ledger.
4. Activate when full: freeze the rules, generate the whole cycle calendar (due dates and auction dates on business days), create the group's ledger accounts, emit `ChitGroupActivated`.
5. Cancel before activation with a defined refund waterfall.

**9.2 Periodic payments (each cycle)**

1. **Demand generation** at COLLECTION_OPEN: one demand per active ticket for the gross subscription. Any dividend carried forward is applied as an adjustment against the demand (not by changing the demand), so the gross and the net are both visible. Arrears and late fees are carried separately.
2. **Payment intake** on simulated channels (UPI, bank transfer, cash at foreman, auto-debit mandate). Every payment has an idempotency key.
3. **Allocation** follows `allocation_order`. Partial and advance payments are supported.
4. **Late fees** accrue per `late_fee_rule` after `grace_days`; offer a daily accrual preview.
5. **Bounce or reversal** reverses the allocation and re-opens the demand.
6. **Receipts** per payment; ticket statement and group statement endpoints.
7. **Default detection** (batch job) flags tickets that meet `default_threshold` and emits `TicketDefaulted`.
8. **Auction gate:** the auction opens only when `auction_quorum_rule` is met; otherwise the cycle is blocked, or the foreman guarantee covers the gap and that exposure is recorded as a receivable from the defaulting ticket.

**9.3 Auction and prize payout**

1. **Eligibility:** non-prized, non-defaulted tickets (or defaulted ones that clear arrears first, per configuration).
2. **Bids** are captured by the operator on a member's behalf now (self-service comes with the portal). Validate bounds; for OPEN_ASCENDING each bid must beat the current highest; for SEALED_BID reveal at close; store the server timestamp.
3. **Winner:** highest discount wins, then `tie_break_rule`. LOTTERY and FIXED_ORDER use a recorded, **reproducible** draw: store the seed, the candidate list and the result so the draw can be replayed.
4. **No bid:** apply `no_bid_rule`.
5. **Last cycle:** the one remaining non-prized ticket gets the full pot with no auction and no discount; apply `last_cycle_commission_rule`.
6. **Computation** (rounding per section 10): `D`, `W = P - D`, `C = commission_pct x P`, `DP = D - C`, per-ticket dividend by the beneficiary rule, using the largest-remainder method so shares add up exactly to `DP`. `DP` must never be negative; if it would be, reject the bid.
7. **Payout pre-conditions:** security verified, arrears cleared, maker-checker approval, `payout_hold_pct` applied, and the **liquidity check** (section 10).
8. **Disbursement:** a simulated payout instruction to the winner's registered account reference; post the journals; mark the ticket PRIZED; record the cycle winner.
9. **After the prize:** the winner keeps paying per `prized_subscription_rule`; the remaining obligation is tracked as a forward liability.
10. Emit `AuctionClosed`, `PrizeDisbursed`, `DividendDeclared`.

**9.4 Defaults and recovery**

- Non-prized defaulter: loses dividend for the defaulted cycles (configurable); remaining contributions are settled at closure by rule.
- Prized defaulter: recover against security (deposit, lien, guarantor); record the shortfall; `foreman_guarantee_mode` decides who bears the loss.
- Journal every recovery step; write-off needs approval.

**9.5 Transfer and exit**

- Transfer a ticket to another customer (not while arrears exist), optional fee, full history kept.
- Voluntary exit only for non-prized tickets, with a settlement rule (refund of paid contributions less a penalty, or a replacement subscriber). A prized ticket cannot exit before it settles its remaining obligations.

**9.6 Closure**

1. **Pre-checks:** every ticket has won exactly once or is explicitly resolved by exit or default; every cycle SETTLED; no open demands, payouts or disputes.
2. **Final settlement:** per ticket, total paid, dividends received, prize received; release or return securities and held-back amounts; settle residual balances; sweep commission and late-fee income to the foreman.
3. **Reconciliation:** the pool accounts net to zero; inflows equal outflows plus recognised income; produce a ticket-wise and cycle-wise closure report.
4. Move the group to CLOSED and make it read-only except for audit reads. Unclaimed balances go to a holding account with an ageing report.
5. Early termination (foreman approval plus a rule-based quorum) with a refund waterfall.

### 10. Money rules and invariants

- `NUMERIC(18,2)` and `BigDecimal`; never floating point. One rounding mode (state which), used everywhere. Shares are allocated with the **largest-remainder method**, leftover paise assigned deterministically by ticket number.
- Put all arithmetic (dividend shares, prize, commission, allocation, late fee, draw) in **pure classes with no Spring dependency**, so they can be tested without a database.
- **Gross identity (every cycle):** `sum of subscriptions due = P` and `P = W + C + DP`; the dividend shares sum to `DP` exactly.
- **Cash identity:** cash collected in a cycle = gross due minus dividends applied as adjustments in that cycle (under `ADJUST_NEXT_INSTALMENT` this is `P - DP(previous cycle)` when everyone pays), plus advances, minus unpaid amounts. v1 of this spec wrongly treated cash as `P`. State both identities in code and tests.
- **Payout liquidity:** before a prize is disbursed, `pool cash available >= W`. If not (for example the winning discount of this cycle is smaller than the previous one, so `W` exceeds the cash that was collected), apply `payout_liquidity_rule` and record any foreman float as a receivable from the pool, settled when later cycles bring cash in.
- **Pool balance is zero at closure;** group-level trial balance debits equal credits at all times.

### 11. Data model (schema `chit`; adapt to the LCM conventions)

`chit_group`, `chit_group_rules_version`, `chit_ticket`, `chit_subscriber_link` (customer to ticket, with history), `chit_cycle`, `chit_demand`, `chit_payment`, `chit_payment_allocation`, `chit_late_fee`, `chit_auction`, `chit_bid`, `chit_draw_record`, `chit_prize_payout`, `chit_dividend`, `chit_security`, `chit_default_case`, `chit_ticket_transfer`, `chit_closure_report`, `chit_audit_event`, plus the platform-style support tables: `chit_event` (feed), `chit_idempotency`, `chit_journal` and `chit_journal_line` (append-only), `chit_ledger_account`.

Every table: surrogate key, business key, version column for optimistic locking, created and updated audit columns, and constraints that enforce invariants (one winner per cycle, one prize per ticket, non-negative amounts, journal lines balance per journal, no update or delete on journal tables via trigger or rule). Flyway migrations `V1__chit_core.sql` and so on, running in schema `chit`. No foreign key leaves the schema.

### 12. Ledger (double entry, own journal)

Per group: `POOL_CASH`, `SECURITY_CASH`, `FOREMAN_CASH`, `CHIT_POOL` (gross subscriptions owed to the pool), `SUBSCRIBER_RECEIVABLE` (per ticket), `PRIZE_PAYABLE`, `DIVIDEND_PAYABLE` (per ticket), `COMMISSION_INCOME`, `LATE_FEE_INCOME`, `ENROLMENT_FEE_INCOME`, `SECURITY_DEPOSIT_LIABILITY` (per ticket), `FOREMAN_GUARANTEE_RECEIVABLE`, `UNCLAIMED`.

**Proposed core templates (review in Phase 2 and ask me to approve before building the rest):**

| Event | Debit | Credit |
|---|---|---|
| Demand raised (gross S) | SUBSCRIBER_RECEIVABLE[t] | CHIT_POOL |
| Payment received | POOL_CASH | SUBSCRIBER_RECEIVABLE[t] |
| Payment reversed | SUBSCRIBER_RECEIVABLE[t] | POOL_CASH |
| Late fee accrued | SUBSCRIBER_RECEIVABLE[t] | LATE_FEE_INCOME |
| Auction settled | CHIT_POOL (P) | PRIZE_PAYABLE (W), COMMISSION_INCOME (C), DIVIDEND_PAYABLE[t] (d_t; sum = DP) |
| Dividend applied to next demand | DIVIDEND_PAYABLE[t] | SUBSCRIBER_RECEIVABLE[t] |
| Dividend paid out | DIVIDEND_PAYABLE[t] | POOL_CASH |
| Prize disbursed | PRIZE_PAYABLE | POOL_CASH |
| Security deposit received | SECURITY_CASH | SECURITY_DEPOSIT_LIABILITY[t] |
| Enrolment fee | FOREMAN_CASH | ENROLMENT_FEE_INCOME |
| Closure sweep of commission and late-fee income | FOREMAN_CASH | POOL_CASH |

You must also propose, for review: default and recovery, foreman guarantee and float, write-off, exit refund, ticket transfer fee, cancellation refund. Every posting is **idempotent**, carries its source event and business date, and is reversed (never edited) to correct. A group trial balance endpoint and a reconciliation check (journals against demands, payments, auctions and payouts) are required.

### 13. APIs, errors, events

- **Root:** `/api/v1/chit`. REST, paginated lists, every endpoint documented in OpenAPI with its IAM permission code in the summary (as LCM does). Groups (create, update, open enrolment, activate, cancel), tickets (list, enrol, transfer, exit), cycle calendar, demands, payments (post, reverse, receipt), auctions (open, bid, close, declare winner), payouts (approve, disburse), default cases, security, closure (pre-check, run), statements and reports, `trial-balance`, `events` (pull feed), `portal` (read models by `customerId`: my chits, my ticket statement, next instalment and net amount, eligibility to bid, prize and dividend history), `maintenance` (status, clear-data), and the endpoints the batch jobs call (demand run, late-fee run, default run) which take the business date and are idempotent per date.
- **Idempotency:** every state-changing call takes `Idempotency-Key`; same key and body returns the first result with `replayed=true`; same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`.
- **Errors:** `{status, code, message}`; callers switch on `code` (examples: `CONFIG_INVALID`, `GROUP_NOT_ACTIVE`, `ILLEGAL_TRANSITION`, `AUCTION_GATE_CLOSED`, `BID_OUT_OF_BOUNDS`, `PAYOUT_LIQUIDITY_SHORT`, `MAKER_CHECKER_VIOLATION`, `BUSINESS_DATE_UNAVAILABLE`).
- **Actor:** people's calls send `X-Actor`; maker-checker compares actors and is switchable with `CHIT_MAKER_CHECKER_ENFORCE`.
- **Events:** `ChitGroupActivated`, `DemandRaised`, `PaymentReceived`, `PaymentReversed`, `LateFeeAccrued`, `TicketDefaulted`, `AuctionOpened`, `BidPlaced`, `AuctionClosed`, `PrizeDisbursed`, `DividendDeclared`, `TicketTransferred`, `GroupClosed`, written in the same transaction as the change and served by the pull feed.

### 14. Integration into the existing platform (the only changes allowed outside the new module)

1. **Root `pom.xml`:** add the `lending-chit-service` module.
2. **`render.yaml`:** a web service block like `lending-lcm-service` (Docker, free plan, `/actuator/health`, DB variables from `lending-db`, `CHIT_DB_SCHEMA`, generated `CHIT_SERVICE_PASSWORD`, `CHIT_RESET_ENABLED` true for the sandbox only, `LENDING_SERVICE_URL`), and the wiring so that the admin and batch services receive its URL and password. **`docker-compose.yml`:** the same service.
3. **Batch service:** a migration (next number after `V7`) seeding daily jobs, and the job classes, in the style of `DemandRaiseBatchJob`: `CHIT_DEMAND_RAISE_DAILY`, `CHIT_LATE_FEE_DAILY`, `CHIT_DEFAULT_CHECK_DAILY`, optionally `CHIT_AUCTION_REMINDER_DAILY`; choose priorities that run after the loan demand job and before reconciliation; add a `ChitServiceClient` (Basic auth, long timeout for a sleeping service); they are restartable, idempotent per business date, and catch up missed dates. Update the batch README job table.
4. **Admin console (backend):** `ChitClient` like `LcmClient`; `/api/admin/chit/**` controllers forwarding to the service; **a new role** `CHIT_OPS` with its own login (`CHIT_OPS_PASSWORD`, optional like the other operations logins) and rules in `AccessPolicy` (every signed-in user may read; only `CHIT_OPS` and `ADMIN` may change); extend `AccessPolicyTest`.
5. **Admin console (React):** a "Chit funds" menu section with: group wizard (live validation and a schedule preview), groups and group detail, enrolment board, cycle dashboard (due versus collected, defaulters), auction console, payout approval queue, defaults and recovery, closure wizard with the reconciliation report, ticket statement, and the group trial balance. Follow the existing page, API and table patterns; keep `tsc` and the Vite build passing.
6. **Maintenance:** add `GET /api/v1/chit/maintenance/status` and `POST /api/v1/chit/maintenance/clear-data` (body `{"confirm":"RESET"}`, off unless `CHIT_RESET_ENABLED=true`, empties every chit table and restarts ids, keeps nothing but the schema history), and extend the admin Maintenance service and screen: pre-check the chit service, then clear it in the same ordered chain as LCM and batch, and refuse with a clear message if it is switched off, busy or unreachable, with nothing deleted. Show its row counts. A failure after lending is cleared must tell the user to press Clear again, as it does today.
7. **E2E suite:** a chit scenario in `lending-e2e-test-service` (create a small group, enrol, activate, run cycles through auction and payout, close) with checks for the invariants, and make `Teardown` clear it. The E2E console option and report show the new scenario.
8. **IAM:** `lending-chit-service/IAM_PERMISSIONS.txt`.
9. **Docs:** `lending-chit-service/README.md` (variables table, API summary, rules), `lending-chit-service/INTEGRATION_SNIPPETS.md` for any shared-file edit you are not allowed to make directly, root `README.md` and `CHANGELOG.md` entries, a section in `docs/CLOUD_DEPLOYMENT.md`, and new rows in the demo runbook for start-up order, the chit login and troubleshooting.

Do **not** change lending-service, LCM or IAM unless you find a blocker; if you do, stop and tell me first.

### 15. Operator screens: behaviour that matters

Show every amount in INR with Indian digit grouping. Destructive or money-moving actions ask for confirmation and name the business date. After any call that times out, resolve the real state before allowing a retry. Maker-checker items show who proposed and who may approve. Never show a button the logged-in role cannot use.

### 16. Non-functional requirements

Maker-checker on payout, write-off, transfer and early termination. Full audit trail for every state change and config change. Concurrency: auction close and payout are safe under concurrent requests (row locks and unique constraints), so two simultaneous closes produce one winner. Observability: structured logs carrying group, cycle and ticket ids. Role-based access (foreman, auditor, subscriber) is enforced at the portal read models by `customerId` and, later, by IAM.

### 17. Worked examples (test fixtures; figures illustrative)

**A. Even dividend.** P = 1,00,000, N = 20, S = 5,000, commission 5% of P, ALL_TICKETS, ADJUST_NEXT_INSTALMENT. Cycle 1: gross 20 x 5,000 = 1,00,000; D = 20,000, so W = 80,000, C = 5,000, DP = 15,000, 750 per ticket. Next instalment per ticket is 5,000 - 750 = 4,250, so cycle 2 cash is 85,000 if all pay. Check: 80,000 + 5,000 + 15,000 = 1,00,000.

**B. Uneven dividend (proves the rounding rule).** Same group, but DP = 15,000.07. In paise that is 1,500,007 over 20 tickets, a base share of 75,000 paise (750.00) and 7 paise left over; tickets 1 to 7 get 750.01 and tickets 8 to 20 get 750.00. Check: 7 x 750.01 + 13 x 750.00 = 15,000.07.

**C. Liquidity gap.** Cycle 2 cash is 85,000 (fixture A). If the winning discount is only D = 10,000, then W = 90,000 and the pool is 5,000 short. Show the outcome under each `payout_liquidity_rule`.

**D. Last cycle.** One non-prized ticket remains; it receives the full 1,00,000 with D = 0. Show the result under each `last_cycle_commission_rule` and that the pool still nets to zero at closure.

**E. Negative dividend guard.** `min_discount_pct` = 3% with `commission_pct` = 5%: the group is rejected at configuration time.

### 18. Delivery phases (stop after each)

0. **Discovery note** (section 2.1): assumptions, open questions, package structure, mismatches with the repo, and the proposed journal templates for the remaining events. Wait for my go-ahead.
1. **Domain and pure calculation code:** enums, state machines, rules validation, the money, dividend, allocation, draw and late-fee classes, with unit tests (plain JUnit, no Spring).
2. **Service skeleton in the platform:** new module, `pom.xml`, Dockerfile, `application.yml`, security, error handler, idempotency, event feed, maintenance, Flyway `V1`, JPA entities, ledger accounts and the reviewed journal templates; root pom, `render.yaml`, `docker-compose.yml` edits; service starts and passes health.
3. **Enrolment, activation, calendar, security and fees** (business date and holidays from lending-service).
4. **Demand, payments, allocation, late fees, default detection** and the three batch jobs with the batch migration and client.
5. **Auction types, winner determination, payout, dividends, last-cycle logic, liquidity rule.**
6. **Defaults and recovery, transfer and exit.**
7. **Closure, reconciliation, reports, portal read models.**
8. **Admin console:** `ChitClient`, controllers, `CHIT_OPS` role and policy test, React screens, Maintenance chain.
9. **E2E scenario, docs, runbook rows, CHANGELOG, final review against the acceptance tests.**
10. *Optional:* Kafka publisher behind a flag; LCM collateral link for prized-ticket security.

### 19. Acceptance tests (JUnit 5; Testcontainers with PostgreSQL where a database is needed)

- Full happy path for a 5-ticket chit: activation, all cycles, closure, pool balance zero, every ticket wins exactly once.
- Property-style tests (seeded random, fixed seed so failures replay; state which library you used and its version, or use plain parameterised JUnit) for the gross identity, the cash identity and exact dividend allocation.
- Fixtures A to E above.
- Partial, advance, late and bounced payments with the allocation order verified.
- Tie-break, no-bid and lottery reproducibility (replaying the stored seed gives the same winner); last-cycle behaviour.
- Prized-ticket default with security recovery under each `foreman_guarantee_mode`.
- Duplicate payment and duplicate event submissions are idempotent; same key with a different body is rejected.
- Concurrent auction-close attempts produce one winner.
- Illegal state transitions are rejected; closure refuses to run with open demands or unsettled cycles.
- A batch job run twice for the same business date, and after a skipped date, produces no duplicates and catches up.
- Maintenance: refuses when switched off, busy or unreachable and deletes nothing; clears everything and restarts ids when allowed; the admin chain reports a partial failure correctly.
- Admin `AccessPolicy` tests for `CHIT_OPS`; `tsc` and the Vite build pass.

### 20. `TODO-VERIFY` register (keep this list in the README and keep every item configurable)

1. Legal cap on foreman commission, and whether it applies on chit value or on the discount.
2. Legal limits on the discount (minimum and maximum) and on the lowest accepted bid.
3. Who shares the dividend (all tickets or non-prized only) and how a prized ticket's subscription changes after it wins.
4. Whether the foreman may bid or hold tickets, and the rule for the foreman's own default.
5. How the last cycle's commission is taken.
6. Security required from a prized subscriber, and the foreman's duty to pay the winner on time.
7. Registration, fee and record-keeping requirements.
8. The rule for a chit that lacks a quorum, a roll-over, or an early termination.

Verify these against the Chit Funds Act and your state's rules before treating any of this as compliant.
