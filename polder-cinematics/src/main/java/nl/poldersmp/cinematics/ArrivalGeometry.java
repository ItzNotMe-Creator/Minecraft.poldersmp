package nl.poldersmp.cinematics;

import java.util.*;

public final class ArrivalGeometry {
  private ArrivalGeometry() {}

  public record Vec(double x, double y, double z) {}

  public static List<Vec> ring(double radius, double height, int points, double angle) {
    if (!Double.isFinite(radius)
        || radius < 0
        || radius > 12
        || !Double.isFinite(height)
        || !Double.isFinite(angle)
        || points < 3
        || points > 96) throw new IllegalArgumentException("Invalid ring");
    List<Vec> out = new ArrayList<>();
    for (int i = 0; i < points; i++) {
      double a = angle + i * Math.PI * 2 / points;
      out.add(new Vec(Math.cos(a) * radius, height, Math.sin(a) * radius));
    }
    return List.copyOf(out);
  }

  /** Six-point route oriented to the actual configured spawn, never a guessed world coordinate. */
  public static List<CameraPath.Point> route(CameraPath.Point spawn, double radius, double height) {
    return route(spawn, radius, height, "ORBITAL");
  }

  public static List<CameraPath.Point> route(
      CameraPath.Point spawn, double radius, double height, String profile) {
    if (!Set.of("ISLAND_AVENUE", "ORBITAL").contains(profile))
      throw new IllegalArgumentException("Unknown route profile");
    boolean avenue = profile.equals("ISLAND_AVENUE");
    if (!Double.isFinite(radius)
        || !Double.isFinite(height)
        || radius < 6
        || radius > 32
        || height < 6
        || height > 48) throw new IllegalArgumentException("Route radius 6..32 and height 6..48");
    double yaw = Math.toRadians(spawn.yaw()),
        fx = -Math.sin(yaw),
        fz = Math.cos(yaw),
        rx = Math.cos(yaw),
        rz = Math.sin(yaw);
    double[][] offsets =
        avenue
            ? new double[][] {
              {-1.5, -.35, 1}, {-1, -.2, .8}, {-.55, .25, .55}, {-.3, .1, .3}, {-.1, 0, .11}
            }
            : new double[][] {
              {-.9, .5, 1}, {.2, 1, .8}, {.8, -.6, .6}, {-.4, -.4, .35}, {-.18, 0, .13}
            };
    List<CameraPath.Point> out = new ArrayList<>();
    int index = 0;
    for (var o : offsets) {
      double focus = avenue ? 1 - index++ / 5.0 : 0, lookForward = 3 + focus * 7;
      double x = spawn.x() + radius * (fx * o[0] + rx * o[1]),
          y = spawn.y() + height * o[2],
          z = spawn.z() + radius * (fz * o[0] + rz * o[1]);
      double dx = spawn.x() + fx * lookForward - x,
          dz = spawn.z() + fz * lookForward - z,
          dy =
              spawn.y()
                  + (avenue ? new double[] {18, 16, 12, 6, 1.8}[index - 1] * height / 24 : 0)
                  - y;
      float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)),
          pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
      out.add(new CameraPath.Point(x, y, z, lookYaw, pitch));
    }
    out.add(spawn);
    return List.copyOf(out);
  }
}
