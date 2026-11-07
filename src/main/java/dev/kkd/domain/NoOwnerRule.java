package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.time.LocalDate;
import java.util.*;

public final class NoOwnerRule implements Rule {
  public List<Finding> evaluate(Snapshot s, LocalDate date) {
    return s.goals().stream()
        .filter(g -> g.owner() == null || g.owner().isBlank())
        .map(
            g ->
                new Finding(
                    "GOAL_NO_OWNER",
                    "critical",
                    "goal",
                    g.id(),
                    g.id(),
                    null,
                    "Goal has no owner",
                    Collections.singletonMap("owner", g.owner())))
        .toList();
  }
}
