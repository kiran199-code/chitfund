# chitfund-service

Chit fund service: chit groups, tickets, cycles, subscriptions, auctions, prize payouts, dividends, defaults and closure,
with its own append-only double-entry journal, its own business date and its own working-day calendar. It is a standalone
service (it was split out of the lending platform and depends on nothing in it). Spring Boot 3.3.2, Java 17, JDBC (no JPA),
Flyway, one service credential plus an `X-Actor` header. The other two parts of the chit fund are `chitfund-batch-service`
(nightly jobs and end of day) and `chitfund-admin-service` (the operations console); both call this service's REST API.

Built in phases from `docs/Chit_Fund_Specification_Prompt_v2.md`. See `BUILD_STATUS.md` for what each phase delivers,
the assumptions, the open questions and what has and has not been verified.

**Where it stands: Phases 1 to 4 of 10, and standalone.** A group can be created, configured, opened for enrolment, filled with tickets
(fees and deposits posted to the ledger), activated (rules frozen, cycle calendar built on working days) or cancelled
with refunds. Since Phase 4 it also raises each cycle's instalments on the due date, takes payments (allocated to late
fees, arrears and the current instalment; the rest held as advance), charges late fees day by day, flags defaulters, and
can reverse a bounced payment. Auctions, payouts and dividends arrive in Phases 5 to 7.

## Run it

```
mvn test                                  # unit tests; the *IT test needs Docker (Testcontainers)
mvn spring-boot:run                       # needs PostgreSQL at DB_HOST:DB_PORT/DB_NAME (default localhost:5432/chitfund)
```

Docker: `docker compose up --build` from the repository root starts PostgreSQL and all three services.
Render: the `chitfund-service` block in `render.yaml`.

| Setting | Default | What it does |
|---|---|---|
| `PORT` | 8086 | HTTP port (Render sets 10000) |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | localhost 5432 chitfund chitfund chitfund | This service's own PostgreSQL database |
| `CHIT_DB_SCHEMA` | chit | Schema owned by this service; Flyway creates it. History table `chit_schema_history` |
| `CHIT_SERVICE_USERNAME` `CHIT_SERVICE_PASSWORD` | chit-service / changeme | The one HTTP Basic credential for `/api/**`; the batch and admin services send it |
| `CHIT_RESET_ENABLED` | false | Allows `POST /api/v1/chit/maintenance/clear-data` (non-production only) |
| `CHIT_MAKER_CHECKER_ENFORCE` | true | Whoever records a ticket's security cannot verify it; also used from Phase 5 on |
| `CHIT_BUSINESS_DATE_MODE` | MANAGED | `MANAGED`: the date is stored and moved forward by end of day (so a test environment can run ahead of the clock). `SYSTEM`: the date is the wall-clock date in `CHIT_TIMEZONE` and is never stored |
| `CHIT_TIMEZONE` | Asia/Kolkata | Time zone for the wall-clock date and for the first MANAGED date |
| `CHIT_WEEKEND_DAYS` | SATURDAY,SUNDAY | Days that are never working days; holidays are added on top in the `chit_holiday` table |
| `CHIT_WITHDRAWAL_REFUNDS_FEES` | false | Whether a ticket withdrawing before the group starts gets its fees back (its deposit always comes back) |
| `CHIT_DB_POOL_SIZE` | 20 | Connection pool size; use 3 on the shared free database |

## Business date and calendar

This service owns both (`businessdate/`, table `chit_business_date` for the date and `chit_holiday` for holidays). The business
date is always the service's own, never the caller's.

