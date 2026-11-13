package nl.poldersmp.lobby;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LobbyConfigurationTest {
  private YamlConfiguration config(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/" + name)) {
      assertNotNull(input);
      var config = new YamlConfiguration();
      config.load(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
      return config;
    }
  }

  @Test
  void defaultsNeedActualCoordinatesAndSeparateTwelveModules() throws Exception {
    var y = config("lobby.yml");
    assertTrue(y.getConfigurationSection("spawn").getKeys(false).isEmpty());
    assertEquals(12, y.getConfigurationSection("modules").getKeys(false).size());
    for (String module : y.getConfigurationSection("modules").getKeys(false))
      assertTrue(y.getBoolean("modules." + module));
    assertFalse(y.getBoolean("events.automatic"));
    assertTrue(config("parkour.yml").getConfigurationSection("courses").getKeys(false).isEmpty());
    var locations = config("locations.yml");
    for (String kind : locations.getKeys(false))
      assertTrue(locations.getConfigurationSection(kind).getKeys(false).isEmpty());
  }

  @Test
  void catalogHasAllVisualCategoriesWithoutCurrencyAdapter() throws Exception {
    var y = config("lobby.yml");
    var categories = new java.util.HashSet<String>();
    for (String id : y.getConfigurationSection("cosmetics.catalog").getKeys(false)) {
      String base = "cosmetics.catalog." + id;
      categories.add(y.getString(base + ".category"));
      assertTrue(y.getLong(base + ".price") >= 0);
    }
    assertEquals(java.util.Set.of("trail", "hat", "wings", "pet", "join", "title"), categories);
    assertEquals("Europe/Amsterdam", y.getString("daily.timezone"));
    assertFalse(y.contains("vault"));
  }
}
