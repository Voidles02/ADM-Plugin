# ADM — Advanced Admin Management

Author: **voidles02**  
Version: **0.11.0-stage13**  
Stage: **13 — TPA aliases and LuckPerms settings**

## Installation

Use Paper 1.21.x and Java 21. The Gradle Kotlin DSL project compiles against Paper 1.21.4; Paper, LuckPerms, and WorldGuard APIs are compile-only. Java and Kotlin code target Java 21. Kotlin stdlib and embedded H2 are declared in the root `manifest.kod`; H2 2.3.232 is an implementation dependency. GrimAC is an optional server dependency loaded before ADM; ADM uses Bukkit permissions and does not link against Grim classes. No libraries are shaded. This is not a Spigot or Folia plugin.

Put the built ADM jar in `plugins/` and restart the server. LuckPerms, GrimAC, WorldEdit, and WorldGuard are optional; WorldGuard integration is initialized when installed, with its WorldEdit dependency loaded first. First startup creates `plugins/ADM/config.yml`, `messages.yml`, and `hud.yml`. Existing files are preserved: absent settings use defaults, and absent messages fall back with a once-per-key warning. Add the new entries from the bundled configuration to customize them.

When LuckPerms is enabled, ADM creates or updates the `moderator`, `admin`, and `owner` groups. It never assigns players to these groups automatically; use LuckPerms commands to promote staff explicitly. The groups inherit permissions in order: `owner` inherits `admin`, and `admin` inherits `moderator`.

## Implemented modules

| Toggle | Features |
| --- | --- |
| `modules.core` | `/adm reload`, `/adm version`, `/adm debug`, `/adm storage-info`, `/adm database`, `/adm log`, `/adm cleanup` |
| `modules.player-tools` | Game modes, flight, speed, god, heal, feed, repair, clear |
| `modules.teleport` | Player/coordinate teleports, back, safe top |
| `modules.tpa` | TPA requests, accept/deny, configurable request cooldown and post-teleport damage protection |
| `modules.information` | Near, ping, online list, whois, seen |
| `modules.chat` | Title announcements, broadcast, clear/mute chat, slowmode, sudo |
| `modules.anticheat` | Connect to supported anticheats, check status, toggle the connected plugin, and manage Grim exemptions |
| `modules.inventory` | Ender Chest providers, locked ender editing, invsee |
| `modules.vanish` | `/vanish on|off|set LVL|help`, level-based visibility, silent joins/quits, pickup and mob-target protection |
| `modules.punishments` | Persistent bans, IP bans, mutes, warnings, kicks, history, alts |
| `modules.staff-chat` | Staff chat and command/social spy; independent of database health |
| `modules.staff-tools` | Persistent freeze and recoverable staff-mode snapshots |
| `modules.reports` | Report submission and staff report GUI |
| `modules.audit` | `/adm log`, `/adm cleanup`, and startup retention pruning |

The storage listener and audit sink are infrastructure independent of module toggles. `/adm storage-info` and `/adm database` remain available through core even when embedded storage fails. Storage-dependent commands reject requests while storage is starting or failed. An unavailable database disables punishments, staff-tools, reports, and audit commands; other modules continue working. Login recording and configured failure fallback remain active. Database failures update storage health; pre-login errors are not logged a second time by their caller, and repeated identical failures are rate-limited to avoid log spam.

A disabled module registers neither its commands nor its feature listeners. Core identity/cache cleanup remains available independently of these toggles. All modules may be disabled. A module exception is logged, its resources are released, and it stays failed until restart. Already registered command nodes reject execution after a failure.

## Commands and permissions

Staff command nodes default to op-only; TPA request commands default to available to all players. `vanish.on-join` defaults to true in the shipped configuration, but joining players still need the vanish permissions, including `adm.admin.vanish.join`. Vanish levels 0, 2, and 3 default to false; level 1 defaults to op. A base permission is always required; targeting someone else additionally requires the `.others` node when listed. Fixed-mode shortcuts share the game-mode permissions and cooldown.

