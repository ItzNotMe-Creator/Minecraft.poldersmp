package nl.poldersmp.core.loot;

import java.nio.file.*;
import java.util.*;
import nl.poldersmp.common.Database;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Opt-in isolated offline-client probe. Never install this fixture on a real server. */
public final class LootLiveProbe extends JavaPlugin {
  private Database database;
  private ItemStack keyCopy;
  private String keyCopyId;
  private final String run = UUID.randomUUID().toString();
  private ItemStack[] snapshot;
  private UUID capacityParcel;

  private Loot loot() {
    return Objects.requireNonNull(
        Bukkit.getServicesManager().load(Loot.class), "Loot service unavailable");
  }

  private Player player() {
    return Objects.requireNonNull(Bukkit.getPlayerExact("LootProbe"), "Connect test client first");
  }

  private void main(Runnable r) {
    Bukkit.getScheduler().runTask(this, r);
  }

  private World world() {
    NamespacedKey marker = new NamespacedKey("lootliveprobe", "isolated_world");
    World w = Bukkit.getWorld("loot_probe_flat");
    if (w == null) {
      boolean existed=Files.exists(Bukkit.getWorldContainer().toPath().resolve("loot_probe_flat"));
      String settings =
          "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\",\"structure_overrides\":[],\"lakes\":false,\"features\":false}";
      w =
          new WorldCreator("loot_probe_flat")
              .type(WorldType.FLAT)
              .generatorSettings(settings)
              .generateStructures(false)
              .createWorld();
      if(!existed)Objects.requireNonNull(w).getPersistentDataContainer().set(marker,PersistentDataType.BYTE,(byte)1);
      w.save();
    }
    if (!w.getPersistentDataContainer().has(marker, PersistentDataType.BYTE))
      throw new IllegalStateException("Refusing to modify an unmarked world");
    return w;
  }

