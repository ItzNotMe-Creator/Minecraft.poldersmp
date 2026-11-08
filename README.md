# PolderSMP — Phase 1 foundation

Four-module Java 21 / Maven project. This repository was empty when cloned; this
foundation was created with the owner's permission. No previous implementations,
build reports, or tests were present to preserve.

## Supported versions

- Minecraft / Paper **1.21.11**, `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`.
- Velocity **3.4.0**, `com.velocitypowered:velocity-api:3.4.0-SNAPSHOT`.
- JDK **21** (a JRE alone is insufficient), Maven **3.9.11** recommended.
- Official API repository: https://repo.papermc.io/repository/maven-public/
- Maven Central: https://repo.maven.apache.org/maven2/

## Build

Run `mvn -B -U clean verify` from the repository root. Outputs:

- `polder-core/target/PolderCore.jar`
- `polder-lobby/target/PolderLobby.jar`
- `polder-velocity/target/PolderVelocity.jar`

GitHub Actions runs the same reactor build and uploads the three shaded plugins
and test reports. `python3 scripts/package.py` also produces `dist/` with
individual JARs, source and configuration ZIPs, and SHA-256 checksums. The shared module and SQLite driver are bundled; server APIs
are provided by the host and must not be bundled. Snapshots are resolved from
the official repository; use the versions above, rather than silently upgrading
an existing Minecraft installation.

## Install and configuration

Stop the target server and back up its plugins/data before replacing JARs.
Place PolderCore on the Paper SMP, PolderLobby on the Paper lobby, and
PolderVelocity on Velocity. Do not install the common JAR separately.
Start with Java 21. Each plugin creates `plugins/<plugin>/foundation.properties`
(Velocity uses its lowercase plugin data directory).

| Setting | Default | Meaning |
| --- | --- | --- |
| `module.enabled` | `true` | Enable that plugin's foundation module |
| `database.enabled` | `true` | Enable Core's SQLite service; reserved on lobby/proxy |
| `debug` | `false` | Diagnostic configuration state, visible in Core debug output |

Booleans are validated strictly. Invalid startup configuration disables a Paper
plugin; Velocity reports initialization failure. Each plugin has its own file.
Module and database toggles require a server restart. `/poldercore reload`
updates supported runtime settings only and preserves current settings on error.
The Phase 1 baseline contains no gameplay; see the Phase 2 review-build section
for the additions on the gameplay branch.

## Core commands

`/poldercore info`, `/poldercore reload`, `/poldercore debug` have matching
`poldercore.info`, `poldercore.reload`, `poldercore.debug` permissions (operator
by default). Tab completion filters by permission. No arguments shows info.
Diagnostics show version, server, Java, module and database readiness without
printing credentials or player records.

## Persistence

Core owns `plugins/PolderCore/polder.db`. A dedicated executor performs database
work away from gameplay threads, with one connection per operation and a busy
timeout. Initialization must finish before readiness becomes `ready`; failure
is logged and disables Core on the server thread. Callers must use returned
futures and schedule any subsequent Paper API work on the server thread.
Shutdown rejects new operations and drains already accepted work.

Schema migration 1 transactionally creates UUID account balances and a transfer
ledger. Schema versions newer than this build are rejected. Migration reruns are
idempotent. Transfers use a caller-supplied UUID transaction key, check funds,
prevent self/nonpositive transfers, and atomically debit, credit and record the
key. Replayed keys do not pay twice. Integer overflow rolls back the transaction.
No command currently creates or spends balances; this is a tested foundation API.

`Database.backup(Path)` queues SQLite `VACUUM INTO` for a consistent standalone
backup and refuses to overwrite an existing destination. Call it asynchronously;
never copy a live SQLite file as a substitute. No automatic retention policy or
public backup command is introduced in Phase 1. Preserve the database and backups
when updating. Never delete worlds or private configuration.

## Dependency diagnosis