| Command | Base permission | Additional permission |
| --- | --- | --- |
| `/adm reload` | `adm.admin.reload` | — |
| `/adm version` | `adm.admin.version` | — |
| `/adm debug` | `adm.admin.debug` | — |
| `/adm database` | `adm.admin.database` | Prints the embedded database file path |
| `/gamemode <survival\|creative\|adventure\|spectator> [player]`, `/gm` | `adm.mod.gamemode` | `adm.mod.gamemode.others` |
| `/gmc [player]`, `/gms [player]`, `/gma [player]`, `/gmsp [player]` | `adm.mod.gamemode` | `adm.mod.gamemode.others` |
| `/fly [player]` | `adm.mod.fly` | `adm.mod.fly.others` |
| `/speed <0-10> [fly\|walk] [player]` | `adm.mod.speed` | `adm.mod.speed.others` |
| `/god [player]` | `adm.admin.god` | `adm.admin.god.others` |
| `/heal [player]` | `adm.mod.heal` | `adm.mod.heal.others` |
| `/feed [player]` | `adm.mod.feed` | `adm.mod.feed.others` |
| `/repair [hand\|all]` | `adm.admin.repair` | `adm.admin.repair.all` for all |
| `/clear [player]` | `adm.admin.clear` | `adm.admin.clear.others` |
| `/tp <player>` | `adm.mod.tp` | — |
| `/tphere <player>` | `adm.mod.tphere` | — |
| `/tpall` | `adm.admin.tpall` | — |
| `/tppos <x> <y> <z> [world]` | `adm.admin.tppos` | — |
| `/back` | `adm.mod.back` | — |
| `/top` | `adm.mod.top` | — |
| `/tpa <player>` (`/admtpa`) | `adm.tpa.use` | Sends a request; default 5-second per-player cooldown |
| `/tpaccept [player]` (`/admtpaccept`), `/tpdeny [player]` (`/admtpdeny`) | `adm.tpa.use` | Requests expire after 60 seconds |
| `/tpa cooldown set <time>`, `/tpa protection <time>` | `adm.admin.tpa.configure` | Saves immediately; `/tpa protection set <time>` also works. Accepts seconds (`5`/`5s`), minutes (`2m`), or hours (`1h`), from 0 to 86400 seconds |
| `/near [radius]` | `adm.mod.near` | — |
| `/ping [player]` | `adm.mod.ping` | — |
| `/list` | `adm.mod.list` | — |
| `/broadcast <message>` | `adm.admin.broadcast` | — |
| `/announcement <message>`, `/annoucement` | `adm.admin.announcement` | Shows a title and chat announcement to online players |
| `/adm-connect <anticheat>` | `adm.admin.anticheat.manage` | Connect ADM to GrimAC, Vulcan, Matrix, Spartan, NoCheatPlus, Themis, or AntiCheatReloaded |
| `/adm:anticheat <status\|on\|off\|refresh\|exemptions>` | `adm.admin.anticheat.manage` | Check or toggle the connected supported plugin; Grim-only refresh and exemption listing |
| `/clearchat` | `adm.mod.clearchat` | — |
| `/mutechat` | `adm.mod.mutechat` | — |
| `/slowmode <seconds\|off>` | `adm.mod.slowmode` | — |
| `/sudo <player> <message or /command>` | `adm.admin.sudo` | — |
| `/endersee <player>` | `adm.admin.endersee` | Read-only; no aliases |
| `/enderedit <player>` | `adm.admin.enderedit` | Exclusive edit lease; no aliases |
| `/invsee <player>` | `adm.admin.invsee` | `adm.admin.invsee.edit` enables editing |
| `/vanish [on|off|set LVL|help]`, `/v` | `adm.admin.vanish` | `adm.vanish.level.<n>` for the chosen level; bare `/vanish` toggles |
| See vanished players | `adm.admin.vanish.see` | Viewer must have a level at least as high as the vanished player's chosen level |
| Silent vanish on join | `adm.admin.vanish.join` | Base vanish permission, a level, and `vanish.on-join: true` |
| `/whois <player>` | `adm.admin.whois` | `adm.admin.whois.ip` reveals online IP |
| `/seen <player>` | `adm.admin.seen` | — |
| `/adm storage-info` | `adm.admin.storage-info` | Connection state, last success, latency, error |
| `/mute <player> [reason]` | `adm.mod.mute` | Hierarchy |
| `/tempmute <player> <duration> [reason]` | `adm.mod.tempmute` | Hierarchy |
| `/unmute <player>` | `adm.mod.unmute` | Hierarchy |
| `/warn <player> [reason]` | `adm.mod.warn` | Hierarchy and configurable escalation |
| `/warnings <player> [page]` | `adm.mod.warnings` | Hierarchy |
| `/clearwarnings <player>` | `adm.mod.clearwarnings` | Hierarchy; history retained |
| `/kick <player> [reason]` | `adm.mod.kick` | Online only; hierarchy |
| `/ban <player> [reason]` | `adm.admin.ban` | Hierarchy |
| `/tempban <player> <duration> [reason]` | `adm.admin.tempban` | Hierarchy |
| `/ipban <player\|ip> [reason]` | `adm.admin.ipban` | Hierarchy for known accounts sharing the IP; rejects requests matching more than 5000 stored accounts |
| `/unban <player\|ip>` | `adm.admin.unban` | Name removes UUID ban and associated IP ban; literal IP removes IP bans |
| `/history <player> [page]` | `adm.admin.history` | Hierarchy |
| `/alts <player> [page]` | `adm.admin.alts` | Accounts with the same last stored IP; hierarchy |
| `/freeze <player>` | `adm.mod.freeze` | Hierarchy; `adm.bypass.freeze` exempts |
| `/staffchat [message]`, `/sc` | `adm.mod.staffchat` | Empty message toggles routing |
| `/staffmode`, `/sm` | `adm.mod.staffmode` | Requires the vanish module; tools check their own permissions |
| `/spy [commands\|social]` | `adm.admin.spy.commands` / `adm.admin.spy.social` | In-memory toggles |
| `/report <player> <reason>` | `adm.report` | Default true; submission cooldown |
| `/reports [page]` | `adm.mod.reports` | `adm.mod.reports.claim`, `adm.mod.reports.close`, `adm.mod.tp` |
| Report/frozen-quit notifications | `adm.mod.notify` | — |
| `/adm log [staff] [target] [action] [page]` | `adm.admin.log` | Use `*` to skip a filter |
| `/adm cleanup` | `adm.admin.cleanup` | Deletes old audit rows and old closed reports |

`/adm` shows core help when the actor has any core permission. Its default alias is `/advancedadminmanagement`. Commands support players and the local console. Console must specify a target for player tools and ping. Repair, near, teleport, inventory views, and vanish need an in-game player context. Whois and seen support console. Suggestions include only online player names and fixed values; worlds and offline players are not suggested. Offline-capable commands accept cached names or UUIDs without a network name lookup.

### Wildcards and hierarchy markers

| Node | Meaning |
| --- | --- |
| `adm.mod.*` | All implemented moderator command nodes and `.others` variants |
| `adm.admin.*` | Admin command nodes including anticheat controls, IP/edit/see permissions, level 1, plus `adm.mod.*`; silent join remains opt-in |
| `adm.*` | All implemented command permissions, bypasses, tiers, and immunity |
| `adm.tier.mod` | Fallback hierarchy rank 1 |
| `adm.tier.admin` | Fallback hierarchy rank 2 |
| `adm.tier.owner` | Fallback hierarchy rank 3 |
| `adm.immune` | Cannot be targeted by protected actions |
| `adm.bypass.hierarchy` | Skip hierarchy and immunity checks |
| `adm.bypass.mutechat` | Chat while global mute is active |
| `adm.bypass.slowmode` | Ignore global chat interval |
| `adm.bypass.freeze` | Exempt from freezing and frozen-player restrictions |
| `adm.bypass.*` | All bypass nodes |

God, clear, heal, and feed on others, sudo, freeze, and every punishment command require a **strictly higher** rank and a non-immune target. Self-actions and console bypass hierarchy. Offline punishment checks use the last recorded rank/immunity, or cached LuckPerms weight when available; offline permissions are not fetched from the network. Without LuckPerms, owner/admin/moderator markers map to ranks 3/2/1; command wildcards alone do not assign a fallback tier. With LuckPerms, rank is the **primary group's weight**, not the highest inherited weight. Missing primary-group weights are 0. Operators normally hold bypass and immunity nodes, so test hierarchy with non-op accounts.

