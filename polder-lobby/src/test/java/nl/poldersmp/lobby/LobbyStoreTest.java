package nl.poldersmp.lobby;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.lobby.data.LobbyStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LobbyStoreTest {
  @TempDir Path dir;

  private LobbyStore open() {
    LobbyStore store = new LobbyStore(dir.resolve("lobby.db"));
    store.migrate().join();
    return store;
  }

  @Test
  void selectionsAndUnlocksSurviveReopening() {
    UUID player = UUID.randomUUID();
    try (var db = open()) {
      db.join(player, "Persistent").join();
      db.reward(player, "earned", 30, "", 1).join();
      db.buy(player, "pet", "pet", 20).join();
    }
    try (var db = open()) {
      var profile = db.profile(player).join();
      assertEquals(10, profile.tokens());
      assertEquals("pet", profile.selections().get("pet"));
      assertTrue(profile.unlocks().contains("pet"));
      assertFalse(db.buy(player, "pet", "pet", 20).join());
      assertEquals(10, db.profile(player).join().tokens());
    }
  }

  @Test
  void futureSchemaIsRefusedWithoutDowngrading() throws Exception {
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("lobby.db"));
        var q = c.createStatement()) {
      q.execute("CREATE TABLE lobby_schema(version INTEGER PRIMARY KEY)");
      q.execute("INSERT INTO lobby_schema VALUES(2)");
    }
    try (var db = new LobbyStore(dir.resolve("lobby.db"))) {
      assertThrows(Exception.class, () -> db.migrate().join());
    }
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("lobby.db"));
        var q = c.createStatement();
        var r = q.executeQuery("SELECT version FROM lobby_schema")) {
      assertTrue(r.next());
      assertEquals(2, r.getInt(1));
    }
  }

  @Test
  void dailyIsAtomicAcrossConcurrentClaimsAndRestart() {
    UUID p = UUID.randomUUID();
    try (var db = open()) {
      db.join(p, "Alice").join();
      List<CompletableFuture<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++)
        calls.add(db.daily(p, LocalDate.of(2026, 11, 13), 5, "trail_spark", 1));
      long success =
          calls.stream()
              .filter(
                  f -> {
                    try {
                      f.join();
                      return true;
                    } catch (Exception e) {
                      return false;
                    }
                  })
              .count();
      assertEquals(1, success);
      assertEquals(5, db.profile(p).join().tokens());
      assertTrue(db.profile(p).join().unlocks().contains("trail_spark"));
    }
    try (var db = open()) {
      assertThrows(Exception.class, () -> db.daily(p, LocalDate.of(2026, 11, 13), 5, "", 2).join());
      assertThrows(Exception.class, () -> db.daily(p, LocalDate.of(2026, 11, 12), 5, "", 2).join());
      assertEquals(2, db.daily(p, LocalDate.of(2026, 11, 14), 5, "", 3).join());
      assertEquals(1, db.daily(p, LocalDate.of(2026, 11, 16), 5, "", 4).join());
      assertEquals(15, db.profile(p).join().tokens());
    }
  }

  @Test
  void cosmeticsNeverDoubleChargeOrGrantNegativeBalances() {
    UUID p = UUID.randomUUID();
    try (var db = open()) {
      db.join(p, "Alice").join();
      assertThrows(Exception.class, () -> db.buy(p, "hat", "hat", 10).join());
      assertTrue(db.profile(p).join().unlocks().isEmpty());
      db.reward(p, "secret:a", 10, "", 1).join();
      assertFalse(db.reward(p, "secret:a", 10, "", 2).join());
      assertTrue(db.buy(p, "hat", "hat", 10).join());
      assertFalse(db.buy(p, "hat", "hat", 10).join());
      assertEquals(0, db.profile(p).join().tokens());
      assertEquals("hat", db.profile(p).join().selections().get("hat"));
      db.clear(p).join();
      assertTrue(db.profile(p).join().selections().isEmpty());
      assertTrue(db.profile(p).join().unlocks().contains("hat"));
      assertThrows(Exception.class, () -> db.reward(p, "bad", -1, "", 1).join());
      assertFalse(db.profile(p).join().achievements().contains("bad"));
    }
  }

  @Test
  void parkourHasRunIdempotencyOneRewardAndMinimumPersonalBest() {
    UUID p = UUID.randomUUID(), run = UUID.randomUUID();
    try (var db = open()) {
      db.join(p, "Alice").join();
      assertTrue(db.finish(run, p, "a", 5000, 5, 1).join());
      assertFalse(db.finish(run, p, "a", 1, 5, 1).join());
      assertFalse(db.finish(UUID.randomUUID(), p, "a", 6000, 5, 1).join());
      assertTrue(db.finish(UUID.randomUUID(), p, "a", 4000, 5, 1).join());
      assertEquals(4000, db.profile(p).join().records().get("a"));
      assertEquals(5, db.profile(p).join().tokens());
      assertTrue(db.top("a").join().getFirst().contains("4000"));
      assertThrows(Exception.class, () -> db.finish(UUID.randomUUID(), p, "a", 0, 5, 1).join());
    }
  }

  @Test
  void playtimeUsesUuidAndPersistsNameChanges() {
    UUID p = UUID.randomUUID();
    try (var db = open()) {
      db.join(p, "Before").join();
      db.time(Map.of(p, 60L)).join();
      db.join(p, "After").join();
      assertEquals(60, db.profile(p).join().playtime());
      assertEquals("After", db.profile(p).join().name());
    }
    try (var db = open()) {
      assertEquals(60, db.profile(p).join().playtime());
    }
  }

  @Test
  void cancelledEventsCannotPayAndRestartsInterrupt() {
    UUID id = UUID.randomUUID(), a = UUID.randomUUID();
    try (var db = open()) {
      db.join(a, "A").join();
      db.event(new LobbyStore.Event(id, "race", "a", "ACTIVE", 1, Long.MAX_VALUE)).join();
      db.point(id, a, "race", 2000).join();
      db.status(id, "CANCELLED").join();
      assertTrue(db.winners(id, true, 1).join().isEmpty());
    }
    try (var db = open()) {
      UUID next = UUID.randomUUID();
      db.event(new LobbyStore.Event(next, "race", "a", "ACTIVE", 1, Long.MAX_VALUE)).join();
    }
    try (var db = open()) {
      assertEquals(0, db.profile(a).join().tokens());
    }
  }

  @Test
  void eventScoresAreDistinctAndRewardsOnce() {
    UUID id = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    try (var db = open()) {
      db.join(a, "A").join();
      db.join(b, "B").join();
      db.event(new LobbyStore.Event(id, "collectible", "", "ACTIVE", 1, Long.MAX_VALUE)).join();
      assertThrows(
          Exception.class,
          () ->
              db.event(
                      new LobbyStore.Event(
                          UUID.randomUUID(), "race", "a", "WARNING", 1, Long.MAX_VALUE))
                  .join());
      db.point(id, a, "star1", 1).join();
      db.point(id, a, "star1", 1).join();
      db.point(id, b, "star1", 1).join();
      db.point(id, b, "star2", 1).join();
      assertEquals(List.of(b, a), db.winners(id, false, 2).join());
      assertTrue(db.winners(id, false, 2).join().isEmpty());
      assertEquals(10, db.profile(b).join().tokens());
      assertEquals(3, db.profile(a).join().tokens());
      assertTrue(db.profile(b).join().unlocks().contains("title_champion"));
    }
  }

  @Test
  void raceWinnerUsesFastestTimeAndMinimumParticipants() {
    UUID id = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    try (var db = open()) {
      db.join(a, "A").join();
      db.join(b, "B").join();
      db.event(new LobbyStore.Event(id, "race", "a", "ACTIVE", 1, Long.MAX_VALUE)).join();
      db.point(id, a, "race", 2000).join();
      db.point(id, a, "race", 3000).join();
      db.point(id, b, "race", 1000).join();
      assertEquals(List.of(b, a), db.winners(id, true, 2).join());
      UUID next = UUID.randomUUID();
      db.event(new LobbyStore.Event(next, "race", "a", "ACTIVE", 1, Long.MAX_VALUE)).join();
      db.point(next, a, "race", 1000).join();
      assertTrue(db.winners(next, true, 2).join().isEmpty());
      assertEquals(3, db.profile(a).join().tokens());
    }
  }

  @Test
  void dateBoundaryUsesConfiguredTimezone() {
    Instant utc = Instant.parse("2026-11-13T23:30:00Z");
    assertEquals(
        LocalDate.of(2026, 11, 14), utc.atZone(ZoneId.of("Europe/Amsterdam")).toLocalDate());
  }
}
