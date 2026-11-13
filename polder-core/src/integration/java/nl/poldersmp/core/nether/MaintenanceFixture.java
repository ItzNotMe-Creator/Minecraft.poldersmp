package nl.poldersmp.core.nether;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Synthetic NBT/directories only: exercises the Windows wrapper without a Minecraft world. */
public final class MaintenanceFixture {
  static void nbt(Path file, long seed) throws Exception {
    try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
      out.writeByte(10);
      out.writeUTF("");
      out.writeByte(10);
      out.writeUTF("Data");
      out.writeByte(10);
      out.writeUTF("WorldGenSettings");
      out.writeByte(4);
      out.writeUTF("seed");
      out.writeLong(seed);
      out.writeByte(0);
      out.writeByte(0);
      out.writeByte(0);
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[1]).toAbsolutePath().normalize();
    if (!root.getFileName().toString().endsWith("nether-fixture"))
      throw new IOException("Dedicated fixture directory required");
    if (args[0].equals("init")) {
      Files.createDirectory(root);
      Files.writeString(root.resolve(".polder-disposable-test"), "synthetic fixture");
      Files.writeString(root.resolve("server.properties"), "level-name=world\n");
      for (String name : List.of("world", "world_nether", "world_the_end", "polder_nether")) {
        Path p = Files.createDirectory(root.resolve(name));
        nbt(p.resolve("level.dat"), 42);
        Files.writeString(p.resolve("session.lock"), "lock");
      }
      Path data = Files.createDirectories(root.resolve("plugins/PolderCore"));
      var store = new ResetStore(data.resolve("nether-reset.db"));
      store.migrate();
      var s = store.initialize(root.toRealPath(), "polder_nether", 42, 1000, 432000000, 3);
      Files.createDirectories(root.resolve("polder_nether/DIM-1/region"));
      Files.writeString(
          root.resolve("polder_nether/DIM-1/region/example.mca"), "synthetic fixture content");
      NetherFiles.mark(root.resolve("polder_nether"), s);
      s = store.request(2000, 1, "not-this-process");
      store.change(s, Map.of("phase", "WAITING_STOP"));
      Files.writeString(root.resolve("operation.txt"), s.get("operation"));
    } else {
      if (!Files.isRegularFile(root.resolve(".polder-disposable-test")))
        throw new IOException("Missing disposable marker");
      var store = new ResetStore(root.resolve("plugins/PolderCore/nether-reset.db"));
      var s = store.read();
      if (args[0].equals("archived")) {
        if (!s.phase().equals("GENERATING") || Files.exists(root.resolve("polder_nether")))
          throw new AssertionError("Archive transition failed");
        NetherFiles.verify(Path.of(s.get("backup")));
      } else if (args[0].equals("recovered")) {
        if (!s.phase().equals("VERIFYING")
            || NbtSeed.read(root.resolve("polder_nether/level.dat")) != 42)
          throw new AssertionError("Recovery failed");
      } else throw new IllegalArgumentException("Unknown fixture action");
      for (String n : List.of("world", "world_nether", "world_the_end"))
        if (NbtSeed.read(root.resolve(n).resolve("level.dat")) != 42)
          throw new AssertionError("Primary world changed");
    }
    System.out.println("MAINTENANCE FIXTURE PASS " + args[0]);
  }
}
