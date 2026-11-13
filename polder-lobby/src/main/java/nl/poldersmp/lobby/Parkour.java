package nl.poldersmp.lobby;

import java.util.*;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

final class Parkour implements Listener {
  private final PolderLobby p;
  private final Map<UUID, Run> runs = new HashMap<>();
  private boolean ownTeleport;

  private static final class Run {
    UUID id = UUID.randomUUID();
    String course;
    Location origin;
    List<Location> checkpoints;
    Location finish;
    long started = System.nanoTime(), penalty;
    Checkpoints progress;
    BossBar bar;
  }

  Parkour(PolderLobby p) {
    this.p = p;
    Bukkit.getPluginManager().registerEvents(this, p);
    Bukkit.getScheduler()
        .runTaskTimer(
            p,
            () -> {
              for (var e : runs.entrySet()) {
                Player player = Bukkit.getPlayer(e.getKey());
                if (player != null) {
                  Run r = e.getValue();
                  r.bar.setTitle(
                      r.course
                          + " • "
                          + String.format(
                              Locale.ROOT, "%.2fs", elapsed(r, System.nanoTime()) / 1000.0)
                          + " • checkpoint "
                          + r.progress.next
                          + "/"
                          + r.checkpoints.size());
                }
              }
            },
            10,
            10);
  }

  static long elapsed(long started, long now, long penalty) {
    return Math.max(0, (now - started) / 1000000) + penalty;
  }

  private static long elapsed(Run r, long now) {
    return elapsed(r.started, now, r.penalty);
  }

  static final class Checkpoints {
    final int total;
    int next;

    Checkpoints(int total) {
      if (total < 0 || total > 32) throw new IllegalArgumentException("Checkpoint count");
      this.total = total;
    }

    boolean visit(int index) {
      if (index != next || next >= total) return false;
      next++;
      return true;
    }

    boolean complete() {
      return next == total;
    }
  }

  void command(Player player, String[] a) throws Exception {
    if (a.length == 0) {
      p.menus.open(player, "parkour");
      return;
    }
    String course = a.length > 1 ? PolderLobby.identifier(a[1]) : first();
    switch (a[0]) {
      case "start" -> start(player, course);
      case "reset" -> reset(player);
      case "quit" -> quit(player, true);
      case "top" ->
          p.result(
              player,
              p.store.top(course),
              list -> {
                player.sendMessage("Parkour • " + course);
                list.forEach(player::sendMessage);
              });
      default -> {
        if (!player.hasPermission("polder.lobby.admin"))
          throw new IllegalArgumentException("Admin permission required");
        switch (a[0]) {
          case "create" -> {
            if (p.parkourConfig.getConfigurationSection("courses").getKeys(false).size() >= 21)
              throw new IllegalArgumentException("Max 21 courses");
            if (p.parkourConfig.contains("courses." + course))
              throw new IllegalArgumentException("Course exists");
            p.parkourConfig.set("courses." + course + ".name", course);
          }
          case "setstart", "setfinish" -> {
            if (!p.safe(player.getLocation()))
              throw new IllegalArgumentException("Stand on safe ground");
            requireCourse(course);
            p.parkourConfig.set(
                "courses." + course + "." + (a[0].equals("setstart") ? "start" : "finish"),
                PolderLobby.point(player.getLocation()));
          }
          case "checkpoint" -> {
            requireCourse(course);
            if (!p.safe(player.getLocation()))
              throw new IllegalArgumentException("Stand on safe ground");
            int n = Integer.parseInt(a[2]);
            if (n < 1 || n > 32) throw new IllegalArgumentException("Checkpoint 1..32");
            p.parkourConfig.set(
                "courses." + course + ".checkpoints." + n, PolderLobby.point(player.getLocation()));
          }
          case "deletecheckpoint" ->
              p.parkourConfig.set(
                  "courses." + course + ".checkpoints." + Integer.parseInt(a[2]), null);
          case "delete" -> p.parkourConfig.set("courses." + course, null);
          default -> throw new IllegalArgumentException("Unknown parkour command");
        }
        stopAll();
        p.save(p.parkourConfig, "parkour.yml");
        player.sendMessage("Parkour course saved.");
      }
    }
  }

  private String first() {
    return p.parkourConfig.getConfigurationSection("courses").getKeys(false).stream()
        .findFirst()
        .orElse("");
  }

  private void requireCourse(String c) {
    if (!p.parkourConfig.contains("courses." + c))
      throw new IllegalArgumentException("Unknown course");
  }

