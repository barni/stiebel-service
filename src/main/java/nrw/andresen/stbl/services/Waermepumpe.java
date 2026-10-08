package nrw.andresen.stbl.services;

import de.fischl.usbtin.USBtinException;
import nrw.andresen.stbl.services.can.ElsterBetriebsstatus;
import nrw.andresen.stbl.services.can.ElsterMessage;
import nrw.andresen.stbl.services.can.ValueContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static nrw.andresen.stbl.services.can.ElsterTable.*;

/**
 * Values of the heat pump from the latest CAN answers, the values calculated from them and the requests that keep
 * them current
 */
@Component
public class Waermepumpe {

    @Autowired
    private CanBus can;
    // Specific heat capacity of water in kJ/(kg*K)
    private static final double WAERMEKAPAZITAET_WASSER = 4.19;
    // Real power = WIRKLEISTUNG_STEIGUNG * apparent power - WIRKLEISTUNG_OFFSET_W, fitted from about 2300
    // compressor starts and stops in the house meter 2024 and 2025. The share rises with the load.
    private static final double WIRKLEISTUNG_STEIGUNG = 1.24;
    private static final double WIRKLEISTUNG_OFFSET_W = 270;
    // Limits of the share, below 500 VA there is no data, above 1000 VA the real power reaches the apparent power
    private static final double WIRKLEISTUNGSANTEIL_MIN = 0.7;
    private static final double WIRKLEISTUNGSANTEIL_MAX = 1.0;
    // The electric counter of the heat pump counts 0.83 (2024) and 0.85 (2025) of the real energy
    static final double STROMZAEHLER_KORREKTUR = 1.2;
    // Values older than two request cycles are not stored
    static final Duration MAX_AGE_20 = Duration.ofSeconds(45);
    static final Duration MAX_AGE_60 = Duration.ofSeconds(130);
    static final Duration MAX_AGE_3600 = Duration.ofMinutes(130);
    // Heat output and efficiency are calculated only after the start phase, while starting the compressor runs up to
    // 40 Hz, the pump already runs and the single values do not fit together
    private static final Duration ANLAUFZEIT = Duration.ofSeconds(60);
    // Defrost by reversing the cycle (seen 2026-09-25): the EXV opens to 100 % (about 20 % while heating), then the
    // compressor runs about 40 s with 50 Hz and heats the evaporator above the flow temperature. Heat is taken from
    // the heating water and the pump runs with 30 l/min, the calculated heat output is meaningless.
    private static final double ABTAUUNG_EXV_PROZENT = 90;
    // The EXV also opens to 100 % for a few seconds at every start after a long standstill (seen 2026-09-25/26), it
    // means defrost only if the compressor stopped shortly before (1.2 and 1.3 min at the defrosts on 2026-09-25)
    private static final Duration ABTAUUNG_MAX_STILLSTAND = Duration.ofMinutes(5);
    // While defrosting the evaporator is 15 K above the flow, at a start after standstill it can be about as warm as
    // the flow (3 K below on 2026-09-26 06:00)
    private static final double ABTAUUNG_VERDAMPFER_UEBER_VORLAUF_K = 5;
    // Heat output and efficiency are not calculated during the defrost and this time after it
    private static final Duration ABTAUUNG_NACHLAUF = Duration.ofSeconds(60);
    // The four parts of an energy counter are answered within milliseconds; parts from different request cycles do not
    // fit together, e.g. the new sum and the old day value at midnight would add the day twice
    private static final Duration ZAEHLER_GLEICHZEITIG = Duration.ofSeconds(10);
    // Manager, heat pump as seen by the manager and as external access, the latter also provides counters and settings
    private static final int MANAGER = 0x180;
    private static final int WAERMEPUMPE = 0x500;
    private static final int HEIZMODUL = 0x514;
    private static final short[] BETRIEBSWERTE_HEIZMODUL = {
            LZ_VERDICHTER_HEIZEN, LZ_VERDICHTER_ABTAUEN, ZEIT_LETZTE_ABTAUUNG, VERDICHTER_STARTS_K, VERDICHTER_STARTS,
            SOLLDREHZAHL_VERDICHTER, SOLL_UEBERHITZUNG, IST_UEBERHITZUNG,
            ISTDREHZAHL_LUEFTER, SOLLDREHZAHL_LUEFTER, UMGEBUNGSTEMPERATUR_INVERTER, TEMPERATUR_INV_VERDICHTER
    };
    private static final short[] EINSTELLUNGEN_HEIZMODUL = {
            AUSLEGUNGSTEMPERATUR, WAERMEBEDARF, SOLLSPREIZUNG, BIVALENZTEMPERATUR_HZG, EINSATZGRENZE_HZG,
            SILENT_LEISTUNG, SILENT_LUEFTER, RAUMSOLLTEMP_I, RAUMSOLLTEMP_NACHT, HEIZKURVE, HZK_KURVENABSTAND,
            RAUMEINFLUSS, SPERRZEIT, MINDESTLAUFZEIT_WE, SCHALTWERKDYNAMIKZEIT
    };
    // Requested every 20 s from the manager, from the heat pump and from the external access
    private static final short[] MONITORING_MANAGER = {BETRIEBS_STATUS, ANZEIGE_HEIZUNGSDRUCK};
    private static final short[] MONITORING_WAERMEPUMPE = {
            TEST_OBJEKT_113_STROM_INVERTER, TEST_OBJEKT_112_SPANNUNG_INVERTER, ANZEIGE_HOCHDRUCK, ANZEIGE_NIEDERDRUCK,
            AUSSENTEMP, HEISSGAS_TEMP, VERDICHTER_DREHZAHL, VERDICHTER_EINTRITTSTEMP, VERDAMPFERTEMP, OELSUMPFTEMP
    };
    // The EXV every 20 s, the defrost with the fully open EXV lasts only about 1-2 minutes
    private static final short[] MONITORING_HEIZMODUL = {WPVORLAUFIST, RUECKLAUFISTTEMP, OEFFNUNGSGRAD_EXV};
    // Energy counters, each as sum and day value in MWh/kWh and kWh/Wh
    private static final short[] ZAEHLER_HEIZMODUL = {
            EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH, EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH,
            EL_AUFNAHMELEISTUNG_HEIZ_TAG_WH, EL_AUFNAHMELEISTUNG_HEIZ_TAG_KWH,
            WAERMEERTRAG_HEIZ_SUM_KWH, WAERMEERTRAG_HEIZ_SUM_MWH, WAERMEERTRAG_HEIZ_TAG_WH, WAERMEERTRAG_HEIZ_TAG_KWH,
            WAERMEERTRAG_2WE_HEIZ_TAG_WH, WAERMEERTRAG_2WE_HEIZ_TAG_KWH,
            WAERMEERTRAG_2WE_HEIZ_SUM_KWH, WAERMEERTRAG_2WE_HEIZ_SUM_MWH
    };
    // 0x500 and 0x514 answer the same run times of the heating element, 0x180 does not have them
    private static final short[] BETRIEBSWERTE_WAERMEPUMPE = {
            WP_WASSERVOLUMENSTROM, LAUFZEIT_DHC1, LAUFZEIT_DHC2, LAUFZEIT_DHC12, LUEFTERLEISTUNG_REL
    };

