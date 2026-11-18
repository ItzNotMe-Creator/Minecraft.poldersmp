package nl.poldersmp.maintenance;

import java.io.IOException;
import java.nio.file.Path;

/** Starts the server through the approved launcher script. Replaceable in tests. */
public interface Launcher {
  void launch(Path launcherScript, Path workingDirectory) throws IOException;

  /** Real launcher: starts the script detached and returns immediately (does not wait for it). */
  static Launcher process() {
    return (script, dir) -> {
      boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
      ProcessBuilder pb =
          windows
              ? new ProcessBuilder("cmd", "/c", "start", "\"PolderSMP\"", "/D", dir.toString(), script.toString())
              : new ProcessBuilder("/bin/sh", script.toString());
      pb.directory(dir.toFile());
      pb.start();
    };
  }
}
