# Phase 7 operator guide

Paper 1.21.11, Java 21. These systems run inside PolderCore. Back up plugin data
before upgrading and stop normally before replacing the JAR. No balance conversion,
starting-money grant or membership replacement is performed on installation.

## Choose the economy deliberately

`economy.yml` defaults to `mode: auto`. The module examines registered services for
the supported Vault Economy interface, including providers registering it directly.
With a provider it uses that provider's real balances. With none, economy commands
remain unavailable: auto never creates an internal currency as a fallback.
`mode: vault` explicitly requires that provider; `mode: disabled` disables new
money operations. `mode: sqlite` explicitly selects the existing Phase 1 `accounts`
table and UUIDs. It preserves its balances and denomination; no new balance table
or synchronization with Vault is introduced. New accounts have zero until funded.

To continue using Phase 1 money/bounties, select sqlite explicitly before upgrading.
If Vault is installed while sqlite is selected, the startup log warns about the
explicit selection; Vault itself and its balances remain unchanged. Changing modes
requires a restart. Pending operations retain their original provider identity;
they never migrate automatically. Reconcile/finish pending work before changing a
provider implementation or its backing bank/database.

Amounts and prices use exact **whole currency units**, matching the existing ledger.
`10.00` is accepted as ten; `10.25`, NaN, negative/oversized values and fractional
YAML prices are rejected rather than rounded. Existing fractional Vault balances
are preserved; give/take/pay change whole units. Vault admin set refuses an existing
fractional balance; use give/take instead. Very large/nonfinite provider balances
are refused at the double-precision boundary. No denomination rescaling occurs.

If another plugin owns a bare command, use `/poldercore:balance`,
`/poldercore:pay`, `/poldercore:shop`, `/poldercore:ah`, `/poldercore:blackmarket`
or `/poldercore:eco`. Existing plugin commands are not unregistered or replaced.

### Transaction and recovery guarantees

SQLite operations debit/credit, finalize order/listing state and enqueue a frozen
item parcel in one database transaction. Negative balances and integer overflow
fail and roll back the complete transaction. Operation UUIDs and immutable legs
prevent replaying the same request with different recipients or amounts. Repeating
a completed ID has no monetary effect. Separate intentional commands create
separate operations; they are not guessed to be accidental duplicates.

Vault has no distributed transaction or request-id API. Each operation and leg is
persisted before calling the provider on the Paper thread. PENDING -> APPLYING
commits before the call; the result is acknowledged afterward. An exception,
reported amount mismatch or interrupted APPLYING leg becomes UNKNOWN. It is never
replayed automatically. Other queued/new operations touching an uncertain account
on that provider wait for reconciliation. A provider change never redirects a leg.

Use `/eco pending` to inspect held work. After independently checking the original
provider's transaction records, an administrator may attest:

- `/eco resolve <operation-uuid> <zero-based-leg> applied`
- `/eco resolve <operation-uuid> <zero-based-leg> not-applied`

Applied skips the already-performed leg. Not-applied allows one new durable attempt.
Every attestation records operator UUID/time. Do not infer a result from the current
balance alone: other transactions could offset it. A wrong operator attestation
can cause a duplicate effect; Vault cannot eliminate that ambiguity. A declined
first leg releases uncharged reservations. A confirmed debit followed by a declined
credit remains pending for delivery, never guessed to be refunded. Do not manually
refund it while its credit is still pending.

`/eco cancel <operation>` cancels only a ready operation with no confirmed,
applying or uncertain legs and returns its reservations/items. Partially applied
transfers require completing/reconciling the existing operation. Recovery commands
remain available when the selected provider is unavailable. No forced balance
merge, speculative retry or silent refund is performed.

