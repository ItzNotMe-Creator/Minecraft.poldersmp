package nl.poldersmp.core.nether;

import java.util.*;

public final class NetherRules {
  private NetherRules() {}

  public static long interval(long hours) {
    if (hours < 1 || hours > 87600)
      throw new IllegalArgumentException("interval-hours outside 1..87600");
    return Math.multiplyExact(hours, 3600000L);
  }

  public static List<Integer> warnings(
      long previousRemaining, long remaining, List<Integer> thresholds) {
    return thresholds.stream()
        .distinct()
        .filter(n -> n > 0 && previousRemaining > n && remaining <= n && remaining >= 0)
        .sorted(Comparator.reverseOrder())
        .toList();
  }

  public static boolean shouldEvacuate(boolean nether, boolean accessOpen) {
    return nether && !accessOpen;
  }

  public static double coordinate(double from, double scale, double bound) {
    return Math.max(-bound, Math.min(bound, from * scale));
  }
}
