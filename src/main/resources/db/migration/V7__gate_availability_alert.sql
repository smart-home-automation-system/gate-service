-- The alerts of the availability monitor (HAS-453): one row for each thing found out - the bridge, the Shelly of a gate,
-- a relay that does not switch itself off, a garage gate not known - kept here, not in memory, so that a restart neither
-- repeats an alert nor forgets to announce a return (the shape of aircon_availability_alert of aircon-service).
-- item is the key of what is watched (bridge, shelly:147, auto-off:148, garage:149), name how the messages name it,
-- since when it is out; told - whether the alert got through: a row is written when the alert is due and marked told once
-- the broker took it, so that an alert that never got through is still announced with the return; last_alert_at the
-- alert or the last reminder told
CREATE TABLE gate_availability_alert
(
    item          VARCHAR(50) PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    since         TIMESTAMP    NOT NULL,
    told          BOOLEAN      NOT NULL,
    last_alert_at TIMESTAMP
);
