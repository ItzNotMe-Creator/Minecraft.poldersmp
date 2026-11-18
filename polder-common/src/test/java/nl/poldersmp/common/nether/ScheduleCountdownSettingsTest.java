package nl.poldersmp.common.nether;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduleCountdownSettingsTest {
  static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  static final Duration FIVE_DAYS = Duration.ofHours(120);

  // ---- scheduling ----

  @Test
  void defaultIntervalIsFiveDays() {
    assertEquals(120, NetherSettings.defaults().intervalHours());
    assertEquals(NOW.plus(Duration.ofDays(5)), NetherSchedule.first(NOW, FIVE_DAYS));
  }

  @Test
  void nextResetCountsFromCompletionNotFromDueTime() {
    Instant completed = NOW.plus(Duration.ofMinutes(45));
    assertEquals(completed.plus(FIVE_DAYS), NetherSchedule.afterSuccess(completed, FIVE_DAYS));
  }

  @Test
  void dueExactlyAtTheScheduledInstant() {
    assertFalse(NetherSchedule.isDue(NOW.minusMillis(1), NOW));
    assertTrue(NetherSchedule.isDue(NOW, NOW));
    assertTrue(NetherSchedule.isDue(NOW.plusSeconds(5), NOW));
  }

  @Test
  void remainingSecondsRoundUpAndNeverGoNegative() {
    assertEquals(1, NetherSchedule.remainingSeconds(NOW, NOW.plusMillis(1)));
    assertEquals(10, NetherSchedule.remainingSeconds(NOW, NOW.plusSeconds(10)));
    assertEquals(0, NetherSchedule.remainingSeconds(NOW, NOW.minusSeconds(99)));
  }

  @Test
  void resumeGivesPlayersAWarningWindowWhenOverdue() {
    Duration window = Duration.ofMinutes(30);
    assertEquals(NOW.plus(window), NetherSchedule.afterResume(NOW.minusSeconds(3600), NOW, window));
    assertEquals(NOW.plus(window), NetherSchedule.afterResume(NOW.plusSeconds(60), NOW, window));
    Instant far = NOW.plus(Duration.ofHours(10));
    assertEquals(far, NetherSchedule.afterResume(far, NOW, window));
  }

  // ---- countdown milestones ----

  @Test
  void announcesEachDefaultMilestoneOnceInOrder() {
    List<Long> ms = NetherSettings.DEFAULT_MILESTONES;
    assertEquals(List.of(1800L, 600L, 300L, 60L, 10L), ms);
    Set<Long> fired = Countdown.initiallyFired(5 * 24 * 3600L, ms);
    List<Long> announced = new java.util.ArrayList<>();
    for (long remaining = 5 * 24 * 3600L; remaining >= 0; remaining--) {
      OptionalLong due = Countdown.poll(remaining, fired, ms);
      if (due.isPresent()) {
        announced.add(due.getAsLong());
      }
    }
    assertEquals(List.of(1800L, 600L, 300L, 60L, 10L), announced);
  }

  @Test
  void aLateTickAnnouncesOnlyTheClosestMilestone() {
    List<Long> ms = NetherSettings.DEFAULT_MILESTONES;
    Set<Long> fired = new HashSet<>();
    OptionalLong due = Countdown.poll(250, fired, ms); // 1800, 600 and 300 are all behind us
    assertEquals(300L, due.getAsLong());
    assertTrue(Countdown.poll(249, fired, ms).isEmpty());
  }

  @Test
  void startingMidCountdownDoesNotReplayEarlierWarnings() {
    List<Long> ms = NetherSettings.DEFAULT_MILESTONES;
    Set<Long> fired = Countdown.initiallyFired(420, ms); // 7 minutes left
    assertEquals(Set.of(1800L, 600L), fired);
    assertTrue(Countdown.poll(420, fired, ms).isEmpty());
    assertEquals(300L, Countdown.poll(300, fired, ms).getAsLong());
  }

  @Test
  void textIsReadable() {
    assertEquals("30 minutes", Countdown.format(1800));
    assertEquals("1 minute", Countdown.format(60));
    assertEquals("10 seconds", Countdown.format(10));
    assertEquals("1 second", Countdown.format(1));
    assertEquals("2 hours", Countdown.format(7200));
  }

  // ---- settings validation ----

  @Test
  void defaultsAreTheDocumentedSafeOnes() {
    NetherSettings s = NetherSettings.defaults();
    assertTrue(s.enabled());
    assertFalse(s.shutdownAutomation(), "unattended shutdowns are off by default");
    assertFalse(s.launcherVerified());
    assertEquals(3, s.retentionKeep());
    assertTrue(s.requireNewSeed());
    assertTrue(s.blockPrimaryNether());
    assertEquals("polder:resource_nether", s.worldKey());
  }

  @Test
  void shutdownAutomationNeedsAVerifiedLauncher() {
    Map<String, Object> v = new HashMap<>();
    v.put("automation.shutdown-enabled", true);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NetherSettings.fromMap(v));
    assertTrue(e.getMessage().contains("launcher-verified"));
    v.put("automation.launcher-verified", true);
    assertTrue(NetherSettings.fromMap(v).shutdownAutomation());
  }

  @Test
  void invalidValuesAreRejectedWithTheKeyName() {
    for (Map.Entry<String, Object> bad :
        Map.<String, Object>of(
                "interval-hours", 0,
                "backup.keep", 0,
                "enabled", "yes",
                "world.key", "../evil",
                "announcements.seconds-before", List.of(60, 60),
                "interval-hours ", 5)
            .entrySet()) {
      if (bad.getKey().endsWith(" ")) {
        continue;
      }
      Map<String, Object> v = Map.of(bad.getKey(), bad.getValue());
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NetherSettings.fromMap(v), bad.getKey());
      assertFalse(e.getMessage().isBlank());
    }
  }

  @Test
  void customIntervalMilestonesAndMessagesAreRead() {
    Map<String, Object> v = new HashMap<>();
    v.put("interval-hours", 24);
    v.put("announcements.seconds-before", List.of(30, 3600, 120));
    v.put("messages.completed", "done");
    NetherSettings s = NetherSettings.fromMap(v);
    assertEquals(24, s.intervalHours());
    assertEquals(List.of(3600L, 120L, 30L), s.milestonesSeconds());
    assertEquals("done", s.messages().get("completed"));
    assertTrue(s.messages().get("countdown").contains("{time}"));
  }

  // ---- seeds ----

  @Test
  void newSeedNeverRepeatsAnOldOne() {
    long[] script = {5, 5, 9};
    int[] i = {0};
    long seed = NetherSeed.next(Set.of(5L), () -> script[i[0]++]);
    assertEquals(9L, seed);
  }

  @Test
  void realRandomSeedsDiffer() {
    assertNotEquals(NetherSeed.next(Set.of()), NetherSeed.next(Set.of()));
  }
}