The historical HTTP 403 could not be reproduced. Official metadata and artifacts
are reachable in this environment. Maven initially failed with “Network is
unreachable” because it does not automatically use HTTPS_PROXY. A local Maven
settings proxy resolved that. The downloaded JDK additionally needed the system
CA trust store for the environment's TLS proxy. TLS validation remained enabled;
no unofficial mirrors, substituted APIs or insecure certificate flags were used.
Use your environment's proxy configuration and trusted CA store when necessary;
these machine-specific settings are deliberately not committed.

See BUILD-REPORT.txt for executed checks, runtime evidence and remaining limits.
The eventual PolderSMP-USB-Deploy.zip is a later deployment deliverable; this phase
supplies foundation plugins and source only.

## Repeat runtime checks

After building, supply official Paper 1.21.11 and Velocity 3.4.0 server JARs:

```
python3 scripts/smoke-test.py --paper-jar /path/to/paper.jar --velocity-jar /path/to/velocity.jar --accept-eula --output /path/to/new-test-directory
```

Review https://aka.ms/MinecraftEULA before using `--accept-eula`. The script
creates fresh localhost-only test servers, checks startup and Core commands,
shuts both down, and retains logs/worlds in the test directory. It requires
Python 3 and Java 21, uses ports 25575/25577, and never modifies an existing
server directory. The offline mode is restricted to these isolated smoke tests;
production proxy forwarding and authentication must be configured separately.
When your environment requires a Java TLS proxy, use its documented
`JAVA_TOOL_OPTIONS`/trust store settings. Server downloads are not bundled.

## Phase 2 review build (unfinished)

Phase 2 lives entirely in PolderCore, on branch `phase2/gameplay`. It is a review
build, not a claim that every Phase 2 acceptance criterion is finished. Common,
Lobby and Velocity implementations are unchanged. Configuration files are copied
on first use without overwriting existing files:

- `weapons.yml`: five legendary weapons, chances, damage, durations, cooldowns,
  dash distance, slam radius, module toggle, particles/sounds, admin acquisition.
- `armor.yml`: independent trim module, numeric strengths, piece scaling,
  activation thresholds, four configurable set patterns and set cooldowns.
- `messages.yml`: GUI titles and principal permission/reload messages.

Commands: `/weapons`, `/weaponinfo`, `/weaponadmin give <online-player> <weapon>`,
`/weaponadmin reload`, `/armorinfo [online-player]`, `/armorcollection`, `/armortop`,
`/armoradmin give <online-player> <trim> <helmet|chestplate|leggings|boots>`,
`/armoradmin reload`. Weapon identifiers are the uppercase names in weapons.yml;
trim identifiers are lowercase vanilla registry keys. Reloads validate before
replacing configuration. Cooldowns are per player and ability, survive equipment
swaps/reconnects in the same process, and reset on server restart.

Permissions: `polder.weapons.use`, `polder.weapons.view`, `polder.armor.use`,
`polder.armor.view` default true; `polder.weapons.admin`, `polder.armor.admin`,
`polder.armor.inspect` default op. Viewing other players requires inspect.
The 54-slot weapon GUI is read-only; preview items never contain weapon PDC.
Issued weapons carry a type and UUID serial, require the matching base material
and a single-item stack, and cannot be combined in anvils. Activation rejects
multiple matching serials in online player inventories. This is not a durable
registry of every copy in offline inventories or containers.

Armor uses the supported ArmorMeta/ArmorTrim API and live trim registry. All 18
patterns in Paper 1.21.11 are detected and represented in collection/grant tools.
Host, Flow and Guster have configurable additional speed defaults. Unknown future
patterns can use `additional-patterns.<key>.speed`. Numeric effects scale by
matching pieces; mixed patterns count separately. Numeric modifiers use stable
plugin keys, replacing rather than accumulating bonuses. Inventory/equip changes
are reconciled every 10 ticks; equipment-change, death, quit and world-change
handlers clean effects. Plugin-owned potion effects are tracked; external potion
changes relinquish ownership, and pre-existing potions are never overwritten.
Continuous effects have short leases; temporary proc effects use their durations.

Discovery data lives in a separate `armor.db` with transactional schema version
1, UUID/pattern uniqueness, async reads/writes and a UUID-based top-ten list.
Disabling the armor gameplay flag does not erase collection history.