| Endpoint | Purpose |
|---|---|
| `GET /business-date` | The date, `previousBusinessDate`, `nextBusinessDate`, `status` (`OPEN` or `EOD_RUNNING`), `workingDay`, `systemDate`, `mode` |
| `POST /business-date/eod/start` (actor) | `OPEN` to `EOD_RUNNING`; repeating it while already running is 409 `EOD_ALREADY_RUNNING` (the batch service treats that as a resume) |
| `POST /business-date/advance` (actor) | Body `{expectedBusinessDate}`: close that day and open the next calendar day (every day gets a business date; `workingDay` only says whether a cycle may fall on it). Repeating it changes nothing. 409 `BUSINESS_DATE_MISMATCH` if the date is neither that day nor the day after |
| `PUT /business-date` (actor) | Body `{businessDate}`: move the date forward by hand. Test environments only (`CHIT_RESET_ENABLED=true`); 409 `BUSINESS_DATE_BACKWARDS` for a past date: clear all data to start again from today |
| `GET /business-date/working-day?date=` | Whether a date is a working day |
| `GET /holidays?year=` | The weekend days and the holidays |
| `PUT /holidays/{date}`, `DELETE /holidays/{date}` (actor) | Add or rename a holiday; remove one. Cycle dates already laid out at activation do not change |

In `SYSTEM` mode start and advance only report the date and move nothing, and set answers 409 `BUSINESS_DATE_NOT_MANAGED`. A set while `CHIT_RESET_ENABLED` is off answers 403 `RESET_DISABLED`.

## What exists in Phase 4

Same conventions as below.

| Endpoint | Purpose |
|---|---|
| `POST /groups/{code}/tickets/{no}/payments` | Record money received. Body `{amount, channel, externalRef}`, channel `UPI`, `BANK_TRANSFER`, `CASH_AT_FOREMAN` or `AUTO_DEBIT`. Applied in the group's order (default late fees, oldest arrears, current); the rest is advance. 422 `PAYMENT_EXCEEDS_OBLIGATION` if it is more than the ticket can owe |
| `POST /groups/{code}/payments/{ref}/reverse` (actor) | Body `{reason}`. Reopens the demands it paid, mirrors the journal. 422 `PAYMENT_ADVANCE_CONSUMED` if its advance was already used; 409 `PAYMENT_ALREADY_REVERSED` |
| `GET /groups/{code}/payments?ticketNo=` and `/payments/{ref}` | Payments, newest first; one payment is the receipt with every allocation line |
| `GET /groups/{code}/demands?ticketNo=&status=` | Demands with paid, outstanding, late fee and days overdue |
| `GET /groups/{code}/tickets/{no}/statement` | A ticket's account: owed, advance held, all demands and payments, open default case |
| `GET /groups/{code}/tickets/{no}/late-fee-preview?days=` | What would accrue over the next days if nothing is paid |
| `GET /groups/{code}/statement` | Cycle by cycle: due, collected, outstanding, collection %, and tickets with money overdue |
| `GET /groups/{code}/default-cases?status=` | Default cases with the reason and figures |
| `GET /dues?view=UPCOMING\|OVERDUE\|OPEN&groupCode=&withinDays=` | Dues across groups. UPCOMING is projected from cycles not yet raised |
| `POST /batch/demand-run`, `/batch/late-fee-run`, `/batch/default-run` | The three daily jobs. Optional `?businessDate=` must equal this service's current business date or the call is refused (409 `BUSINESS_DATE_MISMATCH`). Safe to repeat; returns what changed and any group that failed |

Rules the code follows (also in `BUILD_STATUS.md`): a cycle opens on the first run on or after its due date, and a missed
day is caught up by the next run; late fees are charged one day at a time and cannot be charged twice for a day; a ticket
is flagged DEFAULTED when it has the configured number of unpaid instalments past grace (or one past the days limit) and
is cured automatically when a payment clears everything overdue.

## What exists in Phase 5

Same conventions as Phase 3. Every state-changing call needs `Idempotency-Key` and `X-Actor`. A cycle is addressed by its number.

