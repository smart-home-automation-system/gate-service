-- The last reading of every BLU Door/Window sensor on a garage gate (HAS-473, the copy of window_sensor_reading of
-- blinds-service HAS-420), so that a restart keeps what the service knew: a gateway restarted with this service may
-- restore an older state with its old time, and the newer one must win. One row per sensor, replaced on a change of
-- the reading only, never on a confirmation. sensor_id - the registry id; open - what it said; updated_at - when its
-- gateway heard it (last_updated_ts, an instant, null when the gateway gave none); stored_at - when the row was written,
-- house time. Read back at the start as not confirmed: the history a gateway value is compared with, never a state
-- the gate is told by. A sensor gone from the registry loses its row once the registry lists another set of sensors.
-- Per sensor, not per gate as the task planned: the state of a gate is derived from its sensors, as a window of blinds
CREATE TABLE garage_sensor_reading
(
    sensor_id  BIGINT PRIMARY KEY       NOT NULL,
    open       BOOLEAN                  NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    stored_at  TIMESTAMP                NOT NULL
);
