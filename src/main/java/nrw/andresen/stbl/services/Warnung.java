package nrw.andresen.stbl.services;

import java.time.Duration;
import java.time.Instant;

/**
 * A warning that becomes active when its condition holds for the delay and ends as soon as it no longer holds.
 * Only the start is reported, so one problem gives one mail.
 */
public class Warnung {

    private final String name;
    private final String regel;
    private final Duration verzoegerung;
    private Instant bedingungSeit;
    private Instant aktivSeit;
    private String text;

    /**
     * @param name         short name, also the subject of the mail
     * @param regel        when the warning becomes active, shown as explanation
     * @param verzoegerung how long the condition has to hold, e.g. to ignore a single bad minute
     */
    public Warnung(String name, String regel, Duration verzoegerung) {
        this.name = name;
        this.regel = regel;
        this.verzoegerung = verzoegerung;
    }

    /**
     * Updates the state, returns true if the warning became active now
     *
     * @param text current value, shown while the warning is active
     */
    public synchronized boolean pruefen(boolean bedingung, String text, Instant jetzt) {
        if (!bedingung) {
            bedingungSeit = null;
            aktivSeit = null;
            this.text = null;
            return false;
        }
        this.text = text;
        if (bedingungSeit == null) {
            bedingungSeit = jetzt;
        }
        if (aktivSeit == null && Duration.between(bedingungSeit, jetzt).compareTo(verzoegerung) >= 0) {
            aktivSeit = jetzt;
            return true;
        }
        return false;
    }

    public synchronized boolean isAktiv() {
        return aktivSeit != null;
    }

    public synchronized Instant getAktivSeit() {
        return aktivSeit;
    }

    public synchronized String getText() {
        return text;
    }

    public String getName() {
        return name;
    }

    public String getRegel() {
        return regel;
    }
}
