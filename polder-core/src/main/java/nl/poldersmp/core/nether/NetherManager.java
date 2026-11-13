package nl.poldersmp.core.nether;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns a separate Paper Nether; never unloads, deletes or rewrites a live world. */
public final class NetherManager implements Listener, TabExecutor, AutoCloseable {
  private final JavaPlugin plugin;
  private final Path data, root;
  private final YamlConfiguration config;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "polder-nether-maintenance"));
  private final ResetStore store;
  private volatile ResetStore.State state;
  private volatile boolean closed;
  private boolean busy, access, shutdownRequested;
  private World nether;
  private volatile Location safe;
  private FileChannel lease;
  private org.bukkit.scheduler.BukkitTask ticker;
  private long remaining = Long.MAX_VALUE;

  public NetherManager(JavaPlugin plugin) throws Exception {
    this.plugin = plugin;
    this.data = plugin.getDataFolder().toPath().toAbsolutePath();
    if (!Files.exists(data.resolve("nether.yml"))) plugin.saveResource("nether.yml", false);
    config = YamlConfiguration.loadConfiguration(data.resolve("nether.yml").toFile());
    root = Bukkit.getWorldContainer().toPath().toRealPath();
    store = new ResetStore(data.resolve("nether-reset.db"));
    for (String cmd : List.of("nether", "netheradmin")) {
      plugin.getCommand(cmd).setExecutor(this);
      plugin.getCommand(cmd).setTabCompleter(this);
    }
    if (!config.getBoolean("enabled")) {
      plugin.getLogger().info("Nether reset module disabled; primary dimensions unchanged");
      return;
    }
    NetherRules.interval(config.getLong("interval-hours"));
    if (config.getInt("backup-retention") < 1 || config.getInt("backup-retention") > 100)
      throw new IllegalArgumentException("backup-retention outside 1..100");
    if (config.getInt("verification-timeout-seconds") < 30
        || config.getInt("verification-timeout-seconds") > 600)
      throw new IllegalArgumentException("verification timeout outside 30..600");
    if (config.getIntegerList("warnings-seconds").stream().anyMatch(n -> n < 1 || n > 86400))
      throw new IllegalArgumentException("Invalid warning threshold");
    if (config.getInt("portal-search-radius") < 0
        || config.getInt("portal-search-radius") > 128
        || config.getInt("portal-create-radius") < 0
        || config.getInt("portal-create-radius") > 16)
      throw new IllegalArgumentException("Unbounded portal radius");
    // Also interlocks manual starts against an offline archive/copy in progress.
    NetherFiles.safe(root, data.resolve("maintenance.lock"));
    lease =
        FileChannel.open(
            data.resolve("maintenance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    if (lease.tryLock() == null) {
      lease.close();
      throw new IOException("Offline Nether maintenance owns the world; stop this SMP");
    }
    Bukkit.getPluginManager().registerEvents(this, plugin);
    run(
        () -> {
          store.migrate();
          var s = store.read();
          if (s == null) {
            String name = config.getString("world-name");
            if (name == null || !name.matches("polder_nether(?:_[a-zA-Z0-9_-]+)?"))
              throw new IOException("Dedicated polder_nether world name required");
            if (Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS))
              throw new IOException("Refusing adoption of an existing world");
            s =
                store.initialize(
                    root,
                    name,
                    new SecureRandom().nextLong(),
                    System.currentTimeMillis(),
                    NetherRules.interval(config.getLong("interval-hours")),
                    config.getInt("backup-retention"));
          }
          if (!s.get("name").equals(config.getString("world-name"))
              || !NetherFiles.root(s).equals(root))
            throw new IOException("Registered world/root changed");
          NetherFiles.target(s, false);
          s =
              store.change(
                  s,
                  Map.of(
                      "interval",
                      Long.toString(NetherRules.interval(config.getLong("interval-hours"))),
                      "retention",
                      Integer.toString(config.getInt("backup-retention"))));
          return s;
        },
        s -> {
          state = s;
          prepareSafe(() -> boot());
        });
    ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
  }

  private <T> void run(Callable<T> job, Consumer<T> success) {
    CompletableFuture.supplyAsync(
            () -> {
              try {
                return job.call();
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            },
            worker)
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      if (error != null) {
                        access = false;
                        plugin.getLogger().severe("Nether maintenance blocked: " + error);
                        busy = false;
                      } else success.accept(v);
                    }));
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

  private World overworld() {
    World w = Bukkit.getWorld(config.getString("safe-location.world", "world"));
    if (w == null || w.getEnvironment() != World.Environment.NORMAL)
      throw new IllegalStateException("Safe Overworld unavailable");
    return w;
  }

  private void prepareSafe(Runnable ready) {
    try {
      World w = overworld();
      run(
          () ->
              NetherFiles.properties(root.resolve("server.properties"))
                  .getProperty("level-name", "world"),
          primary -> {
            if (!w.getName().equals(primary)) {
              blocked(new IOException("Evacuation world must be primary Overworld"));
              return;
            }
            Location candidate =
                config.getBoolean("safe-location.use-world-spawn", true)
                    ? w.getSpawnLocation().add(.5, 0, .5)
                    : new Location(
                        w,
                        config.getDouble("safe-location.x"),
                        config.getDouble("safe-location.y"),
                        config.getDouble("safe-location.z"));
            w.getChunkAtAsync(candidate)
                .whenComplete(
                    (chunk, e) ->
                        main(
                            () -> {
                              if (e != null || !safe(candidate)) {
                                blocked(
                                    new IOException(
                                        "Configured safe Overworld location is"
                                            + " obstructed/hazardous"));
                                return;
                              }
                              safe = candidate;
                              ready.run();
                            }));
          });
    } catch (Exception e) {
      blocked(e);
    }
  }

  static boolean safe(Location l) {
    if (l == null
        || l.getWorld().getEnvironment() != World.Environment.NORMAL
        || !l.getWorld().getWorldBorder().isInside(l)
        || l.getY() <= l.getWorld().getMinHeight()
        || l.getY() + 1 >= l.getWorld().getMaxHeight()) return false;
    var floor = l.clone().add(0, -1, 0).getBlock();
    return floor.getType().isSolid()
        && !Set.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
            .contains(floor.getType())
        && l.getBlock().getType().isAir()
        && l.clone().add(0, 1, 0).getBlock().getType().isAir();
  }

  private void blocked(Exception e) {
    access = false;
    busy = false;
    plugin.getLogger().severe("Nether blocked: " + e);
    if (state != null && !Set.of("FAILED", "IDLE").contains(state.phase()))
      run(
          () -> {
            var s = store.read();
            store.failure(s, e);
            return store.read();
          },
          s -> state = s);
  }

  private void boot() {
    if (state == null) return;
    if (!Set.of("IDLE", "GENERATING", "VERIFYING").contains(state.phase())) {
      access = false;
      evacuate(
          () ->
              plugin
                  .getLogger()
                  .warning("Nether awaits offline maintenance/recovery: " + state.phase()));
      return;
    }
    busy = true;
    run(
        () -> {
          var s = store.read();
          Path target = NetherFiles.target(s, false);
          boolean fresh =
              s.phase().equals("GENERATING")
                  || s.phase().equals("IDLE") && s.get("fingerprint").isEmpty();
          if (!Files.exists(target)) {
            if (!fresh)
              throw new IOException(
                  "Previously verified Nether is missing; recover, do not regenerate silently");
            Files.createDirectory(target);
            NetherFiles.mark(target, s);
          } else {
            var marker = NetherFiles.properties(target.resolve(NetherFiles.MARKER));
            if (!s.get("identity").equals(marker.getProperty("identity")))
              throw new IOException("Unowned target");
            if (!fresh) NetherFiles.target(s, true);
          }
          return s;
        },
        s -> {
          state = s;
          try {
            long seed = s.phase().equals("IDLE") ? s.number("seed") : s.number("new-seed");
            nether =
                new WorldCreator(s.get("name"))
                    .environment(World.Environment.NETHER)
                    .seed(seed)
                    .generateStructures(true)
                    .createWorld();
            if (nether == null
                || nether.getEnvironment() != World.Environment.NETHER
                || nether.getSeed() != seed
                || !nether
                    .getWorldFolder()
                    .toPath()
                    .toRealPath()
                    .equals(root.resolve(s.get("name"))))
              throw new IOException("Effective Nether world/seed mismatch");
            nether.save();
            if (s.phase().equals("GENERATING"))
              run(
                  () -> store.change(store.read(), Map.of("phase", "VERIFYING")),
                  v -> {
                    state = v;
                    verify(seed);
                  });
            else verify(seed);
          } catch (Exception e) {
            blocked(e);
          }
        });
  }

  private void verify(long seed) {
    boolean completingReset = state.phase().equals("VERIFYING");
    long deadline =
        System.currentTimeMillis() + 1000L * config.getInt("verification-timeout-seconds");
    List<String> samples = new ArrayList<>();
    sample(
        0,
        samples,
        deadline,
        () -> {
          String fingerprint;
          try {
            fingerprint =
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(
                                String.join("|", samples)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          } catch (Exception e) {
            blocked(e);
            return;
          }
          run(
              () -> {
                var s = store.read();
                Path p = NetherFiles.target(s, true);
                if (NbtSeed.read(p.resolve("level.dat")) != seed)
                  throw new IOException("Saved level.dat seed differs from effective API seed");
                if (s.phase().equals("VERIFYING")) {
                  if (!s.flag("rollback")
                      && (seed == s.number("old-seed") || fingerprint.equals(s.get("fingerprint"))))
                    throw new IOException("New generation did not change seed/terrain fingerprint");
                  s = store.complete(s, System.currentTimeMillis(), fingerprint);
                } else if (s.get("fingerprint").isEmpty())
                  s = store.change(s, Map.of("fingerprint", fingerprint));
                String token = System.getenv("POLDER_LAUNCH_TOKEN");
                if (token != null) {
                  Properties ready = new Properties();
                  ready.setProperty("token", token);
                  ready.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
                  ready.setProperty(
                      "start",
                      ProcessHandle.current().info().startInstant().orElseThrow().toString());
                  NetherFiles.atomic(data.resolve("launcher-ready.properties"), ready);
                }
                return s;
              },
              s -> {
                state = s;
                access = true;
                busy = false;
                remaining = Math.max(0, (s.number("next") - System.currentTimeMillis()) / 1000);
                plugin
                    .getLogger()
                    .info("Managed Nether verified: seed=" + seed + " fingerprint=" + fingerprint);
                if (completingReset)
                  Bukkit.broadcastMessage(
                      s.flag("paused")
                          ? "Nether recovery verified; reset schedule paused."
                          : "Nether regeneration verified; access is open.");
              });
        });
  }

  private void sample(int index, List<String> samples, long deadline, Runnable done) {
    if (System.currentTimeMillis() > deadline) {
      blocked(new IOException("Nether verification timed out"));
      return;
    }
    if (index == 3) {
      nether.save();
      done.run();
      return;
    }
    int cx = index * 3, cz = index * 5;
    nether
        .getChunkAtAsync(cx, cz, true)
        .whenComplete(
            (chunk, error) ->
                main(
                    () -> {
                      if (error != null) {
                        blocked(new IOException("Verification chunk generation failed", error));
                        return;
                      }
                      for (int x = 0; x < 16; x += 4)
                        for (int z = 0; z < 16; z += 4)
                          for (int y = 8; y < 120; y += 8) {
                            var b = chunk.getBlock(x, y, z);
                            samples.add(
                                b.getBlockData().getAsString() + ":" + b.getBiome().getKey());
                          }
                      sample(index + 1, samples, deadline, done);
                    }));
  }

  private void tick() {
    if (state == null || busy) return;
    if (state.phase().equals("WAITING_STOP")) {
      if (config.getBoolean("automatic-shutdown") && !shutdownRequested) {
        busy = true;
        run(
            () -> NetherMaintenance.liveProof(data),
            ok -> {
              busy = false;
              if (ok) {
                shutdownRequested = true;
                Bukkit.broadcastMessage("Nether maintenance: controlled SMP shutdown.");
                Bukkit.shutdown();
              }
            });
      }
      return;
    }
    if (!state.phase().equals("IDLE") || state.flag("paused") || !access) return;
    long nextRemaining =
        Math.max(0, (state.number("next") - System.currentTimeMillis() + 999) / 1000);
    Map<String, String> warnings = new HashMap<>();
    if (nextRemaining > 0)
      for (int threshold :
          NetherRules.warnings(
              remaining, nextRemaining, config.getIntegerList("warnings-seconds"))) {
        String key = "warning-" + threshold;
        if (!state.get(key).equals(state.get("next"))) warnings.put(key, state.get("next"));
      }
    if (!warnings.isEmpty()) {
      busy = true;
      run(
          () -> store.change(store.read(), warnings),
          s -> {
            state = s;
            busy = false;
            Bukkit.broadcastMessage(
                "Nether resets in " + nextRemaining + " seconds. Return to the Overworld.");
          });
      remaining = nextRemaining;
      return;
    }
    remaining = nextRemaining;
    if (nextRemaining == 0) request(null);
  }

  private void request(CommandSender sender) {
    if (state == null || busy || !access || !state.open()) {
      if (sender != null) sender.sendMessage("Nether paused, unavailable or reset already active.");
      return;
    }
    busy = true;
    access = false;
    run(
        () ->
            store.request(
                System.currentTimeMillis(),
                ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElseThrow().toString()),
        s -> {
          state = s;
          Bukkit.broadcastMessage(
              "Nether reset scheduled now. Access disabled; evacuating players.");
          evacuate(
              () -> {
                try {
                  Bukkit.savePlayers();
                  for (World w : Bukkit.getWorlds())
                    if (w.getEnvironment() == World.Environment.NETHER) w.save();
                  run(
                      () -> store.change(store.read(), Map.of("phase", "WAITING_STOP")),
                      v -> {
                        state = v;
                        busy = false;
                        String msg =
                            "Nether saved. Stop SMP normally, then use the approved maintenance"
                                + " launcher. Operation "
                                + v.get("operation");
                        plugin.getLogger().warning(msg);
                        if (sender != null) sender.sendMessage(msg);
                      });
                } catch (Exception e) {
                  blocked(e);
                }
              });
        });
  }

  private void evacuate(Runnable done) {
    if (!safe(safe)) {
      blocked(new IOException("Overworld evacuation destination became unsafe"));
      return;
    }
    NetherEvacuation.move(Bukkit.getOnlinePlayers(), safe)
        .whenComplete(
            (v, e) ->
                main(
                    () -> {
                      if (e != null
                          || !Boolean.TRUE.equals(v)
                          || Bukkit.getOnlinePlayers().stream()
                              .anyMatch(
                                  p -> p.getWorld().getEnvironment() == World.Environment.NETHER)) {
                        blocked(new IOException("Evacuation failed/cancelled; shutdown refused"));
                        return;
                      }
                      done.run();
                    }));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) {
    if (e instanceof PlayerPortalEvent) return;
    if (e.getTo() != null
        && e.getTo().getWorld().getEnvironment() == World.Environment.NETHER
        && (!access || !e.getTo().getWorld().equals(nether))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void portal(PlayerPortalEvent e) {
    if (e.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
    if (!access || nether == null || safe == null) {
      e.setCancelled(true);
      return;
    }
    World from = e.getFrom().getWorld();
    if (from.getEnvironment() == World.Environment.NORMAL) {
      if (!from.equals(safe.getWorld())) {
        e.setCancelled(true);
        return;
      }
      e.setTo(destination(e.getFrom(), nether, 1.0 / 8));
    } else if (from.getEnvironment() == World.Environment.NETHER)
      e.setTo(destination(e.getFrom(), safe.getWorld(), 8));
    else {
      e.setCancelled(true);
      return;
    }
    e.setSearchRadius(config.getInt("portal-search-radius"));
    e.setCreationRadius(config.getInt("portal-create-radius"));
    e.setCanCreatePortal(true);
  }

  private Location destination(Location from, World to, double scale) {
    var border = to.getWorldBorder();
    double half = border.getSize() / 2 - 16;
    var center = border.getCenter();
    double x = Math.max(center.getX() - half, Math.min(center.getX() + half, from.getX() * scale));
    double z = Math.max(center.getZ() - half, Math.min(center.getZ() + half, from.getZ() * scale));
    return new Location(
        to,
        x,
        Math.max(to.getMinHeight() + 8, Math.min(to.getMaxHeight() - 16, from.getY())),
        z,
        from.getYaw(),
        from.getPitch());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityPortal(EntityPortalEvent e) {
    if (e.getEntity() instanceof Player) return;
    if (e.getTo() == null) return;
    World from = e.getFrom().getWorld(), to = e.getTo().getWorld();
    if (from.getEnvironment() == World.Environment.THE_END
        || to.getEnvironment() == World.Environment.THE_END) return;
    if (e.getPortalType() != PortalType.NETHER) return;
    if (!access || nether == null || safe == null) {
      e.setCancelled(true);
      return;
    }
    if (from.getEnvironment() == World.Environment.NORMAL && from.equals(safe.getWorld()))
      e.setTo(destination(e.getFrom(), nether, 1.0 / 8));
    else if (from.getEnvironment() == World.Environment.NETHER)
      e.setTo(destination(e.getFrom(), safe.getWorld(), 8));
    else {
      e.setCancelled(true);
      return;
    }
    e.setSearchRadius(config.getInt("portal-search-radius"));
    e.setCreationRadius(config.getInt("portal-create-radius"));
    e.setCanCreatePortal(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void login(AsyncPlayerPreLoginEvent e) {
    if (safe == null || state == null)
      e.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          "Nether safety initialization is not ready; retry shortly.");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void spawn(AsyncPlayerSpawnLocationEvent e) {
    // This event is asynchronous. Use only immutable world identity and the cached
    // destination; all chunk/block safety checks ran earlier on the server thread.
    Location destination = safe;
    if (destination != null
        && e.getSpawnLocation().getWorld().getEnvironment() == World.Environment.NETHER)
      e.setSpawnLocation(destination.clone());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void respawn(PlayerRespawnEvent e) {
    if (e.getRespawnLocation().getWorld().getEnvironment() == World.Environment.NETHER
        && (!access || !e.getRespawnLocation().getWorld().equals(nether))
        && safe(safe)) e.setRespawnLocation(safe.clone());
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    if (e.getPlayer().getWorld().getEnvironment() == World.Environment.NETHER
        && (!access || !e.getPlayer().getWorld().equals(nether))
        && safe(safe)) e.getPlayer().teleportAsync(safe.clone());
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    boolean admin = cmd.getName().equals("netheradmin");
    if (!sender.hasPermission(admin ? "polder.nether.admin" : "polder.nether.use")) {
      sender.sendMessage("No permission.");
      return true;
    }
    if (!config.getBoolean("enabled")) {
      sender.sendMessage("Managed Nether disabled in nether.yml; see maintenance/README.md.");
      return true;
    }
    if (state == null) {
      sender.sendMessage(
          "Nether persistence/safe destination initializing or blocked; inspect server log.");
      return true;
    }
    String sub = args.length == 0 ? (admin ? "help" : "info") : args[0].toLowerCase(Locale.ROOT);
    if (!admin) {
      if (!Set.of("info", "next").contains(sub)) {
        sender.sendMessage("/nether [next|info]");
        return true;
      }
      sender.sendMessage(
          "Nether "
              + state.get("name")
              + " | phase="
              + state.phase()
              + " | access="
              + access
              + " | paused="
              + state.flag("paused"));
      sender.sendMessage(
          "Last success: "
              + (state.number("last") == 0 ? "none" : Instant.ofEpochMilli(state.number("last")))
              + " | next: "
              + Instant.ofEpochMilli(state.number("next")));
      if (sub.equals("info")) {
        sender.sendMessage(
            "Seed="
                + state.get("seed")
                + " | operation="
                + state.get("operation")
                + " | error="
                + state.get("error"));
        run(store::history, rows -> rows.forEach(sender::sendMessage));
      }
    } else if (sub.equals("reset")) request(sender);
    else if (Set.of("pause", "resume").contains(sub)) {
      if (busy || !state.phase().equals("IDLE")) {
        sender.sendMessage("Finish/recover current maintenance first.");
        return true;
      }
      busy = true;
      run(
          () -> store.change(store.read(), Map.of("paused", Boolean.toString(sub.equals("pause")))),
          s -> {
            state = s;
            busy = false;
            remaining = Long.MAX_VALUE;
            sender.sendMessage(
                "Nether schedule "
                    + (s.flag("paused") ? "paused" : "resumed")
                    + "; persisted deadline retained.");
          });
    } else sender.sendMessage("/netheradmin reset | pause | resume");
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
    boolean admin = c.getName().equals("netheradmin");
    if (args.length != 1 || !s.hasPermission(admin ? "polder.nether.admin" : "polder.nether.use"))
      return List.of();
    return (admin ? List.of("reset", "pause", "resume") : List.of("next", "info"))
        .stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
  }

  @Override
  public void close() {
    closed = true;
    access = false;
    if (ticker != null) ticker.cancel();
    worker.shutdown();
    if (lease != null)
      try {
        lease.close();
      } catch (IOException e) {
        plugin.getLogger().severe("Nether lease close: " + e);
      }
  }
}
