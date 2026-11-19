# gate-service

The driveway gate and the intercom of the house (epic HAS-442): the events of the Hikvision door station at the
gate, the command of the Shelly that opens the gate, their log and the household told. The scaffold of HAS-446,
from the scaffold of aircon-service (itself from energy-service and water-service): Spring Boot 4.1.2,
cholewa-commons 1.14.0, smart-home-sdk 1.23.0 (HAS-473), shelly-client 2.7.0 with its beans on; port 6016 / 8016 locally,
6200 / 8200 in the cluster, base path `/home/gate`; own database `home-automation-gate`, pool 2, `V1` an empty
baseline; `database-password` mandatory; scheduling off under `test`; the Deployment `Recreate`, never scaled
(state in memory will follow). Org-wide rules: `organization-repository/claude/organization.md`.

## Role in the system

- Talks to: the intercom events on `device.prod.gate` of `/device` (HAS-448, the contract in
  `deployment-tools/scripts/hikvision/devices.md`), the Shelly 1 of the gate over HTTP (HAS-449), the household
  category of the notification exchange over a second connection to `/notification` (HAS-452, user `notification`,
  `notification-rabbitmq-password`; `NotificationRabbitConfig` - one more copy of the config kept per service); routed by the gateway under `/gate` (HAS-447).
- Uses libraries: cholewa-commons, smart-home-sdk, shelly-client.
- Database: own database `home-automation-gate`, 2 of the 47 connections of the managed PostgreSQL.
- Integrates with hardware: the Hikvision door station (through the bridge, never directly), the Shelly 1 Gen1 of
  the gate - the device registry holds both (146 `intercom`, 147 `gate` on the channel 0).

## Build & run

- Build + tests: `mvn verify` - **needs a running Docker** since HAS-448 (`IntercomEventRepositoryTest`: the partial
  unique index of V2 on Testcontainers PostgreSQL).
- Local run: `local` Spring profile, port `6016` (Actuator `8016`); in the cluster `6200`, Actuator `8200`.

## Specifics

- **The events of the door station** (HAS-448, `intercom/`): `DeviceEventConsumer` hands every message of
  `device.prod.gate` (every event of every device, about 130 a minute, manual acks, prefetch 3) to
  `IntercomEventRouter`, which drops all but the topics of the bridge for the station `intercom.device` (`gate` - the
  name in DOORBELLS of the bridge) without a word. `Call-state` attributes keep the button for the `ringing` right
  after them; `ringing` is a ring, `dismissed` the end of a call (not answered, rejected or hung up alike - an answer
  publishes nothing), `idle` nothing. **An unlock is read from the attributes of `Door-<n>-relay`** (the door from the
  topic) - one pulse of the relay; the relay states and `Door-unlocked` say it again and are not read. Its time is
  `last_unlocked`, the wall time of the container of the bridge (`intercom.bridge-zone`, UTC), moved into the time of
  the house; **`intercom_event_unlock_uq` (door, occurred_at)** keeps it one row when the bridge publishes it again,
  and `IntercomEventService` completes that refusal. A ring and a dismissed call carry no time: the moment they are
  heard - after a stop of the service, as late as the queue held them (10 min TTL). A call state the bridge publishes only
  with its polling of ISAPI on (`ring`, `onCall`, `error` - off in the manifest) is a WARN, never a row.
  The availability of the bridge (`<entity>.availability`) is `BridgeAvailability`, in memory.
- **The opening of the gate** (HAS-449, `gate/`): `GateRegistry` reads the gate of the registry (`?kind=gate&enabled=true`,
  database-service >= 0.26.0) at the start and every 5 min, and the `auto_off` of its relay from the device
  (`GateSettingsClient`, `GET /settings/relay/<channel>` - shelly-client has no read of Gen1 settings); a gate is
  **safe** only with `auto_off` above 0 - otherwise "turn on" would be a permanent signal to the drive (an ERROR, the
  alert comes with HAS-453). `GateOpener` sends one `relay(address, channel, true)` of `ShellyGen1Client`, never retried
  (no answer: it may have moved); **the slot is taken before the command** - an opening within
  `gate.open.ignore-repeat-for` (PT15S) of the last command sent is `OPEN_TOO_SOON`, so two requests at once send one
  pulse. Every opening, refused ones included, is a row of `gate_command` (V3); a failed write never fails the opening.
