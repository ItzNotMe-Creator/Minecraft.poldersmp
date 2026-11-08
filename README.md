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
No gameplay, GUI, routing, economy commands or Phase 2 features are included.

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
