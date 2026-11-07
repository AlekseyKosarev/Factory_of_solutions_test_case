package dev.kkd.infrastructure;

import dev.kkd.domain.Model.*;
import java.sql.*;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SnapshotRepository {
  private final JdbcTemplate jdbc;

  public SnapshotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Snapshot live() {
    return new Snapshot(
        jdbc.query(
            "SELECT id,name,parent_id FROM department ORDER BY id",
            (r, n) -> new Department(r.getString(1), r.getString(2), r.getString(3))),
        jdbc.query(
            "SELECT id,unit_id,title,owner,parent_goal_id,status,due_date FROM goal ORDER BY id",
            this::goal),
        jdbc.query(
            "SELECT id,goal_id,title,target_value,actual_value,weight,fact_updated_on FROM"
                + " key_result ORDER BY id",
            this::kr));
  }

  public Snapshot load(String runId) {
    return new Snapshot(
        jdbc.query(
            "SELECT unit_id,name,parent_unit_id FROM run_department WHERE run_id=? ORDER BY"
                + " unit_id",
            (r, n) -> new Department(r.getString(1), r.getString(2), r.getString(3)),
            runId),
        jdbc.query(
            "SELECT goal_id,unit_id,title,owner,parent_goal_id,status,due_date FROM run_goal WHERE"
                + " run_id=? ORDER BY goal_id",
            this::goal,
            runId),
        jdbc.query(
            "SELECT kr_id,goal_id,title,target_value,actual_value,weight,fact_updated_on FROM"
                + " run_key_result WHERE run_id=? ORDER BY kr_id",
            this::kr,
            runId));
  }

  public void copy(String runId) {
    jdbc.update("INSERT INTO run_department SELECT ?,id,name,parent_id FROM department", runId);
    jdbc.update(
        "INSERT INTO run_goal SELECT ?,id,unit_id,title,owner,parent_goal_id,status,due_date FROM"
            + " goal",
        runId);
    jdbc.update(
        "INSERT INTO run_key_result SELECT"
            + " ?,id,goal_id,title,target_value,actual_value,weight,fact_updated_on FROM"
            + " key_result",
        runId);
  }

  private Goal goal(ResultSet r, int n) throws SQLException {
    return new Goal(
        r.getString(1),
        r.getString(2),
        r.getString(3),
        r.getString(4),
        r.getString(5),
        r.getString(6),
        r.getObject(7, LocalDate.class));
  }

  private KeyResult kr(ResultSet r, int n) throws SQLException {
    return new KeyResult(
        r.getString(1),
        r.getString(2),
        r.getString(3),
        r.getBigDecimal(4),
        r.getBigDecimal(5),
        r.getBigDecimal(6),
        r.getObject(7, LocalDate.class));
  }
}
