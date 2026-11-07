package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.util.*;
import java.util.stream.Collectors;

public final class DeltaCalculator {
  public record Delta(Long added, Long persisting, Long resolved) {}

  public Delta calculate(List<Finding> current, List<Finding> previous) {
    if (previous == null) return new Delta(null, null, null);
    var n = current.stream().map(Finding::key).collect(Collectors.toSet());
    var p = previous.stream().map(Finding::key).collect(Collectors.toSet());
    long same = n.stream().filter(p::contains).count();
    return new Delta(n.size() - same, same, p.size() - same);
  }
}