- **The screen** (`ScreenOpening`): the router hands every unlock to it while the event is written - **the gate does
  not wait for the database**. `HOUSEHOLDER` from a number of `gate.screens` (`10010110001`, the readable form of
  `control_source`) opens the gate, while younger than `gate.screen.max-age` (PT1M - an unlock that waited in the queue
  through a restart opens nothing) and newer than the last unlock that opened it (the bridge publishing it again). A
  card, a code or another source is stored and opens nothing.
- **The API**: `POST /open?source=dashboard&member=` (the member checked against the profiles, any case, stored as the
  registry spells it), `GET /` and `GET /events?from=&to=` (HAS-450, `GateEventService`: `intercom_event` and
  `gate_command` read newest first and merged by `Flux.mergeComparing`, the commands first into a list so that the
  read holds one connection of the pool of 2 at a time; at most 31 days,
  `INVALID_RANGE`); the codes of `GateError` are wire contract for the dashboard (`GateErrorTest`).
  No question before an opening (owner, 2026-11-16). The first real opening is checked on the gate with the owner.
- **The retention** (HAS-450, `GateRetention`): every night at 03:40 the rows of `intercom_event` and `gate_command`
  older than `gate.retention` (90 days, in days, 31 - 3650, pinned by a test) are deleted, one table after the other,
  each with its own WARN; the bound read inside the chain, the pass bounded at 5 min. After 90 days without a ring
  `GET /` leaves `lastRing` out - no row is no knowledge.
- **The household told** (HAS-452, `household/HouseholdNotifier`, read by notification-service >= 0.12.0): `doorbell
  ringing` at every ring the router hears, once per call - none within `gate.doorbell.ignore-repeat-for` (60 s) of the
  last one told, judged on instants; its subject the door station of the registry (`?kind=intercom`, read by
  `GateRegistry` in the pass of the gate, on its own - a ring before the first read is a WARN and no message),
  `acknowledgePath` `/gate` (the gate page of HAS-454). `gate opened` after a pulse the Shelly took, `source` and
  `member`, the gate of the registry its subject (`GateDevice.name`). A publish is cut off after 30 s and every error is a
  WARN - nothing told ever fails or holds the opening (the dashboard waits for the confirm of the broker, ms, at most
  30 s); the ring is told while it is written (`Mono.when`).
- **The garage gates** (HAS-473, `garage/`): a gate of the registry with `openingSensors` (database-service >= 0.27.0) is
  a garage gate (`GarageGate`), the driveway gate is the lowest id of the gates without one - `GateRegistry` keeps them
  apart in one read of `?kind=gate&enabled=true`, each garage gate with the `auto_off` of its relay
  (`GarageGateState`). **The registry holds 148 Kate and 149 Kris**, registered disabled in HAS-471 and switched on
  after the deploy of 0.6.0. **`GarageSensors` is the copy of `WindowSensors` of blinds-service** (HAS-290, HAS-420 -
  the third with heating-service, kept by the rule of three copies): the sensors the garage gates name and the BTHome
  gateways of the registry every 5 min (`GarageSensorsRefresh`, the first pass 20 s after the start - the gates are read
  in a pass of their own), which `bthomesensor` component of which gateway is the window object (0x2D) of which sensor
  read from `Shelly.GetComponents` (shelly-client `dynamicComponents`), between two reads the status topics of
  `device.prod.gate` (`GarageEventRouter`, next to the router of the intercom in `DeviceEventConsumer` - each drops
  what is not its own before reading a body). A BLU sensor has no beacon: a state holds while its gateway confirms
  it, `gate.garage.gateway-silent-after` (PT30M, in minutes, 10 min - 24 h); a gate is open when one sensor is,
  not known when one is not known, closed when all are; `since` the time its gateway heard the deciding reading.
  **`garage_sensor_reading` (V4)** - per sensor, not per gate as the task planned: the last reading, read back at the
  start as not confirmed (`GarageReadingLoader`, the rule of HAS-419/420), an older reading never replaces a newer
  one (the upsert). `GET /garage`, `/garage/{id}` (`NOT_FOUND_GATE`) - memory only, no query.
