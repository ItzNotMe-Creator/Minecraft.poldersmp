package nl.poldersmp.maintenance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;

/** Every operation is written to the console and appended to a log file. */
public final class MaintenanceLog {
  private final Path file;
  private final PrintStream console;
  private final Clock clock;

  public MaintenanceLog(Path file, PrintStream console, Clock clock) {
    this.file = file;
    this.console = console;
    this.clock = clock;
  }

  public void info(String message) {
    write("INFO ", message);
  }

  public void warn(String message) {
    write("WARN ", message);
  }

  public void error(String message) {
    write("ERROR", message);
  }

  private synchronized void write(String level, String message) {
    String line = Instant.now(clock) + " " + level + " " + message;
    if (console != null) {
      console.println(line);
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(
          file, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      if (console != null) {
        console.println("WARN  could not write log file " + file + ": " + e.getMessage());
      }
    }
  }
}
