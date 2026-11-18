package nl.poldersmp.maintenance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import nl.poldersmp.common.nether.BackupService;
import nl.poldersmp.common.nether.ResetPhase;
import nl.poldersmp.common.nether.ResetState;
import nl.poldersmp.common.nether.ResetStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the whole maintenance flow against a disposable fake server folder. No real world is ever touched. */
class MaintenanceRunnerTest {
  static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

  @TempDir Path temp;
  Path root;
  Path nether;
  Path overworld;
  Path primaryNether;
  Path end;
  ResetStateStore store;
  boolean stopped;
  List<Path> launched;
  MaintenanceRunner runner;

  @BeforeEach
  void setUp() throws Exception {
    root = Files.createDirectories(temp.resolve("server")).toRealPath();
    overworld = Files.createDirectories(root.resolve("world/dimensions/minecraft/overworld"));
    Files.createDirectories(overworld.resolve("region"));
    Files.writeString(overworld.resolve("region/r.0.0.mca"), "OVERWORLD-DATA");
    primaryNether = Files.createDirectories(root.resolve("world/dimensions/minecraft/the_nether"));
    Files.createDirectories(primaryNether.resolve("region"));
    Files.writeString(primaryNether.resolve("region/r.0.0.mca"), "PRIMARY-NETHER");
    end = Files.createDirectories(root.resolve("world/dimensions/minecraft/the_end"));
    Files.createDirectories(end.resolve("region"));
    Files.writeString(end.resolve("region/r.0.0.mca"), "END-DATA");
    nether = root.resolve("world/dimensions/polder/resource_nether");
    Files.createDirectories(nether.resolve("region"));
    Files.writeString(nether.resolve("region/r.0.0.mca"), "OLD-NETHER-1");
    Files.writeString(nether.resolve("region/r.0.1.mca"), "OLD-NETHER-2");
    Files.writeString(root.resolve("start.cmd"), "echo start");

    store = new ResetStateStore(root.resolve("plugins/PolderCore/nether-state.properties"));
    stopped = true;
    launched = new ArrayList<>();
    runner = newRunner();
    save(awaitingMaintenance());
  }

