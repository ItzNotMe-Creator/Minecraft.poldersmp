package nl.poldersmp.core.events;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.competition.Economy;
import nl.poldersmp.core.loot.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EventTest {
  @TempDir Path folder;
  Database db;
  EventStore store;

  @BeforeEach
  void setup() {
    db = new Database(folder.resolve("polder.db"));
    db.initialize().join();
    store = new EventStore(db, new Economy.Foundation());
    store.migrate().join();
  }

  @AfterEach
  void close() {
    db.close();
  }

  UUID seed(long amount) {
    UUID owner = UUID.randomUUID();
    db.submit(
            c -> {
              try (var p = c.prepareStatement("INSERT INTO accounts VALUES(?,?)")) {
                p.setString(1, owner.toString());
                p.setLong(2, amount);
                p.executeUpdate();
              }
              return null;
            })
        .join();
    return owner;
  }

  long balance(UUID owner) {
    return db.submit(
            c -> {
              try (var p = c.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
                p.setString(1, owner.toString());
                try (var r = p.executeQuery()) {
                  return r.next() ? r.getLong(1) : 0L;
                }
              }
            })
        .join();
  }

  EventStore.State plan(EventRules.Type type, long escrow, UUID treasury, int chests) {
    var e =
        new EventStore.State(
            UUID.randomUUID(),
            type,
            "PLANNED",
            UUID.randomUUID(),
            8,
            64,
            8,
            1000,
            10000,
            0,
            100,
            null,
            0,
            "saved-settings",
            escrow,
            treasury,
            false);
    List<EventStore.Chest> cache = new ArrayList<>();
    for (int i = 0; i < chests; i++) cache.add(new EventStore.Chest(i, 8 + i, 64, 8, null));
    store
        .plan(
            e,
            List.of(new LootStore.BlockPlan(8, 64, 8, "minecraft:air", "minecraft:barrel")),
            cache)
        .join();
    return e;
  }

  void phase(EventStore.State e, String from, String to) {
    assertTrue(store.phase(e.id(), from, to, 1, e.health(), List.of(), Map.of()).join());
  }

  EventStore.Award key(EventStore.State e, UUID owner) {
    return new EventStore.Award(
        "event:" + e.id() + ":" + owner + ":key", e.id(), owner, "KEY", "RARE", null, null, 0);
  }

  EventStore.Award cash(EventStore.State e, UUID owner, long amount) {
    return new EventStore.Award(
        "event:" + e.id() + ":" + owner + ":cash", e.id(), owner, "CASH", null, null, null, amount);
  }

  @Test
  void defaultConfigAndWeightedChoices() throws Exception {
    var y = new YamlConfiguration();
    try (var in = getClass().getResourceAsStream("/events.yml")) {
      y.load(new java.io.InputStreamReader(in));
    }
    var config = new EventConfig(y);
    assertEquals(5, config.choices().size());
    assertEquals(100, LootRules.total(config.choices()));
    Map<EventRules.Type, Integer> counts = new EnumMap<>(EventRules.Type.class);
    for (long n = 0; n < 100; n++)
      counts.merge(LootRules.select(config.choices(), n), 1, Integer::sum);
    assertEquals(25, counts.get(EventRules.Type.METEOR));
    assertEquals(15, counts.get(EventRules.Type.PIRATE_SHIP));
    assertEquals(config.choices(), EventConfig.restore(y.saveToString()).choices());
    y.set("types.BOSS.health", Double.NaN);
    assertThrows(IllegalArgumentException.class, () -> new EventConfig(y));
  }

  @Test
  void schedulingPersistsAndNeverAccruesOfflineProgress() {
    Instant now = Instant.parse("2026-11-08T12:00:00Z");
    assertEquals(now.plusSeconds(3600), EventRules.next(now, 3600));
    assertThrows(IllegalArgumentException.class, () -> EventRules.next(now, 0));
    store.setting("next", now.plusSeconds(3600).toString()).join();
    assertEquals("2026-11-08T13:00:00Z", store.setting("next").join());
  }

  @Test
  void fiveSeparateHandlersHaveBoundedNonOverlappingStructures() {
    List<WorldEvent> handlers =
        List.of(
            new MeteorEvent(),
            new SupplyDropEvent(),
            new BossEvent(),
            new PirateShipEvent(),
            new DungeonEvent());
    assertEquals(5, handlers.stream().map(WorldEvent::type).distinct().count());
    for (var h : handlers) {
      Set<String> coordinates = new HashSet<>();
      for (var b : h.structure()) {
        assertTrue(Math.abs(b.x()) <= 4 && Math.abs(b.z()) <= 4 && b.y() >= 0 && b.y() <= 5);
        assertTrue(coordinates.add(b.x() + ":" + b.y() + ":" + b.z()));
      }
    }
    assertEquals(2, new PirateShipEvent().chests().size());
    assertTrue(new BossEvent().chests().isEmpty());
    assertTrue(new PirateShipEvent().ocean());
  }

  @Test
  void planningReservesTreasuryAndOneGlobalEvent() {
    UUID treasury = seed(200);
    var e = plan(EventRules.Type.BOSS, 100, treasury, 0);
    assertEquals(100, balance(treasury));
    assertThrows(CompletionException.class, () -> plan(EventRules.Type.METEOR, 50, treasury, 1));
    assertEquals(100, balance(treasury));
    assertEquals(e.id(), store.active().join().id());
  }

  @Test
  void warningAndPhaseTransitionsAreCompareAndSet() {
    var e = plan(EventRules.Type.METEOR, 0, UUID.randomUUID(), 1);
    phase(e, "PLANNED", "WARNING");
    assertFalse(store.phase(e.id(), "PLANNED", "ACTIVE", 1, 0, List.of(), Map.of()).join());
    assertEquals("WARNING", store.active().join().status());
    phase(e, "WARNING", "ACTIVE");
    phase(e, "ACTIVE", "LOOT");
  }

  @Test
  void treasureIsClaimedAndReservedExactlyOnce() {
    var e = plan(EventRules.Type.SUPPLY_DROP, 0, UUID.randomUUID(), 1);
    UUID player = UUID.randomUUID();
    assertFalse(store.claimAt(e, 0, player, List.of(key(e, player)), 1100, false, 1000).join());
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    phase(e, "ACTIVE", "LOOT");
    assertTrue(store.claimAt(e, 0, player, List.of(key(e, player)), 1100, false, 1000).join());
    assertFalse(store.claimAt(e, 0, player, List.of(key(e, player)), 1100, false, 1000).join());
    assertEquals(1, store.pending().join().size());
    assertEquals("CLEANUP", store.active().join().status());
  }

  @Test
  void shipChestsAreIndependentButEachLimited() {
    var e = plan(EventRules.Type.PIRATE_SHIP, 0, UUID.randomUUID(), 2);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    phase(e, "ACTIVE", "LOOT");
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    assertTrue(store.claimAt(e, 0, a, List.of(key(e, a)), 1100, true, 1000).join());
    assertEquals("LOOT", store.active().join().status());
    assertFalse(store.claimAt(e, 0, b, List.of(key(e, b)), 1100, true, 1000).join());
    assertTrue(store.claimAt(e, 1, b, List.of(key(e, b)), 1100, true, 1000).join());
    assertTrue(store.active().join().preserve());
    assertEquals(2, store.pending().join().size());
  }

  @Test
  void bossFinishAndParticipationCannotRepeat() {
    var e = plan(EventRules.Type.BOSS, 0, UUID.randomUUID(), 0);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    UUID player = UUID.randomUUID();
    assertTrue(
        store.finish(e, player, List.of(key(e, player)), Map.of(player, 40.0), 1100, false).join());
    assertFalse(
        store.finish(e, player, List.of(key(e, player)), Map.of(player, 40.0), 1100, false).join());
    assertEquals(1, store.pending().join().size());
    store.archive(e.id()).join();
    assertNull(store.active().join());
    assertFalse(store.blocks(e.id()).join().isEmpty());
  }

  @Test
  void damageCheckpointsDoNotDoubleCount() {
    var e = plan(EventRules.Type.BOSS, 0, UUID.randomUUID(), 0);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    UUID player = UUID.randomUUID();
    store.checkpoint(e, Map.of(player, 20.0)).join();
    store.checkpoint(e, Map.of(player, 20.0)).join();
    store.checkpoint(e, Map.of(player, 10.0)).join();
    assertEquals(20, store.damage(e.id()).join().get(player));
    assertFalse(store.statistics(true).join().isEmpty());
  }

  @Test
  void damageWinnerSelectionIsDeterministicAndThresholded() {
    UUID low = new UUID(0, 1), high = new UUID(0, 2);
    assertEquals(low, EventRules.winner(Map.of(low, 20.0, high, 20.0), 5));
    assertEquals(high, EventRules.winner(Map.of(low, 2.0, high, 10.0), 5));
    assertNull(EventRules.winner(Map.of(low, 4.0), 5));
    assertNull(EventRules.winner(Map.of(low, Double.NaN), 5));
  }

  @Test
  void restartRestoresStateSettingsAndManifest() {
    var e = plan(EventRules.Type.DUNGEON, 0, UUID.randomUUID(), 1);
    phase(e, "PLANNED", "WARNING");
    store.setting("next", "2026-11-08T13:00:00Z").join();
    db.close();
    db = new Database(folder.resolve("polder.db"));
    db.initialize().join();
    store = new EventStore(db, new Economy.Foundation());
    store.migrate().join();
    assertEquals(e.id(), store.active().join().id());
    assertEquals("WARNING", store.active().join().status());
    assertEquals("saved-settings", store.active().join().settings());
    assertEquals("minecraft:air", store.blocks(e.id()).join().getFirst().original());
    assertEquals(1, store.chests(e.id()).join().size());
  }

  @Test
  void cleanupReservationSurvivesRestart() {
    var e = plan(EventRules.Type.METEOR, 0, UUID.randomUUID(), 1);
    store.finish(e, null, List.of(), Map.of(), 1200, false).join();
    assertEquals("CLEANUP", store.active().join().status());
    assertFalse(store.claimAt(e, 0, UUID.randomUUID(), List.of(), 1200, false, 1000).join());
    store.archive(e.id()).join();
    assertNull(store.active().join());
    assertTrue(store.statistics(false).join().getFirst().contains("CANCELED"));
  }

  @Test
  void treasuryRewardsAndRefundAreOnceOnly() {
    UUID treasury = seed(200), winner = UUID.randomUUID();
    var e = plan(EventRules.Type.BOSS, 100, treasury, 0);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    store.finish(e, winner, List.of(cash(e, winner, 30)), Map.of(winner, 10.0), 1100, false).join();
    assertEquals(2, store.money().join());
    assertEquals(0, store.money().join());
    assertEquals(170, balance(treasury));
    assertEquals(30, balance(winner));
  }

  @Test
  void overspendingRollsBackVictoryAndAwards() {
    UUID treasury = seed(100), winner = UUID.randomUUID();
    var e = plan(EventRules.Type.BOSS, 20, treasury, 0);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    assertThrows(
        CompletionException.class,
        () ->
            store
                .finish(
                    e, winner, List.of(cash(e, winner, 21), key(e, winner)), Map.of(), 1100, false)
                .join());
    assertEquals("ACTIVE", store.active().join().status());
    assertTrue(store.pending().join().isEmpty());
    assertEquals(0, balance(winner));
  }

  @Test
  void failedCashRecipientDoesNotBlockRefundOrOtherRewards() {
    UUID treasury = seed(100), blocked = seed(Long.MAX_VALUE), winner = UUID.randomUUID();
    var e = plan(EventRules.Type.BOSS, 50, treasury, 0);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    store
        .finish(e, winner, List.of(cash(e, blocked, 1), cash(e, winner, 2)), Map.of(), 1100, false)
        .join();
    assertEquals(2, store.money().join());
    assertEquals(Long.MAX_VALUE, balance(blocked));
    assertEquals(2, balance(winner));
    assertEquals(97, balance(treasury));
  }

  @Test
  void rewardOutboxAndLootMailboxReplayAreIdempotent() {
    var e = plan(EventRules.Type.BOSS, 0, UUID.randomUUID(), 0);
    UUID player = UUID.randomUUID(), receipt = UUID.randomUUID();
    var a =
        new EventStore.Award(
            "event:" + e.id() + ":item",
            e.id(),
            player,
            "ITEM",
            "LEGENDARY",
            receipt,
            new byte[] {4, 5, 6},
            0);
    store.finish(e, player, List.of(a), Map.of(player, 10.0), 1100, false).join();
    var loot = new LootStore(db);
    loot.migrate().join();
    var pending = store.pending().join().getFirst();
    var parcel = new LootStore.Parcel(pending.receipt(), player, pending.payload(), "WAITING", 0);
    assertTrue(loot.enqueue(parcel, pending.id(), 1).join());
    assertFalse(loot.enqueue(parcel, pending.id(), 2).join());
    store.acknowledge(pending.id(), true).join();
    assertTrue(store.pending().join().isEmpty());
    assertEquals(1, loot.parcels(player, 3).join().size());
    assertArrayEquals(new byte[] {4, 5, 6}, loot.parcels(player, 3).join().getFirst().payload());
  }

  @Test
  void mobDeathAndMissingEntityRetirementAreDistinct() {
    var e = plan(EventRules.Type.DUNGEON, 0, UUID.randomUUID(), 1);
    UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
    store.mob(e.id(), mob, "WAVE", 1).join();
    store.death(mob, e.id(), Map.of(player, 20.0)).join();
    assertFalse(store.dead(mob).join());
    assertEquals(1, store.mobs(e.id()).join().getFirst().state());
    UUID missing = UUID.randomUUID();
    store.mob(e.id(), missing, "WAVE", 1).join();
    store.retire(missing).join();
    assertTrue(
        store.mobs(e.id()).join().stream().anyMatch(r -> r.id().equals(missing) && r.state() == 2));
  }

  @Test
  void versionedMigrationRejectsFutureSchema() {
    store.migrate().join();
    db.submit(
            c -> {
              try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO world_event_schema VALUES(2)");
              }
              return null;
            })
        .join();
    assertThrows(CompletionException.class, () -> store.migrate().join());
  }

  @Test
  void expiredChestClaimIsRejectedBeforeCleanupTick() {
    var e = plan(EventRules.Type.METEOR, 0, UUID.randomUUID(), 1);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    phase(e, "ACTIVE", "LOOT");
    UUID player = UUID.randomUUID();
    assertFalse(store.claimAt(e, 0, player, List.of(key(e, player)), 11000, false, 10000).join());
    assertTrue(store.pending().join().isEmpty());
  }

  @Test
  void bossCompletionPersistsZeroHealthAndDeathTogether() {
    var e = plan(EventRules.Type.BOSS, 0, UUID.randomUUID(), 0);
    phase(e, "PLANNED", "WARNING");
    phase(e, "WARNING", "ACTIVE");
    UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
    store.mob(e.id(), mob, "BOSS", 0).join();
    var dead = e.phase("ACTIVE", 0, 0);
    assertTrue(
        store
            .finish(dead, player, List.of(key(e, player)), Map.of(player, 100.0), 1100, false)
            .join());
    assertEquals(0, store.active().join().health());
    assertEquals(1, store.mobs(e.id()).join().getFirst().state());
  }
}
