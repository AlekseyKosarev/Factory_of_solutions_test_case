package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.math.*;
import java.util.*;

public final class MetricCalculator {
  public record Metric(
      String key,
      String title,
      String unit,
      BigDecimal value,
      BigDecimal previous,
      BigDecimal delta,
      String trend) {}

  public List<Metric> calculate(
      List<Goal> currentGoals,
      List<Finding> current,
      List<Goal> previousGoals,
      List<Finding> previous) {
    BigDecimal[] n = values(currentGoals, current);
    BigDecimal[] p = previous == null ? new BigDecimal[3] : values(previousGoals, previous);
    return List.of(
        metric("clean_goals_share", "Доля корректных целей", "%", n[0], p[0], true),
        metric("issues_per_100_goals", "Проблем на 100 целей", "per_100_goals", n[1], p[1], false),
        metric("critical_issues_count", "Критические проблемы", "count", n[2], p[2], false));
  }

  private BigDecimal[] values(List<Goal> goals, List<Finding> issues) {
    long active = goals.stream().filter(Goal::active).count();
    long affected = issues.stream().map(Finding::goalId).distinct().count();
    long critical = issues.stream().filter(i -> "critical".equals(i.severity())).count();
    return new BigDecimal[] {
      ratio(active - affected, active), ratio(issues.size(), active), BigDecimal.valueOf(critical)
    };
  }

  private BigDecimal ratio(long numerator, long denominator) {
    return denominator == 0
        ? null
        : BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
  }

  private Metric metric(
      String key, String title, String unit, BigDecimal n, BigDecimal p, boolean higherBetter) {
    BigDecimal delta = n == null || p == null ? null : n.subtract(p);
    String trend =
        delta == null
            ? null
            : delta.signum() == 0
                ? "unchanged"
                : (delta.signum() > 0) == higherBetter ? "better" : "worse";
    return new Metric(key, title, unit, n, p, delta, trend);
  }
}