The isolated LuckPerms integration caches plain prefix, suffix, and primary-group weight metadata. Joins and `UserDataRecalculateEvent` refresh it; quits remove it. ADM also creates/updates its three default groups asynchronously at startup. Permission and inheritance nodes are added without deleting custom group permissions. ADM does not assign users to groups. LuckPerms API references are confined to optional integration and startup setup code. No users are loaded from storage or the network by ADM. If integration initialization fails, ADM logs the problem and uses tier markers.

### Grim Anticheat compatibility

When GrimAC is installed, ADM provides an opt-in permission bridge: players with `adm.grim.exempt` receive Grim's `grim.exempt` permission while GrimAC is enabled. The node defaults to false and is deliberately not included in `adm.admin.*` or the automatically created LuckPerms roles. Grant it only to staff who should bypass Grim checks, for example with `lp group moderator permission set adm.grim.exempt true`. Changes are synchronized within two seconds; attachments are removed when a player leaves, GrimAC is disabled, or ADM shuts down. Without the opt-in node, ADM does not exempt players or alter Grim checks.

`/adm-connect GrimAC` connects ADM to an installed supported anticheat (also supported: Vulcan, Matrix, Spartan, NoCheatPlus, Themis, and AntiCheatReloaded). `/adm:anticheat status` shows the selected plugin; `on` and `off` enable or disable that plugin live through Bukkit's plugin manager. `refresh` and `exemptions` apply only to GrimAC. These controls require `adm.admin.anticheat.manage`; the automatically created LuckPerms admin and owner groups receive it through `adm.admin.*`. ADM does not issue vendor-specific alert or punishment commands to other anticheats.

### Default LuckPerms roles

ADM assigns weights 10, 50, and 100 to the default Moderator, Admin, and Owner groups. Group names are lowercase in LuckPerms. Permissions from each parent group are inherited.

| Group | Weight | Permissions and abilities |
| --- | ---: | --- |
| `moderator` | 10 | `adm.mod.*`, `adm.tpa.use`, vanish level 1, moderation/report/vanish HUD pages, and common moderation vanilla commands (kick, teleport, gamemode, effect, clear) |
| `admin` | 50 | Inherits Moderator; adds `adm.admin.*` and `adm.admin.tpa.configure`, including anticheat controls, vanish level 2, all HUD pages, and common world-management vanilla commands |
| `owner` | 100 | Inherits Admin; adds `adm.*`, hierarchy bypass/immunity, vanish level 3, and `minecraft.command.*` plus `bukkit.command.*` for vanilla/Bukkit commands |

Owner's command wildcards do not grant `*` across unrelated LuckPerms plugins. Group setup does not grant operator status. Assign players explicitly with LuckPerms (console examples):

```text
lp user OwnerName parent set owner
lp user OwnerName primarygroup set owner
lp user AdminName parent set admin
lp user AdminName primarygroup set admin
lp user ModName parent set moderator
lp user ModName primarygroup set moderator
```

Do not grant `adm.*` or `adm.bypass.hierarchy` to the hierarchy test accounts: they intentionally skip rank checks. `adm.immune` is an explicit protection option, not necessary for these examples.

## Configuration and reload

`cooldowns.<command>` is an integer number of seconds, 0–86400; missing entries and 0 disable the cooldown. Cooldowns apply per actor and canonical command. `/gm` and the fixed shortcuts use `cooldowns.gamemode`. Accepted actions start cooldowns; ordinary permission/input denials do not. Teleport requests start cooldowns when accepted even if the eventual teleport is cancelled. Console has no cooldown. TPA has its own `tpa.cooldown-seconds` (default 5) and `tpa.protection-seconds` (default 15); 0 disables either duration. Authorized staff can change them with `/tpa cooldown set <time>` and `/tpa protection <time>`; changes save to `config.yml` and take effect immediately.

`slowmode.min-seconds` and `.max-seconds` bound accepted intervals (defaults 1–300, maximum 86400). `near.default-radius` and `.max-radius` default to 100 and 1000 (maximum 10000). `clearchat.lines` defaults to 100 (range 1–500). Minimum/default values cannot exceed their respective maximums. `login-fallback: allow|deny` determines whether storage errors/timeouts allow or reject login. It never overrides an existing rejection by another plugin.

`/adm reload` reads and validates both YAML files on ADM's executor. Invalid YAML, types, ranges, aliases, or MiniMessage syntax leave the old immutable snapshot active. A valid snapshot is swapped atomically on the main thread, then each enabled module receives `onReload(snapshot)`. Messages, cooldowns, TPA durations, and limits apply immediately; an active slowmode interval is clamped to updated limits. Cached chat denial components are rebuilt. Inventory views close and edit leases flush and release. File audit settings update without replacing its writer; vanish visibility is refreshed. Module toggles and the root command name/aliases are compared with startup and produce one **restart required** notice per changed setting; registered commands and enabled modules do not change on reload.

All ADM messages use configurable MiniMessage templates in `messages.yml`. Missing keys fall back to built-in defaults and warn once per key. Values supplied by players, including broadcast text, are inserted as literal text, not parsed MiniMessage. Startup validation failure is logged and runs built-in defaults without overwriting the broken files.

## Behavior, threading, and cleanup

