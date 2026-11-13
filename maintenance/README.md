# Managed Nether reset operations (Paper 1.21.11, Java 21)

Read before enabling. This feature uses a **separate** supported Paper Nether,
`polder_nether`, and redirects normal Nether portals to it. It does not migrate
builds/items from the existing primary Nether. The primary `<level-name>_nether`,
Overworld and End stay intact; no reset operation targets them. Do not point
multiverse/world-management plugins at the managed world or its archives.

Paper's inspected 1.21.11 CraftServer.createWorld uses WorldCreator.seed for a
new world, but reads existing level.dat generation options for an existing one.
Paper stores Nether terrain/entities/POIs under `<world>/DIM-1`; level.dat, uid.dat,
world configuration and other root files also matter. Changing a YAML seed or
removing region files alone is insufficient. The worker archives the entire
managed directory offline, and the plugin creates a new NETHER environment with
WorldCreator.seed(newSeed). Verification compares the runtime seed, the saved
Data.WorldGenSettings.seed and generated block/biome fingerprints. A missing
previously verified world is an error, never permission to silently regenerate.

## First installation and rehearsal

1. Stop SMP normally. Back up all server data. Install PolderCore.jar and copy
   these scripts somewhere outside any world directory. Use an ordinary local
   disk with atomic rename, not network storage, junctions or symlinked paths.
2. Start once to create nether.yml. Keep `automatic-shutdown: false`. Choose a
   new dedicated `polder_nether` name (optional `_suffix`), `enabled: true`,
   `interval-hours: 120`, retention at least 1 (default 3). Existing/unowned
   directories cannot be adopted. Select a safe, supported Overworld location.
   Its world must match server.properties level-name. Head/feet must be air,
   solid safe floor, and inside the border. Stop/restart to apply changes.
3. Check `/nether info`: verified access, correct world, future persisted deadline.
   Portal routing applies to players and non-player entities; End portals keep
   their normal behavior. Players logging in with a saved Nether location spawn
   at the safe Overworld destination, avoiding stale coordinates after a reset.
4. Stop normally. Approve the exact Java executable and Paper server JAR while
   stopped, then start **only** through Start-SMP.ps1. Approval pins both files'
   SHA-256 hashes. Changing either requires reapproval and another rehearsal.

```powershell
.\Nether-Maintenance.ps1 -Action approve -ServerRoot 'D:\PolderSMP\SMP' -JavaExe 'C:\Java\jdk-21\bin\java.exe' -ServerJar 'D:\PolderSMP\SMP\server.jar'
.\Start-SMP.ps1 -ServerRoot 'D:\PolderSMP\SMP' -JavaExe 'C:\Java\jdk-21\bin\java.exe'
```

5. Rehearse on a disposable/staging server first. Run `/netheradmin reset`:
   access closes, Nether players evacuate, players/worlds save, and the operation
   becomes WAITING_STOP. With shutdown automation off, stop SMP normally using
   its console. The supervisor waits for the actual Java child to exit, copies
   and hashes the complete Nether, archives the old world, and starts the exact
   approved launcher again. Verify changed seed/terrain and restored portals.
6. Only after this full reset-and-restart verification does the supervisor record
   `restart-verified=true`. You may then explicitly set `automatic-shutdown: true`
   while stopped and restart through Start-SMP. A current process/token/PID/start
   handshake and the persisted successful rehearsal are both required for the
   plugin to call Bukkit.shutdown. A normal launch or stale proof cannot qualify.

The Java supervisor uses fixed `-Xms512M -Xmx2G` arguments and `--nogui`; review
that memory budget before deployment. It never executes a shell command, invokes
taskkill/Stop-Process or kills a process. The Windows wrappers call the same
Java 21 implementation tested on disposable directories. Linux can invoke it
with `java -cp plugins/PolderCore.jar nl.poldersmp.core.nether.NetherMaintenance`.
A normal stop with no WAITING_STOP operation exits instead of restarting forever.
Nonzero server exit stops automation and leaves recovery data for inspection.