    // Time of the first received compressor speed above 0, null while the compressor stands still
    private volatile Instant verdichterLaeuftSeit;
    // Time of the last stop of the compressor, null if no stop was seen since the start of the service
    private volatile Instant verdichterGestopptUm;
    // Time of the last 20 s cycle with defrost signs, null if none seen since the start of the service
    private volatile Instant letzteAbtauung;
    // Highest energy per counter (key: index of the MWh counter), the reported sum may fall, see getEnergie
    private final Map<Short, Double> hoechsteEnergie = new ConcurrentHashMap<>();

    /**
     * Requests the values needed every 20 s
     */
    public void anfragen20() throws USBtinException {
        can.anfragen(MANAGER, MONITORING_MANAGER);
        can.anfragen(WAERMEPUMPE, MONITORING_WAERMEPUMPE);
        can.anfragen(HEIZMODUL, MONITORING_HEIZMODUL);
    }

    /**
     * Requests the counters and operating values, every minute
     */
    public void anfragen60() throws USBtinException {
        can.anfragen(HEIZMODUL, ZAEHLER_HEIZMODUL);
        can.anfragen(WAERMEPUMPE, BETRIEBSWERTE_WAERMEPUMPE);
        can.anfragen(HEIZMODUL, BETRIEBSWERTE_HEIZMODUL);
    }

    /**
     * Requests the settings, every hour
     */
    public void anfragen3600() throws USBtinException {
        can.anfragen(HEIZMODUL, EINSTELLUNGEN_HEIZMODUL);
    }

    /**
     * Updates the compressor start and the defrost from the latest values, every 20 s
     */
    public void aktualisieren() {
        updateVerdichterLaeuftSeit();
        updateAbtauung();
    }

    private ElsterMessage nachricht(short index) throws Exception {
        ElsterMessage msg = can.nachricht(index);
        if (msg == null) {
            throw new Exception("NO_VALUES_RECEIVED");
        }
        return msg;
    }

