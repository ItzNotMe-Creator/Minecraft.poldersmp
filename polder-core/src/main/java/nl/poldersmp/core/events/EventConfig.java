package nl.poldersmp.core.events;

import java.io.*;
import java.time.ZoneId;
import java.util.*;
import nl.poldersmp.core.loot.LootRules;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record EventConfig(YamlConfiguration yaml) {
  public EventConfig {
    if (!yaml.isBoolean("enabled")) throw new IllegalArgumentException("enabled must be a boolean");
    for (var e : yaml.getValues(true).entrySet())
      if (e.getValue() instanceof Number n
          && (!Double.isFinite(n.doubleValue()) || n.doubleValue() < 0))
        throw new IllegalArgumentException("Invalid setting: " + e.getKey());
    ZoneId.of(yaml.getString("timezone"));
    if (yaml.getLong("interval-seconds") < 60
        || yaml.getLong("interval-seconds") > 31536000
        || yaml.getInt("search-attempts") < 1
        || yaml.getInt("search-attempts") > 20
        || yaml.getInt("spawn-min-radius") < 128
        || yaml.getInt("spawn-max-radius") < yaml.getInt("spawn-min-radius")
        || yaml.getInt("spawn-max-radius") > 100000
        || yaml.getDouble("arena-radius") < 3
        || yaml.getDouble("arena-radius") > 6
        || yaml.getInt("maximum-participants") < 1
        || yaml.getInt("maximum-participants") > 50
        || yaml.getLong("cleanup-delay-seconds") > 60)
      throw new IllegalArgumentException("Unsafe event limits");
    List<LootRules.Weighted<EventRules.Type>> enabled = new ArrayList<>();
    for (var type : EventRules.Type.values()) {
      String p = "types." + type + ".";
      if (!yaml.isBoolean(p + "enabled"))
        throw new IllegalArgumentException("Missing event toggle");
      if (yaml.getBoolean(p + "enabled"))
        enabled.add(new LootRules.Weighted<>(type, yaml.getLong(p + "weight")));
      if (yaml.getLong(p + "warning-seconds") > 600
          || yaml.getLong(p + "expiration-seconds") < 30
          || yaml.getLong(p + "expiration-seconds") > 86400)
        throw new IllegalArgumentException("Invalid event timing");
      LootRules.Tier.valueOf(yaml.getString(p + "reward-tier"));
      LootRules.Tier.valueOf(yaml.getString(p + "key-tier"));
      if (type == EventRules.Type.BOSS || type == EventRules.Type.DUNGEON) {
        if (!Set.of("ZOMBIE", "HUSK", "SKELETON", "STRAY", "VINDICATOR", "PIGLIN_BRUTE")
                .contains(yaml.getString(p + "mob"))
            || yaml.getDouble(p + "health") < 1
            || yaml.getDouble(p + "health") > 2000
            || yaml.getDouble(p + "special-damage") > 20
            || yaml.getLong(p + "special-interval-seconds") < 3)
          throw new IllegalArgumentException("Unsupported/unsafe boss settings");
      }
      if (type == EventRules.Type.DUNGEON
          && (yaml.getInt(p + "waves") < 1
              || yaml.getInt(p + "waves") > 5
              || yaml.getInt(p + "mobs-per-wave") < 1
              || yaml.getInt(p + "mobs-per-wave") > 6))
        throw new IllegalArgumentException("Unsafe dungeon waves");
    }
    if (yaml.getBoolean("enabled")) LootRules.total(enabled);
    long budget =
        Math.addExact(
            Math.multiplyExact(
                yaml.getLong("economy.participation-money"), yaml.getInt("maximum-participants")),
            Math.multiplyExact(yaml.getLong("economy.winner-money"), 2));
    if (budget > 0) UUID.fromString(yaml.getString("economy.treasury", ""));
  }

  public static EventConfig load(JavaPlugin plugin) {
    File file = new File(plugin.getDataFolder(), "events.yml");
    if (!file.exists()) plugin.saveResource("events.yml", false);
    try {
      YamlConfiguration y = new YamlConfiguration();
      y.load(file);
      return new EventConfig(y);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid events.yml", e);
    }
  }

  public static EventConfig restore(String text) {
    try {
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(text);
      return new EventConfig(y);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid saved event settings", e);
    }
  }

  public List<LootRules.Weighted<EventRules.Type>> choices() {
    List<LootRules.Weighted<EventRules.Type>> out = new ArrayList<>();
    for (var type : EventRules.Type.values())
      if (yaml.getBoolean("types." + type + ".enabled"))
        out.add(new LootRules.Weighted<>(type, yaml.getLong("types." + type + ".weight")));
    return out;
  }

  public long number(EventRules.Type type, String key) {
    return yaml.getLong("types." + type + "." + key);
  }

  public double decimal(EventRules.Type type, String key) {
    return yaml.getDouble("types." + type + "." + key);
  }

  public String text(EventRules.Type type, String key) {
    return yaml.getString("types." + type + "." + key);
  }
}