- **The pulse of a garage gate and its history** (HAS-474, `garage/GaragePulser`, `GarageChangeLog`): `POST
  /garage/{id}/pulse?source=dashboard&member=` - the member checked by `HouseholdMembers` (shared with `POST /open` since),
  one `relay(address, channel, true)`, never retried, **no repeat window** (owner, 2026-11-18: a second press stops the
  gate half-way); a relay without `auto_off` is `GATE_UNAVAILABLE`. Whether the drive reacted is told by its sensor, not
  by a clock - the clock of the gateway GRID POWER runs a few seconds ahead of the cluster: the `since` of the gate when the
  pulse went out is kept, a different one later confirms it, none within 60 s is `lastCommandConfirmed: false`, a second
  pulse within 60 s cancels the check (null). `gate_command` gains `gate_id` (V6; null for the driveway gate - every row
  before and every opening of it) and the outcome `PULSED` (only with a gate - `gate_command_pulse_of_a_garage_gate`).
  `garage_gate_change` (V6): a row for every garage gate that names a sensor whose **value** changed (`GarageSensors`
  hands `Change.flipped` - the same value heard again is never a change), at the time its gateway heard it, a closing
  with since when it stood open. **A change is written once it held for 5 s** (`GarageChangeLog.SETTLE`, the pass
  `writeSettled` every 5 s): the sensor bounces at both ends of the travel (Kate, 2026-11-19 - open, closed, open within
  2 s), so flips within 5 s of each other are one change at the last of them, a closing keeps since the opening before
  the bounce, and a bounce back to where it began writes nothing; the flips waiting are in memory - one within 5 s of a
  restart is lost; a pass the bound (4 s) cuts off puts back the change it was writing (a WARN) and leaves the rest; `GET /events` adds them as the kind `garage` (`gate`, `state`, `openForSeconds`) and the
  pulses among the commands (`gate`); both read into lists before the stream of the station (the pool of 2); the nightly
  retention deletes them with the other tables.
- **The alarms of a garage gate left open** (HAS-475, `garage/GarageAlarms`, replacing the Shelly cloud scene GARAGE
  NOTIFICATION - its midnight check is not moved, owner 2026-11-18): every minute (bounded at 50 s) the open episodes
  of `garage_gate_alert` (V5, one open per gate and kind - `garage_gate_alert_open_uq`), the temperature outside
  (`WeatherClient`, `GET /home/weather/current` of weather-service over k8s DNS - a stale, missing or failed reading
  is no cold) and who is at home (`PresenceClient` / `HouseOccupancy` - **the fifth copy** of the presence client,
  after light, appliance, water and aircon - kept by the owner's choice, 2026-11-18; unknown is never empty), then `GarageAlarmEvaluator` decides per gate -
  pure, tested on a clock: cold (open `cold-open-for` 15 min, below `cold-below` 15 C), empty house (open while empty
  for `empty-for` 5 min, from the later of the opening and the last to go), long (open `long-after` 30 min, never while
  an alarm holds); a repeat every `alarm-every` 30 min / `warning-every` 1 h, at most 10 (the bound of `reminder` of
  the SDK, owner); a condition gone while the gate stays open ends its episode without a word; closed - one `garage
  gate closed` for every open episode; a gate not known does nothing. **An episode is written only once its message
  was told** (`HouseholdNotifier.garageGate` answers whether the broker confirmed it), so a broker away repeats it the
  next pass. Durations in minutes with bounds, pinned by a test; two times of the house compared in its zone.
- **The write is bounded** (30 s) and tried twice more with a backoff, never for a refusal of the schema; a body that
  is not what its topic carries is a WARN and the message is acknowledged.
- **No picture of the station** (owner, 2026-11-17, HAS-444): the station gives no snapshot over ISAPI or the SDK,
  so nothing here stores or serves one.
- **Every unlock is an opening of the gate** (owner, 2026-11-17): one unlock is one pulse of the relay of the
  station (`ON`, then `OFF`) - react to it once, never to `OFF`.
