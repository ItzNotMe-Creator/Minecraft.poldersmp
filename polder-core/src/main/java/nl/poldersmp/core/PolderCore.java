package nl.poldersmp.core;

import java.util.*;
import nl.poldersmp.common.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class PolderCore extends JavaPlugin implements TabExecutor {
  private nl.poldersmp.core.gameplay.Weapons weapons;
  private nl.poldersmp.core.gameplay.Armor armor;
  private Settings settings;
  private Database database;
  private volatile String databaseState = "disabled";

  @Override
  public void onEnable() {
    try {
      settings = Settings.load(getDataFolder().toPath());
    } catch (Exception e) {
      getLogger().severe("Configuration: " + e);
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    Objects.requireNonNull(getCommand("poldercore")).setExecutor(this);
    Objects.requireNonNull(getCommand("poldercore")).setTabCompleter(this);
    if (!settings.enabled()) {
      getLogger().info("Foundation module disabled in configuration");
      return;
    }
    weapons = new nl.poldersmp.core.gameplay.Weapons(this);
    armor = new nl.poldersmp.core.gameplay.Armor(this);
    if (settings.databaseEnabled()) {
      database = new Database(getDataFolder().toPath().resolve("polder.db"));
      databaseState = "starting";
      database
          .initialize()
          .whenComplete(
              (v, e) -> {
                databaseState = e == null ? "ready" : "failed";
                if (e != null) {
                  getLogger().severe("SQLite initialization failed: " + e);
                  getServer()
                      .getScheduler()
                      .runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                }
              });
    }
  }

  @Override
  public void onDisable() {
    if (armor != null) armor.close();
    if (database != null) database.close();
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
    if (!List.of("info", "reload", "debug").contains(sub)) {
      sender.sendMessage("Usage: /poldercore <info|reload|debug>");
      return true;
    }
    if (!sender.hasPermission("poldercore." + sub)) {
      sender.sendMessage("You do not have permission.");
      return true;
    }
    switch (sub) {
      case "info" ->
          sender.sendMessage(
              "PolderCore "
                  + getPluginMeta().getVersion()
                  + " | module="
                  + settings.enabled()
                  + " | SQLite="
                  + databaseState);
      case "reload" -> {
        try {
          Settings next = Settings.load(getDataFolder().toPath());
          if (next.enabled() != settings.enabled()
              || next.databaseEnabled() != settings.databaseEnabled()) {
            sender.sendMessage("Module/database changes require a server restart.");
          } else {
            settings = next;
            sender.sendMessage("Configuration reloaded.");
          }
        } catch (Exception e) {
          sender.sendMessage("Reload failed: " + e.getMessage());
        }
      }
      case "debug" ->
          sender.sendMessage(
              "Java="
                  + Runtime.version()
                  + " | server="
                  + getServer().getVersion()
                  + " | database="
                  + databaseState
                  + " | debug="
                  + settings.debug());
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
    return args.length == 1
        ? List.of("info", "reload", "debug").stream()
            .filter(
                x ->
                    s.hasPermission("poldercore." + x)
                        && x.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList()
        : List.of();
  }
}
