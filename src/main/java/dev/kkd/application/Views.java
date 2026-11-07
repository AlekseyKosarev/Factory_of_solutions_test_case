package dev.kkd.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.kkd.domain.MetricCalculator.Metric;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Views {
  private Views() {}

  public record RunRef(String id, LocalDate snapshotDate) {}

  public record RunView(
      String id, String status, LocalDate snapshotDate, RunRef comparedTo, Long issuesCount) {}

  public record Runs(List<RunView> items) {}

  public record Issue(
      UUID id,
      String ruleCode,
      String severity,
      String objectType,
      String objectId,
      String goalId,
      String unitId,
      String message,
      Map<String, Object> details) {}

  public record IssuePage(List<Issue> items, int limit, int offset, long total) {}

  public record Unit(String id, String name) {}

  public record Severity(String code, long current, Long previous, Long delta) {}

  public record Delta(@JsonProperty("new") Long added, Long persisting, Long resolved) {}

  public record Summary(
      RunRef run,
      RunRef comparedTo,
      Unit unit,
      List<Metric> metrics,
      List<Severity> issuesBySeverity,
      Delta delta) {}
}
