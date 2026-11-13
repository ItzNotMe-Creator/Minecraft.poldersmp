# POLDERSMP - OFFICIAL PHASE 8 EXPANSION
## ULTIMATE LOBBY EXPERIENCE

Update the existing PolderSMP development roadmap and Phase 8 specification.

IMPORTANT: This is an ADDITION, not a replacement. Preserve all existing features and code from Phases 1–7.

Continue using the existing Maven multi-module repository and its established Paper/Velocity version targets. Do not change server versions without approval.

# PHASE 8 - REQUIRED FEATURES

## A. Original Phase 8 features
Implement and retain:
- Persistent player statistics
- Leaderboards
- Unified graphical menus
- PlaceholderAPI integration
- Network-compatible player profiles and statistics
- Configurable messages, permissions and GUI themes

## B. PolderCinematics.jar (SEPARATE LOBBY PLUGIN)
Create a standalone Paper Lobby plugin called PolderCinematics.jar.

Features:
- Automatic cinematic introduction on joining the lobby
- Temporary Spectator Mode
- Smooth configurable camera flythrough above the lobby
- Cinematic waypoint editor with curves and easing
- Intro scenes: sky entrance, aerial flythrough, PolderSMP reveal, landing
- Welcome titles, subtitles, particle effects and sounds
- First-join-only, every-join and once-per-network-session modes
- Skip option
- Admin preview commands
- Safe teleport to lobby spawn
- Restore player gamemode and state
- Cleanup on disconnect, shutdown or animation failure
- No client-side mods required
- Test actual camera behavior on a real Minecraft client

Commands should include /joinintro, /joinintro addpoint, /joinintro setspawn, /joinintro preview, /joinintro skip, /joinintro editor, /joinintro reload and administrative options.

## C. PolderLobby.jar - COMPLETE LOBBY EXPANSION

Implement ALL systems below inside the existing PolderLobby.jar, as separate configurable internal modules.

1. ANIMATED SMP PORTAL
- Admin-configurable portal regions
- Particle animations and sounds
- Entering the portal requests transfer to SMP
- Locked/offline/unavailable status with proper messages
- Optional live hologram above the portal
- Never bypass proxy access restrictions

2. PARKOUR SYSTEM
- Multiple parkour courses
- Start and finish points
- Checkpoints
- Personal best timers
- Leaderboards
- Rewards and achievements
- Restart and quit controls
- Commands: /parkour, /parkour start, /parkour reset, /parkour top
- Admin tools to create/edit courses

3. LOBBY GADGETS
- Double jump
- Grappling hook
- Launch pads
- Particle blaster
- Magic staff
- Configurable cooldowns and animations
- No block destruction or PvP damage
- Disable gadgets during cinematics and parkour where necessary

4. LIVE HOLOGRAMS
- Online player counts
- SMP online/locked status
- Top player kills
- Richest players
- Current King of the SMP
- Upcoming world events
- Parkour leaderboard
- Integrate with existing hologram plugins where possible
- Cache network data instead of continuously querying the SMP

5. COSMETIC SHOP
- Particle trails
- Animated hats
- Cosmetic wings
- Lobby pets
- Join effects
- Cosmetic titles
- Unlockable cosmetics
- Preview and selection GUI
- Persistent player selections
- No pay-to-win advantages

6. HIDDEN LOBBY SECRETS
- Hidden collectible stars
- Secret rooms
- Treasure hunts
- Lobby achievements
- Cosmetic unlock rewards
- Persistent collection progress
- Optional secret discovery announcements

7. DAILY REWARDS
- /daily command
- Daily login reward GUI
- Login streak tracking
- Configurable rewards
- Anti-duplicate claim protection
- Time-zone-aware reset behavior

8. SERVER SELECTOR
- Beautiful inventory GUI
- SMP status
- Online player counts
- Join button
- Locked/server-offline indicators
- Configurable icons, names and lore
- Integration with Velocity, without insecure direct backend access

9. INTERACTIVE NPCS
- SMP join NPC
- Cosmetics NPC
- Parkour NPC
- Player profile NPC
- Daily reward NPC
- Support an established NPC API/plugin instead of implementing unsafe fake players
- Admin commands to register NPC interaction actions

10. PLAYER PROFILES
- /profile and /profile <player>
- Player rank
- Playtime
- Kills and deaths
- Achievements
- Cosmetic unlocks
- Parkour records
- Network statistics
- Clean interactive GUI
- Safe asynchronous retrieval of shared data

11. LOBBY MINI-EVENTS
- Parkour races
- Treasure hunts
- Timed collectible hunts
- Scheduled challenges
- Countdown displays
- Participant leaderboards
- Cosmetic rewards
- Admin controls to start, stop and schedule events

12. WELCOME EFFECTS
- Special first-join fireworks
- Welcome sounds
- Welcome titles and subtitles
- Configurable first-join announcements
- Animated spawn particle effects
- Coordinate effects with PolderCinematics so both systems do not trigger at the same time

## D. LOBBY USER EXPERIENCE

After joining:

1. The player experiences the cinematic introduction when eligible.
2. The camera lands at the configured lobby spawn.
3. Welcome particles and sounds play.
4. The player receives the configured lobby navigation items.
5. The player can access Server Selector, Cosmetics, Profile, Parkour and Daily Rewards.
6. Animated holograms, portals and interactive NPCs create an active, polished hub.

Make all elements customizable rather than hardcoding world coordinates.

## E. TECHNICAL REQUIREMENTS

- PolderCinematics must remain a separate compiled JAR.
- All other lobby features belong in PolderLobby.jar.
- Do not install PolderCore on the lobby.
- Provide well-organized configuration YAML files.
- Implement clear permissions and command tab completion.
- Prevent conflicts between cosmetics, gadgets, parkour and cinematic sessions.
- Do not duplicate established plugin systems unnecessarily.
- Use persistent data for rewards, achievements and statistics.
- Prevent duplicate rewards and exploits.
- Keep database operations asynchronous.
- Avoid heavy particle or hologram updates.
- Support safe disconnect and restart cleanup.
- Make all modules individually configurable.
- Do not overwrite existing worlds, builds or plugin data.
- Preserve LuckPerms and other existing integrations.

## F. PHASE 9 INTEGRATION

Phase 8 should define the interfaces needed for Velocity integration.

Phase 9 will finish:
- Reliable proxy-based server transfers
- Cross-server status synchronization
- Network session tracking
- Shared player statistics
- Locked SMP access enforcement

Use safe fallbacks until the Phase 9 integration is complete.

## G. TESTING

Add automated tests and real-server smoke tests where feasible.

Verify:
- Cinematic player restoration
- Parkour timers/checkpoints
- Reward persistence
- No duplicate rewards
- Portal access restrictions
- Cosmetics and gadget cleanup
- Disconnect/reconnect handling
- Lobby performance with multiple players
- Configuration reload safety
- Network integration failure handling

Build and validate both PolderLobby.jar and PolderCinematics.jar.

## H. FINAL PACKAGING

Phase 10 USB deployment package must include:

Lobby/plugins/PolderLobby.jar
Lobby/plugins/PolderCinematics.jar
SMP/plugins/PolderCore.jar
Velocity/plugins/PolderVelocity.jar

Include default configurations, setup instructions and a lobby configuration guide.

Do not mark Phase 8 complete until all required modules compile, tests pass, and the remaining limitations are documented.

GOAL: Build the full PolderSMP Ultimate Lobby with professional animations, interactive gameplay, polished GUIs, persistent progress and smooth integration with the network.