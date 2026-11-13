# Isolated loot integration probe

The production Maven build deliberately excludes `src/integration`. Do not install
this fixture on a real server: it creates a marked `loot_probe_flat` world, grants
test items, changes loot test settings, and manipulates the dedicated LootProbe
client's inventory. Existing unmarked worlds are refused. No worlds are deleted.
Use a separate localhost-only Paper 1.21.11 directory, Java 21 and PolderCore.jar;
review and accept the Minecraft EULA for that test server yourself.

Compile the fixture after `mvn -pl polder-core -am install`, then obtain its API
classpath with `mvn -pl polder-core dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt`.
For example on Linux, from the repository root:

```
mkdir -p work/loot-probe
javac -proc:none -cp "$(cat polder-core/target/probe-classpath.txt):polder-core/target/PolderCore.jar" -d work/loot-probe polder-core/src/integration/java/nl/poldersmp/core/loot/LootLiveProbe.java
cp polder-core/src/integration/resources/loot-probe-plugin.yml work/loot-probe/plugin.yml
jar --create --file work/LootLiveProbe.jar -C work/loot-probe .
```

Install only in the isolated server. Bind that server to 127.0.0.1:25575, use offline
mode for this local-only client and make the test name LootProbe an operator after
it connects. Install the pinned Node client dependencies under `client/`, then run
`npm run probe`. If operator setup is not yet complete, set `POLDER_LOOT_START_FILE`
to a new marker-file path before starting the client, then create that file after
running `op LootProbe` in the isolated console. Remove the marker before repeating
that setup process. No Microsoft/Minecraft account credentials are used.

The client checks 54-slot GUI extraction protection, every crate tier, persisted
reward delivery, replayed keys, every vault rarity, restoration and full-inventory
deferral. It shortens vault cooldown/radius settings only in the isolated server.
Run it again after a server restart to exercise validation of old receipt-tagged
inventory. Success is `ALL LOOT CLIENT CHECKS PASS`; retain both server/client logs.

## World event client

The Phase 5 fixture is `java/nl/poldersmp/core/events/EventsLiveProbe.java` with
`events-probe-plugin.yml`. Compile/package it using the same classpath procedure
above, substituting EventsLiveProbe paths and JAR name. Install in the isolated
server only. It creates marked `polder_events_land_probe` and
`polder_events_ocean_probe` worlds, equips EventsProbe, changes only that test
server's events.yml and shortens warnings/lifetimes. Unmarked worlds are refused.
Make EventsProbe an operator; run `node client/events-client.js` with the pinned
client dependencies installed. Optional `POLDER_EVENTS_START_FILE` delays setup
until that marker exists. The client connects only to 127.0.0.1:25575, offline.

It fights real waves and bosses, claims meteor/drop/ship/dungeon objectives and
checks persisted parcel payloads, unique physical receipts and full manifest
restoration. Success: `ALL WORLD EVENT CLIENT CHECKS PASS`. Selected executed
markers are recorded in `events-verification.txt`. Test fixtures are excluded from
production JARs. Never run them on a live player server.

## Nether regeneration probes

`java/nl/poldersmp/core/nether/NetherLiveProbe.java` and
`nether-probe-plugin.yml` are exclusively for a new localhost-only Paper test
server (127.0.0.1:25576). The fixture refuses a missing `.polder-disposable-test`
marker. It creates a high Overworld evacuation platform, deliberately changes
blocks in the managed test Nether, provides actual portal construction and
checks that the sentinel block disappeared after generation. Never install it
on production. Compile/package with the same Core/API classpath procedure above,
using NetherLiveProbe paths/name. Make only the offline NetherProbe test client
an operator. EULA acceptance is required for the disposable server.

`client/nether-client.js` uses the existing pinned Mineflayer dependency. Run
with `POLDER_NETHER_MODE=evacuate`, `automatic` or `verify` after isolated setup;
optional `POLDER_NETHER_START_FILE` delays commands until operator setup is ready.
The first mode resumes an overdue paused fixture schedule and audits evacuation.
The automatic mode builds/traverses a real portal, marks old terrain, requests a
reset and waits for controlled shutdown. Enable automation only after completing
the documented supervised manual reset/restart rehearsal. Verify mode checks
new terrain and portal routing after restart. Keep server logs as evidence of
full child exit, backup/archive and subsequent seed verification.

`MaintenanceFixture.java` creates synthetic tiny NBT files, not Minecraft terrain.
The Windows CI compiles it separately, invokes the actual PowerShell apply/recover
wrapper, verifies the stored old seed and checks primary fixture files. Production
Maven excludes all integration fixture classes. Selected live pass markers are in
`nether-verification.txt`; no production world is used in these tests.

## Teams / economy / real Vault probes

`java/nl/poldersmp/core/market/MarketLiveProbe.java` with
`market-probe-plugin.yml` is a separate localhost-only fixture for Paper 1.21.11,
Java 21 and offline test clients MarketAlice/MarketBob. It refuses a missing
`.polder-disposable-test` marker and a non-loopback binding. Never install it on
production: setup changes a small high platform and its synthetic clients' items.
Compile/package with the Core/API classpath procedure used above, substituting
MarketLiveProbe names. Integration sources are excluded from the production JAR.

Use a new isolated server at 127.0.0.1:25577, accept the Minecraft EULA for that
server, install the fixture and Core, explicitly select `mode: sqlite` and shorten
only that fixture's auction listing-seconds to 30. Make both test clients operators.
Run `client/market-client.js` using the existing pinned Mineflayer dependencies;
optional `POLDER_MARKET_START_FILE` delays actions until operator setup is ready.
It verifies payments, team invite/accept/chat/friendly fire, 54-slot extraction
protection, auction custody/payment/receipt, shop buy/sell, black-market limits,
expiry returns and balance/ledger consistency. It expects fresh fixture data;
it never deletes a world/database to hide an earlier run.

For Vault testing, use official Vault 1.7.3 and EssentialsX 2.22.0 releases in the
**isolated** server only, set the sample provider's starting balance to zero and
select `mode: auto`. Preserve the existing SQLite balances. These third-party JARs
are not included in PolderCore or build artifacts. Use namespaced Core commands so
an Essentials command alias cannot bypass the tested ledger.

`vault-client.js` verifies provider selection without importing SQLite funds,
real give/pay/shop/set calls, membership restart persistence and a deliberately
injected lost-acknowledgement debit. `POLDER_VAULT_OPERATION_FILE` selects the saved
operation-ID file (default `work/vault-operation.txt`, relative to the launch cwd).
If a fixture run must be repeated, reinitialize only its synthetic provider accounts
explicitly through admin commands; never reset real accounts. The injected call
really debits seven units but deliberately omits acknowledgement. It is a controlled
fault injection, not a claim of forced JVM/power-loss testing.

Stop normally, restart, and run `vault-recovery-client.js` with that operation file.
It checks the debit was not replayed, affected-account payments are held, explicit
applied attestation makes no second withdrawal, a subsequent payment succeeds, and
Foundation balances/old item receipts remain unchanged. `market-guards-client.js`
checks the final Paper event guards for armor-stand/entity use, consumption and
crafting, read-only GUIs, statistics and private-chat handling after a member is
kicked; it restores the test membership. Its known synthetic balances reflect the
preceding completed scenario. The event probes exercise real Paper handlers;
they do not claim an exhaustive packet/third-party plugin matrix.

Selected actual pass markers are in `market-verification.txt`. Retain the complete
private server/client logs when reproducing. All worlds, accounts and receipt data
from the executed tests were retained, with normal server stops between runs.
