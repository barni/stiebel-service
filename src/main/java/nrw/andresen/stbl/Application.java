package nrw.andresen.stbl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

/**
 * Spring boot main class
 *
 */
@SpringBootApplication
@EnableScheduling
public class Application {

    // The evaluations (comparison, failed starts, daily overview) query InfluxDB for up to 1.5 minutes; with the
    // default of one thread the requests every 20 s and the USB watchdog would wait for them
    private static final int SCHEDULER_THREADS = 4;

    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    private static volatile ConfigurableApplicationContext context;
    private static String[] argumente = new String[0];

    public static void main(String[] args) {
        argumente = args;
        context = SpringApplication.run(Application.class, args);
    }

    /**
     * Restarts the service within the running process: closes the Spring context and starts a new one, which reads
     * the configuration file again. It does not depend on systemd restarting the process. The CAN adapter is closed
     * and opened again, as the USB watchdog does; the values have a gap of about a minute.
     *
     * @param verzoegerung time for the answer of the request that asked for the restart to reach the browser
     * @return false if the service was not started by main, e.g. in a test
     */
    public static synchronized boolean neustarten(Duration verzoegerung) {
        ConfigurableApplicationContext alt = context;
        if (alt == null) {
            return false;
        }
        // Not a thread of the context, which ends with it; not a daemon, so the process stays alive in between
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(verzoegerung.toMillis());
                logger.info("Restart requested on the configuration page, closing the service");
                alt.close();
                context = SpringApplication.run(Application.class, argumente);
            } catch (Throwable e) {
                // Without a context nothing answers any more: end the process, systemd or the user starts it again
                logger.error("Restart failed, the service ends", e);
                System.exit(1);
            }
        }, "neustart");
        thread.setDaemon(false);
        thread.start();
        return true;
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_THREADS);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
}
