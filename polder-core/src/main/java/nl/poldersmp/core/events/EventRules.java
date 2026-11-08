package nl.poldersmp.core.events;

import java.time.*;
import java.util.*;

public final class EventRules {
  private EventRules() {}

  public enum Type {
    METEOR,
    SUPPLY_DROP,
    BOSS,
    PIRATE_SHIP,
    DUNGEON
  }

  public static Instant next(Instant now, long seconds) {
    if (seconds < 1) throw new IllegalArgumentException("Invalid interval");
    return now.plusSeconds(seconds);
  }

  public static UUID winner(Map<UUID, Double> damage, double minimum) {
    return damage.entrySet().stream()
        .filter(e -> Double.isFinite(e.getValue()) && e.getValue() >= minimum)
        .sorted(
            Map.Entry.<UUID, Double>comparingByValue()
                .reversed()
                .thenComparing(e -> e.getKey().toString()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  public static boolean terminal(String status) {
    return status.equals("FINISHED") || status.equals("CANCELED");
  }
}
