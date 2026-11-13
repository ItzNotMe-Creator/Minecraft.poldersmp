package nl.poldersmp.cinematics;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Destructive fixture world preparation is permitted only inside a NEW marked localhost root. */
public final class CelestialProbe extends JavaPlugin {
  private JavaPlugin intro;
  private final Map<UUID, WorldBorder> expectedBorders = new HashMap<>();

  public void onEnable() {
    if (!Files.isRegularFile(Bukkit.getWorldContainer().toPath().resolve(".polder-disposable-test"))
        || !Bukkit.getIp().equals("127.0.0.1")
        || Bukkit.getPort() != 25580)
      throw new IllegalStateException("Disposable loopback 25580 marker required");
    intro = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PolderCinematics");
    if (Bukkit.getPluginManager().getPlugin("PolderLobby") != null
        || Bukkit.getPluginManager().getPlugin("PolderCore") != null)
      throw new IllegalStateException("Standalone fixture only");
  }

  private void check(boolean okay, String what) {
    if (!okay) throw new AssertionError(what);
  }

  private boolean playing(Player p) throws Exception {
    return Boolean.TRUE.equals(
        intro.getClass().getMethod("isPlaying", UUID.class).invoke(intro, p.getUniqueId()));
  }

  private Object field(Object target, String name) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private long owned(Player p) {
    return p.getWorld().getEntities().stream()
        .filter(
            e ->
                p.getUniqueId()
                    .toString()
                    .equals(
                        e.getPersistentDataContainer()
                            .get(
                                new NamespacedKey(intro, "cinematic_visual"),
                                org.bukkit.persistence.PersistentDataType.STRING)))
        .count();
  }

  private int count(Player p, Material type) {
    return Arrays.stream(p.getInventory().getContents())
        .filter(Objects::nonNull)
        .filter(i -> i.getType() == type)
        .mapToInt(ItemStack::getAmount)
        .sum();
  }

  private void intact(Player p) {
    check(count(p, Material.TOTEM_OF_UNDYING) == 2, "Real totems changed");
    check(count(p, Material.DIAMOND) == 7, "Diamonds changed");
    check(p.getHealth() == 20, "Health changed");
  }

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      Player p = (Player) sender;
      switch (args[0]) {
        case "setup" -> {
          p.addAttachment(this, "polder.intro.admin", true);
          World w = p.getWorld();
          w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
          w.setTime(1000);
          for (int x = -28; x <= 28; x++)
            for (int z = -28; z <= 28; z++)
              w.getBlockAt(x, 64, z).setType(Material.QUARTZ_BLOCK, false);
          w.setSpawnLocation(0, 65, 0);
          p.setGameMode(GameMode.SURVIVAL);
          p.setAllowFlight(false);
          p.setFlying(false);
          p.setHealth(20);
          p.setFoodLevel(20);
          p.teleport(new Location(w, .5, 65, .5, 90, -1));
          if (!p.getInventory().contains(Material.TOTEM_OF_UNDYING))
            p.getInventory()
                .addItem(
                    new ItemStack(Material.TOTEM_OF_UNDYING, 2),
                    new ItemStack(Material.DIAMOND, 7));
          w.getWorldBorder().setCenter(0, 0);
          w.getWorldBorder().setSize(80);
          WorldBorder personal = Bukkit.createWorldBorder();
          personal.setCenter(.5, .5);
          personal.setSize(70);
          p.setWorldBorder(personal);
          expectedBorders.put(p.getUniqueId(), personal);
          p.performCommand("joinintro setup replace");
          p.performCommand("joinintro duration 5");
          sender.sendMessage("CELESTIAL SETUP PASS");
        }
        case "camera" -> {
          check(playing(p), "Not active");
          check(p.getGameMode() == GameMode.SPECTATOR, "Not spectator");
          check(p.getSpectatorTarget() instanceof ItemDisplay, "Smooth camera not attached");
          check(
              ((ItemDisplay) p.getSpectatorTarget()).getTeleportDuration() == 2,
              "Interpolation not configured");
          check(owned(p) == 1, "Unexpected camera assets");
          check(
              p.getWorldBorder() != null && p.getWorldBorder().getSize() == 10000,
              "Blue border not hidden for viewer");
          check(p.getWorld().getWorldBorder().getSize() == 80, "Real world border changed");
          intact(p);
          sender.sendMessage("SMOOTH CAMERA ATTACHED PASS");
        }
        case "landing" -> {
          check(playing(p), "Landing lock missing");
          check(p.getGameMode() == GameMode.SURVIVAL, "Landing mode not restored");
          check(
              p.getLocation().distanceSquared(new Location(p.getWorld(), .5, 65, .5)) < 1,
              "Landing position wrong");
          check(owned(p) == 6, "Expected four orbit items and two runes, got " + owned(p));
          intact(p);
          double hp = p.getHealth();
          p.damage(4);
          check(p.getHealth() == hp, "Controlled landing took damage");
          sender.sendMessage("ORBIT RUNE SAFE LANDING PASS");
        }
        case "done" -> {
          check(!playing(p), "Still active");
          check(p.getGameMode() == GameMode.SURVIVAL, "Mode not restored");
          check(!p.getAllowFlight() && !p.isFlying(), "Flight not restored");
          check(owned(p) == 0, "Visual entities leaked");
          check(
              p.getWorldBorder() == expectedBorders.get(p.getUniqueId())
                  && p.getWorldBorder().getSize() == 70,
              "Original personal border not restored");
          check(p.getWorld().getWorldBorder().getSize() == 80, "Global world border changed");
          intact(p);
          Object store = field(intro, "store");
          var future =
              (CompletableFuture<?>)
                  store.getClass().getMethod("pending", UUID.class).invoke(store, p.getUniqueId());
          future.whenComplete(
              (record, error) -> {
                if (isEnabled())
                  Bukkit.getScheduler()
                      .runTask(
                          this,
                          () -> {
                            try {
                              check(error == null && record == null, "Journal not cleared");
                              sender.sendMessage(
                                  "STATE INVENTORY HEALTH VISUAL JOURNAL CLEANUP PASS");
                            } catch (Throwable e) {
                              sender.sendMessage("PROBE FAIL " + e);
                            }
                          });
              });
        }
        case "flooroff" -> {
          p.getWorld().getBlockAt(0, 64, 0).setType(Material.AIR, false);
          sender.sendMessage("UNSAFE LANDING TEST READY");
        }
        case "flooron" -> {
          p.getWorld().getBlockAt(0, 64, 0).setType(Material.QUARTZ_BLOCK, false);
          sender.sendMessage("SAFE LANDING REPAIRED");
        }
        case "pending" -> {
          check(
              playing(p) && p.getGameMode() == GameMode.SPECTATOR,
              "Unsafe landing released player");
          sender.sendMessage("UNSAFE LANDING FAIL-CLOSED PASS");
        }
        case "grant" -> {
          p.addAttachment(this, "polder.intro.admin", true);
          WorldBorder personal = Bukkit.createWorldBorder();
          personal.setCenter(.5, .5);
          personal.setSize(70);
          p.setWorldBorder(personal);
          expectedBorders.put(p.getUniqueId(), personal);
          sender.sendMessage("INTRO ADMIN READY");
        }
        default -> throw new IllegalArgumentException("Unknown probe command");
      }
    } catch (Throwable error) {
      sender.sendMessage("PROBE FAIL " + error);
      getLogger().severe(error.toString());
    }
    return true;
  }
}
