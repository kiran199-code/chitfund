-- Accepting an invitation enrols the person: the customer id (from chitfund-service, keyed by e-mail) and the tickets enrolled are kept here.
-- V1 sized status as VARCHAR(24) but 'ACCEPTED_AWAITING_PAYMENT' is 25 characters, so that status could never be stored.
ALTER TABLE portal_invitation ALTER COLUMN status TYPE VARCHAR(30);

ALTER TABLE portal_invitation
    ADD COLUMN existing_customer_id BIGINT,                       -- an old customer id the admin named when inviting (optional)
    ADD COLUMN customer_id          BIGINT,                       -- the customer in chitfund-service: known at invite time if the e-mail is already a customer, else set on accept
    ADD COLUMN tickets_enrolled     INTEGER NOT NULL DEFAULT 0;

ALTER TABLE portal_invitation
    ADD CONSTRAINT ck_invite_existing_customer CHECK (existing_customer_id IS NULL OR existing_customer_id > 0),
    ADD CONSTRAINT ck_invite_tickets_enrolled CHECK (tickets_enrolled >= 0 AND tickets_enrolled <= tickets_offered),
    ADD CONSTRAINT ck_invite_accepted_enrolled CHECK (status <> 'ACCEPTED' OR (tickets_enrolled >= 1 AND customer_id IS NOT NULL)),
    ADD CONSTRAINT ck_invite_awaiting_customer CHECK (status <> 'ACCEPTED_AWAITING_PAYMENT' OR customer_id IS NOT NULL);

-- Which tickets of the group this invitation enrolled (seq 1 .. tickets offered). A retry skips the ones already here.
CREATE TABLE portal_invitation_ticket (
    invitation_id BIGINT      NOT NULL REFERENCES portal_invitation(id),
    seq           INTEGER     NOT NULL CHECK (seq >= 1),
    ticket_no     INTEGER     NOT NULL CHECK (ticket_no >= 1),
    enrolled_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (invitation_id, seq)
);
