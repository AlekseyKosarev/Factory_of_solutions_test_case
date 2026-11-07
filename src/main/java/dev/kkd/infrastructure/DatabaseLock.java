package dev.kkd.infrastructure;

import java.sql.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class DatabaseLock {
  private final DataSource source;

  public DatabaseLock(DataSource source) {
    this.source = source;
  }

  public <T> T withLock(long key, Supplier<T> work) {
    // Hold a session lock across the separate metadata and evaluation transactions.
    try (var connection = source.getConnection()) {
      lock(connection, "SELECT pg_advisory_lock(?)", key);
      try {
        return work.get();
      } finally {
        lock(connection, "SELECT pg_advisory_unlock(?)", key);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Database lock failed", e);
    }
  }

  private void lock(Connection connection, String sql, long key) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setLong(1, key);
      statement.execute();
    }
  }
}