Legacy Phase 3 competition cash and Phase 5 treasury cash require the supplied
SQLite transaction contract. In sqlite mode, the existing economy service remains
fully transactional and new legacy cash changes are recorded in Phase 7 history.
In Vault/disabled modes, new legacy cash funding and existing legacy cash payouts
are held/rejected: an external call cannot be inserted into their SQLite transaction.
Existing balances, bounties, escrow and pending rewards are preserved for explicit
sqlite selection; they are never minted into a different provider. Zero-cash King
and event/item systems still work. There is no automatic legacy-to-Vault cash
migration. A future external competition-funding bridge must separately track
provider-backed escrow and distinguish previously funded SQLite obligations.

### Commands and permissions

| Command | Permission |
| --- | --- |
| `/balance [history]` | `polder.economy.balance` |
| `/pay <known-name-or-uuid> <whole-amount>` | `polder.economy.pay` |
| `/shop`, `/shop sell <catalog-id>` | `polder.shop.use` and item permission |
| `/ah [mine]`, `/ah search <text>`, `/ah sell <price>`, `/ah buy <uuid>` | `polder.auction.use` |
| `/blackmarket` | `polder.blackmarket.use` and item permission |
| `/eco give|take|set <known-name-or-uuid> <amount>` | `polder.economy.admin` |
| `/eco pending`, `resolve`, `cancel`, `history <player>`, `stats` | `polder.economy.admin` |

Admin permission defaults to operator; player commands default true. Offline name
resolution uses only cached known names; UUIDs work without network name lookups.
Payments are queued and return an operation ID. Admin set completion is sent to the
target player; use `/eco history <target>` to inspect its outcome. History includes
provider identity and the wallet's own leg, not another recipient's balance change.
Statistics separate providers instead of combining different balance domains.
History/statistics cover PolderCore operations; Vault does not expose a complete
transaction history from other plugins.

## Shop, auctions and black market

All menus are 54 slots and deny extraction, shift/number-key moves and dragging.
Auction purchases require confirmation. GUI titles, sounds/particles, prices,
permissions, catalog entries, listing limits/expiry and refresh settings are
configurable. Shop/auction/black-market can each disable new actions independently;
already committed recovery/delivery continues. Configuration changes need restart.

Shop buying grants the configured bundle. Selling consumes the **entire held
stack** at sell-per-item price. Only the configured plain item matches: unrelated
names/lore/enchantments are not destroyed for a resource price. Completed mailbox
receipt tags are validated and excluded only for this comparison. Special items
are generated through the existing canonical RewardFactory/Weapons factory.

Auction listing removes the entire held stack. Container items (including shulkers)
and bundles are excluded to avoid hiding unresolved receipts in nested inventories.
Trades require Survival/Adventure, a valid stack and settled reward receipts.
Listings store exact item bytes, seller UUID, provider, price and expiry. A stale,
self-owned, expired, sold or different-provider listing cannot be purchased.
Search is parameter-bound and paginated; mine filters the seller's listings.

Item ingress uses PREPARED -> TAGGED -> CAPTURED escrow. The original payload is
stored before tagging. The tagged inventory saves before removal, and removal
saves before a listing or sale becomes active. Inventory interaction/drop/swap/use
is blocked while custody changes, including entity/armor-stand interaction, crafting, consumption
and outgoing item use. Players still receive normal incoming damage. Tagged items are retained rather than dropped on
death, and recovery resumes after respawn/login. A normal crash can neither activate
a listing while the saved original remains spendable nor generate two deliveries.
An interrupted untagged preparation cancels without issuing an item copy.

Purchasing CAS-reserves one listing, pays through the selected ledger and enqueues
one owner-bound mailbox parcel. A full inventory defers delivery. Expired unreserved
listings generate one durable return parcel; reserved uncertain purchases stay held
until reconciliation. Offline sellers receive money/returns by UUID. Provider-pinned
item escrow must be finished with its original provider; do not change modes during
an inventory handoff. Changed-provider listings are hidden and retain normal expiry.

Black-market stock is selected for a real-time rotation and persisted as frozen
catalog definitions. Restart does not reroll it. Each purchase creates a fresh
canonical item identity, freezes its payload and transactionally reserves a per-UUID
purchase count. Failed uncharged orders release the limit. Old menu rotations are
rejected; counters survive restarts and mode changes. The next refresh is shown.

