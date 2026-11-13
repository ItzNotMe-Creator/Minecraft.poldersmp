package nl.poldersmp.core.loot;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;

public final class LootStore {
  private final Database database;

  public LootStore(Database database) {
    this.database = database;
  }

  private <T> CompletableFuture<T> tx(Database.Work<T> work) {
    return database.submit(
        c -> {
          c.setAutoCommit(false);
          try {
            T result = work.run(c);
            c.commit();
            return result;
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        });
  }

  private static void bind(PreparedStatement p, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++)
      p.setObject(i + 1, args[i] instanceof UUID ? args[i].toString() : args[i]);
  }

  private static int update(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, args);
      return p.executeUpdate();
    }
  }

  private static long count(Connection c, String sql, Object... args) throws SQLException {
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
                  c, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='loot_schema'")
              > 0) {
            if (count(c, "SELECT MAX(version) FROM loot_schema") != 1)
              throw new SQLException("Unsupported loot schema");
            return null;
          }
          try (var in = LootStore.class.getResourceAsStream("/db/loot/V1__loot.sql")) {
            if (in == null) throw new SQLException("Missing loot migration");
            for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
              if (!sql.isBlank())
                try (var s = c.createStatement()) {
                  s.executeUpdate(sql);
                }
          }
          update(
              c,
              "INSERT INTO loot_settings VALUES('installed_at',?)",
              Long.toString(System.currentTimeMillis()));
          return null;
        });
  }

  public record Parcel(UUID id, UUID owner, byte[] payload, String state, long available) {}

  private static void parcel(Connection c, Parcel parcel, String source, long now)
      throws SQLException {
    update(
        c,
        "INSERT INTO loot_parcels VALUES(?,?,?,'WAITING',?,?,?)",
        parcel.id(),
        parcel.owner(),
        parcel.payload(),
        source,
        parcel.available(),
        now);
  }

  public CompletableFuture<Boolean> issue(
      UUID key, LootRules.Tier tier, Parcel parcel, String source, long now) {
    return tx(
        c -> {
          if (count(c, "SELECT COUNT(*) FROM loot_parcels WHERE source=?", source) > 0)
            return false;
          update(c, "INSERT INTO loot_keys VALUES(?,?,'ISSUED',?)", key, tier.name(), now);
          parcel(c, parcel, source, now);
          return true;
        });
  }

  /** Shared transaction boundary for trades; caller owns commit/rollback. */
  public static boolean enqueueInTransaction(Connection c, Parcel reward, String source, long now)
      throws SQLException {
    if (count(c, "SELECT COUNT(*) FROM loot_parcels WHERE source=?", source) > 0) return false;
    parcel(c, reward, source, now);
    return true;
  }

  public CompletableFuture<Boolean> enqueue(Parcel reward, String source, long now) {
    return tx(
        c -> {
          if (count(c, "SELECT COUNT(*) FROM loot_parcels WHERE source=?", source) > 0)
            return false;
          parcel(c, reward, source, now);
          return true;
        });
  }

  public CompletableFuture<Boolean> open(
      UUID key, LootRules.Tier tier, Parcel reward, String label, long now) {
    return tx(
        c -> {
          if (update(
                  c,
                  "UPDATE loot_keys SET state='USED' WHERE id=? AND tier=? AND state='ISSUED'",
                  key,
                  tier.name())
              != 1) return false;
          parcel(c, reward, "crate:" + key, now);
          update(
              c,
              "INSERT INTO loot_openings VALUES(?,?,?,'CRATE',?,?)",
              reward.id(),
              reward.owner(),
              tier.name(),
              label,
              now);
          return true;
        });
  }

  public CompletableFuture<List<Parcel>> parcels(UUID owner, long now) {
    return database.submit(
        c -> {
          List<Parcel> out = new ArrayList<>();
          try (var p =
              c.prepareStatement(
                  "SELECT * FROM loot_parcels WHERE owner=? AND state!='DELIVERED' AND available<=?"
                      + " ORDER BY created LIMIT 36")) {
            bind(p, owner, now);
            try (var rs = p.executeQuery()) {
              while (rs.next())
                out.add(
                    new Parcel(
                        UUID.fromString(rs.getString("id")),
                        owner,
                        rs.getBytes("payload"),
                        rs.getString("state"),
                        rs.getLong("available")));
            }
          }
          return out;
        });
  }

  public CompletableFuture<Boolean> delivering(UUID id, UUID owner) {
    return tx(
        c ->
            update(
                    c,
                    "UPDATE loot_parcels SET state='DELIVERING' WHERE id=? AND owner=? AND"
                        + " state='WAITING'",
                    id,
                    owner)
                == 1);
  }

  public CompletableFuture<Void> delivered(UUID id, UUID owner) {
    return tx(
        c -> {
          if (update(
                      c,
                      "UPDATE loot_parcels SET state='DELIVERED' WHERE id=? AND owner=? AND"
                          + " state='DELIVERING'",
                      id,
                      owner)
                  != 1
              && count(
                      c,
                      "SELECT COUNT(*) FROM loot_parcels WHERE id=? AND owner=? AND"
                          + " state='DELIVERED'",
                      id,
                      owner)
                  != 1) throw new SQLException("Invalid delivery transition");
          return null;
        });
  }

  public CompletableFuture<Boolean> completed(UUID parcel, UUID owner) {
    return database.submit(
        c ->
            count(
                    c,
                    "SELECT COUNT(*) FROM loot_parcels WHERE id=? AND owner=? AND"
                        + " state='DELIVERED'",
                    parcel,
                    owner)
                > 0);
  }

  public record Crate(UUID world, int x, int y, int z, LootRules.Tier tier) {}

  public CompletableFuture<List<Crate>> crates() {
    return database.submit(
        c -> {
          List<Crate> out = new ArrayList<>();
          try (var s = c.createStatement();
              var rs = s.executeQuery("SELECT * FROM loot_crates")) {
            while (rs.next())
              out.add(
                  new Crate(
                      UUID.fromString(rs.getString(1)),
                      rs.getInt(2),
                      rs.getInt(3),
                      rs.getInt(4),
                      LootRules.Tier.valueOf(rs.getString(5))));
          }
          return out;
        });
  }

  public CompletableFuture<Void> setCrate(Crate crate) {
    return tx(
        c -> {
          update(
              c,
              "INSERT INTO loot_crates VALUES(?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET"
                  + " tier=excluded.tier",
              crate.world(),
              crate.x(),
              crate.y(),
              crate.z(),
              crate.tier().name());
          return null;
        });
  }

  public CompletableFuture<Void> removeCrate(Crate crate) {
    return tx(
        c -> {
          update(
              c,
              "DELETE FROM loot_crates WHERE world=? AND x=? AND y=? AND z=?",
              crate.world(),
              crate.x(),
              crate.y(),
              crate.z());
          return null;
        });
  }

  public record BlockPlan(int x, int y, int z, String original, String placed) {}

  public record Vault(
      UUID id, UUID world, int x, int y, int z, LootRules.Tier tier, String state, long expires) {}

  public CompletableFuture<Void> plan(Vault vault, List<BlockPlan> blocks) {
    return tx(
        c -> {
          update(
              c,
              "INSERT INTO loot_vaults(id,world,x,y,z,tier,state,expires)"
                  + " VALUES(?,?,?,?,?,?,'PLANNED',?)",
              vault.id(),
              vault.world(),
              vault.x(),
              vault.y(),
              vault.z(),
              vault.tier().name(),
              vault.expires());
          for (var b : blocks)
            update(
                c,
                "INSERT INTO loot_vault_blocks VALUES(?,?,?,?,?,?)",
                vault.id(),
                b.x(),
                b.y(),
                b.z(),
                b.original(),
                b.placed());
          return null;
        });
  }

  public CompletableFuture<Void> activate(UUID id) {
    return tx(
        c -> {
          if (update(c, "UPDATE loot_vaults SET state='ACTIVE' WHERE id=? AND state='PLANNED'", id)
              != 1) throw new SQLException("Invalid vault activation");
          return null;
        });
  }

  public CompletableFuture<List<Vault>> vaults() {
    return database.submit(
        c -> {
          List<Vault> out = new ArrayList<>();
          try (var s = c.createStatement();
              var rs = s.executeQuery("SELECT * FROM loot_vaults WHERE state!='REMOVED'")) {
            while (rs.next())
              out.add(
                  new Vault(
                      UUID.fromString(rs.getString("id")),
                      UUID.fromString(rs.getString("world")),
                      rs.getInt("x"),
                      rs.getInt("y"),
                      rs.getInt("z"),
                      LootRules.Tier.valueOf(rs.getString("tier")),
                      rs.getString("state"),
                      rs.getLong("expires")));
          }
          return out;
        });
  }

  public CompletableFuture<List<BlockPlan>> blocks(UUID id) {
    return database.submit(
        c -> {
          List<BlockPlan> out = new ArrayList<>();
          try (var p =
              c.prepareStatement(
                  "SELECT x,y,z,original,placed FROM loot_vault_blocks WHERE vault=?")) {
            bind(p, id);
            try (var rs = p.executeQuery()) {
              while (rs.next())
                out.add(
                    new BlockPlan(
                        rs.getInt(1),
                        rs.getInt(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getString(5)));
            }
          }
          return out;
        });
  }

  public CompletableFuture<Boolean> claim(
      Vault vault, Parcel reward, String label, long now, long cooldown) {
    return tx(
        c -> {
          long last =
              count(
                  c,
                  "SELECT COALESCE(MAX(claimed_at),0) FROM loot_vaults WHERE claimed_by=?",
                  reward.owner());
          if (last > 0 && now - last < cooldown) return false;
          if (update(
                  c,
                  "UPDATE loot_vaults SET state='CLAIMED',claimed_by=?,claimed_at=? WHERE id=? AND"
                      + " state='ACTIVE' AND expires>?",
                  reward.owner(),
                  now,
                  vault.id(),
                  now)
              != 1) return false;
          parcel(c, reward, "vault:" + vault.id(), now);
          update(
              c,
              "INSERT INTO loot_openings VALUES(?,?,?,'VAULT',?,?)",
              reward.id(),
              reward.owner(),
              vault.tier().name(),
              label,
              now);
          return true;
        });
  }

  public CompletableFuture<Void> beginCleanup(UUID id) {
    return tx(
        c -> {
          update(c, "UPDATE loot_vaults SET state='CLEANUP' WHERE id=? AND state!='REMOVED'", id);
          return null;
        });
  }

  public CompletableFuture<Void> removed(UUID id) {
    return tx(
        c -> {
          update(c, "UPDATE loot_vaults SET state='REMOVED' WHERE id=?", id);
          return null;
        });
  }

  public CompletableFuture<List<String>> top(String origin) {
    return database.submit(
        c -> {
          List<String> out = new ArrayList<>();
          try (var p =
              c.prepareStatement(
                  "SELECT player,COUNT(*) FROM loot_openings WHERE origin=? GROUP BY player ORDER"
                      + " BY COUNT(*) DESC,player LIMIT 10")) {
            bind(p, origin);
            try (var rs = p.executeQuery()) {
              while (rs.next()) out.add(rs.getString(1) + " : " + rs.getLong(2));
            }
          }
          return out;
        });
  }

  public record Bonus(String source, UUID player, String kind) {}

  public CompletableFuture<List<Bonus>> bonuses() {
    return database.submit(
        c -> {
          List<Bonus> out = new ArrayList<>();
          if (count(
                  c,
                  "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND"
                      + " name='competition_payouts'")
              == 0) return out;
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT id,recipient,source FROM competition_payouts p WHERE"
                          + " status='COMPLETED' AND source IN ('BOUNTY','KING_REWARD') AND"
                          + " created>=CAST((SELECT value FROM loot_settings WHERE"
                          + " name='installed_at') AS INTEGER) AND NOT EXISTS(SELECT 1 FROM"
                          + " loot_parcels l WHERE l.source='bonus:'||p.id) LIMIT 100")) {
            while (rs.next())
              out.add(
                  new Bonus(
                      "bonus:" + rs.getString(1),
                      UUID.fromString(rs.getString(2)),
                      rs.getString(3)));
          }
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT event,player FROM king_victories k WHERE created>=CAST((SELECT value"
                          + " FROM loot_settings WHERE name='installed_at') AS INTEGER) AND NOT"
                          + " EXISTS(SELECT 1 FROM competition_payouts p WHERE"
                          + " p.id='king:'||k.event) AND NOT EXISTS(SELECT 1 FROM loot_parcels l"
                          + " WHERE l.source='bonus:king:'||k.event) LIMIT 100")) {
            while (rs.next())
              out.add(
                  new Bonus(
                      "bonus:king:" + rs.getString(1),
                      UUID.fromString(rs.getString(2)),
                      "KING_REWARD"));
          }
          return out;
        });
  }

  public CompletableFuture<String> state(String key) {
    return database.submit(
        c -> {
          try (var p = c.prepareStatement("SELECT value FROM loot_settings WHERE name=?")) {
            bind(p, key);
            try (var rs = p.executeQuery()) {
              return rs.next() ? rs.getString(1) : null;
            }
          }
        });
  }

  public CompletableFuture<Void> state(String key, String value) {
    return tx(
        c -> {
          update(
              c,
              "INSERT INTO loot_settings VALUES(?,?) ON CONFLICT(name) DO UPDATE SET"
                  + " value=excluded.value",
              key,
              value);
          return null;
        });
  }
}
