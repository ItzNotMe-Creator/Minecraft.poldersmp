package nl.poldersmp.common.nether;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything about the Nether reset that must survive a restart: phase, schedule, operation id,
 * seeds, the world path the plugin actually observed, and the outcome history. Shared by the
 * plugin and the maintenance tool through {@link ResetStateStore}.
 */
public final class ResetState {

  /** One finished (or failed) reset attempt. */
  public record HistoryEntry(Instant at, String operationId, String outcome, Long seed, String detail) {}

  public static final int MAX_HISTORY = 20;

  private ResetPhase phase = ResetPhase.IDLE;
  private boolean paused;
  private String operationId = "";
  private Instant operationStarted;
  private Instant lastSuccess;
  private Instant nextReset;
  private Long currentSeed;
  private Long pendingSeed;
  private String worldKey = "";
  private String worldPath = "";
  private List<String> protectedPaths = new ArrayList<>();
  private String backupDirectory = "backups/nether";
  private int retentionKeep = 3;
  private String archivePath = "";
  private String launcherPath = "";
  private boolean launcherVerified;
  private final List<HistoryEntry> history = new ArrayList<>();

  // ------------------------------------------------------------------ transitions

  /** Starts a new operation. Refuses if one already exists (duplicate reset prevention). */
  public void begin(String newOperationId, Instant now) {
    if (phase != ResetPhase.IDLE || !operationId.isEmpty()) {
      throw new IllegalStateException("A Nether reset is already in progress (operation " + operationId + ", phase " + phase + ")");
    }
    if (newOperationId == null || newOperationId.isBlank()) {
      throw new IllegalArgumentException("operation id required");
    }
    phase.requireCanMoveTo(ResetPhase.COUNTDOWN);
    phase = ResetPhase.COUNTDOWN;
    operationId = newOperationId;
    operationStarted = now;
    pendingSeed = null;
    archivePath = "";
  }

  public void moveTo(ResetPhase next) {
    phase.requireCanMoveTo(next);
    phase = next;
  }

  /** Marks the operation failed (legal from every active phase) and records why. */
  public void fail(Instant now, String detail) {
    if (phase == ResetPhase.IDLE) {
      throw new IllegalStateException("No reset operation to fail");
    }
    if (phase != ResetPhase.FAILED) {
      moveTo(ResetPhase.FAILED);
    }
    addHistory(new HistoryEntry(now, operationId, "FAILED", pendingSeed, detail));
  }

  /** Successful end: back to IDLE, schedule the next reset, remember the new seed. */
  public void succeed(Instant now, Instant nextResetAt) {
    moveTo(ResetPhase.IDLE);
    addHistory(new HistoryEntry(now, operationId, "SUCCESS", pendingSeed, "Nether reset verified"));
    currentSeed = pendingSeed != null ? pendingSeed : currentSeed;
    lastSuccess = now;
    nextReset = nextResetAt;
    clearOperation();
  }

  /** Recovery ended with the old Nether restored: back to IDLE without counting as a success. */
  public void rolledBack(Instant now, Instant nextResetAt, String detail) {
    moveTo(ResetPhase.IDLE);
    addHistory(new HistoryEntry(now, operationId, "ROLLED_BACK", pendingSeed, detail));
    nextReset = nextResetAt;
    clearOperation();
  }

  /** Countdown cancelled before anything was locked. */
  public void cancelCountdown(Instant now, String detail) {
    moveTo(ResetPhase.IDLE);
    addHistory(new HistoryEntry(now, operationId, "CANCELLED", null, detail));
    clearOperation();
  }

  private void clearOperation() {
    operationId = "";
    operationStarted = null;
    pendingSeed = null;
    archivePath = "";
  }

  private void addHistory(HistoryEntry entry) {
    history.add(entry);
    while (history.size() > MAX_HISTORY) {
      history.remove(0);
    }
  }

  // ------------------------------------------------------------------ derived

  public boolean maintenance() {
    return phase.locksNetherAccess();
  }

  /** Every seed this system has ever used, so a new seed is never a repeat. */
  public List<Long> usedSeeds() {
    List<Long> seeds = new ArrayList<>();
    if (currentSeed != null) {
      seeds.add(currentSeed);
    }
    if (pendingSeed != null) {
      seeds.add(pendingSeed);
    }
    for (HistoryEntry e : history) {
      if (e.seed() != null) {
        seeds.add(e.seed());
      }
    }
    return seeds;
  }

  // ------------------------------------------------------------------ plain accessors

  public ResetPhase phase() { return phase; }
  public boolean paused() { return paused; }
  public void paused(boolean value) { paused = value; }
  public String operationId() { return operationId; }
  public Instant operationStarted() { return operationStarted; }
  public Instant lastSuccess() { return lastSuccess; }
  public Instant nextReset() { return nextReset; }
  public void nextReset(Instant value) { nextReset = value; }
  public Long currentSeed() { return currentSeed; }
  public void currentSeed(Long value) { currentSeed = value; }
  public Long pendingSeed() { return pendingSeed; }
  public void pendingSeed(Long value) { pendingSeed = value; }
  public String worldKey() { return worldKey; }
  public void worldKey(String value) { worldKey = value == null ? "" : value; }
  public String worldPath() { return worldPath; }
  public void worldPath(String value) { worldPath = value == null ? "" : value; }
  public List<String> protectedPaths() { return protectedPaths; }
  public void protectedPaths(List<String> value) { protectedPaths = new ArrayList<>(value); }
  public String backupDirectory() { return backupDirectory; }
  public void backupDirectory(String value) { backupDirectory = value; }
  public int retentionKeep() { return retentionKeep; }
  public void retentionKeep(int value) { retentionKeep = value; }
  public String archivePath() { return archivePath; }
  public void archivePath(String value) { archivePath = value == null ? "" : value; }
  public String launcherPath() { return launcherPath; }
  public void launcherPath(String value) { launcherPath = value == null ? "" : value; }
  public boolean launcherVerified() { return launcherVerified; }
  public void launcherVerified(boolean value) { launcherVerified = value; }
  public List<HistoryEntry> history() { return history; }

  // Used by the store when loading (bypasses transition checks on purpose).
  void restorePhase(ResetPhase value) { phase = value; }
  void restoreOperation(String id, Instant started) { operationId = id; operationStarted = started; }
  void restoreLastSuccess(Instant value) { lastSuccess = value; }
}
