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
opens the gate, their log and the household told. **The scaffold of HAS-446** - the events, the command and the
first endpoints come with HAS-448 onwards.

# Run locally

- Build: `mvn verify` (JDK 21).
- Ports: local profile `6016` (management `8016`); in the cluster `6200` / `8200`.
- Own database `home-automation-gate`, pool 2; `database-password` is mandatory
  (`cholewa.required-properties`). **A local run with the real values is the production database.**
