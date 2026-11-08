package nl.poldersmp.core.loot;

import java.io.*;
import java.util.*;
import nl.poldersmp.core.gameplay.Weapons;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record LootConfig(
    YamlConfiguration crates,
    YamlConfiguration vaults,
    Map<LootRules.Tier, List<LootRules.Weighted<Reward>>> crateRewards,
    Map<LootRules.Tier, List<LootRules.Weighted<Reward>>> vaultRewards) {
  public record Reward(
      String id,
      String kind,
      String value,
      int amount,
      boolean announce,
      Map<String, Integer> enchants) {}

  public static LootConfig load(JavaPlugin plugin) {
    var c = read(plugin, "crates.yml");
    var v = read(plugin, "vaults.yml");
    validate(c, v);
    return new LootConfig(c, v, tables(c, "types"), tables(v, "rarities"));
  }

  private static YamlConfiguration read(JavaPlugin plugin, String name) {
    File file = new File(plugin.getDataFolder(), name);
    if (!file.exists()) plugin.saveResource(name, false);
    try {
      var y = new YamlConfiguration();
      y.load(file);
      return y;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid " + name, e);
    }
  }

  public static void validate(YamlConfiguration c, YamlConfiguration v) {
    for (var y : List.of(c, v)) {
      if (!y.isBoolean("enabled")) throw new IllegalArgumentException("enabled must be boolean");
      for (var e : y.getValues(true).entrySet())
        if (e.getValue() instanceof Number n
            && (!Double.isFinite(n.doubleValue()) || n.doubleValue() < 0))
          throw new IllegalArgumentException("Invalid setting: " + e.getKey());
    }
    if (c.getInt("animation-ticks") < 1 || c.getInt("animation-ticks") > 1200)
      throw new IllegalArgumentException("Invalid animation length");
    if (v.getLong("spawn-interval-seconds") < 60
        || v.getLong("spawn-interval-seconds") > 31536000
        || v.getLong("expiration-seconds") < 60
        || v.getLong("expiration-seconds") > 86400
        || v.getLong("claim-cooldown-seconds") > 31536000
        || v.getInt("attempts") < 1
        || v.getInt("attempts") > 20
        || v.getInt("spawn-min-radius") < 128
        || v.getInt("spawn-max-radius") < v.getInt("spawn-min-radius")
        || v.getInt("spawn-max-radius") > 100000
        || v.getInt("maximum-active") > 20)
      throw new IllegalArgumentException("Unsafe vault limits");
    for (String key : List.of("bounty", "king"))
      LootRules.Tier.valueOf(c.getString("bonuses." + key));
    for (var tier : LootRules.Tier.values())
      if (v.getLong("rarity-weights." + tier) <= 0)
        throw new IllegalArgumentException("Invalid rarity weight");
  }

  public static Map<LootRules.Tier, List<LootRules.Weighted<Reward>>> tables(
      YamlConfiguration y, String root) {
    Map<LootRules.Tier, List<LootRules.Weighted<Reward>>> tables =
        new EnumMap<>(LootRules.Tier.class);
    for (var tier : LootRules.Tier.values()) {
      List<LootRules.Weighted<Reward>> rewards = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      for (var row : y.getMapList(root + "." + tier + ".rewards")) {
        String id = Objects.toString(row.get("id"), ""),
            kind = Objects.toString(row.get("kind"), ""),
            value = Objects.toString(row.get("value"), "");
        int amount = ((Number) row.get("amount")).intValue();
        long weight = ((Number) row.get("weight")).longValue();
        if (((Number) row.get("weight")).doubleValue() != weight
            || ((Number) row.get("amount")).doubleValue() != amount)
          throw new IllegalArgumentException("Amounts and weights must be whole numbers");
        if (id.isBlank() || !ids.add(id) || amount < 1)
          throw new IllegalArgumentException("Invalid reward identity or amount");
        if (kind.equals("WEAPON")) {
          Weapons.Kind.valueOf(value);
          if (amount != 1) throw new IllegalArgumentException("Weapon stacks forbidden");
        } else if (kind.equals("TRIM")) {
          if (amount != 1 || !value.matches("[a-z_]+"))
            throw new IllegalArgumentException("Invalid trim reward");
        } else if (kind.equals("ITEM") || kind.equals("COSMETIC")) {
          Material material = Material.matchMaterial(value);
          if (material == null
              || (material == Material.AIR
                  || material == Material.CAVE_AIR
                  || material == Material.VOID_AIR)
              || amount > 64) throw new IllegalArgumentException("Invalid material/stack " + value);
        } else throw new IllegalArgumentException("Unknown reward kind");
        Map<String, Integer> enchants = new HashMap<>();
        if (row.get("enchants") instanceof Map<?, ?> map)
          for (var entry : map.entrySet())
            enchants.put(entry.getKey().toString(), ((Number) entry.getValue()).intValue());
        rewards.add(
            new LootRules.Weighted<>(
                new Reward(
                    id,
                    kind,
                    value,
                    amount,
                    Boolean.TRUE.equals(row.get("announce")),
                    Map.copyOf(enchants)),
                weight));
      }
      LootRules.total(rewards);
      if (rewards.size() > 45)
        throw new IllegalArgumentException("At most 45 preview rewards per tier");
      tables.put(tier, List.copyOf(rewards));
    }
    return Map.copyOf(tables);
  }
}
