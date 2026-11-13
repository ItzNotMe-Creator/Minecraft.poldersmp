package nl.poldersmp.lobby;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Loopback-only disposable fixture; excluded from Maven artifacts. */
public final class LobbyLiveProbe extends JavaPlugin {
  public static final class Bridge implements nl.poldersmp.common.network.NetworkBridge {
    String state = "locked";
    int transfers;

    public int apiVersion() {
      return 1;
    }

    public Set<String> capabilities() {
      return Set.of("status", "profiles", "sessions", "access-checked-transfers");
    }

    public CompletableFuture<Map<String, Object>> status() {
      return state.equals("fail")
          ? CompletableFuture.failedFuture(new IllegalStateException("Injected provider failure"))
          : CompletableFuture.completedFuture(
              Map.of(
                  "online",
                  !state.equals("offline"),
                  "locked",
                  state.equals("locked"),
                  "players",
                  2));
    }

    public CompletableFuture<Map<String, Object>> profile(UUID id) {
      return CompletableFuture.completedFuture(Map.of("kills", 42, "deaths", 3));
    }

    public CompletableFuture<Boolean> transfer(UUID id, String server) {
      transfers++;
      return CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<String> session(UUID id) {
      return CompletableFuture.completedFuture("disposable-session");
    }
  }

  private final Bridge bridge = new Bridge();

  public void onEnable() {
    if (!Files.isRegularFile(Bukkit.getWorldContainer().toPath().resolve(".polder-disposable-test"))
        || !Bukkit.getIp().equals("127.0.0.1"))
      throw new IllegalStateException("Disposable loopback marker required");
  }

  private Object field(Object object, String name) throws Exception {
    var f = object.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(object);
  }

  private Object call(Object target, String method, Class<?>[] types, Object... values)
      throws Exception {
    return target.getClass().getMethod(method, types).invoke(target, values);
  }

  private void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private YamlConfiguration yaml(JavaPlugin plugin, String file) throws Exception {
    var y = new YamlConfiguration();
    y.load(plugin.getDataFolder().toPath().resolve(file).toFile());
    return y;
  }

  private void save(JavaPlugin plugin, String file, YamlConfiguration y) throws Exception {
    y.save(plugin.getDataFolder().toPath().resolve(file).toFile());
  }

