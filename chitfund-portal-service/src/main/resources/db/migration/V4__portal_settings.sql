-- Text the organiser controls: how to pay, the help line and the office address. Key/value, keys are fixed in the code (SiteSettingsService).
CREATE TABLE portal_setting (
    setting_key VARCHAR(40)   PRIMARY KEY,
    value       VARCHAR(1000) NOT NULL,
    updated_by  VARCHAR(60)   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
