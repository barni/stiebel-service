package nrw.andresen.stbl.services.can;

import java.util.Map;

import static java.util.Map.entry;

/**
 * What the Elster indices on this installation stand for, for the CAN bus page. The names of ElsterTable come from
 * older devices and often do not fit the WPM3; "gedeutet" marks a meaning derived from the bus traffic only.
 */
public final class IndexBeschreibung {

    // For the values from ha-stiebel-control that did not change on this installation
    private static final String KONSTANT = " Auf dieser Anlage am 16.11.2026 über Stunden mit Heizen und Stillstand "
            + "unverändert, misst hier also nichts und wird nicht mehr abgefragt.";

    private static final Map<Short, String> BESCHREIBUNG = Map.ofEntries(
            // Heating circuit
            entry((short) 0x000C, "Außentemperatur in °C. Von der Wärmepumpe (0x500): Fühler der Außeneinheit, beim "
                    + "Abtauen kurz einige Kelvin wärmer. Vom Kessel (0x180) als Rundruf alle 5 s: Außenfühler des "
                    + "Wärmepumpenmanagers, meist etwa 1 K kälter."),
            entry((short) 0x01D6, "Vorlauftemperatur der Wärmepumpe in °C, nur in Schritten von etwa 1,1 K."),
            entry((short) 0x0016, "Rücklauftemperatur in °C, nur in Schritten von etwa 1,1 K. Der Kessel (0x180) misst "
                    + "an einem eigenen Fühler und zeigt oft 1 bis 2 K weniger."),
            entry((short) 0x01D7, "Hilfskessel-Soll in °C. Der Heizungsregler (0x180) schreibt ihn alle 10 s an die "
                    + "Wärmepumpe, er folgt der Heizkurve. Nicht der Vorlauf-Soll, auf den die Wärmepumpe regelt: der "
                    + "lag am 16.11.2026 bei etwa 33 °C, dieser Wert bei 26,5 °C."),
            entry((short) 0x005F, "Anforderung des Managers an die Wärmepumpe, alle 10 s: 0 im Stillstand, 512 beim "
                    + "Heizen (gedeutet). Die Wärmepumpe beantwortet eine Abfrage nur mit 0x8000, deshalb mitgelesen."),
            entry((short) 0x0674, "Wasserdruck im Heizkreis in bar. Normal etwa 1,4 bis 2,3 bar, zulässig 3 bar."),
            entry((short) 0x02E2, "Volumenstrom des Heizwassers durch die Wärmepumpe in l/min."),
            entry((short) 0x0176, "Betriebsstatus des Heizungsreglers als Bitfeld: 0 im Stillstand, 65 beim Heizen. "
                    + "0x8000 bedeutet hier EVU-Sperre und nicht „nicht verfügbar“."),
            // Refrigerant circuit and compressor
            entry((short) 0x07A6, "Hochdruck im Kältekreis in bar (absolut). Entspricht der Kondensationstemperatur, die "
                    + "etwa dem Vorlauf folgt. Abschaltung bei 45 bar."),
            entry((short) 0x07A7, "Niederdruck im Kältekreis in bar (absolut). Entspricht der Verdampfungstemperatur, "
                    + "im Betrieb einige Kelvin unter der Außentemperatur."),
            entry((short) 0x0265, "Temperatur des Kältemittels direkt hinter dem Verdichter in °C."),
            entry((short) 0x063F, "Temperatur des angesaugten Kältemittels am Verdichtereintritt in °C."),
            entry((short) 0x07A9, "Temperatur am Austritt des Verdampfers in °C. Steigt beim Abtauen auf 38 bis 44 °C."),
            entry((short) 0x0A39, "Temperatur des Öls im Verdichter in °C."),
            entry((short) 0x063D, "Ist-Drehzahl des Verdichters in Hz, 0 im Stillstand."),
            entry((short) 0x06EC, "Soll-Drehzahl des Verdichters in Hz."),
            entry((short) 0x063B, "Antwortet mit der Verdichterdrehzahl in Hz, der Name „Kühlfunktion“ passt nicht."),
            entry((short) 0x07CA, "Öffnung des elektronischen Expansionsventils in %. Öffnet beim Abtauen und kurz bei "
                    + "jedem Start nach langem Stillstand auf 100 %."),
            entry((short) 0x07F3, "Soll-Überhitzung des Kältemittels am Verdampferaustritt in K."),
            entry((short) 0x07F4, "Ist-Überhitzung des Kältemittels am Verdampferaustritt in K."),
            entry((short) 0xC281, "Soll-Drehzahl des Lüfters der Außeneinheit in Hz. Im Stillstand steht sie alle 4 Minuten etwa "
                    + "1 Minute auf 4 Hz, der Lüfter dreht dabei meist nicht (16.11.2026)."),
            entry((short) 0xC283, "Ist-Drehzahl des Lüfters der Außeneinheit in Hz."),
            entry((short) 0x0833, "Leistung des Lüfters der Außeneinheit in %, etwa 7,5 % je Hz."),
            entry((short) 0x06B1, "Spannung am Inverter in V. Zeigt etwa 11 V weniger als am Hauszähler."),
            entry((short) 0x06B2, "Strom des Inverters in A. Höchstens 20 A laut Datenblatt, gemessen bis 18 A."),
            entry((short) 0xC1E6, "Umgebungstemperatur im Inverter in °C."),
            entry((short) 0xC1E7, "Temperatur des Inverters am Verdichter in °C."),
            // Counters
            entry((short) 0x07FC, "Laufzeit des Verdichters beim Heizen in Stunden (Zugang 0x514). Die Wärmepumpe "
                    + "(0x500) meldet dem Manager höchstens 9999, über 0x514 kommt der volle Wert."),
            entry((short) 0x0808, "Laufzeit des Verdichters beim Abtauen in Stunden."),
            entry((short) 0x0807, "Dauer der letzten Abtauung: beim Heizmodul (0x514) in Minuten, bei der Wärmepumpe "
                    + "(0x500) in Sekunden."),
            entry((short) 0x071C, "Starts des Verdichters, Tausender. Zusammen mit 0x071D die Gesamtzahl."),
            entry((short) 0x071D, "Starts des Verdichters, Rest unter 1000. Zusammen mit 0x071C die Gesamtzahl."),
            entry((short) 0x0259, "Laufzeit der elektrischen Zusatzheizung, Stufe 1, in Stunden."),
            entry((short) 0x025A, "Laufzeit der elektrischen Zusatzheizung, Stufe 2, in Stunden."),
            entry((short) 0x0805, "Laufzeit der elektrischen Zusatzheizung, Stufe 1 und 2 zusammen, in Stunden."),
            entry((short) 0x091E, "Strom des Verdichters beim Heizen heute, Wh-Anteil. Der Zähler zeigt etwa 85 % des "
                    + "echten Verbrauchs."),
            entry((short) 0x091F, "Strom des Verdichters beim Heizen heute, kWh-Anteil."),
            entry((short) 0x0920, "Strom des Verdichters beim Heizen gesamt, kWh-Anteil."),
            entry((short) 0x0921, "Strom des Verdichters beim Heizen gesamt, MWh-Anteil."),
            entry((short) 0x092E, "Wärme der Wärmepumpe heute, Wh-Anteil."),
            entry((short) 0x092F, "Wärme der Wärmepumpe heute, kWh-Anteil."),
            entry((short) 0x0930, "Wärme der Wärmepumpe gesamt, kWh-Anteil. Wird um Mitternacht um den gerundeten "
                    + "Tageswert erhöht."),
            entry((short) 0x0931, "Wärme der Wärmepumpe gesamt, MWh-Anteil."),
            entry((short) 0x0926, "Wärme der elektrischen Zusatzheizung heute, Wh-Anteil."),
            entry((short) 0x0927, "Wärme der elektrischen Zusatzheizung heute, kWh-Anteil."),
            entry((short) 0x0928, "Wärme der elektrischen Zusatzheizung gesamt, kWh-Anteil."),
            entry((short) 0x0929, "Wärme der elektrischen Zusatzheizung gesamt, MWh-Anteil."),
            // Settings
            entry((short) 0x0005, "Komfort-Temperatur in °C. Ohne Raumfühler am WPM verschiebt sie die Heizkurve "
                    + "parallel, sie ist keine Raumtemperatur."),
            entry((short) 0x0008, "Eco-Temperatur (Absenkung) in °C, wirkt wie die Komfort-Temperatur."),
            entry((short) 0x010E, "Steigung der Heizkurve."),
            entry((short) 0x0111, "Abstand der Heizkurve (Parallelverschiebung) in K."),
            entry((short) 0x010F, "Raumeinfluss, roh mit vertauschten Bytes (1280 = 5). Wirkt nicht, weil kein "
                    + "Raumfühler am WPM hängt."),
            entry((short) 0x0112, "Programmschalter: Betriebsart, die am WPM eingestellt ist."),
            entry((short) 0x0026, "Stillstandzeit: so viele Minuten bleibt der Verdichter nach einem Stopp mindestens "
                    + "aus."),
            entry((short) 0x0914, "Mindestlaufzeit des Verdichters in Minuten."),
            entry((short) 0xFDB0, "Reglerdynamik: wie schnell der Regler auf Abweichungen reagiert."),
            entry((short) 0x0665, "Auslegungstemperatur in °C: Außentemperatur, für die der Wärmebedarf gilt."),
            entry((short) 0x0666, "Wärmebedarf des Hauses bei der Auslegungstemperatur in kW."),
            entry((short) 0x067E, "Soll-Spreizung zwischen Vor- und Rücklauf in K."),
            entry((short) 0x01AC, "Bivalenztemperatur in °C: darunter darf die Zusatzheizung mitheizen."),
            entry((short) 0x01AE, "Einsatzgrenze in °C: darunter heizt nur noch die Zusatzheizung."),
            entry((short) 0x4E9F, "Leistung der Wärmepumpe im Silent Mode in %."),
            entry((short) 0x4EA0, "Lüfterleistung im Silent Mode in %."),
            // Read along
            entry((short) 0xFDF3, "Vorlauftemperatur in 0,1 K: 0x700 meldet sie dem Manager alle 5 s (Name falsch, in "
                    + "OneESP32ToRuleThemAll VORLAUFISTTEMP). Beim Heizen bis 1 K über dem groben Vorlauf. 0x700 beantwortet eigene Abfragen nicht."),
            entry((short) 0xFDF4, "Rücklauftemperatur in 0,1 K von 0x700 (Name falsch, in OneESP32ToRuleThemAll "
                    + "RUECKLAUFISTTEMP). Spreizung mit 0xFDF3 "
                    + "4,9 bis 5,2 K bei 5 K Soll."),
            entry((short) 0xFDF5, "Dritte Temperatur von 0x700 in 0,1 K, liegt knapp über dem feinen Vorlauf. "
                    + "Vermutlich der Vorlauf hinter der elektrischen Nachheizstufe (FLOW_TEMP_NHZ in "
                    + "ha-stiebel-control), Name falsch."),
            entry((short) 0xFE1B, "PWM-Signal der Umwälzpumpe, das der Manager an 0x700 schreibt (gedeutet, Name "
                    + "unbekannt): 100 im Stillstand. Beim Heizen 70 bis 72 bei 9,0 l/min, 66 bis 67 bei 11,1 l/min, "
                    + "57 bis 59 bei 13,5 l/min, 55 bis 56 bei 14,8 l/min: je kleiner, desto mehr Durchfluss."),
            entry((short) 0x0917, "Integral der Regelabweichung, das der Manager alle 10 s an die Wärmepumpe schreibt "
                    + "(gedeutet). Steigt, solange der feine Vorlauf über dem Sollwert liegt, fällt darunter, umso schneller, "
                    + "je größer die Abweichung. Der Sollwert folgt der Heizkurve, etwa Hilfskessel-Soll plus 7 K: 34,2 °C "
                    + "bei 7 bis 8 °C draußen, 34,4 °C bei 6 °C (16.11.2026). Bei -1000 Start: es springt auf 0 und fällt weiter, 40 bis "
                    + "60 s später läuft der Verdichter an, kurz mit 40 Hz, dann ab etwa 26 Hz mit der "
                    + "Frequenz-Anforderung. Bei etwa +1000 Stopp, dabei springt es ebenfalls auf 0 und steigt im Stillstand "
                    + "noch, bis der Vorlauf unter den Sollwert fällt (16.11.2026)."),
            entry((short) 0xFE07, "Von 0x700: 0 im Stillstand, beim Heizen 17 bis 29, etwa 0,7 bis 0,8 × Verdichterdrehzahl. "
                    + "Vielleicht Leistung in %, noch nicht entschlüsselt."),
            entry((short) 0x07A8, "Status-Wort des Verdichters (VERDICHTER, in ha-stiebel-control für die WPL13E "
                    + "0x0008 Stillstand, 0x0221/0x0228 Betrieb). Hier 8 (0x008) im Stillstand, 24 (0x018) beim Heizen, "
                    + "264 (0x108) beim Abtauen am 16.11.2026 um 19:34: Bit 0x010 heißt Heizen, Bit 0x100 Abtauen, "
                    + "Bit 0x008 ist immer gesetzt. Gespeichert als WP_Roh_07A8."),
            entry((short) 0x0A36, "Die Wärmepumpe schreibt ihn etwa jede Sekunde an den Manager, er springt zwischen "
                    + "zwei Werten: 349 bis 388 beim Heizen, im Stillstand langsam fallend bis etwa 280. In "
                    + "OneESP32ToRuleThemAll FROSTSCHUTZTEMPERATUR, also vermutlich eine Wassertemperatur in der "
                    + "Außeneinheit in 0,1 °C, unbestätigt."),
            entry((short) 0x0669, "Frequenz-Anforderung in 0,01 Hz, die die Wärmepumpe dem Manager alle 10 s meldet "
                    + "(gedeutet, Name falsch). Folgt dem Regler-Integral, auch im Stillstand: etwa 34 Hz bei -1000, "
                    + "12 Hz bei +900. Über etwa 20 Hz läuft der Verdichter damit, darunter mit seinen Mindest-20 Hz."),
            // Other traffic on the bus
            entry((short) 0x0126, "Uhrzeit, Minute. Der Manager sendet sie jede Minute als Rundruf und an das "
                    + "Bedienteil."),
            entry((short) 0x005E, "Teilvorrang Warmwasser, Rundruf des Managers, immer 0: Warmwasser macht hier die "
                    + "eigene WWK 300."),
            entry((short) 0x002C, "Mischerlaufzeit, der Manager schreibt sie an die Wärmepumpe, immer 0."),
            entry((short) 0x02CA, "Der Manager schreibt ihn alle 10 s an die Wärmepumpe, direkt vor dem "
                    + "Regler-Integral: Ist-Temperatur des Heizkreises in 0,1 °C (HKISTTEMP in "
                    + "OneESP32ToRuleThemAll). Keine Materialnummer. Im Stillstand am 16.11.2026 gleich dem Rücklauf "
                    + "WPM (254 = 25,4 °C), beim Heizen bis 30,7 °C, am 16.11.2026 nie mehr als 0,2 K vom Rücklauf WPM "
                    + "entfernt. Deshalb nicht eigens gespeichert (bis 16.11.2026 abends als WP_HeizkreisIstWPM)."),
            entry((short) 0x0199, "Softwarenummer des Geräts, stündlich vom Manager (0x180) und von der Außeneinheit "
                    + "(0x500) gelesen: 390 und 446 (Bus-Scan 16.11.2026). Beim Abgleich schreibt der Manager 331 an "
                    + "die Wärmepumpe und die Wärmepumpe meldet 70, also wohl eine andere Zählung."),
            entry((short) 0x019A, "Softwareversion des Geräts: Manager 11, Außeneinheit 2, beim Abgleich ebenso."),
            // Found by the bus scan on 2026-11-16
            entry((short) 0x080A, "Taupunkt Referenz in 0,1 °C (0x500), kein Messwert: der höchste kompensierte "
                    + "Taupunkt seit der letzten Abtauung, also der sauberste Zustand des Verdampfers. Nach einer "
                    + "Abtauung -40 °C, ab 10 Minuten nach dem Start übernimmt sie den kompensierten Wert genau und "
                    + "folgt ihm danach nur nach oben (-7,7 bis -11,8 °C am 17.11.2026). Gespeichert als "
                    + "WP_TaupunktReferenz."),
            entry((short) 0x080B, "Taupunkt kompensiert in 0,1 °C (0x500), sinkt mit wachsendem Eis. Alle fünf "
                    + "Abtauungen am 17.11.2026 starteten, als er 3,1 K unter der Referenz lag. Gespeichert als "
                    + "WP_TaupunktKompensiert, die Reserve bis zur Abtauung als WP_AbtauReserve."),
            entry((short) 0x080D, "Gefilterter Verdampferdruck in 0,01 bar (0x500). Folgt dem Niederdruck, bleibt "
                    + "aber bei Abtauung und Stillstand stehen. Am 16./17.11.2026 gespeichert, dann nicht mehr abgefragt."),
            entry((short) 0x0820, "Verdampfertemperatur vor der Kompensation in 0,1 °C (0x500), gleich 0x07A9. Nicht "
                    + "mehr abgefragt."),
            entry((short) 0x0821, "Verdampfertemperatur nach der Kompensation in 0,1 °C (0x500), gleich 0x07A9. Nicht "
                    + "mehr abgefragt."),
            entry((short) 0x0728, "Kondensationstemperatur in 0,1 °C (0x500, gedeutet): 0,8 °C bei 8,2 bar bis 42,6 °C "
                    + "bei 25,4 bar Hochdruck, wie bei R410A. Gespeichert als WP_Kondensationstemp."),
            entry((short) 0x0729, "Hochdruck in 0,01 bar (0x500), gleich EINGANG6. Nicht abgefragt."),
            entry((short) 0x0673, "Beim Kessel (0x180): Volumenstrom × 100 in l/min, in denselben Stufen wie 0x02E2, "
                    + "also nicht feiner. Nicht abgefragt."),
            entry((short) 0x0A29, "Fühlereingang der Außeneinheit (0x500): Verdampfer in 0,1 °C, wie 0x07A9."),
            entry((short) 0x0A2A, "Fühlereingang der Außeneinheit (0x500): Sauggas in 0,1 °C, wie 0x063F."),
            entry((short) 0x0A2B, "Fühlereingang der Außeneinheit (0x500): Außentemperatur in 0,1 °C, wie 0x000C."),
            entry((short) 0x0A2C, "Fühlereingang der Außeneinheit (0x500): Heißgas in 0,1 °C, wie 0x0265."),
            entry((short) 0x0A2E, "Fühlereingang der Außeneinheit (0x500): Hochdruck in 0,01 bar, wie 0x07A6."),
            entry((short) 0x0A2F, "Fühlereingang der Außeneinheit (0x500): Niederdruck in 0,01 bar, wie 0x07A7."),
            entry((short) 0x0A32, "Fühlereingang der Außeneinheit (0x500): Rücklauf in 0,1 °C, aber in den groben "
                    + "Stufen von 0x0016."),
            entry((short) 0x0A33, "Fühlereingang der Außeneinheit (0x500): Vorlauf in 0,1 °C, aber in den groben "
                    + "Stufen von 0x01D6."),
            entry((short) 0x0668, "An der Außeneinheit (0x500) exakt linear zur Außentemperatur des Managers: 0 bei "
                    + "20 °C, etwa 327 je K kälter (5000 bei 4,7 °C). Vielleicht eine errechnete Heizlast, offen. "
                    + "Gespeichert als WP_Roh_500_0668."),
            entry((short) 0x06C5, "An der Außeneinheit (0x500): 4260 beim Heizen, 2500 bei Abtauung und Stillstand. "
                    + "Bedeutung offen, gespeichert als WP_Roh_500_06C5."),
            entry((short) 0x0822, "Vorlauf der Außeneinheit in 0,1 °C (0x500), aber nur in den groben Stufen von "
                    + "0x01D6 (16.11.2026: 332 bei 33,2 °C). Deshalb nicht abgefragt, fein ist nur 0xFDF3."),
            entry((short) 0x0823, "Rücklauf der Außeneinheit in 0,1 °C (0x500), aber nur in den groben Stufen von "
                    + "0x0016 (16.11.2026: 285 bei 28,5 °C). Deshalb nicht abgefragt, fein ist nur 0xFDF4."),
            entry((short) 0x01BF, "Außentemperatur der Sommer/Winter-Umschaltung in 0,1 °C, 18 °C. Einstellung, "
                    + "stündlich gelesen."),
            entry((short) 0x0A00, "Außentemperatur für den Frostschutz in 0,1 °C, 4 °C. Einstellung, stündlich "
                    + "gelesen."),
            entry((short) 0x01E8, "Maximale Heiztemperatur in 0,1 °C, 65 °C. Einstellung, stündlich gelesen."),
            entry((short) 0x1480, "Heizprogramm 1 Montag bis Freitag: oberes Byte Beginn, unteres Ende in "
                    + "Viertelstunden, 0x185A = 06:00 bis 22:30. Einstellung, stündlich gelesen."),
            entry((short) 0x1490, "Heizprogramm 1 Samstag und Sonntag: 0x1E5C = 07:30 bis 23:00. Einstellung, "
                    + "stündlich gelesen."),
            entry((short) 0x0074, "EVU-Sperre aktiv, der Manager (0x480) antwortet 1, auch beim Heizen (Bus-Scan "
                    + "16.11.2026). Vermutlich die Belegung des EVU-Kontakts, keine aktive Sperre."),
            entry((short) 0xFDAC, "EVU-Einstellung der Wärmepumpe, der Manager (0x480) antwortet 2 (Bus-Scan "
                    + "16.11.2026). Vermutlich Konfiguration."),
            entry((short) 0x06AF, "Das Bedienteil schreibt jede Minute 1 an den Manager, vermutlich ein Lebenszeichen."),
            entry((short) 0x0692, "Der Manager schreibt ihn jede Minute an das Bedienteil: 1 beim Heizen, 0 im "
                    + "Stillstand, vermutlich für die Anzeige."),
            entry((short) 0x4E5E, "Der Manager schreibt ihn jede Minute an das Bedienteil, beim Heizen und im "
                    + "Stillstand 1. Bedeutung unbekannt."),
            entry((short) 0x4E6A, "Der Manager schreibt ihn jede Minute an das Bedienteil: 32 beim Heizen, 0 im "
                    + "Stillstand, vermutlich ein Zustand für die Anzeige."),
            entry((short) 0x4E7E, "Der Manager schreibt ihn jede Minute an das Bedienteil: 1 beim Heizen, 0 im "
                    + "Stillstand, vermutlich für die Anzeige."),
            entry((short) 0x4E30, "Der Manager schreibt ihn jede Minute an die Wärmepumpe, immer 0."),
            entry((short) 0x068D, "Die Wärmepumpe schreibt ihn an den Manager: 0 im Stillstand, beim Heizen 5 bis 13, "
                    + "höher bei höherer Drehzahl (5 bei 21 Hz, 6 bei 24 Hz, 7 bei 30 Hz, 8 bei 34 Hz, 10 bei 40 bis 46 Hz, "
                    + "13 bei 47 Hz). Vermutlich eine Leistungsstufe, unbestätigt."),
            entry((short) 0xFE09, "Der Manager fragt 0x700 alle 5 s danach, 210 bis 211 (vielleicht 21,0 °C). "
                    + "Bedeutung unbekannt, wird als WP_Roh_FE09 mitgelesen."),
            entry((short) 0xFE4C, "Der Manager fragt 0x700 alle 5 s danach, immer 14. Bedeutung unbekannt."),
            entry((short) 0xFE1D, "Der Manager schreibt 0 an 0x700, 0x700 antwortet 2. Bedeutung unbekannt."),
            entry((short) 0xFE1E, "Der Manager schreibt 0 an 0x700. Bedeutung unbekannt."),
            // Verified on a WPL17 ICS classic (newer generation with CAN A/B) by ha-stiebel-control, tested here on
            // 2026-11-16: constant or not available, no longer requested
            entry((short) 0x0004, "Vorlauf-Solltemperatur (ha-stiebel-control, WPL 17 ICS classic). Hier immer "
                    + "5,0 °C, auch beim Heizen mit 34 °C Vorlauf: ein fester Wert, nicht der Sollwert, wird nicht mehr "
                    + "abgefragt."),
            entry((short) 0x01DB, "Aktuelle Wärmeleistung (ha-stiebel-control, WPL 17 ICS classic). Hier immer 0." + KONSTANT),
            entry((short) 0x01DC, "Wärmeertrag heute, Wh-Anteil (ha-stiebel-control). Hier immer 0." + KONSTANT),
            entry((short) 0x0053, "Heizkreispumpe (ha-stiebel-control). Hier immer 0." + KONSTANT),
            entry((short) 0x01D2, "Pumpenstatus als Bitfeld (ha-stiebel-control): 4352 (0x1100), solange die "
                    + "Umwälzpumpe läuft, 4096 (0x1000) im Stillstand (17.11.2026), Bit 0x0100 ist also die Pumpe. "
                    + "Das zeigt auch der Volumenstrom, deshalb nicht abgefragt (bis 17.11.2026 als "
                    + "WP_Roh_Pumpenstatus gespeichert)."),
            entry((short) 0x0059, "Status des Heizkreises (ha-stiebel-control). Hier immer 0." + KONSTANT),
            entry((short) 0xFDAE, "Status der Wärmepumpe als Bitfeld (ha-stiebel-control). Der Manager 0x480 antwortet "
                    + "0x8000 (nicht verfügbar). Beim Abgleich schreibt der Manager 0 an die Wärmepumpe."),
            entry((short) 0xFDB4, "Sommerbetrieb (ha-stiebel-control). Der Manager 0x480 antwortet immer 1, auch beim "
                    + "Heizen." + KONSTANT),
            entry((short) 0x0101, "Legionellenschutz aktiv. Der Manager 0x480 antwortet 0x8000 (nicht verfügbar). "
                    + "Warmwasser macht hier die eigene WWK 300."),
            entry((short) 0x4E8B, "Das Bedienteil schreibt 1 an den Manager, gesehen rund um einen Abgleich "
                    + "(16.11.2026). Bedeutung unbekannt."),
            // Rare block the manager requests from the heat pump after a sync, values from 2026-11-16 19:08 at 40 Hz
            entry((short) 0xC24C, "Seltener Block, den der Manager nach einem Abgleich bei der Wärmepumpe abfragt: 35 "
                    + "(gleich 0x4ED7). Bedeutung unbekannt."),
            entry((short) 0xC24D, "Seltener Block nach einem Abgleich: 75, gleich der Lüfterleistung in % zu der Zeit, "
                    + "vielleicht Zufall. Bedeutung unbekannt."),
            entry((short) 0xC252, "Seltener Block nach einem Abgleich: 0. Bedeutung unbekannt."),
            entry((short) 0xC255, "Seltener Block nach einem Abgleich: 3. Bedeutung unbekannt."),
            entry((short) 0xC303, "Seltener Block nach einem Abgleich: 0. Bedeutung unbekannt."),
            entry((short) 0x4ED7, "Seltener Block nach einem Abgleich: 35 (gleich 0xC24C). Bedeutung unbekannt."),
            entry((short) 0x4ED8, "Seltener Block nach einem Abgleich: 170. Bedeutung unbekannt."),
            entry((short) 0x4ED9, "Seltener Block nach einem Abgleich: 0. Bedeutung unbekannt."),
            entry((short) 0xC2C0, "Seltener Block nach einem Abgleich: 13428, sieht nach einem Zähler aus. Bedeutung "
                    + "unbekannt."),
            entry((short) 0xC2C1, "Seltener Block nach einem Abgleich: 51. Bedeutung unbekannt."),
            entry((short) 0x06FF, "Der Manager fragt ihn beim Abgleich bei der Wärmepumpe ab: 3601. Bedeutung "
                    + "unbekannt, Name TEST_OBJEKT_190 passt nicht."),
            entry((short) 0x0961, "Der Manager fragt ihn beim Abgleich bei der Wärmepumpe ab: 1097. Bedeutung "
                    + "unbekannt."),
            // Sync of manager and heat pump, seen once on 2026-11-16 10:41:58
            entry((short) 0xFFFE, "Systemnachricht (Art 6 und 7), danach Abgleich: die Wärmepumpe schreibt ihre "
                    + "Stammdaten an den Manager, der Manager schreibt seine Softwarenummer und fragt Laufzeiten und "
                    + "Software ab, dann schickt er Datum und Uhrzeit an alle. Mal melden sich alle Geräte (auch der "
                    + "Mischer 0x601), mal stößt die Wärmepumpe allein an. Kein fester Takt: am 16.11.2026 um 10:41, "
                    + "16:19 und 16:32 Uhr."),
            entry((short) 0x079F, "Stützstelle des Niederdruckfühlers, unteres Ende: 100 = 1,00 bar."),
            entry((short) 0x07A0, "Stützstelle des Niederdruckfühlers, oberes Ende: 1700 = 17,00 bar. Messbereich also "
                    + "1 bis 17 bar."),
            entry((short) 0x07A1, "Stützstelle des Hochdruckfühlers, unteres Ende: 100 = 1,00 bar."),
            entry((short) 0x07A2, "Stützstelle des Hochdruckfühlers, oberes Ende: 4300 = 43,00 bar. Messbereich also "
                    + "1 bis 43 bar."),
            entry((short) 0x000B, "Gerätekennung. Die Wärmepumpe meldet 582, der Manager schreibt -128."),
            entry((short) 0x07F2, "Betriebsart der Wärmepumpe, beim Abgleich gemeldet: 3 beim Heizen, 0 im Stillstand."),
            entry((short) 0x0067, "Sonderkreis-Status, beim Abgleich gemeldet: 3 beim Heizen, 0 im Stillstand."),
            entry((short) 0x0061, "Heißt „Abtauung aktiv“, ist es aber nicht: geht etwa 20 s vor dem Verdichterstart "
                    + "von 0 auf 512 (Test 2026-09-23). Beim Abgleich 512 beim Heizen, 0 im Stillstand."),
            entry((short) 0x02C2, "Anzahl der Startversuche des Verdichters, beim Abgleich 0."),
            entry((short) 0x0001, "Fehlermeldung der Wärmepumpe, 0 = keine."),
            entry((short) 0x01F2, "Fehlerart der Wärmepumpe, 0 = keine."),
            entry((short) 0x080E, "Schwerer Fehler (fatal error) der Wärmepumpe, 0 = keiner."),
            entry((short) 0x0828, "Fehler im Parametersatz der Wärmepumpe, 0 = keiner."),
            entry((short) 0x0122, "Datum, Tag. Der Manager schickt Datum und Uhrzeit beim Abgleich als Rundruf."),
            entry((short) 0x0123, "Datum, Monat."),
            entry((short) 0x0124, "Datum, Jahr zweistellig."),
            entry((short) 0x0125, "Uhrzeit, Stunde."),
            entry((short) 0x0359, "Hardwarenummer, die Wärmepumpe antwortet 0x8000 (nicht verfügbar)."),
            entry((short) 0xFDAA, "Buskontrolle, schreibt der Heizungsregler (0x180) beim Abgleich an die Wärmepumpe."),
            entry((short) 0x0A20, "Sammel-Relaisstatus, Rundruf des Managers beim Abgleich, 0."),
            entry((short) 0x0010, "Gerätekonfiguration, Rundruf des Managers beim Abgleich, 0."),
            entry((short) 0x005A, "Speicherstatus, die Wärmepumpe meldet ihn beim Abgleich (-127)."));

