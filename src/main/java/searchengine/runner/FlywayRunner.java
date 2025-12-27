package searchengine.runner;

import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class FlywayRunner implements CommandLineRunner {
    private final Flyway flyway;

    public FlywayRunner(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public void run(String... args) {
        flyway.migrate();
    }
}
