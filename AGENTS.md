# PolderSMP development instructions

Read README.md and BUILD-REPORT.txt before changes. Preserve existing systems,
worlds, secrets, and player data. Work in phase order; Phase 2 needs a separate
request. Modules: polder-common, polder-core, polder-lobby, polder-velocity.
Use Java, Maven, supported Paper/Velocity APIs and SQLite. Use UUIDs for players,
versioned transactional migrations, async database work, atomic economy rewards,
and server-thread Paper calls. Future custom item identities require PDC.
Keep modules independently configurable and respect region/PvP protection.
Include commands, permissions and completion where appropriate. Do not invent
successful builds or tests. Run Maven verification after changes, update the
README and build report, and prepare changes for review. Diagnose dependency
failures; use official repositories with TLS verification. CI must upload JARs.
