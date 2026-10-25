# chitfund-service: build status

Chit fund microservice, now a standalone project (`chitfund`) with its own batch service and admin console. Built in phases from `docs/Chit_Fund_Specification_Prompt_v2.md`.
Last updated 25 Oct 2026. **Phases 1 to 6 of 10 are built, and the chit fund has been split out of the lending platform (see "The split from lending" below). Phases 7 to 10 are not started.** Phases 1 to 4 were written inside the lending repository as `lending-chit-service`; this document keeps those sections as written, so wording such as "lending-service" or "lending-batch-service" in Phases 0 to 4 describes how it was built then. Where it differs from today, "The split from lending" is the current truth.

## The split from lending

The chit fund no longer needs the lending platform. What changed:

| Before (Phases 1 to 4) | Now |
|---|---|
| Business date and working-day calendar read from `lending-service` (`platform/LendingPlatformClient`) | Owned here: `businessdate/` (`BusinessDateService`, `BusinessDateSource`, `WorkingDays`), migration `V5__chit_business_date.sql` (`chit_business_date`, `chit_holiday`). Mode `MANAGED` (stored, moved by end of day) or `SYSTEM` (wall clock). Weekend from `CHIT_WEEKEND_DAYS`, holidays from the table |
| `BUSINESS_DATE_UNAVAILABLE` (503) when lending-service was asleep | Gone: the date is a local read, so nothing waits for another service. `BusinessDateGuard` compares the caller's date with this service's own |
| Three chit jobs lived in `lending-batch-service`, seeded switched off | `chitfund-batch-service` (own repository module): same three jobs, switched on, plus end of day that starts, runs the daily jobs in order and advances the business date |
| Chit screens inside `lending-admin-service` | `chitfund-admin-service`: chit screens, business date and holidays, batch jobs, end of day, maintenance; own logins `ADMIN`, `CHIT_OPS`, `BATCH_OPS` |
| One shared PostgreSQL database with the lending schemas | Its own database (default `chitfund`); schemas `chit` (this service) and `batch` (batch service). The old `chit` schema in the lending database is left alone until you decide to migrate or drop it |
| Maintenance reset also cleared lending data | Clears chit data and, through the admin console, the batch job history; the business date goes back to today |

Not changed: the API root `/api/v1/chit`, the ledger, the rules, and every migration V1 to V4. Customers are still a `customerId` number that is not validated against anything.

### How the split was checked, and what was not

- **Pure code: checked.** 194 unit tests pass under the stand-in runner in this module (the business date rules, the guard, the calendar, and the earlier ones); 38 in the batch module; 43 in the admin module.
- **Database: checked.** V1 to V5 apply on PostgreSQL 16 and `scripts/verify_schema.sql` runs 57 checks, all as expected. A harness ran the business date service against it (36 checks: MANAGED and SYSTEM, start and advance and their repeats, mismatch, backwards set, holidays, reset back to today), and the Phase 3 and 4 harness was re-run (204 checks) with the new date source.
- **User interface: checked.** `tsc --noEmit` and `vite build` pass; a browser run against a mock service exercised every screen and the three role menus.
- **Not done:** a real `mvn` build of any of the three modules, Spring startup, JSON binding, Flyway through Spring, `ChitApplicationIT` (needs Docker), the Docker builds, the batch framework running on real Spring Batch and Quartz, the admin `SecurityConfig`, and any deployment to Render. The first `mvn test` is where a stub-versus-real mismatch would show.


## Phase 0: discovery note

### Assumptions

1. Separate Maven project like `lending-lcm-service`: Java 17, Spring Boot 3.3.2, schema `chit`, history table `chit_schema_history`, port 8086, API root `/api/v1/chit`, variables prefixed `CHIT_`.
2. Own double-entry journal (no reuse of the lending accounting engine). Events by table plus pull feed; Kafka only as an optional extension.
3. Operator screens go into the existing React admin console; subscriber screens wait for the customer portal.
4. Cycle dates follow the platform business date from lending-service. A customer is a `customerId` number.
5. Rounding is HALF_UP at 2 decimals. Percentages are written as 5 for 5%. Late fees count calendar days.
6. Rules can be edited only while a group is DRAFT or OPEN_FOR_ENROLMENT; they are frozen from FULL onward.
7. A standard chit has `cycleCount = ticketCount`, so every ticket wins exactly once. The validator enforces it.
8. Group `FULL` may go back to `OPEN_FOR_ENROLMENT` when a ticket is withdrawn before activation. (Built in Phase 3.)
9. Ticket `TRANSFERRED` is a transient state while a transfer awaits approval; the ticket then returns to ACTIVE or PRIZED with a new subscriber link. A cured defaulter returns to PRIZED if it had won, otherwise ACTIVE.
10. `ROLL_OVER_CYCLE` sends an AUCTION_OPEN cycle back to COLLECTION_OPEN with the calendar extended.
11. No-bid handling: LOTTERY draws a winner at the minimum discount; FOREMAN_DEFAULT_DISCOUNT draws a winner at the foreman's default discount (needs `foremanDefaultDiscountPct`).

### Open questions for you

1. Is `TRANSFERRED` as a transient state right, or do you want the old ticket record closed and a new one opened?
2. With `NON_PRIZED_ONLY`, does the winner of the current cycle share that cycle's dividend? (The calculator takes the list of beneficiaries from the caller, so either works; the service must choose.)
3. Exit of a non-prized ticket: refund paid contributions less penalty, or require a replacement subscriber? **Answered 25 Oct: find a replacement subscriber; no refund** (built in Phase 6).
4. When the pool bears the loss of a write-off, who absorbs it (reduce later dividends, or the foreman)? **Answered 25 Oct: for now the foreman** (built in Phase 6).
5. Late fees on calendar days is assumed. Business days instead?
6. Is a strict `cycleCount = ticketCount` acceptable, or do you want shorter or longer terms?

### Mismatches between the prompt and the repo

- The prompt's source description said Angular; the admin console is React 18 / Vite / TypeScript. Prompt v2 already follows the repo.
- Port 8083 looks unused; 8086 is used here. Check on your side.
- The root `pom.xml` listed five modules; this one is added in Phase 2 (after `lending-lcm-service`).
- **JDBC, not JPA.** The prompt says JPA entities. `lending-lcm-service` uses `JdbcTemplate` and plain SQL, so this service does too: the same pattern, no second persistence style to maintain, and the SQL is visible and testable against Postgres directly. This is a deliberate deviation from the prompt; say so if you want JPA instead.
- **Events.** The platform does have a broker in `docker-compose.yml` (Kafka) and an optional Kafka flag in the batch service, but `render.yaml` has none and the batch service runs with it off. So events are rows in `chit_event` read through a pull feed, as in LCM. A Kafka publisher can be added later without changing the feed.
- `docker-compose.yml` does not contain `lending-lcm-service` or `lending-iam-service` today. Phase 2 adds only the chit service to it, as the prompt asks; the LCM and IAM gaps are left alone.

