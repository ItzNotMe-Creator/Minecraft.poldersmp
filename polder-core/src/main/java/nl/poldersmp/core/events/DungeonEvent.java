package nl.poldersmp.core.events;

import java.util.*;
import org.bukkit.Material;

public final class DungeonEvent implements WorldEvent {
  public EventRules.Type type() {
    return EventRules.Type.DUNGEON;
  }

  public List<ChestOffset> chests() {
    return List.of(new ChestOffset(0, 0, 1, 0));
  }

  public List<Offset> structure() {
    List<Offset> out = new ArrayList<>();
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) {
        out.add(new Offset(x, 0, z, Material.STONE_BRICKS));
        if (Math.abs(x) == 4 || Math.abs(z) == 4) {
          if (x == 0 && Math.abs(z) == 4) continue;
          for (int y = 1; y <= 3; y++) out.add(new Offset(x, y, z, Material.MOSSY_STONE_BRICKS));
        }
      }
    out.add(new Offset(0, 1, 0, Material.BARREL));
    return out;
  }

  public void tick(EventManager.Context c) {
    c.manager().dungeon(c);
  }

  public void defeated(EventManager.Context c) {
    c.manager().bossFinished(c, true);
  }
}
