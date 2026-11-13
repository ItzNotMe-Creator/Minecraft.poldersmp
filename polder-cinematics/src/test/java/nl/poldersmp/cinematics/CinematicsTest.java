package nl.poldersmp.cinematics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CinematicsTest {
  @TempDir Path dir;

  @Test
  void futureJournalSchemaIsRefused() throws Exception {
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("intro.db"));
        var q = c.createStatement()) {
      q.execute("CREATE TABLE intro_schema(version INTEGER PRIMARY KEY)");
      q.execute("INSERT INTO intro_schema VALUES(2)");
    }
    try (var db = new IntroStore(dir.resolve("intro.db"))) {
      assertThrows(Exception.class, () -> db.migrate().join());
    }
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("intro.db"));
        var q = c.createStatement();
        var r = q.executeQuery("SELECT version FROM intro_schema")) {
      assertTrue(r.next());
      assertEquals(2, r.getInt(1));
    }
  }

  @Test
  void curvesLandExactlyAndStayFinite() {
    var points =
        List.of(
            new CameraPath.Point(0, 80, 0, 170, 0),
            new CameraPath.Point(10, 90, 5, -170, 30),
            new CameraPath.Point(20, 70, 0, -150, 0));
    for (String curve : List.of("LINEAR", "CATMULL_ROM"))
      for (String ease : List.of("LINEAR", "SMOOTHSTEP")) {
        assertEquals(points.getFirst().x(), CameraPath.sample(points, 0, curve, ease).x());
        assertEquals(points.getLast().x(), CameraPath.sample(points, 1, curve, ease).x());
        for (int i = 0; i <= 2400; i++) {
          var point = CameraPath.sample(points, i / 2400.0, curve, ease);
          assertTrue(
              Double.isFinite(point.x())
                  && Double.isFinite(point.y())
                  && Double.isFinite(point.z()));
        }
      }
  }

  @Test
  void yawTakesShortestPath() {
    assertEquals(180, CameraPath.angle(170, -170, .5), .001);
    assertEquals(-180, CameraPath.angle(-170, 170, .5), .001);
    assertEquals(730, CameraPath.angle(710, 10, 1), .001);
  }

  @Test
  void easingClampsAndSlowsAtEnds() {
    assertEquals(0, CameraPath.ease(-1, "SMOOTHSTEP"));
    assertEquals(1, CameraPath.ease(2, "SMOOTHSTEP"));
    assertTrue(CameraPath.ease(.1, "SMOOTHSTEP") < .1);
    assertTrue(CameraPath.ease(.9, "SMOOTHSTEP") > .9);
    assertThrows(
        IllegalArgumentException.class, () -> CameraPath.sample(List.of(), 0, "LINEAR", "LINEAR"));
  }

  private IntroStore.Saved saved(UUID id, String op) {
    return new IntroStore.Saved(id, op, "ADVENTURE", false, false, .1f, "world", 1, 70, 2, 20, 30);
  }

  @Test
  void journalSurvivesRestartAndPreservesState() throws Exception {
    UUID id = UUID.randomUUID();
    try (var db = new IntroStore(dir.resolve("intro.db"))) {
      db.migrate().join();
      db.save(saved(id, "op1")).join();
      assertThrows(Exception.class, () -> db.save(saved(id, "op2")).join());
    }
    try (var db = new IntroStore(dir.resolve("intro.db"))) {
      db.migrate().join();
      assertEquals(saved(id, "op1"), db.pending(id).join());
      db.finish(saved(id, "wrong"), true, "session").join();
      assertNotNull(db.pending(id).join());
      assertTrue(db.eligible(id, "FIRST_JOIN", "").join());
      db.finish(saved(id, "op1"), true, "s1").join();
      assertNull(db.pending(id).join());
      assertFalse(db.eligible(id, "FIRST_JOIN", "").join());
      assertTrue(db.eligible(id, "EVERY_JOIN", "").join());
      assertFalse(db.eligible(id, "NETWORK_SESSION", "s1").join());
      assertTrue(db.eligible(id, "NETWORK_SESSION", "s2").join());
      assertFalse(db.eligible(id, "NETWORK_SESSION", "").join());
    }
  }

  @Test
  void previewAndFailedIntroDoNotMarkSeen() {
    UUID id = UUID.randomUUID();
    try (var db = new IntroStore(dir.resolve("intro.db"))) {
      db.migrate().join();
      db.save(saved(id, "op")).join();
      db.finish(saved(id, "op"), false, "").join();
      assertTrue(db.eligible(id, "FIRST_JOIN", "").join());
    }
  }
}
