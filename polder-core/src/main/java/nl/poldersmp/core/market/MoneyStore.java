package nl.poldersmp.core.market;

import static nl.poldersmp.core.market.Sql.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.competition.Economy;
import nl.poldersmp.core.loot.*;

public final class MoneyStore {
  public record Leg(UUID player, long delta) {}

  public record Operation(
      UUID id,
      UUID actor,
      String kind,
      String provider,
      String state,
      long created,
      String detail,
      Double expected,
      Long setBalance) {}

  public record Step(UUID operation, int index, UUID player, long delta, String state) {}

  public record Listing(
      UUID id,
      UUID seller,
      byte[] payload,
      String label,
      long price,
      long expires,
      String state,
      String provider) {}

  public record Deposit(
      UUID id,
      UUID owner,
      byte[] payload,
      String label,
      String kind,
      long price,
      String state,
      long created,
      long expires,
      String provider) {}

  public record Returned(String source, UUID owner, byte[] payload) {}

  private final Database db;

  public MoneyStore(Database db) {
    this.db = db;
  }

  public CompletableFuture<Void> migrate() {
    return tx(
        db,
        c -> {
          nl.poldersmp.core.market.Sql.migrate(c, "market");
          update(c, "UPDATE money_legs SET state='UNKNOWN' WHERE state='APPLYING'");
          update(
              c,
              "UPDATE money_operations SET state='UNKNOWN' WHERE id IN (SELECT operation FROM"
                  + " money_legs WHERE state='UNKNOWN')");
          return null;
        });
  }

  /** Records legacy transactional debits/credits in the same supplied transaction. */
  public static void legacy(Connection c, UUID player, long delta) throws SQLException {
    if (delta == 0) return;
    UUID id = UUID.randomUUID();
    update(
        c,
        "INSERT INTO money_operations VALUES(?,?,?,'sqlite','DONE',?,?,NULL,NULL)",
        id,
        player,
        delta < 0 ? "LEGACY_DEBIT" : "LEGACY_CREDIT",
        System.currentTimeMillis(),
        "Existing competition/event economy transaction");
    update(c, "INSERT INTO money_legs VALUES(?,0,?,?,'DONE')", id, player, delta);
  }

  public CompletableFuture<Long> balance(UUID p) {
    return db.submit(c -> number(c, "SELECT balance FROM accounts WHERE uuid=?", p));
  }

  private static void plan(Connection c, Operation op, List<Leg> legs) throws SQLException {
    var prior =
        rows(c, "SELECT actor,kind,provider,detail FROM money_operations WHERE id=?", op.id());
    if (!prior.isEmpty()) {
      require(
          prior
              .getFirst()
              .equals(Arrays.asList(op.actor().toString(), op.kind(), op.provider(), op.detail())),
          "Operation ID conflict");
      var existing =
          rows(
              c,
              "SELECT player,delta FROM money_legs WHERE operation=? ORDER BY position",
              op.id());
      if (op.setBalance() == null) {
        require(existing.size() == legs.size(), "Replayed leg count changed");
        for (int i = 0; i < legs.size(); i++)
          require(
              existing.get(i).get(0).equals(legs.get(i).player().toString())
                  && ((Number) existing.get(i).get(1)).longValue() == legs.get(i).delta(),
              "Replayed leg changed");
      }
      var fixed =
          rows(c, "SELECT expected,set_balance FROM money_operations WHERE id=?", op.id())
              .getFirst();
      require(
          Objects.equals(
                  fixed.get(0) == null ? null : ((Number) fixed.get(0)).doubleValue(),
                  op.expected())
              && Objects.equals(
                  fixed.get(1) == null ? null : ((Number) fixed.get(1)).longValue(),
                  op.setBalance()),
          "Replayed precondition changed");
      return;
    }
    Set<UUID> parties = new HashSet<>();
    parties.add(op.actor());
    for (var leg : legs) parties.add(leg.player());
    for (UUID party : parties)
      require(
          number(
                  c,
                  "SELECT COUNT(*) FROM money_legs l JOIN money_operations o ON o.id=l.operation"
                      + " WHERE l.player=? AND o.provider=? AND (o.state='UNKNOWN' OR"
                      + " l.state='APPLYING')",
                  party,
                  op.provider())
              == 0,
          "Account has an uncertain external effect; reconcile that operation first");
    require(!legs.isEmpty() || op.setBalance() != null, "No monetary effect");
    update(
        c,
        "INSERT INTO money_operations VALUES(?,?,?,?,'READY',?,?,?,?)",
        op.id(),
        op.actor(),
        op.kind(),
        op.provider(),
        op.created(),
        op.detail(),
        op.expected(),
        op.setBalance());
    int i = 0;
    for (var leg : legs) {
      require(leg.delta() != 0 && leg.delta() != Long.MIN_VALUE, "Invalid delta");
      update(
          c,
          "INSERT INTO money_legs VALUES(?,?,?,?,'PENDING')",
          op.id(),
          i++,
          leg.player(),
          leg.delta());
    }
  }

