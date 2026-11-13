package nl.poldersmp.lobby;

import java.util.*;
import nl.poldersmp.lobby.data.LobbyStore;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;

final class LobbyEvents {
  private final PolderLobby p;
  private LobbyStore.Event event;
  private BossBar bar;
  private boolean pending, active;
  private long next;
  private int generation;
  private final Set<String> submitted = new HashSet<>();

  LobbyEvents(PolderLobby p) {
    this.p = p;
    Bukkit.getScheduler().runTaskTimer(p, this::tick, 20, 20);
  }

  void restoreSchedule() {
    p.store
        .setting("next-event")
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      if (e != null) {
                        p.getLogger().severe("Event schedule unavailable: " + e);
                        return;
                      }
                      try {
                        next =
                            v == null
                                ? System.currentTimeMillis()
                                    + p.cfg.getLong("events.interval-seconds") * 1000
                                : Long.parseLong(v);
                      } catch (Exception bad) {
                        next =
                            System.currentTimeMillis()
                                + p.cfg.getLong("events.interval-seconds") * 1000;
                      }
                      saveNext();
                    }));
  }

  static boolean due(long now, long next) {
    return next > 0 && now >= next;
  }

  private void saveNext() {
    p.store
        .setting("next-event", Long.toString(next))
        .exceptionally(
            e -> {
              p.getLogger().severe("Event schedule persistence: " + e);
              return null;
            });
  }

  String description() {
    return event == null
        ? "Next challenge: " + (next == 0 ? "not scheduled" : java.time.Instant.ofEpochMilli(next))
        : event.type()
            + " • "
            + (active ? "ACTIVE" : "countdown")
            + " • "
            + Math.max(
                0, ((active ? event.ends() : event.starts()) - System.currentTimeMillis()) / 1000)
            + "s";
  }

  void command(Player player, String[] a) {
    if (a.length == 0 || a[0].equals("status")) {
      player.sendMessage(description());
      return;
    }
    if (a[0].equals("top")) {
      if (event == null) {
        player.sendMessage("No live challenge.");
        return;
      }
      p.result(
          player,
          p.store.eventTop(event.id(), event.type().equals("race")),
          rows -> rows.forEach(player::sendMessage));
      return;
    }
    if (!player.hasPermission("polder.lobby.admin"))
      throw new IllegalArgumentException("Admin permission required");
    switch (a[0]) {
      case "start" -> start(a[1]);
      case "stop" -> stop();
      case "schedule" -> {
        long seconds = Long.parseLong(a[1]);
        if (seconds < 10 || seconds > 604800)
          throw new IllegalArgumentException("Schedule 10..604800 seconds");
        next = System.currentTimeMillis() + seconds * 1000;
        saveNext();
        player.sendMessage("Next challenge scheduled; automatic must be enabled to start it.");
      }
      default ->
          throw new IllegalArgumentException(
              "/lobbyevent start <race|treasure|collectible> | stop | schedule <seconds> | top");
    }
  }

  private void start(String type) {
    if (!p.module("events") || event != null || pending)
      throw new IllegalArgumentException("Events unavailable or already running");
    if (!List.of("race", "treasure", "collectible").contains(type))
      throw new IllegalArgumentException("race, treasure, collectible");
    String course = p.cfg.getString("events.course", "");
    if (type.equals("race") && !p.parkourConfig.contains("courses." + course + ".finish"))
      throw new IllegalArgumentException("Configure events.course to a complete parkour course");
    if (!type.equals("race")
        && p.locations.getConfigurationSection("secrets").getKeys(false).isEmpty())
      throw new IllegalArgumentException("Configure hunt secrets first");
    long starts = System.currentTimeMillis() + p.cfg.getLong("events.warning-seconds") * 1000;
    LobbyStore.Event candidate =
        new LobbyStore.Event(
            UUID.randomUUID(),
            type,
            course,
            "WARNING",
            starts,
            starts + p.cfg.getLong("events.duration-seconds") * 1000);
    pending = true;
    int operationGeneration = generation;
    p.store
        .event(candidate)
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      pending = false;
                      if (operationGeneration != generation) {
                        p.store.status(candidate.id(), "CANCELLED");
                        return;
                      }
                      if (e != null) {
                        p.getLogger().warning("Event start refused: " + e);
                        return;
                      }
                      event = candidate;
                      submitted.clear();
                      active = false;
                      bar =
                          Bukkit.createBossBar(
                              type + " starts soon", BarColor.YELLOW, BarStyle.SOLID);
                      Bukkit.broadcast(
                          net.kyori.adventure.text.Component.text(
                              "Lobby "
                                  + type
                                  + " starts in "
                                  + p.cfg.getInt("events.warning-seconds")
                                  + " seconds"));
                      next =
                          System.currentTimeMillis()
                              + p.cfg.getLong("events.interval-seconds") * 1000;
                      saveNext();
                    }));
  }

  private void tick() {
    if (!p.module("events")) return;
    long now = System.currentTimeMillis();
    if (event == null) {
      if (p.cfg.getBoolean("events.automatic") && due(now, next) && !pending)
        try {
          List<String> types = List.of("race", "treasure", "collectible");
          start(types.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(3)));
        } catch (Exception e) {
          next = now + 60000;
          saveNext();
          p.getLogger().warning("Scheduled lobby event: " + e.getMessage());
        }
      return;
    }
    if (pending) return;
    if (!active && now >= event.starts()) {
      pending = true;
      UUID id = event.id();
      p.store
          .status(id, "ACTIVE")
          .whenComplete(
              (v, e) ->
                  p.main(
                      () -> {
                        pending = false;
                        if (event == null || !event.id().equals(id)) return;
                        if (e != null) {
                          stop();
                          return;
                        }
                        active = true;
                        Bukkit.broadcast(
                            net.kyori.adventure.text.Component.text(
                                "Lobby "
                                    + event.type()
                                    + " is live! "
                                    + (event.type().equals("race")
                                        ? "/parkour start " + event.course()
                                        : "Find "
                                            + (event.type().equals("treasure")
                                                ? "treasure"
                                                : "stars")
                                            + " around the lobby.")));
                      }));
    }
    bar.setTitle(description());
    bar.setProgress(
        Math.max(0, Math.min(1, (double) (event.ends() - now) / (event.ends() - event.starts()))));
    for (Player player : Bukkit.getOnlinePlayers())
      if (p.lobbyWorld(player.getWorld())) bar.addPlayer(player);
      else bar.removePlayer(player);
    if (active && now >= event.ends()) {
      LobbyStore.Event ended = event;
      pending = true;
      p.store
          .winners(
              ended.id(), ended.type().equals("race"), p.cfg.getInt("events.minimum-participants"))
          .whenComplete(
              (w, e) ->
                  p.main(
                      () -> {
                        pending = false;
                        if (event == null || !event.id().equals(ended.id())) return;
                        if (e != null) {
                          p.getLogger().warning("Event rewards pending, retrying: " + e);
                          return;
                        }
                        bar.removeAll();
                        bar = null;
                        event = null;
                        active = false;
                        Bukkit.broadcast(
                            net.kyori.adventure.text.Component.text(
                                w.isEmpty()
                                    ? "Lobby challenge ended without enough participants."
                                    : "Lobby challenge winners: "
                                        + w.stream()
                                            .map(
                                                id -> {
                                                  Player pl = Bukkit.getPlayer(id);
                                                  return pl == null ? id.toString() : pl.getName();
                                                })
                                            .toList()));
                        for (UUID id : w) p.refresh(id);
                      }));
    }
  }

  void finishCourse(Player player, String course, long millis) {
    if (event != null
        && active
        && event.type().equals("race")
        && event.course().equals(course)
        && player.hasPermission("polder.lobby.events")
        && System.currentTimeMillis() - millis >= event.starts())
      p.store
          .point(event.id(), player.getUniqueId(), "race", millis)
          .exceptionally(
              e -> {
                p.getLogger().warning("Race score: " + e);
                return null;
              });
  }

  void collect(Player player, String id, String kind) {
    if (event == null
        || !active
        || !player.hasPermission("polder.lobby.events")
        || !p.lobbyWorld(player.getWorld())
        || p.busy(player)) return;
    if (event.type().equals("race")
        || event.type().equals("treasure") && !kind.equals("treasure")
        || event.type().equals("collectible") && !kind.equals("star")) return;
    String key = player.getUniqueId() + ":" + id;
    if (!submitted.add(key)) return;
    p.store
        .point(event.id(), player.getUniqueId(), id, 1)
        .whenComplete(
            (v, e) -> {
              if (e != null) p.main(() -> submitted.remove(key));
            });
  }

  void stop() {
    generation++;
    if (event != null) {
      p.store
          .status(event.id(), "CANCELLED")
          .exceptionally(
              e -> {
                p.getLogger().severe("Event cancellation: " + e);
                return null;
              });
      event = null;
    }
    active = false;
    if (bar != null) {
      bar.removeAll();
      bar = null;
    }
    submitted.clear();
  }
}
