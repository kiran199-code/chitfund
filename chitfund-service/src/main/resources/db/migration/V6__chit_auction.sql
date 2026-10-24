-- V6 (Phase 5): auctions, winner selection, prize payout, dividends, last cycle, liquidity rule. Runs in schema 'chit'.
-- V1 already holds chit_auction, chit_bid, chit_draw_record, chit_prize_payout and chit_dividend. This migration adds
-- what the code needs on top of them and two small tables (payout legs, foreman cover).

-- ---- auctions ---------------------------------------------------------------------------------------------------------

-- A cycle has at most one live auction. Under ROLL_OVER_CYCLE a no-bid auction is kept as ROLLED_OVER and the cycle
-- gets a fresh one later, so uniqueness applies to every status except ROLLED_OVER.
ALTER TABLE chit_auction DROP CONSTRAINT chit_auction_cycle_id_key;
CREATE UNIQUE INDEX uq_chit_auction_live ON chit_auction(cycle_id) WHERE status <> 'ROLLED_OVER';
ALTER TABLE chit_auction ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt >= 1);
-- How the winner was decided: the highest bid, a tie-break draw, a no-bid rule, a lottery / fixed-order auction, the last cycle.
ALTER TABLE chit_auction ADD COLUMN close_basis VARCHAR(24)
    CHECK (close_basis IN ('HIGHEST_BID','TIE_BREAK_EARLIEST','TIE_BREAK_LOWEST_NO','TIE_BREAK_DRAW','NO_BID_LOTTERY',
                           'NO_BID_FOREMAN_DEFAULT','LOTTERY','FIXED_ORDER','LAST_CYCLE','ROLLED_OVER'));
ALTER TABLE chit_auction ADD COLUMN discount_amount NUMERIC(18,2) CHECK (discount_amount >= 0);
ALTER TABLE chit_auction ADD COLUMN draw_record_id BIGINT REFERENCES chit_draw_record(id);
ALTER TABLE chit_auction ADD COLUMN settlement_journal_id BIGINT;
-- What the gate found when the auction opened (quorum rule, amounts), kept for the audit trail.
ALTER TABLE chit_auction ADD COLUMN gate_note VARCHAR(300);
ALTER TABLE chit_auction ADD CONSTRAINT ck_auction_closed_basis CHECK (status = 'OPEN' OR close_basis IS NOT NULL);

-- Bids: the operator who typed it in, and a withdrawal reason. Sealed bids stay hidden until the auction closes.
ALTER TABLE chit_bid ADD COLUMN withdrawn_reason VARCHAR(120);
ALTER TABLE chit_bid ADD COLUMN withdrawn_by VARCHAR(60);
CREATE INDEX idx_chit_bid_auction ON chit_bid(auction_id, discount_amount DESC);

-- Draws: one per purpose and cycle, and a single fixed order per group.
CREATE UNIQUE INDEX uq_chit_draw_cycle_purpose ON chit_draw_record(cycle_id, purpose) WHERE purpose <> 'FIXED_ORDER';
CREATE UNIQUE INDEX uq_chit_draw_fixed_order ON chit_draw_record(group_id) WHERE purpose = 'FIXED_ORDER';

-- ---- cycles -----------------------------------------------------------------------------------------------------------

-- The business date on which the auction closed and the winner was recorded.
ALTER TABLE chit_cycle ADD COLUMN closed_on DATE;

-- ---- payouts ----------------------------------------------------------------------------------------------------------

-- Maker-checker is enforced by the service according to CHIT_MAKER_CHECKER_ENFORCE; with it off a single person may do both
-- (a demo set-up), so the database may not forbid it. The audit columns still record who did what.
ALTER TABLE chit_prize_payout DROP CONSTRAINT ck_payout_maker_checker;
ALTER TABLE chit_prize_payout DROP CONSTRAINT chit_prize_payout_status_check;
ALTER TABLE chit_prize_payout ADD CONSTRAINT ck_payout_status
    CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED','PART_PAID','DISBURSED'));
