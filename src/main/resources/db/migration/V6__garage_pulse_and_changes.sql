-- The pulses of the garage gates and the changes of their state (HAS-474).
-- gate_command gains the registry id of the gate a command went to: null for the driveway gate (every row before V6, and
-- every opening of the driveway gate - the screen and POST /open), the garage gate of a pulse (POST /garage/{id}/pulse)
-- otherwise. A pulse is the outcome PULSED: one pulse opens, stops or closes a garage gate alike.
ALTER TABLE gate_command
    ADD COLUMN gate_id BIGINT;

ALTER TABLE gate_command
    DROP CONSTRAINT gate_command_outcome;

ALTER TABLE gate_command
    ADD CONSTRAINT gate_command_outcome CHECK (outcome IN ('OPENED', 'PULSED', 'FAILED', 'TOO_SOON', 'UNAVAILABLE'));

-- A pulse goes to a garage gate, an opening to the driveway gate
ALTER TABLE gate_command
    ADD CONSTRAINT gate_command_pulse_of_a_garage_gate CHECK ((outcome = 'PULSED') <= (gate_id IS NOT NULL));

-- The changes of the state of a garage gate, as its sensor reported them: at - when its gateway heard it (house time);
-- open - what it became; open_since - for a closing, since when the gate had stood open, null when not known.
-- Written on a change of the value only, never on a confirmation; kept as long as the other tables (gate.retention)
CREATE TABLE garage_gate_change
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    gate_id    BIGINT    NOT NULL,
    at         TIMESTAMP NOT NULL,
    open       BOOLEAN   NOT NULL,
    open_since TIMESTAMP,
    CONSTRAINT garage_gate_change_open_since CHECK (NOT open OR open_since IS NULL)
);

CREATE INDEX garage_gate_change_at ON garage_gate_change (at);
