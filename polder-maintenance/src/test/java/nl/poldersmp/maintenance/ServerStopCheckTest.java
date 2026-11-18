package nl.poldersmp.maintenance;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerStopCheckTest {
  @TempDir Path temp;

  @Test
  void unknownIsNotStopped() throws Exception {
    var r = new ServerStopCheck(temp, List.of(temp.resolve("nether"))).check();
    assertFalse(r.stopped(), "no evidence at all must not count as stopped");
  }

  @Test
  void freeLockIsPositiveEvidence() throws Exception {
    Path world = Files.createDirectories(temp.resolve("nether"));
    Files.writeString(world.resolve("session.lock"), "x");
    var r = new ServerStopCheck(temp, List.of(world)).check();
    assertTrue(r.stopped(), r.reasons().toString());
  }

  @Test
  void aHeldLockMeansRunning() throws Exception {
    Path world = Files.createDirectories(temp.resolve("nether"));
    Path lock = Files.writeString(world.resolve("session.lock"), "x");
    try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE);
        FileLock held = ch.lock()) {
      assertNotNull(held);
      var r = new ServerStopCheck(temp, List.of(world)).check();
      assertFalse(r.stopped());
    }
  }

  @Test
  void aBusyGamePortMeansRunningEvenWithoutLocks() throws Exception {
    try (ServerSocket busy = new ServerSocket(0)) {
      Files.writeString(temp.resolve("server.properties"), "server-port=" + busy.getLocalPort() + "\n");
      var r = new ServerStopCheck(temp, List.of()).check();
      assertFalse(r.stopped(), r.reasons().toString());
    }
  }

  @Test
  void aFreeGamePortAloneIsEnoughEvidence() throws Exception {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    Files.writeString(temp.resolve("server.properties"), "server-port=" + port + "\n");
    assertTrue(new ServerStopCheck(temp, List.of()).check().stopped());
  }

  @Test
  void aBrokenPortSettingIsNotEvidence() throws Exception {
    Files.writeString(temp.resolve("server.properties"), "server-port=banana\n");
    assertFalse(new ServerStopCheck(temp, List.of()).check().stopped());
  }
}