- God mode is damage-event cancellation, not persisted player invulnerability.
- Flight and speed retain their original values and restore on quit or module shutdown. Game mode, healing, feeding, repairing, and inventory clearing are deliberate persistent gameplay changes and are not undone.
- `/speed` accepts integers 0–10, mapped to Bukkit speed 0.0–1.0. Omitted type selects fly if the target is flying, otherwise walk.
- `/back` captures the origin of successful ADM teleports and the death location. Returning with `/back` records its own origin, allowing another return. Locations contain only world UUID and numeric coordinates. Ordinary non-ADM teleports do not update them.
- Teleports use Paper `teleportAsync` so unloaded destination chunks are not synchronously loaded by ADM. Results return through the Bukkit scheduler. Pending request tokens are removed on quit/disable, so late results cannot recreate cleared state. `/tpall` reports requests, not guaranteed successful arrivals.
- `/top` searches the current loaded column for the highest solid non-hazardous support with two passable, non-liquid blocks above it. It refuses when no safe surface exists. Coordinate teleport accepts finite absolute coordinates inside height/world-border limits, not relative coordinates.
- Global mute and slowmode are in-memory and survive ADM reload, but reset on plugin shutdown/restart. Per-player god, return locations, cooldowns, slowmode timestamps, and vanish state are removed on quit. Expired report-submission cooldowns are pruned on the next report; report cooldowns and temporary staff-recovery byte snapshots are cleared on module shutdown, while durable recovery snapshots remain in embedded storage. Inventory projections refresh every two ticks only while an invsee session is open; vanish permission visibility refreshes every second.
- Chat enforcement uses Paper's synchronous `ChatEvent`, intentionally scheduling normal chat handling on the server thread. Early returns, preallocated timestamps, and cached denial components avoid ADM allocations during normal hot-path chat checks. Bypass permissions are checked live.
- All gameplay/permission/hierarchy access is main-thread confined. Reload workers handle only immutable plain data and local YAML parsers. LuckPerms event callbacks copy UUIDs and schedule cache refresh on the main thread. Async teleport completion schedules all live player/world access back to the main thread.
- ConcurrentHashMap-backed player caches are documented in their owning services; mutable gameplay state remains main-thread confined. ConfigService's atomic snapshot and message warning sets are safe for cross-thread reads. Service methods own module/permission/hierarchy/cooldown/audit rules, not the command adapter.
- Accepted feature actions are logged to the server log; sudo logs actor and target but never its command payload. Command spies suppress common login/password commands. Inventory access/edits, punishments, staff-mode transitions, freeze changes, and reports go through `api.AuditSink` into embedded storage. Whois IP values are not logged to the server log.
- Shutdown restores staff snapshots on the main thread, closes inventory views, flushes/releases provider leases, restores other temporary player state, unregisters listeners, cancels tasks, and drains database/audit/reload workers within one shared five-second waiting budget. Snapshots are durable before staff kits can be equipped.

## Known limitations

Vanilla offline Ender Chest access and offline invsee remain unavailable. No network offline name lookup is performed. Failed startup modules need a restart. Existing YAML files are not automatically rewritten during an upgrade. Coordinate teleports do not promise safe terrain; `/top` is the safe-surface tool. Other plugins can cancel teleports or chat. If a snapshot's world is unavailable or restoration fails, ADM keeps the snapshot and refuses to discard it. Name/IP associations and offline hierarchy reflect recorded data, not a live identity-provider lookup. Social spy recognizes configured private-message command labels rather than third-party plugin-specific events.

When WorldGuard is installed, ADM checks its `exit-via-teleport` and `entry` region flags for `/tp`, `/tphere`, `/tpall`, `/tppos`, `/back`, `/top`, and TPA requests. WorldGuard bypass permissions are honored. Paper teleport-event cancellations remain authoritative. ADM does not yet link directly to BetterEnderChest, CoreProtect, AuthMe Reloaded, Quizy, or CombatLog APIs; its Ender Chest provider registry and AuditSink are available as extension points. Plugin-specific adapters require the target plugin's API contract and version.

## Inventory HUD

`/adm-hud` (alias `/admhud`) opens the control panel. `adm.hud.use` grants access; each category also needs `adm.hud.category.<id>`. Categories are player, teleport, inventory, chat, vanish, server, settings, moderation, punishments, reports, logs, integrations, and status. `adm.hud.*` includes these nodes. HUD access never grants command permissions: service permission, hierarchy and cooldown checks still apply. HUD infrastructure stays available with every feature module disabled.

Every screen has Back, Home and Close. The selected target carries between action pages; right-click Target restores yourself. Player heads show ping, world, gamemode and staff flags. Action details show target availability, effective permission (including `.others` where relevant), and current state. Search and offline stored-name/UUID input use private chat capture: type `cancel` to leave. A single configurable timeout cancels abandoned input. Destructive actions require confirmation even when optional confirmations are disabled. Speed/coordinate adjusters support shift-click for larger steps. HUD inventories cancel all item transfers, creative/number-key/offhand swaps and drags, including the lower inventory.

`hud.yml` controls titles, category toggles/slots, content slots, materials, custom model data, filler/border, sounds, presets and tooltip text. Override an action item at `buttons.<command>-<argument>` (for example `buttons.heal-`). `/adm reload` validates HUD settings with other configuration and closes all HUD sessions and pending input. Per-viewer sounds, optional confirmations and compact preferences save asynchronously in embedded storage. Closing, quit, kick, reload and disable release sessions, pending input and session-owned tasks; late async results cannot reopen a HUD. HUD action entries use audit source `HUD`.

### HUD page overview and status checks

| Category | Features |
| --- | --- |
| Player / Teleport / Inventory | Player tools, searchable targets, coordinate adjusters, provider/offline/edit/lock details |
| Chat / Vanish / Server / Settings | Chat actions and spy, vanish levels/staff kit, guarded maintenance, async per-viewer preferences |
| Moderation | Freeze, mute/tempmute/unmute, warn, paginated warnings, confirmed clearwarnings and kick |
| Punishments | Confirmed ban/tempban/ipban/unban, type-filtered paginated history, alts |
| Reports | Paginated state/details, claim, close, and teleport through the teleport service |
| Logs | Paginated audit with exact staff, target and action filters; * removes a filter |
| Integrations | Actual LuckPerms hook/version and registered Ender Chest provider capabilities |
| Status | Module registration/dependencies/storage health, pending work, bounded timestamped errors, safe tests |

| HUD permission | Meaning |
| --- | --- |
| `adm.hud.use` | Open `/adm-hud` or `/admhud` |
| `adm.hud.category.<id>` | Open the named category (13 IDs listed above) |
| `adm.hud.status.test` | Run a safe dry run from module items on Status |
| `adm.hud.*` | All HUD nodes; does not replace underlying command nodes |

Command item names and indicator materials show green for working, yellow for degraded dependencies (fallback LuckPerms tiers, starting/slow storage), red for disabled/failed/unregistered modules, and gray for missing permission. Disabled category items stay unavailable. Status includes config enablement, actual command/listener registration, storage latency/connection, module-scoped pending database work, and bounded recent errors with timestamps. The summary lists non-healthy modules.

Opening Status or Refresh performs an asynchronous `SELECT 1` ping. Results use `status.ttl-seconds` (default 5); no background polling occurs by default. Optional `status.live-refresh-seconds` runs one shared lightweight task only while Status is visible and stops when its last viewer leaves. Test checks module enablement, command resolution, viewer permission, and embedded storage without executing gameplay/destructive actions; it checks stored-target resolution only for punishments and reports, where stored targets are required. Results appear in chat and the module tooltip. Tests require `adm.hud.status.test` and are audited as `HUD`.

