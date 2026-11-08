package nl.poldersmp.core.loot;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.*;
import org.bukkit.util.BoundingBox;

final class Vaults implements AutoCloseable {
  private final Loot loot;
  private final Map<UUID, LootStore.Vault> active = new HashMap<>();
  private final Map<UUID, List<LootStore.BlockPlan>> plans = new HashMap<>();
  private final Set<UUID> cleaning = new HashSet<>();
  private boolean searching, closed;
  private long next;
  private final org.bukkit.scheduler.BukkitTask task;

  Vaults(Loot loot) {
    this.loot = loot;
    recover();
    loot.store
        .state("next-vault")
        .whenComplete(
            (value, error) ->
                loot.main(
                    () -> {
                      if (error != null) loot.failed(error);
                      next =
                          value == null
                              ? System.currentTimeMillis() + interval()
                              : Long.parseLong(value);
                      if (value == null) persistNext();
                    }));
    task = Bukkit.getScheduler().runTaskTimer(loot.plugin, this::tick, 20, 20);
  }

  private long interval() {
    return loot.config.vaults().getLong("spawn-interval-seconds") * 1000;
  }

  private void persistNext() {
    loot.store
        .state("next-vault", Long.toString(next))
        .exceptionally(
            e -> {
              loot.failed(e);
              return null;
            });
  }

  void reload() {
    next = System.currentTimeMillis() + interval();
    persistNext();
  }

  private void recover() {
    loot.store
        .vaults()
        .whenComplete(
            (rows, error) ->
                loot.main(
                    () -> {
                      if (error != null) {
                        loot.failed(error);
                        return;
                      }
                      for (var row : rows) {
                        active.put(row.id(), row);
                        loot.store
                            .blocks(row.id())
                            .whenComplete(
                                (blocks, e) ->
                                    loot.main(
                                        () -> {
                                          if (e != null) {
                                            loot.failed(e);
                                            return;
                                          }
                                          plans.put(row.id(), blocks);
                                          World world = Bukkit.getWorld(row.world());
                                          if (world == null) {
                                            loot.plugin
                                                .getLogger()
                                                .warning(
                                                    "Vault restoration waiting for unavailable"
                                                        + " world "
                                                        + row.world());
                                            return;
                                          }
                                          load(world, row.x(), row.z())
                                              .whenComplete(
                                                  (v, f) ->
                                                      loot.main(
                                                          () -> {
                                                            if (f != null) {
                                                              loot.failed(f);
                                                              return;
                                                            }
                                                            boolean intact =
                                                                !blocks.isEmpty()
                                                                    && blocks.stream()
                                                                        .allMatch(
                                                                            b ->
                                                                                world
                                                                                    .getBlockAt(
                                                                                        b.x(),
                                                                                        b.y(),
                                                                                        b.z())
                                                                                    .getBlockData()
                                                                                    .getAsString()
                                                                                    .equals(
                                                                                        b
                                                                                            .placed()));
                                                            if (!row.state().equals("ACTIVE")
                                                                || row.expires()
                                                                    <= System.currentTimeMillis()
                                                                || !intact) cleanup(row);
                                                          }));
                                        }));
                      }
                    }));
  }

  private final Map<String, Integer> tickets = new HashMap<>();
  private final Set<org.bukkit.scheduler.BukkitTask> ticketTasks = new HashSet<>();

