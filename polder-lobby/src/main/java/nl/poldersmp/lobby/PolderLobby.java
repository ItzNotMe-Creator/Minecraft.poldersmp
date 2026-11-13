package nl.poldersmp.lobby;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import nl.poldersmp.common.Settings;
import nl.poldersmp.lobby.data.LobbyStore;
import nl.poldersmp.lobby.network.LobbyNetwork;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class PolderLobby extends JavaPlugin implements Listener, TabExecutor {
  YamlConfiguration cfg, parkourConfig, locations, messages;
  LobbyStore store;
  final LobbyNetwork network = new LobbyNetwork();
  final Activities activities = new Activities();
  final Map<UUID, LobbyStore.Profile> profiles = new HashMap<>();
  final Map<UUID, Map<String, Object>> networkProfiles = new HashMap<>();
  final Map<UUID, String> ranks = new HashMap<>();
  private final Map<UUID, Long> clocks = new HashMap<>();
  private final Set<UUID> welcomed = new HashSet<>();
  private final Set<UUID> loaded = new HashSet<>();
  Parkour parkour;
  LobbyCosmetics cosmetics;
  LobbyGadgets gadgets;
  LobbyLocations places;
  LobbyEvents events;
  LobbyMenus menus;
  NamespacedKey navKey;
  boolean ready, closing;
  private Runnable unregisterPlaceholders;

  @Override
  public void onEnable() {
    try {
      var foundation = Settings.load(getDataFolder().toPath());
      if (!foundation.enabled()) return;
      if (!foundation.databaseEnabled()) {
        getLogger()
            .warning(
                "Phase 8 features require database.enabled=true; persistent activities remain"
                    + " disabled.");
        return;
      }
      load();
      if (!cfg.getBoolean("enabled")) return;
      navKey = new NamespacedKey(this, "navigation");
      store = new LobbyStore(getDataFolder().toPath().resolve("lobby.db"));
      parkour = new Parkour(this);
      cosmetics = new LobbyCosmetics(this);
      gadgets = new LobbyGadgets(this);
      places = new LobbyLocations(this);
      events = new LobbyEvents(this);
      menus = new LobbyMenus(this);
      Bukkit.getPluginManager().registerEvents(this, this);
      for (String name :
          List.of(
              "lobby",
              "lobbyadmin",
              "parkour",
              "daily",
              "profile",
              "cosmetics",
              "gadgets",
              "lobbyevent")) {
        getCommand(name).setExecutor(this);
        getCommand(name).setTabCompleter(this);
      }
      store
          .migrate()
          .whenComplete(
              (v, e) ->
                  main(
                      () -> {
                        if (e != null) {
                          getLogger().severe("Lobby database: " + e);
                          Bukkit.getPluginManager().disablePlugin(this);
                          return;
                        }
                        ready = true;
                        events.restoreSchedule();
                        for (Player p : Bukkit.getOnlinePlayers()) initialize(p);
                      }));
      Bukkit.getScheduler()
          .runTaskTimer(
              this,
              () -> {
                if (ready) {
                  flushTime();
                  network.refresh();
                  for (Player p : Bukkit.getOnlinePlayers()) refresh(p.getUniqueId());
                }
              },
              20,
              cfg.getInt("network.poll-seconds") * 20L);
      if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
        var expansion = new LobbyPlaceholders(this);
        expansion.register();
        unregisterPlaceholders = expansion::unregister;
      }
    } catch (Exception e) {
      getLogger().severe("Lobby startup: " + e);
      Bukkit.getPluginManager().disablePlugin(this);
    }
  }

  void load() throws Exception {
    var a = yaml("lobby.yml");
    var b = yaml("parkour.yml");
    var c = yaml("locations.yml");
    var d = yaml("messages.yml");
    ZoneId.of(a.getString("daily.timezone"));
    if (a.getInt("network.poll-seconds") < 5
        || a.getInt("network.poll-seconds") > 300
        || a.getInt("network.max-age-seconds") < 5
        || a.getInt("network.max-age-seconds") > 600
        || a.getInt("cosmetics.maximum-visual-players") < 1
        || a.getInt("cosmetics.maximum-visual-players") > 100
        || a.getLong("daily.tokens") < 0
        || a.getLong("daily.tokens") > 1000000
        || a.getInt("events.interval-seconds") < 60
        || a.getInt("events.warning-seconds") < 1
        || a.getInt("events.duration-seconds") < 10
        || a.getInt("events.duration-seconds") > 3600
        || a.getInt("events.minimum-participants") < 1
        || b.getLong("minimum-millis") < 1
        || b.getLong("checkpoint-penalty-millis") < 0
        || b.getLong("first-completion-tokens") < 0
        || b.getLong("first-completion-tokens") > 1000000
        || b.getDouble("maximum-step") < 1
        || b.getDouble("maximum-step") > 20)
      throw new IllegalArgumentException("Invalid lobby limits");
    if (c.getKeys(false).stream()
        .anyMatch(
            k ->
                c.getConfigurationSection(k) == null
                    || c.getConfigurationSection(k).getKeys(false).size() > 64))
      throw new IllegalArgumentException("Max 64 locations per type");
    if (b.getConfigurationSection("courses") == null
        || b.getConfigurationSection("courses").getKeys(false).size() > 21)
      throw new IllegalArgumentException("Max 21 courses");
    var catalog = a.getConfigurationSection("cosmetics.catalog");
    if (catalog == null || catalog.getKeys(false).size() > 128)
      throw new IllegalArgumentException("Configure at most 128 cosmetics");
    for (String id : catalog.getKeys(false)) {
      identifier(id);
      String base = id + ".";
      if (!Set.of("trail", "hat", "wings", "pet", "join", "title")
              .contains(catalog.getString(base + "category", ""))
          || catalog.getLong(base + "price") < 0
          || catalog.getLong(base + "price") > 1000000
          || Material.matchMaterial(catalog.getString(base + "material", "")) == null)
        throw new IllegalArgumentException("Invalid cosmetic: " + id);
    }
    cfg = a;
    parkourConfig = b;
    locations = c;
    messages = d;
  }

  YamlConfiguration yaml(String file) throws Exception {
    File f = new File(getDataFolder(), file);
    if (!f.exists()) saveResource(file, false);
    var y = new YamlConfiguration();
    y.load(f);
    return y;
  }

  void save(YamlConfiguration y, String file) throws IOException {
    y.save(new File(getDataFolder(), file));
  }

  public boolean module(String name) {
    return ready && cfg.getBoolean("modules." + name, false);
  }

  public void main(Runnable r) {
    if (isEnabled() && !closing)
      Bukkit.getScheduler()
          .runTask(
              this,
              () -> {
                if (!closing) r.run();
              });
  }

  <T> void result(Player p, CompletableFuture<T> future, Consumer<T> action) {
    future.whenComplete(
        (v, e) ->
            main(
                () -> {
                  if (!p.isOnline()) return;
                  if (e != null) {
                    p.sendMessage("Action unavailable: " + root(e).getMessage());
                    getLogger().fine("Lobby action: " + e);
                  } else action.accept(v);
                }));
  }

  static Throwable root(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e;
  }

  boolean use(Player p, String module) {
    if (!module(module) || !p.hasPermission("polder.lobby." + module)) {
      p.sendMessage("This feature is unavailable.");
      return false;
    }
    return true;
  }

  void msg(Player p, String key) {
    p.sendMessage(messages.getString("prefix", "") + messages.getString(key, key));
  }

  void msg(Player player, String key, String placeholder, String value) {
    player.sendMessage(
        messages.getString("prefix", "")
            + messages.getString(key, key).replace("{" + placeholder + "}", value));
  }

  public boolean acquireActivity(UUID id, String activity) {
    boolean acquired = activities.acquire(id, activity);
    if (acquired && gadgets != null) {
      Player player = Bukkit.getPlayer(id);
      if (player != null) gadgets.suspend(player);
    }
    return acquired;
  }

  public void releaseActivity(UUID id, String activity) {
    activities.release(id, activity);
  }

  boolean cinematic(UUID id) {
    var plugin = Bukkit.getPluginManager().getPlugin("PolderCinematics");
    if (plugin == null || !plugin.isEnabled()) return false;
    try {
      return Boolean.TRUE.equals(
          plugin.getClass().getMethod("isPlaying", UUID.class).invoke(plugin, id));
    } catch (Exception e) {
      return true;
    }
  }

  boolean busy(Player p) {
    return activities.busy(p.getUniqueId()) || cinematic(p.getUniqueId());
  }

  public void introFinished(UUID id) {
    Player p = Bukkit.getPlayer(id);
    if (p != null && loaded.contains(id) && !cinematic(id)) welcome(p);
  }

  private void initialize(Player p) {
    UUID id = p.getUniqueId();
    result(
        p,
        store.join(id, p.getName()).thenCompose(v -> store.profile(id)),
        profile -> {
          loaded.add(id);
          profiles.put(id, profile);
          clocks.put(id, System.nanoTime());
          refresh(id);
          Bukkit.getScheduler()
              .runTaskLater(
                  this,
                  () -> {
                    if (p.isOnline() && !cinematic(id)) welcome(p);
                  },
                  2);
        });
  }

  void refresh(UUID id) {
    store
        .profile(id)
        .whenComplete(
            (v, e) ->
                main(
                    () -> {
                      if (e == null && v != null && Bukkit.getPlayer(id) != null)
                        profiles.put(id, v);
                    }));
    network
        .profile(id)
        .whenComplete(
            (v, e) ->
                main(
                    () -> {
                      if (e == null && Bukkit.getPlayer(id) != null) networkProfiles.put(id, v);
                    }));
    ranks.put(id, rank(id));
  }

  private String rank(UUID id) {
    var provider = Bukkit.getPluginManager().getPlugin("LuckPerms");
    if (provider == null) return "Player";
    try {
      Class<?> api =
          Class.forName(
              "net.luckperms.api.LuckPermsProvider", true, provider.getClass().getClassLoader());
      Object lp = api.getMethod("get").invoke(null);
      Class<?> iface =
          Class.forName("net.luckperms.api.LuckPerms", true, provider.getClass().getClassLoader());
      Object manager = iface.getMethod("getUserManager").invoke(lp);
      Object user =
          Class.forName(
                  "net.luckperms.api.model.user.UserManager",
                  true,
                  provider.getClass().getClassLoader())
              .getMethod("getUser", UUID.class)
              .invoke(manager, id);
      return user == null
          ? "Player"
          : String.valueOf(
              Class.forName(
                      "net.luckperms.api.model.user.User",
                      true,
                      provider.getClass().getClassLoader())
                  .getMethod("getPrimaryGroup")
                  .invoke(user));
    } catch (Exception e) {
      return "Player";
    }
  }

  private void welcome(Player p) {
    if (!welcomed.add(p.getUniqueId())) return;
    if (module("welcome")) {
      p.showTitle(
          Title.title(
              Component.text(cfg.getString("welcome.title")),
              Component.text(cfg.getString("welcome.subtitle"))));
      try {
        p.playSound(p.getLocation(), Sound.valueOf(cfg.getString("welcome.sound")), .7f, 1);
      } catch (Exception ignored) {
      }
      if (cfg.getBoolean("welcome.particles"))
        for (int i = 0; i < 20; i++) {
          int frame = i;
          Bukkit.getScheduler()
              .runTaskLater(
                  this,
                  () -> {
                    if (p.isOnline() && !busy(p))
                      p.getWorld()
                          .spawnParticle(
                              Particle.END_ROD,
                              p.getLocation().add(0, .2 + frame * .06, 0),
                              5,
                              .6,
                              .1,
                              .6,
                              .01);
                  },
                  i * 2L);
        }
      result(
          p,
          store.reward(p.getUniqueId(), "welcome:first", 0, "", System.currentTimeMillis()),
          first -> {
            if (first) {
              if (cfg.getBoolean("welcome.announcements"))
                Bukkit.broadcast(Component.text("Welcome to PolderSMP, " + p.getName() + "!"));
              if (cfg.getBoolean("welcome.first-join-firework")) cosmetics.firework(p);
            }
          });
      cosmetics.arrival(p);
    }
    navigation(p);
  }

  void navigation(Player p) {
    if (!cfg.getBoolean("navigation.enabled")) return;
    var section = cfg.getConfigurationSection("navigation");
    for (String id : List.of("selector", "cosmetics", "profile", "parkour", "daily")) {
      boolean exists = false;
      for (ItemStack old : p.getInventory().getContents())
        if (old != null
            && id.equals(
                old.getItemMeta()
                    .getPersistentDataContainer()
                    .get(navKey, PersistentDataType.STRING))) exists = true;
      if (exists) continue;
      ItemStack item =
          LobbyMenus.item(
              Material.valueOf(section.getString(id + ".material")),
              section.getString(id + ".name"),
              List.of("Right-click to open"));
      var meta = item.getItemMeta();
      meta.getPersistentDataContainer().set(navKey, PersistentDataType.STRING, id);
      item.setItemMeta(meta);
      int slot = section.getInt(id + ".slot");
      if (slot < 0 || slot > 8) slot = -1;
      if (slot < 0 || p.getInventory().getItem(slot) != null) slot = p.getInventory().firstEmpty();
      if (slot >= 0) p.getInventory().setItem(slot, item);
    }
  }

  void flushTime() {
    Map<UUID, Long> seconds = new HashMap<>();
    long now = System.nanoTime();
    clocks.replaceAll(
        (id, last) -> {
          long delta = (now - last) / 1000000000L;
          if (delta > 0) seconds.put(id, delta);
          return last + delta * 1000000000L;
        });
    if (!seconds.isEmpty())
      store
          .time(seconds)
          .exceptionally(
              e -> {
                getLogger().severe("Playtime flush failed: " + e);
                return null;
              });
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    if (ready) initialize(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    Player p = e.getPlayer();
    if (ready) flushTime();
    clocks.remove(p.getUniqueId());
    loaded.remove(p.getUniqueId());
    welcomed.remove(p.getUniqueId());
    profiles.remove(p.getUniqueId());
    networkProfiles.remove(p.getUniqueId());
    ranks.remove(p.getUniqueId());
    if (parkour != null) parkour.quit(p, false);
    if (cosmetics != null) cosmetics.remove(p.getUniqueId());
    activities.clear(p.getUniqueId());
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    if (e.getItem() == null || !e.getAction().isRightClick()) return;
    String id =
        e.getItem()
            .getItemMeta()
            .getPersistentDataContainer()
            .get(navKey, PersistentDataType.STRING);
    if (id != null) {
      e.setCancelled(true);
      if (busy(e.getPlayer())) msg(e.getPlayer(), "activity-busy");
      else menus.open(e.getPlayer(), id);
    }
  }

  @EventHandler
  public void deathItems(PlayerDeathEvent event) {
    event
        .getDrops()
        .removeIf(i -> i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(navKey));
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent event) {
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              if (event.getPlayer().isOnline() && !cinematic(event.getPlayer().getUniqueId()))
                navigation(event.getPlayer());
            },
            3);
  }

  @EventHandler(ignoreCancelled = true)
  public void drop(PlayerDropItemEvent e) {
    if (e.getItemDrop().getItemStack().getItemMeta().getPersistentDataContainer().has(navKey))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void damage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p
        && lobbyWorld(p.getWorld())
        && cfg.getBoolean("protect-world")
        && !p.hasPermission("polder.lobby.build")) e.setCancelled(true);
    if (e instanceof EntityDamageByEntityEvent d
        && d.getDamager().getPersistentDataContainer().has(new NamespacedKey(this, "visual")))
      e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void block(BlockBreakEvent e) {
    if (protect(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    if (protect(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void worldInteract(PlayerInteractEvent e) {
    if (protect(e.getPlayer())
        && e.getClickedBlock() != null
        && e.getClickedBlock().getState() instanceof org.bukkit.block.Container)
      e.setCancelled(true);
  }

  boolean lobbyWorld(World w) {
    String name = cfg.getString("spawn.world", "");
    return !name.isEmpty() && name.equals(w.getName());
  }

  boolean protect(Player p) {
    return cfg.getBoolean("protect-world")
        && lobbyWorld(p.getWorld())
        && !p.hasPermission("polder.lobby.build");
  }

  Location location(YamlConfiguration file, String path) {
    var sec = file.getConfigurationSection(path);
    if (sec == null || !sec.contains("world")) return null;
    World w = Bukkit.getWorld(sec.getString("world", ""));
    if (w == null) return null;
    for (String key : List.of("x", "y", "z"))
      if (!sec.isDouble(key) && !sec.isInt(key) && !sec.isLong(key)) return null;
    Location l =
        new Location(
            w,
            sec.getDouble("x"),
            sec.getDouble("y"),
            sec.getDouble("z"),
            (float) sec.getDouble("yaw"),
            (float) sec.getDouble("pitch"));
    return Double.isFinite(l.getX())
            && Double.isFinite(l.getY())
            && Double.isFinite(l.getZ())
            && w.getWorldBorder().isInside(l)
            && l.getY() > w.getMinHeight()
            && l.getY() < w.getMaxHeight() - 1
        ? l
        : null;
  }

  static Map<String, Object> point(Location l) {
    return Map.of(
        "world",
        l.getWorld().getName(),
        "x",
        l.getX(),
        "y",
        l.getY(),
        "z",
        l.getZ(),
        "yaw",
        l.getYaw(),
        "pitch",
        l.getPitch());
  }

  boolean safe(Location l) {
    return l != null
        && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)
        && l.getBlock().isPassable()
        && !l.getBlock().isLiquid()
        && l.clone().add(0, 1, 0).getBlock().isPassable()
        && l.clone().add(0, -1, 0).getBlock().getType().isSolid()
        && !Set.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
            .contains(l.clone().add(0, -1, 0).getBlock().getType());
  }

  void transfer(Player p) {
    transfer(p, "selector");
  }

  void transfer(Player p, String feature) {
    if (!use(p, feature) || busy(p)) return;
    var status = network.status(cfg.getLong("network.max-age-seconds") * 1000);
    if (status.isEmpty()) {
      msg(p, "network-unavailable");
      return;
    }
    if (!Boolean.TRUE.equals(status.get("online"))) {
      msg(p, "server-offline");
      return;
    }
    if (Boolean.TRUE.equals(status.get("locked"))) {
      msg(p, "server-locked");
      return;
    }
    result(
        p,
        network.transfer(
            p.getUniqueId(),
            cfg.getString("network.target"),
            cfg.getLong("network.max-age-seconds") * 1000),
        ok -> {
          if (!ok) msg(p, "network-unavailable");
        });
  }

  @Override
  public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
    if (!(s instanceof Player p)) {
      s.sendMessage("Use these lobby commands in game.");
      return true;
    }
    if (!ready || !loaded.contains(p.getUniqueId())) {
      p.sendMessage("Lobby data is not ready.");
      return true;
    }
    try {
      switch (cmd.getName()) {
        case "lobbyadmin" -> admin(p, a);
        case "parkour" -> {
          if (use(p, "parkour")) parkour.command(p, a);
        }
        case "lobbyevent" -> {
          if (use(p, "events")) events.command(p, a);
        }
        case "gadgets" -> {
          if (use(p, "gadgets")) gadgets.command(p, a);
        }
        case "profile" -> {
          if (use(p, "profiles")) {
            if (a.length == 0) menus.profile(p, p.getUniqueId());
            else {
              var offline = Bukkit.getOfflinePlayerIfCached(a[0]);
              if (offline == null)
                throw new IllegalArgumentException("Unknown player; join the lobby first");
              menus.profile(p, offline.getUniqueId());
            }
          }
        }
        case "cosmetics" -> {
          if (use(p, "cosmetics")) {
            if (a.length > 1 && a[0].equalsIgnoreCase("preview")) cosmetics.preview(p, a[1]);
            else menus.open(p, "cosmetics");
          }
        }
        case "daily" -> menus.open(p, "daily");
        default -> menus.open(p, "home");
      }
    } catch (Exception e) {
      p.sendMessage("Lobby action refused: " + e.getMessage());
    }
    return true;
  }

  private void admin(Player p, String[] a) throws Exception {
    if (!p.hasPermission("polder.lobby.admin")) return;
    if (a.length == 0) {
      p.sendMessage(
          "/lobbyadmin setspawn | reload | portal <id> <pos1|pos2> | hologram <id> <kind> | secret"
              + " <id> <star|room|treasure> | npc <Citizens-id>"
              + " <selector|cosmetics|parkour|profile|daily> | remove"
              + " <portals|holograms|secrets|npcs> <id>");
      return;
    }
    switch (a[0]) {
      case "setspawn" -> {
        if (!safe(p.getLocation())) throw new IllegalArgumentException("Stand at a safe spawn");
        cfg.set("spawn", point(p.getLocation()));
        save(cfg, "lobby.yml");
      }
      case "reload" -> {
        load();
        menus.closeAll();
        parkour.stopAll();
        events.stop();
        cosmetics.cleanup();
        gadgets.cleanup();
        places.reload();
        p.sendMessage(
            "Lobby reloaded; running activities cancelled safely. Timer intervals apply on"
                + " restart.");
      }
      case "portal" -> {
        String id = identifier(a[1]);
        capacity("portals", id);
        if (!List.of("pos1", "pos2").contains(a[2]))
          throw new IllegalArgumentException("pos1 or pos2");
        locations.set("portals." + id + "." + a[2], point(p.getLocation()));
        save(locations, "locations.yml");
      }
      case "hologram" -> {
        if (!LobbyLocations.KINDS.contains(a[2]))
          throw new IllegalArgumentException("Kinds: " + LobbyLocations.KINDS);
        capacity("holograms", identifier(a[1]));
        locations.set("holograms." + identifier(a[1]) + ".location", point(p.getLocation()));
        locations.set("holograms." + a[1] + ".kind", a[2]);
        save(locations, "locations.yml");
        places.reload();
      }
      case "secret" -> {
        if (!List.of("star", "room", "treasure").contains(a[2]))
          throw new IllegalArgumentException("star, room, treasure");
        capacity("secrets", identifier(a[1]));
        locations.set("secrets." + identifier(a[1]) + ".location", point(p.getLocation()));
        locations.set("secrets." + a[1] + ".type", a[2]);
        locations.set("secrets." + a[1] + ".tokens", 2);
        locations.set("secrets." + a[1] + ".unlock", "");
        save(locations, "locations.yml");
      }
      case "npc" -> {
        int id = Integer.parseInt(a[1]);
        if (id < 0
            || !List.of("selector", "cosmetics", "parkour", "profile", "daily").contains(a[2]))
          throw new IllegalArgumentException("Invalid NPC action");
        capacity("npcs", String.valueOf(id));
        locations.set("npcs." + id + ".action", a[2]);
        save(locations, "locations.yml");
        p.sendMessage("Registered existing Citizens NPC. Citizens must be installed.");
      }
      case "remove" -> {
        if (!List.of("portals", "holograms", "secrets", "npcs").contains(a[1]))
          throw new IllegalArgumentException("Unknown location type");
        locations.set(a[1] + "." + identifier(a[2]), null);
        save(locations, "locations.yml");
        places.reload();
      }
      default -> throw new IllegalArgumentException("Unknown admin action");
    }
    p.sendMessage("Lobby settings saved.");
  }

  private void capacity(String type, String id) {
    if (!locations.contains(type + "." + id)
        && locations.getConfigurationSection(type).getKeys(false).size() >= 64)
      throw new IllegalArgumentException("Max 64 locations per type");
  }

  static String identifier(String id) {
    if (!id.matches("[a-zA-Z0-9_-]{1,40}"))
      throw new IllegalArgumentException("Use letters, numbers, _ or - (max 40)");
    return id;
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
    if (!ready) return List.of();
    List<String> values =
        switch (c.getName()) {
          case "parkour" ->
              a.length == 1
                  ? List.of(
                      "start",
                      "reset",
                      "quit",
                      "top",
                      "create",
                      "setstart",
                      "setfinish",
                      "checkpoint",
                      "deletecheckpoint",
                      "delete")
                  : a.length == 2
                      ? new ArrayList<>(
                          parkourConfig.getConfigurationSection("courses").getKeys(false))
                      : List.of();
          case "lobbyadmin" ->
              s.hasPermission("polder.lobby.admin")
                  ? List.of("setspawn", "reload", "portal", "hologram", "secret", "npc", "remove")
                  : List.of();
          case "gadgets" -> List.of("grapple", "blaster", "staff", "off");
          case "lobbyevent" -> List.of("status", "top", "start", "stop", "schedule");
          case "cosmetics" -> List.of("preview");
          default -> List.of();
        };
    String last = a.length == 0 ? "" : a[a.length - 1];
    return values.stream().filter(v -> v.startsWith(last.toLowerCase(Locale.ROOT))).toList();
  }

  @Override
  public void onDisable() {
    if (menus != null) menus.closeAll();
    if (events != null) events.stop();
    if (parkour != null) parkour.stopAll();
    if (cosmetics != null) cosmetics.cleanup();
    if (gadgets != null) gadgets.cleanup();
    if (places != null) places.cleanup();
    if (unregisterPlaceholders != null) unregisterPlaceholders.run();
    if (store != null) {
      flushTime();
      closing = true;
      store.close();
    }
    ready = false;
  }
}
