package nl.poldersmp.core.gameplay;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import nl.poldersmp.common.Database;

public final class Discoveries implements AutoCloseable {
  private final Database db;

  public Discoveries(Path path) {
    db = new Database(path);
  }

  public CompletableFuture<Void> initialize() {
    return db.submit(
        c -> {
          c.setAutoCommit(false);
          try (var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS armor_schema(version INTEGER PRIMARY KEY)");
            try (var rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM armor_schema")) {
              if (rs.getInt(1) > 1) throw new java.sql.SQLException("Newer armor schema");
            }
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS discoveries(player TEXT NOT NULL,trim TEXT NOT"
                    + " NULL,PRIMARY KEY(player,trim))");
            s.executeUpdate("INSERT OR IGNORE INTO armor_schema VALUES(1)");
            c.commit();
            return null;
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        });
  }

  public CompletableFuture<Void> discover(UUID player, String trim) {
    return db.submit(
        c -> {
          try (var p = c.prepareStatement("INSERT OR IGNORE INTO discoveries VALUES(?,?)")) {
            p.setString(1, player.toString());
            p.setString(2, trim);
            p.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<Set<String>> collection(UUID player) {
    return db.submit(
        c -> {
          Set<String> found = new TreeSet<>();
          try (var p = c.prepareStatement("SELECT trim FROM discoveries WHERE player=?")) {
            p.setString(1, player.toString());
            try (var rs = p.executeQuery()) {
              while (rs.next()) found.add(rs.getString(1));
            }
          }
          return Set.copyOf(found);
        });
  }

  public CompletableFuture<List<String>> top() {
    return db.submit(
        c -> {
          List<String> rows = new ArrayList<>();
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT player,COUNT(*) AS total FROM discoveries GROUP BY player ORDER BY"
                          + " total DESC,player LIMIT 10")) {
            while (rs.next()) rows.add(rs.getString(1) + " : " + rs.getInt(2));
          }
          return rows;
        });
  }

  public void close() {
    db.close();
  }
}
