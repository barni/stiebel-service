package nrw.andresen.stbl.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Version, git commit and build time of the running service, so it is clear after a deployment which state runs.
 * Maven writes both files during the build; started from the IDE or in a test they are missing.
 */
@Component
public class Version {

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Berlin"));

    private final Logger logger = LoggerFactory.getLogger(Version.class);
    private final String text;
    private final Instant gestartet = Instant.now();

    public Version(ObjectProvider<BuildProperties> build, ObjectProvider<GitProperties> git) {
        BuildProperties b = build.getIfAvailable();
        GitProperties g = git.getIfAvailable();
        this.text = text(b == null ? null : b.getVersion(), g == null ? null : g.getShortCommitId(),
                g != null && "true".equals(g.get("dirty")), b == null ? null : b.getTime());
    }

    /**
     * @param geaendert the build contained changes that were not committed, so the commit alone does not describe it
     */
    static String text(String version, String commit, boolean geaendert, Instant gebaut) {
        if (version == null && commit == null) {
            return "unbekannt (ohne Build-Informationen gestartet)";
        }
        StringBuilder sb = new StringBuilder(version == null ? "Version unbekannt" : version);
        if (commit != null) {
            sb.append(", Commit ").append(commit);
            if (geaendert) {
                sb.append(" mit nicht eingecheckten Änderungen");
            }
        }
        if (gebaut != null) {
            sb.append(", gebaut ").append(ZEIT.format(gebaut));
        }
        return sb.toString();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void melden() {
        logger.info("stbl-service started: " + text);
    }

    public String getText() {
        return text;
    }

    public Instant getGestartet() {
        return gestartet;
    }
}
