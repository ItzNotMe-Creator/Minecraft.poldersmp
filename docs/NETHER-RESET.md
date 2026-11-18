# Automatic Nether reset (Phase 6)

The Nether resets every **120 hours (5 days)** with a **new random seed**. The Overworld and the End are
never reset, moved or deleted.

> Status: the pure logic, the state machine, backups, path safety and the maintenance tool are covered by
> unit tests that were run. The Paper plugin part (world creation, portals, teleporting) is compiled by
> GitHub Actions, but **has not been run on a real Paper 26.3 server yet**. Follow "First-time setup and
> dry run" below before trusting it with a real world.

## How a new seed is possible (the honest version)

In Paper 26.x the whole main world has **one** seed. The Nether is only a sub-folder
(`world/dimensions/minecraft/the_nether`). Deleting that folder, or changing a seed in a config file, would
regenerate terrain with the *same* seed (or do nothing at all). So that is not used.

Instead the reset works with a **separate Nether world**, the "resource Nether" (key
`polder:resource_nether`), which is created with its own seed (`WorldCreator ... .seed(x)`). Every reset:

1. archives the old resource Nether folder into a verified backup,
2. picks a new random seed that was never used before,
3. lets the plugin create the world again with that seed after the server restarts,
4. **checks that `World.getSeed()` really equals the new seed** (and differs from the old one). If not, the
   reset is reported as FAILED and the Nether stays closed. A seed is never faked.

Nether portals lead to the resource Nether (`block-primary-nether: true`). The normal vanilla Nether of the
main world is left untouched and unused.

## What happens during a reset

| Step | Who | What |
|---|---|---|
| 1 | plugin | Announcements 30 min, 10 min, 5 min, 1 min, 10 s before |
| 2 | plugin | Nether closed: portals refuse entry (phase `LOCKED`) |
| 3 | plugin | Players in the Nether are teleported to a safe Overworld spot near spawn (`EVACUATED`) |
| 4 | plugin | `Bukkit.savePlayers()` + save all worlds (`SAVED`) |
| 5 | plugin | `AWAITING_MAINTENANCE`. Only if `automation.shutdown-enabled` is true: controlled `Bukkit.shutdown()` (never a kill) |
| 6 | tool | Verifies the server is really stopped (session.lock + game port), never kills anything |
| 7 | tool | Verifies the exact Nether folder and refuses unsafe paths (`MAINTENANCE_RUNNING`) |
| 8-9 | tool | Moves the old Nether to `backups/nether/<UTC time>_<operation>` and verifies it with SHA-256 manifest |
| 10 | tool | Picks the new seed, saves state `PREPARED` |
| 11-12 | tool / you | Start the server (`start` only with a verified launcher; otherwise you start it normally) |
| 13 | plugin | Creates the Nether with the new seed and verifies it (`VERIFYING`) |
| 14-15 | plugin | Re-opens Nether access, announces completion, schedules the next reset (`IDLE`) |

The plugin never deletes or moves a world folder. Only the offline tool archives the old Nether, and only when
the plugin said `AWAITING_MAINTENANCE` **and** the server is verifiably stopped.

## Installation

1. Build or download the artifacts (GitHub Actions run of this branch -> artifact `PolderSMP-build`):
   `PolderCore.jar`, `PolderMaintenance.jar`, `nether-maintenance.cmd`.
2. Use **Paper 26.3** and **Java 25** for the SMP server (that is what this build targets).
3. Put `PolderCore.jar` in the SMP server's `plugins` folder and start the server once. It creates
   `plugins/PolderCore/nether.yml` and the world `polder:resource_nether`.
4. On the Windows PC, put `PolderMaintenance.jar` and `nether-maintenance.cmd` in a folder of your choice
   (Java 21+ needed). All tool commands take `--server-dir "C:\path\to\smp"` (default: current folder).
5. Give admins `polder.nether.admin` (default: operators). Everyone has `polder.nether.use`.

## Configuration (`plugins/PolderCore/nether.yml`)

See the comments in the file. Important settings:

| Key | Default | Meaning |
|---|---|---|
| `interval-hours` | 120 | Hours between resets |
| `world.key` | `polder:resource_nether` | Name of the resource Nether world |
| `block-primary-nether` | true | Portals lead to the resource Nether only |
| `announcements.seconds-before` | 1800,600,300,60,10 | Announcement times |
| `automation.shutdown-enabled` | **false** | Plugin stops the server itself. Needs `launcher-verified` |
| `automation.launcher-verified` | **false** | You confirm the start/restart procedure was tested |
| `backup.keep` | 3 | Verified backups to keep (newest is never deleted) |
| `verify.require-new-seed` | true | Reset only counts if the seed really changed |