  public CompletableFuture<Void> plan(Operation op, List<Leg> legs) {
    return tx(
        db,
        c -> {
          plan(c, op, legs);
          return null;
        });
  }

  public CompletableFuture<List<Operation>> pending(String provider) {
    return db.submit(
        c -> {
          List<Operation> list = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  """
SELECT requested.id,requested.actor,requested.kind,requested.provider,requested.state,
  requested.created,requested.detail,requested.expected,requested.set_balance
FROM money_operations requested
WHERE requested.state='READY' AND requested.provider=? AND NOT EXISTS (
  SELECT 1 FROM money_legs own
  JOIN money_legs held ON held.player=own.player
  JOIN money_operations other ON other.id=held.operation
  WHERE own.operation=requested.id AND other.id!=requested.id
    AND other.provider=requested.provider
    AND (other.state='UNKNOWN' OR held.state='APPLYING'))
ORDER BY requested.created,requested.id LIMIT 20
""",
                  provider)) list.add(operation(r));
          return list;
        });
  }

  private static Operation operation(List<Object> r) {
    return new Operation(
        UUID.fromString((String) r.get(0)),
        UUID.fromString((String) r.get(1)),
        (String) r.get(2),
        (String) r.get(3),
        (String) r.get(4),
        ((Number) r.get(5)).longValue(),
        (String) r.get(6),
        r.get(7) == null ? null : ((Number) r.get(7)).doubleValue(),
        r.get(8) == null ? null : ((Number) r.get(8)).longValue());
  }

  public CompletableFuture<List<Step>> steps(UUID op) {
    return db.submit(
        c -> {
          List<Step> list = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT position,player,delta,state FROM money_legs WHERE operation=? ORDER BY"
                      + " position",
                  op))
            list.add(
                new Step(
                    op,
                    ((Number) r.get(0)).intValue(),
                    UUID.fromString((String) r.get(1)),
                    ((Number) r.get(2)).longValue(),
                    (String) r.get(3)));
          return list;
        });
  }

  private static void cancelContent(Connection c, UUID op) throws SQLException {
    var orders =
        rows(
            c,
            "SELECT kind,reference,buyer,rotation,sku FROM market_orders WHERE operation=? AND"
                + " state='RESERVED'",
            op);
    for (var order : orders) {
      if (order.get(0).equals("AUCTION"))
        update(
            c,
            "UPDATE market_listings SET state='ACTIVE',buyer=NULL,operation=NULL WHERE id=? AND"
                + " operation=? AND state='RESERVED'",
            order.get(1),
            op);
      if (order.get(0).equals("BLACK"))
        update(
            c,
            "UPDATE market_limits SET count=count-1 WHERE rotation=? AND sku=? AND player=? AND"
                + " count>0",
            order.get(3),
            order.get(4),
            order.get(2));
      if (order.get(0).equals("SELL"))
        update(
            c,
            "INSERT OR IGNORE INTO market_returns SELECT 'sale:'||id,owner,payload,'PENDING' FROM"
                + " market_deposits WHERE id=?",
            order.get(1));
    }
    update(
        c, "UPDATE market_orders SET state='CANCELED' WHERE operation=? AND state='RESERVED'", op);
  }

  private static void finishContent(Connection c, UUID op, long now) throws SQLException {
    for (var row :
        rows(
            c,
            "SELECT buyer,payload,receipt,kind,reference FROM market_orders WHERE operation=? AND"
                + " state='RESERVED'",
            op)) {
      if (row.get(1) != null)
        LootStore.enqueueInTransaction(
            c,
            new LootStore.Parcel(
                UUID.fromString((String) row.get(2)),
                UUID.fromString((String) row.get(0)),
                (byte[]) row.get(1),
                "WAITING",
                now),
            "market:" + op,
            now);
      if (row.get(3).equals("AUCTION"))
        require(
            update(
                    c,
                    "UPDATE market_listings SET state='SOLD' WHERE id=? AND state='RESERVED' AND"
                        + " operation=?",
                    row.get(4),
                    op)
                == 1,
            "Auction reservation changed");
    }
    update(c, "UPDATE market_orders SET state='DONE' WHERE operation=? AND state='RESERVED'", op);
    update(c, "UPDATE money_operations SET state='DONE' WHERE id=? AND state='READY'", op);
  }

  public CompletableFuture<Void> internal(UUID op, long now) {
    return tx(
        db,
        c -> {
          var rows =
              rows(
                  c,
                  "SELECT actor,kind,set_balance FROM money_operations WHERE id=? AND"
                      + " provider='sqlite' AND state='READY'",
                  op);
          if (rows.isEmpty()) return null;
          var row = rows.getFirst();
          if (row.get(2) != null) {
            UUID player = UUID.fromString((String) row.get(0));
            long target = ((Number) row.get(2)).longValue();
            long current = number(c, "SELECT balance FROM accounts WHERE uuid=?", player);
            long delta = Math.subtractExact(target, current);
            if (delta != 0)
              update(c, "INSERT INTO money_legs VALUES(?,0,?,?,'PENDING')", op, player, delta);
          }
          var account = new Economy.Foundation();
          for (var r :
              rows(
                  c,
                  "SELECT player,delta FROM money_legs WHERE operation=? ORDER BY position",
                  op)) {
            UUID p = UUID.fromString((String) r.get(0));
            long delta = ((Number) r.get(1)).longValue();
            if (delta < 0) account.debit(c, p, -delta);
            else account.credit(c, p, delta);
          }
          update(c, "UPDATE money_legs SET state='DONE' WHERE operation=?", op);
          finishContent(c, op, now);
          return null;
        });
  }

  public CompletableFuture<Void> decline(UUID op, String reason) {
    return tx(
        db,
        c -> {
          require(
              number(c, "SELECT COUNT(*) FROM money_operations WHERE id=? AND state='READY'", op)
                  == 1,
              "Only a ready, unapplied operation can be canceled");
          require(
              number(
                      c,
                      "SELECT COUNT(*) FROM money_legs WHERE operation=? AND state IN"
                          + " ('DONE','APPLYING','UNKNOWN')",
                      op)
                  == 0,
              "Partial/uncertain operation must be reconciled");
          update(c, "UPDATE money_legs SET state='DECLINED' WHERE operation=?", op);
          update(
              c, "UPDATE money_operations SET state='DECLINED' WHERE id=? AND state='READY'", op);
          cancelContent(c, op);
          update(
              c,
              "INSERT INTO money_audit(operation,actor,message,created) VALUES(?,'system',?,?)",
              op,
              reason,
              System.currentTimeMillis());
          return null;
        });
  }

  public CompletableFuture<Boolean> applying(Step s) {
    return tx(
        db,
        c ->
            update(
                    c,
                    "UPDATE money_legs SET state='APPLYING' WHERE operation=? AND position=? AND"
                        + " state='PENDING' AND EXISTS (SELECT 1 FROM money_operations WHERE id=?"
                        + " AND state='READY')",
                    s.operation(),
                    s.index(),
                    s.operation())
                == 1);
  }

  public CompletableFuture<Void> acknowledge(Step s, String result) {
    return tx(
        db,
        c -> {
          require(Set.of("DONE", "PENDING", "UNKNOWN").contains(result), "Invalid acknowledgement");
          require(
              update(
                      c,
                      "UPDATE money_legs SET state=? WHERE operation=? AND position=? AND"
                          + " state='APPLYING'",
                      result,
                      s.operation(),
                      s.index())
                  == 1,
              "Leg not applying");
          if (result.equals("UNKNOWN"))
            update(c, "UPDATE money_operations SET state='UNKNOWN' WHERE id=?", s.operation());
          return null;
        });
  }

  public CompletableFuture<Void> externalFinish(UUID op, long now) {
    return tx(
        db,
        c -> {
          require(
              number(c, "SELECT COUNT(*) FROM money_legs WHERE operation=? AND state!='DONE'", op)
                  == 0,
              "Unconfirmed external legs");
          require(
              number(c, "SELECT COUNT(*) FROM money_operations WHERE id=? AND state='READY'", op)
                  == 1,
              "Operation held");
          finishContent(c, op, now);
          return null;
        });
  }

  public CompletableFuture<Void> resolve(
      UUID op, int index, boolean applied, UUID admin, long now) {
    return tx(
        db,
        c -> {
          require(
              update(
                      c,
                      "UPDATE money_legs SET state=? WHERE operation=? AND position=? AND"
                          + " state='UNKNOWN'",
                      applied ? "DONE" : "PENDING",
                      op,
                      index)
                  == 1,
              "Only uncertain legs can be reconciled");
          if (number(c, "SELECT COUNT(*) FROM money_legs WHERE operation=? AND state='UNKNOWN'", op)
              == 0)
            update(
                c, "UPDATE money_operations SET state='READY' WHERE id=? AND state='UNKNOWN'", op);
          update(
              c,
              "INSERT INTO money_audit(operation,actor,message,created) VALUES(?,?,?,?)",
              op,
              admin,
              "Operator attested leg " + index + " " + (applied ? "applied" : "not applied"),
              now);
          return null;
        });
  }

  public CompletableFuture<List<String>> history(UUID p) {
    return db.submit(
        c -> {
          List<String> out = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT o.id,o.kind,o.provider,o.state,o.created,l.delta,o.detail FROM"
                      + " money_operations o LEFT JOIN money_legs l ON l.operation=o.id WHERE"
                      + " (l.player=? OR (l.player IS NULL AND o.actor=?)) ORDER BY o.created DESC"
                      + " LIMIT 20",
                  p,
                  p)) out.add(r.toString());
          return out;
        });
  }

  public CompletableFuture<List<String>> audit() {
    return db.submit(
        c -> {
          List<String> out = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT id,kind,provider,state,detail FROM money_operations WHERE state IN"
                      + " ('UNKNOWN','READY') ORDER BY created LIMIT 30")) out.add(r.toString());
          return out;
        });
  }

  public CompletableFuture<String> stats() {
    return db.submit(
        c -> {
          List<String> lines = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT o.provider,COUNT(DISTINCT o.id),COALESCE(SUM(CASE WHEN l.delta>0 THEN"
                      + " l.delta ELSE 0 END),0) FROM money_operations o LEFT JOIN money_legs l ON"
                      + " l.operation=o.id WHERE o.state='DONE' GROUP BY o.provider"))
            lines.add(r.get(0) + ": completed " + r.get(1) + " • gross credits " + r.get(2));
          lines.add(
              "Uncertain operations (all providers): "
                  + number(c, "SELECT COUNT(*) FROM money_operations WHERE state='UNKNOWN'"));
          return String.join("\n", lines);
        });
  }

  public CompletableFuture<Void> deposit(Deposit d, int perPlayer, int maximum) {
    return tx(
        db,
        c -> {
          require(
              number(
                          c,
                          "SELECT COUNT(*) FROM market_deposits WHERE owner=? AND kind='AUCTION'"
                              + " AND state IN ('PREPARED','TAGGED')",
                          d.owner())
                      + number(
                          c,
                          "SELECT COUNT(*) FROM market_listings WHERE seller=? AND state IN"
                              + " ('ACTIVE','RESERVED')",
                          d.owner())
                  < perPlayer,
              "Auction listing limit");
          require(
              number(c, "SELECT COUNT(*) FROM market_listings WHERE state IN ('ACTIVE','RESERVED')")
                  < maximum,
              "Auction is full");
          update(
              c,
              "INSERT INTO market_deposits VALUES(?,?,?,?,?,?,'PREPARED',?,?,?)",
              d.id(),
              d.owner(),
              d.payload(),
              d.label(),
              d.kind(),
              d.price(),
              d.created(),
              d.expires(),
              d.provider());
          return null;
        });
  }

  public CompletableFuture<List<Deposit>> deposits(UUID owner) {
    return db.submit(
        c -> {
          List<Deposit> list = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT id,payload,label,kind,price,state,created,expires,provider FROM"
                      + " market_deposits WHERE owner=? AND state IN ('PREPARED','TAGGED')",
                  owner))
            list.add(
                new Deposit(
                    UUID.fromString((String) r.get(0)),
                    owner,
                    (byte[]) r.get(1),
                    (String) r.get(2),
                    (String) r.get(3),
                    ((Number) r.get(4)).longValue(),
                    (String) r.get(5),
                    ((Number) r.get(6)).longValue(),
                    ((Number) r.get(7)).longValue(),
                    (String) r.get(8)));
          return list;
        });
  }

  public CompletableFuture<Void> abandon(UUID id, UUID owner) {
    return tx(
        db,
        c -> {
          update(
              c,
              "UPDATE market_deposits SET state='CANCELED' WHERE id=? AND owner=? AND"
                  + " state='PREPARED'",
              id,
              owner);
          return null;
        });
  }

  public CompletableFuture<Void> tagged(UUID id, UUID owner) {
    return tx(
        db,
        c -> {
          require(
              update(
                      c,
                      "UPDATE market_deposits SET state='TAGGED' WHERE id=? AND owner=? AND"
                          + " state='PREPARED'",
                      id,
                      owner)
                  == 1,
              "Deposit changed");
          return null;
        });
  }

  public CompletableFuture<Void> captured(Deposit d, String provider, long now) {
    return tx(
        db,
        c -> {
          var stored =
              rows(
                  c,
                  "SELECT owner,payload,label,kind,price,state,created,expires,provider FROM"
                      + " market_deposits WHERE id=?",
                  d.id());
          require(
              stored.size() == 1 && stored.getFirst().get(0).equals(d.owner().toString()),
              "Escrow owner mismatch");
          var r = stored.getFirst();
          Deposit actual =
              new Deposit(
                  d.id(),
                  d.owner(),
                  (byte[]) r.get(1),
                  (String) r.get(2),
                  (String) r.get(3),
                  ((Number) r.get(4)).longValue(),
                  (String) r.get(5),
                  ((Number) r.get(6)).longValue(),
                  ((Number) r.get(7)).longValue(),
                  (String) r.get(8));
          require(
              actual.provider().equals(provider),
              "Escrow is pinned to its original provider; restore that provider before capture");
          require(
              update(
                      c,
                      "UPDATE market_deposits SET state='CAPTURED' WHERE id=? AND state='TAGGED'",
                      actual.id())
                  == 1,
              "Deposit already captured");
          if (actual.kind().equals("AUCTION"))
            update(
                c,
                "INSERT INTO market_listings(id,seller,payload,label,price,expires,state,provider)"
                    + " VALUES(?,?,?,?,?,?,'ACTIVE',?)",
                actual.id(),
                actual.owner(),
                actual.payload(),
                actual.label(),
                actual.price(),
                actual.expires(),
                actual.provider());
          else {
            plan(
                c,
                new Operation(
                    actual.id(),
                    actual.owner(),
                    "SELL",
                    provider,
                    "READY",
                    now,
                    "sale:" + actual.id(),
                    null,
                    null),
                List.of(new Leg(actual.owner(), actual.price())));
            update(
                c,
                "INSERT INTO market_orders(operation,buyer,kind,reference,state)"
                    + " VALUES(?,?,'SELL',?,'RESERVED')",
                actual.id(),
                actual.owner(),
                actual.id());
          }
          return null;
        });
  }

  public CompletableFuture<List<Listing>> listings(UUID owner, String query, int page, long now) {
    return listings(owner, query, page, now, null);
  }

  public CompletableFuture<List<Listing>> listings(
      UUID owner, String query, int page, long now, String provider) {
    return db.submit(
        c -> {
          List<Listing> out = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT id,seller,payload,label,price,expires,state,provider FROM market_listings"
                      + " WHERE state='ACTIVE' AND expires>? AND (? IS NULL OR seller=?) AND"
                      + " instr(lower(label),lower(?))>0 AND (? IS NULL OR provider=?) ORDER BY"
                      + " expires,id LIMIT 45 OFFSET ?",
                  now,
                  owner,
                  owner,
                  query,
                  provider,
                  provider,
                  Math.max(0, page) * 45)) {
            out.add(
                new Listing(
                    UUID.fromString((String) r.get(0)),
                    UUID.fromString((String) r.get(1)),
                    (byte[]) r.get(2),
                    (String) r.get(3),
                    ((Number) r.get(4)).longValue(),
                    ((Number) r.get(5)).longValue(),
                    (String) r.get(6),
                    (String) r.get(7)));
          }
          return out;
        });
  }

  public CompletableFuture<Listing> listing(UUID id) {
    return db.submit(
        c -> {
          var rs =
              rows(
                  c,
                  "SELECT seller,payload,label,price,expires,state,provider FROM market_listings"
                      + " WHERE id=?",
                  id);
          require(!rs.isEmpty(), "Unknown listing");
          var r = rs.getFirst();
          return new Listing(
              id,
              UUID.fromString((String) r.get(0)),
              (byte[]) r.get(1),
              (String) r.get(2),
              ((Number) r.get(3)).longValue(),
              ((Number) r.get(4)).longValue(),
              (String) r.get(5),
              (String) r.get(6));
        });
  }

  public CompletableFuture<Void> order(
      Operation op,
      List<Leg> legs,
      LootStore.Parcel item,
      String kind,
      String reference,
      Long rotation,
      String sku,
      int limit,
      long now) {
    return tx(
        db,
        c -> {
          require(
              number(c, "SELECT COUNT(*) FROM money_operations WHERE id=?", op.id()) == 0,
              "Order already reserved");
          if (kind.equals("AUCTION")) {
            require(
                update(
                        c,
                        "UPDATE market_listings SET state='RESERVED',buyer=?,operation=? WHERE id=?"
                            + " AND state='ACTIVE' AND seller!=? AND expires>? AND provider=?",
                        op.actor(),
                        op.id(),
                        reference,
                        op.actor(),
                        now,
                        op.provider())
                    == 1,
                "Listing sold, expired, or owned by you");
          }
          if (kind.equals("BLACK")) {
            require(
                number(
                        c,
                        "SELECT COUNT(*) FROM market_stock WHERE rotation=? AND sku=?",
                        rotation,
                        sku)
                    == 1,
                "Stock expired");
            require(
                number(
                        c,
                        "SELECT count FROM market_limits WHERE rotation=? AND sku=? AND player=?",
                        rotation,
                        sku,
                        op.actor())
                    < limit,
                "Rotation purchase limit reached");
            update(
                c,
                "INSERT INTO market_limits VALUES(?,?,?,1) ON CONFLICT(rotation,sku,player) DO"
                    + " UPDATE SET count=count+1",
                rotation,
                sku,
                op.actor());
          }
          plan(c, op, legs);
          update(
              c,
              "INSERT INTO market_orders VALUES(?,?,?,?,?,?,'RESERVED',?,?)",
              op.id(),
              op.actor(),
              kind,
              reference,
              item == null ? null : item.payload(),
              item == null ? null : item.id(),
              rotation,
              sku);
          return null;
        });
  }

  public CompletableFuture<Void> expire(long now) {
    return tx(
        db,
        c -> {
          for (var r :
              rows(
                  c,
                  "SELECT id,seller,payload FROM market_listings WHERE state='ACTIVE' AND"
                      + " expires<=?",
                  now)) {
            update(
                c,
                "INSERT OR IGNORE INTO market_returns VALUES(?,?,?,'PENDING')",
                "auction:" + r.get(0),
                r.get(1),
                r.get(2));
            update(
                c,
                "UPDATE market_listings SET state='EXPIRED' WHERE id=? AND state='ACTIVE'",
                r.get(0));
          }
          return null;
        });
  }

  public CompletableFuture<List<Returned>> returns() {
    return db.submit(
        c -> {
          List<Returned> list = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT source,owner,payload FROM market_returns WHERE state='PENDING' LIMIT 20"))
            list.add(
                new Returned(
                    (String) r.get(0), UUID.fromString((String) r.get(1)), (byte[]) r.get(2)));
          return list;
        });
  }

  public CompletableFuture<Void> returned(Returned r, LootStore.Parcel parcel, long now) {
    return tx(
        db,
        c -> {
          if (update(
                  c,
                  "UPDATE market_returns SET state='DONE' WHERE source=? AND state='PENDING'",
                  r.source())
              == 1) LootStore.enqueueInTransaction(c, parcel, r.source(), now);
          return null;
        });
  }

  public CompletableFuture<List<String>> stock(
      long rotation, List<Map.Entry<String, String>> definitions) {
    return tx(
        db,
        c -> {
          if (number(c, "SELECT COUNT(*) FROM market_stock WHERE rotation=?", rotation) == 0)
            for (var entry : definitions)
              update(
                  c,
                  "INSERT INTO market_stock VALUES(?,?,?)",
                  rotation,
                  entry.getKey(),
                  entry.getValue());
          List<String> list = new ArrayList<>();
          for (var r :
              rows(
                  c, "SELECT definition FROM market_stock WHERE rotation=? ORDER BY sku", rotation))
            list.add((String) r.get(0));
          return list;
        });
  }
}