### Custom screens and buttons API

Register on the server thread after ADM enables, using shared ADM classes rather than shading them:

```kotlin
val registry = requireNotNull(server.servicesManager.load(com.tecnor.adm.api.HudRegistry::class.java))
registry.register(this, "example") {
    object : com.tecnor.adm.hud.HudScreen {
        override val id = "example"
        override val title = "Example"
        override fun buttons(manager: com.tecnor.adm.hud.HudManager,
            session: com.tecnor.adm.hud.HudSession, viewer: org.bukkit.entity.Player) = listOf(
            com.tecnor.adm.hud.HudButton(10, "hello", "Hello", permission = "example.use") { _, _, player, _ ->
                player.sendMessage("Hello")
            }
        )
    }
}
// Add to another screen with registry.addButton(this, "player", button).
// Before shutdown: registry.unregisterAll(this).
```

Custom screens appear on Home. Navigation and protection are automatic; callbacks must use their own guarded service layer. `HudManager.navigate(session, viewer, screen)` pushes the previous screen; `refresh` skips unchanged slots. `load(session, work, apply)` runs SQL work asynchronously with plain data and applies live UI changes on the server thread only while the session exists. Screen data may be loaded in `HudScreen.opened`. Provider/plugin removal closes HUDs before stale callbacks can execute.

## Additional command and inventory behavior

- `/endersee` is a detached read-only snapshot. All clicks, including bottom-inventory clicks, shift clicks, number keys, offhand swaps, double clicks, creative clicks, drops, and drags are cancelled. `/enderedit` opens the provider's live/write-through inventory with one exclusive lock per target UUID. Existing viewers close before editing, and target/other viewer opens are denied until the lock is released. Another ADM staff view is refused while an edit lock exists.
- Vanilla edits use the actual online player's Ender Chest; there is no stale copy to save over newer contents. Both ender commands report **offline access unavailable** for cached offline players or UUIDs when vanilla is the selected provider. A higher-priority provider can opt into offline access.
- Target quit, staff quit, reload, disable, and provider unregister close affected views. Closing returns the staff cursor using Bukkit's normal close handling, synchronously flushes the lease, closes it, and releases the UUID lock. Providers must honor the lease contract below.
- `/invsee` is online-only. The 45-slot projection exposes slots 0–35 (storage/hotbar), 36–39 (boots through helmet), and 40 (offhand); slots 41–44 are unavailable. Read-only mode uses the same cancellation rules as endersee. Editable mode performs transactions against the target's **live** inventory on the server thread, rather than saving a GUI snapshot. Stale target-slot clicks are rejected and refreshed. Regular pickups/placement, shift transfers, hotbar/offhand swaps, drops, collection, and drags are supported; creative cloning is not. Drag transactions run on the next server tick after vanilla cursor restoration and validate all touched slots and the cursor before applying. Target/staff death closes invsee views. Self-editing is refused. Grant only `adm.admin.invsee` to read-only staff; ops and the admin wildcard also have editing permission.
- `/vanish` toggles; `/vanish on` uses the highest permitted level; `/vanish off` restores visibility; `/vanish set LVL` enables or changes to that exact permitted level; `/vanish help` explains visibility levels. Tab completion suggests these subcommands and levels 0–3. Numeric level nodes beyond 0–3 are supported through permission attachments. Viewers need `adm.admin.vanish.see` **and** a permitted level at least as high as the vanished player's chosen level. Unauthorized viewers lose entity and tab visibility. Existing mob targets are cleared; new targeting and item pickups are cancelled. Original pickup eligibility is restored on quit/disable/unvanish.
- Silent joining is opt-in via `adm.admin.vanish.join` plus `vanish.on-join` (default true); no vanish state persists through quit. Actual quits while vanished are always silent. `vanish.fake-quit` emits a configurable fake quit when manually entering vanish; `vanish.fake-join` emits a fake join when manually leaving it. Both default false. `/list` and `/near` respect viewer visibility.
- Whois online data includes ping, game mode, block location, and `PLAY_ONE_MINUTE` playtime converted from ticks. The IP line is omitted without `adm.admin.whois.ip`. Offline whois exposes only Paper first-played/last-seen and marks all other fields unavailable. Seen uses the same Paper timestamps, rendered as UTC ISO instants; absent timestamps are unavailable.

## File audit and sink API

`audit.enabled` defaults true. `audit.max-file-bytes` defaults 10485760 (10 MiB; accepted range 1024–2147483647). `audit.rotate-daily` defaults true. One dedicated async writer writes UTF-8 JSONL to `plugins/ADM/audit/audit-YYYY-MM-DD-N.jsonl`, rotating by UTC date or size. Its queue is bounded by both 4096 records and 8 MiB; records rejected at the limit are reported in the server log. Files older than `audit.retention-days` are pruned on the first file rotation of each UTC day. Existing active files are never overwritten and a new file is started after restart. A valid reload updates options after closing current inventory views. Queued records retain the options at submission; writer shutdown drains rather than discards them. File failures report truncated record previews to the server log as a fallback.

Access records include actor/target UUID and name, inventory type, read/edit mode and provider ID. Edit records include changed slot numbers, before/after material and amount, plus SHA-256 fingerprints of Paper item serialization; item contents and metadata are not written into audit details. Gameplay objects are serialized on the main thread; only immutable strings/UUIDs/instants reach the writer. Restrict access to the audit directory like other administrative logs.

`com.tecnor.adm.api.AuditSink` is registered with Bukkit's `ServicesManager`. Inventory and moderation operations use the highest-priority registered sink. ADM now owns a database-backed default sink independent of the inventory toggle; third-party sinks can replace it at a higher service priority. `audit.file-secondary: true` writes every record to the existing JSONL sink as well. Database audit failure falls back to the file sink even when secondary logging is false. Database rows record staff, target, action, details, timestamp, and source `COMMAND`.

`audit.retention-days` and `reports.retention-days` default to 90. `/adm cleanup` prunes audit rows and closed reports older than their retention thresholds; open/claimed reports and punishment history are retained. `audit.prune-on-start` defaults true. File rotation settings still apply to secondary/fallback files; database rows are pruned rather than rotated. Protect the database and audit files: they contain administrative and player information.

