package nl.poldersmp.common;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public record Settings(boolean enabled, boolean databaseEnabled, boolean debug) {
  public static Settings load(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path file = directory.resolve("foundation.properties");
    if (Files.notExists(file))
      try (InputStream in = Settings.class.getResourceAsStream("/foundation.properties")) {
        if (in == null) throw new IOException("Missing default configuration");
        Files.copy(in, file);
      }
    Properties p = new Properties();
    try (var in = Files.newInputStream(file)) {
      p.load(in);
    }
    return new Settings(bool(p, "module.enabled"), bool(p, "database.enabled"), bool(p, "debug"));
  }

  private static boolean bool(Properties p, String key) throws IOException {
    String v = p.getProperty(key);
    if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v))
      throw new IOException("Invalid boolean: " + key);
    return Boolean.parseBoolean(v);
  }
}
