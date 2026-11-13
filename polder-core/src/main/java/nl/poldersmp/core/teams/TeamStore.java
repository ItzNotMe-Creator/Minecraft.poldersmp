package nl.poldersmp.core.teams;

import static nl.poldersmp.core.market.Sql.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import nl.poldersmp.common.Database;

public final class TeamStore {
  public record Limits(
      int teams,
      int members,
      int outgoing,
      int incoming,
      long invite,
      long creation,
      long war,
      long request,
      long farming) {}

  public record Team(
      UUID id, String name, UUID leader, boolean fire, long kills, long deaths, long wins) {}

  public record Member(UUID player, UUID team, String role) {}

  public record Relation(UUID a, UUID b, String kind, long expires, long aKills, long bKills) {}

  public record Snapshot(
      Map<UUID, Team> teams, Map<UUID, Member> members, List<Relation> relations) {
    public Snapshot {
      teams = Map.copyOf(teams);
      members = Map.copyOf(members);
      relations = List.copyOf(relations);
    }

    public boolean friendly(UUID a, UUID b) {
      var x = members.get(a);
      var y = members.get(b);
      if (x == null || y == null) return false;
      if (x.team().equals(y.team())) return true;
      return relations.stream()
          .anyMatch(
              r ->
                  r.kind().equals("ALLY")
                      && ((r.a().equals(x.team()) && r.b().equals(y.team()))
                          || (r.b().equals(x.team()) && r.a().equals(y.team()))));
    }

    public boolean hostilePresence(Set<UUID> players) {
      for (UUID a : players)
        for (UUID b : players) if (!a.equals(b) && !friendly(a, b)) return true;
      return false;
    }

    public boolean damage(UUID a, UUID b, boolean alliedFire) {
      if (!friendly(a, b)) return true;
      var x = members.get(a);
      var y = members.get(b);
      return x.team().equals(y.team()) ? teams.get(x.team()).fire() : alliedFire;
    }
  }

  private final Database db;
  private final Limits limits;

  public TeamStore(Database db, Limits limits) {
    this.db = db;
    this.limits = limits;
  }

  public CompletableFuture<Void> migrate() {
    return tx(
        db,
        c -> {
          nl.poldersmp.core.market.Sql.migrate(c, "teams");
          return null;
        });
  }

  private static Member member(Connection c, UUID player) throws SQLException {
    var rows = rows(c, "SELECT team,role FROM team_members WHERE player=?", player);
    if (rows.isEmpty()) return null;
    return new Member(
        player, UUID.fromString((String) rows.getFirst().get(0)), (String) rows.getFirst().get(1));
  }

  private static Member officer(Connection c, UUID player) throws SQLException {
    var m = member(c, player);
    require(m != null && !m.role().equals("MEMBER"), "Officer permission required");
    return m;
  }

  private static void calm(Connection c, UUID p, long now) throws SQLException {
    require(
        number(c, "SELECT until_time FROM team_combat WHERE player=?", p) <= now,
        "Team changes are blocked during combat");
  }

  public CompletableFuture<Snapshot> snapshot() {
    return db.submit(
        c -> {
          Map<UUID, Team> ts = new HashMap<>();
          for (var r :
              rows(
                  c,
                  "SELECT id,name,leader,friendly_fire,kills,deaths,wins FROM teams WHERE"
                      + " active=1")) {
            UUID id = UUID.fromString((String) r.get(0));
            ts.put(
                id,
                new Team(
                    id,
                    (String) r.get(1),
                    UUID.fromString((String) r.get(2)),
                    ((Number) r.get(3)).intValue() != 0,
                    ((Number) r.get(4)).longValue(),
                    ((Number) r.get(5)).longValue(),
                    ((Number) r.get(6)).longValue()));
          }
          Map<UUID, Member> ms = new HashMap<>();
          for (var r : rows(c, "SELECT player,team,role FROM team_members")) {
            UUID id = UUID.fromString((String) r.get(0));
            ms.put(id, new Member(id, UUID.fromString((String) r.get(1)), (String) r.get(2)));
          }
          List<Relation> rs = new ArrayList<>();
          for (var r : rows(c, "SELECT a,b,kind,expires,score_a,score_b FROM team_relations"))
            rs.add(
                new Relation(
                    UUID.fromString((String) r.get(0)),
                    UUID.fromString((String) r.get(1)),
                    (String) r.get(2),
                    ((Number) r.get(3)).longValue(),
                    ((Number) r.get(4)).longValue(),
                    ((Number) r.get(5)).longValue()));
          return new Snapshot(ts, ms, rs);
        });
  }