## Embedded H2 storage and punishments

ADM uses embedded H2 2.3.232 at `plugins/ADM/adm.mv.db`. It runs in-process; no database server, network listener, or external service is required. The `api.Storage` service exposes bounded asynchronous work, stored name resolution, and a thread-safe health snapshot. One database worker has a bounded 128-job queue. SQL and migrations run only on that worker; gameplay, permissions, item serialization, and inventory restoration run on the server thread. Pre-login waits are bounded and occur only on Paper's asynchronous login thread; the chat mute check never performs database work. Transactions restore auto-commit defensively, preserve the original SQL error when rollback/cleanup also fails, and close a connection that cannot be safely recovered. H2 write delay is disabled for durable commits, its cache is capped at 2 MiB, and lock waits are capped at three seconds. Identical recurring errors are rate-limited.

Schema changes use the `adm_schema` version table and idempotent migrations through schema version 4. The tables preserve player/name history, punishments, frozen state, staff recovery snapshots, reports, audit events, and HUD preferences. Name lookups and audit filters remain case-insensitive. Startup logs the H2 engine version and runs a transactional read/write self-check. Check `/adm storage-info` or HUD Status: normal state is `CONNECTED`. The H2 JDBC dependency is declared in Gradle and the root `manifest.kod`; there is no driver download or fallback loader.

This build does not read, convert, or delete existing legacy database files. The new H2 database starts empty; preserve any older database separately if its historical records are needed. Back up `adm.mv.db` only after stopping the server, and restrict access because it contains player identities, IP addresses, punishments, reports, audit entries, and staff recovery data.

Run `/adm database` with `adm.admin.database` to print the absolute H2 file path. The command does not expose the database over the network. Do not move, replace, or edit the live file while the server is running.

Pre-login records event UUID, name, IP, and first/last login timestamps. Name history maps recorded names to UUIDs, case-insensitively; reused names resolve to the most recent recorded account. Offline punishment targets must exist in this table; use a UUID to disambiguate. `/whois` and `/seen` retain Paper information and append stored first/last login and name history. Stored last IP is shown only with `adm.admin.whois.ip`, including offline results.

Bans, IP bans, mutes, warnings, and kicks are recorded durably before live effects. Durations accept positive integer minutes/hours/days (`30m`, `2h`, `7d`, `30d`); bans and mutes without a duration are permanent. Expiry checks use timestamps, not timer tasks. Mutes are loaded during pre-login and cached through the session, then evicted on quit. Warnings cleared with `/clearwarnings` stop contributing to escalation but remain in `/history`. IP bans apply to all accounts using the IP; alts only groups accounts by last stored IP and is not proof of common ownership. List commands use indexed SQL count/limit/offset pagination, with `punishments.page-size` default 10 (1–50).

Example escalation, evaluated on the new uncleared warning count and applied through the same punishment transaction/services:

```yaml
punishments:
  default-reason: No reason specified
  page-size: 10
  escalation:
    - warnings: 3
      action: tempmute
      duration: 1h
    - warnings: 5
      action: tempban
      duration: 1d
```

## Staff tools and reports

Freeze persists until explicitly toggled off. Block-position movement, teleports, interaction, inventory changes, and commands outside `freeze.command-whitelist` are blocked. The title is sent once per freeze/join; one shared actionbar task runs only while at least one online, non-exempt player is frozen. `freeze.actionbar-seconds` must be at least 5. `freeze.quit-action` accepts `notify` (default; `adm.mod.notify` recipients), `none`, or `command:<console command>` with `{player}` substitution. Relogging reloads persisted freeze state; bypass holders are exempt.

Staff chat uses `staffchat.prefix` MiniMessage and cached LuckPerms prefix/suffix (legacy ampersand formatting) when available. Messages are literal text. `/sc message` sends once; `/sc` toggles routing. Spy toggles are cleared on quit. `spy.social-commands` identifies social commands; `spy.ignored-commands` defaults to login/register to avoid exposing authentication payloads.

Staff mode closes existing inventory sessions, serializes the full inventory including armor/offhand plus game mode, flight flags/speeds, location, and prior vanish level on the main thread, and commits the snapshot asynchronously **before** swapping to the staff kit. Entry/recovery/exit transitions lock inventory manipulation. Kit items cannot be dropped, transferred, or collected through invsee. Slot 1 randomly teleports (`adm.mod.tp`); right-click a player with slot 2 to freeze (`adm.mod.freeze`), slot 3 to inspect (`adm.admin.whois`), or slot 4 to open read-only invsee (`adm.admin.invsee`). Staff mode enables creative flight and vanish. Exact snapshot state is restored on exit/quit/disable, saved to Paper player data, and only then removed from storage. A surviving snapshot is loaded before play on the next join after a crash. Snapshot flight/speed state takes precedence over earlier temporary `/fly` or `/speed` caches for that player.

Reports require a stored player target and a reason. `adm.report` defaults true; `reports.cooldown-seconds` defaults 60. Online staff with `adm.mod.notify` receive notifications. The 54-slot `/reports` GUI shows 45 entries per page, including open/claimed/closed status. Left-click claims an open report; right-click closes it; shift-left teleports to an online reported player. Each action has its own permission. Atomic conditional updates prevent double claims. All clicks and drags, including the viewer's bottom inventory, are cancelled.

`/adm log` also uses a protected 45-entry paginated inventory for players and paginated text for console. Filters are exact, case-insensitive staff name, target name, and action; use `*` to omit positional filters, e.g. `/adm log Alice * BAN 2` or `/adm log 2`. Arrow buttons navigate pages. GUI items never enter player inventories.

## Ender Chest provider guide

All provider/registry/lease calls are **server-thread-only**. Add ADM as a required dependency loaded before your provider plugin, with shared classpath access; compile against ADM's API without copying or shading its classes. Acquire the registry after ADM has enabled its inventory module:

```kotlin
val registry = requireNotNull(server.servicesManager.load(EnderChestProviderRegistry::class.java))
registry.register(this, provider)
// Before your provider's backing service shuts down:
registry.unregister(this, provider.id)
```

API package: `com.tecnor.adm.api`. The signatures are:

