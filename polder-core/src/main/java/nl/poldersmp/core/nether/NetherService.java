package nl.poldersmp.core.nether;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import nl.poldersmp.common.nether.Countdown;
import nl.poldersmp.common.nether.NetherSchedule;
import nl.poldersmp.common.nether.NetherSeed;
import nl.poldersmp.common.nether.NetherSettings;
import nl.poldersmp.common.nether.PostResetVerifier;
import nl.poldersmp.common.nether.ResetPhase;
import nl.poldersmp.common.nether.ResetState;
import nl.poldersmp.common.nether.ResetStateStore;
import nl.poldersmp.common.nether.SafeSpotFinder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The online half of the automatic Nether reset. It runs on the server thread, one step per second,
 * and persists every phase change to the shared state file before acting on it.
 *
 * <p>It never deletes or moves any world folder. Archiving the old Nether happens only in the offline
 * maintenance tool, while the server is stopped. After a restart this service creates the resource
 * Nether with the new seed and refuses to report success unless the world really has that seed.
 */
public final class NetherService {
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
  private static final long EVACUATION_TIMEOUT_SECONDS = 30;
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final JavaPlugin plugin;
  private final Clock clock = Clock.systemUTC();
  private final ResetStateStore store;
  private NetherSettings settings;
  private ResetState state = new ResetState();
  private boolean stateBroken;
  private BukkitTask task;
  private Set<Long> fired = new HashSet<>();
  private Instant evacuationStarted;
  private boolean started;

  public NetherService(JavaPlugin plugin) {
    this.plugin = plugin;
    this.store = new ResetStateStore(plugin.getDataFolder().toPath().resolve("nether-state.properties"));
  }

  // ------------------------------------------------------------------ lifecycle

  /** @return false when the feature stays off (bad configuration). Never throws. */
  public boolean start() {
    try {
      File file = new File(plugin.getDataFolder(), "nether.yml");
      if (!file.exists()) {
        plugin.saveResource("nether.yml", false);
      }
      YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
      Map<String, Object> flat = new HashMap<>();
      for (Map.Entry<String, Object> e : yaml.getValues(true).entrySet()) {
        if (!(e.getValue() instanceof ConfigurationSection)) {
          flat.put(e.getKey(), e.getValue());
        }
      }
      settings = NetherSettings.fromMap(flat);
    } catch (RuntimeException e) {
      plugin.getLogger().severe("nether.yml is invalid, the Nether reset feature stays OFF: " + e.getMessage());
      return false;
    }
    if (!settings.enabled()) {
      plugin.getLogger().info("Nether reset is disabled in nether.yml");
      return false;
    }
    try {
      state = store.load();
    } catch (IOException e) {
      stateBroken = true;
      plugin.getLogger().severe("Nether state file problem: " + e.getMessage() + ". The Nether stays CLOSED until an admin fixes or removes plugins/PolderCore/nether-state.properties.");
    }
    started = true;
    // World creation must not happen inside the plugin enable call.
    Bukkit.getScheduler().runTask(plugin, this::initialise);
    return true;
  }

  public void stop() {
    if (task != null) {
      task.cancel();
    }
    if (started && !stateBroken) {
      save();
    }
  }

  private void initialise() {
    if (!stateBroken) {
      exportSettings();
      Instant now = Instant.now(clock);
      switch (state.phase()) {
        case IDLE, COUNTDOWN -> {
          ensureWorld(state.currentSeed() != null ? state.currentSeed() : NetherSeed.next(state.usedSeeds()));
          adoptObservedSeed();
          catchUpSchedule(now);
          fired = Countdown.initiallyFired(NetherSchedule.remainingSeconds(now, state.nextReset()), settings.milestonesSeconds());
        }
        case LOCKED, EVACUATED, SAVED, AWAITING_MAINTENANCE -> {
          plugin.getLogger().warning("Server restarted during a Nether reset (phase " + state.phase() + "). Continuing.");
          ensureWorld(state.currentSeed() != null ? state.currentSeed() : NetherSeed.next(state.usedSeeds()));
        }
        case PREPARED, VERIFYING -> finishReset();
        case MAINTENANCE_RUNNING, FAILED, RECOVERING ->
            plugin.getLogger().severe("An earlier Nether reset (operation " + state.operationId() + ") did not finish (phase " + state.phase() + "). The Nether stays closed. Run the maintenance tool: recover");
      }
      save();
    }
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
  }

  /** Writes what the offline tool needs to know into the shared state file. */
  private void exportSettings() {
    state.worldKey(settings.worldKey());
    state.backupDirectory(settings.backupDirectory());
    state.retentionKeep(settings.retentionKeep());
    state.launcherPath(settings.launcherPath());
    state.launcherVerified(settings.launcherVerified());
    refreshProtectedPaths();
  }

