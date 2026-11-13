package nl.poldersmp.cinematics;

import java.time.Duration;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.*;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One shared frame budget bounds every viewer/particle packet, including burst fan-out. */
final class ArrivalRenderer {
  private final VisualAssets assets;
  private final ShowSettings settings;
  private final EffectBudget budget;
  private int remainingSessions;

  ArrivalRenderer(VisualAssets assets, ShowSettings settings) {
    this.assets = assets;
    this.settings = settings;
    budget = new EffectBudget(settings.packets(), settings.particles());
  }

  void frame(int sessions) {
    budget.reset();
    remainingSessions = Math.max(1, sessions);
  }

  static Component text(String value, Player player) {
    return MiniMessage.miniMessage()
        .deserialize(value, Placeholder.unparsed("player", player.getName()));
  }

  private final class Paint {
    final List<Player> viewers;
    int left;

    Paint(List<Player> viewers) {
      this.viewers = viewers;
      left =
          Math.min(
              settings.perSession(),
              Math.max(8, budget.remainingPackets() / Math.max(1, remainingSessions--)));
    }

    void particle(Particle type, Location at, int count, double spread, double speed) {
      if (!at.getWorld().getWorldBorder().isInside(at)) return;
      for (Player viewer : viewers)
        if (left > 0 && budget.take(Math.max(1, count))) {
          left--;
          viewer.spawnParticle(type, at, count, spread, spread, spread, speed);
        }
    }

