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

## Phase 4 — Crates and secret vaults

`phase4/loot` continues Phase 3 and adds only Core loot code plus its configuration,
tests and artifact workflow. The Phase 2 review limitations remain recorded;
this phase neither replaces older systems nor implements Phase 5.

### Crates, keys and reward tables

All five tiers are configured in `crates.yml`: COMMON, RARE, EPIC, LEGENDARY,
MYTHIC. Each has a nonempty positive integer weighted table. Common includes
resources, diamonds, food and experience bottles; Rare includes enchanted gear
and valuables; Epic includes netherite, armor and collectibles; Legendary/Mythic
include canonical weapons, trimmed equipment and exclusive collectibles.
Experience is delivered as usable bottles so it follows the same recoverable
physical-item protocol as other rewards. Armor uses the vanilla trim API;
weapons call the existing `Weapons.create(Kind)` factory with the same type/serial
identity and ability configuration, never a copied lookalike implementation.

Commands:

- `/crates` opens the 54-slot tier menu, then a 54-slot weighted reward preview.
- `/crates keys` shows physical keys and requests mailbox delivery.
- `/crates top` shows persistent opening statistics.
- `/crateadmin set <type>` registers the empty single chest/barrel you look at.
- `/crateadmin remove` unregisters that crate without deleting its block.
- `/crateadmin givekey <online-player> <type> <amount>` queues 1–64 physical keys.
- `/crateadmin reload` validates/reloads both loot configurations.

`polder.crates.use` defaults true, `polder.crates.admin` defaults op. Registered
crates are protected from ordinary breaking, explosions, pistons and hopper
transfers; remove their registration before moving them. Keyless/mismatched-key
interactions show the preview. A matching registered key is consumed once, the
reward is selected and persisted once, and the 60-tick animation displays that
already selected outcome. Preview and animation inventories reject extraction.
Sounds, particles, rare announcements, weights and animation time are configurable.

Each key has PDC tier and UUID fields **and an issued record in SQLite**. A PDC
lookalike without a record is invalid. Redemption atomically transitions ISSUED
to USED and reserves the selected reward. Copying an item cannot create another
redeemable key; all copies share that spent record. Keys are single items rather
than stacks of interchangeable UUIDs. Statistics count committed openings, even
if the player disconnects before the cosmetic animation finishes.

### Delivery and interruption recovery

All rewards, including granted keys, enter a durable UUID-owned mailbox. A full
inventory defers delivery; nothing is thrown on the ground, discarded or rerolled.
One free storage slot is required because each delivery has its own receipt tag.
Mailbox delivery runs automatically while online and resumes after reconnect or
restart. Registered source IDs also make external `Loot.grantKey` integrations
idempotent; call this API on the Paper server thread (the returned future tracks
the asynchronous database commit), and use the same stable source ID when retrying the same earned reward.

The state machine is WAITING → DELIVERING → DELIVERED. The payload and unique
receipt are saved before touching inventory. During DELIVERING, receipt-tagged
items are locked against ordinary moving, dropping, consuming and use; a death
retains provisional items. Inventory insertion and Paper's player save occur on
the server thread, then the database confirms completion and unlocks the item.
On recovery, an existing receipt in the owner's inventory is confirmed instead
of inserted again. A missing provisional item is inserted from the persisted
payload. Completed receipts are never issued again if their item was used or
traded. Old completed receipts are prevalidated after login/restart.

SQL uses the existing async Foundation manager. This protocol assumes Paper owns
its player inventory persistence; an external cross-server inventory synchronizer
must preserve receipts and save ordering and requires its own integration. Do
not strip receipt PDC or restore the economy database independently of player
and world backups. There is no new currency or direct unrecoverable XP grant.

### Secret vaults

`vaults.yml` has its own enabled flag, five rarity weights and independent custom
loot tables. Defaults: a spawn attempt every 30 minutes, 30-minute expiration,
one-hour per-player discovery cooldown, at most three active vaults. Spawn and
claim effects are configurable. The trap applies short Slowness/Blindness only
when no corresponding effect already exists; it causes no explosion, terrain
fire or direct damage that could bypass region PvP rules.

**Set `worlds: [your-overworld-name]` explicitly** before enabling placement.
An empty allowlist spawns nothing. `/vaultadmin spawn <rarity>` uses the same
random safety checks as scheduled spawning; it cannot force unsafe placement.
`/vaultadmin remove` conditionally cleans the nearest vault within 16 blocks.
`/vaultadmin reload` reloads loot settings. `/vaults` displays active/restoring
counts and the persistent discovery leaderboard. `polder.vaults.use` defaults
true; `polder.vaults.admin` defaults op.

