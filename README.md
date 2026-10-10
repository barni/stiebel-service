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
  `WP_<name>`, field `value`, e.g. `WP_Hochdruck`, `WP_AbgabeWaerme` (MWh), `WP_ArbeitszahlGeschaetzt`, also the
  spread `WP_Spreizung`, `WP_LaufzeitProStart` and the efficiency of the counters `WP_EffizienzZaehler`.
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
- **Comparison of the setting Wärmebedarf** (heat demand at design temperature): every 6 hours the service calculates
  compressor starts, run time and heat per day from InfluxDB (since `auswertung.start`, default 2023-07-01) and groups
  the days by daily mean outdoor temperature (below −5, −5 to 0, … 10 to 15 °C) and by the setting of that day. Shown
  on the status page and as JSON from `/waermebedarf`. Fewer starts at the same heat mean longer, more efficient runs.
  A start counts after a standstill of at least 3 minutes: the restart after a defrost (about 2 minutes break, around
  20 a day near 0 °C) depends on the weather and not on the setting. The daily overview counts the same way.
  These restarts are shown as defrosts per day, also for the days before the service detected defrosts itself.
  The heat pump's own outdoor temperature `WP_Aussentemp` is only stored since 09/2026; for older days set a Home
  Assistant entity (measurement `°C`), the token then needs read access to that bucket:
  `auswertung.aussentemp.bucket=home_assistant`, `auswertung.aussentemp.entity=aussen_temperatur`.
- **Failed starts per heating season** (July to June) on the status page and as JSON from `/fehlstarts`: the
  compressor does not start (fault list INV H ROTORVEKTOR), the heat pump waits about 23 minutes. Found in the stored
  values (drop of the high pressure after a standstill, no run, next run 18–30 minutes later). Shown per season as
  count, per MWh of heat and per 1000 starts on days with 0–15 °C mean outdoor temperature (failed starts hardly
  happen in frost), with the number of covered days. All seasons since `auswertung.fehlstarts.start` (default 2019-01-01)
  2 minutes after the start, then the current season every hour.
- **Daily overview** on the status page and as JSON from `/tagesuebersicht`: compressor starts, run time, heat,
  estimated electric energy of the compressor inverter, efficiency and defrosts of today and yesterday, compared with
  the days of similar outdoor temperature (±1.5 K) from the comparison above. Updated every 10 minutes.
- **Warnings** checked every minute, shown on the status page and as JSON from `/ueberwachung`; a mail is sent when a
  warning becomes active: heating pressure below `warnung.heizungsdruck.min` (default 1.3 bar) or above
  `warnung.heizungsdruck.max` (default 2.5 bar), heating element on,
  answer rate below `warnung.antwortquote.min` % (default 90) for 10 minutes, more than `warnung.starts.proStunde`
  compressor starts within an hour (default 6), high pressure of the refrigerant circuit above
  `warnung.hochdruck.max` (default 38 bar), spread above `warnung.spreizung.max` (default 10 K) for 5 minutes while
  the compressor runs (too little water flow), a failed check of the service.
- **Settings** of the heat pump manager, read every hour from 0x514 and stored as `WP_Einstellung_<name>`: design
  temperature, heat demand, spread, bivalence, heating limit, silent mode, comfort and eco temperature, heating curve,
  curve distance, room influence, standstill and minimum run time, controller dynamics, each with the default of the
  WPM 3 manual in its tooltip. A change is sent by mail (also one made while the service was stopped, compared with
  the last stored value).
- **Pressure swing** of the heating circuit per day: span of the pressure divided by the span of the mean water
  temperature in bar per 10 K, shown for yesterday and stored as `WP_DruckhubJe10K`. The value rises over the months
  when the expansion vessel loses its gas charge. Only for days with at least 12 h run time of the compressor.
- **Version**, git commit and build time are written to the log at the start and shown on the status page together
  with the start time of the service.
- **Check of the service** 30 s after the start and every 15 minutes: InfluxDB reachable, reading the bucket (and the
  bucket of the outdoor temperature), writing, USBtin connected and answering, mail configured.
- **Mail** to `stbl.mail.to` when no CAN answer arrives for 2 minutes and the USB connection is restarted, when a
  new entry appears in the fault list and when a warning becomes active.

Derived values: the calculated heat output uses flow rate and spread, the estimated efficiency divides it by the
real power estimated from the inverter's apparent power; both are skipped during the start phase and the defrost. The
energy counters of the heat pump are combined from sum and day counters and never fall.

## Configuration

Copy [application.properties](application.properties) next to the jar and fill in login, USBtin port and InfluxDB.
The InfluxDB token is read from the environment variable `INFLUX_TOKEN`.

The page `/konfiguration`, linked with the status page in both directions, shows the settings of the service that are
safe to change in the browser: the limits of the warnings, the start dates and the outdoor temperature of the
evaluations, InfluxDB on or off and the recipient of the mails. Saving checks every value and writes it into
`application.properties` in the working directory (another file with `stbl.config.datei`); comments and order stay, a
copy of the file before is kept as `.bak`. The service reads its settings only at the start: "Speichern und neu starten"
restarts it within the running process (the Spring context is closed and started again, no systemd needed; the values
have a gap of about a minute), "Nur speichern" marks every value that differs from the running service until the next
restart. Passwords, tokens, the login, the mail
server, the address of InfluxDB and the CAN adapter are not on the page. The user of the service needs write access to
the file and its directory, otherwise the page is read-only.

InfluxDB is optional. With `influx.enabled=false` the service only shows the live values from the CAN bus: nothing is
stored, the values on the status page are not clickable, and the cards calculated from stored values are left out
(daily overview, comparison of the setting Wärmebedarf, failed starts per season) as well as the checks of InfluxDB.
`/history` answers 503, the JSON of the evaluations is empty. Warnings, fault list and mails work as before; a setting
changed while the service was stopped is not noticed, because the last stored value is the reference for that.

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
