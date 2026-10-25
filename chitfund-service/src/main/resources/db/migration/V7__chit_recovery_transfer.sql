-- V7: defaults and recovery, write-off, ticket transfer and replacement exit (Phase 6). Runs in schema 'chit'.
--
-- Decisions behind this migration (Kiran, 25 Oct 2026):
--   * A non-prized ticket leaves by handing the same ticket record to a replacement subscriber (chit_ticket_transfer.kind =
--     'REPLACEMENT'); there is no pool refund. A transfer keeps the record, history and security with the ticket.
--   * A loss that cannot be recovered is borne by the foreman: the overdue instalment is paid into the pool by the foreman
--     (a foreman cover) and the cover is then written off to GUARANTEE_LOSS_EXPENSE.
--   * Maker and checker are enforced by the service from CHIT_MAKER_CHECKER_ENFORCE (as for payouts since V6), so the two
--     database checks that hard-wired it are dropped and a one-person demo can switch it off.

-- ---- transfers and replacement exits ------------------------------------------------------------------------------------

ALTER TABLE chit_ticket_transfer DROP CONSTRAINT ck_transfer_maker_checker;

ALTER TABLE chit_ticket_transfer ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'TRANSFER'
    CHECK (kind IN ('TRANSFER','REPLACEMENT'));
ALTER TABLE chit_ticket_transfer ADD COLUMN reason VARCHAR(200);
ALTER TABLE chit_ticket_transfer ADD COLUMN decision_reason VARCHAR(200);
-- the new holder's payout account; the old holder's account never carries over
ALTER TABLE chit_ticket_transfer ADD COLUMN payout_account_ref VARCHAR(60);
-- the ticket's status when the request was made, so a rejection puts it back exactly as it was
ALTER TABLE chit_ticket_transfer ADD COLUMN previous_status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
    CHECK (previous_status IN ('ACTIVE','PRIZED','DEFAULTED'));
ALTER TABLE chit_ticket_transfer ADD COLUMN requested_on DATE;
UPDATE chit_ticket_transfer SET requested_on = created_at::date WHERE requested_on IS NULL;
ALTER TABLE chit_ticket_transfer ALTER COLUMN requested_on SET NOT NULL;
ALTER TABLE chit_ticket_transfer ADD COLUMN effective_on DATE;
ALTER TABLE chit_ticket_transfer ADD CONSTRAINT ck_transfer_effective
    CHECK ((status = 'APPROVED') = (effective_on IS NOT NULL));
-- a fee only applies to a plain transfer, never to a replacement exit
ALTER TABLE chit_ticket_transfer ADD CONSTRAINT ck_transfer_fee_kind CHECK (kind = 'TRANSFER' OR fee_amount = 0);
CREATE INDEX idx_chit_transfer_ticket ON chit_ticket_transfer(ticket_id, id);

-- ---- demands: a written-off late fee -------------------------------------------------------------------------------------

-- A late fee the foreman gives up. It is not paid, so it gets its own column; the demand then ends as WRITTEN_OFF.
ALTER TABLE chit_demand ADD COLUMN late_fee_waived NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (late_fee_waived >= 0);
-- 'WRITTEN_OFF' is longer than the VARCHAR(8) the column had (found by running the write-off against a real Postgres)
ALTER TABLE chit_demand ALTER COLUMN status TYPE VARCHAR(12);
ALTER TABLE chit_demand DROP CONSTRAINT chit_demand_status_check;
ALTER TABLE chit_demand ADD CONSTRAINT chit_demand_status_check CHECK (status IN ('OPEN','PAID','CANCELLED','WRITTEN_OFF'));
ALTER TABLE chit_demand DROP CONSTRAINT ck_demand_fee_not_overpaid;
ALTER TABLE chit_demand ADD CONSTRAINT ck_demand_fee_not_overpaid CHECK (late_fee_paid + late_fee_waived <= late_fee_accrued);
-- a WRITTEN_OFF demand has nothing left to pay on the subscription and its remaining fee is exactly the waived amount
ALTER TABLE chit_demand ADD CONSTRAINT ck_demand_written_off CHECK (
    status <> 'WRITTEN_OFF' OR (
        subscription_paid = subscription_amount - dividend_adjustment
        AND late_fee_paid + late_fee_waived = late_fee_accrued
        AND late_fee_waived > 0));

-- ---- default cases -------------------------------------------------------------------------------------------------------

ALTER TABLE chit_default_case DROP CONSTRAINT ck_default_writeoff_mc;
-- what the held deposit has paid towards arrears and towards the foreman's covers, and what the ticket repaid the foreman
ALTER TABLE chit_default_case ADD COLUMN security_applied NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (security_applied >= 0);
ALTER TABLE chit_default_case ADD COLUMN cover_recovered NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (cover_recovered >= 0);

-- Every recovery step, one row each, so a journal always has a business event behind it and the posting keys are unique.
CREATE TABLE chit_recovery_action (
    id               BIGSERIAL PRIMARY KEY,
    -- a repayment of a foreman cover can come from a ticket that never defaulted, so the case is optional
    case_id          BIGINT        REFERENCES chit_default_case(id),
    ticket_id        BIGINT        NOT NULL REFERENCES chit_ticket(id),
    kind             VARCHAR(20)   NOT NULL CHECK (kind IN ('SECURITY_APPLIED','COVER_REPAID')),
    -- SECURITY_APPLIED: paid to arrears (pool) and to the foreman's covers; COVER_REPAID: only to the foreman's covers
    arrears_amount   NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (arrears_amount >= 0),
    guarantee_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (guarantee_amount >= 0),
    channel          VARCHAR(20),
    external_ref     VARCHAR(60),
    payment_ref      VARCHAR(40),
    business_date    DATE          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(60)   NOT NULL,
    CHECK (arrears_amount + guarantee_amount > 0),
    CHECK (kind = 'SECURITY_APPLIED' OR arrears_amount = 0),
    CHECK (kind = 'COVER_REPAID' OR case_id IS NOT NULL)
);
CREATE INDEX idx_chit_recovery_action_ticket ON chit_recovery_action(ticket_id, id);

-- ---- write-off (maker-checker) --------------------------------------------------------------------------------------------

-- The amounts are a snapshot taken when the maker asks. The checker can only approve what is still true: if anything moved in
-- between, the service refuses and the maker asks again.
CREATE TABLE chit_write_off (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             BIGINT        NOT NULL REFERENCES chit_default_case(id),
    ticket_id           BIGINT        NOT NULL REFERENCES chit_ticket(id),
    status              VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    -- overdue subscription the foreman pays into the pool and then writes off
    subscription_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (subscription_amount >= 0),
    -- balance of earlier foreman covers on this ticket that is written off
    cover_amount        NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (cover_amount >= 0),
    -- late fees on the overdue demands, given up (the foreman's income, not the pool's)
    fee_amount          NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    reason              VARCHAR(200)  NOT NULL,
    made_by             VARCHAR(60)   NOT NULL,
    checked_by          VARCHAR(60),
    decision_reason     VARCHAR(200),
    requested_on        DATE          NOT NULL,
    decided_on          DATE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_at          TIMESTAMPTZ,
    CHECK (subscription_amount + cover_amount + fee_amount > 0),
    CONSTRAINT ck_write_off_decided CHECK (status = 'PENDING' OR (checked_by IS NOT NULL AND decided_on IS NOT NULL))
);
CREATE UNIQUE INDEX uq_chit_write_off_pending ON chit_write_off(case_id) WHERE status = 'PENDING';
CREATE INDEX idx_chit_write_off_ticket ON chit_write_off(ticket_id, id);
