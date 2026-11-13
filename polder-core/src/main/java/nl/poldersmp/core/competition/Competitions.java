package nl.poldersmp.core.competition;

import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.gameplay.AbilityGuard;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

public final class Competitions implements Listener, TabExecutor, AutoCloseable {
  private final JavaPlugin plugin;
  private final CompetitionStore store;
  private CompetitionConfig config;
  private YamlConfiguration messages;
  private CompetitionStore.KingEvent event;
  private volatile UUID currentKing;
  private Instant next;
  private BossBar bar;
  private boolean ready, busy;
  private volatile boolean closed;
  private volatile String titlePrefix;
  private volatile boolean titleChat;
  private long lastRetry;
  private final Map<UUID, PermissionAttachment> titles = new HashMap<>();
  private final org.bukkit.scheduler.BukkitTask task;

  public Competitions(JavaPlugin plugin, Database database) {
    this.plugin = plugin;
    config = CompetitionConfig.load(plugin);
    messages = loadMessages();
    refreshChatSettings();
    Economy economy = Bukkit.getServicesManager().load(Economy.class);
    store = new CompetitionStore(database, economy == null ? new Economy.Foundation() : economy);
    for (String name : List.of("bounty", "bounties", "bountyadmin", "king")) {
      Objects.requireNonNull(plugin.getCommand(name)).setExecutor(this);
      plugin.getCommand(name).setTabCompleter(this);
    }
    Bukkit.getPluginManager().registerEvents(this, plugin);
    if (Bukkit.getPluginManager().getPlugin("Vault") != null)
      plugin
          .getLogger()
          .info(
              "Competition cash uses the transactional Economy service. Legacy SQLite balances"
                  + " are never mirrored into Vault; cash is held unless sqlite mode is selected.");
    store
        .migrate()
        .thenCompose(v -> store.active())
        .thenAccept(
            active -> {
              store
                  .state("current_king")
                  .thenCombine(
                      store.state("next_event"), (king, schedule) -> new String[] {king, schedule})
                  .whenComplete(
                      (state, error) ->
                          onMain(
                              () -> {
                                if (error != null) {
                                  failure(error);
                                  return;
                                }
                                event = active;
                                currentKing = state[0] == null ? null : UUID.fromString(state[0]);
                                next =
                                    state[1] == null
                                        ? nextSchedule(Instant.now())
                                        : Instant.parse(state[1]);
                                if (state[1] == null)
                                  store
                                      .state("next_event", next.toString())
                                      .exceptionally(
                                          e -> {
                                            failure(e);
                                            return null;
                                          });
                                ready = true;
                                syncTitles();
                                if (event != null) ensureBar();
                                plugin
                                    .getLogger()
                                    .info(
                                        "Competition persistence ready; active King event="
                                            + (event == null ? "none" : event.id()));
                              }));
            })
        .exceptionally(
            e -> {
              failure(e);
              return null;
            });
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
  }

  private YamlConfiguration loadMessages() {
    File file = new File(plugin.getDataFolder(), "messages.yml");
    if (!file.exists()) plugin.saveResource("messages.yml", false);
    return YamlConfiguration.loadConfiguration(file);
  }

  private String message(String key, String fallback) {
    return messages.getString(key, fallback);
  }

