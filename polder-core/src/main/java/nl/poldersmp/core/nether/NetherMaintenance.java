package nl.poldersmp.core.nether;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Java 21 offline worker and approved process supervisor; no Bukkit classes. */
public final class NetherMaintenance {
  private final Path root, data;
  private final ResetStore store;

  public NetherMaintenance(Path root) throws Exception {
    this.root = root.toRealPath();
    this.data = this.root.resolve("plugins/PolderCore");
    NetherFiles.safe(this.root, data);
    store = new ResetStore(data.resolve("nether-reset.db"));
    if (!Files.isRegularFile(data.resolve("nether-reset.db")))
      throw new IOException("Enable/initialize the managed Nether first");
    store.migrate();
    if (!NetherFiles.root(store.read()).equals(this.root))
      throw new IOException("Server root registration mismatch");
  }

  private void log(String text) throws Exception {
    String line = Instant.now() + " " + text;
    System.out.println(line);
    NetherFiles.safe(root, data.resolve("nether-maintenance.log"));
    Files.writeString(
        data.resolve("nether-maintenance.log"),
        line + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private FileChannel lock(String name) throws Exception {
    Path path = data.resolve(name);
    NetherFiles.safe(root, path);
    var c = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      if (c.tryLock() == null) throw new IOException("Another maintenance tool owns " + name);
      return c;
    } catch (Exception e) {
      c.close();
      throw e;
    }
  }

  public void apply() throws Exception {
    try (var exclusive = lock("maintenance.lock")) {
      ResetStore.State s = store.read();
      if (!s.phase().equals("WAITING_STOP"))
        throw new IOException(
            "Expected WAITING_STOP; incomplete/duplicate operation requires explicit recovery");
      try (var stopped = new NetherFiles.Stopped(s)) {
        Path target = NetherFiles.target(s, true), base = root.resolve("nether-backups");
        NetherFiles.safe(root, base);
        Files.createDirectories(base);
        String id = s.get("operation");
        UUID.fromString(id);
        Path backup = base.resolve(Instant.now().toString().replace(':', '-') + "-" + id);
        NetherFiles.safe(root, backup);
        Files.createDirectory(backup);
        Properties owner = new Properties();
        owner.setProperty("identity", s.get("identity"));
        owner.setProperty("operation", id);
        NetherFiles.atomic(backup.resolve("owner.properties"), owner);
        s =
            store.change(
                s,
                Map.of(
                    "phase",
                    "BACKUP",
                    "backup",
                    backup.toString(),
                    "archive",
                    backup.resolve("previous-world").toString()));
        log("BACKUP " + id + " " + target + " -> " + backup);
        NetherFiles.copy(target, backup.resolve("snapshot"), stopped.sessionContents(target));
        NetherFiles.verifySource(
            target, backup.resolve("snapshot"), stopped.sessionContents(target));
        NetherFiles.manifest(backup);
        if (NbtSeed.read(backup.resolve("snapshot/level.dat")) != s.number("old-seed"))
          throw new IOException("Backup seed mismatch");
        // Windows forbids renaming a folder containing our open session.lock.
        // PID is already exited; release session locks immediately before same-volume move.
      }
      s = store.read();
      try {
        // Re-check process and all locks a second time, before releasing them for rename.
        try (var stoppedAgain = new NetherFiles.Stopped(s)) {
          NetherFiles.target(s, true);
          NetherFiles.verify(Path.of(s.get("backup")));
        }
        Files.move(
            NetherFiles.target(s, true), Path.of(s.get("archive")), StandardCopyOption.ATOMIC_MOVE);
        s = store.change(s, Map.of("phase", "ARCHIVED"));
        log("ARCHIVED " + s.get("operation"));
        if (Files.exists(NetherFiles.target(s, false)))
          throw new IOException("Target recreated concurrently; refusing generation");
        s = store.change(s, Map.of("phase", "GENERATING"));
        log("READY TO GENERATE seed=" + s.get("new-seed") + " operation=" + s.get("operation"));
        pruneInternal();
      } catch (Exception e) {
        fail(e);
        throw e;
      }
    } catch (Exception e) {
      var failedState = store.read();
      if (Set.of("BACKUP", "ARCHIVED").contains(failedState.phase())) fail(e);
      log("REFUSED/FAILED " + e);
      throw e;
    }
  }

  private void fail(Exception e) throws Exception {
    var s = store.read();
    if (!s.phase().equals("IDLE") && !s.phase().equals("FAILED")) store.failure(s, e);
    log("FAILED " + e);
  }

  private Path validatedBackup(ResetStore.State s) throws Exception {
    Path b = Path.of(s.get("backup"));
    NetherFiles.safe(root.resolve("nether-backups"), b);
    var p = NetherFiles.properties(b.resolve("owner.properties"));
    if (!s.get("identity").equals(p.getProperty("identity"))
        || !s.get("operation").equals(p.getProperty("operation")))
      throw new IOException("Backup owner mismatch");
    NetherFiles.verify(b);
    return b;
  }

  public void recover(String operation) throws Exception {
    try (var exclusive = lock("maintenance.lock")) {
      var s = store.read();
      if (s.phase().equals("IDLE") || !s.get("operation").equals(operation))
        throw new IOException("Explicit current operation ID required");
      try (var stopped = new NetherFiles.Stopped(s)) {
        NetherFiles.target(s, false);
      }
      Path target = NetherFiles.target(s, false);
      Path backup = null;
      if (!s.get("backup").isEmpty())
        try {
          backup = validatedBackup(s);
        } catch (Exception e) {
          log("Incomplete backup: " + e);
        }
      if (backup == null) {
        // Before a verified archive the old live directory is authoritative, and untouched.
        if (!Set.of("REQUESTED", "WAITING_STOP", "BACKUP", "FAILED").contains(s.phase())
            || !s.get("archive").isEmpty() && Files.exists(Path.of(s.get("archive"))))
          throw new IOException("No verified recovery snapshot; operator review required");
        NetherFiles.target(s, true);
        if (NbtSeed.read(target.resolve("level.dat")) != s.number("old-seed"))
          throw new IOException("Old world not intact");
      } else {
        Path quarantine = backup.resolve("quarantine-" + UUID.randomUUID());
        if (Files.exists(target)) {
          NetherFiles.tree(target);
          Files.move(target, quarantine, StandardCopyOption.ATOMIC_MOVE);
          log("QUARANTINED incomplete generation " + quarantine);
        }
        NetherFiles.copy(backup.resolve("snapshot"), target);
        if (NbtSeed.read(target.resolve("level.dat")) != s.number("old-seed"))
          throw new IOException("Restored seed mismatch");
      }
      store.change(
          s,
          Map.of(
              "phase",
              "VERIFYING",
              "rollback",
              "true",
              "new-seed",
              s.get("old-seed"),
              "paused",
              "true",
              "error",
              ""));
      log(
          "RECOVERY staged; start through approved launcher, verify old seed, then resume"
              + " manually");
    }
  }

  public void approve(Path java, Path jar) throws Exception {
    try (var exclusive = lock("maintenance.lock");
        var stopped = new NetherFiles.Stopped(store.read())) {
      Path j = java.toRealPath(), server = jar.toRealPath();
      if (!server.getParent().equals(root) || !server.getFileName().toString().endsWith(".jar"))
        throw new IOException("Server JAR must be directly inside registered SMP root");
      if (!Files.isExecutable(j)) throw new IOException("Java executable unavailable");
      Properties p = new Properties();
      p.setProperty("root", root.toString());
      p.setProperty("java", j.toString());
      p.setProperty("java-sha256", NetherFiles.sha(j));
      p.setProperty("jar", server.toString());
      p.setProperty("jar-sha256", NetherFiles.sha(server));
      p.setProperty("restart-verified", "false");
      NetherFiles.atomic(data.resolve("launcher.properties"), p);
      log("Launcher approved; live startup handshake still required before automatic shutdown");
    }
  }

  public void run() throws Exception {
    try (var supervisor = lock("supervisor.lock")) {
      while (true) {
        var s = store.read();
        if (Set.of("WAITING_STOP").contains(s.phase())) {
          apply();
          s = store.read();
        }
        if (!Set.of("IDLE", "GENERATING", "VERIFYING").contains(s.phase()))
          throw new IOException(
              "Incomplete reset " + s.phase() + "; recover explicitly before starting");
        try (var stopped = new NetherFiles.Stopped(s)) {
          NetherFiles.target(s, false);
        }
        var p = NetherFiles.properties(data.resolve("launcher.properties"));
        Path java = Path.of(p.getProperty("java")), jar = Path.of(p.getProperty("jar"));
        if (!root.toString().equals(p.getProperty("root"))
            || !jar.getParent().equals(root)
            || !NetherFiles.sha(jar).equals(p.getProperty("jar-sha256"))
            || !NetherFiles.sha(java).equals(p.getProperty("java-sha256")))
          throw new IOException("Approved launcher changed; reapprove while stopped");
        boolean resetStartup = s.phase().equals("GENERATING") || s.phase().equals("VERIFYING");
        String token = UUID.randomUUID().toString();
        Files.deleteIfExists(data.resolve("launcher-proof.properties"));
        Files.deleteIfExists(data.resolve("launcher-ready.properties"));
        var builder =
            new ProcessBuilder(
                    java.toString(), "-Xms512M", "-Xmx2G", "-jar", jar.toString(), "--nogui")
                .directory(root.toFile())
                .inheritIO();
        builder.environment().put("POLDER_LAUNCH_TOKEN", token);
        log("START approved SMP launcher");
        Process process = builder.start();
        boolean verified = false;
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
          Path ready = data.resolve("launcher-ready.properties");
          if (!verified && Files.isRegularFile(ready)) {
            var r = NetherFiles.properties(ready);
            if (token.equals(r.getProperty("token"))
                && Long.toString(process.pid()).equals(r.getProperty("pid"))) {
              if (resetStartup
                  && store.read().phase().equals("IDLE")
                  && !store.read().flag("rollback")) {
                p.setProperty("restart-verified", "true");
                NetherFiles.atomic(data.resolve("launcher.properties"), p);
              }
              r.setProperty("restart-verified", p.getProperty("restart-verified", "false"));
              r.setProperty("supervisor-pid", Long.toString(ProcessHandle.current().pid()));
              r.setProperty(
                  "supervisor-start",
                  ProcessHandle.current().info().startInstant().orElseThrow().toString());
              r.setProperty("jar-sha256", p.getProperty("jar-sha256"));
              NetherFiles.atomic(data.resolve("launcher-proof.properties"), r);
              verified = true;
              log("VERIFIED live launcher handshake pid=" + process.pid());
            }
          }
        }
        log("EXIT SMP pid=" + process.pid() + " code=" + process.exitValue());
        // waitFor returned: the child has fully exited, including final world saves.
        if (process.exitValue() != 0)
          throw new IOException("Abnormal SMP exit; refusing unattended filesystem changes");
        if (!store.read().phase().equals("WAITING_STOP")) return;
        apply();
      }
    }
  }

