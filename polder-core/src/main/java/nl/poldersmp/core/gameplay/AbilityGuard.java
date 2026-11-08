package nl.poldersmp.core.gameplay;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Optional Bukkit ServicesManager integration. Called only on the Paper server thread. */
public interface AbilityGuard {
  boolean allowMovement(Player player, Location from, Location to);

  boolean allowDamage(Player player, LivingEntity target);
}