### Proposed package structure

```
com.chitfund.service
  common/        Money, ChitException, ConfigInvalidException, RuleViolation          (Phase 1, built)
  domain/        enums and statuses                                                   (Phase 1, built)
  domain/rules/  ChitRules, LateFeeRule, DefaultThreshold, RulesValidator, RulesEditPolicy   (Phase 1, built)
  domain/state/  StateMachine, Lifecycles                                             (Phase 1, built)
  calc/          LargestRemainder, AuctionSettlementCalculator, AllocationEngine,
                 LateFeeCalculator, Draw                                              (Phase 1, built)
  config/        SecurityConfig, ApiExceptionHandler, ChitSettings, ClockConfig       (Phase 2)
  group/ ticket/ cycle/ demand/ payment/ auction/ payout/ dividend/ security/ default/ transfer/ closure/   (Phases 3 to 7)
  ledger/        journal, accounts, templates, trial balance, reconciliation          (Phase 2 onward)
  events/        EventFeed and the event writer                                       (Phase 2)
  maintenance/   status and clear-data                                                (Phase 2)
  portal/        customer-scoped read models                                          (Phase 7)
  platform/      LendingPlatformClient (business date, working-day calendar)         (Phase 3, built)
  calendar/      CycleCalendarGenerator                                               (Phase 3, built)
  enrolment/     quote, enrol, withdraw, verify, activate, cancel                     (Phase 3, built)
  collection/    demands, payments, late fees, defaults, statements, batch endpoints  (Phase 4, built)
```

### Journal templates still to review (the core ones are in section 12 of the prompt)

New accounts these need: `FOREMAN_FLOAT_PAYABLE`, `TRANSFER_FEE_INCOME`, `WRITE_OFF_LOSS`, `GUARANTEE_LOSS_EXPENSE`.

| Event | Debit | Credit |
|---|---|---|
| Security applied to a prized defaulter's arrears | SECURITY_DEPOSIT_LIABILITY[t]; then POOL_CASH | SUBSCRIBER_RECEIVABLE[t]; then SECURITY_CASH |
| Foreman covers a shortfall (pays the pool) | POOL_CASH; then FOREMAN_GUARANTEE_RECEIVABLE[t] | FOREMAN_CASH; then SUBSCRIBER_RECEIVABLE[t] |
| Ticket repays a guaranteed amount | FOREMAN_CASH | FOREMAN_GUARANTEE_RECEIVABLE[t] |
| Guarantee written off, foreman bears the loss | GUARANTEE_LOSS_EXPENSE | FOREMAN_GUARANTEE_RECEIVABLE[t] |
| Receivable written off, pool bears the loss (needs open question 4) | WRITE_OFF_LOSS | SUBSCRIBER_RECEIVABLE[t] |
| Foreman floats a liquidity gap | POOL_CASH | FOREMAN_FLOAT_PAYABLE |
| Float repaid from later collections | FOREMAN_FLOAT_PAYABLE | POOL_CASH |
| Ticket transfer fee | FOREMAN_CASH | TRANSFER_FEE_INCOME |
| Cancellation refund of fees | ENROLMENT_FEE_INCOME | FOREMAN_CASH |
| Cancellation refund of a deposit | SECURITY_DEPOSIT_LIABILITY[t] | SECURITY_CASH |
| Exit refund | waits for open question 3 | |

## Phase 1: what is built

Pure Java, no Spring, no database. 33 main classes and 8 test classes with 81 tests, all under `chitfund-service/`.

| Area | Classes | What it does |
|---|---|---|
| Money | `common/Money` | Scale, percentage, paise conversion; one rounding mode |
| Errors | `common/ChitException`, `ConfigInvalidException`, `RuleViolation` | Stable error codes; no HTTP status in this layer |
| Statuses and rules enums | `domain/*` (16 enums) | Group, ticket and cycle statuses; every configurable choice from the prompt |
| Rules | `domain/rules/ChitRules` (+ builder), `LateFeeRule`, `DefaultThreshold` | The whole configuration as data |
| Validation | `RulesValidator`, `RulesEditPolicy` | 18 error codes, all reported together, including `DISCOUNT_BELOW_COMMISSION` |
| State machines | `domain/state/StateMachine`, `Lifecycles` | Explicit transition tables for group, ticket and cycle; illegal moves refused with `ILLEGAL_TRANSITION` |
| Dividend split | `calc/LargestRemainder` | Exact, deterministic split in paise (ties go to the lower ticket number) |
| Auction money | `calc/AuctionSettlementCalculator`, `AuctionSettlement` | Prize, commission, dividend pool and shares; last cycle with both commission rules; negative pool refused |
| Payments | `calc/AllocationEngine` | Late fees, then oldest arrears, then the current instalment, then advance; custom order; partial payments |
| Late fees | `calc/LateFeeCalculator` | Flat or percent per day, grace, caps, safe to rerun and to catch up |
| Draw | `calc/Draw` | Reproducible winner or full order from a seed (SHA-256 based), with an audit record |

Fixtures from section 17 of the prompt are tests: A (even dividend), B (15,000.07 over 20 tickets: tickets 1 to 7 get 750.01), D (last cycle, both commission rules) and E (minimum discount below the commission is rejected). Fixture C (liquidity gap) belongs to Phase 5.

Not yet built, by design: winner selection and bid validation, the liquidity check, the calendar generator, and everything with a database or HTTP.

## Phase 1: how it was checked, and what was not

- **Done:** every main and test class compiles with `javac` (Java 21 here, source is Java 17 compatible, no preview features). The 81 tests were run with a small stand-in for the JUnit launcher, because Maven Central cannot be reached from the build environment. All passed. A deliberate break of the tie-break rule made 3 tests fail, so the tests do catch that.
- **Not done:** a Maven build, the real JUnit 5 run, the Spring Boot parent and dependency resolution, Testcontainers (not used yet), and any code review by you.
- The tests use only `@Test` and the common `Assertions` methods, so they should run unchanged under JUnit 5. If one does not compile, tell me.



## Phase 2: what is built

The service is now a Spring Boot application: it starts, migrates its own schema, authenticates callers and answers health. No business feature yet.

