package nl.poldersmp.common.nether;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Player evacuation target choice and post-reset verification. */
class EvacuationVerificationTest {

  /** Flat grass world, floor y=63, with holes the tests can add. */
  static final class Flat implements SafeSpotFinder.Terrain {
    final Set<String> lava = new HashSet<>();
    final Set<String> noSurface = new HashSet<>();
    final Set<String> blockedHead = new HashSet<>();
    double borderRadius = 1000;

    static String k(int x, int z) { return x + "," + z; }

    @Override public int surfaceY(int x, int z) { return noSurface.contains(k(x, z)) ? Integer.MIN_VALUE : 63; }
    @Override public boolean isPassable(int x, int y, int z) { return y > 63 && !(y == 65 && blockedHead.contains(k(x, z))); }
    @Override public boolean isHazard(int x, int y, int z) { return lava.contains(k(x, z)) && y <= 64; }
    @Override public boolean isSolid(int x, int y, int z) { return y <= 63; }
    @Override public boolean insideBorder(double x, double z) { return Math.abs(x) < borderRadius && Math.abs(z) < borderRadius; }
    @Override public int minY() { return -64; }
    @Override public int maxY() { return 320; }
  }

  @Test
  void usesTheSpawnColumnWhenItIsSafe() {
    Optional<SafeSpotFinder.Spot> spot = SafeSpotFinder.find(new Flat(), 10, 20, 24);
    assertEquals(new SafeSpotFinder.Spot(10, 64, 20), spot.orElseThrow());
  }

  @Test
  void skipsLavaAndUnsafeColumnsAndFindsTheNearestSafeOne() {
    Flat world = new Flat();
    world.lava.add(Flat.k(0, 0));
    world.blockedHead.add(Flat.k(1, 0));
    world.noSurface.add(Flat.k(0, 1));
    SafeSpotFinder.Spot spot = SafeSpotFinder.find(world, 0, 0, 24).orElseThrow();
    assertFalse(world.lava.contains(Flat.k(spot.x(), spot.z())));
    assertTrue(Math.max(Math.abs(spot.x()), Math.abs(spot.z())) == 1, "nearest ring that works");
    assertEquals(64, spot.y());
  }

  @Test
  void neverPicksASpotOutsideTheWorldBorder() {
    Flat world = new Flat();
    world.borderRadius = 5;
    SafeSpotFinder.Spot spot = SafeSpotFinder.find(world, 4, 4, 24).orElseThrow();
    assertTrue(Math.abs(spot.x() + 0.5) < 5 && Math.abs(spot.z() + 0.5) < 5);
  }

  @Test
  void givesUpCleanlyWhenNothingIsSafeNearby() {
    Flat world = new Flat();
    for (int x = -3; x <= 3; x++) {
      for (int z = -3; z <= 3; z++) {
        world.lava.add(Flat.k(x, z));
      }
    }
    assertTrue(SafeSpotFinder.find(world, 0, 0, 3).isEmpty());
  }

  // ---- post-reset verification ----

  PostResetVerifier.Facts good() {
    return new PostResetVerifier.Facts(true, true, 222L, 222L, 111L, true, true, true);
  }

  @Test
  void acceptsARealNewNether() {
    assertEquals(0, PostResetVerifier.verify(good(), true).size());
  }

  @Test
  void rejectsASeedThatIsNotTheOneThatWasPrepared() {
    var f = new PostResetVerifier.Facts(true, true, 999L, 222L, 111L, true, true, true);
    assertTrue(PostResetVerifier.verify(f, true).get(0).contains("not the prepared seed"));
  }

  @Test
  void rejectsASeedIdenticalToTheOldNether() {
    var f = new PostResetVerifier.Facts(true, true, 111L, 111L, 111L, true, true, true);
    assertTrue(PostResetVerifier.verify(f, true).stream().anyMatch(s -> s.contains("same seed")));
    assertTrue(PostResetVerifier.verify(f, false).isEmpty(), "only when requireNewSeed is on");
  }

  @Test
  void rejectsMissingWorldWrongEnvironmentOrMissingPreparation() {
    assertFalse(PostResetVerifier.verify(new PostResetVerifier.Facts(false, false, 0, 1L, null, true, true, true), true).isEmpty());
    assertFalse(PostResetVerifier.verify(new PostResetVerifier.Facts(true, false, 222L, 222L, 111L, true, true, true), true).isEmpty());
    assertFalse(PostResetVerifier.verify(new PostResetVerifier.Facts(true, true, 222L, null, 111L, true, true, true), true).isEmpty());
  }

  @Test
  void rejectsWhenTheWrongFolderWasUsedOrOtherDimensionsAreMissing() {
    assertFalse(PostResetVerifier.verify(new PostResetVerifier.Facts(true, true, 222L, 222L, 111L, false, true, true), true).isEmpty());
    assertFalse(PostResetVerifier.verify(new PostResetVerifier.Facts(true, true, 222L, 222L, 111L, true, false, true), true).isEmpty());
    assertFalse(PostResetVerifier.verify(new PostResetVerifier.Facts(true, true, 222L, 222L, 111L, true, true, false), true).isEmpty());
  }
}