  MaintenanceRunner newRunner() {
    ServerProbe probe = () -> new ServerProbe.Result(stopped, List.of(stopped ? "fake: stopped" : "fake: running"));
    Launcher launcher = (script, dir) -> launched.add(script);
    MaintenanceLog log = new MaintenanceLog(root.resolve("logs/nether-maintenance.log"), null, Clock.fixed(NOW, ZoneOffset.UTC));
    return new MaintenanceRunner(root, store, probe, launcher, log, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  ResetState awaitingMaintenance() {
    ResetState s = new ResetState();
    s.currentSeed(1111L);
    s.nextReset(NOW);
    s.worldKey("polder:resource_nether");
    s.worldPath(nether.toString());
    s.protectedPaths(List.of(overworld.toString(), primaryNether.toString(), end.toString()));
    s.backupDirectory("backups/nether");
    s.retentionKeep(3);
    s.launcherPath("start.cmd");
    s.launcherVerified(false);
    s.begin("op-1", NOW);
    for (ResetPhase p : List.of(ResetPhase.LOCKED, ResetPhase.EVACUATED, ResetPhase.SAVED, ResetPhase.AWAITING_MAINTENANCE)) {
      s.moveTo(p);
    }
    return s;
  }

  void save(ResetState s) throws IOException {
    store.save(s);
  }

  ResetState load() throws IOException {
    return store.load();
  }

  String read(Path p) throws IOException {
    return Files.readString(p);
  }

  void assertOtherDimensionsUntouched() throws IOException {
    assertEquals("OVERWORLD-DATA", read(overworld.resolve("region/r.0.0.mca")));
    assertEquals("PRIMARY-NETHER", read(primaryNether.resolve("region/r.0.0.mca")));
    assertEquals("END-DATA", read(end.resolve("region/r.0.0.mca")));
  }

  // ------------------------------------------------------------ happy path

  @Test
  void fullRunArchivesVerifiesAndPreparesANewSeed() throws Exception {
    var out = runner.run();
    assertEquals(MaintenanceRunner.OK, out.exitCode(), out.message());

    ResetState s = load();
    assertEquals(ResetPhase.PREPARED, s.phase());
    assertNotNull(s.pendingSeed());
    assertNotEquals(1111L, s.pendingSeed(), "the new seed differs from the old one");
    assertFalse(Files.exists(nether), "old Nether moved away");

    Path archive = Path.of(s.archivePath());
    assertTrue(archive.startsWith(root.resolve("backups/nether")));
    assertEquals("OLD-NETHER-1", read(archive.resolve("region/r.0.0.mca")));
    assertEquals(List.of(), BackupService.verifyArchive(archive));
    assertTrue(BackupService.isMarkedValid(archive));
    assertOtherDimensionsUntouched();
    assertTrue(read(root.resolve("logs/nether-maintenance.log")).contains("Backup verified"), "operations are logged");
  }

  @Test
  void runningTwiceDoesNotArchiveTwice() throws Exception {
    assertEquals(MaintenanceRunner.OK, runner.run().exitCode());
    Long seed = load().pendingSeed();
    var second = runner.run();
    assertEquals(MaintenanceRunner.OK, second.exitCode());
    assertTrue(second.message().contains("Already prepared"));
    assertEquals(seed, load().pendingSeed(), "duplicate run keeps the prepared seed");
    try (var dirs = Files.list(root.resolve("backups/nether"))) {
      assertEquals(1, dirs.count());
    }
  }

  @Test
  void firstResetWithoutAnExistingNetherStillPreparesASeed() throws Exception {
    BackupService.deleteTree(nether);
    var out = runner.run();
    assertEquals(MaintenanceRunner.OK, out.exitCode(), out.message());
    ResetState s = load();
    assertEquals(ResetPhase.PREPARED, s.phase());
    assertEquals("", s.archivePath());
  }

  // ------------------------------------------------------------ refusals

  @Test
  void refusesWhileTheServerMayStillBeRunning() throws Exception {
    stopped = false;
    var out = runner.run();
    assertEquals(MaintenanceRunner.REFUSED, out.exitCode());
    assertEquals(ResetPhase.AWAITING_MAINTENANCE, load().phase(), "state not changed");
    assertEquals("OLD-NETHER-1", read(nether.resolve("region/r.0.0.mca")), "Nether not touched");
    assertFalse(Files.exists(root.resolve("backups")));
  }

  @Test
  void refusesUnlessThePluginAskedForMaintenance() throws Exception {
    for (ResetPhase phase : List.of(ResetPhase.IDLE, ResetPhase.COUNTDOWN, ResetPhase.SAVED)) {
      ResetState s = awaitingMaintenance();
      ResetState fresh = new ResetState();
      fresh.worldPath(nether.toString());
      fresh.protectedPaths(s.protectedPaths());
      fresh.backupDirectory("backups/nether");
      if (phase != ResetPhase.IDLE) {
        fresh.begin("op-x", NOW);
        fresh.moveTo(ResetPhase.LOCKED);
        if (phase == ResetPhase.SAVED) {
          fresh.moveTo(ResetPhase.EVACUATED);
          fresh.moveTo(ResetPhase.SAVED);
        }
      }
      save(fresh);
      var out = runner.run();
      assertEquals(MaintenanceRunner.REFUSED, out.exitCode(), "phase " + fresh.phase());
      assertEquals("OLD-NETHER-1", read(nether.resolve("region/r.0.0.mca")));
    }
  }

  @Test
  void refusesToTouchTheOverworldEndOrPrimaryNether() throws Exception {
    for (Path bad : List.of(overworld, end, primaryNether, root.resolve("world"), root, root.getParent())) {
      ResetState s = awaitingMaintenance();
      s.worldPath(bad.toString());
      save(s);
      var out = runner.run();
      assertEquals(MaintenanceRunner.REFUSED, out.exitCode(), bad.toString());
      assertEquals(ResetPhase.AWAITING_MAINTENANCE, load().phase());
    }
    assertOtherDimensionsUntouched();
    assertEquals("OLD-NETHER-1", read(nether.resolve("region/r.0.0.mca")));
  }

  @Test
  void refusesWhenTheNetherFolderWasNeverRecorded() throws Exception {
    ResetState s = awaitingMaintenance();
    s.worldPath("");
    save(s);
    assertEquals(MaintenanceRunner.REFUSED, runner.run().exitCode());
  }

  @Test
  void refusesABackupFolderInsideAWorld() throws Exception {
    ResetState s = awaitingMaintenance();
    s.backupDirectory("world/dimensions/minecraft/overworld/backup");
    save(s);
    assertEquals(MaintenanceRunner.REFUSED, runner.run().exitCode());
    assertOtherDimensionsUntouched();
  }

  @Test
  void refusesACorruptStateFileWithoutRepairingIt() throws Exception {
    Files.writeString(store.file(), "phase=GARBAGE\n");
    assertEquals(MaintenanceRunner.REFUSED, runner.run().exitCode());
    assertEquals(MaintenanceRunner.REFUSED, runner.status().exitCode());
    assertEquals("phase=GARBAGE\n", read(store.file()));
  }

  // ------------------------------------------------------------ failure and recovery

  @Test
  void aFailedBackupLeavesTheOldNetherInPlaceAndRecoveryRollsBack() throws Exception {
    // Force a failure: the archive folder the tool wants already exists.
    Files.createDirectories(root.resolve("backups/nether/20261118-100000_op-1"));
    var out = runner.run();
    assertEquals(MaintenanceRunner.FAILED, out.exitCode());
    ResetState s = load();
    assertEquals(ResetPhase.FAILED, s.phase());
    assertTrue(s.maintenance(), "Nether access stays locked while failed");
    assertEquals("OLD-NETHER-1", read(nether.resolve("region/r.0.0.mca")), "original untouched");

    assertEquals(MaintenanceRunner.REFUSED, runner.run().exitCode(), "no new run until recovered");

    var rec = runner.recover();
    assertEquals(MaintenanceRunner.OK, rec.exitCode(), rec.message());
    ResetState after = load();
    assertEquals(ResetPhase.IDLE, after.phase());
    assertEquals("ROLLED_BACK", after.history().get(after.history().size() - 1).outcome());
    assertEquals("OLD-NETHER-1", read(nether.resolve("region/r.0.0.mca")));
  }

  @Test
  void anIncompleteRunIsDetectedAndRecoveryRestoresFromTheBackup() throws Exception {
    // Simulate a crash right after the old Nether was archived but before PREPARED was saved.
    ResetState s = awaitingMaintenance();
    s.moveTo(ResetPhase.MAINTENANCE_RUNNING);
    Path archive = root.resolve("backups/nether/20261118-100000_op-1");
    s.archivePath(archive.toString());
    s.pendingSeed(5555L);
    save(s);
    BackupService.archive(nether, archive);
    BackupService.markValid(archive, "op-1", 1111L);
    assertFalse(Files.exists(nether));

    assertEquals(MaintenanceRunner.REFUSED, runner.run().exitCode(), "incomplete run blocks a new run");
    assertTrue(runner.status().message().contains("did not finish"));

    var rec = runner.recover();
    assertEquals(MaintenanceRunner.OK, rec.exitCode(), rec.message());
    assertEquals("OLD-NETHER-2", read(nether.resolve("region/r.0.1.mca")));
    assertEquals(List.of(), BackupService.verifyArchive(archive), "the backup is kept after recovery");
    assertEquals(ResetPhase.IDLE, load().phase());
    assertOtherDimensionsUntouched();
  }

  @Test
  void recoveryFailsLoudlyWhenBothNetherAndArchiveAreGone() throws Exception {
    ResetState s = awaitingMaintenance();
    s.moveTo(ResetPhase.MAINTENANCE_RUNNING);
    s.archivePath(root.resolve("backups/nether/missing").toString());
    save(s);
    BackupService.deleteTree(nether);
    var rec = runner.recover();
    assertEquals(MaintenanceRunner.FAILED, rec.exitCode());
    assertEquals(ResetPhase.FAILED, load().phase());
  }

  @Test
  void recoveryNeedsAStoppedServerAndSomethingToRecover() throws Exception {
    assertEquals(MaintenanceRunner.REFUSED, runner.recover().exitCode(), "AWAITING_MAINTENANCE is not a failure");
    ResetState s = awaitingMaintenance();
    s.moveTo(ResetPhase.MAINTENANCE_RUNNING);
    save(s);
    stopped = false;
    assertEquals(MaintenanceRunner.REFUSED, runner.recover().exitCode());
    assertEquals(ResetPhase.MAINTENANCE_RUNNING, load().phase());
  }

  // ------------------------------------------------------------ verify, retention, start

  @Test
  void verifyDetectsABackupThatWasDamagedAfterwards() throws Exception {
    runner.run();
    assertEquals(MaintenanceRunner.OK, runner.verify().exitCode());
    Path archive = Path.of(load().archivePath());
    Files.writeString(archive.resolve("region/r.0.0.mca"), "DAMAGED");
    var out = runner.verify();
    assertEquals(MaintenanceRunner.FAILED, out.exitCode());
    assertTrue(out.message().contains("differs"));
  }

  @Test
  void retentionKeepsTheConfiguredNumberAndNeverTheLastValidOne() throws Exception {
    Path backups = Files.createDirectories(root.resolve("backups/nether"));
    for (String name : List.of("20260101-000000_a", "20260102-000000_b", "20260103-000000_c")) {
      Path dir = Files.createDirectories(backups.resolve(name + "/region"));
      Files.writeString(dir.resolve("r.mca"), name);
      BackupService.writeManifest(backups.resolve(name).resolve(BackupService.MANIFEST_NAME), BackupService.scan(backups.resolve(name)));
      BackupService.markValid(backups.resolve(name), name, 1L);
      Files.setLastModifiedTime(backups.resolve(name), java.nio.file.attribute.FileTime.fromMillis(NOW.toEpochMilli() - 1000L * (10 - Integer.parseInt(name.substring(6, 8)))));
    }
    ResetState s = awaitingMaintenance();
    s.retentionKeep(2);
    save(s);
    assertEquals(MaintenanceRunner.OK, runner.run().exitCode());
    try (var dirs = Files.list(backups)) {
      long valid = dirs.filter(BackupService::isMarkedValid).count();
      assertEquals(2, valid, "keep=2 valid backups, including the new one");
    }
    assertTrue(BackupService.isMarkedValid(Path.of(load().archivePath())), "the newest backup survives");

    ResetState zero = load();
    var out = runner.cleanup();
    assertEquals(MaintenanceRunner.OK, out.exitCode());
    assertTrue(BackupService.isMarkedValid(Path.of(zero.archivePath())));
  }

  @Test
  void startNeedsAPreparedResetAndAVerifiedLauncher() throws Exception {
    assertEquals(MaintenanceRunner.REFUSED, runner.start().exitCode(), "not prepared yet");
    runner.run();
    var unverified = runner.start();
    assertEquals(MaintenanceRunner.REFUSED, unverified.exitCode());
    assertTrue(unverified.message().contains("not been verified"));
    assertTrue(launched.isEmpty());

    ResetState s = load();
    s.launcherVerified(true);
    save(s);
    stopped = false;
    assertEquals(MaintenanceRunner.REFUSED, runner.start().exitCode(), "server still running");
    assertTrue(launched.isEmpty());

    stopped = true;
    var ok = runner.start();
    assertEquals(MaintenanceRunner.OK, ok.exitCode(), ok.message());
    assertEquals(List.of(root.resolve("start.cmd")), launched);
  }

  @Test
  void launcherOutsideTheServerFolderIsRefused() throws Exception {
    runner.run();
    ResetState s = load();
    s.launcherVerified(true);
    s.launcherPath("../evil.cmd");
    save(s);
    assertEquals(MaintenanceRunner.REFUSED, runner.start().exitCode());
    assertTrue(launched.isEmpty());
  }
}
