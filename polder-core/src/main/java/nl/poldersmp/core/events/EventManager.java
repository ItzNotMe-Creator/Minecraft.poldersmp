package nl.poldersmp.core.events;

import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.competition.Economy;
import nl.poldersmp.core.loot.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Container;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;

public final class EventManager implements Listener, TabExecutor, AutoCloseable {
  public static final class Context {
    final EventManager manager;
    EventStore.State state;
    final EventConfig config;
    final WorldEvent handler;
    final List<LootStore.BlockPlan> blocks;
    final List<EventStore.Chest> chests;
    final Map<UUID, Double> damage;
    final Map<UUID, EventStore.Mob> actors = new HashMap<>();
    final Set<UUID> projectiles = new HashSet<>();
    boolean transition, loaded, loading, checkpoint;
    long special, trap;
    int resumeFrames;
    BlockDisplay visual;
    BossBar bar;

    Context(
        EventManager manager,
        EventStore.State state,
        List<LootStore.BlockPlan> blocks,
        List<EventStore.Chest> chests,
        Map<UUID, Double> damage,
        List<EventStore.Mob> mobs) {
      this.manager = manager;
      this.state = state;
      this.config = EventConfig.restore(state.settings());
      this.handler = manager.handlers.get(state.type());
      this.blocks = blocks;
      this.chests = new ArrayList<>(chests);
      this.damage = new HashMap<>(damage);
      for (var mob : mobs) actors.put(mob.id(), mob);
    }

    public EventManager manager() {
      return manager;
    }

    public EventStore.State state() {
      return state;
    }

    Location location() {
      World world = Bukkit.getWorld(state.world());
      return world == null ? null : new Location(world, state.x(), state.y(), state.z());
    }
  }

  private final JavaPlugin plugin;
  private final EventStore store;
  private final NamespacedKey eventKey, roleKey, waveKey;
  private final Map<EventRules.Type, WorldEvent> handlers = new EnumMap<>(EventRules.Type.class);
  private EventConfig config;
  private YamlConfiguration messages;
  private Context current;
  private Instant next;
  private boolean ready, starting, outbox;
  private volatile boolean closed;
  private long nextRecovery;
  private final org.bukkit.scheduler.BukkitTask task;

  public EventManager(JavaPlugin plugin, Database db) {
    this.plugin = plugin;
    this.config = EventConfig.load(plugin);
    this.messages =
        YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    Economy economy = Bukkit.getServicesManager().load(Economy.class);
    store = new EventStore(db, economy == null ? new Economy.Foundation() : economy);
    eventKey = new NamespacedKey(plugin, "world_event");
    roleKey = new NamespacedKey(plugin, "world_event_role");
    waveKey = new NamespacedKey(plugin, "world_event_wave");
    for (var handler :
        List.of(
            new MeteorEvent(),
            new SupplyDropEvent(),
            new BossEvent(),
            new PirateShipEvent(),
            new DungeonEvent())) handlers.put(handler.type(), handler);
    for (String name : List.of("events", "eventadmin")) {
      plugin.getCommand(name).setExecutor(this);
      plugin.getCommand(name).setTabCompleter(this);
    }
    Bukkit.getPluginManager().registerEvents(this, plugin);
    store
        .migrate()
        .thenCompose(v -> store.setting("next"))
        .whenComplete(
            (saved, error) ->
                main(
                    () -> {
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      next =
                          saved == null
                              ? EventRules.next(
                                  Instant.now(), config.yaml().getLong("interval-seconds"))
                              : Instant.parse(saved);
                      if (saved == null) saveNext();
                      store
                          .active()
                          .whenComplete(
                              (state, e) ->
                                  main(
                                      () -> {
                                        if (e != null) {
                                          failed(e);
                                          return;
                                        }
                                        ready = true;
                                        if (state != null) restore(state);
                                        plugin.getLogger().info("World event persistence ready");
                                      }));
                    }));
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
  }

  private Loot loot() {
    return Bukkit.getServicesManager().load(Loot.class);
  }

