# gate-service

[![CI](https://github.com/smart-home-automation-system/gate-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/gate-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_gate-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_gate-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_gate-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_gate-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/gate-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/gate-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/gate-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.2-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_gate-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_gate-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_gate-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_gate-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/gate-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/gate-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/gate-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/gate-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/gate-service?style=plastic)

---


# Description

The driveway gate and the intercom of the house (epic HAS-442): the events of the Hikvision door station at the
gate (rings, openings from the indoor screen) through the SDK bridge of the cluster, the command of the Shelly that
opens the gate, their log and the household told.

- **The events of the door station** (HAS-448): the topics of the bridge on `device.prod.gate` of the vhost `/device`
  (user `device`) - a ring with the button it came from, a dismissed call, an unlock with how it was opened, by what and
  which door - stored in `intercom_event`; an unlock once, by its door and the time the station gives. The availability
  of the bridge is kept in memory. The motion of the camera and the events of every other device cause nothing.

- **The opening of the gate** (HAS-449): the Shelly 1 of the gate from the device registry (the kind `gate`, id 147,
  channel 0), its relay pulsed by one "turn on" - its `auto_off` (1 s) ends the pulse; nothing is sent while the relay
  does not switch itself off. An unlock of the indoor screen opens the gate at once (only an unlock younger than 60 s,
  and the same unlock never twice); a second opening within 15 s is refused - the gate is step-by-step. Every opening is
  logged in `gate_command`.
- **The log** (HAS-450): the rings, dismissed calls and unlocks of the station and the commands of the gate as one
  timeline, the newest first; both tables are kept 90 days (`gate.retention`), deleted every night at 03:40.

- **The household told** (HAS-452): a ring at the gate as `doorbell ringing` - once per call (none again within 60 s),
  pushed by notification-service to every member and opening the gate page of the dashboard - and every opening of the
  gate as `gate opened` with where from and who, on the intercom channel of Discord; never by SMS. A notification never
  fails nor holds the opening of the gate.
- **The garage gates** (HAS-473): a gate of the registry with `openingSensors` is a garage gate - the driveway gate
  is the one without. The state of each is read from the Shelly BLU Door/Window sensor on it, through the BTHome
  gateway that hears it (`Shelly.GetComponents` every 5 min and the `bthomesensor` status topics between): open,
  closed, or not known when the sensor never reported or its gateway has been silent for 30 min
  (`gate.garage.gateway-silent-after`) - a BLU sensor sends only when it changes. The last reading of each sensor is
  stored and read back at a start as not known until its gateway confirms it.
- **The alarms of a garage gate left open** (HAS-475, replacing the Shelly cloud scene GARAGE NOTIFICATION), judged
  every minute: **cold** - open for 15 min while the temperature outside (weather-service, not stale) is below 15 C;
  **empty house** - open while nobody is at home (presence-service; not known is never empty) for 5 min; both alarms,
  repeated every 30 min; **long** - open for 30 min, a warning repeated every hour, not while an alarm holds. At most
  10 repeats; when the gate closes the household is told once. Told as the `garage gate ...` household events -
  notification-service >= 0.13.0 puts them on the intercom channel, pushes them and texts the first message of an
  alarm to the members named for it. The episodes are kept in `garage_gate_alert`, so a restart neither repeats nor
  loses one. The thresholds are `gate.garage.alarm.*`.

# API

Base path `/home/gate`, routed by the gateway since 0.12.0 (HAS-447) - by path, every method.

| Method | Path | What it does |
|---|---|---|
| `POST` | `/open?source=dashboard&member=<name>` | Opens the gate for a member of the household (checked against the profiles of database-service, any case). `204` once the Shelly took the command; `400 UNKNOWN_MEMBER`, `409 OPEN_TOO_SOON` (within 15 s of the last command), `502 DEVICE_FAILED` (it may have moved all the same), `503 GATE_UNAVAILABLE` (no gate, or a relay that does not switch itself off), `502 REGISTRY_UNAVAILABLE` (the profiles could not be read). No question asked - as the button of the indoor screen. |
| `GET` | `/` | The gate (`gate`: id, reachable, safe, `autoOffSeconds`, `checkedAt`), the bridge (`bridge`: online, at), the last ring (`lastRing`: at, button) and the last opening (`lastOpening`: at, source, member). A part not known is left out. |
| `GET` | `/garage` | The garage gates (HAS-473), by id: `id`, `name`, `state` (`open` / `closed` / `unknown`), `since` (when the gateway heard the reading that decides it), `confirmedAt` (when a gateway last stood for its sensors), `reachable` and `safe` of its Shelly. A field not known is left out; an empty list before the first read of the registry. |
| `GET` | `/garage/{id}` | One garage gate; `404 NOT_FOUND_GATE` for an id that is no garage gate. Since HAS-474 also `lastCommandConfirmed` - whether its sensor answered the last pulse: `false` after 60 s without a change, left out with no pulse, while it is checked or when a second pulse cancelled the check. |
| `POST` | `/garage/{id}/pulse?source=dashboard&member=<name>` | One pulse of a garage gate (HAS-474) - opens, stops or closes it alike; no question and **no repeat window** (a second press stops the gate half-way). `204` once the Shelly took it; `400 UNKNOWN_MEMBER`, `404 NOT_FOUND_GATE`, `502 DEVICE_FAILED` (it may have moved all the same), `503 GATE_UNAVAILABLE` (a relay that does not switch itself off), `502 REGISTRY_UNAVAILABLE`. Logged in `gate_command` with the id of the gate. |
| `GET` | `/events?from=&to=` | The timeline of a period (the time of the house, ISO `2026-11-18T00:00:00`, `from` included, `to` not, at most 31 days), the newest first: each item `kind` and `at` with the fields of its kind - `ring` (`button`), `dismissed`, `unlock` (`door`, `unlockType`, `screen`), `command` (`source` screen / dashboard, `member`, `gate` of a garage gate since HAS-474 - left out for the driveway gate, `outcome` opened / pulsed / failed / too soon / unavailable), `garage` since HAS-474 (`gate`, `state` open / closed, `openForSeconds` of a closing - a change of a garage gate, written once it held for 5 s: its sensor bounces at the ends of the travel); a field not known is left out. `400 INVALID_RANGE`. No picture - the station gives none. |

# Run locally

- Build: `mvn verify` (JDK 21) - **needs a running Docker**: the repository tests run on PostgreSQL in Testcontainers.
- Ports: local profile `6016` (management `8016`); in the cluster `6200` / `8200`.
- Own database `home-automation-gate`, pool 2: `database-host`, `database-port`, `database-name`, `database-user`,
  `database-password` and `flyway-url`; `database-password` is mandatory (`cholewa.required-properties`), the rest
  falls back to `localhost` / dummy values that fail on Flyway.
- The services of the cluster: `internal.service.database`, `.weather`, `.presence` (locally `6005`, `6010`, `6009`).
- The broker: `rabbitmq-password` (user `device`, mandatory); the local profile consumes `device.dev.gate` on
  `localhost`. The household messages: `notification-rabbitmq-password` (user `notification`, mandatory), `env: dev`
  locally - `notification.dev.household`. database-service: `internal.service.database` (`localhost:6005` locally). **A local run with the real
  values is the production database - and opens the real gate.**
