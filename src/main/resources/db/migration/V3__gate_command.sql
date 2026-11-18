-- The commands of the gate (HAS-449): every opening sent to the Shelly of the gate and every one refused before it -
-- from the indoor screen (an unlock of the door station) or from the dashboard (by a member of the household).

CREATE TABLE gate_command
(
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at      TIMESTAMP   NOT NULL,
    source  VARCHAR(16) NOT NULL,
    -- who opened it from the dashboard, as the household registry spells the name
    member  VARCHAR(50),
    -- OPENED: the Shelly took it; FAILED: it did not answer or refused - it may have moved all the same; TOO_SOON: within
    -- gate.open.ignore-repeat-for of the one before; UNAVAILABLE: no gate to command, or a relay that does not switch
    -- itself off
    outcome VARCHAR(16) NOT NULL,
    CONSTRAINT gate_command_source CHECK (source IN ('SCREEN', 'DASHBOARD')),
    CONSTRAINT gate_command_outcome CHECK (outcome IN ('OPENED', 'FAILED', 'TOO_SOON', 'UNAVAILABLE')),
    CONSTRAINT gate_command_member CHECK ((source = 'DASHBOARD') = (member IS NOT NULL))
);

CREATE INDEX gate_command_at ON gate_command (at);
