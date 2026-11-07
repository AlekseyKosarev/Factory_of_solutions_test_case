package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.time.LocalDate;
import java.util.*;

public final class StaleProgressRule implements Rule {
  public List<Finding> evaluate(Snapshot s, LocalDate date) {
    return s.keyResults().stream()
        .filter(k -> k.factUpdatedOn() == null || k.factUpdatedOn().isBefore(date.minusDays(45)))
        .map(
            k ->
                new Finding(
                    "KR_STALE_PROGRESS",
                    "minor",
                    "kr",
                    k.id(),
                    k.goalId(),
                    k.id(),
                    "Key result progress is missing or older than 45 days",
                    Collections.singletonMap(
                        "fact_updated_on",
                        k.factUpdatedOn() == null ? null : k.factUpdatedOn().toString())))
        .toList();
  }
}
