package nl.poldersmp.core.market;

import java.util.*;
import nl.poldersmp.core.loot.LootConfig;
import org.bukkit.configuration.file.YamlConfiguration;

public record MarketConfig(YamlConfiguration yaml, Map<String, Entry> shop, List<Entry> pool) {
  public record Entry(
      String id,
      String kind,
      String value,
      int amount,
      long buy,
      long sell,
      int limit,
      String permission) {
    public LootConfig.Reward reward() {
      return new LootConfig.Reward(id, kind, value, amount, false, Map.of());
    }

    public String encode() {
      var y = new YamlConfiguration();
      y.set("id", id);
      y.set("kind", kind);
      y.set("value", value);
      y.set("amount", amount);
      y.set("buy", buy);
      y.set("sell-per-item", sell);
      y.set("limit", limit);
      y.set("permission", permission);
      return y.saveToString();
    }

    public static Entry decode(String text) throws Exception {
      var y = new YamlConfiguration();
      y.loadFromString(text);
      return entry(y, "");
    }
  }

  private static long integer(YamlConfiguration y, String path, long fallback) {
    Object value = y.get(path);
    if (value == null) return fallback;
    if (!(value instanceof Number))
      throw new IllegalArgumentException("Numeric YAML value required for " + path);
    try {
      return new java.math.BigDecimal(value.toString()).longValueExact();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Whole integer required for " + path, e);
    }
  }

  public static MarketConfig read(YamlConfiguration y) {
    if (!Set.of("auto", "sqlite", "vault", "disabled").contains(y.getString("mode", "")))
      throw new IllegalArgumentException("Invalid economy mode");
    for (String path :
        List.of(
            "maximum-transaction",
            "auction.listing-seconds",
            "auction.maximum-listings-per-player",
            "auction.maximum-listings",
            "black-market.refresh-seconds",
            "black-market.stock-size"))
      if (integer(y, path, 0) < 1 || integer(y, path, 0) > 1_000_000_000)
        throw new IllegalArgumentException("Invalid economy setting " + path);
    if (y.getInt("black-market.stock-size") > 45)
      throw new IllegalArgumentException("Black market max 45 slots");
    Map<String, Entry> shop = new LinkedHashMap<>();
    var section = y.getConfigurationSection("shop.items");
    if (section != null)
      for (String id : section.getKeys(false)) shop.put(id, entry(y, "shop.items." + id + "."));
    if (shop.size() > 45) throw new IllegalArgumentException("Shop max 45 entries");
    List<Entry> pool = new ArrayList<>();
    section = y.getConfigurationSection("black-market.pool");
    if (section != null)
      for (String id : section.getKeys(false)) pool.add(entry(y, "black-market.pool." + id + "."));
    if (y.getInt("black-market.stock-size") > pool.size())
      throw new IllegalArgumentException("Black stock exceeds pool");
    return new MarketConfig(y, Map.copyOf(shop), List.copyOf(pool));
  }

  private static Entry entry(YamlConfiguration y, String p) {
    String id =
        p.isEmpty()
            ? y.getString("id")
            : p.substring(0, p.length() - 1)
                .substring(p.substring(0, p.length() - 1).lastIndexOf('.') + 1);
    String kind = y.getString(p + "kind", "ITEM"), value = y.getString(p + "value", "");
    int amount = Math.toIntExact(integer(y, p + "amount", 1));
    long price = integer(y, p + "buy", 0), sell = integer(y, p + "sell-per-item", 0);
    if (id == null
        || !id.matches("[a-zA-Z0-9_-]{1,40}")
        || !Set.of("ITEM", "WEAPON", "TRIM", "COSMETIC").contains(kind)
        || value.isBlank()
        || amount < 1
        || amount > 64
        || price < 1
        || price > 1_000_000_000
        || sell < 0
        || sell > 1_000_000_000
        || integer(y, p + "limit", 1) < 1
        || integer(y, p + "limit", 1) > 1000)
      throw new IllegalArgumentException("Invalid catalog entry " + id);
    return new Entry(
        id,
        kind,
        value,
        amount,
        price,
        sell,
        (int) integer(y, p + "limit", 1),
        y.getString(p + "permission", "polder.blackmarket.use"));
  }
}
