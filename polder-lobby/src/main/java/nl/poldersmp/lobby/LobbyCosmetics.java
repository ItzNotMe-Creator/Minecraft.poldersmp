package nl.poldersmp.lobby;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

final class LobbyCosmetics implements Listener {
  private final PolderLobby p;
  final NamespacedKey visual;
  private final Map<UUID, List<Entity>> entities = new HashMap<>();
  private final Map<UUID, Preview> previews = new HashMap<>();
  private int tick;

  private record Preview(String cosmetic, long until) {}

  LobbyCosmetics(PolderLobby p) {
    this.p = p;
    visual = new NamespacedKey(p, "visual");
    Bukkit.getPluginManager().registerEvents(this, p);
    for (World w : Bukkit.getWorlds())
      for (Entity entity : w.getEntities()) if (owned(entity)) entity.remove();
    Bukkit.getScheduler().runTaskTimer(p, this::tick, 10, 10);
  }

  private boolean owned(Entity e) {
    return e.getPersistentDataContainer().has(visual);
  }

  private void tag(Entity e) {
    e.getPersistentDataContainer().set(visual, PersistentDataType.BYTE, (byte) 1);
    e.setPersistent(false);
  }

  void firework(Player player) {
    Firework fire = player.getWorld().spawn(player.getLocation().add(0, 1, 0), Firework.class);
    tag(fire);
    var meta = fire.getFireworkMeta();
    meta.addEffect(
        FireworkEffect.builder()
            .withColor(Color.AQUA, Color.YELLOW)
            .with(FireworkEffect.Type.BALL)
            .trail(true)
            .build());
    meta.setPower(0);
    fire.setFireworkMeta(meta);
    Bukkit.getScheduler()
        .runTaskLater(
            p,
            () -> {
              if (fire.isValid()) fire.detonate();
            },
            2);
  }

  void arrival(Player player) {
    var profile = p.profiles.get(player.getUniqueId());
    if (profile != null && profile.selections().containsKey("join"))
      player
          .getWorld()
          .spawnParticle(Particle.FIREWORK, player.getLocation().add(0, 1, 0), 35, .6, .6, .6, .05);
  }

  void preview(Player player, String id) {
    if (!p.use(player, "cosmetics") || p.busy(player)) return;
    if (!p.cfg.contains("cosmetics.catalog." + id))
      throw new IllegalArgumentException("Unknown cosmetic");
    remove(player.getUniqueId());
    previews.put(player.getUniqueId(), new Preview(id, System.currentTimeMillis() + 5000));
    player.closeInventory();
    player.sendMessage("Preview • 5 seconds");
  }

