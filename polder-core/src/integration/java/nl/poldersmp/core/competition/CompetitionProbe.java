package nl.poldersmp.core.competition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import nl.poldersmp.common.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated Paper startup probe; excluded from production compilation and packaging. */
public final class CompetitionProbe extends JavaPlugin {
  @Override
  public void onEnable() {
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              for (String name : List.of("bounty", "bounties", "bountyadmin", "king")) {
                var command = Bukkit.getPluginCommand(name);
                if (command == null || !(command.getExecutor() instanceof Competitions))
                  throw new AssertionError("Competition executor unavailable: " + name);
              }
              Path core =
                  Bukkit.getPluginManager().getPlugin("PolderCore").getDataFolder().toPath();
              for (String name : List.of("bounties.yml", "king.yml"))
                if (!Files.isRegularFile(core.resolve(name)))
                  throw new AssertionError("Missing configuration: " + name);
              Database db = new Database(core.resolve("polder.db"));
              db.submit(
                      c -> {
                        for (String table : List.of("schema_migrations", "competition_schema"))
                          try (var s = c.createStatement();
                              var rs = s.executeQuery("SELECT MAX(version) FROM " + table)) {
                            if (rs.getInt(1) != 1)
                              throw new AssertionError("Unexpected schema version: " + table);
                          }
                        return null;
                      })
                  .whenComplete(
                      (v, error) -> {
                        db.close();
                        if (error != null) getLogger().severe("COMPETITION PROBE FAILED: " + error);
                        else
                          getLogger()
                              .info(
                                  "COMPETITION PROBE PASS: 4 executors; configs; foundation and"
                                      + " competition schema v1");
                      });
            },
            60);
  }
}