  void start(Player player, String course) {
    if (!p.use(player, "parkour")) return;
    requireCourse(course);
    if (p.busy(player) || !p.safe(player.getLocation()) || !eligible(player))
      throw new IllegalArgumentException(
          "Leave other activities and stand safely in Survival/Adventure");
    String base = "courses." + course;
    Location start = p.location(p.parkourConfig, base + ".start"),
        finish = p.location(p.parkourConfig, base + ".finish");
    if (!p.safe(start) || !p.safe(finish) || !start.getWorld().equals(finish.getWorld()))
      throw new IllegalArgumentException(
          "Course start/finish must be safe, loaded, and share a world");
    List<Location> checks = new ArrayList<>();
    var section = p.parkourConfig.getConfigurationSection(base + ".checkpoints");
    if (section != null)
      for (int i = 1; i <= section.getKeys(false).size(); i++) {
        Location l = p.location(p.parkourConfig, base + ".checkpoints." + i);
        if (!p.safe(l) || !start.getWorld().equals(l.getWorld()))
          throw new IllegalArgumentException(
              "Checkpoints must be sequential 1..N and safely loaded");
        checks.add(l);
      }
    if (!p.acquireActivity(player.getUniqueId(), "PARKOUR"))
      throw new IllegalArgumentException("Activity busy");
    Run run = new Run();
    run.origin = player.getLocation();
    run.course = course;
    run.finish = finish;
    run.checkpoints = List.copyOf(checks);
    run.progress = new Checkpoints(checks.size());
    p.cosmetics.remove(player.getUniqueId());
    try {
      ownTeleport = true;
      if (!player.teleport(start)) throw new IllegalArgumentException("Start teleport denied");
      player.setFallDistance(0);
      run.started = System.nanoTime();
      run.bar = Bukkit.createBossBar("Parkour • " + course, BarColor.BLUE, BarStyle.SOLID);
      run.bar.addPlayer(player);
      runs.put(player.getUniqueId(), run);
      player.sendMessage("Parkour started. /parkour reset • /parkour quit");
    } catch (Exception e) {
      p.releaseActivity(player.getUniqueId(), "PARKOUR");
      throw e;
    } finally {
      ownTeleport = false;
    }
  }

  private boolean eligible(Player p) {
    return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
        && !p.isFlying()
        && !p.isGliding()
        && !p.isInsideVehicle()
        && !p.isRiptiding();
  }

  void reset(Player player) {
    Run r = runs.get(player.getUniqueId());
    if (r == null) throw new IllegalArgumentException("No parkour run");
    Location target =
        r.progress.next == 0
            ? p.location(p.parkourConfig, "courses." + r.course + ".start")
            : r.checkpoints.get(r.progress.next - 1);
    if (!p.safe(target)) throw new IllegalArgumentException("Checkpoint no longer safe");
    try {
      ownTeleport = true;
      if (!player.teleport(target)) throw new IllegalArgumentException("Teleport denied");
      r.penalty = Math.addExact(r.penalty, p.parkourConfig.getLong("checkpoint-penalty-millis"));
      player.setFallDistance(0);
    } finally {
      ownTeleport = false;
    }
  }

  void quit(Player player, boolean returnToStart) {
    Run r = runs.remove(player.getUniqueId());
    if (r == null) return;
    r.bar.removeAll();
    p.releaseActivity(player.getUniqueId(), "PARKOUR");
    if (returnToStart && p.safe(r.origin)) {
      try {
        ownTeleport = true;
        player.teleport(r.origin);
      } finally {
        ownTeleport = false;
      }
    }
    player.sendMessage("Parkour ended.");
  }

  void stopAll() {
    for (UUID id : new ArrayList<>(runs.keySet())) {
      Player player = Bukkit.getPlayer(id);
      if (player != null) quit(player, true);
      else {
        runs.remove(id).bar.removeAll();
        p.releaseActivity(id, "PARKOUR");
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    Run r = runs.get(e.getPlayer().getUniqueId());
    if (r == null || ownTeleport || !e.hasChangedPosition()) return;
    Player player = e.getPlayer();
    if (!eligible(player)
        || !e.getFrom().getWorld().equals(e.getTo().getWorld())
        || e.getFrom().distanceSquared(e.getTo())
            > Math.pow(p.parkourConfig.getDouble("maximum-step"), 2)) {
      quit(player, false);
      return;
    }
    if (r.progress.next < r.checkpoints.size()
        && near(e.getTo(), r.checkpoints.get(r.progress.next))) {
      r.progress.visit(r.progress.next);
      r.bar.setProgress((double) r.progress.next / Math.max(1, r.checkpoints.size()));
      player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, .5f, 1.5f);
    }
    if (r.progress.next == r.checkpoints.size() && near(e.getTo(), r.finish)) {
      long millis = elapsed(r, System.nanoTime());
      if (millis < p.parkourConfig.getLong("minimum-millis")) {
        quit(player, false);
        return;
      }
      runs.remove(player.getUniqueId());
      r.bar.removeAll();
      p.releaseActivity(player.getUniqueId(), "PARKOUR");
      p.result(
          player,
          p.store.finish(
              r.id,
              player.getUniqueId(),
              r.course,
              millis,
              p.parkourConfig.getLong("first-completion-tokens"),
              System.currentTimeMillis()),
          best -> {
            player.sendMessage(
                "Finished "
                    + r.course
                    + " in "
                    + millis
                    + " ms"
                    + (best ? " • personal best!" : ""));
            p.refresh(player.getUniqueId());
            p.events.finishCourse(player, r.course, millis);
          });
    }
  }

  static boolean near(Location a, Location b) {
    return a != null
        && b != null
        && a.getWorld().equals(b.getWorld())
        && a.distanceSquared(b) <= 1.2 * 1.2;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) {
    if (!ownTeleport) quit(e.getPlayer(), false);
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    quit(e.getEntity(), false);
  }

  @EventHandler
  public void mode(PlayerGameModeChangeEvent e) {
    quit(e.getPlayer(), false);
  }
}
