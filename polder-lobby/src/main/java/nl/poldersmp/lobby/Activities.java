package nl.poldersmp.lobby;

import java.util.*;

/** Main-thread ownership, never additive activity flags. */
public final class Activities {
  private final Map<UUID, String> owners = new HashMap<>();

  public boolean acquire(UUID id, String owner) {
    return owners.putIfAbsent(id, owner) == null;
  }

  public void release(UUID id, String owner) {
    owners.remove(id, owner);
  }

  public boolean busy(UUID id) {
    return owners.containsKey(id);
  }

  public void clear(UUID id) {
    owners.remove(id);
  }
}
