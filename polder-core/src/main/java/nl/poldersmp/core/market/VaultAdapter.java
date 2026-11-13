package nl.poldersmp.core.market;

import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.plugin.*;

/** Reflects the supported Vault Economy contract without bundling/replacing its API. */
public final class VaultAdapter {
  private final Object provider;
  private final Class<?> api;
  private final String name;

  private VaultAdapter(Object provider, Class<?> api) throws Exception {
    this.provider = provider;
    this.api = api;
    name = (String) api.getMethod("getName").invoke(provider);
    if (!((Boolean) api.getMethod("isEnabled").invoke(provider)))
      throw new IllegalStateException("Vault economy disabled");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static VaultAdapter detect() throws Exception {
    for (Class<?> api : Bukkit.getServicesManager().getKnownServices()) {
      if (api.getName().equals("net.milkbowl.vault.economy.Economy")) {
        var registration = Bukkit.getServicesManager().getRegistration((Class) api);
        if (registration != null) return new VaultAdapter(registration.getProvider(), api);
      }
    }
    return null;
  }

  public String id() {
    return "vault:" + name + ":" + provider.getClass().getName();
  }

  private void main() {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Vault calls require Paper thread");
    if (Bukkit.getServicesManager().getRegistrations((Class) api).stream()
        .noneMatch(r -> ((RegisteredServiceProvider<?>) r).getProvider() == provider))
      throw new IllegalStateException("Pinned Vault provider disappeared");
  }

  public double balance(UUID p) throws Exception {
    main();
    double value =
        ((Number)
                api.getMethod("getBalance", OfflinePlayer.class)
                    .invoke(provider, Bukkit.getOfflinePlayer(p)))
            .doubleValue();
    if (!Double.isFinite(value) || value < 0 || value > 9_007_199_254_740_991d)
      throw new IllegalStateException("Unsupported provider balance");
    return value;
  }

  public boolean apply(UUID p, long delta) throws Exception {
    main();
    double before = balance(p);
    if (delta < 0 && before < -(double) delta) return false;
    if (delta > 0 && before + delta > 9_007_199_254_740_991d)
      throw new IllegalStateException("Provider precision boundary");
    Object response =
        api.getMethod(
                delta < 0 ? "withdrawPlayer" : "depositPlayer", OfflinePlayer.class, double.class)
            .invoke(provider, Bukkit.getOfflinePlayer(p), Math.abs((double) delta));
    boolean success =
        (Boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
    if (success
        && Double.compare(
                response.getClass().getField("amount").getDouble(response),
                Math.abs((double) delta))
            != 0)
      throw new IllegalStateException(
          "Provider reported a different applied amount; reconciliation required");
    return success;
  }
}