```kotlin
interface EnderChestProvider {
    val id: String
    val priority: Int // defaults to 0; higher wins
    val supportsOffline: Boolean
    fun supports(target: UUID): Boolean
    fun open(target: UUID, editable: Boolean): EnderChestLease
}
interface EnderChestLease : AutoCloseable {
    val inventory: Inventory
    fun flush()
    override fun close()
}
```

- IDs must be unique lowercase identifiers. Registry `register(owner, provider)`, `unregister(owner, id)`, `unregisterAll(owner)`, `providers()`, and `select(target)` are exposed. Only the owning plugin may unregister its entry. Explicit unregister closes its ADM views **before** removal; ADM also unregisters disabled plugin owners. Explicit unregister in your own `onDisable` is recommended so your backing service is still usable during flush.
- Highest priority accepting the target wins (ties use registration order); vanilla accepts all UUIDs at the minimum integer priority. Selection does not silently fall through when a selected provider lacks offline support, preventing accidental access to a different chest.
- `open` must return a chest-shaped inventory of 9–54 slots in multiples of nine. Editable leases must exclude all owner GUI access and competing API writes, including offline-to-online transitions, until `close`. ADM prevents its own competing staff views and vanilla Ender Chest opens, but cannot enforce writes in another plugin's private storage code. Close existing provider viewers before returning a write-buffer lease.
- Prefer a live backing inventory. If using a buffer, `flush` must synchronously and atomically apply changed items to the provider's authoritative data without overwriting concurrent data. ADM flushes after accepted ender edit ticks and again at close; `close` releases provider-owned resources. Do not schedule a deferred save that can race reconnect, server player saves, or lock release. `flush`/`close` must be reliable and `close` must tolerate cleanup after partial acquisition. Provider failure is logged; arbitrary broken provider persistence cannot be repaired by ADM.
- Read leases are copied into a snapshot and immediately closed. Never expose mutable storage to asynchronous work. UUIDs, not player names, identify chests. Third-party code must not retain a lease inventory and mutate it after closing.

### Example offline-capable test provider (not installed or enabled)

This is a **separate test plugin**, not ADM runtime code. It owns isolated in-memory test chests for configured UUIDs, online or offline; it does **not** read, replace, or persist vanilla player data. Contents survive ADM reload and staff/target disconnects while the test plugin remains enabled, but not a test-plugin/server restart. Replace the UUID below with a known test account. A production integration replaces the map with its own authoritative service and enforces that service's external access locks.

```kotlin
package example

import com.tecnor.adm.api.EnderChestLease
import com.tecnor.adm.api.EnderChestProvider
import com.tecnor.adm.api.EnderChestProviderRegistry
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class MemoryTestProvider(private val targets: Set<UUID>) : EnderChestProvider {
    override val id = "memory-test"
    override val priority = 100
    override val supportsOffline = true
    private val chests = mutableMapOf<UUID, Inventory>()
    private val editing = mutableSetOf<UUID>()
    override fun supports(target: UUID) = target in targets

    override fun open(target: UUID, editable: Boolean): EnderChestLease {
        check(Bukkit.isPrimaryThread())
        require(supports(target))
        check(target !in editing)
        val chest = chests.getOrPut(target) {
            Bukkit.createInventory(null, 27).also { it.setItem(0, ItemStack(Material.DIAMOND, 8)) }
        }
        if (editable) editing.add(target)
        return object : EnderChestLease {
            override val inventory = chest
            override fun flush() = Unit // inventory is already authoritative
            override fun close() { if (editable) editing.remove(target) }
        }
    }
}

class TestEnderPlugin : JavaPlugin() {
    private lateinit var registry: EnderChestProviderRegistry
    private lateinit var provider: MemoryTestProvider
    override fun onEnable() {
        registry = requireNotNull(server.servicesManager.load(EnderChestProviderRegistry::class.java))
        provider = MemoryTestProvider(setOf(UUID.fromString("00000000-0000-0000-0000-000000000001")))
        registry.register(this, provider)
    }
    override fun onDisable() {
        if (::registry.isInitialized && ::provider.isInitialized) registry.unregister(this, provider.id)
    }
}
```

The example plugin's `paper-plugin.yml`:

```yaml
name: TestEnder
version: '1.0'
main: example.TestEnderPlugin
api-version: '1.21'
dependencies:
  server:
    ADM:
      load: BEFORE
      required: true
      join-classpath: true
```

## Manual test checklist