    /**
     * Names shown instead of the Elster name where the Elster name is misleading; a trailing "?" marks a meaning that
     * is only guessed. The Elster name stays the key and is shown in the tooltip.
     */
    private static final Map<Short, String> ANZEIGENAME = Map.ofEntries(
            entry((short) 0x02CA, "Heizkreis-Isttemperatur"),
            entry((short) 0xFDF3, "Vorlauf fein"),
            entry((short) 0xFDF4, "Rücklauf fein"),
            entry((short) 0xFDF5, "Vorlauf hinter Heizstab?"),
            entry((short) 0x0669, "Frequenz-Anforderung"),
            entry((short) 0x0917, "Regler-Integral"),
            entry((short) 0x06B1, "Spannung Inverter"),
            entry((short) 0x06B2, "Strom Inverter"),
            entry((short) 0x063B, "Verdichterdrehzahl"),
            entry((short) 0x07A8, "Verdichter-Status"),
            entry((short) 0xFE1B, "Pumpe PWM"),
            entry((short) 0x005F, "Heizanforderung"),
            entry((short) 0x0061, "Heizanforderung beim Abgleich"),
            entry((short) 0x0A36, "Wassertemperatur Außeneinheit?"),
            entry((short) 0xFE07, "Verdichterleistung?"),
            entry((short) 0x068D, "Leistungsstufe?"),
            entry((short) 0x0692, "Heizen (Anzeige)?"),
            entry((short) 0x4E6A, "Zustand (Anzeige)?"),
            entry((short) 0x4E7E, "Heizen (Anzeige)?"),
            entry((short) 0x0A29, "Eingang Verdampfer"),
            entry((short) 0x0A2A, "Eingang Sauggas"),
            entry((short) 0x0A2B, "Eingang Außentemperatur"),
            entry((short) 0x0A2C, "Eingang Heißgas"),
            entry((short) 0x0A2E, "Eingang Hochdruck"),
            entry((short) 0x0A2F, "Eingang Niederdruck"),
            entry((short) 0x0A32, "Eingang Rücklauf"),
            entry((short) 0x0A33, "Eingang Vorlauf"),
            entry((short) 0x0A37, "Fühlereingang 15"),
            entry((short) 0x0728, "Kondensationstemperatur"),
            entry((short) 0x080A, "Taupunkt Referenz"),
            entry((short) 0x080B, "Taupunkt kompensiert"),
            entry((short) 0x1480, "Heizprogramm Mo–Fr"),
            entry((short) 0x1490, "Heizprogramm Sa–So"),
            entry((short) 0x01BF, "Sommer/Winter-Umschaltung"));

