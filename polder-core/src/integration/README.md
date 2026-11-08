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
