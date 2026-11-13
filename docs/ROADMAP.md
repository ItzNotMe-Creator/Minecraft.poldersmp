# PolderSMP development roadmap

This adds Phase 8 to the existing project. README.md and BUILD-REPORT.txt retain
all implementation history and unfinished work from Phases 1–7; those systems
are not replaced by this roadmap. Version targets stay Java 21, Paper 1.21.11
and Velocity 3.4.0.

| Phase | Scope | Repository status |
|---|---|---|
| 1 | Common foundation, Core, Lobby, Velocity, SQLite, builds | Existing implementation preserved |
| 2 | Legendary weapons and armor trims | Existing implementation and recorded limitations preserved |
| 3 | Bounties and King competition | Existing implementation and recorded limitations preserved |
| 4 | Crates and secret vaults | Existing implementation preserved |
| 5 | Automatic world events | Existing implementation preserved |
| 6 | Safe Nether maintenance | Existing implementation and maintenance safeguards preserved |
| 7 | Teams and economy | Existing implementation preserved; see TEAMS-ECONOMY.md |
| 8 | Ultimate Lobby + standalone Cinematics | Implementation under review; see PHASE8-SPEC.md and LOBBY.md |
| 9 | Trusted proxy transfers/status/sessions/shared profiles/access enforcement | Deferred; Phase 8 defines a provider contract and fails closed |
| 10 | USB deployment package and verified Windows installation | Deferred; packaging plan below |

## Phase 10 packaging plan

The deployment archive must contain these independent plugins:

- `Lobby/plugins/PolderLobby.jar`
- `Lobby/plugins/PolderCinematics.jar`
- `SMP/plugins/PolderCore.jar`
- `Velocity/plugins/PolderVelocity.jar`

Include matching default configuration directories, Java/Maven requirements,
lobby setup instructions, existing Nether maintenance documentation, safe Windows
launchers and backup/recovery instructions. Never ship server worlds, live SQLite
files, credentials, authentication configuration or a copied LuckPerms database.
Do not install PolderCore on the lobby. PolderCinematics must also run alone.

`scripts/package-lobby.py` produces a **Phase 8 review bundle**, not an approved
Phase 10 USB installer. Native Windows launcher/deployment validation and the
Phase 9 bridge remain prerequisites to final deployment. It includes the complete
tracked source project and actual compiled JARs; it fails when required builds
are missing. The Phase 1 packager is preserved for its original use.
