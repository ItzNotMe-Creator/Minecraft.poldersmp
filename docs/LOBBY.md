# Ultimate Lobby operator guide

Install `PolderLobby.jar` and `PolderCinematics.jar` on a **Paper 1.21.11 lobby
running Java 21**. Never install PolderCore there. Cinematics also works without
PolderLobby. Existing SMP/proxy code, worlds and player databases are unchanged.
Back up plugin folders and player data before upgrading.

## Initial setup

Both plugins use the existing `foundation.properties` master enable setting.
`database.enabled=false` also keeps these persistent activities disabled.
Default coordinates and courses are deliberately empty. Nothing constructs a
lobby or edits your world. Set up a lobby build and keep its active chunks loaded
using your server's established spawn/chunk management.

1. Stand on safe ground at the landing point. Run `/lobbyadmin setspawn` and
   `/joinintro setspawn`. They save separate settings; keep these spawn points aligned.
2. Fly to camera positions using your normal administrative tools and run
   `/joinintro addpoint` at each one (2–64 points in the same loaded lobby world).
   Point yaw/pitch controls the view. Segments may be at most 512 blocks long.
3. Use a final waypoint close to the safe spawn for a smooth landing scene.
   Return to safe ground. Run `/joinintro preview`, then `/joinintro skip` to
   test interruption. `/joinintro editor` opens a protected 54-slot, two-page
   editor showing waypoint coordinates and buttons for curve/easing selection.
   `/joinintro remove <index>`, `curve <LINEAR|CATMULL_ROM>` and
   `easing <LINEAR|SMOOTHSTEP>` edit the path. Editing requires no active intro.
4. Test every scene on a graphical, unmodified Minecraft 1.21.11 client. Tune
   points, duration and easing for the actual build. Protocol smoke tests do not
   establish visual smoothness, nausea comfort or scene framing.

Cinematics saves mode, flight flags/speed and original location in its own
`cinematics.db` **before** changing mode. It preloads a bounded chunk set
asynchronously and holds plugin tickets during playback. A safe landing must
be passable, above solid nonhazardous ground, inside the border, and loaded.
Failed/denied landings retain the journal and activity lock for administrator
repair and `/joinintro skip` retry. Recovery is checked before intro eligibility
on reconnect. Do not delete `intro_pending` to dismiss a stuck player. Repair a
safe spawn, reload, then retry. If persistence is unavailable, restore database
access first. Never manually edit a database while the plugin is running.

Modes: `FIRST_JOIN`, `EVERY_JOIN`, `NETWORK_SESSION`. Without a trusted session
provider, NETWORK_SESSION falls back to FIRST_JOIN. Previews do not mark a player
seen. `skip-marks-seen` controls skips of ordinary introductions. At most 32
introductions run concurrently. Player inventory, health, potion effects and
LuckPerms ranks are not replaced. An externally changed gamemode is preserved.

## Configuration

`plugins/PolderLobby/` contains:

| File | Purpose |
|---|---|
| `foundation.properties` | Existing foundation master setting |
| `lobby.yml` | Module switches, theme, navigation, network cache, cosmetics, welcome, daily, events, gadgets |
| `parkour.yml` | Courses, checkpoints, timer/reward/anti-teleport settings |
| `locations.yml` | Portal corners, hologram anchors/types, secrets, Citizens action bindings |
| `messages.yml` | Welcome/transport/activity/secret notification text |
| `lobby.db` | Separate UUID profiles, playtime, noncash cosmetic tokens, reward sources, selections, course records and event state |

`plugins/PolderCinematics/config.yml` configures mode, scenes, titles, sound and
particle switches, duration, curve/easing, chunk limit, points and spawn.
Its database never contains SMP economy accounts. Each database has its own
versioned migration marker; newer unknown schemas are refused.

All twelve lobby modules can be disabled independently under `modules`.
`/lobbyadmin reload` validates a candidate configuration before replacing it,
cancels activities, releases gadget flight and removes plugin visuals. Invalid
syntax/limits retain the running configuration. Poll intervals need a restart.
`/joinintro reload` validates then stops current intros using the recovery path.
Disabling modules preserves unlocks and history. Configure Citizens and
DecentHolograms before startup; newly installed integrations need a restart.

