package nl.poldersmp.lobby;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import nl.poldersmp.lobby.network.LobbyNetwork;
import org.junit.jupiter.api.Test;

class LobbyRulesTest {
  @Test
  void providerSnapshotsRejectMalformedValuesAndCopyLists() {
    Map<String, Object> broken = new HashMap<>();
    broken.put("online", null);
    assertTrue(LobbyNetwork.sanitize(broken).isEmpty());
    assertTrue(LobbyNetwork.sanitize(Map.of("players", Double.NaN)).isEmpty());
    assertTrue(LobbyNetwork.sanitize(Map.of("king", "a".repeat(161))).isEmpty());
    assertTrue(LobbyNetwork.sanitize(Map.of("kills", List.of(1))).isEmpty());
    ArrayList<String> list = new ArrayList<>(List.of("Alice 10"));
    var snapshot = LobbyNetwork.sanitize(Map.of("kills", list));
    list.add("Bob 5");
    assertEquals(List.of("Alice 10"), snapshot.get("kills"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("king", "bad"));
  }

  @Test
  void activitiesHaveSingleOwner() {
    Activities a = new Activities();
    UUID id = UUID.randomUUID();
    assertTrue(a.acquire(id, "PARKOUR"));
    assertFalse(a.acquire(id, "CINEMATIC"));
    a.release(id, "CINEMATIC");
    assertTrue(a.busy(id));
    a.release(id, "PARKOUR");
    assertFalse(a.busy(id));
    assertTrue(a.acquire(id, "CINEMATIC"));
    a.clear(id);
    assertFalse(a.busy(id));
  }

  @Test
  void accessFailsClosedForMissingOfflineAndLocked() {
    assertFalse(LobbyNetwork.allowed(Map.of()));
    assertFalse(LobbyNetwork.allowed(Map.of("online", false, "locked", false)));
    assertFalse(LobbyNetwork.allowed(Map.of("online", true, "locked", true)));
    assertFalse(LobbyNetwork.allowed(Map.of("online", true)));
    assertTrue(LobbyNetwork.allowed(Map.of("online", true, "locked", false)));
  }

  @Test
  void staleAndClockReversalAreRejected() {
    assertTrue(LobbyNetwork.fresh(1000, 900, 100));
    assertFalse(LobbyNetwork.fresh(1001, 900, 100));
    assertFalse(LobbyNetwork.fresh(100, 900, 100));
    assertFalse(LobbyNetwork.fresh(100, 0, 100));
  }

  @Test
  void timerAndSequentialCheckpoints() {
    assertEquals(2500, Parkour.elapsed(1000000000L, 3000000000L, 500));
    assertEquals(500, Parkour.elapsed(3000000000L, 1000000000L, 500));
    var checkpoints = new Parkour.Checkpoints(3);
    assertFalse(checkpoints.visit(2));
    assertFalse(checkpoints.complete());
    assertTrue(checkpoints.visit(0));
    assertFalse(checkpoints.visit(0));
    assertFalse(checkpoints.visit(2));
    assertTrue(checkpoints.visit(1));
    assertTrue(checkpoints.visit(2));
    assertTrue(checkpoints.complete());
    assertFalse(checkpoints.visit(3));
    assertFalse(LobbyEvents.due(1000, 0));
    assertFalse(LobbyEvents.due(999, 1000));
    assertTrue(LobbyEvents.due(1000, 1000));
  }
}
