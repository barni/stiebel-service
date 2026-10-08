package nrw.andresen.stbl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_THREADS);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
}
