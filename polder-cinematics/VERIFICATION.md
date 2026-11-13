# Celestial Arrival verification

Target: Java 21 / Paper 1.21.11, unchanged server API versions. Plugin metadata:
PolderCinematics 1.1.0-celestial. Only the cinematics module and its build/docs
were changed; the other gameplay/lobby/proxy modules are preserved.

## Executed automated build

`mvn -B -pl polder-cinematics -am clean verify` succeeded locally:
**29 tests**, zero failures/errors/skips (Common 11, Cinematics 18).
Tests cover original journal/version/migration/eligibility/recovery behavior,
exact-once choreography with lag/backward time, packet/particle burst budgets,
geometry/rotation/easing/yaw, valid/invalid preset configuration, preserved custom
settings, and the west-facing island route ending at 0,65,0 / yaw90 / pitch-1.
Configuration tests separate the pure parser from Paper's live item registry;
the actual supported materials are exercised on the native Paper fixture.

## Executed native Paper/client checks

A new marked disposable localhost server at 127.0.0.1:25580 ran Paper 1.21.11
build 132 / Java 21. No PolderLobby, PolderCore, ProtocolLib or other gameplay
plugin was installed. The excluded CelestialProbe fixture prepared its own test
platform only; it refuses startup without the disposable marker/loopback port.

The passing full client exercised all three themes, plus the built-in island
preset with the exact confirmed anchor. It observed four totem status-35 pops,
621 camera-entity movement packets, 19,414 particle packets spanning seven
particle types, and camera attach/detach packets. Actual server assertions proved:

- Display-entity spectator camera attachment and interpolation duration 2.
- Original Survival mode restored before the controlled landing ceremony.
- Four orbiting item displays and two rune displays during landing.
- Two real inventory totems, seven diamonds and health 20 unchanged.
- Controlled landing damage rejected; no visual entities or journal rows leaked.
- Exact previous personal-border instance restored (size70); world border stayed80.
- Skip during camera flight cleans up and restores state.
- Logout during landing and reconnect preserves inventory/state with no visuals.
- An intentionally removed landing floor retains safe Spectator recovery; after
  repairing it, skip completes restoration and clears the journal.
- The protected 54-slot studio resisted item extraction.

The expected fault-injection run logs a refused/unsafe landing error. It is a
passing failure-recovery scenario, not a claim that the server logged no errors.
Initial failed harness runs and the immediate-setup section representation bug
were corrected before the passing run. Their private test data/logs are retained.

These are native server/protocol-client tests, not graphical Minecraft rendering
or a measurement of performance on a busy live server. The supplied screenshots
informed the route profile; the actual island world is not present in the test
workspace. Run `/joinintro setup island` and `/joinintro preview` on the real
lobby to accept framing, FOV, comfort and tree/roof clearance before enabling it
for everyone. This is the exact remaining visual acceptance task.

See repository BUILD-REPORT.txt and PR/Actions for additional shutdown/restart
and actual Linux/Windows CI results when recorded. No successful CI result is
assumed before a workflow finishes.

## Final shutdown and standalone restart

With an active interpolated camera and a confirmed pending SURVIVAL/flight0/flying0
snapshot, the disposable server received a graceful Ctrl+C shutdown request after
the workspace reconnection invalidated its old console handle. Paper ran normal
plugin shutdown and saved all dimensions. Compressed vanilla player NBT then
verified Survival, no flight, health20, two real totems, **Pos[0,65,0]** and
**Rotation[90,-1]**. The SQLite recovery journal was empty and integrity_check=ok.

The fixture JAR was temporarily moved out; Paper initialized **exactly one plugin**,
PolderCinematics. After restart, a normal automatic join completed camera attach,
full landing, one totem pop, camera return and zero remaining display entities.
The player retained two real totems and health20. Captured border packets showed
80 → 10000 → 80, proving the temporary viewer border and restoration to the real
world border with no personal border present. Seen eligibility persisted and the
journal cleared. Paper stopped normally; fixture files and all worlds/data remain.

## Actual CI

Production/test code commit 0e26976357b21ff94441c2c0e1a1925342730f74 passed:
https://github.com/ItzNotMe-Creator/Minecraft.poldersmp/actions/runs/43487967318
Linux job132101364305 and Windows job132101364756 each executed **29 passing
tests**, zero failures/errors/skips, BUILD SUCCESS and standalone packaging/upload.
Job logs were read. Windows bundle14069060749 and Ubuntu bundle14069075242 contain
only the cinematic plugin, single-plugin installation ZIP and checksums.
Review: https://github.com/ItzNotMe-Creator/Minecraft.poldersmp/pull/8 (draft for
actual-island visual acceptance). The final follow-up changes only test packet
observation and verification documentation, not production code.