  private void tick() {
    tick++;
    int shown = 0;
    Set<UUID> active = new HashSet<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID id = player.getUniqueId();
      if (!p.module("cosmetics")
          || p.busy(player)
          || !p.lobbyWorld(player.getWorld())
          || shown++ >= p.cfg.getInt("cosmetics.maximum-visual-players")) {
        remove(id);
        continue;
      }
      var profile = p.profiles.get(id);
      if (profile == null) continue;
      Map<String, String> choices = new HashMap<>(profile.selections());
      choices
          .entrySet()
          .removeIf(
              e ->
                  !p.cfg.contains("cosmetics.catalog." + e.getValue())
                      || !profile.unlocks().contains(e.getValue()));
      Preview preview = previews.get(id);
      if (preview != null) {
        if (preview.until < System.currentTimeMillis()) {
          remove(id);
          preview = null;
        } else
          choices =
              Map.of(
                  p.cfg.getString("cosmetics.catalog." + preview.cosmetic + ".category", "trail"),
                  preview.cosmetic);
      }
      active.add(id);
      if (preview != null
          && choices.containsKey("join")
          && tick % 2 == 0
          && p.cfg.getBoolean("effects.particles", true))
        player.spawnParticle(
            Particle.FIREWORK, player.getLocation().add(0, 1, 0), 15, .5, .5, .5, .03);
      if (p.cfg.getBoolean("effects.particles", true) && choices.containsKey("trail"))
        player
            .getWorld()
            .spawnParticle(
                Particle.ELECTRIC_SPARK, player.getLocation().add(0, .15, 0), 4, .2, .1, .2, .01);
      if (p.cfg.getBoolean("effects.particles", true) && choices.containsKey("wings")) {
        for (int side : new int[] {-1, 1})
          for (int i = 1; i <= 5; i++) {
            Vector v =
                new Vector(side * i * .2, 1.2 - Math.abs(Math.sin(tick * .15)) * i * .08, .35)
                    .rotateAroundY(Math.toRadians(-player.getYaw()));
            player
                .getWorld()
                .spawnParticle(Particle.END_ROD, player.getLocation().add(v), 1, 0, 0, 0, 0);
          }
      }
      boolean wantsEntities =
          choices.containsKey("pet") || choices.containsKey("hat") || choices.containsKey("title");
      List<Entity> owned = entities.computeIfAbsent(id, k -> new ArrayList<>());
      boolean pet = owned.stream().anyMatch(e -> e instanceof Rabbit && e.isValid()),
          hat = owned.stream().anyMatch(e -> e instanceof ItemDisplay && e.isValid());
      if (choices.containsKey("pet") && !pet) {
        Rabbit r = player.getWorld().spawn(player.getLocation(), Rabbit.class);
        tag(r);
        r.setAI(false);
        r.setGravity(false);
        r.setInvulnerable(true);
        r.setSilent(true);
        r.setCollidable(false);
        owned.add(r);
      }
      if (choices.containsKey("hat") && !hat) {
        ItemDisplay display =
            player.getWorld().spawn(player.getLocation().add(0, 2.3, 0), ItemDisplay.class);
        tag(display);
        Material material =
            Material.matchMaterial(
                p.cfg.getString(
                    "cosmetics.catalog." + choices.get("hat") + ".material", "GOLDEN_HELMET"));
        display.setItemStack(new ItemStack(material == null ? Material.GOLDEN_HELMET : material));
        display.setTeleportDuration(10);
        display.setViewRange(.375f);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
        owned.add(display);
      }
      if (choices.containsKey("title")
          && owned.stream().noneMatch(e -> e instanceof TextDisplay && e.isValid())) {
        TextDisplay text =
            player.getWorld().spawn(player.getLocation().add(0, 2.8, 0), TextDisplay.class);
        tag(text);
        text.text(
            net.kyori.adventure.text.Component.text(
                p.cfg.getString(
                    "cosmetics.catalog." + choices.get("title") + ".name", "Lobby Champion")));
        text.setBillboard(Display.Billboard.CENTER);
        text.setTeleportDuration(10);
        text.setViewRange(.25f);
        owned.add(text);
      }
      for (Entity entity : owned) {
        if (!entity.isValid()) continue;
        Location dest =
            player
                .getLocation()
                .add(
                    entity instanceof Rabbit
                        ? new Vector(.8, .15, .8)
                        : new Vector(
                            0,
                            (entity instanceof TextDisplay ? 2.8 : 2.3) + Math.sin(tick * .2) * .12,
                            0));
        dest.setYaw(tick * 12 % 360);
        dest.setPitch(0);
        entity.teleport(dest);
      }
      if (!wantsEntities && !owned.isEmpty()) remove(id);
    }
    for (UUID id : new ArrayList<>(entities.keySet())) if (!active.contains(id)) remove(id);
  }

  void remove(UUID id) {
    List<Entity> owned = entities.remove(id);
    if (owned != null) owned.forEach(Entity::remove);
    previews.remove(id);
  }

  void cleanup() {
    for (UUID id : new ArrayList<>(entities.keySet())) remove(id);
    previews.clear();
    for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (owned(e)) e.remove();
  }

  @EventHandler
  public void chunk(ChunkLoadEvent e) {
    for (Entity entity : e.getChunk().getEntities()) if (owned(entity)) entity.remove();
  }

  @EventHandler
  public void damage(EntityDamageEvent e) {
    if (owned(e.getEntity())) e.setCancelled(true);
    if (e instanceof EntityDamageByEntityEvent d && owned(d.getDamager())) e.setCancelled(true);
  }

  @EventHandler
  public void death(EntityDeathEvent e) {
    if (owned(e.getEntity())) {
      e.getDrops().clear();
      e.setDroppedExp(0);
    }
  }
}
