-- The entrance panel at the door (HAS-483): a third source of an opening of the driveway gate, without a member - the
-- check of the member (V3) keeps a member to the dashboard alone
ALTER TABLE gate_command
    DROP CONSTRAINT gate_command_source;

ALTER TABLE gate_command
    ADD CONSTRAINT gate_command_source CHECK (source IN ('SCREEN', 'DASHBOARD', 'PANEL'));
