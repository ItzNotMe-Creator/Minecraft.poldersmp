package nl.poldersmp.core.loot;

import java.util.*;

public final class LootRules {
  private LootRules() {}

  public enum Tier {
    COMMON,
    RARE,
    EPIC,
    LEGENDARY,
    MYTHIC
  }

  public record Weighted<T>(T value, long weight) {
    public Weighted {
      if (weight <= 0) throw new IllegalArgumentException("Weights must be positive");
    }
  }

  public static <T> T select(List<Weighted<T>> rewards, long ticket) {
    long total = total(rewards);
    if (ticket < 0 || ticket >= total) throw new IllegalArgumentException("Ticket out of bounds");
    for (var reward : rewards) {
      if (ticket < reward.weight()) return reward.value();
      ticket -= reward.weight();
    }
    throw new AssertionError();
  }

  public static long total(List<? extends Weighted<?>> rewards) {
    if (rewards.isEmpty()) throw new IllegalArgumentException("Empty reward table");
    long total = 0;
    for (var reward : rewards) total = Math.addExact(total, reward.weight());
    return total;
  }

  public static boolean validKey(String tier, String id, int amount) {
    try {
      Tier.valueOf(tier);
      UUID.fromString(id);
      return amount == 1;
    } catch (Exception e) {
      return false;
    }
  }

  public record Eligibility(
      boolean normalWorld,
      boolean border,
      boolean terrain,
      boolean uninhabited,
      boolean noStructures,
      boolean noContainers,
      boolean noPlayers,
      boolean wilderness,
      boolean airOnly) {
    public boolean allowed() {
      return normalWorld
          && border
          && terrain
          && uninhabited
          && noStructures
          && noContainers
          && noPlayers
          && wilderness
          && airOnly;
    }
  }
}
