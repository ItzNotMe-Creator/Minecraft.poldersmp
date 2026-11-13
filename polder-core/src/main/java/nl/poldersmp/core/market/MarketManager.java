package nl.poldersmp.core.market;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.competition.Economy;
import nl.poldersmp.core.gameplay.Weapons;
import nl.poldersmp.core.loot.*;
import nl.poldersmp.core.teams.Teams;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarketManager implements Listener, TabExecutor, AutoCloseable {
  private final JavaPlugin plugin;
  private final MoneyStore store;
  private final MarketConfig config;
  private final RewardFactory factory;
  private final NamespacedKey escrow;
  private final Set<UUID> busy = new HashSet<>(),
      recovering = new HashSet<>(),
      checking = new HashSet<>();
  private final Economy gate;
  private final VaultAdapter vault;
  private final String provider;
  private boolean ready, ticking, closed;
  private long nextAttempt;
  private final org.bukkit.scheduler.BukkitTask task;

  public MarketManager(JavaPlugin plugin, Database db, Weapons weapons) {
    this.plugin = plugin;
    store = new MoneyStore(db);
    escrow = new NamespacedKey(plugin, "trade_escrow");
    gate =
        new Economy() {
          public void debit(Connection c, UUID p, long n) throws SQLException {
            if (!ready || !"sqlite".equals(provider))
              throw new SQLException(
                  "Transactional legacy cash requires explicit sqlite mode; existing balances are"
                      + " preserved");
            new Economy.Foundation().debit(c, p, n);
            MoneyStore.legacy(c, p, -n);
          }

          public void credit(Connection c, UUID p, long n) throws SQLException {
            if (!ready || !"sqlite".equals(provider))
              throw new SQLException(
                  "Legacy payouts held: no automatic migration to an external provider");
            new Economy.Foundation().credit(c, p, n);
            MoneyStore.legacy(c, p, n);
          }
        };
    Bukkit.getServicesManager().register(Economy.class, gate, plugin, ServicePriority.Highest);
    if (!new File(plugin.getDataFolder(), "economy.yml").exists())
      plugin.saveResource("economy.yml", false);
    config =
        MarketConfig.read(
            YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "economy.yml")));
    factory = new RewardFactory(plugin, weapons);
    VaultAdapter found;
    try {
      found = VaultAdapter.detect();
    } catch (Exception e) {
      throw new IllegalArgumentException("Vault inspection failed; refusing fallback", e);
    }
    String mode = config.yaml().getString("mode");
    vault = !mode.equals("sqlite") && !mode.equals("disabled") ? found : null;
    provider = mode.equals("sqlite") ? "sqlite" : vault != null ? vault.id() : "disabled";
    if (mode.equals("vault") && vault == null)
      plugin.getLogger().severe("Requested Vault economy unavailable; trading disabled");
    if (found != null && mode.equals("sqlite"))
      plugin
          .getLogger()
          .warning(
              "Explicit SQLite selected while Vault is present; no balances are merged. Change mode"
                  + " if this is unintended.");
    for (var entry : config.shop().values()) factory.create(entry.reward());
    for (var entry : config.pool()) factory.create(entry.reward());
    for (String name : List.of("balance", "pay", "shop", "ah", "blackmarket", "eco")) {
      plugin.getCommand(name).setExecutor(this);
      plugin.getCommand(name).setTabCompleter(this);
    }
    Bukkit.getPluginManager().registerEvents(this, plugin);
    new LootStore(db)
        .migrate()
        .thenCompose(v -> store.migrate())
        .whenComplete(
            (v, e) ->
                main(
                    () -> {
                      if (e != null) failure(e);
                      else {
                        ready = true;
                        plugin
                            .getLogger()
                            .info(
                                "Economy ready; selected provider="
                                    + provider
                                    + "; existing balances untouched");
                      }
                    }));
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
  }

  private Loot loot() {
    return Bukkit.getServicesManager().load(Loot.class);
  }

  private void main(Runnable r) {
    if (!closed && plugin.isEnabled())
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (!closed) r.run();
              });
  }

  private void failure(Throwable e) {
    plugin.getLogger().severe("Economy/market: " + e);
  }

  private <T> void result(CommandSender p, CompletableFuture<T> future, Consumer<T> action) {
    future.whenComplete(
        (v, e) ->
            main(
                () -> {
                  if (e != null) {
                    failure(e);
                    p.sendMessage(
                        "Action failed: "
                            + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
                  } else action.accept(v);
                }));
  }

  private void feedback(Player p, String text) {
    p.sendMessage(text);
    if (config.yaml().getBoolean("sounds"))
      p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
    if (config.yaml().getBoolean("particles"))
      p.getWorld()
          .spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 5, .2, .2, .2);
  }

  private void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  private boolean locked(ItemStack item) {
    return item != null
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(escrow, PersistentDataType.STRING);
  }

  private UUID tag(ItemStack item) {
    try {
      return UUID.fromString(
          item.getItemMeta().getPersistentDataContainer().get(escrow, PersistentDataType.STRING));
    } catch (Exception e) {
      return null;
    }
  }

  private boolean locked(Player p) {
    return busy.contains(p.getUniqueId())
        || recovering.contains(p.getUniqueId())
        || Arrays.stream(p.getInventory().getContents()).anyMatch(this::locked)
        || Arrays.stream(p.getOpenInventory().getTopInventory().getContents())
            .anyMatch(this::locked)
        || locked(p.getItemOnCursor());
  }

  private boolean found(Player p, UUID id) {
    return Arrays.stream(p.getInventory().getContents()).anyMatch(i -> id.equals(tag(i)))
        || Arrays.stream(p.getOpenInventory().getTopInventory().getContents())
            .anyMatch(i -> id.equals(tag(i)))
        || id.equals(tag(p.getItemOnCursor()));
  }

  private void remove(Player p, UUID id) {
    for (Inventory inventory : List.of(p.getInventory(), p.getOpenInventory().getTopInventory()))
      for (int i = 0; i < inventory.getSize(); i++)
        if (id.equals(tag(inventory.getItem(i)))) inventory.setItem(i, null);
    if (id.equals(tag(p.getItemOnCursor()))) p.setItemOnCursor(null);
  }

  private MoneyStore.Operation op(
      UUID id, UUID actor, String kind, String detail, Double expected, Long set) {
    return new MoneyStore.Operation(
        id, actor, kind, provider, "READY", System.currentTimeMillis(), detail, expected, set);
  }

  private void queued(CommandSender sender, UUID id) {
    sender.sendMessage(
        "Operation queued: "
            + id
            + ". Completion and any held payments appear in /balance history or /eco pending.");
  }

  private void money(
      CommandSender sender,
      UUID actor,
      String kind,
      List<MoneyStore.Leg> legs,
      String detail,
      Double expected,
      Long set) {
    UUID id = UUID.randomUUID();
    if (sender instanceof Player p) {
      require(!busy.contains(p.getUniqueId()), "An action is already pending");
      busy.add(p.getUniqueId());
    }
    store
        .plan(op(id, actor, kind, detail, expected, set), legs)
        .whenComplete(
            (v, e) ->
                main(
                    () -> {
                      if (sender instanceof Player p) busy.remove(p.getUniqueId());
                      if (e != null) {
                        failure(e);
                        sender.sendMessage(
                            "Payment planning failed; no provider call made: "
                                + (e.getCause() == null
                                    ? e.getMessage()
                                    : e.getCause().getMessage()));
                      } else queued(sender, id);
                    }));
  }

  private void tick() {
    if (!ready || ticking || loot() == null || System.currentTimeMillis() < nextAttempt) return;
    nextAttempt = System.currentTimeMillis() + 2000;
    ticking = true;
    for (Player p : Bukkit.getOnlinePlayers()) recover(p);
    store
        .expire(System.currentTimeMillis())
        .thenCompose(v -> store.returns())
        .whenComplete(
            (returns, error) ->
                main(
                    () -> {
                      if (error != null) {
                        failure(error);
                        ticking = false;
                        return;
                      }
                      List<CompletableFuture<?>> fs = new ArrayList<>();
                      for (var returned : returns) {
                        try {
                          fs.add(
                              store.returned(
                                  returned,
                                  loot()
                                      .prepareItem(
                                          returned.owner(),
                                          ItemStack.deserializeBytes(returned.payload())),
                                  System.currentTimeMillis()));
                        } catch (Exception e) {
                          failure(e);
                        }
                      }
                      CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new))
                          .thenCompose(v -> store.pending(provider))
                          .whenComplete(
                              (ops, e) ->
                                  main(
                                      () -> {
                                        if (e != null) {
                                          failure(e);
                                          ticking = false;
                                          return;
                                        }
                                        if (ops.isEmpty() || provider.equals("disabled")) {
                                          ticking = false;
                                          return;
                                        }
                                        process(ops.getFirst());
                                      }));
                    }));
  }

  private void process(MoneyStore.Operation op) {
    if (provider.equals("sqlite")) {
      store
          .internal(op.id(), System.currentTimeMillis())
          .whenComplete(
              (v, e) -> {
                CompletableFuture<?> done =
                    e == null
                        ? CompletableFuture.completedFuture(null)
                        : store.decline(op.id(), String.valueOf(e));
                done.whenComplete(
                    (x, error) ->
                        main(
                            () -> {
                              ticking = false;
                              if (error != null) failure(error);
                              var p = Bukkit.getPlayer(op.actor());
                              if (p != null)
                                feedback(
                                    p,
                                    e == null
                                        ? "Transaction completed: " + op.id()
                                        : "Transaction declined; reserved item/stock released: "
                                            + op.id());
                            }));
              });
      return;
    }
    store
        .steps(op.id())
        .whenComplete(
            (steps, e) ->
                main(
                    () -> {
                      if (e != null) {
                        failure(e);
                        ticking = false;
                        return;
                      }
                      var next =
                          steps.stream()
                              .filter(s -> !s.state().equals("DONE"))
                              .findFirst()
                              .orElse(null);
                      if (next == null) {
                        if (op.expected() != null && steps.isEmpty()) {
                          try {
                            if (Double.compare(vault.balance(op.actor()), op.expected()) != 0) {
                              store
                                  .decline(op.id(), "Set precondition changed")
                                  .whenComplete(
                                      (v, error) ->
                                          main(
                                              () -> {
                                                ticking = false;
                                                if (error != null) failure(error);
                                              }));
                              return;
                            }
                          } catch (Exception failure) {
                            failure(failure);
                            ticking = false;
                            return;
                          }
                        }
                        store
                            .externalFinish(op.id(), System.currentTimeMillis())
                            .whenComplete(
                                (v, error) ->
                                    main(
                                        () -> {
                                          ticking = false;
                                          if (error != null) failure(error);
                                          else {
                                            Player p = Bukkit.getPlayer(op.actor());
                                            if (p != null)
                                              feedback(
                                                  p, "External transaction completed: " + op.id());
                                          }
                                        }));
                        return;
                      }
                      if (next.state().equals("APPLYING")) {
                        store
                            .acknowledge(next, "UNKNOWN")
                            .whenComplete(
                                (v, error) ->
                                    main(
                                        () -> {
                                          ticking = false;
                                          if (error != null) failure(error);
                                        }));
                        return;
                      }
                      if (!MoneyRules.mayRetry(next.state())) {
                        ticking = false;
                        return;
                      }
                      store
                          .applying(next)
                          .whenComplete(
                              (changed, error) ->
                                  main(
                                      () -> {
                                        if (error != null || !changed) {
                                          ticking = false;
                                          if (error != null) failure(error);
                                          return;
                                        }
                                        String state = "UNKNOWN";
                                        boolean declined = false;
                                        try {
                                          if (op.expected() != null
                                              && next.index() == 0
                                              && Double.compare(
                                                      vault.balance(next.player()), op.expected())
                                                  != 0) declined = true;
                                          else if (vault.apply(next.player(), next.delta()))
                                            state = "DONE";
                                          else declined = true;
                                        } catch (Exception failure) {
                                          failure(failure);
                                        }
                                        if (declined) state = "PENDING";
                                        String acknowledgement = state;
                                        boolean failed = declined;
                                        store
                                            .acknowledge(next, acknowledgement)
                                            .whenComplete(
                                                (v, err) -> {
                                                  CompletableFuture<?> done =
                                                      err == null && failed && next.index() == 0
                                                          ? store.decline(
                                                              op.id(),
                                                              "Provider declined before any"
                                                                  + " confirmed debit")
                                                          : CompletableFuture.completedFuture(null);
                                                  done.whenComplete(
                                                      (x, error2) ->
                                                          main(
                                                              () -> {
                                                                ticking = false;
                                                                if (err != null) failure(err);
                                                                if (error2 != null) failure(error2);
                                                              }));
                                                });
                                      }));
                    }));
  }

  private void recover(Player p) {
    if (!ready) return;
    UUID owner = p.getUniqueId();
    if (busy.contains(owner) || recovering.contains(owner) || !checking.add(owner)) return;
    store
        .deposits(owner)
        .whenComplete(
            (rows, e) ->
                main(
                    () -> {
                      checking.remove(owner);
                      if (e != null) {
                        recovering.remove(owner);
                        failure(e);
                        return;
                      }
                      if (!p.isOnline() || p.isDead() || rows.isEmpty()) {
                        recovering.remove(owner);
                        return;
                      }
                      recovering.add(owner);
                      recoverDeposit(p, rows.getFirst());
                    }));
  }

  private void recoverDeposit(Player p, MoneyStore.Deposit d) {
    if (!d.provider().equals(provider)) {
      recovering.remove(d.owner());
      return;
    }
    boolean found = found(p, d.id());
    if (d.state().equals("PREPARED")) {
      CompletableFuture<?> action =
          found ? store.tagged(d.id(), d.owner()) : store.abandon(d.id(), d.owner());
      action.whenComplete(
          (v, e) ->
              main(
                  () -> {
                    recovering.remove(d.owner());
                    if (e != null) failure(e);
                  }));
      return;
    }
    if (!p.isOnline() || p.isDead()) {
      recovering.remove(d.owner());
      return;
    }
    try {
      remove(p, d.id());
      p.saveData();
      store
          .captured(d, provider, System.currentTimeMillis())
          .whenComplete(
              (v, e) ->
                  main(
                      () -> {
                        recovering.remove(d.owner());
                        if (e != null) failure(e);
                        else
                          feedback(
                              p,
                              d.kind().equals("AUCTION")
                                  ? "Auction listing activated: " + d.id()
                                  : "Sale entered the payment ledger.");
                      }));
    } catch (Exception e) {
      recovering.remove(d.owner());
      failure(e);
    }
  }

  private void escrow(Player p, String kind, long price) {
    require(
        p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE,
        "Trade only in Survival or Adventure");
    require(
        !locked(p) && loot() != null && loot().canTrade(p),
        "Inventory is busy or contains an unconfirmed reward");
    ItemStack original = p.getInventory().getItemInMainHand().clone();
    require(
        !original.getType().isAir()
            && !locked(original)
            && !(original.getItemMeta() instanceof BlockStateMeta)
            && original.getType() != Material.BUNDLE,
        "Hold a tradable item stack; containers/bundles are excluded");
    require(
        original.getAmount() >= 1 && original.getAmount() <= original.getMaxStackSize(),
        "Unsupported stack size");
    UUID id = UUID.randomUUID();
    long now = System.currentTimeMillis();
    String label =
        original.getType().name()
            + " "
            + (original.getItemMeta().hasDisplayName()
                ? PlainTextComponentSerializer.plainText()
                    .serialize(original.getItemMeta().displayName())
                : "");
    var d =
        new MoneyStore.Deposit(
            id,
            p.getUniqueId(),
            original.serializeAsBytes(),
            label.substring(0, Math.min(120, label.length())),
            kind,
            price,
            "PREPARED",
            now,
            now + 1000L * config.yaml().getLong("auction.listing-seconds"),
            provider);
    busy.add(p.getUniqueId());
    store
        .deposit(
            d,
            config.yaml().getInt("auction.maximum-listings-per-player"),
            config.yaml().getInt("auction.maximum-listings"))
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      if (error != null) {
                        busy.remove(p.getUniqueId());
                        failure(error);
                        p.sendMessage("Escrow preparation failed; item untouched.");
                        return;
                      }
                      if (!p.isOnline()
                          || p.isDead()
                          || !p.getInventory().getItemInMainHand().equals(original)) {
                        busy.remove(p.getUniqueId());
                        store.abandon(id, p.getUniqueId());
                        return;
                      }
                      try {
                        ItemStack tagged = original.clone();
                        var meta = tagged.getItemMeta();
                        meta.getPersistentDataContainer()
                            .set(escrow, PersistentDataType.STRING, id.toString());
                        tagged.setItemMeta(meta);
                        p.getInventory().setItemInMainHand(tagged);
                        p.saveData();
                        store
                            .tagged(id, p.getUniqueId())
                            .whenComplete(
                                (x, e) ->
                                    main(
                                        () -> {
                                          busy.remove(p.getUniqueId());
                                          if (e != null) failure(e);
                                          recover(p);
                                        }));
                      } catch (Exception e) {
                        busy.remove(p.getUniqueId());
                        failure(e);
                      }
                    }));
  }

  private void buy(Player p, MarketConfig.Entry entry, Long rotation) {
    require(
        p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE,
        "Trade only in Survival or Adventure");
    require(!locked(p), "Inventory action pending");
    require(p.hasPermission(entry.permission()), "Catalog permission required");
    require(
        entry.buy() <= config.yaml().getLong("maximum-transaction"),
        "Price exceeds transaction limit");
    UUID id = UUID.randomUUID();
    LootStore.Parcel parcel = loot().prepareItem(p.getUniqueId(), factory.create(entry.reward()));
    var operation =
        op(
            id,
            p.getUniqueId(),
            rotation == null ? "SHOP" : "BLACK",
            entry.id() + ":" + entry.buy(),
            null,
            null);
    busy.add(p.getUniqueId());
    store
        .order(
            operation,
            List.of(new MoneyStore.Leg(p.getUniqueId(), -entry.buy())),
            parcel,
            rotation == null ? "SHOP" : "BLACK",
            entry.id(),
            rotation,
            entry.id(),
            entry.limit(),
            System.currentTimeMillis())
        .whenComplete(
            (v, e) ->
                main(
                    () -> {
                      busy.remove(p.getUniqueId());
                      if (e != null) {
                        failure(e);
                        p.sendMessage("Purchase refused: " + e.getCause());
                      } else queued(p, id);
                    }));
  }

  private void auctionBuy(Player p, UUID listing) {
    require(
        p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE,
        "Trade only in Survival or Adventure");
    require(!locked(p), "Inventory action pending");
    busy.add(p.getUniqueId());
    store
        .listing(listing)
        .whenComplete(
            (row, e) ->
                main(
                    () -> {
                      if (e != null) {
                        busy.remove(p.getUniqueId());
                        failure(e);
                        return;
                      }
                      try {
                        require(
                            row.provider().equals(provider), "Listing uses a different provider");
                        require(
                            row.price() <= config.yaml().getLong("maximum-transaction"),
                            "Price exceeds limit");
                        UUID id = UUID.randomUUID();
                        var parcel =
                            loot()
                                .prepareItem(
                                    p.getUniqueId(), ItemStack.deserializeBytes(row.payload()));
                        store
                            .order(
                                op(
                                    id,
                                    p.getUniqueId(),
                                    "AUCTION",
                                    "listing:" + listing,
                                    null,
                                    null),
                                List.of(
                                    new MoneyStore.Leg(p.getUniqueId(), -row.price()),
                                    new MoneyStore.Leg(row.seller(), row.price())),
                                parcel,
                                "AUCTION",
                                listing.toString(),
                                null,
                                null,
                                0,
                                System.currentTimeMillis())
                            .whenComplete(
                                (v, error) ->
                                    main(
                                        () -> {
                                          busy.remove(p.getUniqueId());
                                          if (error != null) {
                                            failure(error);
                                            p.sendMessage("Listing purchase refused.");
                                          } else queued(p, id);
                                        }));
                      } catch (Exception error) {
                        busy.remove(p.getUniqueId());
                        p.sendMessage(error.getMessage());
                      }
                    }));
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    String name = cmd.getName();
    String perm =
        name.equals("eco")
            ? "polder.economy.admin"
            : name.equals("balance")
                ? "polder.economy.balance"
                : name.equals("pay")
                    ? "polder.economy.pay"
                    : name.equals("ah")
                        ? "polder.auction.use"
                        : name.equals("blackmarket") ? "polder.blackmarket.use" : "polder.shop.use";
    if (!sender.hasPermission(perm)) {
      sender.sendMessage("No permission.");
      return true;
    }
    boolean recoveryCommand =
        name.equals("eco")
            && args.length > 0
            && List.of("pending", "resolve", "history", "stats", "cancel").contains(args[0]);
    if (!ready || provider.equals("disabled") && !recoveryCommand) {
      sender.sendMessage(
          "Economy unavailable. Configure an existing Vault provider or explicitly select sqlite;"
              + " balances are preserved.");
      return true;
    }
    try {
      if (name.equals("balance")) {
        require(sender instanceof Player, "Player command");
        var p = (Player) sender;
        if (args.length > 0 && args[0].equals("history"))
          result(p, store.history(p.getUniqueId()), rows -> rows.forEach(p::sendMessage));
        else if (vault == null)
          result(p, store.balance(p.getUniqueId()), b -> p.sendMessage("Balance: " + b));
        else p.sendMessage("Balance (" + provider + "): " + vault.balance(p.getUniqueId()));
      } else if (name.equals("pay")) {
        require(sender instanceof Player && args.length == 2, "/pay <known player|uuid> <amount>");
        Player p = (Player) sender;
        UUID other = Teams.resolve(args[0]);
        require(!other.equals(p.getUniqueId()), "Cannot pay yourself");
        long n = MoneyRules.amount(args[1], config.yaml().getLong("maximum-transaction"));
        money(
            p,
            p.getUniqueId(),
            "PAY",
            List.of(new MoneyStore.Leg(p.getUniqueId(), -n), new MoneyStore.Leg(other, n)),
            other + ":" + n,
            null,
            null);
      } else if (name.equals("eco")) admin(sender, args);
      else {
        require(sender instanceof Player, "Player command");
        Player p = (Player) sender;
        require(loot() != null, "Item mailbox unavailable");
        if (name.equals("shop")) {
          require(config.yaml().getBoolean("shop.enabled"), "Shop disabled");
          if (args.length == 2 && args[0].equals("sell")) {
            var entry = config.shop().get(args[1]);
            require(
                entry != null
                    && entry.kind().equals("ITEM")
                    && entry.sell() > 0
                    && p.hasPermission(entry.permission()),
                "Item not sellable or permitted");
            ItemStack hand = p.getInventory().getItemInMainHand();
            require(loot().canTrade(p), "Receipt validation pending");
            ItemStack clean = hand.clone();
            var meta = clean.getItemMeta();
            if (meta != null) {
              meta.getPersistentDataContainer().remove(new NamespacedKey(plugin, "loot_delivery"));
              meta.getPersistentDataContainer().remove(new NamespacedKey(plugin, "loot_owner"));
              clean.setItemMeta(meta);
            }
            require(
                clean.isSimilar(
                    new ItemStack(Objects.requireNonNull(Material.matchMaterial(entry.value())))),
                "Sell only the configured plain item");
            long price = Math.multiplyExact(entry.sell(), hand.getAmount());
            require(price <= config.yaml().getLong("maximum-transaction"), "Sale exceeds limit");
            escrow(p, "SELL", price);
          } else shop(p, false);
        }
        if (name.equals("blackmarket")) {
          require(config.yaml().getBoolean("black-market.enabled"), "Black market disabled");
          shop(p, true);
        }
        if (name.equals("ah")) {
          require(config.yaml().getBoolean("auction.enabled"), "Auction disabled");
          if (args.length == 2 && args[0].equals("sell"))
            escrow(
                p,
                "AUCTION",
                MoneyRules.amount(args[1], config.yaml().getLong("maximum-transaction")));
          else if (args.length == 2 && args[0].equals("buy"))
            auctionBuy(p, UUID.fromString(args[1]));
          else
            auctions(
                p,
                args.length > 0 && args[0].equals("mine"),
                args.length >= 2 && args[0].equals("search")
                    ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                    : "",
                0);
        }
      }
    } catch (Exception e) {
      sender.sendMessage(e.getMessage());
    }
    return true;
  }

  private void admin(CommandSender sender, String[] a) throws Exception {
    require(
        a.length > 0,
        "/eco give|take|set <player> <amount> | pending | resolve <op> <leg> applied|not-applied |"
            + " history <player> | stats");
    UUID admin = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
    if (a[0].equals("cancel") && a.length == 2) {
      result(
          sender,
          store.decline(UUID.fromString(a[1]), "Canceled by operator " + admin),
          v -> sender.sendMessage("Unapplied operation canceled; items/limits returned."));
      return;
    }
    if (a[0].equals("pending")) {
      result(sender, store.audit(), rows -> rows.forEach(sender::sendMessage));
      return;
    }
    if (a[0].equals("stats")) {
      result(sender, store.stats(), sender::sendMessage);
      return;
    }
    if (a[0].equals("history") && a.length == 2) {
      result(sender, store.history(Teams.resolve(a[1])), rows -> rows.forEach(sender::sendMessage));
      return;
    }
    if (a[0].equals("resolve") && a.length == 4) {
      require(
          List.of("applied", "not-applied").contains(a[3]),
          "Explicit provider reconciliation required");
      result(
          sender,
          store.resolve(
              UUID.fromString(a[1]),
              Integer.parseInt(a[2]),
              a[3].equals("applied"),
              admin,
              System.currentTimeMillis()),
          v ->
              sender.sendMessage(
                  "Attestation recorded; inspect provider history before resuming any uncertain"
                      + " payment."));
      return;
    }
    require(
        a.length == 3 && List.of("give", "take", "set").contains(a[0]),
        "/eco give|take|set <player> <whole amount>");
    UUID target = Teams.resolve(a[1]);
    long n =
        a[0].equals("set") && a[2].equals("0")
            ? 0
            : MoneyRules.amount(a[2], config.yaml().getLong("maximum-transaction"));
    if (a[0].equals("set")) {
      if (vault == null)
        money(
            sender,
            target,
            "SET",
            List.of(),
            "admin:" + admin + " target:" + target + " set:" + n,
            null,
            n);
      else {
        double before = vault.balance(target);
        require(
            before == Math.rint(before),
            "External fractional balances are preserved; use give/take instead");
        long delta = Math.subtractExact(n, (long) before);
        money(
            sender,
            target,
            "SET",
            delta == 0 ? List.of() : List.of(new MoneyStore.Leg(target, delta)),
            "admin:" + admin + " target:" + target + " set:" + n,
            before,
            n);
      }
    } else
      money(
          sender,
          admin,
          a[0].toUpperCase(Locale.ROOT),
          List.of(new MoneyStore.Leg(target, a[0].equals("give") ? n : -n)),
          "admin:" + admin + " target:" + target + " " + a[0] + ":" + n,
          null,
          null);
  }

  private static final class Menu implements InventoryHolder {
    Inventory inv;
    String type, query;
    int page;
    boolean mine;
    long rotation;
    Map<Integer, MarketConfig.Entry> entries = new HashMap<>();
    Map<Integer, UUID> listings = new HashMap<>();
    UUID confirmation;

    public Inventory getInventory() {
      return inv;
    }
  }

  private ItemStack icon(Material material, String title, String... lore) {
    var item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(title));
    meta.lore(Arrays.stream(lore).map(Component::text).toList());
    item.setItemMeta(meta);
    return item;
  }

  private Menu menu(String name, String type) {
    Menu m = new Menu();
    m.type = type;
    m.inv =
        Bukkit.createInventory(
            m,
            54,
            Component.text(
                config
                    .yaml()
                    .getString("gui-titles." + type, "{default}")
                    .replace("{default}", name)));
    for (int i = 45; i < 54; i++) m.inv.setItem(i, icon(Material.GRAY_STAINED_GLASS_PANE, " "));
    return m;
  }

  private void shop(Player p, boolean black) {
    if (!black) {
      var m = menu("PolderSMP Shop", "SHOP");
      int slot = 0;
      for (var e : config.shop().values()) {
        m.entries.put(slot, e);
        var item = factory.create(e.reward());
        var meta = item.getItemMeta();
        meta.lore(
            List.of(
                Component.text("Buy: " + e.buy()),
                Component.text("Sell per plain item: " + e.sell()),
                Component.text("Permission: " + e.permission()),
                Component.text("Left: buy • /shop sell " + e.id() + " to sell hand")));
        item.setItemMeta(meta);
        m.inv.setItem(slot++, item);
      }
      p.openInventory(m.inv);
      return;
    }
    long rotation =
        MoneyRules.rotation(
            System.currentTimeMillis(), config.yaml().getLong("black-market.refresh-seconds"));
    List<MarketConfig.Entry> selected = new ArrayList<>(config.pool());
    Collections.shuffle(selected);
    var seeds =
        selected.subList(0, config.yaml().getInt("black-market.stock-size")).stream()
            .map(e -> Map.entry(e.id(), e.encode()))
            .toList();
    result(
        p,
        store.stock(rotation, seeds),
        definitions -> {
          Menu m = menu("PolderSMP Black Market", "BLACK");
          m.rotation = rotation;
          int slot = 0;
          for (String definition : definitions)
            try {
              var e = MarketConfig.Entry.decode(definition);
              m.entries.put(slot, e);
              var item = factory.create(e.reward());
              var meta = item.getItemMeta();
              meta.lore(
                  List.of(
                      Component.text("Price: " + e.buy()),
                      Component.text("Per rotation: " + e.limit()),
                      Component.text(
                          "Refresh: "
                              + java.time.Instant.ofEpochMilli(
                                  (rotation + 1)
                                      * 1000L
                                      * config.yaml().getLong("black-market.refresh-seconds")))));
              item.setItemMeta(meta);
              m.inv.setItem(slot++, item);
            } catch (Exception error) {
              failure(error);
            }
          p.openInventory(m.inv);
        });
  }

  private void auctions(Player p, boolean mine, String query, int page) {
    result(
        p,
        store.listings(
            mine ? p.getUniqueId() : null,
            query,
            Math.min(1000, Math.max(0, page)),
            System.currentTimeMillis(),
            provider),
        rows -> {
          Menu m = menu("PolderSMP Auctions", "AUCTION");
          m.mine = mine;
          m.query = query;
          m.page = Math.max(0, page);
          for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            ItemStack item = ItemStack.deserializeBytes(row.payload());
            var meta = item.getItemMeta();
            meta.lore(
                List.of(
                    Component.text("Price: " + row.price()),
                    Component.text("Seller: " + row.seller()),
                    Component.text("Expires: " + java.time.Instant.ofEpochMilli(row.expires())),
                    Component.text("Click to inspect and confirm")));
            item.setItemMeta(meta);
            m.inv.setItem(i, item);
            m.listings.put(i, row.id());
          }
          m.inv.setItem(45, icon(Material.ARROW, "Previous"));
          m.inv.setItem(
              49,
              icon(
                  Material.CHEST,
                  "/ah sell <price>",
                  "/ah search <text>",
                  "Expired items return through your mailbox"));
          m.inv.setItem(53, icon(Material.ARROW, "Next"));
          p.openInventory(m.inv);
        });
  }

  private void confirm(Player p, UUID id) {
    result(
        p,
        store.listing(id),
        row -> {
          Menu m = menu("Confirm auction • " + row.price(), "CONFIRM");
          m.confirmation = id;
          m.inv.setItem(22, ItemStack.deserializeBytes(row.payload()));
          m.inv.setItem(
              40,
              icon(
                  Material.LIME_CONCRETE,
                  "Confirm purchase: " + row.price(),
                  "One listing • one buyer • persisted delivery"));
          p.openInventory(m.inv);
        });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void click(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (e.getView().getTopInventory().getHolder() instanceof Menu m) {
      e.setCancelled(true);
      if (!ready || provider.equals("disabled") || locked(p)) return;
      try {
        int slot = e.getRawSlot();
        if (m.entries.containsKey(slot)) {
          if (m.type.equals("BLACK"))
            require(
                m.rotation
                    == MoneyRules.rotation(
                        System.currentTimeMillis(),
                        config.yaml().getLong("black-market.refresh-seconds")),
                "Stock refreshed; reopen menu");
          buy(p, m.entries.get(slot), m.type.equals("BLACK") ? m.rotation : null);
        } else if (m.type.equals("AUCTION")) {
          if (m.listings.containsKey(slot)) confirm(p, m.listings.get(slot));
          if (slot == 45) auctions(p, m.mine, m.query, m.page - 1);
          if (slot == 53) auctions(p, m.mine, m.query, m.page + 1);
        } else if (m.type.equals("CONFIRM") && slot == 40) {
          auctionBuy(p, m.confirmation);
          p.closeInventory();
        }
      } catch (Exception error) {
        p.sendMessage(error.getMessage());
      }
    } else if (locked(p)
        || locked(e.getCurrentItem())
        || locked(e.getCursor())
        || e.getHotbarButton() >= 0 && locked(p.getInventory().getItem(e.getHotbarButton())))
      e.setCancelled(true);
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu
        || e.getWhoClicked() instanceof Player p && locked(p)
        || locked(e.getOldCursor())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void drop(PlayerDropItemEvent e) {
    if (locked(e.getPlayer()) || locked(e.getItemDrop().getItemStack())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void swap(PlayerSwapHandItemsEvent e) {
    if (locked(e.getPlayer()) || locked(e.getMainHandItem()) || locked(e.getOffHandItem()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void use(PlayerInteractEvent e) {
    if (locked(e.getPlayer()) || locked(e.getItem())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent e) {
    if (locked(e.getItem().getItemStack()) || e.getEntity() instanceof Player p && locked(p))
      e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void hopper(InventoryMoveItemEvent e) {
    if (locked(e.getItem())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityUse(PlayerInteractEntityEvent e) {
    if (locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void preciseEntityUse(PlayerInteractAtEntityEvent e) {
    if (locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void stand(PlayerArmorStandManipulateEvent e) {
    if (locked(e.getPlayer()) || locked(e.getPlayerItem()) || locked(e.getArmorStandItem()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void consume(PlayerItemConsumeEvent e) {
    if (locked(e.getPlayer()) || locked(e.getItem())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void durability(PlayerItemDamageEvent e) {
    if (locked(e.getPlayer()) || locked(e.getItem())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void place(org.bukkit.event.block.BlockPlaceEvent e) {
    if (locked(e.getPlayer()) || locked(e.getItemInHand())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void breakBlock(org.bukkit.event.block.BlockBreakEvent e) {
    if (locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void outgoing(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p && locked(p)) e.setCancelled(true);
    else if (e.getDamager() instanceof Projectile a
        && a.getShooter() instanceof Player p
        && locked(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void opened(InventoryOpenEvent e) {
    if (e.getPlayer() instanceof Player p && locked(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void inventoryPickup(InventoryPickupItemEvent e) {
    if (locked(e.getItem().getItemStack())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void craft(CraftItemEvent e) {
    if (e.getWhoClicked() instanceof Player p && locked(p)
        || Arrays.stream(e.getInventory().getMatrix()).anyMatch(this::locked)) e.setCancelled(true);
  }

  @EventHandler
  public void prepareCraft(PrepareItemCraftEvent e) {
    if (Arrays.stream(e.getInventory().getMatrix()).anyMatch(this::locked))
      e.getInventory().setResult(null);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void died(PlayerDeathEvent e) {
    for (ItemStack i : new ArrayList<>(e.getDrops()))
      if (locked(i)) {
        e.getDrops().remove(i);
        if (!e.getKeepInventory()) e.getItemsToKeep().add(i);
      }
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    recover(e.getPlayer());
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    main(() -> recover(e.getPlayer()));
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
    if (args.length == 1) {
      List<String> choices =
          switch (c.getName()) {
            case "eco" ->
                s.hasPermission("polder.economy.admin")
                    ? List.of(
                        "give", "take", "set", "pending", "resolve", "cancel", "history", "stats")
                    : List.of();
            case "ah" -> List.of("sell", "search", "mine", "buy");
            case "shop" -> List.of("sell");
            case "balance" -> List.of("history");
            default -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
          };
      return choices.stream().filter(v -> v.startsWith(args[0])).toList();
    }
    if (c.getName().equals("shop") && args.length == 2)
      return config.shop().keySet().stream().filter(v -> v.startsWith(args[1])).toList();
    if (c.getName().equals("eco") && args.length == 2)
      return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    return List.of();
  }

  public void close() {
    closed = true;
    task.cancel();
    Bukkit.getServicesManager().unregister(Economy.class, gate);
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
  }
}
