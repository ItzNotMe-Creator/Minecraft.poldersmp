package nl.poldersmp.core.events;

import java.nio.file.*;
import java.util.*;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.competition.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated offline-client fixture. Never install on a real server. */
public final class EventsLiveProbe extends JavaPlugin {
  private Database db;
  private EventStore reader;
  private EventStore.State last;

  private Player player() {
    return Objects.requireNonNull(Bukkit.getPlayerExact("EventsProbe"));
  }

  private void main(Runnable action) {
    Bukkit.getScheduler().runTask(this, action);
  }

  @Override
  public void onEnable() {
    db =
        new Database(
            Bukkit.getPluginManager()
                .getPlugin("PolderCore")
                .getDataFolder()
                .toPath()
                .resolve("polder.db"));
    reader = new EventStore(db, new Economy.Foundation());
  }

  @Override
  public void onDisable() {
    db.close();
  }

  private World world(boolean ocean) {
    String name = ocean ? "polder_events_ocean_probe" : "polder_events_land_probe";
    NamespacedKey marker = new NamespacedKey("eventsliveprobe", "test_world");
    World world = Bukkit.getWorld(name);
    if (world == null) {
      boolean existed = Files.exists(Bukkit.getWorldContainer().toPath().resolve(name));
      String layers =
          ocean
              ? "[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:stone\",\"height\":3},{\"block\":\"minecraft:water\",\"height\":60}]"
              : "[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}]";
      String settings =
          "{\"layers\":"
              + layers
              + ",\"biome\":\"minecraft:"
              + (ocean ? "deep_ocean" : "plains")
              + "\",\"structure_overrides\":[],\"lakes\":false,\"features\":false}";
      world =
          new WorldCreator(name)
              .type(WorldType.FLAT)
              .generatorSettings(settings)
              .generateStructures(false)
              .createWorld();
      if (!existed)
        world.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
    }
    if (!world.getPersistentDataContainer().has(marker, PersistentDataType.BYTE))
      throw new IllegalStateException("Refusing unmarked world");
    world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
    world.setDifficulty(Difficulty.NORMAL);
    return world;
  }

