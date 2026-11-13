package nl.poldersmp.core.nether;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NetherTest {
  @TempDir Path temp;
  ResetStore store;
  Path root;

  static void nbt(Path file, long seed) throws Exception {
    try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
      out.writeByte(10);
      out.writeUTF("");
      out.writeByte(10);
      out.writeUTF("Data");
      out.writeByte(10);
      out.writeUTF("WorldGenSettings");
      out.writeByte(4);
      out.writeUTF("seed");
      out.writeLong(seed);
      out.writeByte(0);
      out.writeByte(0);
      out.writeByte(0);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    root = temp.toRealPath();
    Files.writeString(root.resolve("server.properties"), "level-name=world\n");
    for (String name : List.of("world", "world_nether", "world_the_end")) {
      Path p = Files.createDirectory(root.resolve(name));
      nbt(p.resolve("level.dat"), 12);
      Files.writeString(p.resolve("session.lock"), "lock");
    }
    Path data = Files.createDirectories(root.resolve("plugins/PolderCore"));
    store = new ResetStore(data.resolve("nether-reset.db"));
    store.migrate();
    var s = store.initialize(root, "polder_nether", 42, 1000, NetherRules.interval(120), 3);
    makeWorld(s, 42);
    store.change(s, Map.of("fingerprint", "old-terrain"));
  }

  void makeWorld(ResetStore.State s, long seed) throws Exception {
    Path p = root.resolve(s.get("name"));
    Files.createDirectories(p.resolve("DIM-1/region"));
    nbt(p.resolve("level.dat"), seed);
    Files.writeString(p.resolve("session.lock"), "lock");
    Files.writeString(p.resolve("DIM-1/region/r.0.0.mca"), "terrain:" + seed);
    NetherFiles.mark(p, s);
  }

  ResetStore.State waiting() throws Exception {
    var s = store.request(2000, 1, "unrelated-process-start");
    return store.change(s, Map.of("phase", "WAITING_STOP"));
  }

  NetherMaintenance tool() throws Exception {
    return new NetherMaintenance(root);
  }

  @Test
  void realFiveDayScheduleAndRestartPersistence() throws Exception {
    assertEquals(432000000L, NetherRules.interval(120));
    assertEquals(432001000L, store.read().number("next"));
    var reopened = new ResetStore(root.resolve("plugins/PolderCore/nether-reset.db"));
    reopened.migrate();
    assertEquals(store.read().get("next"), reopened.read().get("next"));
    assertThrows(IllegalArgumentException.class, () -> NetherRules.interval(0));
  }

  @Test
  void allWarningsCrossThresholdAndDoNotRepeat() {
    var levels = List.of(1800, 600, 300, 60, 10);
    assertEquals(List.of(1800), NetherRules.warnings(1801, 1800, levels));
    assertTrue(NetherRules.warnings(1800, 1799, levels).isEmpty());
    assertEquals(List.of(600, 300, 60, 10), NetherRules.warnings(601, 9, levels));
  }

  @Test
  void countdownAndPauseDoNotReschedule() throws Exception {
    var s = store.read();
    long deadline = s.number("next");
    s = store.change(s, Map.of("paused", "true"));
    assertThrows(IOException.class, () -> store.request(3000, 1, "x"));
    s = store.change(s, Map.of("paused", "false"));
    assertEquals(deadline, s.number("next"));
  }

  @Test
  void duplicateRequestsAndStaleTransitionsRefused() throws Exception {
    var before = store.read();
    var a = waiting();
    assertNotEquals(a.number("old-seed"), a.number("new-seed"));
    assertThrows(IOException.class, () -> store.request(3, 1, "x"));
    assertThrows(IOException.class, () -> store.change(before, Map.of("phase", "IDLE")));
    assertEquals(1, store.history().size());
  }

  @Test
  void immutableIdentityCannotChange() throws Exception {
    var s = store.read();
    assertThrows(IOException.class, () -> store.change(s, Map.of("name", "world")));
  }

  @Test
  void completeArchiveAndNewSeedWithoutTouchingOtherDimensions() throws Exception {
    Map<Path, String> originals = new HashMap<>();
    for (String n : List.of("world", "world_nether", "world_the_end")) {
      Path p = root.resolve(n).resolve("level.dat");
      originals.put(p, NetherFiles.sha(p));
    }
    waiting();
    tool().apply();
    var s = store.read();
    assertEquals("GENERATING", s.phase());
    assertFalse(Files.exists(root.resolve("polder_nether")));
    NetherFiles.verify(Path.of(s.get("backup")));
    assertTrue(Files.exists(Path.of(s.get("archive")).resolve("DIM-1/region/r.0.0.mca")));
    for (var e : originals.entrySet()) assertEquals(e.getValue(), NetherFiles.sha(e.getKey()));
    makeWorld(s, s.number("new-seed"));
    s = store.change(s, Map.of("phase", "VERIFYING"));
    var done = store.complete(s, 10000, "different-terrain");
    assertEquals("IDLE", done.phase());
    assertEquals(10000, done.number("last"));
    assertEquals(432010000L, done.number("next"));
    assertTrue(store.history().getFirst().contains("SUCCESS"));
    var old = s;
    assertThrows(IOException.class, () -> store.complete(old, 10001, "again"));
  }

  @Test
  void loadedSessionLockRejectsReset() throws Exception {
    waiting();
    try (var c = FileChannel.open(root.resolve("world/session.lock"), StandardOpenOption.WRITE);
        var l = c.lock()) {
      assertThrows(Exception.class, () -> tool().apply());
      assertTrue(Files.exists(root.resolve("polder_nether/level.dat")));
    }
  }

  @Test
  void runningProcessRejectedEvenAfterPluginDisables() throws Exception {
    var s =
        store.request(
            1,
            ProcessHandle.current().pid(),
            ProcessHandle.current().info().startInstant().orElseThrow().toString());
    store.change(s, Map.of("phase", "WAITING_STOP"));
    assertThrows(IOException.class, () -> tool().apply());
    assertEquals("WAITING_STOP", store.read().phase());
  }

  @Test
  void repeatedOfflineApplyDoesNotArchiveTwice() throws Exception {
    waiting();
    tool().apply();
    String backup = store.read().get("backup");
    assertThrows(IOException.class, () -> tool().apply());
    assertEquals(backup, store.read().get("backup"));
  }

  @Test
  void backupMutationDetectedAndRecoveryRefused() throws Exception {
    waiting();
    tool().apply();
    var s = store.read();
    Path b = Path.of(s.get("backup"));
    Files.writeString(b.resolve("snapshot/DIM-1/region/r.0.0.mca"), "corrupted");
    assertThrows(IOException.class, () -> NetherFiles.verify(b));
    assertThrows(IOException.class, () -> tool().recover(s.get("operation")));
    assertFalse(Files.exists(root.resolve("polder_nether")));
  }

  @Test
  void recoveryRestoresCompleteOldNetherAndPauses() throws Exception {
    waiting();
    tool().apply();
    var s = store.read();
    makeWorld(s, s.number("new-seed"));
    tool().recover(s.get("operation"));
    s = store.read();
    assertEquals("VERIFYING", s.phase());
    assertTrue(s.flag("rollback"));
    assertEquals(42, NbtSeed.read(root.resolve("polder_nether/level.dat")));
    var done = store.complete(s, 9000, "old-terrain");
    assertTrue(done.flag("paused"));
    assertEquals(0, done.number("last"));
    assertTrue(store.history().getFirst().contains("ROLLED_BACK"));
  }

  @Test
  void interruptedBackupCanRecoverUntouchedOriginal() throws Exception {
    var s = waiting();
    store.change(s, Map.of("phase", "BACKUP"));
    tool().recover(s.get("operation"));
    assertEquals(42, NbtSeed.read(root.resolve("polder_nether/level.dat")));
    assertEquals("VERIFYING", store.read().phase());
  }

  @Test
  void incorrectOperationIdCannotRecover() throws Exception {
    var s = waiting();
    assertThrows(IOException.class, () -> tool().recover(UUID.randomUUID().toString()));
    assertEquals(s.phase(), store.read().phase());
  }

  @Test
  void extraAndMissingFilesInvalidateManifest() throws Exception {
    waiting();
    tool().apply();
    Path b = Path.of(store.read().get("backup"));
    Files.writeString(b.resolve("snapshot/extra"), "extra");
    assertThrows(IOException.class, () -> NetherFiles.verify(b));
    Files.delete(b.resolve("snapshot/extra"));
    Files.delete(b.resolve("snapshot/level.dat"));
    assertThrows(IOException.class, () -> NetherFiles.verify(b));
  }

  @Test
  void targetPathTraversalAndReservedPrimaryRefused() throws Exception {
    Properties p = (Properties) store.read().values().clone();
    p.setProperty("name", "../world");
    assertThrows(IOException.class, () -> NetherFiles.target(new ResetStore.State(0, p), false));
    Files.writeString(root.resolve("server.properties"), "level-name=polder_nether\n");
    assertThrows(IOException.class, () -> NetherFiles.target(store.read(), true));
  }

  @Test
  void symlinksAndMixedDimensionStorageRejected() throws Exception {
    Path external = Files.createDirectories(temp.resolve("external"));
    Path link = root.resolve("polder_nether/link");
    if (!System.getProperty("os.name").startsWith("Windows")) {
      Files.createSymbolicLink(link, external);
      assertThrows(IOException.class, () -> NetherFiles.tree(root.resolve("polder_nether")));
      Files.delete(link);
    }
    Files.createDirectory(root.resolve("polder_nether/DIM1"));
    assertThrows(IOException.class, () -> NetherFiles.target(store.read(), true));
  }

  @Test
  void markerMismatchRefused() throws Exception {
    Files.writeString(
        root.resolve("polder_nether/" + NetherFiles.MARKER), "identity=wrong\nname=polder_nether");
    assertThrows(IOException.class, () -> NetherFiles.target(store.read(), true));
  }

  @Test
  void retentionKeepsLatestValidRecoveryAndPendingBackup() throws Exception {
    for (int i = 0; i < 5; i++) {
      var s = waiting();
      tool().apply();
      s = store.read();
      makeWorld(s, s.number("new-seed"));
      s = store.change(s, Map.of("phase", "VERIFYING"));
      store.complete(s, 10000 + i, "terrain" + i);
    }
    tool().prune();
    long exists = store.successfulBackups().stream().filter(Files::exists).count();
    assertEquals(3, exists);
    NetherFiles.verify(store.successfulBackups().getFirst());
  }

  @Test
  void futureSchemaRejected() throws Exception {
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + root.resolve("plugins/PolderCore/nether-reset.db"));
        var q = c.createStatement()) {
      q.execute("INSERT INTO nether_schema VALUES(99)");
    }
    assertThrows(IOException.class, store::migrate);
  }

  @Test
  void savedSeedReaderRejectsInvalidData() throws Exception {
    Path p = root.resolve("bad.dat");
    Files.writeString(p, "not NBT");
    assertThrows(IOException.class, () -> NbtSeed.read(p));
    assertEquals(42, NbtSeed.read(root.resolve("polder_nether/level.dat")));
  }

  @Test
  void evacuationMovesOnlyNetherAndWaitsForConfirmation() throws Exception {
    World n = mock(World.class), w = mock(World.class);
    when(n.getEnvironment()).thenReturn(World.Environment.NETHER);
    when(w.getEnvironment()).thenReturn(World.Environment.NORMAL);
    Player a = mock(Player.class), b = mock(Player.class);
    when(a.getWorld()).thenReturn(n);
    when(b.getWorld()).thenReturn(w);
    Location safe = new Location(w, 0, 70, 0);
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    when(a.teleportAsync(any(), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(pending);
    var result = NetherEvacuation.move(List.of(a, b), safe);
    assertFalse(result.isDone());
    pending.complete(true);
    assertTrue(result.get());
    verify(b, never()).teleportAsync(any(), any(PlayerTeleportEvent.TeleportCause.class));
  }

  @Test
  void cancelledEvacuationCannotAuthorizeShutdown() throws Exception {
    World n = mock(World.class);
    when(n.getEnvironment()).thenReturn(World.Environment.NETHER);
    Player p = mock(Player.class);
    when(p.getWorld()).thenReturn(n);
    when(p.teleportAsync(any(), any(PlayerTeleportEvent.TeleportCause.class)))
        .thenReturn(CompletableFuture.completedFuture(false));
    assertFalse(NetherEvacuation.move(List.of(p), new Location(n, 0, 70, 0)).get());
  }

  @Test
  void unsafeEvacuationFloorOrObstructionRejected() {
    World w = mock(World.class);
    var border = mock(WorldBorder.class);
    when(w.getEnvironment()).thenReturn(World.Environment.NORMAL);
    when(w.getWorldBorder()).thenReturn(border);
    when(border.isInside(any())).thenReturn(true);
    when(w.getMinHeight()).thenReturn(-64);
    when(w.getMaxHeight()).thenReturn(320);
    Block floor = mock(Block.class), air = mock(Block.class);
    when(w.getBlockAt(any(Location.class)))
        .thenAnswer(call -> ((Location) call.getArgument(0)).getBlockY() == 69 ? floor : air);
    when(w.getBlockAt(0, 69, 0)).thenReturn(floor);
    when(w.getBlockAt(0, 70, 0)).thenReturn(air);
    when(w.getBlockAt(0, 71, 0)).thenReturn(air);
    Material solid = mock(Material.class), empty = mock(Material.class);
    when(solid.isSolid()).thenReturn(true);
    when(empty.isAir()).thenReturn(true);
    when(floor.getType()).thenReturn(solid);
    when(air.getType()).thenReturn(empty);
    Location l = new Location(w, 0, 70, 0);
    assertTrue(NetherManager.safe(l));
    when(solid.isSolid()).thenReturn(false);
    assertFalse(NetherManager.safe(l));
  }

  @Test
  void invalidStateTransitionsCannotSkipBackup() throws Exception {
    var s = store.read();
    assertThrows(IOException.class, () -> store.change(s, Map.of("phase", "GENERATING")));
    assertThrows(IOException.class, () -> store.complete(s, 10000, "fake"));
  }

  @Test
  void concurrentResetRequestsHaveOneOperation() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      var calls =
          List.<Callable<Boolean>>of(
              () -> {
                try {
                  store.request(1, 1, "x");
                  return true;
                } catch (Exception e) {
                  return false;
                }
              },
              () -> {
                try {
                  store.request(2, 1, "x");
                  return true;
                } catch (Exception e) {
                  return false;
                }
              });
      long wins = 0;
      for (var f : workers.invokeAll(calls)) if (f.get()) wins++;
      assertEquals(1, wins);
      assertEquals(1, store.history().size());
    }
  }

  @Test
  void warningReceiptSurvivesReopeningDatabase() throws Exception {
    var s = store.read();
    store.change(s, Map.of("warning-1800", s.get("next")));
    var reopened = new ResetStore(root.resolve("plugins/PolderCore/nether-reset.db"));
    assertEquals(s.get("next"), reopened.read().get("warning-1800"));
  }

  @Test
  void failedGenerationKeepsRecoveryMetadata() throws Exception {
    waiting();
    tool().apply();
    var s = store.read();
    store.failure(s, new IOException("generation test failure"));
    var failed = store.read();
    assertEquals("FAILED", failed.phase());
    assertEquals(s.get("operation"), failed.get("operation"));
    assertEquals(s.get("backup"), failed.get("backup"));
    tool().recover(failed.get("operation"));
    assertEquals(42, NbtSeed.read(root.resolve("polder_nether/level.dat")));
  }

  @Test
  void selfConsistentBackupMustStillMatchOriginalSource() throws Exception {
    Path source = root.resolve("polder_nether"), copy = root.resolve("verification-copy");
    NetherFiles.copy(source, copy);
    NetherFiles.verifySource(source, copy, Map.of());
    Files.writeString(copy.resolve("DIM-1/region/r.0.0.mca"), "wrong but fully readable content");
    assertThrows(IOException.class, () -> NetherFiles.verifySource(source, copy, Map.of()));
  }
}