  private void onMain(Runnable action) {
    if (!closed && plugin.isEnabled())
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (!closed) action.run();
              });
  }

  private void failure(Throwable error) {
    plugin.getLogger().severe("Competition operation failed: " + error);
  }

  private <T> void result(CommandSender sender, CompletableFuture<T> future, Consumer<T> action) {
    future.whenComplete(
        (v, error) ->
            onMain(
                () -> {
                  if (error != null) {
                    sender.sendMessage(
                        message(
                            "competition-error",
                            "Operation failed; no uncommitted money was moved."));
                    failure(error);
                  } else action.accept(v);
                }));
  }

  private Instant nextSchedule(Instant after) {
    return nextSchedule(config, after);
  }

  private static Instant nextSchedule(CompetitionConfig configuration, Instant after) {
    var c = configuration.king();
    return CompetitionRules.nextWeekly(
        after,
        ZoneId.of(c.getString("schedule.timezone")),
        DayOfWeek.valueOf(c.getString("schedule.day")),
        LocalTime.parse(c.getString("schedule.time")));
  }

  private static String name(UUID uuid) {
    String name = Bukkit.getOfflinePlayer(uuid).getName();
    return name == null ? uuid.toString() : name;
  }

  private UUID resolve(String text) {
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException ignored) {
    }
    OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(text);
    if (player == null)
      throw new IllegalArgumentException(
          "Use a known player name or UUID; no offline name lookups are performed");
    return player.getUniqueId();
  }

  private Location point(String key) {
    var c = config.king();
    World world = Bukkit.getWorld(c.getString(key + ".world", ""));
    return world == null
        ? null
        : new Location(
            world, c.getDouble(key + ".x"), c.getDouble(key + ".y"), c.getDouble(key + ".z"));
  }

  private boolean eligible(Player p) {
    return p.isOnline()
        && !p.isDead()
        && !p.isInvulnerable()
        && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
        && p.hasPermission("polder.king.play")
        && !p.hasPermission("polder.king.exempt")
        && !p.hasMetadata("vanished");
  }

  private Set<UUID> arenaPlayers() {
    Set<UUID> players = new HashSet<>();
    Location arena = point("arena");
    if (arena == null) return players;
    double radius = config.king().getDouble("arena.radius");
    for (Player p : arena.getWorld().getPlayers())
      if (eligible(p) && p.getLocation().distanceSquared(arena) <= radius * radius)
        players.add(p.getUniqueId());
    var teams = Bukkit.getServicesManager().load(nl.poldersmp.core.teams.Teams.class);
    if (teams != null && !teams.kingEligible(players)) players.clear();
    return players;
  }

  private Set<UUID> capturePlayers(Set<UUID> arenaPlayers) {
    Set<UUID> found = new HashSet<>();
    Location capture = point("capture");
    if (capture == null) return found;
    double radius = config.king().getDouble("capture-radius");
    for (UUID id : arenaPlayers) {
      Player p = Bukkit.getPlayer(id);
      if (p != null
          && p.getWorld().equals(capture.getWorld())
          && p.getLocation().distanceSquared(capture) <= radius * radius) found.add(id);
    }
    return found;
  }

  private void ensureBar() {
    if (bar == null) bar = Bukkit.createBossBar("King of the SMP", BarColor.YELLOW, BarStyle.SOLID);
    for (Player p : Bukkit.getOnlinePlayers())
      if (eligible(p) && point("arena") != null && p.getWorld().equals(point("arena").getWorld()))
        bar.addPlayer(p);
  }

  private void cleanupBar() {
    if (bar != null) {
      bar.removeAll();
      bar = null;
    }
  }

  private void tick() {
    if (!ready || closed) return;
    long now = System.currentTimeMillis();
    if (now - lastRetry >= 5000) {
      lastRetry = now;
      store
          .recoverPayouts(now)
          .exceptionally(
              error -> {
                failure(error);
                return 0;
              });
    }
    if (busy) return;
    if (event == null) {
      if (config.king().getBoolean("enabled")
          && next != null
          && !Instant.ofEpochMilli(now).isBefore(next)) {
        if (now - next.toEpochMilli() > config.king().getLong("schedule.catch-up-seconds") * 1000) {
          advanceSchedule();
          return;
        }
        if (arenaPlayers().size() >= config.king().getInt("minimum-participants") && validArena()) {
          begin(null, "weekly:" + next, false);
        }
      }
      return;
    }
    if (!config.king().getBoolean("enabled") || !validArena()) {
      finish(null, "Event canceled: arena unavailable or module disabled.");
      return;
    }
    ensureBar();
    for (Player p : new ArrayList<>(bar.getPlayers()))
      if (!eligible(p) || !p.getWorld().equals(point("arena").getWorld())) bar.removePlayer(p);
    if (now >= event.ends()) {
      finish(null, message("king-timeout", "King event ended without a winner."));
      return;
    }
    Set<UUID> arena = arenaPlayers();
    if (event.status().equals("COUNTDOWN")) {
      long remaining = Math.max(0, (event.starts() - now + 999) / 1000);
      bar.setTitle("King of the SMP • starts in " + remaining + "s");
      bar.setProgress(0);
      if (remaining > 0) return;
      if (arena.size() < config.king().getInt("minimum-participants")) {
        finish(null, "Not enough participants; prize refunded.");
        return;
      }
    }
    Set<UUID> occupants = capturePlayers(arena);
    var progress =
        new CompetitionRules.Capture(event.candidate(), event.progress())
            .advance(
                occupants,
                arena.size() >= config.king().getInt("minimum-participants"),
                1,
                config.king().getBoolean("reset-when-contested"));
    var updated =
        new CompetitionStore.KingEvent(
            event.id(),
            event.slot(),
            "ACTIVE",
            event.starts(),
            event.ends(),
            progress.candidate(),
            progress.progress(),
            null,
            event.prize(),
            event.treasury());
    busy = true;
    store
        .checkpoint(updated, arena)
        .whenComplete(
            (v, error) ->
                onMain(
                    () -> {
                      busy = false;
                      if (error != null) {
                        failure(error);
                        return;
                      }
                      event = updated;
                      double needed = config.king().getDouble("capture-seconds");
                      bar.setProgress(Math.clamp(progress.progress() / needed, 0, 1));
                      bar.setTitle(
                          occupants.size() > 1
                              ? "King of the SMP • CONTESTED"
                              : "King of the SMP • "
                                  + (progress.candidate() == null
                                      ? "Waiting"
                                      : name(progress.candidate()))
                                  + " • "
                                  + (int) progress.progress()
                                  + " / "
                                  + (int) needed
                                  + "s");
                      progress
                          .winner(needed)
                          .ifPresent(id -> finish(id, "The new King is " + name(id) + "!"));
                    }));
  }

  private boolean validArena() {
    Location arena = point("arena"), capture = point("capture");
    return arena != null
        && capture != null
        && arena.getWorld().equals(capture.getWorld())
        && arena.getWorld().getPVP()
        && arena.distanceSquared(capture) <= Math.pow(config.king().getDouble("arena.radius"), 2);
  }

  private void advanceSchedule() {
    Instant planned = nextSchedule(Instant.now());
    busy = true;
    store
        .state("next_event", planned.toString())
        .whenComplete(
            (v, error) ->
                onMain(
                    () -> {
                      busy = false;
                      if (error == null) next = planned;
                      else failure(error);
                    }));
  }

  private void begin(CommandSender sender, String slot, boolean manual) {
    if (event != null || busy) {
      if (sender != null) sender.sendMessage("An event or state transition is already active.");
      return;
    }
    if (!config.king().getBoolean("enabled") || !validArena()) {
      if (sender != null) sender.sendMessage("Configure a PvP arena and capture point first.");
      return;
    }
    if (arenaPlayers().size() < config.king().getInt("minimum-participants")) {
      if (sender != null) sender.sendMessage("Not enough eligible arena participants.");
      return;
    }
    long reward = config.king().getLong("rewards.money");
    String treasuryText = config.king().getString("rewards.treasury", "");
    UUID treasury = reward > 0 ? UUID.fromString(treasuryText) : new UUID(0, 0);
    busy = true;
    store
        .start(
            UUID.randomUUID(),
            slot,
            System.currentTimeMillis(),
            config.king().getLong("countdown-seconds") * 1000,
            config.king().getLong("maximum-duration-seconds") * 1000,
            reward,
            treasury)
        .whenComplete(
            (created, error) ->
                onMain(
                    () -> {
                      busy = false;
                      if (error != null) {
                        failure(error);
                        if (sender != null)
                          sender.sendMessage(
                              "Event could not start; check treasury and persistence.");
                        return;
                      }
                      event = created;
                      ensureBar();
                      Bukkit.broadcast(
                          Component.text(
                              message("king-start", "King of the SMP countdown has started!")));
                      if (!manual) advanceSchedule();
                    }));
  }

  private void finish(UUID winner, String announcement) {
    if (event == null || busy) return;
    busy = true;
    UUID id = event.id();
    store
        .finish(id, winner, System.currentTimeMillis())
        .whenComplete(
            (changed, error) ->
                onMain(
                    () -> {
                      busy = false;
                      if (error != null) {
                        failure(error);
                        return;
                      }
                      event = null;
                      cleanupBar();
                      if (changed) {
                        if (winner != null) {
                          currentKing = winner;
                          syncTitles();
                          Player player = Bukkit.getPlayer(winner);
                          if (player != null) {
                            if (config.king().getBoolean("sounds", true))
                              player.playSound(
                                  player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
                            if (config.king().getBoolean("particles", true))
                              player
                                  .getWorld()
                                  .spawnParticle(
                                      Particle.HAPPY_VILLAGER,
                                      player.getLocation().add(0, 1, 0),
                                      30,
                                      .5,
                                      .5,
                                      .5);
                          }
                        }
                        Bukkit.broadcast(Component.text(announcement));
                        store
                            .recoverPayouts(System.currentTimeMillis())
                            .exceptionally(
                                e -> {
                                  failure(e);
                                  return 0;
                                });
                      }
                    }));
  }

  private void syncTitles() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      boolean should = player.getUniqueId().equals(currentKing);
      PermissionAttachment attachment = titles.remove(player.getUniqueId());
      if (attachment != null) player.removeAttachment(attachment);
      if (should) {
        attachment = player.addAttachment(plugin);
        attachment.setPermission("polder.king.current", true);
        titles.put(player.getUniqueId(), attachment);
      }
    }
  }

  @EventHandler
  public void joined(PlayerJoinEvent e) {
    if (ready) syncTitles();
  }

  @EventHandler
  public void left(PlayerQuitEvent e) {
    PermissionAttachment a = titles.remove(e.getPlayer().getUniqueId());
    if (a != null) e.getPlayer().removeAttachment(a);
    if (bar != null) bar.removePlayer(e.getPlayer());
  }

  private void refreshChatSettings() {
    titlePrefix = config.king().getString("title.text", "[👑 King] ");
    titleChat = config.king().getBoolean("title.chat-prefix", true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void chat(io.papermc.paper.event.player.AsyncChatEvent e) {
    UUID king = currentKing;
    if (king != null && king.equals(e.getPlayer().getUniqueId()) && titleChat) {
      var original = e.renderer();
      Component prefix = Component.text(titlePrefix);
      e.renderer(
          (source, display, message, viewer) ->
              original.render(source, prefix.append(display), message, viewer));
    }
  }

  private boolean teammates(Player a, Player b) {
    var teams = Bukkit.getServicesManager().load(nl.poldersmp.core.teams.Teams.class);
    if (teams != null && teams.friendly(a.getUniqueId(), b.getUniqueId())) return true;
    var team = a.getScoreboard().getEntryTeam(a.getName());
    var reverse = b.getScoreboard().getEntryTeam(b.getName());
    return team != null && team.hasEntry(b.getName())
        || reverse != null && reverse.hasEntry(a.getName());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void death(PlayerDeathEvent e) {
    if (!ready || !config.bounties().getBoolean("enabled")) return;
    Player victim = e.getEntity(), killer = victim.getKiller();
    if (victim.getGameMode() != GameMode.SURVIVAL && victim.getGameMode() != GameMode.ADVENTURE)
      return;
    if (killer == null
        || killer.equals(victim)
        || !victim.getWorld().getPVP()
        || teammates(killer, victim)
        || killer.getGameMode() != GameMode.SURVIVAL && killer.getGameMode() != GameMode.ADVENTURE)
      return;
    if (!(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent damage)
        || damage.isCancelled()) return;
    Entity source = damage.getDamager();
    if (!source.equals(killer)
        && (!(source instanceof Projectile projectile) || !killer.equals(projectile.getShooter())))
      return;
    var settings = config.bounties();
    if (victim.getStatistic(Statistic.PLAY_ONE_MINUTE)
            < settings.getLong("anti-farming.minimum-victim-play-seconds") * 20
        || victim.getTicksLived()
            < settings.getLong("anti-farming.minimum-victim-life-seconds") * 20) return;
    if (settings.getBoolean("anti-farming.block-same-address", true)
        && victim.getAddress() != null
        && killer.getAddress() != null
        && victim.getAddress().getAddress().equals(killer.getAddress().getAddress())) return;
    for (var guard : Bukkit.getServicesManager().getRegistrations(AbilityGuard.class))
      if (!guard.getProvider().allowDamage(killer, victim)) return;
    UUID death =
        UUID.nameUUIDFromBytes(
            (victim.getUniqueId() + ":" + victim.getStatistic(Statistic.DEATHS))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    store
        .claim(
            death,
            killer.getUniqueId(),
            victim.getUniqueId(),
            System.currentTimeMillis(),
            settings.getLong("anti-farming.pair-cooldown-seconds") * 1000)
        .whenComplete(
            (amount, error) ->
                onMain(
                    () -> {
                      if (error != null) {
                        failure(error);
                        return;
                      }
                      if (amount > 0) {
                        Bukkit.broadcast(
                            Component.text(
                                name(killer.getUniqueId())
                                    + " claimed "
                                    + amount
                                    + " bounty on "
                                    + name(victim.getUniqueId())));
                        store
                            .recoverPayouts(System.currentTimeMillis())
                            .exceptionally(
                                ex -> {
                                  failure(ex);
                                  return 0;
                                });
                        if (settings.getBoolean("sounds", true) && killer.isOnline())
                          killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
                        if (settings.getBoolean("particles", true) && killer.isOnline())
                          killer
                              .getWorld()
                              .spawnParticle(
                                  Particle.HAPPY_VILLAGER, killer.getLocation(), 15, .4, .4, .4);
                      }
                    }));
  }

  private static final class Menu implements InventoryHolder {
    Inventory inventory;
    int page;
    boolean bounty;

    public Inventory getInventory() {
      return inventory;
    }
  }

  private ItemStack icon(Material material, String name, String... lore) {
    ItemStack item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(name));
    meta.lore(Arrays.stream(lore).map(Component::text).toList());
    item.setItemMeta(meta);
    return item;
  }

  private void bountyMenu(Player p, int requested) {
    if (!config.bounties().getBoolean("gui.enabled", true)) {
      p.sendMessage("Bounty GUI disabled.");
      return;
    }
    result(
        p,
        store.bounties(),
        rows -> {
          if (!p.isOnline()) return;
          int page = Math.clamp(requested, 0, Math.max(0, (rows.size() - 1) / 45));
          Menu holder = new Menu();
          holder.page = page;
          holder.bounty = true;
          holder.inventory =
              Bukkit.createInventory(
                  holder, 54, Component.text(config.bounties().getString("gui.title")));
          for (int i = page * 45; i < Math.min(rows.size(), page * 45 + 45); i++) {
            var bounty = rows.get(i);
            ItemStack item =
                icon(
                    Material.PLAYER_HEAD,
                    "#" + bounty.rank() + " • " + name(bounty.player()),
                    "Bounty: " + bounty.amount(),
                    "Successful claims against target: " + bounty.claims(),
                    "Bounty kills: " + bounty.kills(),
                    "Contributors: " + bounty.contributors(),
                    "UUID: " + bounty.player());
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(bounty.player()));
            item.setItemMeta(meta);
            holder.inventory.setItem(i - page * 45, item);
          }
          holder.inventory.setItem(45, icon(Material.ARROW, "Previous page"));
          holder.inventory.setItem(
              49, icon(Material.PAPER, "Page " + (page + 1), "Targets: " + rows.size()));
          holder.inventory.setItem(53, icon(Material.ARROW, "Next page"));
          p.openInventory(holder.inventory);
        });
  }

  private void kingMenu(Player p) {
    if (!config.king().getBoolean("gui.enabled", true)) {
      p.sendMessage("King GUI disabled.");
      return;
    }
    result(
        p,
        store.kingHistory(false),
        history -> {
          if (!p.isOnline()) return;
          Menu holder = new Menu();
          holder.inventory =
              Bukkit.createInventory(
                  holder, 54, Component.text(config.king().getString("gui.title")));
          holder.inventory.setItem(
              13,
              icon(
                  Material.GOLDEN_HELMET,
                  "Current King",
                  currentKing == null ? "None" : name(currentKing)));
          holder.inventory.setItem(
              20,
              icon(
                  Material.CLOCK,
                  "Next competition",
                  next == null
                      ? "Loading"
                      : next.atZone(ZoneId.of(config.king().getString("schedule.timezone")))
                          .toString()));
          holder.inventory.setItem(
              22,
              icon(
                  Material.BEACON,
                  "Status",
                  event == null ? "Idle" : event.status(),
                  "Capture: "
                      + (event == null ? 0 : (int) event.progress())
                      + " / "
                      + config.king().getInt("capture-seconds")));
          holder.inventory.setItem(
              24,
              icon(
                  Material.GOLD_INGOT,
                  "Rewards",
                  "King title and "
                      + config.king().getLong("rewards.money")
                      + " existing currency"));
          int i = 36;
          for (String row : history)
            holder.inventory.setItem(i++, icon(Material.PAPER, "Previous winner", row));
          p.openInventory(holder.inventory);
        });
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
    e.setCancelled(true);
    if (menu.bounty && e.getWhoClicked() instanceof Player p) {
      if (e.getRawSlot() == 45) bountyMenu(p, menu.page - 1);
      if (e.getRawSlot() == 53) bountyMenu(p, menu.page + 1);
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    String name = command.getName();
    boolean admin =
        name.equals("bountyadmin")
            || name.equals("king")
                && args.length > 0
                && Set.of("start", "end", "setarena", "setcapturepoint", "reload")
                    .contains(args[0].toLowerCase(Locale.ROOT));
    String permission =
        admin
            ? (name.equals("king") ? "polder.king.admin" : "polder.bounty.admin")
            : (name.equals("king") ? "polder.king.view" : "polder.bounty.use");
    if (!sender.hasPermission(permission)) {
      sender.sendMessage(message("no-permission", "No permission."));
      return true;
    }
    if (!ready) {
      sender.sendMessage("Competition database is not ready.");
      return true;
    }
    try {
      if (name.equals("king")) {
        kingCommand(sender, args);
        return true;
      }
      if (!config.bounties().getBoolean("enabled")) {
        sender.sendMessage("Bounties disabled.");
        return true;
      }
      if (name.equals("bounties") || args.length == 1 && args[0].equalsIgnoreCase("top")) {
        if (sender instanceof Player p) bountyMenu(p, 0);
        else
          result(
              sender,
              store.bounties(),
              rows ->
                  rows.stream()
                      .limit(10)
                      .forEach(
                          row ->
                              sender.sendMessage(
                                  row.rank() + ". " + row.player() + " : " + row.amount())));
      } else if (name.equals("bountyadmin")) {
        if (args.length < 2)
          throw new IllegalArgumentException("/bountyadmin clear <player> | set <player> <amount>");
        UUID target = resolve(args[1]);
        long desired;
        if (args[0].equalsIgnoreCase("clear") && args.length == 2) desired = 0;
        else if (args[0].equalsIgnoreCase("set") && args.length == 3)
          desired = Long.parseLong(args[2]);
        else throw new IllegalArgumentException("Invalid admin arguments");
        if (desired < 0 || desired > config.bounties().getLong("maximum"))
          throw new IllegalArgumentException("Amount outside configured limits");
        UUID payer =
            sender instanceof Player p
                ? p.getUniqueId()
                : config.bounties().getString("admin-funding-account", "").isEmpty()
                    ? null
                    : UUID.fromString(config.bounties().getString("admin-funding-account"));
        result(
            sender,
            store.setBounty(UUID.randomUUID(), target, desired, payer, System.currentTimeMillis()),
            amount -> {
              sender.sendMessage("Bounty set to " + amount + "; any refunds are queued.");
              store
                  .recoverPayouts(System.currentTimeMillis())
                  .exceptionally(
                      e -> {
                        failure(e);
                        return 0;
                      });
            });
      } else if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
        UUID target = resolve(args[1]);
        result(
            sender,
            store.bounties(),
            rows -> {
              sender.sendMessage(
                  name(target)
                      + " bounty: "
                      + rows.stream()
                          .filter(row -> row.player().equals(target))
                          .mapToLong(CompetitionStore.Bounty::amount)
                          .sum());
              result(
                  sender, store.history(target), history -> history.forEach(sender::sendMessage));
            });
      } else if (args.length == 2 && sender instanceof Player p) {
        UUID target = resolve(args[0]);
        long amount = Long.parseLong(args[1]);
        if (amount < config.bounties().getLong("minimum")
            || amount > config.bounties().getLong("maximum"))
          throw new IllegalArgumentException("Amount outside configured limits");
        result(
            sender,
            store.place(
                UUID.randomUUID(), p.getUniqueId(), target, amount, System.currentTimeMillis()),
            total -> {
              sender.sendMessage("Bounty escrow funded; total " + total);
              if (total >= config.bounties().getLong("large-announcement")
                  && total - amount < config.bounties().getLong("large-announcement"))
                Bukkit.broadcast(Component.text("Large bounty: " + name(target) + " — " + total));
            });
      } else sender.sendMessage("/bounty <known-player|UUID> <whole-amount> | info <player> | top");
    } catch (Exception error) {
      sender.sendMessage("Unable to complete: " + error.getMessage());
    }
    return true;
  }

  private void kingCommand(CommandSender sender, String[] args) {
    String sub = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "gui" -> {
        if (sender instanceof Player p) kingMenu(p);
        else
          sender.sendMessage(
              "/king status | current | top | history | start | end | setarena | setcapturepoint |"
                  + " reload");
      }
      case "status" -> {
        sender.sendMessage(
            "King event: "
                + (event == null
                    ? "idle"
                    : event.status() + " " + event.id() + " capture=" + event.progress())
                + "; next="
                + (next == null
                    ? "loading"
                    : next.atZone(ZoneId.of(config.king().getString("schedule.timezone")))));
        result(
            sender,
            store.pending(),
            pending -> sender.sendMessage("Pending competition payouts: " + pending));
      }
      case "current" ->
          sender.sendMessage("Current King: " + (currentKing == null ? "none" : name(currentKing)));
      case "top", "history" ->
          result(
              sender,
              store.kingHistory(sub.equals("top")),
              rows -> rows.forEach(sender::sendMessage));
      case "start" -> begin(sender, "manual:" + UUID.randomUUID(), true);
      case "end" -> {
        if (busy) throw new IllegalArgumentException("State update in progress; retry shortly");
        if (event == null) sender.sendMessage("No active event.");
        else finish(null, "King event ended by administrator; treasury refunded.");
      }
      case "reload" -> {
        if (event != null || busy)
          throw new IllegalArgumentException("End the current event before changing rules");
        CompetitionConfig nextConfig = CompetitionConfig.load(plugin);
        Instant planned = nextSchedule(nextConfig, Instant.now());
        busy = true;
        store
            .state("next_event", planned.toString())
            .whenComplete(
                (v, error) ->
                    onMain(
                        () -> {
                          busy = false;
                          if (error != null) {
                            failure(error);
                            sender.sendMessage("Reload failed; previous settings retained.");
                            return;
                          }
                          config = nextConfig;
                          next = planned;
                          messages = loadMessages();
                          refreshChatSettings();
                          sender.sendMessage("Competition settings reloaded.");
                        }));
      }
      case "setarena", "setcapturepoint" -> {
        if (event != null || busy)
          throw new IllegalArgumentException("End the current event first");
        if (!(sender instanceof Player p)) throw new IllegalArgumentException("Player required");
        String path = sub.equals("setarena") ? "arena" : "capture";
        var c = config.king();
        Location location = p.getLocation();
        c.set(path + ".world", p.getWorld().getName());
        c.set(path + ".x", location.getX());
        c.set(path + ".y", location.getY());
        c.set(path + ".z", location.getZ());
        try {
          c.save(new File(plugin.getDataFolder(), "king.yml"));
        } catch (java.io.IOException e) {
          throw new IllegalStateException(e);
        }
        sender.sendMessage(path + " position saved.");
      }
      default -> sender.sendMessage("Unknown King command.");
    }
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command c, String alias, String[] args) {
    List<String> choices = List.of();
    String command = c.getName();
    if (command.equals("king")) {
      if (!sender.hasPermission("polder.king.view")) return List.of();
      choices = new ArrayList<>(List.of("top", "history", "status", "current"));
      if (sender.hasPermission("polder.king.admin"))
        choices.addAll(List.of("start", "end", "setarena", "setcapturepoint", "reload"));
      if (args.length != 1) return List.of();
    } else {
      if (!sender.hasPermission(
          command.equals("bountyadmin") ? "polder.bounty.admin" : "polder.bounty.use"))
        return List.of();
      if (command.equals("bountyadmin") && args.length == 1) choices = List.of("clear", "set");
      else if (command.equals("bounty") && args.length == 1) {
        choices = new ArrayList<>(List.of("info", "top"));
        choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
      } else if (args.length == 2)
        choices = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
  }

  @Override
  public void close() {
    closed = true;
    task.cancel();
    cleanupBar();
    for (var entry : titles.entrySet()) {
      Player player = Bukkit.getPlayer(entry.getKey());
      if (player != null) player.removeAttachment(entry.getValue());
    }
    titles.clear();
  }
}
