package nl.poldersmp.cinematics;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Exact live references plus a private orphan marker. No equipment, item drops or world edits. */
final class VisualAssets implements Listener {
  private final JavaPlugin plugin;
  private final NamespacedKey marker;
  private final Map<UUID, Entity> live = new HashMap<>();

  VisualAssets(JavaPlugin plugin) {
    this.plugin = plugin;
    marker = new NamespacedKey(plugin, "cinematic_visual");
    for (World world : Bukkit.getWorlds())
      for (Entity e : world.getEntities()) if (marked(e)) e.remove();
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  private boolean marked(Entity e) {
    return e.getPersistentDataContainer().has(marker);
  }

  <T extends Entity> T create(Location location, Class<T> type, UUID owner) {
    T entity =
        location
            .getWorld()
            .spawn(
                location,
                type,
                e -> {
                  e.setPersistent(false);
                  e.setInvulnerable(true);
                  e.setSilent(true);
                  e.setGravity(false);
                  e.setVisibleByDefault(false);
                  e.getPersistentDataContainer()
                      .set(marker, PersistentDataType.STRING, owner.toString());
                });
    live.put(entity.getUniqueId(), entity);
    return entity;
  }

  void show(Player player, Entity entity) {
    player.showEntity(plugin, entity);
  }

  void hide(Player player, Entity entity) {
    player.hideEntity(plugin, entity);
  }

  void remove(Entity entity) {
    if (entity != null && live.remove(entity.getUniqueId(), entity)) entity.remove();
  }

  void cleanup() {
    for (Entity entity : new ArrayList<>(live.values())) remove(entity);
  }

  int count() {
    return live.size();
  }

  @EventHandler
  public void chunk(ChunkLoadEvent event) {
    for (Entity entity : event.getChunk().getEntities())
      if (marked(entity) && live.get(entity.getUniqueId()) != entity) entity.remove();
  }

  @EventHandler(ignoreCancelled = true)
  public void damage(EntityDamageEvent event) {
    if (marked(event.getEntity())) event.setCancelled(true);
  }
}
