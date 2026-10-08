package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // What the CAN nodes are, as far as known from the answered values
    private static final Map<Integer, String> KNOTEN_ERKLAERUNG = Map.of(
            0x180, "Wärmepumpenmanager WPM3 im HM Trend, Heizungsregler (Betriebsstatus, Heizungsdruck).",
            0x480, "Wärmepumpenmanager WPM3, Steuerung der Wärmepumpe, fragt die Außeneinheit 0x500 ab.",
            0x500, "Außeneinheit WPL 17 ACS (Verdichter, Kältekreis, Inverter, Lüfter).",
            0x514, "Außeneinheit, Zugang für externe Abfragen mit Zählern, Laufzeiten und Einstellungen.");

    private static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm:ss");

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static String zahl(double wert, int stellen) {
        return String.format(Locale.GERMANY, "%." + stellen + "f", wert);
    }

    private static String zeitpunkt(Instant zeit) {
        return zeit == null ? null : ZEITPUNKT.format(zeit.atZone(ZoneId.systemDefault()));
    }

    /**
     * Returns actual status
     *
     * @return HTML page
     */
    public String getStatus() {
        CanStatistik statistik = can.getStatistik();
        StatusPage page = new StatusPage()
                .badge("Verdichter läuft", "Verdichter aus", wp::isVerdichterOn)
                .kpi("Außentemperatur", "°C", 1, "Aussentemp", wp::getAussentemp)
                .info("Außentemperatur am Fühler der Außeneinheit (0x500). Kann vom Außenfühler des "
                        + "Wärmepumpenmanagers etwas abweichen, beim Abtauen steigt sie kurz um einige Kelvin.")
                .kpi("Vorlauf", "°C", 1, "VorlaufIstTemp", wp::getVorlaufIstTemp)
                .info("Temperatur des Wassers, das von der Wärmepumpe zum Heizkreis fließt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .kpi("Rücklauf", "°C", 1, "RuecklaufIstTemp", wp::getRuecklaufIstTemp)
                .info("Temperatur des Wassers, das vom Heizkreis zur Wärmepumpe zurückkommt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .kpi("Verdichter", "Hz", 0, "VerdichterDrehzahlHz", wp::getVerdichterDrehzahl)
                .info("Aktuelle Drehzahl des Inverter-Verdichters in der Außeneinheit (0x500). 0 bedeutet, der "
                        + "Verdichter steht.")
                .kpi("Inverter (berechnet)", "VA", 0, "LeistungInverter", wp::getLeistungInverter)
                .info("Momentane Leistungsaufnahme des Verdichter-Inverters. Die echte Wirkleistung ist bei kleiner "
                        + "Last bis etwa 20 % niedriger.")
                .formula(Waermepumpe.FORMEL_SCHEINLEISTUNG)
                .card("Betrieb")
                .pill("Verdichter", wp::isVerdichterOn)
                .info("Verdichter läuft, aus dem Betriebsstatus des Wärmepumpenmanagers (0x180).")
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
                .row("Laufzeit DHC 1", "h", 0, "LaufzeitDHZ1", wp::getLaufzeit_DHC1)
                .info("Betriebsstunden der Heizstab-Stufe 1 seit Inbetriebnahme (0x500).")
                .row("Laufzeit DHC 2", "h", 0, "LaufzeitDHZ2", wp::getLaufzeit_DHC2)
                .info("Betriebsstunden der Heizstab-Stufe 2 seit Inbetriebnahme (0x500).")
                .row("Laufzeit DHC 1+2", "h", 0, "LaufzeitDHZ12", wp::getLaufzeit_DHC12)
                .info("Betriebsstunden mit beiden Heizstab-Stufen gleichzeitig seit Inbetriebnahme (0x500).")
                .card("Verdichter")
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
                .info("Dauer der letzten Abtauung (0x514).")
                .note("Zähler seit Inbetriebnahme.")
                .card("Heizkreis")
                .row("Vorlauf", "°C", 1, "VorlaufIstTemp", wp::getVorlaufIstTemp)
                .info("Temperatur des Wassers, das von der Wärmepumpe zum Heizkreis fließt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .row("Rücklauf", "°C", 1, "RuecklaufIstTemp", wp::getRuecklaufIstTemp)
                .info("Temperatur des Wassers, das vom Heizkreis zur Wärmepumpe zurückkommt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .row("Spreizung (berechnet)", "K", 1, "Spreizung", wp::getSpreizung)
                .info("Temperaturunterschied zwischen Vor- und Rücklauf. Der Sollwert steht unter Einstellungen "
                        + "(Soll-Spreizung).")
                .formula("Vorlauf − Rücklauf")
                .row("Volumenstrom", "l/min", 1, "WasserVolumenstrom", wp::getVolumenstrom)
                .info("Heizwasser, das durch die Wärmepumpe fließt (0x500). Wird jede Minute abgefragt.")
                .row("Wärmeleistung (berechnet)", "kW", 2, "WaermeleistungBerechnet", wp::getWaermeleistung)
                .info("Wärme, die die Wärmepumpe gerade an das Heizwasser abgibt.")
                .formula(Waermepumpe.formelWaermeleistung())
                .row("Heizungsdruck", "bar", 2, "Heizungsdruck", wp::getHeizungsdruck)
                .info("Wasserdruck im Heizkreis, gemessen im HM Trend und gemeldet vom Wärmepumpenmanager (0x180).")
                .card("Kältekreis")
                .row("Verdichter-Drehzahl", "Hz", 0, "VerdichterDrehzahlHz", wp::getVerdichterDrehzahl)
                .info("Aktuelle Drehzahl des Inverter-Verdichters in der Außeneinheit (0x500). 0 bedeutet, der "
                        + "Verdichter steht.")
                .row("Verdichter-Solldrehzahl", "Hz", 0, "VerdichterSollDrehzahlHz", wp::getVerdichterSollDrehzahl)
                .info("Drehzahl, die die Regelung vom Verdichter fordert (0x514).")
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
                .card("Inverter")
                .row("Spannung", "V", 1, "SpannungInverter", wp::getSpannungInverter)
                .info("Netzspannung am Verdichter-Inverter (0x500). Zeigt dauerhaft etwa 10 V zu wenig an, der "
                        + "Hauszähler misst richtig.")
                .row("Strom", "A", 1, "StromInverter", wp::getStromInverter)
                .info("Stromaufnahme des Verdichter-Inverters (0x500).")
                .row("Scheinleistung (berechnet)", "VA", 0, "LeistungInverter", wp::getLeistungInverter)
                .info("Momentane Leistungsaufnahme des Verdichter-Inverters. Die echte Wirkleistung ist bei kleiner "
                        + "Last bis etwa 20 % niedriger.")
                .formula(Waermepumpe.FORMEL_SCHEINLEISTUNG)
                .row("Umgebungstemperatur", "°C", 1, "UmgebungstempInverter", wp::getUmgebungstempInverter)
                .info("Temperatur in der Umgebung der Inverter-Elektronik (0x514).")
                .row("Temperatur Verdichter", "°C", 1, "TempInverterVerdichter", wp::getTempInverterVerdichter)
                .info("Vom Inverter gemessene Temperatur am Verdichter (0x514).")
                .note("Spannung × Strom ohne Leistungsfaktor. Die Wirkleistung liegt laut Smartmeter bei etwa "
                        + "80 % bei 600 VA und erreicht ab 1000 VA die Scheinleistung.")
                .card("Energie Heizen")
                .row("Stromaufnahme", "MWh", 3, "AufnahmeLeistung", wp::getAufnahmeLeistung)
                .info("Elektrische Energie fürs Heizen seit Inbetriebnahme laut Stromzähler der Wärmepumpe (0x514), "
                        + "aus Summe und Tageswert zusammengesetzt. Der Zähler zählt rund 15–20 % zu wenig.")
                .row("Wärmeerzeugung", "MWh", 3, "AbgabeWaerme", wp::getAbgabeWaerme)
                .info("Heizwärme seit Inbetriebnahme laut Wärmezähler der Wärmepumpe (0x514), aus Summe und "
                        + "Tageswert zusammengesetzt. Stimmt auf etwa 6 % mit der Rechnung aus Durchfluss und "
                        + "Spreizung überein.")
                .row("Zusatzheizung", "MWh", 3, "WaermeZusatzheizung", wp::getWaermeZusatzheizung)
                .info("Vom Heizstab erzeugte Wärme seit Inbetriebnahme (0x514).")
                .row("Effizienz gesamt (Zähler, berechnet)", "", 2, "EffizienzZaehler", wp::getEffizienz)
                .info("Arbeitszahl seit Inbetriebnahme nach den Zählern der Wärmepumpe. Zu hoch, weil der "
                        + "Stromzähler zu wenig zählt.")
                .formula("Wärmeerzeugung ÷ Stromaufnahme, beides Zähler der Wärmepumpe")
                .row("Effizienz gesamt (korrigiert, berechnet)", "", 2, "EffizienzKorrigiert", wp::getEffizienzKorrigiert)
                .info("Realistische Arbeitszahl seit Inbetriebnahme, mit korrigiertem Stromverbrauch.")
                .formula("Wärmeerzeugung ÷ (Stromaufnahme × " + Waermepumpe.zahl(Waermepumpe.STROMZAEHLER_KORREKTUR)
                        + "), der Stromzähler der Wärmepumpe zählt rund 20 % zu wenig")
                .row("Arbeitszahl aktuell (berechnet)", "", 1, "ArbeitszahlGeschaetzt", wp::getArbeitszahl)
                .info("Momentane Arbeitszahl (COP): abgegebene Wärme je eingesetzter elektrischer Leistung.")
                .formula(Waermepumpe.formelArbeitszahl())
                .note("Zähler der Wärmepumpe seit Inbetriebnahme. Der Stromzähler zählt rund 20 % zu wenig, "
                        + "die korrigierte Effizienz rechnet das heraus. Die aktuelle Arbeitszahl ist geschätzt: "
                        + "berechnete Wärmeleistung geteilt durch die aus der Scheinleistung geschätzte Wirkleistung.")
                .card("Einstellungen")
                .row("Auslegungstemperatur", "°C", 1, "Einstellung_Auslegungstemperatur", wp::getAuslegungstemperatur, Waermepumpe.MAX_AGE_3600)
                .info("Tiefste Außentemperatur, für die die Anlage ausgelegt ist. Einstellung im Wärmepumpenmanager, "
                        + "gelesen von 0x514.")
                .row("Wärmebedarf", "kW", 1, "Einstellung_Waermebedarf", wp::getWaermebedarf, Waermepumpe.MAX_AGE_3600)
                .info("Heizlast des Hauses bei Auslegungstemperatur. Begrenzt, wie stark die Wärmepumpe bei Kälte "
                        + "hochregelt. Einstellung im Wärmepumpenmanager.")
                .row("Soll-Spreizung", "K", 1, "Einstellung_SollSpreizung", wp::getSollSpreizung, Waermepumpe.MAX_AGE_3600)
                .info("Gewünschter Unterschied zwischen Vor- und Rücklauf. Einstellung im Wärmepumpenmanager.")
                .row("Bivalenztemperatur", "°C", 1, "Einstellung_Bivalenztemperatur", wp::getBivalenztemperatur, Waermepumpe.MAX_AGE_3600)
                .info("Unterhalb dieser Außentemperatur darf der Heizstab zuheizen. Einstellung im Wärmepumpenmanager.")
                .row("Einsatzgrenze Heizen", "°C", 1, "Einstellung_EinsatzgrenzeHeizen", wp::getEinsatzgrenzeHeizen, Waermepumpe.MAX_AGE_3600)
                .info("Unterhalb dieser Außentemperatur heizt die Wärmepumpe nicht mehr, nur noch der Heizstab. "
                        + "Einstellung im Wärmepumpenmanager.")
                .row("Silent Leistung", "%", 0, "Einstellung_SilentLeistung", wp::getSilentLeistung, Waermepumpe.MAX_AGE_3600)
                .info("Begrenzung der Verdichterleistung im leisen Silent-Betrieb. Einstellung im Wärmepumpenmanager.")
                .row("Silent Lüfter", "%", 0, "Einstellung_SilentLuefter", wp::getSilentLuefter, Waermepumpe.MAX_AGE_3600)
                .info("Begrenzung der Lüfterdrehzahl im leisen Silent-Betrieb. Einstellung im Wärmepumpenmanager.")
                .note("Heizlast bei Auslegungstemperatur und weitere Einstellungen, stündlich abgefragt.")
                .card("Wärmebedarf-Vergleich");
        List<WaermebedarfVergleich.Gruppe> gruppen = vergleich.getGruppen();
        if (gruppen.isEmpty()) {
            page.text("Tage", vergleich.getBerechnet() == null ? "wird berechnet" : "keine");
        } else {
            List<List<String>> zeilen = new ArrayList<>();
            for (WaermebedarfVergleich.Gruppe g : gruppen) {
                zeilen.add(List.of(g.band(), zahl(g.waermebedarf(), 1) + " kW", String.valueOf(g.tage()),
                        zahl(g.startsProTag(), 1), zahl(g.laufzeitProTag(), 1), zahl(g.laufzeitProStart(), 2),
                        zahl(g.waermeProTag(), 0), zahl(g.leistungKW(), 2)));
            }
            page.table(List.of("Außen", "Wärmebedarf", "Tage", "Starts/Tag", "h/Tag", "h/Start", "kWh/Tag",
                    "kW im Lauf"), zeilen);
        }
        page.note("Tage seit " + vergleich.getStart().format(DATUM) + " nach Tagesmittel der Außentemperatur und "
                        + "eingestelltem Wärmebedarf. Starts und Laufzeit aus der Inverterleistung, Wärme aus dem "
                        + "Wärmezähler, „kW im Lauf“ = Wärme ÷ Laufzeit. Weniger Starts bei gleicher Wärme "
                        + "bedeutet längere, effizientere Läufe. Die Einstellung wird erst seit 22.09.2026 "
                        + "gespeichert, davor gilt der erste gespeicherte Wert. Tage mit Lücken fehlen. Berechnet "
                        + (vergleich.getBerechnet() == null ? "nach dem Start" : zeitpunkt(vergleich.getBerechnet()))
                        + ", alle 6 Stunden neu.")
                .card("Fehlerliste");
        List<Fehlerliste.Eintrag> eintraege = can.getFehlerliste().eintraege();
        if (eintraege.isEmpty()) {
            page.text("Einträge", can.getFehlerlisteAngefragt() == null ? null : "keine")
                    .info("Die Fehlerliste des Wärmepumpenmanagers wird alle 10 Minuten gelesen.");
        }
        for (Fehlerliste.Eintrag eintrag : eintraege) {
            page.text(Fehlerliste.ZEITFORMAT.format(eintrag.zeit()), eintrag.text())
                    .info("Code " + eintrag.code() + ", Platz " + (eintrag.platz() + 1) + " im Ringspeicher des WPM.");
        }
        page.note("Fehlerliste des WPM (DIAGNOSE → FEHLERLISTE), 20 Einträge, der neueste zuerst. Alle 10 Minuten "
                        + "gelesen, ein neuer Eintrag wird per Mail gemeldet.")
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
                .info("Wie oft die Überwachung die USB-Verbindung neu gestartet hat, weil 2 min keine Antwort kam.")
                .text("Letzter Neustart", zeitpunkt(statistik.getLetzterNeustart()))
                .info("Zeitpunkt des letzten USB-Neustarts durch die Überwachung.")
                .note("Neustarts durch die Überwachung (keine Antwort für 2 min), gezählt seit dem Start des "
                        + "Dienstes.")
                .card("CAN-Bus")
                .row("Letzte Antwort vor", "s", 0, can::getSekundenSeitAntwort)
                .info("Zeit seit der letzten Antwort auf eine Anfrage des Dienstes. Nach 120 s startet die "
                        + "Überwachung die USB-Verbindung neu.")
                .row("Empfangen", "/min", 0, "CAN_Empfangen", () -> can.minute(CanStatistik.Minute::empfangen))
                .info("Alle empfangenen CAN-Nachrichten pro Minute. Mit can.logall=false nur die Antworten an den "
                        + "Dienst.")
                .row("davon Antworten", "/min", 0, "CAN_Antworten", () -> can.minute(CanStatistik.Minute::antworten))
                .info("Antworten auf Anfragen des Dienstes pro Minute.")
                .row("Anfragen gesendet", "/min", 0, "CAN_Gesendet", () -> can.minute(CanStatistik.Minute::gesendet))
                .info("Vom Dienst gesendete Abfragen pro Minute.")
                .row("Antwortquote (berechnet)", "%", 0, "CAN_Antwortquote",
                        () -> can.minute(CanStatistik.Minute::antwortquote))
                .info("Anteil der beantworteten Anfragen. Dauerhaft deutlich unter 100 % deutet auf Probleme am Bus "
                        + "oder auf Werte hin, die kein Gerät kennt.")
                .formula("Antworten ÷ gesendete Anfragen der letzten Minute, höchstens 100 %")
                .row("Antwort „nicht verfügbar“", "/min", 0, "CAN_NichtVerfuegbar",
                        () -> can.minute(CanStatistik.Minute::nichtVerfuegbar))
                .info("Antworten mit dem Wert 0x8000 pro Minute: Das Gerät kennt den abgefragten Wert nicht.")
                .row("Buslast (berechnet)", "%", 1, "CAN_Buslast", () -> can.minute(CanStatistik.Minute::buslast))
                .info("Wie stark der CAN-Bus ausgelastet ist.")
                .formula("Summe (47 + 8 × Datenbytes) Bit aller empfangenen und gesendeten Nachrichten der letzten "
                        + "Minute ÷ 60 s ÷ " + can.getBitrate() + " bit/s. Ohne Bit-Stuffing, also eher etwas zu niedrig");
        for (CanStatistik.KnotenStatus knoten : statistik.getKnoten()) {
            page.row(knoten.name(), "/min", 0, CanBus.knotenSerie(knoten.id()),
                    () -> new ValueContainer<>(knoten.proMinute(), knoten.zuletzt()))
                    .info(KNOTEN_ERKLAERUNG.getOrDefault(knoten.id(), "Unbekanntes Gerät am CAN-Bus.")
                            + " Nachrichten pro Minute, „seit“ zeigt, wann es zuletzt gesendet hat.");
        }
        return page
                .note("Werte der letzten vollen Minute, je Gerät die Nachrichten pro Minute. Mit can.logall=false "
                        + "kommen nur die Antworten an diesen Dienst an.")
                .render();
    }
}