    void dust(Location at, Color from, Color to, float size) {
      if (!at.getWorld().getWorldBorder().isInside(at)) return;
      var data = new Particle.DustTransition(from, to, size);
      for (Player viewer : viewers)
        if (left > 0 && budget.take(1)) {
          left--;
          viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, data);
        }
    }
  }

  void scene(Player player, int scene, boolean sounds) {
    if (!sounds || !settings.enabled()) return;
    Sound[] tones = {
      Sound.BLOCK_BEACON_ACTIVATE,
      Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
      Sound.UI_TOAST_CHALLENGE_COMPLETE,
      Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM
    };
    player.playSound(
        player.getLocation(), tones[scene], settings.volume() * .65f, scene == 2 ? 1.35f : 1.1f);
  }

  void flight(Player player, CameraPath.Point point, double progress, int tick, boolean particles) {
    if (!settings.enabled() || !particles || tick % 2 != 0) return;
    Paint paint = new Paint(List.of(player));
    Location eye =
        new Location(
            player.getWorld(), point.x(), point.y() + 1.62, point.z(), point.yaw(), point.pitch());
    Vector forward = eye.getDirection(), right = new Vector(-forward.getZ(), 0, forward.getX());
    if (right.lengthSquared() < .001) right = new Vector(1, 0, 0);
    right.normalize();
    Vector up = forward.clone().crossProduct(right).normalize();
    Location center = eye.clone().add(forward.clone().multiply(4.4));
    if (settings.flightRings()) {
      int n = settings.samples();
      double radius = 1.6 + .25 * Math.sin(tick * .09);
      for (int i = 0; i < n; i++) {
        double a = 2 * Math.PI * i / n + tick * .025;
        Location at =
            center
                .clone()
                .add(right.clone().multiply(Math.cos(a) * radius))
                .add(up.clone().multiply(Math.sin(a) * radius));
        paint.dust(
            at, i % 3 == 0 ? settings.secondary() : settings.primary(), settings.accent(), .65f);
      }
    }
    if (settings.stardust()) paint.particle(Particle.END_ROD, center, 4, 1.8, .015);
    if (progress > .5 && progress < .75 && tick % 8 == 0)
      paint.particle(Particle.FIREWORK, center, 8, 1.4, .015);
    if (tick % 10 == 0) player.sendActionBar(text(settings.actionbar(), player));
  }

  Landing land(Player player, Location at, boolean particles, boolean sounds) {
    return new Landing(player, at.clone(), particles, sounds);
  }

  final class Landing implements AutoCloseable {
    private final Player player;
    private final Location anchor;
    private final boolean particles, sounds;
    private final ArrivalTimeline timeline = new ArrivalTimeline(settings.duration());
    private final long started = System.nanoTime();
    private final List<Entity> entities = new ArrayList<>();
    private final Set<UUID> shown = new HashSet<>();
    private boolean closed;
    private int frame;

    Landing(Player player, Location anchor, boolean particles, boolean sounds) {
      this.player = player;
      this.anchor = anchor;
      this.particles = particles;
      this.sounds = sounds;
      try {
        if (settings.enabled() && settings.items())
          for (int i = 0; i < 4; i++) {
            ItemDisplay display =
                assets.create(anchor.clone().add(0, 1, 0), ItemDisplay.class, player.getUniqueId());
            entities.add(display);
            display.setItemStack(
                new ItemStack(i % 2 == 0 ? Material.TOTEM_OF_UNDYING : settings.crystal()));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setInterpolationDuration(2);
            display.setTeleportDuration(2);
            display.setViewRange(.5f);
            display.setShadowRadius(0);
            display.setGlowing(true);
            display.setGlowColorOverride(i % 2 == 0 ? settings.secondary() : settings.primary());
          }
        if (settings.enabled() && settings.runes())
          for (int i = 0; i < 2; i++) {
            TextDisplay rune =
                assets.create(anchor.clone().add(0, 1, 0), TextDisplay.class, player.getUniqueId());
            entities.add(rune);
            rune.text(
                Component.text(
                        "✦ ◇ ✦",
                        net.kyori.adventure.text.format.TextColor.color(settings.accent().asRGB()))
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
            rune.setBillboard(Display.Billboard.CENTER);
            rune.setDefaultBackground(false);
            rune.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            rune.setShadowed(true);
            rune.setTeleportDuration(2);
            rune.setViewRange(.375f);
          }
        syncViewers();
      } catch (RuntimeException e) {
        close();
        throw e;
      }
    }

    private List<Player> viewers() {
      List<Player> list = new ArrayList<>();
      list.add(player);
      if (settings.nearby())
        anchor.getWorld().getNearbyPlayers(anchor, settings.viewerRadius()).stream()
            .filter(p -> !p.equals(player) && p.isOnline())
            .sorted(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(anchor)))
            .limit(settings.viewers())
            .forEach(list::add);
      return List.copyOf(list);
    }

    private void syncViewers() {
      Set<UUID> now = new HashSet<>();
      for (Player viewer : viewers()) {
        now.add(viewer.getUniqueId());
        if (shown.add(viewer.getUniqueId())) entities.forEach(e -> assets.show(viewer, e));
      }
      for (UUID id : new HashSet<>(shown))
        if (!now.contains(id)) {
          Player old = Bukkit.getPlayer(id);
          if (old != null) entities.forEach(e -> assets.hide(old, e));
          shown.remove(id);
        }
    }

    boolean tick() {
      if (closed) return true;
      frame++;
      List<ArrivalTimeline.Cue> cues = timeline.advance((System.nanoTime() - started) / 1e9);
      double t = timeline.progress();
      if (frame % 5 == 1) syncViewers();
      Paint paint = new Paint(viewers());
      for (var cue : cues) cue(cue, paint);
      double envelope = t < .58 ? .8 + t : Math.max(0, (1 - t) / .42);
      int index = 0;
      for (Entity entity : entities) {
        if (!entity.isValid()) continue;
        double angle = frame * .095 + index * Math.PI / 2,
            radius = entity instanceof TextDisplay ? 2.1 : 1.4;
        if (t > .38 && t < .58) radius *= 1 - (t - .38) / .20 * .7;
        if (t >= .58) radius = radius * (.5 + (t - .58) * 3);
        double y =
            entity instanceof TextDisplay
                ? 1.35 + Math.sin(frame * .08 + index) * .25
                : 1.05 + Math.sin(frame * .1 + index) * .35;
        Location pos = anchor.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
        pos.setYaw((float) (-Math.toDegrees(angle) + 90));
        pos.setPitch(0);
        entity.teleport(pos);
        float scale = (float) Math.max(.05, Math.min(.8, envelope * .65));
        if (entity instanceof ItemDisplay display) {
          display.setInterpolationDelay(0);
          display.setTransformation(
              new Transformation(
                  new Vector3f(),
                  new Quaternionf()
                      .rotateY((float) angle)
                      .rotateZ((float) Math.sin(frame * .09) * .18f),
                  new Vector3f(scale),
                  new Quaternionf()));
        }
        index++;
      }
      if (particles && frame % 2 == 0) {
        int n = settings.samples();
        double radius = t < .58 ? 1.35 + Math.sin(frame * .08) * .1 : .5 + (t - .58) * 6;
        for (var v : ArrivalGeometry.ring(radius, .08, n, frame * .025))
          paint.dust(
              anchor.clone().add(v.x(), v.y(), v.z()),
              settings.primary(),
              settings.secondary(),
              .9f);
        for (int strand = 0; strand < 2; strand++)
          for (int i = 0; i < Math.max(6, n / 2); i++) {
            double a = frame * .12 + i * .45 + strand * Math.PI,
                height = ((i * .24 + frame * .035) % 3.1);
            double r = .8 + height * .15;
            paint.dust(
                anchor.clone().add(Math.cos(a) * r, height, Math.sin(a) * r),
                strand == 0 ? settings.secondary() : settings.primary(),
                settings.accent(),
                .7f);
          }
        if (t >= .58 && t < .9) {
          for (var v :
              ArrivalGeometry.ring(.5 + (t - .58) * 8, .2, Math.max(12, n - 4), -frame * .04))
            paint.dust(
                anchor.clone().add(v.x(), v.y(), v.z()),
                settings.accent(),
                settings.primary(),
                .8f);
        }
      }
      if (frame % 10 == 0) player.sendActionBar(text(settings.subtitle(), player));
      return timeline.complete();
    }

    private void cue(ArrivalTimeline.Cue cue, Paint paint) {
      switch (cue) {
        case AWAKEN -> {
          if (sounds)
            player.playSound(anchor, Sound.BLOCK_BEACON_ACTIVATE, settings.volume() * .7f, .7f);
          if (particles) paint.particle(Particle.ENCHANT, anchor.clone().add(0, 1, 0), 20, .8, .08);
        }
        case ORBIT -> {
          if (sounds)
            player.playSound(anchor, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, settings.volume(), 1.3f);
        }
        case CONVERGE -> {
          if (sounds)
            player.playSound(
                anchor, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, settings.volume() * .6f, 1.6f);
          if (particles)
            paint.particle(Particle.ELECTRIC_SPARK, anchor.clone().add(0, 1.2, 0), 24, .6, .07);
        }
        case TOTEM -> {
          if (settings.overlay() && sounds && particles)
            player.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
          else if (sounds) player.playSound(anchor, Sound.ITEM_TOTEM_USE, settings.volume(), 1.1f);
          if (particles && settings.burst() > 0) {
            paint.particle(
                Particle.TOTEM_OF_UNDYING, anchor.clone().add(0, 1, 0), settings.burst(), .75, .12);
            paint.particle(Particle.FIREWORK, anchor.clone().add(0, 1, 0), 24, .4, .1);
            paint.particle(Particle.END_ROD, anchor.clone().add(0, 1, 0), 18, .4, .12);
          }
          player.showTitle(
              Title.title(
                  text(settings.title(), player),
                  text(settings.subtitle(), player),
                  Title.Times.times(
                      Duration.ofMillis(100),
                      Duration.ofMillis(
                          Math.max(100, (long) ((settings.duration() * .42 - .4) * 1000))),
                      Duration.ofMillis(300))));
        }
        case AFTERGLOW -> {
          if (sounds)
            player.playSound(
                anchor, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, settings.volume() * .6f, 1.4f);
          if (particles) paint.particle(Particle.WAX_ON, anchor.clone().add(0, .8, 0), 12, .8, .01);
        }
      }
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      for (Entity entity : entities) assets.remove(entity);
      entities.clear();
      shown.clear();
    }
  }
}
