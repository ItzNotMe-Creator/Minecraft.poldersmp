package nl.poldersmp.lobby;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Location;

/** Optional DHAPI bridge. Names alone are never proof of resource ownership. Main thread only. */
final class HologramAdapter implements AutoCloseable {
  private final Class<?> api;
  private final Consumer<String> warning;
  private final Map<String, Object> owned = new HashMap<>();

  HologramAdapter(Class<?> api, Consumer<String> warning) {
    this.api = api;
    this.warning = warning;
  }

  private Object current(String name) throws ReflectiveOperationException {
    return api.getMethod("getHologram", String.class).invoke(null, name);
  }

  boolean render(String name, Location location, List<String> lines)
      throws ReflectiveOperationException {
    Object current = current(name), previous = owned.get(name);
    if (current != null && current != previous) {
      owned.remove(name);
      return false;
    }
    if (current == null) {
      owned.remove(name);
      Object created =
          api.getMethod("createHologram", String.class, Location.class, boolean.class, List.class)
              .invoke(null, name, location, false, List.copyOf(lines));
      if (created == null || current(name) != created)
        throw new IllegalStateException("Cannot confirm hologram creation ownership");
      owned.put(name, created);
      return true;
    }
    for (var method : api.getMethods())
      if (method.getName().equals("setHologramLines")
          && method.getParameterCount() == 2
          && method.getParameterTypes()[0].isInstance(current)
          && method.getParameterTypes()[1] == List.class) {
        method.invoke(null, current, List.copyOf(lines));
        return true;
      }
    throw new NoSuchMethodException("DHAPI.setHologramLines");
  }

  @Override
  public void close() {
    for (var entry : new HashMap<>(owned).entrySet())
      try {
        if (current(entry.getKey()) == entry.getValue())
          api.getMethod("removeHologram", String.class).invoke(null, entry.getKey());
        owned.remove(entry.getKey());
      } catch (ReflectiveOperationException e) {
        warning.accept(
            "Could not remove owned hologram "
                + entry.getKey()
                + ": "
                + e.getClass().getSimpleName());
      }
  }
}
