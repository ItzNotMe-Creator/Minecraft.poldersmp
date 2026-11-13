package nl.poldersmp.cinematics;

import java.util.*;

public final class CameraPath {
  private CameraPath() {}

  public record Point(double x, double y, double z, float yaw, float pitch) {}

  public static double ease(double t, String easing) {
    t = Math.clamp(t, 0, 1);
    return easing.equals("SMOOTHSTEP") ? t * t * (3 - 2 * t) : t;
  }

  public static float angle(float a, float b, double t) {
    double d =
        Math.floorMod((long) Math.round((b - a) * 1000000) + 180000000L, 360000000L) / 1000000.0
            - 180;
    return (float) (a + d * t);
  }

  private static double cubic(double a, double b, double c, double d, double t) {
    return .5
        * ((2 * b)
            + (-a + c) * t
            + (2 * a - 5 * b + 4 * c - d) * t * t
            + (-a + 3 * b - 3 * c + d) * t * t * t);
  }

  public static Point sample(List<Point> points, double t, String curve, String easing) {
    if (points.size() < 2) throw new IllegalArgumentException("At least two points");
    double progress = ease(t, easing) * (points.size() - 1);
    int i = Math.min(points.size() - 2, (int) progress);
    double local = progress - i;
    Point a = points.get(Math.max(0, i - 1)),
        b = points.get(i),
        c = points.get(i + 1),
        d = points.get(Math.min(points.size() - 1, i + 2));
    return new Point(
        curve.equals("CATMULL_ROM") ? cubic(a.x, b.x, c.x, d.x, local) : b.x + (c.x - b.x) * local,
        curve.equals("CATMULL_ROM") ? cubic(a.y, b.y, c.y, d.y, local) : b.y + (c.y - b.y) * local,
        curve.equals("CATMULL_ROM") ? cubic(a.z, b.z, c.z, d.z, local) : b.z + (c.z - b.z) * local,
        angle(b.yaw, c.yaw, local),
        (float) (b.pitch + (c.pitch - b.pitch) * local));
  }
}
