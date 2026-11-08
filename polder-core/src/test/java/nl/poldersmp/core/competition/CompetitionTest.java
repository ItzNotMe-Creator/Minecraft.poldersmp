package nl.poldersmp.core.competition;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CompetitionTest {
  @TempDir Path folder;
  Database db;
  CompetitionStore store;

  @BeforeEach
  void setup() {
    db = new Database(folder.resolve("ledger.db"));
    db.initialize().join();
    store = new CompetitionStore(db, new Economy.Foundation());
    store.migrate().join();
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

  long balance(UUID player) {
    return db.submit(
            c -> {
              try (var p = c.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
                p.setString(1, player.toString());
                try (var rs = p.executeQuery()) {
                  return rs.next() ? rs.getLong(1) : 0L;
                }
              }
            })
        .join();
  }

  @Test
  void contributionsEscrowAndRequestReplay() {
    UUID a = seed(100), b = seed(200), target = UUID.randomUUID(), request = UUID.randomUUID();
    assertEquals(40, store.place(request, a, target, 40, 1000).join());
    assertEquals(40, store.place(request, a, target, 40, 1000).join());
    assertEquals(90, store.place(UUID.randomUUID(), b, target, 50, 1000).join());
    assertEquals(60, balance(a));
    assertEquals(150, balance(b));
    assertEquals(90, store.bounties().join().getFirst().amount());
  }

  @Test
  void insufficientFundsRollback() {
    UUID giver = seed(10), target = UUID.randomUUID();
    assertThrows(
        CompletionException.class,
        () -> store.place(UUID.randomUUID(), giver, target, 11, 1000).join());
    assertEquals(10, balance(giver));
    assertTrue(store.bounties().join().isEmpty());
  }

  @Test
  void claimIsReservedOnceAndPayoutIsIdempotent() {
    UUID giver = seed(100),
        victim = UUID.randomUUID(),
        killer = UUID.randomUUID(),
        death = UUID.randomUUID();
    store.place(UUID.randomUUID(), giver, victim, 100, 1000).join();
    assertEquals(100, store.claim(death, killer, victim, 2000, 100).join());
    assertEquals(0, store.claim(death, killer, victim, 2000, 100).join());
    assertEquals(1, store.pending().join());
    assertEquals(0, balance(killer));
    assertEquals(1, store.recoverPayouts(2100).join());
    assertEquals(0, store.recoverPayouts(2200).join());
    assertEquals(100, balance(killer));
    assertTrue(store.bounties().join().isEmpty());
  }

  @Test
  void competingClaimsOnlyOneWins() {
    UUID giver = seed(100), victim = UUID.randomUUID();
    store.place(UUID.randomUUID(), giver, victim, 100, 1).join();
    var a = store.claim(UUID.randomUUID(), UUID.randomUUID(), victim, 10, 0);
    var b = store.claim(UUID.randomUUID(), UUID.randomUUID(), victim, 10, 0);
    assertEquals(100, a.join() + b.join());
    assertEquals(1, store.pending().join());
  }

  @Test
  void farmingProtectionPersistsIncludingReversePair() {
    UUID giver = seed(300), victim = UUID.randomUUID(), killer = UUID.randomUUID();
    store.place(UUID.randomUUID(), giver, victim, 100, 1000).join();
    store.claim(UUID.randomUUID(), killer, victim, 2000, 10000).join();
    store.place(UUID.randomUUID(), giver, victim, 100, 3000).join();
    assertEquals(0, store.claim(UUID.randomUUID(), killer, victim, 4000, 10000).join());
    store.place(UUID.randomUUID(), giver, killer, 100, 3000).join();
    assertEquals(0, store.claim(UUID.randomUUID(), victim, killer, 4000, 10000).join());
    assertEquals(0, store.claim(UUID.randomUUID(), victim, victim, 4000, 0).join());
    assertFalse(CompetitionRules.eligibleClaim(killer, victim, true, 20000, 2000, 1000));
    assertFalse(CompetitionRules.eligibleClaim(victim, victim, false, 20000, 0, 1000));
  }

  @Test
  void refundsAndAdminSetConserveMoney() {
    UUID a = seed(100), b = seed(100), target = UUID.randomUUID();
    store.place(UUID.randomUUID(), a, target, 80, 1).join();
    store.place(UUID.randomUUID(), b, target, 20, 2).join();
    UUID request = UUID.randomUUID();
    store.setBounty(request, target, 30, b, 3).join();
    store.setBounty(request, target, 30, b, 3).join();
    store.recoverPayouts(4).join();
    assertEquals(90, balance(a));
    assertEquals(80, balance(b));
    store.setBounty(UUID.randomUUID(), target, 50, b, 5).join();
    assertEquals(60, balance(b));
    store.setBounty(UUID.randomUUID(), target, 0, null, 6).join();
    store.recoverPayouts(7).join();
    assertEquals(100, balance(a));
    assertEquals(100, balance(b));
  }

  @Test
  void pendingPayoutSurvivesRestart() {
    UUID giver = seed(100), target = UUID.randomUUID(), killer = UUID.randomUUID();
    store.place(UUID.randomUUID(), giver, target, 100, 1).join();
    store.claim(UUID.randomUUID(), killer, target, 2, 0).join();
    db.close();
    db = new Database(folder.resolve("ledger.db"));
    db.initialize().join();
    store = new CompetitionStore(db, new Economy.Foundation());
    store.migrate().join();
    store.recoverPayouts(3).join();
    assertEquals(100, balance(killer));
    assertEquals(0, store.pending().join());
  }

  @Test
  void badPayoutDoesNotBlockOthers() {
    UUID giver = seed(20),
        target = UUID.randomUUID(),
        overflow = seed(Long.MAX_VALUE),
        other = UUID.randomUUID();
    store.place(UUID.randomUUID(), giver, target, 10, 1).join();
    store.claim(UUID.randomUUID(), overflow, target, 2, 0).join();
    store.place(UUID.randomUUID(), giver, target, 10, 3).join();
    store.claim(UUID.randomUUID(), other, target, 4, 0).join();
    assertEquals(1, store.recoverPayouts(5).join());
    assertEquals(10, balance(other));
    assertEquals(Long.MAX_VALUE, balance(overflow));
    assertEquals(1, store.pending().join());
  }

  @Test
  void captureContestationSwitchAndWinner() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    var progress = new CompetitionRules.Capture(null, 0);
    progress = progress.advance(Set.of(a), true, 60, false);
    assertTrue(progress.winner(120).isEmpty());
    assertEquals(60, progress.advance(Set.of(a, b), true, 100, false).progress());
    assertEquals(0, progress.advance(Set.of(a, b), true, 100, true).progress());
    assertEquals(1, progress.advance(Set.of(b), true, 1, false).progress());
    assertEquals(0, progress.advance(Set.of(a), false, 1, false).progress());
    progress = progress.advance(Set.of(a), true, 60, false);
    assertEquals(a, progress.winner(120).orElseThrow());
    assertEquals(0, progress.advance(Set.of(), true, 1, false).progress());
  }

  @Test
  void weeklyAmsterdamAndDst() {
    ZoneId zone = ZoneId.of("Europe/Amsterdam");
    assertEquals(
        Instant.parse("2026-11-14T19:00:00Z"),
        CompetitionRules.nextWeekly(
            Instant.parse("2026-11-08T12:00:00Z"), zone, DayOfWeek.SATURDAY, LocalTime.of(20, 0)));
    assertEquals(
        Instant.parse("2026-07-04T18:00:00Z"),
        CompetitionRules.nextWeekly(
            Instant.parse("2026-07-01T00:00:00Z"), zone, DayOfWeek.SATURDAY, LocalTime.of(20, 0)));
    assertEquals(
        Instant.parse("2026-10-31T19:00:00Z"),
        CompetitionRules.nextWeekly(
            Instant.parse("2026-10-24T18:00:00Z"), zone, DayOfWeek.SATURDAY, LocalTime.of(20, 0)));
  }

  @Test
  void kingRewardsOnceAndRestoresCurrentState() {
    UUID treasury = seed(100), winner = UUID.randomUUID(), id = UUID.randomUUID();
    var event = store.start(id, "weekly:test", 1000, 100, 10000, 60, treasury).join();
    assertEquals(40, balance(treasury));
    store
        .checkpoint(
            new CompetitionStore.KingEvent(
                id,
                event.slot(),
                "ACTIVE",
                event.starts(),
                event.ends(),
                winner,
                120,
                null,
                60,
                treasury),
            Set.of(winner))
        .join();
    assertEquals(id, store.active().join().id());
    assertTrue(store.finish(id, winner, 2000).join());
    assertFalse(store.finish(id, winner, 2001).join());
    store.recoverPayouts(2100).join();
    assertEquals(60, balance(winner));
    assertEquals(winner.toString(), store.state("current_king").join());
    assertEquals(1, store.kingHistory(true).join().size());
    assertNull(store.active().join());
  }

  @Test
  void kingCancelRefundsAndDuplicateSlotRollsBack() {
    UUID treasury = seed(100), id = UUID.randomUUID();
    store.start(id, "same-slot", 1, 1, 100, 30, treasury).join();
    assertThrows(
        CompletionException.class,
        () -> store.start(UUID.randomUUID(), "other-slot", 1, 1, 100, 30, treasury).join());
    assertEquals(70, balance(treasury));
    assertTrue(store.finish(id, null, 10).join());
    store.recoverPayouts(11).join();
    assertEquals(100, balance(treasury));
    assertThrows(
        CompletionException.class,
        () -> store.start(UUID.randomUUID(), "same-slot", 100, 1, 100, 30, treasury).join());
    assertEquals(100, balance(treasury));
  }

  @Test
  void cannotSelectNonparticipantKing() {
    UUID treasury = seed(100);
    var event = store.start(UUID.randomUUID(), "test", 1, 1, 100, 20, treasury).join();
    assertThrows(
        CompletionException.class, () -> store.finish(event.id(), UUID.randomUUID(), 10).join());
    assertNotNull(store.active().join());
    assertEquals(0, store.pending().join());
  }

  @Test
  void schemaMigrationIsIdempotentAndRejectsNewer() {
    store.migrate().join();
    db.submit(
            c -> {
              try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO competition_schema VALUES(2)");
              }
              return null;
            })
        .join();
    assertThrows(CompletionException.class, () -> store.migrate().join());
  }

  @Test
  void defaultConfigurationAndValidation() throws Exception {
    var bounty = new org.bukkit.configuration.file.YamlConfiguration();
    var king = new org.bukkit.configuration.file.YamlConfiguration();
    try (var in = getClass().getResourceAsStream("/bounties.yml")) {
      bounty.load(new java.io.InputStreamReader(in));
    }
    try (var in = getClass().getResourceAsStream("/king.yml")) {
      king.load(new java.io.InputStreamReader(in));
    }
    assertDoesNotThrow(() -> new CompetitionConfig(bounty, king));
    king.set("capture-radius", Double.NaN);
    assertThrows(IllegalArgumentException.class, () -> new CompetitionConfig(bounty, king));
    king.set("capture-radius", 7);
    king.set("rewards.money", 10);
    assertThrows(IllegalArgumentException.class, () -> new CompetitionConfig(bounty, king));
  }

  @Test
  void liveKingCheckpointAndScheduleSurviveRestart() {
    UUID treasury = seed(100), player = UUID.randomUUID();
    var original =
        store.start(UUID.randomUUID(), "persisted-slot", 1000, 100, 10000, 10, treasury).join();
    var active =
        new CompetitionStore.KingEvent(
            original.id(),
            original.slot(),
            "ACTIVE",
            original.starts(),
            original.ends(),
            player,
            42,
            null,
            original.prize(),
            treasury);
    store.checkpoint(active, Set.of(player)).join();
    store.state("next_event", "2026-11-14T19:00:00Z").join();
    db.close();
    db = new Database(folder.resolve("ledger.db"));
    db.initialize().join();
    store = new CompetitionStore(db, new Economy.Foundation());
    store.migrate().join();
    assertEquals(player, store.active().join().candidate());
    assertEquals(42, store.active().join().progress());
    assertEquals("2026-11-14T19:00:00Z", store.state("next_event").join());
  }

  @Test
  void replayedAdminRequestDoesNotRefundNewContributions() {
    UUID payer = seed(200), target = UUID.randomUUID(), request = UUID.randomUUID();
    store.place(UUID.randomUUID(), payer, target, 100, 1).join();
    store.setBounty(request, target, 50, payer, 2).join();
    store.place(UUID.randomUUID(), payer, target, 20, 3).join();
    assertEquals(70, store.setBounty(request, target, 50, payer, 4).join());
    store.recoverPayouts(5).join();
    assertEquals(130, balance(payer));
  }

  @Test
  void leaderboardRanksTotalsAndContributors() {
    UUID a = seed(100), b = seed(100), first = UUID.randomUUID(), second = UUID.randomUUID();
    store.place(UUID.randomUUID(), a, first, 50, 1).join();
    store.place(UUID.randomUUID(), b, first, 50, 2).join();
    store.place(UUID.randomUUID(), a, second, 10, 3).join();
    var board = store.bounties().join();
    assertEquals(first, board.getFirst().player());
    assertEquals(1, board.getFirst().rank());
    assertEquals(2, board.getFirst().contributors());
    assertEquals(100, board.getFirst().amount());
    assertEquals(2, board.getLast().rank());
  }
}
