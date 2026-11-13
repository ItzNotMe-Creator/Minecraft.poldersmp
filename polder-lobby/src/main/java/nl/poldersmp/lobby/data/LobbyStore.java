package nl.poldersmp.lobby.data;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;

public final class LobbyStore implements AutoCloseable {
  private final Database db;

  public LobbyStore(java.nio.file.Path p) {
    db = new Database(p);
  }

  private <T> CompletableFuture<T> tx(Database.Work<T> work) {
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

  private static void bind(PreparedStatement p, Object... v) throws SQLException {
    for (int i = 0; i < v.length; i++)
      p.setObject(i + 1, v[i] instanceof UUID ? v[i].toString() : v[i]);
  }

  private static int update(Connection c, String sql, Object... v) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, v);
      return p.executeUpdate();
    }
  }

  private static List<List<Object>> rows(Connection c, String sql, Object... v)
      throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, v);
      try (var r = p.executeQuery()) {
        List<List<Object>> out = new ArrayList<>();
        while (r.next()) {
          List<Object> a = new ArrayList<>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) a.add(r.getObject(i));
          out.add(a);
        }
        return out;
      }
    }
  }

  private static long number(Connection c, String sql, Object... v) throws SQLException {
    var r = rows(c, sql, v);
    return r.isEmpty() ? 0 : ((Number) r.getFirst().getFirst()).longValue();
  }

  public CompletableFuture<Void> migrate() {
    return tx(
        c -> {
          update(c, "CREATE TABLE IF NOT EXISTS lobby_schema(version INTEGER PRIMARY KEY)");
          long v = number(c, "SELECT COALESCE(MAX(version),0) FROM lobby_schema");
          if (v > 1) throw new SQLException("Newer lobby schema");
          if (v == 0) {
            try (var in = LobbyStore.class.getResourceAsStream("/db/lobby/V1__lobby.sql")) {
              if (in == null) throw new SQLException("Missing lobby migration");
              for (String sql :
                  new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";"))
                if (!sql.isBlank()) update(c, sql);
            }
            update(c, "INSERT INTO lobby_schema VALUES(1)");
          }
          update(
              c,
              "UPDATE lobby_events SET status='INTERRUPTED' WHERE status IN ('WARNING','ACTIVE')");
          return null;
        });
  }

  public CompletableFuture<Void> join(UUID p, String name) {
    return tx(
        c -> {
          update(
              c,
              "INSERT INTO lobby_players(player,name) VALUES(?,?) ON CONFLICT(player) DO UPDATE SET"
                  + " name=excluded.name",
              p,
              name);
          return null;
        });
  }

  public record Profile(
      UUID player,
      String name,
      long tokens,
      long playtime,
      int streak,
      List<String> achievements,
      Set<String> unlocks,
      Map<String, String> selections,
      Map<String, Long> records) {}

  public CompletableFuture<Profile> profile(UUID p) {
    return db.submit(
        c -> {
          var r =
              rows(c, "SELECT name,tokens,playtime,streak FROM lobby_players WHERE player=?", p);
          if (r.isEmpty()) return null;
          List<String> earned = new ArrayList<>();
          for (var row :
              rows(c, "SELECT source FROM lobby_rewards WHERE player=? ORDER BY created", p))
            earned.add((String) row.get(0));
          Set<String> unlocks = new HashSet<>();
          for (var row : rows(c, "SELECT cosmetic FROM lobby_unlocks WHERE player=?", p))
            unlocks.add((String) row.get(0));
          Map<String, String> selected = new HashMap<>();
          for (var row :
              rows(c, "SELECT category,cosmetic FROM lobby_selections WHERE player=?", p))
            selected.put((String) row.get(0), (String) row.get(1));
          Map<String, Long> records = new HashMap<>();
          for (var row : rows(c, "SELECT course,millis FROM lobby_records WHERE player=?", p))
            records.put((String) row.get(0), ((Number) row.get(1)).longValue());
          var row = r.getFirst();
          return new Profile(
              p,
              (String) row.get(0),
              ((Number) row.get(1)).longValue(),
              ((Number) row.get(2)).longValue(),
              ((Number) row.get(3)).intValue(),
              List.copyOf(earned),
              Set.copyOf(unlocks),
              Map.copyOf(selected),
              Map.copyOf(records));
        });
  }

  public CompletableFuture<Void> time(Map<UUID, Long> seconds) {
    return tx(
        c -> {
          for (var e : seconds.entrySet())
            if (e.getValue() > 0)
              update(
                  c,
                  "UPDATE lobby_players SET playtime=playtime+? WHERE player=?",
                  e.getValue(),
                  e.getKey());
          return null;
        });
  }

  private static boolean reward(
      Connection c, UUID player, String source, long tokens, String unlock, long now)
      throws SQLException {
    if (tokens < 0 || tokens > 1000000) throw new SQLException("Invalid cosmetic token reward");
    if (update(c, "INSERT OR IGNORE INTO lobby_rewards VALUES(?,?,?)", player, source, now) == 0)
      return false;
    if (update(c, "UPDATE lobby_players SET tokens=tokens+? WHERE player=?", tokens, player) != 1)
      throw new SQLException("Player not initialized");
    if (unlock != null && !unlock.isBlank())
      update(c, "INSERT OR IGNORE INTO lobby_unlocks VALUES(?,?)", player, unlock);
    return true;
  }

  public CompletableFuture<Boolean> reward(
      UUID player, String source, long tokens, String unlock, long now) {
    return tx(
        c -> {
          if (number(c, "SELECT COUNT(*) FROM lobby_players WHERE player=?", player) != 1)
            throw new SQLException("Player not initialized");
          return reward(c, player, source, tokens, unlock, now);
        });
  }

  public CompletableFuture<Integer> daily(
      UUID p, LocalDate date, long tokens, String unlock, long now) {
    return tx(
        c -> {
          var r = rows(c, "SELECT last_daily,streak FROM lobby_players WHERE player=?", p);
          if (r.isEmpty()) throw new SQLException("Player not initialized");
          String prior = (String) r.getFirst().get(0);
          if (!prior.isEmpty() && !date.isAfter(LocalDate.parse(prior)))
            throw new SQLException("Daily reward already claimed or clock moved backwards");
          int streak =
              !prior.isEmpty() && LocalDate.parse(prior).plusDays(1).equals(date)
                  ? Math.addExact(((Number) r.getFirst().get(1)).intValue(), 1)
                  : 1;
          if (!reward(c, p, "daily:" + date, tokens, unlock, now))
            throw new SQLException("Already claimed");
          update(
              c,
              "UPDATE lobby_players SET last_daily=?,streak=? WHERE player=?",
              date.toString(),
              streak,
              p);
          return streak;
        });
  }

  public CompletableFuture<Boolean> buy(UUID p, String cosmetic, String category, long cost) {
    return tx(
        c -> {
          if (cost < 0) throw new SQLException("Negative cosmetic price");
          boolean owned =
              number(
                      c,
                      "SELECT COUNT(*) FROM lobby_unlocks WHERE player=? AND cosmetic=?",
                      p,
                      cosmetic)
                  > 0;
          if (!owned) {
            if (update(
                    c,
                    "UPDATE lobby_players SET tokens=tokens-? WHERE player=? AND tokens>=?",
                    cost,
                    p,
                    cost)
                != 1) throw new SQLException("Not enough cosmetic tokens");
            update(c, "INSERT INTO lobby_unlocks VALUES(?,?)", p, cosmetic);
          }
          update(
              c,
              "INSERT INTO lobby_selections VALUES(?,?,?) ON CONFLICT(player,category) DO UPDATE"
                  + " SET cosmetic=excluded.cosmetic",
              p,
              category,
              cosmetic);
          return !owned;
        });
  }

  public CompletableFuture<Void> clear(UUID p) {
    return tx(
        c -> {
          update(c, "DELETE FROM lobby_selections WHERE player=?", p);
          return null;
        });
  }

  public CompletableFuture<Boolean> finish(
      UUID run, UUID p, String course, long millis, long tokens, long now) {
    return tx(
        c -> {
          if (millis < 1) throw new SQLException("Invalid time");
          if (update(
                  c,
                  "INSERT OR IGNORE INTO lobby_finishes VALUES(?,?,?,?,?)",
                  run,
                  p,
                  course,
                  millis,
                  now)
              == 0) return false;
          boolean best =
              number(c, "SELECT COUNT(*) FROM lobby_records WHERE player=? AND course=?", p, course)
                      == 0
                  || millis
                      < number(
                          c,
                          "SELECT millis FROM lobby_records WHERE player=? AND course=?",
                          p,
                          course);
          update(
              c,
              "INSERT INTO lobby_records VALUES(?,?,?) ON CONFLICT(player,course) DO UPDATE SET"
                  + " millis=MIN(millis,excluded.millis)",
              p,
              course,
              millis);
          reward(c, p, "parkour:" + course, tokens, "", now);
          return best;
        });
  }

  public CompletableFuture<List<String>> top(String course) {
    return db.submit(
        c -> {
          List<String> out = new ArrayList<>();
          String sql =
              course == null
                  ? "SELECT name,playtime FROM lobby_players ORDER BY playtime DESC,player LIMIT 10"
                  : "SELECT p.name,r.millis FROM lobby_records r JOIN lobby_players p ON"
                      + " p.player=r.player WHERE course=? ORDER BY r.millis,r.player LIMIT 10";
          for (var r : rows(c, sql, course == null ? new Object[] {} : new Object[] {course}))
            out.add(r.get(0) + " • " + r.get(1) + (course == null ? " seconds" : " ms"));
          return out;
        });
  }

  public CompletableFuture<String> setting(String key) {
    return db.submit(
        c -> {
          var r = rows(c, "SELECT value FROM lobby_settings WHERE name=?", key);
          return r.isEmpty() ? null : (String) r.getFirst().get(0);
        });
  }

  public CompletableFuture<Void> setting(String key, String value) {
    return tx(
        c -> {
          update(
              c,
              "INSERT INTO lobby_settings VALUES(?,?) ON CONFLICT(name) DO UPDATE SET"
                  + " value=excluded.value",
              key,
              value);
          return null;
        });
  }

  public record Event(UUID id, String type, String course, String status, long starts, long ends) {}

  public CompletableFuture<Void> event(Event e) {
    return tx(
        c -> {
          update(
              c,
              "INSERT INTO lobby_events VALUES(?,?,?,?,?,?)",
              e.id,
              e.type,
              e.course,
              e.status,
              e.starts,
              e.ends);
          return null;
        });
  }

  public CompletableFuture<Void> status(UUID id, String status) {
    return tx(
        c -> {
          update(c, "UPDATE lobby_events SET status=? WHERE id=?", status, id);
          return null;
        });
  }

  public CompletableFuture<Void> point(UUID event, UUID player, String target, long score) {
    return tx(
        c -> {
          if (number(
                  c,
                  "SELECT COUNT(*) FROM lobby_events WHERE id=? AND status='ACTIVE' AND ends>?",
                  event,
                  System.currentTimeMillis())
              == 1)
            update(
                c,
                "INSERT INTO lobby_event_points VALUES(?,?,?,?) ON CONFLICT(event,player,target) DO"
                    + " UPDATE SET score=MIN(score,excluded.score)",
                event,
                player,
                target,
                score);
          return null;
        });
  }

  public CompletableFuture<List<UUID>> winners(UUID event, boolean race, int minimum) {
    return tx(
        c -> {
          if (update(
                  c,
                  "UPDATE lobby_events SET status='FINISHED' WHERE id=? AND status='ACTIVE'",
                  event)
              != 1) return List.of();
          var all =
              rows(
                  c,
                  "SELECT player,"
                      + (race ? "MIN(score)" : "COUNT(*)")
                      + " points FROM lobby_event_points WHERE event=? GROUP BY player ORDER BY"
                      + " points "
                      + (race ? "ASC" : "DESC")
                      + ",player",
                  event);
          if (all.size() < minimum) return List.of();
          List<UUID> out = new ArrayList<>();
          for (int i = 0; i < Math.min(3, all.size()); i++) {
            UUID p = UUID.fromString((String) all.get(i).get(0));
            reward(
                c,
                p,
                "event:" + event,
                i == 0 ? 10 : 3,
                i == 0 ? "title_champion" : "",
                System.currentTimeMillis());
            out.add(p);
          }
          return out;
        });
  }

  public CompletableFuture<List<String>> eventTop(UUID event, boolean race) {
    return db.submit(
        c -> {
          List<String> out = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT p.name,"
                      + (race ? "MIN(e.score)" : "COUNT(*)")
                      + " points FROM lobby_event_points e JOIN lobby_players p ON"
                      + " p.player=e.player WHERE e.event=? GROUP BY e.player ORDER BY points "
                      + (race ? "ASC" : "DESC")
                      + ",e.player LIMIT 10",
                  event)) out.add(r.get(0) + " • " + r.get(1) + (race ? " ms" : " points"));
          return out;
        });
  }

  public void close() {
    db.close();
  }
}