Damage uses ordinary cancellable Paper damage events and checks world PvP and
scoreboard friendly fire for weapon damage. Dash checks body collision, ground,
world border and teleport cancellation. Slam has line-of-sight/radius checks and
no block changes. Lightning is visual-only. A Bukkit ServicesManager
`AbilityGuard` provider can approve movement and damage through external region
systems. Without a provider, dash and slam fail closed when WorldGuard,
GriefPrevention or Towny is detected. No bundled adapter for those plugins has
been implemented yet; other protection plugins must supply a guard or be tested
and integrated before enabling these movement/area abilities.

### Exact continuation task

Finish Phase 2 acceptance on `phase2/gameplay`: implement and integration-test
protection adapters; add acquisition options beyond configurable admin grants;
finish durable weapon-serial authority across offline/container transfers;
complete per-ability duration/cooldown/effect and GUI configuration; verify potion
ownership and cleanup with real competing plugins; add multiplayer combat,
collision, PvP/friendly-fire, equipment/break/death/respawn/world-change, GUI and
anti-duplication regression tests. Re-run the real-server probe, Maven and CI,
update this report, then promote the draft only after those criteria pass.
Do not implement Phase 3.

## Phase 3 — Bounties and King of the SMP

Branch `phase3/competitions` continues `phase2/gameplay`; the Phase 2 review
limitations remain unchanged. Phase 3 adds only Core competition code, its
configuration/tests, documentation and an artifact workflow. It does not change
Common/Lobby/Velocity implementations or implement Phase 4.

### Economy and durable payouts

Both systems use the **existing Phase 1 `polder.db` accounts ledger**, in whole
currency units. No separate currency, balance table, minting command or automatic
Vault conversion is added. Phase 7 can supply a Bukkit `Economy` service before
Core starts; its debit/credit implementation must participate in the supplied
SQLite connection/transaction and have no external side effects. Without one,
the Phase 1 implementation is selected. A conventional non-idempotent Vault
API cannot safely promise exactly-once credits across a process crash; it is
therefore not silently substituted or mirrored. This implements the existing
local-economy option rather than introducing a competing Vault balance.

Bounty contributions debit existing balances and become escrow in one
transaction. A confirmed claim atomically consumes that escrow, records the kill
and reserves a unique payout. The recovery worker credits the recipient and
marks that payout completed in the same SQLite transaction. Restarting or
repeating a claim/recovery cannot pay again. Failure leaves a pending payout;
credit overflow rolls back only that payout so other recipients can still be
paid. Recovery runs every five seconds. `/king status` shows the pending count;
`competition_payouts` retains status, attempts, last error and completion times.
No gameplay handler waits for SQL.

This foundation does not create starting money. An existing funded Phase 1
account or a future transactional economy provider is required to place a bounty.
Test account funding occurs only in test fixtures. Back up `polder.db` normally;
its existing backup API includes all competition data.

### Bounties

`bounties.yml` independently enables bounties and configures funding limits,
large-bounty announcements, anti-farming times, same-address checks, particles,
sounds and the 54-slot GUI. Names resolve only from Paper's player cache; UUIDs
support uncached/offline players without blocking Mojang lookups.

- `/bounty <known-player|UUID> <whole-amount>` adds your funded contribution.
- `/bounty info <player|UUID>` shows the current total and recent claim history.
- `/bounties` and `/bounty top` open a ranked player-head GUI with bounty totals,
  bounty-kill statistics, contributor counts, target UUIDs and pagination.
- `/bountyadmin clear <player|UUID>` queues refunds of all unclaimed contributions.
- `/bountyadmin set <player|UUID> <amount>` changes the total. Increases debit the
  administrator's existing balance; console increases require a configured,
  funded `admin-funding-account`. Reductions refund original contributors in
  contribution order. Admin set never creates currency.

`polder.bounty.use` defaults true; `polder.bounty.admin` defaults op. GUI previews
cannot be removed through click, drag, shift or hotbar operations.

