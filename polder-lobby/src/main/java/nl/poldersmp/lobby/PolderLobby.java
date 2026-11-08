package nl.poldersmp.lobby;

import nl.poldersmp.common.Settings;
import org.bukkit.plugin.java.JavaPlugin;

public final class PolderLobby extends JavaPlugin {
  @Override
  public void onEnable() {
    try {
      Settings settings = Settings.load(getDataFolder().toPath());
      getLogger().info("Lobby foundation " + (settings.enabled() ? "enabled" : "disabled"));
    } catch (Exception e) {
      getLogger().severe("Configuration: " + e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }
}
