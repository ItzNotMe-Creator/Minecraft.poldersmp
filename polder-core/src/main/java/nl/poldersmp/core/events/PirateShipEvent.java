package nl.poldersmp.core.events;

import java.util.*;
import org.bukkit.Material;

public final class PirateShipEvent implements WorldEvent {
  public EventRules.Type type() {
    return EventRules.Type.PIRATE_SHIP;
  }

  public boolean ocean() {
    return true;
  }

  public List<ChestOffset> chests() {
    return List.of(new ChestOffset(0, -2, 1, 0), new ChestOffset(1, 2, 1, 0));
  }

  public List<Offset> structure() {
    List<Offset> out = new ArrayList<>();
    for (int x = -4; x <= 4; x++)
      for (int z = -2; z <= 2; z++) {
        out.add(new Offset(x, 0, z, Material.SPRUCE_PLANKS));
        if (Math.abs(z) == 2) out.add(new Offset(x, 1, z, Material.SPRUCE_FENCE));
      }
    for (int y = 1; y <= 5; y++) out.add(new Offset(0, y, 0, Material.OAK_FENCE));
    for (int x : new int[] {-1, 1})
      for (int y = 3; y <= 4; y++) out.add(new Offset(x, y, 0, Material.WHITE_WOOL));
    out.add(new Offset(-2, 1, 0, Material.BARREL));
    out.add(new Offset(2, 1, 0, Material.BARREL));
    return out;
  }

  public void tick(EventManager.Context c) {
    c.manager().unlock(c, List.of());
  }
}
