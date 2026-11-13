package nl.poldersmp.core.nether;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Caller must validate the destination and invoke on the Paper server thread. */
public final class NetherEvacuation {
  private NetherEvacuation() {}

  public static CompletableFuture<Boolean> move(
      Collection<? extends Player> players, Location safe) {
    List<CompletableFuture<Boolean>> moves = new ArrayList<>();
    for (Player p : players)
      if (p.getWorld().getEnvironment() == World.Environment.NETHER)
        moves.add(p.teleportAsync(safe.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN));
    return CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new))
        .thenApply(v -> moves.stream().allMatch(CompletableFuture::join));
  }
}
