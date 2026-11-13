package nl.poldersmp.core.market;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;

/** All calls execute on Database's worker; no server-thread database I/O. */
public final class Sql {
  private Sql() {}

  public static <T> CompletableFuture<T> tx(Database db, Database.Work<T> work) {
    return db.submit(
        c -> {
          c.setAutoCommit(false);
          try {
            T v = work.run(c);
            c.commit();
            return v;
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        });
  }

  public static void bind(PreparedStatement p, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++)
      p.setObject(i + 1, values[i] instanceof UUID ? values[i].toString() : values[i]);
  }

  public static int update(Connection c, String sql, Object... v) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, v);
      return p.executeUpdate();
    }
  }

  public static long number(Connection c, String sql, Object... v) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, v);
      try (var r = p.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  public static List<List<Object>> rows(Connection c, String sql, Object... v) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, v);
      try (var r = p.executeQuery()) {
        List<List<Object>> rows = new ArrayList<>();
        while (r.next()) {
          List<Object> row = new ArrayList<>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getObject(i));
          rows.add(row);
        }
        return rows;
      }
    }
  }

  public static void migrate(Connection c, String name) throws Exception {
    try (var q = c.createStatement()) {
      q.execute("CREATE TABLE IF NOT EXISTS " + name + "_schema(version INTEGER PRIMARY KEY)");
    }
    long version = number(c, "SELECT COALESCE(MAX(version),0) FROM " + name + "_schema");
    if (version > 1) throw new SQLException("Newer " + name + " schema");
    if (version == 0) {
      try (var in = Sql.class.getResourceAsStream("/db/" + name + "/V1__" + name + ".sql")) {
        if (in == null) throw new SQLException("Missing migration");
        for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
          if (!sql.isBlank())
            try (var q = c.createStatement()) {
              q.execute(sql);
            }
      }
      update(c, "INSERT INTO " + name + "_schema VALUES(1)");
    }
  }

  public static void require(boolean condition, String message) throws SQLException {
    if (!condition) throw new SQLException(message);
  }
}
