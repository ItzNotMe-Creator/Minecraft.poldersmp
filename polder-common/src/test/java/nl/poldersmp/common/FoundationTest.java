package nl.poldersmp.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FoundationTest {
  @TempDir Path temp;
  Database db;

  @BeforeEach
  void setup() {
    db = new Database(temp.resolve("nested/test.db"));
    db.initialize().join();
  }

  @AfterEach
  void close() {
    db.close();
  }

  UUID seed(long amount) {
    UUID id = UUID.randomUUID();
    db.submit(
            c -> {
              try (var p = c.prepareStatement("INSERT INTO accounts VALUES(?,?)")) {
                p.setString(1, id.toString());
                p.setLong(2, amount);
                p.executeUpdate();
              }
              return null;
            })
        .join();
    return id;
  }

  long balance(UUID id) {
    return db.submit(
            c -> {
              try (var p = c.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
                p.setString(1, id.toString());
                try (var rs = p.executeQuery()) {
                  return rs.next() ? rs.getLong(1) : 0L;
                }
              }
            })
        .join();
  }

  @Test
  void migrationIsIdempotent() {
    db.initialize().join();
    assertEquals(
        1,
        db.submit(
                c -> {
                  try (var s = c.createStatement();
                      var rs = s.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
                    return rs.getInt(1);
                  }
                })
            .join());
  }

  @Test
  void atomicTransferAndReplay() {
    UUID from = seed(100), to = UUID.randomUUID(), id = UUID.randomUUID();
    assertTrue(db.transfer(id, from, to, 40).join());
    assertFalse(db.transfer(id, from, to, 40).join());
    assertEquals(60, balance(from));
    assertEquals(40, balance(to));
  }

  @Test
  void insufficientFunds() {
    UUID from = seed(10), to = UUID.randomUUID();
    assertFalse(db.transfer(UUID.randomUUID(), from, to, 11).join());
    assertEquals(10, balance(from));
    assertEquals(0, balance(to));
  }

  @Test
  void simultaneousSpending() {
    UUID from = seed(100), to = UUID.randomUUID();
    var a = db.transfer(UUID.randomUUID(), from, to, 80);
    var b = db.transfer(UUID.randomUUID(), from, to, 80);
    assertNotEquals(a.join(), b.join());
    assertEquals(20, balance(from));
    assertEquals(80, balance(to));
  }

  @Test
  void rollbackOnCreditFailure() {
    UUID from = seed(20), to = seed(Long.MAX_VALUE);
    assertThrows(
        CompletionException.class, () -> db.transfer(UUID.randomUUID(), from, to, 1).join());
    assertEquals(20, balance(from));
  }

  @Test
  void backupIsConsistent() throws Exception {
    UUID id = seed(42);
    Path backup = db.backup(temp.resolve("backup.db")).join();
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + backup);
        var s = c.createStatement();
        var rs = s.executeQuery("SELECT balance FROM accounts WHERE uuid='" + id + "'")) {
      assertEquals(42, rs.getLong(1));
    }
    assertThrows(CompletionException.class, () -> db.backup(backup).join());
  }

  @Test
  void configValidation() throws Exception {
    Path config = temp.resolve("config");
    assertTrue(Settings.load(config).enabled());
    Files.writeString(config.resolve("foundation.properties"), "module.enabled=maybe");
    assertThrows(java.io.IOException.class, () -> Settings.load(config));
  }

  @Test
  void invalidTransfers() {
    UUID id = seed(10);
    assertThrows(CompletionException.class, () -> db.transfer(UUID.randomUUID(), id, id, 1).join());
    assertThrows(
        CompletionException.class,
        () -> db.transfer(UUID.randomUUID(), id, UUID.randomUUID(), 0).join());
  }

  @Test
  void rejectsNewerSchemaWithoutLosingBalances() {
    UUID id = seed(99);
    db.submit(
            c -> {
              try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO schema_migrations VALUES(2)");
              }
              return null;
            })
        .join();
    assertThrows(CompletionException.class, () -> db.initialize().join());
    assertEquals(99, balance(id));
  }

  @Test
  void closeDrainsAcceptedWorkAndRejectsNewWork() {
    var accepted = db.submit(c -> 42);
    db.close();
    assertEquals(42, accepted.join());
    assertThrows(CompletionException.class, () -> db.submit(c -> 0).join());
  }

  @Test
  void preservesExistingConfiguration() throws Exception {
    Path directory = temp.resolve("existing");
    Files.createDirectories(directory);
    Path file = directory.resolve("foundation.properties");
    String text = "module.enabled=false\ndatabase.enabled=false\ndebug=true\n";
    Files.writeString(file, text);
    var settings = Settings.load(directory);
    assertFalse(settings.enabled());
    assertFalse(settings.databaseEnabled());
    assertTrue(settings.debug());
    assertEquals(text, Files.readString(file));
  }
}
