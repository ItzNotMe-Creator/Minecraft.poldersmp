package nl.poldersmp.cinematics;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable validated snapshot: active shows never mix old/new configuration. */
public record ShowSettings(
    boolean enabled,
    String theme,
    String density,
    boolean camera,
    int interpolation,
    double routeRadius,
    double routeHeight,
    String routeProfile,
    boolean hideBorder,
    boolean clearPath,
    boolean landing,
    double duration,
    boolean overlay,
    int burst,
    boolean items,
    boolean runes,
    boolean nearby,
    double viewerRadius,
    int viewers,
    String title,
    String subtitle,
    float volume,
    boolean flightRings,
    boolean stardust,
    String actionbar,
    int sessions,
    int packets,
    int particles,
    int perSession,
    Color primary,
    Color secondary,
    Color accent,
    Material crystal) {
  public static final Set<String> THEMES = Set.of("CELESTIAL", "INFERNO", "AURORA");

  private static int integer(YamlConfiguration y, String key, int min, int max) {
    int n = y.getInt(key);
    if (!y.isInt(key) || n < min || n > max)
      throw new IllegalArgumentException(key + " must be " + min + ".." + max);
    return n;
  }

  private static double number(YamlConfiguration y, String key, double min, double max) {
    double n = y.getDouble(key);
    if (!y.isDouble(key) && !y.isInt(key) || !Double.isFinite(n) || n < min || n > max)
      throw new IllegalArgumentException(key + " must be " + min + ".." + max);
    return n;
  }

  private static boolean bool(YamlConfiguration y, String key) {
    if (!y.isBoolean(key)) throw new IllegalArgumentException("Boolean required: " + key);
    return y.getBoolean(key);
  }

  private static String text(YamlConfiguration y, String key) {
    String s = y.getString(key);
    if (s == null || s.length() > 300) throw new IllegalArgumentException("Invalid text: " + key);
    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        .deserialize(
            s,
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                "player", "Player"));
    return s;
  }

  private static Color color(YamlConfiguration y, String key) {
    String value = Objects.requireNonNullElse(y.getString(key), "");
    if (!value.matches("#[0-9a-fA-F]{6}"))
      throw new IllegalArgumentException("Expected #RRGGBB: " + key);
    return Color.fromRGB(Integer.parseInt(value.substring(1), 16));
  }

  public static ShowSettings load(YamlConfiguration y) {
    return load(y, Material::isItem);
  }

  static ShowSettings load(YamlConfiguration y, java.util.function.Predicate<Material> isItem) {
    String theme = Objects.requireNonNullElse(y.getString("theme"), "").toUpperCase(Locale.ROOT),
        density = Objects.requireNonNullElse(y.getString("density"), "").toUpperCase(Locale.ROOT);
    if (!THEMES.contains(theme) || !Set.of("LOW", "MEDIUM", "HIGH").contains(density))
      throw new IllegalArgumentException("Unknown theme/density");
    String profile =
        Objects.requireNonNullElse(y.getString("setup.profile"), "").toUpperCase(Locale.ROOT);
    if (!Set.of("ISLAND_AVENUE", "ORBITAL").contains(profile))
      throw new IllegalArgumentException("Unknown route profile");
    String base = "themes." + theme + ".";
    Material crystal =
        Material.matchMaterial(Objects.requireNonNullElse(y.getString(base + "crystal"), ""));
    if (crystal == null
        || !isItem.test(crystal)
        || (crystal == Material.AIR
            || crystal == Material.CAVE_AIR
            || crystal == Material.VOID_AIR))
      throw new IllegalArgumentException("Invalid crystal material");
    int packets = integer(y, "performance.maximum-packets-per-tick", 100, 3000),
        particles = integer(y, "performance.maximum-particles-per-tick", 100, 16000);
    if (particles < packets)
      throw new IllegalArgumentException("Particle budget must cover packet budget");
    return new ShowSettings(
        bool(y, "enabled"),
        theme,
        density,
        bool(y, "smooth-camera.enabled"),
        integer(y, "smooth-camera.interpolation-ticks", 1, 5),
        number(y, "setup.radius", 6, 32),
        number(y, "setup.height", 6, 48),
        profile,
        bool(y, "flight.hide-world-border"),
        bool(y, "flight.require-clear-path"),
        bool(y, "landing.enabled"),
        number(y, "landing.duration-seconds", 2, 10),
        bool(y, "landing.totem-overlay"),
        integer(y, "landing.burst-particles", 0, 300),
        bool(y, "landing.orbit-items"),
        bool(y, "landing.floating-runes"),
        bool(y, "landing.nearby-viewers"),
        number(y, "landing.viewer-radius", 8, 32),
        integer(y, "landing.maximum-nearby-viewers", 0, 8),
        text(y, "landing.title"),
        text(y, "landing.subtitle"),
        (float) number(y, "landing.sound-volume", 0, 1),
        bool(y, "flight.portal-rings"),
        bool(y, "flight.stardust"),
        text(y, "flight.actionbar"),
        integer(y, "performance.maximum-sessions", 1, 32),
        packets,
        particles,
        integer(y, "performance.maximum-packets-per-session", 40, 1000),
        color(y, base + "primary"),
        color(y, base + "secondary"),
        color(y, base + "accent"),
        crystal);
  }

  public int samples() {
    return switch (density) {
      case "LOW" -> 12;
      case "MEDIUM" -> 20;
      default -> 28;
    };
  }
}
