package nl.poldersmp.core.loot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.competition.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LootTest {
  @TempDir Path folder;
  Database db;
  LootStore store;

  @BeforeEach
  void setup() {
    db = new Database(folder.resolve("polder.db"));
    db.initialize().join();
    store = new LootStore(db);
    store.migrate().join();
  }

  @AfterEach
  void close() {
    db.close();
  }

  LootStore.Parcel parcel(UUID owner) {
    return new LootStore.Parcel(UUID.randomUUID(), owner, new byte[] {1, 2, 3}, "WAITING", 0);
  }

  UUID issue(UUID owner, LootRules.Tier tier) {
    UUID key = UUID.randomUUID();
    assertTrue(store.issue(key, tier, parcel(owner), "issue:" + key, 1).join());
    return key;
  }

  @Test
  void weightedSelectionHasExactConfiguredDistribution() {
    var table =
        List.of(
            new LootRules.Weighted<>("common", 60),
            new LootRules.Weighted<>("rare", 30),
            new LootRules.Weighted<>("mythic", 10));
    Map<String, Integer> counts = new HashMap<>();
    for (long ticket = 0; ticket < 100; ticket++)
      counts.merge(LootRules.select(table, ticket), 1, Integer::sum);
    assertEquals(Map.of("common", 60, "rare", 30, "mythic", 10), counts);
    assertEquals("mythic", LootRules.select(table, 99));
  }

  @Test
  void invalidProbabilityTablesFailClosed() {
    assertThrows(IllegalArgumentException.class, () -> new LootRules.Weighted<>("bad", 0));
    assertThrows(IllegalArgumentException.class, () -> LootRules.total(List.of()));
    assertThrows(
        ArithmeticException.class,
        () ->
            LootRules.total(
                List.of(
                    new LootRules.Weighted<>(1, Long.MAX_VALUE), new LootRules.Weighted<>(2, 1))));
    assertThrows(
        IllegalArgumentException.class,
        () -> LootRules.select(List.of(new LootRules.Weighted<>(1, 1)), 1));
  }

  @Test
  void validatesPhysicalKeyIdentityShape() {
    assertTrue(LootRules.validKey("MYTHIC", UUID.randomUUID().toString(), 1));
    assertFalse(LootRules.validKey("MYTHIC", "forged", 1));
    assertFalse(LootRules.validKey("UNKNOWN", UUID.randomUUID().toString(), 1));
    assertFalse(LootRules.validKey("COMMON", UUID.randomUUID().toString(), 2));
    assertFalse(LootRules.validKey(null, null, 1));
  }

  @Test
  void allFiveDefaultTablesHaveGuaranteedRewards() throws Exception {
    YamlConfiguration c = read("crates.yml"), v = read("vaults.yml");
    LootConfig.validate(c, v);
    for (var y : List.of(c, v)) {
      var tables = LootConfig.tables(y, y == c ? "types" : "rarities");
      assertEquals(5, tables.size());
      for (var tier : LootRules.Tier.values()) {
        var pool = tables.get(tier);
        assertFalse(pool.isEmpty());
        assertNotNull(LootRules.select(pool, LootRules.total(pool) - 1));
      }
    }
  }

  private YamlConfiguration read(String name) throws Exception {
    var y = new YamlConfiguration();
    try (var in = getClass().getResourceAsStream("/" + name)) {
      assertNotNull(in);
      y.load(new java.io.InputStreamReader(in));
    }
    return y;
  }

  @Test
  void unknownAndWrongTierKeysCannotOpen() {
    UUID player = UUID.randomUUID();
    assertFalse(
        store.open(UUID.randomUUID(), LootRules.Tier.COMMON, parcel(player), "reward", 2).join());
    UUID key = issue(player, LootRules.Tier.RARE);
    assertFalse(store.open(key, LootRules.Tier.COMMON, parcel(player), "reward", 3).join());
    assertTrue(store.open(key, LootRules.Tier.RARE, parcel(player), "reward", 3).join());
  }

  @Test
  void copiedKeyCanOnlyReserveOneReward() {
    UUID player = UUID.randomUUID(), key = issue(player, LootRules.Tier.COMMON);
    var a = store.open(key, LootRules.Tier.COMMON, parcel(player), "iron", 2);
    var b = store.open(key, LootRules.Tier.COMMON, parcel(player), "diamond", 2);
    assertNotEquals(a.join(), b.join());
    assertEquals(1, store.top("CRATE").join().size());
    assertEquals(2, store.parcels(player, 3).join().size());
  }

  @Test
  void issuingSameRewardSourceTwiceIsIdempotent() {
    UUID player = UUID.randomUUID();
    assertTrue(
        store
            .issue(UUID.randomUUID(), LootRules.Tier.MYTHIC, parcel(player), "king:event", 1)
            .join());
    assertFalse(
        store
            .issue(UUID.randomUUID(), LootRules.Tier.MYTHIC, parcel(player), "king:event", 2)
            .join());
    assertEquals(1, store.parcels(player, 3).join().size());
  }

  @Test
  void reservationFailureDoesNotSpendKey() {
    UUID player = UUID.randomUUID(), key = UUID.randomUUID();
    var existing = parcel(player);
    store.issue(key, LootRules.Tier.COMMON, existing, "source", 1).join();
    assertThrows(
        CompletionException.class,
        () -> store.open(key, LootRules.Tier.COMMON, existing, "duplicate-id", 2).join());
    assertTrue(store.open(key, LootRules.Tier.COMMON, parcel(player), "correct", 3).join());
  }

  @Test
  void deliveryTransitionIsOwnerBoundAndIdempotent() {
    UUID player = UUID.randomUUID(), stranger = UUID.randomUUID();
    var delivery = parcel(player);
    store.issue(UUID.randomUUID(), LootRules.Tier.COMMON, delivery, "delivery", 1).join();
    assertThrows(CompletionException.class, () -> store.delivered(delivery.id(), player).join());
    assertFalse(store.delivering(delivery.id(), stranger).join());
    assertTrue(store.delivering(delivery.id(), player).join());
    assertFalse(store.delivering(delivery.id(), player).join());
    store.delivered(delivery.id(), player).join();
    store.delivered(delivery.id(), player).join();
    assertTrue(store.completed(delivery.id(), player).join());
    assertTrue(store.parcels(player, 2).join().isEmpty());
  }

  @Test
  void interruptedDeliveryAndSelectionSurviveRestart() {
    UUID player = UUID.randomUUID();
    var reward = parcel(player);
    store.issue(UUID.randomUUID(), LootRules.Tier.LEGENDARY, reward, "saved", 1).join();
    store.delivering(reward.id(), player).join();
    db.close();
    db = new Database(folder.resolve("polder.db"));
    db.initialize().join();
    store = new LootStore(db);
    store.migrate().join();
    var restored = store.parcels(player, 2).join().getFirst();
    assertEquals("DELIVERING", restored.state());
    assertEquals(reward.id(), restored.id());
    assertArrayEquals(reward.payload(), restored.payload());
    store.delivered(restored.id(), player).join();
    assertTrue(store.parcels(player, 3).join().isEmpty());
  }

  LootStore.Vault vault(long expires) {
    var vault =
        new LootStore.Vault(
            UUID.randomUUID(),
            UUID.randomUUID(),
            10,
            65,
            10,
            LootRules.Tier.MYTHIC,
            "PLANNED",
            expires);
    store
        .plan(
            vault,
            List.of(new LootStore.BlockPlan(10, 65, 10, "minecraft:air", "minecraft:barrel")))
        .join();
    return vault;
  }

  @Test
  void plannedVaultCannotBeClaimedAndActiveVaultClaimsOnce() {
    var vault = vault(1000);
    UUID player = UUID.randomUUID();
    assertFalse(store.claim(vault, parcel(player), "weapon", 100, 0).join());
    store.activate(vault.id()).join();
    var a = store.claim(vault, parcel(player), "weapon", 101, 0);
    var b = store.claim(vault, parcel(UUID.randomUUID()), "weapon", 101, 0);
    assertNotEquals(a.join(), b.join());
    assertEquals(1, store.top("VAULT").join().size());
  }

  @Test
  void expiredVaultAndPlayerCooldownPreventClaims() {
    UUID player = UUID.randomUUID();
    var first = vault(1000);
    store.activate(first.id()).join();
    assertTrue(store.claim(first, parcel(player), "reward", 100, 500).join());
    store.removed(first.id()).join();
    var second = vault(1000);
    store.activate(second.id()).join();
    assertFalse(store.claim(second, parcel(player), "reward", 200, 500).join());
    assertFalse(store.claim(second, parcel(UUID.randomUUID()), "reward", 1000, 0).join());
    assertTrue(store.claim(second, parcel(player), "reward", 600, 500).join());
  }

  @Test
  void placementRequiresEverySafetyCondition() {
    assertTrue(
        new LootRules.Eligibility(true, true, true, true, true, true, true, true, true).allowed());
    for (int i = 0; i < 9; i++) {
      boolean[] b = {true, true, true, true, true, true, true, true, true};
      b[i] = false;
      assertFalse(
          new LootRules.Eligibility(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8])
              .allowed());
    }
  }

  @Test
  void blueprintAndLocationsPersistWithoutDeletingOriginalMetadata() {
    var vault = vault(1000);
    assertEquals("minecraft:air", store.blocks(vault.id()).join().getFirst().original());
    store.removed(vault.id()).join();
    assertTrue(store.vaults().join().isEmpty());
    assertEquals("minecraft:barrel", store.blocks(vault.id()).join().getFirst().placed());
    var crate = new LootStore.Crate(UUID.randomUUID(), 1, 64, 3, LootRules.Tier.EPIC);
    store.setCrate(crate).join();
    assertEquals(crate, store.crates().join().getFirst());
    store.removeCrate(crate).join();
    assertTrue(store.crates().join().isEmpty());
  }

  @Test
  void schemaMigrationRejectsNewerVersion() {
    store.migrate().join();
    db.submit(
            c -> {
              try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO loot_schema VALUES(2)");
              }
              return null;
            })
        .join();
    assertThrows(CompletionException.class, () -> store.migrate().join());
  }

  @Test
  void onlyCompletedBountyRewardsProduceBonusKeys() {
    CompetitionStore competitions = new CompetitionStore(db, new Economy.Foundation());
    competitions.migrate().join();
    UUID giver = UUID.randomUUID(), victim = UUID.randomUUID(), killer = UUID.randomUUID();
    db.submit(
            c -> {
              try (var p = c.prepareStatement("INSERT INTO accounts VALUES(?,100)")) {
                p.setString(1, giver.toString());
                p.executeUpdate();
              }
              return null;
            })
        .join();
    long now = System.currentTimeMillis() + 10;
    competitions.place(UUID.randomUUID(), giver, victim, 50, now).join();
    competitions.claim(UUID.randomUUID(), killer, victim, now + 1, 0).join();
    assertTrue(store.bonuses().join().isEmpty());
    competitions.recoverPayouts(now + 2).join();
    var bonus = store.bonuses().join().getFirst();
    assertEquals(killer, bonus.player());
    store
        .issue(UUID.randomUUID(), LootRules.Tier.COMMON, parcel(killer), bonus.source(), now + 3)
        .join();
    assertTrue(store.bonuses().join().isEmpty());
  }

  @Test
  void cleanupReservationPreventsClaimsBeforeBlocksChange() {
    var vault = vault(1000);
    store.activate(vault.id()).join();
    store.beginCleanup(vault.id()).join();
    assertEquals("CLEANUP", store.vaults().join().getFirst().state());
    assertFalse(store.claim(vault, parcel(UUID.randomUUID()), "reward", 200, 0).join());
    assertFalse(store.blocks(vault.id()).join().isEmpty());
  }

  @Test
  void zeroCashKingVictoryGrantsExactlyOneKeyBonus() {
    CompetitionStore competition = new CompetitionStore(db, new Economy.Foundation());
    competition.migrate().join();
    UUID winner = UUID.randomUUID(), treasury = UUID.randomUUID();
    long now = System.currentTimeMillis() + 10;
    var event =
        competition.start(UUID.randomUUID(), "zero-cash", now, 0, 10000, 0, treasury).join();
    competition.checkpoint(event, Set.of(winner)).join();
    competition.finish(event.id(), winner, now + 1).join();
    var bonus = store.bonuses().join().getFirst();
    assertEquals("KING_REWARD", bonus.kind());
    store
        .issue(UUID.randomUUID(), LootRules.Tier.LEGENDARY, parcel(winner), bonus.source(), now + 2)
        .join();
    assertTrue(store.bonuses().join().isEmpty());
  }

  @Test
  void historicRewardsAreNotRetroactivelyDuplicated() {
    CompetitionStore competition = new CompetitionStore(db, new Economy.Foundation());
    competition.migrate().join();
    UUID winner = UUID.randomUUID();
    var event =
        competition.start(UUID.randomUUID(), "historic", 1, 0, 100, 0, UUID.randomUUID()).join();
    competition.checkpoint(event, Set.of(winner)).join();
    competition.finish(event.id(), winner, 2).join();
    assertTrue(store.bonuses().join().isEmpty());
  }
}
