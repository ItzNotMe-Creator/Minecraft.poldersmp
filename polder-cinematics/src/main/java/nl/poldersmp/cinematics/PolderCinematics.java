package nl.poldersmp.cinematics;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import nl.poldersmp.common.Settings;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class PolderCinematics extends JavaPlugin implements Listener, TabExecutor {
  private IntroStore store;
  private YamlConfiguration config, effects;
  private ShowSettings showSettings;
  private VisualAssets assets;
  private ArrivalRenderer renderer;
  private int frame;
  private boolean ready, internal, closed;
  private final Map<UUID, Plan> active = new HashMap<>();
  private final Set<UUID> waiting = new HashSet<>();
  private final Set<UUID> skipped = new HashSet<>(), recoveryErrors = new HashSet<>();
  private org.bukkit.scheduler.BukkitTask ticker;

  private static final class Plan {
    Player player;
    IntroStore.Saved saved;
    ItemDisplay camera;
    ArrivalRenderer.Landing arrival;
    ShowSettings show;
    boolean particles, sounds;
    WorldBorder previousBorder, visualBorder;
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
                "Cinematic recovery requires database.enabled=true; introductions remain"
                    + " disabled.");
        return;
      }
      if (!new File(getDataFolder(), "config.yml").exists()) saveResource("config.yml", false);
      config = read();
      effects = readEffects();
      showSettings = ShowSettings.load(effects);
      assets = new VisualAssets(this);
      renderer = new ArrivalRenderer(assets, showSettings);
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

  private YamlConfiguration readEffects() throws Exception {
    File file = new File(getDataFolder(), "effects.yml");
    if (!file.exists()) saveResource("effects.yml", false);
    var y = new YamlConfiguration();
    y.load(file);
    try (var input = getResource("effects.yml")) {
      if (input == null) throw new IOException("Missing effects defaults");
      y.setDefaults(
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)));
    }
    ShowSettings.load(y);
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
    if (active.size() >= showSettings.sessions()) {
      handoff(p);
      p.sendMessage("Introduction capacity reached; welcome to the lobby.");
      return;
    }
    Plan plan = new Plan();
    plan.player = p;
    plan.preview = preview;
    plan.token = token == null ? "" : token;
    plan.show = showSettings;
    plan.particles = config.getBoolean("particles");
    plan.sounds = config.getBoolean("sounds");
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
            || p.getWorldBorder() != null && !p.getWorldBorder().isInside(l)
            || l.getY() < l.getWorld().getMinHeight() + 1
            || l.getY() >= l.getWorld().getMaxHeight())
          throw new IllegalArgumentException("Camera path outside world/border");
        for (double dx : new double[] {-.3, .3})
          for (double dz : new double[] {-.3, .3}) {
            int cx = (int) Math.floor(l.getX() + dx) >> 4,
                cz = (int) Math.floor(l.getZ() + dz) >> 4;
            chunks.add((((long) cx) << 32) ^ (cz & 0xffffffffL));
          }
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
                                            validateClearPath(plan);
                                            if (plan.show.enabled() && plan.show.hideBorder()) {
                                              plan.previousBorder = p.getWorldBorder();
                                              plan.visualBorder = Bukkit.createWorldBorder();
                                              plan.visualBorder.setCenter(
                                                  plan.landing.getX(), plan.landing.getZ());
                                              plan.visualBorder.setSize(10000);
                                              plan.visualBorder.setWarningDistance(0);
                                              plan.visualBorder.setWarningTime(0);
                                              p.setWorldBorder(plan.visualBorder);
                                            }
                                            p.setGameMode(GameMode.SPECTATOR);
                                            if (p.getGameMode() != GameMode.SPECTATOR)
                                              throw new IllegalStateException(
                                                  "Spectator change refused");
                                            p.closeInventory();
                                            if (plan.show.enabled() && plan.show.camera()) {
                                              var first = plan.points.getFirst();
                                              Location eye =
                                                  new Location(
                                                      plan.landing.getWorld(),
                                                      first.x(),
                                                      first.y() + 1.62,
                                                      first.z(),
                                                      first.yaw(),
                                                      first.pitch());
                                              if (!p.teleport(
                                                  eye.clone().subtract(0, 1.62, 0),
                                                  PlayerTeleportEvent.TeleportCause.PLUGIN))
                                                throw new IllegalStateException(
                                                    "Initial camera teleport refused");
                                              plan.camera =
                                                  assets.create(
                                                      eye, ItemDisplay.class, p.getUniqueId());
                                              plan.camera.setTeleportDuration(
                                                  plan.show.interpolation());
                                              plan.camera.setViewRange(1);
                                              plan.camera.setShadowRadius(0);
                                              assets.show(p, plan.camera);
                                              p.setSpectatorTarget(plan.camera);
                                              if (p.getSpectatorTarget() != plan.camera)
                                                throw new IllegalStateException(
                                                    "Camera attachment refused");
                                            }
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
    frame++;
    renderer.frame(active.size());
    for (Plan plan : new ArrayList<>(active.values())) {
      if (plan.finishing) continue;
      if (plan.arrival != null) {
        try {
          if (!plan.player.isOnline()) {
            finish(plan, false);
            continue;
          }
          if (plan.arrival.tick()) finish(plan, !plan.preview);
        } catch (Exception e) {
          getLogger().warning("Landing interrupted: " + e);
          finish(plan, false);
        }
        continue;
      }
      if (!plan.running) continue;
      if (!plan.player.isOnline()) {
        finish(plan, false);
        continue;
      }
      double t = (System.nanoTime() - plan.started) / (config.getDouble("duration-seconds") * 1e9);
      if (t >= 1) {
        if (plan.show.enabled() && plan.show.landing()) land(plan);
        else finish(plan, !plan.preview);
        continue;
      }
      try {
        if (plan.player.getGameMode() != GameMode.SPECTATOR)
          throw new IllegalStateException("Camera mode changed");
        var point =
            CameraPath.sample(
                plan.points, t, config.getString("curve"), config.getString("easing"));
        internal = true;
        if (plan.camera != null) {
          if (!plan.camera.isValid() || plan.player.getSpectatorTarget() != plan.camera)
            throw new IllegalStateException("Camera detached");
          if (!plan.camera.teleport(
              new Location(
                  plan.landing.getWorld(),
                  point.x(),
                  point.y() + 1.62,
                  point.z(),
                  point.yaw(),
                  point.pitch()))) throw new IllegalStateException("Camera movement refused");
        } else if (!plan.player.teleport(
            new Location(
                plan.landing.getWorld(),
                point.x(),
                point.y(),
                point.z(),
                point.yaw(),
                point.pitch()),
            PlayerTeleportEvent.TeleportCause.PLUGIN))
          throw new IllegalStateException("Camera teleport refused");
        internal = false;
        int scene = Math.min(3, (int) (t * 4));
        if (scene != plan.scene) {
          plan.scene = scene;
          var lines = config.getStringList("scenes");
          plan.player.showTitle(
              Title.title(
                  ArrivalRenderer.text(
                      lines.size() > scene ? lines.get(scene) : "PolderSMP", plan.player),
                  ArrivalRenderer.text(
                      config.getString("subtitle", "/joinintro skip"), plan.player)));
          renderer.scene(plan.player, scene, plan.sounds);
        }
        renderer.flight(plan.player, point, t, frame, plan.particles);
      } catch (Exception e) {
        internal = false;
        getLogger().warning("Camera interrupted: " + e);
        finish(plan, false);
      }
    }
  }

  private void validateClearPath(Plan plan) {
    if (!plan.show.enabled() || !plan.show.clearPath()) return;
    int samples = Math.min(300, Math.max(40, config.getInt("duration-seconds") * 10));
    for (int i = 0; i <= samples; i++) {
      var point =
          CameraPath.sample(
              plan.points,
              i / (double) samples,
              config.getString("curve"),
              config.getString("easing"));
      for (double[] offset : new double[][] {{0, 0}, {.25, 0}, {-.25, 0}, {0, .25}, {0, -.25}}) {
        Location eye =
            new Location(
                plan.landing.getWorld(),
                point.x() + offset[0],
                point.y() + 1.62,
                point.z() + offset[1]);
        if (!eye.getWorld().isChunkLoaded(eye.getBlockX() >> 4, eye.getBlockZ() >> 4)
            || !eye.getBlock().isPassable())
          throw new IllegalArgumentException(
              "Camera route clips a building/tree near "
                  + eye.getBlockX()
                  + ", "
                  + eye.getBlockY()
                  + ", "
                  + eye.getBlockZ()
                  + ". Adjust the route in /joinintro editor or setup height in effects.yml");
      }
    }
  }

  private void restoreBorder(Plan plan) {
    if (plan.visualBorder == null) return;
    try {
      if (plan.player.getWorldBorder() == plan.visualBorder) {
        WorldBorder old = plan.previousBorder;
        if (old != null && old.getWorld() != null && !old.getWorld().equals(plan.player.getWorld()))
          old = null;
        plan.player.setWorldBorder(old);
      }
    } catch (RuntimeException e) {
      getLogger().warning("Cinematic border restoration: " + e);
    } finally {
      plan.visualBorder = null;
      plan.previousBorder = null;
    }
  }

  private void detach(Plan plan) {
    if (plan.camera != null) {
      if (plan.player.getGameMode() == GameMode.SPECTATOR
          && plan.player.getSpectatorTarget() == plan.camera) plan.player.setSpectatorTarget(null);
      assets.remove(plan.camera);
      plan.camera = null;
    }
  }

  private void restoreMode(Plan plan) {
    Player p = plan.player;
    if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode().name().equals(plan.saved.mode())) {
      p.setGameMode(GameMode.valueOf(plan.saved.mode()));
      if (p.getGameMode() != GameMode.valueOf(plan.saved.mode()))
        throw new IllegalStateException("Original game mode restoration refused");
      p.setAllowFlight(plan.saved.flight());
      p.setFlying(plan.saved.flight() && plan.saved.flying());
      p.setFlySpeed(plan.saved.speed());
    }
    p.setFallDistance(0);
  }

  private void land(Plan plan) {
    plan.running = false;
    try {
      internal = true;
      detach(plan);
      if (!safe(plan.landing)
          || !plan.player.teleport(plan.landing, PlayerTeleportEvent.TeleportCause.PLUGIN))
        throw new IllegalStateException("Safe landing refused");
      restoreMode(plan);
      plan.player.saveData();
      plan.arrival = renderer.land(plan.player, plan.landing, plan.particles, plan.sounds);
    } catch (Exception e) {
      getLogger().warning("Landing unavailable: " + e);
      finish(plan, false);
    } finally {
      internal = false;
    }
  }

  private void finish(Plan plan, boolean seen) {
    if (plan.finishing) return;
    Player p = plan.player;
    plan.running = false;
    plan.finishing = true;
    boolean restored = false;
    if (plan.arrival != null) {
      plan.arrival.close();
      plan.arrival = null;
    }
    try {
      Location dest = landing(plan.saved);
      internal = true;
      detach(plan);
      if (dest == null || p.isDead() || !p.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN))
        throw new IllegalStateException(
            "Safe landing unavailable or teleport refused; use /joinintro skip after repairing"
                + " spawn");
      restoreMode(plan);
      p.clearTitle();
      p.sendActionBar(Component.empty());
      p.saveData();
      restored = true;
    } catch (Exception e) {
      getLogger().severe("Intro restoration retained for retry: " + e);
      if (p.isOnline())
        p.sendMessage(
            "Intro recovery pending. Ask an administrator to repair the safe spawn, then /joinintro"
                + " skip.");
    } finally {
      restoreBorder(plan);
      internal = false;
      if (plan.camera != null) assets.remove(plan.camera);
      plan.camera = null;
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
  public void stopSpectating(PlayerStopSpectatingEntityEvent event) {
    Plan plan = active.get(event.getPlayer().getUniqueId());
    if (!internal && plan != null && event.getSpectatorTarget() == plan.camera)
      event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player p && active.containsKey(p.getUniqueId()))
      event.setCancelled(true);
    if (event instanceof EntityDamageByEntityEvent by
        && by.getDamager() instanceof Player p
        && active.containsKey(p.getUniqueId())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void controlledInteract(PlayerInteractEvent event) {
    if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void controlledDrop(PlayerDropItemEvent event) {
    if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
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
    if (config == null || showSettings == null) {
      p.sendMessage("Cinematics is disabled or configuration is unavailable.");
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
        case "preview" -> {
          if (!ready) throw new IllegalStateException("Introduction persistence is still loading");
          start(p, true, "");
        }
        case "setup" -> {
          for (int i = 1; i < a.length; i++)
            if (!Set.of("island", "replace").contains(a[i].toLowerCase(Locale.ROOT)))
              throw new IllegalArgumentException("/joinintro setup [island] [replace]");
          setup(
              p,
              Arrays.stream(a).anyMatch(v -> v.equalsIgnoreCase("replace")),
              Arrays.stream(a).anyMatch(v -> v.equalsIgnoreCase("island")));
        }
        case "studio" -> studio(p);
        case "border" -> {
          if (a.length != 2 || !Set.of("hide", "show").contains(a[1].toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("/joinintro border <hide|show>");
          var candidate = readEffects();
          candidate.set("flight.hide-world-border", a[1].equalsIgnoreCase("hide"));
          ShowSettings validated = ShowSettings.load(candidate);
          candidate.save(new File(getDataFolder(), "effects.yml"));
          effects = candidate;
          showSettings = validated;
          renderer = new ArrivalRenderer(assets, validated);
          p.sendMessage("Cinematic border visuals: " + a[1]);
        }
        case "route" -> {
          if (a.length != 2
              || !Set.of("ISLAND_AVENUE", "ORBITAL").contains(a[1].toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("ISLAND_AVENUE or ORBITAL");
          var candidate = readEffects();
          candidate.set("setup.profile", a[1].toUpperCase(Locale.ROOT));
          ShowSettings validated = ShowSettings.load(candidate);
          candidate.save(new File(getDataFolder(), "effects.yml"));
          effects = candidate;
          showSettings = validated;
          renderer = new ArrivalRenderer(assets, validated);
          p.sendMessage(
              "Route preset saved. Stand at landing, face your landmark, then /joinintro setup"
                  + " replace to regenerate with a backup.");
        }
        case "theme", "density" -> {
          if (a.length != 2) throw new IllegalArgumentException("/joinintro " + sub + " <value>");
          String value = a[1].toUpperCase(Locale.ROOT);
          if (!(sub.equals("theme") ? ShowSettings.THEMES : Set.of("LOW", "MEDIUM", "HIGH"))
              .contains(value)) throw new IllegalArgumentException("Unknown " + sub);
          var candidate = readEffects();
          candidate.set(sub, value);
          ShowSettings validated = ShowSettings.load(candidate);
          candidate.save(new File(getDataFolder(), "effects.yml"));
          effects = candidate;
          showSettings = validated;
          renderer = new ArrivalRenderer(assets, validated);
          p.sendMessage("Celestial Arrival " + sub + ": " + value);
        }
        case "mode" -> {
          if (a.length != 2
              || !Set.of("FIRST_JOIN", "EVERY_JOIN", "NETWORK_SESSION")
                  .contains(a[1].toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("FIRST_JOIN, EVERY_JOIN or NETWORK_SESSION");
          config.set("mode", a[1].toUpperCase(Locale.ROOT));
          config.save(new File(getDataFolder(), "config.yml"));
          p.sendMessage("Introduction mode: " + config.getString("mode"));
        }
        case "duration" -> {
          int seconds = Integer.parseInt(a[1]);
          if (seconds < 1 || seconds > 120)
            throw new IllegalArgumentException("Duration 1..120 seconds");
          config.set("duration-seconds", seconds);
          config.save(new File(getDataFolder(), "config.yml"));
          p.sendMessage(
              "Flythrough duration: "
                  + seconds
                  + " seconds; landing "
                  + showSettings.duration()
                  + " seconds");
        }
        case "info" -> {
          p.sendMessage(
              "Celestial Arrival • "
                  + showSettings.theme()
                  + " • "
                  + showSettings.density()
                  + " • camera "
                  + (showSettings.camera() ? "SMOOTH" : "DIRECT")
                  + " • active "
                  + active.size());
          p.sendMessage(
              "Stand at your landing point: /joinintro setup. Customize: /joinintro studio. Test:"
                  + " /joinintro preview.");
        }
        case "setspawn" -> {
          if (!safe(p.getLocation()))
            throw new IllegalArgumentException("Stand at a safe landing point");
          config.createSection("spawn", point(p.getLocation()));
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
          var effectCandidate = readEffects();
          var settingsCandidate = ShowSettings.load(effectCandidate);
          config = candidate;
          for (Plan plan : new ArrayList<>(active.values())) finish(plan, false);
          effects = effectCandidate;
          showSettings = settingsCandidate;
          renderer = new ArrivalRenderer(assets, showSettings);
          p.sendMessage("Intro configuration and Celestial Arrival effects reloaded.");
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
                "/joinintro setup [island] [replace] | studio | preview | skip | theme"
                    + " <CELESTIAL|INFERNO|AURORA> | density <LOW|MEDIUM|HIGH> | mode <mode> |"
                    + " duration <seconds> | editor | addpoint | setspawn | remove <index> |"
                    + " reload");
      }
    } catch (Exception e) {
      p.sendMessage("Intro action refused: " + e.getMessage());
    }
    return true;
  }

  private void setup(Player player, boolean replace, boolean island) throws Exception {
    Location origin = player.getLocation();
    if (island) {
      for (String key : List.of("x", "y", "z", "yaw", "pitch")) {
        String path = "setup.island-anchor." + key;
        double value = effects.getDouble(path);
        if ((!effects.isDouble(path) && !effects.isInt(path)) || !Double.isFinite(value))
          throw new IllegalArgumentException("Invalid island anchor: " + key);
      }
      origin =
          new Location(
              player.getWorld(),
              effects.getDouble("setup.island-anchor.x"),
              effects.getDouble("setup.island-anchor.y"),
              effects.getDouble("setup.island-anchor.z"),
              (float) effects.getDouble("setup.island-anchor.yaw"),
              (float) effects.getDouble("setup.island-anchor.pitch"));
    }
    if (!safe(origin))
      throw new IllegalArgumentException("Stand on safe solid ground at the landing point");
    if (!config.getMapList("points").isEmpty() && !replace)
      throw new IllegalArgumentException(
          "Your route is preserved. Use /joinintro setup replace to back it up and generate a new"
              + " route");
    var generated =
        ArrivalGeometry.route(
            new CameraPath.Point(
                origin.getX(), origin.getY(), origin.getZ(), origin.getYaw(), origin.getPitch()),
            showSettings.routeRadius(),
            showSettings.routeHeight(),
            showSettings.routeProfile());
    Set<Long> chunks = new HashSet<>();
    for (int i = 0; i <= 600; i++) {
      var sample =
          CameraPath.sample(
              generated, i / 600.0, config.getString("curve"), config.getString("easing"));
      Location l = new Location(origin.getWorld(), sample.x(), sample.y(), sample.z());
      if (!l.getWorld().getWorldBorder().isInside(l)
          || player.getWorldBorder() != null && !player.getWorldBorder().isInside(l)
          || l.getY() < l.getWorld().getMinHeight() + 1
          || l.getY() + 2 >= l.getWorld().getMaxHeight())
        throw new IllegalArgumentException(
            "Generated route exceeds the world/border. Reduce setup radius/height in effects.yml");
      chunks.add((((long) (l.getBlockX() >> 4)) << 32) ^ ((l.getBlockZ() >> 4) & 0xffffffffL));
    }
    if (chunks.size() > config.getInt("maximum-chunks"))
      throw new IllegalArgumentException("Generated route exceeds chunk budget");
    var candidate = new YamlConfiguration();
    candidate.loadFromString(config.saveToString());
    candidate.createSection("spawn", point(origin));
    List<Map<String, Object>> points = new ArrayList<>();
    for (var point : generated)
      points.add(
          point(
              new Location(
                  origin.getWorld(), point.x(), point.y(), point.z(), point.yaw(), point.pitch())));
    candidate.set("points", points);
    java.nio.file.Path file = getDataFolder().toPath().resolve("config.yml"),
        backups = getDataFolder().toPath().resolve("route-backups");
    java.nio.file.Files.createDirectories(backups);
    java.nio.file.Files.copy(
        file,
        backups.resolve("config-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".yml"));
    java.nio.file.Path temporary =
        java.nio.file.Files.createTempFile(getDataFolder().toPath(), "route-", ".yml");
    try {
      candidate.save(temporary.toFile());
      try {
        java.nio.file.Files.move(
            temporary,
            file,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        java.nio.file.Files.move(
            temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      java.nio.file.Files.deleteIfExists(temporary);
    }
    config = candidate;
    player.sendMessage(
        "Celestial route ready: 6 waypoints, safe landing saved, previous config backed up."
            + " /joinintro preview");
  }

  private static final class Studio implements org.bukkit.inventory.InventoryHolder {
    org.bukkit.inventory.Inventory inventory;
    final Map<Integer, String[]> commands = new HashMap<>();

    public org.bukkit.inventory.Inventory getInventory() {
      return inventory;
    }
  }

  private void studioButton(
      Studio studio,
      int slot,
      Material material,
      String name,
      List<String> lore,
      String... command) {
    var item = new org.bukkit.inventory.ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(
        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(name));
    meta.lore(
        lore.stream()
            .map(
                line ->
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(line))
            .toList());
    item.setItemMeta(meta);
    studio.inventory.setItem(slot, item);
    if (command.length > 0) studio.commands.put(slot, command);
  }

  private void studio(Player player) {
    Studio studio = new Studio();
    studio.inventory =
        Bukkit.createInventory(
            studio,
            54,
            ArrivalRenderer.text(
                "<gradient:#39efbe:#ffe5a0><bold>CELESTIAL STUDIO</bold></gradient>", player));
    for (int i = 0; i < 54; i++)
      if (i < 9 || i > 44 || i % 9 == 0 || i % 9 == 8)
        studioButton(studio, i, Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    studioButton(
        studio,
        20,
        Material.TOTEM_OF_UNDYING,
        "<green><bold>CELESTIAL</bold></green>",
        List.of(
            "<gray>Emerald energy • golden totems</gray>",
            "<gray>Glittering constellations</gray>"),
        "theme",
        "CELESTIAL");
    studioButton(
        studio,
        22,
        Material.BLAZE_POWDER,
        "<gold><bold>INFERNO</bold></gold>",
        List.of("<gray>Ember gold • crimson energy</gray>", "<gray>A blazing arrival</gray>"),
        "theme",
        "INFERNO");
    studioButton(
        studio,
        24,
        Material.AMETHYST_SHARD,
        "<light_purple><bold>AURORA</bold></light_purple>",
        List.of(
            "<gray>Violet crystals • glacial light</gray>",
            "<gray>Echoes from the night sky</gray>"),
        "theme",
        "AURORA");
    studioButton(
        studio,
        13,
        Material.NETHER_STAR,
        "<aqua>Your cinematic</aqua>",
        List.of(
            "<gray>Theme: " + showSettings.theme() + "</gray>",
            "<gray>Particles: " + showSettings.density() + "</gray>",
            "<gray>Flythrough: "
                + config.getInt("duration-seconds")
                + "s • landing: "
                + showSettings.duration()
                + "s</gray>"));
    studioButton(
        studio,
        30,
        Material.ENDER_EYE,
        "<yellow>Preview the full sequence</yellow>",
        List.of("<gray>Stand on safe ground first</gray>"),
        "preview");
    studioButton(
        studio,
        32,
        Material.FIREWORK_STAR,
        "<aqua>Particle density: " + showSettings.density() + "</aqua>",
        List.of("<gray>Click to cycle LOW / MEDIUM / HIGH</gray>"),
        "density",
        showSettings.density().equals("LOW")
            ? "MEDIUM"
            : showSettings.density().equals("MEDIUM") ? "HIGH" : "LOW");
    studioButton(
        studio,
        40,
        Material.COMPASS,
        "<green>Generate a route here</green>",
        List.of(
            "<gray>Stand at the safe landing point</gray>",
            "<gray>Existing routes are preserved</gray>"),
        "setup");
    studioButton(
        studio,
        45,
        Material.PAPER,
        "<white>Waypoint editor</white>",
        List.of("<gray>Inspect and fine-tune your path</gray>"),
        "editor");
    studioButton(studio, 49, Material.BARRIER, "<red>Close</red>", List.of());
    player.openInventory(studio.inventory);
  }

  @EventHandler
  public void studioClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof Studio studio)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)
        || !player.hasPermission("polder.intro.admin")
        || event.getClickedInventory() != studio.inventory) return;
    if (event.getRawSlot() == 49) {
      player.closeInventory();
      return;
    }
    String[] command = studio.commands.get(event.getRawSlot());
    if (command == null) return;
    player.closeInventory();
    onCommand(player, getCommand("joinintro"), "joinintro", command);
    if (!command[0].equals("preview") && !command[0].equals("editor")) studio(player);
  }

  @EventHandler
  public void studioDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Studio) event.setCancelled(true);
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

  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] args) {
    List<String> values = List.of();
    if (args.length == 1)
      values =
          sender.hasPermission("polder.intro.admin")
              ? List.of(
                  "setup",
                  "studio",
                  "preview",
                  "skip",
                  "theme",
                  "density",
                  "mode",
                  "duration",
                  "addpoint",
                  "setspawn",
                  "editor",
                  "remove",
                  "curve",
                  "easing",
                  "reload",
                  "info")
              : List.of("skip");
    else if (args.length == 2 && sender.hasPermission("polder.intro.admin"))
      values =
          switch (args[0].toLowerCase(Locale.ROOT)) {
            case "theme" -> List.of("CELESTIAL", "INFERNO", "AURORA");
            case "border" -> List.of("hide", "show");
            case "route" -> List.of("ISLAND_AVENUE", "ORBITAL");
            case "density" -> List.of("LOW", "MEDIUM", "HIGH");
            case "mode" -> List.of("FIRST_JOIN", "EVERY_JOIN", "NETWORK_SESSION");
            case "setup" -> List.of("island", "replace");
            case "curve" -> List.of("CATMULL_ROM", "LINEAR");
            case "easing" -> List.of("SMOOTHSTEP", "LINEAR");
            default -> List.of();
          };
    String filter = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(filter)).toList();
  }

  public void onDisable() {
    for (Player player : Bukkit.getOnlinePlayers())
      if (player.getOpenInventory().getTopInventory().getHolder() instanceof Editor
          || player.getOpenInventory().getTopInventory().getHolder() instanceof Studio)
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
    if (assets != null) assets.cleanup();
    if (store != null) store.close();
  }
}
