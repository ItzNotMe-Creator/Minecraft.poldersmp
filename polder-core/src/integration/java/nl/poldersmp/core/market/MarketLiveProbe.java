package nl.poldersmp.core.market;

import java.nio.file.*;
import java.util.*;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.teams.Teams;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated localhost-only fixture. Never install on a real player server. */
public final class MarketLiveProbe extends JavaPlugin {
  private Database db;

  public void onEnable() {
    if (!Files.isRegularFile(
        Bukkit.getWorldContainer().toPath().resolve(".polder-disposable-test")))
      throw new IllegalStateException("Disposable marker required");
    db =
        new Database(
            Bukkit.getPluginManager()
                .getPlugin("PolderCore")
                .getDataFolder()
                .toPath()
                .resolve("polder.db"));
  }

  public void onDisable() {
    db.close();
  }

  void main(Runnable r) {
    Bukkit.getScheduler().runTask(this, r);
  }

  Player alice() {
    return Objects.requireNonNull(Bukkit.getPlayerExact("MarketAlice"));
  }

  Player bob() {
    return Objects.requireNonNull(Bukkit.getPlayerExact("MarketBob"));
  }

  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    try {
      if (!Bukkit.getIp().equals("127.0.0.1")) throw new IllegalStateException("Loopback only");
      switch (args[0]) {
        case "setup" -> {
          World w = Bukkit.getWorld("world");
          w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
          w.setTime(1000);
          for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
              w.getBlockAt(x, 179, z).setType(Material.STONE, false);
              w.getBlockAt(x, 180, z).setType(Material.AIR, false);
              w.getBlockAt(x, 181, z).setType(Material.AIR, false);
            }
          for (Player p : List.of(alice(), bob())) {
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.teleport(new Location(w, p.equals(alice()) ? .5 : 2.5, 180, .5));
          }
          sender.sendMessage("MARKET LIVE SETUP PASS");
        }
        case "stack" -> {
          Player p = (Player) sender;
          int slot = p.getInventory().firstEmpty();
          if (slot < 0 || slot > 8) throw new AssertionError("No empty hotbar slot");
          p.getInventory().setHeldItemSlot(slot);
          p.getInventory()
              .setItem(slot, new ItemStack(Material.valueOf(args[1]), Integer.parseInt(args[2])));
          p.saveData();
          sender.sendMessage("MARKET STACK READY");
        }
        case "friendly" -> {
          Player a = alice(), b = bob();
          if (!Bukkit.getServicesManager()
              .load(Teams.class)
              .friendly(a.getUniqueId(), b.getUniqueId()))
            throw new AssertionError("Membership not recognized");
          double hp = b.getHealth();
          b.setNoDamageTicks(0);
          b.damage(2, a);
          if (b.getHealth() != hp) throw new AssertionError("Friendly fire applied");
          sender.sendMessage("TEAM FRIENDLY FIRE PASS");
        }
        case "guards" -> {
          Player p = (Player) sender;
          p.closeInventory();
          ItemStack before = p.getInventory().getItemInMainHand().clone();
          var crafting = (CraftingInventory) p.getOpenInventory().getTopInventory();
          ItemStack[] matrix = crafting.getMatrix().clone();
          ItemStack result = crafting.getResult();
          var stand =
              (org.bukkit.entity.ArmorStand)
                  p.getWorld()
                      .spawnEntity(
                          p.getLocation().add(1, 0, 0), org.bukkit.entity.EntityType.ARMOR_STAND);
          try {
            ItemStack tagged = new ItemStack(Material.APPLE);
            var meta = tagged.getItemMeta();
            meta.getPersistentDataContainer()
                .set(
                    new NamespacedKey("poldercore", "trade_escrow"),
                    PersistentDataType.STRING,
                    UUID.randomUUID().toString());
            tagged.setItemMeta(meta);
            p.getInventory().setItemInMainHand(tagged);
            var use =
                new org.bukkit.event.player.PlayerInteractEntityEvent(p, stand, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(use);
            if (!use.isCancelled()) throw new AssertionError("Escrow entity use allowed");
            var consume =
                new org.bukkit.event.player.PlayerItemConsumeEvent(p, tagged, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(consume);
            if (!consume.isCancelled()) throw new AssertionError("Escrow consumption allowed");
            var equip =
                new org.bukkit.event.player.PlayerArmorStandManipulateEvent(
                    p,
                    stand,
                    tagged,
                    new ItemStack(Material.AIR),
                    EquipmentSlot.HAND,
                    EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(equip);
            if (!equip.isCancelled()) throw new AssertionError("Escrow armor stand use allowed");
            ItemStack ingredient = new ItemStack(Material.OAK_LOG);
            var data = ingredient.getItemMeta();
            data.getPersistentDataContainer()
                .set(
                    new NamespacedKey("poldercore", "trade_escrow"),
                    PersistentDataType.STRING,
                    UUID.randomUUID().toString());
            ingredient.setItemMeta(data);
            ItemStack[] grid = new ItemStack[matrix.length];
            grid[0] = ingredient;
            crafting.setMatrix(grid);
            crafting.setResult(new ItemStack(Material.OAK_PLANKS, 4));
            Bukkit.getPluginManager()
                .callEvent(
                    new org.bukkit.event.inventory.PrepareItemCraftEvent(
                        crafting, p.getOpenInventory(), false));
            if (crafting.getResult() != null && !crafting.getResult().getType().isAir())
              throw new AssertionError("Escrow ingredient produced a result");
            sender.sendMessage("MARKET ESCROW PAPER EVENT GUARDS PASS");
          } finally {
            crafting.setMatrix(matrix);
            crafting.setResult(result);
            p.getInventory().setItemInMainHand(before);
            stand.remove();
            p.saveData();
          }
        }
        case "uncertain" -> {
          var adapter = Objects.requireNonNull(VaultAdapter.detect());
          UUID actor = alice().getUniqueId(), id = UUID.randomUUID();
          var store = new MoneyStore(db);
          var op =
              new MoneyStore.Operation(
                  id,
                  actor,
                  "VAULT_PROBE",
                  adapter.id(),
                  "READY",
                  System.currentTimeMillis(),
                  "Injected lost acknowledgement fixture",
                  null,
                  null);
          store
              .plan(op, List.of(new MoneyStore.Leg(actor, -7)))
              .thenCompose(v -> store.steps(id))
              .thenCompose(
                  steps -> {
                    var step = steps.getFirst();
                    return store
                        .applying(step)
                        .thenApply(
                            ok -> {
                              if (!ok)
                                throw new IllegalStateException(
                                    "Fixture lost application reservation");
                              return step;
                            });
                  })
              .whenComplete(
                  (step, error) ->
                      main(
                          () -> {
                            try {
                              if (error != null) throw new IllegalStateException(error);
                              if (!adapter.apply(actor, -7))
                                throw new AssertionError("Provider refused fixture debit");
                              sender.sendMessage("VAULT UNCERTAIN EFFECT APPLIED " + id);
                            } catch (Exception e) {
                              sender.sendMessage("VAULT FIXTURE FAIL " + e);
                            }
                          }));
        }
        case "audit" -> {
          List<UUID> actors = List.of(alice().getUniqueId(), bob().getUniqueId());
          db.submit(
                  c -> {
                    for (UUID actor : actors) {
                      long balance =
                          Sql.number(c, "SELECT balance FROM accounts WHERE uuid=?", actor);
                      long ledger =
                          Sql.number(
                              c,
                              "SELECT COALESCE(SUM(l.delta),0) FROM money_legs l JOIN"
                                  + " money_operations o ON o.id=l.operation WHERE l.player=? AND"
                                  + " o.state='DONE' AND o.provider='sqlite'",
                              actor);
                      if (balance != ledger) throw new AssertionError("Ledger mismatch");
                    }
                    if (Sql.number(c, "SELECT COUNT(*) FROM market_listings WHERE state='SOLD'")
                        != 1) throw new AssertionError("Auction not sold once");
                    var parcels =
                        Sql.rows(
                            c,
                            "SELECT p.id,p.owner,p.payload,p.state FROM loot_parcels p JOIN"
                                + " market_orders o ON p.source='market:'||o.operation WHERE"
                                + " o.kind='AUCTION'");
                    if (parcels.size() != 1 || !parcels.getFirst().get(3).equals("DELIVERED"))
                      throw new AssertionError("Auction parcel not delivered once");
                    if (Sql.number(c, "SELECT COUNT(*) FROM money_operations WHERE state='UNKNOWN'")
                        != 0) throw new AssertionError("Unexpected uncertain operation");
                    return parcels;
                  })
              .whenComplete(
                  (rows, error) ->
                      main(
                          () -> {
                            if (error != null) {
                              sender.sendMessage("MARKET AUDIT FAIL " + error);
                              return;
                            }
                            var row = rows.getFirst();
                            UUID owner = UUID.fromString((String) row.get(1));
                            Player p = Bukkit.getPlayer(owner);
                            ItemStack expected = ItemStack.deserializeBytes((byte[]) row.get(2));
                            int copies = 0;
                            for (ItemStack item : p.getInventory().getContents())
                              if (item != null
                                  && item.hasItemMeta()
                                  && row.get(0)
                                      .equals(
                                          item.getItemMeta()
                                              .getPersistentDataContainer()
                                              .get(
                                                  new NamespacedKey("poldercore", "loot_delivery"),
                                                  PersistentDataType.STRING))) {
                                if (!expected.equals(item))
                                  throw new AssertionError("Auction payload changed");
                                copies++;
                              }
                            if (copies != 1)
                              throw new AssertionError("Physical receipt count " + copies);
                            sender.sendMessage("MARKET AUDIT PASS");
                          }));
        }
        case "expiry" ->
            db.submit(
                    c -> {
                      if (Sql.number(
                                  c, "SELECT COUNT(*) FROM market_listings WHERE state='EXPIRED'")
                              != 1
                          || Sql.number(c, "SELECT COUNT(*) FROM market_returns WHERE state='DONE'")
                              != 1) throw new AssertionError("Expiration/return not completed");
                      return Sql.rows(
                          c,
                          "SELECT id,payload,state FROM loot_parcels WHERE source LIKE"
                              + " 'auction:%'");
                    })
                .whenComplete(
                    (rows, error) ->
                        main(
                            () -> {
                              if (error != null) {
                                sender.sendMessage("MARKET EXPIRY FAIL " + error);
                                return;
                              }
                              if (rows.size() != 1 || !rows.getFirst().get(2).equals("DELIVERED"))
                                throw new AssertionError("Return not delivered");
                              var row = rows.getFirst();
                              int copies = 0;
                              for (ItemStack i : alice().getInventory().getContents())
                                if (i != null
                                    && i.hasItemMeta()
                                    && row.get(0)
                                        .equals(
                                            i.getItemMeta()
                                                .getPersistentDataContainer()
                                                .get(
                                                    new NamespacedKey(
                                                        "poldercore", "loot_delivery"),
                                                    PersistentDataType.STRING))) copies++;
                              if (copies != 1) throw new AssertionError("Return copies");
                              sender.sendMessage("MARKET EXPIRY PASS");
                            }));
        default -> sender.sendMessage("setup|stack|friendly|audit|expiry");
      }
    } catch (Throwable e) {
      sender.sendMessage("MARKET PROBE FAIL " + e);
      e.printStackTrace();
    }
    return true;
  }
}
