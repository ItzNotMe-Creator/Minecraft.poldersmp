package nl.poldersmp.common.nether;

import java.time.Duration;
import java.time.Instant;

/** Real-world-time scheduling rules for the Nether reset. Pure functions, no clock access. */
public final class NetherSchedule {

  private NetherSchedule() {}

  /** The very first reset is one interval after the system is first set up. */
  public static Instant first(Instant now, Duration interval) {
    return now.plus(interval);
  }

  /** After a successful reset the next one is one full interval later (not "interval after due time"). */
  public static Instant afterSuccess(Instant completedAt, Duration interval) {
    return completedAt.plus(interval);
  }

  public static boolean isDue(Instant now, Instant next) {
    return !now.isBefore(next);
  }

  /** Whole seconds left, rounded up, never negative. */
  public static long remainingSeconds(Instant now, Instant next) {
    Duration d = Duration.between(now, next);
    long s = d.getSeconds() + (d.getNano() > 0 ? 1 : 0);
    return Math.max(0, s);
  }

  /**
   * When an admin resumes a paused schedule: keep the old time if it is still comfortably in the
   * future, otherwise give players a fresh warning window instead of resetting instantly.
   */
  public static Instant afterResume(Instant next, Instant now, Duration warningWindow) {
    Instant earliest = now.plus(warningWindow);
    return next.isAfter(earliest) ? next : earliest;
  }

  /** The time to use right after loading state: keep a saved time, otherwise start a new interval. */
  public static Instant orFirst(Instant saved, Instant now, Duration interval) {
    return saved != null ? saved : first(now, interval);
  }
}
