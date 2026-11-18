package nl.poldersmp.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import nl.poldersmp.common.nether.BackupService;
import nl.poldersmp.common.nether.NetherSchedule;
import nl.poldersmp.common.nether.NetherSeed;
import nl.poldersmp.common.nether.PathGuard;
import nl.poldersmp.common.nether.ResetPhase;
import nl.poldersmp.common.nether.ResetState;
import nl.poldersmp.common.nether.ResetStateStore;
import nl.poldersmp.common.nether.RetentionPolicy;

/**
 * The offline half of a Nether reset. Every public command first proves it is safe to act, and every
 * destructive step is recorded in the shared state file BEFORE it happens, so a crash leaves enough
 * information for {@link #recover()}.
 *
 * <p>Nothing here can stop or kill a process. The server must already be stopped (by the plugin's
 * controlled shutdown or by the operator).
 */
public final class MaintenanceRunner {
  public static final int OK = 0;
  public static final int FAILED = 1;
  public static final int REFUSED = 2;

  public record Outcome(int exitCode, String message) {}

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final Set<String> NEVER_A_NETHER_TARGET = Set.of("overworld", "the_nether", "the_end", "dim-1", "dim1", "world", "world_nether", "world_the_end", "minecraft");
  private static final Duration WARNING_WINDOW = Duration.ofMinutes(30);

  private final Path serverRoot;
  private final ResetStateStore store;
  private final ServerProbe probe;
  private final Launcher launcher;
  private final MaintenanceLog log;
  private final Clock clock;

