package nl.poldersmp.common.nether;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validated Nether reset settings (nether.yml). The plugin hands in the flattened YAML values
 * ({@code "backup.keep" -> 3}); anything invalid throws, so a typo can never silently change a
 * destructive operation. Pure Java: unit tested without a server.
 */
public record NetherSettings(
    boolean enabled,
    int intervalHours,
    String worldKey,
    boolean blockPrimaryNether,
    List<Long> milestonesSeconds,
    int overdueGraceSeconds,
    int resumeDelayMinutes,
    int evacuationRadius,
    boolean shutdownAutomation,
    boolean launcherVerified,
    String launcherPath,
    String backupDirectory,
    int retentionKeep,
    boolean requireNewSeed,
    Map<String, String> messages) {

  public static final List<Long> DEFAULT_MILESTONES = List.of(1800L, 600L, 300L, 60L, 10L);
  private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  /** Default message texts; {@code {time}} and {@code {player}} are replaced when sent. */
  public static final Map<String, String> DEFAULT_MESSAGES =
      Map.of(
          "countdown", "<gold>[Nether]</gold> The Nether resets in <yellow>{time}</yellow>. Leave the Nether before then!",
          "locked", "<gold>[Nether]</gold> The Nether is now closed for its scheduled reset.",
          "evacuated", "<gold>[Nether]</gold> You were moved to the Overworld because the Nether is resetting.",
          "shutdown", "<gold>[Nether]</gold> The server is stopping for the Nether reset.",
          "completed", "<gold>[Nether]</gold> <green>The Nether has been reset. A brand new Nether is open!</green>",
          "failed", "<gold>[Nether]</gold> <red>The Nether reset did not finish. Nether access stays closed until an admin fixes it.</red>",
          "denied", "<gold>[Nether]</gold> <red>The Nether is closed right now.</red>");

  public static NetherSettings defaults() {
    return fromMap(Map.of());
  }

  /** @throws IllegalArgumentException naming the offending key */
  public static NetherSettings fromMap(Map<String, Object> v) {
    Map<String, String> messages = new LinkedHashMap<>(DEFAULT_MESSAGES);
    for (String key : DEFAULT_MESSAGES.keySet()) {
      Object text = v.get("messages." + key);
      if (text != null) {
        messages.put(key, String.valueOf(text));
      }
    }
    String worldKey = text(v, "world.key", "polder:resource_nether").toLowerCase(java.util.Locale.ROOT);
    if (!KEY.matcher(worldKey).matches() || worldKey.contains("..")) {
      throw new IllegalArgumentException("world.key must look like namespace:name, got '" + worldKey + "'");
    }
    boolean shutdown = bool(v, "automation.shutdown-enabled", false);
    boolean launcherVerified = bool(v, "automation.launcher-verified", false);
    if (shutdown && !launcherVerified) {
      throw new IllegalArgumentException(
          "automation.shutdown-enabled needs automation.launcher-verified: true. Unattended shutdowns stay"
              + " off until the launcher and restart procedure have been verified.");
    }
    return new NetherSettings(
        bool(v, "enabled", true),
        integer(v, "interval-hours", 120, 1, 24 * 60),
        worldKey,
        bool(v, "block-primary-nether", true),
        milestones(v.get("announcements.seconds-before")),
        integer(v, "overdue-grace-seconds", 60, 10, 3600),
        integer(v, "resume-delay-minutes", 30, 1, 1440),
        integer(v, "evacuation.search-radius", 24, 0, 128),
        shutdown,
        launcherVerified,
        text(v, "automation.launcher-path", ""),
        text(v, "backup.directory", "backups/nether"),
        integer(v, "backup.keep", 3, 1, 1000),
        bool(v, "verify.require-new-seed", true),
        Collections.unmodifiableMap(messages));
  }

  private static boolean bool(Map<String, Object> v, String key, boolean fallback) {
    Object value = v.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean flag) {
      return flag;
    }
    throw new IllegalArgumentException(key + " must be true or false");
  }

  private static int integer(Map<String, Object> v, String key, int fallback, int min, int max) {
    Object value = v.get(key);
    if (value == null) {
      return fallback;
    }
    if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
      throw new IllegalArgumentException(key + " must be a whole number");
    }
    long n = number.longValue();
    if (n < min || n > max) {
      throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", got " + n);
    }
    return (int) n;
  }

  private static String text(Map<String, Object> v, String key, String fallback) {
    Object value = v.get(key);
    return value == null ? fallback : String.valueOf(value).trim();
  }

  private static List<Long> milestones(Object raw) {
    if (raw == null) {
      return DEFAULT_MILESTONES;
    }
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("announcements.seconds-before must be a non-empty list of seconds");
    }
    List<Long> out = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Number n) || n.longValue() < 1 || n.longValue() > 7 * 24 * 3600) {
        throw new IllegalArgumentException("announcements.seconds-before entries must be 1..604800 seconds");
      }
      out.add(n.longValue());
    }
    out.sort(Collections.reverseOrder());
    for (int i = 1; i < out.size(); i++) {
      if (out.get(i).equals(out.get(i - 1))) {
        throw new IllegalArgumentException("announcements.seconds-before has a duplicate entry");
      }
    }
    return List.copyOf(out);
  }
}
