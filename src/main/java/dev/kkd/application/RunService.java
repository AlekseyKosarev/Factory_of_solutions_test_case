package dev.kkd.application;

import dev.kkd.domain.RuleEngine;
import dev.kkd.infrastructure.*;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RunService {
  private final QualityRepository quality;
  private final SnapshotRepository snapshots;
  private final DatabaseLock lock;
  private final TransactionTemplate transaction;
  private final RuleEngine engine = new RuleEngine();

  public RunService(
      QualityRepository quality,
      SnapshotRepository snapshots,
      DatabaseLock lock,
      PlatformTransactionManager manager) {
    this.quality = quality;
    this.snapshots = snapshots;
    this.lock = lock;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public void execute(String id, LocalDate date) {
    execute(id, date, false);
  }

  public void executeDemo(String id, LocalDate date) {
    execute(id, date, true);
  }

  private void execute(String id, LocalDate date, boolean demo) {
    lock.withLock(
        2026110601L,
        () -> {
          quality.abandonRunning();
          var existing = quality.find(id);
          if (existing.isPresent()) {
            if (demo && existing.get().status().equals("COMPLETED")) return null;
            if (demo && existing.get().status().equals("FAILED")) quality.deleteFailed(id);
            else throw new IllegalArgumentException("Run ID already exists");
          }
          var previous = quality.latest().orElse(null);
          if (previous != null
              && (!date.isAfter(previous.snapshotDate())
                  || !previous.ruleSetVersion().equals("v1")))
            throw new IllegalArgumentException("New run must be later and use the same rule set");
          // Run metadata survives an evaluation rollback; snapshot and findings commit atomically.
          quality.start(id, date, previous == null ? null : previous.id());
          try {
            transaction.executeWithoutResult(
                status -> {
                  // All source reads and SQL copies observe the same repeatable-read snapshot.
                  var snapshot = snapshots.live();
                  var findings = engine.evaluate(snapshot, date);
                  snapshots.copy(id);
                  quality.save(id, findings);
                  quality.complete(id);
                });
          } catch (RuntimeException e) {
            quality.fail(id);
            throw e;
          }
          return null;
        });
  }
}