Claims require a real PlayerDeathEvent and a final direct-player or player-shot
projectile damage source matching the credited killer. Environmental kills,
self-claims, same-scoreboard-team claims, disallowed world PvP and claims rejected
by a registered AbilityGuard are excluded. Survival/adventure modes, minimum
victim play/life times, optional same-address blocking and a persistent symmetric
killer/victim cooldown reduce farming. Defaults block the same pair for 24 hours.
Same-address checks require correctly configured Velocity forwarding; households
may need an operator-reviewed exception by disabling that setting. These measures
cannot prove that two independently connected accounts belong to different people.

### Weekly King competition

`king.yml` defaults to Saturday **20:00 Europe/Amsterdam**, radius 7, capture time
120 seconds, maximum active duration 20 minutes, minimum 2 eligible participants
and a 60-second countdown. Scheduling uses zone-aware Java time, including DST.
A due event waits up to five minutes for participants; a missed window is skipped
instead of replaying historical events. Weekly slots are unique in SQLite.

Set up the arena on a Paper PvP world with `/king setarena`, then stand on the
objective and run `/king setcapturepoint`. The arena is a configurable spherical
area (radius 64 by default); the objective must lie inside it. Commands persist
positions in king.yml without editing worlds or protection settings. Configure
region/claim plugins to permit intended arena PvP; this plugin does not bypass
those plugins or force-enable a world's PvP flag.

`/king` opens the 54-slot information GUI. `/king status`, `/king current`,
`/king top`, `/king history` provide text views. `/king start`, `/king end`,
`/king setarena`, `/king setcapturepoint`, `/king reload` require
`polder.king.admin` (op by default). `polder.king.view` and `polder.king.play`
default true; `polder.king.exempt` and `polder.king.current` default false.
Exempt, vanished, invulnerable, dead, spectator and creative players do not count.

Only one eligible player inside the capture sphere can make progress, with at
least the configured participant count currently in the arena. Another candidate
resets progress; an empty objective resets it. Contention pauses by default, or
resets with `reset-when-contested`. Progress is checkpointed each second and
never accrues while the server is offline. The event ID, deadlines, candidate,
progress, participants and history survive restart. An expired event ends without
an invented winner. Keep arena/capture rules unchanged while a persisted event is
active, including when editing files during downtime; online reload/reconfiguration
is blocked during active events. A server shutdown suspends the event rather than
canceling its reserved prize. Boss bars/tasks are removed on shutdown and restored
when appropriate after startup.

The title is `[👑 King] PlayerName`, represented by a plugin-owned
`polder.king.current` attachment and an optional chat-renderer prefix. It adds no
LuckPerms group and never replaces Owner/Admin/staff groups or prefixes. Chat
plugins can use this separate permission and disable `title.chat-prefix` if they
manage their own title rendering. Changing King removes the previous attachment
and assigns the new one; current King is restored from SQLite on login/restart.

By default the reward is the title/statistics and **zero minted money**. Set
`rewards.money` and a funded existing `rewards.treasury` UUID to award cash.
Cash is reserved when an event starts, paid once to a valid winner, or refunded
on cancellation/timeout/admin end. Ending an event never chooses an arbitrary
leader. Current King changes only after a successful persisted victory. Financial
changes, victory recording and payout reservation are transactional.

`/king reload` validates and reloads both competition configs/messages while idle.
The foundation database must be enabled for both systems. Disabling bounties does
not discard escrow; already reserved payouts still recover. Disabling King cancels
an existing event and queues any treasury refund on its next tick.

### Competition persistence and verification

`db/competition/V1__competitions.sql` has independent `competition_schema` tracking
inside the existing ledger database. It creates contribution/claim/request/payout,
event/participant/victory/scheduling tables, a unique live-event index, and normalized
`bounties`/`king_statistics` views. It does not overwrite the Phase 1 schema/version
or Phase 2's armor database. Unknown future competition schemas are rejected.

`CompetitionTest` covers calculations/rankings, contribution/refund conservation,
idempotency, competing claims, anti-farming, failed-credit isolation, restart
recovery, capture contention/winner selection, Amsterdam DST scheduling,
configuration validation, active event checkpoints/history and migrations.
The Phase 3 CI artifact includes PolderCore.jar, all YAML files, full source and
test reports. See BUILD-REPORT.txt for actual executed build and runtime results.