  private void refreshProtectedPaths() {
    World resource = resourceNether();
    Path resourcePath = resource == null ? null : resource.getWorldPath().toAbsolutePath().normalize();
    List<String> protectedPaths = new ArrayList<>();
    for (World w : Bukkit.getWorlds()) {
      if (resource != null && w.getUID().equals(resource.getUID())) {
        continue;
      }
      Path p = w.getWorldPath().toAbsolutePath().normalize();
      // A folder that contains the resource Nether cannot be listed as protected (the tool would
      // refuse the Nether itself). The tool still has built-in refusals for main world folders.
      if (resourcePath != null && resourcePath.startsWith(p)) {
        continue;
      }
      protectedPaths.add(p.toString());
    }
    state.protectedPaths(protectedPaths);
    if (resourcePath != null) {
      state.worldPath(resourcePath.toString());
    }
  }

  // ------------------------------------------------------------------ the one-second tick

  private void tick() {
    if (stateBroken || !started) {
      return;
    }
    try {
      Instant now = Instant.now(clock);
      switch (state.phase()) {
        case IDLE -> idle(now);
        case COUNTDOWN -> countdown(now);
        case LOCKED -> evacuate(now);
        case EVACUATED -> saveEverything();
        case SAVED -> awaitMaintenance();
        default -> { }
      }
    } catch (RuntimeException e) {
      plugin.getLogger().severe("Nether reset tick failed: " + e);
      failOperation("Unexpected error: " + e);
    }
  }

