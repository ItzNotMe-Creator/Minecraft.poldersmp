package nl.poldersmp.core.events;

import java.util.*;
import org.bukkit.Material;

public interface WorldEvent {
  record Offset(int x, int y, int z, Material material) {}

  record ChestOffset(int index, int x, int y, int z) {}

  EventRules.Type type();

  List<Offset> structure();

  default List<ChestOffset> chests() {
    return List.of(new ChestOffset(0, 0, 0, 0));
  }

  default boolean ocean() {
    return false;
  }

  void tick(EventManager.Context context);

  default void defeated(EventManager.Context context) {
    context.manager().bossFinished(context, false);
  }

  static List<Offset> cache(Material accent) {
    return List.of(
        new Offset(0, 0, 0, Material.BARREL),
        new Offset(-2, 0, -2, accent),
        new Offset(-2, 0, 2, accent),
        new Offset(2, 0, -2, accent),
        new Offset(2, 0, 2, accent));
  }
}