  public MaintenanceRunner(Path serverRoot, ResetStateStore store, ServerProbe probe, Launcher launcher, MaintenanceLog log, Clock clock) {
    this.serverRoot = serverRoot.toAbsolutePath().normalize();
    this.store = store;
    this.probe = probe;
    this.launcher = launcher;
    this.log = log;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ status

  public Outcome status() {
    ResetState s;
    try {
      s = store.load();
    } catch (IOException e) {
      return refuse("State file problem: " + e.getMessage());
    }
    StringBuilder b = new StringBuilder();
    b.append("Phase:             ").append(s.phase()).append('\n');
    b.append("Operation:         ").append(s.operationId().isEmpty() ? "-" : s.operationId()).append('\n');
    b.append("Paused:            ").append(s.paused()).append('\n');
    b.append("Last success:      ").append(s.lastSuccess() == null ? "never" : s.lastSuccess()).append('\n');
    b.append("Next reset:        ").append(s.nextReset() == null ? "not scheduled" : s.nextReset()).append('\n');
    b.append("Current seed:      ").append(s.currentSeed() == null ? "unknown" : s.currentSeed()).append('\n');
    b.append("Pending seed:      ").append(s.pendingSeed() == null ? "-" : s.pendingSeed()).append('\n');
    b.append("Nether folder:     ").append(s.worldPath().isEmpty() ? "not recorded yet (start the server once)" : s.worldPath()).append('\n');
    b.append("Launcher verified: ").append(s.launcherVerified()).append('\n');
    ServerProbe.Result stopped = probe.check();
    b.append("Server stopped:    ").append(stopped.stopped() ? "yes" : "NO / unknown").append(" (").append(String.join("; ", stopped.reasons())).append(")\n");
    if (s.phase() == ResetPhase.MAINTENANCE_RUNNING || s.phase() == ResetPhase.FAILED || s.phase() == ResetPhase.RECOVERING) {
      b.append("!! An earlier reset did not finish. Run: recover\n");
    }
    try {
      Path root = backupRoot(s);
      if (root != null && Files.isDirectory(root)) {
        b.append("Backups in ").append(root).append(":\n");
        for (RetentionPolicy.BackupInfo info : RetentionPolicy.list(root)) {
          b.append("  ").append(info.dir().getFileName()).append(info.valid() ? "  (valid)" : "  (NOT verified)").append('\n');
        }
      }
    } catch (IOException | RuntimeException e) {
      b.append("Backups could not be listed: ").append(e.getMessage()).append('\n');
    }
    if (!s.history().isEmpty()) {
      b.append("Previous resets:\n");
      for (ResetState.HistoryEntry h : s.history()) {
        b.append("  ").append(h.at()).append("  ").append(h.outcome()).append("  ").append(h.operationId()).append("  ").append(h.detail()).append('\n');
      }
    }
    return new Outcome(OK, b.toString().stripTrailing());
  }

  // ------------------------------------------------------------------ run

  /** Archives the old Nether and prepares a new seed. Only when the server is stopped and the plugin asked for it. */
  public Outcome run() {
    ResetState s;
    try {
      s = store.load();
    } catch (IOException e) {
      return refuse("State file problem: " + e.getMessage());
    }
    switch (s.phase()) {
      case PREPARED, VERIFYING -> {
        return new Outcome(OK, "Already prepared (seed " + s.pendingSeed() + "). Nothing to do: start the server so the plugin can verify the new Nether.");
      }
      case MAINTENANCE_RUNNING, FAILED, RECOVERING -> {
        return refuse("An earlier reset (operation " + s.operationId() + ") did not finish. Run 'recover' first. Nothing was changed.");
      }
      case AWAITING_MAINTENANCE -> { }
      default -> {
        return refuse("The plugin has not finished its part (phase " + s.phase() + "). A reset must be started in the game with /netheradmin reset, and the server must reach AWAITING_MAINTENANCE. Nothing was changed.");
      }
    }
    Outcome stopped = requireStopped();
    if (stopped != null) {
      return stopped;
    }
    Path nether;
    Path backups;
    try {
      nether = netherDir(s);
      backups = backupRoot(s);
    } catch (PathGuard.UnsafePathException | IllegalArgumentException e) {
      return refuse("Unsafe path, nothing was changed: " + e.getMessage());
    }

    Instant now = Instant.now(clock);
    try {
      s.moveTo(ResetPhase.MAINTENANCE_RUNNING);
      store.save(s);
      log.info("Operation " + s.operationId() + ": maintenance started. Nether folder: " + nether);

      long seed = NetherSeed.next(s.usedSeeds());
      s.pendingSeed(seed);
      Path archive = backups.resolve(STAMP.format(now) + "_" + s.operationId());
      boolean hasOld = Files.isDirectory(nether);
      s.archivePath(hasOld ? archive.toString() : "");
      store.save(s); // recorded BEFORE anything is moved

      if (hasOld) {
        log.info("Archiving " + nether + " -> " + archive);
        BackupService.archive(nether, archive);
        List<String> problems = BackupService.verifyArchive(archive);
        if (!problems.isEmpty()) {
          throw new IOException("Backup verification failed: " + problems);
        }
        BackupService.markValid(archive, s.operationId(), s.currentSeed());
        log.info("Backup verified (" + BackupService.readManifest(archive.resolve(BackupService.MANIFEST_NAME)).files().size() + " files).");
      } else {
        log.warn("No Nether folder exists yet, so there is nothing to archive (first reset).");
      }

      s.moveTo(ResetPhase.PREPARED);
      store.save(s);
      log.info("Prepared. New Nether seed: " + seed + ". Start the server to finish the reset.");
    } catch (IOException | RuntimeException e) {
      return failed(s, "Reset preparation failed: " + e.getMessage());
    }
    pruneBackups(s, backups);
    return new Outcome(OK, "Prepared new Nether seed " + s.pendingSeed() + ". Start the server now.");
  }

  // ------------------------------------------------------------------ recover

  public Outcome recover() {
    ResetState s;
    try {
      s = store.load();
    } catch (IOException e) {
      return refuse("State file problem: " + e.getMessage());
    }
    ResetPhase phase = s.phase();
    if (phase != ResetPhase.FAILED && phase != ResetPhase.MAINTENANCE_RUNNING && phase != ResetPhase.RECOVERING) {
      return refuse("Nothing to recover (phase " + phase + ").");
    }
    Outcome stopped = requireStopped();
    if (stopped != null) {
      return stopped;
    }
    Path nether;
    try {
      nether = netherDir(s);
    } catch (PathGuard.UnsafePathException | IllegalArgumentException e) {
      return refuse("Unsafe path, nothing was changed: " + e.getMessage());
    }
    Instant now = Instant.now(clock);
    try {
      if (phase == ResetPhase.MAINTENANCE_RUNNING) {
        s.fail(now, "Incomplete maintenance run detected (tool or computer stopped midway)");
      }
      if (s.phase() == ResetPhase.FAILED) {
        s.moveTo(ResetPhase.RECOVERING);
      }
      store.save(s);
      log.info("Recovery of operation " + s.operationId() + " started.");

      Path archive = s.archivePath().isEmpty() ? null : Path.of(s.archivePath());
      boolean netherExists = Files.isDirectory(nether);
      boolean archiveExists = archive != null && Files.isDirectory(archive);
      String result;
      if (netherExists && !archiveExists) {
        result = "The old Nether is still in place; nothing had been moved.";
      } else if (netherExists) {
        // Both exist. Prove the live Nether is complete by comparing it with the manifest written
        // before the move started (inside the archive when it finished, next to it when it did not).
        Path inside = archive.resolve(BackupService.MANIFEST_NAME);
        Path sidecar = archive.resolveSibling(archive.getFileName() + ".manifest");
        Path manifestFile = Files.exists(inside) ? inside : (Files.exists(sidecar) ? sidecar : null);
        if (manifestFile == null) {
          result = "The old Nether is still in place. A folder named like the archive exists but is not one of ours; it was left alone.";
          log.warn("Archive folder without any manifest left untouched: " + archive);
        } else if (BackupService.compare(BackupService.readManifest(manifestFile), BackupService.scan(nether)).isEmpty()) {
          result = "The old Nether is in place and complete (it matches the manifest taken before the move).";
        } else {
          return failed(s, "Both the Nether folder and an archive exist and the Nether does not match the manifest. Manual check needed: " + nether + " and " + archive);
        }
      } else if (archiveExists) {
        log.info("Restoring " + archive + " -> " + nether + " (the backup is kept).");
        BackupService.restore(archive, nether);
        result = "The old Nether was restored from the backup and verified.";
      } else if (archive == null) {
        result = "There was no old Nether folder; the plugin will create it again.";
      } else {
        return failed(s, "The Nether folder and its archive are both missing: " + archive + ". Restore it from a backup by hand.");
      }
      s.rolledBack(now, NetherSchedule.afterResume(s.nextReset(), now, WARNING_WINDOW), result);
      store.save(s);
      log.info("Recovery finished: " + result);
      return new Outcome(OK, result + " The reset was rolled back; start the server.");
    } catch (IOException | RuntimeException e) {
      return failed(s, "Recovery failed: " + e.getMessage());
    }
  }

  // ------------------------------------------------------------------ verify / cleanup / start

  /** Offline consistency check of state, paths and backup. Reads only. */
  public Outcome verify() {
    ResetState s;
    try {
      s = store.load();
    } catch (IOException e) {
      return refuse("State file problem: " + e.getMessage());
    }
    List<String> problems = new ArrayList<>();
    try {
      Path nether = netherDir(s);
      if (s.phase() == ResetPhase.PREPARED) {
        if (s.pendingSeed() == null) {
          problems.add("PREPARED but no new seed recorded");
        }
        if (Files.exists(nether)) {
          problems.add("PREPARED but the Nether folder still exists: " + nether);
        }
      }
      if (!s.archivePath().isEmpty()) {
        Path archive = Path.of(s.archivePath());
        if (!Files.isDirectory(archive)) {
          problems.add("recorded archive is missing: " + archive);
        } else {
          problems.addAll(BackupService.verifyArchive(archive));
          if (!BackupService.isMarkedValid(archive) && s.phase() == ResetPhase.PREPARED) {
            problems.add("archive was not marked valid: " + archive);
          }
        }
      }
    } catch (PathGuard.UnsafePathException | IllegalArgumentException e) {
      problems.add("unsafe path: " + e.getMessage());
    } catch (IOException e) {
      problems.add("verification error: " + e.getMessage());
    }
    if (problems.isEmpty()) {
      log.info("Verify: everything consistent (phase " + s.phase() + ").");
      return new Outcome(OK, "Everything is consistent (phase " + s.phase() + ").");
    }
    problems.forEach(p -> log.warn("Verify: " + p));
    return new Outcome(FAILED, "Problems found:\n  " + String.join("\n  ", problems));
  }

  /** Applies backup retention. Refused while a reset is in the middle of its work. */
  public Outcome cleanup() {
    ResetState s;
    try {
      s = store.load();
    } catch (IOException e) {
      return refuse("State file problem: " + e.getMessage());
    }
    if (s.phase() == ResetPhase.MAINTENANCE_RUNNING || s.phase() == ResetPhase.RECOVERING) {
      return refuse("A reset is in progress; cleanup refused.");
    }
    try {
      int removed = pruneBackups(s, backupRoot(s));
      return new Outcome(OK, "Removed " + removed + " old backup(s). Valid backups kept: at least the newest " + Math.max(1, s.retentionKeep()) + ".");
    } catch (PathGuard.UnsafePathException | IllegalArgumentException e) {
      return refuse("Unsafe path: " + e.getMessage());
    }
  }

  /** Starts the server through the approved launcher, only when the launcher was verified by the operator. */
  public Outcome start() {
    ResetState s;
    try {
      s = store.load();
    } catch (IOException e) {
      return refuse("State file problem: " + e.getMessage());
    }
    if (s.phase() != ResetPhase.PREPARED) {
      return refuse("Server start is only done after a successful 'run' (phase PREPARED), not in phase " + s.phase() + ".");
    }
    if (!s.launcherVerified() || s.launcherPath().isBlank()) {
      return refuse("The launcher has not been verified (automation.launcher-verified in nether.yml). Start the server yourself with your normal start script.");
    }
    Outcome verified = verify();
    if (verified.exitCode() != OK) {
      return new Outcome(REFUSED, "Not starting: " + verified.message());
    }
    Path script = serverRoot.resolve(s.launcherPath()).normalize();
    if (!script.startsWith(serverRoot) || !Files.isRegularFile(script)) {
      return refuse("Launcher script not found inside the server folder: " + script);
    }
    Outcome stopped = requireStopped();
    if (stopped != null) {
      return stopped;
    }
    try {
      log.info("Starting the server through the approved launcher: " + script);
      launcher.launch(script, serverRoot);
    } catch (IOException e) {
      log.error("Launcher failed: " + e.getMessage());
      return new Outcome(FAILED, "Launcher failed: " + e.getMessage());
    }
    return new Outcome(OK, "Launcher started. The plugin will verify the new Nether when the server is up.");
  }

  // ------------------------------------------------------------------ helpers

  private Outcome requireStopped() {
    ServerProbe.Result result = probe.check();
    if (!result.stopped()) {
      String why = String.join("; ", result.reasons());
      log.warn("Refused: server is not verifiably stopped (" + why + ")");
      return new Outcome(REFUSED, "Refused: the server is not verifiably stopped (" + why + "). Stop it normally (type 'stop' in its console), then run this again. Nothing was changed.");
    }
    log.info("Server is stopped: " + String.join("; ", result.reasons()));
    return null;
  }

  private Outcome refuse(String message) {
    log.warn("Refused: " + message);
    return new Outcome(REFUSED, message);
  }

  private Outcome failed(ResetState s, String message) {
    log.error(message);
    try {
      if (s.phase() != ResetPhase.FAILED && s.phase() != ResetPhase.IDLE) {
        s.fail(Instant.now(clock), message);
      }
      store.save(s);
    } catch (IOException | RuntimeException e) {
      log.error("Could not record the failure in the state file: " + e.getMessage());
    }
    return new Outcome(FAILED, message + "\nThe Nether stays closed. Run 'recover' to roll back, then try again.");
  }

  private List<Path> protectedPaths(ResetState s) {
    List<Path> list = new ArrayList<>();
    for (String p : s.protectedPaths()) {
      list.add(resolve(p));
    }
    list.add(serverRoot.resolve("plugins"));
    return list;
  }

  Path netherDir(ResetState s) {
    if (s.worldPath().isBlank()) {
      throw new IllegalArgumentException("The plugin has not recorded the Nether folder yet. Start the server once with PolderCore installed.");
    }
    Path candidate = resolve(s.worldPath());
    String name = candidate.getFileName() == null ? "" : candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    Path parent = candidate.getParent();
    String parentName = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (NEVER_A_NETHER_TARGET.contains(name) || parentName.equals("minecraft")) {
      throw new PathGuard.UnsafePathException("Refusing a vanilla dimension or main world folder as the resource Nether: " + candidate);
    }
    if (Files.exists(candidate.resolve("level.dat"))) {
      throw new PathGuard.UnsafePathException("Refusing a folder that contains level.dat (a main world): " + candidate);
    }
    return PathGuard.requireSafeDimensionDir(serverRoot, candidate, protectedPaths(s));
  }

  Path backupRoot(ResetState s) {
    if (s.worldPath().isBlank()) {
      return null;
    }
    return PathGuard.requireSafeBackupDir(serverRoot, resolve(s.backupDirectory()), netherDir(s), protectedPaths(s));
  }

  private Path resolve(String text) {
    Path p = Path.of(text);
    return (p.isAbsolute() ? p : serverRoot.resolve(p)).normalize();
  }

  private int pruneBackups(ResetState s, Path backups) {
    int removed = 0;
    try {
      if (backups == null || !Files.isDirectory(backups)) {
        return 0;
      }
      for (RetentionPolicy.BackupInfo old : RetentionPolicy.selectForDeletion(RetentionPolicy.list(backups), s.retentionKeep())) {
        if (!old.dir().toAbsolutePath().normalize().startsWith(backups.toAbsolutePath().normalize())) {
          continue; // never delete outside the backup folder
        }
        log.info("Retention: deleting old verified backup " + old.dir().getFileName());
        BackupService.deleteTree(old.dir());
        removed++;
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Retention cleanup failed (the reset itself is not affected): " + e.getMessage());
    }
    return removed;
  }
}
