-- V5: a signed-in member asks to join an open plan; staff approve or decline. Replaces invitations as the way in.
-- A request is only a request: it takes no seat and charges nothing. group_code and group_name are a snapshot (the group lives in chitfund-service).
CREATE TABLE portal_join_request (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT       NOT NULL REFERENCES portal_member(id),
    email       VARCHAR(254) NOT NULL,
    member_name VARCHAR(120),
    group_code  VARCHAR(30)  NOT NULL,
    group_name  VARCHAR(120) NOT NULL,
    tickets     INTEGER      NOT NULL CHECK (tickets BETWEEN 1 AND 3),
    status      VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','DECLINED','WITHDRAWN')),
    note        VARCHAR(300),
    decided_by  VARCHAR(60),
    decided_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_join_decided CHECK ((status IN ('APPROVED','DECLINED')) = (decided_at IS NOT NULL AND decided_by IS NOT NULL))
);
-- One live request per member and group; a declined or withdrawn one does not block a new request.
CREATE UNIQUE INDEX uq_join_live ON portal_join_request (member_id, group_code) WHERE status IN ('PENDING','APPROVED');
CREATE INDEX ix_join_member ON portal_join_request (member_id, id DESC);
CREATE INDEX ix_join_status ON portal_join_request (status, id DESC);
CREATE TRIGGER trg_portal_join_request_updated_at BEFORE UPDATE ON portal_join_request FOR EACH ROW EXECUTE FUNCTION set_updated_at();
