package nl.poldersmp.core.loot;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.gameplay.Weapons;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class Loot implements Listener, TabExecutor, AutoCloseable {
  final JavaPlugin plugin;
  final LootStore store;
  final RewardFactory factory;
  volatile LootConfig config;
  private final NamespacedKey keyId, keyTier, deliveryId, deliveryOwner;
  private final Map<String, LootStore.Crate> crates = new HashMap<>();
  private final Set<UUID> mailChecks = new HashSet<>();
  private final Set<UUID> unlocked = new HashSet<>(),
      checking = new HashSet<>(),
      operations = new HashSet<>(),
      deliveries = new HashSet<>(),
      animations = new HashSet<>(),
      initial = new HashSet<>();
  private final Set<org.bukkit.scheduler.BukkitTask> animationsTasks = new HashSet<>();
  private boolean ready, initializationFailed;
  private volatile boolean closed;
  private long nextBonus;
  private final org.bukkit.scheduler.BukkitTask tick;
  private Vaults vaults;

  public Loot(JavaPlugin plugin, Database database, Weapons weapons) {
    this.plugin = plugin;
    this.store = new LootStore(database);
    this.factory = new RewardFactory(plugin, weapons);
    this.config = LootConfig.load(plugin);
    validateRewards(config);
    keyId = new NamespacedKey(plugin, "crate_key_id");
    keyTier = new NamespacedKey(plugin, "crate_key_tier");
    deliveryId = new NamespacedKey(plugin, "loot_delivery");
    deliveryOwner = new NamespacedKey(plugin, "loot_owner");
    for (String name : List.of("crates", "crateadmin", "vaults", "vaultadmin")) {
      plugin.getCommand(name).setExecutor(this);
      plugin.getCommand(name).setTabCompleter(this);
    }
    Bukkit.getPluginManager().registerEvents(this, plugin);
    for (Player p : Bukkit.getOnlinePlayers()) initial.add(p.getUniqueId());
    store
        .migrate()
        .thenCompose(v -> store.crates())
        .whenComplete(
            (locations, error) ->
                main(
                    () -> {
                      if (error != null) {
                        initializationFailed = true;
                        initial.clear();
                        failed(error);
                        return;
                      }
                      for (var location : locations) crates.put(position(location), location);
                      ready = true;
                      vaults = new Vaults(this);
                      plugin
                          .getLogger()
                          .info("Loot persistence ready; crate locations=" + crates.size());
                    }));
    Bukkit.getServicesManager()
        .register(Loot.class, this, plugin, org.bukkit.plugin.ServicePriority.Normal);
    tick = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
  }

  private void validateRewards(LootConfig candidate) {
    for (var map : List.of(candidate.crateRewards(), candidate.vaultRewards()))
      for (var pool : map.values()) for (var reward : pool) factory.create(reward.value());
  }

  void main(Runnable action) {
    if (!closed && plugin.isEnabled())
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (!closed) action.run();
              });
  }

  void failed(Throwable error) {
    plugin.getLogger().severe("Loot operation failed: " + error);
  }

  <T> void result(CommandSender sender, CompletableFuture<T> future, Consumer<T> done) {
    future.whenComplete(
        (v, e) ->
            main(
                () -> {
                  if (e != null) {
                    failed(e);
                    sender.sendMessage(
                        "Loot operation failed; any reserved rewards remain in your mailbox.");
                  } else done.accept(v);
                }));
  }

  private static String position(UUID world, int x, int y, int z) {
    return world + ":" + x + ":" + y + ":" + z;
  }

  private static String position(LootStore.Crate crate) {
    return position(crate.world(), crate.x(), crate.y(), crate.z());
  }

  static String position(Block block) {
    return position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
  }

  boolean crateAt(Block block) {
    return crates.containsKey(position(block));
  }

  private boolean busy(Player player) {
    return operations.contains(player.getUniqueId())
        || deliveries.contains(player.getUniqueId())
        || initial.contains(player.getUniqueId());
  }

  private String tag(ItemStack item, NamespacedKey key) {
    return item != null && item.hasItemMeta()
        ? item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING)
        : null;
  }

  private boolean hasLockedInventory(Player player) {
    boolean any = false;
    for (ItemStack item : player.getInventory().getContents()) any = locked(item) || any;
    return any;
  }

  private boolean locked(ItemStack item) {
    String value = tag(item, deliveryId);
    if (value == null) return false;
    try {
      UUID id = UUID.fromString(value), owner = UUID.fromString(tag(item, deliveryOwner));
      if (unlocked.contains(id)) return false;
      if (ready && checking.add(id))
        store
            .completed(id, owner)
            .whenComplete(
                (done, error) ->
                    main(
                        () -> {
                          checking.remove(id);
                          if (error != null) failed(error);
                          else if (done) unlocked.add(id);
                        }));
      return true;
    } catch (Exception e) {
      return true;
    }
  }

  public ItemStack key(UUID id, LootRules.Tier tier) {
    ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK);
    var meta = item.getItemMeta();
    meta.displayName(Component.text("✦ " + tier + " Crate Key"));
    meta.lore(
        List.of(
            Component.text("Registered physical key • one opening"),
            Component.text("Use on a matching PolderSMP crate.")));
    meta.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, id.toString());
    meta.getPersistentDataContainer().set(keyTier, PersistentDataType.STRING, tier.name());
    item.setItemMeta(meta);
    return item;
  }

  private LootStore.Parcel parcel(UUID owner, ItemStack item, long available) {
    UUID id = UUID.randomUUID();
    var meta = item.getItemMeta();
    meta.getPersistentDataContainer().set(deliveryId, PersistentDataType.STRING, id.toString());
    meta.getPersistentDataContainer()
        .set(deliveryOwner, PersistentDataType.STRING, owner.toString());
    item.setItemMeta(meta);
    return new LootStore.Parcel(id, owner, item.serializeAsBytes(), "WAITING", available);
  }

  LootConfig.Reward select(LootRules.Tier tier, boolean vault) {
    var pool = (vault ? config.vaultRewards() : config.crateRewards()).get(tier);
    return LootRules.select(pool, ThreadLocalRandom.current().nextLong(LootRules.total(pool)));
  }

  LootStore.Parcel reward(UUID owner, LootConfig.Reward definition, long available) {
    return parcel(owner, factory.create(definition), available);
  }

  /** Call on the Paper thread. Retry the same earned reward with the same stable source ID. */
  public CompletableFuture<Boolean> grantKey(UUID owner, LootRules.Tier tier, String source) {
    UUID id = UUID.randomUUID();
    long now = System.currentTimeMillis();
    return store.issue(id, tier, parcel(owner, key(id, tier), now), source, now);
  }

  /** Event integrations prepare frozen payloads on the Paper thread, then persist their outbox. */
  public boolean canTrade(Player player) {
    return ready && !busy(player) && !hasLockedInventory(player);
  }

  public LootStore.Parcel prepareItem(UUID owner, ItemStack item) {
    return parcel(owner, item.clone(), System.currentTimeMillis());
  }

  public LootStore.Parcel prepareReward(UUID owner, LootRules.Tier tier) {
    return reward(owner, select(tier, false), System.currentTimeMillis());
  }

  public CompletableFuture<Boolean> enqueuePrepared(LootStore.Parcel reward, String source) {
    return store.enqueue(reward, source, System.currentTimeMillis());
  }

  public CompletableFuture<Location> findEventSite(
      List<World> worlds, int minimum, int maximum, int attempts, boolean ocean) {
    if (!ready || vaults == null)
      return CompletableFuture.failedFuture(
          new IllegalStateException("Wilderness service is initializing"));
    return vaults.findSite(worlds, minimum, maximum, attempts, ocean);
  }

  public CompletableFuture<Void> loadEventArea(Location location) {
    if (vaults == null)
      return CompletableFuture.failedFuture(
          new IllegalStateException("Wilderness service is initializing"));
    return vaults.loadArea(location);
  }

  public boolean eventWilderness(Location location) {
    return vaults != null && vaults.approveArea(location);
  }

  public void holdEventArea(UUID owner, Location location) {
    vaults.holdArea(owner, location);
  }

  public void releaseEventArea(UUID owner) {
    if (vaults != null) vaults.releaseArea(owner);
  }

  private void tick() {
    if (!ready) return;
    for (Player player : Bukkit.getOnlinePlayers()) hasLockedInventory(player);
    for (Player p : Bukkit.getOnlinePlayers())
      if (!p.isDead() && !busy(p) && !animations.contains(p.getUniqueId())) mail(p);
      else if (initial.contains(p.getUniqueId())
          && !deliveries.contains(p.getUniqueId())
          && !p.isDead()) mail(p);
    if (System.currentTimeMillis() >= nextBonus && config.crates().getBoolean("enabled")) {
      nextBonus = System.currentTimeMillis() + 10000;
      store
          .bonuses()
          .whenComplete(
              (bonuses, error) ->
                  main(
                      () -> {
                        if (error != null) {
                          failed(error);
                          return;
                        }
                        for (var bonus : bonuses) {
                          var tier =
                              LootRules.Tier.valueOf(
                                  config
                                      .crates()
                                      .getString(
                                          "bonuses."
                                              + (bonus.kind().equals("BOUNTY")
                                                  ? "bounty"
                                                  : "king")));
                          grantKey(bonus.player(), tier, bonus.source())
                              .exceptionally(
                                  e -> {
                                    failed(e);
                                    return false;
                                  });
                        }
                      }));
    }
  }

  private void mail(Player player) {
    UUID owner = player.getUniqueId();
    if (deliveries.contains(owner) || !mailChecks.add(owner)) return;
    store
        .parcels(owner, System.currentTimeMillis())
        .whenComplete(
            (parcels, error) ->
                main(
                    () -> {
                      mailChecks.remove(owner);
                      if (error != null) {
                        initial.remove(owner);
                        failed(error);
                        return;
                      }
                      if (!player.isOnline() || player.isDead()) return;
                      if (parcels.isEmpty()) {
                        initial.remove(owner);
                        return;
                      }
                      if (deliveries.add(owner)) deliver(player, parcels.getFirst());
                    }));
  }

  private void deliver(Player player, LootStore.Parcel parcel) {
    UUID owner = player.getUniqueId();
    if (!player.isOnline() || player.isDead()) {
      deliveries.remove(owner);
      return;
    }
    int existing = find(player, parcel.id());
    if (existing < 0 && player.getInventory().firstEmpty() < 0) {
      initial.remove(owner);
      deliveries.remove(owner);
      return;
    }
    if (parcel.state().equals("WAITING")) {
      store
          .delivering(parcel.id(), owner)
          .whenComplete(
              (changed, error) ->
                  main(
                      () -> {
                        if (error != null) {
                          deliveries.remove(owner);
                          failed(error);
                          return;
                        }
                        if (!changed) {
                          deliveries.remove(owner);
                          return;
                        }
                        deliver(
                            player,
                            new LootStore.Parcel(
                                parcel.id(),
                                owner,
                                parcel.payload(),
                                "DELIVERING",
                                parcel.available()));
                      }));
      return;
    }
    try {
      if (existing < 0) {
        int slot = player.getInventory().firstEmpty();
        if (slot < 0) {
          deliveries.remove(owner);
          return;
        }
        player.getInventory().setItem(slot, ItemStack.deserializeBytes(parcel.payload()));
      }
      player.saveData();
    } catch (Exception e) {
      deliveries.remove(owner);
      failed(e);
      return;
    }
    store
        .delivered(parcel.id(), owner)
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      deliveries.remove(owner);
                      if (error != null) {
                        initial.add(owner);
                        failed(error);
                        return;
                      }
                      unlocked.add(parcel.id());
                      initial.remove(owner);
                      player.sendActionBar(
                          Component.text("Reward delivered safely to your inventory."));
                    }));
  }

  private int find(Player player, UUID parcel) {
    ItemStack[] contents = player.getInventory().getContents();
    for (int i = 0; i < contents.length; i++)
      if (parcel.toString().equals(tag(contents[i], deliveryId))) return i;
    return -1;
  }

  private static final class Menu implements InventoryHolder {
    Inventory inventory;
    LootRules.Tier tier;
    boolean opening;

    public Inventory getInventory() {
      return inventory;
    }
  }

  private ItemStack icon(Material material, String name) {
    ItemStack item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(name));
    item.setItemMeta(meta);
    return item;
  }

  private ItemStack preview(LootConfig.Reward definition, long weight, long total) {
    ItemStack item = factory.create(definition);
    var meta = item.getItemMeta();
    for (NamespacedKey key : new HashSet<>(meta.getPersistentDataContainer().getKeys()))
      meta.getPersistentDataContainer().remove(key);
    List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
    lore.add(
        Component.text(
            "Weight "
                + weight
                + " • "
                + String.format(Locale.ROOT, "%.2f%%", 100.0 * weight / total)));
    meta.lore(lore);
    item.setItemMeta(meta);
    return item;
  }

  private void preview(Player player, LootRules.Tier tier) {
    Menu menu = new Menu();
    menu.tier = tier;
    menu.inventory =
        Bukkit.createInventory(
            menu, 54, Component.text(config.crates().getString("gui-title") + " • " + tier));
    var pool = config.crateRewards().get(tier);
    long total = LootRules.total(pool);
    for (int i = 0; i < pool.size(); i++)
      menu.inventory.setItem(i, preview(pool.get(i).value(), pool.get(i).weight(), total));
    menu.inventory.setItem(
        49, icon(Material.TRIPWIRE_HOOK, "One registered matching key = one guaranteed reward"));
    player.openInventory(menu.inventory);
  }

  private void menu(Player player) {
    Menu menu = new Menu();
    menu.inventory =
        Bukkit.createInventory(menu, 54, Component.text(config.crates().getString("gui-title")));
    int i = 20;
    for (var tier : LootRules.Tier.values())
      menu.inventory.setItem(i++, icon(Material.CHEST, tier + " • Click to preview"));
    player.openInventory(menu.inventory);
  }

  private void animate(Player player, LootRules.Tier tier, LootConfig.Reward definition) {
    UUID owner = player.getUniqueId();
    animations.add(owner);
    Menu menu = new Menu();
    menu.opening = true;
    menu.tier = tier;
    menu.inventory = Bukkit.createInventory(menu, 54, Component.text("Opening • " + tier));
    player.openInventory(menu.inventory);
    int duration = config.crates().getInt("animation-ticks");
    int[] elapsed = {0};
    org.bukkit.scheduler.BukkitTask[] task = {null};
    task[0] =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  elapsed[0] += 5;
                  if (elapsed[0] >= duration) {
                    animations.remove(owner);
                    animationsTasks.remove(task[0]);
                    task[0].cancel();
                    if (player.isOnline()) {
                      menu.inventory.setItem(22, preview(definition, 1, 1));
                      effects(player.getLocation(), false);
                      announce(player, tier, definition);
                      mail(player);
                    }
                    return;
                  }
                  var random = select(tier, false);
                  menu.inventory.setItem(22, preview(random, 1, 1));
                  if (player.isOnline() && config.crates().getBoolean("sounds", true))
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, .4f, 1.2f);
                },
                1,
                5);
    animationsTasks.add(task[0]);
  }

  void effects(Location location, boolean vault) {
    var c = vault ? config.vaults() : config.crates();
    if (c.getBoolean("sounds", true))
      location.getWorld().playSound(location, Sound.BLOCK_ENDER_CHEST_OPEN, 1, 1);
    if (c.getBoolean("particles", true))
      location
          .getWorld()
          .spawnParticle(Particle.ENCHANT, location.clone().add(0, 1, 0), 30, .5, .5, .5, .1);
  }

  void announce(Player player, LootRules.Tier tier, LootConfig.Reward definition) {
    if (definition.announce() && config.crates().getBoolean("rare-announcements", true))
      Bukkit.broadcast(
          Component.text(player.getName() + " found " + definition.id() + " • " + tier));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    if (e.getHand() != EquipmentSlot.HAND) return;
    Player player = e.getPlayer();
    if (busy(player) || hasLockedInventory(player) || locked(e.getItem())) {
      e.setCancelled(true);
      return;
    }
    Block block = e.getClickedBlock();
    if (block == null || !e.getAction().isRightClick()) return;
    if (vaults != null && vaults.interact(player, block)) {
      e.setCancelled(true);
      return;
    }
    var crate = crates.get(position(block));
    if (crate == null) return;
    e.setCancelled(true);
    if (!ready
        || !config.crates().getBoolean("enabled")
        || !player.hasPermission("polder.crates.use")) return;
    String id = tag(e.getItem(), keyId), tier = tag(e.getItem(), keyTier);
    if (e.getItem() == null
        || e.getItem().getType() != Material.TRIPWIRE_HOOK
        || !LootRules.validKey(tier, id, e.getItem() == null ? 0 : e.getItem().getAmount())
        || !crate.tier().name().equals(tier)) {
      preview(player, crate.tier());
      return;
    }
    UUID owner = player.getUniqueId(), key = UUID.fromString(id);
    if (animations.contains(owner) || !operations.add(owner)) return;
    var definition = select(crate.tier(), false);
    long now = System.currentTimeMillis();
    var reward = reward(owner, definition, now + config.crates().getLong("animation-ticks") * 50);
    store
        .open(key, crate.tier(), reward, definition.id(), now)
        .whenComplete(
            (opened, error) ->
                main(
                    () -> {
                      operations.remove(owner);
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      if (!opened) {
                        player.sendMessage("This key is invalid or already spent.");
                        return;
                      }
                      for (int slot = 0; slot < player.getInventory().getSize(); slot++)
                        if (id.equals(tag(player.getInventory().getItem(slot), keyId)))
                          player.getInventory().setItem(slot, null);
                      if (player.isOnline()) player.saveData();
                      if (player.isOnline()) animate(player, crate.tier(), definition);
                    }));
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu menu) {
      e.setCancelled(true);
      if (!menu.opening
          && menu.tier == null
          && e.getWhoClicked() instanceof Player p
          && e.getRawSlot() >= 20
          && e.getRawSlot() < 25) {
        LootRules.Tier tier = LootRules.Tier.values()[e.getRawSlot() - 20];
        Bukkit.getScheduler().runTask(plugin, () -> preview(p, tier));
      }
      return;
    }
    if (e.getWhoClicked() instanceof Player p
        && (busy(p)
            || locked(e.getCurrentItem())
            || locked(e.getCursor())
            || e.getHotbarButton() >= 0 && locked(p.getInventory().getItem(e.getHotbarButton()))))
      e.setCancelled(true);
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu
        || e.getWhoClicked() instanceof Player p && (busy(p) || locked(e.getOldCursor())))
      e.setCancelled(true);
  }

  @EventHandler
  public void drop(PlayerDropItemEvent e) {
    if (busy(e.getPlayer()) || locked(e.getItemDrop().getItemStack())) e.setCancelled(true);
  }

  @EventHandler
  public void swap(PlayerSwapHandItemsEvent e) {
    if (busy(e.getPlayer()) || locked(e.getMainHandItem()) || locked(e.getOffHandItem()))
      e.setCancelled(true);
  }

  @EventHandler
  public void consume(PlayerItemConsumeEvent e) {
    if (busy(e.getPlayer()) || hasLockedInventory(e.getPlayer()) || locked(e.getItem()))
      e.setCancelled(true);
  }

  @EventHandler
  public void damage(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p
        && (busy(p) || hasLockedInventory(p) || locked(p.getInventory().getItemInMainHand())))
      e.setCancelled(true);
  }

  @EventHandler
  public void bow(EntityShootBowEvent e) {
    if (e.getEntity() instanceof Player player && (busy(player) || hasLockedInventory(player)))
      e.setCancelled(true);
  }

  @EventHandler
  public void pickup(EntityPickupItemEvent e) {
    if (locked(e.getItem().getItemStack())) e.setCancelled(true);
  }

  @EventHandler
  public void move(InventoryMoveItemEvent e) {
    if (locked(e.getItem()) || crateInventory(e.getSource()) || crateInventory(e.getDestination()))
      e.setCancelled(true);
  }

  private boolean crateInventory(Inventory inventory) {
    if (inventory instanceof DoubleChestInventory both)
      return crateInventory(both.getLeftSide()) || crateInventory(both.getRightSide());
    return inventory.getLocation() != null && protectedBlock(inventory.getLocation().getBlock());
  }

  @EventHandler
  public void open(InventoryOpenEvent e) {
    if (crateInventory(e.getInventory())) e.setCancelled(true);
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    for (var item : new ArrayList<>(e.getDrops()))
      if (locked(item)) {
        e.getDrops().remove(item);
        e.getItemsToKeep().add(item);
      }
  }

  @EventHandler
  public void joined(PlayerJoinEvent e) {
    if (!initializationFailed) initial.add(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void left(PlayerQuitEvent e) {
    initial.remove(e.getPlayer().getUniqueId());
  }

  private boolean protectedBlock(Block block) {
    return crateAt(block) || vaults != null && vaults.protectedBlock(block);
  }

  @EventHandler(ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent e) {
    if (protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    if (protectedBlock(e.getBlockPlaced())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void explode(EntityExplodeEvent e) {
    e.blockList().removeIf(this::protectedBlock);
  }

  @EventHandler(ignoreCancelled = true)
  public void explode(BlockExplodeEvent e) {
    e.blockList().removeIf(this::protectedBlock);
  }

  @EventHandler(ignoreCancelled = true)
  public void piston(BlockPistonExtendEvent e) {
    if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void piston(BlockPistonRetractEvent e) {
    if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    String name = command.getName();
    boolean admin = name.endsWith("admin");
    if (!sender.hasPermission(
        admin
            ? name.equals("crateadmin") ? "polder.crates.admin" : "polder.vaults.admin"
            : name.equals("crates") ? "polder.crates.use" : "polder.vaults.use")) {
      sender.sendMessage("No permission.");
      return true;
    }
    if (!ready) {
      sender.sendMessage(
          initializationFailed
              ? "Loot service unavailable; contact an administrator."
              : "Loot database is initializing.");
      return true;
    }
    try {
      if (name.startsWith("vault")) {
        vaults.command(sender, name, args);
        return true;
      }
      if (name.equals("crates")) {
        if (args.length > 0 && args[0].equalsIgnoreCase("top"))
          result(
              sender,
              store.top("CRATE"),
              rows -> {
                sender.sendMessage("Crate openings (UUID : count)");
                rows.forEach(sender::sendMessage);
              });
        else if (sender instanceof Player p) {
          if (args.length > 0 && args[0].equalsIgnoreCase("keys")) {
            Map<String, Integer> counts = new TreeMap<>();
            for (var item : p.getInventory().getContents()) {
              String tier = tag(item, keyTier);
              if (tier != null) counts.merge(tier, 1, Integer::sum);
            }
            p.sendMessage(
                "Physical keys: "
                    + counts
                    + ". Pending rewards deliver automatically when space is available.");
            mail(p);
          } else menu(p);
        } else sender.sendMessage("/crates top");
        return true;
      }
      if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
        reload();
        sender.sendMessage("Loot settings reloaded.");
      } else if (args.length == 4 && args[0].equalsIgnoreCase("givekey")) {
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) throw new IllegalArgumentException("Online player required");
        var tier = LootRules.Tier.valueOf(args[2].toUpperCase(Locale.ROOT));
        int amount = Integer.parseInt(args[3]);
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Amount must be 1..64");
        List<CompletableFuture<Boolean>> grants = new ArrayList<>();
        for (int i = 0; i < amount; i++)
          grants.add(grantKey(target.getUniqueId(), tier, "admin:" + UUID.randomUUID()));
        result(
            sender,
            CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)),
            v -> sender.sendMessage(amount + " registered keys queued to the player's mailbox."));
      } else if (sender instanceof Player p && args.length >= 1) {
        Block block = p.getTargetBlockExact(6);
        if (block == null) throw new IllegalArgumentException("Look at a chest or barrel");
        if (args[0].equalsIgnoreCase("set") && args.length == 2) {
          if (!(block.getState() instanceof Container container)
              || !(block.getType() == Material.CHEST || block.getType() == Material.BARREL)
              || !container.getInventory().isEmpty())
            throw new IllegalArgumentException("Use an empty single chest or barrel");
          if (block.getState() instanceof Chest chest
              && chest.getInventory().getHolder() instanceof DoubleChest)
            throw new IllegalArgumentException("Double chests are unsupported");
          var crate =
              new LootStore.Crate(
                  block.getWorld().getUID(),
                  block.getX(),
                  block.getY(),
                  block.getZ(),
                  LootRules.Tier.valueOf(args[1].toUpperCase(Locale.ROOT)));
          result(
              sender,
              store.setCrate(crate),
              v -> {
                crates.put(position(crate), crate);
                sender.sendMessage("Crate location registered.");
              });
        } else if (args[0].equalsIgnoreCase("remove") && args.length == 1) {
          var crate = crates.get(position(block));
          if (crate == null) throw new IllegalArgumentException("Not a registered crate");
          result(
              sender,
              store.removeCrate(crate),
              v -> {
                crates.remove(position(crate));
                sender.sendMessage("Crate unregistered; block preserved.");
              });
        } else
          sender.sendMessage(
              "/crateadmin set <type> | remove | givekey <player> <type> <amount> | reload");
      } else sender.sendMessage("/crateadmin givekey <player> <type> <amount> | reload");
    } catch (Exception e) {
      sender.sendMessage("Unable to complete: " + e.getMessage());
    }
    return true;
  }

  void reload() {
    LootConfig next = LootConfig.load(plugin);
    validateRewards(next);
    config = next;
    if (vaults != null) vaults.reload();
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command c, String alias, String[] args) {
    boolean admin = c.getName().endsWith("admin");
    if (!sender.hasPermission(
        admin
            ? c.getName().equals("crateadmin") ? "polder.crates.admin" : "polder.vaults.admin"
            : c.getName().equals("crates") ? "polder.crates.use" : "polder.vaults.use"))
      return List.of();
    List<String> choices =
        args.length == 1
            ? c.getName().equals("crateadmin")
                ? List.of("set", "remove", "givekey", "reload")
                : c.getName().equals("vaultadmin")
                    ? List.of("spawn", "remove", "reload")
                    : List.of("keys", "top")
            : args.length == 2 && args[0].equalsIgnoreCase("givekey")
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : args.length == 2 || args.length == 3
                    ? Arrays.stream(LootRules.Tier.values()).map(Enum::name).toList()
                    : List.of();
    return choices.stream()
        .filter(
            s ->
                s.toLowerCase(Locale.ROOT)
                    .startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
        .toList();
  }

  @Override
  public void close() {
    closed = true;
    Bukkit.getServicesManager().unregister(Loot.class, this);
    tick.cancel();
    for (var task : animationsTasks) task.cancel();
    animationsTasks.clear();
    if (vaults != null) vaults.close();
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
  }
}
