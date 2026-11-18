package nl.poldersmp.common.nether;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Path safety, backup verification and retention, all on disposable temporary folders. */
class PathBackupTest {
  @TempDir Path temp;
  Path root;
  Path overworld;
  Path end;
  Path primaryNether;
  Path nether;
  Path backups;

  @BeforeEach
  void layout() throws Exception {
    root = Files.createDirectories(temp.resolve("server")).toRealPath();
    overworld = Files.createDirectories(root.resolve("world/dimensions/minecraft/overworld/region"));
    end = Files.createDirectories(root.resolve("world/dimensions/minecraft/the_end/region"));
    primaryNether = Files.createDirectories(root.resolve("world/dimensions/minecraft/the_nether/region"));
    nether = root.resolve("world/dimensions/polder/resource_nether");
    backups = root.resolve("backups/nether");
  }

  List<Path> protectedDirs() {
    return List.of(overworld.getParent(), end.getParent(), primaryNether.getParent(), root.resolve("plugins"), backups);
  }

  List<Path> protectedDirsExact() {
    return List.of(overworld.getParent(), end.getParent(), primaryNether.getParent(), root.resolve("plugins"));
  }

  void fillNether(String content) throws IOException {
    Files.createDirectories(nether.resolve("region"));
    Files.writeString(nether.resolve("region/r.0.0.mca"), content);
    Files.writeString(nether.resolve("region/r.0.1.mca"), content + "-b");
    Files.createDirectories(nether.resolve("data"));
    Files.writeString(nether.resolve("data/x.dat"), "d");
    Files.writeString(nether.resolve("session.lock"), "lock");
  }

  // ---------------- path safety ----------------

  @Test
  void acceptsTheRealNetherFolderEvenBeforeItExists() {
    assertEquals(nether, PathGuard.requireSafeDimensionDir(root, nether, protectedDirsExact()));
  }

  @Test
  void acceptsAFolderWithNetherContent() throws Exception {
    fillNether("a");
    assertEquals(nether, PathGuard.requireSafeDimensionDir(root, root.relativize(nether), protectedDirsExact()));
  }

