package nl.poldersmp.cinematics;

import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;

public final class IntroStore implements AutoCloseable {
  private final Database db;

  public IntroStore(java.nio.file.Path p) {
    db = new Database(p);
  }

  public record Saved(
      UUID player,
      String operation,
      String mode,
      boolean flight,
      boolean flying,
      float speed,
      String world,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {}

  public CompletableFuture<Void> migrate() {
    return db.submit(
        c -> {
          c.setAutoCommit(false);
          try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS intro_schema(version INTEGER PRIMARY KEY)");
            try (var r = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM intro_schema")) {
              if (r.getInt(1) > 1) throw new java.sql.SQLException("Newer intro schema");
            }
            s.execute(
                "CREATE TABLE IF NOT EXISTS intro_seen(player TEXT PRIMARY KEY,first_seen INTEGER"
                    + " NOT NULL,last_session TEXT NOT NULL)");
            s.execute(
                "CREATE TABLE IF NOT EXISTS intro_pending(player TEXT PRIMARY KEY,operation TEXT"
                    + " NOT NULL,mode TEXT NOT NULL,flight INTEGER NOT NULL,flying INTEGER NOT"
                    + " NULL,speed REAL NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT"
                    + " NULL,z REAL NOT NULL,yaw REAL NOT NULL,pitch REAL NOT NULL)");
            s.execute("INSERT OR IGNORE INTO intro_schema VALUES(1)");
            c.commit();
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
          return null;
        });
  }

  public CompletableFuture<Boolean> eligible(UUID p, String mode, String session) {
    return db.submit(
        c -> {
          try (var q =
              c.prepareStatement("SELECT first_seen,last_session FROM intro_seen WHERE player=?")) {
            q.setString(1, p.toString());
            try (var r = q.executeQuery()) {
              if (!r.next()) return true;
              return switch (mode) {
                case "EVERY_JOIN" -> true;
                case "NETWORK_SESSION" ->
                    session != null && !session.isBlank() ? !session.equals(r.getString(2)) : false;
                default -> false;
              };
            }
          }
        });
  }

  public CompletableFuture<Void> save(Saved s) {
    return db.submit(
        c -> {
          try (var q =
              c.prepareStatement("INSERT INTO intro_pending VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            Object[] v = {
              s.player.toString(),
              s.operation,
              s.mode,
              s.flight ? 1 : 0,
              s.flying ? 1 : 0,
              s.speed,
              s.world,
              s.x,
              s.y,
              s.z,
              s.yaw,
              s.pitch
            };
            for (int i = 0; i < v.length; i++) q.setObject(i + 1, v[i]);
            q.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<Saved> pending(UUID p) {
    return db.submit(
        c -> {
          try (var q = c.prepareStatement("SELECT * FROM intro_pending WHERE player=?")) {
            q.setString(1, p.toString());
            try (var r = q.executeQuery()) {
              return !r.next()
                  ? null
                  : new Saved(
                      p,
                      r.getString("operation"),
                      r.getString("mode"),
                      r.getBoolean("flight"),
                      r.getBoolean("flying"),
                      r.getFloat("speed"),
                      r.getString("world"),
                      r.getDouble("x"),
                      r.getDouble("y"),
                      r.getDouble("z"),
                      r.getFloat("yaw"),
                      r.getFloat("pitch"));
            }
          }
        });
  }

  public CompletableFuture<Void> finish(Saved s, boolean seen, String session) {
    return db.submit(
        c -> {
          c.setAutoCommit(false);
          try {
            int removed;
            try (var q =
                c.prepareStatement("DELETE FROM intro_pending WHERE player=? AND operation=?")) {
              q.setString(1, s.player.toString());
              q.setString(2, s.operation);
              removed = q.executeUpdate();
            }
            if (seen && removed == 1)
              try (var q =
                  c.prepareStatement(
                      "INSERT INTO intro_seen VALUES(?,1,?) ON CONFLICT(player) DO UPDATE SET"
                          + " first_seen=1,last_session=excluded.last_session")) {
                q.setString(1, s.player.toString());
                q.setString(2, session == null ? "" : session);
                q.executeUpdate();
              }
            c.commit();
            return null;
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        });
  }

  public void close() {
    db.close();
  }
}
