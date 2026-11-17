package nrw.andresen.stbl;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SchedulerTest {

    static class Probe {
        final CompletableFuture<String> can = new CompletableFuture<>();
        final CompletableFuture<String> auswertung = new CompletableFuture<>();

        @Scheduled(fixedRate = 60000, scheduler = Application.CAN_SCHEDULER)
        public void canAufgabe() {
            can.complete(Thread.currentThread().getName());
        }

        @Scheduled(fixedRate = 60000)
        public void auswertung() {
            auswertung.complete(Thread.currentThread().getName());
        }
    }

    @Configuration
    @EnableScheduling
    static class Konfiguration {
        private final Application application = new Application();

        @Bean
        public ThreadPoolTaskScheduler taskScheduler() {
            return application.taskScheduler();
        }

        @Bean(Application.CAN_SCHEDULER)
        public ThreadPoolTaskScheduler canScheduler() {
            return application.canScheduler();
        }

        @Bean
        public Probe probe() {
            return new Probe();
        }
    }

    @Test
    public void testCanAufgabenLaufenGetrennt() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Konfiguration.class)) {
            Probe probe = context.getBean(Probe.class);
            assertTrue(probe.can.get(5, TimeUnit.SECONDS).startsWith("can-"));
            assertTrue(probe.auswertung.get(5, TimeUnit.SECONDS).startsWith("scheduling-"));
        }
    }
}
