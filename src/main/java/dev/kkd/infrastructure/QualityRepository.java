package dev.kkd.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kkd.domain.Model.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QualityRepository {
  public record Run(
      String id,
      LocalDate snapshotDate,
      String status,
      String comparedToRunId,
      LocalDate previousDate,
      String ruleSetVersion,
      Long issuesCount) {}

  public record StoredIssue(
      UUID id,
      String ruleCode,
      String severity,
      String objectType,
      String objectId,
      String goalId,
      String unitId,
      String message,
      Map<String, Object> details) {}

  public record Page(List<StoredIssue> items, int limit, int offset, long total) {}

  private static final String RUN_SQL =
      """
      SELECT r.*,p.snapshot_date AS previous_date,
      CASE WHEN r.status='COMPLETED' THEN (SELECT count(*) FROM quality_issue i WHERE i.run_id=r.id) END AS issues_count
      FROM quality_run r LEFT JOIN quality_run p ON p.id=r.compared_to_run_id
      """;
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper mapper;

  public QualityRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.mapper = mapper;
  }

  public List<Run> runs() {
    return jdbc.query(RUN_SQL + " ORDER BY r.snapshot_date DESC,r.id DESC", this::run);
  }

  public Optional<Run> find(String id) {
    return jdbc.query(RUN_SQL + " WHERE r.id=?", this::run, id).stream().findFirst();
  }

  public Optional<Run> latest() {
    return jdbc
        .query(
            RUN_SQL + " WHERE r.status='COMPLETED' ORDER BY r.snapshot_date DESC,r.id DESC LIMIT 1",
            this::run)
        .stream()
        .findFirst();
  }

  private Run run(ResultSet r, int n) throws SQLException {
    return new Run(
        r.getString("id"),
        r.getObject("snapshot_date", LocalDate.class),
        r.getString("status"),
        r.getString("compared_to_run_id"),
        r.getObject("previous_date", LocalDate.class),
        r.getString("rule_set_version"),
        r.getObject("issues_count", Long.class));
  }

  public void abandonRunning() {
    jdbc.update(
        "UPDATE quality_run SET status='FAILED',failure_code='INTERRUPTED' WHERE status='RUNNING'");
  }

  public void deleteFailed(String id) {
    jdbc.update("DELETE FROM quality_run WHERE id=? AND status='FAILED'", id);
  }

  public void start(String id, LocalDate date, String previous) {
    jdbc.update(
        "INSERT INTO quality_run(id,snapshot_date,compared_to_run_id,status) VALUES"
            + " (?,?,?,'RUNNING')",
        id,
        date,
        previous);
  }

  public void complete(String id) {
    jdbc.update("UPDATE quality_run SET status='COMPLETED',completed_at=now() WHERE id=?", id);
  }

  public void fail(String id) {
    jdbc.update(
        "UPDATE quality_run SET status='FAILED',failure_code='EVALUATION_FAILED' WHERE id=?", id);
  }

  public void save(String runId, List<Finding> findings) {
    for (var f : findings)
      jdbc.update(
          """
          INSERT INTO quality_issue(id,run_id,rule_code,object_type,object_id,goal_id,kr_id,severity,message,details)
          VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
          """,
          UUID.randomUUID(),
          runId,
          f.ruleCode(),
          f.objectType(),
          f.objectId(),
          f.goalId(),
          f.krId(),
          f.severity(),
          f.message(),
          json(f.details()));
  }

  public List<Finding> findings(String runId) {
    return jdbc.query(
        "SELECT * FROM quality_issue WHERE run_id=?",
        (r, n) ->
            new Finding(
                r.getString("rule_code"),
                r.getString("severity"),
                r.getString("object_type"),
                r.getString("object_id"),
                r.getString("goal_id"),
                r.getString("kr_id"),
                r.getString("message"),
                details(r.getString("details"))),
        runId);
  }

  public Page issues(
      String runId, Set<String> units, String rule, String severity, int limit, int offset) {
    Map<String, Object> params = new HashMap<>();
    params.put("run", runId);
    params.put("limit", limit);
    params.put("offset", offset);
    String from =
        " FROM quality_issue i JOIN run_goal g ON g.run_id=i.run_id AND g.goal_id=i.goal_id WHERE"
            + " i.run_id=:run";
    if (units != null) {
      if (units.isEmpty()) return new Page(List.of(), limit, offset, 0);
      from += " AND g.unit_id IN (:units)";
      params.put("units", units);
    }
    if (rule != null) {
      from += " AND i.rule_code=:rule";
      params.put("rule", rule);
    }
    if (severity != null) {
      from += " AND i.severity=:severity";
      params.put("severity", severity);
    }
    long total =
        Objects.requireNonNull(named.queryForObject("SELECT count(*)" + from, params, Long.class));
    String order =
        " ORDER BY CASE i.severity WHEN 'critical' THEN 0 WHEN 'major' THEN 1 ELSE 2"
            + " END,i.rule_code,i.object_type,i.object_id LIMIT :limit OFFSET :offset";
    var rows =
        named.query(
            "SELECT i.*,g.unit_id" + from + order,
            params,
            (r, n) ->
                new StoredIssue(
                    r.getObject("id", UUID.class),
                    r.getString("rule_code"),
                    r.getString("severity"),
                    r.getString("object_type"),
                    r.getString("object_id"),
                    r.getString("goal_id"),
                    r.getString("unit_id"),
                    r.getString("message"),
                    details(r.getString("details"))));
    return new Page(rows, limit, offset, total);
  }

  private String json(Map<String, Object> value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encode issue", e);
    }
  }

  private Map<String, Object> details(String json) {
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decode issue", e);
    }
  }
}