Mailbox/escrow guarantees assume Paper owns inventory persistence. Independent
player-data rollbacks, creative duplication tools, external inventory sync and
custom item mutation require coordinated integration. Existing Phase 2 serial
registry limitations remain documented; this phase does not pretend to authenticate
arbitrary copies created outside the supported inventory workflow.

## Teams

`teams.yml` independently enables teams and configures member/team counts,
creation cooldown, invitation limits/expiry, combat locks, kill-farming interval,
war duration, allied friendly fire, King eligibility, effects and GUI title.
Memberships and all permissions use UUIDs. Names are case-insensitive, 3..16 ASCII
letters/numbers/underscores. Invites survive restart until expiry and accept checks
capacity again atomically. Multiple invitations use `/team accept <team>`.

`/team` or `/team info` opens the team GUI; `/team top` opens rankings by war wins
and kills. Both are paginated. All team commands require `polder.team.use` (default
true); ownership/rank checks are enforced again in the transaction.

- `/team create <name>`, `invite <player>`, `invites`, `accept [team]`, `deny`.
- `/team leave`, `kick <player>`, `promote <player>`, `disband`.
- `/team chat` toggles private team chat; `/team chat <message>` sends once.
- `/team friendlyfire on|off` is leader-only.
- `/team ally <team>`, `rival <team>`, `war <team>`.
- `/team ally|rival remove <team>`; `/team ally|war accept <team>`.

Leaders and officers invite; officers kick only members. Only leaders promote:
member becomes officer; promoting an officer transfers leadership and leaves the
previous leader an officer. Leaders transfer before leaving or disband explicitly.
Disband preserves statistics/creation cooldown and cannot bypass active war/combat.
Team changes, friendly-fire toggles and diplomacy cannot evade a combat tag.

Alliances and wars require reciprocal consent; rivals are declarations. Wars track
confirmed hostile kills during their configured duration, ignore duplicate deaths
and repeated victim farming, and award a win once on expiry (ties have no winner).
Wars do not enable PvP in protected regions or PvP-disabled worlds. Team/ally kills
never count for bounty/team farming even if friendly fire is enabled. Private chat
uses an immutable UUID snapshot and server-thread delivery; leaving cannot leave a
player trapped in a vanished private channel. A queued private message is discarded
if the sender changes teams; a membership-loss notice never republishes it globally.

The shared AbilityGuard plus normal cancellable damage events protect legendary
weapons and vanilla/projectile PvP. Bounty checks include team/allied membership.
By default King arena eligibility requires genuinely hostile participants rather
than only teammates/allies; this can be configured. Existing protection services,
scoreboards and staff ranks are preserved.

## Persistence, validation and remaining compatibility work

`db/teams/V1__teams.sql` and `db/market/V1__market.sql` add independent versioned
schemas to existing `polder.db`. No old table or real balance is replaced. Database
work uses the existing async worker; Paper/inventory/Vault calls use the appropriate
server thread. Builds retain Paper 1.21.11 / Velocity 3.4.0 / Java 21 dependencies.

See BUILD-REPORT.txt for executed automated and disposable-server results. Tests
cover SQL persistence/restart, role/size/invitation/combat constraints, diplomacy,
war scoring, money overflow/idempotency, provider pins, external uncertain states,
account holds, auction reservation/returns, and stock limits. Native Paper tests
use separate marked localhost worlds and synthetic bot accounts. Real Vault and
Essentials jars used for validation are not bundled or installed on production.

Compatibility work intentionally outside this implementation: provider-backed cash
funding for the legacy competition/treasury SQLite interfaces, automatic currency
migration (never enabled), a matrix of third-party inventory synchronizers/claim
plugins and forced hardware-power-loss testing. For external legacy cash support,
continue with: add explicit provider-pinned competition escrow and payout outboxes,
without converting existing SQLite obligations; test mixed historical obligations
and failure recovery before enabling external competition funding. No Phase 8.
