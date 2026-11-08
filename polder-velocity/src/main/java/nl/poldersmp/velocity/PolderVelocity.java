package nl.poldersmp.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import java.nio.file.Path;
import nl.poldersmp.common.Settings;
import org.slf4j.Logger;

@Plugin(id = "poldervelocity", name = "PolderVelocity", version = "1.0.0-SNAPSHOT")
public final class PolderVelocity {
  private final Logger logger;
  private final Path directory;

  @Inject
  public PolderVelocity(Logger logger, @DataDirectory Path directory) {
    this.logger = logger;
    this.directory = directory;
  }

  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    try {
      Settings settings = Settings.load(directory);
      logger.info("Velocity foundation {}", settings.enabled() ? "enabled" : "disabled");
    } catch (Exception e) {
      throw new IllegalStateException("Invalid PolderVelocity configuration", e);
    }
  }
}
