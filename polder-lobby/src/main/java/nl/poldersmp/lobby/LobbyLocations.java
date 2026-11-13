package nl.poldersmp.lobby;

import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

final class LobbyLocations implements Listener {
  static final List<String> KINDS =
      List.of("online", "status", "kills", "richest", "king", "events", "parkour");
  private final PolderLobby p;
  private final NamespacedKey tag;
  private final Map<String, TextDisplay> displays = new HashMap<>();
  private final Set<String> external = new HashSet<>();
  private Class<?> dh;
  private final Map<UUID, String> inside = new HashMap<>();
  private final Set<String> pending = new HashSet<>();
  private List<String> parkourTop = List.of("No records yet");
  private int frames;

  LobbyLocations(PolderLobby p) {
    this.p = p;
    tag = new NamespacedKey(p, "hologram");
    Bukkit.getPluginManager().registerEvents(this, p);
    for (World w : Bukkit.getWorlds())
      for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(tag)) e.remove();
    hookCitizens();
    Bukkit.getScheduler().runTaskTimer(p, this::tick, 20, 5);
    Bukkit.getScheduler()
        .runTaskTimer(
            p,
            () -> {
              if (!p.module("holograms")) return;
              String course = p.cfg.getString("holograms.parkour-course", "");
              p.store
                  .top(
                      course.isEmpty()
                          ? p
                              .parkourConfig
                              .getConfigurationSection("courses")
                              .getKeys(false)
                              .stream()
                              .findFirst()
                              .orElse("_unconfigured")
                          : course)
                  .whenComplete(
                      (v, e) ->
                          p.main(
                              () -> {
                                if (e == null) parkourTop = v;
                              }));
              updateHolograms();
            },
            40,
            100);
  }

  private void hookCitizens() {
    var citizens = Bukkit.getPluginManager().getPlugin("Citizens");
    if (citizens == null || !citizens.isEnabled()) {
      p.getLogger().info("Citizens unavailable: NPC bindings remain inactive.");
      return;
    }
    try {
      Class<? extends Event> eventClass =
          (Class<? extends Event>)
              Class.forName(
                  "net.citizensnpcs.api.event.NPCRightClickEvent",
                  true,
                  citizens.getClass().getClassLoader());
      Class<?> npcType =
          Class.forName("net.citizensnpcs.api.npc.NPC", true, citizens.getClass().getClassLoader());
      Bukkit.getPluginManager()
          .registerEvent(
              eventClass,
              this,
              EventPriority.NORMAL,
              (listener, event) -> {
                if (!p.module("npcs")) return;
                try {
                  Object npc = eventClass.getMethod("getNPC").invoke(event);
                  int id = ((Number) npcType.getMethod("getId").invoke(npc)).intValue();
                  Player player = (Player) eventClass.getMethod("getClicker").invoke(event);
                  String action = p.locations.getString("npcs." + id + ".action");
                  if (action != null
                      && player.hasPermission("polder.lobby.npcs")
                      && !p.busy(player)) {
                    if (action.equals("selector")) p.transfer(player);
                    else p.menus.open(player, action);
                  }
                } catch (Exception e) {
                  p.getLogger().warning("Citizens action failed: " + e);
                }
              },
              p,
              true);
    } catch (Exception e) {
      p.getLogger().warning("Citizens API incompatible; no NPC listeners registered: " + e);
    }
  }

  void reload() {
    cleanup();
    dh = null;
    var plugin = Bukkit.getPluginManager().getPlugin("DecentHolograms");
    if (plugin != null && plugin.isEnabled())
      try {
        dh =
            Class.forName(
                "eu.decentsoftware.holograms.api.DHAPI", true, plugin.getClass().getClassLoader());
      } catch (Exception e) {
        p.getLogger().warning("DecentHolograms API unavailable; using bounded TextDisplays");
      }
    updateHolograms();
  }

  private void tick() {
    if (!p.ready) return;
    frames++;
    if (frames == 1) reload();
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (!p.lobbyWorld(player.getWorld()) || p.busy(player)) {
        inside.remove(player.getUniqueId());
        continue;
      }
      Location here = player.getLocation();
      if (p.module("portals") && player.hasPermission("polder.lobby.portals")) {
        String entered = "";
        var portals = p.locations.getConfigurationSection("portals");
        if (portals != null)
          for (String id : portals.getKeys(false)) {
            Location a = p.location(p.locations, "portals." + id + ".pos1"),
                b = p.location(p.locations, "portals." + id + ".pos2");
            if (a == null || b == null || !a.getWorld().equals(b.getWorld())) continue;
            if (inBox(here, a, b)) {
              entered = id;
              break;
            }
          }
        String old = inside.put(player.getUniqueId(), entered);
        if (!entered.isEmpty() && !entered.equals(old)) {
          player.playSound(here, Sound.BLOCK_PORTAL_AMBIENT, .4f, 1.2f);
          p.transfer(player, "portals");
        }
        if (frames % 4 == 0 && portals != null)
          for (String id : portals.getKeys(false)) {
            Location a = p.location(p.locations, "portals." + id + ".pos1"),
                b = p.location(p.locations, "portals." + id + ".pos2");
            if (a != null
                && b != null
                && a.getWorld().equals(player.getWorld())
                && a.getWorld().equals(b.getWorld())
                && a.distanceSquared(here) < 1024) {
              Location center = a.clone().add(b).multiply(.5);
              player.spawnParticle(
                  Particle.PORTAL,
                  center,
                  6,
                  Math.min(3, Math.abs(a.getX() - b.getX()) / 2),
                  Math.min(4, Math.abs(a.getY() - b.getY()) / 2),
                  Math.min(3, Math.abs(a.getZ() - b.getZ()) / 2),
                  .02);
            }
          }
      }
      if (p.module("secrets")) {
        var secrets = p.locations.getConfigurationSection("secrets");
        if (secrets != null)
          for (String id : secrets.getKeys(false)) {
            String base = "secrets." + id;
            Location point = p.location(p.locations, base + ".location");
            if (point == null
                || !point.getWorld().equals(player.getWorld())
                || point.distanceSquared(here) > 64) continue;
            if (frames % 4 == 0)
              player.spawnParticle(
                  Particle.END_ROD, point.clone().add(0, .6, 0), 2, .15, .3, .15, .01);
            if (point.distanceSquared(here) <= 2.25) collect(player, id);
          }
      }
    }
  }

  static boolean inBox(Location l, Location a, Location b) {
    return l.getWorld().equals(a.getWorld())
        && a.getWorld().equals(b.getWorld())
        && l.getX() >= Math.min(a.getX(), b.getX())
        && l.getX() < Math.max(a.getX(), b.getX()) + 1
        && l.getY() >= Math.min(a.getY(), b.getY())
        && l.getY() < Math.max(a.getY(), b.getY()) + 1
        && l.getZ() >= Math.min(a.getZ(), b.getZ())
        && l.getZ() < Math.max(a.getZ(), b.getZ()) + 1;
  }

  private void collect(Player player, String id) {
    if (!player.hasPermission("polder.lobby.secrets")) return;
    String key = player.getUniqueId() + ":" + id;
    var profile = p.profiles.get(player.getUniqueId());
    p.events.collect(player, id, p.locations.getString("secrets." + id + ".type", "star"));
    if (profile == null || profile.achievements().contains("secret:" + id) || !pending.add(key))
      return;
    p.store
        .reward(
            player.getUniqueId(),
            "secret:" + id,
            p.locations.getLong("secrets." + id + ".tokens", 2),
            p.locations.getString("secrets." + id + ".unlock", ""),
            System.currentTimeMillis())
        .whenComplete(
            (first, error) ->
                p.main(
                    () -> {
                      pending.remove(key);
                      if (error != null) {
                        p.getLogger().warning("Secret reward failed: " + error);
                        return;
                      }
                      p.refresh(player.getUniqueId());
                      if (first && player.isOnline()) {
                        p.msg(player, "secret-found", "secret", id);
                        player.playSound(
                            player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .6f, 1.3f);
                        if (p.locations.getBoolean("secrets." + id + ".announce"))
                          Bukkit.broadcast(
                              Component.text(player.getName() + " discovered a lobby secret!"));
                      }
                    }));
  }

  private List<String> lines(String kind) {
    var status = p.network.status(p.cfg.getLong("network.max-age-seconds") * 1000);
    return switch (kind) {
      case "online" ->
          List.of("PolderSMP Network", "Players: " + status.getOrDefault("players", "Unavailable"));
      case "status" ->
          List.of(
              "SMP Status",
              status.isEmpty()
                  ? "Unavailable"
                  : !Boolean.TRUE.equals(status.get("online"))
                      ? "Offline"
                      : Boolean.TRUE.equals(status.get("locked")) ? "Locked" : "Online");
      case "parkour" -> parkourTop.isEmpty() ? List.of("Parkour • No records yet") : parkourTop;
      default -> {
        Object data = status.get(kind);
        if (data instanceof List<?> list)
          yield list.stream().limit(10).map(String::valueOf).toList();
        yield List.of(
            kind.toUpperCase(Locale.ROOT),
            data == null ? "Network data unavailable" : String.valueOf(data));
      }
    };
  }

  private void updateHolograms() {
    if (!p.module("holograms")) return;
    var section = p.locations.getConfigurationSection("holograms");
    if (section == null) return;
    for (String id : section.getKeys(false)) {
      Location loc = p.location(p.locations, "holograms." + id + ".location");
      if (loc == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4))
        continue;
      List<String> text = lines(p.locations.getString("holograms." + id + ".kind", "status"));
      String name = "polderlobby_" + id;
      if (dh != null)
        try {
          if (external.add(name))
            dh.getMethod("createHologram", String.class, Location.class, boolean.class, List.class)
                .invoke(null, name, loc, false, text);
          else {
            Object holo = dh.getMethod("getHologram", String.class).invoke(null, name);
            if (holo != null)
              for (var method : dh.getMethods())
                if (method.getName().equals("setHologramLines")
                    && method.getParameterCount() == 2
                    && method.getParameterTypes()[1] == List.class) {
                  method.invoke(null, holo, text);
                  break;
                }
          }
          continue;
        } catch (Exception e) {
          p.getLogger()
              .warning(
                  "Hologram integration failed, using TextDisplay: "
                      + e.getClass().getSimpleName());
          try {
            dh.getMethod("removeHologram", String.class).invoke(null, name);
          } catch (Exception ignored) {
          }
          external.remove(name);
          dh = null;
        }
      TextDisplay display = displays.get(id);
      if (display == null || !display.isValid()) {
        display = loc.getWorld().spawn(loc, TextDisplay.class);
        display.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        display.setPersistent(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setViewRange(.375f);
        displays.put(id, display);
      }
      display.text(Component.text(String.join("\n", text)));
    }
  }

  void cleanup() {
    displays.values().forEach(Entity::remove);
    displays.clear();
    if (dh != null)
      for (String name : external)
        try {
          dh.getMethod("removeHologram", String.class).invoke(null, name);
        } catch (Exception ignored) {
        }
    external.clear();
  }

  @EventHandler
  public void chunk(ChunkLoadEvent e) {
    for (Entity entity : e.getChunk().getEntities())
      if (entity.getPersistentDataContainer().has(tag)) entity.remove();
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    inside.remove(e.getPlayer().getUniqueId());
  }
}