    public ValueContainer<Short> getBetriebstatus() {
        ElsterMessage betriebsstatus = can.nachricht(BETRIEBS_STATUS);
        if (betriebsstatus == null) {
            return new ValueContainer<>((short) 0, "NO_VALUE", Instant.MIN);
        }
        // Payload is formatted as decimal, so the bit field has to be taken from the raw value
        return new ValueContainer<>(betriebsstatus.getRawValue(),
                betriebsstatus.getValue(), betriebsstatus.getTimestamp());
    }


    private ValueContainer<Boolean> isBetriebsstatusSet(ElsterBetriebsstatus flag) throws Exception {
        ElsterMessage betriebsstatus = nachricht(BETRIEBS_STATUS);
        return new ValueContainer<>(
                (betriebsstatus.getRawValue() & flag.id) == flag.id,
                betriebsstatus.getTimestamp()
        );
    }

    public ValueContainer<Boolean> isVerdichterOn() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.verdichter_1);
    }

    public ValueContainer<Boolean> isPufferladepumpeOn() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.pufferladepumpe_1);
    }

    public ValueContainer<Boolean> isWarmwasserladepumpe() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.warmwasserladepumpe);
    }

    public ValueContainer<Boolean> isDHC_1On() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.dhc_1);
    }

    public ValueContainer<Boolean> isDHC_2On() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.dhc_2);
    }

    public ValueContainer<Boolean> isEvuSperre() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.evu_sperre);
    }


    /**
     * Energy in MWh, the sum counters do not contain the values of the current day. The value never falls: at midnight
     * the heat pump adds only the whole kWh of the day to the sum counter and drops the remaining Wh (e.g. 2026-09-23
     * 111.938458 -> 111.938 MWh), during the defrost the heat counter falls by a few Wh. The highest value is kept
     * until the counters exceed it again, it is lost on a restart. Parts from different request cycles are not used,
     * a too high value would be kept.
     */
    private ValueContainer<Double> getEnergie(short sumMWH, short sumKWH, short tagKWH, short tagWH) throws Exception {
        ElsterMessage elsterMessageMWH = nachricht(sumMWH);
        ElsterMessage elsterMessageKWH = nachricht(sumKWH);
        ElsterMessage elsterMessageTagKWH = nachricht(tagKWH);
        ElsterMessage elsterMessageTagWH = nachricht(tagWH);

        List<Instant> zeiten = Arrays.asList(elsterMessageKWH.getTimestamp(), elsterMessageMWH.getTimestamp(),
                elsterMessageTagKWH.getTimestamp(), elsterMessageTagWH.getTimestamp());
        if (!gleichzeitig(zeiten)) {
            throw new Exception("ZAEHLER_UNVOLLSTAENDIG");
        }

        Integer summeMWH =  Integer.parseInt(elsterMessageMWH.getValue());
        Integer summeKWH =  Integer.parseInt(elsterMessageKWH.getValue());
        Integer tagesKWH =  Integer.parseInt(elsterMessageTagKWH.getValue());
        Integer tagesWH =  Integer.parseInt(elsterMessageTagWH.getValue());

        double summe = hoechsteEnergie.merge(sumMWH, energie(summeMWH, summeKWH, tagesKWH, tagesWH), Math::max);

        return new ValueContainer<>(summe, Collections.min(zeiten));
    }

    static boolean gleichzeitig(List<Instant> zeiten) {
        return Duration.between(Collections.min(zeiten), Collections.max(zeiten)).compareTo(ZAEHLER_GLEICHZEITIG) <= 0;
    }

    static double energie(int summeMWH, int summeKWH, int tagesKWH, int tagesWH) {
        return summeMWH + (summeKWH/1000d) + (tagesKWH/1000d) + (tagesWH/(1000d*1000d));
    }

    public ValueContainer<Double> getAufnahmeLeistung() throws Exception {
        return getEnergie(EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH, EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH,
                EL_AUFNAHMELEISTUNG_HEIZ_TAG_KWH, EL_AUFNAHMELEISTUNG_HEIZ_TAG_WH);
    }

    public ValueContainer<Double> getAbgabeWaerme() throws Exception {
        return getEnergie(WAERMEERTRAG_HEIZ_SUM_MWH, WAERMEERTRAG_HEIZ_SUM_KWH,
                WAERMEERTRAG_HEIZ_TAG_KWH, WAERMEERTRAG_HEIZ_TAG_WH);
    }

    public ValueContainer<Double> getWaermeZusatzheizung() throws Exception {
        return getEnergie(WAERMEERTRAG_2WE_HEIZ_SUM_MWH, WAERMEERTRAG_2WE_HEIZ_SUM_KWH,
                WAERMEERTRAG_2WE_HEIZ_TAG_KWH, WAERMEERTRAG_2WE_HEIZ_TAG_WH);
    }


    /**
     * Efficiency with the electric counter corrected, it counts about 20 % too little
     */
    public ValueContainer<Double> getEffizienzKorrigiert() throws Exception {
        ValueContainer<Double> abgabeWaerme = getAbgabeWaerme();
        ValueContainer<Double> aufnahmeLeistung = getAufnahmeLeistung();
        return new ValueContainer<>(abgabeWaerme.getValue() / (aufnahmeLeistung.getValue() * STROMZAEHLER_KORREKTUR),
                Collections.min(Arrays.asList(abgabeWaerme.getTimestamp(), aufnahmeLeistung.getTimestamp())));
    }

    public ValueContainer<Double> getEffizienz() throws Exception {
        ValueContainer<Double> abgabeWaerme = getAbgabeWaerme();
        ValueContainer<Double> aufnahmeLeistung = getAufnahmeLeistung();
        return new ValueContainer<>(abgabeWaerme.getValue() / aufnahmeLeistung.getValue(),
                Collections.min(Arrays.asList(abgabeWaerme.getTimestamp(), aufnahmeLeistung.getTimestamp())));
    }

    public ValueContainer<Double> getDecimalValue(short index) throws Exception {
        ElsterMessage msg = nachricht(index);

        Double value = Double.parseDouble(msg.getValue().replace(",", "."));

        return new ValueContainer<>(value, msg.getTimestamp());
    }

    public ValueContainer<Double> getStromInverter() throws Exception {
        return getDecimalValue(TEST_OBJEKT_113_STROM_INVERTER);
    }

    public ValueContainer<Double> getVorlaufIstTemp() throws Exception {
        return getDecimalValue(WPVORLAUFIST);
    }

    public ValueContainer<Double> getRuecklaufIstTemp() throws Exception {
        return getDecimalValue(RUECKLAUFISTTEMP);
    }

    public ValueContainer<Double> getSpannungInverter() throws Exception {
        return getDecimalValue(TEST_OBJEKT_112_SPANNUNG_INVERTER);
    }

    public ValueContainer<Double> getLeistungInverter() throws Exception {
        ValueContainer<Double> spannungInverter = getSpannungInverter();
        ValueContainer<Double> stromInverter = getStromInverter();
        return new ValueContainer<>(spannungInverter.getValue() * stromInverter.getValue(),
                Collections.min(Arrays.asList(spannungInverter.getTimestamp(), stromInverter.getTimestamp())));
    }

    public ValueContainer<Double> getSpreizung() throws Exception {
        ValueContainer<Double> vorlauf = getVorlaufIstTemp();
        ValueContainer<Double> ruecklauf = getRuecklaufIstTemp();
        return new ValueContainer<>(vorlauf.getValue() - ruecklauf.getValue(),
                Collections.min(Arrays.asList(vorlauf.getTimestamp(), ruecklauf.getTimestamp())));
    }

    public ValueContainer<Double> getHeizungsdruck() throws Exception {
        return getDecimalValue(ANZEIGE_HEIZUNGSDRUCK);
    }

    public ValueContainer<Double> getLaufzeit_DHC1() throws Exception {
        return getDecimalValue(LAUFZEIT_DHC1);
    }

    public ValueContainer<Double> getLaufzeit_DHC2() throws Exception {
        return getDecimalValue(LAUFZEIT_DHC2);
    }

    public ValueContainer<Double> getLaufzeit_DHC12() throws Exception {
        return getDecimalValue(LAUFZEIT_DHC12);
    }

    public ValueContainer<Double> getHochdruck() throws Exception {
        return getDecimalValue(ANZEIGE_HOCHDRUCK);
    }

    public ValueContainer<Double> getNiederdruck() throws Exception {
        return getDecimalValue(ANZEIGE_NIEDERDRUCK);
    }

    public ValueContainer<Double> getAussentemp() throws Exception {
        return getDecimalValue(AUSSENTEMP);
    }

    public ValueContainer<Double> getHeissgasTemp() throws Exception {
        return getDecimalValue(HEISSGAS_TEMP);
    }

    /**
     * Value from the raw payload, for indices whose type in ElsterTable is unknown or wrong
     */
    private ValueContainer<Double> getScaledValue(short index, double divisor) throws Exception {
        ElsterMessage msg = nachricht(index);
        return new ValueContainer<>(msg.getRawValue() / divisor, msg.getTimestamp());
    }

    public ValueContainer<Double> getVerdichterDrehzahl() throws Exception {
        return getScaledValue(VERDICHTER_DREHZAHL, 1);
    }

    public ValueContainer<Double> getVerdichterEintrittstemp() throws Exception {
        return getScaledValue(VERDICHTER_EINTRITTSTEMP, 10);
    }

    public ValueContainer<Double> getVerdampferTemp() throws Exception {
        return getScaledValue(VERDAMPFERTEMP, 10);
    }

    public ValueContainer<Double> getOelsumpfTemp() throws Exception {
        return getScaledValue(OELSUMPFTEMP, 10);
    }

    public ValueContainer<Double> getVolumenstrom() throws Exception {
        return getScaledValue(WP_WASSERVOLUMENSTROM, 10);
    }

    public ValueContainer<Double> getLaufzeitVerdichterHeizen() throws Exception {
        return getScaledValue(LZ_VERDICHTER_HEIZEN, 1);
    }

    public ValueContainer<Double> getLaufzeitVerdichterAbtauen() throws Exception {
        return getScaledValue(LZ_VERDICHTER_ABTAUEN, 1);
    }

    public ValueContainer<Double> getDauerLetzteAbtauung() throws Exception {
        return getScaledValue(ZEIT_LETZTE_ABTAUUNG, 1);
    }

    public ValueContainer<Double> getVerdichterStarts() throws Exception {
        ValueContainer<Double> tausender = getScaledValue(VERDICHTER_STARTS_K, 1);
        ValueContainer<Double> rest = getScaledValue(VERDICHTER_STARTS, 1);
        return new ValueContainer<>(tausender.getValue() * 1000 + rest.getValue(),
                Collections.min(Arrays.asList(tausender.getTimestamp(), rest.getTimestamp())));
    }

    /**
     * Average compressor runtime per start in h since commissioning
     */
    public ValueContainer<Double> getLaufzeitProStart() throws Exception {
        ValueContainer<Double> laufzeit = getLaufzeitVerdichterHeizen();
        ValueContainer<Double> starts = getVerdichterStarts();
        if (starts.getValue() <= 0) {
            throw new Exception("KEINE_STARTS");
        }
        return new ValueContainer<>(laufzeit.getValue() / starts.getValue(),
                Collections.min(Arrays.asList(laufzeit.getTimestamp(), starts.getTimestamp())));
    }

    public ValueContainer<Double> getVerdichterSollDrehzahl() throws Exception {
        return getScaledValue(SOLLDREHZAHL_VERDICHTER, 1);
    }

    public ValueContainer<Double> getSollUeberhitzung() throws Exception {
        return getScaledValue(SOLL_UEBERHITZUNG, 10);
    }

    public ValueContainer<Double> getIstUeberhitzung() throws Exception {
        return getScaledValue(IST_UEBERHITZUNG, 10);
    }

    public ValueContainer<Double> getOeffnungsgradExv() throws Exception {
        return getScaledValue(OEFFNUNGSGRAD_EXV, 10);
    }

    public ValueContainer<Double> getLuefterIstDrehzahl() throws Exception {
        return getScaledValue(ISTDREHZAHL_LUEFTER, 1);
    }

    public ValueContainer<Double> getLuefterSollDrehzahl() throws Exception {
        return getScaledValue(SOLLDREHZAHL_LUEFTER, 1);
    }

    public ValueContainer<Double> getLuefterLeistung() throws Exception {
        return getScaledValue(LUEFTERLEISTUNG_REL, 1);
    }

    public ValueContainer<Double> getUmgebungstempInverter() throws Exception {
        return getScaledValue(UMGEBUNGSTEMPERATUR_INVERTER, 10);
    }

    public ValueContainer<Double> getTempInverterVerdichter() throws Exception {
        return getScaledValue(TEMPERATUR_INV_VERDICHTER, 10);
    }

    public ValueContainer<Double> getAuslegungstemperatur() throws Exception {
        return getScaledValue(AUSLEGUNGSTEMPERATUR, 10);
    }

    public ValueContainer<Double> getWaermebedarf() throws Exception {
        return getScaledValue(WAERMEBEDARF, 10);
    }

    public ValueContainer<Double> getSollSpreizung() throws Exception {
        return getScaledValue(SOLLSPREIZUNG, 10);
    }

    public ValueContainer<Double> getBivalenztemperatur() throws Exception {
        return getScaledValue(BIVALENZTEMPERATUR_HZG, 10);
    }

    public ValueContainer<Double> getEinsatzgrenzeHeizen() throws Exception {
        return getScaledValue(EINSATZGRENZE_HZG, 10);
    }

    public ValueContainer<Double> getSilentLeistung() throws Exception {
        return getScaledValue(SILENT_LEISTUNG, 1);
    }

    public ValueContainer<Double> getSilentLuefter() throws Exception {
        return getScaledValue(SILENT_LUEFTER, 1);
    }

    /**
     * Setting of the heat pump manager, stored hourly as WP_Einstellung_name; a change is reported by mail
     */
    public record Einstellung(String name, String label, String unit, int decimals,
                              Callable<ValueContainer<Double>> wert, String info) {
    }

    private static final String WPM = " Einstellung im Wärmepumpenmanager.";

    /**
     * All settings in the order of the status page; the first ones limit the power, the others decide how often the
     * compressor starts
     */
    public List<Einstellung> getEinstellungen() {
        return List.of(
                new Einstellung("Auslegungstemperatur", "Auslegungstemperatur", "°C", 1, this::getAuslegungstemperatur,
                        "Tiefste Außentemperatur, für die die Anlage ausgelegt ist. Standard −15 °C." + WPM),
                new Einstellung("Waermebedarf", "Wärmebedarf", "kW", 1, this::getWaermebedarf,
                        "Heizlast des Hauses bei Auslegungstemperatur. Begrenzt, wie stark die Wärmepumpe bei Kälte "
                                + "hochregelt. Standard 15 kW, gemessen ~6,4 kW bei −7 °C." + WPM),
                new Einstellung("SollSpreizung", "Soll-Spreizung", "K", 1, this::getSollSpreizung,
                        "Gewünschter Unterschied zwischen Vor- und Rücklauf." + WPM),
                new Einstellung("Bivalenztemperatur", "Bivalenztemperatur", "°C", 1, this::getBivalenztemperatur,
                        "Unterhalb dieser Außentemperatur darf der Heizstab zuheizen. Standard −20 °C." + WPM),
                new Einstellung("EinsatzgrenzeHeizen", "Einsatzgrenze Heizen", "°C", 1, this::getEinsatzgrenzeHeizen,
                        "Unterhalb dieser Außentemperatur heizt nur noch der Heizstab. Standard −20 °C." + WPM),
                new Einstellung("SilentLeistung", "Silent Leistung", "%", 0, this::getSilentLeistung,
                        "Begrenzung der Verdichterleistung im leisen Silent-Betrieb (nur wirksam, wenn SILENT MODE "
                                + "an ist). Standard 100 %." + WPM),
                new Einstellung("SilentLuefter", "Silent Lüfter", "%", 0, this::getSilentLuefter,
                        "Begrenzung der Lüfterdrehzahl im leisen Silent-Betrieb (nur wirksam, wenn SILENT MODE an "
                                + "ist). Standard 100 %." + WPM),
                new Einstellung("Komforttemperatur", "Komfort-Temperatur", "°C", 1,
                        () -> getScaledValue(RAUMSOLLTEMP_I, 10), "Raum-Solltemperatur im Komfortbetrieb. Standard 20 °C." + WPM),
                new Einstellung("Ecotemperatur", "Eco-Temperatur", "°C", 1,
                        () -> getScaledValue(RAUMSOLLTEMP_NACHT, 10), "Raum-Solltemperatur im Eco-Betrieb. Standard 20 °C." + WPM),
                new Einstellung("Heizkurve", "Steigung Heizkurve", "", 2, () -> getScaledValue(HEIZKURVE, 100),
                        "Wie stark die Vorlauftemperatur mit sinkender Außentemperatur steigt. Eine steilere Kurve "
                                + "heizt mehr, der Verdichter läuft öfter. Standard 0,6." + WPM),
                new Einstellung("Kurvenabstand", "Abstand Heizkurve", "", 0,
                        () -> getScaledValue(HZK_KURVENABSTAND, 10),
                        "Abstand der Heizkurve, 1 bis 10, Standard 3 (Anleitung WPM 3)." + WPM),
                new Einstellung("Raumeinfluss", "Raumeinfluss", "", 0, this::getRaumeinfluss,
                        "Wie stark die Raumtemperatur an der Fernbedienung die Vorlauftemperatur verändert, aus bis "
                                + "20, Standard 5 (Anleitung WPM 3)." + WPM),
                new Einstellung("Stillstandzeit", "Stillstandzeit", "min", 0, () -> getScaledValue(SPERRZEIT, 1),
                        "Mindestpause zwischen zwei Verdichterstarts. Standard 20 min." + WPM),
                new Einstellung("Mindestlaufzeit", "Mindestlaufzeit", "min", 0,
                        () -> getScaledValue(MINDESTLAUFZEIT_WE, 1), "Mindestlaufzeit des Verdichters. Standard 10 min." + WPM),
                new Einstellung("Reglerdynamik", "Reglerdynamik", "", 0, this::getReglerdynamik,
                        "Schaltabstand zwischen Verdichter und den Stufen des Heizstabs: klein für schnell "
                                + "reagierende, groß für träge Heizsysteme. 1 bis 500, Standard 100." + WPM));
    }

    private ValueContainer<Double> getRaumeinfluss() throws Exception {
        ElsterMessage msg = nachricht(RAUMEINFLUSS);
        return new ValueContainer<>((double) littleEndian(msg.getRawValue()), msg.getTimestamp());
    }

    private ValueContainer<Double> getReglerdynamik() throws Exception {
        ElsterMessage msg = nachricht(SCHALTWERKDYNAMIKZEIT);
        return new ValueContainer<>((double) littleEndian(msg.getRawValue()), msg.getTimestamp());
    }

    /**
     * Value of an index with swapped bytes, e.g. 0x1900 = 25
     */
    static int littleEndian(short roh) {
        int wert = roh & 0xffff;
        return ((wert & 0xff) << 8) | (wert >> 8);
    }

    /**
     * Heat output calculated from flow rate and temperature difference
     */
    public ValueContainer<Double> getWaermeleistung() throws Exception {
        ValueContainer<Double> drehzahl = getVerdichterDrehzahl();
        if (drehzahl.getValue() <= 0) {
            return new ValueContainer<>(0d, drehzahl.getTimestamp());
        }
        if (!nachAnlauf(verdichterLaeuftSeit, Instant.now())) {
            throw new Exception("VERDICHTER_STARTET");
        }
        if (inAbtauung(letzteAbtauung, Instant.now())) {
            throw new Exception("ABTAUUNG");
        }
        ValueContainer<Double> volumenstrom = getVolumenstrom();
        // The flow rate is requested only every 60 s and can still be 0 from before the start
        if (volumenstrom.getValue() <= 0) {
            throw new Exception("KEIN_VOLUMENSTROM");
        }
        ValueContainer<Double> spreizung = getSpreizung();
        return new ValueContainer<>(waermeleistung(volumenstrom.getValue(), spreizung.getValue()),
                Collections.min(Arrays.asList(volumenstrom.getTimestamp(), spreizung.getTimestamp())));
    }

    /**
     * True if the compressor runs longer than the start phase
     */
    static boolean nachAnlauf(Instant laeuftSeit, Instant jetzt) {
        return laeuftSeit != null && !Duration.between(laeuftSeit, jetzt).minus(ANLAUFZEIT).isNegative();
    }

    /**
     * True if the values show a defrost: EXV (almost) fully open shortly after a stop, or the evaporator warmer than
     * the flow while the compressor runs
     *
     * @param stillstand standstill before the current run or up to now, null if unknown
     */
    static boolean abtauung(double exvProzent, double verdampferTemp, double vorlaufTemp, double drehzahlHz,
                            Duration stillstand) {
        boolean kurzGestoppt = stillstand != null && stillstand.compareTo(ABTAUUNG_MAX_STILLSTAND) <= 0;
        return (exvProzent >= ABTAUUNG_EXV_PROZENT && kurzGestoppt)
                || (drehzahlHz > 0 && verdampferTemp > vorlaufTemp + ABTAUUNG_VERDAMPFER_UEBER_VORLAUF_K);
    }

    static Duration stillstand(Instant laeuftSeit, Instant gestopptUm, Instant jetzt) {
        if (gestopptUm == null) {
            return null;
        }
        return Duration.between(gestopptUm, laeuftSeit != null ? laeuftSeit : jetzt);
    }

    /**
     * True during the defrost and shortly after it
     */
    static boolean inAbtauung(Instant letzteAbtauung, Instant jetzt) {
        return letzteAbtauung != null && Duration.between(letzteAbtauung, jetzt).compareTo(ABTAUUNG_NACHLAUF) < 0;
    }

    /**
     * Remembers the last defrost, from the latest received values
     */
    private void updateAbtauung() {
        try {
            if (abtauung(getOeffnungsgradExv().getValue(), getVerdampferTemp().getValue(),
                    getVorlaufIstTemp().getValue(), getVerdichterDrehzahl().getValue(),
                    stillstand(verdichterLaeuftSeit, verdichterGestopptUm, Instant.now()))) {
                letzteAbtauung = Instant.now();
            }
        } catch (Exception e) {
            // values missing, no decision possible
        }
    }

    public ValueContainer<Boolean> isAbtauung() throws Exception {
        ValueContainer<Double> exv = getOeffnungsgradExv();
        return new ValueContainer<>(inAbtauung(letzteAbtauung, Instant.now()), exv.getTimestamp());
    }

    public ValueContainer<Double> getAbtauung() throws Exception {
        ValueContainer<Boolean> abtauung = isAbtauung();
        return new ValueContainer<>(abtauung.getValue() ? 1d : 0d, abtauung.getTimestamp());
    }

    /**
     * Remembers when the compressor started, from the latest received speed
     */
    private void updateVerdichterLaeuftSeit() {
        try {
            ValueContainer<Double> drehzahl = getVerdichterDrehzahl();
            if (drehzahl.getValue() <= 0) {
                if (verdichterLaeuftSeit != null) {
                    verdichterGestopptUm = drehzahl.getTimestamp();
                }
                verdichterLaeuftSeit = null;
            } else if (verdichterLaeuftSeit == null) {
                verdichterLaeuftSeit = drehzahl.getTimestamp();
            }
        } catch (Exception e) {
            verdichterLaeuftSeit = null;
        }
    }

    /**
     * Heat output in kW from flow rate in l/min and temperature difference in K
     */
    static double waermeleistung(double volumenstromLproMin, double spreizungK) {
        return volumenstromLproMin / 60d * WAERMEKAPAZITAET_WASSER * spreizungK;
    }

    /**
     * Estimated real power in W from the apparent power of the inverter in VA
     */
    static double wirkleistung(double scheinleistungVA) {
        if (scheinleistungVA <= 0) {
            return 0;
        }
        double anteil = WIRKLEISTUNG_STEIGUNG - WIRKLEISTUNG_OFFSET_W / scheinleistungVA;
        anteil = Math.max(WIRKLEISTUNGSANTEIL_MIN, Math.min(WIRKLEISTUNGSANTEIL_MAX, anteil));
        return scheinleistungVA * anteil;
    }

    /**
     * Estimated efficiency, calculated heat output divided by the estimated real power of the inverter
     */
    public ValueContainer<Double> getArbeitszahl() throws Exception {
        if (!nachAnlauf(verdichterLaeuftSeit, Instant.now())) {
            throw new Exception("VERDICHTER_AUS");
        }
        ValueContainer<Double> waermeleistung = getWaermeleistung();
        ValueContainer<Double> scheinleistung = getLeistungInverter();
        double wirkleistung = wirkleistung(scheinleistung.getValue());
        if (wirkleistung <= 0) {
            throw new Exception("VERDICHTER_AUS");
        }
        return new ValueContainer<>(waermeleistung.getValue() * 1000d / wirkleistung,
                Collections.min(Arrays.asList(waermeleistung.getTimestamp(), scheinleistung.getTimestamp())));
    }

    static final String FORMEL_SCHEINLEISTUNG = "Spannung × Strom des Inverters, ohne Leistungsfaktor";

    /**
     * Number for the formulas on the status page, e.g. 4,19 or 0,7
     */
    static String zahl(double value) {
        return new DecimalFormat("0.0#", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(value);
    }

    static String formelWaermeleistung() {
        return "Volumenstrom [l/min] ÷ 60 × " + zahl(WAERMEKAPAZITAET_WASSER) + " kJ/(kg·K) × Spreizung [K]. "
                + "0 bei stehendem Verdichter, kein Wert in den ersten " + ANLAUFZEIT.toSeconds()
                + " s nach dem Start, während der Abtauung und ohne Volumenstrom";
    }

    static String formelArbeitszahl() {
        return "Wärmeleistung ÷ Wirkleistung, Wirkleistung = Scheinleistung × (" + zahl(WIRKLEISTUNG_STEIGUNG)
                + " − " + (int) WIRKLEISTUNG_OFFSET_W + " W ÷ Scheinleistung), Anteil begrenzt auf "
                + zahl(WIRKLEISTUNGSANTEIL_MIN) + " bis " + zahl(WIRKLEISTUNGSANTEIL_MAX)
                + " (Abgleich mit dem Hauszähler 2024/25)";
    }

    static String formelAbtauung() {
        return "An, wenn das Expansionsventil innerhalb von " + ABTAUUNG_MAX_STILLSTAND.toMinutes()
                + " min nach einem Verdichterstopp auf mindestens " + (int) ABTAUUNG_EXV_PROZENT
                + " % öffnet oder der Verdampfer bei laufendem Verdichter mehr als "
                + (int) ABTAUUNG_VERDAMPFER_UEBER_VORLAUF_K + " K wärmer als der Vorlauf ist. Bleibt "
                + ABTAUUNG_NACHLAUF.toSeconds() + " s nach dem Ende an";
    }
}