  private Map<String, Object> point(double x, double y, double z) {
    return Map.of("world", "world", "x", x, "y", y, "z", z, "yaw", -90, "pitch", 10);
  }

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      Player player = (Player) sender;
      JavaPlugin lobby = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PolderLobby"),
          intro = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PolderCinematics");
      switch (args[0]) {
        case "hologram" -> {
          long count =
              player.getWorld().getEntities().stream()
                  .filter(
                      e ->
                          e instanceof TextDisplay
                              && e.getPersistentDataContainer().getKeys().stream()
                                  .anyMatch(k -> k.toString().equals("polderlobby:hologram")))
                  .count();
          check(count == 1, "Expected exactly one owned status display, found " + count);
          sender.sendMessage("NATIVE STATUS HOLOGRAM PASS");
        }
        case "portal" -> {
          var loc = yaml(lobby, "locations.yml");
          loc.set("portals.probe.pos1", point(1, 180, 0));
          loc.set("portals.probe.pos2", point(2, 182, 1));
          save(lobby, "locations.yml", loc);
          player.performCommand("lobbyadmin reload");
          player.teleport(new Location(player.getWorld(), .5, 180, .5));
          sender.sendMessage("PORTAL FIXTURE READY");
        }
        case "placeholders" -> {
          var papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
          check(papi != null && papi.isEnabled(), "Real PlaceholderAPI missing");
          Class<?> api =
              Class.forName(
                  "me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
          String parsed =
              String.valueOf(
                  api.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                      .invoke(null, player, "%polderlobby_tokens%:%polderlobby_rank%"));
          check(parsed.matches("[0-9]+:.+"), "Expansion did not resolve: " + parsed);
          sender.sendMessage("REAL PLACEHOLDERAPI EXPANSION PASS " + parsed);
        }
        case "network" -> {
          Bukkit.getServicesManager()
              .unregister(nl.poldersmp.common.network.NetworkBridge.class, bridge);
          if (!args[1].equals("clear")) {
            bridge.state = args[1];
            Bukkit.getServicesManager()
                .register(
                    nl.poldersmp.common.network.NetworkBridge.class,
                    bridge,
                    this,
                    org.bukkit.plugin.ServicePriority.Highest);
          }
          Object network = field(lobby, "network");
          var future = (CompletableFuture<?>) call(network, "refresh", new Class[] {});
          future.whenComplete(
              (v, e) ->
                  Bukkit.getScheduler()
                      .runTask(
                          this, () -> sender.sendMessage("NETWORK FIXTURE " + args[1] + " READY")));
        }
        case "networkaudit" -> {
          check(
              bridge.transfers == Integer.parseInt(args[1]),
              "Unexpected transfer count " + bridge.transfers);
          sender.sendMessage("NETWORK TRANSFER AUDIT PASS " + bridge.transfers);
        }
        case "setup" -> {
          check(
              Bukkit.getPluginManager().getPlugin("PolderCore") == null,
              "Core must not be on lobby");
          var perms = player.addAttachment(this);
          for (String permission : List.of("polder.lobby.admin", "polder.intro.admin"))
            perms.setPermission(permission, true);
          perms.setPermission("polder.lobby.gadgets", false);
          World world = player.getWorld();
          world.setTime(1000);
          world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
          for (int x = -5; x <= 22; x++)
            for (int z = -5; z <= 8; z++) {
              world.getBlockAt(x, 179, z).setType(Material.STONE, false);
              for (int y = 180; y <= 195; y++)
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
          player.setGameMode(GameMode.SURVIVAL);
          player.setAllowFlight(false);
          player.teleport(new Location(world, .5, 180, .5));
          if (!player.getInventory().contains(Material.DIAMOND))
            player.getInventory().addItem(new ItemStack(Material.DIAMOND, 7));
          var y = yaml(lobby, "lobby.yml");
          y.set("spawn", point(.5, 180, .5));
          y.set("events.course", "probe");
          y.set("events.warning-seconds", 2);
          y.set("events.duration-seconds", 10);
          y.set("events.minimum-participants", 1);
          save(lobby, "lobby.yml", y);
          var parkour = yaml(lobby, "parkour.yml");
          parkour.set("courses.probe.name", "Probe");
          parkour.set("courses.probe.start", point(2.5, 180, .5));
          parkour.set("courses.probe.checkpoints.1", point(5.5, 180, .5));
          parkour.set("courses.probe.finish", point(10.5, 180, .5));
          save(lobby, "parkour.yml", parkour);
          var loc = yaml(lobby, "locations.yml");
          loc.set("holograms.status.location", point(2.5, 183, 4.5));
          loc.set("holograms.status.kind", "status");
          loc.set("secrets.probe.location", point(17.5, 180, .5));
          loc.set("secrets.probe.type", "star");
          loc.set("secrets.probe.tokens", 2);
          save(lobby, "locations.yml", loc);
          var i = yaml(intro, "config.yml");
          i.set("spawn", point(.5, 180, .5));
          i.set("points", List.of(point(.5, 190, .5), point(8.5, 192, 4.5), point(12.5, 188, .5)));
          i.set("duration-seconds", 6);
          i.set("delay-ticks", 10);
          i.set("mode", "EVERY_JOIN");
          save(intro, "config.yml", i);
          player.performCommand("lobbyadmin reload");
          player.performCommand("joinintro reload");
          sender.sendMessage("LOBBY FIXTURE SETUP PASS");
        }
        case "restore" -> {
          check(player.getGameMode() == GameMode.SURVIVAL, "Gamemode not restored");
          check(!player.isFlying(), "Still flying");
          check(
              player.getLocation().distanceSquared(new Location(player.getWorld(), .5, 180, .5))
                  < 2,
              "Unsafe landing");
          check(
              Boolean.FALSE.equals(
                  call(intro, "isPlaying", new Class[] {UUID.class}, player.getUniqueId())),
              "Intro still busy");
          check(
              player.getInventory().contains(Material.DIAMOND, 7),
              "Unrelated inventory overwritten");
          check(
              Boolean.TRUE.equals(
                  call(
                      lobby,
                      "acquireActivity",
                      new Class[] {UUID.class, String.class},
                      player.getUniqueId(),
                      "PROBE")),
              "Activity not released");
          call(
              lobby,
              "releaseActivity",
              new Class[] {UUID.class, String.class},
              player.getUniqueId(),
              "PROBE");
          sender.sendMessage("INTRO RESTORATION INVENTORY ACTIVITY PASS");
        }
        case "busy" -> {
          check(player.getGameMode() == GameMode.SPECTATOR, "Not spectator");
          check(
              Boolean.FALSE.equals(
                  call(
                      lobby,
                      "acquireActivity",
                      new Class[] {UUID.class, String.class},
                      player.getUniqueId(),
                      "PROBE")),
              "Concurrent activity accepted");
          sender.sendMessage("INTRO EXCLUSIVE ACTIVITY PASS");
        }
        case "grants" -> {
          var attachment = player.addAttachment(this);
          attachment.setPermission("polder.lobby.admin", true);
          attachment.setPermission("polder.intro.admin", true);
          attachment.setPermission("polder.lobby.gadgets", false);
          sender.sendMessage("PROBE GRANTS PASS");
        }
        case "audit" -> {
          Object db = field(lobby, "store");
          var future =
              (CompletableFuture<?>)
                  call(db, "profile", new Class[] {UUID.class}, player.getUniqueId());
          future.whenComplete(
              (profile, error) ->
                  Bukkit.getScheduler()
                      .runTask(
                          this,
                          () -> {
                            try {
                              if (error != null) throw new RuntimeException(error);
                              long tokens =
                                  ((Number) call(profile, "tokens", new Class[] {})).longValue();
                              var records = (Map<?, ?>) call(profile, "records", new Class[] {});
                              check(records.containsKey("probe"), "Parkour record missing");
                              check(tokens >= 10, "Daily/parkour rewards missing");
                              sender.sendMessage("LOBBY PERSISTENCE AUDIT PASS tokens=" + tokens);
                            } catch (Throwable e) {
                              sender.sendMessage("PROBE FAIL " + e);
                              getLogger().severe(e.toString());
                            }
                          }));
        }
        case "abort" -> {
          player.teleport(new Location(player.getWorld(), .5, 180, .5));
          check(
              Boolean.TRUE.equals(
                  call(
                      lobby,
                      "acquireActivity",
                      new Class[] {UUID.class, String.class},
                      player.getUniqueId(),
                      "PROBE")),
              "Teleport did not cancel parkour");
          call(
              lobby,
              "releaseActivity",
              new Class[] {UUID.class, String.class},
              player.getUniqueId(),
              "PROBE");
          sender.sendMessage("PARKOUR TELEPORT CANCEL PASS");
        }
        case "secret" -> {
          player.teleport(new Location(player.getWorld(), 17.5, 180, .5));
          sender.sendMessage("SECRET TELEPORT READY");
        }
        case "spawn" -> {
          player.teleport(new Location(player.getWorld(), .5, 180, .5));
          sender.sendMessage("PROBE SPAWN READY");
        }
        case "deny" -> {
          var attachment = player.addAttachment(this);
          attachment.setPermission("polder.lobby.daily", false);
          sender.sendMessage("DAILY DENIED READY");
        }
        case "allow" -> {
          var attachment = player.addAttachment(this);
          attachment.setPermission("polder.lobby.daily", true);
          sender.sendMessage("DAILY ALLOWED READY");
        }
        case "cosmetics" -> {
          Object db = field(lobby, "store");
          var future =
              (CompletableFuture<?>)
                  call(
                      db,
                      "reward",
                      new Class[] {UUID.class, String.class, long.class, String.class, long.class},
                      player.getUniqueId(),
                      "probe:grant",
                      100L,
                      "",
                      System.currentTimeMillis());
          future.whenComplete(
              (v, e) ->
                  Bukkit.getScheduler()
                      .runTask(
                          this,
                          () ->
                              sender.sendMessage(
                                  e == null ? "COSMETIC TEST CREDIT READY" : "PROBE FAIL " + e)));
        }
        case "clean" -> {
          long pets =
              player.getWorld().getEntities().stream()
                  .filter(
                      e ->
                          e instanceof Rabbit
                              && e.getPersistentDataContainer().getKeys().stream()
                                  .anyMatch(k -> k.toString().equals("polderlobby:visual")))
                  .count();
          sender.sendMessage("VISUAL PETS " + pets);
        }
        default -> throw new IllegalArgumentException("Unknown fixture action");
      }
    } catch (Throwable e) {
      sender.sendMessage("PROBE FAIL " + e);
      getLogger().severe(e.toString());
    }
    return true;
  }
}
