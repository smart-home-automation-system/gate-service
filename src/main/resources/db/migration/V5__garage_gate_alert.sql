-- The alarms of a garage gate left open (HAS-475): one episode per gate and kind - COLD (open while it is cold outside),
-- EMPTY_HOUSE (open while nobody is at home), LONG (open for long) - from the first message to the closing of the gate,
-- so a restart neither repeats nor loses one. open_since - since when the gate stands open, the time its gateway heard
-- it (house time); raised_at - the first message; last_message_at - the last message of the episode; reminders - the
-- repeats told (at most 10, the reminder of the payload); closed_at - when the episode ended, null while open.
CREATE TABLE garage_gate_alert
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    gate_id         BIGINT      NOT NULL,
    kind            VARCHAR(16) NOT NULL,
    open_since      TIMESTAMP   NOT NULL,
    raised_at       TIMESTAMP   NOT NULL,
    last_message_at TIMESTAMP   NOT NULL,
    reminders       INTEGER     NOT NULL,
    closed_at       TIMESTAMP,
    CONSTRAINT garage_gate_alert_kind CHECK (kind IN ('COLD', 'EMPTY_HOUSE', 'LONG')),
    CONSTRAINT garage_gate_alert_reminders CHECK (reminders BETWEEN 0 AND 10)
);

-- one open episode per gate and kind
CREATE UNIQUE INDEX garage_gate_alert_open_uq ON garage_gate_alert (gate_id, kind) WHERE closed_at IS NULL;
