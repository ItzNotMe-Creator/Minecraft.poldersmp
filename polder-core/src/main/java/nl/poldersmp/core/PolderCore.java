package nl.poldersmp.core;

import java.util.*;
import nl.poldersmp.common.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class PolderCore extends JavaPlugin implements TabExecutor {
  private nl.poldersmp.core.gameplay.Weapons weapons;
  private nl.poldersmp.core.gameplay.Armor armor;
  private nl.poldersmp.core.competition.Competitions competitions;
  private nl.poldersmp.core.loot.Loot loot;
  private nl.poldersmp.core.events.EventManager worldEvents;
  private nl.poldersmp.core.nether.NetherManager nether;
  private nl.poldersmp.core.teams.Teams teams;
  private nl.poldersmp.core.market.MarketManager market;
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
    try {
      nether = new nl.poldersmp.core.nether.NetherManager(this);
    } catch (Exception error) {
      getLogger().severe("Nether startup refused: " + error);
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
                if (e == null)
                  getServer()
                      .getScheduler()
                      .runTask(
                          this,
                          () -> {
                            if (isEnabled()) {
                              try {
                                teams = new nl.poldersmp.core.teams.Teams(this, database);
                              } catch (RuntimeException error) {
                                getLogger().severe("Teams startup failed: " + error);
                              }
                              try {
                                market =
                                    new nl.poldersmp.core.market.MarketManager(
                                        this, database, weapons);
                              } catch (RuntimeException error) {
                                getLogger()
                                    .severe("Economy startup failed (cash gate closed): " + error);
                              }
                              try {
                                competitions =
                                    new nl.poldersmp.core.competition.Competitions(this, database);
                              } catch (RuntimeException error) {
                                getLogger().severe("Competition startup failed: " + error);
                              }
                              try {
                                loot = new nl.poldersmp.core.loot.Loot(this, database, weapons);
                              } catch (RuntimeException error) {
                                getLogger().severe("Loot startup failed: " + error);
                              }
                              try {
                                worldEvents =
                                    new nl.poldersmp.core.events.EventManager(this, database);
                              } catch (RuntimeException error) {
                                getLogger().severe("World event startup failed: " + error);
                              }
                            }
                          });
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
    if (market != null) market.close();
    if (teams != null) teams.close();
    if (nether != null) nether.close();
    if (worldEvents != null) worldEvents.close();
    if (loot != null) loot.close();
    if (competitions != null) competitions.close();
    if (armor != null) armor.close();
    if (database != null) database.close();
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equals("poldercore")) {
      sender.sendMessage(
          "Gameplay service unavailable: database disabled, initializing, or invalid"
              + " configuration.");
      return true;
    }
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