  public CompletableFuture<Void> combat(UUID a, UUID b, long until) {
    return tx(
        db,
        c -> {
          for (UUID p : List.of(a, b))
            update(
                c,
                "INSERT INTO team_combat VALUES(?,?) ON CONFLICT(player) DO UPDATE SET"
                    + " until_time=MAX(until_time,excluded.until_time)",
                p,
                until);
          return null;
        });
  }

  public CompletableFuture<UUID> create(UUID player, String name, long now) {
    return tx(
        db,
        c -> {
          calm(c, player, now);
          require(
              name.matches("[A-Za-z0-9_]{3,16}"),
              "Team names use 3..16 letters, numbers or underscores");
          require(member(c, player) == null, "Already in a team");
          require(
              number(c, "SELECT COUNT(*) FROM teams WHERE active=1") < limits.teams(),
              "Team limit reached");
          require(
              number(
                          c,
                          "SELECT COALESCE(MAX(created),0) FROM team_creations WHERE player=?",
                          player)
                      == 0
                  || number(c, "SELECT MAX(created) FROM team_creations WHERE player=?", player)
                          + limits.creation()
                      <= now,
              "Team creation cooldown");
          UUID id = UUID.randomUUID();
          update(
              c,
              "INSERT INTO teams(id,name,leader,created) VALUES(?,?,?,?)",
              id,
              name,
              player,
              now);
          update(c, "INSERT INTO team_members VALUES(?,?,'LEADER',?)", player, id, now);
          update(c, "INSERT INTO team_creations VALUES(?,?)", player, now);
          return id;
        });
  }

  public CompletableFuture<Void> invite(UUID actor, UUID target, long now) {
    return tx(
        db,
        c -> {
          var m = officer(c, actor);
          require(member(c, target) == null, "Target is already in a team");
          update(c, "DELETE FROM team_invitations WHERE expires<=?", now);
          require(
              number(c, "SELECT COUNT(*) FROM team_members WHERE team=?", m.team())
                  < limits.members(),
              "Team is full");
          require(
              number(c, "SELECT COUNT(*) FROM team_invitations WHERE team=?", m.team())
                  < limits.outgoing(),
              "Outgoing invite limit");
          require(
              number(c, "SELECT COUNT(*) FROM team_invitations WHERE player=?", target)
                  < limits.incoming(),
              "Incoming invite limit");
          update(
              c,
              "INSERT INTO team_invitations VALUES(?,?,?,?) ON CONFLICT(team,player) DO UPDATE SET"
                  + " inviter=excluded.inviter,expires=excluded.expires",
              m.team(),
              target,
              actor,
              Math.addExact(now, limits.invite()));
          return null;
        });
  }

  public CompletableFuture<Void> accept(UUID player, String selected, long now) {
    return tx(
        db,
        c -> {
          calm(c, player, now);
          require(member(c, player) == null, "Already in a team");
          var invites =
              rows(
                  c,
                  "SELECT i.team,t.name FROM team_invitations i JOIN teams t ON t.id=i.team WHERE"
                      + " i.player=? AND i.expires>? AND t.active=1 ORDER BY t.name",
                  player,
                  now);
          if (selected != null)
            invites =
                invites.stream()
                    .filter(r -> ((String) r.get(1)).equalsIgnoreCase(selected))
                    .toList();
          require(invites.size() == 1, "Use /team accept <team> to choose one valid invitation");
          String id = (String) invites.getFirst().get(0);
          require(
              number(c, "SELECT COUNT(*) FROM team_members WHERE team=?", id) < limits.members(),
              "Team is full");
          update(c, "INSERT INTO team_members VALUES(?,?,'MEMBER',?)", player, id, now);
          update(c, "DELETE FROM team_invitations WHERE player=?", player);
          return null;
        });
  }

  public CompletableFuture<List<String>> invitations(UUID player, long now) {
    return db.submit(
        c -> {
          List<String> list = new ArrayList<>();
          for (var r :
              rows(
                  c,
                  "SELECT t.name,i.expires FROM team_invitations i JOIN teams t ON t.id=i.team"
                      + " WHERE i.player=? AND i.expires>? AND t.active=1 ORDER BY t.name",
                  player,
                  now))
            list.add(
                r.get(0)
                    + " • expires "
                    + java.time.Instant.ofEpochMilli(((Number) r.get(1)).longValue()));
          return list;
        });
  }

  public CompletableFuture<Void> deny(UUID player) {
    return tx(
        db,
        c -> {
          update(c, "DELETE FROM team_invitations WHERE player=?", player);
          return null;
        });
  }

  public CompletableFuture<Void> leave(UUID p, long now) {
    return tx(
        db,
        c -> {
          calm(c, p, now);
          var m = member(c, p);
          require(
              m != null && !m.role().equals("LEADER"),
              "Leader must transfer leadership or disband");
          update(c, "DELETE FROM team_members WHERE player=?", p);
          return null;
        });
  }

