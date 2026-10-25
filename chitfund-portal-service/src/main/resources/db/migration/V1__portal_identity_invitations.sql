-- V1: identity, invitations, sessions, mail outbox. Runs in schema 'portal' (its own Flyway history table).
-- Nothing here stores a password, a Google token or a raw invite token (the one exception, the mail body waiting in the
-- outbox, is blanked as soon as the mail is sent; see portal_outbox.body_redacted).

CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END $$ LANGUAGE plpgsql;

-- A person who can sign in to the portal. Created at first Google sign-in. google_sub is Google's stable account id.
CREATE TABLE portal_member (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    display_name  VARCHAR(120),
    google_sub    VARCHAR(255),
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BLOCKED')),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_member_email_lower CHECK (email = lower(email) AND email = btrim(email) AND email LIKE '%_@_%')
);
CREATE UNIQUE INDEX uq_portal_member_email ON portal_member(email);
CREATE UNIQUE INDEX uq_portal_member_sub ON portal_member(google_sub) WHERE google_sub IS NOT NULL;
CREATE TRIGGER trg_portal_member_updated_at BEFORE UPDATE ON portal_member FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One invitation. The group lives in chitfund-service, so there is no foreign key to it; group_code and group_name are a
-- snapshot taken when the invite was made.
CREATE TABLE portal_invitation (
    id                 BIGSERIAL PRIMARY KEY,
    group_code         VARCHAR(30)  NOT NULL,
    group_name         VARCHAR(120) NOT NULL,
    email              VARCHAR(254) NOT NULL,
    invitee_name       VARCHAR(120),
    tickets_offered    INTEGER      NOT NULL DEFAULT 1 CHECK (tickets_offered >= 1),
    status             VARCHAR(24)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED_AWAITING_PAYMENT','ACCEPTED','DECLINED','EXPIRED','REVOKED')),
    -- SHA-256 of the token in the emailed link, hex. The token itself is never stored here.
    token_hash         CHAR(64)     NOT NULL,
    token_version      INTEGER      NOT NULL DEFAULT 1 CHECK (token_version >= 1),
    expires_at         TIMESTAMPTZ  NOT NULL,
    member_id          BIGINT       REFERENCES portal_member(id),
    payout_account_ref VARCHAR(60),
    created_by         VARCHAR(60)  NOT NULL,
    sent_at            TIMESTAMPTZ,
    first_opened_at    TIMESTAMPTZ,
    accepted_at        TIMESTAMPTZ,
    declined_at        TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    revoked_by         VARCHAR(60),
    revoke_reason      VARCHAR(200),
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_invite_email_lower CHECK (email = lower(email) AND email = btrim(email)),
    CONSTRAINT ck_invite_declined CHECK ((status = 'DECLINED') = (declined_at IS NOT NULL)),
    CONSTRAINT ck_invite_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL AND revoked_by IS NOT NULL)),
    CONSTRAINT ck_invite_accepted CHECK (status NOT IN ('ACCEPTED_AWAITING_PAYMENT','ACCEPTED')
        OR (accepted_at IS NOT NULL AND member_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_portal_invitation_token ON portal_invitation(token_hash);
-- At most one live invitation per group and address; a declined, expired or revoked one does not block a new invite.
CREATE UNIQUE INDEX uq_portal_invitation_live ON portal_invitation(group_code, email)
    WHERE status IN ('PENDING','ACCEPTED_AWAITING_PAYMENT','ACCEPTED');
CREATE INDEX idx_portal_invitation_group ON portal_invitation(group_code, id);
CREATE INDEX idx_portal_invitation_email ON portal_invitation(email);
CREATE TRIGGER trg_portal_invitation_updated_at BEFORE UPDATE ON portal_invitation FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- A signed-in browser session. Only the hash of the cookie value is stored; a session can be revoked.
CREATE TABLE portal_session (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT      NOT NULL REFERENCES portal_member(id),
    token_hash  CHAR(64)    NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    user_agent  VARCHAR(200),
    ip          VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_portal_session_token ON portal_session(token_hash);
CREATE INDEX idx_portal_session_member ON portal_session(member_id);

-- Mail waiting to be sent. Written in the same transaction as the business change, sent afterwards, so a mail failure never
-- undoes or blocks the action. The dedupe key makes a rerun write the same mail only once.
CREATE TABLE portal_outbox (
    id               BIGSERIAL PRIMARY KEY,
    kind             VARCHAR(30)  NOT NULL,
    recipient_email  VARCHAR(254) NOT NULL,
    subject          VARCHAR(200) NOT NULL,
    body_text        TEXT         NOT NULL,
    -- true once sent: body_text is then replaced so a link token does not stay in the database
    body_redacted    BOOLEAN      NOT NULL DEFAULT FALSE,
    dedupe_key       VARCHAR(120) NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts         INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),
    provider_message_id VARCHAR(120),
    related_invitation_id BIGINT  REFERENCES portal_invitation(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    CONSTRAINT ck_outbox_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_portal_outbox_dedupe ON portal_outbox(dedupe_key);
CREATE INDEX idx_portal_outbox_due ON portal_outbox(next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE idempotency_key (
    idem_key     VARCHAR(100) PRIMARY KEY,
    operation    VARCHAR(40)  NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    result_ref   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE portal_audit (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(30)  NOT NULL,
    entity_ref  VARCHAR(80)  NOT NULL,
    action      VARCHAR(30)  NOT NULL,
    actor       VARCHAR(254) NOT NULL,
    source_ip   VARCHAR(64),
    detail      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_portal_audit_entity ON portal_audit(entity_type, entity_ref, id);
