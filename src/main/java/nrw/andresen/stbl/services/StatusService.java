package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * HTML status page with all current values, the fault list and the state of the CAN connection
 */
@Component
public class StatusService {

    @Autowired
    private Waermepumpe wp;
    @Autowired
    private CanBus can;
    @Autowired
    private WaermebedarfVergleich vergleich;
    @Autowired
    private Tagesuebersicht tagesuebersicht;
    @Autowired
    private Ueberwachung ueberwachung;

    // What the CAN nodes are, as far as known from the answered values
    private static final Map<Integer, String> KNOTEN_ERKLAERUNG = Map.of(
            0x180, "Wärmepumpenmanager WPM3 im HM Trend, Heizungsregler (Betriebsstatus, Heizungsdruck).",
            0x480, "Wärmepumpenmanager WPM3, Steuerung der Wärmepumpe, fragt die Außeneinheit 0x500 ab.",
            0x500, "Außeneinheit WPL 17 ACS (Verdichter, Kältekreis, Inverter, Lüfter).",
            0x514, "Außeneinheit, Zugang für externe Abfragen mit Zählern, Laufzeiten und Einstellungen.");

    private static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm:ss");

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static String zahl(Double wert, int stellen) {
        return wert == null ? "–" : String.format(Locale.GERMANY, "%." + stellen + "f", wert);
    }

    private static String zeitpunkt(Instant zeit) {
        return zeit == null ? null : ZEITPUNKT.format(zeit.atZone(ZoneId.systemDefault()));
    }

    /**
     * Status page in four areas: overview (always open), plant, evaluation and system (folded)
     *
     * @return HTML page
     */
    public String getStatus() {
        StatusPage page = new StatusPage();
        kopf(page);
        page.bereich("Übersicht");
        warnungen(page);
        tagesuebersicht(page);
        fehlerliste(page);
        anlage(page);
        auswertung(page);
        system(page);
        return page.render();
    }

