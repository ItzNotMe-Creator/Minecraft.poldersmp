package nl.poldersmp.common.nether;

import java.util.Optional;

/**
 * Finds a safe standing spot in the Overworld for players who must leave the Nether.
 * Pure logic: the world is described by {@link Terrain}, so it is unit tested without a server.
 */
public final class SafeSpotFinder {

  private SafeSpotFinder() {}

  public interface Terrain {
    /** Y of the highest solid block in this column, or Integer.MIN_VALUE if the column is empty. */
    int surfaceY(int x, int z);

    boolean isPassable(int x, int y, int z);

    boolean isHazard(int x, int y, int z);

    boolean isSolid(int x, int y, int z);

    boolean insideBorder(double x, double z);

    int minY();

    int maxY();
  }

  public record Spot(int x, int y, int z) {}

  /** Searches outward in square rings around (centerX, centerZ) up to {@code maxRadius} blocks. */
  public static Optional<Spot> find(Terrain terrain, int centerX, int centerZ, int maxRadius) {
    for (int radius = 0; radius <= maxRadius; radius++) {
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
            continue; // only the ring, inner squares were checked already
          }
          Optional<Spot> spot = check(terrain, centerX + dx, centerZ + dz);
          if (spot.isPresent()) {
            return spot;
          }
        }
      }
    }
    return Optional.empty();
  }

  static Optional<Spot> check(Terrain t, int x, int z) {
    if (!t.insideBorder(x + 0.5, z + 0.5)) {
      return Optional.empty();
    }
    int floor = t.surfaceY(x, z);
    if (floor == Integer.MIN_VALUE) {
      return Optional.empty();
    }
    int feet = floor + 1;
    if (floor <= t.minY() || feet + 1 >= t.maxY()) {
      return Optional.empty();
    }
    boolean ok =
        t.isSolid(x, floor, z)
            && !t.isHazard(x, floor, z)
            && t.isPassable(x, feet, z)
            && t.isPassable(x, feet + 1, z)
            && !t.isHazard(x, feet, z)
            && !t.isHazard(x, feet + 1, z);
    return ok ? Optional.of(new Spot(x, feet, z)) : Optional.empty();
  }
}
