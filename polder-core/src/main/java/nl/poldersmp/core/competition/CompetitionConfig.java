package nl.poldersmp.core.competition;

import java.io.*;
import java.time.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record CompetitionConfig(YamlConfiguration bounties, YamlConfiguration king) {
  public static CompetitionConfig load(JavaPlugin plugin) {
    return new CompetitionConfig(read(plugin, "bounties.yml"), read(plugin, "king.yml"));
  }

  public CompetitionConfig {
    for (YamlConfiguration c : java.util.List.of(bounties, king)) {
      if (!c.isBoolean("enabled")) throw new IllegalArgumentException("enabled must be boolean");
      for (var e : c.getValues(true).entrySet()) {
        if (e.getValue() instanceof Number n) {
          boolean coordinate =
              java.util.Set.of(
                      "arena.x", "arena.y", "arena.z", "capture.x", "capture.y", "capture.z")
                  .contains(e.getKey());
          if (!Double.isFinite(n.doubleValue()) || !coordinate && n.doubleValue() < 0)
            throw new IllegalArgumentException("Invalid number: " + e.getKey());
          if (e.getKey().endsWith("seconds") && n.doubleValue() > 31536000)
            throw new IllegalArgumentException("Duration exceeds one year: " + e.getKey());
          if (coordinate && Math.abs(n.doubleValue()) > 30000000)
            throw new IllegalArgumentException("Coordinate outside world limits");
        }
      }
    }
    if (king.getLong("rewards.money") > 0)
      java.util.UUID.fromString(king.getString("rewards.treasury", ""));

    if (bounties.getLong("minimum") < 1
        || bounties.getLong("maximum") < bounties.getLong("minimum"))
      throw new IllegalArgumentException("Invalid bounty limits");
    if (king.getInt("minimum-participants") < 2
        || king.getDouble("capture-seconds") < 1
        || king.getDouble("capture-radius") < 1
        || king.getDouble("arena.radius") < king.getDouble("capture-radius")
        || king.getLong("maximum-duration-seconds") < king.getLong("capture-seconds"))
      throw new IllegalArgumentException("Invalid King capture settings");
    if (king.getLong("countdown-seconds") > 86400
        || king.getLong("maximum-duration-seconds") > 86400)
      throw new IllegalArgumentException("Event duration exceeds one day");
    ZoneId.of(king.getString("schedule.timezone"));
    DayOfWeek.valueOf(king.getString("schedule.day"));
    LocalTime.parse(king.getString("schedule.time"));
  }

  private static YamlConfiguration read(JavaPlugin plugin, String name) {
    File file = new File(plugin.getDataFolder(), name);
    if (!file.exists()) plugin.saveResource(name, false);
    try {
      YamlConfiguration c = new YamlConfiguration();
      c.load(file);
      return c;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid " + name, e);
    }
  }
}