    private void kopf(StatusPage page) {
        List<String> aktiv = ueberwachung.getWarnungen().stream().filter(Warnung::isAktiv).map(Warnung::getName)
                .toList();
        page.badge("Verdichter läuft", "Verdichter aus", wp::isVerdichterOn)
                .hinweis(aktiv.isEmpty() ? "keine Warnung" : String.join(", ", aktiv), !aktiv.isEmpty())
                .badgeWennAn("Abtauung", wp::isAbtauung)
                .badgeWennAn("EVU-Sperre", wp::isEvuSperre)
                .badgeWennAn("Heizstab", () -> {
                    ValueContainer<Boolean> dhc1 = wp.isDHC_1On();
                    return new ValueContainer<>(dhc1.getValue() || wp.isDHC_2On().getValue(), dhc1.getTimestamp());
                })
                .kpi("Außentemperatur", "°C", 1, "Aussentemp", wp::getAussentemp)
                .info("Außentemperatur am Fühler der Außeneinheit (0x500). Kann vom Außenfühler des "
                        + "Wärmepumpenmanagers etwas abweichen, beim Abtauen steigt sie kurz um einige Kelvin.")
                .kpi("Vorlauf", "°C", 1, "VorlaufIstTemp", wp::getVorlaufIstTemp)
                .info("Temperatur des Wassers, das von der Wärmepumpe zum Heizkreis fließt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .kpi("Rücklauf", "°C", 1, "RuecklaufIstTemp", wp::getRuecklaufIstTemp)
                .info("Temperatur des Wassers, das vom Heizkreis zur Wärmepumpe zurückkommt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .kpi("Heizungsdruck", "bar", 2, "Heizungsdruck", wp::getHeizungsdruck)
                .info("Wasserdruck im Heizkreis, gemessen im HM Trend und gemeldet vom Wärmepumpenmanager (0x180).")
                .kpi("Wärmeleistung (berechnet)", "kW", 2, "WaermeleistungBerechnet", wp::getWaermeleistung)
                .info("Wärme, die die Wärmepumpe gerade an das Heizwasser abgibt.")
                .formula(Waermepumpe.formelWaermeleistung())
                .ersatz("–")
                .kpi("Arbeitszahl (berechnet)", "", 1, "ArbeitszahlGeschaetzt", wp::getArbeitszahl)
                .info("Momentane Arbeitszahl (COP): abgegebene Wärme je eingesetzter elektrischer Leistung. Nur "
                        + "während der Verdichter läuft.")
                .formula(Waermepumpe.formelArbeitszahl())
                .ersatz("aus");
    }

    private void warnungen(StatusPage page) {
        page.card("Warnungen");
        List<Warnung> warnungen = ueberwachung.getWarnungen();
        boolean keine = true;
        for (Warnung warnung : warnungen) {
            if (warnung.isAktiv()) {
                keine = false;
                String seit = Fehlerliste.ZEITFORMAT.format(warnung.getAktivSeit().atZone(ZoneId.systemDefault()));
                page.text(warnung.getName(), "seit " + seit + ": " + warnung.getText()).info(warnung.getRegel());
            }
        }
        if (keine) {
            page.text("Aktive Warnungen", "keine")
                    .info("Jede Minute geprüft. Wird eine Warnung aktiv, kommt eine Mail an stbl.mail.to.");
        }
        page.details("Alle " + warnungen.size() + " Prüfungen");
        for (Warnung warnung : warnungen) {
            page.text(warnung.getName(), warnung.isAktiv() ? "aktiv" : "nein").info(warnung.getRegel());
        }
        page.detailsEnde();
    }

    private void tagesuebersicht(StatusPage page) {
        page.card("Heute und gestern");
        Tagesuebersicht.Tag heute = tagesuebersicht.getHeute();
        Tagesuebersicht.Tag gestern = tagesuebersicht.getGestern();
        if (heute == null || gestern == null) {
            page.text("Tage", "wird berechnet");
            return;
        }
        Tagesuebersicht.Vergleichstage gleich = tagesuebersicht.getAehnlicheTage();
        List<List<String>> zeilen = new ArrayList<>();
        zeilen.add(List.of("Außen °C", zahl(heute.aussentemp(), 1), zahl(gestern.aussentemp(), 1),
                gleich == null ? "–" : zahl(gleich.aussentemp(), 1)));
        zeilen.add(List.of("Starts", String.valueOf(heute.starts()), String.valueOf(gestern.starts()),
                gleich == null ? "–" : zahl(gleich.starts(), 1)));
        zeilen.add(List.of("Laufzeit h", zahl(heute.laufzeitH(), 1), zahl(gestern.laufzeitH(), 1),
                gleich == null ? "–" : zahl(gleich.laufzeitH(), 1)));
        zeilen.add(List.of("h je Start", zahl(heute.laufzeitProStart(), 2), zahl(gestern.laufzeitProStart(), 2),
                gleich == null ? "–" : zahl(gleich.laufzeitProStart(), 2)));
        zeilen.add(List.of("Wärme kWh", zahl(heute.waermeKWh(), 1), zahl(gestern.waermeKWh(), 1),
                gleich == null ? "–" : zahl(gleich.waermeKWh(), 1)));
        zeilen.add(List.of("Strom kWh", zahl(heute.stromKWh(), 1), zahl(gestern.stromKWh(), 1), "–"));
        zeilen.add(List.of("Arbeitszahl", zahl(heute.arbeitszahl(), 1), zahl(gestern.arbeitszahl(), 1), "–"));
        zeilen.add(List.of("Abtauungen", String.valueOf(heute.abtauungen()), String.valueOf(gestern.abtauungen()),
                "–"));
        page.table(List.of("", "Heute", "Gestern", "Ø gleiche Außentemp."), zeilen)
                .note("„Ø gleiche Außentemp.“: Mittel aller Tage seit " + vergleich.getStart().format(DATUM)
                        + ", deren Tagesmittel höchstens " + zahl(Tagesuebersicht.AEHNLICH_K, 1)
                        + " K von gestern abweicht" + (gleich == null ? "" : " (" + gleich.tage() + " Tage)")
                        + ". Daran siehst du, ob gestern normal war. Strom = geschätzte Wirkleistung des "
                        + "Verdichter-Inverters, Arbeitszahl = Wärme ÷ Strom. Stand "
                        + zeitpunkt(tagesuebersicht.getBerechnet()) + ".");
    }

    private void fehlerliste(StatusPage page) {
        page.card("Fehlerliste");
        List<Fehlerliste.Eintrag> eintraege = can.getFehlerliste().eintraege();
        if (eintraege.isEmpty()) {
            page.text("Einträge", can.getFehlerlisteAngefragt() == null ? null : "keine")
                    .info("Die Fehlerliste des Wärmepumpenmanagers wird alle 10 Minuten gelesen.");
            return;
        }
        Map<String, Long> anzahl = new LinkedHashMap<>();
        eintraege.forEach(e -> anzahl.merge(e.text(), 1L, Long::sum));
        anzahl.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> page.text(e.getValue() + " ×", e.getKey()));
        Fehlerliste.Eintrag neuester = eintraege.get(0);
        page.text("Letzter Eintrag", Fehlerliste.ZEITFORMAT.format(neuester.zeit()) + " · " + neuester.text())
                .info("Fehlerliste des WPM (DIAGNOSE → FEHLERLISTE) mit 20 Plätzen, alle 10 Minuten gelesen. Ein "
                        + "neuer Eintrag wird per Mail gemeldet.")
                .details("Alle " + eintraege.size() + " Einträge");
        for (Fehlerliste.Eintrag eintrag : eintraege) {
            page.text(Fehlerliste.ZEITFORMAT.format(eintrag.zeit()), eintrag.text())
                    .info("Code " + eintrag.code() + ", Platz " + (eintrag.platz() + 1) + " im Ringspeicher des WPM.");
        }
        page.detailsEnde();
    }

    private void anlage(StatusPage page) {
        page.bereichKlappbar("Anlage", "Betrieb · Heizkreis · Kältekreis · Verdichter", false)
                .card("Betrieb")
                .pill("Pufferladepumpe", wp::isPufferladepumpeOn)
                .info("Pumpe, die das Heizwasser durch die Wärmepumpe fördert, Betriebsstatus des "
                        + "Wärmepumpenmanagers (0x180).")
                .pill("Warmwasserladepumpe", wp::isWarmwasserladepumpe)
                .info("Ladung des Warmwasserspeichers aktiv, Betriebsstatus des Wärmepumpenmanagers (0x180).")
                .pill("DHC 1", wp::isDHC_1On)
                .info("Stufe 1 des elektrischen Heizstabs (Not-/Zusatzheizung) im HM Trend, Betriebsstatus (0x180).")
                .pill("DHC 2", wp::isDHC_2On)
                .info("Stufe 2 des elektrischen Heizstabs (Not-/Zusatzheizung) im HM Trend, Betriebsstatus (0x180).")
                .pill("EVU-Sperre", wp::isEvuSperre)
                .info("Sperrsignal des Energieversorgers am Kontakt EVU. Während der Sperre darf die Wärmepumpe "
                        + "nicht laufen.")
                .pill("Abtauung (berechnet)", wp::isAbtauung)
                .info("Die Außeneinheit taut ihren vereisten Verdampfer ab und nimmt dafür kurz Wärme aus dem "
                        + "Heizwasser. Der Dienst erkennt das selbst, weil die Wärmepumpe es nicht zuverlässig "
                        + "meldet.")
                .formula(Waermepumpe.formelAbtauung())
                .row("Spreizung (berechnet)", "K", 1, "Spreizung", wp::getSpreizung)
                .info("Temperaturunterschied zwischen Vor- und Rücklauf. Der Sollwert steht unter Einstellungen "
                        + "(Soll-Spreizung).")
                .formula("Vorlauf − Rücklauf")
                .row("Volumenstrom", "l/min", 1, "WasserVolumenstrom", wp::getVolumenstrom)
                .info("Heizwasser, das durch die Wärmepumpe fließt (0x500). Wird jede Minute abgefragt.")
                .row("Laufzeit DHC 1", "h", 0, "LaufzeitDHZ1", wp::getLaufzeit_DHC1)
                .info("Betriebsstunden der Heizstab-Stufe 1 seit Inbetriebnahme (0x500).")
                .row("Laufzeit DHC 2", "h", 0, "LaufzeitDHZ2", wp::getLaufzeit_DHC2)
                .info("Betriebsstunden der Heizstab-Stufe 2 seit Inbetriebnahme (0x500).")
                .row("Laufzeit DHC 1+2", "h", 0, "LaufzeitDHZ12", wp::getLaufzeit_DHC12)
                .info("Betriebsstunden mit beiden Heizstab-Stufen gleichzeitig seit Inbetriebnahme (0x500).")
                .card("Kältekreis")
                .row("Hochdruck", "bar", 2, "Hochdruck", wp::getHochdruck)
                .info("Absoluter Druck auf der Hochdruckseite des Kältekreises (0x500). Entspricht der "
                        + "Kondensationstemperatur, die etwa der Vorlauftemperatur folgt.")
                .row("Niederdruck", "bar", 2, "Niederdruck", wp::getNiederdruck)
                .info("Absoluter Druck auf der Saugseite des Kältekreises (0x500). Entspricht der "
                        + "Verdampfungstemperatur, im Betrieb einige Kelvin unter der Außentemperatur.")
                .row("Heißgas", "°C", 1, "HeissgasTemp", wp::getHeissgasTemp)
                .info("Temperatur des Kältemittels direkt hinter dem Verdichter (0x500).")
                .row("Verdichter-Eintritt", "°C", 1, "VerdichterEintrittstemp", wp::getVerdichterEintrittstemp)
                .info("Temperatur des angesaugten Kältemittels am Verdichtereintritt (0x500).")
                .row("Verdampfer", "°C", 1, "VerdampferTemp", wp::getVerdampferTemp)
                .info("Temperatur am Austritt des Verdampfers in der Außeneinheit (0x500). Steigt beim Abtauen stark "
                        + "an.")
                .row("Ölsumpf", "°C", 1, "OelsumpfTemp", wp::getOelsumpfTemp)
                .info("Temperatur des Öls im Verdichter (0x500).")
                .row("Überhitzung", "K", 1, "UeberhitzungIst", wp::getIstUeberhitzung)
                .info("Wie viel wärmer das Kältemittel am Verdampferaustritt ist als seine Verdampfungstemperatur "
                        + "(0x514). Darauf regelt das Expansionsventil.")
                .row("Überhitzung Soll", "K", 1, "UeberhitzungSoll", wp::getSollUeberhitzung)
                .info("Zielwert der Überhitzung für das Expansionsventil (0x514).")
                .row("Expansionsventil", "%", 1, "OeffnungsgradEXV", wp::getOeffnungsgradExv)
                .info("Öffnung des elektronischen Expansionsventils (0x514). Beim Heizen meist um 20–25 %, beim "
                        + "Abtauen und kurz nach einem Start bis 100 %.")
                .row("Lüfter", "Hz", 0, "LuefterIstDrehzahlHz", wp::getLuefterIstDrehzahl)
                .info("Ist-Drehzahl des Lüfters der Außeneinheit (0x514).")
                .row("Lüfter Soll", "Hz", 0, "LuefterSollDrehzahlHz", wp::getLuefterSollDrehzahl)
                .info("Von der Regelung geforderte Lüfterdrehzahl (0x514).")
                .row("Lüfterleistung", "%", 0, "LuefterLeistung", wp::getLuefterLeistung)
                .info("Relative Leistung des Lüfters der Außeneinheit (0x500).")
                .card("Verdichter und Inverter")
                .row("Drehzahl", "Hz", 0, "VerdichterDrehzahlHz", wp::getVerdichterDrehzahl)
                .info("Aktuelle Drehzahl des Inverter-Verdichters in der Außeneinheit (0x500). 0 bedeutet, der "
                        + "Verdichter steht.")
                .row("Solldrehzahl", "Hz", 0, "VerdichterSollDrehzahlHz", wp::getVerdichterSollDrehzahl)
                .info("Drehzahl, die die Regelung vom Verdichter fordert (0x514).")
                .row("Spannung", "V", 1, "SpannungInverter", wp::getSpannungInverter)
                .info("Netzspannung am Verdichter-Inverter (0x500). Zeigt dauerhaft etwa 10 V zu wenig an, der "
                        + "Hauszähler misst richtig.")
                .row("Strom", "A", 1, "StromInverter", wp::getStromInverter)
                .info("Stromaufnahme des Verdichter-Inverters (0x500).")
                .row("Scheinleistung (berechnet)", "VA", 0, "LeistungInverter", wp::getLeistungInverter)
                .info("Momentane Leistungsaufnahme des Verdichter-Inverters. Die Wirkleistung liegt laut Smartmeter "
                        + "bei etwa 80 % bei 600 VA und erreicht ab 1000 VA die Scheinleistung.")
                .formula(Waermepumpe.FORMEL_SCHEINLEISTUNG)
                .row("Umgebungstemperatur Inverter", "°C", 1, "UmgebungstempInverter", wp::getUmgebungstempInverter)
                .info("Temperatur in der Umgebung der Inverter-Elektronik (0x514).")
                .row("Temperatur Verdichter", "°C", 1, "TempInverterVerdichter", wp::getTempInverterVerdichter)
                .info("Vom Inverter gemessene Temperatur am Verdichter (0x514).")
                .row("Laufzeit Heizen", "h", 0, "LaufzeitVerdichterHeizen", wp::getLaufzeitVerdichterHeizen)
                .info("Betriebsstunden des Verdichters im Heizbetrieb seit Inbetriebnahme (0x514).")
                .row("Starts", "", 0, "VerdichterStarts", wp::getVerdichterStarts)
                .info("Anzahl der Verdichterstarts seit Inbetriebnahme (0x514). Wenige, lange Läufe sind effizienter "
                        + "und schonen den Verdichter.")
                .row("Laufzeit je Start (berechnet)", "h", 2, "LaufzeitProStart", wp::getLaufzeitProStart)
                .info("Durchschnittliche Laufzeit eines Verdichterlaufs über die gesamte Betriebszeit.")
                .formula("Laufzeit Heizen ÷ Verdichterstarts")
                .row("Laufzeit Abtauen", "h", 0, "LaufzeitVerdichterAbtauen", wp::getLaufzeitVerdichterAbtauen)
                .info("Betriebsstunden des Verdichters beim Abtauen seit Inbetriebnahme (0x514).")
                .row("Dauer letzte Abtauung", "min", 0, "DauerLetzteAbtauung", wp::getDauerLetzteAbtauung)
                .info("Dauer der letzten Abtauung (0x514).");
    }

    private void auswertung(StatusPage page) {
        Duration stunde = Waermepumpe.MAX_AGE_3600;
        page.bereichKlappbar("Auswertung", "Energie · Einstellungen · Vergleich Wärmebedarf", false)
                .zweiSpalten()
                .card("Energie seit Inbetriebnahme")
                .row("Stromaufnahme", "MWh", 3, "AufnahmeLeistung", wp::getAufnahmeLeistung)
                .info("Elektrische Energie fürs Heizen laut Stromzähler der Wärmepumpe (0x514), aus Summe und "
                        + "Tageswert zusammengesetzt. Der Zähler zählt rund 15–20 % zu wenig.")
                .row("Wärmeerzeugung", "MWh", 3, "AbgabeWaerme", wp::getAbgabeWaerme)
                .info("Heizwärme laut Wärmezähler der Wärmepumpe (0x514), aus Summe und Tageswert zusammengesetzt. "
                        + "Stimmt auf etwa 6 % mit der Rechnung aus Durchfluss und Spreizung überein.")
                .row("Zusatzheizung", "MWh", 3, "WaermeZusatzheizung", wp::getWaermeZusatzheizung)
                .info("Vom Heizstab erzeugte Wärme (0x514).")
                .row("Effizienz (Zähler, berechnet)", "", 2, "EffizienzZaehler", wp::getEffizienz)
                .info("Arbeitszahl nach den Zählern der Wärmepumpe. Zu hoch, weil der Stromzähler zu wenig zählt.")
                .formula("Wärmeerzeugung ÷ Stromaufnahme, beides Zähler der Wärmepumpe")
                .row("Effizienz (korrigiert, berechnet)", "", 2, "EffizienzKorrigiert", wp::getEffizienzKorrigiert)
                .info("Realistische Arbeitszahl mit korrigiertem Stromverbrauch.")
                .formula("Wärmeerzeugung ÷ (Stromaufnahme × " + Waermepumpe.zahl(Waermepumpe.STROMZAEHLER_KORREKTUR)
                        + "), der Stromzähler der Wärmepumpe zählt rund 20 % zu wenig")
                .card("Einstellungen")
                .row("Auslegungstemperatur", "°C", 1, "Einstellung_Auslegungstemperatur",
                        wp::getAuslegungstemperatur, stunde)
                .info("Tiefste Außentemperatur, für die die Anlage ausgelegt ist. Einstellung im Wärmepumpenmanager, "
                        + "gelesen von 0x514.")
                .row("Wärmebedarf", "kW", 1, "Einstellung_Waermebedarf", wp::getWaermebedarf, stunde)
                .info("Heizlast des Hauses bei Auslegungstemperatur. Begrenzt, wie stark die Wärmepumpe bei Kälte "
                        + "hochregelt. Einstellung im Wärmepumpenmanager.")
                .row("Soll-Spreizung", "K", 1, "Einstellung_SollSpreizung", wp::getSollSpreizung, stunde)
                .info("Gewünschter Unterschied zwischen Vor- und Rücklauf. Einstellung im Wärmepumpenmanager.")
                .row("Bivalenztemperatur", "°C", 1, "Einstellung_Bivalenztemperatur", wp::getBivalenztemperatur,
                        stunde)
                .info("Unterhalb dieser Außentemperatur darf der Heizstab zuheizen. Einstellung im Wärmepumpenmanager.")
                .row("Einsatzgrenze Heizen", "°C", 1, "Einstellung_EinsatzgrenzeHeizen", wp::getEinsatzgrenzeHeizen,
                        stunde)
                .info("Unterhalb dieser Außentemperatur heizt die Wärmepumpe nicht mehr, nur noch der Heizstab. "
                        + "Einstellung im Wärmepumpenmanager.")
                .row("Silent Leistung", "%", 0, "Einstellung_SilentLeistung", wp::getSilentLeistung, stunde)
                .info("Begrenzung der Verdichterleistung im leisen Silent-Betrieb. Einstellung im Wärmepumpenmanager.")
                .row("Silent Lüfter", "%", 0, "Einstellung_SilentLuefter", wp::getSilentLuefter, stunde)
                .info("Begrenzung der Lüfterdrehzahl im leisen Silent-Betrieb. Einstellung im Wärmepumpenmanager.")
                .note("Stündlich vom Wärmepumpenmanager gelesen.")
                .card("Vergleich Einstellung Wärmebedarf")
                .breit();
        List<WaermebedarfVergleich.Vergleich> vergleiche = vergleich.getVergleich();
        if (vergleiche.isEmpty()) {
            page.text("Tage", vergleich.getBerechnet() == null ? "wird berechnet" : "keine");
        } else {
            List<List<String>> zeilen = new ArrayList<>();
            for (WaermebedarfVergleich.Vergleich v : vergleiche) {
                WaermebedarfVergleich.Gruppe alt = v.vorher();
                WaermebedarfVergleich.Gruppe neu = v.nachher();
                zeilen.add(List.of(
                        v.band() + " (" + (alt == null ? "" : alt.tage() + " / ") + neu.tage() + ")",
                        (alt == null ? "" : zahl(alt.waermebedarf(), 1) + " → ") + zahl(neu.waermebedarf(), 1) + " kW",
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::startsProTag, 1),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::laufzeitProStart, 2),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::laufzeitProTag, 1),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::waermeProTag, 0),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::leistungKW, 2),
                        WaermebedarfVergleich.bewertung(alt, neu)));
            }
            page.table(List.of("Außen (Tage)", "Einstellung", "Starts/Tag", "h/Start", "h/Tag", "kWh/Tag",
                    "kW im Lauf", "Bewertung"), zeilen, true);
        }
        page.note("Tage seit " + vergleich.getStart().format(DATUM) + " nach Tagesmittel der Außentemperatur und "
                        + "der Einstellung Wärmebedarf im Wärmepumpenmanager (eingestellter Wert, kein Messwert). "
                        + "Gibt es in einem Bereich Tage mit zwei Einstellungen, steht der frühere Wert vor dem Pfeil. "
                        + "„kW im Lauf“ = Wärme ÷ Laufzeit. Bewertung ab " + WaermebedarfVergleich.MIN_TAGE
                        + " Tagen je Einstellung: „besser“ bei mindestens "
                        + Math.round((1 - WaermebedarfVergleich.STARTS_BESSER) * 100)
                        + " % weniger Starts, „zu knapp?“ wenn die Wärme pro Tag um mehr als "
                        + Math.round(WaermebedarfVergleich.WAERME_TOLERANZ * 100) + " % sinkt oder der Verdichter "
                        + "mehr als " + (int) WaermebedarfVergleich.MAX_LAUFZEIT_H + " h am Tag läuft. Passt die "
                        + "Einstellung, bleibt die Wärme gleich, die Starts sinken und die Läufe werden länger; dazu "
                        + "bleiben die Räume warm und der Heizstab aus. Stand " + (vergleich.getBerechnet() == null ? "–"
                        : zeitpunkt(vergleich.getBerechnet())) + ".");
    }

    private void system(StatusPage page) {
        CanStatistik statistik = can.getStatistik();
        List<Ueberwachung.Pruefung> pruefungen = ueberwachung.getPruefungen();
        // Mail that is not set up is a decision, not a fault
        List<String> fehler = pruefungen.stream()
                .filter(p -> !p.ok() && !(p.name().equals("Mail") && p.text().equals("nicht eingerichtet")))
                .map(Ueberwachung.Pruefung::name).toList();
        String zusammenfassung = pruefungen.isEmpty() ? "Prüfung läuft"
                : fehler.isEmpty() ? "alles OK" : "Fehler: " + String.join(", ", fehler);
        page.bereichKlappbar("System", zusammenfassung, !fehler.isEmpty())
                .card("Dienst");
        if (pruefungen.isEmpty()) {
            page.text("Prüfung", "läuft 30 s nach dem Start");
        }
        for (Ueberwachung.Pruefung pruefung : pruefungen) {
            page.text(pruefung.name(), pruefung.text()).info(pruefung.erklaerung());
        }
        page.note("Geprüft " + (ueberwachung.getGeprueft() == null ? "noch nicht"
                        : zeitpunkt(ueberwachung.getGeprueft())) + ", alle 15 Minuten neu.")
                .card("USB-Adapter")
                .pill("Verbindung", can::isAdapterVerbunden)
                .info("USBtin am Pi verbunden und CAN-Kanal geöffnet.")
                .text("Port", can.getPort() + " · " + can.getBitrate() / 1000 + " kbit/s")
                .info("Serielle Schnittstelle des USBtin am Pi und Bitrate des Wärmepumpen-Busses.")
                .text("Firmware / Hardware", statistik.getFirmware() == null ? null
                        : statistik.getFirmware() + " / " + statistik.getHardware())
                .info("Versionen des USBtin, beim Verbinden ausgelesen.")
                .text("Seriennummer", statistik.getSeriennummer())
                .info("Seriennummer des USBtin.")
                .text("Verbunden seit", zeitpunkt(statistik.getVerbundenSeit()))
                .info("Zeitpunkt der letzten erfolgreichen Verbindung, also Start des Dienstes oder letzter "
                        + "USB-Neustart.")
                .row("USB-Neustarts", "", 0, "CAN_UsbNeustarts", can::getUsbNeustarts)
                .info("Wie oft die Überwachung die USB-Verbindung seit dem Start des Dienstes neu gestartet hat, "
                        + "weil 2 min keine Antwort kam.")
                .text("Letzter Neustart", zeitpunkt(statistik.getLetzterNeustart()))
                .info("Zeitpunkt des letzten USB-Neustarts durch die Überwachung.")
                .card("CAN-Bus")
                .row("Letzte Antwort vor", "s", 0, can::getSekundenSeitAntwort)
                .info("Zeit seit der letzten Antwort auf eine Anfrage des Dienstes. Nach 120 s startet die "
                        + "Überwachung die USB-Verbindung neu.")
                .row("Antwortquote (berechnet)", "%", 0, "CAN_Antwortquote",
                        () -> can.minute(CanStatistik.Minute::antwortquote))
                .info("Anteil der beantworteten Anfragen. Dauerhaft deutlich unter 100 % deutet auf Probleme am Bus "
                        + "oder auf Werte hin, die kein Gerät kennt.")
                .formula("Antworten ÷ gesendete Anfragen der letzten Minute, höchstens 100 %")
                .row("Empfangen", "/min", 0, "CAN_Empfangen", () -> can.minute(CanStatistik.Minute::empfangen))
                .info("Alle empfangenen CAN-Nachrichten pro Minute. Mit can.logall=false nur die Antworten an den "
                        + "Dienst.")
                .row("davon Antworten", "/min", 0, "CAN_Antworten", () -> can.minute(CanStatistik.Minute::antworten))
                .info("Antworten auf Anfragen des Dienstes pro Minute.")
                .row("Anfragen gesendet", "/min", 0, "CAN_Gesendet", () -> can.minute(CanStatistik.Minute::gesendet))
                .info("Vom Dienst gesendete Abfragen pro Minute.")
                .row("Antwort „nicht verfügbar“", "/min", 0, "CAN_NichtVerfuegbar",
                        () -> can.minute(CanStatistik.Minute::nichtVerfuegbar))
                .info("Antworten mit dem Wert 0x8000 pro Minute: Das Gerät kennt den abgefragten Wert nicht.")
                .row("Buslast (berechnet)", "%", 1, "CAN_Buslast", () -> can.minute(CanStatistik.Minute::buslast))
                .info("Wie stark der CAN-Bus ausgelastet ist.")
                .formula("Summe (47 + 8 × Datenbytes) Bit aller empfangenen und gesendeten Nachrichten der letzten "
                        + "Minute ÷ 60 s ÷ " + can.getBitrate() + " bit/s. Ohne Bit-Stuffing, also eher etwas zu "
                        + "niedrig");
        for (CanStatistik.KnotenStatus knoten : statistik.getKnoten()) {
            page.row(knoten.name(), "/min", 0, CanBus.knotenSerie(knoten.id()),
                    () -> new ValueContainer<>(knoten.proMinute(), knoten.zuletzt()))
                    .info(KNOTEN_ERKLAERUNG.getOrDefault(knoten.id(), "Unbekanntes Gerät am CAN-Bus.")
                            + " Nachrichten pro Minute, „seit“ zeigt, wann es zuletzt gesendet hat.");
        }
        page.note("Werte der letzten vollen Minute.");
    }

    /**
     * Value of the setting used before and the current one, e.g. "23,5 → 18,2", only the current one without days
     * of an earlier setting
     */
    private static String vorherNachher(WaermebedarfVergleich.Gruppe vorher, WaermebedarfVergleich.Gruppe nachher,
                                        ToDoubleFunction<WaermebedarfVergleich.Gruppe> wert, int stellen) {
        String neu = zahl(wert.applyAsDouble(nachher), stellen);
        return vorher == null ? neu : zahl(wert.applyAsDouble(vorher), stellen) + " → " + neu;
    }
}
