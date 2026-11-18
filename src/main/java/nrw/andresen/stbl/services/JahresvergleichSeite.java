package nrw.andresen.stbl.services;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTML of the page with the electricity of the heating per month in the last three years, optionally with the
 * household electricity and the wallbox stacked on top, linked with the other pages
 */
public final class JahresvergleichSeite {

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Berlin"));
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMANY);
    private static final int J = Jahresvergleich.JAHRE;
    // Size of the drawing in the units of the viewBox, the browser scales it to the width of the card
    private static final int B = 720;
    private static final int H = 300;
    private static final int LINKS = 52;
    private static final int RECHTS = 8;
    private static final int OBEN = 16;
    private static final int UNTEN = 28;
    // Below this change of the grid import the shares of the change say nothing
    private static final double MIN_AENDERUNG_KWH = 50;
    // A rise of the grid import above this is marked
    private static final double STEIGT_AB_PROZENT = 5;

    private JahresvergleichSeite() {
    }

    /**
     * @param vergleich null while it is not calculated yet
     * @param hinweis   shown instead of the comparison, e.g. that InfluxDB is switched off; null for the comparison
     * @param haushalt  show the household electricity too, only if the house meter is configured
     * @param wallbox   show the wallbox too, only if its counter is configured
     */
    public static String render(Jahresvergleich.Vergleich vergleich, String hinweis, boolean haushalt,
                                boolean wallbox) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Wärmepumpe · Jahresvergleich</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>Jahresvergleich</h1><p class=\"sub\">")
                .append(vergleich == null ? "Stromverbrauch je Jahr und Monat"
                        : (vergleich.gesamt() != null ? "Netzbezug des Hauses und Strom der Heizung, "
                        : "Strom der Heizung je Monat, ") + vergleich.jahre().get(0) + " bis " + vergleich.jahr())
                .append("</p></div><nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"zustand\">Zustand</a> · ")
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a></nav></header>");
        if (hinweis != null || vergleich == null) {
            html.append("<p class=\"meldung\">").append(text(hinweis != null ? hinweis
                    : "Der Vergleich wird berechnet, das dauert nach dem Start des Dienstes einige Minuten."))
                    .append("</p></main></body></html>");
            return html.toString();
        }
        boolean mitHaushalt = haushalt && vergleich.mitHaushalt();
        boolean mitWallbox = wallbox && vergleich.mitWallbox();
        List<Integer> jahre = vergleich.jahre();
        int jahr = vergleich.jahr();
        // With the house meter the tiles below say the same as the sentence
        if (vergleich.gesamt() == null) {
            html.append("<p class=\"meldung\">").append(text(satz(vergleich))).append("</p>");
        }
        html.append("<section class=\"kpis\">");
        if (vergleich.gesamt() != null) {
            html.append(kpi("Gesamt " + jahr + " bis " + DATUM.format(vergleich.bis()), zahl(vergleich.gesamt(), 0),
                            "kWh"))
                    .append(kpi("Gesamt " + (jahr - 1) + " gleiche Tage", zahl(vergleich.gesamtVorjahrGleich(), 0),
                            "kWh"))
                    .append(kpi("Gesamt Veränderung", prozent(vergleich.gesamtAenderung()), ""))
                    .append("</section>").append(jahresbalken(vergleich)).append(ursachen(vergleich))
                    .append(prognose(vergleich))
                    .append("<section class=\"kpis\">");
        }
        // With the house meter the heating of the same days is in "Woran liegt es?" and the whole years in the
        // table per month; without it these tiles are the only summary of the heating
        if (vergleich.gesamt() == null) {
            html.append(kpi("Heizung ≈ " + jahr + " bis " + DATUM.format(vergleich.bis()),
                            zahl(vergleich.heizung(J - 1), 0), "kWh"))
                    .append(kpi("Heizung ≈ " + (jahr - 1) + " gleiche Tage", zahl(vergleich.heizungGleich(J - 2), 0),
                            "kWh"))
                    .append(kpi("Heizung ≈ Veränderung", prozent(vergleich.aenderung()), ""));
            for (int i = J - 2; i >= 0; i--) {
                html.append(kpi("Heizung ≈ " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.heizung(i), 0), "kWh"));
            }
        }
        // With the house meter the years are in the bars above
        if (mitHaushalt && vergleich.gesamt() == null) {
            html.append(kpi("Haushalt " + jahr + " bis " + DATUM.format(vergleich.bis()),
                    zahl(vergleich.haushalt(J - 1), 0), "kWh"));
            for (int i = J - 2; i >= 0; i--) {
                html.append(kpi("Haushalt " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.haushalt(i), 0), "kWh"));
            }
        }
        if (mitWallbox && vergleich.gesamt() == null) {
            html.append(kpi("Wallbox " + jahr + " bis " + DATUM.format(vergleich.bis()),
                    zahl(vergleich.wallbox(J - 1), 0), "kWh"));
            for (int i = J - 2; i >= 0; i--) {
                html.append(kpi("Wallbox " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.wallbox(i), 0), "kWh"));
            }
        }
        // Without the house meter the forecast of the heating follows its tiles
        html.append("</section>").append(vergleich.gesamt() == null ? prognose(vergleich) : "")
                .append("<section class=\"cards\"><article class=\"card breit\"><h2>Strom je Monat</h2>")
                .append("<p class=\"legende\">");
        for (int i = 0; i < J; i++) {
            html.append("<span><i class=\"sw j").append(i).append("\"></i>").append(jahre.get(i)).append("</span>");
        }
        if (mitHaushalt) {
            html.append("<span><i class=\"sw haushalt\"></i>Haushalt</span>");
        }
        if (mitWallbox) {
            html.append("<span><i class=\"sw wallbox\"></i>Wallbox</span>");
        }
        html.append("<span>").append(mitHaushalt || mitWallbox ? "übereinander auf der Heizung, " : "")
                .append("der laufende Monat ist heller, er ist noch nicht vorbei</span></p>");
        List<String> schalter = new ArrayList<>();
        if (vergleich.mitHaushalt()) {
            schalter.add("<a class=\"nav\" href=\"" + adresse(!mitHaushalt, mitWallbox) + "\">Haushaltsstrom "
                    + (mitHaushalt ? "ausblenden" : "einblenden") + "</a>");
        }
        if (vergleich.mitWallbox()) {
            schalter.add("<a class=\"nav\" href=\"" + adresse(mitHaushalt, !mitWallbox) + "\">Wallbox "
                    + (mitWallbox ? "ausblenden" : "einblenden") + "</a>");
        }
        if (!schalter.isEmpty()) {
            html.append("<p class=\"umschalten\">").append(String.join(" · ", schalter)).append("</p>");
        }
        html.append(diagramm(vergleich, mitHaushalt, mitWallbox))
                .append("</article><article class=\"card breit\"><h2>Die Monate</h2>")
                .append(tabelle(kopf(vergleich, mitHaushalt, mitWallbox), zeilen(vergleich, mitHaushalt, mitWallbox)))
                .append("<p class=\"note\">Heizung ist die geschätzte Wirkleistung des Verdichter-Inverters aus der ")
                .append("gespeicherten Scheinleistung, wie in der Tagesübersicht, ohne Pumpe und Regelung. Der Zähler ")
                .append("der Wärmepumpe zeigt etwa 15 % weniger. Die Änderung vergleicht ").append(jahr)
                .append(" mit ").append(jahr - 1).append(", im laufenden Monat dieselben Tage bis zur gleichen Stunde.")
                .append(vergleich.mitHaushalt() ? " Haushalt ist der Netzbezug laut Hauszähler minus Heizung und "
                        + "Wallbox; Strom aus der eigenen PV-Anlage ist nicht enthalten." : " Den Haushaltsstrom "
                        + "zeigt die Seite, wenn in der Konfiguration der Hauszähler eingetragen ist.")
                .append(vergleich.mitWallbox() ? " Wallbox ist der Zählerstand der Wallbox, nur Ladungen zu Hause."
                        : "")
                .append(vergleich.gesamt() != null ? " Gesamt ist der Netzbezug laut Hauszähler vom 1. Januar bis "
                        + "jetzt, verglichen mit dem Vorjahr bis zur gleichen Stunde." : "")
                .append(" Außentemperatur: Mittel der Tagesmittel.</p>")
                .append("</article></section><p class=\"note\">Stand ").append(ZEIT.format(vergleich.berechnet()))
                .append(", stündlich neu berechnet.</p></main></body></html>");
        return html.toString();
    }

    /**
     * Card "Woran liegt es?": the parts of the grid import from the 1st of January against the same days of the year
     * before, with a sentence that names the biggest change
     */
    static String ursachen(Jahresvergleich.Vergleich v) {
        List<Jahresvergleich.Anteil> anteile = v.aufteilung();
        if (anteile.isEmpty()) {
            return "";
        }
        Jahresvergleich.Anteil gesamt = anteile.get(0);
        String zeitraum = "1. Januar bis " + DATUM.format(v.bis());
        // Three years when the grid import of two years before is known
        boolean dreiJahre = gesamt.vorvorher() != null;
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Anteil a : anteile) {
            boolean mitAnteil = a != gesamt && Math.abs(gesamt.differenz()) >= MIN_AENDERUNG_KWH;
            List<String> zeile = new ArrayList<>();
            zeile.add(geschaetzt(a.name()));
            if (dreiJahre) {
                zeile.add(zahl(a.vorvorher(), 0));
            }
            zeile.add(zahl(a.vorher(), 0));
            zeile.add(zahl(a.jetzt(), 0));
            zeile.add(differenz(a));
            if (dreiJahre) {
                zeile.add(a.vorvorher() == null ? "–" : vorzeichen(a.jetzt() - a.vorvorher())
                        + zahl(Math.abs(a.jetzt() - a.vorvorher()), 0) + " kWh"
                        + (a.prozentVorvorjahr() == null ? "" : " (" + prozent(a.prozentVorvorjahr()) + ")"));
            }
            zeile.add(mitAnteil ? anteil(a.differenz() / gesamt.differenz() * 100) : "–");
            zeilen.add(zeile);
        }
        List<String> kopf = new ArrayList<>();
        kopf.add(zeitraum);
        if (dreiJahre) {
            kopf.add((v.jahr() - 2) + " kWh");
        }
        kopf.add((v.jahr() - 1) + " kWh");
        kopf.add(v.jahr() + " kWh");
        kopf.add(dreiJahre ? "Änderung zu " + (v.jahr() - 1) : "Änderung");
        if (dreiJahre) {
            kopf.add("Änderung zu " + (v.jahr() - 2));
        }
        kopf.add("Anteil an der Änderung");
        Double prozent = gesamt.prozent();
        boolean steigt = prozent != null && prozent > STEIGT_AB_PROZENT;
        return "<section class=\"cards\"><article class=\"card breit\"><h2>Woran liegt es?</h2>"
                + "<p class=\"ursache" + (steigt ? " steigt" : "") + "\">" + text(ursache(anteile, v.jahr())) + "</p>"
                + "<p class=\"legende\">Abstand zu " + (v.jahr() - 1) + ", seit 1. Januar aufsummiert: über null mehr "
                + "als " + (v.jahr() - 1) + ", unter null weniger; steigt eine Linie, wächst der Abstand gerade.</p>"
                + abstandsverlauf(v, anteile) + tabelle(kopf, zeilen)
                + "<p class=\"note\">Jeweils vom 1. Januar bis zum selben Tag und zur selben Stunde. Rest ist "
                + "Netzbezug minus Heizung" + (v.mitWallbox() ? " und Wallbox" : "") + ", also der übrige Haushalt mit "
                + "Warmwasser, Lüftung und Geräten. Der Anteil an der Änderung bezieht sich auf " + (v.jahr() - 1)
                + ".</p></article></section>";
    }

    /**
     * Card with the forecast up to the end of the year: what is used so far, what is still expected and the whole
     * year against the year before
     */
    static String prognose(Jahresvergleich.Vergleich v) {
        List<Jahresvergleich.Prognose> teile = v.prognose();
        if (teile.isEmpty()) {
            return "";
        }
        int jahr = v.jahr();
        Jahresvergleich.Prognose erste = teile.get(0);
        String was = erste.name().equals("Gesamt") ? "Das Haus wird " + jahr + " voraussichtlich "
                + zahl(erste.jahr(), 0) + " kWh aus dem Netz beziehen"
                : "Die Heizung wird " + jahr + " voraussichtlich " + zahl(erste.jahr(), 0) + " kWh brauchen";
        Double aenderung = erste.aenderung();
        String satz = was + (aenderung == null ? "." : ", " + (Math.abs(aenderung) < 1 ? "etwa so viel wie"
                : zahl(Math.abs(aenderung), 1) + " % " + (aenderung > 0 ? "mehr" : "weniger") + " als") + " "
                + (jahr - 1) + " (" + zahl(erste.vorjahr(), 0) + " kWh).");
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Prognose p : teile) {
            zeilen.add(List.of(geschaetzt(p.name()), zahl(p.bisher(), 0), zahl(p.erwartet(), 0), zahl(p.jahr(), 0),
                    zahl(p.vorjahr(), 0), prozent(p.aenderung())));
        }
        return "<section class=\"cards\"><article class=\"card breit\"><h2>Prognose bis Jahresende</h2>"
                + "<p class=\"ursache\">" + text(satz) + "</p>"
                + tabelle(List.of(String.valueOf(jahr), "bisher kWh", "bis 31.12. erwartet kWh", "Prognose kWh",
                (jahr - 1) + " kWh", "Änderung"), zeilen)
                + "<p class=\"note\">Erwartet bis 31.12.: Heizung wie im Rest der Vorjahre (Mittel aus "
                + (jahr - 1) + " und " + (jahr - 2) + "), der übrige Haushalt wie im Rest von " + (jahr - 1)
                + ", die Wallbox mit dem Verbrauch je Tag der letzten drei vollen Monate. Ein kalter oder milder "
                + "Winter verschiebt die Heizung um einige hundert kWh.</p></article></section>";
    }

    /**
     * Sentence on the change of the grid import and its biggest part
     */
    static String ursache(List<Jahresvergleich.Anteil> anteile, int jahr) {
        Jahresvergleich.Anteil gesamt = anteile.get(0);
        Double prozent = gesamt.prozent();
        if (prozent == null) {
            return "Für " + (jahr - 1) + " fehlt der Netzbezug, ein Vergleich ist nicht möglich.";
        }
        if (Math.abs(gesamt.differenz()) < MIN_AENDERUNG_KWH || Math.abs(prozent) < 1) {
            return "Der Stromverbrauch ist etwa gleich geblieben wie in denselben Tagen " + (jahr - 1) + ".";
        }
        boolean mehr = gesamt.differenz() > 0;
        Jahresvergleich.Anteil treiber = null;
        for (Jahresvergleich.Anteil a : anteile.subList(1, anteile.size())) {
            if (treiber == null || (mehr ? a.differenz() > treiber.differenz() : a.differenz() < treiber.differenz())) {
                treiber = a;
            }
        }
        return "Der Stromverbrauch ist gegenüber denselben Tagen " + (jahr - 1) + " um "
                + zahl(Math.abs(gesamt.differenz()), 0) + " kWh (" + zahl(Math.abs(prozent), 1) + " %) "
                + (mehr ? "gestiegen, vor allem durch " : "gesunken, vor allem ") + teil(treiber.name(), mehr) + " ("
                + vorzeichen(treiber.differenz()) + zahl(Math.abs(treiber.differenz()), 0) + " kWh).";
    }

    private static String teil(String name, boolean mehr) {
        if (name.startsWith("Rest")) {
            return mehr ? "den übrigen Haushalt" : "beim übrigen Haushalt";
        }
        return (mehr ? "die " : "bei der ") + name;
    }

    private static String differenz(Jahresvergleich.Anteil a) {
        return vorzeichen(a.differenz()) + zahl(Math.abs(a.differenz()), 0) + " kWh"
                + (a.prozent() == null ? "" : " (" + prozent(a.prozent()) + ")");
    }

    private static String anteil(double prozent) {
        return Math.abs(prozent) < 0.5 ? "0 %" : (prozent < 0 ? "−" : "") + zahl(Math.abs(prozent), 0) + " %";
    }

    private static String vorzeichen(double wert) {
        return wert >= 0.5 ? "+" : wert <= -0.5 ? "−" : "±";
    }

    /**
     * Address of this page with the given layers shown
     */
    static String adresse(boolean haushalt, boolean wallbox) {
        // Both are shown unless switched off, the page is about the whole grid import
        List<String> parameter = new ArrayList<>();
        if (!haushalt) {
            parameter.add("haushalt=nein");
        }
        if (!wallbox) {
            parameter.add("wallbox=nein");
        }
        return "jahresvergleich" + (parameter.isEmpty() ? "" : "?" + String.join("&amp;", parameter));
    }

    /**
     * One sentence without the house meter: the heating this year up to today against the same days of the year
     * before and the whole years before
     */
    static String satz(Jahresvergleich.Vergleich v) {
        int jahr = v.jahr();
        Double aenderung = v.aenderung();
        String text = "Vom 1. Januar bis " + DATUM.format(v.bis()) + " " + jahr + " hat die Heizung "
                + zahl(v.heizung(J - 1), 0) + " kWh Strom gebraucht";
        if (aenderung != null) {
            text += ", " + (Math.abs(aenderung) < 0.5 ? "genauso viel wie"
                    : zahl(Math.abs(aenderung), 1) + " % " + (aenderung > 0 ? "mehr" : "weniger") + " als")
                    + " in denselben Tagen " + (jahr - 1) + " (" + zahl(v.heizungGleich(J - 2), 0) + " kWh)";
        }
        return text + ". Im ganzen Jahr waren es " + zahl(v.heizung(J - 2), 0) + " kWh (" + (jahr - 1) + ") und "
                + zahl(v.heizung(J - 3), 0) + " kWh (" + (jahr - 2) + ").";
    }

    static List<String> kopf(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitWallbox) {
        List<String> kopf = new ArrayList<>();
        kopf.add("Monat");
        if (v.gesamt() != null) {
            // With the house meter: the grid import per year first, the heating as a part of it
            v.jahre().forEach(j -> kopf.add("Gesamt " + j + " kWh"));
            kopf.add("Änderung");
            v.jahre().forEach(j -> kopf.add("Heizung ≈ " + j + " kWh"));
            v.jahre().forEach(j -> kopf.add("Außen " + j + " °C"));
            return kopf;
        }
        v.jahre().forEach(j -> kopf.add("Heizung ≈ " + j + " kWh"));
        kopf.add("Änderung");
        if (mitHaushalt) {
            v.jahre().forEach(j -> kopf.add("Haushalt " + j + " kWh"));
        }
        if (mitWallbox) {
            v.jahre().forEach(j -> kopf.add("Wallbox " + j + " kWh"));
        }
        v.jahre().forEach(j -> kopf.add("Außen " + j + " °C"));
        return kopf;
    }

    /**
     * Rows of the table: month, heating per year, change, household and wallbox per year if shown, outdoor
     * temperature per year
     */
    static List<List<String>> zeilen(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitWallbox) {
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Monat m : v.monate()) {
            List<String> zeile = new ArrayList<>();
            zeile.add(m.name());
            if (v.gesamt() != null) {
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(gesamt(m, i), 0) + (i == J - 1 ? bisHeute(v, m, gesamt(m, i)) : ""));
                }
                Double jetzt = gesamt(m, J - 1);
                Double vorher = gesamt(m, J - 2);
                // The current month is not over, the year before has no value up to the same day
                zeile.add(m.laufend() || jetzt == null || vorher == null || vorher < 5 ? "–"
                        : prozent((jetzt / vorher - 1) * 100));
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.heizung().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.heizung().get(i)) : ""));
                }
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.aussen().get(i), 1));
                }
                zeilen.add(zeile);
                continue;
            }
            for (int i = 0; i < J; i++) {
                zeile.add(zahl(m.heizung().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.heizung().get(i)) : ""));
            }
            zeile.add(prozent(m.aenderung()));
            if (mitHaushalt) {
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.haushalt().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.haushalt().get(i)) : ""));
                }
            }
            if (mitWallbox) {
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.wallbox().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.wallbox().get(i)) : ""));
                }
            }
            for (int i = 0; i < J; i++) {
                zeile.add(zahl(m.aussen().get(i), 1));
            }
            zeilen.add(zeile);
        }
        return zeilen;
    }

    private static String bisHeute(Jahresvergleich.Vergleich v, Jahresvergleich.Monat m, Double wert) {
        return m.laufend() && wert != null ? " (bis " + v.bis().getDayOfMonth() + ".)" : "";
    }

    /**
     * Bars per month as inline SVG, one per year with the current year in the accent color, the household and the
     * wallbox stacked on top if shown, a tooltip per bar
     */
    static String diagramm(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitWallbox) {
        double max = 0;
        for (Jahresvergleich.Monat m : v.monate()) {
            for (int i = 0; i < J; i++) {
                max = Math.max(max, wert(m.heizung().get(i)) + (mitHaushalt ? wert(m.haushalt().get(i)) : 0)
                        + (mitWallbox ? wert(m.wallbox().get(i)) : 0));
            }
        }
        double schritt = schritt(max);
        double oben = Math.max(schritt, Math.ceil(max / schritt) * schritt);
        StringBuilder svg = new StringBuilder("<div class=\"diagramm\"><svg viewBox=\"0 0 " + B + " " + H
                + "\" width=\"100%\" role=\"img\" aria-label=\"Strom der Heizung" + (mitHaushalt ? ", des Haushalts" : "")
                + (mitWallbox ? ", der Wallbox" : "") + " je Monat, " + v.jahre().get(0) + " bis " + v.jahr() + "\">");
        for (double t = 0; t <= oben + schritt / 2; t += schritt) {
            double y = y(t, oben);
            svg.append(String.format(Locale.ROOT, "<line class=\"gitter\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>",
                    LINKS, B - RECHTS, y, y));
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>",
                    LINKS - 6, y + 4, zahl(t, 0)));
        }
        double breite = (B - LINKS - RECHTS) / 12.0;
        double balken = Math.min(18, (breite * 0.8) / J);
        for (Jahresvergleich.Monat m : v.monate()) {
            double x0 = LINKS + (m.monat() - 1) * breite + (breite - balken * J) / 2;
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>",
                    x0 + balken * J / 2, H - 8, m.name()));
            for (int i = 0; i < J; i++) {
                Double heizung = m.heizung().get(i);
                if (heizung == null) {
                    continue;
                }
                int jahr = v.jahre().get(i);
                boolean laufend = m.laufend() && i == J - 1;
                Double haushalt = mitHaushalt ? m.haushalt().get(i) : null;
                Double wallbox = mitWallbox ? m.wallbox().get(i) : null;
                String titel = m.name() + " " + jahr + (laufend ? " bis " + v.bis().getDayOfMonth() + "." : "")
                        + ": Heizung " + zahl(heizung, 0) + " kWh"
                        + (haushalt == null ? "" : ", Haushalt " + zahl(haushalt, 0) + " kWh")
                        + (wallbox == null ? "" : ", Wallbox " + zahl(wallbox, 0) + " kWh")
                        + (m.aussen().get(i) == null ? "" : ", außen " + zahl(m.aussen().get(i), 1) + " °C")
                        + (m.laufend() && i < J - 1 && m.heizungGleich().get(i) != null
                        ? "; bis zum gleichen Tag " + zahl(m.heizungGleich().get(i), 0) + " kWh" : "");
                double x = x0 + i * balken + 1;
                svg.append(balken("j" + i + (laufend ? " laufend" : ""), x, balken - 2, 0, heizung, oben, titel));
                double unten = heizung;
                if (haushalt != null && haushalt > 0) {
                    svg.append(balken("haushalt j" + i + (laufend ? " laufend" : ""), x, balken - 2, unten,
                            unten + haushalt, oben, titel));
                    unten += haushalt;
                }
                if (wallbox != null && wallbox > 0) {
                    svg.append(balken("wallbox j" + i + (laufend ? " laufend" : ""), x, balken - 2, unten,
                            unten + wallbox, oben, titel));
                }
            }
        }
        svg.append(String.format(Locale.ROOT, "<line class=\"achslinie\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>",
                LINKS, B - RECHTS, y(0, oben), y(0, oben)));
        return svg.append("</svg></div>").toString();
    }

    private static String balken(String klasse, double x, double breite, double von, double bis, double oben,
                                 String titel) {
        double y = y(bis, oben);
        double hoehe = Math.max(0, y(von, oben) - y);
        return String.format(Locale.ROOT, "<rect class=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" "
                + "rx=\"2\" data-wert=\"%s\"></rect>", klasse, x, y, breite, hoehe, text(titel));
    }

    private static double y(double wert, double oben) {
        return OBEN + (1 - wert / oben) * (H - OBEN - UNTEN);
    }

    /**
     * Step of the grid lines: 1, 2 or 5 times a power of ten, about 5 lines
     */
    static double schritt(double max) {
        if (max <= 0) {
            return 1;
        }
        double roh = max / 5;
        double stelle = Math.pow(10, Math.floor(Math.log10(roh)));
        double n = roh / stelle;
        return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * stelle;
    }

    private static double wert(Double wert) {
        return wert == null ? 0 : wert;
    }

    private static String kpi(String label, String wert, String einheit) {
        return "<article class=\"kpi\"><div class=\"label\">" + text(label) + "</div><div class=\"value\">"
                + text(wert) + (einheit.isEmpty() ? "" : "<span class=\"unit\">" + einheit + "</span>")
                + "</div></article>";
    }

    private static String tabelle(List<String> kopf, List<List<String>> zeilen) {
        StringBuilder html = new StringBuilder("<div class=\"tbl\"><table><thead><tr>");
        kopf.forEach(k -> html.append("<th>").append(text(k)).append("</th>"));
        html.append("</tr></thead><tbody>");
        for (List<String> zeile : zeilen) {
            html.append("<tr>");
            zeile.forEach(z -> html.append("<td>").append(text(z)).append("</td>"));
            html.append("</tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    static String zahl(Double wert, int stellen) {
        return wert == null ? "–" : String.format(Locale.GERMANY, "%,." + stellen + "f", wert);
    }

    static String prozent(Double wert) {
        if (wert == null) {
            return "–";
        }
        return (wert > 0.05 ? "+" : wert < -0.05 ? "−" : "±") + zahl(Math.abs(wert), 1) + " %";
    }

    private static String text(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Grid import of a month: heating, household and wallbox together, null without the house meter
     */
    static Double gesamt(Jahresvergleich.Monat m, int i) {
        Double haushalt = m.haushalt().get(i);
        if (haushalt == null) {
            return null;
        }
        return wert(m.heizung().get(i)) + haushalt + wert(m.wallbox().get(i));
    }

    /**
     * Card with one bar per year, the current year with its forecast, made of heating, household and wallbox: how
     * the whole consumption develops and of what it consists. Empty without the house meter.
     */
    static String jahresbalken(Jahresvergleich.Vergleich v) {
        if (v.gesamt() == null) {
            return "";
        }
        List<Jahresvergleich.Prognose> teile = v.prognose();
        List<double[]> jahre = new ArrayList<>();
        for (int i = 0; i < J - 1; i++) {
            jahre.add(new double[]{v.heizung(i), wert(v.haushalt(i)), wert(v.wallbox(i))});
        }
        double[] jetzt = new double[3];
        for (Jahresvergleich.Prognose p : teile) {
            int k = teil(p.name()).equals("heizung") ? 0 : teil(p.name()).equals("haushalt") ? 1
                    : teil(p.name()).equals("wallbox") ? 2 : -1;
            if (k >= 0) {
                jetzt[k] = p.jahr();
            }
        }
        jahre.add(jetzt);
        double max = jahre.stream().mapToDouble(t -> t[0] + t[1] + t[2]).max().orElse(0);
        if (max <= 0) {
            return "";
        }
        int zeile = 34;
        int links = 120;
        int rechts = 190;
        int hoehe = J * zeile + 6;
        double breite = B - links - rechts;
        String[] klassen = {"heizung", "haushalt", "wallbox"};
        String[] namen = {"Heizung ≈", "Haushalt", "Wallbox"};
        StringBuilder svg = new StringBuilder("<div class=\"diagramm\"><svg viewBox=\"0 0 " + B + " " + hoehe
                + "\" width=\"100%\" role=\"img\" aria-label=\"Netzbezug je Jahr\">");
        for (int i = 0; i < J; i++) {
            double[] t = jahre.get(i);
            double y = 4 + i * zeile;
            boolean prognose = i == J - 1;
            int jahr = v.jahre().get(i);
            svg.append(String.format(Locale.ROOT, "<text class=\"teil\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">"
                    + "%s</text>", links - 10, y + zeile / 2.0 + 1, jahr + (prognose ? " Prognose" : "")));
            double x = links;
            double summe = t[0] + t[1] + t[2];
            for (int k = 0; k < 3; k++) {
                if (t[k] <= 0) {
                    continue;
                }
                double w = t[k] / max * breite;
                String titel = jahr + (prognose ? " (Prognose)" : "") + ": " + namen[k] + " " + zahl(t[k], 0)
                        + " kWh von " + zahl(summe, 0) + " kWh";
                svg.append(String.format(Locale.ROOT, "<rect class=\"teil %s%s\" x=\"%.1f\" y=\"%.1f\" "
                        + "width=\"%.1f\" height=\"%d\" data-wert=\"%s\"></rect>", klassen[k],
                        prognose ? " prognose" : "", x, y + 4, Math.max(1, w - 2), zeile - 10, text(titel)));
                x += w;
            }
            // Change against the year before, the current year with its forecast
            String aenderung = "";
            if (i > 0) {
                double[] v0 = jahre.get(i - 1);
                double vorher = v0[0] + v0[1] + v0[2];
                if (vorher > 0) {
                    aenderung = "<tspan class=\"aenderung\" dx=\"10\">" + prozent((summe / vorher - 1) * 100)
                            + "</tspan>";
                }
            }
            svg.append(String.format(Locale.ROOT, "<text class=\"wert\" x=\"%.1f\" y=\"%.1f\">%s kWh%s</text>",
                    x + 6, y + zeile / 2.0 + 1, (prognose ? "≈ " : "") + zahl(summe, 0), aenderung));
        }
        return "<section class=\"cards\"><article class=\"card breit\"><h2>Netzbezug je Jahr</h2>"
                + "<p class=\"legende\"><span><i class=\"sw teil-heizung\"></i>Heizung ≈</span><span><i class=\"sw "
                + "haushalt\"></i>Haushalt</span><span><i class=\"sw wallbox\"></i>Wallbox</span><span>"
                + v.jahr() + " heller: bis " + DATUM.format(v.bis()) + " gemessen, der Rest ist die Prognose unten; "
                + "% gegenüber dem Vorjahr"
                + "</span></p>" + svg + "</svg></div></article></section>";
    }

    /**
     * Gap to the year before added up from the 1st of January, one line for the whole grid import and one per
     * part: when the gap grows and through which part. Uses the months that are over and the same days of the
     * current month.
     */
    static String abstandsverlauf(Jahresvergleich.Vergleich v, List<Jahresvergleich.Anteil> anteile) {
        if (v.monate().size() < 12 || anteile.isEmpty()) {
            return "";
        }
        int jahr = v.jahr();
        String[] klassen = {"gesamt", "heizung", "haushalt", "wallbox"};
        // Values per line: x as part of the year, y the gap so far
        List<List<double[]>> linien = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            List<double[]> l = new ArrayList<>();
            l.add(new double[]{0, 0});
            linien.add(l);
        }
        double[] summe = new double[4];
        double tage = java.time.Year.of(jahr).length();
        for (Jahresvergleich.Monat m : v.monate()) {
            if (m.laufend() || m.monat() > v.bis().getMonthValue()) {
                break;
            }
            Double gJetzt = gesamt(m, J - 1);
            Double gVorher = gesamt(m, J - 2);
            if (gJetzt == null || gVorher == null) {
                return "";
            }
            double h = wert(m.heizung().get(J - 1)) - wert(m.heizung().get(J - 2));
            double w = wert(m.wallbox().get(J - 1)) - wert(m.wallbox().get(J - 2));
            double hh = wert(m.haushalt().get(J - 1)) - wert(m.haushalt().get(J - 2));
            double[] d = {gJetzt - gVorher, h, hh, w};
            double x = java.time.YearMonth.of(jahr, m.monat()).atEndOfMonth().getDayOfYear() / tage;
            for (int k = 0; k < 4; k++) {
                summe[k] += d[k];
                linien.get(k).add(new double[]{x, summe[k], m.monat()});
            }
        }
        // The last point: the same days up to now, as in the table
        double xHeute = v.bis().getDayOfYear() / tage;
        for (Jahresvergleich.Anteil a : anteile) {
            int k = java.util.Arrays.asList(klassen).indexOf(teil(a.name()));
            linien.get(k).add(new double[]{xHeute, a.differenz(), 0});
        }
        boolean mitWallbox = anteile.stream().anyMatch(a -> teil(a.name()).equals("wallbox"));
        double min = 0;
        double max = 0;
        for (List<double[]> l : linien) {
            for (double[] p : l) {
                min = Math.min(min, p[1]);
                max = Math.max(max, p[1]);
            }
        }
        double schritt = schritt(Math.max(max - min, 1));
        double unten = Math.floor(min / schritt) * schritt;
        double oben = Math.max(schritt, Math.ceil(max / schritt) * schritt);
        int rechts = 130;
        double breite = B - LINKS - rechts;
        java.util.function.DoubleUnaryOperator yv = w -> OBEN + (oben - w) / (oben - unten) * (H - OBEN - UNTEN);
        StringBuilder svg = new StringBuilder("<div class=\"diagramm\"><svg viewBox=\"0 0 " + B + " " + H
                + "\" width=\"100%\" role=\"img\" aria-label=\"Abstand zu " + (jahr - 1) + " seit 1. Januar\">");
        for (double t = unten; t <= oben + schritt / 2; t += schritt) {
            double y = yv.applyAsDouble(t);
            svg.append(String.format(Locale.ROOT, "<line class=\"%s\" x1=\"%d\" x2=\"%.1f\" y1=\"%.1f\" "
                    + "y2=\"%.1f\"/>", Math.abs(t) < 1e-9 ? "achslinie" : "gitter", LINKS, LINKS + breite, y, y));
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%d\" y=\"%.1f\" "
                    + "text-anchor=\"end\">%s</text>", LINKS - 6, y + 4,
                    (t > 0 ? "+" : t < 0 ? "−" : "") + zahl(Math.abs(t), 0)));
        }
        for (int monat = 1; monat <= 12; monat++) {
            double mitte = java.time.YearMonth.of(jahr, monat).atDay(15).getDayOfYear() / tage;
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%.1f\" y=\"%d\" "
                    + "text-anchor=\"middle\">%s</text>", LINKS + mitte * breite, H - 8,
                    v.monate().get(monat - 1).name()));
        }
        String[] namen = {"Gesamt", "Heizung ≈", mitWallbox ? "Haushalt" : "Haushalt und Wallbox", "Wallbox"};
        List<double[]> etiketten = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            List<double[]> l = linien.get(k);
            if (k == 3 && !mitWallbox) {
                continue;
            }
            StringBuilder p = new StringBuilder();
            for (double[] x : l) {
                p.append(p.isEmpty() ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f", LINKS + x[0] * breite,
                        yv.applyAsDouble(x[1])));
            }
            svg.append("<polyline class=\"abstand ").append(klassen[k]).append("\" points=\"").append(p)
                    .append("\"/>");
            for (int i = 1; i < l.size(); i++) {
                double[] x = l.get(i);
                String wann = x[2] == 0 ? "bis " + DATUM.format(v.bis())
                        : "bis Ende " + v.monate().get((int) x[2] - 1).name();
                svg.append(String.format(Locale.ROOT, "<circle class=\"abstand %s\" cx=\"%.1f\" cy=\"%.1f\" "
                        + "r=\"3.5\" data-wert=\"%s\"></circle>", klassen[k], LINKS + x[0] * breite,
                        yv.applyAsDouble(x[1]), text(namen[k] + " " + wann + ": " + vorzeichen(x[1])
                                + zahl(Math.abs(x[1]), 0) + " kWh gegenüber " + (jahr - 1))));
            }
            double[] ende = l.get(l.size() - 1);
            etiketten.add(new double[]{yv.applyAsDouble(ende[1]), k, ende[1], LINKS + ende[0] * breite});
        }
        etiketten.sort(java.util.Comparator.comparingDouble(e -> e[0]));
        for (int i = 1; i < etiketten.size(); i++) {
            if (etiketten.get(i)[0] - etiketten.get(i - 1)[0] < 15) {
                etiketten.get(i)[0] = etiketten.get(i - 1)[0] + 15;
            }
        }
        for (double[] e : etiketten) {
            int k = (int) e[1];
            svg.append(String.format(Locale.ROOT, "<text class=\"etikett %s\" x=\"%.1f\" y=\"%.1f\">%s %s</text>",
                    klassen[k], e[3] + 8, e[0] + 4, text(namen[k].replace(" ≈", "")),
                    vorzeichen(e[2]) + zahl(Math.abs(e[2]), 0)));
        }
        return svg.append("</svg></div>").toString();
    }

    private static String teil(String name) {
        return name.equals("Gesamt") ? "gesamt" : name.equals("Wallbox") ? "wallbox"
                : name.equals("Heizung") ? "heizung" : "haushalt";
    }

    // The color of the wallbox is the page's own, light and dark like the tokens of the status page
    private static final String CSS = """
            :root { --wallbox: #6d5bd0; }
            @media (prefers-color-scheme: dark) { :root { --wallbox: #a99bf0; } }
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600;
              background: var(--card); border: 1px solid var(--line); }
            .tbl { overflow-x: auto; }
            .tbl table { font-size: 14px; }
            .legende { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 4px 0 4px; color: var(--muted);
              font-size: 13px; }
            .legende span { display: inline-flex; align-items: center; gap: 6px; }
            .umschalten { margin: 0 0 8px; font-size: 14px; }
            .ursache { margin: 4px 0 8px; font-weight: 600; }
            .cards + .kpis, .cards + .cards { margin-top: 16px; }
            .ursache.steigt { color: var(--warn); }
            .sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
            .sw.j0 { background: var(--muted); opacity: 0.3; }
            .sw.j1 { background: var(--muted); opacity: 0.65; }
            .sw.j2 { background: var(--accent); }
            .sw.haushalt { background: var(--stale); }
            .sw.wallbox { background: var(--wallbox); }
            .diagramm rect.j0 { fill: var(--muted); fill-opacity: 0.3; }
            .diagramm rect.j1 { fill: var(--muted); fill-opacity: 0.65; }
            .diagramm rect.j2 { fill: var(--accent); }
            .diagramm rect.haushalt { fill: var(--stale); }
            .diagramm rect.haushalt.j0 { fill-opacity: 0.35; }
            .diagramm rect.haushalt.j1 { fill-opacity: 0.6; }
            .diagramm rect.haushalt.j2 { fill-opacity: 0.9; }
            .diagramm rect.laufend { fill-opacity: 0.5; }
            .diagramm rect.wallbox { fill: var(--wallbox); }
            .diagramm rect.wallbox.j0 { fill-opacity: 0.35; }
            .diagramm rect.wallbox.j1 { fill-opacity: 0.6; }
            .diagramm rect.wallbox.j2 { fill-opacity: 0.9; }
            .diagramm rect.haushalt.laufend, .diagramm rect.wallbox.laufend { fill-opacity: 0.45; }
            .diagramm rect:hover { stroke: var(--text); stroke-width: 1; }
            .diagramm .gitter { stroke: var(--line); stroke-width: 1; }
            .diagramm .achslinie { stroke: var(--muted); stroke-width: 1; }
            .diagramm .achse { fill: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
            .diagramm polyline.abstand { fill: none; stroke-width: 2.5; stroke-linejoin: round; }
            .diagramm polyline.abstand.gesamt { stroke: var(--text); stroke-width: 3; }
            .diagramm polyline.abstand.heizung { stroke: var(--accent); }
            .diagramm polyline.abstand.haushalt { stroke: var(--stale); }
            .diagramm polyline.abstand.wallbox { stroke: var(--wallbox); }
            .diagramm circle.abstand { stroke: var(--card); stroke-width: 1.5; }
            .diagramm circle.abstand.gesamt { fill: var(--text); }
            .diagramm circle.abstand.heizung { fill: var(--accent); }
            .diagramm circle.abstand.haushalt { fill: var(--stale); }
            .diagramm circle.abstand.wallbox { fill: var(--wallbox); }
            .diagramm .etikett { font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; fill: var(--text); }
            .diagramm .etikett.heizung { fill: var(--accent); }
            .diagramm .etikett.haushalt { fill: var(--stale); }
            .diagramm .etikett.wallbox { fill: var(--wallbox); }
            .diagramm rect.teil.heizung { fill: var(--accent); }
            .diagramm rect.teil.haushalt { fill: var(--stale); }
            .diagramm rect.teil.wallbox { fill: var(--wallbox); }
            .diagramm rect.teil.prognose { fill-opacity: 0.55; }
            .diagramm .teil { font-size: 13px; fill: var(--text); }
            .diagramm .aenderung { font-weight: 500; fill: var(--muted); }
            .diagramm .wert { font-size: 13px; fill: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }
            .sw.teil-heizung { background: var(--accent); }
            """;

    /**
     * The electricity of the heating is the estimated real power of the inverter, marked like everywhere with ≈
     */
    static String geschaetzt(String name) {
        return name.equals("Heizung") ? "Heizung ≈" : name;
    }
}
