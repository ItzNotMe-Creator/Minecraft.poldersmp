package nl.poldersmp.common.nether;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResetStateTest {
  static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  @Test
  void fullHappyPathEndsIdleWithHistoryAndNewSeed() {
    ResetState s = new ResetState();
    s.currentSeed(111L);
    s.begin("op-1", NOW);
    for (ResetPhase p :
        List.of(
            ResetPhase.LOCKED,
            ResetPhase.EVACUATED,
            ResetPhase.SAVED,
            ResetPhase.AWAITING_MAINTENANCE,
            ResetPhase.MAINTENANCE_RUNNING)) {
      s.moveTo(p);
    }
    s.pendingSeed(222L);
    s.moveTo(ResetPhase.PREPARED);
    s.moveTo(ResetPhase.VERIFYING);
    Instant next = NOW.plusSeconds(120 * 3600L);
    s.succeed(NOW, next);

    assertEquals(ResetPhase.IDLE, s.phase());
    assertEquals(222L, s.currentSeed());
    assertEquals(next, s.nextReset());
    assertEquals(NOW, s.lastSuccess());
    assertEquals("", s.operationId());
    assertEquals("SUCCESS", s.history().get(0).outcome());
  }

  @Test
  void duplicateResetIsRefusedWhileOneIsActive() {
    ResetState s = new ResetState();
    s.begin("op-1", NOW);
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.begin("op-2", NOW));
    assertTrue(e.getMessage().contains("op-1"));
    assertEquals("op-1", s.operationId());
  }

  @Test
  void stepsCannotBeSkipped() {
    ResetState s = new ResetState();
    s.begin("op-1", NOW);
    assertThrows(IllegalStateException.class, () -> s.moveTo(ResetPhase.AWAITING_MAINTENANCE));
    assertThrows(IllegalStateException.class, () -> s.moveTo(ResetPhase.PREPARED));
    assertEquals(ResetPhase.COUNTDOWN, s.phase());
  }

  @Test
  void everyActivePhaseCanFailAndFailedCanOnlyRecover() {
    for (ResetPhase p : ResetPhase.values()) {
      if (p == ResetPhase.IDLE || p == ResetPhase.FAILED || p == ResetPhase.RECOVERING) {
        continue;
      }
      assertTrue(p.canMoveTo(ResetPhase.FAILED), p + " must be able to fail");
    }
    assertTrue(ResetPhase.FAILED.canMoveTo(ResetPhase.RECOVERING));
    assertFalse(ResetPhase.FAILED.canMoveTo(ResetPhase.IDLE));
    assertFalse(ResetPhase.FAILED.canMoveTo(ResetPhase.AWAITING_MAINTENANCE));
    assertFalse(ResetPhase.IDLE.canMoveTo(ResetPhase.LOCKED));
  }

  @Test
  void failureIsRecordedAndKeepsTheOperationUntilRecovered() {
    ResetState s = new ResetState();
    s.begin("op-1", NOW);
    s.moveTo(ResetPhase.LOCKED);
    s.fail(NOW, "disk full");
    assertEquals(ResetPhase.FAILED, s.phase());
    assertEquals("op-1", s.operationId());
    assertTrue(s.maintenance(), "Nether stays closed while failed");
    assertThrows(IllegalStateException.class, () -> s.begin("op-2", NOW));

    s.moveTo(ResetPhase.RECOVERING);
    s.rolledBack(NOW, NOW.plusSeconds(3600), "old Nether restored");
    assertEquals(ResetPhase.IDLE, s.phase());
    assertEquals("ROLLED_BACK", s.history().get(1).outcome());
    s.begin("op-2", NOW); // a new reset is possible again
  }

  @Test
  void netherAccessLockMatchesPhase() {
    assertFalse(ResetPhase.IDLE.locksNetherAccess());
    assertFalse(ResetPhase.COUNTDOWN.locksNetherAccess());
    assertTrue(ResetPhase.LOCKED.locksNetherAccess());
    assertTrue(ResetPhase.PREPARED.locksNetherAccess());
    assertTrue(ResetPhase.VERIFYING.locksNetherAccess());
  }

  @Test
  void toolMayOnlyRunWhileAwaitingMaintenance() {
    for (ResetPhase p : ResetPhase.values()) {
      assertEquals(p == ResetPhase.AWAITING_MAINTENANCE, p.toolMayRun());
    }
  }

  @Test
  void usedSeedsIncludeHistoryAndPending() {
    ResetState s = new ResetState();
    s.currentSeed(1L);
    s.begin("a", NOW);
    s.pendingSeed(2L);
    s.fail(NOW, "x");
    assertTrue(s.usedSeeds().containsAll(List.of(1L, 2L)));
  }

  @Test
  void historyIsCapped() {
    ResetState s = new ResetState();
    for (int i = 0; i < ResetState.MAX_HISTORY + 5; i++) {
      s.begin("op-" + i, NOW);
      s.cancelCountdown(NOW, "cancel");
    }
    assertEquals(ResetState.MAX_HISTORY, s.history().size());
    assertEquals("op-" + (ResetState.MAX_HISTORY + 4), s.history().get(ResetState.MAX_HISTORY - 1).operationId());
  }
}
