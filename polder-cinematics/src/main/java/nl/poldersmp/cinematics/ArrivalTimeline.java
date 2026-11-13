package nl.poldersmp.cinematics;

import java.util.*;

/** Wall-time choreography. A lag spike catches up cues once, never replays them. */
public final class ArrivalTimeline {
  public enum Cue {
    AWAKEN,
    ORBIT,
    CONVERGE,
    TOTEM,
    AFTERGLOW
  }

  private final double duration;
  private final EnumSet<Cue> played = EnumSet.noneOf(Cue.class);
  private double elapsed;

  public ArrivalTimeline(double duration) {
    if (!Double.isFinite(duration) || duration < 2 || duration > 10)
      throw new IllegalArgumentException("Landing duration must be 2..10 seconds");
    this.duration = duration;
  }

  public List<Cue> advance(double seconds) {
    if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Finite time required");
    elapsed = Math.max(elapsed, Math.max(0, seconds));
    List<Cue> cues = new ArrayList<>();
    double[] at = {0, .16, .38, .58, .79};
    for (Cue cue : Cue.values())
      if (elapsed >= duration * at[cue.ordinal()] && played.add(cue)) cues.add(cue);
    return List.copyOf(cues);
  }

  public double progress() {
    return Math.clamp(elapsed / duration, 0, 1);
  }

  public boolean complete() {
    return elapsed >= duration;
  }
}
