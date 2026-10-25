-- Offers and marketing material staff publish for members (and, for public ones, for visitors who have not signed in).
-- Text and links only: no files are stored here. An offer is live between starts_on and ends_on (both inclusive, ends_on optional) while active.
CREATE TABLE portal_offer (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(120) NOT NULL CHECK (length(btrim(title)) > 0),
    body       VARCHAR(600) NOT NULL DEFAULT '',
    link_url   VARCHAR(500),
    image_url  VARCHAR(500),
    audience   VARCHAR(10)  NOT NULL CHECK (audience IN ('PUBLIC', 'MEMBERS')),
    starts_on  DATE         NOT NULL,
    ends_on    DATE,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by VARCHAR(60)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by VARCHAR(60),
    updated_at TIMESTAMPTZ,
    CONSTRAINT ck_offer_dates CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_portal_offer_live ON portal_offer (active, starts_on, ends_on);
