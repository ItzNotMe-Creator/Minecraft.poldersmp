package nl.poldersmp.core.market;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.loot.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MarketTest {
  @TempDir Path temp;
  Database db;
  MoneyStore store;
  UUID a = UUID.randomUUID(), b = UUID.randomUUID();

  @BeforeEach
  void setup() {
    db = new Database(temp.resolve("state.db"));
    db.initialize().join();
    new LootStore(db).migrate().join();
    store = new MoneyStore(db);
    store.migrate().join();
    db.submit(
            c -> {
              Sql.update(c, "INSERT INTO accounts VALUES(?,1000)", a);
              return null;
            })
        .join();
  }

  @AfterEach
  void close() {
    db.close();
  }

  MoneyStore.Operation op(UUID id, String provider, String type) {
    return new MoneyStore.Operation(
        id, a, type, provider, "READY", 1000, type + ":" + id, null, null);
  }

  long count(String sql, Object... v) {
    return db.submit(c -> Sql.number(c, sql, v)).join();
  }

  LootStore.Parcel parcel(UUID p) {
    return new LootStore.Parcel(UUID.randomUUID(), p, new byte[] {1, 2, 3}, "WAITING", 1000);
  }

  MoneyStore.Deposit listing(long expires) {
    var d =
        new MoneyStore.Deposit(
            UUID.randomUUID(),
            a,
            new byte[] {1, 2, 3},
            "DIAMOND test",
            "AUCTION",
            100,
            "PREPARED",
            1000,
            expires,
            "sqlite");
    store.deposit(d, 10, 100).join();
    store.tagged(d.id(), a).join();
    store.captured(d, "sqlite", 1001).join();
    return d;
  }

  @Test
  void moneyParsingNeverRoundsOrOverflows() {
    assertEquals(10, MoneyRules.amount("10.00", 100));
    for (String s : List.of("0", "-1", "0.01", "NaN", "9223372036854775808", "101"))
      assertThrows(IllegalArgumentException.class, () -> MoneyRules.amount(s, 100));
  }

  @Test
  void explicitConfigurationAndExistingBalancesPreserved() throws Exception {
    var y = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/economy.yml").toFile());
    var cfg = MarketConfig.read(y);
    assertEquals("auto", cfg.yaml().getString("mode"));
    assertEquals(1000, store.balance(a).join());
    assertTrue(cfg.pool().stream().anyMatch(e -> e.kind().equals("WEAPON")));
    y.set("mode", "silent-fallback");
    assertThrows(IllegalArgumentException.class, () -> MarketConfig.read(y));
  }

  @Test
  void atomicPaymentReplaysOnceAndCannotAlterLegs() {
    UUID id = UUID.randomUUID();
    var op = op(id, "sqlite", "PAY");
    var legs = List.of(new MoneyStore.Leg(a, -200), new MoneyStore.Leg(b, 200));
    store.plan(op, legs).join();
    store.internal(id, 1001).join();
    store.plan(op, legs).join();
    store.internal(id, 1002).join();
    assertEquals(800, store.balance(a).join());
    assertEquals(200, store.balance(b).join());
    assertThrows(
        CompletionException.class,
        () ->
            store
                .plan(op, List.of(new MoneyStore.Leg(a, -201), new MoneyStore.Leg(b, 201)))
                .join());
  }

  @Test
  void insufficientFundsRollbackWholeTransfer() {
    UUID id = UUID.randomUUID();
    store
        .plan(
            op(id, "sqlite", "PAY"),
            List.of(new MoneyStore.Leg(a, -1001), new MoneyStore.Leg(b, 1001)))
        .join();
    assertThrows(CompletionException.class, () -> store.internal(id, 1001).join());
    assertEquals(1000, store.balance(a).join());
    assertEquals(0, store.balance(b).join());
    store.decline(id, "insufficient").join();
    assertEquals(
        "DECLINED",
        db.submit(
                c ->
                    Sql.rows(c, "SELECT state FROM money_operations WHERE id=?", id)
                        .getFirst()
                        .getFirst())
            .join());
  }

  @Test
  void creditOverflowCannotLoseDebit() {
    db.submit(
            c -> {
              Sql.update(c, "INSERT INTO accounts VALUES(?,?)", b, Long.MAX_VALUE);
              return null;
            })
        .join();
    UUID id = UUID.randomUUID();
    store
        .plan(op(id, "sqlite", "PAY"), List.of(new MoneyStore.Leg(a, -1), new MoneyStore.Leg(b, 1)))
        .join();
    assertThrows(CompletionException.class, () -> store.internal(id, 1001).join());
    assertEquals(1000, store.balance(a).join());
    assertEquals(Long.MAX_VALUE, store.balance(b).join());
  }

  @Test
  void administrationSetIsAtomicAndAudited() {
    UUID id = UUID.randomUUID();
    var operation =
        new MoneyStore.Operation(
            id, a, "SET", "sqlite", "READY", 1000, "operator:uuid set:25", null, 25L);
    store.plan(operation, List.of()).join();
    store.internal(id, 1001).join();
    assertEquals(25, store.balance(a).join());
    assertTrue(store.history(a).join().getFirst().contains("SET"));
    store.internal(id, 1002).join();
    assertEquals(25, store.balance(a).join());
  }

  @Test
  void applyingVaultLegBecomesUncertainAfterRestart() {
    UUID id = UUID.randomUUID();
    store
        .plan(
            op(id, "vault:provider", "PAY"),
            List.of(new MoneyStore.Leg(a, -5), new MoneyStore.Leg(b, 5)))
        .join();
    var step = store.steps(id).join().getFirst();
    assertTrue(store.applying(step).join());
    assertFalse(store.applying(step).join());
    store.migrate().join();
    assertEquals("UNKNOWN", store.steps(id).join().getFirst().state());
    assertTrue(store.pending("vault:provider").join().isEmpty());
    assertEquals(1000, store.balance(a).join());
  }

  @Test
  void uncertainExternalEffectRequiresExplicitAttestation() {
    UUID id = UUID.randomUUID();
    store.plan(op(id, "vault:provider", "PAY"), List.of(new MoneyStore.Leg(a, -5))).join();
    var step = store.steps(id).join().getFirst();
    store.applying(step).join();
    store.acknowledge(step, "UNKNOWN").join();
    assertThrows(CompletionException.class, () -> store.decline(id, "cannot infer balance").join());
    store.resolve(id, 0, true, b, 2000).join();
    store.externalFinish(id, 2001).join();
    assertEquals(1, count("SELECT COUNT(*) FROM money_audit WHERE operation=?", id));
    assertEquals(1000, store.balance(a).join());
  }

  @Test
  void notAppliedResolutionAllowsOneDurableRetry() {
    UUID id = UUID.randomUUID();
    store.plan(op(id, "vault:A", "PAY"), List.of(new MoneyStore.Leg(a, -5))).join();
    var s = store.steps(id).join().getFirst();
    store.applying(s).join();
    store.acknowledge(s, "UNKNOWN").join();
    store.resolve(id, 0, false, b, 2000).join();
    assertEquals("PENDING", store.steps(id).join().getFirst().state());
    assertEquals(1, store.pending("vault:A").join().size());
    assertTrue(store.pending("vault:B").join().isEmpty());
  }

  @Test
  void externalCompletionRequiresAllLegsAcknowledged() {
    UUID id = UUID.randomUUID();
    store
        .plan(
            op(id, "vault:A", "PAY"), List.of(new MoneyStore.Leg(a, -5), new MoneyStore.Leg(b, 5)))
        .join();
    assertThrows(CompletionException.class, () -> store.externalFinish(id, 1001).join());
    for (var s : store.steps(id).join()) {
      store.applying(s).join();
      store.acknowledge(s, "DONE").join();
    }
    store.externalFinish(id, 1002).join();
    assertEquals(0, store.pending("vault:A").join().size());
  }

  @Test
  void taggedEscrowMustCommitBeforeListing() {
    var d =
        new MoneyStore.Deposit(
            UUID.randomUUID(),
            a,
            new byte[] {8},
            "item",
            "AUCTION",
            5,
            "PREPARED",
            1000,
            9000,
            "sqlite");
    store.deposit(d, 10, 100).join();
    assertThrows(CompletionException.class, () -> store.captured(d, "sqlite", 1001).join());
    assertTrue(store.listings(null, "", 0, 1001).join().isEmpty());
    store.tagged(d.id(), a).join();
    store.captured(d, "sqlite", 1002).join();
    assertThrows(CompletionException.class, () -> store.captured(d, "sqlite", 1003).join());
    assertEquals(1, store.listings(null, "", 0, 1003).join().size());
  }

  @Test
  void providerChangesCannotConsumeOldEscrow() {
    var d =
        new MoneyStore.Deposit(
            UUID.randomUUID(),
            a,
            new byte[] {8},
            "item",
            "SELL",
            5,
            "PREPARED",
            1000,
            9000,
            "vault:A");
    store.deposit(d, 10, 100).join();
    store.tagged(d.id(), a).join();
    assertThrows(CompletionException.class, () -> store.captured(d, "sqlite", 1001).join());
    assertEquals("TAGGED", store.deposits(a).join().getFirst().state());
  }

  @Test
  void oneAuctionOneBuyerOneMailboxParcel() {
    var listing = listing(9000);
    db.submit(
            c -> {
              Sql.update(c, "INSERT INTO accounts VALUES(?,500)", b);
              return null;
            })
        .join();
    UUID id = UUID.randomUUID();
    var operation =
        new MoneyStore.Operation(
            id, b, "AUCTION", "sqlite", "READY", 1001, "auction:" + listing.id(), null, null);
    var item = parcel(b);
    store
        .order(
            operation,
            List.of(new MoneyStore.Leg(b, -100), new MoneyStore.Leg(a, 100)),
            item,
            "AUCTION",
            listing.id().toString(),
            null,
            null,
            0,
            1001)
        .join();
    assertThrows(
        CompletionException.class,
        () ->
            store
                .order(
                    op(UUID.randomUUID(), "sqlite", "AUCTION"),
                    List.of(new MoneyStore.Leg(a, -100)),
                    parcel(a),
                    "AUCTION",
                    listing.id().toString(),
                    null,
                    null,
                    0,
                    1001)
                .join());
    store.internal(id, 1002).join();
    store.internal(id, 1003).join();
    assertEquals("SOLD", store.listing(listing.id()).join().state());
    assertEquals(1100, store.balance(a).join());
    assertEquals(400, store.balance(b).join());
    assertEquals(1, count("SELECT COUNT(*) FROM loot_parcels WHERE source=?", "market:" + id));
  }

  @Test
  void failedAuctionPurchaseReleasesReservation() {
    var d = listing(9000);
    UUID id = UUID.randomUUID();
    var op = new MoneyStore.Operation(id, b, "AUCTION", "sqlite", "READY", 1001, "buy", null, null);
    store
        .order(
            op,
            List.of(new MoneyStore.Leg(b, -100), new MoneyStore.Leg(a, 100)),
            parcel(b),
            "AUCTION",
            d.id().toString(),
            null,
            null,
            0,
            1001)
        .join();
    assertThrows(CompletionException.class, () -> store.internal(id, 1002).join());
    store.decline(id, "no funds").join();
    assertEquals("ACTIVE", store.listing(d.id()).join().state());
    assertEquals(0, count("SELECT COUNT(*) FROM loot_parcels WHERE source=?", "market:" + id));
  }

  @Test
  void expirationReturnsExactlyOnceAndReservedListingIsHeld() {
    var d = listing(1500);
    store.expire(1500).join();
    store.expire(1600).join();
    var returns = store.returns().join();
    assertEquals(1, returns.size());
    var p = parcel(a);
    store.returned(returns.getFirst(), p, 1601).join();
    store.returned(returns.getFirst(), p, 1602).join();
    assertEquals(
        1, count("SELECT COUNT(*) FROM loot_parcels WHERE source=?", returns.getFirst().source()));
    assertEquals("EXPIRED", store.listing(d.id()).join().state());
  }

  @Test
  void searchAndPaginationUseBoundParameters() {
    listing(9000);
    assertEquals(1, store.listings(null, "diamond", 0, 1001).join().size());
    assertTrue(store.listings(null, "' OR 1=1", 0, 1001).join().isEmpty());
    assertTrue(store.listings(null, "", 1, 1001).join().isEmpty());
  }

  @Test
  void frozenStockDoesNotRerollAfterRestart() throws Exception {
    var e =
        new MarketConfig.Entry(
            "blade", "WEAPON", "LIGHTNING_BLADE", 1, 100, 0, 1, "polder.blackmarket.use");
    var first = store.stock(1, List.of(Map.entry(e.id(), e.encode()))).join();
    var again = store.stock(1, List.of(Map.entry("other", "different"))).join();
    assertEquals(first, again);
    assertEquals(e, MarketConfig.Entry.decode(first.getFirst()));
    assertEquals(1, MoneyRules.rotation(1500, 1));
  }

  @Test
  void blackMarketLimitsArePersistentAndFailedOrderRefundsLimit() {
    store.stock(1, List.of(Map.entry("blade", "frozen"))).join();
    UUID first = UUID.randomUUID();
    store
        .order(
            op(first, "sqlite", "BLACK"),
            List.of(new MoneyStore.Leg(a, -100)),
            parcel(a),
            "BLACK",
            "blade",
            1L,
            "blade",
            1,
            1000)
        .join();
    assertThrows(
        CompletionException.class,
        () ->
            store
                .order(
                    op(UUID.randomUUID(), "sqlite", "BLACK"),
                    List.of(new MoneyStore.Leg(a, -100)),
                    parcel(a),
                    "BLACK",
                    "blade",
                    1L,
                    "blade",
                    1,
                    1000)
                .join());
    store.decline(first, "cancel").join();
    store
        .order(
            op(UUID.randomUUID(), "sqlite", "BLACK"),
            List.of(new MoneyStore.Leg(a, -100)),
            parcel(a),
            "BLACK",
            "blade",
            1L,
            "blade",
            1,
            1001)
        .join();
  }

  @Test
  void sellPaymentFailureReturnsTheStoredItem() {
    var d =
        new MoneyStore.Deposit(
            UUID.randomUUID(),
            a,
            new byte[] {8},
            "item",
            "SELL",
            50,
            "PREPARED",
            1000,
            9000,
            "sqlite");
    store.deposit(d, 10, 100).join();
    store.tagged(d.id(), a).join();
    store.captured(d, "sqlite", 1001).join();
    store.decline(d.id(), "cancel before payment").join();
    assertEquals(1, store.returns().join().size());
    assertEquals(1000, store.balance(a).join());
  }

  @Test
  void futureSchemaNeverMutatesBalances() {
    db.submit(
            c -> {
              Sql.update(c, "INSERT INTO market_schema VALUES(99)");
              return null;
            })
        .join();
    assertThrows(CompletionException.class, () -> store.migrate().join());
    assertEquals(1000, store.balance(a).join());
  }

  @Test
  void auctionCurrencyCannotChangeUnderAnExistingListing() {
    var d = listing(9000);
    assertTrue(store.listings(null, "", 0, 1001, "vault:other").join().isEmpty());
    var op =
        new MoneyStore.Operation(
            UUID.randomUUID(),
            b,
            "AUCTION",
            "vault:other",
            "READY",
            1001,
            "wrong-provider",
            null,
            null);
    assertThrows(
        CompletionException.class,
        () ->
            store
                .order(
                    op,
                    List.of(new MoneyStore.Leg(b, -100)),
                    parcel(b),
                    "AUCTION",
                    d.id().toString(),
                    null,
                    null,
                    0,
                    1001)
                .join());
    assertEquals("ACTIVE", store.listing(d.id()).join().state());
  }

  @Test
  void legacyCashHistoryRollsBackWithItsExistingTransaction() {
    assertThrows(
        CompletionException.class,
        () ->
            Sql.tx(
                    db,
                    c -> {
                      new nl.poldersmp.core.competition.Economy.Foundation().debit(c, a, 20);
                      MoneyStore.legacy(c, a, -20);
                      throw new java.sql.SQLException("simulated later failure");
                    })
                .join());
    assertEquals(1000, store.balance(a).join());
    assertEquals(0, count("SELECT COUNT(*) FROM money_operations"));
    Sql.tx(
            db,
            c -> {
              new nl.poldersmp.core.competition.Economy.Foundation().debit(c, a, 20);
              MoneyStore.legacy(c, a, -20);
              return null;
            })
        .join();
    assertEquals(980, store.balance(a).join());
    assertTrue(store.history(a).join().getFirst().contains("LEGACY_DEBIT"));
  }

  @Test
  void concurrentAuctionBuyersCannotBothReserve() {
    var listing = listing(9000);
    UUID third = UUID.randomUUID();
    var first =
        new MoneyStore.Operation(
            UUID.randomUUID(), b, "AUCTION", "sqlite", "READY", 1001, "buyer-b", null, null);
    var second =
        new MoneyStore.Operation(
            UUID.randomUUID(), third, "AUCTION", "sqlite", "READY", 1001, "buyer-c", null, null);
    var futures =
        List.of(
            store.order(
                first,
                List.of(new MoneyStore.Leg(b, -100)),
                parcel(b),
                "AUCTION",
                listing.id().toString(),
                null,
                null,
                0,
                1001),
            store.order(
                second,
                List.of(new MoneyStore.Leg(third, -100)),
                parcel(third),
                "AUCTION",
                listing.id().toString(),
                null,
                null,
                0,
                1001));
    int success = 0;
    for (var f : futures)
      try {
        f.join();
        success++;
      } catch (CompletionException ignored) {
      }
    assertEquals(1, success);
    assertEquals(1, count("SELECT COUNT(*) FROM market_orders"));
  }

  @Test
  void fractionalConfigurationIsRejectedInsteadOfRounded() {
    var y = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/economy.yml").toFile());
    y.set("shop.items.iron.buy", 160.01);
    assertThrows(IllegalArgumentException.class, () -> MarketConfig.read(y));
    y.set("shop.items.iron.buy", 160);
    y.set("shop.items.iron.sell-per-item", 5.5);
    assertThrows(IllegalArgumentException.class, () -> MarketConfig.read(y));
    y.set("shop.items.iron.sell-per-item", 5);
    y.set("maximum-transaction", "1000");
    assertThrows(IllegalArgumentException.class, () -> MarketConfig.read(y));
  }

  @Test
  void uncertainAccountsCannotStartAnotherPaymentOnSameProvider() {
    UUID id = UUID.randomUUID();
    store.plan(op(id, "vault:A", "PAY"), List.of(new MoneyStore.Leg(a, -5))).join();
    var step = store.steps(id).join().getFirst();
    store.applying(step).join();
    store.acknowledge(step, "UNKNOWN").join();
    assertThrows(
        CompletionException.class,
        () ->
            store
                .plan(op(UUID.randomUUID(), "vault:A", "PAY"), List.of(new MoneyStore.Leg(a, -5)))
                .join());
    store.plan(op(UUID.randomUUID(), "vault:B", "PAY"), List.of(new MoneyStore.Leg(a, -5))).join();
  }

  @Test
  void queuedPaymentsAlsoWaitBehindAnUncertainEffect() {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    store.plan(op(first, "vault:A", "PAY"), List.of(new MoneyStore.Leg(a, -5))).join();
    store.plan(op(second, "vault:A", "PAY"), List.of(new MoneyStore.Leg(a, -5))).join();
    var step = store.steps(first).join().getFirst();
    store.applying(step).join();
    store.acknowledge(step, "UNKNOWN").join();
    assertTrue(store.pending("vault:A").join().isEmpty());
    store.resolve(first, 0, true, b, 2000).join();
    assertEquals(2, store.pending("vault:A").join().size());
  }

  @Test
  void walletHistoryShowsOnlyThatPlayersLeg() {
    UUID id = UUID.randomUUID();
    store
        .plan(
            op(id, "sqlite", "PAY"), List.of(new MoneyStore.Leg(a, -20), new MoneyStore.Leg(b, 20)))
        .join();
    store.internal(id, 1001).join();
    assertEquals(1, store.history(a).join().size());
    assertEquals(1, store.history(b).join().size());
    assertTrue(store.history(a).join().getFirst().contains("-20"));
  }

  @Test
  void capturedEscrowUsesFrozenDatabasePayloadAndPrice() {
    UUID id = UUID.randomUUID();
    var original =
        new MoneyStore.Deposit(
            id, a, new byte[] {1, 2}, "real", "AUCTION", 50, "PREPARED", 1000, 9000, "sqlite");
    store.deposit(original, 10, 100).join();
    store.tagged(id, a).join();
    var changed =
        new MoneyStore.Deposit(
            id, a, new byte[] {9}, "forged", "AUCTION", 1, "TAGGED", 1000, 9000, "sqlite");
    store.captured(changed, "sqlite", 1001).join();
    var listing = store.listing(id).join();
    assertEquals(50, listing.price());
    assertArrayEquals(new byte[] {1, 2}, listing.payload());
    assertEquals("real", listing.label());
  }
}
