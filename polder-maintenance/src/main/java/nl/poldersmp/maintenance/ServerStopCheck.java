package nl.poldersmp.maintenance;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Decides whether the server is stopped, without ever touching the server process.
 *
 * <p>Two independent checks:
 *
 * <ol>
 *   <li>{@code session.lock} files. A running Minecraft server holds a lock on the one in each world
 *       folder it has loaded. If we cannot lock (or even open) it, the server is running.
 *   <li>The game port from {@code server.properties}. If we cannot bind it, something is using it.
 * </ol>
 *
 * "Stopped" is only reported when no check shows a running server AND at least one check positively
 * succeeded (a lock was acquired, or the port was free). Missing information means "unknown", which is
 * treated as not stopped.
 */
public final class ServerStopCheck implements ServerProbe {
  private final Path serverRoot;
  private final List<Path> worldFolders;

  public ServerStopCheck(Path serverRoot, List<Path> worldFolders) {
    this.serverRoot = serverRoot;
    this.worldFolders = List.copyOf(worldFolders);
  }

  @Override
  public Result check() {
    List<String> running = new ArrayList<>();
    List<String> proof = new ArrayList<>();

    for (Path folder : worldFolders) {
      Path lock = folder.resolve("session.lock");
      if (Files.notExists(lock)) {
        continue;
      }
      try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
        FileLock held = channel.tryLock();
        if (held == null) {
          running.add("session.lock is held by another process: " + lock);
        } else {
          held.release();
          proof.add("session.lock is free: " + lock);
        }
      } catch (OverlappingFileLockException e) {
        running.add("session.lock is locked inside this process: " + lock);
      } catch (IOException e) {
        // On Windows a lock held by the server makes the open itself fail.
        running.add("session.lock cannot be opened (" + e.getMessage() + "): " + lock);
      }
    }

    int port = gamePort();
    if (port > 0) {
      try (ServerSocket socket = new ServerSocket()) {
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(port));
        proof.add("game port " + port + " is free");
      } catch (IOException e) {
        running.add("game port " + port + " is in use (" + e.getMessage() + ")");
      }
    }

    if (!running.isEmpty()) {
      return new Result(false, running);
    }
    if (proof.isEmpty()) {
      return new Result(false, List.of("cannot tell: no session.lock and no server.properties found under " + serverRoot));
    }
    return new Result(true, proof);
  }

  /** The configured game port, or -1 when it cannot be read. */
  int gamePort() {
    Path file = serverRoot.resolve("server.properties");
    if (Files.notExists(file)) {
      return -1;
    }
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      p.load(in);
      int port = Integer.parseInt(p.getProperty("server-port", "25565").trim());
      return port >= 1 && port <= 65535 ? port : -1;
    } catch (IOException | NumberFormatException e) {
      return -1;
    }
  }
}
