package nl.poldersmp.lobby;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Cache-only: PlaceholderAPI may call this from asynchronous expansion consumers. */
final class LobbyPlaceholders extends PlaceholderExpansion {
  private final PolderLobby p;
  private volatile java.util.Map<String, String> snapshot = java.util.Map.of();

  LobbyPlaceholders(PolderLobby p) {
    this.p = p;
    org.bukkit.Bukkit.getScheduler()
        .runTaskTimer(
            p,
            () -> {
              java.util.Map<String, String> next = new java.util.HashMap<>();
              for (var e : p.profiles.entrySet()) {
                var v = e.getValue();
                String id = e.getKey().toString();
                next.put(id + "_playtime", Long.toString(v.playtime()));
                next.put(id + "_tokens", Long.toString(v.tokens()));
                next.put(id + "_streak", Integer.toString(v.streak()));
                next.put(id + "_rank", p.ranks.getOrDefault(e.getKey(), "Player"));
                next.put(id + "_achievements", Integer.toString(v.achievements().size()));
                next.put(id + "_unlocks", Integer.toString(v.unlocks().size()));
                var net = p.networkProfiles.getOrDefault(e.getKey(), java.util.Map.of());
                next.put(id + "_kills", String.valueOf(net.getOrDefault("kills", "Unavailable")));
                next.put(id + "_deaths", String.valueOf(net.getOrDefault("deaths", "Unavailable")));
              }
              var state = p.network.status(p.cfg.getLong("network.max-age-seconds") * 1000);
              next.put(
                  "status",
                  state.isEmpty()
                      ? "Unavailable"
                      : !Boolean.TRUE.equals(state.get("online"))
                          ? "Offline"
                          : Boolean.TRUE.equals(state.get("locked")) ? "Locked" : "Online");
              next.put("online", String.valueOf(state.getOrDefault("players", "Unavailable")));
              snapshot = java.util.Map.copyOf(next);
            },
            20,
            20);
  }

  public String getIdentifier() {
    return "polderlobby";
  }

  public String getAuthor() {
    return "PolderSMP";
  }

  public String getVersion() {
    return p.getPluginMeta().getVersion();
  }

  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer player, String params) {
    String key =
        java.util.Set.of("status", "online").contains(params)
            ? params
            : player == null ? "" : player.getUniqueId() + "_" + params;
    return snapshot.getOrDefault(key, "Unavailable");
  }
}
