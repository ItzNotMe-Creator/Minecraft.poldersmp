package nl.poldersmp.core.events;

import java.util.*;
import org.bukkit.Material;

public final class MeteorEvent implements WorldEvent {
  public EventRules.Type type() {
    return EventRules.Type.METEOR;
  }

  public List<Offset> structure() {
    return WorldEvent.cache(Material.POLISHED_BLACKSTONE);
  }

  public void tick(EventManager.Context c) {
    c.manager().fall(c, Material.MAGMA_BLOCK);
  }
}
