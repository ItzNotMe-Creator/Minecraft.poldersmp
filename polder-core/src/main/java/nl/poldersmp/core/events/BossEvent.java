package nl.poldersmp.core.events;

import java.util.*;
import org.bukkit.Material;

public final class BossEvent implements WorldEvent {
  public EventRules.Type type() {
    return EventRules.Type.BOSS;
  }

  public List<Offset> structure() {
    return List.of(
        new Offset(-3, 0, -3, Material.CHISELED_STONE_BRICKS),
        new Offset(-3, 0, 3, Material.CHISELED_STONE_BRICKS),
        new Offset(3, 0, -3, Material.CHISELED_STONE_BRICKS),
        new Offset(3, 0, 3, Material.CHISELED_STONE_BRICKS));
  }

  public List<ChestOffset> chests() {
    return List.of();
  }

  public void tick(EventManager.Context c) {
    c.manager().boss(c);
  }
}
