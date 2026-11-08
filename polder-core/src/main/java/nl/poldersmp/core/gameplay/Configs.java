package nl.poldersmp.core.gameplay;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

final class Configs {
  static YamlConfiguration load(File file) {
    try {
      YamlConfiguration c = new YamlConfiguration();
      c.load(file);
      if (!c.isBoolean("enabled")) throw new IllegalArgumentException("enabled must be a boolean");
      for (var entry : c.getValues(true).entrySet())
        if (entry.getValue() instanceof Number n
            && (!Double.isFinite(n.doubleValue()) || n.doubleValue() < 0))
          throw new IllegalArgumentException("Invalid nonnegative number: " + entry.getKey());
      for (var entry : c.getValues(true).entrySet())
        if (entry.getKey().endsWith("cooldown")
            && entry.getValue() instanceof Number n
            && n.doubleValue() > 86400)
          throw new IllegalArgumentException("Cooldown exceeds 24 hours");
      return c;
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Invalid configuration " + file.getName() + ": " + e.getMessage(), e);
    }
  }
}
