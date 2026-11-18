package nl.poldersmp.core.nether;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import nl.poldersmp.common.nether.ResetState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** /nether [next|info] for everyone, /netheradmin reset|pause|resume for admins. */
public final class NetherCommands implements TabExecutor {
  private final NetherService service;

  public NetherCommands(NetherService service) {
    this.service = service;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!service.active()) {
      sender.sendMessage("The Nether reset feature is not running (see the server log / nether.yml).");
      return true;
    }
    return command.getName().equalsIgnoreCase("netheradmin") ? admin(sender, args) : player(sender, args);
  }

  private boolean player(CommandSender sender, String[] args) {
    if (!sender.hasPermission("polder.nether.use")) {
      sender.sendMessage("You do not have permission.");
      return true;
    }
    String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "next" -> sender.sendMessage(nextText());
      case "info" -> {
        ResetState s = service.state();
        sender.sendMessage("Nether reset every " + service.settings().intervalHours() + " hours (" + service.settings().intervalHours() / 24.0 + " days). The Overworld and End never reset.");
        sender.sendMessage(nextText());
        sender.sendMessage("Last reset: " + (s.lastSuccess() == null ? "never" : s.lastSuccess()));
        sender.sendMessage("Nether access: " + (service.accessLocked() ? "CLOSED" : "open") + (s.paused() ? " (automatic resets paused)" : ""));
        if (sender.hasPermission("polder.nether.admin")) {
          sender.sendMessage("Phase: " + s.phase() + " | operation: " + (s.operationId().isEmpty() ? "-" : s.operationId()) + " | seed: " + s.currentSeed());
        }
      }
      case "" -> {
        sender.sendMessage(nextText() + (service.accessLocked() ? " The Nether is closed right now." : ""));
      }
      default -> sender.sendMessage("Usage: /nether [next|info]");
    }
    return true;
  }

  private String nextText() {
    ResetState s = service.state();
    if (s.paused()) {
      return "Automatic Nether resets are paused.";
    }
    long seconds = service.secondsUntilNext();
    if (seconds < 0) {
      return "The next Nether reset is not scheduled yet.";
    }
    return "Next Nether reset in " + NetherService.longTime(seconds) + " (" + Instant.now().plusSeconds(seconds) + ").";
  }

  private boolean admin(CommandSender sender, String[] args) {
    if (!sender.hasPermission("polder.nether.admin")) {
      sender.sendMessage("You do not have permission.");
      return true;
    }
    String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "reset" -> {
        boolean now = false;
        boolean confirmed = false;
        for (int i = 1; i < args.length; i++) {
          now |= args[i].equalsIgnoreCase("now");
          confirmed |= args[i].equalsIgnoreCase("confirm");
        }
        if (!confirmed) {
          sender.sendMessage("This closes the Nether, moves everyone out and resets it with a new seed. The Overworld and End are not touched.");
          sender.sendMessage("Run /netheradmin reset confirm (normal countdown) or /netheradmin reset now confirm (no countdown).");
        } else {
          sender.sendMessage(service.requestReset(now));
        }
      }
      case "pause" -> sender.sendMessage(service.pause());
      case "resume" -> sender.sendMessage(service.resume());
      default -> sender.sendMessage("Usage: /netheradmin <reset [now] confirm|pause|resume>");
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (command.getName().equalsIgnoreCase("netheradmin")) {
      if (!sender.hasPermission("polder.nether.admin")) {
        return List.of();
      }
      if (args.length == 1) {
        return filter(List.of("reset", "pause", "resume"), args[0]);
      }
      if (args.length >= 2 && args[0].equalsIgnoreCase("reset")) {
        return filter(List.of("now", "confirm"), args[args.length - 1]);
      }
      return List.of();
    }
    return args.length == 1 && sender.hasPermission("polder.nether.use") ? filter(List.of("next", "info"), args[0]) : List.of();
  }

  private static List<String> filter(List<String> options, String prefix) {
    return options.stream().filter(o -> o.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
  }
}
