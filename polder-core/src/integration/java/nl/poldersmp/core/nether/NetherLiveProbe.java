package nl.poldersmp.core.nether;

import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable loopback server only. Does not ship in the production JAR. */
public final class NetherLiveProbe extends JavaPlugin {
  private Path root;

  @Override
  public void onEnable() {
    root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    if (!Files.isRegularFile(root.resolve(".polder-disposable-test")))
      throw new IllegalStateException("Disposable test marker required");
  }

  private Player player() {
    return Objects.requireNonNull(Bukkit.getPlayerExact("NetherProbe"));
  }

  @Override
  public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
    try {
      if (!Bukkit.getIp().equals("127.0.0.1"))
        throw new IllegalStateException("Loopback binding required");
      World w =
          Objects.requireNonNull(
              Bukkit.getWorld(args[0].equals("setup") ? "world" : "polder_nether"));
      switch (args[0]) {
        case "setup" -> {
          for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
              w.getBlockAt(x, 179, z).setType(Material.STONE, false);
              w.getBlockAt(x, 180, z).setType(Material.AIR, false);
              w.getBlockAt(x, 181, z).setType(Material.AIR, false);
            }
          w.save();
          s.sendMessage("NETHER SAFE SETUP PASS");
        }
        case "enter" -> {
          for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
              w.getBlockAt(x, 99, z).setType(Material.STONE, false);
              w.getBlockAt(x, 100, z).setType(Material.AIR, false);
              w.getBlockAt(x, 101, z).setType(Material.AIR, false);
            }
          w.getBlockAt(10, 100, 10).setType(Material.DIAMOND_BLOCK, false);
          player().setGameMode(GameMode.CREATIVE);
          player().teleport(new Location(w, .5, 100, .5));
          w.save();
          s.sendMessage("NETHER ENTER PASS " + w.getSeed());
        }
        case "evacuated" -> {
          if (player().getWorld().getEnvironment() != World.Environment.NORMAL)
            throw new AssertionError("Player not evacuated");
          s.sendMessage("NETHER EVACUATION PASS");
        }
        case "fresh" -> {
          if (w.getBlockAt(10, 100, 10).getType() == Material.DIAMOND_BLOCK)
            throw new AssertionError("Old placed block survived");
          s.sendMessage("NETHER REGENERATION PASS seed=" + w.getSeed());
        }
        case "physical" -> {
          World over = Bukkit.getWorld("world");
          for (int x = 2; x <= 5; x++)
            for (int y = 180; y <= 184; y++) {
              over.getBlockAt(x, y, 0)
                  .setType(
                      x == 2 || x == 5 || y == 180 || y == 184
                          ? Material.OBSIDIAN
                          : Material.NETHER_PORTAL,
                      false);
            }
          player().setGameMode(GameMode.CREATIVE);
          player().teleport(new Location(over, 3.5, 181, .5));
          s.sendMessage("NETHER PHYSICAL PORTAL READY");
        }
        case "where" -> s.sendMessage("NETHER LOCATION " + player().getWorld().getName());
        case "portal" -> {
          var from = new Location(Bukkit.getWorld("world"), 80, 80, 80);
          var e =
              new org.bukkit.event.player.PlayerPortalEvent(
                  player(),
                  from,
                  new Location(Bukkit.getWorld("world_nether"), 10, 80, 10),
                  org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
          Bukkit.getPluginManager().callEvent(e);
          if (e.isCancelled() || !e.getTo().getWorld().equals(w) || e.getTo().getX() != 10)
            throw new AssertionError("Portal routing failed");
          s.sendMessage("NETHER PORTAL ROUTING PASS");
        }
        default -> s.sendMessage("setup|enter|evacuated|fresh|portal");
      }
    } catch (Throwable e) {
      s.sendMessage("NETHER LIVE FAIL " + e);
      e.printStackTrace();
    }
    return true;
  }
}
