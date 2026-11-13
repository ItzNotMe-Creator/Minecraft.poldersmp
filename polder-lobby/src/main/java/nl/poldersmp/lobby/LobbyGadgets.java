package nl.poldersmp.lobby;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;

/**
 * Visual/movement only. No projectile entities, damage, block mutation or inventory replacements.
 */
final class LobbyGadgets implements Listener {
  private final PolderLobby p;
  private final Map<UUID, String> selected = new HashMap<>();
  private final Map<UUID, Long> cooldown = new HashMap<>();
  private final Set<UUID> flightOwned = new HashSet<>();

  LobbyGadgets(PolderLobby p) {
    this.p = p;
    Bukkit.getPluginManager().registerEvents(this, p);
    Bukkit.getScheduler()
        .runTaskTimer(
            p,
            () -> {
              for (Player player : Bukkit.getOnlinePlayers()) {
                UUID id = player.getUniqueId();
                if (!eligible(player) || !p.cfg.getBoolean("gadgets.double-jump")) {
                  restore(player);
                  continue;
                }
                if (player.isOnGround() && !player.getAllowFlight() && ready(id)) {
                  player.setAllowFlight(true);
                  flightOwned.add(id);
                }
              }
            },
            2,
            2);
  }

  private boolean eligible(Player player) {
    return p.module("gadgets")
        && player.hasPermission("polder.lobby.gadgets")
        && p.lobbyWorld(player.getWorld())
        && !p.busy(player)
        && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
        && !player.isInsideVehicle()
        && !player.isGliding();
  }

  private boolean ready(UUID id) {
    return System.nanoTime() >= cooldown.getOrDefault(id, 0L);
  }

  private boolean trigger(Player player) {
    if (!eligible(player) || !ready(player.getUniqueId())) return false;
    cooldown.put(
        player.getUniqueId(),
        System.nanoTime()
            + Math.max(1, Math.min(120, p.cfg.getInt("gadgets.cooldown-seconds"))) * 1000000000L);
    return true;
  }

  private void restore(Player player) {
    if (flightOwned.remove(player.getUniqueId())
        && player.getGameMode() != GameMode.CREATIVE
        && player.getGameMode() != GameMode.SPECTATOR) {
      player.setFlying(false);
      player.setAllowFlight(false);
    }
  }

  void command(Player player, String[] a) {
    if (a.length == 0) {
      player.sendMessage(
          "/gadgets grapple | blaster | staff | off • sneak-right-click empty hand to use."
              + " Double-jump with Space.");
      return;
    }
    if (!List.of("grapple", "blaster", "staff", "off").contains(a[0]))
      throw new IllegalArgumentException("Unknown gadget");
    selected.put(player.getUniqueId(), a[0]);
    player.sendMessage("Selected gadget: " + a[0]);
  }

  @EventHandler(ignoreCancelled = true)
  public void fly(PlayerToggleFlightEvent e) {
    Player player = e.getPlayer();
    if (!flightOwned.contains(player.getUniqueId())) return;
    e.setCancelled(true);
    restore(player);
    if (trigger(player)) {
      Vector v = player.getLocation().getDirection().setY(.8).normalize().multiply(.9);
      player.setVelocity(v);
      effect(player, Particle.CLOUD);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    Player player = e.getPlayer();
    if (!e.getAction().isRightClick() || !player.isSneaking() || e.getItem() != null) return;
    String gadget = selected.getOrDefault(player.getUniqueId(), "off");
    if (gadget.equals("off")
        || !p.cfg.getBoolean("gadgets." + (gadget.equals("grapple") ? "grappling" : gadget))
        || !trigger(player)) return;
    e.setCancelled(true);
    switch (gadget) {
      case "grapple" -> {
        var hit =
            player
                .getWorld()
                .rayTraceBlocks(
                    player.getEyeLocation(),
                    player.getEyeLocation().getDirection(),
                    12,
                    FluidCollisionMode.NEVER,
                    true);
        if (hit != null) {
          Vector direction =
              hit.getHitPosition()
                  .subtract(player.getLocation().toVector())
                  .normalize()
                  .multiply(1.15);
          direction.setY(Math.min(.9, Math.max(.3, direction.getY())));
          player.setVelocity(direction);
        }
        effect(player, Particle.END_ROD);
      }
      case "blaster" -> beam(player, Particle.FIREWORK);
      case "staff" -> {
        beam(player, Particle.ENCHANT);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .5f, 1.5f);
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    Player player = e.getPlayer();
    if (!e.hasChangedBlock() || !p.cfg.getBoolean("gadgets.launch-pads")) return;
    Material material =
        Material.matchMaterial(
            p.cfg.getString("gadgets.launch-material", "LIGHT_WEIGHTED_PRESSURE_PLATE"));
    if (e.getTo().getBlock().getType() == material && trigger(player)) {
      player.setVelocity(player.getLocation().getDirection().setY(.8).normalize().multiply(1.1));
      effect(player, Particle.CLOUD);
    }
  }

  private void effect(Player player, Particle particle) {
    if (p.cfg.getBoolean("effects.particles", true))
      player.getWorld().spawnParticle(particle, player.getLocation(), 15, .2, .2, .2, .03);
    if (p.cfg.getBoolean("effects.sounds", true))
      player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, .4f, 1.4f);
  }

  private void beam(Player player, Particle particle) {
    if (!p.cfg.getBoolean("effects.particles", true)) return;
    Location start = player.getEyeLocation();
    Vector direction = start.getDirection();
    for (int i = 1; i <= 16; i++) {
      Location point = start.clone().add(direction.clone().multiply(i * .4));
      if (!point.getBlock().isPassable()) break;
      player.getWorld().spawnParticle(particle, point, 1, 0, 0, 0, 0);
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    restore(e.getPlayer());
    selected.remove(e.getPlayer().getUniqueId());
    cooldown.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    restore(e.getPlayer());
  }

  void suspend(Player player) {
    restore(player);
  }

  void cleanup() {
    for (Player player : Bukkit.getOnlinePlayers()) restore(player);
  }
}
