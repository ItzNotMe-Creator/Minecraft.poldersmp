package nl.poldersmp.core.teams;

import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import nl.poldersmp.common.Database;
import nl.poldersmp.core.gameplay.AbilityGuard;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class Teams implements Listener, TabExecutor, AbilityGuard, AutoCloseable {
  private final JavaPlugin plugin;
  private final TeamStore store;
  private final YamlConfiguration config;
  private volatile TeamStore.Snapshot snapshot =
      new TeamStore.Snapshot(Map.of(), Map.of(), List.of());
  private final Set<UUID> chatting = ConcurrentHashMap.newKeySet(), busy = new HashSet<>();
  private final Map<UUID, Long> combat = new HashMap<>();
  private boolean ready, refreshing;
  private final org.bukkit.scheduler.BukkitTask timer;

  public Teams(JavaPlugin plugin, Database db) {
    this.plugin = plugin;
    if (!new java.io.File(plugin.getDataFolder(), "teams.yml").exists())
      plugin.saveResource("teams.yml", false);
    config =
        YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(), "teams.yml"));
    for (String k :
        List.of(
            "maximum-teams",
            "maximum-members",
            "maximum-outgoing-invitations",
            "maximum-incoming-invitations",
            "invitation-seconds",
            "creation-cooldown-seconds",
            "combat-seconds",
            "kill-farming-seconds",
            "war-seconds",
            "relation-request-seconds"))
      if (config.getLong(k) < 1 || config.getLong(k) > 1_000_000)
        throw new IllegalArgumentException("Invalid teams setting: " + k);
    if (config.getInt("maximum-teams") > 5000
        || config.getInt("maximum-members") > 100
        || config.getInt("maximum-outgoing-invitations") > 100
        || config.getInt("maximum-incoming-invitations") > 100)
      throw new IllegalArgumentException("Team size/invitation bounds exceeded");
    store =
        new TeamStore(
            db,
            new TeamStore.Limits(
                config.getInt("maximum-teams"),
                config.getInt("maximum-members"),
                config.getInt("maximum-outgoing-invitations"),
                config.getInt("maximum-incoming-invitations"),
                1000L * config.getLong("invitation-seconds"),
                1000L * config.getLong("creation-cooldown-seconds"),
                1000L * config.getLong("war-seconds"),
                1000L * config.getLong("relation-request-seconds"),
                1000L * config.getLong("kill-farming-seconds")));
    plugin.getCommand("team").setExecutor(this);
    plugin.getCommand("team").setTabCompleter(this);
    Bukkit.getServicesManager().register(Teams.class, this, plugin, ServicePriority.Normal);
    if (config.getBoolean("enabled")) {
      Bukkit.getServicesManager()
          .register(AbilityGuard.class, this, plugin, ServicePriority.Normal);
      Bukkit.getPluginManager().registerEvents(this, plugin);
      store
          .migrate()
          .thenCompose(v -> store.expire(System.currentTimeMillis()))
          .thenCompose(v -> store.snapshot())
          .whenComplete(
              (s, e) ->
                  main(
                      () -> {
                        if (e != null) failure(e);
                        else {
                          snapshot = s;
                          ready = true;
                          plugin.getLogger().info("Teams ready: " + s.teams().size());
                        }
                      }));
    }
    timer =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  if (ready && !refreshing) {
                    refreshing = true;
                    store
                        .expire(System.currentTimeMillis())
                        .thenCompose(v -> store.snapshot())
                        .whenComplete(
                            (s, e) ->
                                main(
                                    () -> {
                                      refreshing = false;
                                      if (e != null) failure(e);
                                      else snapshot = s;
                                    }));
                  }
                },
                200,
                200);
  }

  private void main(Runnable r) {
    if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
  }

  private void failure(Throwable e) {
    plugin.getLogger().severe("Team operation: " + e);
  }

  public boolean friendly(UUID a, UUID b) {
    return config.getBoolean("enabled") && snapshot.friendly(a, b);
  }

  public boolean kingEligible(Set<UUID> players) {
    if (!config.getBoolean("enabled") || !config.getBoolean("king-require-hostile-teams"))
      return true;
    if (!ready) return false;
    return snapshot.hostilePresence(players);
  }

  public boolean allowMovement(Player p, Location a, Location b) {
    return true;
  }

  public boolean allowDamage(Player p, LivingEntity to) {
    return !(to instanceof Player victim)
        || ready
            && snapshot.damage(
                p.getUniqueId(), victim.getUniqueId(), config.getBoolean("allied-friendly-fire"));
  }

  public static UUID resolve(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException ignored) {
    }
    Player p = Bukkit.getPlayerExact(s);
    if (p != null) return p.getUniqueId();
    var old = Bukkit.getOfflinePlayerIfCached(s);
    if (old != null) return old.getUniqueId();
    throw new IllegalArgumentException("Use a known player name or UUID");
  }

  private UUID team(String name) {
    return snapshot.teams().values().stream()
        .filter(t -> t.name().equalsIgnoreCase(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown team"))
        .id();
  }

  private String name(UUID id) {
    var p = Bukkit.getOfflinePlayer(id);
    return p.getName() == null ? id.toString() : p.getName();
  }

  private void result(Player p, CompletableFuture<?> future) {
    busy.add(p.getUniqueId());
    future
        .thenCompose(v -> store.snapshot())
        .whenComplete(
            (s, e) ->
                main(
                    () -> {
                      busy.remove(p.getUniqueId());
                      if (e != null) {
                        p.sendMessage("Team action failed: " + e.getCause());
                        failure(e);
                      } else {
                        snapshot = s;
                        p.sendMessage("Team updated.");
                        if (config.getBoolean("sounds"))
                          p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
                        if (config.getBoolean("particles"))
                          p.getWorld()
                              .spawnParticle(
                                  Particle.HAPPY_VILLAGER,
                                  p.getLocation().add(0, 1, 0),
                                  5,
                                  .2,
                                  .2,
                                  .2);
                      }
                    }));
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    if (!(sender instanceof Player p)) {
      sender.sendMessage("Player command.");
      return true;
    }
    if (!p.hasPermission("polder.team.use")) {
      p.sendMessage("No permission.");
      return true;
    }
    if (!config.getBoolean("enabled") || !ready) {
      p.sendMessage("Teams disabled or initializing.");
      return true;
    }
    if (busy.contains(p.getUniqueId())) {
      p.sendMessage("Team action pending.");
      return true;
    }
    String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
    UUID id = p.getUniqueId();
    long now = System.currentTimeMillis();
    try {
      if (List.of("create", "accept", "leave", "kick", "promote", "disband").contains(sub)
          && combat.getOrDefault(id, 0L) > now)
        throw new IllegalArgumentException("Team changes blocked in combat");
      switch (sub) {
        case "create" -> {
          require(args.length == 2, "/team create <name>");
          result(p, store.create(id, args[1], now));
        }
        case "invite" -> {
          require(args.length == 2, "/team invite <player>");
          UUID target = resolve(args[1]);
          result(
              p,
              store
                  .invite(id, target, now)
                  .thenRun(
                      () ->
                          main(
                              () -> {
                                var other = Bukkit.getPlayer(target);
                                if (other != null)
                                  other.sendMessage(
                                      "Team invite from "
                                          + p.getName()
                                          + ". /team accept or /team deny");
                              })));
        }
        case "accept" -> result(p, store.accept(id, args.length > 1 ? args[1] : null, now));
        case "invites" ->
            store
                .invitations(id, now)
                .whenComplete(
                    (rows, error) ->
                        main(
                            () -> {
                              if (error != null) {
                                failure(error);
                                p.sendMessage("Invitation lookup failed.");
                              } else if (rows.isEmpty()) p.sendMessage("No active invitations.");
                              else rows.forEach(p::sendMessage);
                            }));
        case "deny" -> result(p, store.deny(id));
        case "leave" -> result(p, store.leave(id, now));
        case "kick", "promote" -> {
          require(args.length == 2, "/team " + sub + " <player>");
          result(p, store.manage(id, resolve(args[1]), sub, now));
        }
        case "disband" -> result(p, store.disband(id, now));
        case "friendlyfire" -> {
          require(
              args.length == 2 && List.of("on", "off").contains(args[1]),
              "/team friendlyfire on|off");
          result(p, store.fire(id, args[1].equals("on")));
        }
        case "ally", "rival", "war" -> {
          require(args.length >= 2, "/team " + sub + " [remove|accept] <team>");
          boolean remove = args[1].equals("remove");
          String target = args[args.length - 1];
          result(
              p,
              store.relation(
                  id,
                  team(target),
                  sub.equals("ally") ? "ALLY" : sub.equals("rival") ? "RIVAL" : "WAR",
                  remove,
                  now));
        }
        case "chat" -> {
          require(snapshot.members().containsKey(id), "Join a team first");
          if (args.length == 1) {
            if (!chatting.remove(id)) chatting.add(id);
            p.sendMessage("Team chat " + (chatting.contains(id) ? "enabled" : "disabled"));
          } else
            sendChat(
                id, Component.text(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
        }
        case "top" -> gui(p, true, 0);
        case "info", "gui" -> gui(p, false, 0);
        default ->
            p.sendMessage(
                "/team"
                    + " create|invite|accept|deny|leave|kick|promote|disband|info|chat|ally|rival|war|top|friendlyfire");
      }
    } catch (Exception e) {
      p.sendMessage(e.getMessage());
    }
    return true;
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  private void sendChat(UUID sender, Component text) {
    var m = snapshot.members().get(sender);
    if (m == null) return;
    var source = Bukkit.getPlayer(sender);
    if (source == null) return;
    Component message = Component.text("[Team] " + source.getName() + ": ").append(text);
    for (var other : snapshot.members().values())
      if (other.team().equals(m.team())) {
        var p = Bukkit.getPlayer(other.player());
        if (p != null) p.sendMessage(message);
      }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void chat(io.papermc.paper.event.player.AsyncChatEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    if (!chatting.contains(id)) return;
    e.setCancelled(true);
    var membership = snapshot.members().get(id);
    if (membership == null) {
      chatting.remove(id);
      main(
          () -> {
            var player = Bukkit.getPlayer(id);
            if (player != null)
              player.sendMessage(
                  "Team chat disabled because membership ended; your message was not sent.");
          });
      return;
    }
    UUID room = membership.team();
    Component message = e.message();
    main(
        () -> {
          var current = snapshot.members().get(id);
          if (current != null && current.team().equals(room)) sendChat(id, message);
        });
  }

  private Player attacker(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p) return p;
    if (e.getDamager() instanceof Projectile a && a.getShooter() instanceof Player p) return p;
    return null;
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void damage(EntityDamageByEntityEvent e) {
    Player p = attacker(e);
    if (p != null && e.getEntity() instanceof Player victim && !allowDamage(p, victim))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void tag(EntityDamageByEntityEvent e) {
    Player a = attacker(e);
    if (a != null && e.getEntity() instanceof Player b && e.getFinalDamage() > 0) {
      long until = System.currentTimeMillis() + 1000L * config.getLong("combat-seconds");
      combat.put(a.getUniqueId(), until);
      combat.put(b.getUniqueId(), until);
      store
          .combat(a.getUniqueId(), b.getUniqueId(), until)
          .exceptionally(
              error -> {
                failure(error);
                return null;
              });
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void death(PlayerDeathEvent e) {
    Player v = e.getEntity(), k = v.getKiller();
    if (!ready
        || k == null
        || !v.getWorld().getPVP()
        || friendly(k.getUniqueId(), v.getUniqueId())
        || !(v.getLastDamageCause() instanceof EntityDamageByEntityEvent d)
        || d.isCancelled()
        || !k.equals(attacker(d))) return;
    UUID id =
        UUID.nameUUIDFromBytes(
            (v.getUniqueId() + ":" + v.getStatistic(Statistic.DEATHS))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    store
        .death(id, k.getUniqueId(), v.getUniqueId(), System.currentTimeMillis())
        .exceptionally(
            error -> {
              failure(error);
              return null;
            });
  }

  private static final class Menu implements InventoryHolder {
    Inventory inv;
    boolean top;
    int page;

    public Inventory getInventory() {
      return inv;
    }
  }

  private ItemStack icon(Material material, String title, String... lore) {
    ItemStack i = new ItemStack(material);
    var meta = i.getItemMeta();
    meta.displayName(Component.text(title));
    meta.lore(Arrays.stream(lore).map(Component::text).toList());
    i.setItemMeta(meta);
    return i;
  }

  private void gui(Player p, boolean top, int page) {
    Menu m = new Menu();
    m.top = top;
    m.page = Math.min(1000, Math.max(0, page));
    m.inv = Bukkit.createInventory(m, 54, Component.text(config.getString("gui-title")));
    for (int i = 45; i < 54; i++) m.inv.setItem(i, icon(Material.GRAY_STAINED_GLASS_PANE, " "));
    if (top) {
      var sorted =
          snapshot.teams().values().stream()
              .sorted(
                  Comparator.comparingLong(TeamStore.Team::wins)
                      .thenComparingLong(TeamStore.Team::kills)
                      .reversed()
                      .thenComparing(TeamStore.Team::name))
              .toList();
      for (int i = 0; i < 45 && i + m.page * 45 < sorted.size(); i++) {
        var t = sorted.get(i + m.page * 45);
        m.inv.setItem(
            i,
            icon(
                Material.SHIELD,
                "#" + (i + m.page * 45 + 1) + " " + t.name(),
                "War wins: " + t.wins(),
                "Kills: " + t.kills() + " • Deaths: " + t.deaths()));
      }
    } else {
      var membership = snapshot.members().get(p.getUniqueId());
      if (membership == null)
        m.inv.setItem(
            22,
            icon(
                Material.PAPER,
                "Create or join a team",
                "/team create <name>",
                "/team accept [team]",
                "/team invites"));
      else {
        var t = snapshot.teams().get(membership.team());
        m.inv.setItem(
            4,
            icon(
                Material.WHITE_BANNER,
                t.name(),
                "Your rank: " + membership.role(),
                "Leader: " + name(t.leader()),
                "Friendly fire: " + t.fire(),
                "Kills: " + t.kills() + " • Deaths: " + t.deaths(),
                "War wins: " + t.wins()));
        var roster =
            snapshot.members().values().stream()
                .filter(v -> v.team().equals(t.id()))
                .sorted(
                    Comparator.comparingInt(
                            (TeamStore.Member member) ->
                                switch (member.role()) {
                                  case "LEADER" -> 0;
                                  case "OFFICER" -> 1;
                                  default -> 2;
                                })
                        .thenComparing(member -> member.player().toString()))
                .toList();
        for (int i = 0; i + m.page * 27 < roster.size() && i < 27; i++) {
          var r = roster.get(i + m.page * 27);
          ItemStack head = icon(Material.PLAYER_HEAD, name(r.player()), r.role());
          var meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
          meta.setOwningPlayer(Bukkit.getOfflinePlayer(r.player()));
          head.setItemMeta(meta);
          m.inv.setItem(9 + i, head);
        }
        m.inv.setItem(
            40,
            icon(
                Material.WRITABLE_BOOK,
                "Relations / wars",
                snapshot.relations().stream()
                    .filter(r -> r.a().equals(t.id()) || r.b().equals(t.id()))
                    .map(
                        r ->
                            r.kind()
                                + " "
                                + (snapshot
                                        .teams()
                                        .containsKey(r.a().equals(t.id()) ? r.b() : r.a())
                                    ? snapshot
                                        .teams()
                                        .get(r.a().equals(t.id()) ? r.b() : r.a())
                                        .name()
                                    : "old team")
                                + " "
                                + r.aKills()
                                + ":"
                                + r.bKills())
                    .limit(10)
                    .toArray(String[]::new)));
      }
    }
    m.inv.setItem(45, icon(Material.ARROW, "Previous"));
    m.inv.setItem(49, icon(Material.NETHER_STAR, top ? "Your team" : "Leaderboard"));
    m.inv.setItem(53, icon(Material.ARROW, "Next"));
    p.openInventory(m.inv);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu m) {
      e.setCancelled(true);
      if (e.getWhoClicked() instanceof Player p) {
        if (e.getRawSlot() == 49) gui(p, !m.top, 0);
        if (e.getRawSlot() == 45) gui(p, m.top, m.page - 1);
        if (e.getRawSlot() == 53) gui(p, m.top, m.page + 1);
      }
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @Override
  public List<String> onTabComplete(CommandSender p, Command c, String a, String[] args) {
    if (!p.hasPermission("polder.team.use")) return List.of();
    if (args.length == 1)
      return List.of(
              "create",
              "invite",
              "accept",
              "deny",
              "leave",
              "kick",
              "promote",
              "disband",
              "info",
              "chat",
              "ally",
              "rival",
              "war",
              "top",
              "friendlyfire")
          .stream()
          .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
          .toList();
    if (args.length == 2) {
      if (List.of("ally", "rival", "war", "accept").contains(args[0]))
        return snapshot.teams().values().stream()
            .map(TeamStore.Team::name)
            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
            .toList();
      return Bukkit.getOnlinePlayers().stream()
          .map(Player::getName)
          .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
          .toList();
    }
    return List.of();
  }

  public void close() {
    timer.cancel();
    Bukkit.getServicesManager().unregister(Teams.class, this);
    Bukkit.getServicesManager().unregister(AbilityGuard.class, this);
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
  }
}
