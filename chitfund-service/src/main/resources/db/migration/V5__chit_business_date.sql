-- Phase 4b: the chitfund owns its business date and working-day calendar (it no longer reads them from lending-service).

-- One row. MANAGED mode reads and moves it; SYSTEM mode ignores it and follows the wall clock.
CREATE TABLE chit_business_date (
    id            SMALLINT PRIMARY KEY CHECK (id = 1),
    business_date DATE        NOT NULL,
    status        VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'EOD_RUNNING')),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    VARCHAR(60) NOT NULL DEFAULT 'system'
);
INSERT INTO chit_business_date (id, business_date, status) VALUES (1, (now() AT TIME ZONE 'Asia/Kolkata')::date, 'OPEN');

-- Days that are not working days on top of the weekend (the weekend itself is the setting chit.calendar.weekend).
CREATE TABLE chit_holiday (
    holiday_date DATE PRIMARY KEY,
    name         VARCHAR(100) NOT NULL CHECK (length(btrim(name)) > 0),
    created_by   VARCHAR(60)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