| Area | What | Notes |
|---|---|---|
| Application | `ChitApplication`, `application.yml`, `Dockerfile`, `pom.xml` | Port 8086, schema `chit`, history table `chit_schema_history`, env prefix `CHIT_`. Same as LCM apart from names. |
| Security | `config/SecurityConfig` | One Basic credential for `/api/**`, JSON 401. Identical to the LCM class apart from names; the IAM swap point. |
| Errors | `config/ApiExceptionHandler`, `config/ErrorStatus` | `{timestamp,status,code,message}`; rule violations list every violation. One table maps a code to a status (400, 403, 404, 409, 422; 500 for internal-consistency codes). |
| Schema | `V1__chit_core.sql`, `V2__chit_ledger_support.sql` | 24 tables. V2 adds idempotency, event feed, audit and the ledger. |
| Ledger model | `ledger/AccountType`, `AccountRef`, `Line`, `JournalDraft`, `LedgerBalances` | A journal cannot be built unbalanced; zero lines are dropped, negative ones refused. |
| Journal templates | `ledger/JournalTemplates` | 20 templates, pure functions. The 6 core ones follow section 12 of the prompt; the rest are the reviewed list above (marked REVIEW in the code). |
| Ledger service | `LedgerService`, `LedgerRepository`, `LedgerController` | `post` (idempotent by posting key, joins the caller's transaction), `reverse` (mirror journal, once), trial balance, journal reads. |
| Idempotency | `idempotency/IdempotencyStore`, `IdempotencyCheck` | Claim a key; replay the earlier result; a different request on the same key is a 409. |
| Events | `events/EventFeed`, `common/Audit` | Pull feed with the gap-free `txid-id` cursor; audit log. |
| Maintenance | `maintenance/*` | Status and clear-data behind `CHIT_RESET_ENABLED` and the word `RESET`; one `TRUNCATE ... RESTART IDENTITY` over 24 tables. |
| Wiring | root `pom.xml`, `render.yaml`, `docker-compose.yml` | New module and service blocks. See the repo diff. |

### Database rules (checked on PostgreSQL 16)

Journals need two or more lines and debits equal to credits, checked at commit by a deferred constraint trigger. Journals and lines are append-only. A line must sit on an account of its own group. A posting key is used once. Per-ticket accounts must name a ticket, group accounts must not. Also from V1: one winner per cycle, one prize per ticket, one open subscriber link per ticket, a demand cannot be overpaid, maker is not checker (payout, transfer, write-off), a frozen rules version cannot change, `cycleCount = ticketCount` and `subscription x tickets = chit value`.

### Ledger accounts and the two decisions still open

New accounts beyond the prompt: `FOREMAN_FLOAT_PAYABLE`, `TRANSFER_FEE_INCOME`, `WRITE_OFF_LOSS`, `GUARANTEE_LOSS_EXPENSE`. Journal for the **prize hold** is new in this phase: when part of a prize is held back as security, `Dr PRIZE_PAYABLE gross, Dr SECURITY_CASH hold / Cr POOL_CASH gross, Cr SECURITY_DEPOSIT_LIABILITY[t] hold`. Please review it with the other REVIEW templates. The exit-refund journal is still not written; it waits for open question 3. Who finally absorbs a write-off (open question 4) does not change the journal, only who is charged in Phase 6.

## Phase 2: how it was checked, and what was not

- **Database: checked.** V1 and V2 apply cleanly on PostgreSQL 16 in order. `scripts/verify_schema.sql` exercises 26 rules (each refused case is attempted and must fail) and passes. Every SQL statement the ledger repository, idempotency store and event feed use was run in psql with real values. The reset's single `TRUNCATE ... RESTART IDENTITY` over all 24 tables was run with data in every table family and restarts the ids at 1.
- **Pure code: checked.** 133 tests pass under the stand-in runner (81 from Phase 1, 52 new): journal rules, all 20 templates, the error-status table, actor/hash/JSON helpers, the idempotency decision, the event cursor, the maintenance refusals, and three drift guards (Java enums against the SQL CHECK lists, the reset list against the tables the migrations create). `TwentyCycleSimulationTest` runs a full 20-ticket chit through the templates under both last-cycle commission rules and an uneven dividend; the pool and every payable net to zero after the closure sweep and the pool never goes negative. A deliberate break of the auction journal made 4 of those tests fail.
- **Spring code: compiled against stand-ins only.** Maven Central cannot be reached from the build environment, so the Spring classes were compiled against small hand-written stubs of the Spring, Swagger types they use (JdbcTemplate, annotations, ResponseEntity and so on), with the real Jackson and SLF4J jars. That proves the code is type-correct against the signatures I stubbed, not that it runs. `SecurityConfig` could not be stubbed; it is a straight copy of the LCM class (diff checked, names only).
- **Not done:** `mvn` build, the real JUnit 5 run, Spring context startup, Flyway running the migrations through Spring, the Testcontainers test `ChitApplicationIT` (written, never executed), the Docker build, the `render.yaml` and `docker-compose.yml` changes on real infrastructure, and any review by you.

If `mvn test` fails on first run, send me the output; the likeliest causes are a mismatch between my stubs and the real Spring signatures, or the IT needing Docker.

## Decisions you gave, as applied

- **Ticket transfer (your Option A).** A transferred ticket stays the same record. The holder changes through the subscriber link and history and arrears travel with it. In Phase 3 this shows up as: `chit_ticket.customer_id` is the *current holder* and every enrolment opens a subscriber link; the transfer in Phase 6 must update both in one transaction.
- **Strict term (your "yes strict").** Cycles must equal tickets, so every ticket wins exactly once. The validator and a database CHECK already enforce it; the calendar generator builds exactly one cycle per ticket.

## Phase 3: what is built

Enrolment, activation, the calendar, security and fees. 25 new main classes and edits to 7 existing ones, one new migration (V3), 43 tests added (176 in all).

| Area | What | Notes |
|---|---|---|
| Schema | `V3__chit_enrolment.sql` | Seat numbers can be reused after a withdrawal (partial unique index on live tickets); `chit_fee_receipt`; security rows carry received date, verifier, reference and return date; cancel and activation details on the group. 25 tables now. |
| Rules | `securityDepositPct` added to `ChitRules` and validated; `RulesDefaults` fills optional rules | Only the required fields have to be sent. `subscription` defaults to value / tickets when that divides exactly. |
| Calendar | `calendar/CycleCalendarGenerator` | Monthly (day of month; a day past month end means the last day) and weekly or fortnightly (weekday). A non-working day moves *forward* (at most 14 days). Refused with `CALENDAR_OVERLAP` if an auction would not be before the next due date. |
| lending-service client | `platform/LendingPlatformClient` | Reads `GET /api/v1/business-date` and `/working-day?date=`. Read timeout 90 s (a sleeping free-plan lending-service), one retry on 502/503/504 or an I/O error, then `503 BUSINESS_DATE_UNAVAILABLE`; nothing is changed. Optional Basic credential. |
| Groups | `group/*` | Create (DRAFT), list, get, rules versions (new version on each change), open enrolment, cycle list, preview. |
| Enrolment | `enrolment/*` | Quote, enrol (several tickets per customer), withdraw, verify security, cancel, activation check, activate. |
| Ledger | `LedgerService.ensureGroupAccounts`; fee and deposit journals | Posting keys: `ENROL_FEE:<ticketId>:ENROLMENT`, `ENROL_FEE:<ticketId>:REGISTRATION`, `SECURITY_RECV:<ticketId>`, refunds `ENROL_FEE_REFUND:...`, `SEC_REFUND:<ticketId>`. |
| Events | `ChitGroupCreated`, `ChitRulesUpdated`, `EnrolmentOpened`, `TicketEnrolled`, `ChitGroupFull`, `TicketWithdrawn`, `SecurityVerified`, `ChitGroupActivated`, `ChitGroupCancelled` | In `chit_event`, readable through the feed. |
| Errors | `BUSINESS_DATE_UNAVAILABLE` 503; 409 for `GROUP_CODE_EXISTS`, `GROUP_FULL`, `ENROLMENT_NOT_OPEN`, `WITHDRAWAL_NOT_ALLOWED`, `RULES_LOCKED_BY_ENROLMENT`, `ACTIVATION_BLOCKED`; 403 `MAKER_CHECKER_VIOLATION` | Others (amount mismatch, guarantor, calendar) are 422. |
| Permissions | 7 new codes in `IAM_PERMISSIONS.txt` | `chit.group.*` and `chit.enrolment.*`. |

### How activation works

1. A repeated request (same key) is answered first, without touching lending-service.
2. The group, rules and tickets are read and every blocker checked (`GROUP_NOT_FULL`, `TICKETS_INCOMPLETE`, `SECURITY_MISSING`, `SECURITY_NOT_VERIFIED`). Only then is the whole calendar built from lending-service, so no lock or transaction is held during those calls.
3. One transaction takes the group's row lock, refuses if the group changed since step 2 (`DATA_CONFLICT`, nothing written, repeat the call), re-checks everything including `FIRST_DUE_DATE_PASSED` against the business date, then freezes the rules version, sets tickets ACTIVE, inserts the cycles as SCHEDULED, creates the full chart of ledger accounts, sets the group ACTIVE and writes the event.

### New assumptions for you to confirm or change

1. **Security is collected at enrolment, from every ticket.** `DEPOSIT` = `securityDepositPct` % of the chit value, received in cash (Dr SECURITY_CASH / Cr SECURITY_DEPOSIT_LIABILITY). `LIEN_ON_DEPOSIT` = the same figure but **no cash moves**: the subscriber's own deposit is marked and its reference recorded. `GUARANTOR` = a different customer is named; no money. The legal limits on a deposit are not hard-coded (TODO-VERIFY). If you want security collected only when a ticket wins a prize, say so; that is a different flow (the prize-hold template already exists for it).
2. **Amounts received must equal the quote exactly.** No part payment or advance at enrolment; a short or excess amount is refused (`ENROLMENT_AMOUNT_MISMATCH`).
3. **Enrolment and registration fees both post to `ENROLMENT_FEE_INCOME`** with the same template, as two journals.
4. **Withdrawal before activation returns the deposit and keeps the fees** (setting `CHIT_WITHDRAWAL_REFUNDS_FEES`, default off). **Cancelling the group refunds everything**, ticket by ticket in number order: deposit, then enrolment fee, then registration fee. A lien is released, not paid.
5. **Once any ticket is enrolled these terms are locked:** chit value, ticket and cycle count, subscription, frequency, enrolment fee, registration fee, security rule and deposit percentage. Other rules can still change and create a new version. From FULL onward everything is frozen (as before).
6. **Seat numbers** are the lowest free number from 1 to N. A withdrawn ticket stays as an EXITED row and its number is reused by the next person.
7. **Maker-checker on security:** whoever recorded the security (the `X-Actor` of the enrolment call, or `lending-service` if none was sent) cannot verify it. Turn off with `CHIT_MAKER_CHECKER_ENFORCE=false` for single-person demos.
8. **Customers are not validated.** `customerId` is a number; the chit service does not check that the customer exists in lending-service. There is no customer lookup in the scope of this phase.
9. **The group code is supplied by the caller** (3 to 30 characters, capitals, digits, `-`, `_`), not generated.
10. **Activation needs the first instalment date on or after the business date.** The start date is not forced into the future when a draft is created.
11. **A repeated request returns current state, flagged `replayed: true`.** For withdraw and cancel the refund lines are rebuilt from the receipts and security rows, not stored, so they always match the books.
12. **Working-day lookups are one call per date** (about two per cycle). The weekend set is configurable in lending-service, so it is asked, not assumed.

## Phase 3: how it was checked, and what was not

- **Database: checked.** V1 to V3 apply cleanly on PostgreSQL 16; `scripts/verify_schema.sql` now runs 40 checks (refused and allowed cases) and every refused case is refused.
- **Pure code: checked.** 176 tests pass under the stand-in runner (133 before, 43 new): the calendar generator (month ends, leap year, weekly and fortnightly, holiday rolling, overlap), the lending-service client against a throw-away local HTTP server (success, retry, no retry on 4xx, outage, unreadable reply, credentials), quote and amount checks, activation blockers, refund plans, the money-term lock, and the rules snapshot round trip.
- **Services against a real database: checked, with limits.** The group, enrolment and activation services were run end to end against PostgreSQL 16 through a sandbox harness (150 checks, all passing): create and replay, wrong-key reuse, every refusal listed above, enrolling to FULL, withdrawal and seat reuse, rule locking, maker-checker, activation with a holiday and a weekend, replay, cancel with refunds (every ledger account back to zero and the trial balance in balance), guarantor and lien groups, event and audit rows. The harness is **not** part of the repo and is **not** Spring: it replaces `JdbcTemplate` with a bridge to PostgreSQL and wraps calls in transactions by hand. It does not exercise concurrent requests, the real `@Transactional` proxies, or JSON binding.
- **Controllers, wiring, JSON binding: compiled only.** The REST controllers and the Spring configuration compile against the stubs but have not run. In particular, binding the request body to the 34-field `ChitRules` record, the security filter on the new paths, and the 201 status are unverified until `mvn test` runs.
- **`ChitApplicationIT`: extended, never executed.** It now also drives a group from DRAFT to ACTIVE and a cancellation over HTTP, with a local stand-in for lending-service. It needs Docker.
- **The real lending-service was not called.** The client was tested against a stand-in built from the controller's source (`GET /api/v1/business-date` returns `businessDate`; `/working-day?date=` returns `workingDay`). On Render the first call may take a minute if lending-service is asleep.
- **Not done:** `mvn` build, real JUnit run, Spring startup, Flyway through Spring, Docker build, Render and Compose changes on real infrastructure, concurrent enrolment of the last seat, and your review.

## Phase 4: what is built

Demands, payments, allocation, late fees, default detection and the three daily jobs. One new migration (V4, no new tables), a new `collection` package of 14 classes, four pure classes in `calc`, 21 tests added (197 in all).

| Area | What | Notes |
|---|---|---|
| Schema | `V4__chit_collection.sql` | `chit_cycle.collection_opened_on`; `chit_demand.raised_on` and a CHECK that `PAID` means subscription (net of dividend) and late fees are both settled; `chit_payment.external_ref`, `reversed_by`; `chit_payment_allocation.from_advance`, `advance_used` with checks; reason and figures on `chit_default_case`. `ResetTables` is unchanged because no table was added. |
| Demand run | `collection/DemandWriter`, `CollectionBatchService` | Per group, one transaction under the group lock. Every SCHEDULED cycle whose due date has been reached is opened, in order, and gets one demand per ticket in ACTIVE, PRIZED, DEFAULTED or TRANSFERRED status. A missed day is caught up by the next run; a rerun finds nothing to do (unique `(cycle, ticket)` plus `ON CONFLICT DO NOTHING`). Journal `DEMAND:<demandId>`, dated with the run's business date. |
| Advance | `calc/AdvancePlanner` | A payment larger than what is owed leaves an ADVANCE row. A new demand uses it oldest first (`advance_used`, plus a `from_advance` CURRENT row against the demand). No journal is needed for that move: the payment already credited the subscriber's receivable account. |
| Dividends | `DemandWriter` | A DECLARED dividend with mode `ADJUST_NEXT_INSTALMENT` from an earlier cycle lowers the next demand (whole dividends only) and posts `DIVIDEND_APPLIED`. Nothing declares dividends until Phase 5, so this path was tested with a hand-inserted row. |
| Payments | `PaymentService` | Records, allocates via `AllocationEngine` in `allocationOrder`, saves demand figures, posts `PAYMENT:<id>`, cures a defaulter whose overdue is cleared. Receipt reference `RCPT-<id>`. Idempotent on the key. |
| Reversal | `PaymentService.reverse` | Reduces the demand figures by what the payment paid, marks its allocation rows reversed (not deleted), mirrors the journal (`PAYMENT:<id>:R`). Refused if the payment's advance has already been used by a later demand. |
| Late fees | `LateFeeWriter`, `calc/LateFeeCalculator` | Walks each overdue open demand day by day from its last charged day to the business date. One `chit_late_fee` row per demand per day; posting key `LATEFEE:<demandId>:<date>`. Cap and flat/per-day rules are the Phase 1 calculator. |
| Defaults | `DefaultWriter`, `calc/DefaultDetector` | An instalment is missed when something is owed and it is more than `graceDays` past due. Breach = `missedInstalments` reached, or the oldest is `daysOverdue` old. Ticket becomes DEFAULTED and a default case records the reason, counts and shortfall. Nothing is recovered or written off (Phase 6). |
| Reads | `CollectionReadService`, `CollectionController` | Payments and receipts, demands, ticket statement, group statement (cycle by cycle), default cases, late-fee preview, and `GET /dues` (UPCOMING / OVERDUE / OPEN across groups). |
| Batch endpoints | `CollectionBatchController` | `POST /batch/demand-run`, `/late-fee-run`, `/default-run`. Optional `businessDate` must equal lending-service's date (`BusinessDateGuard`), else 409 `BUSINESS_DATE_MISMATCH`. One failing group is reported in the summary and the others still run. |
| Events | `DemandRaised`, `CycleCollectionOpened`, `DividendApplied`, `PaymentReceived`, `PaymentReversed`, `LateFeeAccrued`, `TicketDefaulted`, `TicketCured` | In `chit_event`, readable through the feed. |
| Errors | 409 `GROUP_NOT_ACTIVE`, `TICKET_NOT_PAYABLE`, `PAYMENT_ALREADY_REVERSED`, `BUSINESS_DATE_MISMATCH`; 422 `PAYMENT_EXCEEDS_OBLIGATION`, `PAYMENT_ADVANCE_CONSUMED` | |
| Permissions | 7 new codes in `IAM_PERMISSIONS.txt` | `chit.demand.read`, `chit.statement.read`, `chit.payment.read|write|reverse`, `chit.default.read`, `chit.batch.run`. |

### New assumptions for you to confirm or change

1. **Allocation buckets.** LATE_FEES is the fee outstanding on any demand; ARREARS is subscription on cycles before the latest opened one; CURRENT is subscription on the latest opened one. Within a bucket the oldest cycle is paid first. Order is the group's `allocationOrder`.
2. **A payment can be at most what the ticket can still owe**: outstanding on raised demands, plus the instalments not yet raised, less advance already held. Anything above is refused (`PAYMENT_EXCEEDS_OBLIGATION`) rather than parked as an unexplained credit. Unapplied dividends are ignored, so the limit is slightly generous.
3. **Money received before the first demand is advance** and is used automatically when the demand is raised.
4. **Ledger dates.** Demand, late-fee and reversal journals carry the business date of the run that posted them, not the due date, so a day that is already closed is never touched. The demand keeps the real due date; late fees count from it.
5. **Late-fee catch-up uses today's unpaid amount** for every day it makes good. If the run was skipped for several days and a part payment came in between, the earlier days are charged on the reduced amount, not replayed day by day. This is the one approximation in the job. In normal daily running it never applies.
6. **Calendar days, not working days,** for grace and late fees (as already assumed in Phase 1). TODO-VERIFY against the real rule.
7. **Default threshold counts subscription only.** Late fees do not make an instalment "missed". The `shortfall` on a case does include fees owed on overdue demands.
8. **Cure rule.** A DEFAULTED ticket is cured (back to ACTIVE, or PRIZED if it holds a win) when no raised demand past its due date has anything left to pay, subscription or fee. Cure is automatic on payment. If the next check finds it breached again (for example after a bounced payment), a new case is opened beside the cured one.
9. **Which tickets owe an instalment:** ACTIVE, PRIZED, DEFAULTED, TRANSFERRED. A prized ticket pays the full subscription (the only rule built).
10. **Payments and the foreman.** Payments are accepted while the group is ACTIVE or CLOSING. Cash received by the foreman is recorded like any other channel; the ledger treats all channels as pool cash for now. TODO-VERIFY if cash at the foreman should be tracked separately.
11. **Reversal of a payment whose advance has been used is refused.** Taking it back would need the later demands to be reopened too; that is left to a person (and probably Phase 6).
12. **The three batch jobs are seeded switched off** in lending-batch-service, so an unverified chit job can never hold up the loan end-of-day. Switch them on from the Configuration screen when you are ready.
13. **Reads call lending-service for the business date** (days overdue are measured against it). If lending-service is asleep a read can answer 503 `BUSINESS_DATE_UNAVAILABLE` like the writes do.

## Phase 4: how it was checked, and what was not

- **Pure code: checked.** 197 tests pass under the stand-in runner (176 before): demand figures, default detection (grace edge, days limit, either condition), advance use, the payment limit, the business-date guard, and the new error statuses.
- **Database: checked.** V1 to V4 apply cleanly on PostgreSQL 16; `scripts/verify_schema.sql` now runs 50 checks (10 new for V4) and every refused case is refused.
- **Services against a real database: checked, with limits.** A sandbox harness (204 checks, all passing) ran the services against PostgreSQL 16: nothing opens before the due date; advance before the first demand; the demand run and its rerun; idempotent payments (replay, reused key); allocation lines for partial, over- and under-payments; a configurable allocation order and a flat fee; late fees with grace, catch-up, cap and rerun; default detection at the grace boundary and its rerun; cure back to ACTIVE and to PRIZED; reversal and re-flagging; refusal of a reversal whose advance was used; dividend adjustment; the dues, statement and preview reads; one broken group not stopping the others; and, after every step, ledger identities (trial balance in balance, receivable = owed less advance, pool cash = payments received, pool = demanded, late-fee income = fees accrued). The harness is **not** in the repo and is **not** Spring: it replaces `JdbcTemplate` with a bridge to PostgreSQL and wraps calls in transactions by hand.
- **Controllers, wiring, JSON binding: compiled only.** The new REST controllers and the 201/200 statuses have not run. The request bodies are simple records, but nothing has proved the binding.
- **Not done:** `mvn` build, real JUnit run, Spring startup, Flyway through Spring, Docker build, concurrent payments for one ticket, `ChitApplicationIT` (not extended for Phase 4; it needs Docker), and the live Render deployment.

## Phase 5: what is built

Auctions, winner selection, last cycle, prize payout with maker-checker and the liquidity rule, dividends, foreman cover. One migration (V6, two new tables), a new `auction` package, seven pure classes in `calc`, 24 tests added (221 in all).

| Area | What | Notes |
|---|---|---|
| Schema | `V6__chit_auction.sql` | A cycle has one live auction (a rolled-over attempt is kept and a new one opened); how an auction closed (`close_basis`), discount, draw and settlement journal; payout legs (`chit_payout_leg`), part-paid and float columns; `chit_foreman_cover`; channel `INTERNAL` on payments; unique draws per cycle and purpose and one fixed order per group. The old database CHECK that a payout's maker and checker differ was **dropped**: the service enforces it from `CHIT_MAKER_CHECKER_ENFORCE`, like security verification, so a one-person demo can switch it off. |
| Gate | `calc/QuorumGate`, `AuctionService.open` | `ALL_PAID`, `MIN_COLLECTION_PCT`, `FOREMAN_COVERS_SHORTFALL`. A cover posts `FOREMAN_COVERS` (pool cash up, ticket owes the foreman) as an INTERNAL payment that cannot be reversed. |
| Bids | `calc/BidRules` | Whole paise, inside the band, at least the commission, open ascending must beat the highest; sealed amounts hidden until the close. Server time stamps every bid. |
| Winner | `calc/WinnerSelector`, `calc/Draw` | Highest bid; ties by earliest bid, lowest ticket number or a draw. Every draw is stored with algorithm, seed and candidates, and replays to the same result (tested). The seed travels as text in JSON. |
| Settlement | `AuctionSettlementCalculator` | `P = W + C + DP`, dividends by largest remainder (fixture B: 750.01 to tickets 1 to 7, 750.00 to the rest). One journal `AUCTION:<id>`. |
| Last cycle | `AuctionService.close` | The one ticket left wins the whole pot; `NONE` or `DEDUCT_FROM_PRIZE` commission; no dividend. |
| Payout | `PayoutService` | Request (maker), approve or reject (checker), disburse; preconditions listed under the errors below; journals `PRIZEPAY:<payout>:<leg>`. |
| Liquidity | `calc/PoolPosition`, `calc/LiquidityDecision` | See assumption 1. Fixture C, three ways: block (nothing paid until funded), foreman floats 5,000 (repaid later), pay 85,000 now and 5,000 later with the 10% hold spread over the legs (8,500 and 500). |
| Dividends | `AuctionService`, `DividendService` | `ADJUST_NEXT_INSTALMENT` (set against the next instalment when it is raised, or at once if that was already raised), `CREDIT_SUBSCRIBER_BALANCE` (credited at the close as an INTERNAL payment, then used as advance), `PAY_OUT` (paid in cash with an explicit call). |
| No bid | `AuctionService` | `LOTTERY` (recorded draw at the lowest discount), `FOREMAN_DEFAULT_DISCOUNT` (recorded draw at the foreman's discount), `ROLL_OVER_CYCLE` (see assumption 4). |
| Errors | 409 `AUCTION_GATE_CLOSED`, `AUCTION_NOT_OPEN`, `AUCTION_NOT_DUE`, `PREVIOUS_CYCLE_OPEN`, `PREVIOUS_PAYOUT_PENDING`, `ROLL_OVER_BLOCKED`, `LAST_CYCLE_NO_AUCTION`, `LAST_CYCLE_CANDIDATES`, `NO_ELIGIBLE_TICKETS`, `BIDS_NOT_ACCEPTED`, `PAYOUT_EXISTS`, `PAYOUT_NOT_APPROVED`, `PAYMENT_NOT_REVERSIBLE`, `DIVIDEND_MODE_MISMATCH`, `FLOAT_REPAYMENT_TOO_LARGE`; 403 `MAKER_CHECKER_VIOLATION`; 422 `BID_OUT_OF_BOUNDS`, `BID_NOT_HIGHER`, `TICKET_NOT_ELIGIBLE`, `PAYOUT_LIQUIDITY_SHORT`, `PAYOUT_SECURITY_NOT_VERIFIED`, `PAYOUT_ARREARS_UNCLEARED`, `PAYOUT_ACCOUNT_MISSING`, `PAYOUT_TICKET_NOT_IN_GOOD_STANDING`; 500 `SETTLEMENT_UNBALANCED` | |
| Permissions | 11 new codes in `IAM_PERMISSIONS.txt` | `chit.auction.*`, `chit.payout.*`, `chit.dividend.*` |

### An observation about the liquidity example in the spec

With every member paying, the pool's cash always equals what it owes (prize + commission + dividends = the chit value). The pool can therefore only be short when **collection is short**: in fixture C the 5,000 gap exists because one instalment of 5,000 was not collected (95% collected under a 90% gate), not because of the auction itself. The code models it that way, and the harness reproduces fixture C exactly (available 85,000 against a prize of 90,000). If the spec meant a shortfall that arises with full collection, it needs restating.

### New assumptions for you to confirm or change

1. **"Available for a prize"** is pool cash minus commission earned, minus dividends declared and not yet applied or paid, minus advance held for tickets, minus prizes owed to other cycles, minus the foreman's float still owed. Late-fee income is treated as cash the pool may use. Float repayment is allowed only from what is left after every claim.
2. **A discount for LOTTERY, FIXED_ORDER and the no-bid lottery is the minimum of the band.** The spec fixes no figure for them; the foreman-default rule uses its own percentage.
3. **FIXED_ORDER is drawn once, when the first cycle closes, over every ticket, and stored.** Each cycle takes the first ticket in that order that has not won and is in good standing.
4. **Roll-over:** a no-bid auction is kept as ROLLED_OVER, the cycle goes back to COLLECTION_OPEN, its auction date moves to the next date the calendar gives and every later cycle shifts by one slot. Refused (`ROLL_OVER_BLOCKED`) once a later cycle has started collecting.
5. **Defaulters:** a ticket that is DEFAULTED cannot bid or win and, with `defaulterLosesDividend`, receives no dividend; its share goes to the others. A defaulted ticket that clears its arrears and bids is **not** supported yet (it must be cured first).
6. **Who gets a dividend:** `ALL_TICKETS` includes the winner of the cycle; `NON_PRIZED_ONLY` leaves out every ticket that has won, this cycle's winner included. Exited tickets get nothing.
7. **The ticket becomes PRIZED at the first money leg**, not at the auction close, so a winner whose payout is blocked is still ACTIVE and still pays.
8. **Cycle SETTLED** when the prize is fully paid and no PAY_OUT dividend is unpaid. `ADJUST_NEXT_INSTALMENT` dividends do not hold a cycle open; if no later cycle exists they stay DECLARED for Phase 7 (closure).
9. **A foreman cover and a credited dividend are payments on channel `INTERNAL`** so demands, advance and statements stay one mechanism. They cannot be submitted or reversed by a caller. Recovering a cover from the ticket is Phase 6.
10. **Order:** cycles are auctioned and their prizes paid strictly in order.
11. **Bids are entered by operators** on the holder's behalf (the portal does not bid in this phase); the server time of entry is the bid time.
12. **The payout hold** is a percentage of the gross prize; across legs it is spread pro rata and the last leg takes the remainder, so the total hold is exact.

## Phase 5: how it was checked, and what was not

- **Pure code: checked.** 221 tests pass under the stand-in runner: bids, winner and tie-break, draw replay, the gate under all three rules, pool position and liquidity (fixture C's numbers), hold spreading, dividend beneficiaries, roll-over dates, the new error statuses, and the JSON shape of a draw seed and a hidden sealed bid.
- **Database: checked.** V1 to V6 apply on PostgreSQL 16; `scripts/verify_schema.sql` runs 79 checks.
- **Services against a real database: checked, with limits.** The sandbox harness (514 checks, all passing) ran every rule on PostgreSQL 16: fixture A (20 tickets, bids, a refused low, equal and out-of-band bid, close, 79,400 prize, 780 dividends, payout with a refused self-approval, the second cycle at 4,220 net per ticket and a pool surplus of exactly zero), fixture B (rounding to the paisa), fixture C under all three rules, fixture D (the last cycle under both commission rules, pool cash left equals commission income), tie-breaks under all three rules including a replayed draw, sealed bids, lottery and fixed-order groups (every ticket wins once, draws replay), all three no-bid rules including a roll-over to the end of the group, the gate under all three rules and the foreman cover, the three dividend modes, NON_PRIZED_ONLY and a defaulter, the payout preconditions and a rejection and resubmission, maker-checker switched off, cycle ordering, **two simultaneous closes on separate connections (exactly one wins, one settlement journal)**, events (a sealed amount is never published) and replays. After every payout the ledger is in balance and prize payable, dividend payable, commission, pool cash, float and security cash each equal what the rows say.
- **Admin screens: checked in a browser against a mock API**, not against the real service: open, bid, a refused bid, close, request, a refused self-approval, approve, part payment, balance, dividends, and the group page. TypeScript compiles.
- **Controllers, wiring, JSON binding: compiled only,** as before. The new controller has not served a request. The admin forwarding has unit tests (48 in the admin service).
- **Not done:** `mvn` build, real JUnit run, Spring startup, Flyway through Spring, `@Transactional` proxies (the harness wraps calls by hand), Docker build, `ChitApplicationIT` (not extended), the live Render deployment, and your review. Note also that nothing yet closes a group or moves the last cycle's leftover cash (Phase 7).

## Phase 6: what is built

Defaults and recovery, write-off, ticket transfer and replacement exit. One migration (V7), new `transfer` and `recovery` packages, two pure calculators, 40 tests added (261 in all).

| Area | What | Notes |
|---|---|---|
| Schema | `V7__chit_recovery_transfer.sql` | Transfers gain kind (`TRANSFER` or `REPLACEMENT`), reasons, previous status, dates and the new holder's payout account; demands gain `late_fee_waived` and status `WRITTEN_OFF` (the status column is widened to 12); default cases gain `security_applied` and `cover_recovered`; new `chit_recovery_action` and `chit_write_off`. The two database CHECKs that hard-wired maker is not checker (transfer and write-off) are **dropped**: the service enforces them from `CHIT_MAKER_CHECKER_ENFORCE`, as for payouts since V6. |
| Transfer | `TransferService`, `TransferEligibility` | A transfer hands the same ticket to another customer. Request by a maker, approval by a different checker. While pending an ACTIVE or PRIZED ticket shows `TRANSFERRED`; a DEFAULTED ticket stays DEFAULTED. Approval closes the old subscriber link, opens a new one, changes the holder, restores the status and posts the transfer fee. A rejection restores the status exactly. |
| Replacement exit | `TransferService.requestExit` | Your answer to question 3. An ACTIVE ticket that has not won leaves by naming a replacement who takes the same ticket (link reason `REPLACEMENT`). No refund, no penalty, no fee. A prize winner is refused (`EXIT_PRIZED_TICKET`); a winner can be transferred instead. |
| Fee | rule `transferFee` (default 0) | Paid by the incoming holder on a plain transfer, never on a replacement exit. Journal `TRANSFERFEE:<id>`: Dr foreman cash, Cr `TRANSFER_FEE_INCOME`. Old snapshots without the field still load. |
| Arrears | `TransferEligibility` | Nothing overdue (instalments, late fees) and nothing owed to the foreman on covers, otherwise `TRANSFER_ARREARS_EXIST`. This **replaces** the "arrears travel with the ticket" wording of Option A under "Decisions you gave", at your 25 Oct answer: arrears are cleared (or written off) before the ticket changes hands. Security stays with the ticket. |
| Apply security | `RecoveryService.applySecurity` | For a DEFAULTED ticket with an open case. The held deposit pays overdue demands first (in the group's allocation order, as an INTERNAL payment `SECAPPLY:<action>`), then the foreman's covers, oldest first (`SECGUAR:<action>:<cover>`). Amount optional (a cap). When the deposit reaches zero the security row becomes APPLIED. If the ticket then owes nothing it returns to good standing and the case closes RECOVERED. |
| Repay a cover | `RecoveryService.repayCover` | Money the holder pays the foreman against a cover; not a payment into the pool (`GUARREPAY:<action>:<cover>`). Works with no open case. Refuses more than is owed (`COVER_REPAYMENT_TOO_LARGE`). |
| Write-off | `RecoveryService.requestWriteOff` / `approveWriteOff` | Your answer to question 4. Allowed only when `foremanGuaranteeMode` is not `NONE`, the ticket is DEFAULTED, the deposit has been applied (`SECURITY_STILL_HELD` otherwise) and something is owed. The request stores a snapshot; the checker approves only if it is still true (`WRITE_OFF_STALE`). On approval the foreman pays each overdue subscription into the pool as a cover, overdue late fees are waived (`LFWAIVE:<demand>`), and every open cover is written off to `GUARANTEE_LOSS_EXPENSE` (`GWO:<cover>`). A demand ends WRITTEN_OFF only when a fee was waived; otherwise PAID. |
| After a write-off | `TransferService.approve` | The ticket stays DEFAULTED and the case OPEN until a replacement subscriber takes it by a **transfer**; approval then returns it to ACTIVE and closes the case WRITTEN_OFF. |
| Errors | 409 `RECOVERY_TICKET_NOT_DEFAULTED`, `RECOVERY_NO_OPEN_CASE`, `COVER_REPAYMENT_TOO_LARGE`, `WRITE_OFF_PENDING`, `WRITE_OFF_STALE`, `WRITE_OFF_DEMAND_ALREADY_COVERED`, `SECURITY_STILL_HELD`, `TRANSFER_PENDING`, `TRANSFER_PAYOUT_PENDING`, `TRANSFER_TICKET_NOT_ELIGIBLE`, `EXIT_PRIZED_TICKET`; 403 `MAKER_CHECKER_VIOLATION`; 422 `NO_SECURITY_HELD`, `NOTHING_TO_RECOVER`, `NOTHING_TO_WRITE_OFF`, `WRITE_OFF_NOT_PERMITTED`, `TRANSFER_ARREARS_EXIST`, `TRANSFER_SAME_CUSTOMER`, `TRANSFER_GUARANTOR_CONFLICT` | |
| Endpoints | transfer: `POST /groups/{code}/tickets/{no}/transfer`, `.../exit`, `GET .../transfers`, `.../transfers/{id}`, `.../tickets/{no}/transfers`, `POST .../transfers/{id}/approve\|reject`. recovery: `GET .../tickets/{no}/recovery`, `POST .../recovery/apply-security`, `.../recovery/repay-cover`, `.../recovery/write-offs`, `GET .../write-offs[/{id}]`, `POST .../write-offs/{id}/approve\|reject` | All mutating calls need `Idempotency-Key` and `X-Actor`. |
| Permissions | 7 new codes in `IAM_PERMISSIONS.txt` | `chit.transfer.read\|request\|approve`, `chit.recovery.read\|apply`, `chit.writeoff.request\|approve` |

### New assumptions and limitations for you to confirm or change

1. **A group with `foremanGuaranteeMode` NONE cannot write off**, and its rules are frozen once active, so a defaulted ticket there can only be recovered (deposit, payment) or transferred once cleared. Relax the rule or tell me if you want write-off allowed there too.
2. **Only a held DEPOSIT goes through the books.** A LIEN or a GUARANTOR claim is made outside the system and the money received is recorded as an ordinary payment on the ticket (`NO_SECURITY_HELD` explains this).
3. **Write-off needs an open default case.**
4. **The incoming holder in a replacement after a write-off pays the transfer fee**, because that path is a plain transfer (a replacement *exit* is only for ACTIVE tickets). If you want it free, the fee can be waived for a ticket whose case was written off.
5. **Unpaid dividends and advance travel with the ticket** on transfer.
6. **`PaymentService.cureIfClear` is unchanged:** a case with `foreman_covered > 0` is never cured by ordinary payments; use apply-security or repay-cover.
7. **After an APPLIED deposit** the payout precondition `PAYOUT_SECURITY_NOT_VERIFIED` would block that ticket's later prize until security is posted again.
8. **A transfer also needs the group ACTIVE or CLOSING**; before activation a member withdraws and the seat is enrolled again.

## Phase 6: how it was checked, and what was not

- **Pure code: checked.** 261 tests pass under the stand-in runner (221 earlier + 40): the recovery waterfall, transfer eligibility in its stated order, the new journals, the `transferFee` rule and its JSON, the V7 schema against the code, the new error statuses.
- **Database: checked.** V1 to V7 apply on PostgreSQL 16; `scripts/verify_schema.sql` now runs 104 checks (25 for V7).
- **Services against a real database: checked, with limits.** A sandbox harness (`harness/`, 125 checks, all passing) runs the real services over PostgreSQL 16 with hand-driven transactions: transfer with a fee, a refused self-approval, replay, rejection, same-customer and arrears refusals, a replacement exit with no fee, a prize winner refused for exit and held back by `TRANSFER_PAYOUT_PENDING` until paid, applying a deposit to arrears and to auction covers, repaying covers (and refusing too much), write-off with and without a late fee, a stale snapshot refused, replay of an approval, mode NONE refused, and a replacement closing the case WRITTEN_OFF. After each step the trial balance is in balance and each ticket's receivable equals what its demands say. **It found one defect** (the demand status column was too short for `WRITTEN_OFF`), fixed in V7.
- **Not done:** the admin service and screens for Phase 6 (the Phase 5 pattern, not started), `mvn` build, real JUnit run, Spring startup, controllers and JSON binding (compiled only), `@Transactional` proxies (the harness wraps calls by hand), Flyway through Spring, `ChitApplicationIT` (not extended), the live deployment, and your review.

## Next: Phase 7 (not started)

Group closure and the last cycle's leftover cash, after your review of Phase 6.

## Customer portal reads (added for Customer Portal v2)

`/api/v1/chit/customers/{customerId}/tickets|payments|requests|auctions` (read only, `chit.customer.portfolio.read`) and a `customerId` filter on `/api/v1/chit/dues`. Auction state is derived from the
existing auction view (OPEN is LIVE, NOT_OPENED is UPCOMING, else CLOSED); the winning ticket and prize are shown only to the winner. Verified by 59 harness checks on PostgreSQL 16 (228 in all) and 273 unit tests.
Not verified: the controller and Spring wiring over real HTTP.
