package nl.poldersmp.core.competition;

import java.sql.*;
import java.util.UUID;

/**
 * Transactional economy contract. Implementations must use the supplied transaction, never external
 * side effects.
 */
public interface Economy {
  void debit(Connection c, UUID player, long amount) throws SQLException;

  void credit(Connection c, UUID player, long amount) throws SQLException;

  final class Foundation implements Economy {
    public void debit(Connection c, UUID player, long amount) throws SQLException {
      if (amount < 0) throw new SQLException("Negative debit");
      if (amount == 0) return;
      try (var p =
          c.prepareStatement("UPDATE accounts SET balance=balance-? WHERE uuid=? AND balance>=?")) {
        p.setLong(1, amount);
        p.setString(2, player.toString());
        p.setLong(3, amount);
        if (p.executeUpdate() != 1) throw new SQLException("Insufficient existing economy balance");
      }
    }

    public void credit(Connection c, UUID player, long amount) throws SQLException {
      if (amount <= 0) throw new SQLException("Invalid credit");
      try (var p =
          c.prepareStatement(
              "INSERT INTO accounts(uuid,balance) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET"
                  + " balance=balance+excluded.balance")) {
        p.setString(1, player.toString());
        p.setLong(2, amount);
        p.executeUpdate();
      }
    }
  }
}
