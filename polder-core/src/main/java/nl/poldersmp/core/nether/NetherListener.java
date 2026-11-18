package nl.poldersmp.core.nether;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Portal behaviour: closes the Nether during a reset and routes portals to the resource Nether. */
public final class NetherListener implements Listener {
  private final JavaPlugin plugin;
  private final NetherService service;

  public NetherListener(JavaPlugin plugin, NetherService service) {
    this.plugin = plugin;
    this.service = service;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPortal(PlayerPortalEvent event) {
    if (!service.active() || event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
      return;
    }
    Location from = event.getFrom();
    World fromWorld = from.getWorld();
    if (fromWorld.getEnvironment() == World.Environment.NORMAL) {
      if (service.accessLocked()) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(service.deniedMessage());
        return;
      }
      if (service.settings().blockPrimaryNether()) {
        World resource = service.resourceNether();
        if (resource == null) {
          event.setCancelled(true);
          event.getPlayer().sendMessage(service.deniedMessage());
          return;
        }
        double y = Math.max(resource.getMinHeight() + 1, Math.min(120, from.getY()));
        event.setTo(new Location(resource, from.getX() / 8.0, y, from.getZ() / 8.0, from.getYaw(), from.getPitch()));
        event.setCanCreatePortal(true);
        event.setSearchRadius(128);
        event.setCreationRadius(16);
      }
    } else if (fromWorld.getEnvironment() == World.Environment.NETHER) {
      World overworld = service.overworld();
      if (overworld != null) {
        event.setTo(new Location(overworld, from.getX() * 8.0, from.getY(), from.getZ() * 8.0, from.getYaw(), from.getPitch()));
        event.setCanCreatePortal(true);
        event.setSearchRadius(128);
        event.setCreationRadius(16);
      }
    }
  }

  /** Items, minecarts and mobs must not slip into a closed or unused Nether either. */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityPortal(EntityPortalEvent event) {
    if (!service.active() || event.getTo() == null) {
      return;
    }
    World target = event.getTo().getWorld();
    if (target != null && target.getEnvironment() == World.Environment.NETHER && (service.accessLocked() || service.isPrimaryNether(target) && service.settings().blockPrimaryNether())) {
      event.setCancelled(true);
    }
  }

  /** Someone who logs in inside a closed (or unused vanilla) Nether is moved to the Overworld. */
  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    if (!service.active()) {
      return;
    }
    Player player = event.getPlayer();
    World world = player.getWorld();
    if (world.getEnvironment() != World.Environment.NETHER) {
      return;
    }
    boolean mustLeave = service.accessLocked() || service.isPrimaryNether(world) && service.settings().blockPrimaryNether();
    if (mustLeave) {
      plugin.getServer().getScheduler().runTask(plugin, () -> {
        if (player.isOnline()) {
          service.sendToSafety(player);
        }
      });
    }
  }
}
