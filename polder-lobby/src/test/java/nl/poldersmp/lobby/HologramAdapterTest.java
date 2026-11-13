package nl.poldersmp.lobby;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.bukkit.Location;
import org.junit.jupiter.api.*;

class HologramAdapterTest {
  static final Map<String, Hologram> registry = new HashMap<>();

  public static final class Hologram {
    List<String> lines;

    Hologram(List<String> lines) {
      this.lines = List.copyOf(lines);
    }
  }

  public static final class Api {
    public static Hologram getHologram(String name) {
      return registry.get(name);
    }

    public static Hologram createHologram(
        String name, Location location, boolean save, List<String> lines) {
      if (registry.containsKey(name)) throw new IllegalStateException("Already exists");
      Hologram h = new Hologram(lines);
      registry.put(name, h);
      return h;
    }

    public static void setHologramLines(Hologram h, List<String> lines) {
      h.lines = List.copyOf(lines);
    }

    public static void removeHologram(String name) {
      registry.remove(name);
    }
  }

  public static final class NoSetterApi {
    public static Hologram getHologram(String name) {
      return Api.getHologram(name);
    }

    public static Hologram createHologram(
        String name, Location location, boolean save, List<String> lines) {
      return Api.createHologram(name, location, save, lines);
    }

    public static void removeHologram(String name) {
      Api.removeHologram(name);
    }
  }

  @BeforeEach
  void clear() {
    registry.clear();
  }

  @Test
  void existingHologramIsNeitherUpdatedNorDeleted() throws Exception {
    Hologram prior = new Hologram(List.of("Existing staff board"));
    registry.put("polderlobby_status", prior);
    try (var adapter = new HologramAdapter(Api.class, s -> fail(s))) {
      assertFalse(adapter.render("polderlobby_status", null, List.of("Replacement")));
      assertEquals(List.of("Existing staff board"), prior.lines);
    }
    assertSame(prior, registry.get("polderlobby_status"));
  }

  @Test
  void onlyConfirmedCreatedInstanceIsUpdatedAndRemoved() throws Exception {
    try (var adapter = new HologramAdapter(Api.class, s -> fail(s))) {
      assertTrue(adapter.render("ours", null, List.of("Online 1")));
      Hologram created = registry.get("ours");
      assertTrue(adapter.render("ours", null, List.of("Online 2")));
      assertSame(created, registry.get("ours"));
      assertEquals(List.of("Online 2"), created.lines);
    }
    assertFalse(registry.containsKey("ours"));
  }

  @Test
  void replacementByAnotherPluginSurvivesCleanup() throws Exception {
    var adapter = new HologramAdapter(Api.class, s -> fail(s));
    adapter.render("ours", null, List.of("Original"));
    adapter.render("ours2", null, List.of("Another original"));
    Hologram replacement = new Hologram(List.of("Another plugin"));
    registry.put("ours", replacement);
    Hologram untouchedReplacement = new Hologram(List.of("Replacement before close"));
    registry.put("ours2", untouchedReplacement);
    assertFalse(adapter.render("ours", null, List.of("Do not overwrite")));
    adapter.close();
    assertSame(replacement, registry.get("ours"));
    assertSame(untouchedReplacement, registry.get("ours2"));
    assertEquals(List.of("Another plugin"), replacement.lines);
  }

  @Test
  void missingUpdateApiFailsExplicitlyAndCleansOnlyOwnedObjects() throws Exception {
    Hologram other = new Hologram(List.of("Keep"));
    registry.put("other", other);
    var adapter = new HologramAdapter(NoSetterApi.class, s -> fail(s));
    assertTrue(adapter.render("ours", null, List.of("New")));
    assertThrows(
        NoSuchMethodException.class, () -> adapter.render("ours", null, List.of("Updated")));
    adapter.close();
    assertEquals(Map.of("other", other), registry);
  }
}