  public CompletableFuture<Void> manage(UUID actor, UUID target, String action, long now) {
    return tx(
        db,
        c -> {
          calm(c, actor, now);
          calm(c, target, now);
          var a = officer(c, actor);
          var b = member(c, target);
          require(
              b != null && b.team().equals(a.team()) && !actor.equals(target),
              "Target must be another team member");
          require(
              a.role().equals("LEADER") || b.role().equals("MEMBER") && action.equals("kick"),
              "Leader permission required");
          require(!b.role().equals("LEADER"), "Cannot remove leader");
          if (action.equals("kick")) update(c, "DELETE FROM team_members WHERE player=?", target);
          else if (b.role().equals("MEMBER"))
            update(c, "UPDATE team_members SET role='OFFICER' WHERE player=?", target);
          else {
            update(c, "UPDATE team_members SET role='OFFICER' WHERE player=?", actor);
            update(c, "UPDATE team_members SET role='LEADER' WHERE player=?", target);
            update(c, "UPDATE teams SET leader=? WHERE id=?", target, a.team());
          }
          return null;
        });
  }

  public CompletableFuture<Void> disband(UUID p, long now) {
    return tx(
        db,
        c -> {
          calm(c, p, now);
          var m = officer(c, p);
          require(m.role().equals("LEADER"), "Leader permission required");
          require(
              number(
                      c,
                      "SELECT COUNT(*) FROM team_combat b JOIN team_members m ON m.player=b.player"
                          + " WHERE m.team=? AND b.until_time>?",
                      m.team(),
                      now)
                  == 0,
              "Team has members in combat");
          require(
              number(
                      c,
                      "SELECT COUNT(*) FROM team_relations WHERE (a=? OR b=?) AND kind='WAR'",
                      m.team(),
                      m.team())
                  == 0,
              "Finish active war before disbanding");
          update(c, "DELETE FROM team_members WHERE team=?", m.team());
          update(c, "DELETE FROM team_invitations WHERE team=?", m.team());
          update(c, "DELETE FROM team_relations WHERE a=? OR b=?", m.team(), m.team());
          update(c, "UPDATE teams SET active=0,name=name||'_'||id WHERE id=?", m.team());
          return null;
        });
  }

  public CompletableFuture<Void> fire(UUID p, boolean on) {
    return fire(p, on, System.currentTimeMillis());
  }

  public CompletableFuture<Void> fire(UUID p, boolean on, long now) {
    return tx(
        db,
        c -> {
          var m = officer(c, p);
          require(m.role().equals("LEADER"), "Leader permission required");
          require(
              number(
                      c,
                      "SELECT COUNT(*) FROM team_combat c JOIN team_members m ON m.player=c.player"
                          + " WHERE m.team=? AND c.until_time>?",
                      m.team(),
                      now)
                  == 0,
              "Friendly-fire changes blocked in combat");
          update(c, "UPDATE teams SET friendly_fire=? WHERE id=?", on ? 1 : 0, m.team());
          return null;
        });
  }

