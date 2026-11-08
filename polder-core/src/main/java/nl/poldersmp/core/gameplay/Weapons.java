package nl.poldersmp.core.gameplay;

import java.io.File;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;

public final class Weapons implements Listener, TabExecutor {
  public enum Kind {
    LIGHTNING_BLADE,
    SHADOW_DAGGER,
    TITAN_HAMMER,
    FROSTBITE_SWORD,
    SOUL_REAPER
  }

  private final JavaPlugin plugin;
  private final NamespacedKey type, serial;
  private final Rules.Cooldowns cooldowns = new Rules.Cooldowns();
  private YamlConfiguration config;
  private boolean secondary;
  private final Map<EntityDamageByEntityEvent, Kind> pending = new IdentityHashMap<>();

  public Weapons(JavaPlugin plugin) {
    this.plugin = plugin;
    type = new NamespacedKey(plugin, "legendary_weapon");
    serial = new NamespacedKey(plugin, "weapon_serial");
    reload();
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    for (String cmd : List.of("weapons", "weaponinfo", "weaponadmin")) {
      plugin.getCommand(cmd).setExecutor(this);
      plugin.getCommand(cmd).setTabCompleter(this);
    }
  }

  public void reload() {
    File file = new File(plugin.getDataFolder(), "weapons.yml");
    if (!file.exists()) plugin.saveResource("weapons.yml", false);
    YamlConfiguration next = Configs.load(file);
    for (Kind k : Kind.values()) {
      String key = "weapons." + k.name();
      if (next.getDouble(key + ".cooldown") < 0
          || next.getDouble(key + ".chance", 1) < 0
          || next.getDouble(key + ".chance", 1) > 1)
        throw new IllegalArgumentException("Invalid weapon settings: " + k);
    }
    config = next;
  }

  private double value(Kind k, String name, double def) {
    return config.getDouble("weapons." + k.name() + "." + name, def);
  }

  public Kind identify(ItemStack item) {
    return identify(item, type, serial);
  }

