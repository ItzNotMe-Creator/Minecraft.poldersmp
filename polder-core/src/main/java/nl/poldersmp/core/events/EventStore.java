package nl.poldersmp.core.events;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.competition.Economy;
import nl.poldersmp.core.loot.LootStore;

public final class EventStore {
  private final Database db;
  private final Economy economy;

  public EventStore(Database db, Economy economy) {
    this.db = db;
    this.economy = economy;
  }

  private <T> CompletableFuture<T> tx(Database.Work<T> work) {
    return db.submit(
        c -> {
          c.setAutoCommit(false);
          try {
            T r = work.run(c);
            c.commit();
            return r;
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        });
  }

  static void bind(PreparedStatement p, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++)
      p.setObject(i + 1, args[i] instanceof UUID ? args[i].toString() : args[i]);
  }

  static int update(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, args);
      return p.executeUpdate();
    }
  }

  static long count(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, args);
      try (var rs = p.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0;
      }
    }
  }

  public CompletableFuture<Void> migrate() {
    return tx(
        c -> {
          if (count(
                  c,
                  "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND"
                      + " name='world_event_schema'")
              > 0) {
            if (count(c, "SELECT MAX(version) FROM world_event_schema") != 1)
              throw new SQLException("Unsupported world-event schema");
            return null;
          }
          try (var in = EventStore.class.getResourceAsStream("/db/events/V1__world_events.sql")) {
            if (in == null) throw new SQLException("Missing event migration");
            for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
              if (!sql.isBlank())
                try (var s = c.createStatement()) {
                  s.executeUpdate(sql);
                }
          }
          return null;
        });
  }

  public record State(
      UUID id,
      EventRules.Type type,
      String status,
      UUID world,
      int x,
      int y,
      int z,
      long starts,
      long expires,
      int stage,
      double health,
      UUID winner,
      long cleanupAfter,
      String settings,
      long escrow,
      UUID treasury,
      boolean preserve) {
    public State phase(String status, int stage, double health) {
      return new State(
          id,
          type,
          status,
          world,
          x,
          y,
          z,
          starts,
          expires,
          stage,
          health,
          winner,
          cleanupAfter,
          settings,
          escrow,
          treasury,
          preserve);
    }
  }

  public record Chest(int index, int x, int y, int z, UUID owner) {}

  public record Mob(UUID id, String role, int wave, int state) {}

  public record Award(
      String id,
      UUID event,
      UUID owner,
      String kind,
      String tier,
      UUID receipt,
      byte[] payload,
      long amount) {}

  private static UUID uuid(String s) {
    return s == null ? null : UUID.fromString(s);
  }

  private static State state(ResultSet r) throws SQLException {
    return new State(
        uuid(r.getString("id")),
        EventRules.Type.valueOf(r.getString("type")),
        r.getString("status"),
        uuid(r.getString("world")),
        r.getInt("x"),
        r.getInt("y"),
        r.getInt("z"),
        r.getLong("starts"),
        r.getLong("expires"),
        r.getInt("stage"),
        r.getDouble("health"),
        uuid(r.getString("claimed_by")),
        r.getLong("cleanup_after"),
        r.getString("settings"),
        r.getLong("escrow"),
        uuid(r.getString("treasury")),
        r.getInt("preserve_structure") == 1);
  }

  public CompletableFuture<State> active() {
    return db.submit(
        c -> {
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT * FROM world_events WHERE status NOT IN ('FINISHED','CANCELED') LIMIT"
                          + " 1")) {
            return rs.next() ? state(rs) : null;
          }
        });
  }

  public CompletableFuture<Void> plan(
      State e, List<LootStore.BlockPlan> blocks, List<Chest> chests) {
    return tx(
        c -> {
          economy.debit(c, e.treasury(), e.escrow());
          update(
              c,
              "INSERT INTO"
                  + " world_events(id,type,status,world,x,y,z,starts,expires,stage,health,settings,escrow,treasury)"
                  + " VALUES(?,?,'PLANNED',?,?,?,?,?,?,?,?,?,?,?)",
              e.id(),
              e.type().name(),
              e.world(),
              e.x(),
              e.y(),
              e.z(),
              e.starts(),
              e.expires(),
              e.stage(),
              e.health(),
              e.settings(),
              e.escrow(),
              e.treasury());
          for (var b : blocks)
            update(
                c,
                "INSERT INTO world_event_blocks VALUES(?,?,?,?,?,?)",
                e.id(),
                b.x(),
                b.y(),
                b.z(),
                b.original(),
                b.placed());
          for (var chest : chests)
            update(
                c,
                "INSERT INTO world_event_chests(event,chest,x,y,z) VALUES(?,?,?,?,?)",
                e.id(),
                chest.index(),
                chest.x(),
                chest.y(),
                chest.z());
          return null;
        });
  }

  public CompletableFuture<List<LootStore.BlockPlan>> blocks(UUID event) {
    return db.submit(
        c -> {
          List<LootStore.BlockPlan> out = new ArrayList<>();
          try (var p =
              c.prepareStatement(
                  "SELECT x,y,z,original,placed FROM world_event_blocks WHERE event=?")) {
            bind(p, event);
            try (var r = p.executeQuery()) {
              while (r.next())
                out.add(
                    new LootStore.BlockPlan(
                        r.getInt(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5)));
            }
          }
          return out;
        });
  }

  public CompletableFuture<List<Chest>> chests(UUID event) {
    return db.submit(
        c -> {
          List<Chest> out = new ArrayList<>();
          try (var p =
              c.prepareStatement(
                  "SELECT chest,x,y,z,owner FROM world_event_chests WHERE event=? ORDER BY"
                      + " chest")) {
            bind(p, event);
            try (var r = p.executeQuery()) {
              while (r.next())
                out.add(
                    new Chest(
                        r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), uuid(r.getString(5))));
            }
          }
          return out;
        });
  }

  public CompletableFuture<Map<UUID, Double>> damage(UUID event) {
    return db.submit(
        c -> {
          Map<UUID, Double> out = new HashMap<>();
          try (var p =
              c.prepareStatement("SELECT player,damage FROM world_event_damage WHERE event=?")) {
            bind(p, event);
            try (var r = p.executeQuery()) {
              while (r.next()) out.put(uuid(r.getString(1)), r.getDouble(2));
            }
          }
          return out;
        });
  }

  private static void damage(Connection c, UUID event, Map<UUID, Double> totals)
      throws SQLException {
    for (var entry : totals.entrySet()) {
      if (!Double.isFinite(entry.getValue()) || entry.getValue() < 0)
        throw new SQLException("Invalid damage");
      update(
          c,
          "INSERT INTO world_event_damage VALUES(?,?,?) ON CONFLICT(event,player) DO UPDATE SET"
              + " damage=MAX(damage,excluded.damage)",
          event,
          entry.getKey(),
          entry.getValue());
    }
  }

  public CompletableFuture<Void> checkpoint(State e, Map<UUID, Double> totals) {
    return tx(
        c -> {
          update(
              c,
              "UPDATE world_events SET health=? WHERE id=? AND status='ACTIVE'",
              e.health(),
              e.id());
          damage(c, e.id(), totals);
          return null;
        });
  }

  public CompletableFuture<Boolean> phase(
      UUID id,
      String expected,
      String status,
      int stage,
      double health,
      List<Award> awards,
      Map<UUID, Double> totals) {
    return tx(
        c -> {
          if (update(
                  c,
                  "UPDATE world_events SET status=?,stage=?,health=? WHERE id=? AND status=?",
                  status,
                  stage,
                  health,
                  id,
                  expected)
              != 1) return false;
          damage(c, id, totals);
          awards(c, awards);
          if (status.equals("LOOT"))
            update(c, "UPDATE world_event_entities SET dead=1 WHERE event=? AND role='BOSS'", id);
          return true;
        });
  }

  private static void awards(Connection c, List<Award> awards) throws SQLException {
    for (var a : awards) {
      if (a.amount() < 0) throw new SQLException("Negative reward");
      update(
          c,
          "INSERT OR IGNORE INTO"
              + " world_event_awards(id,event,owner,kind,tier,receipt,payload,amount)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          a.id(),
          a.event(),
          a.owner(),
          a.kind(),
          a.tier(),
          a.receipt(),
          a.payload(),
          a.amount());
    }
    if (!awards.isEmpty()) {
      UUID event = awards.getFirst().event();
      if (count(
              c,
              "SELECT COALESCE(SUM(amount),0) FROM world_event_awards WHERE event=? AND"
                  + " kind='CASH'",
              event)
          > count(c, "SELECT escrow FROM world_events WHERE id=?", event))
        throw new SQLException("Reward exceeds reserved treasury");
    }
  }

  public CompletableFuture<Boolean> claim(
      State e, int chest, UUID player, List<Award> awards, long cleanup, boolean preserve) {
    return claimAt(e, chest, player, awards, cleanup, preserve, System.currentTimeMillis());
  }

  public CompletableFuture<Boolean> claimAt(
      State e,
      int chest,
      UUID player,
      List<Award> awards,
      long cleanup,
      boolean preserve,
      long now) {
    return tx(
        c -> {
          if (count(
                      c,
                      "SELECT COUNT(*) FROM world_events WHERE id=? AND status='LOOT' AND"
                          + " expires>?",
                      e.id(),
                      now)
                  != 1
              || update(
                      c,
                      "UPDATE world_event_chests SET owner=? WHERE event=? AND chest=? AND owner IS"
                          + " NULL",
                      player,
                      e.id(),
                      chest)
                  != 1) return false;
          awards(c, awards);
          if (count(
                  c,
                  "SELECT COUNT(*) FROM world_event_chests WHERE event=? AND owner IS NULL",
                  e.id())
              == 0) end(c, e, player, cleanup, preserve);
          return true;
        });
  }

  public CompletableFuture<Boolean> finish(
      State e,
      UUID winner,
      List<Award> awards,
      Map<UUID, Double> totals,
      long cleanup,
      boolean preserve) {
    return tx(
        c -> {
          if (count(
                  c,
                  "SELECT COUNT(*) FROM world_events WHERE id=? AND status IN"
                      + " ('WARNING','ACTIVE','LOOT','PLANNED')",
                  e.id())
              != 1) return false;
          damage(c, e.id(), totals);
          update(c, "UPDATE world_events SET health=? WHERE id=?", e.health(), e.id());
          if (e.health() <= 0)
            update(
                c,
                "UPDATE world_event_entities SET dead=1 WHERE event=? AND role='BOSS' AND dead=0",
                e.id());
          awards(c, awards);
          end(c, e, winner, cleanup, preserve);
          return true;
        });
  }

  private static void end(Connection c, State e, UUID winner, long cleanup, boolean preserve)
      throws SQLException {
    long spent =
        count(
            c,
            "SELECT COALESCE(SUM(amount),0) FROM world_event_awards WHERE event=? AND kind='CASH'",
            e.id());
    if (spent > e.escrow()) throw new SQLException("Overspent event escrow");
    long refund = e.escrow() - spent;
    if (refund > 0)
      update(
          c,
          "INSERT OR IGNORE INTO world_event_awards(id,event,owner,kind,amount)"
              + " VALUES(?,?,?,'CASH',?)",
          "event:" + e.id() + ":refund",
          e.id(),
          e.treasury(),
          refund);
    update(
        c,
        "UPDATE world_events SET status='CLEANUP',claimed_by=?,cleanup_after=?,preserve_structure=?"
            + " WHERE id=?",
        winner,
        cleanup,
        preserve ? 1 : 0,
        e.id());
  }

  public CompletableFuture<Void> archive(UUID id) {
    return tx(
        c -> {
          update(c, "UPDATE world_event_entities SET dead=2 WHERE event=? AND dead=0", id);
          update(
              c,
              "UPDATE world_events SET status=CASE WHEN claimed_by IS NULL THEN 'CANCELED' ELSE"
                  + " 'FINISHED' END WHERE id=? AND status='CLEANUP'",
              id);
          return null;
        });
  }

  public CompletableFuture<Void> mob(UUID event, UUID mob, String role, int wave) {
    return tx(
        c -> {
          update(
              c,
              "INSERT OR IGNORE INTO world_event_entities(event,entity,role,wave) VALUES(?,?,?,?)",
              event,
              mob,
              role,
              wave);
          return null;
        });
  }

  public CompletableFuture<Boolean> dead(UUID mob) {
    return tx(
        c ->
            update(c, "UPDATE world_event_entities SET dead=1 WHERE entity=? AND dead=0", mob)
                == 1);
  }

  public CompletableFuture<Void> retire(UUID mob) {
    return tx(
        c -> {
          update(c, "UPDATE world_event_entities SET dead=2 WHERE entity=? AND dead=0", mob);
          return null;
        });
  }

  public CompletableFuture<Void> death(UUID mob, UUID event, Map<UUID, Double> totals) {
    return tx(
        c -> {
          update(c, "UPDATE world_event_entities SET dead=1 WHERE entity=? AND dead=0", mob);
          damage(c, event, totals);
          return null;
        });
  }

  public CompletableFuture<List<Mob>> mobs(UUID event) {
    return db.submit(
        c -> {
          List<Mob> out = new ArrayList<>();
          try (var p =
              c.prepareStatement(
                  "SELECT entity,role,wave,dead FROM world_event_entities WHERE event=?")) {
            bind(p, event);
            try (var r = p.executeQuery()) {
              while (r.next())
                out.add(new Mob(uuid(r.getString(1)), r.getString(2), r.getInt(3), r.getInt(4)));
            }
          }
          return out;
        });
  }

  public CompletableFuture<List<Award>> pending() {
    return db.submit(
        c -> {
          List<Award> out = new ArrayList<>();
          try (var s = c.createStatement();
              var r =
                  s.executeQuery(
                      "SELECT * FROM world_event_awards WHERE state='PENDING' AND kind!='CASH'"
                          + " ORDER BY attempts,id LIMIT 100")) {
            while (r.next())
              out.add(
                  new Award(
                      r.getString("id"),
                      uuid(r.getString("event")),
                      uuid(r.getString("owner")),
                      r.getString("kind"),
                      r.getString("tier"),
                      uuid(r.getString("receipt")),
                      r.getBytes("payload"),
                      r.getLong("amount")));
          }
          return out;
        });
  }

  public CompletableFuture<Void> acknowledge(String id, boolean success) {
    return tx(
        c -> {
          update(
              c,
              "UPDATE world_event_awards SET state=?,attempts=attempts+1 WHERE id=?",
              success ? "DONE" : "PENDING",
              id);
          return null;
        });
  }

  public CompletableFuture<Integer> money() {
    return tx(
        c -> {
          List<Award> out = new ArrayList<>();
          try (var s = c.createStatement();
              var r =
                  s.executeQuery(
                      "SELECT * FROM world_event_awards WHERE state='PENDING' AND kind='CASH' ORDER"
                          + " BY attempts,id LIMIT 100")) {
            while (r.next())
              out.add(
                  new Award(
                      r.getString("id"),
                      uuid(r.getString("event")),
                      uuid(r.getString("owner")),
                      "CASH",
                      null,
                      null,
                      null,
                      r.getLong("amount")));
          }
          int paid = 0;
          for (var a : out) {
            Savepoint point = c.setSavepoint();
            try {
              economy.credit(c, a.owner(), a.amount());
              update(
                  c,
                  "UPDATE world_event_awards SET state='DONE',attempts=attempts+1 WHERE id=?",
                  a.id());
              c.releaseSavepoint(point);
              paid++;
            } catch (SQLException error) {
              c.rollback(point);
              c.releaseSavepoint(point);
              update(c, "UPDATE world_event_awards SET attempts=attempts+1 WHERE id=?", a.id());
            }
          }
          return paid;
        });
  }

  public CompletableFuture<String> setting(String name) {
    return db.submit(
        c -> {
          try (var p = c.prepareStatement("SELECT value FROM world_event_settings WHERE name=?")) {
            bind(p, name);
            try (var r = p.executeQuery()) {
              return r.next() ? r.getString(1) : null;
            }
          }
        });
  }

  public CompletableFuture<Void> setting(String name, String value) {
    return tx(
        c -> {
          update(
              c,
              "INSERT INTO world_event_settings VALUES(?,?) ON CONFLICT(name) DO UPDATE SET"
                  + " value=excluded.value",
              name,
              value);
          return null;
        });
  }

  public CompletableFuture<List<String>> statistics(boolean boss) {
    return db.submit(
        c -> {
          List<String> out = new ArrayList<>();
          String sql =
              boss
                  ? "SELECT player,SUM(damage) FROM world_event_damage GROUP BY player ORDER BY"
                      + " SUM(damage) DESC,player LIMIT 10"
                  : "SELECT type,status,COUNT(*) FROM world_events GROUP BY type,status ORDER BY"
                      + " type,status";
          try (var s = c.createStatement();
              var r = s.executeQuery(sql)) {
            while (r.next())
              out.add(
                  boss
                      ? r.getString(1) + " : " + r.getDouble(2) + " damage"
                      : r.getString(1) + " " + r.getString(2) + " : " + r.getLong(3));
          }
          return out;
        });
  }
}