Candidate selection is intentionally conservative:

- Overworld only, with the entire clearance buffer inside the world border.
- Nine surrounding chunks must be uninhabited, contain no generated structures
  and contain no tile entities/containers. Players must be at least 128 blocks away.
- Natural, level, supported ground plus a conservative natural-material buffer;
  the entire structure footprint must be air. Existing blocks are never replaced.
- Chunks load/generate asynchronously using Paper APIs, with temporary tickets.
  Set `allow-new-chunks: false` to restrict candidates to existing terrain.
- A registered Bukkit `VaultProtection` provider must approve the complete buffer.
  If a protection plugin named in `protection-plugins` is enabled without a
  provider, placement fails closed. Adapters also control claiming. Add custom
  claim-plugin names to that list and install a compatible provider before use.

Visited wilderness and many natural structures are rejected on purpose. A server
can report that no eligible position was found; it does not weaken its checks.
Normal player bases are excluded through inhabited chunks, region checks and
crafted-material/container checks. Artificial maps or builds made entirely via
external editors without habitation metadata require an approving region adapter
and a carefully designated world; block appearance alone cannot identify every
possible unclaimed build.

Each tier has a distinct small stone/rarity-accent structure and a sealed barrel
objective. The PLANNED row and original/placed block-data manifest commit before
construction. ACTIVE → CLAIMED is a transactional, single-global-claim transition
which also reserves the loot and records the player cooldown/statistic. An expired,
removed or already-claimed vault cannot grant again. Discovery is announced only
after a successful claim. Neither the physical barrel nor its blocks contain
free extractable reward inventory.

Cleanup first reserves CLEANUP in SQLite to stop claim races, then restores only
blocks still matching the manifest. Modified blocks and nonempty containers are
preserved. The manifest is retained for audit even after REMOVED. Startup rolls
back incomplete plans, cleans interrupted claims/cleanup and retires incomplete
or expired structures. Unavailable worlds retain their restoration metadata.
A shutdown leaves valid active vaults persistent instead of deleting worlds.

### Earlier-phase reward integration

A newly installed loot schema records its installation timestamp. Completed
bounty payouts earned after that timestamp grant one configured key (COMMON by
default). King victories grant one configured key (LEGENDARY by default), including
when the King cash reward is zero. Cash-backed victories wait for the existing
payout to complete. Stable payout/event source IDs prevent duplicate key grants;
existing historical wins/payouts are not retroactively multiplied. Canonical weapon
and vanilla trimmed-armor rewards work with the existing Phase 2 systems.

All tables are in the existing `polder.db`, behind an independent versioned
`db/loot/V1__loot.sql` migration and `loot_schema`. Foundation, competition and
armor schemas/data are preserved. The Phase 4 CI artifact contains PolderCore.jar,
YAML defaults, full source and test reports. See BUILD-REPORT.txt for executed
unit, persistence, real-client and restart checks.

## Phase 5: automatic world events

PolderCore now runs one persistent world event at a time. Enable individual types
in `events.yml`, explicitly allow wilderness worlds with `worlds`, and configure
intervals, weights, warnings, expiration, effects and rewards. The default empty
world list deliberately prevents placement until an operator selects a world.
`/events` lists types; `/events active`, `/events next` and `/events top` show
status, the next scheduled attempt and lifetime participant statistics. Operators
use `/eventadmin start <event>`, `stop` and `reload` (including tab completion).
Permissions are `polder.events.view`, `polder.events.play` and
`polder.events.admin`; `polder.events.exempt` excludes a player from competition.
Reload is refused during an event; its saved configuration remains authoritative.

- METEOR: warning, falling display, impact effects and one sealed resource reward.
- SUPPLY_DROP: falling supply display, one contested treasure claim and a crate key.
- BOSS: configured hostile mob, health/progress bar, cancellable close-range special
  attacks, tracked damage, winner loot and threshold-based participation keys.
- PIRATE_SHIP: air-only ocean deck, mast and sail with two independently claimed
  treasure barrels. `persist-structure` can retain the completed landmark.
- DUNGEON: open-top air-only ruins, configurable enemy waves, damaging trap,
  final boss, participation keys and a final treasure objective.