  @Test
  void refusesOverworldEndAndPrimaryNether() {
    for (Path bad : List.of(overworld.getParent(), end.getParent(), primaryNether.getParent())) {
      assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, bad, protectedDirsExact()));
    }
  }

  @Test
  void refusesParentsAndChildrenOfProtectedFolders() {
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, root.resolve("world"), protectedDirsExact()));
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, overworld.resolve("region"), protectedDirsExact()));
  }

  @Test
  void refusesTheServerRootAndPathsOutsideIt() {
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, root, protectedDirsExact()));
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, root.getParent(), protectedDirsExact()));
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, temp.resolve("elsewhere"), protectedDirsExact()));
  }

  @Test
  void refusesDotDotTricks() {
    Path trick = root.resolve("world/dimensions/polder/../minecraft/overworld");
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, trick, protectedDirsExact()));
  }

  @Test
  void refusesAFolderWithUnknownContent() throws Exception {
    Files.createDirectories(nether);
    Files.writeString(nether.resolve("my-holiday-photos.zip"), "x");
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, nether, protectedDirsExact()));
  }

  @Test
  void refusesSymbolicLinks() throws Exception {
    Path real = Files.createDirectories(root.resolve("elsewhere-inside/region"));
    Files.createDirectories(nether.getParent());
    try {
      Files.createSymbolicLink(nether, real.getParent());
    } catch (UnsupportedOperationException | IOException e) {
      return; // platform without symlink permission (some Windows setups): nothing to test
    }
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeDimensionDir(root, nether, protectedDirsExact()));
  }

  @Test
  void backupFolderMustNotOverlapTheWorld() {
    assertEquals(backups, PathGuard.requireSafeBackupDir(root, backups, nether, protectedDirsExact()));
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeBackupDir(root, nether.resolve("backup"), nether, protectedDirsExact()));
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeBackupDir(root, overworld.resolve("backup"), nether, protectedDirsExact()));
    assertThrows(PathGuard.UnsafePathException.class, () -> PathGuard.requireSafeBackupDir(root, root, nether, protectedDirsExact()));
  }

  // ---------------- backup verification ----------------

  @Test
  void archiveMovesTheFolderAndTheCopyVerifies() throws Exception {
    fillNether("original");
    Path archive = backups.resolve("20261109-100000_op1");
    BackupService.Manifest manifest = BackupService.archive(nether, archive);

    assertFalse(Files.exists(nether), "the old Nether was moved away");
    assertEquals(3, manifest.files().size(), "session.lock is not archived");
    assertTrue(Files.exists(archive.resolve("region/r.0.0.mca")));
    assertEquals(List.of(), BackupService.verifyArchive(archive));
    assertFalse(Files.exists(backups.resolve("20261109-100000_op1.manifest")), "sidecar manifest moved inside");
  }

  @Test
  void verificationDetectsDamage() throws Exception {
    fillNether("original");
    Path archive = backups.resolve("a1");
    BackupService.archive(nether, archive);

    Files.writeString(archive.resolve("region/r.0.0.mca"), "tampered");
    assertTrue(BackupService.verifyArchive(archive).stream().anyMatch(p -> p.contains("differs")));

    Files.delete(archive.resolve("data/x.dat"));
    assertTrue(BackupService.verifyArchive(archive).stream().anyMatch(p -> p.startsWith("missing")));

    Files.writeString(archive.resolve("extra.txt"), "?");
    assertTrue(BackupService.verifyArchive(archive).stream().anyMatch(p -> p.contains("extra")));
  }

  @Test
  void archiveWithoutManifestCannotBeVerified() throws Exception {
    Path lonely = Files.createDirectories(backups.resolve("lonely"));
    assertFalse(BackupService.verifyArchive(lonely).isEmpty());
    assertFalse(BackupService.isMarkedValid(lonely));
  }

  @Test
  void refusesToOverwriteAnExistingArchiveAndKeepsTheOriginal() throws Exception {
    fillNether("original");
    Path archive = Files.createDirectories(backups.resolve("exists"));
    assertThrows(IOException.class, () -> BackupService.archive(nether, archive));
    assertTrue(Files.exists(nether.resolve("region/r.0.0.mca")), "original untouched");
  }

  @Test
  void refusesToArchiveSomethingThatIsNotThere() {
    assertThrows(IOException.class, () -> BackupService.archive(nether, backups.resolve("x")));
  }

  @Test
  void validMarkerRequiresManifestAndStatus() throws Exception {
    fillNether("o");
    Path archive = backups.resolve("ok");
    BackupService.archive(nether, archive);
    assertFalse(BackupService.isMarkedValid(archive));
    BackupService.markValid(archive, "op", 5L);
    assertTrue(BackupService.isMarkedValid(archive));
  }

  @Test
  void restoreCopiesBackVerifiedAndKeepsTheArchive() throws Exception {
    fillNether("original");
    Path archive = backups.resolve("r1");
    BackupService.archive(nether, archive);
    BackupService.restore(archive, nether);
    assertEquals("original", Files.readString(nether.resolve("region/r.0.0.mca")));
    assertFalse(Files.exists(nether.resolve(BackupService.MANIFEST_NAME)), "no tool files in the live world");
    assertTrue(Files.exists(archive.resolve("region/r.0.0.mca")), "backup is kept");
    assertEquals(List.of(), BackupService.verifyArchive(archive));
  }

  @Test
  void restoreRefusesDamagedArchiveAndExistingTarget() throws Exception {
    fillNether("original");
    Path archive = backups.resolve("r2");
    BackupService.archive(nether, archive);
    assertThrows(IOException.class, () -> BackupService.restore(archive, primaryNether.getParent()));
    Files.writeString(archive.resolve("region/r.0.0.mca"), "bad");
    assertThrows(IOException.class, () -> BackupService.restore(archive, nether));
    assertFalse(Files.exists(nether), "nothing half-restored is left behind");
  }

  // ---------------- retention ----------------

  RetentionPolicy.BackupInfo info(String name, long age, boolean valid) {
    return new RetentionPolicy.BackupInfo(backups.resolve(name), 1_000_000L - age, valid);
  }

  @Test
  void keepsTheNewestThreeValidBackupsByDefault() {
    List<RetentionPolicy.BackupInfo> all = new ArrayList<>(List.of(info("d", 40, true), info("a", 10, true), info("c", 30, true), info("b", 20, true), info("e", 50, true)));
    List<String> doomed = RetentionPolicy.selectForDeletion(all, 3).stream().map(b -> b.dir().getFileName().toString()).toList();
    assertEquals(List.of("e", "d"), doomed);
  }

  @Test
  void neverDeletesTheLatestValidBackupEvenWithKeepZero() {
    List<RetentionPolicy.BackupInfo> all = List.of(info("new", 1, true), info("old", 99, true));
    List<String> doomed = RetentionPolicy.selectForDeletion(all, 0).stream().map(b -> b.dir().getFileName().toString()).toList();
    assertEquals(List.of("old"), doomed);
  }

  @Test
  void invalidBackupsAreNeverDeletedAutomaticallyAndDoNotCount() {
    List<RetentionPolicy.BackupInfo> all = List.of(info("broken-new", 1, false), info("good-1", 10, true), info("good-2", 20, true));
    assertEquals(List.of(), RetentionPolicy.selectForDeletion(all, 2));
    assertEquals(1, RetentionPolicy.selectForDeletion(all, 1).size());
  }

  @Test
  void nothingIsDeletedWhenThereAreFewerThanKeep() {
    assertEquals(List.of(), RetentionPolicy.selectForDeletion(List.of(info("a", 1, true)), 3));
    assertEquals(List.of(), RetentionPolicy.selectForDeletion(List.of(), 3));
  }
}
