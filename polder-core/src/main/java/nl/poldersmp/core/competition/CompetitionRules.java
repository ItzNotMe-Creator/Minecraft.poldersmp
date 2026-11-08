package nl.poldersmp.core.competition;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

public final class CompetitionRules {
  private CompetitionRules() {}

  public static boolean eligibleClaim(
      UUID killer, UUID victim, boolean teammate, long now, long last, long delay) {
    return !killer.equals(victim) && !teammate && (last == 0 || now - last >= delay);
  }

  public static Instant nextWeekly(Instant after, ZoneId zone, DayOfWeek day, LocalTime time) {
    ZonedDateTime local = after.atZone(zone);
    ZonedDateTime candidate =
        local.toLocalDate().with(TemporalAdjusters.nextOrSame(day)).atTime(time).atZone(zone);
    if (!candidate.toInstant().isAfter(after)) candidate = candidate.plusWeeks(1);
    return candidate.toInstant();
  }

  public record Capture(UUID candidate, double progress) {
    public Capture advance(
        Set<UUID> occupants, boolean enough, double seconds, boolean resetContested) {
      if (!enough || occupants.isEmpty()) return new Capture(null, 0);
      if (occupants.size() != 1) return resetContested ? new Capture(null, 0) : this;
      UUID only = occupants.iterator().next();
      return new Capture(only, only.equals(candidate) ? progress + seconds : seconds);
    }

    public Optional<UUID> winner(double required) {
      return candidate != null && progress >= required ? Optional.of(candidate) : Optional.empty();
    }
  }
}