  public static Kind identify(ItemStack item, NamespacedKey type, NamespacedKey serial) {
    if (item == null || !item.hasItemMeta() || item.getAmount() != 1) return null;
    var data = item.getItemMeta().getPersistentDataContainer();
    String id = data.get(type, PersistentDataType.STRING),
        uuid = data.get(serial, PersistentDataType.STRING);
    try {
      UUID.fromString(uuid);
      Kind k = Kind.valueOf(id);
      return item.getType() == material(k) ? k : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static Material material(Kind k) {
    return k == Kind.TITAN_HAMMER
        ? Material.NETHERITE_AXE
        : k == Kind.FROSTBITE_SWORD ? Material.DIAMOND_SWORD : Material.NETHERITE_SWORD;
  }

  private ItemStack item(Kind k, boolean real) {
    ItemStack item = new ItemStack(material(k));
    var meta = item.getItemMeta();
    meta.displayName(
        Component.text("✦ " + k.name().replace('_', ' '))
            .color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
    meta.lore(
        List.of(
            Component.text("LEGENDARY • PolderSMP"),
            Component.text(config.getString("weapons." + k.name() + ".description", "")),
            Component.text("Cooldown: " + value(k, "cooldown", 20) + " seconds"),
            Component.text("Acquisition: administrator grant")));
    if (real) {
      meta.getPersistentDataContainer().set(type, PersistentDataType.STRING, k.name());
      meta.getPersistentDataContainer()
          .set(serial, PersistentDataType.STRING, UUID.randomUUID().toString());
    }
    item.setItemMeta(meta);
    return item;
  }

  private boolean ready(Player p, Kind k) {
    return unique(p)
        && config.getBoolean("enabled", true)
        && config.getBoolean("weapons." + k.name() + ".enabled", true)
        && p.hasPermission("polder.weapons.use")
        && cooldowns.acquire(
            p.getUniqueId(),
            k.name(),
            System.nanoTime() / 1000000,
            (long) (value(k, "cooldown", 20) * 1000));
  }

  private boolean unique(Player owner) {
    ItemStack held = owner.getInventory().getItemInMainHand();
    if (!held.hasItemMeta()) return false;
    String id =
        held.getItemMeta().getPersistentDataContainer().get(serial, PersistentDataType.STRING);
    int copies = 0;
    for (Player online : Bukkit.getOnlinePlayers())
      for (ItemStack item : online.getInventory().getContents())
        if (item != null
            && item.hasItemMeta()
            && Objects.equals(
                id,
                item.getItemMeta()
                    .getPersistentDataContainer()
                    .get(serial, PersistentDataType.STRING))) copies += item.getAmount();
    return copies == 1;
  }

  private void effects(Location l, Particle particle, Sound sound) {
    if (config.getBoolean("particles", true))
      l.getWorld().spawnParticle(particle, l, 25, .5, .5, .5, .05);
    if (config.getBoolean("sounds", true)) l.getWorld().playSound(l, sound, 1, 1);
  }

  private boolean canHit(Player from, LivingEntity target) {
    if (target.equals(from) || target.isInvulnerable()) return false;
    for (var provider : Bukkit.getServicesManager().getRegistrations(AbilityGuard.class))
      if (!provider.getProvider().allowDamage(from, target)) return false;
    if (target instanceof Player to) {
      if (!from.getWorld().getPVP()) return false;
      var team = from.getScoreboard().getEntryTeam(from.getName());
      if (team != null && team.hasEntry(to.getName()) && !team.allowFriendlyFire()) return false;
    }
    return true;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void hit(EntityDamageByEntityEvent e) {
    if (secondary
        || !(e.getDamager() instanceof Player p)
        || !(e.getEntity() instanceof LivingEntity target)
        || !canHit(p, target)) return;
    Kind k = identify(p.getInventory().getItemInMainHand());
    if (k != Kind.LIGHTNING_BLADE && k != Kind.FROSTBITE_SWORD) return;
    if (!Rules.chance(value(k, "chance", .15), Math.random()) || !ready(p, k)) return;
    pending.put(e, k);
    if (k == Kind.LIGHTNING_BLADE) e.setDamage(e.getDamage() + value(k, "damage", 3));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void confirmedHit(EntityDamageByEntityEvent e) {
    Kind k = pending.remove(e);
    if (e.isCancelled()
        || e.getFinalDamage() <= 0
        || !(e.getEntity() instanceof LivingEntity target)) return;
    if (secondary && e.getDamager() instanceof Player p) {
      Vector delta = target.getLocation().toVector().subtract(p.getLocation().toVector());
      if (delta.lengthSquared() > 0)
        target.setVelocity(
            delta.normalize().multiply(value(Kind.TITAN_HAMMER, "knockback", .7)).setY(.35));
    }
    if (k == Kind.LIGHTNING_BLADE) {
      target.getWorld().strikeLightningEffect(target.getLocation());
      effects(target.getLocation(), Particle.ELECTRIC_SPARK, Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
    } else if (k == Kind.FROSTBITE_SWORD) {
      if (!target.hasPotionEffect(PotionEffectType.SLOWNESS))
        target.addPotionEffect(
            new PotionEffect(PotionEffectType.SLOWNESS, (int) value(k, "duration-ticks", 60), 1));
      effects(target.getLocation(), Particle.SNOWFLAKE, Sound.BLOCK_GLASS_BREAK);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void kill(EntityDeathEvent e) {
    Player p = e.getEntity().getKiller();
    if (p == null) return;
    Kind k = identify(p.getInventory().getItemInMainHand());
    if (k != Kind.SOUL_REAPER || !ready(p, k)) return;
    var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
    if (max != null && p.getHealth() > 0)
      p.setHealth(Math.min(max.getValue(), p.getHealth() + value(k, "healing", 4)));
    effects(p.getLocation(), Particle.SOUL, Sound.PARTICLE_SOUL_ESCAPE);
  }

  @EventHandler
  public void use(PlayerInteractEvent e) {
    if (e.useItemInHand() == Event.Result.DENY) return;
    if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
    Player p = e.getPlayer();
    Kind k = identify(e.getItem());
    if (k != Kind.SHADOW_DAGGER && k != Kind.TITAN_HAMMER) return;
    if (!config.getBoolean("enabled", true)) return;
    // Movement/area abilities fail closed around protection plugins until an explicit adapter
    // approves them.
    if (protectedEnvironment()) {
      p.sendMessage("This ability requires a protection integration on this server.");
      return;
    }
    if (k == Kind.SHADOW_DAGGER) {
      Location start = p.getLocation(), safe = start.clone();
      Vector direction = start.getDirection().setY(0).normalize();
      double distance = Math.clamp(value(k, "distance", 6), 0, 16);
      for (double step = .25; step <= distance; step += .25) {
        Location next = start.clone().add(direction.clone().multiply(step));
        if (Bukkit.getServicesManager().getRegistrations(AbilityGuard.class).stream()
            .anyMatch(provider -> !provider.getProvider().allowMovement(p, start, next))) break;
        if (!clearBody(p, next)
            || !next.getBlock().isPassable()
            || !next.clone().add(0, 1, 0).getBlock().isPassable()
            || !next.clone().subtract(0, 1, 0).getBlock().getType().isSolid()
            || !next.getWorld().getWorldBorder().isInside(next)) break;
        safe = next;
      }
      if (safe.distanceSquared(start) < .1 || !ready(p, k)) return;
      if (p.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN))
        effects(safe, Particle.SMOKE, Sound.ENTITY_ENDERMAN_TELEPORT);
    } else {
      if (!p.isOnGround() || !ready(p, k)) return;
      double radius = Math.clamp(value(k, "radius", 4), 0, 12);
      for (Entity entity : p.getNearbyEntities(radius, radius, radius)) {
        if (!(entity instanceof LivingEntity target)
            || target.getLocation().distanceSquared(p.getLocation()) > radius * radius
            || !canHit(p, target)
            || !p.hasLineOfSight(target)) continue;
        secondary = true;
        try {
          target.damage(value(k, "damage", 6), p);
        } finally {
          secondary = false;
        }
      }
      effects(p.getLocation(), Particle.CRIT, Sound.ENTITY_GENERIC_EXPLODE);
    }
  }

  private boolean clearBody(Player p, Location destination) {
    var offset = destination.toVector().subtract(p.getLocation().toVector());
    var body = p.getBoundingBox().clone().shift(offset);
    for (int x = (int) Math.floor(body.getMinX()); x <= Math.floor(body.getMaxX()); x++)
      for (int y = (int) Math.floor(body.getMinY()); y <= Math.floor(body.getMaxY()); y++)
        for (int z = (int) Math.floor(body.getMinZ()); z <= Math.floor(body.getMaxZ()); z++) {
          var block = destination.getWorld().getBlockAt(x, y, z);
          if (!block.isPassable() && body.overlaps(block.getBoundingBox())) return false;
        }
    return true;
  }

  private boolean protectedEnvironment() {
    if (!Bukkit.getServicesManager().getRegistrations(AbilityGuard.class).isEmpty()) return false;
    return plugin.getServer().getPluginManager().getPlugin("WorldGuard") != null
        || plugin.getServer().getPluginManager().getPlugin("GriefPrevention") != null
        || plugin.getServer().getPluginManager().getPlugin("Towny") != null;
  }

  private static final class Menu implements InventoryHolder {
    private Inventory inventory;

    public Inventory getInventory() {
      return inventory;
    }
  }

  private void menu(Player p) {
    Menu holder = new Menu();
    holder.inventory =
        Bukkit.createInventory(holder, 54, Component.text(Messages.get(plugin, "weapons-title")));
    ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
    var meta = border.getItemMeta();
    meta.displayName(Component.text(" "));
    border.setItemMeta(meta);
    for (int i = 0; i < 54; i++) holder.inventory.setItem(i, border);
    int i = 20;
    for (Kind k : Kind.values()) holder.inventory.setItem(i++, item(k, false));
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

  @EventHandler(ignoreCancelled = true)
  public void anvil(PrepareAnvilEvent e) {
    if (identify(e.getInventory().getFirstItem()) != null
        || identify(e.getInventory().getSecondItem()) != null) e.setResult(null);
  }

  @Override
  public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
    if (!s.hasPermission(
        c.getName().equals("weaponadmin") ? "polder.weapons.admin" : "polder.weapons.view")) {
      s.sendMessage(Messages.get(plugin, "no-permission"));
      return true;
    }
    try {
      if (c.getName().equals("weaponadmin")) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
          reload();
          s.sendMessage(Messages.get(plugin, "weapons-reloaded"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
          Player target = Bukkit.getPlayerExact(args[1]);
          if (target == null) throw new IllegalArgumentException("Player must be online");
          Kind k = Kind.valueOf(args[2].toUpperCase(Locale.ROOT));
          if (!config.getBoolean("acquisition.admin-give", true))
            throw new IllegalArgumentException("Admin acquisition disabled");
          if (target.getInventory().firstEmpty() < 0)
            throw new IllegalArgumentException("Inventory full");
          target.getInventory().addItem(item(k, true));
          s.sendMessage("Granted " + k);
        } else s.sendMessage("/weaponadmin give <player> <weapon> | reload");
      } else if (s instanceof Player p) {
        if (c.getName().equals("weaponinfo")) {
          Kind k = identify(p.getInventory().getItemInMainHand());
          if (k == null) p.sendMessage("Hold a legendary weapon.");
          else p.sendMessage(k + ": " + config.getString("weapons." + k.name() + ".description"));
        } else menu(p);
      }
      return true;
    } catch (Exception ex) {
      s.sendMessage("Unable to complete: " + ex.getMessage());
      return true;
    }
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
    if (!s.hasPermission("polder.weapons.admin")) return List.of();
    List<String> choices =
        args.length == 1
            ? List.of("give", "reload")
            : args.length == 2
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : args.length == 3
                    ? Arrays.stream(Kind.values()).map(Enum::name).toList()
                    : List.of();
    return choices.stream()
        .filter(
            x ->
                x.toLowerCase(Locale.ROOT)
                    .startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
        .toList();
  }
}
