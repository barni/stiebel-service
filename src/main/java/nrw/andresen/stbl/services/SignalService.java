package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The four signals shown on every page: operation of the heat pump, active warnings, condition and the service
 */
@Component
public class SignalService {

    static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @Autowired
    private Waermepumpe wp;
    @Autowired
    private Ueberwachung ueberwachung;
    @Autowired
    private Zustand zustand;
    @Autowired
    private CanBus can;
    @Autowired
    private InfluxController influx;

    /**
     * Frame of a page with tabs and signals
     *
     * @param aktiv target of the current page, e.g. "status"
     * @param basis prefix of the links, "" or "../"
     */
    public String kopf(String aktiv, String basis) {
        return Rahmen.kopf(aktiv, basis, influx.isAktiv(), signale(), UHRZEIT.format(Instant.now()));
    }

    public List<Rahmen.Signal> signale() {
        List<Rahmen.Signal> signale = new ArrayList<>();
        signale.add(betrieb());
        signale.add(warnungen(ueberwachung.getWarnungen().stream().filter(Warnung::isAktiv)
                .map(Warnung::getName).toList()));
        if (influx.isAktiv()) {
            Zustand.Ergebnis e = zustand.getErgebnis();
            signale.add(zustand(e == null ? null : e.pruefungen()));
        }
        Double quote = null;
        try {
            quote = can.minute(CanStatistik.Minute::antwortquote).getValue();
        } catch (Exception e) {
            // no minute finished yet
        }
        signale.add(dienst(ueberwachung.getPruefungen(), quote));
        return signale;
    }

    private Rahmen.Signal betrieb() {
        Boolean laeuft = wert(wp::isVerdichterOn);
        Boolean abtauung = wert(wp::isAbtauung);
        Double schein = wert(wp::getLeistungInverter);
        return betrieb(laeuft, abtauung, wp.getVerdichterLaeuftSeit(), wp.getVerdichterGestopptUm(),
                schein == null ? null : Waermepumpe.wirkleistung(schein), Instant.now());
    }

    static Rahmen.Signal betrieb(Boolean laeuft, Boolean abtauung, Instant laeuftSeit, Instant gestopptUm,
                                 Double stromW, Instant jetzt) {
        if (Boolean.TRUE.equals(abtauung)) {
            return new Rahmen.Signal("Betrieb", "Taut ab", "aktiv", "◆", "status");
        }
        if (Boolean.TRUE.equals(laeuft)) {
            String text = "Heizt" + (laeuftSeit == null ? "" : " seit " + dauer(laeuftSeit, jetzt))
                    + (stromW == null || stromW <= 0 ? "" : " · " + kw(stromW) + " kW");
            return new Rahmen.Signal("Betrieb", text, "aktiv", "▲", "status");
        }
        if (laeuft == null) {
            return new Rahmen.Signal("Betrieb", "keine Daten", "neutral", "?", "status");
        }
        return new Rahmen.Signal("Betrieb", "Stillstand" + (gestopptUm == null ? ""
                : " seit " + UHRZEIT.format(gestopptUm)), "neutral", "■", "status");
    }

    static Rahmen.Signal warnungen(List<String> aktiv) {
        if (aktiv.isEmpty()) {
            return new Rahmen.Signal("Warnungen", "keine", "ok", "✓", "anlage#meldungen");
        }
        return new Rahmen.Signal("Warnungen", aktiv.size() == 1 ? aktiv.get(0) : aktiv.size() + " aktiv: "
                + String.join(", ", aktiv), "warn", "!", "anlage#meldungen");
    }

    static Rahmen.Signal zustand(List<Zustand.Pruefung> pruefungen) {
        if (pruefungen == null) {
            return new Rahmen.Signal("Zustand", "wird berechnet", "neutral", "…", "zustand");
        }
        String gesamt = ZustandSeite.gesamt(pruefungen);
        List<String> namen = pruefungen.stream().filter(p -> p.status().equals(gesamt)).map(Zustand.Pruefung::name)
                .toList();
        return switch (gesamt) {
            case "auffällig" -> new Rahmen.Signal("Zustand", "auffällig: " + String.join(", ", namen), "warn", "!",
                    "zustand");
            case "beobachten" -> new Rahmen.Signal("Zustand", "beobachten: " + String.join(", ", namen), "achtung",
                    "!", "zustand");
            case "unauffällig" -> new Rahmen.Signal("Zustand", "unauffällig", "ok", "✓", "zustand");
            default -> new Rahmen.Signal("Zustand", "zu wenige Daten", "neutral", "…", "zustand");
        };
    }

    static Rahmen.Signal dienst(List<Ueberwachung.Pruefung> pruefungen, Double antwortquote) {
        if (pruefungen.isEmpty()) {
            return new Rahmen.Signal("Dienst und CAN-Bus", "Prüfung läuft", "neutral", "…", "anlage#system");
        }
        // Mail that is not set up is a decision, not a fault
        List<String> fehler = pruefungen.stream()
                .filter(p -> !p.ok() && !(p.name().equals("Mail") && p.text().equals("nicht eingerichtet")))
                .map(Ueberwachung.Pruefung::name).toList();
        if (!fehler.isEmpty()) {
            return new Rahmen.Signal("Dienst und CAN-Bus", "Fehler: " + String.join(", ", fehler), "warn", "!",
                    "anlage#system");
        }
        return new Rahmen.Signal("Dienst und CAN-Bus", "OK" + (antwortquote == null ? ""
                : " · Antworten " + Math.round(antwortquote) + " %"), "ok", "✓", "anlage#system");
    }

    static String dauer(Instant seit, Instant jetzt) {
        long minuten = Math.max(0, Duration.between(seit, jetzt).toMinutes());
        return minuten < 60 ? minuten + " min" : minuten / 60 + ":" + String.format("%02d", minuten % 60) + " h";
    }

    static String kw(double watt) {
        return String.format(Locale.GERMANY, "%.2f", watt / 1000);
    }

    private static <T extends java.io.Serializable> T wert(java.util.concurrent.Callable<ValueContainer<T>> quelle) {
        try {
            return quelle.call().getValue();
        } catch (Exception e) {
            return null;
        }
    }
}
