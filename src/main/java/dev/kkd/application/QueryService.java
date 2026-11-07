package dev.kkd.application;

import dev.kkd.application.Views.*;
import dev.kkd.domain.*;
import dev.kkd.domain.Model.*;
import dev.kkd.infrastructure.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
  private final QualityRepository quality;
  private final SnapshotRepository snapshots;
  private final MetricCalculator metrics = new MetricCalculator();
  private final DeltaCalculator deltas = new DeltaCalculator();

  public QueryService(QualityRepository quality, SnapshotRepository snapshots) {
    this.quality = quality;
    this.snapshots = snapshots;
  }

  public Runs runs() {
    return new Runs(
        quality.runs().stream()
            .map(
                r ->
                    new RunView(
                        r.id(),
                        r.status().toLowerCase(Locale.ROOT),
                        r.snapshotDate(),
                        comparison(r),
                        r.issuesCount()))
            .toList());
  }

  public Summary summary(String runId, String unitId) {
    var run = completed(runId);
    var snapshot = snapshots.load(runId);
    var unit = unit(snapshot, unitId);
    var goals = scopedGoals(snapshot, unitId);
    var current = scopedIssues(quality.findings(runId), goals);
    List<Goal> previousGoals = List.of();
    List<Finding> previous = null;
    if (run.comparedToRunId() != null) {
      previousGoals = scopedGoals(snapshots.load(run.comparedToRunId()), unitId);
      previous = scopedIssues(quality.findings(run.comparedToRunId()), previousGoals);
    }
    List<Severity> severities = new ArrayList<>();
    for (var code : List.of("critical", "major", "minor")) {
      long n = count(current, code);
      Long p = previous == null ? null : count(previous, code);
      severities.add(new Severity(code, n, p, p == null ? null : n - p));
    }
    var delta = deltas.calculate(current, previous);
    return new Summary(
        new RunRef(run.id(), run.snapshotDate()),
        comparison(run),
        new Unit(unit.id(), unit.name()),
        metrics.calculate(goals, current, previousGoals, previous),
        severities,
        new Delta(delta.added(), delta.persisting(), delta.resolved()));
  }

  public IssuePage issues(
      String runId, String unitId, String rule, String severity, int limit, int offset) {
    completed(runId);
    Set<String> units = null;
    if (unitId != null) {
      var snapshot = snapshots.load(runId);
      unit(snapshot, unitId);
      units = snapshot.subtree(unitId);
    }
    var page = quality.issues(runId, units, rule, severity, limit, offset);
    return new IssuePage(
        page.items().stream()
            .map(
                i ->
                    new Issue(
                        i.id(),
                        i.ruleCode(),
                        i.severity(),
                        i.objectType(),
                        i.objectId(),
                        i.goalId(),
                        i.unitId(),
                        i.message(),
                        i.details()))
            .toList(),
        page.limit(),
        page.offset(),
        page.total());
  }

  private long count(List<Finding> issues, String severity) {
    return issues.stream().filter(i -> i.severity().equals(severity)).count();
  }

  private List<Goal> scopedGoals(Snapshot s, String unitId) {
    var units = s.subtree(unitId);
    return s.goals().stream().filter(g -> units.contains(g.unitId())).toList();
  }

  private List<Finding> scopedIssues(List<Finding> issues, List<Goal> goals) {
    var ids = goals.stream().map(Goal::id).collect(Collectors.toSet());
    return issues.stream().filter(i -> ids.contains(i.goalId())).toList();
  }

  private Department unit(Snapshot s, String id) {
    return s.departments().stream()
        .filter(d -> d.id().equals(id))
        .findFirst()
        .orElseThrow(
            () ->
                new ServiceException(
                    404,
                    "UNIT_NOT_FOUND",
                    "Department " + id + " was not found in the selected run"));
  }

  private QualityRepository.Run completed(String id) {
    var run =
        quality
            .find(id)
            .orElseThrow(
                () -> new ServiceException(404, "RUN_NOT_FOUND", "Run " + id + " was not found"));
    if (!run.status().equals("COMPLETED"))
      throw new ServiceException(422, "RUN_NOT_COMPLETED", "Run " + id + " is not completed");
    return run;
  }

  private RunRef comparison(QualityRepository.Run run) {
    return run.comparedToRunId() == null
        ? null
        : new RunRef(run.comparedToRunId(), run.previousDate());
  }
}
