package nl.poldersmp.core.nether;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/** Synchronous persistence boundary: invoke only on the maintenance/DB worker. */
public final class ResetStore {
  public record State(long revision, Properties values) {
    public State {
      values = (Properties) values.clone();
    }

    public String get(String key) {
      return values.getProperty(key, "");
    }

    public long number(String key) {
      return Long.parseLong(get(key));
    }

    public boolean flag(String key) {
      return Boolean.parseBoolean(get(key));
    }

    public String phase() {
      return get("phase");
    }

    public boolean open() {
      return phase().equals("IDLE") && !flag("paused");
    }
  }

  private final Path file;

  public ResetStore(Path file) {
    this.file = file;
  }

  private Connection connect() throws Exception {
    Files.createDirectories(file.getParent());
    var c = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (var s = c.createStatement()) {
      s.execute("PRAGMA busy_timeout=10000");
      s.execute("PRAGMA synchronous=FULL");
    }
    return c;
  }

  public void migrate() throws Exception {
    try (var c = connect()) {
      c.setAutoCommit(false);
      try (var s = c.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS nether_schema(version INTEGER PRIMARY KEY)");
        int v;
        try (var r = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM nether_schema")) {
          v = r.getInt(1);
        }
        if (v > 1) throw new IOException("Newer Nether schema");
        if (v == 0) {
          try (var in = ResetStore.class.getResourceAsStream("/db/nether/V1__reset.sql")) {
            if (in == null) throw new IOException("Missing Nether migration");
            for (String sql :
                new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";"))
              if (!sql.isBlank()) s.execute(sql);
          }
          s.execute("INSERT INTO nether_schema VALUES(1)");
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static State read(Connection c) throws Exception {
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT revision,document FROM nether_state WHERE id=1")) {
      if (!r.next()) return null;
      Properties p = new Properties();
      p.load(new StringReader(r.getString(2)));
      return new State(r.getLong(1), p);
    }
  }

  public State read() throws Exception {
    try (var c = connect()) {
      return read(c);
    }
  }

  private static String text(Properties p) throws Exception {
    var w = new StringWriter();
    p.store(w, null);
    return w.toString();
  }

  public State initialize(Path root, String name, long seed, long now, long interval, int retention)
      throws Exception {
    Properties p = new Properties();
    Map.of(
            "root",
            root.toRealPath().toString(),
            "name",
            name,
            "identity",
            UUID.randomUUID().toString(),
            "seed",
            Long.toString(seed),
            "last",
            "0",
            "next",
            Long.toString(Math.addExact(now, interval)),
            "interval",
            Long.toString(interval),
            "retention",
            Integer.toString(retention),
            "phase",
            "IDLE",
            "paused",
            "false")
        .forEach(p::setProperty);
    try (var c = connect();
        var s = c.prepareStatement("INSERT INTO nether_state VALUES(1,0,?)")) {
      s.setString(1, text(p));
      s.executeUpdate();
    }
    return read();
  }

  public State change(State expected, Map<String, String> edits) throws Exception {
    String nextPhase = edits.getOrDefault("phase", expected.phase());
    if (!nextPhase.equals(expected.phase())) {
      var allowed =
          switch (expected.phase()) {
            case "IDLE" -> Set.of("REQUESTED");
            case "REQUESTED" -> Set.of("WAITING_STOP", "FAILED", "VERIFYING");
            case "WAITING_STOP" -> Set.of("BACKUP", "FAILED", "VERIFYING");
            case "BACKUP" -> Set.of("ARCHIVED", "FAILED", "VERIFYING");
            case "ARCHIVED" -> Set.of("GENERATING", "FAILED", "VERIFYING");
            case "GENERATING" -> Set.of("VERIFYING", "FAILED");
            case "VERIFYING" -> Set.of("FAILED");
            case "FAILED" -> Set.of("VERIFYING");
            default -> Set.<String>of();
          };
      if (!allowed.contains(nextPhase))
        throw new IOException("Invalid reset transition " + expected.phase() + " -> " + nextPhase);
    }
    Properties p = (Properties) expected.values().clone();
    for (var e : edits.entrySet()) {
      if (Set.of("root", "name", "identity").contains(e.getKey()))
        throw new IOException("Immutable world identity");
      p.setProperty(e.getKey(), e.getValue());
    }
    try (var c = connect();
        var s =
            c.prepareStatement(
                "UPDATE nether_state SET revision=revision+1,document=? WHERE id=1 AND"
                    + " revision=?")) {
      s.setString(1, text(p));
      s.setLong(2, expected.revision());
      if (s.executeUpdate() != 1)
        throw new IOException("Concurrent reset state change; retry safely");
    }
    return new State(expected.revision() + 1, p);
  }

  public State request(long now, long pid, String processStart) throws Exception {
    try (var c = connect()) {
      c.setAutoCommit(false);
      try {
        State s = read(c);
        if (s == null || !s.phase().equals("IDLE") || s.flag("paused"))
          throw new IOException("Reset paused or already in progress");
        long seed;
        do {
          seed = new SecureRandom().nextLong();
        } while (seed == s.number("seed"));
        Properties p = (Properties) s.values().clone();
        Map.of(
                "phase",
                "REQUESTED",
                "operation",
                UUID.randomUUID().toString(),
                "new-seed",
                Long.toString(seed),
                "old-seed",
                s.get("seed"),
                "pid",
                Long.toString(pid),
                "process-start",
                processStart,
                "backup",
                "",
                "archive",
                "",
                "error",
                "",
                "rollback",
                "false")
            .forEach(p::setProperty);
        try (var q =
            c.prepareStatement(
                "UPDATE nether_state SET revision=revision+1,document=? WHERE id=1 AND"
                    + " revision=?")) {
          q.setString(1, text(p));
          q.setLong(2, s.revision());
          if (q.executeUpdate() != 1) throw new IOException("Duplicate request");
        }
        try (var q =
            c.prepareStatement("INSERT INTO nether_history VALUES(?,?,NULL,'PENDING',NULL,NULL)")) {
          q.setString(1, p.getProperty("operation"));
          q.setLong(2, now);
          q.executeUpdate();
        }
        c.commit();
        return new State(s.revision() + 1, p);
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  public State complete(State s, long now, String fingerprint) throws Exception {
    if (!s.phase().equals("VERIFYING")) throw new IOException("Not verifying");
    boolean rollback = s.flag("rollback");
    Properties p = (Properties) s.values().clone();
    p.setProperty("phase", "IDLE");
    p.setProperty("seed", s.get("new-seed"));
    p.setProperty("fingerprint", fingerprint);
    p.setProperty("paused", Boolean.toString(rollback));
    p.setProperty("error", "");
    if (!rollback) {
      p.setProperty("last", Long.toString(now));
      p.setProperty("next", Long.toString(Math.addExact(now, s.number("interval"))));
    }
    try (var c = connect()) {
      c.setAutoCommit(false);
      try {
        try (var q =
            c.prepareStatement(
                "UPDATE nether_state SET revision=revision+1,document=? WHERE revision=? AND"
                    + " id=1")) {
          q.setString(1, text(p));
          q.setLong(2, s.revision());
          if (q.executeUpdate() != 1)
            throw new IOException("Verification already completed/changed");
        }
        try (var q =
            c.prepareStatement(
                "UPDATE nether_history SET completed=?,outcome=?,backup=?,detail=? WHERE"
                    + " operation=? AND outcome='PENDING'")) {
          q.setLong(1, now);
          q.setString(2, rollback ? "ROLLED_BACK" : "SUCCESS");
          q.setString(3, s.get("backup"));
          q.setString(4, s.get("failure-detail") + " " + fingerprint);
          q.setString(5, s.get("operation"));
          if (q.executeUpdate() != 1) throw new IOException("Outcome already recorded");
        }
        c.commit();
        return new State(s.revision() + 1, p);
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  public void failure(State s, Throwable e) throws Exception {
    Properties p = (Properties) s.values().clone();
    p.setProperty("phase", "FAILED");
    p.setProperty("failed-phase", s.phase());
    p.setProperty("error", e.toString());
    p.setProperty("failure-detail", e.toString());
    try (var c = connect()) {
      c.setAutoCommit(false);
      try {
        try (var q =
            c.prepareStatement(
                "UPDATE nether_state SET revision=revision+1,document=? WHERE id=1 AND"
                    + " revision=?")) {
          q.setString(1, text(p));
          q.setLong(2, s.revision());
          if (q.executeUpdate() != 1) throw new IOException("Failure state changed concurrently");
        }
        try (var q =
            c.prepareStatement(
                "UPDATE nether_history SET outcome='FAILED',detail=? WHERE operation=? AND"
                    + " outcome='PENDING'")) {
          q.setString(1, e.toString());
          q.setString(2, s.get("operation"));
          q.executeUpdate();
        }
        c.commit();
      } catch (Exception error) {
        c.rollback();
        throw error;
      }
    }
  }

  public List<String> history() throws Exception {
    try (var c = connect();
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT operation,outcome,completed FROM nether_history ORDER BY requested DESC"
                    + " LIMIT 10")) {
      List<String> out = new ArrayList<>();
      while (r.next()) out.add(r.getString(1) + " " + r.getString(2) + " " + r.getString(3));
      return out;
    }
  }

  public List<Path> successfulBackups() throws Exception {
    try (var c = connect();
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT backup FROM nether_history WHERE outcome='SUCCESS' AND backup IS NOT NULL"
                    + " AND backup!='' ORDER BY completed DESC")) {
      List<Path> out = new ArrayList<>();
      while (r.next()) out.add(Path.of(r.getString(1)));
      return out;
    }
  }
}
