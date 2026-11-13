package nl.poldersmp.cinematics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ArrivalTest {
  private ShowSettings load(YamlConfiguration y) {
    return ShowSettings.load(
        y,
        m ->
            java.util.Set.of(
                    org.bukkit.Material.AMETHYST_SHARD,
                    org.bukkit.Material.BLAZE_ROD,
                    org.bukkit.Material.ECHO_SHARD)
                .contains(m));
  }

  private YamlConfiguration defaults() throws Exception {
    var y = new YamlConfiguration();
    try (var in = getClass().getResourceAsStream("/effects.yml")) {
      assertNotNull(in);
      y.load(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }
    return y;
  }

  @Test
  void lagCatchesUpAllCuesExactlyOnce() {
    var timeline = new ArrivalTimeline(4);
    assertEquals(List.of(ArrivalTimeline.Cue.AWAKEN), timeline.advance(0));
    assertEquals(
        List.of(ArrivalTimeline.Cue.ORBIT, ArrivalTimeline.Cue.CONVERGE, ArrivalTimeline.Cue.TOTEM),
        timeline.advance(2.4));
    assertTrue(timeline.advance(1).isEmpty());
    assertEquals(List.of(ArrivalTimeline.Cue.AFTERGLOW), timeline.advance(20));
    assertTrue(timeline.complete());
    assertTrue(timeline.advance(21).isEmpty());
    assertEquals(1, timeline.progress());
  }

  @Test
  void timelinesAreIndependentAndRejectInvalidTiming() {
    var a = new ArrivalTimeline(2);
    var b = new ArrivalTimeline(4);
    a.advance(2);
    assertTrue(a.complete());
    assertEquals(0, b.progress());
    assertEquals(5, b.advance(5).size());
    assertThrows(IllegalArgumentException.class, () -> new ArrivalTimeline(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new ArrivalTimeline(11));
    assertThrows(IllegalArgumentException.class, () -> b.advance(Double.POSITIVE_INFINITY));
  }

  @Test
  void budgetCountsBurstQuantityAndFanoutWithoutOverflow() {
    var budget = new EffectBudget(4, 10);
    assertTrue(budget.take(7));
    assertFalse(budget.take(Integer.MAX_VALUE));
    assertFalse(budget.take(4));
    assertTrue(budget.take(1));
    assertTrue(budget.take(1));
    assertTrue(budget.take(1));
    assertFalse(budget.take(1));
    assertEquals(10, budget.particles());
    budget.reset();
    assertEquals(4, budget.remainingPackets());
    assertEquals(0, budget.particles());
    assertFalse(budget.take(0));
  }

  @Test
  void packetCapAlsoStopsManySmallParticles() {
    var budget = new EffectBudget(2, 100);
    assertTrue(budget.take(1));
    assertTrue(budget.take(1));
    assertFalse(budget.take(1));
    assertEquals(2, budget.particles());
    assertThrows(IllegalArgumentException.class, () -> new EffectBudget(100, 10));
  }

  @Test
  void ringsAreClosedUniformAndStayWithinTheirRadius() {
    for (int count : List.of(12, 20, 28, 96)) {
      var ring = ArrivalGeometry.ring(2.5, .15, count, .2);
      assertEquals(count, ring.size());
      assertEquals(count, new HashSet<>(ring).size());
      for (var p : ring) {
        assertEquals(2.5, Math.hypot(p.x(), p.z()), 1e-9);
        assertEquals(.15, p.y());
      }
      double chord =
          Math.hypot(ring.get(0).x() - ring.get(1).x(), ring.get(0).z() - ring.get(1).z());
      assertEquals(
          chord,
          Math.hypot(
              ring.getLast().x() - ring.getFirst().x(), ring.getLast().z() - ring.getFirst().z()),
          1e-9);
    }
    assertThrows(IllegalArgumentException.class, () -> ArrivalGeometry.ring(Double.NaN, 0, 12, 0));
    assertThrows(IllegalArgumentException.class, () -> ArrivalGeometry.ring(1, 0, 1000, 0));
  }

  @Test
  void generatedRoutesUseRealSpawnAndFinishAtExactOriginalOrientation() {
    for (float yaw : new float[] {-180, -90, 0, 90, 179}) {
      var spawn = new CameraPath.Point(1540.5, 120, -803.5, yaw, 12);
      var route = ArrivalGeometry.route(spawn, 16, 18);
      assertEquals(6, route.size());
      assertEquals(spawn, route.getLast());
      for (int i = 0; i <= 1200; i++) {
        var p = CameraPath.sample(route, i / 1200.0, "CATMULL_ROM", "SMOOTHSTEP");
        assertTrue(Math.hypot(p.x() - spawn.x(), p.z() - spawn.z()) < 32);
        assertTrue(p.y() >= spawn.y() - .5 && p.y() <= spawn.y() + 20);
        assertTrue(Float.isFinite(p.yaw()) && Float.isFinite(p.pitch()));
      }
    }
  }

  @Test
  void islandAvenueMatchesConfirmedSpawnAndApproachesWithoutOrbiting() {
    var spawn = new CameraPath.Point(0, 65, 0, 90, -1);
    var route = ArrivalGeometry.route(spawn, 16, 24, "ISLAND_AVENUE");
    assertEquals(spawn, route.getLast());
    double last = Double.POSITIVE_INFINITY;
    for (int i = 0; i <= 1200; i++) {
      var p = CameraPath.sample(route, i / 1200.0, "CATMULL_ROM", "SMOOTHSTEP");
      assertTrue(p.x() <= last + 1e-8, "Island approach doubled back");
      last = p.x();
      assertTrue(Math.abs(p.z()) < 7, "Camera left central avenue framing");
      assertTrue(p.y() >= 65 && p.y() <= 89.1);
      assertTrue(p.yaw() > 65 && p.yaw() < 120);
    }
    assertEquals(90, route.getLast().yaw());
    assertEquals(-1, route.getLast().pitch());
  }

  @Test
  void routeRotationAndValidation() {
    var a = ArrivalGeometry.route(new CameraPath.Point(0, 100, 0, 0, 0), 16, 18).getFirst();
    var b = ArrivalGeometry.route(new CameraPath.Point(0, 100, 0, 90, 0), 16, 18).getFirst();
    assertEquals(-a.z(), b.x(), 1e-6);
    assertEquals(a.x(), b.z(), 1e-6);
    assertThrows(IllegalArgumentException.class, () -> ArrivalGeometry.route(a, 0, 18));
    assertThrows(IllegalArgumentException.class, () -> ArrivalGeometry.route(a, 16, Double.NaN));
  }

  @Test
  void allPresetsLoadAndAreDifferent() throws Exception {
    var y = defaults();
    Set<Integer> colors = new HashSet<>();
    for (String theme : ShowSettings.THEMES) {
      y.set("theme", theme);
      var s = load(y);
      assertTrue(s.camera());
      assertTrue(s.overlay());
      assertEquals(4, s.duration());
      assertTrue(
          java.util.Set.of(
                  org.bukkit.Material.AMETHYST_SHARD,
                  org.bukkit.Material.BLAZE_ROD,
                  org.bukkit.Material.ECHO_SHARD)
              .contains(s.crystal()));
      colors.add(s.primary().asRGB());
    }
    assertEquals(3, colors.size());
  }

  @Test
  void unsafeConfigurationIsRejectedBeforeReload() throws Exception {
    for (var entry :
        Map.of(
                "landing.burst-particles",
                100000,
                "performance.maximum-packets-per-tick",
                999999,
                "smooth-camera.interpolation-ticks",
                0,
                "landing.duration-seconds",
                -1,
                "landing.viewer-radius",
                1000,
                "performance.maximum-sessions",
                1000)
            .entrySet()) {
      var y = defaults();
      y.set(entry.getKey(), entry.getValue());
      assertThrows(IllegalArgumentException.class, () -> load(y), entry.getKey());
    }
    var y = defaults();
    y.set("landing.orbit-items", "yes");
    assertThrows(IllegalArgumentException.class, () -> load(y));
  }

  @Test
  void defaultsCanUpgradeOldEffectsWithoutOverwritingCustomValues() throws Exception {
    var custom = new YamlConfiguration();
    custom.set("theme", "AURORA");
    custom.set("landing.totem-overlay", false);
    custom.setDefaults(defaults());
    var settings = load(custom);
    assertEquals("AURORA", settings.theme());
    assertFalse(settings.overlay());
    assertEquals(4, settings.duration());
    assertFalse(custom.saveToString().contains("maximum-packets-per-tick"));
  }

  @Test
  void invalidThemeColorAndMaterialAreRejected() throws Exception {
    for (var e :
        Map.of(
                "theme",
                "UNKNOWN",
                "themes.CELESTIAL.primary",
                "green",
                "themes.CELESTIAL.crystal",
                "AIR",
                "density",
                "EXTREME")
            .entrySet()) {
      var y = defaults();
      y.set(e.getKey(), e.getValue());
      assertThrows(IllegalArgumentException.class, () -> load(y));
    }
  }
}
