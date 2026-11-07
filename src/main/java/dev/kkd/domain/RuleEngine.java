package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.time.LocalDate;
import java.util.*;

public final class RuleEngine {
  private final List<Rule> rules;

  public RuleEngine() {
    this(List.of(new NoOwnerRule(), new NoKrRule(), new WeightsRule(), new StaleProgressRule()));
  }

  public RuleEngine(List<Rule> rules) {
    this.rules = List.copyOf(rules);
  }

  public List<Finding> evaluate(Snapshot snapshot, LocalDate date) {
    snapshot.validate();
    var active = snapshot.activeOnly();
    var result = rules.stream().flatMap(r -> r.evaluate(active, date).stream()).toList();
    if (result.stream().map(Finding::key).distinct().count() != result.size())
      throw new IllegalStateException("Duplicate issue key");
    return result;
  }
}