Meteor/drop displays do not use explosions, fire, terrain changes or falling-block
physics. Boss mobs are restricted to the supported hostile whitelist (zombie,
husk, skeleton, stray, vindicator and piglin brute). Combat uses normal cancellable
Paper damage events; spectators, creative players and exempt players cannot earn
or claim. Mobs stay near the arena and cannot transform, pick up items or change
blocks. Event blocks, containers and projectiles are protected during their
lifetime; no loose vanilla mob loot or experience duplicates the stored rewards.

Placement reuses the Phase 4 protection service and conservative terrain guards,
including asynchronous bounded chunk loading, held chunk tickets, border buffers,
uninhabited chunks, no structures/containers, nearby-player exclusion and natural
terrain checks. Ocean events additionally require ocean biome/water support.
Every new structure block must be air. Dungeon floors are temporary blocks above
existing ground: this system does not excavate player terrain. A named protection
plugin without an approving `VaultProtection` provider fails closed. Adapters must
approve placement and claims; unknown/custom protection plugins need registration
in `vaults.yml`. These restrictions intentionally reject many candidate locations.

The `db/events/V1__world_events.sql` migration adds an independent schema to the
existing `polder.db`. A unique live-event constraint, compare-and-set phases,
per-chest reservations, damage snapshots, actor UUIDs, restoration manifests and
an award outbox cover scheduling through cleanup. Restart retains the original
expiry/configuration, health and participation; incomplete plans roll back and
expired events clean up. Missing worlds retain recovery metadata. Cleanup restores
only matching event blocks and preserves changed blocks/nonempty containers.
Successful/naturally expired ships can persist; administrative cancellation rolls
back their temporary geometry. No worlds are deleted.

Selected item payloads are frozen before reward reservation. Stable event/source
IDs bridge the outbox to Phase 4's durable mailbox, including offline players,
full inventories and replay after interruption. Its tier tables provide resources,
canonical Phase 2 weapons and trimmed armor; keys use the registered crate factory.
Boss/dungeon participation and wins feed persistent `/events top` statistics.
Optional cash rewards use the existing transactional economy service and a funded
UUID treasury: a bounded budget is reserved before the event and unused funds are
refunded. Default money rewards are zero; there is no second currency or minting.

See BUILD-REPORT.txt and `polder-core/src/integration` for executed tests and the
isolated Paper/client fixture. The Phase 5 Actions artifact includes the JAR,
configuration defaults, source archive and separate test reports. Phase 6 is not
implemented. Existing earlier-phase limitations remain documented above.

## Phase 6: managed Nether regeneration

PolderCore includes a separate managed Nether reset module, configured in
`nether.yml`. Default interval: **120 real-world hours**; default backup retention:
three successful verified backups. Enable the module only after reading
[the installation, launcher rehearsal and recovery instructions](maintenance/README.md).
The module and unattended shutdown are disabled by default. Unattended shutdown
requires explicit configuration, an approved current launcher handshake and a
successful supervised reset/restart rehearsal.

The supported approach creates `polder_nether` through Paper's WorldCreator with
its own seed and redirects Nether portals to it. Existing primary Nether builds
are preserved in their original dimension; they are not automatically migrated.
The Overworld and End are never reset. An existing unowned directory cannot be
adopted or overwritten. Players logging in from a saved Nether position return
to the safe Overworld location, avoiding stale coordinates after regeneration.

`/nether [next|info]` shows state/countdown. Operators use
`/netheradmin reset|pause|resume`; permissions are `polder.nether.use` and
`polder.nether.admin`. Pauses/restarts keep the saved deadline. Announcements
occur at 30, 10, 5, 1 minutes and 10 seconds. When due, Nether access closes,
players evacuate and data saves. Without explicitly verified shutdown automation,
the operation waits for a normal manual SMP stop.

The Java 21 maintenance supervisor, with Windows PowerShell wrappers in
`maintenance/`, waits for the actual server process to exit. It verifies world
identity/paths/session locks, copies and hashes the entire managed world, archives
the old directory and starts only the approved Java/Paper launcher. Paper then
verifies the new effective and saved seed plus changed generated terrain/biomes
before reopening access. No loaded world is deleted. Interrupted operations
require explicit recovery by operation UUID, retain their backups, and keep
access closed. The latest valid recovery backup is never pruned.

State, history, operation UUID, last success, next deadline and maintenance phases
persist in `plugins/PolderCore/nether-reset.db`, with a versioned independent
migration. Phase 6 CI packages the JAR/configuration/source/operator scripts and
runs Linux and Windows maintenance checks. See BUILD-REPORT.txt for actual test
results and limits. Phase 7 is not implemented.
