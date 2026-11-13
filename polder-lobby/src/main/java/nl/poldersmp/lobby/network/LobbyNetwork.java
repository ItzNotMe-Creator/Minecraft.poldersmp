package nl.poldersmp.lobby.network;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;

/**
 * Phase 8 only consumes trusted provider services; no untrusted message listener or direct connect.
 */
public final class LobbyNetwork {
  private volatile Map<String, Object> cache = Map.of();
  private volatile long fetched;
  private Object provider;
  private Class<?> api;
  private Set<?> capabilities = Set.of();

  @SuppressWarnings({"rawtypes", "unchecked"})
  private boolean find() {
    provider = null;
    api = null;
    capabilities = Set.of();
    for (Class<?> c : Bukkit.getServicesManager().getKnownServices())
      if (c.getName().equals("nl.poldersmp.common.network.NetworkBridge"))
        try {
          Object p = Bukkit.getServicesManager().getRegistration((Class) c).getProvider();
          if (((Number) c.getMethod("apiVersion").invoke(p)).intValue() == 1) {
            provider = p;
            api = c;
            capabilities = Set.copyOf((Set<?>) c.getMethod("capabilities").invoke(p));
            return true;
          }
        } catch (Exception ignored) {
        }
    return false;
  }

  public CompletableFuture<Void> refresh() {
    if (!find() || !capabilities.contains("status")) {
      cache = Map.of();
      fetched = 0;
      return CompletableFuture.completedFuture(null);
    }
    try {
      return ((CompletableFuture<Map<String, Object>>) api.getMethod("status").invoke(provider))
          .orTimeout(3, TimeUnit.SECONDS)
          .handle(
              (m, e) -> {
                m = e == null ? sanitize(m) : Map.of();
                if (e == null
                    && !m.isEmpty()
                    && m.get("online") instanceof Boolean
                    && m.get("locked") instanceof Boolean
                    && m.get("players") instanceof Number n
                    && Double.isFinite(n.doubleValue())
                    && n.doubleValue() == n.longValue()
                    && n.longValue() >= 0
                    && n.longValue() <= 100000) {
                  cache = Map.copyOf(m);
                  fetched = System.currentTimeMillis();
                } else {
                  cache = Map.of();
                  fetched = 0;
                }
                return null;
              });
    } catch (Exception e) {
      cache = Map.of();
      fetched = 0;
      return CompletableFuture.completedFuture(null);
    }
  }

  /** Snapshot bounded JDK values; never retain provider-owned mutable lists. */
  public static Map<String, Object> sanitize(Map<String, Object> input) {
    if (input == null || input.size() > 32) return Map.of();
    try {
      Map<String, Object> result = new HashMap<>();
      for (var entry : input.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        if (key == null || !key.matches("[a-zA-Z0-9_-]{1,40}")) return Map.of();
        if (value instanceof String text) {
          if (text.length() > 160) return Map.of();
        } else if (value instanceof Number n) {
          if (!Double.isFinite(n.doubleValue())) return Map.of();
        } else if (value instanceof List<?> list) {
          if (list.size() > 10
              || list.stream().anyMatch(v -> !(v instanceof String text) || text.length() > 160))
            return Map.of();
          value = List.copyOf(list);
        } else if (!(value instanceof Boolean)) return Map.of();
        result.put(key, value);
      }
      return Map.copyOf(result);
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  public Map<String, Object> status(long maxAge) {
    return fresh(System.currentTimeMillis(), fetched, maxAge) ? cache : Map.of();
  }

  public static boolean fresh(long now, long fetched, long maxAge) {
    return fetched > 0 && now >= fetched && now - fetched <= maxAge;
  }

  public static boolean allowed(Map<String, Object> status) {
    return Boolean.TRUE.equals(status.get("online")) && Boolean.FALSE.equals(status.get("locked"));
  }

  public CompletableFuture<Boolean> transfer(UUID p, String target, long age) {
    if (!allowed(status(age)) || !find() || !capabilities.contains("access-checked-transfers"))
      return CompletableFuture.completedFuture(false);
    try {
      return ((CompletableFuture<Boolean>)
              api.getMethod("transfer", UUID.class, String.class).invoke(provider, p, target))
          .orTimeout(5, TimeUnit.SECONDS)
          .exceptionally(e -> false);
    } catch (Exception e) {
      return CompletableFuture.completedFuture(false);
    }
  }

  public CompletableFuture<Map<String, Object>> profile(UUID p) {
    if (!find() || !capabilities.contains("profiles"))
      return CompletableFuture.completedFuture(Map.of());
    try {
      return ((CompletableFuture<Map<String, Object>>)
              api.getMethod("profile", UUID.class).invoke(provider, p))
          .orTimeout(3, TimeUnit.SECONDS)
          .handle((m, e) -> e == null ? sanitize(m) : Map.of());
    } catch (Exception e) {
      return CompletableFuture.completedFuture(Map.of());
    }
  }
}
