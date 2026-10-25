-- Customer master: the e-mail address is the unique key, the id is the internal customer id used everywhere else (tickets, subscriber links,
-- transfers, guarantors). Customers that already exist as a bare number in those tables get a row here when they are first invited or looked up
-- by e-mail (their existing id is kept). New customers get a fresh id from the sequence, which never hands out an id already in use.
CREATE TABLE chit_customer (
    id           BIGSERIAL PRIMARY KEY,
    email        VARCHAR(254) NOT NULL,
    display_name VARCHAR(120),
    source       VARCHAR(12)  NOT NULL DEFAULT 'INVITATION' CHECK (source IN ('INVITATION','LEGACY_LINK')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(60)  NOT NULL,
    CONSTRAINT ck_chit_customer_email CHECK (email = lower(email) AND email = btrim(email) AND email LIKE '%_@_%')
);
CREATE UNIQUE INDEX uq_chit_customer_email ON chit_customer(email);

-- start the sequence above every customer id already in use, so a new customer cannot collide with an existing one
SELECT setval('chit_customer_id_seq', GREATEST(
    (SELECT COALESCE(MAX(customer_id), 0) FROM chit_ticket),
    (SELECT COALESCE(MAX(customer_id), 0) FROM chit_subscriber_link),
    1000));
