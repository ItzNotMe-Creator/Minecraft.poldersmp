package nl.poldersmp.core.nether;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Offline world operations. No recursive operation accepts an arbitrary world path. */
public final class NetherFiles {
  private NetherFiles() {}

  public static final String MARKER = ".polder-nether.properties";

  public static Path root(ResetStore.State s) throws Exception {
    Path p = Path.of(s.get("root"));
    if (!p.isAbsolute() || !p.normalize().equals(p) || !p.toRealPath().equals(p))
      throw new IOException("Non-canonical server root");
    return p;
  }

  public static Properties properties(Path p) throws Exception {
    Properties v = new Properties();
    try (var in = Files.newInputStream(p)) {
      v.load(in);
    }
    return v;
  }

  public static void atomic(Path path, Properties p) throws Exception {
    Files.createDirectories(path.getParent());
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
    try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW)) {
      p.store(out, "Polder Nether maintenance");
    }
    try (var c = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
      c.force(true);
    }
    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  public static Path target(ResetStore.State s, boolean exists) throws Exception {
    Path root = root(s);
    String name = s.get("name");
    if (!name.matches("polder_nether(?:_[a-zA-Z0-9_-]+)?"))
      throw new IOException("Managed world must use a dedicated polder_nether name");
    Properties server = properties(root.resolve("server.properties"));
    String primary = server.getProperty("level-name", "world");
    if (!primary.matches("[a-zA-Z0-9_-]+"))
      throw new IOException("Unsupported primary world path; manual review required");
    if (Set.of(primary, primary + "_nether", primary + "_the_end").contains(name))
      throw new IOException("Reserved primary dimension");
    Path p = root.resolve(name);
    safe(root, p);
    if (exists) {
      if (!Files.isDirectory(p) || !Files.isRegularFile(p.resolve("level.dat")))
        throw new IOException("Not a complete managed world");
      Properties marker = properties(p.resolve(MARKER));
      if (!s.get("identity").equals(marker.getProperty("identity"))
          || !name.equals(marker.getProperty("name")))
        throw new IOException("World identity mismatch");
      if (!Files.isDirectory(p.resolve("DIM-1")))
        throw new IOException("Expected Paper Nether DIM-1 storage");
      if (Files.exists(p.resolve("DIM1")) || Files.exists(p.resolve("region")))
        throw new IOException("Unexpected Overworld/End terrain in target");
    }
    return p;
  }

  public static void safe(Path root, Path p) throws Exception {
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !root.toRealPath().equals(root))
      throw new IOException("Aliased path root");
    Path n = p.toAbsolutePath().normalize();
    if (!n.startsWith(root) || n.equals(root) || !n.equals(p.toAbsolutePath()))
      throw new IOException("Unsafe path: " + p);
    Path at = root;
    for (Path part : root.relativize(n)) {
      at = at.resolve(part);
      if (Files.exists(at, LinkOption.NOFOLLOW_LINKS)
          && (!at.toRealPath().equals(at) || Files.isSymbolicLink(at)))
        throw new IOException("Link/reparse/alias path: " + at);
    }
  }

  public static String sha(Path p) throws Exception {
    var d = MessageDigest.getInstance("SHA-256");
    try (var in = Files.newInputStream(p)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) >= 0) d.update(b, 0, n);
    }
    return HexFormat.of().formatHex(d.digest());
  }

  public static List<Path> tree(Path p) throws Exception {
    List<Path> all;
    try (var walk = Files.walk(p)) {
      all = walk.sorted().toList();
    }
    for (Path f : all) {
      if (Files.isSymbolicLink(f)
          || !f.toRealPath().equals(f)
          || (!Files.isRegularFile(f) && !Files.isDirectory(f)))
        throw new IOException("Unsupported world file/link: " + f);
    }
    return all;
  }

  public static void copy(Path from, Path to) throws Exception {
    copy(from, to, Map.of());
  }

  public static void copy(Path from, Path to, Map<Path, byte[]> lockedFiles) throws Exception {
    if (Files.exists(to)) throw new IOException("Destination exists");
    for (Path f : tree(from)) {
      Path dest = to.resolve(from.relativize(f));
      if (Files.isDirectory(f)) Files.createDirectory(dest);
      else if (lockedFiles.containsKey(f))
        Files.write(dest, lockedFiles.get(f), StandardOpenOption.CREATE_NEW);
      else Files.copy(f, dest, StandardCopyOption.COPY_ATTRIBUTES);
    }
  }

  /** Verify the copied tree against its stopped source, not only against itself. */
  public static void verifySource(Path source, Path snapshot, Map<Path, byte[]> lockedFiles)
      throws Exception {
    Set<String> expected = new HashSet<>();
    for (Path p : tree(source)) {
      String relative = source.relativize(p).toString();
      expected.add(relative);
      Path copy = snapshot.resolve(relative);
      if (Files.isDirectory(p)) {
        if (!Files.isDirectory(copy))
          throw new IOException("Missing backup directory: " + relative);
      } else {
        String original =
            lockedFiles.containsKey(p)
                ? HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(lockedFiles.get(p)))
                : sha(p);
        if (!Files.isRegularFile(copy) || !original.equals(sha(copy)))
          throw new IOException("Source/backup mismatch: " + relative);
      }
    }
    Set<String> actual = new HashSet<>();
    for (Path p : tree(snapshot)) actual.add(snapshot.relativize(p).toString());
    if (!actual.equals(expected)) throw new IOException("Source/backup tree mismatch");
  }

  public static void manifest(Path container) throws Exception {
    Path snapshot = container.resolve("snapshot");
    Properties hashes = new Properties();
    for (Path f : tree(snapshot))
      if (Files.isRegularFile(f))
        hashes.setProperty(
            snapshot.relativize(f).toString().replace('\\', '/'), Files.size(f) + ":" + sha(f));
    if (!hashes.containsKey("level.dat") || !hashes.containsKey(MARKER))
      throw new IOException("Incomplete backup");
    atomic(container.resolve("manifest.properties"), hashes);
    verify(container);
  }

  public static void verify(Path container) throws Exception {
    Properties hashes = properties(container.resolve("manifest.properties"));
    Path snapshot = container.resolve("snapshot");
    int count = 0;
    if (!hashes.containsKey("level.dat") || !hashes.containsKey(MARKER))
      throw new IOException("Incomplete manifest");
    for (Path f : tree(snapshot))
      if (Files.isRegularFile(f)) {
        count++;
        String key = snapshot.relativize(f).toString().replace('\\', '/');
        if (!(Files.size(f) + ":" + sha(f)).equals(hashes.getProperty(key)))
          throw new IOException("Backup hash mismatch: " + key);
      }
    if (count != hashes.size()) throw new IOException("Backup file count mismatch");
  }

  public static void mark(Path target, ResetStore.State s) throws Exception {
    Properties p = new Properties();
    p.setProperty("identity", s.get("identity"));
    p.setProperty("name", s.get("name"));
    atomic(target.resolve(MARKER), p);
  }

  /** Holds every existing primary/managed session lock until the offline operation ends. */
  public static final class Stopped implements AutoCloseable {
    private final List<FileChannel> channels = new ArrayList<>();
    private final Map<Path, FileChannel> locked = new HashMap<>();

    public Stopped(ResetStore.State s) throws Exception {
      try {
        if (!s.get("pid").isEmpty()) {
          var p = ProcessHandle.of(s.number("pid"));
          if (p.isPresent()
              && p.get().isAlive()
              && p.get()
                  .info()
                  .startInstant()
                  .map(Object::toString)
                  .orElse("")
                  .equals(s.get("process-start")))
            throw new IOException("SMP process has not fully exited");
        }
        Path root = root(s);
        String primary =
            properties(root.resolve("server.properties")).getProperty("level-name", "world");
        if (!primary.matches("[a-zA-Z0-9_-]+")) throw new IOException("Unsupported primary path");
        if (!Files.isRegularFile(root.resolve(primary).resolve("level.dat")))
          throw new IOException("Primary Overworld missing");
        for (String name :
            List.of(primary, primary + "_nether", primary + "_the_end", s.get("name"))) {
          Path world = root.resolve(name);
          safe(root, world);
          if (!Files.exists(world)) continue;
          Path lock = world.resolve("session.lock");
          if (!Files.isRegularFile(lock)) throw new IOException("Missing session lock: " + world);
          FileChannel c = FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE);
          channels.add(c);
          locked.put(lock, c);
          if (c.tryLock() == null) throw new IOException("World still loaded: " + world);
        }
      } catch (Exception e) {
        close();
        throw e;
      }
    }

    public Map<Path, byte[]> sessionContents(Path world) throws Exception {
      Path path = world.resolve("session.lock");
      FileChannel c = locked.get(path);
      if (c == null || c.size() > 1024) throw new IOException("Unrecognized session lock");
      var buffer = java.nio.ByteBuffer.allocate((int) c.size());
      while (buffer.hasRemaining())
        if (c.read(buffer, buffer.position()) < 0) throw new EOFException("Session lock changed");
      return Map.of(path, buffer.array());
    }

    public void close() throws IOException {
      for (var c : channels) c.close();
      channels.clear();
    }
  }

  public static void deleteBackup(Path p) throws Exception {
    List<Path> all = tree(p);
    for (int i = all.size() - 1; i >= 0; i--) Files.delete(all.get(i));
  }
}
