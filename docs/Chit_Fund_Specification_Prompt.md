# Prompt: Chit Fund Module — End-to-End Specification

Paste everything below the line into your coding assistant. Items marked `TODO-VERIFY` are rules whose real-world values or variants I could not confirm; they are deliberately configurable. Verify them against primary sources (the Chit Funds Act and your state's rules) before treating any of this as compliant.

---

## 1. Role and goal

You are a senior backend/full-stack engineer. Build a **Chit Fund module** on top of my existing core banking platform (Spring Boot, PostgreSQL, JPA, Flyway, Testcontainers, Angular; Kafka for events). This is a **personal training project**, not a production or regulated product. It must cover, end to end: **group creation and enrolment, periodic subscription collection, monthly auction and prize payout, defaults, and final closure.**

Work in phases (section 14). Before writing code, output: (a) the assumptions you are making, (b) any open questions, (c) the proposed package structure. Then proceed phase by phase, and stop after each phase for my review.

Do **not** invent library methods or APIs. If unsure that something exists in the version I use, say so and tell me to verify it.

## 2. Scope

In scope: chit group configuration, enrolment of subscribers (including multiple tickets per person), cycle calendar, demand generation, payment collection and allocation, late fees, auction (several types), prize payout, dividend handling, defaults and security, ticket transfer and exit, pool accounting, closure and reconciliation, REST APIs, events, Angular screens, tests.

Out of scope: real payment gateway integration (simulate channels), real KYC (reference an existing customer ID), regulatory filings, tax computation.

## 3. Glossary

- **Chit value / pot (P):** total amount drawn each cycle.
- **Members / tickets (N):** number of tickets; equals number of cycles for a standard chit. One person may hold several tickets.
- **Subscription (S):** per-ticket contribution each cycle, normally `S = P / N`.
- **Foreman:** the organiser who runs the chit and earns a commission.
- **Auction / bid:** each cycle, eligible members bid a *discount* they are willing to give up in exchange for receiving the pot now.
- **Discount (D):** the amount by which the prize is reduced.
- **Prize (W):** amount paid to the winner, `W = P − D`.
- **Commission (C):** foreman's fee, configured as a percentage of P. `TODO-VERIFY` legal cap.
- **Dividend pool (DP):** `DP = D − C`, shared among members per configuration.
- **Prized / non-prized ticket:** whether the ticket has already won a prize.

## 4. Configuration (all rules must be data-driven, never hard-coded)

Per chit group:

| Parameter | Notes |
|---|---|
| name, code | unique |
| chit_value P | decimal |
| ticket_count N | integer |
| term / cycle_count | default = N |
| subscription S | default P/N; validate arithmetic |
| frequency | MONTHLY default; allow WEEKLY / FORTNIGHTLY |
| start_date, instalment_due_day, auction_day | business-day calendar aware, Asia/Kolkata |
| commission_pct | validated against a configurable `max_commission_pct` (`TODO-VERIFY`) |
| auction_type | OPEN_ASCENDING_DISCOUNT, SEALED_BID, LOTTERY, FIXED_ORDER |
| min_discount_pct, max_discount_pct | of P; `TODO-VERIFY` legal limits |
| tie_break_rule | EARLIEST_BID_TIME, LOTTERY, LOWEST_TICKET_NO |
| no_bid_rule | LOTTERY, FOREMAN_DEFAULT_DISCOUNT, ROLL_OVER_CYCLE |
| dividend_beneficiaries | ALL_TICKETS or NON_PRIZED_ONLY (variants differ; keep configurable) |
| dividend_mode | ADJUST_NEXT_INSTALMENT, PAY_OUT, CREDIT_SUBSCRIBER_BALANCE |
| prized_subscription_rule | FULL_SUBSCRIPTION, or other configurable schedule after winning |
| grace_days | before a payment counts as late |
| late_fee_rule | FLAT or PERCENT_PER_DAY, with cap |
| default_threshold | number of missed instalments or days that mark a ticket DEFAULTED |
| auction_quorum_rule | e.g. ALL_PAID, MIN_COLLECTION_PCT, FOREMAN_COVERS_SHORTFALL |
| security_rule | NONE, GUARANTOR, DEPOSIT, LIEN_ON_DEPOSIT; applies to prized tickets |
| payout_hold_pct | optional retention held until security/arrears are cleared |
| enrolment_fee, registration_fee | optional |
| foreman_guarantee_mode | whether foreman covers shortfalls to pay the winner on time |

Validation: reject configurations that do not reconcile (`S × N ≠ P`, commission above cap, discount bounds inconsistent, auction day before due day, etc.), with clear error codes.

## 5. State machines

**Chit group:** `DRAFT → OPEN_FOR_ENROLMENT → FULL → ACTIVE → CLOSING → CLOSED`, plus `CANCELLED` (only before ACTIVE, with refund of fees/deposits).
Activation requires all tickets enrolled, security collected where required, and cycle calendar generated.

**Ticket:** `ENROLLED → ACTIVE → PRIZED → COMPLETED`, with side states `DEFAULTED`, `TRANSFERRED`, `EXITED`.

**Cycle:** `SCHEDULED → COLLECTION_OPEN → AUCTION_OPEN → AUCTION_CLOSED → PAYOUT_PENDING → PAYOUT_DONE → SETTLED`.
Transitions must be explicit, validated, audited, and reject illegal jumps.

## 6. Functional requirements

### 6.1 Creation and enrolment
1. Create a group in DRAFT with the configuration above; validate; save versioned rules (rules snapshot is frozen at activation).
2. Open enrolment; subscribers enrol by referencing an existing customer ID. A customer may hold multiple tickets. Assign ticket numbers.
3. Collect security and fees at enrolment according to `security_rule`; record them in the ledger (section 8).
4. Activate when full: freeze the rules, generate the full cycle calendar (due dates, auction dates), create the pool ledger accounts, and emit `ChitGroupActivated`.
5. Support cancelling before activation with a defined refund waterfall.

### 6.2 Periodic payments (each cycle)
1. **Demand generation** at COLLECTION_OPEN: one demand per active ticket. Net demand = subscription minus any dividend adjustment carried forward (per `dividend_mode`), plus arrears and late fees.
2. **Payment intake:** simulated channels (UPI, bank transfer, cash at foreman, auto-debit mandate). Every payment carries an idempotency key.
3. **Allocation order** (configurable, default): late fees → oldest arrears → current instalment → advance. Support partial and advance payments.
4. **Late fees** accrue per `late_fee_rule` after `grace_days`; show a daily accrual preview.
5. **Bounce/reversal** of a payment reverses its allocation and re-opens the demand.
6. **Receipts** generated per payment; ticket statement and group statement endpoints.
7. **Default detection:** a scheduled job flags tickets meeting `default_threshold` and emits `TicketDefaulted`.
8. **Auction gate:** the auction may open only when `auction_quorum_rule` is satisfied; otherwise the cycle is blocked or the foreman guarantee covers the gap (and that exposure is recorded as a receivable from the defaulting ticket).

### 6.3 Auction and prize payout
1. **Eligibility:** only non-prized, non-defaulted tickets (or defaulted tickets that clear arrears first, per configuration) may bid.
2. **Bid capture:** validate discount within bounds; for OPEN_ASCENDING, each bid must exceed the current highest; for SEALED_BID, reveal at close; record bid time with server timestamp.
3. **Winner determination:** highest discount wins; apply `tie_break_rule`. For LOTTERY and FIXED_ORDER, use a recorded, reproducible draw (store the seed and draw record for audit).
4. **No-bid handling:** apply `no_bid_rule`.
5. **Last cycle:** the one remaining non-prized ticket receives the full pot with no auction and no discount.
6. **Computation** (all amounts rounded per section 9): `D`, `W = P − D`, `C = commission_pct × P`, `DP = D − C`, per-ticket dividend by the beneficiary rule, using largest-remainder allocation so shares sum exactly to `DP`.
7. **Payout pre-conditions:** security verified, arrears cleared, payout approval completed (maker-checker). Apply `payout_hold_pct` if configured.
8. **Disbursement:** create a payout instruction to the winner's registered account (simulated), post ledger entries, mark the ticket PRIZED, record the cycle's winner.
9. **Post-prize obligation:** the prized ticket keeps its remaining instalments per `prized_subscription_rule`; its future-instalment obligation is tracked as a forward liability.
10. Emit `AuctionClosed`, `PrizeDisbursed`, `DividendDeclared`.

### 6.4 Defaults and recovery
- **Non-prized defaulter:** loses dividend entitlement for defaulted cycles (configurable); remaining contributions are handled at closure per rule.
- **Prized defaulter:** recovery against security (deposit, lien, guarantor); record shortfall; foreman guarantee mode decides who bears the loss.
- Ledger every recovery step; support write-off with approval.

### 6.5 Ticket transfer and exit
- Transfer a ticket to another customer (not allowed while arrears exist); transfer fee optional; history preserved.
- Voluntary exit: allowed only for non-prized tickets, with a settlement rule (refund of paid contributions less penalty, or replacement subscriber required). Prized tickets cannot exit before settling remaining obligations.

### 6.6 Closure
Closure runs after the final cycle is SETTLED:
1. **Pre-checks:** every ticket has won exactly once (or is explicitly resolved via exit/default); every cycle SETTLED; no open demands, payouts or disputes.
2. **Final settlement:** reconcile each ticket (total paid, dividends received, prize received); release or return securities and held-back amounts; settle residual dividend/refund balances; recognise foreman commission and late-fee income.
3. **Reconciliation:** pool account balance must be zero; sum of all inflows equals sum of all outflows plus recognised income; produce a closure report (ticket-wise and cycle-wise).
4. Move group to CLOSED and make it immutable except for audit reads. Unclaimed balances go to a defined holding account with an ageing report.
5. Support early termination (approved by foreman and rule-based quorum) with a defined refund waterfall.

## 7. Data model (tables; adapt to existing conventions)

`chit_group`, `chit_group_rules_version`, `chit_ticket`, `chit_subscriber_link` (customer ↔ ticket), `chit_cycle`, `chit_demand`, `chit_payment`, `chit_payment_allocation`, `chit_late_fee`, `chit_auction`, `chit_bid`, `chit_draw_record`, `chit_prize_payout`, `chit_dividend`, `chit_security`, `chit_default_case`, `chit_ticket_transfer`, `chit_closure_report`, `chit_audit_event`.
Every table: surrogate key, business key, version column for optimistic locking, created/updated audit columns, and constraints enforcing invariants (e.g. one winner per cycle, one prize per ticket, non-negative amounts). Provide Flyway migrations and JPA entities.

## 8. Ledger (double-entry) — required journal templates

Define ledger accounts per group: `POOL_CASH`, `SUBSCRIBER_RECEIVABLE` (per ticket), `PRIZE_PAYABLE`, `DIVIDEND_PAYABLE` (per ticket), `COMMISSION_INCOME`, `LATE_FEE_INCOME`, `SECURITY_DEPOSIT_LIABILITY`, `FOREMAN_GUARANTEE_RECEIVABLE`, `UNCLAIMED`.
Provide journal templates for: enrolment fee, security deposit, demand raised, payment received, payment reversed, late fee accrued, auction settled (commission and dividend recognised), prize disbursed, dividend adjusted/paid, default and recovery, write-off, exit refund, closure sweep. Every posting is idempotent and references its source event.

## 9. Money rules

- Use `NUMERIC(18,2)` and `BigDecimal`; no floating point.
- Define one rounding mode and apply it consistently; shares are allocated with the **largest-remainder method**, with deterministic assignment of leftover paise (e.g. by ticket number).
- **Conservation invariant:** per cycle, `collections = prize + commission + dividend pool` (after accounting for arrears/foreman guarantee). Assert this in code and tests.

## 10. APIs (REST) and events (Kafka)

REST (indicative): create/update/activate/cancel group; list/enrol/transfer/exit tickets; get cycle calendar; list demands; post payment; reverse payment; open/close auction; place bid; declare winner; approve and disburse payout; default cases; closure pre-check; run closure; statements and reports.
All write endpoints take an idempotency key; all list endpoints paginate.
Events: `ChitGroupActivated`, `DemandRaised`, `PaymentReceived`, `PaymentReversed`, `LateFeeAccrued`, `TicketDefaulted`, `AuctionOpened`, `BidPlaced`, `AuctionClosed`, `PrizeDisbursed`, `DividendDeclared`, `TicketTransferred`, `GroupClosed`. Use an outbox pattern so DB writes and events cannot diverge.

## 11. UI (Angular)

- **Foreman/admin:** group wizard with live validation and a schedule preview; enrolment board; cycle dashboard (collected vs due, defaulters); auction console; payout approval queue; defaults and recovery; closure wizard with the reconciliation report.
- **Subscriber:** my chits; my ticket statement; upcoming instalment and net amount; bid screen when eligible; dividend and prize history; transfer/exit request.

## 12. Non-functional requirements

- Maker-checker on payout, write-off, transfer, early termination.
- Full audit trail for every state change and every config change.
- Concurrency: auction close and payout must be safe under concurrent requests (locking and unique constraints).
- Scheduling: timezone Asia/Kolkata, holiday-aware business-day calendar.
- Observability: structured logs with group, cycle and ticket IDs.
- Security: role-based access (foreman, auditor, subscriber).

## 13. Worked example (use as a test fixture; figures are illustrative only)

Chit value ₹1,00,000, N = 20, S = ₹5,000, commission 5% of P, dividend to ALL_TICKETS, ADJUST_NEXT_INSTALMENT.
Cycle 1: collections 20 × 5,000 = ₹1,00,000. Winning discount D = ₹20,000, so prize W = ₹80,000, commission C = ₹5,000, dividend pool = ₹15,000, ₹750 per ticket. Next instalment per ticket = ₹5,000 − ₹750 = ₹4,250.
Check: 80,000 + 5,000 + 15,000 = 1,00,000.
Add a second fixture where the pool does not divide evenly (e.g. a dividend pool of ₹15,001 over 20 tickets) to prove the rounding rule.

## 14. Delivery phases

1. Domain model, state machines, Flyway migrations, JPA entities, rules validation, unit tests.
2. Enrolment, activation, calendar generation, ledger accounts and journal templates.
3. Demand generation, payment intake, allocation, late fees, default detection.
4. Auction types, winner determination, payout, dividend handling, last-cycle logic.
5. Defaults/recovery, transfer/exit, security handling.
6. Closure, reconciliation, reports.
7. REST APIs, events/outbox, Angular screens.
8. End-to-end scenario tests.

## 15. Acceptance tests (Testcontainers, PostgreSQL)

- Full happy path for a small chit (e.g. 5 tickets) through activation, all cycles, closure, with pool balance zero.
- Property-style tests asserting the conservation invariant and that every ticket wins exactly once.
- Partial, advance, late and bounced payments; allocation order verified.
- Tie-break, no-bid, lottery reproducibility, and last-cycle behaviour.
- Prized-ticket default with security recovery under each foreman-guarantee mode.
- Duplicate payment/event submissions are idempotent.
- Concurrent auction-close attempts produce one winner.
- Illegal state transitions are rejected.
- Closure refuses to run with open demands or unsettled cycles.

## 16. Working rules

- Keep every rule configurable and mark real-world assumptions `TODO-VERIFY`.
- Prefer small, reviewable commits per phase, each with passing tests.
- If a requirement is ambiguous, state your assumption explicitly instead of silently choosing.