1. Build through Kodari's Compile window; verify a successful Java 21 build. No compilation or server execution is asserted by this checklist.
2. Start on Paper 1.21.x without LuckPerms; confirm YAML generation and all configured module statuses. Repeat with LuckPerms; verify `moderator`, `admin`, and `owner` are created with weights and inheritance, but no users are assigned automatically. Assign test users and verify each role's ADM and vanilla command permissions. Repeat with every module false: no feature commands should activate; infrastructure still handles storage fallback.
3. Test `/adm` and its alias, reload/version/debug/storage-info/database, permission denial, and console execution. Confirm author `voidles02` and Stage 13 version. Debug toggles diagnostic logging ON/OFF while still listing module states.
4. Test every game mode, `/gm`, all four shortcuts, fly, both speed types including 0/10, god damage protection, heal/feed, repair hand/all including armor/offhand, and clear including armor/offhand. Test self, online others, unknown players, extra arguments, and console with explicit targets.
5. Give base tool permission but not `.others`: others must be denied. Add `.others` and test heal/feed/god/clear on lower, equal, higher, and immune non-op targets. Equal/higher and immune must be denied. Repeat with tier markers without LuckPerms, then primary group weights with LuckPerms; verify a live LP user recalculation updates rank. Test console and hierarchy bypass.
6. Test tp/tphere/tpall, absolute tppos with/without world, invalid world, NaN/out-of-bounds coordinates, and unloaded destination chunks. Confirm main-thread chunk loading is not initiated by ADM. Cancel a teleport from another plugin and check its failure message.
7. Test back before any saved location, after each ADM teleport, after death/respawn, and twice consecutively. Quit/rejoin: prior back location must be absent. Test top in caves, under a roof, near hazardous blocks, in liquid columns, and where no safe surface exists.
8. Test near default/custom/invalid radius, same-world filtering, ping self/other/console, and list. Completion must contain only online names and fixed values, not offline names/world names.
9. Test broadcast, clear chat, global mute/bypass, slowmode/off/bypass, configured interval bounds, and sudo chat/command/hierarchy/nested invocation. Slowmode must allow the first message, block an immediate second, and allow another after the interval. Test with non-op users: ops have bypasses by default.
10. Change a message and cooldown, reload, and verify immediate changes, including cached chat denials. Remove a message key: built-in fallback plus one warning. Invalid YAML, unbalanced tags, wrong booleans, and bad ranges must preserve the whole old snapshot.
11. Change each module toggle and the ADM root/aliases, reload, and verify individual restart-required notices with unchanged live registration. Restart and verify the changes. Set a game-mode cooldown and alternate gm/gmc/gms: shortcuts must share it.
12. Enable temporary god/fly/speed, populate back and slowmode timestamps/cooldowns, then quit/rejoin. God/back/cooldowns must be cleared, speeds/flight restored, and first chat allowed. Disable ADM with players online and check restoration and listener/task cleanup. Stop during reload or a pending teleport: no late state reinsertion or scheduler-after-disable errors.
13. With vanilla only, use both ender commands against online, cached offline, and offline UUID targets. Offline must say unavailable. Test read-only left/right/shift/number-key/offhand/double/creative clicks, drops and drags in both inventories: nothing changes. Grant edit and verify transfers update the real chest.
14. While editing, try target chest opens and a second staff edit/see. No simultaneous writer or duplicate item may appear. Quit target, quit staff, reload ADM, and disable ADM in separate trials with items in slots and on the staff cursor. Verify views close, items are conserved, and the chest is accessible afterward.
15. Install the separate documented test provider only on a test server. Access its configured UUID offline with both ender commands, edit, close and reopen, then test staff/target quits and ADM reload. Unregister/disable the provider with an open view: it must close before provider removal. Vanilla targets still use vanilla behavior.
16. Invsee offline must report unavailable. Online read-only must reject every edit path. Grant the edit node; test armor/offhand/storage transactions and target movement, pickup, inventory clicks and death while viewing. Verify live changes are never overwritten by a closing snapshot, stale clicks refresh, multiple staff cannot duplicate, and quit/reload/disable closes views.
17. Check JSONL access/edit records and metadata changes. Set max size to 1024, generate edits, and check rotation; daily files rotate at UTC midnight. Toggle audit options via reload. Stop with pending records and verify they drain. Make the audit directory unwritable and verify server-log fallback records.
18. Use non-op viewers with no see permission, see but lower level, and see plus equal/higher level. Test `/vanish on`, `/vanish off`, `/vanish set LVL`, `/vanish help`, bare `/vanish`, `/v`, denied levels, tab/entity visibility, mob target clearing and new targeting, item pickup, new viewer joins, and live permission changes. Grant silent-join permission and reconnect: actual join/quit messages must be absent and state removed on quit. Test optional fake messages and disable restoration.
19. Whois without IP permission must omit the IP line; with it, show online IP. Test ping, game mode, location and playtime. Offline whois must expose only Paper dates and mark the rest unavailable. Seen must match Paper first-played/last-seen, including console, missing dates, cached names and UUIDs.
20. Start once with the plugin data directory unavailable or unwritable. ADM must report storage FAILED, storage-dependent modules must be unavailable, and non-storage commands and staff chat must work. Test both login fallback settings; restore write access and restart.
21. Apply bans, IP bans, mutes, warnings, clears and removals; restart and verify persistence/expiry. Test every punishment command against lower/equal/higher/immune offline and online targets. Test both configured warning thresholds. Populate 500+ rows and check history/warnings/alts pages and stored aliases/IP gating.
22. Freeze a non-exempt player; test block movement, rotation, interaction, teleport, whitelisted and blocked commands, quit notification/action, and reconnect. Freeze multiple players; unfreeze/quit the last one and verify the shared actionbar task stops.
23. Enter staff mode with metadata-rich items in every inventory region and distinct flight/game mode/location settings. Test every kit tool and inventory-transfer prevention. Exit, quit, disable, and force-stop/restart separately; compare exact restored state and retained snapshots when a world or database is unavailable. Repeat after using Stage 1 fly/speed.
24. Test staffchat toggle/one-shot, rank formatting, command/social spy permissions, ignored credentials, and quit clearing. Submit reports with cooldown; test claim races, close and teleport permissions, status display, 500+ rows, bottom inventory clicks, creative clicks, shift/hotbar/offhand swaps and drags.
25. Check database inventory edit and punishment audit records, COMMAND source, optional JSONL secondary and fallback on failure. Paginate `/adm log` with 500+ rows and mixed filters; try all GUI item-extraction paths. Test cleanup retention without deleting open reports or punishment history.
26. Open `/adm-hud` and `/admhud`; check all 13 category nodes separately, individual command nodes and `.others`, Back/Home/Close and persistent target selection. Compare command and HUD outcomes, hierarchy denials and cooldowns. Confirm every destructive action, including kick/clear/tpall/sudo/ban/tempban/ipban/clearwarnings, and cancel each without effects.
27. Test player search and offline stored UUIDs, numeric/coordinate/vanish presets, arbitrary reason and duration input, permanent durations and their separate permissions. Type cancel, wait for timeout, quit, kick and reload during input/loading. Private input must not reach normal or staff chat, and no late result may reopen a closed HUD.
28. Attempt every inventory click/drag, hotbar/offhand swap, shift transfer, drop, double-click and creative clone in every HUD screen. Check item conservation, loading placeholders, pagination/filter boundaries and live report detail refresh. Save preferences, close immediately and restart: preferences must persist.
29. Inspect H2 startup's engine version and read/write self-check, schema version 4, and persistence after restart. Use `/adm storage-info` and Status to confirm CONNECTED. Test an unwritable database directory and slow storage. Missing permission is gray; disabled/failed is red; absent LuckPerms/slow storage is yellow. Disable every feature module: the HUD still opens with unavailable categories.
30. Select a Status module and use Test with/without `adm.hud.status.test`; confirm chat/tooltip pass/fail and no gameplay mutations. Enable optional live refresh and open Status for multiple viewers: exactly one shared refresh task, stopping after the last Status viewer leaves. Check HUD source on action and async outcome audit rows, including provider-backed edits, and confirm no sessions/input/tasks survive closing/reload/quit/disable.