  private void idle(Instant now) {
    if (state.paused()) {
      return;
    }
    if (state.nextReset() == null) {
      state.nextReset(NetherSchedule.first(now, Duration.ofHours(settings.intervalHours())));
      save();
    }
    long remaining = NetherSchedule.remainingSeconds(now, state.nextReset());
    long window = settings.milestonesSeconds().get(0);
    if (remaining <= window) {
      state.begin("reset-" + STAMP.format(now) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF)), now);
      fired = Countdown.initiallyFired(remaining, settings.milestonesSeconds());
      save();
      plugin.getLogger().info("Nether reset countdown started (operation " + state.operationId() + ").");
      countdown(now);
    }
  }

  private void countdown(Instant now) {
    if (state.paused()) {
      state.cancelCountdown(now, "Paused by an admin");
      save();
      broadcastText("<gold>[Nether]</gold> The scheduled Nether reset was paused.");
      return;
    }
    long remaining = NetherSchedule.remainingSeconds(now, state.nextReset());
    OptionalLong due = Countdown.poll(remaining, fired, settings.milestonesSeconds());
    if (due.isPresent()) {
      broadcast("countdown", Map.of("{time}", Countdown.format(due.getAsLong())));
    }
    if (remaining <= 0) {
      state.moveTo(ResetPhase.LOCKED);
      save();
      broadcast("locked", Map.of());
      plugin.getLogger().info("Nether is now locked for reset " + state.operationId());
    }
  }

  private void evacuate(Instant now) {
    if (evacuationStarted == null) {
      evacuationStarted = now;
    }
    List<Player> stuck = playersInNether();
    if (stuck.isEmpty()) {
      evacuationStarted = null;
      state.moveTo(ResetPhase.EVACUATED);
      save();
      return;
    }
    boolean timedOut = Duration.between(evacuationStarted, now).getSeconds() > EVACUATION_TIMEOUT_SECONDS;
    for (Player p : stuck) {
      if (timedOut) {
        // Last resort: their data is saved on disconnect, and they come back to a safe place.
        p.kick(MINI.deserialize(settings.messages().get("evacuated")));
      } else {
        sendToSafety(p);
      }
    }
  }

  private void saveEverything() {
    Bukkit.savePlayers();
    for (World w : Bukkit.getWorlds()) {
      w.save();
    }
    state.moveTo(ResetPhase.SAVED);
    save();
    plugin.getLogger().info("World and player data saved for reset " + state.operationId());
  }

  private void awaitMaintenance() {
    state.moveTo(ResetPhase.AWAITING_MAINTENANCE);
    save(); // persisted BEFORE any shutdown is requested
    if (settings.shutdownAutomation()) {
      broadcast("shutdown", Map.of());
      plugin.getLogger().warning("Nether reset ready. Requesting a controlled server shutdown in 5 seconds.");
      Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 100L);
    } else {
      plugin.getLogger().warning(
          "Nether reset is READY for maintenance. Stop the server (type 'stop'), then run the maintenance tool: nether-maintenance run");
      for (Player p : Bukkit.getOnlinePlayers()) {
        if (p.hasPermission("polder.nether.admin")) {
          p.sendMessage(Component.text("[Nether] Reset ready: stop the server and run the maintenance tool."));
        }
      }
    }
  }

  // ------------------------------------------------------------------ finishing after the restart

  private void finishReset() {
    Instant now = Instant.now(clock);
    Long seed = state.pendingSeed();
    if (seed == null) {
      failOperation("The reset was prepared but no new seed was recorded.");
      return;
    }
    if (state.phase() == ResetPhase.PREPARED) {
      state.moveTo(ResetPhase.VERIFYING);
      save();
    }
    World world = ensureWorld(seed);
    Path recorded = state.worldPath().isEmpty() ? null : Path.of(state.worldPath()).toAbsolutePath().normalize();
    boolean folderOk = false;
    if (world != null && recorded != null) {
      folderOk = world.getWorldPath().toAbsolutePath().normalize().equals(recorded);
    }
    boolean overworld = false;
    boolean end = false;
    for (World w : Bukkit.getWorlds()) {
      overworld |= w.getEnvironment() == World.Environment.NORMAL;
      end |= w.getEnvironment() == World.Environment.THE_END;
    }
    PostResetVerifier.Facts facts =
        new PostResetVerifier.Facts(
            world != null,
            world != null && world.getEnvironment() == World.Environment.NETHER,
            world == null ? 0L : world.getSeed(),
            seed,
            state.currentSeed(),
            folderOk,
            overworld,
            end);
    List<String> failures = PostResetVerifier.verify(facts, settings.requireNewSeed());
    if (!failures.isEmpty()) {
      plugin.getLogger().severe("Nether reset verification FAILED: " + String.join(" | ", failures));
      failOperation("Verification failed: " + String.join("; ", failures));
      return;
    }
    refreshProtectedPaths();
    state.succeed(now, NetherSchedule.afterSuccess(now, Duration.ofHours(settings.intervalHours())));
    save();
    fired = new HashSet<>();
    plugin.getLogger().info("Nether reset verified: new seed " + world.getSeed() + " is active. Nether access re-enabled.");
    broadcast("completed", Map.of());
  }

  private void failOperation(String detail) {
    try {
      if (state.phase() != ResetPhase.IDLE && state.phase() != ResetPhase.FAILED) {
        state.fail(Instant.now(clock), detail);
      }
    } catch (RuntimeException e) {
      plugin.getLogger().severe("Could not record failure: " + e.getMessage());
    }
    save();
    broadcast("failed", Map.of());
  }

  // ------------------------------------------------------------------ worlds

  public NamespacedKey worldKey() {
    return NamespacedKey.fromString(settings.worldKey());
  }

  public World resourceNether() {
    return settings == null ? null : Bukkit.getWorld(worldKey());
  }

  /** The existing Overworld (the first NORMAL world). */
  public World overworld() {
    for (World w : Bukkit.getWorlds()) {
      if (w.getEnvironment() == World.Environment.NORMAL) {
        return w;
      }
    }
    return null;
  }

  /**
   * Loads the resource Nether, creating it with {@code seed} only when it does not exist yet. An
   * existing folder keeps the seed stored in it, which is why the result is always verified.
   */
  private World ensureWorld(long seed) {
    World existing = resourceNether();
    if (existing != null) {
      return existing;
    }
    try {
      World created = WorldCreator.ofKey(worldKey()).environment(World.Environment.NETHER).seed(seed).createWorld();
      if (created == null) {
        plugin.getLogger().severe("The server could not create the world " + settings.worldKey());
        return null;
      }
      refreshProtectedPaths();
      return created;
    } catch (RuntimeException e) {
      plugin.getLogger().severe("Creating the resource Nether failed: " + e);
      return null;
    }
  }

  /** In normal operation the world's own seed is the truth: remember it so the next reset can compare. */
  private void adoptObservedSeed() {
    World w = resourceNether();
    if (w != null && (state.currentSeed() == null || state.currentSeed() != w.getSeed())) {
      if (state.currentSeed() != null) {
        plugin.getLogger().warning("Recorded seed " + state.currentSeed() + " differs from the world's seed " + w.getSeed() + "; using the world's.");
      }
      state.currentSeed(w.getSeed());
    }
  }

  private void catchUpSchedule(Instant now) {
    Duration interval = Duration.ofHours(settings.intervalHours());
    Instant next = NetherSchedule.orFirst(state.nextReset(), now, interval);
    if (Duration.between(next, now).getSeconds() > settings.overdueGraceSeconds()) {
      next = NetherSchedule.afterResume(next, now, Duration.ofMinutes(settings.resumeDelayMinutes()));
      plugin.getLogger().info("A reset was due while the server was off. Players get a warning window; reset at " + next);
    }
    state.nextReset(next);
  }

  // ------------------------------------------------------------------ players

  private List<Player> playersInNether() {
    List<Player> list = new ArrayList<>();
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (p.getWorld().getEnvironment() == World.Environment.NETHER) {
        list.add(p);
      }
    }
    return list;
  }

  public boolean isPrimaryNether(World w) {
    World resource = resourceNether();
    return w.getEnvironment() == World.Environment.NETHER && (resource == null || !w.getUID().equals(resource.getUID()));
  }

  /** Teleports a player to a safe Overworld spot near spawn. */
  public void sendToSafety(Player player) {
    World over = overworld();
    if (over == null) {
      return;
    }
    Location spawn = over.getSpawnLocation();
    Optional<SafeSpotFinder.Spot> spot = SafeSpotFinder.find(new BukkitTerrain(over), spawn.getBlockX(), spawn.getBlockZ(), settings.evacuationRadius());
    Location target = spot.map(s -> new Location(over, s.x() + 0.5, s.y(), s.z() + 0.5)).orElse(spawn);
    player.teleportAsync(target).thenAccept(ok -> {
      if (Boolean.TRUE.equals(ok)) {
        player.sendMessage(MINI.deserialize(settings.messages().get("evacuated")));
      }
    });
  }

  // ------------------------------------------------------------------ state for listener/commands

  public boolean active() {
    return started;
  }

  /** True when nobody may enter the Nether right now. */
  public boolean accessLocked() {
    return stateBroken || state.maintenance();
  }

  public NetherSettings settings() {
    return settings;
  }

  public ResetState state() {
    return state;
  }

  public Component deniedMessage() {
    return MINI.deserialize(settings.messages().get("denied"));
  }

  // ------------------------------------------------------------------ admin actions

  public String requestReset(boolean immediately) {
    if (stateBroken) {
      return "The state file is damaged; fix it first.";
    }
    if (state.phase() != ResetPhase.IDLE && state.phase() != ResetPhase.COUNTDOWN) {
      return "A reset is already in progress (phase " + state.phase() + ", operation " + state.operationId() + "). Duplicate resets are refused.";
    }
    if (state.paused()) {
      return "Resets are paused. Use /netheradmin resume first.";
    }
    Instant now = Instant.now(clock);
    Instant when = immediately ? now : now.plusSeconds(settings.milestonesSeconds().get(0));
    if (state.phase() == ResetPhase.COUNTDOWN && !immediately) {
      return "A reset countdown is already running.";
    }
    state.nextReset(when);
    save();
    return immediately
        ? "Reset starts now: the Nether closes, players are moved out and the world is saved."
        : "Reset scheduled in " + Countdown.format(settings.milestonesSeconds().get(0)) + " with the normal announcements.";
  }

  public String pause() {
    state.paused(true);
    save();
    return "Automatic Nether resets are paused" + (state.phase() == ResetPhase.COUNTDOWN ? "; the running countdown is cancelled." : ".");
  }

  public String resume() {
    Instant now = Instant.now(clock);
    state.paused(false);
    if (state.nextReset() != null) {
      state.nextReset(NetherSchedule.afterResume(state.nextReset(), now, Duration.ofMinutes(settings.resumeDelayMinutes())));
    }
    fired = new HashSet<>();
    save();
    return "Automatic Nether resets resumed. Next reset: " + state.nextReset();
  }

  public long secondsUntilNext() {
    return state.nextReset() == null ? -1 : NetherSchedule.remainingSeconds(Instant.now(clock), state.nextReset());
  }

  public static String longTime(long seconds) {
    long d = seconds / 86400;
    long h = seconds % 86400 / 3600;
    long m = seconds % 3600 / 60;
    StringBuilder b = new StringBuilder();
    if (d > 0) b.append(d).append(d == 1 ? " day " : " days ");
    if (h > 0 || d > 0) b.append(h).append(h == 1 ? " hour " : " hours ");
    b.append(m).append(m == 1 ? " minute" : " minutes");
    return b.toString();
  }

  // ------------------------------------------------------------------ helpers

  private void save() {
    try {
      store.save(state);
    } catch (IOException e) {
      plugin.getLogger().severe("Could not save the Nether state file: " + e.getMessage());
    }
  }

  private void broadcast(String key, Map<String, String> replacements) {
    String text = settings.messages().get(key);
    for (Map.Entry<String, String> r : replacements.entrySet()) {
      text = text.replace(r.getKey(), r.getValue());
    }
    Bukkit.getServer().broadcast(MINI.deserialize(text));
  }

  private void broadcastText(String miniMessage) {
    Bukkit.getServer().broadcast(MINI.deserialize(miniMessage));
  }
}
