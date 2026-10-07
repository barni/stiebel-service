# stbl-service

Reads a Stiebel Eltron air/water heat pump over its CAN bus and makes the values available as REST API, status page
and InfluxDB time series. It only sends read requests to the heat pump and changes no setting.

Developed and verified with a **WPL 17 ACS classic** with hydraulic module **HM Trend** (WPM manager). Other Stiebel
Eltron / Tecalor models use the same Elster protocol, but indices, CAN nodes and scaling can differ: check every value
before you rely on it.

## Hardware

- [USBtin](https://www.fischl.de/usbtin/) USB-CAN adapter connected to the CAN bus of the heat pump (20 kbit/s), it
  shows up as serial port, e.g. `/dev/ttyACM0`
- A small Linux computer, e.g. a Raspberry Pi, with Java 17 or newer

## What it provides

- **InfluxDB 2**: every 20 s (pressures, temperatures, compressor, inverter, defrost), every 60 s (energy counters, run
  times, flow rate, calculated heat output and efficiency, fan, superheat, EXV) and every hour (settings). Measurement
  `WP_<name>`, field `value`, e.g. `WP_Hochdruck`, `WP_AbgabeWaerme` (MWh), `WP_ArbeitszahlGeschaetzt`.
- **REST API** (HTTP basic auth), JSON `{"timestamp": ..., "value": ..., "valueString": ...}`, e.g. `/hochdruck`,
  `/vorlaufIstTemp`, `/abgabeWaerme`, `/aufnahmeLeistung`, `/arbeitszahl`, `/heizungsdruck`; see `StblController` for
  all endpoints. A value that is missing or not meaningful (e.g. efficiency while the compressor stands still) is
  answered with HTTP 503 and the reason.
- **Status page** `/status` with all current values. A click on a value that is stored in InfluxDB shows its course
  as chart, selectable for 6 h, 24 h, 7 days, 30 days or 1 year (from `/history/<name>?range=6h|24h|7d|30d|1y`,
  name as in InfluxDB without `WP_`, e.g. `/history/VorlaufIstTemp?range=7d`; means over 1 min to 12 h, default
  24h). The InfluxDB token then also needs read access to the bucket.
  Every value has an ⓘ tooltip that explains it and names the CAN node it comes from; values calculated by the
  service are marked "(berechnet)" and show their formula as well.
- **Connection**: the status page shows the USBtin adapter (port, firmware, connected since, restarts by the
  watchdog) and the CAN bus of the last full minute (received messages, answers, sent requests, answer rate, answers
  "not available", estimated bus load, messages per node). Stored every minute as `WP_CAN_*`, e.g.
  `WP_CAN_Antwortquote`, `WP_CAN_Buslast`, `WP_CAN_Knoten_480`, and as JSON from `/verbindung`.
- **Fault list** of the heat pump manager (DIAGNOSE → FEHLERLISTE): the 20 entries with time and fault code are read
  every 10 minutes from `FEHLERFELD_0..139` (0x0B00–0x0B8B at node 0x180, 7 values per entry: minute, hour, day,
  month, year, 0, code). Shown on the status page with text for known codes (e.g. 8116 `INV H ROTORVEKTOR`) and as
  JSON from `/fehlerliste`, newest first.
- **Mail** to `stbl.mail.to` when no CAN answer arrives for 2 minutes and the USB connection is restarted, and when a
  new entry appears in the fault list.

Derived values: the calculated heat output uses flow rate and spread, the estimated efficiency divides it by the
real power estimated from the inverter's apparent power; both are skipped during the start phase and the defrost. The
energy counters of the heat pump are combined from sum and day counters and never fall.

## Configuration

Copy [application.properties](application.properties) next to the jar and fill in login, USBtin port and InfluxDB.
The InfluxDB token is read from the environment variable `INFLUX_TOKEN`.

## Building

    mvn initialize
    mvn clean install

`mvn initialize` installs the USBtinLib jar from `src/main/resources` into the local Maven repository.

## Starting

Start the jar with java from the folder that contains application.properties, e.g. as systemd unit:

    [Service]
    User=pi
    WorkingDirectory=/var/stbl-service
    Environment=INFLUX_TOKEN=...
    ExecStart=/usr/bin/java -jar /var/stbl-service/stbl-service-0.2.0-SNAPSHOT.jar
    SuccessExitStatus=143

[deploy.sh](deploy.sh) copies a new build to `/var/stbl-service` and restarts the unit `stbl`.

## License

Copyright (C) 2019-2026 Stephan Andresen

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
Public License as published by the Free Software Foundation version 3 of the License, see
[COPYING.LESSER](COPYING.LESSER), which supplements the GNU General Public License in [COPYING](COPYING).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

It contains third-party code under the same license, see [THIRD-PARTY.md](THIRD-PARTY.md).
