package nl.poldersmp.common.nether;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * Announcement milestones (30 min, 10 min, 5 min, 1 min, 10 s before by default).
 *
 * The countdown is never stored as "seconds left"; it is always derived from the persisted
 * next-reset instant, so it survives restarts. What must not repeat after a restart is the set of
 * milestones that already passed; {@link #initiallyFired} rebuilds that set from the time left.
 */
public final class Countdown {

  private Countdown() {}

  /** Milestones that are already behind us when the countdown (re)starts. */
  public static Set<Long> initiallyFired(long remainingSeconds, List<Long> milestones) {
    Set<Long> fired = new TreeSet<>();
    for (long m : milestones) {
      if (m > remainingSeconds) {
        fired.add(m);
      }
    }
    return fired;
  }

  /**
   * Call once per second. Returns the milestone to announce now, if any. Every milestone that is due
   * is marked fired, but only the closest one is announced (a late tick never spams old warnings).
   */
  public static OptionalLong poll(long remainingSeconds, Set<Long> fired, List<Long> milestones) {
    long announce = -1;
    for (long m : milestones) {
      if (m >= remainingSeconds && !fired.contains(m)) {
        fired.add(m);
        if (announce < 0 || m < announce) {
          announce = m;
        }
      }
    }
    return announce < 0 ? OptionalLong.empty() : OptionalLong.of(announce);
  }

  /** "30 minutes", "1 minute", "10 seconds". */
  public static String format(long seconds) {
    if (seconds >= 3600 && seconds % 3600 == 0) {
      long h = seconds / 3600;
      return h + (h == 1 ? " hour" : " hours");
    }
    if (seconds >= 60 && seconds % 60 == 0) {
      long m = seconds / 60;
      return m + (m == 1 ? " minute" : " minutes");
    }
    return seconds + (seconds == 1 ? " second" : " seconds");
  }
}