    // Found by the bus scan on 2026-11-16, meaning still open, requested every minute and stored raw
    private static final java.util.Set<Short> SCAN_ROHWERTE = java.util.Set.of(
            (short) 0x06C2, (short) 0x06C3, (short) 0x06C4, (short) 0x06A0, (short) 0x06AC, (short) 0x0A37);

    private IndexBeschreibung() {
    }

    /**
     * Name to show for the index: the own name where the Elster name is misleading, otherwise the Elster name
     */
    public static String anzeigename(short index, String elsterName) {
        return ANZEIGENAME.getOrDefault(index, elsterName);
    }

    /**
     * Description of the index, null if none is known
     */
    public static String beschreibung(short index) {
        String text = BESCHREIBUNG.get(index);
        if (text == null && SCAN_ROHWERTE.contains(index)) {
            return "Beim Bus-Scan am 16.11.2026 mit einem Wert gefunden, Bedeutung unbekannt. Wird jede Minute "
                    + "abgefragt und roh als WP_Roh_Gerät_Index gespeichert, um zu sehen, ob und wie er sich mit dem "
                    + "Betrieb ändert.";
        }
        if (text == null && index >= Fehlerliste.ERSTES_FELD && index < Fehlerliste.ERSTES_FELD + Fehlerliste.FELDER) {
            return "Feld der Fehlerliste des Wärmepumpenmanagers: 20 Einträge zu je 7 Feldern, nämlich Minute, Stunde, "
                    + "Tag, Monat, Jahr, ein Feld ohne Bedeutung und der Fehlercode.";
        }
        return text;
    }
}
