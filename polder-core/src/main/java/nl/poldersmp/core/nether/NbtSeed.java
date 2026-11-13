package nl.poldersmp.core.nether;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

/** Read-only bounded NBT inspection of Data.WorldGenSettings.seed. Never edits level.dat. */
public final class NbtSeed {
  private NbtSeed() {}

  public static long read(Path file) throws Exception {
    try (var raw = Files.newInputStream(file);
        var zip = new GZIPInputStream(raw);
        var in = new DataInputStream(new BufferedInputStream(zip))) {
      if (in.readUnsignedByte() != 10) throw new IOException("Expected NBT compound");
      in.readUTF();
      Long seed = compound(in, "", 0);
      if (seed == null) throw new IOException("Missing saved world generation seed");
      return seed;
    }
  }

  private static Long compound(DataInputStream in, String path, int depth) throws Exception {
    if (depth > 64) throw new IOException("NBT depth");
    Long found = null;
    while (true) {
      int tag = in.readUnsignedByte();
      if (tag == 0) return found;
      String name = in.readUTF();
      String key = path + "/" + name;
      if (tag == 4 && key.equals("/Data/WorldGenSettings/seed")) found = in.readLong();
      else if (tag == 10) {
        Long child = compound(in, key, depth + 1);
        if (child != null) found = child;
      } else skip(in, tag, depth + 1);
    }
  }

  private static int length(DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > 16777216) throw new IOException("NBT length");
    return n;
  }

  private static void skip(DataInputStream in, int tag, int depth) throws Exception {
    if (depth > 64) throw new IOException("NBT depth");
    switch (tag) {
      case 1 -> in.readByte();
      case 2 -> in.readShort();
      case 3, 5 -> in.readInt();
      case 4, 6 -> in.readLong();
      case 7 -> in.skipNBytes(length(in));
      case 8 -> in.readUTF();
      case 9 -> {
        int t = in.readUnsignedByte(), n = length(in);
        for (int i = 0; i < n; i++) skip(in, t, depth + 1);
      }
      case 10 -> compound(in, "/ignored", depth + 1);
      case 11 -> in.skipNBytes(4L * length(in));
      case 12 -> in.skipNBytes(8L * length(in));
      default -> throw new IOException("Unknown NBT tag");
    }
  }
}
