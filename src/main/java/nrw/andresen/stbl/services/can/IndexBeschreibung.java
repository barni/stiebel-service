package nrw.andresen.stbl.services.can;

import java.util.Map;

import static java.util.Map.entry;

/**
 * What the Elster indices on this installation stand for, for the CAN bus page. The names of ElsterTable come from
 * older devices and often do not fit the WPM3; "gedeutet" marks a meaning derived from the bus traffic only.
 */
public final class IndexBeschreibung {

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
            entry((short) 0xC281, "Soll-Drehzahl des Lüfters der Außeneinheit in Hz."),
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
            entry((short) 0xFDF3, "Vorlauftemperatur in 0,1 K: 0x700 meldet sie dem Manager alle 5 s (gedeutet, Name "
                    + "falsch). Beim Heizen bis 1 K über dem groben Vorlauf. 0x700 beantwortet eigene Abfragen nicht."),
            entry((short) 0xFDF4, "Rücklauftemperatur in 0,1 K von 0x700 (gedeutet, Name falsch). Spreizung mit 0xFDF3 "
                    + "4,9 bis 5,2 K bei 5 K Soll."),
            entry((short) 0xFDF5, "Dritte Temperatur von 0x700 in 0,1 K, liegt nahe am feinen Vorlauf. Bedeutung "
                    + "unbekannt, Name falsch."),
            entry((short) 0xFE1B, "PWM-Signal der Umwälzpumpe, das der Manager an 0x700 schreibt (gedeutet, Name "
                    + "unbekannt): 100 im Stillstand. Beim Heizen 70 bis 72 bei 9,0 l/min, 66 bis 67 bei 11,1 l/min, "
                    + "57 bis 59 bei 13,5 l/min: je kleiner, desto mehr Durchfluss."),
            entry((short) 0x0917, "Integral der Regelabweichung, das der Manager alle 10 s an die Wärmepumpe schreibt "
                    + "(gedeutet). Steigt, solange der Vorlauf über etwa 33 °C liegt, fällt darunter. Bei etwa -1000 "
                    + "Start und volle Leistung, ab etwa 0 kleinste Drehzahl, bei etwa +900 bis +1000 Stopp "
                    + "(16.11.2026)."),
            entry((short) 0xFE07, "Von 0x700: 0 im Stillstand, beim Heizen 17 bis 23, etwa 0,8 × Verdichterdrehzahl. "
                    + "Vielleicht Leistung in %, noch nicht entschlüsselt."),
            entry((short) 0x07A8, "Wärmepumpe an Manager: 8 im Stillstand, 24 beim Heizen. Vermutlich Statusbits, noch "
                    + "nicht entschlüsselt."),
            entry((short) 0x0A36, "Die Wärmepumpe schreibt ihn etwa jede Sekunde an den Manager, er springt zwischen "
                    + "zwei Werten: 349 bis 388 beim Heizen, im Stillstand langsam fallend bis etwa 280. Vielleicht eine "
                    + "Temperatur in 0,1 °C nahe der Inverter-Temperatur am Verdichter, unbestätigt."),
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
                    + "Regler-Integral. Ändert sich langsam (254 bis 279, im Stillstand am 16.11.2026 256 bis 259). "
                    + "Keine Materialnummer, Bedeutung unbekannt, wird als WP_Roh_02CA mitgelesen."),
            entry((short) 0x0199, "Softwarenummer des Geräts. Beim Abgleich schreibt der Manager seine eigene (331) "
                    + "an die Wärmepumpe, die Wärmepumpe meldet 70."),
            entry((short) 0x019A, "Softwareversion des Geräts. Manager 11, Wärmepumpe 2."),
            entry((short) 0x06AF, "Das Bedienteil schreibt jede Minute 1 an den Manager, vermutlich ein Lebenszeichen."),
            entry((short) 0x0692, "Der Manager schreibt ihn jede Minute an das Bedienteil, Bedeutung unbekannt."),
            entry((short) 0x4E5E, "Der Manager schreibt ihn jede Minute an das Bedienteil, Bedeutung unbekannt."),
            entry((short) 0x4E6A, "Der Manager schreibt ihn jede Minute an das Bedienteil, Bedeutung unbekannt."),
            entry((short) 0x4E7E, "Der Manager schreibt ihn jede Minute an das Bedienteil, Bedeutung unbekannt."),
            entry((short) 0x4E30, "Der Manager schreibt ihn jede Minute an die Wärmepumpe, immer 0."),
            entry((short) 0x068D, "Die Wärmepumpe schreibt ihn an den Manager: 0 im Stillstand, beim Heizen 5 bis 8, "
                    + "höher bei höherer Drehzahl (5 bei 21 Hz, 6 bei 24 Hz, 7 bei 30 Hz). Vermutlich eine "
                    + "Leistungsstufe, unbestätigt."),
            entry((short) 0xFE09, "Der Manager fragt 0x700 alle 5 s danach, 210 bis 211 (vielleicht 21,0 °C). "
                    + "Bedeutung unbekannt, wird als WP_Roh_FE09 mitgelesen."),
            entry((short) 0xFE4C, "Der Manager fragt 0x700 alle 5 s danach, immer 14. Bedeutung unbekannt."),
            entry((short) 0xFE1D, "Der Manager schreibt 0 an 0x700, 0x700 antwortet 2. Bedeutung unbekannt."),
            entry((short) 0xFE1E, "Der Manager schreibt 0 an 0x700. Bedeutung unbekannt."),
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
            entry((short) 0xFDAE, "Status der Wärmepumpe, schreibt der Manager beim Abgleich, 0."),
            entry((short) 0x0A20, "Sammel-Relaisstatus, Rundruf des Managers beim Abgleich, 0."),
            entry((short) 0x0010, "Gerätekonfiguration, Rundruf des Managers beim Abgleich, 0."),
            entry((short) 0x005A, "Speicherstatus, die Wärmepumpe meldet ihn beim Abgleich (-127)."));

    private IndexBeschreibung() {
    }

    /**
     * Description of the index, null if none is known
     */
    public static String beschreibung(short index) {
        String text = BESCHREIBUNG.get(index);
        if (text == null && index >= Fehlerliste.ERSTES_FELD && index < Fehlerliste.ERSTES_FELD + Fehlerliste.FELDER) {
            return "Feld der Fehlerliste des Wärmepumpenmanagers: 20 Einträge zu je 7 Feldern, nämlich Minute, Stunde, "
                    + "Tag, Monat, Jahr, ein Feld ohne Bedeutung und der Fehlercode.";
        }
        return text;
    }
}
