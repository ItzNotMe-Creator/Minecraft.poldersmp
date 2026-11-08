package nl.poldersmp.core.verification;

import java.util.UUID;
import nl.poldersmp.core.gameplay.Armor;
import nl.poldersmp.core.gameplay.Weapons;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated verification plugin; never packaged in PolderCore. */
public final class GameplayProbe extends JavaPlugin {
  @Override
  public void onEnable() {
    int patterns = 0;
    for (TrimPattern pattern : Registry.TRIM_PATTERN) {
      ItemStack piece = new ItemStack(Material.NETHERITE_CHESTPLATE);
      ArmorMeta meta = (ArmorMeta) piece.getItemMeta();
      meta.setTrim(new ArmorTrim(TrimMaterial.GOLD, pattern));
      piece.setItemMeta(meta);
      var detected = Armor.detect(new ItemStack[] {piece, piece, piece, piece});
      if (detected.getOrDefault(pattern.getKey().getKey(), 0) != 4)
        throw new AssertionError("Trim detection: " + pattern);
      patterns++;
    }
    if (patterns < 18) throw new AssertionError("Expected at least 18 patterns");
    for (Weapons.Kind kind : Weapons.Kind.values()) {
      Material material =
          kind == Weapons.Kind.TITAN_HAMMER
              ? Material.NETHERITE_AXE
              : kind == Weapons.Kind.FROSTBITE_SWORD
                  ? Material.DIAMOND_SWORD
                  : Material.NETHERITE_SWORD;
      ItemStack item = new ItemStack(material);
      var meta = item.getItemMeta();
      NamespacedKey type = new NamespacedKey("poldercore", "legendary_weapon"),
          serial = new NamespacedKey("poldercore", "weapon_serial");
      meta.getPersistentDataContainer().set(type, PersistentDataType.STRING, kind.name());
      meta.getPersistentDataContainer()
          .set(serial, PersistentDataType.STRING, UUID.randomUUID().toString());
      item.setItemMeta(meta);
      if (Weapons.identify(item, type, serial) != kind)
        throw new AssertionError("PDC identification: " + kind);
      ItemStack roundtrip = ItemStack.deserializeBytes(item.serializeAsBytes());
      if (Weapons.identify(roundtrip, type, serial) != kind)
        throw new AssertionError("PDC round trip: " + kind);
    }
    getLogger()
        .info(
            "GAMEPLAY PROBE PASS: "
                + patterns
                + " trim patterns; 5 persistent weapon identities and serialization round trips");
  }
}
