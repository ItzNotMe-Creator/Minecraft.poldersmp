package nl.poldersmp.core.teams;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TeamTest {
  @TempDir Path temp;
  Database db;
  TeamStore store;
  UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

  @BeforeEach
  void init() {
    db = new Database(temp.resolve("state.db"));
    db.initialize().join();
    store = new TeamStore(db, new TeamStore.Limits(10, 2, 3, 2, 1000, 10000, 5000, 2000, 3000));
    store.migrate().join();
  }

  @AfterEach
  void close() {
    db.close();
  }

  UUID create(UUID p, String n) {
    return store.create(p, n, 1000).join();
  }

  void join(UUID leader, UUID member) {
    store.invite(leader, member, 1000).join();
    store.accept(member, null, 1100).join();
  }

  @Test
  void createUsesUuidAndPersistsRoles() {
    UUID id = create(a, "Alpha");
    var s = store.snapshot().join();
    assertEquals(id, s.members().get(a).team());
    assertEquals("LEADER", s.members().get(a).role());
    assertEquals(a, s.teams().get(id).leader());
    assertThrows(CompletionException.class, () -> store.create(a, "Again", 1200).join());
  }

  @Test
  void namesAreCaseInsensitiveAndValidated() {
    create(a, "Alpha");
    assertThrows(CompletionException.class, () -> store.create(b, "alpha", 1000).join());
    assertThrows(CompletionException.class, () -> store.create(b, "bad name", 1000).join());
  }

  @Test
  void invitationsExpireAndDeny() {
    create(a, "Alpha");
    store.invite(a, b, 1000).join();
    assertThrows(CompletionException.class, () -> store.accept(b, null, 2000).join());
    store.invite(a, b, 2100).join();
    store.deny(b).join();
    assertThrows(CompletionException.class, () -> store.accept(b, null, 2150).join());
  }

  @Test
  void acceptEnforcesTeamSizeAndOneMembership() {
    create(a, "Alpha");
    store.invite(a, b, 1000).join();
    store.invite(a, c, 1000).join();
    store.accept(b, null, 1100).join();
    assertThrows(CompletionException.class, () -> store.accept(c, null, 1101).join());
    assertThrows(CompletionException.class, () -> store.accept(b, null, 1101).join());
  }

  @Test
  void concurrentAcceptCannotOverfill() {
    create(a, "Alpha");
    store.invite(a, b, 1000).join();
    store.invite(a, c, 1000).join();
    var first = store.accept(b, null, 1100);
    var second = store.accept(c, null, 1100);
    int success = 0;
    for (var f : List.of(first, second))
      try {
        f.join();
        success++;
      } catch (CompletionException ignored) {
      }
    assertEquals(1, success);
    assertEquals(2, store.snapshot().join().members().size());
  }

  @Test
  void leadershipTransferAndHierarchy() {
    var id = create(a, "Alpha");
    join(a, b);
    assertThrows(CompletionException.class, () -> store.leave(a, 1200).join());
    assertThrows(CompletionException.class, () -> store.manage(b, a, "kick", 1200).join());
    store.manage(a, b, "promote", 1200).join();
    assertEquals("OFFICER", store.snapshot().join().members().get(b).role());
    store.manage(a, b, "promote", 1300).join();
    assertEquals(b, store.snapshot().join().teams().get(id).leader());
    store.leave(a, 1400).join();
    assertFalse(store.snapshot().join().members().containsKey(a));
  }

  @Test
  void combatBlocksJoiningLeavingAndDisband() {
    create(a, "Alpha");
    store.invite(a, b, 1000).join();
    store.combat(a, b, 3000).join();
    assertThrows(CompletionException.class, () -> store.accept(b, null, 1500).join());
    assertThrows(CompletionException.class, () -> store.disband(a, 1500).join());
    store.invite(a, b, 3001).join();
    store.accept(b, null, 3100).join();
    store.combat(a, b, 5000).join();
    assertThrows(CompletionException.class, () -> store.leave(b, 3200).join());
    store.leave(b, 5001).join();
  }

  @Test
  void disbandPreservesCreationCooldownAndReleasesName() {
    create(a, "Alpha");
    store.disband(a, 1100).join();
    assertThrows(CompletionException.class, () -> store.create(a, "Other", 1200).join());
    store.create(b, "alpha", 1200).join();
    assertTrue(store.snapshot().join().members().containsKey(b));
  }

  @Test
  void alliesNeedReciprocalConsentAndFriendlyFireIsExplicit() {
    UUID ta = create(a, "Alpha"), tb = create(b, "Beta");
    store.relation(a, tb, "ALLY", false, 1000).join();
    assertFalse(store.snapshot().join().friendly(a, b));
    store.relation(b, ta, "ALLY", false, 1100).join();
    var s = store.snapshot().join();
    assertTrue(s.friendly(a, b));
    assertFalse(s.damage(a, b, false));
    assertTrue(s.damage(a, b, true));
    store.relation(a, tb, "ALLY", false, 1150).join();
    store.relation(b, ta, "ALLY", false, 1160).join();
    assertEquals(
        1,
        store.snapshot().join().relations().stream().filter(r -> r.kind().equals("ALLY")).count());
    store.relation(a, tb, "ALLY", true, 1200).join();
    assertFalse(store.snapshot().join().friendly(a, b));
  }

  @Test
  void sameTeamFirePermission() {
    create(a, "Alpha");
    join(a, b);
    assertFalse(store.snapshot().join().damage(a, b, false));
    store.fire(a, true).join();
    assertTrue(store.snapshot().join().damage(a, b, false));
    assertTrue(store.snapshot().join().friendly(a, b));
  }

  @Test
  void warScoresConfirmedUniqueKillsAndAwardsOnce() {
    UUID ta = create(a, "Alpha"), tb = create(b, "Beta");
    store.relation(a, tb, "WAR", false, 1000).join();
    store.relation(b, ta, "WAR", false, 1100).join();
    UUID death = UUID.randomUUID();
    store.death(death, a, b, 1500).join();
    store.death(death, a, b, 1500).join();
    store.death(UUID.randomUUID(), a, b, 1600).join();
    store.expire(7000).join();
    store.expire(8000).join();
    var s = store.snapshot().join();
    assertEquals(1, s.teams().get(ta).kills());
    assertEquals(1, s.teams().get(tb).deaths());
    assertEquals(1, s.teams().get(ta).wins());
    assertEquals("FINISHED", s.relations().getFirst().kind());
  }

  @Test
  void teammateAndAllyKillsNeverFarmStats() {
    UUID ta = create(a, "Alpha"), tb = create(c, "Beta");
    join(a, b);
    store.death(UUID.randomUUID(), a, b, 2000).join();
    store.relation(a, tb, "ALLY", false, 2000).join();
    store.relation(c, ta, "ALLY", false, 2100).join();
    store.death(UUID.randomUUID(), a, c, 2200).join();
    assertEquals(0, store.snapshot().join().teams().get(ta).kills());
  }

  @Test
  void activeWarBlocksDisband() {
    UUID ta = create(a, "Alpha"), tb = create(b, "Beta");
    store.relation(a, tb, "WAR", false, 1000).join();
    store.relation(b, ta, "WAR", false, 1100).join();
    assertThrows(CompletionException.class, () -> store.disband(a, 1200).join());
    store.expire(6200).join();
    store.disband(a, 6300).join();
    assertFalse(store.snapshot().join().teams().containsKey(ta));
  }

  @Test
  void snapshotRestartAndFutureSchema() {
    create(a, "Alpha");
    var fresh = new TeamStore(db, new TeamStore.Limits(10, 2, 3, 2, 1000, 10000, 5000, 2000, 3000));
    fresh.migrate().join();
    assertEquals("LEADER", fresh.snapshot().join().members().get(a).role());
    db.submit(
            c -> {
              c.createStatement().execute("INSERT INTO teams_schema VALUES(99)");
              return null;
            })
        .join();
    assertThrows(CompletionException.class, () -> fresh.migrate().join());
  }

  @Test
  void kingNeedsHostileParticipantsNotAlliedFarming() {
    UUID ta = create(a, "Alpha"), tb = create(b, "Beta");
    store.relation(a, tb, "ALLY", false, 1000).join();
    store.relation(b, ta, "ALLY", false, 1100).join();
    var snapshot = store.snapshot().join();
    assertFalse(snapshot.hostilePresence(Set.of(a, b)));
    assertTrue(snapshot.hostilePresence(Set.of(a, b, c)));
    assertFalse(snapshot.hostilePresence(Set.of(a)));
  }

  @Test
  void diplomacyAndFireCannotBeChangedDuringCombat() {
    create(a, "Alpha");
    UUID tb = create(c, "Beta");
    join(a, b);
    store.combat(b, c, 3000).join();
    assertThrows(
        CompletionException.class, () -> store.relation(a, tb, "ALLY", false, 1500).join());
    assertThrows(CompletionException.class, () -> store.fire(a, true, 1500).join());
    store.fire(a, true, 3001).join();
  }
}
