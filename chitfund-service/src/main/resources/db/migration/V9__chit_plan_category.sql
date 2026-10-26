-- V9: how a group is shown on the customer portal's plan shelf. All three are optional; a group without a category appears only under "All Plans".
ALTER TABLE chit_group ADD COLUMN plan_category VARCHAR(20)
    CHECK (plan_category IS NULL OR plan_category IN ('EDUCATION','HOME','WEDDING','SHORT_TERM','RETIREMENT','BUSINESS'));
ALTER TABLE chit_group ADD COLUMN branch VARCHAR(60);
ALTER TABLE chit_group ADD COLUMN state VARCHAR(40);
CREATE INDEX idx_chit_group_plan ON chit_group(status, plan_category);