  private ItemStack armor(Material material) {
    var item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.setUnbreakable(true);
    item.setItemMeta(meta);
    return item;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    try {
      Player player = player();
      switch (args[0]) {
        case "setup" -> {
          World world = world(false);
          world(true);
          for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++)
              world.getBlockAt(x, 100, z).setType(Material.STONE, false);
          world.setSpawnLocation(0, 101, 0);
          player.setGameMode(GameMode.SURVIVAL);
          player.getInventory().setHelmet(armor(Material.NETHERITE_HELMET));
          player.getInventory().setChestplate(armor(Material.NETHERITE_CHESTPLATE));
          player.getInventory().setLeggings(armor(Material.NETHERITE_LEGGINGS));
          player.getInventory().setBoots(armor(Material.NETHERITE_BOOTS));
          player.getInventory().setItem(0, armor(Material.NETHERITE_SWORD));
          player.getInventory().setHeldItemSlot(0);
          player.setHealth(20);
          player.setFoodLevel(20);
          player.teleport(new Location(world, .5, 101, .5));
          sender.sendMessage("EVENT LIVE SETUP PASS");
        }
        case "config" -> {
          World selected = world(args[1].equals("PIRATE_SHIP"));
          FileSettings.save(this, selected.getName());
          player.teleport(new Location(world(false), .5, 101, .5));
          player.setHealth(20);
          Bukkit.dispatchCommand(player, "eventadmin reload");
        }
        case "state" ->
            reader
                .active()
                .whenComplete(
                    (e, error) ->
                        main(
                            () -> {
                              if (error != null) {
                                sender.sendMessage("EVENT STATE FAIL " + error);
                                return;
                              }
                              if (e == null) {
                                sender.sendMessage("EVENT STATE NONE");
                                return;
                              }
                              last = e;
                              sender.sendMessage(
                                  "EVENT STATE " + e.type() + " " + e.status() + " " + e.stage());
                            }));
        case "visit" ->
            reader
                .active()
                .whenComplete(
                    (e, error) -> {
                      if (e == null) {
                        main(() -> sender.sendMessage("EVENT VISIT NONE"));
                        return;
                      }
                      last = e;
                      if (args.length == 1
                          && (e.type() == EventRules.Type.BOSS
                              || e.type() == EventRules.Type.DUNGEON)) {
                        main(
                            () -> {
                              World world = Bukkit.getWorld(e.world());
                              player.teleport(
                                  new Location(
                                      world,
                                      e.x() + 2.5,
                                      e.y() + (e.type() == EventRules.Type.DUNGEON ? 1 : 0),
                                      e.z() + .5));
                              sender.sendMessage("EVENT COMBAT READY");
                            });
                      } else
                        reader
                            .chests(e.id())
                            .whenComplete(
                                (chests, f) ->
                                    main(
                                        () -> {
                                          var chest =
                                              chests.stream()
                                                  .filter(c -> c.owner() == null)
                                                  .findFirst()
                                                  .orElse(null);
                                          if (chest == null) {
                                            sender.sendMessage("EVENT CHESTS EMPTY");
                                            return;
                                          }
                                          World world = Bukkit.getWorld(e.world());
                                          Location standing =
                                              e.type() == EventRules.Type.PIRATE_SHIP
                                                  ? new Location(
                                                      world,
                                                      e.x() + (chest.index() == 0 ? -3.5 : 3.5),
                                                      e.y() + 1,
                                                      e.z() + .5)
                                                  : new Location(
                                                      world,
                                                      chest.x() + .5,
                                                      chest.y(),
                                                      chest.z() - 2.5);
                                          standing.setDirection(
                                              new Location(
                                                      world,
                                                      chest.x() + .5,
                                                      chest.y() + .5,
                                                      chest.z() + .5)
                                                  .toVector()
                                                  .subtract(
                                                      standing
                                                          .toVector()
                                                          .add(
                                                              new org.bukkit.util.Vector(
                                                                  0, 1.62, 0))));
                                          player.teleport(standing);
                                          sender.sendMessage(
                                              "EVENT CHEST READY "
                                                  + chest.x()
                                                  + " "
                                                  + chest.y()
                                                  + " "
                                                  + chest.z());
                                        }));
                    });
        case "health" ->
            reader
                .active()
                .whenComplete(
                    (e, error) ->
                        main(
                            () -> sender.sendMessage("EVENT HP " + (e == null ? -1 : e.health()))));
        case "audit" -> {
          if (last == null) throw new AssertionError("No event recorded");
          EventStore.State e = last;
          db.submit(
                  c -> {
                    try (var p = c.prepareStatement("SELECT status FROM world_events WHERE id=?")) {
                      p.setString(1, e.id().toString());
                      try (var rs = p.executeQuery()) {
                        if (!rs.next() || !rs.getString(1).equals("FINISHED"))
                          throw new AssertionError("Event not finished");
                      }
                    }
                    List<Object[]> rows = new ArrayList<>();
                    try (var p =
                        c.prepareStatement(
                            "SELECT a.id,p.id,p.payload,p.state FROM world_event_awards a LEFT JOIN"
                                + " loot_parcels p ON p.source=a.id WHERE a.event=? AND"
                                + " a.kind!='CASH'")) {
                      p.setString(1, e.id().toString());
                      try (var rs = p.executeQuery()) {
                        while (rs.next()) {
                          if (!"DELIVERED".equals(rs.getString(4)))
                            throw new AssertionError("Event parcel not delivered");
                          rows.add(new Object[] {rs.getString(2), rs.getBytes(3)});
                        }
                      }
                    }
                    int expected = e.type() == EventRules.Type.METEOR ? 1 : 2;
                    if (rows.size() != expected)
                      throw new AssertionError("Unexpected award count: " + rows.size());
                    return rows;
                  })
              .whenComplete(
                  (rows, error) ->
                      main(
                          () -> {
                            if (error != null) {
                              sender.sendMessage("EVENT AUDIT FAIL " + error);
                              return;
                            }
                            for (var row : rows) {
                              int copies = 0;
                              var expected = ItemStack.deserializeBytes((byte[]) row[1]);
                              for (var item : player.getInventory().getContents())
                                if (item != null
                                    && item.hasItemMeta()
                                    && row[0].equals(
                                        item.getItemMeta()
                                            .getPersistentDataContainer()
                                            .get(
                                                new NamespacedKey("poldercore", "loot_delivery"),
                                                PersistentDataType.STRING))) {
                                  if (!expected.equals(item))
                                    throw new AssertionError("Reward payload changed");
                                  copies++;
                                }
                              if (copies != 1)
                                throw new AssertionError("Reward duplicated/missing");
                            }
                            reader
                                .blocks(e.id())
                                .whenComplete(
                                    (blocks, f) ->
                                        main(
                                            () -> {
                                              World world = Bukkit.getWorld(e.world());
                                              for (var block : blocks)
                                                if (!world
                                                    .getBlockAt(block.x(), block.y(), block.z())
                                                    .getBlockData()
                                                    .getAsString()
                                                    .equals(block.original()))
                                                  throw new AssertionError("Manifest not restored");
                                              sender.sendMessage("EVENT AUDIT PASS " + e.type());
                                            }));
                          }));
        }
        default -> sender.sendMessage("Unknown probe command");
      }
      return true;
    } catch (Throwable e) {
      sender.sendMessage("EVENT PROBE FAILED " + e);
      return true;
    }
  }

  private static final class FileSettings {
    static void save(JavaPlugin probe, String world) throws Exception {
      var file =
          new java.io.File(
              Bukkit.getPluginManager().getPlugin("PolderCore").getDataFolder(), "events.yml");
      var y = YamlConfiguration.loadConfiguration(file);
      y.set("worlds", List.of(world));
      y.set("spawn-min-radius", 256);
      y.set("spawn-max-radius", 512);
      y.set("search-attempts", 20);
      y.set("cleanup-delay-seconds", 1);
      y.set("minimum-damage", 1);
      for (var type : EventRules.Type.values()) {
        String p = "types." + type + ".";
        y.set(p + "warning-seconds", 1);
        y.set(p + "expiration-seconds", 180);
        if (type == EventRules.Type.METEOR || type == EventRules.Type.SUPPLY_DROP)
          y.set(p + "fall-seconds", 1);
        if (type == EventRules.Type.BOSS || type == EventRules.Type.DUNGEON) {
          y.set(p + "health", 10);
          y.set(p + "special-damage", 0);
          y.set(p + "special-interval-seconds", 3);
        }
        if (type == EventRules.Type.DUNGEON) {
          y.set(p + "waves", 2);
          y.set(p + "mobs-per-wave", 2);
        }
      }
      y.save(file);
    }
  }
}
