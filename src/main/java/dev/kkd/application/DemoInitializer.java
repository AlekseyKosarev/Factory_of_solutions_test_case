package dev.kkd.application;

import dev.kkd.infrastructure.*;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DemoInitializer implements ApplicationRunner {
  private final boolean enabled;
  private final DatabaseLock lock;
  private final RunService runs;
  private final QualityRepository quality;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public DemoInitializer(
      @Value("${kkd.demo-enabled}") boolean enabled,
      DatabaseLock lock,
      RunService runs,
      QualityRepository quality,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager) {
    this.enabled = enabled;
    this.lock = lock;
    this.runs = runs;
    this.quality = quality;
    this.jdbc = jdbc;
    tx = new TransactionTemplate(manager);
  }

  @Override
  public void run(ApplicationArguments args) {
    initialize();
  }

  public void initialize() {
    if (!enabled) return;
    lock.withLock(
        2026110602L,
        () -> {
          if (quality.find("RUN-002").filter(r -> r.status().equals("COMPLETED")).isPresent())
            return null;
          if (jdbc.queryForObject("SELECT count(*) FROM goal", Long.class) != 24L)
            throw new IllegalStateException(
                "Demo requires the 24-goal fixture; check Liquibase demo context");
          runs.executeDemo("RUN-001", LocalDate.parse("2026-10-19"));
          // Idempotent patch: safe to repeat if the process stopped before RUN-002 completed.
          tx.executeWithoutResult(
              status -> {
                jdbc.update("UPDATE goal SET owner='Employee 1' WHERE id='G01'");
                jdbc.update("UPDATE goal SET owner=NULL WHERE id='G07'");
                jdbc.update("UPDATE key_result SET weight=100 WHERE id='K04'");
                jdbc.update(
                    "UPDATE key_result SET fact_updated_on=DATE '2026-08-01' WHERE id IN"
                        + " ('K08','K09')");
              });
          runs.executeDemo("RUN-002", LocalDate.parse("2026-10-26"));
          return null;
        });
  }
}
