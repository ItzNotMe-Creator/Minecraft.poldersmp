package nl.poldersmp.core.loot;

import java.util.*;
import net.kyori.adventure.text.Component;
import nl.poldersmp.core.gameplay.Weapons;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class RewardFactory {
  private final Weapons weapons;
  private final NamespacedKey collectible;

  public RewardFactory(JavaPlugin plugin, Weapons weapons) {
    this.weapons = weapons;
    collectible = new NamespacedKey(plugin, "collectible");
  }

  public ItemStack create(LootConfig.Reward reward) {
    ItemStack item;
    switch (reward.kind()) {
      case "WEAPON" -> item = weapons.create(Weapons.Kind.valueOf(reward.value()));
      case "TRIM" -> {
        item = new ItemStack(Material.NETHERITE_CHESTPLATE);
        var pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(reward.value()));
        if (pattern == null) throw new IllegalArgumentException("Unknown trim " + reward.value());
        ArmorMeta meta = (ArmorMeta) item.getItemMeta();
        meta.setTrim(new ArmorTrim(TrimMaterial.GOLD, pattern));
        meta.displayName(Component.text("✦ " + reward.id().replace('-', ' ')));
        item.setItemMeta(meta);
      }
      case "ITEM", "COSMETIC" -> {
        item =
            new ItemStack(
                Objects.requireNonNull(Material.matchMaterial(reward.value())), reward.amount());
        if (reward.kind().equals("COSMETIC")) {
          var meta = item.getItemMeta();
          meta.displayName(Component.text("✦ " + reward.id().replace('-', ' ')));
          meta.lore(List.of(Component.text("PolderSMP collectible • " + reward.id())));
          meta.getPersistentDataContainer()
              .set(collectible, PersistentDataType.STRING, reward.id());
          item.setItemMeta(meta);
        }
      }
      default -> throw new IllegalArgumentException("Unknown reward");
    }
    if (!item.getType().isItem() || item.getAmount() > item.getMaxStackSize())
      throw new IllegalArgumentException("Invalid registered item stack");
    var meta = item.getItemMeta();
    for (var e : reward.enchants().entrySet()) {
      var enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(e.getKey()));
      if (enchant == null
          || e.getValue() < 1
          || e.getValue() > enchant.getMaxLevel()
          || !enchant.canEnchantItem(item))
        throw new IllegalArgumentException("Invalid enchantment " + e.getKey());
      meta.addEnchant(enchant, e.getValue(), false);
    }
    item.setItemMeta(meta);
    return item;
  }
}
