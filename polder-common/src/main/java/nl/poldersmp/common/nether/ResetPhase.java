package nl.poldersmp.common.nether;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where a Nether reset currently is. The plugin and the Windows maintenance tool both move through
 * these phases, and only the transitions listed here are legal, so a reset can never skip a safety
 * step (for example: archive the old Nether before the server has stopped).
 *
 * <pre>
 * IDLE -> COUNTDOWN -> LOCKED -> EVACUATED -> SAVED -> AWAITING_MAINTENANCE   (plugin, server running)
 *      -> MAINTENANCE_RUNNING -> PREPARED                                      (tool, server stopped)
 *      -> VERIFYING -> IDLE                                                    (plugin, after restart)
 * any active phase -> FAILED -> RECOVERING -> IDLE | AWAITING_MAINTENANCE
 * </pre>
 */
public enum ResetPhase {
  IDLE,
  COUNTDOWN,
  LOCKED,
  EVACUATED,
  SAVED,
  AWAITING_MAINTENANCE,
  MAINTENANCE_RUNNING,
  PREPARED,
  VERIFYING,
  FAILED,
  RECOVERING;

  private static final Map<ResetPhase, Set<ResetPhase>> NEXT = new EnumMap<>(ResetPhase.class);

  static {
    NEXT.put(IDLE, EnumSet.of(COUNTDOWN));
    NEXT.put(COUNTDOWN, EnumSet.of(LOCKED, IDLE, FAILED));
    NEXT.put(LOCKED, EnumSet.of(EVACUATED, FAILED));
    NEXT.put(EVACUATED, EnumSet.of(SAVED, FAILED));
    NEXT.put(SAVED, EnumSet.of(AWAITING_MAINTENANCE, FAILED));
    NEXT.put(AWAITING_MAINTENANCE, EnumSet.of(MAINTENANCE_RUNNING, FAILED));
    NEXT.put(MAINTENANCE_RUNNING, EnumSet.of(PREPARED, FAILED));
    NEXT.put(PREPARED, EnumSet.of(VERIFYING, FAILED));
    NEXT.put(VERIFYING, EnumSet.of(IDLE, FAILED));
    NEXT.put(FAILED, EnumSet.of(RECOVERING));
    NEXT.put(RECOVERING, EnumSet.of(IDLE, AWAITING_MAINTENANCE, FAILED));
  }

  public boolean canMoveTo(ResetPhase next) {
    return NEXT.get(this).contains(next);
  }

  public void requireCanMoveTo(ResetPhase next) {
    if (!canMoveTo(next)) {
      throw new IllegalStateException("Illegal reset transition " + this + " -> " + next);
    }
  }

  /** A reset operation exists (anything except IDLE). */
  public boolean isActive() {
    return this != IDLE;
  }

  /** New Nether access must be refused in this phase. COUNTDOWN still allows access (a warning only). */
  public boolean locksNetherAccess() {
    return this != IDLE && this != COUNTDOWN;
  }

  /** The server process is expected to be stopped while the tool works. */
  public boolean toolMayRun() {
    return this == AWAITING_MAINTENANCE;
  }
}
