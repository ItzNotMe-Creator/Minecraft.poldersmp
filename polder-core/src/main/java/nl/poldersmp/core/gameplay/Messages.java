package nl.poldersmp.core.gameplay;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class Messages {
  static String get(JavaPlugin plugin, String key) {
    File file = new File(plugin.getDataFolder(), "messages.yml");
    if (!file.exists()) plugin.saveResource("messages.yml", false);
    return YamlConfiguration.loadConfiguration(file).getString(key, key);
  }
}
