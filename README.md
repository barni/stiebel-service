# stbl-service

Reads a Stiebel Eltron air/water heat pump over its CAN bus and makes the values available as REST API, status page
and InfluxDB time series. It only sends read requests to the heat pump and changes no setting.

Developed and verified with a **WPL 17 ACS classic** with hydraulic module **HM Trend** (WPM manager). Other Stiebel
Eltron / Tecalor models use the same Elster protocol, but indices, CAN nodes and scaling can differ: check every value
before you rely on it.

## Hardware

- [USBtin](https://www.fischl.de/usbtin/) USB-CAN adapter connected to the CAN bus of the heat pump (20 kbit/s), it
  shows up as serial port, e.g. `/dev/ttyACM0`
- A small Linux computer, e.g. a Raspberry Pi, with Java 25 or newer

## What it provides

- **InfluxDB 2**: every 20 s (pressures, temperatures, compressor, inverter, defrost), every 60 s (energy counters, run
  times, flow rate, calculated heat output and efficiency, fan, superheat, EXV) and every hour (settings). Measurement
  `WP_<name>`, field `value`, e.g. `WP_Hochdruck`, `WP_AbgabeWaerme` (MWh), `WP_ArbeitszahlGeschaetzt`, also the
  spread `WP_Spreizung`, `WP_LaufzeitProStart` and the efficiency of the counters `WP_EffizienzZaehler`.
- **REST API** (HTTP basic auth sent with the request, e.g. `curl -u`; the pages use a login form that is remembered for 30 days), JSON `{"timestamp": ..., "value": ..., "valueString": ...}`, e.g. `/hochdruck`,
  `/vorlaufIstTemp`, `/abgabeWaerme`, `/aufnahmeLeistung`, `/arbeitszahl`, `/heizungsdruck`; see `StblController` for
  all endpoints. A value that is missing or not meaningful (e.g. efficiency while the compressor stands still) is
  answered with HTTP 503 and the reason.
- **Pages**: all pages share a bar with tabs (Übersicht, Anlage, Woche, Jahr, Zustand, CAN-Bus, Einstellungen) and
  four signals: operation (heating with the estimated electricity, standing still since, defrosting), active
  warnings, the condition rating and the service with the CAN answer rate.
- **Overview** `/status`: what the heat pump does right now (running: electricity, heat, efficiency and the margin
  until the next defrost; standing still: the controller integral that starts it at −1000), the numbers of today
  against yesterday, tiles with the course of the last 24 hours (outdoor temperature, estimated electricity, heating
  pressure, compressor), a timeline of the electricity, the defrosts and the flow, return and outdoor temperatures,
  failures and checks. The courses are read from InfluxDB at most once a minute.
- **Plant** `/anlage` with all current values, the warnings, the fault list and the evaluations. A
  click on a value that is stored in InfluxDB shows its course
  as chart, selectable for 6 h, 24 h, 7 days, 30 days or 1 year (from `/history/<name>?range=6h|24h|7d|30d|1y`,
  name as in InfluxDB without `WP_`, e.g. `/history/VorlaufIstTemp?range=7d`; means over 1 min to 12 h, default
  24h). The InfluxDB token then also needs read access to the bucket.
  The overview, the plant page, the CAN bus page and the scan page update themselves in place (20 s, 10 s) without
  reloading, so
  an open tooltip, folded areas, the course dialog and the scroll position stay.
  Every value has an ⓘ tooltip that explains it and names the CAN node it comes from; values calculated by the
  service (calculated or estimated) are marked "≈" and show their formula as well.
- **Connection**: the status page shows the USBtin adapter (port, firmware, connected since, restarts by the
  watchdog) and the CAN bus of the last full minute (received messages, answers, sent requests, answer rate, answers
  "not available", estimated bus load, messages per node). Stored every minute as `WP_CAN_*`, e.g.
  `WP_CAN_Antwortquote`, `WP_CAN_Buslast`, `WP_CAN_Knoten_480`, and as JSON from `/verbindung`.
- **Bus scan** on the page `/canbus/scan` (linked from the CAN bus page): a button starts a one time read of every
  index of the Elster table at 0x180, 0x480, 0x500 and 0x514, about 10 read requests per second, about 25 minutes.
  Nothing is written; indices the service requests anyway and the fault list are skipped. The page lists every
  answer with a value (display name, device, value, raw value, whether the index is described), values 0 on request,
  and `/canbus/scan.csv` returns all of them. The warning for a low answer rate is off while the scan runs. The
  result is kept until the service restarts.
- **Finds of the bus scan** of 2026-11-16, checked over a day: reference and compensated dew point and the
  condensation temperature from 0x500 every 20 s (`WP_TaupunktReferenz`, `WP_TaupunktKompensiert`,
  `WP_Kondensationstemp`). The reference keeps the highest compensated dew point since the last defrost (from 10
  minutes after a start on), the compensated dew point falls while ice grows and the heat pump defrosts when it is about 3.1 K below the reference;
  the remaining margin is shown on the status page and stored as `WP_AbtauReserve`, only while the compressor runs
  for at least 11 minutes. Eight values of unknown meaning are requested every
  minute and stored raw as `WP_Roh_<node>_<index>`, e.g. `WP_Roh_500_0668`. The software number and version of the
  manager and the outdoor unit are read every hour.
- **Fault list** of the heat pump manager (DIAGNOSE → FEHLERLISTE): the 20 entries with time and fault code are read
  every 10 minutes from `FEHLERFELD_0..139` (0x0B00–0x0B8B at node 0x180, 7 values per entry: minute, hour, day,
  month, year, 0, code). Shown on the status page with text for known codes (e.g. 8116 `INV H ROTORVEKTOR`) and as
  JSON from `/fehlerliste`, newest first.
- **Comparison of the setting Wärmebedarf** (heat demand at design temperature): every 6 hours the service calculates
  compressor starts, run time and heat per day from InfluxDB (since `auswertung.start`, default 2023-07-01) and groups
  the days by daily mean outdoor temperature (below −5, −5 to 0, … 10 to 15 °C) and by the setting of that day. Shown
  on the status page and as JSON from `/waermebedarf`. Fewer starts at the same heat mean longer, more efficient runs.
  The rating compares the starts (better, worse, hardly any difference) and says "zu knapp?" only when the compressor
  runs more than 22 hours a day; a change of the heat per day of more than 10 % is only added as a note, because
  sunny autumn days need less heat than winter days with the same daily mean.
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
- **Home Assistant over MQTT** when `mqtt.host` is set (with `mqtt.port`, `mqtt.benutzer`, `mqtt.passwort`, topic
  prefix `mqtt.praefix`, default `stbl`): every 30 s all values as one JSON object on `stbl/zustand` (retained),
  `stbl/status` online/offline (last will), and after every connection MQTT discovery messages under
  `homeassistant/…/stbl/…/config`, so Home Assistant creates the device "Wärmepumpe" with 24 entities: temperatures,
  pressures, estimated power, heat output, efficiency, compressor speed, flow, defrost margin, controller integral,
  energy of today for the energy dashboard, operation, warnings, condition and service, and binary sensors for
  compressor, defrost, EVU block and any warning. Read only, nothing can be switched from Home Assistant.
- **Year comparison** on the page `/jahresvergleich` and as JSON from `/jahresvergleich/daten`: estimated electric
  energy of the heating per month in the current year and the two years before, as bars and as a table with the
  change and the mean outdoor temperature. The current month is compared with the same days of the year before.
  With the Home Assistant counters `auswertung.haushalt.zaehler` (grid import of the house meter) and
  `auswertung.haushalt.wallbox` (kWh) in `auswertung.haushalt.bucket` the page also shows the total grid import from
  the 1st of January against the same days of the year before, one bar per year for all years with readings of the
  house meter (the current one with its forecast)
  made of heating, household and wallbox, and the monthly bars stack the household electricity (grid import minus
  heating and wallbox) and the wallbox on top of the heating; `haushalt=nein` / `wallbox=nein` leave them out. The
  card "Woran liegt es?" shows the gap to the year before added up from the 1st of January, in total and per part,
  and a table against the two years before; a forecast up to the end of the year adds to each part what is still expected: heating as in the
  rest of the two years before, the household as in the rest of the year before, the wallbox at the rate of the last
  three full months. Months that are over are read once, the current
  month every hour. "Verbrauch im Zeitraum" (`/jahresvergleich?von=2025-12-01&bis=2026-11-30`) shows the readings
  of the house meter at the start of the first and the end of the last day, the grid import between them and its
  parts, to compare with the electricity bill.
- **Condition and ageing** on the page `/zustand` and as JSON from `/zustand/daten`: per heating season (July to June,
  since `auswertung.zustand.start`, default 2022-07-01) the efficiency, starts, hours per start and defrosts at the
  same outdoor temperature (0 to 5 °C and 5 to 10 °C), failed starts per 1000 mild starts and the heating pressure
  in winter. From the 10 minute means of the steady run from November to March: outdoor air minus evaporation
  temperature at 0 to 5 °C (refrigerant, dirty evaporator), condensation temperature minus flow (deposits in the
  condenser), spread (water flow) and the change of the heating pressure per 10 K water temperature (gas charge of
  the expansion vessel; per winter month the daily means of pressure against water temperature, normalized to
  1.8 bar and 30 °C, months with a refill left out); evaporation and condensation temperature from the pressures for R410A. Refills of the
  heating circuit are found in the hourly pressure, the loss per year between two refills is rated too. The newest
  season with enough days is compared with the mean of the three seasons before and each value rated as
  unauffällig, beobachten or auffällig. The physical values (evaporator, condenser, spread, pressure swing, loss of
  pressure) are also compared with the fixed mean of the first two seasons, so a slow drift over years is found too;
  the worse rating counts; the hours per start depend on the settings and are shown without a rating.
  Seasons that are over are read once, the current one every 6 hours. When a check gets worse a mail is sent, for
  auffällig also by Pushover; the ratings are stored as `WP_Zustand_<check>`, so a restart does not report them
  again, and getting better is not reported.
- **Warnings** checked every minute, shown on the status page and as JSON from `/ueberwachung`; a mail is sent when a
  warning becomes active: heating pressure below `warnung.heizungsdruck.min` (default 1.3 bar) or above
  `warnung.heizungsdruck.max` (default 2.5 bar), heating element on,
  answer rate below `warnung.antwortquote.min` % (default 90) for 10 minutes, more than `warnung.starts.proStunde`
  compressor starts within an hour (default 3), high pressure of the refrigerant circuit above
  `warnung.hochdruck.max` (default 38 bar), spread above `warnung.spreizung.max` (default 10 K) for 5 minutes while
  the compressor runs (too little water flow), a failed check of the service.
- **Settings** of the heat pump manager, read every hour from 0x514 and stored as `WP_Einstellung_<name>`: design
  temperature, heat demand, spread, bivalence, heating limit, silent mode, comfort and eco temperature, heating curve,
  curve distance, room influence, standstill and minimum run time, controller dynamics, heating program 1 (weekdays
  and weekend, shown as time slot), summer/winter switch, frost protection and maximum heating temperature, most with
  the default of the WPM 3 manual in its tooltip. A change is sent by mail (also one made while the service was stopped, compared with
  the last stored value).
- **Pressure swing** of the heating circuit per day: span of the pressure divided by the span of the mean water
  temperature in bar per 10 K, stored once a day as `WP_DruckhubJe10K` for evaluations. The value rises over the months
  when the expansion vessel loses its gas charge. Only for days with at least 12 h run time of the compressor.
- **Version**, git commit and build time are written to the log at the start and shown on the status page together
  with the start time of the service.
- **Check of the service** 30 s after the start and every 15 minutes: InfluxDB reachable, reading the bucket (and the
  bucket of the outdoor temperature), writing, USBtin connected and answering, mail configured, Pushover (once set up)
  delivering.
- **Mail** to `stbl.mail.to` when no CAN answer arrives for 2 minutes and the USB connection is restarted, when a
  new entry appears in the fault list and when a warning becomes active.
- **Pushover**: the same alerts also as push message over [pushover.net](https://pushover.net) when
  `pushover.user` and `pushover.token` are set (priority with `pushover.prioritaet`, -2 to 1). The button "Testnachricht senden" on the configuration page sends a test alert over both ways.

Derived values: the calculated heat output uses flow rate and spread, the estimated efficiency divides it by the
real power estimated from the inverter's apparent power; both are skipped during the start phase and the defrost. The
energy counters of the heat pump are combined from sum and day counters and never fall.

## Configuration

Copy [application.properties](application.properties) next to the jar and fill in login, USBtin port and InfluxDB.
The InfluxDB token is read from the environment variable `INFLUX_TOKEN`.

The page `/konfiguration` shows at the top the state of the service (version, start, checks), the software of the
devices and the USB adapter, below the settings of the service: the
limits of the warnings, the start dates and the outdoor temperature of the evaluations, InfluxDB (on or off, address,
organisation, bucket), mail (recipient, server, port, user, password, authentication, STARTTLS), Pushover (user key,
API token, priority) and the CAN adapter (port, speed, receive all messages). The mail password and the Pushover token
can be set but are never shown. Saving checks every value and
writes it into
`application.properties` in the working directory (another file with `stbl.config.datei`); comments and order stay, a
copy of the file before is kept as `.bak`. The service reads its settings only at the start: "Speichern und neu starten"
restarts it within the running process (the Spring context is closed and started again, no systemd needed; the values
have a gap of about a minute), "Nur speichern" marks every value that differs from the running service until the next
restart. Not on the page: the login, address and port of the web server (a typo
would lock the page out), the log file and the token of InfluxDB. The user of the service needs write access to
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

Start the jar with java from the folder that contains application.properties, e.g. with the systemd unit
[stbl.service](stbl.service): copy it to `/etc/systemd/system/`, then `sudo systemctl daemon-reload` and
`sudo systemctl enable --now stbl`. `Restart=on-failure` starts the service again after a crash; without it a stopped
service stays stopped and sends no warnings.

Alerts (mail and Pushover) are sent in the background, a slow mail server does not delay the requests to the heat
pump. The mail server gets 10 s to accept the connection and 20 s to answer (other values with
`spring.mail.properties.mail.smtp.connectiontimeout`, `.timeout` and `.writetimeout` in milliseconds). While the CAN
adapter stays silent it is restarted every 2 minutes, the alert about it comes for the first restart and then once
an hour.

[deploy.sh](deploy.sh) builds on the development computer with all tests and installs the jar on the Pi
(`./deploy.sh pi@host`): it copies it to `/var/stbl-service` and restarts the unit `stbl`. Do not build on a Pi 3, a
Maven build next to the running service fills its 1 GB until it no longer answers. [stbl.service](stbl.service)
limits the heap to 192 MB; after changing the unit in `/etc/systemd/system` run `sudo systemctl daemon-reload`.

## License

Copyright (C) 2019-2026 Stephan Andresen

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
Public License as published by the Free Software Foundation version 3 of the License, see
[COPYING.LESSER](COPYING.LESSER), which supplements the GNU General Public License in [COPYING](COPYING).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

It contains third-party code under the same license, see [THIRD-PARTY.md](THIRD-PARTY.md).
