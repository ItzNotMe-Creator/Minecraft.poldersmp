package nl.poldersmp.core.loot;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** Claim/region adapters must approve the full buffer, not just the center block. */
public interface VaultProtection {
  boolean wilderness(World world, BoundingBox footprint);

  default boolean canClaim(Player player, Location location) {
    return true;
  }
}
