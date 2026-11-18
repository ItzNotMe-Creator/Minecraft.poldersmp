package nl.poldersmp.common.nether;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads and writes the reset state file (plugins/PolderCore/nether-state.properties).
 *
 * Writes go to a temporary file first and are then moved into place, so a crash can never leave a
 * half-written state file. A corrupt file is reported, never "repaired" silently: guessing the state
 * of a destructive operation is exactly what must not happen.
 */
public final class ResetStateStore {

  private final Path file;

  public ResetStateStore(Path file) {
    this.file = file;
  }

  public Path file() {
    return file;
  }

  /** A fresh IDLE state when the file does not exist yet. */
  public ResetState load() throws IOException {
    ResetState state = new ResetState();
    if (Files.notExists(file)) {
      return state;
    }
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      p.load(in);
    }
    try {
      String version = p.getProperty("format", "1");
      if (!"1".equals(version)) {
        throw new IOException("Unsupported state file format " + version);
      }
      state.restorePhase(ResetPhase.valueOf(p.getProperty("phase", "IDLE")));
      state.paused(Boolean.parseBoolean(p.getProperty("paused", "false")));
      state.restoreOperation(p.getProperty("operation.id", ""), instant(p, "operation.started"));
      state.restoreLastSuccess(instant(p, "last-success"));
      state.nextReset(instant(p, "next-reset"));
      state.currentSeed(longOrNull(p, "seed.current"));
      state.pendingSeed(longOrNull(p, "seed.pending"));
      state.worldKey(p.getProperty("world.key", ""));
      state.worldPath(p.getProperty("world.path", ""));
      List<String> protectedPaths = new ArrayList<>();
      for (int i = 0; p.getProperty("protected." + i) != null; i++) {
        protectedPaths.add(p.getProperty("protected." + i));
      }
      state.protectedPaths(protectedPaths);
      state.backupDirectory(p.getProperty("backup.directory", "backups/nether"));
      state.retentionKeep(Integer.parseInt(p.getProperty("backup.keep", "3")));
      state.archivePath(p.getProperty("operation.archive", ""));
      state.launcherPath(p.getProperty("launcher.path", ""));
      state.launcherVerified(Boolean.parseBoolean(p.getProperty("launcher.verified", "false")));
      int count = Integer.parseInt(p.getProperty("history.count", "0"));
      for (int i = 0; i < count; i++) {
        String[] parts = p.getProperty("history." + i, "").split("\\|", 5);
        if (parts.length < 5) {
          throw new IOException("Bad history entry " + i);
        }
        state.history().add(
            new ResetState.HistoryEntry(
                Instant.ofEpochMilli(Long.parseLong(parts[0])),
                parts[1],
                parts[2],
                parts[3].isEmpty() ? null : Long.parseLong(parts[3]),
                parts[4]));
      }
    } catch (RuntimeException e) {
      throw new IOException("State file " + file + " is corrupt: " + e.getMessage()
          + ". Do not delete it blindly; ask the operator (see the Nether operator guide).", e);
    }
    return state;
  }

  public void save(ResetState s) throws IOException {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("phase", s.phase().name());
    p.setProperty("paused", Boolean.toString(s.paused()));
    p.setProperty("operation.id", s.operationId());
    put(p, "operation.started", s.operationStarted());
    put(p, "last-success", s.lastSuccess());
    put(p, "next-reset", s.nextReset());
    if (s.currentSeed() != null) {
      p.setProperty("seed.current", Long.toString(s.currentSeed()));
    }
    if (s.pendingSeed() != null) {
      p.setProperty("seed.pending", Long.toString(s.pendingSeed()));
    }
    p.setProperty("world.key", s.worldKey());
    p.setProperty("world.path", s.worldPath());
    for (int i = 0; i < s.protectedPaths().size(); i++) {
      p.setProperty("protected." + i, s.protectedPaths().get(i));
    }
    p.setProperty("backup.directory", s.backupDirectory());
    p.setProperty("backup.keep", Integer.toString(s.retentionKeep()));
    p.setProperty("operation.archive", s.archivePath());
    p.setProperty("launcher.path", s.launcherPath());
    p.setProperty("launcher.verified", Boolean.toString(s.launcherVerified()));
    p.setProperty("history.count", Integer.toString(s.history().size()));
    for (int i = 0; i < s.history().size(); i++) {
      ResetState.HistoryEntry e = s.history().get(i);
      p.setProperty(
          "history." + i,
          e.at().toEpochMilli() + "|" + clean(e.operationId()) + "|" + clean(e.outcome()) + "|"
              + (e.seed() == null ? "" : e.seed()) + "|" + clean(e.detail()));
    }

    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp)) {
      p.store(out, "PolderSMP Nether reset state. Edited by the plugin and the maintenance tool; do not edit by hand.");
    }
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String clean(String text) {
    return text == null ? "" : text.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
  }

  private static void put(Properties p, String key, Instant value) {
    if (value != null) {
      p.setProperty(key, Long.toString(value.toEpochMilli()));
    }
  }

  private static Instant instant(Properties p, String key) {
    String text = p.getProperty(key);
    return text == null || text.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(text));
  }

  private static Long longOrNull(Properties p, String key) {
    String text = p.getProperty(key);
    return text == null || text.isBlank() ? null : Long.parseLong(text);
  }
}
