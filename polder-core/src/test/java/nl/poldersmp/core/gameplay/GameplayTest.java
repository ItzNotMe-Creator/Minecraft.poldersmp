package nl.poldersmp.core.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.inventory.meta.trim.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GameplayTest {
  @TempDir Path temp;

  @Test
  void activationBoundaries() {
    assertFalse(Rules.chance(0, 0));
    assertTrue(Rules.chance(.15, .149));
    assertFalse(Rules.chance(.15, .15));
    assertTrue(Rules.chance(1, .999));
  }

  @Test
  void cooldownBoundaryAndIndependentPlayers() {
    var c = new Rules.Cooldowns();
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    assertTrue(c.acquire(a, "blade", 100, 15000));
    assertFalse(c.acquire(a, "blade", 15099, 15000));
    assertTrue(c.acquire(a, "blade", 15100, 15000));
    assertTrue(c.acquire(b, "blade", 100, 15000));
    assertTrue(c.acquire(a, "dagger", 100, 20000));
  }

  @Test
  void mixedScalingAndSets() {
    var counts = Rules.counts(List.of("snout", "snout", "tide", "wild"));
    assertEquals(.5, Rules.scale(counts.get("snout")));
    assertTrue(Rules.set(counts, "snout", 2));
    assertFalse(Rules.set(counts, "snout", 4));
    assertEquals(.25, Rules.scale(counts.get("tide")));
    assertEquals(1, Rules.scale(10));
  }

  @Test
  void identifiesOnlyPersistentValidSingleWeapon() {
    NamespacedKey type = NamespacedKey.minecraft("weapon"),
        serial = NamespacedKey.minecraft("serial");
    ItemStack item = mock(ItemStack.class);
    ItemMeta meta = mock(ItemMeta.class);
    PersistentDataContainer pdc = mock(PersistentDataContainer.class);
    when(item.hasItemMeta()).thenReturn(true);
    when(item.getItemMeta()).thenReturn(meta);
    when(meta.getPersistentDataContainer()).thenReturn(pdc);
    when(item.getAmount()).thenReturn(1);
    when(item.getType()).thenReturn(Material.NETHERITE_SWORD);
    when(pdc.get(type, PersistentDataType.STRING)).thenReturn("LIGHTNING_BLADE");
    when(pdc.get(serial, PersistentDataType.STRING)).thenReturn(UUID.randomUUID().toString());
    assertEquals(Weapons.Kind.LIGHTNING_BLADE, Weapons.identify(item, type, serial));
    when(item.getAmount()).thenReturn(2);
    assertNull(Weapons.identify(item, type, serial));
    when(item.getAmount()).thenReturn(1);
    when(pdc.get(serial, PersistentDataType.STRING)).thenReturn("forged");
    assertNull(Weapons.identify(item, type, serial));
    assertNull(Weapons.identify(null, type, serial));
  }

  @Test
  void ignoresUntrimmedItems() {
    ItemStack item = mock(ItemStack.class);
    ArmorMeta meta = mock(ArmorMeta.class);
    when(item.getItemMeta()).thenReturn(meta);
    when(meta.hasTrim()).thenReturn(false);
    assertTrue(Armor.detect(new ItemStack[] {null, item}).isEmpty());
  }

  @Test
  void loadsBundledConfigurations() throws Exception {
    for (String name : List.of("weapons.yml", "armor.yml")) {
      try (var in = getClass().getResourceAsStream("/" + name)) {
        assertNotNull(in);
        var config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in));
        assertTrue(config.getBoolean("enabled"));
        if (name.equals("weapons.yml"))
          for (Weapons.Kind k : Weapons.Kind.values())
            assertTrue(config.getDouble("weapons." + k + ".cooldown") > 0);
        else assertEquals(.25, config.getDouble("scaling.1"));
      }
    }
  }

  @Test
  void discoveriesPersistAndAreIdempotent() {
    UUID player = UUID.randomUUID();
    Path file = temp.resolve("armor.db");
    try (var store = new Discoveries(file)) {
      store.initialize().join();
      store.discover(player, "snout").join();
      store.discover(player, "snout").join();
      assertEquals(Set.of("snout"), store.collection(player).join());
    }
    try (var store = new Discoveries(file)) {
      store.initialize().join();
      assertEquals(Set.of("snout"), store.collection(player).join());
      assertEquals(1, store.top().join().size());
    }
  }
}
