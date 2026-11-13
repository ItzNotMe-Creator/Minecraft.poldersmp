# Celestial Arrival — standalone cinematic intro

**One plugin: PolderCinematics.jar.** Paper 1.21.11, Java 21. No resource pack,
client mod, ProtocolLib, Core or Lobby plugin is required. The SQLite driver is
bundled. Minecraft/Paper/Java targets match the existing PolderSMP project.

## Your floating-island spawn

This build includes an **ISLAND_AVENUE** preset designed from the supplied
red-roof tower / snowy tree / central stone courtyard screenshots. Confirmed
landing: **X 0, Y 65, Z 0, yaw 90 (west), pitch -1**. World name is deliberately
resolved from the administrator's actual current world instead of guessed.

1. Stop Paper normally. Back up `plugins/PolderCinematics/`, then replace the old
   PolderCinematics.jar with this JAR. Keep only one copy of the plugin JAR.
2. Start Paper and join the lobby as an operator. Stand near the spawn in its
   actual world, then run **`/joinintro setup island`**.
3. If an existing route is configured, it is preserved. To explicitly replace it,
   run **`/joinintro setup island replace`**. The previous public route config is
   backed up under `plugins/PolderCinematics/route-backups/` before replacement.
4. Stand on safe solid ground and run **`/joinintro preview`**.
5. Open **`/joinintro studio`** to select a visual theme and particle density.
   Set `/joinintro mode EVERY_JOIN` if you want it on every lobby join.

The island approach opens high behind the landing, frames the towers, gently
sweeps toward the central avenue and descends to the confirmed landing. It avoids
a full orbit around the tall peripheral trees. The precise six points are computed
relative to the anchor and checked against the real world and personal borders.
A loaded, safe landing floor is required. Preloaded camera samples reject solid
buildings/trees by default. The screenshots provide visual direction; actual
framing/FOV and comfort still require a Minecraft client preview on this build.
If a shot clips your particular build, adjust the saved points or raise setup
height in effects.yml and regenerate with an explicit backup/replace command.

For another lobby, stand at its desired landing point, face the main landmark,
and use `/joinintro setup` (or `setup replace`). This captures your position and
view instead of using the island anchor. `ISLAND_AVENUE` is the default approach;
`/joinintro route ORBITAL` selects the wider orbit template for the next setup.

## What plays

1. A vanilla Spectator camera attached to an invisible display entity follows a
   Catmull-Rom path with client interpolation and easing.
2. Particle portals, stardust, gradient scene titles and layered sounds accompany
   the flythrough.
3. At the safe landing the original gamemode/flight settings return. The short
   landing ceremony retains the activity lock and protects the controlled player.
4. Four floating totems/crystals orbit the player, glowing runes float nearby,
   and twin particle spirals rise through expanding emerald/gold rings.
5. The orbit converges, then releases a totem-particle burst and optional vanilla
   totem-pop overlay, a shockwave and welcome title. Actual totems, health,
   equipment, blocks, ranks and potion effects are not consumed or replaced.
6. All temporary visuals and camera entities are removed, restoration is saved,
   the journal is cleared, and player control is released.

Themes: **CELESTIAL** (emerald/gold), **INFERNO** (ember/crimson), **AURORA**
(violet/glacial). Every palette and crystal material is configurable in effects.yml.
Nearby viewers can see landing effects, with configurable distance/count limits.
The viewer's blue border can be hidden during the intro; the real world's border
is unchanged, and both original route boundaries remain enforced. The exact
previous personal-border instance is restored unless another plugin replaced it.

## Commands

Player: `/joinintro skip` cancels either flight or landing and performs safe cleanup.

Administrative permission: `polder.intro.admin` (operator by default).

| Command | Use |
|---|---|
| `/joinintro` / `/joinintro info` | Status and quick setup help |
| `/joinintro setup [island] [replace]` | Generate a route and safe landing, with backup |
| `/joinintro studio` | Protected 54-slot theme/preview menu |
| `/joinintro preview` | Preview without changing first-join eligibility |
| `/joinintro theme <CELESTIAL|INFERNO|AURORA>` | Visual preset |
| `/joinintro density <LOW|MEDIUM|HIGH>` | Particle geometry density |
| `/joinintro mode <FIRST_JOIN|EVERY_JOIN|NETWORK_SESSION>` | Join policy |
| `/joinintro duration <seconds>` | Flythrough duration, 1–120 seconds |
| `/joinintro border <hide|show>` | Temporary participant-only border visuals |
| `/joinintro route <ISLAND_AVENUE|ORBITAL>` | Template for the next setup |
| `/joinintro setspawn` | Save your current safe landing |
| `/joinintro addpoint` | Append current feet position and view |
| `/joinintro remove <index>` | Remove a waypoint, starting at 1 |
| `/joinintro editor` | Protected waypoint/curve/easing editor |
| `/joinintro curve <CATMULL_ROM|LINEAR>` | Camera geometry |
| `/joinintro easing <SMOOTHSTEP|LINEAR>` | Acceleration/deceleration |
| `/joinintro reload` | Validate candidates, safely stop shows, then reload |

Finish active introductions before editing. Reload and skip remain available for
recovery. Do not use Minecraft's global `/reload` to update the JAR.

## Configuration and safety

- `config.yml`: existing enabled flag, join policy, duration, actual spawn/points,
  scenes, particle and sound switches. Existing values are preserved on upgrade.
- `effects.yml`: new choreography, island anchor, profiles, themes, viewer limits,
  temporary border, clear-path checking, interpolation and performance caps.
  Missing new keys use packaged defaults without rewriting your custom settings.
- `foundation.properties`: module/database settings. Persistent recovery requires
  `database.enabled=true`; without it, introductions stay disabled.
- `cinematics.db`: existing UUID seen/session state and original-mode restoration
  journal. No schema/data reset is required. Previews do not mark you seen.

NETWORK_SESSION requires a trusted session provider. Without one, FIRST_JOIN is
used. Players who watched an older first-join intro remain seen: preview or choose
EVERY_JOIN rather than deleting their database.

Skip, logout, shutdown, denied camera movement and failed landing close owned
visuals and restore state. If no safe landing remains or teleport/mode restoration
is refused, the journal and activity lock remain for repair and `/joinintro skip`
retry. Repair the floor/spawn and persistence first; do not manually delete the
journal. No loaded world is deleted, unloaded or regenerated.

The player-only border is a temporary visual setting, not a change to world
protection. Original world and player boundaries are checked before it is applied.
Collision checking samples loaded blocks only; it is not a replacement for your
region plugins or an actual client framing review. Vanilla world protection can
refuse camera setup/movement, in which case restoration takes precedence.

Particle budgets cap explicit packets, particle quantities, viewer fan-out and
concurrent shows. Built-in totem animation/entity packets are bounded by show
count. Silent mode suppresses the native totem overlay because its sound is part
of the vanilla status effect; the separate totem particles still work.

## Build

From the repository root: `mvn -B -pl polder-cinematics -am clean verify`, then
`python scripts/package-cinematics.py`. Output: a standalone JAR, single-plugin
installation ZIP and SHA-256 checksums under dist/cinematics. The cinematics
workflow builds this target on Linux and Windows and uploads only this plugin.
