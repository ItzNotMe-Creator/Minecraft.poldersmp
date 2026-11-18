package nl.poldersmp.core.nether;

import java.util.Set;
import nl.poldersmp.common.nether.SafeSpotFinder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Lets the pure {@link SafeSpotFinder} look at a real Bukkit world. Main thread only. */
final class BukkitTerrain implements SafeSpotFinder.Terrain {
  private static final Set<Material> HAZARDS =
      Set.of(
          Material.LAVA,
          Material.WATER,
          Material.FIRE,
          Material.SOUL_FIRE,
          Material.MAGMA_BLOCK,
          Material.CAMPFIRE,
          Material.SOUL_CAMPFIRE,
          Material.CACTUS,
          Material.SWEET_BERRY_BUSH,
          Material.WITHER_ROSE,
          Material.POWDER_SNOW,
          Material.COBWEB);

  private final World world;

  BukkitTerrain(World world) {
    this.world = world;
  }

  @Override
  public int surfaceY(int x, int z) {
    int y = world.getHighestBlockYAt(x, z);
    if (y <= world.getMinHeight()) {
      return Integer.MIN_VALUE;
    }
    return world.getBlockAt(x, y, z).getType().isSolid() ? y : Integer.MIN_VALUE;
  }

  @Override
  public boolean isPassable(int x, int y, int z) {
    Block block = world.getBlockAt(x, y, z);
    return block.isPassable() && !block.isLiquid();
  }

  @Override
  public boolean isHazard(int x, int y, int z) {
    return HAZARDS.contains(world.getBlockAt(x, y, z).getType());
  }

  @Override
  public boolean isSolid(int x, int y, int z) {
    return world.getBlockAt(x, y, z).getType().isSolid();
  }

  @Override
  public boolean insideBorder(double x, double z) {
    return world.getWorldBorder().isInside(new Location(world, x, 64, z));
  }

  @Override
  public int minY() {
    return world.getMinHeight();
  }

  @Override
  public int maxY() {
    return world.getMaxHeight();
  }
}