  private void main(Runnable action) {
    if (!closed && plugin.isEnabled())
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (!closed) action.run();
              });
  }

  private void failed(Throwable error) {
    plugin.getLogger().severe("World event operation failed: " + error);
  }

  private <T> void result(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
    future.whenComplete(
        (v, error) ->
            main(
                () -> {
                  if (error != null) {
                    failed(error);
                    sender.sendMessage(
                        "Event operation failed; persisted state and rewards are retained.");
                  } else success.accept(v);
                }));
  }

  private void saveNext() {
    store
        .setting("next", next.toString())
        .exceptionally(
            e -> {
              failed(e);
              return null;
            });
  }

  private void restore(EventStore.State state) {
    starting = true;
    var blocks = store.blocks(state.id());
    var chests = store.chests(state.id());
    var damage = store.damage(state.id());
    var mobs = store.mobs(state.id());
    CompletableFuture.allOf(blocks, chests, damage, mobs)
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      starting = false;
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      current =
                          new Context(
                              this,
                              state,
                              blocks.join(),
                              chests.join(),
                              damage.join(),
                              mobs.join());
                      load(current);
                    }));
  }

  private void load(Context c) {
    if (c.loading || loot() == null || c.location() == null) return;
    c.loading = true;
    loot()
        .loadEventArea(c.location())
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      c.loading = false;
                      if (current != c) return;
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      loot().holdEventArea(c.state.id(), c.location());
                      c.loaded = true;
                      c.resumeFrames = 2;
                      if (c.state.status().equals("PLANNED")) {
                        cancel(c, "Incomplete event placement rolled back.");
                        return;
                      }
                      boolean intact =
                          c.blocks.stream()
                              .allMatch(
                                  b ->
                                      c.location()
                                          .getWorld()
                                          .getBlockAt(b.x(), b.y(), b.z())
                                          .getBlockData()
                                          .getAsString()
                                          .equals(b.placed()));
                      if (!c.state.status().equals("CLEANUP") && !intact) {
                        cancel(c, "Event structure was changed; cleanup reserved.");
                        return;
                      }
                      // Entity restoration completes before the first resumed actor scan.
                    }));
  }

  private void tick() {
    if (!ready) return;
    long now = System.currentTimeMillis();
    if (now >= nextRecovery) {
      nextRecovery = now + 5000;
      recoverRewards();
    }
    Context c = current;
    if (c == null) {
      if (!starting
          && config.yaml().getBoolean("enabled")
          && next != null
          && now >= next.toEpochMilli()) {
        next =
            EventRules.next(Instant.ofEpochMilli(now), config.yaml().getLong("interval-seconds"));
        saveNext();
        var choices = config.choices();
        start(
            null,
            LootRules.select(
                choices, ThreadLocalRandom.current().nextLong(LootRules.total(choices))));
      }
      return;
    }
    if (c.location() == null) {
      c.loaded = false;
      if (c.bar != null) c.bar.removeAll();
      return;
    }
    if (!c.loaded) {
      load(c);
      return;
    }
    if (c.resumeFrames > 0) {
      c.resumeFrames--;
      return;
    }
    if (c.transition) return;
    if (!config.yaml().getBoolean("enabled") && !c.state.status().equals("CLEANUP")) {
      finish(c, null, List.of(), false);
      return;
    }
    if (c.state.status().equals("CLEANUP")) {
      if (now >= c.state.cleanupAfter()) cleanup(c);
      return;
    }
    if (!loot().eventWilderness(c.location())) {
      cancel(c, "Event canceled because wilderness protection changed.");
      return;
    }
    bar(c);
    if ((c.state.type() == EventRules.Type.BOSS || c.state.type() == EventRules.Type.DUNGEON)
        && c.location().getWorld().getDifficulty() == Difficulty.PEACEFUL) {
      cancel(c, "Combat event requires a non-peaceful world.");
      return;
    }
    if (now >= c.state.expires()) {
      finish(
          c,
          null,
          List.of(),
          c.state.type() == EventRules.Type.PIRATE_SHIP
              && c.config.yaml().getBoolean("types.PIRATE_SHIP.persist-structure"));
      return;
    }
    if (c.state.status().equals("PLANNED")) {
      cancel(c, "Unconfirmed event placement rolled back.");
      return;
    }
    if (c.state.status().equals("WARNING")) {
      if (now >= c.state.starts()) phase(c, "ACTIVE", c.state.stage(), c.state.health(), List.of());
      return;
    }
    if (c.state.status().equals("ACTIVE")) {
      scan(c);
      c.handler.tick(c);
    } else if (c.state.status().equals("LOOT")) removeCombat(c);
    if (!c.checkpoint && !c.transition && c.state.status().equals("ACTIVE")) {
      c.checkpoint = true;
      store
          .checkpoint(c.state, Map.copyOf(c.damage))
          .whenComplete(
              (v, e) ->
                  main(
                      () -> {
                        c.checkpoint = false;
                        if (e != null) failed(e);
                      }));
    }
  }

  private boolean eligible(Player p) {
    return p.isOnline()
        && !p.isDead()
        && !p.isInvulnerable()
        && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
        && p.hasPermission("polder.events.play")
        && !p.hasPermission("polder.events.exempt")
        && !p.hasMetadata("vanished");
  }

  private boolean inside(Context c, Location loc) {
    return c.location() != null
        && loc.getWorld().equals(c.location().getWorld())
        && loc.distanceSquared(c.location().clone().add(.5, 1, .5))
            <= Math.pow(c.config.yaml().getDouble("arena-radius"), 2);
  }

  private String coords(Context c) {
    return c.location().getWorld().getName()
        + " "
        + c.state.x()
        + " "
        + c.state.y()
        + " "
        + c.state.z();
  }

  private void announce(String key, String fallback, Context c) {
    Bukkit.broadcast(
        Component.text(
            messages
                .getString(key, fallback)
                .replace("{event}", c.state.type().name())
                .replace("{coordinates}", coords(c))));
  }

  private void bar(Context c) {
    if (c.bar == null) c.bar = Bukkit.createBossBar("World event", BarColor.PURPLE, BarStyle.SOLID);
    double progress =
        c.state.status().equals("WARNING")
            ? 1
            : c.state.health() > 0
                ? c.state.health() / Math.max(1, c.config.decimal(c.state.type(), "health"))
                : 1;
    c.bar.setProgress(Math.clamp(progress, 0, 1));
    c.bar.setTitle(
        c.state.type()
            + " • "
            + (c.state.status().equals("WARNING")
                ? "starts in "
                    + Math.max(0, (c.state.starts() - System.currentTimeMillis() + 999) / 1000)
                    + "s"
                : c.state.status().equals("LOOT")
                    ? "Treasure available"
                    : c.state.type() == EventRules.Type.DUNGEON
                        ? "Stage " + c.state.stage()
                        : c.state.status()));
    double distance = c.config.yaml().getDouble("bar-distance");
    for (Player p : new ArrayList<>(c.bar.getPlayers()))
      if (!p.isOnline()
          || !p.getWorld().equals(c.location().getWorld())
          || p.getLocation().distanceSquared(c.location()) > distance * distance)
        c.bar.removePlayer(p);
    for (Player p : c.location().getWorld().getPlayers())
      if (eligible(p) && p.getLocation().distanceSquared(c.location()) <= distance * distance)
        c.bar.addPlayer(p);
  }

  private void start(CommandSender sender, EventRules.Type type) {
    if (starting || current != null) {
      if (sender != null) sender.sendMessage("An event or transition is already active.");
      return;
    }
    if (!config.yaml().getBoolean("enabled")
        || !config.yaml().getBoolean("types." + type + ".enabled")) {
      if (sender != null) sender.sendMessage("This event is disabled.");
      return;
    }
    Loot loot = loot();
    if (loot == null) {
      if (sender != null) sender.sendMessage("Loot/wilderness service unavailable.");
      return;
    }
    List<World> worlds =
        config.yaml().getStringList("worlds").stream()
            .map(Bukkit::getWorld)
            .filter(Objects::nonNull)
            .toList();
    if (worlds.isEmpty()) {
      if (sender != null) sender.sendMessage("Configure an explicit event world allowlist first.");
      return;
    }
    starting = true;
    EventConfig snapshot = EventConfig.restore(config.yaml().saveToString());
    WorldEvent handler = handlers.get(type);
    loot.findEventSite(
            worlds,
            snapshot.yaml().getInt("spawn-min-radius"),
            snapshot.yaml().getInt("spawn-max-radius"),
            snapshot.yaml().getInt("search-attempts"),
            handler.ocean())
        .whenComplete(
            (location, error) ->
                main(
                    () -> {
                      if (error != null) {
                        starting = false;
                        if (sender != null)
                          sender.sendMessage("No approved wilderness site found.");
                        return;
                      }
                      try {
                        List<LootStore.BlockPlan> blocks = new ArrayList<>();
                        for (var b : handler.structure()) {
                          var block =
                              location
                                  .getWorld()
                                  .getBlockAt(
                                      location.getBlockX() + b.x(),
                                      location.getBlockY() + b.y(),
                                      location.getBlockZ() + b.z());
                          if (!block.getType().isAir())
                            throw new IllegalStateException("Structure footprint is not air");
                          var floor =
                              location
                                  .getWorld()
                                  .getBlockAt(block.getX(), location.getBlockY() - 1, block.getZ());
                          if (handler.ocean()
                              ? floor.getType() != Material.WATER
                              : !floor.getType().isSolid())
                            throw new IllegalStateException("Unsupported structure footprint");
                          blocks.add(
                              new LootStore.BlockPlan(
                                  block.getX(),
                                  block.getY(),
                                  block.getZ(),
                                  block.getBlockData().getAsString(),
                                  b.material().createBlockData().getAsString()));
                        }
                        List<EventStore.Chest> chests =
                            handler.chests().stream()
                                .map(
                                    b ->
                                        new EventStore.Chest(
                                            b.index(),
                                            location.getBlockX() + b.x(),
                                            location.getBlockY() + b.y(),
                                            location.getBlockZ() + b.z(),
                                            null))
                                .toList();
                        long now = System.currentTimeMillis(),
                            starts = now + snapshot.number(type, "warning-seconds") * 1000;
                        long budget =
                            Math.addExact(
                                Math.multiplyExact(
                                    snapshot.yaml().getLong("economy.participation-money"),
                                    snapshot.yaml().getInt("maximum-participants")),
                                Math.multiplyExact(
                                    snapshot.yaml().getLong("economy.winner-money"),
                                    Math.max(1, chests.size())));
                        UUID treasury =
                            budget == 0
                                ? new UUID(0, 0)
                                : UUID.fromString(snapshot.yaml().getString("economy.treasury"));
                        var state =
                            new EventStore.State(
                                UUID.randomUUID(),
                                type,
                                "PLANNED",
                                location.getWorld().getUID(),
                                location.getBlockX(),
                                location.getBlockY(),
                                location.getBlockZ(),
                                starts,
                                starts + snapshot.number(type, "expiration-seconds") * 1000,
                                0,
                                snapshot.decimal(type, "health"),
                                null,
                                0,
                                snapshot.yaml().saveToString(),
                                budget,
                                treasury,
                                false);
                        store
                            .plan(state, blocks, chests)
                            .whenComplete(
                                (v, e) ->
                                    main(
                                        () -> {
                                          starting = false;
                                          if (e != null) {
                                            failed(e);
                                            if (sender != null)
                                              sender.sendMessage(
                                                  "Event could not reserve its state/treasury.");
                                            return;
                                          }
                                          Context c =
                                              new Context(
                                                  this, state, blocks, chests, Map.of(), List.of());
                                          current = c;
                                          loot.holdEventArea(state.id(), location);
                                          c.loaded = true;
                                          if (!loot.eventWilderness(location)
                                              || blocks.stream()
                                                  .anyMatch(
                                                      b ->
                                                          !location
                                                              .getWorld()
                                                              .getBlockAt(b.x(), b.y(), b.z())
                                                              .getBlockData()
                                                              .getAsString()
                                                              .equals(b.original()))) {
                                            cancel(c, "Event placement canceled safely.");
                                            return;
                                          }
                                          for (var b : blocks)
                                            location
                                                .getWorld()
                                                .getBlockAt(b.x(), b.y(), b.z())
                                                .setBlockData(
                                                    Bukkit.createBlockData(b.placed()), false);
                                          phase(c, "WARNING", 0, state.health(), List.of());
                                          announce(
                                              "event-warning",
                                              "{event} begins soon at {coordinates}",
                                              c);
                                          if (sender != null)
                                            sender.sendMessage("Event planned at " + coords(c));
                                        }));
                      } catch (Exception e) {
                        starting = false;
                        if (sender != null) sender.sendMessage("Site rejected: " + e.getMessage());
                      }
                    }));
  }

  private void phase(
      Context c, String status, int stage, double health, List<EventStore.Award> awards) {
    if (c.transition) return;
    c.transition = true;
    String old = c.state.status();
    store
        .phase(c.state.id(), old, status, stage, health, awards, Map.copyOf(c.damage))
        .whenComplete(
            (changed, error) ->
                main(
                    () -> {
                      c.transition = false;
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      if (changed) {
                        c.state = c.state.phase(status, stage, health);
                        if (status.equals("ACTIVE"))
                          announce("event-active", "{event} is active at {coordinates}", c);
                        if (status.equals("LOOT")) {
                          removeCombat(c);
                          effects(c, Sound.ENTITY_GENERIC_EXPLODE, Particle.END_ROD);
                          announce("event-loot", "Treasure is ready: {event} at {coordinates}", c);
                        }
                      }
                    }));
  }

  public void unlock(Context c, List<EventStore.Award> awards) {
    if (c.state.status().equals("ACTIVE")) phase(c, "LOOT", c.state.stage(), 0, awards);
  }

  public void fall(Context c, Material material) {
    double seconds = Math.max(1, c.config.number(c.state.type(), "fall-seconds"));
    double fraction =
        Math.clamp((System.currentTimeMillis() - c.state.starts()) / (seconds * 1000), 0, 1);
    if (fraction >= 1) {
      if (c.visual != null) {
        c.visual.remove();
        c.visual = null;
      }
      unlock(c, List.of());
      return;
    }
    if (c.visual == null)
      c.visual =
          c.location()
              .getWorld()
              .spawn(
                  c.location().clone().add(0, 30, 0),
                  BlockDisplay.class,
                  display -> {
                    display.setBlock(material.createBlockData());
                    display.setPersistent(false);
                    display
                        .getPersistentDataContainer()
                        .set(eventKey, PersistentDataType.STRING, c.state.id().toString());
                    display.setTeleportDuration(10);
                  });
    c.visual.teleport(c.location().clone().add(0, 30 * (1 - fraction), 0));
    if (c.config.yaml().getBoolean("particles"))
      c.location()
          .getWorld()
          .spawnParticle(Particle.SMOKE, c.visual.getLocation(), 10, .3, .3, .3, .01);
  }

  private void effects(Context c, Sound sound, Particle particle) {
    if (c.config.yaml().getBoolean("sounds"))
      c.location().getWorld().playSound(c.location(), sound, 1, 1);
    if (c.config.yaml().getBoolean("particles"))
      c.location()
          .getWorld()
          .spawnParticle(particle, c.location().clone().add(.5, 1, .5), 35, 2, .4, 2, .03);
  }

  private String tag(Entity entity, NamespacedKey key) {
    return entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
  }

  private boolean owned(Context c, Entity entity) {
    return c.state.id().toString().equals(tag(entity, eventKey));
  }

  private void scan(Context c) {
    for (Entity entity : c.location().getWorld().getNearbyEntities(c.location(), 8, 8, 8)) {
      if (!(entity instanceof Mob mob) || !owned(c, mob)) continue;
      int wave =
          mob.getPersistentDataContainer().getOrDefault(waveKey, PersistentDataType.INTEGER, 0);
      String role = tag(mob, roleKey);
      var row = c.actors.get(mob.getUniqueId());
      if (row != null && row.state() != 0) {
        mob.remove();
        continue;
      }
      if (row == null) {
        row = new EventStore.Mob(mob.getUniqueId(), role == null ? "WAVE" : role, wave, 0);
        c.actors.put(row.id(), row);
        store
            .mob(c.state.id(), row.id(), row.role(), wave)
            .exceptionally(
                e -> {
                  failed(e);
                  return null;
                });
      }
    }
    for (var row : new ArrayList<>(c.actors.values())) {
      if (row.state() != 0) continue;
      Entity entity = Bukkit.getEntity(row.id());
      if (entity == null) {
        c.actors.put(row.id(), new EventStore.Mob(row.id(), row.role(), row.wave(), 2));
        store
            .retire(row.id())
            .exceptionally(
                e -> {
                  failed(e);
                  return null;
                });
      } else if (!inside(c, entity.getLocation())) entity.teleport(spawnPoint(c));
    }
    for (UUID id : new HashSet<>(c.projectiles)) {
      Entity projectile = Bukkit.getEntity(id);
      if (projectile == null) c.projectiles.remove(id);
      else if (!inside(c, projectile.getLocation()) || projectile.getTicksLived() > 100) {
        projectile.remove();
        c.projectiles.remove(id);
      }
    }
  }

  private Location spawnPoint(Context c) {
    return c.location()
        .clone()
        .add(
            c.state.type() == EventRules.Type.DUNGEON ? 1.5 : .5,
            c.state.type() == EventRules.Type.DUNGEON ? 1 : 0,
            .5);
  }

  private Mob spawn(Context c, String role, int wave, double health) {
    if (c.transition) return null;
    EntityType type = EntityType.valueOf(c.config.text(c.state.type(), "mob"));
    Class<? extends Mob> clazz =
        Objects.requireNonNull(type.getEntityClass()).asSubclass(Mob.class);
    Mob mob =
        c.location()
            .getWorld()
            .spawn(
                spawnPoint(c),
                clazz,
                entity -> {
                  entity
                      .getPersistentDataContainer()
                      .set(eventKey, PersistentDataType.STRING, c.state.id().toString());
                  entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role);
                  entity
                      .getPersistentDataContainer()
                      .set(waveKey, PersistentDataType.INTEGER, wave);
                  entity.setPersistent(true);
                  entity.setRemoveWhenFarAway(false);
                  entity.setCanPickupItems(false);
                  entity.customName(
                      Component.text(
                          role.equals("BOSS")
                              ? c.config.text(c.state.type(), "name")
                              : "Dungeon Sentinel • Wave " + wave));
                  entity.setCustomNameVisible(true);
                  Objects.requireNonNull(entity.getAttribute(Attribute.MAX_HEALTH))
                      .setBaseValue(health);
                  entity.setHealth(health);
                  ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
                  var meta = helmet.getItemMeta();
                  meta.setUnbreakable(true);
                  helmet.setItemMeta(meta);
                  if (entity.getEquipment() != null) {
                    entity.getEquipment().setHelmet(helmet);
                    entity.getEquipment().setHelmetDropChance(0);
                  }
                });
    if (!mob.isValid()) {
      cancel(c, "Event mob spawn was denied.");
      return null;
    }
    var row = new EventStore.Mob(mob.getUniqueId(), role, wave, 0);
    c.actors.put(row.id(), row);
    store
        .mob(c.state.id(), row.id(), role, wave)
        .exceptionally(
            e -> {
              failed(e);
              return null;
            });
    return mob;
  }

  public void boss(Context c) {
    if (c.state.health() <= 0) {
      c.handler.defeated(c);
      return;
    }
    Mob boss =
        c.actors.values().stream()
            .filter(r -> r.role().equals("BOSS") && r.state() == 0)
            .map(r -> Bukkit.getEntity(r.id()))
            .filter(Mob.class::isInstance)
            .map(Mob.class::cast)
            .findFirst()
            .orElse(null);
    if (boss == null) boss = spawn(c, "BOSS", c.state.stage(), c.state.health());
    if (boss == null) return;
    double health = Math.min(c.state.health(), boss.getHealth());
    if (boss.getHealth() > health) boss.setHealth(health);
    c.state = c.state.phase(c.state.status(), c.state.stage(), health);
    target(c, boss);
    long now = System.currentTimeMillis();
    if (now >= c.special) {
      c.special = now + 1000 * c.config.number(c.state.type(), "special-interval-seconds");
      effects(c, Sound.ENTITY_EVOKER_CAST_SPELL, Particle.SWEEP_ATTACK);
      for (Player p : c.location().getWorld().getPlayers())
        if (eligible(p)
            && inside(c, p.getLocation())
            && p.getLocation().distanceSquared(boss.getLocation()) < 16)
          p.damage(c.config.decimal(c.state.type(), "special-damage"), boss);
    }
  }

  private void target(Context c, Mob mob) {
    if (mob == null) return;
    Player player =
        c.location().getWorld().getPlayers().stream()
            .filter(p -> eligible(p) && inside(c, p.getLocation()))
            .min(
                java.util.Comparator.comparingDouble(
                    p -> p.getLocation().distanceSquared(mob.getLocation())))
            .orElse(null);
    mob.setTarget(player);
  }

  public void dungeon(Context c) {
    int waves = (int) c.config.number(c.state.type(), "waves"),
        size = (int) c.config.number(c.state.type(), "mobs-per-wave");
    if (c.state.stage() == 0) {
      phase(c, "ACTIVE", 1, c.state.health(), List.of());
      return;
    }
    if (c.state.stage() > waves) {
      boss(c);
      return;
    }
    int stage = c.state.stage();
    long dead =
        c.actors.values().stream()
            .filter(r -> r.role().equals("WAVE") && r.wave() == stage && r.state() == 1)
            .count();
    List<Mob> alive =
        c.actors.values().stream()
            .filter(r -> r.role().equals("WAVE") && r.wave() == stage && r.state() == 0)
            .map(r -> Bukkit.getEntity(r.id()))
            .filter(Mob.class::isInstance)
            .map(Mob.class::cast)
            .toList();
    if (dead >= size) {
      phase(c, "ACTIVE", stage + 1, c.state.health(), List.of());
      return;
    }
    for (int i = alive.size(); i < size - dead; i++) target(c, spawn(c, "WAVE", stage, 20));
    for (Mob mob : alive) target(c, mob);
    if (c.config.yaml().getBoolean("types.DUNGEON.trap") && System.currentTimeMillis() >= c.trap) {
      c.trap = System.currentTimeMillis() + 5000;
      for (Player player : c.location().getWorld().getPlayers())
        if (eligible(player)
            && inside(c, player.getLocation())
            && !player.hasPotionEffect(PotionEffectType.SLOWNESS))
          player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
    }
  }

  private List<EventStore.Award> participation(Context c) {
    List<EventStore.Award> out = new ArrayList<>();
    double minimum = c.config.yaml().getDouble("minimum-damage");
    for (var entry : c.damage.entrySet())
      if (entry.getValue() >= minimum) {
        UUID owner = entry.getKey();
        out.add(
            new EventStore.Award(
                "event:" + c.state.id() + ":" + owner + ":participation",
                c.state.id(),
                owner,
                "KEY",
                c.config.text(c.state.type(), "key-tier"),
                null,
                null,
                0));
        cash(
            out,
            c,
            owner,
            "participation-cash",
            c.config.yaml().getLong("economy.participation-money"));
      }
    return out;
  }

  private void cash(
      List<EventStore.Award> out, Context c, UUID owner, String purpose, long amount) {
    if (amount > 0)
      out.add(
          new EventStore.Award(
              "event:" + c.state.id() + ":" + owner + ":" + purpose,
              c.state.id(),
              owner,
              "CASH",
              null,
              null,
              null,
              amount));
  }

  private EventStore.Award item(Context c, UUID owner, String purpose) {
    var tier = LootRules.Tier.valueOf(c.config.text(c.state.type(), "reward-tier"));
    var parcel = loot().prepareReward(owner, tier);
    return new EventStore.Award(
        "event:" + c.state.id() + ":" + purpose,
        c.state.id(),
        owner,
        "ITEM",
        tier.name(),
        parcel.id(),
        parcel.payload(),
        0);
  }

  public void bossFinished(Context c, boolean dungeon) {
    if (c.transition) return;
    List<EventStore.Award> awards = participation(c);
    UUID winner = EventRules.winner(c.damage, c.config.yaml().getDouble("minimum-damage"));
    if (dungeon) {
      unlock(c, awards);
      return;
    }
    if (winner != null) {
      awards.add(item(c, winner, "boss-winner"));
      cash(awards, c, winner, "winner-cash", c.config.yaml().getLong("economy.winner-money"));
    }
    finish(c, winner, awards, false);
  }

  private void finish(Context c, UUID winner, List<EventStore.Award> awards, boolean preserve) {
    if (c.transition) return;
    c.transition = true;
    long cleanup =
        System.currentTimeMillis() + c.config.yaml().getLong("cleanup-delay-seconds") * 1000;
    store
        .finish(c.state, winner, awards, Map.copyOf(c.damage), cleanup, preserve)
        .whenComplete(
            (changed, error) ->
                main(
                    () -> {
                      c.transition = false;
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      if (changed) {
                        removeCombat(c);
                        store
                            .active()
                            .whenComplete(
                                (state, e) ->
                                    main(
                                        () -> {
                                          if (e != null) {
                                            failed(e);
                                            return;
                                          }
                                          if (current == c && state != null) c.state = state;
                                        }));
                        announce(
                            "event-finished",
                            "{event} ended at {coordinates}; earned rewards are queued.",
                            c);
                      }
                    }));
  }

  private void cancel(Context c, String message) {
    plugin.getLogger().info(message);
    finish(c, null, List.of(), false);
  }

  private void removeCombat(Context c) {
    if (c.visual != null) {
      c.visual.remove();
      c.visual = null;
    }
    for (UUID id : c.actors.keySet()) {
      Entity entity = Bukkit.getEntity(id);
      if (entity != null && owned(c, entity)) entity.remove();
    }
    for (Entity entity : c.location().getWorld().getNearbyEntities(c.location(), 12, 40, 12))
      if (owned(c, entity)) entity.remove();
    for (UUID id : c.projectiles) {
      Entity e = Bukkit.getEntity(id);
      if (e != null) e.remove();
    }
    c.projectiles.clear();
  }

  private void cleanup(Context c) {
    if (c.transition) return;
    c.transition = true;
    removeCombat(c);
    int preserved = 0;
    if (!c.state.preserve())
      for (var b : c.blocks) {
        var block = c.location().getWorld().getBlockAt(b.x(), b.y(), b.z());
        if (block.getBlockData().getAsString().equals(b.placed())
            && (!(block.getState() instanceof Container container)
                || container.getInventory().isEmpty()))
          block.setBlockData(Bukkit.createBlockData(b.original()), false);
        else if (!block.getBlockData().getAsString().equals(b.original())) preserved++;
      }
    if (preserved > 0)
      plugin
          .getLogger()
          .warning(
              "Event cleanup retained "
                  + preserved
                  + " modified/occupied blocks; manifest retained: "
                  + c.state.id());
    store
        .archive(c.state.id())
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      c.transition = false;
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      if (c.bar != null) c.bar.removeAll();
                      loot().releaseEventArea(c.state.id());
                      if (current == c) current = null;
                    }));
  }

  private void recoverRewards() {
    if (outbox || loot() == null) return;
    outbox = true;
    store
        .money()
        .thenCompose(v -> store.pending())
        .whenComplete(
            (awards, error) ->
                main(
                    () -> {
                      if (error != null) {
                        outbox = false;
                        failed(error);
                        return;
                      }
                      List<CompletableFuture<?>> futures = new ArrayList<>();
                      for (var award : awards) {
                        CompletableFuture<Boolean> grant =
                            award.kind().equals("KEY")
                                ? loot()
                                    .grantKey(
                                        award.owner(),
                                        LootRules.Tier.valueOf(award.tier()),
                                        award.id())
                                : loot()
                                    .enqueuePrepared(
                                        new LootStore.Parcel(
                                            award.receipt(),
                                            award.owner(),
                                            award.payload(),
                                            "WAITING",
                                            0),
                                        award.id());
                        futures.add(
                            grant
                                .handle(
                                    (created, e) -> {
                                      if (e != null) failed(e);
                                      return e == null;
                                    })
                                .thenCompose(ok -> store.acknowledge(award.id(), ok)));
                      }
                      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                          .whenComplete(
                              (v, e) ->
                                  main(
                                      () -> {
                                        outbox = false;
                                        if (e != null) failed(e);
                                      }));
                    }));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    Context c = current;
    if (c == null
        || !c.loaded
        || e.getHand() != EquipmentSlot.HAND
        || !e.getAction().isRightClick()
        || e.getClickedBlock() == null) return;
    var block = e.getClickedBlock();
    if (!block.getWorld().getUID().equals(c.state.world())) return;
    var chest =
        c.chests.stream()
            .filter(
                ch -> ch.x() == block.getX() && ch.y() == block.getY() && ch.z() == block.getZ())
            .findFirst()
            .orElse(null);
    if (chest == null) return;
    e.setCancelled(true);
    if (!c.state.status().equals("LOOT")
        || c.transition
        || chest.owner() != null
        || !eligible(e.getPlayer())
        || !loot().eventWilderness(c.location())) return;
    for (var guard : Bukkit.getServicesManager().getRegistrations(VaultProtection.class))
      if (!guard.getProvider().canClaim(e.getPlayer(), block.getLocation())) return;
    UUID owner = e.getPlayer().getUniqueId();
    List<EventStore.Award> awards = new ArrayList<>();
    awards.add(item(c, owner, "chest:" + chest.index()));
    if (c.state.type() == EventRules.Type.SUPPLY_DROP)
      awards.add(
          new EventStore.Award(
              "event:" + c.state.id() + ":drop-key",
              c.state.id(),
              owner,
              "KEY",
              c.config.text(c.state.type(), "key-tier"),
              null,
              null,
              0));
    cash(
        awards,
        c,
        owner,
        "chest-cash:" + chest.index(),
        c.config.yaml().getLong("economy.winner-money"));
    c.transition = true;
    boolean preserve =
        c.state.type() == EventRules.Type.PIRATE_SHIP
            && c.config.yaml().getBoolean("types.PIRATE_SHIP.persist-structure");
    store
        .claim(
            c.state,
            chest.index(),
            owner,
            awards,
            System.currentTimeMillis() + 1000 * c.config.yaml().getLong("cleanup-delay-seconds"),
            preserve)
        .whenComplete(
            (claimed, error) ->
                main(
                    () -> {
                      c.transition = false;
                      if (error != null) {
                        failed(error);
                        return;
                      }
                      if (claimed) {
                        c.chests.set(
                            c.chests.indexOf(chest),
                            new EventStore.Chest(
                                chest.index(), chest.x(), chest.y(), chest.z(), owner));
                        e.getPlayer()
                            .sendMessage(
                                "Event treasure reserved; rewards deliver through your mailbox.");
                        if (c.state.type() == EventRules.Type.PIRATE_SHIP)
                          announce(
                              "event-discovery",
                              "A pirate treasure was discovered at {coordinates}!",
                              c);
                        store
                            .active()
                            .whenComplete(
                                (state, x) ->
                                    main(
                                        () -> {
                                          if (x != null) failed(x);
                                          else if (current == c && state != null) c.state = state;
                                        }));
                      }
                    }));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void attackBounds(EntityDamageByEntityEvent e) {
    Context c = current;
    if (c == null) return;
    Entity source = e.getDamager();
    if (owned(c, source)
        && (!c.state.status().equals("ACTIVE")
            || !(e.getEntity() instanceof Player p)
            || !eligible(p)
            || !inside(c, p.getLocation()))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    Context c = current;
    if (c == null
        || !c.state.status().equals("ACTIVE")
        || !(e.getEntity() instanceof Mob mob)
        || !owned(c, mob)) return;
    var row = c.actors.get(mob.getUniqueId());
    if (row == null) return;
    double actual = Math.min(mob.getHealth(), Math.max(0, e.getFinalDamage()));
    if (row.role().equals("BOSS"))
      c.state =
          c.state.phase(c.state.status(), c.state.stage(), Math.max(0, mob.getHealth() - actual));
    if (e instanceof EntityDamageByEntityEvent hit) {
      Player player =
          hit.getDamager() instanceof Player p
              ? p
              : hit.getDamager() instanceof Projectile projectile
                      && projectile.getShooter() instanceof Player p
                  ? p
                  : null;
      if (player != null
          && eligible(player)
          && inside(c, player.getLocation())
          && (c.damage.containsKey(player.getUniqueId())
              || c.damage.size() < c.config.yaml().getInt("maximum-participants")))
        c.damage.merge(player.getUniqueId(), actual, Double::sum);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void death(EntityDeathEvent e) {
    Context c = current;
    if (c == null || !owned(c, e.getEntity())) return;
    e.getDrops().clear();
    e.setDroppedExp(0);
    if (System.currentTimeMillis() >= c.state.expires()) {
      cancel(c, "Boss died after event expiration; rewards not reserved.");
      return;
    }
    var row = c.actors.get(e.getEntity().getUniqueId());
    if (row == null || row.state() != 0) return;
    c.actors.put(row.id(), new EventStore.Mob(row.id(), row.role(), row.wave(), 1));
    if (row.role().equals("BOSS")) {
      c.state = c.state.phase(c.state.status(), c.state.stage(), 0);
      c.handler.defeated(c);
    } else
      store
          .death(row.id(), c.state.id(), Map.copyOf(c.damage))
          .exceptionally(
              error -> {
                failed(error);
                return null;
              });
  }

  @EventHandler(ignoreCancelled = true)
  public void launch(ProjectileLaunchEvent e) {
    Context c = current;
    if (c != null && e.getEntity().getShooter() instanceof Entity shooter && owned(c, shooter)) {
      e.getEntity()
          .getPersistentDataContainer()
          .set(eventKey, PersistentDataType.STRING, c.state.id().toString());
      e.getEntity().setPersistent(false);
      c.projectiles.add(e.getEntity().getUniqueId());
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void target(EntityTargetLivingEntityEvent e) {
    Context c = current;
    if (c != null
        && owned(c, e.getEntity())
        && (e.getTarget() != null
            && (!(e.getTarget() instanceof Player p)
                || !eligible(p)
                || !inside(c, p.getLocation())))) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void transform(EntityTransformEvent e) {
    if (current != null && owned(current, e.getEntity())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void grief(EntityChangeBlockEvent e) {
    if (current != null && owned(current, e.getEntity())) e.setCancelled(true);
  }

  public boolean protectedBlock(org.bukkit.block.Block block) {
    Context c = current;
    return c != null
        && block.getWorld().getUID().equals(c.state.world())
        && c.blocks.stream()
            .anyMatch(b -> b.x() == block.getX() && b.y() == block.getY() && b.z() == block.getZ());
  }

  @EventHandler(ignoreCancelled = true)
  public void breaking(BlockBreakEvent e) {
    if (protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void placing(BlockPlaceEvent e) {
    if (protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void explode(EntityExplodeEvent e) {
    e.blockList().removeIf(this::protectedBlock);
  }

  @EventHandler(ignoreCancelled = true)
  public void explode(BlockExplodeEvent e) {
    e.blockList().removeIf(this::protectedBlock);
  }

  @EventHandler(ignoreCancelled = true)
  public void burning(BlockBurnEvent e) {
    if (protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void ignite(BlockIgniteEvent e) {
    if (protectedBlock(e.getBlock())
        || current != null
            && e.getIgnitingEntity() != null
            && owned(current, e.getIgnitingEntity())) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void extend(BlockPistonExtendEvent e) {
    if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void retract(BlockPistonRetractEvent e) {
    if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
  }

  private boolean eventInventory(Inventory inventory) {
    if (inventory instanceof DoubleChestInventory both)
      return eventInventory(both.getLeftSide()) || eventInventory(both.getRightSide());
    return inventory.getLocation() != null && protectedBlock(inventory.getLocation().getBlock());
  }

  @EventHandler
  public void inventory(InventoryOpenEvent e) {
    if (eventInventory(e.getInventory())) e.setCancelled(true);
  }

  @EventHandler
  public void hopper(InventoryMoveItemEvent e) {
    if (eventInventory(e.getSource()) || eventInventory(e.getDestination())) e.setCancelled(true);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    boolean admin = command.getName().equals("eventadmin");
    if (!sender.hasPermission(admin ? "polder.events.admin" : "polder.events.view")) {
      sender.sendMessage("No permission.");
      return true;
    }
    if (!ready) {
      sender.sendMessage("World-event persistence is initializing.");
      return true;
    }
    try {
      if (admin) {
        if (args.length == 2 && args[0].equalsIgnoreCase("start"))
          start(sender, EventRules.Type.valueOf(args[1].toUpperCase(Locale.ROOT)));
        else if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
          if (starting || current != null && current.transition)
            throw new IllegalArgumentException("State transition in progress; retry shortly");
          if (current == null) sender.sendMessage("No active event.");
          else finish(current, null, List.of(), false);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
          if (current != null || starting)
            throw new IllegalArgumentException("Stop the current event before reload");
          EventConfig candidate = EventConfig.load(plugin);
          Instant planned =
              EventRules.next(Instant.now(), candidate.yaml().getLong("interval-seconds"));
          starting = true;
          store
              .setting("next", planned.toString())
              .whenComplete(
                  (v, e) ->
                      main(
                          () -> {
                            starting = false;
                            if (e != null) {
                              failed(e);
                              sender.sendMessage("Reload failed; previous settings retained.");
                              return;
                            }
                            config = candidate;
                            next = planned;
                            messages =
                                YamlConfiguration.loadConfiguration(
                                    new File(plugin.getDataFolder(), "messages.yml"));
                            sender.sendMessage("World events reloaded.");
                          }));
        } else sender.sendMessage("/eventadmin start <event> | stop | reload");
      } else {
        String sub = args.length == 0 ? "overview" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("active"))
          sender.sendMessage(
              current == null
                  ? "No active event."
                  : current.state.type()
                      + " • "
                      + current.state.status()
                      + " • "
                      + (current.location() == null ? "world unavailable" : coords(current))
                      + " • stage="
                      + current.state.stage());
        else if (sub.equals("next"))
          sender.sendMessage(
              "Next random event: "
                  + (next == null
                      ? "loading"
                      : next.atZone(ZoneId.of(config.yaml().getString("timezone")))));
        else if (sub.equals("top"))
          result(
              sender,
              store.statistics(true),
              rows -> {
                sender.sendMessage("Boss contributors (UUID : damage)");
                rows.forEach(sender::sendMessage);
              });
        else {
          sender.sendMessage(
              "World events: " + handlers.keySet() + ". /events active | next | top");
          result(sender, store.statistics(false), rows -> rows.forEach(sender::sendMessage));
        }
      }
    } catch (Exception e) {
      sender.sendMessage("Unable to complete: " + e.getMessage());
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    boolean admin = command.getName().equals("eventadmin");
    if (!sender.hasPermission(admin ? "polder.events.admin" : "polder.events.view"))
      return List.of();
    List<String> choices =
        args.length == 1
            ? admin ? List.of("start", "stop", "reload") : List.of("active", "next", "top")
            : admin && args.length == 2 && args[0].equalsIgnoreCase("start")
                ? Arrays.stream(EventRules.Type.values()).map(Enum::name).toList()
                : List.of();
    return choices.stream()
        .filter(
            s ->
                s.toLowerCase(Locale.ROOT)
                    .startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
        .toList();
  }

  @Override
  public void close() {
    closed = true;
    task.cancel();
    Context c = current;
    if (c != null) {
      store
          .checkpoint(c.state, Map.copyOf(c.damage))
          .exceptionally(
              error -> {
                failed(error);
                return null;
              });
      if (c.bar != null) c.bar.removeAll();
      if (c.visual != null) c.visual.remove();
      if (loot() != null) loot().releaseEventArea(c.state.id());
    }
  }
}