  @Override
  public void onEnable() {
    Path core =
        Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("PolderCore"))
            .getDataFolder()
            .toPath();
    database = new Database(core.resolve("polder.db"));
  }

  @Override
  public void onDisable() {
    if (database != null) database.close();
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      Player p = player();
      switch (args[0]) {
        case "setup" -> {
          for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
            var stack = p.getInventory().getItem(slot);
            if (stack != null
                && stack.getType() == Material.TRIPWIRE_HOOK
                && stack.hasItemMeta()
                && stack
                    .getItemMeta()
                    .getPersistentDataContainer()
                    .has(
                        new NamespacedKey("poldercore", "crate_key_id"), PersistentDataType.STRING))
              p.getInventory().setItem(slot, null);
          }
          World w = world();
          w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
          for (int x = -3; x <= 18; x++)
            for (int z = -3; z <= 6; z++) {
              w.getBlockAt(x, 100, z).setType(Material.STONE, false);
              for (int y = 101; y <= 104; y++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
          for (int i = 0; i < 5; i++)
            w.getBlockAt(2 + i * 3, 101, 2).setType(Material.BARREL, false);
          p.setGameMode(GameMode.SURVIVAL);
          p.teleport(new Location(w, 2.5, 101, -.5));
          sender.sendMessage("LIVE SETUP PASS");
        }
        case "select" -> {
          LootRules.Tier tier = LootRules.Tier.valueOf(args[1]);
          double x = 2 + tier.ordinal() * 3 + .5;
          Location pos = new Location(world(), x, 101, -.5);
          pos.setDirection(new org.bukkit.util.Vector(0, -1.12, 3));
          p.teleport(pos);
          Bukkit.getScheduler()
              .runTaskLater(this, () -> Bukkit.dispatchCommand(p, "crateadmin set " + tier), 5);
        }
        case "key" -> {
          LootRules.Tier tier = LootRules.Tier.valueOf(args[1]);
          loot()
              .grantKey(p.getUniqueId(), tier, "probe:" + run + ":" + tier)
              .whenComplete(
                  (created, error) ->
                      main(
                          () ->
                              sender.sendMessage(
                                  error == null && created
                                      ? "LIVE KEY QUEUED"
                                      : "LIVE KEY FAILED " + error)));
        }
        case "copy" -> {
          NamespacedKey id = new NamespacedKey("poldercore", "crate_key_id");
          keyCopy =
              Arrays.stream(p.getInventory().getContents())
                  .filter(Objects::nonNull)
                  .filter(
                      i ->
                          i.hasItemMeta()
                              && i.getItemMeta()
                                  .getPersistentDataContainer()
                                  .has(id, PersistentDataType.STRING))
                  .findFirst()
                  .orElseThrow()
                  .clone();
          keyCopyId =
              keyCopy.getItemMeta().getPersistentDataContainer().get(id, PersistentDataType.STRING);
          sender.sendMessage("LIVE KEY SNAPSHOT PASS");
        }
        case "replay" -> {
          if (keyCopy == null) throw new AssertionError("Missing key snapshot");
          p.getInventory().addItem(keyCopy.clone());
          sender.sendMessage("LIVE REPLAY INSERTED");
        }
        case "clearreplay" -> {
          for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (item != null
                && item.hasItemMeta()
                && Objects.equals(
                    keyCopyId,
                    item.getItemMeta()
                        .getPersistentDataContainer()
                        .get(
                            new NamespacedKey("poldercore", "crate_key_id"),
                            PersistentDataType.STRING))) p.getInventory().setItem(i, null);
          }
          sender.sendMessage("LIVE SPENT COPY CLEARED");
        }
        case "audit" -> {
          String source = "crate:" + keyCopyId;
          database
              .submit(
                  c -> {
                    try (var query =
                        c.prepareStatement(
                            "SELECT p.id,p.state,p.payload,(SELECT COUNT(*) FROM loot_openings o"
                                + " WHERE o.id=p.id) FROM loot_parcels p WHERE source=?")) {
                      query.setString(1, source);
                      try (var rs = query.executeQuery()) {
                        if (!rs.next() || !rs.getString(2).equals("DELIVERED") || rs.getInt(4) != 1)
                          throw new AssertionError("Parcel not delivered exactly once");
                        return new Object[] {rs.getString(1), rs.getBytes(3)};
                      }
                    }
                  })
              .whenComplete(
                  (data, error) ->
                      main(
                          () -> {
                            if (error != null) {
                              sender.sendMessage("LIVE AUDIT FAIL " + error);
                              return;
                            }
                            int count = 0;
                            ItemStack expected = ItemStack.deserializeBytes((byte[]) data[1]);
                            for (ItemStack item : p.getInventory().getContents())
                              if (item != null
                                  && item.hasItemMeta()
                                  && data[0].equals(
                                      item.getItemMeta()
                                          .getPersistentDataContainer()
                                          .get(
                                              new NamespacedKey("poldercore", "loot_delivery"),
                                              PersistentDataType.STRING))) {
                                if (!expected.equals(item))
                                  throw new AssertionError("Delivered payload changed");
                                count++;
                              }
                            if (count != 1)
                              throw new AssertionError("Expected exactly one physical reward");
                            sender.sendMessage("LIVE CRATE AUDIT PASS");
                          }));
        }
        case "vault" -> {
          var cfg =
              YamlConfiguration.loadConfiguration(
                  new java.io.File(
                      Bukkit.getPluginManager().getPlugin("PolderCore").getDataFolder(),
                      "vaults.yml"));
          cfg.set("worlds", List.of("loot_probe_flat"));
          cfg.set("spawn-min-radius", 128);
          cfg.set("spawn-max-radius", 512);
          cfg.set("claim-cooldown-seconds", 0);
          cfg.save(
              new java.io.File(
                  Bukkit.getPluginManager().getPlugin("PolderCore").getDataFolder(), "vaults.yml"));
          Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "crateadmin reload");
          p.teleport(new Location(world(), 2.5, 101, -.5));
          Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vaultadmin spawn " + args[1]);
        }
        case "visit" ->
            database
                .submit(
                    c -> {
                      try (var s = c.createStatement();
                          var rs =
                              s.executeQuery(
                                  "SELECT id,x,y,z FROM loot_vaults WHERE state='ACTIVE' ORDER BY"
                                      + " expires DESC LIMIT 1")) {
                        if (!rs.next()) return null;
                        return new Object[] {
                          rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)
                        };
                      }
                    })
                .whenComplete(
                    (row, error) ->
                        main(
                            () -> {
                              if (error != null || row == null) {
                                sender.sendMessage("LIVE VAULT NOT READY");
                                return;
                              }
                              Location pos =
                                  new Location(
                                      world(), (int) row[1] + .5, (int) row[2], (int) row[3] - 2.5);
                              pos.setDirection(new org.bukkit.util.Vector(0, -1.12, 3));
                              p.teleport(pos);
                              sender.sendMessage(
                                  "LIVE VAULT READY " + row[1] + " " + row[2] + " " + row[3]);
                            }));
        case "vaultaudit" ->
            database
                .submit(
                    c -> {
                      try (var s = c.createStatement();
                          var rs =
                              s.executeQuery(
                                  "SELECT id,world FROM loot_vaults WHERE state='REMOVED' AND"
                                      + " claimed_by IS NOT NULL ORDER BY claimed_at DESC LIMIT"
                                      + " 1")) {
                        if (!rs.next()) throw new AssertionError("Claimed vault not cleaned");
                        return rs.getString(1);
                      }
                    })
                .whenComplete(
                    (id, error) -> {
                      if (error != null) {
                        main(() -> sender.sendMessage("LIVE VAULT AUDIT FAIL " + error));
                        return;
                      }
                      new LootStore(database)
                          .blocks(UUID.fromString(id))
                          .whenComplete(
                              (blocks, e) ->
                                  main(
                                      () -> {
                                        for (var block : blocks)
                                          if (!world()
                                              .getBlockAt(block.x(), block.y(), block.z())
                                              .getBlockData()
                                              .getAsString()
                                              .equals(block.original()))
                                            throw new AssertionError("Vault block not restored");
                                        sender.sendMessage("LIVE VAULT AUDIT PASS");
                                      }));
                    });
        case "fill" -> {
          snapshot = p.getInventory().getStorageContents().clone();
          ItemStack[] full = new ItemStack[36];
          Arrays.setAll(full, i -> new ItemStack(Material.COBBLESTONE, 64));
          p.getInventory().setStorageContents(full);
          loot()
              .grantKey(p.getUniqueId(), LootRules.Tier.COMMON, "capacity:" + run)
              .whenComplete((v, e) -> main(() -> sender.sendMessage("LIVE CAPACITY QUEUED")));
        }
        case "capacity" ->
            database
                .submit(
                    c -> {
                      try (var q =
                          c.prepareStatement("SELECT id,state FROM loot_parcels WHERE source=?")) {
                        q.setString(1, "capacity:" + run);
                        try (var rs = q.executeQuery()) {
                          if (!rs.next()) throw new AssertionError("Missing parcel");
                          capacityParcel = UUID.fromString(rs.getString(1));
                          return rs.getString(2);
                        }
                      }
                    })
                .whenComplete(
                    (state, e) -> main(() -> sender.sendMessage("LIVE CAPACITY STATE " + state)));
        case "free" -> {
          p.getInventory().setItem(0, null);
          sender.sendMessage("LIVE CAPACITY SLOT FREED");
        }
        case "restore" -> {
          ItemStack delivered = p.getInventory().getItem(0);
          p.getInventory().setStorageContents(snapshot);
          if (delivered != null) p.getInventory().addItem(delivered);
          sender.sendMessage("LIVE INVENTORY RESTORED");
        }
        default -> sender.sendMessage("Unknown probe subcommand");
      }
    } catch (Throwable e) {
      sender.sendMessage("LIVE PROBE FAILED " + e);
      getLogger().severe(e.toString());
    }
    return true;
  }
}
