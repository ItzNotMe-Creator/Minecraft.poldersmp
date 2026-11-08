package nl.poldersmp.core.competition;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import nl.poldersmp.common.Database;

public final class CompetitionStore {
  private final Database database;
  private final Economy economy;

  public CompetitionStore(Database database, Economy economy) {
    this.database = database;
    this.economy = economy;
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

  private static int update(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, args);
      return p.executeUpdate();
    }
  }

  private static void bind(PreparedStatement p, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++)
      p.setObject(i + 1, args[i] instanceof UUID ? args[i].toString() : args[i]);
  }

  private static long number(Connection c, String sql, Object... args) throws SQLException {
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
          boolean exists;
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND"
                          + " name='competition_schema'")) {
            exists = rs.getInt(1) > 0;
          }
          if (exists) {
            if (number(c, "SELECT COALESCE(MAX(version),0) FROM competition_schema") != 1)
              throw new SQLException("Unsupported competition schema");
            return null;
          }
          try (var in =
              CompetitionStore.class.getResourceAsStream("/db/competition/V1__competitions.sql")) {
            if (in == null) throw new SQLException("Missing competition migration");
            for (String statement :
                new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
              if (!statement.isBlank())
                try (var s = c.createStatement()) {
                  s.executeUpdate(statement);
                }
          }
          return null;
        });
  }

  public record Bounty(
      UUID player, long amount, int rank, long claims, long kills, int contributors) {}

  public CompletableFuture<List<Bounty>> bounties() {
    return database.submit(
        c -> {
          List<Bounty> rows = new ArrayList<>();
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT target,SUM(remaining) total,(SELECT COUNT(*) FROM bounty_claims b"
                          + " WHERE b.target=a.target) claims,(SELECT COUNT(*) FROM bounty_claims k"
                          + " WHERE k.killer=a.target) kills,COUNT(DISTINCT contributor)"
                          + " contributors FROM bounty_contributions a WHERE remaining>0 GROUP BY"
                          + " target ORDER BY total DESC,target")) {
            int rank = 1;
            while (rs.next())
              rows.add(
                  new Bounty(
                      UUID.fromString(rs.getString(1)),
                      rs.getLong(2),
                      rank++,
                      rs.getLong(3),
                      rs.getLong(4),
                      rs.getInt(5)));
          }
          return rows;
        });
  }

  public CompletableFuture<Long> place(
      UUID request, UUID payer, UUID target, long amount, long now) {
    if (amount <= 0 || payer.equals(target))
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Invalid target or amount"));
    return tx(
        c -> {
          if (number(c, "SELECT COUNT(*) FROM bounty_contributions WHERE id=?", request) > 0)
            return total(c, target);
          economy.debit(c, payer, amount);
          long total = Math.addExact(total(c, target), amount);
          update(
              c,
              "INSERT INTO bounty_contributions VALUES(?,?,?,?,?,?)",
              request,
              target,
              payer,
              amount,
              amount,
              now);
          return total;
        });
  }

  private static long total(Connection c, UUID target) throws SQLException {
    return number(
        c, "SELECT COALESCE(SUM(remaining),0) FROM bounty_contributions WHERE target=?", target);
  }

  private static void payout(
      Connection c, String id, UUID recipient, long amount, String source, long now)
      throws SQLException {
    if (amount > 0)
      update(
          c,
          "INSERT OR IGNORE INTO competition_payouts(id,recipient,amount,source,status,created)"
              + " VALUES(?,?,?,?,'PENDING',?)",
          id,
          recipient,
          amount,
          source,
          now);
  }

  public CompletableFuture<Long> claim(
      UUID death, UUID killer, UUID victim, long now, long pairDelay) {
    if (killer.equals(victim)) return CompletableFuture.completedFuture(0L);
    return tx(
        c -> {
          if (number(c, "SELECT COUNT(*) FROM bounty_claims WHERE id=?", death) > 0) return 0L;
          long last =
              number(
                  c,
                  "SELECT COALESCE(MAX(created),0) FROM bounty_claims WHERE (target=? AND killer=?)"
                      + " OR (target=? AND killer=?)",
                  victim,
                  killer,
                  killer,
                  victim);
          if (!CompetitionRules.eligibleClaim(killer, victim, false, now, last, pairDelay))
            return 0L;
          long amount = total(c, victim);
          if (amount == 0) return 0L;
          update(
              c, "INSERT INTO bounty_claims VALUES(?,?,?,?,?)", death, victim, killer, amount, now);
          update(c, "UPDATE bounty_contributions SET remaining=0 WHERE target=?", victim);
          payout(c, "bounty:" + death, killer, amount, "BOUNTY", now);
          return amount;
        });
  }

  public CompletableFuture<Long> setBounty(
      UUID request, UUID target, long desired, UUID payer, long now) {
    if (desired < 0)
      return CompletableFuture.failedFuture(new IllegalArgumentException("Negative bounty"));
    return tx(
        c -> {
          if (update(c, "INSERT OR IGNORE INTO competition_requests VALUES(?)", request) == 0)
            return total(c, target);
          long current = total(c, target);
          if (desired > current) {
            if (payer == null || payer.equals(target))
              throw new SQLException("A separate funded account is required");
            long increase = desired - current;
            economy.debit(c, payer, increase);
            update(
                c,
                "INSERT INTO bounty_contributions VALUES(?,?,?,?,?,?)",
                request,
                target,
                payer,
                increase,
                increase,
                now);
          } else if (desired < current) {
            long refund = current - desired;
            record Contribution(String id, UUID payer, long remaining) {}
            List<Contribution> contributions = new ArrayList<>();
            try (var p =
                c.prepareStatement(
                    "SELECT id,contributor,remaining FROM bounty_contributions WHERE target=? AND"
                        + " remaining>0 ORDER BY created,id")) {
              bind(p, target);
              try (var rs = p.executeQuery()) {
                while (rs.next())
                  contributions.add(
                      new Contribution(
                          rs.getString(1), UUID.fromString(rs.getString(2)), rs.getLong(3)));
              }
            }
            for (Contribution contribution : contributions) {
              if (refund == 0) break;
              long part = Math.min(refund, contribution.remaining());
              update(
                  c,
                  "UPDATE bounty_contributions SET remaining=remaining-? WHERE id=?",
                  part,
                  contribution.id());
              payout(
                  c,
                  "refund:" + request + ":" + contribution.id(),
                  contribution.payer(),
                  part,
                  "BOUNTY_REFUND",
                  now);
              refund -= part;
            }
            if (refund != 0) throw new SQLException("Contribution totals changed during refund");
          }
          return desired;
        });
  }

  public CompletableFuture<Integer> recoverPayouts(long now) {
    return tx(
        c -> {
          int count = 0;
          record Payment(String id, UUID recipient, long amount) {}
          List<Payment> payments = new ArrayList<>();
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT id,recipient,amount FROM competition_payouts WHERE status='PENDING'"
                          + " ORDER BY attempts,created,id LIMIT 100")) {
            while (rs.next())
              payments.add(
                  new Payment(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getLong(3)));
          }
          for (Payment payment : payments) {
            Savepoint save = c.setSavepoint();
            try {
              economy.credit(c, payment.recipient(), payment.amount());
              update(
                  c,
                  "UPDATE competition_payouts SET"
                      + " status='COMPLETED',completed=?,attempts=attempts+1,last_error=NULL WHERE"
                      + " id=? AND status='PENDING'",
                  now,
                  payment.id());
              c.releaseSavepoint(save);
              count++;
            } catch (SQLException error) {
              c.rollback(save);
              c.releaseSavepoint(save);
              update(
                  c,
                  "UPDATE competition_payouts SET attempts=attempts+1,last_error=? WHERE id=?",
                  error.getClass().getSimpleName(),
                  payment.id());
            }
          }
          return count;
        });
  }

  public CompletableFuture<Long> pending() {
    return database.submit(
        c -> number(c, "SELECT COUNT(*) FROM competition_payouts WHERE status='PENDING'"));
  }

  public CompletableFuture<List<String>> history(UUID player) {
    return database.submit(
        c -> {
          List<String> lines = new ArrayList<>();
          try (var p =
              c.prepareStatement(
                  "SELECT killer,amount,created FROM bounty_claims WHERE target=? ORDER BY created"
                      + " DESC LIMIT 10")) {
            bind(p, player);
            try (var rs = p.executeQuery()) {
              while (rs.next())
                lines.add(
                    rs.getString(1)
                        + " won "
                        + rs.getLong(2)
                        + " @ "
                        + java.time.Instant.ofEpochMilli(rs.getLong(3)));
            }
          }
          return lines;
        });
  }

  public record KingEvent(
      UUID id,
      String slot,
      String status,
      long starts,
      long ends,
      UUID candidate,
      double progress,
      UUID winner,
      long prize,
      UUID treasury) {}

  private static UUID uuid(String s) {
    return s == null ? null : UUID.fromString(s);
  }

  private static KingEvent event(ResultSet r) throws SQLException {
    return new KingEvent(
        uuid(r.getString("id")),
        r.getString("slot"),
        r.getString("status"),
        r.getLong("starts"),
        r.getLong("ends"),
        uuid(r.getString("candidate")),
        r.getDouble("progress"),
        uuid(r.getString("winner")),
        r.getLong("prize"),
        uuid(r.getString("treasury")));
  }

  public CompletableFuture<KingEvent> active() {
    return database.submit(
        c -> {
          try (var s = c.createStatement();
              var rs =
                  s.executeQuery(
                      "SELECT * FROM king_events WHERE status IN ('COUNTDOWN','ACTIVE') LIMIT 1")) {
            return rs.next() ? event(rs) : null;
          }
        });
  }

  public CompletableFuture<KingEvent> start(
      UUID id, String slot, long now, long countdown, long duration, long prize, UUID treasury) {
    return tx(
        c -> {
          if (prize < 0) throw new SQLException("Invalid prize");
          if (prize > 0) economy.debit(c, treasury, prize);
          update(
              c,
              "INSERT INTO king_events(id,slot,status,created,starts,ends,prize,treasury)"
                  + " VALUES(?,?,'COUNTDOWN',?,?,?,?,?)",
              id,
              slot,
              now,
              now + countdown,
              now + countdown + duration,
              prize,
              treasury);
          return new KingEvent(
              id,
              slot,
              "COUNTDOWN",
              now + countdown,
              now + countdown + duration,
              null,
              0,
              null,
              prize,
              treasury);
        });
  }

  public CompletableFuture<Void> checkpoint(KingEvent e, Set<UUID> participants) {
    return tx(
        c -> {
          update(
              c,
              "UPDATE king_events SET status=?,candidate=?,progress=? WHERE id=? AND status IN"
                  + " ('COUNTDOWN','ACTIVE')",
              e.status(),
              e.candidate(),
              e.progress(),
              e.id());
          for (UUID player : participants)
            update(c, "INSERT OR IGNORE INTO king_participants VALUES(?,?)", e.id(), player);
          return null;
        });
  }

  public CompletableFuture<Boolean> finish(UUID id, UUID winner, long now) {
    return tx(
        c -> {
          KingEvent e;
          try (var p = c.prepareStatement("SELECT * FROM king_events WHERE id=?")) {
            bind(p, id);
            try (var rs = p.executeQuery()) {
              if (!rs.next()) return false;
              e = event(rs);
            }
          }
          if (!e.status().equals("COUNTDOWN") && !e.status().equals("ACTIVE")) return false;
          if (winner != null
              && number(
                      c,
                      "SELECT COUNT(*) FROM king_participants WHERE event=? AND player=?",
                      id,
                      winner)
                  == 0) throw new SQLException("Winner did not participate");
          update(
              c,
              "UPDATE king_events SET status=?,winner=?,finished=? WHERE id=?",
              winner == null ? "CANCELED" : "FINISHED",
              winner,
              now,
              id);
          if (winner != null) {
            update(c, "INSERT INTO king_victories VALUES(?,?,?)", id, winner, now);
            update(
                c,
                "INSERT INTO king_scheduling(name,value) VALUES('current_king',?) ON CONFLICT(name)"
                    + " DO UPDATE SET value=excluded.value",
                winner.toString());
          }
          payout(
              c,
              "king:" + id,
              winner == null ? e.treasury() : winner,
              e.prize(),
              winner == null ? "KING_REFUND" : "KING_REWARD",
              now);
          return true;
        });
  }

  public CompletableFuture<String> state(String key) {
    return database.submit(
        c -> {
          try (var p = c.prepareStatement("SELECT value FROM king_scheduling WHERE name=?")) {
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
              "INSERT INTO king_scheduling VALUES(?,?) ON CONFLICT(name) DO UPDATE SET"
                  + " value=excluded.value",
              key,
              value);
          return null;
        });
  }

  public CompletableFuture<List<String>> kingHistory(boolean top) {
    return database.submit(
        c -> {
          List<String> rows = new ArrayList<>();
          String sql =
              top
                  ? "SELECT player,COUNT(*) FROM king_victories GROUP BY player ORDER BY COUNT(*)"
                      + " DESC,player LIMIT 10"
                  : "SELECT player,created FROM king_victories ORDER BY created DESC LIMIT 10";
          try (var s = c.createStatement();
              var rs = s.executeQuery(sql)) {
            while (rs.next())
              rows.add(
                  rs.getString(1)
                      + " : "
                      + (top ? rs.getLong(2) : java.time.Instant.ofEpochMilli(rs.getLong(2))));
          }
          return rows;
        });
  }
}