## Scheduling and commands

The default is 120 real-world hours. First initialization stores a deadline five
days ahead; each verified success starts the next interval. Restarts and pauses
preserve the deadline. An overdue enabled schedule runs when safety initialization
is ready and it is resumed; downtime never fakes a successful reset. Warnings are
30, 10, 5, 1 minutes and 10 seconds, with persisted warning receipts.

`/nether`, `/nether next`, `/nether info` require `polder.nether.use` (default true).
`/netheradmin reset|pause|resume` requires `polder.nether.admin` (operator default).
Pause affects reset scheduling, not access to a verified Nether. Manual reset
also requires an unpaused, verified idle system. Commands have tab completion.
Changing configuration requires a normal restart. Disabling the feature stops
managing portal access; it does not delete or merge any dimension.

A reset remains REQUESTED/WAITING_STOP until evacuation/save succeeds. Cancelled
teleports or a newly obstructed safe location block shutdown. Unexpected database,
world, backup or verification errors keep Nether access closed and do not announce
success. Never bypass this by manually removing the target world.

## Backups, recovery and retention

State/history live in `plugins/PolderCore/nether-reset.db`, with an independent
versioned migration. Back it up with the rest of the server. The immutable root,
world name and identity prevent retargeting a reset to another dimension. The
maintenance log and launcher proof/configuration live beside this database.

`nether-backups/<UTC timestamp>-<operation UUID>/` contains:

- snapshot/: complete copied world, verified against per-file size/SHA-256 hashes;
- manifest.properties and owner.properties;
- previous-world/: the atomically moved original, after backup verification;
- optional quarantine-* directories retained during recovery.

The tool checks registered PID/start identity, primary and managed session locks,
shared maintenance ownership, exact paths, link/reparse aliases, marker identity,
Nether layout and seed. It refuses an active SMP, duplicate operation, incomplete
archive or unexpected path. All server starts must use the approved supervisor:
the plugin shares the maintenance interlock to prevent loading a target while the
worker copies/archives it. Do not run a second server against the same files.

Inspect and recover only while stopped:

```powershell
.\Nether-Maintenance.ps1 -Action status -ServerRoot 'D:\PolderSMP\SMP' -JavaExe 'C:\Java\jdk-21\bin\java.exe'
.\Nether-Maintenance.ps1 -Action recover -OperationId 'CURRENT-UUID-FROM-STATUS' -ServerRoot 'D:\PolderSMP\SMP' -JavaExe 'C:\Java\jdk-21\bin\java.exe'
.\Start-SMP.ps1 -ServerRoot 'D:\PolderSMP\SMP' -JavaExe 'C:\Java\jdk-21\bin\java.exe'
```

Recovery verifies the snapshot before restoring. Any incomplete new world moves
to quarantine, rather than being overwritten. Before a verified archive, an intact
old world can be retained. Startup verifies the restored old seed, records
ROLLED_BACK and pauses the schedule; inspect before `/netheradmin resume`.
No verified recovery source means refusal and operator intervention. Initial
configuration/safe-location errors with no active operation can be fixed and
restarted; they do not authorize deletion of an unowned directory.

Keep the latest three successful verified backups by default. Offline apply/prune
removes only older owned successful backup containers, always preserving at least
the newest valid recovery snapshot. Incomplete/failed backups are kept for review.
A just-completed cycle may temporarily leave an extra backup until the next offline
maintenance/prune. Increasing retention takes effect on restart. Never manually
remove the latest valid backup or pending operation metadata.

```powershell
.\Nether-Maintenance.ps1 -Action prune -ServerRoot 'D:\PolderSMP\SMP' -JavaExe 'C:\Java\jdk-21\bin\java.exe'
```

Backups protect world files; coordinate external inventories, claim databases,
map renderers and cross-server synchronization separately. Full-server backup
rollback requires the matching reset database and player data. No production
world is used for reset tests. See BUILD-REPORT.txt for actual validation and
platform limitations; unexecuted Windows/runtime tests are not claimed passed.
