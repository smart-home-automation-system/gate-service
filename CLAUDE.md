# gate-service

The driveway gate and the intercom of the house (epic HAS-442): the events of the Hikvision door station at the
gate, the command of the Shelly that opens the gate, their log and the household told. The scaffold of HAS-446,
from the scaffold of aircon-service (itself from energy-service and water-service): Spring Boot 4.1.2,
cholewa-commons 1.14.0, smart-home-sdk 1.22.0, shelly-client 2.7.0 with its beans on; port 6016 / 8016 locally,
6200 / 8200 in the cluster, base path `/home/gate`; own database `home-automation-gate`, pool 2, `V1` an empty
baseline; `database-password` mandatory; scheduling off under `test`; the Deployment `Recreate`, never scaled
(state in memory will follow). Org-wide rules: `organization-repository/claude/organization.md`.

## Role in the system

- Talks to: the intercom events on `device.prod.gate` of `/device` (HAS-448, the contract in
  `deployment-tools/scripts/hikvision/devices.md`), the Shelly 1 of the gate over HTTP (HAS-449), the household
  category of the notification exchange (HAS-452); routed by the gateway under `/gate` (HAS-447).
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
- **The write is bounded** (30 s) and tried twice more with a backoff, never for a refusal of the schema; a body that
  is not what its topic carries is a WARN and the message is acknowledged.
- **No picture of the station** (owner, 2026-11-17, HAS-444): the station gives no snapshot over ISAPI or the SDK,
  so nothing here stores or serves one.
- **Every unlock is an opening of the gate** (owner, 2026-11-17): one unlock is one pulse of the relay of the
  station (`ON`, then `OFF`) - react to it once, never to `OFF`.