| Endpoint | Purpose |
|---|---|
| `GET /groups/{code}/cycles/{n}/auction` | The auction: type, bounds, bids (sealed amounts are hidden while it is open and shown after the close), winner, how it was decided, the split of the pot, the dividends, the draw |
| `GET /groups/{code}/cycles/{n}/auction/gate` | Whether the auction may open and why not: rule, amount due and collected, the unpaid tickets |
| `POST .../auction/open` (actor) | Open it. 409 `AUCTION_GATE_CLOSED` (collection not open, or the quorum rule is not met), `AUCTION_NOT_DUE` (before the auction date), `PREVIOUS_CYCLE_OPEN`. Under `FOREMAN_COVERS_SHORTFALL` the foreman's cover for each unpaid instalment is posted here |
| `POST .../auction/bids` (actor) | Body `{ticketNo, discountAmount}`. Entered by an operator for the holder. 422 `BID_OUT_OF_BOUNDS`, `BID_NOT_HIGHER` (open ascending: each bid must beat the highest), `TICKET_NOT_ELIGIBLE` (already won, or not in good standing). Sealed: a new bid from a ticket replaces its earlier one |
| `POST .../auction/bids/{seq}/withdraw` (actor) | Body `{reason}` |
| `POST .../auction/close` (actor) | Decides the winner (highest bid; tie by the group's rule; recorded draw for LOTTERY, FIXED_ORDER and the no-bid rules; the last cycle goes to the one ticket left), splits the pot, posts one journal, declares dividends. A close that found no bid under `ROLL_OVER_CYCLE` returns status `ROLLED_OVER` |
| `POST .../payout/request` (actor) | Maker. 422 `PAYOUT_SECURITY_NOT_VERIFIED`, `PAYOUT_ARREARS_UNCLEARED`, `PAYOUT_ACCOUNT_MISSING`, `PAYOUT_TICKET_NOT_IN_GOOD_STANDING`; 409 `PAYOUT_EXISTS` |
| `POST .../payout/approve` and `/reject` (actor) | Checker. 403 `MAKER_CHECKER_VIOLATION` for the requester while enforcement is on. Reject needs `{reason}`; the payout can then be requested again |
| `POST .../payout/disburse` (actor) | Pays out under the group's liquidity rule. 422 `PAYOUT_LIQUIDITY_SHORT` when `BLOCK_UNTIL_FUNDED` cannot pay; `FOREMAN_FLOATS` floats the gap; `PAY_AVAILABLE_AND_BALANCE_LATER` pays what is free (status `PART_PAID`), call again for the balance |
| `POST .../payout/float-repayments` (actor) | Body `{amount}`; only from money the pool holds beyond what it owes |
| `POST .../dividends/pay` (actor) | For `PAY_OUT` groups: pays the cycle's declared dividends in cash |
| `GET /groups/{code}/payouts`, `/cycles/{n}/payout` | Payouts with their legs |
| `GET /groups/{code}/liquidity?cycleNo=` | The pool's cash against what it owes; with a cycle, what its prize would do under each of the three rules |
| `GET /groups/{code}/dividends?ticketNo=`, `/draws`, `/foreman-covers` | Dividends, every draw (algorithm, seed as text, candidates, result: replayable), the foreman's covers |

Order is enforced: cycles are auctioned in order and their prizes paid in order (409 `PREVIOUS_CYCLE_OPEN`, `PREVIOUS_PAYOUT_PENDING`). Two simultaneous
closes cannot both win: the group row lock serialises them and the second is refused (`ILLEGAL_TRANSITION`). Events (`AuctionOpened`, `BidPlaced`,
`BidWithdrawn`, `AuctionClosed`, `AuctionRolledOver`, `DividendDeclared`, `DividendApplied`, `DividendPaid`, `PrizePayoutRequested|Approved|Rejected`,
`PrizeDisbursed`, `ForemanCovered`, `ForemanFloatRepaid`) are written in the same transaction as the change; a sealed bid's amount is never published.

## What exists in Phase 3

All under `/api/v1/chit`. State-changing calls need an `Idempotency-Key` header; the same key and body returns the first
result with `"replayed": true`, the same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`. Calls marked (actor)
also need `X-Actor`. A group is addressed by its `groupCode`.

| Endpoint | Purpose |
|---|---|
| `POST /groups` (actor) | Create a group in DRAFT. Body `{groupCode, name, foremanRef, rules}`; optional rules take defaults; 422 `CONFIG_INVALID` lists every violation |
| `GET /groups?status=&limit=&offset=` | Groups, newest first |
| `POST /groups/preview` | Validate rules and show the cycle dates, saving nothing |
| `GET /groups/{code}` | One group with seats taken and left |
| `GET /groups/{code}/rules`, `/rules/versions`, `/rules/versions/{n}` | Current rules and the history of versions |
| `PUT /groups/{code}/rules` (actor) | New rules version while DRAFT or OPEN; money terms lock once a ticket is enrolled |
| `POST /groups/{code}/open-enrolment` (actor) | DRAFT to OPEN_FOR_ENROLMENT |
| `GET /groups/{code}/enrolment-quote` | What each ticket must pay: fees, deposit or lien, guarantor needed |
| `POST /groups/{code}/tickets` | Enrol one ticket; amounts received must equal the quote; next free number assigned; becomes FULL at the last seat |
| `GET /groups/{code}/tickets?includeExited=` and `/tickets/{no}` | Tickets with fees and security |
| `POST /groups/{code}/tickets/{no}/withdraw` | Before activation: deposit returned, number freed, FULL goes back to OPEN |
| `POST /groups/{code}/tickets/{no}/security/verify` (actor) | Checker step; not the person who recorded it |
| `GET /groups/{code}/activation-check` | Every reason the group cannot start yet |
| `POST /groups/{code}/activate` (actor) | Freeze rules, build the calendar, create ledger accounts, set tickets ACTIVE |
| `POST /groups/{code}/cancel` (actor) | Before activation: refund deposits and fees, close the group |
| `GET /groups/{code}/cycles` | The cycle calendar (empty until activation) |

The business date and the working-day calendar are this service's own (see above), read from its own tables inside the call.

## What exists since Phase 2

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Health, no credential |
| `GET /api/v1/chit/events?cursor=&limit=` | Pull feed of events, gap-free cursor, oldest first |
| `GET /api/v1/chit/ledger/groups/{id}/trial-balance` | Debit and credit totals per account; `inBalance` must be true |
| `GET /api/v1/chit/ledger/groups/{id}/journals?afterId=&limit=` | Journals with their lines |
| `GET /api/v1/chit/ledger/journals/{id}` | One journal |
| `GET /api/v1/chit/maintenance/status` | Whether clearing is on, and row counts |
| `POST /api/v1/chit/maintenance/clear-data` | Body `{"confirm":"RESET"}`; empties every chit table |
| `/swagger-ui.html` | API docs |

Errors are always `{timestamp, status, code, message}` (rule violations add `violations`). The code is the stable part;
`config/ErrorStatus` is the one place that maps a code to an HTTP status.

## The journal

Every money movement is a journal of at least two lines, debits equal to credits, built from `ledger/JournalTemplates`
(20 templates) and posted by `LedgerService.post` inside the caller's transaction. The database enforces the same
rules: a deferred trigger refuses an unbalanced journal at commit, journals and lines cannot be updated or deleted,
a line must sit on an account of its journal's group, and a posting key can be used once. A mistake is corrected by a
reversing journal (`LedgerService.reverse`), never by an edit. `TwentyCycleSimulationTest` runs a whole 20-ticket chit
through the templates and checks the pool nets to zero after the closure sweep.

`scripts/verify_schema.sql` runs 79 checks of the database rules (refused and allowed cases) against a scratch database.

## Layout

```
common/        Money, ChitException, Actor, Audit, Json, Hashing
domain/        enums, rules, state machines                    (Phase 1)
calc/          settlement, allocation, late fees, draw          (Phase 1; demand figures, default detector, advance, limit: Phase 4)
collection/    demands, payments, late fees, defaults, batch    (Phase 4)
auction/       auctions, bids, winner, payout, dividends        (Phase 5)
businessdate/  business date (MANAGED or SYSTEM), holidays, EOD    (split from lending)
calendar/      cycle dates on working days                      (Phase 3)
group/         create, rules versions, open enrolment           (Phase 3)
enrolment/     quote, enrol, withdraw, verify, activate, cancel (Phase 3)
ledger/        journal model, templates, repository, service    (Phase 2)
idempotency/   key claim and replay check                       (Phase 2)
events/        pull feed                                        (Phase 2)
maintenance/   status and clear-data                            (Phase 2)
config/        security, error handling, settings               (Phase 2)
```

Replacing the Basic credential with the IAM JWT resource server changes `config/SecurityConfig` only; the actor then
comes from the token instead of `X-Actor`. Permission codes are in `IAM_PERMISSIONS.txt`.
