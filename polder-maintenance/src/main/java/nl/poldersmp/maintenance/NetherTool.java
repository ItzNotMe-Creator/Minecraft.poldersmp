package nl.poldersmp.maintenance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import nl.poldersmp.common.nether.ResetStateStore;

/**
 * Command line entry: {@code java -jar PolderMaintenance.jar <command> [--server-dir DIR]}.
 * Exit codes: 0 done, 1 failed, 2 refused (unsafe or not allowed; nothing was changed).
 */
public final class NetherTool {
  private NetherTool() {}

  public static void main(String[] args) {
    System.exit(execute(args));
  }

  static int execute(String[] args) {
    String command = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);
    Path server = Path.of(".");
    for (int i = 1; i + 1 < args.length; i++) {
      if (args[i].equals("--server-dir")) {
        server = Path.of(args[i + 1]);
      }
    }
    server = server.toAbsolutePath().normalize();
    if (!Files.isDirectory(server)) {
      System.err.println("Server folder not found: " + server);
      return MaintenanceRunner.REFUSED;
    }
    Path stateFile = server.resolve("plugins/PolderCore/nether-state.properties");
    ResetStateStore store = new ResetStateStore(stateFile);
    MaintenanceLog log = new MaintenanceLog(server.resolve("logs/nether-maintenance.log"), System.out, Clock.systemUTC());

    ServerProbe probe;
    try {
      var state = store.load();
      List<Path> worlds = new java.util.ArrayList<>();
      if (!state.worldPath().isBlank()) {
        worlds.add(Path.of(state.worldPath()));
      }
      for (String p : state.protectedPaths()) {
        worlds.add(Path.of(p));
      }
      probe = new ServerStopCheck(server, worlds);
    } catch (java.io.IOException e) {
      System.err.println("State file problem: " + e.getMessage());
      return MaintenanceRunner.REFUSED;
    }

    MaintenanceRunner runner = new MaintenanceRunner(server, store, probe, Launcher.process(), log, Clock.systemUTC());
    MaintenanceRunner.Outcome out =
        switch (command) {
          case "status" -> runner.status();
          case "run" -> runner.run();
          case "recover" -> runner.recover();
          case "verify" -> runner.verify();
          case "cleanup" -> runner.cleanup();
          case "start" -> runner.start();
          default -> {
            System.out.println(HELP);
            yield new MaintenanceRunner.Outcome(command.equals("help") ? 0 : 2, "");
          }
        };
    if (!out.message().isEmpty()) {
      (out.exitCode() == 0 ? System.out : System.err).println(out.message());
    }
    return out.exitCode();
  }

  private static final String HELP =
      """
      PolderSMP Nether maintenance tool

      Usage: java -jar PolderMaintenance.jar <command> [--server-dir DIR]

        status    show the reset state, backups and whether the server is stopped (read only)
        run       back up and move away the old Nether and prepare a new seed (server must be stopped)
        start     start the server through the verified launcher (only after a successful run)
        recover   roll back an unfinished or failed reset using the verified backup
        verify    check state, paths and backup consistency (read only)
        cleanup   apply backup retention (newest valid backups are always kept)

      Exit codes: 0 done, 1 failed, 2 refused (nothing was changed).
      This tool never stops or kills the server. Stop it normally first.
      """;
}