  public static boolean liveProof(Path data) throws Exception {
    String token = System.getenv("POLDER_LAUNCH_TOKEN");
    if (token == null || token.isBlank()) return false;
    Path path = data.resolve("launcher-proof.properties");
    if (!Files.isRegularFile(path)) return false;
    var p = NetherFiles.properties(path);
    var self = ProcessHandle.current();
    if (!Boolean.parseBoolean(p.getProperty("restart-verified", "false"))) return false;
    if (!token.equals(p.getProperty("token"))
        || !Long.toString(self.pid()).equals(p.getProperty("pid"))
        || !self.info().startInstant().orElseThrow().toString().equals(p.getProperty("start")))
      return false;
    var parent = ProcessHandle.of(Long.parseLong(p.getProperty("supervisor-pid")));
    return parent.isPresent()
        && parent.get().isAlive()
        && parent
            .get()
            .info()
            .startInstant()
            .orElseThrow()
            .toString()
            .equals(p.getProperty("supervisor-start"));
  }

  public void prune() throws Exception {
    try (var exclusive = lock("maintenance.lock");
        var stopped = new NetherFiles.Stopped(store.read())) {
      pruneInternal();
    }
  }

  private void pruneInternal() throws Exception {
    var s = store.read();
    List<Path> paths = store.successfulBackups();
    int keep = Math.max(1, (int) s.number("retention")), valid = 0;
    for (Path p : paths) {
      if (!Files.exists(p)) continue;
      NetherFiles.safe(root.resolve("nether-backups"), p);
      var owner = NetherFiles.properties(p.resolve("owner.properties"));
      if (!s.get("identity").equals(owner.getProperty("identity")))
        throw new IOException("Refusing unowned backup");
      try {
        NetherFiles.verify(p);
      } catch (Exception e) {
        log("Preserving invalid backup for inspection: " + p);
        continue;
      }
      valid++;
      if (valid > keep) {
        NetherFiles.deleteBackup(p);
        log("PRUNED older successful backup " + p);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException(
          "approve|run|apply|recover|prune|status <SMP-root> [arguments]");
    var tool = new NetherMaintenance(Path.of(args[1]));
    switch (args[0]) {
      case "approve" -> {
        if (args.length != 4) throw new IllegalArgumentException("approve ROOT JAVA SERVER-JAR");
        tool.approve(Path.of(args[2]), Path.of(args[3]));
      }
      case "run" -> tool.run();
      case "apply" -> tool.apply();
      case "recover" -> {
        if (args.length != 3) throw new IllegalArgumentException("recover ROOT OPERATION-ID");
        tool.recover(args[2]);
      }
      case "prune" -> tool.prune();
      case "status" -> {
        System.out.println(tool.store.read());
        tool.store.history().forEach(System.out::println);
      }
      default -> throw new IllegalArgumentException("Unknown action");
    }
  }
}
