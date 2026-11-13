package nl.poldersmp.cinematics;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import nl.poldersmp.common.Settings;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class PolderCinematics extends JavaPlugin implements Listener, TabExecutor {
  private IntroStore store;
  private YamlConfiguration config;
  private boolean ready, internal, closed;
  private final Map<UUID, Plan> active = new HashMap<>();
  private final Set<UUID> waiting = new HashSet<>();
  private final Set<UUID> skipped = new HashSet<>(), recoveryErrors = new HashSet<>();
  private org.bukkit.scheduler.BukkitTask ticker;

  private static final class Plan {
    Player player;
    IntroStore.Saved saved;
    List<CameraPath.Point> points;
    Location landing;
    String token = "";
    boolean preview, running, finishing;
    long started;
    int ticks;
    int scene = -1;
    Set<Chunk> held = new HashSet<>();
  }

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
      if (!new File(getDataFolder(), "config.yml").exists()) saveResource("config.yml", false);
      config = read();
      store = new IntroStore(getDataFolder().toPath().resolve("cinematics.db"));
      getCommand("joinintro").setExecutor(this);
      getCommand("joinintro").setTabCompleter(this);
      Bukkit.getPluginManager().registerEvents(this, this);
      store
          .migrate()
          .whenComplete(
              (v, e) ->
                  main(
                      () -> {
                        if (e != null) {
                          getLogger().severe("Intro persistence: " + e);
                          Bukkit.getPluginManager().disablePlugin(this);
                        } else {
                          ready = true;
                          for (Player player : Bukkit.getOnlinePlayers())
                            if (waiting.add(player.getUniqueId())) recover(player);
                        }
                      }));
      ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
    } catch (Exception e) {
      getLogger().severe("Intro startup: " + e);
      Bukkit.getPluginManager().disablePlugin(this);
    }
  }

  private YamlConfiguration read() throws Exception {
    var y = new YamlConfiguration();
    y.load(new File(getDataFolder(), "config.yml"));
    if (!List.of("FIRST_JOIN", "EVERY_JOIN", "NETWORK_SESSION").contains(y.getString("mode"))
        || !List.of("CATMULL_ROM", "LINEAR").contains(y.getString("curve"))
        || !List.of("SMOOTHSTEP", "LINEAR").contains(y.getString("easing"))
        || y.getInt("duration-seconds") < 1
        || y.getInt("duration-seconds") > 120
        || y.getInt("maximum-chunks") < 1
        || y.getInt("maximum-chunks") > 64
        || y.getInt("delay-ticks") < 0
        || y.getInt("delay-ticks") > 200)
      throw new IllegalArgumentException("Invalid intro timing/curve/chunk limits");
    if (y.getMapList("points").size() > 64) throw new IllegalArgumentException("Max 64 waypoints");
    return y;
  }

  private void main(Runnable r) {
    if (!closed && isEnabled())
      Bukkit.getScheduler()
          .runTask(
              this,
              () -> {
                if (!closed) r.run();
              });
  }

  public boolean isPlaying(UUID id) {
    return waiting.contains(id) || active.containsKey(id);
  }

  public void skip(UUID id) {
    Player p = Bukkit.getPlayer(id);
    Plan plan = active.get(id);
    if (plan != null) finish(plan, !plan.preview && config.getBoolean("skip-marks-seen"));
    else {
      if (waiting.contains(id)) {
        skipped.add(id);
        if (p != null && recoveryErrors.remove(id)) recover(p);
      }
    }
  }

  private Object lobby(String method, Class<?>[] types, Object... args) {
    var p = Bukkit.getPluginManager().getPlugin("PolderLobby");
    if (p == null || !p.isEnabled()) return null;
    try {
      return p.getClass().getMethod(method, types).invoke(p, args);
    } catch (Exception e) {
      getLogger().warning("Lobby coordination unavailable: " + e.getClass().getSimpleName());
      return null;
    }
  }

  private boolean lease(Player p) {
    var plugin = Bukkit.getPluginManager().getPlugin("PolderLobby");
    if (plugin == null || !plugin.isEnabled()) return true;
    Object result =
        lobby(
            "acquireActivity",
            new Class[] {UUID.class, String.class},
            p.getUniqueId(),
            "CINEMATIC");
    return Boolean.TRUE.equals(result);
  }

  private void handoff(Player p) {
    lobby("releaseActivity", new Class[] {UUID.class, String.class}, p.getUniqueId(), "CINEMATIC");
    lobby("introFinished", new Class[] {UUID.class}, p.getUniqueId());
  }

  private CompletableFuture<String> session(UUID id) {
    for (Class<?> type : Bukkit.getServicesManager().getKnownServices())
      if (type.getName().equals("nl.poldersmp.common.network.NetworkBridge"))
        try {
          Object s = Bukkit.getServicesManager().getRegistration((Class) type).getProvider();
          if (((Number) type.getMethod("apiVersion").invoke(s)).intValue() != 1
              || !((Set<?>) type.getMethod("capabilities").invoke(s)).contains("sessions"))
            continue;
          return ((CompletableFuture<String>) type.getMethod("session", UUID.class).invoke(s, id))
              .orTimeout(2, TimeUnit.SECONDS)
              .exceptionally(e -> "");
        } catch (Exception e) {
          getLogger().warning("Network session unavailable; using first-join policy");
        }
    return CompletableFuture.completedFuture("");
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void join(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    waiting.add(p.getUniqueId());
    Bukkit.getScheduler().runTaskLater(this, () -> recover(p), config.getInt("delay-ticks"));
  }

  private void recover(Player p) {
    if (!p.isOnline()) {
      waiting.remove(p.getUniqueId());
      return;
    }
    if (!ready) {
      Bukkit.getScheduler().runTaskLater(this, () -> recover(p), 10);
      return;
    }
    store
        .pending(p.getUniqueId())
        .whenComplete(
            (saved, error) ->
                main(
                    () -> {
                      if (error != null) {
                        recoveryErrors.add(p.getUniqueId());
                        p.sendMessage(
                            "Introduction recovery unavailable; ask an administrator to check"
                                + " persistence, then /joinintro skip to retry.");
                        return;
                      }
                      if (saved != null) {
                        Plan recovery = new Plan();
                        recovery.player = p;
                        recovery.saved = saved;
                        recovery.landing = landing(saved);
                        finish(recovery, false);
                        return;
                      }
                      recoveryErrors.remove(p.getUniqueId());
                      if (!waiting.contains(p.getUniqueId())) return;
                      if (skipped.remove(p.getUniqueId())
                          || !config.getBoolean("enabled")
                          || !p.hasPermission("polder.intro.use")) {
                        waiting.remove(p.getUniqueId());
                        handoff(p);
                        return;
                      }
                      String selectedMode = config.getString("mode");
                      session(p.getUniqueId())
                          .whenComplete(
                              (token, e) -> {
                                String mode = selectedMode;
                                if (mode.equals("NETWORK_SESSION")
                                    && (token == null || token.isBlank())) mode = "FIRST_JOIN";
                                store
                                    .eligible(p.getUniqueId(), mode, token)
                                    .whenComplete(
                                        (eligible, f) ->
                                            main(
                                                () -> {
                                                  if (!waiting.contains(p.getUniqueId())
                                                      || !p.isOnline()) return;
                                                  if (skipped.remove(p.getUniqueId())
                                                      || f != null
                                                      || !eligible) {
                                                    waiting.remove(p.getUniqueId());
                                                    handoff(p);
                                                  } else start(p, false, token);
                                                }));
                              });
                    }));
  }

  private Location location(Map<?, ?> m) {
    World w = Bukkit.getWorld(String.valueOf(m.get("world")));
    if (w == null) throw new IllegalArgumentException("Configure a loaded lobby world");
    double x = num(m, "x"), y = num(m, "y"), z = num(m, "z");
    return new Location(w, x, y, z, (float) num(m, "yaw"), (float) num(m, "pitch"));
  }

  private double num(Map<?, ?> m, String k) {
    if (List.of("x", "y", "z").contains(k) && !(m.get(k) instanceof Number))
      throw new IllegalArgumentException("Missing waypoint coordinate: " + k);
    Object n = m.get(k);
    double d = n instanceof Number v ? v.doubleValue() : 0;
    if (!Double.isFinite(d)) throw new IllegalArgumentException("Nonfinite waypoint");
    return d;
  }

  private Location configuredSpawn() {
    var section = config.getConfigurationSection("spawn");
    return section == null ? null : location(section.getValues(false));
  }

  private boolean safe(Location l) {
    return l != null
        && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)
        && l.getWorld().getWorldBorder().isInside(l)
        && l.getY() > l.getWorld().getMinHeight()
        && l.getY() + 1 < l.getWorld().getMaxHeight()
        && l.getBlock().isPassable()
        && !l.getBlock().isLiquid()
        && l.clone().add(0, 1, 0).getBlock().isPassable()
        && !l.clone().add(0, 1, 0).getBlock().isLiquid()
        && l.clone().add(0, -1, 0).getBlock().getType().isSolid()
        && !Set.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
            .contains(l.clone().add(0, -1, 0).getBlock().getType());
  }

  private Location landing(IntroStore.Saved s) {
    try {
      Location l = configuredSpawn();
      if (l != null
          && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)
          && safe(l)) return l;
    } catch (Exception ignored) {
    }
    World w = Bukkit.getWorld(s.world());
    if (w == null) return null;
    Location old = new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    if (safe(old)) return old;
    Location spawn = w.getSpawnLocation();
    return safe(spawn) ? spawn : null;
  }

  private void start(Player p, boolean preview, String token) {
    waiting.remove(p.getUniqueId());
    if (active.containsKey(p.getUniqueId())) return;
    if (active.size() >= 32) {
      handoff(p);
      p.sendMessage("Introduction capacity reached; welcome to the lobby.");
      return;
    }
    Plan plan = new Plan();
    plan.player = p;
    plan.preview = preview;
    plan.token = token == null ? "" : token;
    try {
      if (p.isDead()
          || p.isInsideVehicle()
          || p.isGliding()
          || p.isSleeping()
          || p.isRiptiding()
          || (p.getGameMode() == GameMode.SPECTATOR && p.getSpectatorTarget() != null))
        throw new IllegalArgumentException(
            "Leave vehicles and other controlled movement before an intro");
      Location target = configuredSpawn();
      if (target == null || !safe(target) || !safe(p.getLocation()))
        throw new IllegalArgumentException(
            "Record a safe spawn; intro origin and landing must be safe");
      if (!p.getWorld().equals(target.getWorld()))
        throw new IllegalArgumentException("Introduction only runs in its configured lobby world");
      var raw = config.getMapList("points");
      if (raw.size() < 2) throw new IllegalArgumentException("Add at least two waypoints");
      List<CameraPath.Point> points = new ArrayList<>();
      for (var m : raw) {
        Location l = location(m);
        if (!l.getWorld().equals(target.getWorld()))
          throw new IllegalArgumentException("Waypoints must share the spawn world");
        points.add(new CameraPath.Point(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
      }
      for (int i = 1; i < points.size(); i++) {
        var a = points.get(i - 1);
        var b = points.get(i);
        if (Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2)
            > 512 * 512)
          throw new IllegalArgumentException("Waypoint segments must be at most 512 blocks");
      }
      if (!lease(p)) throw new IllegalArgumentException("Another lobby activity is active");
      Location old = p.getLocation();
      plan.saved =
          new IntroStore.Saved(
              p.getUniqueId(),
              UUID.randomUUID().toString(),
              p.getGameMode().name(),
              p.getAllowFlight(),
              p.isFlying(),
              p.getFlySpeed(),
              old.getWorld().getName(),
              old.getX(),
              old.getY(),
              old.getZ(),
              old.getYaw(),
              old.getPitch());
      plan.points = List.copyOf(points);
      plan.landing = target;
      Set<Long> chunks = new HashSet<>();
      for (int i = 0; i <= config.getInt("duration-seconds") * 20; i++) {
        var point =
            CameraPath.sample(
                points,
                (double) i / (config.getInt("duration-seconds") * 20),
                config.getString("curve"),
                config.getString("easing"));
        Location l = new Location(target.getWorld(), point.x(), point.y(), point.z());
        if (!l.getWorld().getWorldBorder().isInside(l)
            || l.getY() < l.getWorld().getMinHeight() + 1
            || l.getY() >= l.getWorld().getMaxHeight())
          throw new IllegalArgumentException("Camera path outside world/border");
        chunks.add((((long) (l.getBlockX() >> 4)) << 32) ^ ((l.getBlockZ() >> 4) & 0xffffffffL));
      }
      chunks.add(
          (((long) (target.getBlockX() >> 4)) << 32) ^ ((target.getBlockZ() >> 4) & 0xffffffffL));
      if (chunks.size() > config.getInt("maximum-chunks"))
        throw new IllegalArgumentException("Path exceeds chunk budget");
      active.put(p.getUniqueId(), plan);
      List<CompletableFuture<?>> futures = new ArrayList<>();
      for (long key : chunks)
        futures.add(
            target
                .getWorld()
                .getChunkAtAsync((int) (key >> 32), (int) key, true)
                .thenAccept(
                    chunk ->
                        main(
                            () -> {
                              if (active.get(p.getUniqueId()) == plan) {
                                chunk.addPluginChunkTicket(this);
                                plan.held.add(chunk);
                              }
                            })));
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
          .orTimeout(15, TimeUnit.SECONDS)
          .whenComplete(
              (v, e) ->
                  main(
                      () -> {
                        if (active.get(p.getUniqueId()) != plan) return;
                        if (e != null || !p.isOnline()) {
                          finish(plan, false);
                          return;
                        }
                        store
                            .save(plan.saved)
                            .whenComplete(
                                (x, error) ->
                                    main(
                                        () -> {
                                          if (active.get(p.getUniqueId()) != plan) return;
                                          if (error != null) {
                                            getLogger().severe("Intro snapshot: " + error);
                                            finish(plan, false);
                                            return;
                                          }
                                          internal = true;
                                          try {
                                            p.setGameMode(GameMode.SPECTATOR);
                                            if (p.getGameMode() != GameMode.SPECTATOR)
                                              throw new IllegalStateException(
                                                  "Spectator change refused");
                                            plan.started = System.nanoTime();
                                            plan.running = true;
                                          } catch (Exception failure) {
                                            finish(plan, false);
                                          } finally {
                                            internal = false;
                                          }
                                        }));
                      }));
    } catch (Exception e) {
      p.sendMessage("Introduction unavailable: " + e.getMessage());
      if (plan.saved != null) finish(plan, false);
      else handoff(p);
    }
  }

  private void tick() {
    for (Plan p : new ArrayList<>(active.values())) {
      if (!p.running) continue;
      if (!p.player.isOnline()) {
        finish(p, false);
        continue;
      }
      double t = (System.nanoTime() - p.started) / (config.getDouble("duration-seconds") * 1e9);
      if (t >= 1) {
        finish(p, !p.preview);
        continue;
      }
      try {
        var point =
            CameraPath.sample(p.points, t, config.getString("curve"), config.getString("easing"));
        internal = true;
        boolean moved =
            p.player.teleport(
                new Location(
                    p.landing.getWorld(),
                    point.x(),
                    point.y(),
                    point.z(),
                    point.yaw(),
                    point.pitch()),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        internal = false;
        if (!moved) throw new IllegalStateException("Camera teleport denied");
        int scene = Math.min(3, (int) (t * 4));
        if (scene != p.scene) {
          p.scene = scene;
          var lines = config.getStringList("scenes");
          p.player.showTitle(
              Title.title(
                  Component.text(lines.size() > scene ? lines.get(scene) : "PolderSMP"),
                  Component.text(config.getString("subtitle", "/joinintro skip"))));
          if (config.getBoolean("sounds"))
            p.player.playSound(p.player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .7f, 1f);
        }
        if (config.getBoolean("particles") && p.scene == 2 && ++p.ticks % 5 == 0)
          p.player.spawnParticle(Particle.END_ROD, p.player.getLocation(), 2, .2, .2, .2);
      } catch (Exception e) {
        internal = false;
        getLogger().warning("Camera interrupted: " + e);
        finish(p, false);
      }
    }
  }

  private void finish(Plan plan, boolean seen) {
    if (plan.finishing) return;
    Player p = plan.player;
    plan.running = false;
    plan.finishing = true;
    boolean restored = false;
    try {
      Location dest = landing(plan.saved);
      internal = true;
      if (dest == null || p.isDead() || !p.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN))
        throw new IllegalStateException(
            "Safe landing unavailable or teleport refused; use /joinintro skip after repairing"
                + " spawn");
      if (p.getGameMode() == GameMode.SPECTATOR
          || p.getGameMode().name().equals(plan.saved.mode())) {
        p.setGameMode(GameMode.valueOf(plan.saved.mode()));
        if (p.getGameMode() != GameMode.valueOf(plan.saved.mode()))
          throw new IllegalStateException("Original game mode restoration refused");
        p.setAllowFlight(plan.saved.flight());
        p.setFlying(plan.saved.flight() && plan.saved.flying());
        p.setFlySpeed(plan.saved.speed());
      }
      p.setFallDistance(0);
      p.clearTitle();
      p.saveData();
      restored = true;
    } catch (Exception e) {
      getLogger().severe("Intro restoration retained for retry: " + e);
      if (p.isOnline())
        p.sendMessage(
            "Intro recovery pending. Ask an administrator to repair the safe spawn, then /joinintro"
                + " skip.");
    } finally {
      internal = false;
      for (Chunk chunk : plan.held) chunk.removePluginChunkTicket(this);
      plan.held.clear();
    }
    if (restored) {
      active.put(p.getUniqueId(), plan);
      if (store != null)
        store
            .finish(plan.saved, seen, plan.token)
            .whenComplete(
                (v, e) ->
                    main(
                        () -> {
                          plan.finishing = false;
                          if (e != null) {
                            getLogger().severe("Intro cleanup journal: " + e);
                            return;
                          }
                          if (active.remove(p.getUniqueId(), plan)) {
                            waiting.remove(p.getUniqueId());
                            if (p.isOnline()) handoff(p);
                          }
                        }));
    } else if (p.isOnline()) {
      plan.finishing = false;
      active.put(p.getUniqueId(), plan);
    } else {
      active.remove(p.getUniqueId());
      waiting.remove(p.getUniqueId());
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    waiting.remove(e.getPlayer().getUniqueId());
    skipped.remove(e.getPlayer().getUniqueId());
    recoveryErrors.remove(e.getPlayer().getUniqueId());
    Plan plan = active.get(e.getPlayer().getUniqueId());
    if (plan != null) finish(plan, false);
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent event) {
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              Plan plan = active.get(event.getPlayer().getUniqueId());
              if (plan != null) finish(plan, false);
            },
            2);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) {
    if (!internal && active.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    if (active.containsKey(e.getPlayer().getUniqueId()) && !internal && e.hasChangedPosition())
      e.setTo(e.getFrom());
  }

  public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
    if (!(s instanceof Player p)) {
      s.sendMessage("Player command");
      return true;
    }
    String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
    if (sub.equals("skip")) {
      skip(p.getUniqueId());
      return true;
    }
    if (!p.hasPermission("polder.intro.admin")) {
      p.sendMessage("/joinintro skip");
      return true;
    }
    try {
      if (!active.isEmpty() && !List.of("reload", "info").contains(sub))
        throw new IllegalArgumentException("Finish active intros before editing");
      switch (sub) {
        case "preview" -> start(p, true, "");
        case "setspawn" -> {
          if (!safe(p.getLocation()))
            throw new IllegalArgumentException("Stand at a safe landing point");
          config.set("spawn", point(p.getLocation()));
          config.save(new File(getDataFolder(), "config.yml"));
          p.sendMessage("Intro spawn saved.");
        }
        case "addpoint" -> {
          var list = new ArrayList<Map<?, ?>>(config.getMapList("points"));
          if (list.size() >= 64) throw new IllegalArgumentException("Max 64 points");
          list.add(point(p.getLocation()));
          config.set("points", list);
          config.save(new File(getDataFolder(), "config.yml"));
          p.sendMessage("Intro point saved: " + list.size());
        }
        case "remove" -> {
          int i = Integer.parseInt(a[1]) - 1;
          var list = new ArrayList<Map<?, ?>>(config.getMapList("points"));
          list.remove(i);
          config.set("points", list);
          config.save(new File(getDataFolder(), "config.yml"));
          p.sendMessage("Point removed.");
        }
        case "reload" -> {
          var candidate = read();
          config = candidate;
          for (Plan plan : new ArrayList<>(active.values())) finish(plan, false);
          p.sendMessage("Intro configuration reloaded.");
        }
        case "curve" -> {
          String value = a[1].toUpperCase(Locale.ROOT);
          if (!List.of("CATMULL_ROM", "LINEAR").contains(value))
            throw new IllegalArgumentException("CATMULL_ROM or LINEAR");
          config.set("curve", value);
          config.save(new File(getDataFolder(), "config.yml"));
        }
        case "easing" -> {
          String value = a[1].toUpperCase(Locale.ROOT);
          if (!List.of("SMOOTHSTEP", "LINEAR").contains(value))
            throw new IllegalArgumentException("SMOOTHSTEP or LINEAR");
          config.set("easing", value);
          config.save(new File(getDataFolder(), "config.yml"));
        }
        case "editor" -> editor(p, 0);
        default ->
            p.sendMessage(
                "/joinintro addpoint | setspawn | preview | skip | editor | remove <index> |"
                    + " reload");
      }
    } catch (Exception e) {
      p.sendMessage("Intro action refused: " + e.getMessage());
    }
    return true;
  }

  private Map<String, Object> point(Location l) {
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

  private static final class Editor implements org.bukkit.inventory.InventoryHolder {
    org.bukkit.inventory.Inventory inventory;
    int page;

    public org.bukkit.inventory.Inventory getInventory() {
      return inventory;
    }
  }

  private void editor(Player p, int page) {
    Editor holder = new Editor();
    holder.page = page;
    holder.inventory =
        Bukkit.createInventory(holder, 54, Component.text("Cinematic waypoint editor"));
    var list = config.getMapList("points");
    for (int j = 0; j < 45; j++) {
      int index = page * 45 + j;
      if (index >= list.size()) break;
      var item = new org.bukkit.inventory.ItemStack(Material.PAPER);
      var meta = item.getItemMeta();
      meta.displayName(Component.text("Waypoint " + (index + 1)));
      meta.lore(
          List.of(
              Component.text(list.get(index).toString()),
              Component.text("/joinintro remove " + (index + 1))));
      item.setItemMeta(meta);
      holder.inventory.setItem(j, item);
    }
    for (int slot : new int[] {45, 48, 49, 53}) {
      var item = new org.bukkit.inventory.ItemStack(Material.ARROW);
      var meta = item.getItemMeta();
      meta.displayName(
          Component.text(
              slot == 45
                  ? "Previous"
                  : slot == 53
                      ? "Next"
                      : slot == 48
                          ? "Curve: " + config.getString("curve")
                          : "Easing: " + config.getString("easing")));
      item.setItemMeta(meta);
      holder.inventory.setItem(slot, item);
    }
    p.openInventory(holder.inventory);
  }

  @EventHandler
  public void editor(org.bukkit.event.inventory.InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Editor holder)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || !p.hasPermission("polder.intro.admin")
        || isPlaying(p.getUniqueId())) return;
    int slot = e.getRawSlot();
    if (slot == 45) editor(p, Math.max(0, holder.page - 1));
    else if (slot == 53) editor(p, Math.min(1, holder.page + 1));
    else if (slot == 48 || slot == 49) {
      String key = slot == 48 ? "curve" : "easing";
      config.set(
          key,
          slot == 48
              ? (config.getString(key).equals("LINEAR") ? "CATMULL_ROM" : "LINEAR")
              : (config.getString(key).equals("LINEAR") ? "SMOOTHSTEP" : "LINEAR"));
      try {
        config.save(new File(getDataFolder(), "config.yml"));
        editor(p, holder.page);
      } catch (Exception ex) {
        p.sendMessage("Could not save editor changes: " + ex.getMessage());
      }
    }
  }

  @EventHandler
  public void editorDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Editor) e.setCancelled(true);
  }

  public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
    if (a.length != 1) return List.of();
    return (s.hasPermission("polder.intro.admin")
            ? List.of(
                "addpoint",
                "setspawn",
                "preview",
                "skip",
                "editor",
                "remove",
                "curve",
                "easing",
                "reload")
            : List.of("skip"))
        .stream().filter(v -> v.startsWith(a[0])).toList();
  }

  public void onDisable() {
    for (Player player : Bukkit.getOnlinePlayers())
      if (player.getOpenInventory().getTopInventory().getHolder() instanceof Editor)
        player.closeInventory();
    if (ticker != null) ticker.cancel();
    Set<UUID> affected = new HashSet<>(waiting);
    affected.addAll(active.keySet());
    for (Plan plan : new ArrayList<>(active.values())) finish(plan, false);
    active.clear();
    waiting.clear();
    for (UUID id : affected) {
      lobby("releaseActivity", new Class[] {UUID.class, String.class}, id, "CINEMATIC");
      Player player = Bukkit.getPlayer(id);
      if (player != null
          && player.getGameMode() != GameMode.SPECTATOR
          && safe(player.getLocation())) lobby("introFinished", new Class[] {UUID.class}, id);
    }
    closed = true;
    if (store != null) store.close();
  }
}
