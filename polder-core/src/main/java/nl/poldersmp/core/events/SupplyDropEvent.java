package nl.poldersmp.core.events;

import java.util.*;
import org.bukkit.Material;

public final class SupplyDropEvent implements WorldEvent {
  public EventRules.Type type() {
    return EventRules.Type.SUPPLY_DROP;
  }

  public List<Offset> structure() {
    return WorldEvent.cache(Material.CUT_COPPER);
  }

  public void tick(EventManager.Context c) {
    c.manager().fall(c, Material.BARREL);
  }
}
