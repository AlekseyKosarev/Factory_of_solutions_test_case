package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public final class NoKrRule implements Rule {
  public List<Finding> evaluate(Snapshot s, LocalDate date) {
    var withKr = s.keyResults().stream().map(KeyResult::goalId).collect(Collectors.toSet());
    return s.goals().stream()
        .filter(g -> !withKr.contains(g.id()))
        .map(
            g ->
                new Finding(
                    "GOAL_NO_KR",
                    "critical",
                    "goal",
                    g.id(),
                    g.id(),
                    null,
                    "Goal has no key results",
                    Map.of("kr_count", 0)))
        .toList();
  }
}