Use a dedicated lobby world. `protect-world` cancels player damage, block
break/place and container access for ordinary players in the configured spawn
world. `polder.lobby.build` bypasses those guards for builders. This is not a
replacement for your established region protection rules. No module changes
terrain, invokes destructive explosions or deals gadget damage.

## Commands and permissions

| Command | Permission / use |
|---|---|
| `/lobby` | `polder.lobby.use`, main 54-slot menu |
| `/profile [cached-player-name]` | `polder.lobby.profiles` |
| `/daily` | `polder.lobby.daily`, claim from the GUI |
| `/cosmetics [preview <id>]` | `polder.lobby.cosmetics` |
| `/gadgets <grapple|blaster|staff|off>` | `polder.lobby.gadgets` |
| `/parkour [start <course>|reset|quit|top <course>]` | `polder.lobby.parkour` |
| `/lobbyevent [status|top]` | `polder.lobby.events` |
| `/lobbyevent start <race|treasure|collectible>` | `polder.lobby.admin` plus events permission |
| `/lobbyevent stop` / `schedule <seconds>` | Same; schedule changes persisted next run time; automatic must be enabled |
| `/lobbyadmin ...` | `polder.lobby.admin`, default operator |
| `/joinintro skip` | Available for recovery; auto eligibility uses `polder.intro.use` |
| `/joinintro <editor/admin action>` | `polder.intro.admin`, default operator |

Player feature permissions default true; administrative/build permissions default
operator. NPC, portal and secret access have their own `polder.lobby.<module>`
permissions. Server selector requests also require `polder.lobby.selector`.
Commands provide tab completion. Player-only commands reject console use.

### Parkour

Create up to 21 courses:

```
/parkour create ridge
/parkour setstart ridge
/parkour checkpoint ridge 1
/parkour checkpoint ridge 2
/parkour setfinish ridge
/parkour top ridge
```

Stand at each actual safe location when saving. Checkpoints must be sequential
1..N (max 32), and start/finish/checkpoints must share a world. Remove with
`/parkour deletecheckpoint ridge 2` or `/parkour delete ridge`. Editing cancels
runs to prevent old/new course mixing. Configure nonoverlapping points and a
reasonable minimum duration for your build. A 1.2-block sphere triggers each
point; participants must hit checkpoints in order. `/parkour reset` returns to
the last checkpoint with a configurable timer penalty. `/parkour quit` returns
to the safe origin. Teleports, death, changed gamemode, flight, riptide, vehicles,
large movement steps and disconnect cancel the run. Timers use monotonic time;
active timers are not restored after restart. Only a first completion per course
awards tokens; each run ID and personal best are persisted transactionally.
Gadgets/cinematics cannot share the parkour activity lock. This is validation
against common exploits, not a substitute for your server's anti-cheat.

### Portals, holograms and NPCs

```
/lobbyadmin portal survival pos1
/lobbyadmin portal survival pos2
/lobbyadmin hologram survival status
/lobbyadmin npc 12 selector
```

Stand at opposite region corners for the portal, then above it for its optional
hologram. Hologram kinds: `online`, `status`, `kills`, `richest`, `king`, `events`,
`parkour`. Set `holograms.parkour-course` in lobby.yml to choose a course (the first
configured course is the fallback). Counts and shared statistics are cached;
missing network fields display **Unavailable**, never fictional zeroes.
A known DecentHolograms API is used when installed; otherwise owned TextDisplays
provide a bounded fallback. Updates occur every five seconds, not every tick.
An empty parkour leaderboard says no records yet.

NPC bindings use **existing Citizens IDs**. Create/configure the NPC in Citizens,
then bind `selector`, `cosmetics`, `parkour`, `profile` or `daily`. No NPC is
created without Citizens. Incompatible/missing APIs log an explicit inactive
state. Remove locations with `/lobbyadmin remove <portals|holograms|secrets|npcs> <id>`.
There are at most 64 locations per type. Only this plugin's marked visuals are
removed at reload, chunk load, disconnect or shutdown.

### Cosmetics, gadgets, secrets, daily and events

