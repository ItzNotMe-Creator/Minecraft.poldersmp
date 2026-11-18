package nl.poldersmp.common.nether;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Archives a Nether folder and proves the archive is complete.
 *
 * A "manifest" lists every file with its size and SHA-256. The manifest of the original folder is
 * taken BEFORE it is moved, and the archive is compared against it AFTER the move, so a backup that
 * lost or changed a single byte is detected. Nothing here ever deletes a folder that has not first
 * been verified in its new location.
 */
public final class BackupService {

  public static final String MANIFEST_NAME = ".polder-manifest.txt";
  public static final String STATUS_NAME = ".polder-status.properties";
  /** Files the Minecraft server recreates by itself and that may be locked; never part of a manifest. */
  private static final List<String> IGNORED = List.of("session.lock");

  private BackupService() {}

  public record Entry(long size, String sha256) {}

  public record Manifest(Map<String, Entry> files) {
    public long totalBytes() {
      return files.values().stream().mapToLong(Entry::size).sum();
    }
  }

  // ------------------------------------------------------------------ manifest

  public static Manifest scan(Path dir) throws IOException {
    Map<String, Entry> files = new TreeMap<>();
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            String name = file.getFileName().toString();
            if (attrs.isSymbolicLink()) {
              throw new IOException("Symbolic link inside the folder: " + file);
            }
            if (IGNORED.contains(name) || name.equals(MANIFEST_NAME) || name.equals(STATUS_NAME)) {
              return FileVisitResult.CONTINUE;
            }
            String relative = dir.relativize(file).toString().replace('\\', '/');
            files.put(relative, new Entry(attrs.size(), sha256(file)));
            return FileVisitResult.CONTINUE;
          }
        });
    return new Manifest(files);
  }

  public static void writeManifest(Path file, Manifest manifest) throws IOException {
    StringBuilder text = new StringBuilder("# polder-manifest v1\n");
    manifest.files().forEach((path, e) -> text.append(e.sha256()).append('\t').append(e.size()).append('\t').append(path).append('\n'));
    Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
  }

  public static Manifest readManifest(Path file) throws IOException {
    Map<String, Entry> files = new TreeMap<>();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    if (lines.isEmpty() || !lines.get(0).startsWith("# polder-manifest v1")) {
      throw new IOException("Not a manifest file: " + file);
    }
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.split("\t", 3);
      if (parts.length != 3) {
        throw new IOException("Bad manifest line: " + line);
      }
      files.put(parts[2], new Entry(Long.parseLong(parts[1]), parts[0]));
    }
    return new Manifest(files);
  }

  /** Empty list = identical. Otherwise one readable problem per difference. */
  public static List<String> compare(Manifest expected, Manifest actual) {
    List<String> problems = new ArrayList<>();
    for (var e : expected.files().entrySet()) {
      Entry other = actual.files().get(e.getKey());
      if (other == null) {
        problems.add("missing: " + e.getKey());
      } else if (other.size() != e.getValue().size()) {
        problems.add("size differs: " + e.getKey());
      } else if (!other.sha256().equals(e.getValue().sha256())) {
        problems.add("content differs: " + e.getKey());
      }
    }
    for (String key : actual.files().keySet()) {
      if (!expected.files().containsKey(key)) {
        problems.add("unexpected extra file: " + key);
      }
    }
    return problems;
  }

  /** Re-reads a finished archive and checks it against the manifest stored inside it. */
  public static List<String> verifyArchive(Path archive) throws IOException {
    Path manifestFile = archive.resolve(MANIFEST_NAME);
    if (Files.notExists(manifestFile)) {
      return List.of("manifest missing: archive cannot be verified");
    }
    return compare(readManifest(manifestFile), scan(archive));
  }

  // ------------------------------------------------------------------ archiving

  /**
   * Moves {@code source} to {@code archive} (which must not exist) and verifies the result.
   *
   * Same drive: a rename (instant, no extra disk space). Different drive: copy, verify, and only then
   * remove the original. The manifest is written next to the archive first, so a crash at any point
   * leaves enough information for recovery.
   *
   * @return the manifest of the archived folder
   */
  public static Manifest archive(Path source, Path archive) throws IOException {
    if (Files.notExists(source) || !Files.isDirectory(source)) {
      throw new IOException("Nothing to archive: " + source);
    }
    if (Files.exists(archive)) {
      throw new IOException("Archive folder already exists, refusing to overwrite: " + archive);
    }
    Files.createDirectories(archive.toAbsolutePath().getParent());

    Manifest before = scan(source);
    Path sidecar = archive.resolveSibling(archive.getFileName() + ".manifest");
    writeManifest(sidecar, before);

    if (sameStore(source, archive.toAbsolutePath().getParent())) {
      Files.move(source, archive, StandardCopyOption.ATOMIC_MOVE);
    } else {
      copyTree(source, archive);
      List<String> problems = compare(before, scan(archive));
      if (!problems.isEmpty()) {
        throw new IOException("Copy to archive is not identical, original kept: " + problems);
      }
      deleteTree(source);
    }

    List<String> problems = compare(before, scan(archive));
    if (!problems.isEmpty()) {
      throw new IOException("Archive does not match the original: " + problems);
    }
    Files.move(sidecar, archive.resolve(MANIFEST_NAME), StandardCopyOption.REPLACE_EXISTING);
    return before;
  }

  /** Writes the "this archive was verified" marker that retention relies on. */
  public static void markValid(Path archive, String operationId, Long oldSeed) throws IOException {
    java.util.Properties p = new java.util.Properties();
    p.setProperty("valid", "true");
    p.setProperty("operation", operationId);
    p.setProperty("created", Long.toString(System.currentTimeMillis()));
    if (oldSeed != null) {
      p.setProperty("seed", Long.toString(oldSeed));
    }
    try (var out = Files.newOutputStream(archive.resolve(STATUS_NAME))) {
      p.store(out, "Verified by PolderSMP maintenance tool");
    }
  }

  public static boolean isMarkedValid(Path archive) {
    Path status = archive.resolve(STATUS_NAME);
    if (Files.notExists(status) || Files.notExists(archive.resolve(MANIFEST_NAME))) {
      return false;
    }
    java.util.Properties p = new java.util.Properties();
    try (InputStream in = Files.newInputStream(status)) {
      p.load(in);
    } catch (IOException e) {
      return false;
    }
    return "true".equals(p.getProperty("valid"));
  }

  // ------------------------------------------------------------------ helpers

  static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[1 << 16];
      int read;
      while ((read = in.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean sameStore(Path a, Path b) {
    try {
      FileStore first = Files.getFileStore(a);
      FileStore second = Files.getFileStore(b);
      return first.equals(second);
    } catch (IOException e) {
      return false;
    }
  }

  private static void copyTree(Path from, Path to) throws IOException {
    Files.walkFileTree(
        from,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            Files.createDirectories(to.resolve(from.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (!IGNORED.contains(file.getFileName().toString())) {
              Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Deletes a folder without following symbolic links. Callers must have verified the path first. */
  public static void deleteTree(Path root) throws IOException {
    Files.walkFileTree(
        root,
        java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
        Integer.MAX_VALUE,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  static boolean isRegularNoFollow(Path p) {
    return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
  }
}
