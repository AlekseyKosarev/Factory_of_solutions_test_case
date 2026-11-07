package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class WeightsRule implements Rule {
  public List<Finding> evaluate(Snapshot s, LocalDate date) {
    Map<String, BigDecimal> sums = new HashMap<>();
    for (var k : s.keyResults()) sums.merge(k.goalId(), k.weight(), BigDecimal::add);
    return s.goals().stream()
        .filter(
            g ->
                sums.containsKey(g.id())
                    && sums.get(g.id()).compareTo(BigDecimal.valueOf(100)) != 0)
        .map(
            g ->
                new Finding(
                    "KR_WEIGHTS_SUM",
                    "major",
                    "goal",
                    g.id(),
                    g.id(),
                    null,
                    "Key result weights must total 100",
                    Map.of("actual_sum", sums.get(g.id()), "expected_sum", 100)))
        .toList();
  }
}