  private CompletableFuture<Void> load(World world, int x, int z) {
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++) {
        final int cx = (x >> 4) + dx, cz = (z >> 4) + dz;
        futures.add(
            world
                .getChunkAtAsync(cx, cz, loot.config.vaults().getBoolean("allow-new-chunks", true))
                .thenCompose(
                    chunk -> {
                      CompletableFuture<Void> loaded = new CompletableFuture<>();
                      loot.main(
                          () -> {
                            if (chunk == null) {
                              loaded.completeExceptionally(
                                  new IllegalStateException("Chunk generation disabled"));
                              return;
                            }
                            String key = world.getUID() + ":" + cx + ":" + cz;
                            world.addPluginChunkTicket(cx, cz, loot.plugin);
                            tickets.merge(key, 1, Integer::sum);
                            org.bukkit.scheduler.BukkitTask[] release = {null};
                            release[0] =
                                Bukkit.getScheduler()
                                    .runTaskLater(
                                        loot.plugin,
                                        () -> {
                                          ticketTasks.remove(release[0]);
                                          Integer count = tickets.get(key);
                                          if (count != null && count <= 1) {
                                            tickets.remove(key);
                                            world.removePluginChunkTicket(cx, cz, loot.plugin);
                                          } else if (count != null) tickets.put(key, count - 1);
                                        },
                                        200);
                            ticketTasks.add(release[0]);
                            loaded.complete(null);
                          });
                      return loaded;
                    }));
      }
    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
  }

  private boolean loaded(World world, int x, int z) {
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++)
        if (!world.isChunkLoaded((x >> 4) + dx, (z >> 4) + dz)) return false;
    return true;
  }

  private void tick() {
    long now = System.currentTimeMillis();
    for (var row : new ArrayList<>(active.values())) if (row.expires() <= now) cleanup(row);
    if (next == 0 || now < next || searching || !loot.config.vaults().getBoolean("enabled")) return;
    next = now + interval();
    persistNext();
    List<LootRules.Weighted<LootRules.Tier>> tiers = new ArrayList<>();
    for (var tier : LootRules.Tier.values())
      tiers.add(
          new LootRules.Weighted<>(tier, loot.config.vaults().getLong("rarity-weights." + tier)));
    spawn(
        null,
        LootRules.select(tiers, ThreadLocalRandom.current().nextLong(LootRules.total(tiers))));
  }

  private boolean guardsAvailable() {
    boolean protectedServer =
        loot.config.vaults().getStringList("protection-plugins").stream()
            .anyMatch(name -> Bukkit.getPluginManager().isPluginEnabled(name));
    return !protectedServer
        || !Bukkit.getServicesManager().getRegistrations(VaultProtection.class).isEmpty();
  }

  private boolean wilderness(World world, int x, int y, int z) {
    BoundingBox box =
        new BoundingBox(x - 8, world.getMinHeight(), z - 8, x + 9, world.getMaxHeight(), z + 9);
    return guardsAvailable()
        && Bukkit.getServicesManager().getRegistrations(VaultProtection.class).stream()
            .allMatch(provider -> provider.getProvider().wilderness(world, box));
  }

  private static final Set<Material> GROUND =
      Set.of(
          Material.GRASS_BLOCK,
          Material.DIRT,
          Material.STONE,
          Material.SAND,
          Material.RED_SAND,
          Material.GRAVEL,
          Material.PODZOL,
          Material.SNOW_BLOCK);
  private static final Set<Material> NATURAL =
      Set.of(
          Material.AIR,
          Material.CAVE_AIR,
          Material.GRASS_BLOCK,
          Material.DIRT,
          Material.COARSE_DIRT,
          Material.STONE,
          Material.DEEPSLATE,
          Material.ANDESITE,
          Material.DIORITE,
          Material.GRANITE,
          Material.SAND,
          Material.RED_SAND,
          Material.SANDSTONE,
          Material.RED_SANDSTONE,
          Material.GRAVEL,
          Material.PODZOL,
          Material.SNOW_BLOCK,
          Material.SNOW,
          Material.SHORT_GRASS,
          Material.TALL_GRASS,
          Material.DANDELION,
          Material.POPPY,
          Material.COAL_ORE,
          Material.IRON_ORE,
          Material.COPPER_ORE);

  private boolean eligible(World world, int x, int y, int z) {
    return eligible(world, x, y, z, false);
  }

  private boolean eligible(World world, int x, int y, int z, boolean ocean) {
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++)
        if (!world.isChunkLoaded((x >> 4) + dx, (z >> 4) + dz)) return false;
    if (world.getEnvironment() != World.Environment.NORMAL) return false;
    boolean border = true,
        terrain = true,
        uninhabited = true,
        structures = true,
        containers = true,
        players = true,
        air = true;
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++) {
        Chunk chunk = world.getChunkAt((x >> 4) + dx, (z >> 4) + dz);
        uninhabited &= chunk.getInhabitedTime() == 0;
        structures &= chunk.getStructures().isEmpty();
        containers &= chunk.getTileEntities().length == 0;
      }
    for (int dx : new int[] {-8, 8})
      for (int dz : new int[] {-8, 8})
        border &= world.getWorldBorder().isInside(new Location(world, x + dx, y, z + dz));
    for (Player player : world.getPlayers())
      if (player.getLocation().distanceSquared(new Location(world, x, y, z)) < 128 * 128)
        players = false;
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++) {
        terrain &=
            ocean
                ? world.getBlockAt(x + dx, y - 1, z + dz).getType() == Material.WATER
                : GROUND.contains(world.getBlockAt(x + dx, y - 1, z + dz).getType());
        for (int dy = 0; dy <= 2; dy++)
          air &= world.getBlockAt(x + dx, y + dy, z + dz).getType().isAir();
      }
    for (int dx = -8; dx <= 8; dx++)
      for (int dz = -8; dz <= 8; dz++)
        for (int dy = -3; dy <= 8; dy++)
          if (!(ocean && world.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.WATER)
              && !NATURAL.contains(world.getBlockAt(x + dx, y + dy, z + dz).getType()))
            terrain = false;
    if (ocean && !world.getBiome(x, y, z).getKey().getKey().contains("ocean")) return false;
    return new LootRules.Eligibility(
            true,
            border,
            terrain,
            uninhabited,
            structures,
            containers,
            players,
            wilderness(world, x, y, z),
            air)
        .allowed();
  }

  private List<LootStore.BlockPlan> blueprint(
      World world, int x, int y, int z, LootRules.Tier tier) {
    List<LootStore.BlockPlan> blocks = new ArrayList<>();
    Material accent =
        switch (tier) {
          case COMMON -> Material.MOSSY_COBBLESTONE;
          case RARE -> Material.COPPER_BLOCK;
          case EPIC -> Material.AMETHYST_BLOCK;
          case LEGENDARY -> Material.GOLD_BLOCK;
          case MYTHIC -> Material.CRYING_OBSIDIAN;
        };
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++) {
        if (dx == 0 && dz == 0) {
          plan(blocks, world, x, y, z, Material.BARREL);
          continue;
        }
        if (dx != 0 && dz != 0) {
          plan(blocks, world, x + dx, y, z + dz, Material.CHISELED_STONE_BRICKS);
          plan(blocks, world, x + dx, y + 1, z + dz, accent);
        }
      }
    return blocks;
  }

  private void plan(
      List<LootStore.BlockPlan> out, World world, int x, int y, int z, Material placed) {
    out.add(
        new LootStore.BlockPlan(
            x,
            y,
            z,
            world.getBlockAt(x, y, z).getBlockData().getAsString(),
            placed.createBlockData().getAsString()));
  }

  void spawn(CommandSender sender, LootRules.Tier tier) {
    if (!loot.config.vaults().getBoolean("enabled")
        || searching
        || active.size() >= loot.config.vaults().getInt("maximum-active")) {
      if (sender != null) sender.sendMessage("Vault spawning disabled, busy or at capacity.");
      return;
    }
    if (!guardsAvailable()) {
      if (sender != null)
        sender.sendMessage(
            "A VaultProtection adapter is required for the installed protection plugin.");
      return;
    }
    List<World> worlds =
        loot.config.vaults().getStringList("worlds").stream()
            .map(Bukkit::getWorld)
            .filter(Objects::nonNull)
            .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
            .toList();
    if (worlds.isEmpty()) {
      if (sender != null)
        sender.sendMessage("Configure an explicit overworld in vaults.yml worlds first.");
      return;
    }
    searching = true;
    attempt(sender, tier, worlds, loot.config.vaults().getInt("attempts"));
  }

  private void attempt(
      CommandSender sender, LootRules.Tier tier, List<World> worlds, int remaining) {
    if (closed) {
      searching = false;
      return;
    }
    if (remaining == 0) {
      searching = false;
      if (sender != null)
        sender.sendMessage(
            "No eligible untouched wilderness location found; nothing was overwritten.");
      return;
    }
    World world = worlds.get(ThreadLocalRandom.current().nextInt(worlds.size()));
    double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
    double distance =
        ThreadLocalRandom.current()
            .nextDouble(
                loot.config.vaults().getInt("spawn-min-radius"),
                loot.config.vaults().getInt("spawn-max-radius") + 1.0);
    int x = ((int) (world.getSpawnLocation().getX() + Math.cos(angle) * distance) >> 4) * 16 + 8,
        z = ((int) (world.getSpawnLocation().getZ() + Math.sin(angle) * distance) >> 4) * 16 + 8;
    if (!world.getWorldBorder().isInside(new Location(world, x, world.getSeaLevel(), z))) {
      attempt(sender, tier, worlds, remaining - 1);
      return;
    }
    load(world, x, z)
        .whenComplete(
            (v, error) ->
                loot.main(
                    () -> {
                      if (closed) return;
                      if (error != null) {
                        attempt(sender, tier, worlds, remaining - 1);
                        return;
                      }
                      int y =
                          world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                      if (y < world.getMinHeight() + 4
                          || y + 9 >= world.getMaxHeight()
                          || !eligible(world, x, y, z)) {
                        attempt(sender, tier, worlds, remaining - 1);
                        return;
                      }
                      List<LootStore.BlockPlan> blocks = blueprint(world, x, y, z, tier);
                      var vault =
                          new LootStore.Vault(
                              UUID.randomUUID(),
                              world.getUID(),
                              x,
                              y,
                              z,
                              tier,
                              "PLANNED",
                              System.currentTimeMillis()
                                  + loot.config.vaults().getLong("expiration-seconds") * 1000);
                      loot.store
                          .plan(vault, blocks)
                          .whenComplete(
                              (none, e) ->
                                  loot.main(
                                      () -> {
                                        if (e != null) {
                                          searching = false;
                                          loot.failed(e);
                                          return;
                                        }
                                        active.put(vault.id(), vault);
                                        plans.put(vault.id(), blocks);
                                        if (!eligible(world, x, y, z)) {
                                          searching = false;
                                          cleanup(vault);
                                          return;
                                        }
                                        for (var b : blocks) {
                                          if (!world
                                              .getBlockAt(b.x(), b.y(), b.z())
                                              .getBlockData()
                                              .getAsString()
                                              .equals(b.original())) {
                                            searching = false;
                                            cleanup(vault);
                                            return;
                                          }
                                        }
                                        for (var b : blocks)
                                          world
                                              .getBlockAt(b.x(), b.y(), b.z())
                                              .setBlockData(
                                                  Bukkit.createBlockData(b.placed()), false);
                                        loot.store
                                            .activate(vault.id())
                                            .whenComplete(
                                                (done, f) ->
                                                    loot.main(
                                                        () -> {
                                                          searching = false;
                                                          if (f != null) {
                                                            loot.failed(f);
                                                            cleanup(vault);
                                                            return;
                                                          }
                                                          active.put(
                                                              vault.id(),
                                                              new LootStore.Vault(
                                                                  vault.id(),
                                                                  vault.world(),
                                                                  x,
                                                                  y,
                                                                  z,
                                                                  tier,
                                                                  "ACTIVE",
                                                                  vault.expires()));
                                                          if (sender != null)
                                                            sender.sendMessage(
                                                                tier
                                                                    + " vault placed at "
                                                                    + world.getName()
                                                                    + " "
                                                                    + x
                                                                    + " "
                                                                    + y
                                                                    + " "
                                                                    + z);
                                                          loot.effects(
                                                              new Location(world, x, y, z), true);
                                                        }));
                                      }));
                    }));
  }

  boolean protectedBlock(Block block) {
    for (var entry : plans.entrySet()) {
      LootStore.Vault row = active.get(entry.getKey());
      if (row != null && row.world().equals(block.getWorld().getUID()))
        for (var b : entry.getValue())
          if (b.x() == block.getX() && b.y() == block.getY() && b.z() == block.getZ()) return true;
    }
    return false;
  }

  boolean interact(Player player, Block block) {
    var row =
        active.values().stream()
            .filter(
                v ->
                    v.world().equals(block.getWorld().getUID())
                        && v.x() == block.getX()
                        && v.y() == block.getY()
                        && v.z() == block.getZ())
            .findFirst()
            .orElse(null);
    if (row == null) return false;
    if (!loot.config.vaults().getBoolean("enabled")
        || !row.state().equals("ACTIVE")
        || !player.hasPermission("polder.vaults.use")) return true;
    if (!wilderness(block.getWorld(), row.x(), row.y(), row.z())) return true;
    for (var provider : Bukkit.getServicesManager().getRegistrations(VaultProtection.class))
      if (!provider.getProvider().canClaim(player, block.getLocation())) return true;
    var definition = loot.select(row.tier(), true);
    long now = System.currentTimeMillis();
    var parcel = loot.reward(player.getUniqueId(), definition, now);
    loot.store
        .claim(
            row,
            parcel,
            definition.id(),
            now,
            loot.config.vaults().getLong("claim-cooldown-seconds") * 1000)
        .whenComplete(
            (claimed, error) ->
                loot.main(
                    () -> {
                      if (error != null) {
                        loot.failed(error);
                        return;
                      }
                      if (!claimed) {
                        player.sendMessage(
                            "Vault already claimed, expired, or your discovery cooldown is"
                                + " active.");
                        return;
                      }
                      active.put(
                          row.id(),
                          new LootStore.Vault(
                              row.id(),
                              row.world(),
                              row.x(),
                              row.y(),
                              row.z(),
                              row.tier(),
                              "CLAIMED",
                              row.expires()));
                      if (loot.config.vaults().getBoolean("trap", true) && player.isOnline()) {
                        if (!player.hasPotionEffect(PotionEffectType.SLOWNESS))
                          player.addPotionEffect(
                              new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                        if (!player.hasPotionEffect(PotionEffectType.BLINDNESS))
                          player.addPotionEffect(
                              new PotionEffect(PotionEffectType.BLINDNESS, 20, 0));
                      }
                      Bukkit.broadcast(
                          net.kyori.adventure.text.Component.text(
                              player.getName() + " discovered a " + row.tier() + " secret vault!"));
                      loot.effects(block.getLocation(), true);
                      loot.announce(player, row.tier(), definition);
                      cleanup(row);
                    }));
    return true;
  }

  private void cleanup(LootStore.Vault row) {
    if (!cleaning.add(row.id())) return;
    World world = Bukkit.getWorld(row.world());
    if (world == null) {
      cleaning.remove(row.id());
      return;
    }
    List<LootStore.BlockPlan> blocks = plans.get(row.id());
    if (blocks == null) {
      cleaning.remove(row.id());
      return;
    }
    loot.store
        .beginCleanup(row.id())
        .thenCompose(v -> load(world, row.x(), row.z()))
        .whenComplete(
            (v, error) ->
                loot.main(
                    () -> {
                      if (error != null) {
                        cleaning.remove(row.id());
                        loot.failed(error);
                        return;
                      }
                      if (!loaded(world, row.x(), row.z())) {
                        cleaning.remove(row.id());
                        return;
                      }
                      int preserved = 0;
                      for (var b : blocks) {
                        Block block = world.getBlockAt(b.x(), b.y(), b.z());
                        if (block.getBlockData().getAsString().equals(b.placed())
                            && (!(block.getState() instanceof Container container)
                                || container.getInventory().isEmpty()))
                          block.setBlockData(Bukkit.createBlockData(b.original()), false);
                        else if (!block.getBlockData().getAsString().equals(b.original()))
                          preserved++;
                      }
                      if (preserved > 0)
                        loot.plugin
                            .getLogger()
                            .warning(
                                "Vault cleanup preserved "
                                    + preserved
                                    + " changed/occupied blocks; manifest retained: "
                                    + row.id());
                      loot.store
                          .removed(row.id())
                          .whenComplete(
                              (done, e) ->
                                  loot.main(
                                      () -> {
                                        cleaning.remove(row.id());
                                        if (e != null) {
                                          loot.failed(e);
                                          return;
                                        }
                                        active.remove(row.id());
                                        plans.remove(row.id());
                                      }));
                    }));
  }

  void command(CommandSender sender, String command, String[] args) {
    if (command.equals("vaults")) {
      loot.result(
          sender,
          loot.store.top("VAULT"),
          rows -> {
            sender.sendMessage(
                "Secret vaults: "
                    + active.size()
                    + " active/restoring. Discovery leaderboard (UUID : claims):");
            rows.forEach(sender::sendMessage);
          });
      return;
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("spawn"))
      spawn(sender, LootRules.Tier.valueOf(args[1].toUpperCase(Locale.ROOT)));
    else if (args.length == 1
        && args[0].equalsIgnoreCase("remove")
        && sender instanceof Player player) {
      var row =
          active.values().stream()
              .filter(
                  v ->
                      v.world().equals(player.getWorld().getUID())
                          && player
                                  .getLocation()
                                  .distanceSquared(
                                      new Location(player.getWorld(), v.x(), v.y(), v.z()))
                              <= 16 * 16)
              .min(
                  java.util.Comparator.comparingDouble(
                      v ->
                          player
                              .getLocation()
                              .distanceSquared(
                                  new Location(player.getWorld(), v.x(), v.y(), v.z()))))
              .orElseThrow(() -> new IllegalArgumentException("Stand within 16 blocks of a vault"));
      cleanup(row);
      sender.sendMessage("Conditional vault cleanup queued.");
    } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
      loot.reload();
      sender.sendMessage("Loot settings reloaded.");
    } else sender.sendMessage("/vaultadmin spawn <rarity> | remove | reload");
  }

  CompletableFuture<Void> loadArea(Location location) {
    return load(location.getWorld(), location.getBlockX(), location.getBlockZ());
  }

  boolean approveArea(Location location) {
    return wilderness(
        location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private record Held(World world, int x, int z) {}

  private final Map<UUID, List<Held>> heldAreas = new HashMap<>();

  void holdArea(UUID owner, Location location) {
    if (heldAreas.containsKey(owner)) return;
    List<Held> held = new ArrayList<>();
    World world = location.getWorld();
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++) {
        int x = (location.getBlockX() >> 4) + dx, z = (location.getBlockZ() >> 4) + dz;
        world.addPluginChunkTicket(x, z, loot.plugin);
        tickets.merge(world.getUID() + ":" + x + ":" + z, 1, Integer::sum);
        held.add(new Held(world, x, z));
      }
    heldAreas.put(owner, held);
  }

  void releaseArea(UUID owner) {
    var held = heldAreas.remove(owner);
    if (held == null) return;
    for (var h : held) {
      String key = h.world().getUID() + ":" + h.x() + ":" + h.z();
      Integer count = tickets.get(key);
      if (count != null && count <= 1) {
        tickets.remove(key);
        h.world().removePluginChunkTicket(h.x(), h.z(), loot.plugin);
      } else if (count != null) tickets.put(key, count - 1);
    }
  }

  CompletableFuture<Location> findSite(
      List<World> worlds, int minimum, int maximum, int attempts, boolean ocean) {
    var future = new CompletableFuture<Location>();
    find(worlds, minimum, maximum, attempts, ocean, future);
    return future;
  }

  private void find(
      List<World> worlds,
      int minimum,
      int maximum,
      int remaining,
      boolean ocean,
      CompletableFuture<Location> result) {
    if (closed || remaining <= 0 || worlds.isEmpty() || !guardsAvailable()) {
      result.completeExceptionally(new IllegalStateException("No approved wilderness site found"));
      return;
    }
    World world = worlds.get(ThreadLocalRandom.current().nextInt(worlds.size()));
    double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2),
        distance = ThreadLocalRandom.current().nextDouble(minimum, maximum + 1.0);
    int x = ((int) (world.getSpawnLocation().getX() + Math.cos(angle) * distance) >> 4) * 16 + 8,
        z = ((int) (world.getSpawnLocation().getZ() + Math.sin(angle) * distance) >> 4) * 16 + 8;
    if (!world.getWorldBorder().isInside(new Location(world, x, world.getSeaLevel(), z))) {
      find(worlds, minimum, maximum, remaining - 1, ocean, result);
      return;
    }
    load(world, x, z)
        .whenComplete(
            (v, error) ->
                loot.main(
                    () -> {
                      if (error != null || !loaded(world, x, z)) {
                        find(worlds, minimum, maximum, remaining - 1, ocean, result);
                        return;
                      }
                      int y =
                          world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                      if (y < world.getMinHeight() + 4
                          || y + 10 >= world.getMaxHeight()
                          || !eligible(world, x, y, z, ocean)) {
                        find(worlds, minimum, maximum, remaining - 1, ocean, result);
                        return;
                      }
                      result.complete(new Location(world, x, y, z));
                    }));
  }

  @Override
  public void close() {
    closed = true;
    task.cancel();
    for (var ticket : ticketTasks) ticket.cancel();
    for (World world : Bukkit.getWorlds()) world.removePluginChunkTickets(loot.plugin);
    tickets.clear();
  }
}
