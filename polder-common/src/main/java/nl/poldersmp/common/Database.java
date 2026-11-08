package nl.poldersmp.common;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;

public final class Database implements AutoCloseable {
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "polder-sqlite"));
  private final Path file;
  private boolean closed;

  public Database(Path file) {
    this.file = file.toAbsolutePath();
  }

  @FunctionalInterface
  public interface Work<T> {
    T run(Connection c) throws Exception;
  }

  public synchronized <T> CompletableFuture<T> submit(Work<T> work) {
    if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            Files.createDirectories(file.getParent());
          } catch (java.io.IOException e) {
            throw new CompletionException(e);
          }
          try (var c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var s = c.createStatement()) {
              s.execute("PRAGMA busy_timeout=5000");
              s.execute("PRAGMA foreign_keys=ON");
            }
            return work.run(c);
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        worker);
  }

  public CompletableFuture<Void> initialize() {
    return submit(
        c -> {
          Files.createDirectories(file.getParent());
          c.setAutoCommit(false);
          try (var s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY)");
            int version;
            try (var rs =
                s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
              version = rs.getInt(1);
            }
            if (version > 1) throw new SQLException("Database schema newer than plugin");
            if (version < 1) {
              try (var input =
                  Database.class.getResourceAsStream("/db/migration/V1__foundation.sql")) {
                if (input == null) throw new java.io.IOException("Missing migration 1");
                String sql =
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String statement : sql.split(";")) {
                  if (!statement.isBlank()) s.executeUpdate(statement);
                }
              }
              s.executeUpdate("INSERT INTO schema_migrations VALUES(1)");
            }
            c.commit();
            return null;
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        });
  }

  public CompletableFuture<Boolean> transfer(UUID id, UUID from, UUID to, long amount) {
    if (amount <= 0 || from.equals(to))
      return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid transfer"));
    return submit(
        c -> {
          c.setAutoCommit(false);
          try {
            try (var p = c.prepareStatement("SELECT 1 FROM transfers WHERE id=?")) {
              p.setString(1, id.toString());
              try (var rs = p.executeQuery()) {
                if (rs.next()) {
                  c.rollback();
                  return false;
                }
              }
            }
            try (var p =
                c.prepareStatement(
                    "UPDATE accounts SET balance=balance-? WHERE uuid=? AND balance>=?")) {
              p.setLong(1, amount);
              p.setString(2, from.toString());
              p.setLong(3, amount);
              if (p.executeUpdate() != 1) {
                c.rollback();
                return false;
              }
            }
            try (var p =
                c.prepareStatement(
                    "INSERT INTO accounts(uuid,balance) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET"
                        + " balance=balance+excluded.balance")) {
              p.setString(1, to.toString());
              p.setLong(2, amount);
              p.executeUpdate();
            }
            try (var p = c.prepareStatement("INSERT INTO transfers VALUES(?,?,?,?)")) {
              p.setString(1, id.toString());
              p.setString(2, from.toString());
              p.setString(3, to.toString());
              p.setLong(4, amount);
              p.executeUpdate();
            }
            c.commit();
            return true;
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        });
  }

  public CompletableFuture<Path> backup(Path destination) {
    return submit(
        c -> {
          Path target = destination.toAbsolutePath();
          Files.createDirectories(target.getParent());
          if (Files.exists(target)) throw new java.io.IOException("Backup already exists");
          try (var s = c.createStatement()) {
            s.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
          }
          return target;
        });
  }

  @Override
  public synchronized void close() {
    closed = true;
    worker.shutdown();
  }
}
