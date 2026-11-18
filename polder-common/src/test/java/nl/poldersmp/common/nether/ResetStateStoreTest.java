package nl.poldersmp.common.nether;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResetStateStoreTest {
  @TempDir Path temp;
  static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  ResetStateStore store() {
    return new ResetStateStore(temp.resolve("plugins/PolderCore/nether-state.properties"));
  }

  @Test
  void missingFileGivesFreshIdleState() throws Exception {
    ResetState s = store().load();
    assertEquals(ResetPhase.IDLE, s.phase());
    assertNull(s.nextReset());
  }

  @Test
  void everythingRoundTrips() throws Exception {
    ResetState s = new ResetState();
    s.currentSeed(-42L);
    s.nextReset(NOW.plusSeconds(1000));
    s.worldKey("polder:resource_nether");
    s.worldPath("/srv/world/dimensions/polder/resource_nether");
    s.protectedPaths(List.of("/srv/world/a", "/srv/world/b"));
    s.backupDirectory("backups/nether");
    s.retentionKeep(5);
    s.launcherPath("start.bat");
    s.launcherVerified(true);
    s.paused(true);
    s.begin("op-1", NOW);
    s.moveTo(ResetPhase.LOCKED);
    s.pendingSeed(77L);
    s.archivePath("/srv/backups/x");
    s.fail(NOW, "detail with | pipe\nand newline");
    store().save(s);

    ResetState back = store().load();
    assertEquals(ResetPhase.FAILED, back.phase());
    assertEquals("op-1", back.operationId());
    assertEquals(NOW, back.operationStarted());
    assertEquals(-42L, back.currentSeed());
    assertEquals(77L, back.pendingSeed());
    assertEquals(NOW.plusSeconds(1000), back.nextReset());
    assertTrue(back.paused());
    assertEquals(List.of("/srv/world/a", "/srv/world/b"), back.protectedPaths());
    assertEquals(5, back.retentionKeep());
    assertTrue(back.launcherVerified());
    assertEquals("/srv/backups/x", back.archivePath());
    assertEquals(1, back.history().size());
    assertEquals("FAILED", back.history().get(0).outcome());
  }

  /** The countdown is derived from the saved next-reset, so a restart continues it, never restarts it. */
  @Test
  void countdownSurvivesARestart() throws Exception {
    Duration interval = Duration.ofHours(120);
    ResetState first = new ResetState();
    first.nextReset(NetherSchedule.first(NOW, interval));
    store().save(first);

    // 'Restart' 4 days and 23.5 hours later: 30 minutes remain.
    Instant afterRestart = NOW.plus(Duration.ofHours(119)).plus(Duration.ofMinutes(30));
    ResetState loaded = store().load();
    Instant next = NetherSchedule.orFirst(loaded.nextReset(), afterRestart, interval);
    assertEquals(NOW.plus(interval), next, "the saved schedule is used, not restarted");

    long remaining = NetherSchedule.remainingSeconds(afterRestart, next);
    assertEquals(1800, remaining);

    List<Long> milestones = NetherSettings.DEFAULT_MILESTONES;
    Set<Long> fired = Countdown.initiallyFired(remaining, milestones);
    assertTrue(fired.isEmpty(), "30 minutes are left so no milestone is behind us yet");
    assertEquals(1800L, Countdown.poll(remaining, fired, milestones).getAsLong());
    assertTrue(Countdown.poll(remaining, fired, milestones).isEmpty(), "and it is not repeated");
  }

  @Test
  void corruptFileIsReportedNotRepaired() throws Exception {
    ResetStateStore store = store();
    Files.createDirectories(store.file().getParent());
    Files.writeString(store.file(), "phase=NOT_A_PHASE\n");
    IOException e = assertThrows(IOException.class, store::load);
    assertTrue(e.getMessage().contains("corrupt"));
    assertEquals("phase=NOT_A_PHASE\n", Files.readString(store.file()), "file is left untouched");
  }

  @Test
  void saveReplacesTheFileWithoutLeavingTemporaryFiles() throws Exception {
    ResetStateStore store = store();
    ResetState s = new ResetState();
    store.save(s);
    s.paused(true);
    store.save(s);
    assertTrue(store.load().paused());
    try (var files = Files.list(store.file().getParent())) {
      assertEquals(1, files.count());
    }
  }
}
