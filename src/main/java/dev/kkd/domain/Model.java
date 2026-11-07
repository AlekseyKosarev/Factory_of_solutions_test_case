package dev.kkd.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class Model {
  private Model() {}

  public record Department(String id, String name, String parentId) {}

  public record Goal(
      String id,
      String unitId,
      String title,
      String owner,
      String parentGoalId,
      String status,
      LocalDate dueDate) {
    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }

  public record KeyResult(
      String id,
      String goalId,
      String title,
      BigDecimal targetValue,
      BigDecimal actualValue,
      BigDecimal weight,
      LocalDate factUpdatedOn) {}

  public record Snapshot(
      List<Department> departments, List<Goal> goals, List<KeyResult> keyResults) {
    public Snapshot {
      departments = List.copyOf(departments);
      goals = List.copyOf(goals);
      keyResults = List.copyOf(keyResults);
    }

    public Snapshot activeOnly() {
      var active = goals.stream().filter(Goal::active).toList();
      var ids = new HashSet<>(active.stream().map(Goal::id).toList());
      return new Snapshot(
          departments, active, keyResults.stream().filter(k -> ids.contains(k.goalId())).toList());
    }

    public Set<String> subtree(String unitId) {
      var ids = new HashSet<String>();
      if (departments.stream().noneMatch(d -> d.id().equals(unitId))) return ids;
      ids.add(unitId);
      boolean changed;
      do {
        changed = false;
        for (var d : departments) if (ids.contains(d.parentId())) changed |= ids.add(d.id());
      } while (changed);
      return ids;
    }

    public void validate() {
      Map<String, String> units = new HashMap<>();
      for (var d : departments) units.put(d.id(), d.parentId());
      Map<String, String> parents = new HashMap<>();
      for (var g : goals) {
        if (!units.containsKey(g.unitId()))
          throw new IllegalArgumentException("Missing department");
        parents.put(g.id(), g.parentGoalId());
      }
      checkTree(units);
      checkTree(parents);
      for (var k : keyResults)
        if (!parents.containsKey(k.goalId())) throw new IllegalArgumentException("Missing goal");
    }

    private static void checkTree(Map<String, String> parents) {
      for (var id : parents.keySet()) {
        Set<String> visited = new HashSet<>();
        for (String p = id; p != null; p = parents.get(p)) {
          if (!parents.containsKey(p)) throw new IllegalArgumentException("Missing parent");
          if (!visited.add(p)) throw new IllegalArgumentException("Hierarchy cycle");
        }
      }
    }
  }

  public record IssueKey(String ruleCode, String objectType, String objectId) {}

  public record Finding(
      String ruleCode,
      String severity,
      String objectType,
      String objectId,
      String goalId,
      String krId,
      String message,
      Map<String, Object> details) {
    public Finding {
      details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public IssueKey key() {
      return new IssueKey(ruleCode, objectType, objectId);
    }
  }
}
