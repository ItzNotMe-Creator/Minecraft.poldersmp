package nl.poldersmp.core.gameplay;

import java.util.*;

public final class Rules {
  private Rules() {}

  public static boolean chance(double probability, double roll) {
    return probability > 0 && roll >= 0 && roll < probability;
  }

  public static double scale(int pieces) {
    return Math.clamp(pieces, 0, 4) / 4.0;
  }

  public static Map<String, Integer> counts(Collection<String> trims) {
    Map<String, Integer> counts = new HashMap<>();
    for (String trim : trims) if (trim != null) counts.merge(trim, 1, Integer::sum);
    return Map.copyOf(counts);
  }

  public static boolean set(Map<String, Integer> counts, String pattern, int threshold) {
    return counts.getOrDefault(pattern, 0) >= threshold;
  }

  public static final class Cooldowns {
    private final Map<String, Long> until = new HashMap<>();

    public boolean acquire(UUID player, String ability, long now, long duration) {
      String key = player + ":" + ability;
      if (now < until.getOrDefault(key, Long.MIN_VALUE)) return false;
      until.put(key, now + duration);
      return true;
    }
  }
}
