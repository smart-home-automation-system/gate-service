-- The events of the door station at the gate (HAS-448): a ring, a dismissed call, an unlock - as the Hikvision bridge
-- publishes them (deployment-tools/scripts/hikvision/devices.md). No picture: the station gives none (HAS-444).

CREATE TABLE intercom_event
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind           VARCHAR(16) NOT NULL,
    -- when it happened, in the time of the house: for an unlock the last_unlocked of the station, for a ring or a
    -- dismissed call the moment the service heard it (their topics carry no time)
    occurred_at    TIMESTAMP   NOT NULL,
    received_at    TIMESTAMP   NOT NULL,
    -- the number of the button of the station a ring came from
    device_number  VARCHAR(32),
    unlock_type    VARCHAR(32),
    -- what the station names as the source of an unlock: the number of the indoor screen as ASCII codes
    control_source VARCHAR(64),
    number         VARCHAR(32),
    door           SMALLINT,
    CONSTRAINT intercom_event_kind CHECK (kind IN ('RING', 'DISMISSED', 'UNLOCK')),
    CONSTRAINT intercom_event_unlock_door CHECK ((kind = 'UNLOCK') = (door IS NOT NULL)),
    CONSTRAINT intercom_event_door_range CHECK (door BETWEEN 1 AND 9)
);

-- an unlock is one row: the station names it by the time it happened, so the attributes the bridge publishes again
-- (a restart of the bridge, a repeat of the broker) are the same unlock, not a second opening of the gate
CREATE UNIQUE INDEX intercom_event_unlock_uq ON intercom_event (door, occurred_at) WHERE kind = 'UNLOCK';

CREATE INDEX intercom_event_occurred_at ON intercom_event (occurred_at);