  public CompletableFuture<Void> relation(
      UUID p, UUID other, String kind, boolean remove, long now) {
    return tx(
        db,
        c -> {
          var m = officer(c, p);
          UUID a = m.team(), b = other;
          require(
              !a.equals(b)
                  && number(c, "SELECT COUNT(*) FROM teams WHERE id=? AND active=1", b) == 1,
              "Invalid other team");
          require(
              number(
                      c,
                      "SELECT COUNT(*) FROM team_combat c JOIN team_members m ON m.player=c.player"
                          + " WHERE m.team IN (?,?) AND c.until_time>?",
                      a,
                      b,
                      now)
                  == 0,
              "Diplomacy changes blocked while either team is in combat");
          if (remove) {
            require(!kind.equals("WAR"), "Wars finish at their configured deadline");
            update(
                c,
                "DELETE FROM team_relations WHERE ((a=? AND b=?) OR (a=? AND b=?)) AND kind IN"
                    + " (?,?)",
                a,
                b,
                b,
                a,
                kind,
                kind + "_REQUEST");
            return null;
          }
          require(
              number(
                      c,
                      "SELECT COUNT(*) FROM team_relations WHERE ((a=? AND b=?) OR (a=? AND b=?))"
                          + " AND kind='WAR'",
                      a,
                      b,
                      b,
                      a)
                  == 0,
              "Already at war");
          if (kind.equals("ALLY")
              && number(
                      c,
                      "SELECT COUNT(*) FROM team_relations WHERE ((a=? AND b=?) OR (a=? AND b=?))"
                          + " AND kind='ALLY'",
                      a,
                      b,
                      b,
                      a)
                  > 0) return null;
          if (kind.equals("RIVAL")) {
            update(
                c,
                "DELETE FROM team_relations WHERE ((a=? AND b=?) OR (a=? AND b=?)) AND kind IN"
                    + " ('ALLY','ALLY_REQUEST')",
                a,
                b,
                b,
                a);
            update(
                c,
                "INSERT OR IGNORE INTO team_relations(a,b,kind,expires) VALUES(?,?,'RIVAL',0)",
                a,
                b);
          } else {
            boolean reciprocal =
                number(
                        c,
                        "SELECT COUNT(*) FROM team_relations WHERE a=? AND b=? AND kind=? AND"
                            + " expires>?",
                        b,
                        a,
                        kind + "_REQUEST",
                        now)
                    > 0;
            if (reciprocal) {
              update(
                  c,
                  "DELETE FROM team_relations WHERE ((a=? AND b=?) OR (a=? AND b=?)) AND kind IN"
                      + " ('ALLY_REQUEST','WAR_REQUEST','RIVAL','FINISHED')",
                  a,
                  b,
                  b,
                  a);
              if (kind.equals("WAR"))
                update(
                    c,
                    "DELETE FROM team_relations WHERE ((a=? AND b=?) OR (a=? AND b=?)) AND"
                        + " kind='ALLY'",
                    a,
                    b,
                    b,
                    a);
              update(
                  c,
                  "INSERT OR IGNORE INTO team_relations(a,b,kind,expires) VALUES(?,?,?,?)",
                  a,
                  b,
                  kind,
                  kind.equals("WAR") ? Math.addExact(now, limits.war()) : 0);
            } else
              update(
                  c,
                  "INSERT INTO team_relations(a,b,kind,expires) VALUES(?,?,?,?) ON"
                      + " CONFLICT(a,b,kind) DO UPDATE SET expires=excluded.expires",
                  a,
                  b,
                  kind + "_REQUEST",
                  Math.addExact(now, limits.request()));
          }
          return null;
        });
  }

  public CompletableFuture<Void> death(UUID id, UUID killer, UUID victim, long now) {
    return tx(
        db,
        c -> {
          if (number(c, "SELECT COUNT(*) FROM team_deaths WHERE id=?", id) > 0) return null;
          long last =
              number(
                  c,
                  "SELECT COALESCE(MAX(created),0) FROM team_deaths WHERE killer=? AND victim=?",
                  killer,
                  victim);
          if (killer.equals(victim) || last > 0 && last + limits.farming() > now) return null;
          var k = member(c, killer);
          var v = member(c, victim);
          if (k != null
              && v != null
              && (k.team().equals(v.team())
                  || number(
                          c,
                          "SELECT COUNT(*) FROM team_relations WHERE ((a=? AND b=?) OR (a=? AND"
                              + " b=?)) AND kind='ALLY'",
                          k.team(),
                          v.team(),
                          v.team(),
                          k.team())
                      > 0)) return null;
          update(c, "INSERT INTO team_deaths VALUES(?,?,?,?)", id, killer, victim, now);
          if (k != null) update(c, "UPDATE teams SET kills=kills+1 WHERE id=?", k.team());
          if (v != null) update(c, "UPDATE teams SET deaths=deaths+1 WHERE id=?", v.team());
          if (k != null && v != null) {
            update(
                c,
                "UPDATE team_relations SET score_a=score_a+1 WHERE a=? AND b=? AND kind='WAR' AND"
                    + " expires>?",
                k.team(),
                v.team(),
                now);
            update(
                c,
                "UPDATE team_relations SET score_b=score_b+1 WHERE b=? AND a=? AND kind='WAR' AND"
                    + " expires>?",
                k.team(),
                v.team(),
                now);
          }
          return null;
        });
  }

  public CompletableFuture<Void> expire(long now) {
    return tx(
        db,
        c -> {
          update(c, "DELETE FROM team_invitations WHERE expires<=?", now);
          update(
              c,
              "DELETE FROM team_relations WHERE kind IN ('ALLY_REQUEST','WAR_REQUEST') AND"
                  + " expires<=?",
              now);
          for (var r :
              rows(
                  c,
                  "SELECT a,b,score_a,score_b FROM team_relations WHERE kind='WAR' AND expires<=?",
                  now)) {
            String winner =
                ((Number) r.get(2)).longValue() == ((Number) r.get(3)).longValue()
                    ? null
                    : (String)
                        r.get(
                            ((Number) r.get(2)).longValue() > ((Number) r.get(3)).longValue()
                                ? 0
                                : 1);
            update(
                c,
                "UPDATE team_relations SET kind='FINISHED',winner=? WHERE a=? AND b=? AND"
                    + " kind='WAR'",
                winner,
                r.get(0),
                r.get(1));
            if (winner != null) update(c, "UPDATE teams SET wins=wins+1 WHERE id=?", winner);
          }
          return null;
        });
  }
}
