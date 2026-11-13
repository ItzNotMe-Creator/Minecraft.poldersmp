package nl.poldersmp.common.network;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 9 provider contract. Only trusted server plugins register implementations. All values use
 * JDK types so shaded Paper plugin class loaders can interoperate. transfer MUST recheck proxy
 * access restrictions at execution, never trust cached status. No inbound client/plugin message
 * constitutes authentication or a trusted provider.
 */
public interface NetworkBridge {
  int apiVersion();

  Set<String> capabilities();

  CompletableFuture<Map<String, Object>> status();

  CompletableFuture<Map<String, Object>> profile(UUID player);

  CompletableFuture<Boolean> transfer(UUID player, String server);

  CompletableFuture<String> session(UUID player);
}
