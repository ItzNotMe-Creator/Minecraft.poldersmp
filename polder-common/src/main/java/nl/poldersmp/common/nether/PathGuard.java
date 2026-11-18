package nl.poldersmp.common.nether;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;

/**
 * Refuses unsafe paths before anything is moved or deleted.
 *
 * The Nether directory must be strictly inside the server folder, must not be (or contain, or sit
 * inside) the Overworld, the End, the primary Nether, the plugins folder or the backup folder, must
 * not go through symbolic links, and, if it already has content, must look like a Minecraft
 * dimension folder. Every rule failure throws {@link UnsafePathException} with a plain explanation.
 */
public final class PathGuard {

  private PathGuard() {}

  public static final class UnsafePathException extends IllegalStateException {
    public UnsafePathException(String message) {
      super(message);
    }
  }

  /**
   * @param serverRoot the folder that holds server.jar
   * @param candidate the Nether dimension folder (absolute, or relative to serverRoot)
   * @param protectedDirs Overworld, End, primary Nether, plugins and backup folders
   * @return the verified, normalised absolute path
   */
  public static Path requireSafeDimensionDir(Path serverRoot, Path candidate, Collection<Path> protectedDirs) {
    try {
      Path root = serverRoot.toRealPath();
      Path absolute = candidate.isAbsolute() ? candidate : root.resolve(candidate);
      Path normal = absolute.normalize();
      if (!startsWith(normal, root) && Files.exists(normal)) {
        normal = normal.toRealPath(); // same folder spelled differently (8.3 names, letter case)
      }

      if (!startsWith(normal, root) || same(normal, root)) {
        throw new UnsafePathException("The Nether folder " + normal + " is not inside the server folder " + root);
      }
      requireNoSymlinks(root, normal);
      Path real = Files.exists(normal) ? normal.toRealPath() : normal;
      if (!startsWith(real, root) || same(real, root)) {
        throw new UnsafePathException("The Nether folder resolves outside the server folder: " + real);
      }

      for (Path protectedDir : protectedDirs) {
        if (protectedDir == null || protectedDir.toString().isBlank()) {
          continue;
        }
        Path other = Files.exists(protectedDir) ? protectedDir.toRealPath() : protectedDir.toAbsolutePath().normalize();
        if (same(real, other) || startsWith(real, other) || startsWith(other, real)) {
          throw new UnsafePathException("Refusing: " + real + " overlaps protected folder " + other);
        }
      }

      if (Files.exists(real)) {
        if (!Files.isDirectory(real)) {
          throw new UnsafePathException(real + " exists but is not a folder");
        }
        if (!isEmpty(real) && !looksLikeDimension(real)) {
          throw new UnsafePathException(
              real + " has content but does not look like a Minecraft dimension folder (no region/data/level.dat)");
        }
      }
      return real;
    } catch (IOException e) {
      throw new UnsafePathException("Could not verify path " + candidate + ": " + e.getMessage());
    }
  }

  /** The backup folder must not sit inside the Nether folder or any protected folder. */
  public static Path requireSafeBackupDir(Path serverRoot, Path backupDir, Path netherDir, Collection<Path> protectedDirs) {
    try {
      Path root = serverRoot.toRealPath();
      Path abs = (backupDir.isAbsolute() ? backupDir : root.resolve(backupDir)).normalize();
      Path real = Files.exists(abs) ? abs.toRealPath() : abs;
      if (same(real, root)) {
        throw new UnsafePathException("The backup folder cannot be the server folder itself");
      }
      if (startsWith(real, netherDir) || startsWith(netherDir, real)) {
        throw new UnsafePathException("The backup folder overlaps the Nether folder");
      }
      for (Path p : protectedDirs) {
        if (p == null || p.toString().isBlank()) {
          continue;
        }
        Path other = Files.exists(p) ? p.toRealPath() : p.toAbsolutePath().normalize();
        if (same(real, other) || startsWith(real, other) || startsWith(other, real)) {
          throw new UnsafePathException("The backup folder overlaps protected folder " + other);
        }
      }
      return real;
    } catch (IOException e) {
      throw new UnsafePathException("Could not verify backup path: " + e.getMessage());
    }
  }

  static boolean looksLikeDimension(Path dir) {
    return Files.isDirectory(dir.resolve("region"))
        || Files.exists(dir.resolve("level.dat"))
        || Files.isDirectory(dir.resolve("data"))
        || Files.isDirectory(dir.resolve("DIM-1"));
  }

  private static boolean isEmpty(Path dir) throws IOException {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      return !entries.iterator().hasNext();
    }
  }

  private static void requireNoSymlinks(Path root, Path target) {
    Path current = root;
    for (Path part : root.relativize(target)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) {
        throw new UnsafePathException("Refusing: " + current + " is a symbolic link");
      }
    }
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static String key(Path p) {
    String s = p.toString();
    return windows() ? s.toLowerCase(Locale.ROOT) : s;
  }

  static boolean same(Path a, Path b) {
    return key(a).equals(key(b));
  }

  static boolean startsWith(Path child, Path parent) {
    if (windows()) {
      return key(child).startsWith(key(parent) + java.io.File.separator) || same(child, parent);
    }
    return child.startsWith(parent);
  }
}
