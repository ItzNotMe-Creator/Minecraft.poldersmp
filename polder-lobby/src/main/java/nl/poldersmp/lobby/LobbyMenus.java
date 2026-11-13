package nl.poldersmp.lobby;

import java.time.*;
import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

final class LobbyMenus implements Listener {
  private final PolderLobby p;

  static final class Menu implements InventoryHolder {
    Inventory inv;
    final UUID viewer;
    final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    Menu(UUID id) {
      viewer = id;
    }

    public Inventory getInventory() {
      return inv;
    }
  }

  LobbyMenus(PolderLobby p) {
    this.p = p;
    Bukkit.getPluginManager().registerEvents(this, p);
  }

  static ItemStack item(Material material, String name, List<String> lore) {
    var i = new ItemStack(material);
    var m = i.getItemMeta();
    m.displayName(
        Component.text(name, net.kyori.adventure.text.format.NamedTextColor.AQUA)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
    m.lore(
        lore.stream()
            .map(
                line ->
                    Component.text(line, net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
            .toList());
    i.setItemMeta(m);
    return i;
  }

  private Menu menu(Player viewer, String title) {
    var m = new Menu(viewer.getUniqueId());
    m.inv =
        Bukkit.createInventory(m, 54, Component.text(p.cfg.getString("gui.title") + " • " + title));
    Material filler =
        Material.matchMaterial(p.cfg.getString("gui.filler", "GRAY_STAINED_GLASS_PANE"));
    for (int i = 0; i < 54; i++)
      if (i < 9 || i > 44 || i % 9 == 0 || i % 9 == 8)
        m.inv.setItem(
            i, item(filler == null ? Material.GRAY_STAINED_GLASS_PANE : filler, " ", List.of()));
    button(
        m,
        49,
        Material.NETHER_STAR,
        "Lobby Home",
        List.of("Return to the main menu"),
        v -> open(v, "home"));
    return m;
  }

  private void button(
      Menu m, int slot, Material icon, String name, List<String> lore, Consumer<Player> action) {
    m.inv.setItem(slot, item(icon, name, lore));
    if (action != null) m.actions.put(slot, action);
  }

  private void show(Player p, Menu m) {
    p.openInventory(m.inv);
  }

  void open(Player player, String kind) {
    if (p.busy(player)) {
      p.msg(player, "activity-busy");
      return;
    }
    switch (kind) {
      case "selector" -> selector(player);
      case "profile" -> profile(player, player.getUniqueId());
      case "daily" -> daily(player);
      case "cosmetics" -> cosmetics(player, 0);
      case "parkour" -> parkour(player);
      default -> {
        Menu m = menu(player, "Explore");
        button(
            m,
            20,
            Material.COMPASS,
            "Server Selector",
            List.of("Live SMP status and access"),
            v -> open(v, "selector"));
        button(
            m,
            22,
            Material.CHEST,
            "Cosmetics",
            List.of("Trails • hats • wings • pets", "Cosmetic tokens have no cash value"),
            v -> open(v, "cosmetics"));
        button(
            m,
            24,
            Material.PLAYER_HEAD,
            "Your Profile",
            List.of("Achievements • records • unlocks"),
            v -> profile(v, v.getUniqueId()));
        button(
            m,
            30,
            Material.FEATHER,
            "Parkour",
            List.of("Courses and personal records"),
            v -> open(v, "parkour"));
        button(
            m,
            32,
            Material.CLOCK,
            "Daily Rewards",
            List.of("Daily cosmetic rewards and streak"),
            v -> open(v, "daily"));
        button(
            m,
            40,
            Material.FIREWORK_ROCKET,
            "Lobby Challenges",
            List.of(p.events.description(), "/lobbyevent top"),
            null);
        button(
            m,
            42,
            Material.GOLD_INGOT,
            "Playtime Leaderboard",
            List.of("Top ten lobby explorers"),
            v -> p.result(v, p.store.top(null), list -> list.forEach(v::sendMessage)));
        show(player, m);
      }
    }
  }

  private void selector(Player player) {
    if (!p.use(player, "selector")) return;
    Menu m = menu(player, "Server Selector");
    var status = p.network.status(p.cfg.getLong("network.max-age-seconds") * 1000);
    boolean online = Boolean.TRUE.equals(status.get("online")),
        locked = Boolean.TRUE.equals(status.get("locked"));
    String state =
        status.isEmpty() ? "Unavailable" : !online ? "Offline" : locked ? "Locked" : "Online";
    Material icon = Material.matchMaterial(p.cfg.getString("selector.material", "GRASS_BLOCK"));
    button(
        m,
        22,
        icon == null ? Material.GRASS_BLOCK : icon,
        p.cfg.getString("selector.name", "PolderSMP Survival"),
        List.of(
            "Status: " + state,
            "Players: " + status.getOrDefault("players", "Unavailable"),
            p.cfg.getString("selector.lore", "Click to request proxy access")),
        p::transfer);
    show(player, m);
  }

  void profile(Player viewer, UUID target) {
    if (p.busy(viewer) || !p.use(viewer, "profiles")) return;
    p.result(
        viewer,
        p.store.profile(target),
        data -> {
          if (data == null) {
            viewer.sendMessage("No lobby profile found.");
            return;
          }
          Menu m = menu(viewer, "Profile • " + data.name());
          var net = p.networkProfiles.getOrDefault(target, Map.of());
          String rank = p.ranks.getOrDefault(target, "Unavailable");
          button(
              m,
              13,
              Material.PLAYER_HEAD,
              data.name(),
              List.of(
                  "Rank: " + rank,
                  "Lobby playtime: " + data.playtime() + " seconds",
                  "Kills: " + net.getOrDefault("kills", "Unavailable"),
                  "Deaths: " + net.getOrDefault("deaths", "Unavailable"),
                  "Cosmetic tokens: " + data.tokens(),
                  "Daily streak: " + data.streak()),
              null);
          button(
              m,
              29,
              Material.DIAMOND,
              "Achievements • " + data.achievements().size(),
              limit(data.achievements(), 20),
              null);
          button(
              m,
              31,
              Material.CHEST,
              "Cosmetic Unlocks • " + data.unlocks().size(),
              limit(new ArrayList<>(data.unlocks()), 20),
              v -> open(v, "cosmetics"));
          button(
              m,
              33,
              Material.FEATHER,
              "Personal Bests",
              limit(
                  data.records().entrySet().stream()
                      .map(e -> e.getKey() + ": " + e.getValue() + " ms")
                      .toList(),
                  20),
              v -> open(v, "parkour"));
          button(
              m,
              40,
              Material.SUNFLOWER,
              "Network Profile",
              net.isEmpty()
                  ? List.of("Network statistics unavailable until a trusted bridge is installed")
                  : limit(
                      net.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList(),
                      20),
              null);
          show(viewer, m);
        });
  }

  private static List<String> limit(List<String> list, int max) {
    return list.isEmpty() ? List.of("None yet") : list.subList(0, Math.min(max, list.size()));
  }

  private void daily(Player player) {
    if (!p.use(player, "daily")) return;
    Menu m = menu(player, "Daily Rewards");
    var data = p.profiles.get(player.getUniqueId());
    button(
        m,
        22,
        Material.CLOCK,
        "Claim Today's Reward",
        List.of(
            "Date: " + LocalDate.now(ZoneId.of(p.cfg.getString("daily.timezone"))),
            "Reward: " + p.cfg.getLong("daily.tokens") + " cosmetic tokens",
            "Current streak: " + (data == null ? 0 : data.streak()),
            "One claim per date; no cash or gameplay power"),
        v -> {
          if (!p.use(v, "daily")) return;
          p.result(
              v,
              p.store.daily(
                  v.getUniqueId(),
                  LocalDate.now(ZoneId.of(p.cfg.getString("daily.timezone"))),
                  p.cfg.getLong("daily.tokens"),
                  p.cfg.getString("daily.unlock", ""),
                  System.currentTimeMillis()),
              streak -> {
                p.refresh(v.getUniqueId());
                p.msg(v, "daily-success", "streak", String.valueOf(streak));
                v.closeInventory();
              });
        });
    show(player, m);
  }

  private void parkour(Player player) {
    if (!p.use(player, "parkour")) return;
    Menu m = menu(player, "Parkour Courses");
    int slot = 10;
    for (String course : p.parkourConfig.getConfigurationSection("courses").getKeys(false)) {
      if (slot >= 44) break;
      if (slot % 9 == 8) slot += 2;
      button(
          m,
          slot++,
          Material.FEATHER,
          course,
          List.of("Click to start", "/parkour top " + course, "/parkour reset • /parkour quit"),
          v -> {
            v.closeInventory();
            try {
              p.parkour.start(v, course);
            } catch (Exception e) {
              v.sendMessage(e.getMessage());
            }
          });
    }
    show(player, m);
  }

  private void cosmetics(Player player, int page) {
    if (!p.use(player, "cosmetics")) return;
    var catalog = p.cfg.getConfigurationSection("cosmetics.catalog");
    List<String> ids = new ArrayList<>(catalog.getKeys(false));
    Menu m = menu(player, "Cosmetics • " + (page + 1));
    var data = p.profiles.get(player.getUniqueId());
    for (int j = 0; j < 21; j++) {
      int index = page * 21 + j;
      if (index >= ids.size()) break;
      String id = ids.get(index), base = "cosmetics.catalog." + id;
      int slot = 10 + j / 7 * 9 + j % 7;
      boolean owned = data != null && data.unlocks().contains(id);
      Material material = Material.matchMaterial(p.cfg.getString(base + ".material", "PAPER"));
      button(
          m,
          slot,
          material == null ? Material.PAPER : material,
          p.cfg.getString(base + ".name", id),
          List.of(
              owned
                  ? "Unlocked • click to select"
                  : "Cost: " + p.cfg.getLong(base + ".price") + " cosmetic tokens",
              "Right-click: preview for 5 seconds",
              "/cosmetics preview " + id),
          v -> {
            if (!p.use(v, "cosmetics")) return;
            p.result(
                v,
                p.store.buy(
                    v.getUniqueId(),
                    id,
                    p.cfg.getString(base + ".category"),
                    p.cfg.getLong(base + ".price")),
                bought -> {
                  p.cosmetics.remove(v.getUniqueId());
                  p.refresh(v.getUniqueId());
                  v.sendMessage(bought ? "Cosmetic unlocked and selected." : "Cosmetic selected.");
                  v.closeInventory();
                });
          });
      m.actions.put(-slot - 1, v -> p.cosmetics.preview(v, id));
    }
    button(
        m,
        45,
        Material.ARROW,
        "Previous",
        List.of(),
        page > 0 ? v -> cosmetics(v, page - 1) : null);
    button(
        m,
        53,
        Material.ARROW,
        "Next",
        List.of(),
        (page + 1) * 21 < ids.size() ? v -> cosmetics(v, page + 1) : null);
    button(
        m,
        48,
        Material.BARRIER,
        "Disable Cosmetics",
        List.of("Preserves your unlocks"),
        v ->
            p.result(
                v,
                p.store.clear(v.getUniqueId()),
                x -> {
                  p.cosmetics.remove(v.getUniqueId());
                  p.refresh(v.getUniqueId());
                  v.closeInventory();
                }));
    show(player, m);
  }

  void closeAll() {
    for (Player player : Bukkit.getOnlinePlayers())
      if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu)
        player.closeInventory();
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player viewer)
        || !m.viewer.equals(viewer.getUniqueId())
        || e.getClickedInventory() != m.inv
        || p.busy(viewer)) return;
    int key =
        e.isRightClick() && m.actions.containsKey(-e.getRawSlot() - 1)
            ? -e.getRawSlot() - 1
            : e.getRawSlot();
    var action = m.actions.get(key);
    if (action != null)
      try {
        if (p.cfg.getBoolean("gui.sounds"))
          viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, .4f, 1);
        action.accept(viewer);
      } catch (Exception ex) {
        viewer.sendMessage("Menu action unavailable: " + ex.getMessage());
      }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }
}