Cosmetics are visual only: trails, bobbing display hats, wings, AI-disabled bunny
pets, arrival particles and floating titles. They never replace armor/inventory
or LuckPerms metadata. The shop offers right-click five-second previews, unlock,
selection and a clear-selections button. Visual player count is capped (32 by
default), with updates twice a second. Catalog IDs and categories are stable
keys; renaming an ID creates a distinct unlock. Cosmetic tokens are noncash
progress points: no exchange, transfers, purchases with SMP currency or automatic
balance imports. An already unlocked cosmetic costs nothing to select again.

Double-jump uses the flight toggle only while this module owns the flight
permission; it relinquishes it before activity snapshots and on cleanup. Select
other gadgets with `/gadgets` and sneak-right-click **empty-handed** to use them.
Grapple targets solid blocks within 12 blocks. Blaster/staff are visual beams;
launch pads use the configured material. Shared player cooldown prevents spam.

Register a secret at its actual location:

```
/lobbyadmin secret attic star
/lobbyadmin secret library room
/lobbyadmin secret wreck treasure
```

Edit each entry's `tokens`, optional `unlock` catalog ID and `announce` in
locations.yml. Standing within 1.5 blocks collects it once per UUID; records and
reward changes commit in one SQLite transaction. Reusing a secret ID preserves
its claim history; use a new ID for a new collectible. Daily rewards use a
calendar date in `daily.timezone` (Europe/Amsterdam by default), consecutive-day
streaks and a unique UUID/date claim. Backward clock/date changes cannot reclaim
older dates. A GUI is required to claim; mere login does not silently pay.

Mini-events provide a persistent countdown and boss bar. Races use the configured
`events.course` and fastest valid completion begun after the event started;
treasure hunts count distinct treasure secrets, collectible challenges count
distinct stars. Top three eligible participants receive noncash cosmetic tokens;
the winner also unlocks the champion title. Minimum participants and intervals
are configurable. The database reserves finalization and rewards in one
transaction. Restart marks unfinished events interrupted without payouts. A
persisted next run time survives restarts. Automatic scheduling defaults **off**.

Welcome effects begin only after the intro releases the player. First-join
fireworks cannot cause damage. Navigation items use PDC identities and occupy
only empty inventory slots; unrelated items are preserved. Fill an inventory
slot to prevent its automatic use. Ordinary players cannot drop navigation items.

## Network and placeholders

Phase 8 defines `nl.poldersmp.common.network.NetworkBridge`, API version 1. A
trusted Paper-side adapter registers a Bukkit service with that fully qualified
interface name. Calls use JDK-only values across shaded class loaders. Capabilities:
`status`, `profiles`, `sessions`, `access-checked-transfers`. All methods return
CompletableFuture. The transfer provider **must recheck proxy restrictions at
execution**. Clients and incoming plugin messages are never trusted providers.

Status fields: `online` boolean, `locked` boolean, `players` nonnegative number;
optional `kills`, `richest`, `king`, `events` contain display values/list lines.
Profile fields include `kills`, `deaths` and other bounded display values. Status
refreshes at the configured interval, expires after max-age and times out after
three seconds. Transfers time out after five seconds. No provider, stale data,
locked/offline status or missing access-check capability denies the transfer.
There is no direct backend Connect packet fallback. **Phase 9 implements the
actual proxy provider; Phase 8 does not promise working network transfers.**

Optional PlaceholderAPI 2.11.6 expansion (cache-only, no database/network calls
from placeholder threads): `%polderlobby_playtime%`, `tokens`, `streak`, `rank`,
`achievements`, `unlocks`, `kills`, `deaths`, `status`, `online`. Rank reads the
cached LuckPerms user/primary group without changing ranks. Offline network/rank
data may remain unavailable; profiles use cached known names and UUIDs.

## Acceptance and continuation

See BUILD-REPORT.txt for executed checks and actual CI results. The automated
native smoke harness is documented under polder-lobby/src/integration/README.md.
Pending acceptance must be reported honestly: graphical Minecraft camera review,
third-party NPC/hologram compatibility matrix, large-player endurance and real
Phase 9 proxy integration. Do not treat protocol clients as a graphical camera
sign-off or enable unsupported integrations based solely on successful compilation.