-- PAY_AVAILABLE_AND_BALANCE_LATER pays in legs; paid_gross / paid_hold are the running totals.
ALTER TABLE chit_prize_payout ADD COLUMN paid_gross NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (paid_gross >= 0);
ALTER TABLE chit_prize_payout ADD COLUMN paid_hold  NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (paid_hold >= 0);
-- FOREMAN_FLOATS: what the foreman put into the pool for this prize, and what has been paid back.
ALTER TABLE chit_prize_payout ADD COLUMN float_amount   NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (float_amount >= 0);
ALTER TABLE chit_prize_payout ADD COLUMN float_repaid   NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (float_repaid >= 0);
ALTER TABLE chit_prize_payout ADD COLUMN decision_note  VARCHAR(200);
ALTER TABLE chit_prize_payout ADD COLUMN approved_at    TIMESTAMPTZ;
ALTER TABLE chit_prize_payout ADD COLUMN disbursed_by   VARCHAR(60);
ALTER TABLE chit_prize_payout ADD COLUMN account_ref    VARCHAR(60);
ALTER TABLE chit_prize_payout ADD CONSTRAINT ck_payout_paid_within CHECK (paid_gross <= gross_prize AND paid_hold <= hold_amount AND paid_hold <= paid_gross);
ALTER TABLE chit_prize_payout ADD CONSTRAINT ck_payout_float_within CHECK (float_repaid <= float_amount);
ALTER TABLE chit_prize_payout ADD CONSTRAINT ck_payout_done_means_paid CHECK (status <> 'DISBURSED' OR (paid_gross = gross_prize AND paid_hold = hold_amount));

-- Every movement of a payout: a payment leg to the winner, the foreman's float and its repayment.
CREATE TABLE chit_payout_leg (
    id              BIGSERIAL PRIMARY KEY,
    payout_id       BIGINT        NOT NULL REFERENCES chit_prize_payout(id),
    leg_no          INTEGER       NOT NULL CHECK (leg_no >= 1),
    kind            VARCHAR(16)   NOT NULL CHECK (kind IN ('DISBURSEMENT','FLOAT','FLOAT_REPAYMENT')),
    amount          NUMERIC(18,2) NOT NULL CHECK (amount > 0),   -- gross for a DISBURSEMENT leg
    hold_amount     NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (hold_amount >= 0),
    instruction_ref VARCHAR(60),                                   -- the simulated payout instruction
    business_date   DATE          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(60)   NOT NULL,
    UNIQUE (payout_id, leg_no),
    CHECK (hold_amount <= amount)
);

-- ---- dividends --------------------------------------------------------------------------------------------------------

ALTER TABLE chit_dividend ADD COLUMN declared_on DATE;
ALTER TABLE chit_dividend ADD COLUMN settled_on DATE;
CREATE INDEX idx_chit_dividend_ticket ON chit_dividend(ticket_id, status);

-- ---- payments made by the platform itself -----------------------------------------------------------------------------

-- A foreman cover and a dividend credited to the subscriber's balance are recorded as payments so that the demands,
-- the advance and the statements stay one mechanism. They carry the channel INTERNAL, which no caller can submit
-- and which cannot be reversed.
ALTER TABLE chit_payment DROP CONSTRAINT chit_payment_channel_check;
ALTER TABLE chit_payment ADD CONSTRAINT chit_payment_channel_check
    CHECK (channel IN ('UPI','BANK_TRANSFER','CASH_AT_FOREMAN','AUTO_DEBIT','INTERNAL'));

-- ---- foreman cover ----------------------------------------------------------------------------------------------------

-- When the quorum rule is FOREMAN_COVERS_SHORTFALL the foreman pays an unpaid instalment into the pool. The ticket owes
-- the foreman that amount (FOREMAN_GUARANTEE_RECEIVABLE); recovery is Phase 6.
CREATE TABLE chit_foreman_cover (
    id               BIGSERIAL PRIMARY KEY,
    cycle_id         BIGINT        NOT NULL REFERENCES chit_cycle(id),
    ticket_id        BIGINT        NOT NULL REFERENCES chit_ticket(id),
    demand_id        BIGINT        NOT NULL UNIQUE REFERENCES chit_demand(id),
    payment_id       BIGINT        NOT NULL REFERENCES chit_payment(id),
    amount           NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    recovered_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (recovered_amount >= 0),
    status           VARCHAR(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RECOVERED','WRITTEN_OFF')),
    covered_on       DATE          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(60)   NOT NULL,
    CHECK (recovered_amount <= amount)
);
CREATE INDEX idx_chit_foreman_cover_ticket ON chit_foreman_cover(ticket_id);
