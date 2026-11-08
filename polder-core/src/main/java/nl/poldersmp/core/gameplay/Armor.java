package nl.poldersmp.core.gameplay;

import java.io.File;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;

public final class Armor implements Listener, TabExecutor, AutoCloseable {
  private final JavaPlugin plugin;
  private final Discoveries store;
  private final Rules.Cooldowns cooldowns = new Rules.Cooldowns();
  private final Map<UUID, Map<String, Integer>> equipped = new HashMap<>();
  private final Map<UUID, Set<String>> observed = new HashMap<>();
  private final NamespacedKey speedKey, jumpKey, miningKey, sneakKey, waterKey;
  private YamlConfiguration config;
  private final Map<UUID, Set<PotionEffectType>> owned = new HashMap<>();
  private boolean changingPotion;
  private boolean ready;

  private double scale(int count) {
    return config.getDouble("scaling." + Math.clamp(count, 0, 4), Rules.scale(count));
  }

  private final org.bukkit.scheduler.BukkitTask task;

  public Armor(JavaPlugin plugin) {
    this.plugin = plugin;
    speedKey = new NamespacedKey(plugin, "trim_speed");
    jumpKey = new NamespacedKey(plugin, "trim_jump");
    miningKey = new NamespacedKey(plugin, "trim_mining");
    sneakKey = new NamespacedKey(plugin, "trim_sneak");
    waterKey = new NamespacedKey(plugin, "trim_water");
    reload();
    store = new Discoveries(plugin.getDataFolder().toPath().resolve("armor.db"));
    store
        .initialize()
        .whenComplete(
            (v, e) ->
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (e == null) ready = true;
                          else plugin.getLogger().severe("Armor discovery database failed: " + e);
                        }));
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    for (String command : List.of("armorinfo", "armorcollection", "armortop", "armoradmin")) {
      plugin.getCommand(command).setExecutor(this);
      plugin.getCommand(command).setTabCompleter(this);
    }
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 10);
  }

  public void reload() {
    File file = new File(plugin.getDataFolder(), "armor.yml");
    if (!file.exists()) plugin.saveResource("armor.yml", false);
    YamlConfiguration next = Configs.load(file);
    for (String key :
        List.of(
            "sentry-speed",
            "ward-resistance",
            "eye-dodge",
            "rib-resistance",
            "shaper-resistance",
            "dune-resistance")) {
      double n = next.getDouble("bonuses." + key);
      if (n < 0 || n > 1) throw new IllegalArgumentException("Invalid armor bonus: " + key);
    }
    config = next;
  }

  public static Map<String, Integer> detect(ItemStack[] armor) {
    List<String> patterns = new ArrayList<>();
    for (ItemStack item : armor)
      if (item != null && item.getItemMeta() instanceof ArmorMeta meta && meta.hasTrim())
        patterns.add(meta.getTrim().getPattern().getKey().getKey());
    return Rules.counts(patterns);
  }

  private Map<String, Integer> counts(Player p) {
    return config.getBoolean("enabled", true) && p.hasPermission("polder.armor.use")
        ? detect(p.getInventory().getArmorContents())
        : Map.of();
  }

  private double strength(Player p, String trim) {
    return scale(counts(p).getOrDefault(trim, 0));
  }

  private double bonus(String key, double def) {
    return config.getDouble("bonuses." + key, def);
  }

  private boolean set(Player p, String name, int pieces) {
    return config.getBoolean("sets." + name + ".enabled", true)
        && Rules.set(counts(p), config.getString("sets." + name + ".trim", ""), pieces);
  }

  private boolean cooldown(Player p, String key, double seconds) {
    return cooldowns.acquire(
        p.getUniqueId(), key, System.nanoTime() / 1000000, (long) (seconds * 1000));
  }

  private void attribute(Player p, Attribute attr, NamespacedKey key, double amount) {
    var instance = p.getAttribute(attr);
    if (instance == null) return;
    var old = instance.getModifiers().stream().filter(m -> m.getKey().equals(key)).findFirst();
    if (old.isPresent() && old.get().getAmount() == amount) return;
    old.ifPresent(instance::removeModifier);
    if (amount != 0)
      instance.addModifier(
          new AttributeModifier(
              key,
              amount,
              attr == Attribute.WATER_MOVEMENT_EFFICIENCY
                  ? AttributeModifier.Operation.ADD_NUMBER
                  : AttributeModifier.Operation.ADD_SCALAR));
  }

  // Short leases never overwrite an existing potion, even if the existing potion is weaker.
  private void effect(Player p, PotionEffectType type, int ticks, int amp) {
    if (!p.hasPotionEffect(type)) {
      changingPotion = true;
      try {
        if (p.addPotionEffect(new PotionEffect(type, ticks, amp, true, false, true)))
          owned.computeIfAbsent(p.getUniqueId(), id -> new HashSet<>()).add(type);
      } finally {
        changingPotion = false;
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void potionChanged(EntityPotionEffectEvent e) {
    if (!changingPotion && e.getEntity() instanceof Player p) {
      Set<PotionEffectType> effects = owned.get(p.getUniqueId());
      if (effects != null) effects.remove(e.getModifiedType());
    }
  }

  private void clearPotions(Player p) {
    Set<PotionEffectType> effects = owned.remove(p.getUniqueId());
    if (effects != null) for (PotionEffectType type : effects) p.removePotionEffect(type);
  }

  private void clear(Player p) {
    clearPotions(p);
    attribute(p, Attribute.MOVEMENT_SPEED, speedKey, 0);
    attribute(p, Attribute.JUMP_STRENGTH, jumpKey, 0);
    attribute(p, Attribute.BLOCK_BREAK_SPEED, miningKey, 0);
    attribute(p, Attribute.SNEAKING_SPEED, sneakKey, 0);
    attribute(p, Attribute.WATER_MOVEMENT_EFFICIENCY, waterKey, 0);
    equipped.remove(p.getUniqueId());
  }

  @EventHandler
  public void quit(org.bukkit.event.player.PlayerQuitEvent e) {
    clear(e.getPlayer());
    observed.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    clear(e.getEntity());
  }

  @EventHandler
  public void world(org.bukkit.event.player.PlayerChangedWorldEvent e) {
    clear(e.getPlayer());
  }

  @EventHandler
  public void equipped(com.destroystokyo.paper.event.player.PlayerArmorChangeEvent e) {
    clearPotions(e.getPlayer());
  }

  private void tick() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      Map<String, Integer> now = counts(p);
      if (!now.equals(equipped.get(p.getUniqueId()))) clearPotions(p);
      double additional = 0;
      for (var entry : now.entrySet())
        additional +=
            config.getDouble("additional-patterns." + entry.getKey() + ".speed", 0)
                * scale(entry.getValue());
      equipped.put(p.getUniqueId(), now);
      if (ready) {
        Set<String> seen = observed.computeIfAbsent(p.getUniqueId(), id -> new HashSet<>());
        for (String trim : now.keySet())
          if (seen.add(trim))
            store
                .discover(p.getUniqueId(), trim)
                .exceptionally(
                    e -> {
                      plugin.getLogger().warning("Discovery write failed: " + e);
                      return null;
                    });
      }
      attribute(
          p,
          Attribute.MOVEMENT_SPEED,
          speedKey,
          additional
              + bonus("sentry-speed", .05) * scale(now.getOrDefault("sentry", 0))
              + (p.isSprinting()
                  ? bonus("wayfinder-speed", .05) * scale(now.getOrDefault("wayfinder", 0))
                  : 0));
      attribute(
          p,
          Attribute.JUMP_STRENGTH,
          jumpKey,
          bonus("spire-jump", .1) * scale(now.getOrDefault("spire", 0)));
      attribute(
          p,
          Attribute.BLOCK_BREAK_SPEED,
          miningKey,
          bonus("raiser-mining", .1) * scale(now.getOrDefault("raiser", 0)));
      attribute(
          p,
          Attribute.SNEAKING_SPEED,
          sneakKey,
          set(p, "shadow-assassin", 2) ? bonus("shadow-sneaking", .15) : 0);
      attribute(
          p,
          Attribute.WATER_MOVEMENT_EFFICIENCY,
          waterKey,
          bonus("coast-swimming", .1) * scale(now.getOrDefault("coast", 0)));
      if (now.getOrDefault("tide", 0) >= config.getInt("thresholds.tide", 2)
          || set(p, "ocean-guardian", 2)) effect(p, PotionEffectType.WATER_BREATHING, 12, 0);
      if (set(p, "ocean-guardian", 4) && p.isInWater())
        effect(p, PotionEffectType.DOLPHINS_GRACE, 12, 0);
      if (p.getHealth() > 0
          && p.getHealth() < 8
          && set(p, "natures-wrath", 4)
          && cooldown(p, "nature", config.getDouble("sets.natures-wrath.cooldown", 120)))
        effect(p, PotionEffectType.REGENERATION, 100, 1);
    }
    Set<UUID> online = new HashSet<>();
    for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
    equipped.keySet().retainAll(online);
    observed.keySet().retainAll(online);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    if (!(e.getEntity() instanceof Player p)) return;
    double resistance = bonus("ward-resistance", .05) * strength(p, "ward");
    switch (e.getCause()) {
      case FALL -> resistance += bonus("dune-resistance", .2) * strength(p, "dune");
      case BLOCK_EXPLOSION, ENTITY_EXPLOSION ->
          resistance += bonus("rib-resistance", .2) * strength(p, "rib");
      case PROJECTILE -> resistance += bonus("shaper-resistance", .15) * strength(p, "shaper");
      default -> {}
    }
    if (e instanceof EntityDamageByEntityEvent
        && Math.random() < bonus("eye-dodge", .1) * strength(p, "eye")) {
      e.setCancelled(true);
      return;
    }
    e.setDamage(e.getDamage() * (1 - Math.clamp(resistance, 0, .8)));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void damaged(EntityDamageEvent e) {
    if (!(e.getEntity() instanceof Player p) || e.getFinalDamage() <= 0) return;
    boolean snout = counts(p).getOrDefault("snout", 0) >= config.getInt("thresholds.snout", 2);
    if ((snout || set(p, "nether-king", 2))
        && cooldown(p, "fire", config.getDouble("sets.nether-king.cooldown", 60)))
      effect(p, PotionEffectType.FIRE_RESISTANCE, 100, 0);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void regeneration(EntityRegainHealthEvent e) {
    if (e.getEntity() instanceof Player p
        && e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED)
      e.setAmount(
          e.getAmount()
              * (1
                  + bonus("wild-regeneration", .2) * strength(p, "wild")
                  + (set(p, "natures-wrath", 2) ? bonus("nature-regeneration", .1) : 0)));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void knockback(io.papermc.paper.event.entity.EntityKnockbackEvent e) {
    if (e.getEntity() instanceof Player p
        && Math.random() < bonus("silence-resist", .2) * strength(p, "silence"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void attack(EntityDamageByEntityEvent e) {
    if (!(e.getDamager() instanceof Player p)) return;
    if (p.isInWater() && set(p, "ocean-guardian", 4))
      e.setDamage(e.getDamage() * (1 + bonus("ocean-damage", .1)));
    if (Math.random() < bonus("bolt-chance", .05) * strength(p, "bolt")
        && cooldown(p, "bolt", 15)) {
      e.getEntity().getWorld().strikeLightningEffect(e.getEntity().getLocation());
      e.setDamage(e.getDamage() + bonus("bolt-damage", 1));
    }
  }

  @EventHandler
  public void kill(EntityDeathEvent e) {
    Player p = e.getEntity().getKiller();
    if (p == null) return;
    if (counts(p).getOrDefault("vex", 0) >= config.getInt("thresholds.vex", 2)
        && cooldown(p, "vex", 60)) effect(p, PotionEffectType.INVISIBILITY, 60, 0);
    if (e.getEntity() instanceof Player
        && set(p, "nether-king", 4)
        && cooldown(p, "nether-kill", config.getDouble("sets.nether-king.cooldown", 60)))
      effect(p, PotionEffectType.STRENGTH, 100, 0);
    if (set(p, "shadow-assassin", 4)
        && cooldown(p, "shadow", config.getDouble("sets.shadow-assassin.cooldown", 90)))
      effect(p, PotionEffectType.INVISIBILITY, 80, 0);
  }

  private static final class Menu implements InventoryHolder {
    Inventory inventory;

    public Inventory getInventory() {
      return inventory;
    }
  }

  private void collection(Player p, Set<String> discovered) {
    Menu holder = new Menu();
    holder.inventory =
        Bukkit.createInventory(holder, 54, Component.text(Messages.get(plugin, "armor-title")));
    int index = 10;
    for (TrimPattern pattern : Registry.TRIM_PATTERN) {
      String name = pattern.getKey().getKey();
      ItemStack icon =
          new ItemStack(
              discovered.contains(name) ? Material.NETHERITE_CHESTPLATE : Material.GRAY_DYE);
      var meta = icon.getItemMeta();
      meta.displayName(Component.text(name));
      meta.lore(
          List.of(
              Component.text(discovered.contains(name) ? "Discovered" : "Undiscovered"),
              Component.text("Matching pieces: 25% / 50% / 75% / 100%")));
      icon.setItemMeta(meta);
      holder.inventory.setItem(index++, icon);
      if (index >= 44) break;
    }
    p.openInventory(holder.inventory);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @Override
  public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
    boolean admin = command.getName().equals("armoradmin");
    if (!s.hasPermission(admin ? "polder.armor.admin" : "polder.armor.view")) {
      s.sendMessage(Messages.get(plugin, "no-permission"));
      return true;
    }
    try {
      switch (command.getName()) {
        case "armorinfo" -> {
          Player target =
              args.length == 0 && s instanceof Player p
                  ? p
                  : args.length == 1 ? Bukkit.getPlayerExact(args[0]) : null;
          if (target == null) throw new IllegalArgumentException("Online player required");
          if (!s.equals(target) && !s.hasPermission("polder.armor.inspect"))
            throw new IllegalArgumentException("No permission to inspect others");
          s.sendMessage("Armor: " + counts(target));
        }
        case "armorcollection" -> {
          if (!(s instanceof Player p)) throw new IllegalArgumentException("Player required");
          if (!ready) throw new IllegalArgumentException("Database unavailable");
          store
              .collection(p.getUniqueId())
              .whenComplete(
                  (found, e) ->
                      Bukkit.getScheduler()
                          .runTask(
                              plugin,
                              () -> {
                                if (e == null && p.isOnline()) collection(p, found);
                                else s.sendMessage("Collection unavailable.");
                              }));
        }
        case "armortop" -> {
          if (!ready) throw new IllegalArgumentException("Database unavailable");
          store
              .top()
              .whenComplete(
                  (rows, e) ->
                      Bukkit.getScheduler()
                          .runTask(
                              plugin,
                              () -> {
                                if (e == null) {
                                  s.sendMessage("Armor collectors (UUID : discoveries)");
                                  rows.forEach(s::sendMessage);
                                } else s.sendMessage("Leaderboard unavailable.");
                              }));
        }
        case "armoradmin" -> {
          if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reload();
            s.sendMessage(Messages.get(plugin, "armor-reloaded"));
          } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            TrimPattern pattern =
                Registry.TRIM_PATTERN.get(
                    NamespacedKey.minecraft(args[2].toLowerCase(Locale.ROOT)));
            if (target == null || pattern == null)
              throw new IllegalArgumentException("Invalid player or pattern");
            String slot = args[3].toUpperCase(Locale.ROOT);
            Material material =
                switch (slot) {
                  case "HEAD", "HELMET" -> Material.NETHERITE_HELMET;
                  case "CHEST", "CHESTPLATE" -> Material.NETHERITE_CHESTPLATE;
                  case "LEGS", "LEGGINGS" -> Material.NETHERITE_LEGGINGS;
                  case "FEET", "BOOTS" -> Material.NETHERITE_BOOTS;
                  default -> throw new IllegalArgumentException("Invalid armor slot");
                };
            if (target.getInventory().firstEmpty() < 0)
              throw new IllegalArgumentException("Inventory full");
            ItemStack item = new ItemStack(material);
            ArmorMeta meta = (ArmorMeta) item.getItemMeta();
            meta.setTrim(new ArmorTrim(TrimMaterial.GOLD, pattern));
            item.setItemMeta(meta);
            target.getInventory().addItem(item);
            s.sendMessage("Trimmed armor granted.");
          } else s.sendMessage("/armoradmin give <player> <trim> <slot> | reload");
        }
        default -> {}
      }
      return true;
    } catch (Exception e) {
      s.sendMessage("Unable to complete: " + e.getMessage());
      return true;
    }
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
    if (!s.hasPermission("polder.armor.admin") && !c.getName().equals("armorinfo"))
      return List.of();
    List<String> options =
        args.length == 1
            ? List.of("give", "reload")
            : args.length == 2
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : args.length == 3
                    ? Registry.TRIM_PATTERN.stream().map(t -> t.getKey().getKey()).toList()
                    : args.length == 4
                        ? List.of("helmet", "chestplate", "leggings", "boots")
                        : List.of();
    return options.stream()
        .filter(x -> x.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
        .toList();
  }

  @Override
  public void close() {
    task.cancel();
    for (Player p : Bukkit.getOnlinePlayers()) {
      clearPotions(p);
      attribute(p, Attribute.MOVEMENT_SPEED, speedKey, 0);
      attribute(p, Attribute.JUMP_STRENGTH, jumpKey, 0);
      attribute(p, Attribute.BLOCK_BREAK_SPEED, miningKey, 0);
      attribute(p, Attribute.SNEAKING_SPEED, sneakKey, 0);
      attribute(p, Attribute.WATER_MOVEMENT_EFFICIENCY, waterKey, 0);
    }
    store.close();
  }
}