A wrong value stops the feature from starting (it logs which key is wrong). Nothing silently falls back on
something unsafe. Changes need a server restart.

## First-time setup and dry run (do this once, on a COPY of the server)

Do **not** try this first on your live world.

1. Copy the whole SMP server folder to a test folder and start the copy with PolderCore.
2. In the game: `/netheradmin reset now confirm`. Watch it close the Nether, move you out and save.
   The log says `Nether reset is READY for maintenance`.
3. Type `stop` in the server console and wait until the console window is gone.
4. Run `nether-maintenance.cmd status --server-dir "C:\test-smp"` - it must say `Server stopped: yes`.
5. Run `nether-maintenance.cmd run --server-dir "C:\test-smp"`. It archives the old Nether and prints a new seed.
6. Start the test server with your normal start script. The log must say
   `Nether reset verified: new seed ... is active`.
7. Walk through a portal and check the Nether is new. Check `backups/nether/...` exists.
8. Check that your start script restarts the server cleanly. **Only then** set in `nether.yml`
   `automation.launcher-path: "start.cmd"`, `automation.launcher-verified: true` and, if you want the plugin to
   stop the server by itself, `automation.shutdown-enabled: true`.

Until step 8 is done, no unattended shutdown ever happens.

## Day to day

Players: `/nether` (next reset), `/nether next`, `/nether info`.

Admins: `/netheradmin reset confirm` (normal countdown), `/netheradmin reset now confirm`,
`/netheradmin pause`, `/netheradmin resume` (gives players a fresh warning window).

When the log says the reset is ready for maintenance (automation off), do steps 3-6 from the list above, then
start the server. Tool commands:

| Command | Does |
|---|---|
| `status` | Phase, next reset, seeds, backups, whether the server is stopped (read only) |
| `run` | Archives old Nether + prepares the new seed (server must be stopped) |
| `start` | Starts the server through the verified launcher (only after `run`) |
| `recover` | Rolls back an unfinished/failed reset from the verified backup (backup is kept) |
| `verify` | Checks state, paths and backup (read only) |
| `cleanup` | Applies backup retention |

Exit codes: 0 done, 1 failed, 2 refused (nothing was changed). Every action is written to
`logs/nether-maintenance.log` in the server folder.

## When something goes wrong

* `status` shows `FAILED`, `MAINTENANCE_RUNNING` or `RECOVERING`: an earlier reset did not finish. The Nether
  stays closed. Stop the server, run `recover`, start the server. New resets are refused until this is done.
* The tool says it is **refused**: read the message; nothing was changed. Typical: server still running, wrong
  phase, or a path that is not safe.
* Verification failed after the restart (seed mismatch): the Nether stays closed, state is `FAILED`. Stop the
  server and run `recover` to bring back the old Nether, then report the log.
* The state file is damaged: the plugin refuses to guess, keeps the Nether closed and logs it. Fix or remove
  `plugins/PolderCore/nether-state.properties` only after checking `backups/nether`.

## Safety rules built in

* The tool refuses the Overworld, End, vanilla Nether, any folder with `level.dat`, the server root, anything
  outside the server folder, symbolic links, folders with unknown content, and backup folders inside a world.
* A running server is detected via `session.lock` and the game port. "Unknown" counts as running.
* No code anywhere stops or kills a process. Shutdown is only `Bukkit.shutdown()` from the plugin.
* Retention only deletes backups that were verified, always keeps the newest verified backup, and never
  deletes unverified backups.
* The old Nether is moved, never deleted; recovery copies it back and keeps the backup.

## Known limits / to verify on a real server

* `World.getWorldPath()`, `WorldCreator.ofKey(...)` and the folder Paper uses for the custom world were
  written against the documented Paper 26.3 API but not run. The plugin records the real folder path into the
  state file and the tool refuses anything that is not safe, so a wrong guess fails closed. The dry run
  above checks this.
* Worlds created by API are re-created by the plugin on every start; check the dry run shows the same
  Nether before and after a normal restart.
* Portal routing (1:8 scaling, portal search/creation) uses `PlayerPortalEvent` and needs an in-game check.
* The one-second tick and the state file write run on the server thread; the file is tiny.
