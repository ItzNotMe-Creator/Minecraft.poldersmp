# Disposable Paper lobby smoke tests

These fixtures are excluded from production JARs. Run only on a new loopback
Paper 1.21.11 test root containing `.polder-disposable-test`. The fixture refuses
startup without both that marker and server-ip=127.0.0.1. It creates a platform
and configuration in that disposable world only. It must never be deployed to a
player server. Accept the Minecraft EULA yourself before starting the test root.

1. Build the reactor with Java 21 / `mvn -B clean verify`.
2. Put only PolderLobby.jar and PolderCinematics.jar in the new server's plugins
   folder, plus the compiled LobbyLiveProbe fixture. Never add PolderCore.
3. Compile src/integration/java/.../LobbyLiveProbe.java against the built lobby
   JAR and provided Paper API/dependencies. Package its class files with plugin.yml:

   ```yaml
   name: LobbyLiveProbe
   version: 1
   main: nl.poldersmp.lobby.LobbyLiveProbe
   api-version: '1.21.11'
   depend: [PolderLobby, PolderCinematics]
   commands:
     lobbyprobe: {}
   ```

4. Start Paper on **127.0.0.1:25578**, offline mode for this isolated fixture,
   Java 21, a disposable flat world, view/simulation distance 3. Use a terminal
   with usable stdin so the server can be stopped using `stop`.
5. In src/integration/client run `npm ci --ignore-scripts`, then
   `POLDER_LOBBY_USER=FreshTestName node lobby-client.js` (Windows: set the
   environment variable using PowerShell). Use a fresh name for a new full run;
   progress is intentionally never erased. A full run claims today's reward.
6. Stop Paper normally, restart with the same database/world/player data, then
   run with the same name and `POLDER_LOBBY_RESTART=1` for the persistence audit.

The protocol client exercises camera position updates, Spectator state, skip,
exclusive activities, safe landing, unrelated inventory preservation, complete
camera path, 54-slot GUI extraction, daily duplicate/permission guards, missing
proxy failure, native movement through parkour/checkpoints, teleport cancellation,
secrets, cosmetic pet selection/removal and disconnect/reconnect recovery.
The fixture can register **synthetic test-only** network services using
`/lobbyprobe network <locked|offline|online|fail|clear>` and audit call counts with
`/lobbyprobe networkaudit <count>`. This tests the consumer contract only; it is
not a Phase 9 proxy implementation and performs no real server transfer.

For optional integration checks, install real supported PlaceholderAPI, Citizens
and DecentHolograms builds in a separate disposable test cycle. Do not pretend
fallback TextDisplays validate a third-party hologram plugin. No NPC is created
by these fixtures. Record which combinations were actually executed.

**A protocol client is not a graphical Minecraft client.** A human must still
review actual camera rendering, scene framing, easing and comfort in an unmodified
Minecraft 1.21.11 client. Multiple-player endurance/proxy integration need their
own recorded acceptance runs. See BUILD-REPORT.txt for exact executed outcomes.
