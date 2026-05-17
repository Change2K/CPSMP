# CPSMP Compatibility Notes

This document describes which server platforms and versions CPSMP supports,
and what to keep in mind when updating the plugin for newer Paper/Minecraft
releases. Player-facing copy is German and lives in `messages.yml`; all class
names, config keys and code comments are intentionally English.

For the full list of build/runtime dependencies and optional integrations
(Vault and Vault-compatible economy providers), see
[`DEPENDENCIES.md`](DEPENDENCIES.md).

## Supported platforms

| Platform | Support level | Notes |
|---|---|---|
| **Paper 1.21.4** | **Primary** | Reference target. All features developed and tested here first. |
| Paper 1.21.5+ | Best-effort | Expected to work without changes. Re-test after a major release. |
| Purpur (Paper-based) | Best-effort | Should work whenever the underlying Paper version is supported. |
| Spigot 1.21.x | Best-effort | The teleport pipeline falls back to a synchronous main-thread teleport. MiniMessage / Adventure messaging is Paper-bundled and is not shaded for Spigot; messages may render differently or not at all without `adventure-platform-bukkit`. |
| CraftBukkit / vanilla Bukkit | Best-effort | Same caveats as Spigot. |
| Folia | **Not supported** | CPSMP uses `Bukkit.getScheduler()` and `Bukkit.isPrimaryThread()`, which Folia replaces with region schedulers. |

CPSMP is **Paper-first**. Fallbacks for non-Paper servers must never
reduce the quality of the Paper experience.

## What is and is not used

CPSMP intentionally avoids version-fragile constructs:

- **No NMS** (`net.minecraft.server.*`).
- **No CraftBukkit internals** (`org.bukkit.craftbukkit.*`, `getHandle()`).
- **No obfuscated server classes**.
- **No reflection on internal server APIs**.
- **No experimental Paper APIs** (`@ApiStatus.Experimental`, etc.).
- **No hardcoded Minecraft version checks**. Platform capabilities are
  detected reflectively in `compat/ServerCompatibility`.

The plugin uses only:

- Public Bukkit/Spigot API (events, scheduler, world, blocks, entities).
- Public Paper API where it improves performance or safety (async teleport,
  async chunk loading, `PluginMeta`). All Paper-only entry points go through
  the `compat` package and have Bukkit fallbacks.
- Adventure (`Component`, `MiniMessage`, `Title`, `Audience`) for messaging.
  This ships with Paper natively; on pure Spigot/CraftBukkit you would need
  to shade `adventure-platform-bukkit` to get full message support.

## Compatibility abstractions

| Component | Purpose |
|---|---|
| `compat/ServerCompatibility` | Reflective probe of Paper-only APIs (`teleportAsync`, `getChunkAtAsync`, `getPluginMeta`). Also exposes `getPluginVersion(Plugin)` with a `PluginDescriptionFile` fallback. |
| `compat/TeleportAdapter` | Interface for async-style teleport + chunk loading. |
| `compat/PaperTeleportAdapter` | Uses Paper's native async APIs. Selected automatically when both `teleportAsync` and `getChunkAtAsync` are present. |
| `compat/BukkitTeleportAdapter` | Sync fallback. Pre-loads the destination chunk on the main thread, then teleports via `Player#teleport(Location, TeleportCause)`. All work runs through the Bukkit scheduler so the API is never touched off-thread. |
| `compat/RegistryLookup` | Version-tolerant resolver for `Sound` and `Particle`. Accepts both legacy enum names (e.g. `ENTITY_ENDERMAN_TELEPORT`) and namespaced keys (e.g. `minecraft:entity.enderman.teleport`). Survives `Sound`/`Particle` enum reshuffles in newer Paper. |

The selected teleport backend is logged at startup and shown in
`/cpsmpadmin info` (`PaperAsync` or `BukkitSync`).

## RTP cooldown bypass

Two ways to skip the `/rtp` and `smp_rtp`-portal cooldown:

- The player is **OP**.
- The player holds **`cpsmp.rtp.bypasscooldown`**.

Both paths are checked by `RTPService#hasRtpCooldownBypass(Player)` and apply
to every entry point of `runRandomTeleport`, including the `smp_rtp` portal.
The cooldown is also **not written** for bypassing players, so subsequent
calls remain instant. The configured `rtp.cooldown-seconds` is preserved and
still applies to everyone else.

## V3.0 Homes, TPA, and inventories

- **Homes / TPA / optional `/back`** use `teleports.yml`, German strings in `messages.yml`, and SQLite (`teleports.db` by default) for `player_homes` and `teleport_back_locations` when Homes or `/back` are enabled. Cooldown bypass uses `cpsmp.home.bypasscooldown`, `cpsmp.tpa.bypasscooldown`, or OP.
- **Command conflicts**: Bukkit resolves overlapping command names by plugin load order. CPSMP registers duplicate **`cp*`-prefixed** commands in `plugin.yml` (`/cphome`, `/cptpa`, …) as a stable fallback without unregistering other plugins' commands. Startup may log a German hint if a primary name is owned by another plugin (`messages.yml` → `admin.log.*`).
- **Inventories**: CPSMP does not clear or migrate inventories on `/smpspawn`, `/rtp`, portals, zones, Homes, or TPA. If you use Multiverse-Inventories, PerWorldInventory, or similar, configure one shared group for your SMP gameplay worlds.

## Updating to a newer Paper / Minecraft version

1. Bump the `paper.api.version` property in `pom.xml`:

   ```xml
   <paper.api.version>1.21.X-R0.1-SNAPSHOT</paper.api.version>
   ```

2. If the new release moves the minimum Java version, bump `<java.version>`
   in `pom.xml` (and the `<maven.compiler.source/target>` mirrors above it).

3. If you crossed a Minecraft minor release (e.g. 1.21 → 1.22), update
   `api-version` in `src/main/resources/plugin.yml` to match.

4. Run `mvn -DskipTests clean package` and confirm BUILD SUCCESS with no
   warnings.

5. Smoke test on a clean server:

   - `/smpspawn`, `/rtp`, `/cpsmpadmin info`, `/cpsmpadmin reload`
     (CPSMP intentionally does **not** register `/spawn`; that name is left
     free for another plugin to claim.)
   - After `/cpsmpadmin reload`, confirm portal scanning still runs once
     (no duplicate polling tasks); tweak `portals.check-interval-ticks` and
     reload again to verify the new interval applies.
   - Walk into each configured portal (`lobby_to_smp`, `smp_rtp`,
     `smp_to_danger_zone`, `smp_to_attack_zone`)
   - Verify the teleport delay countdown, cancel-on-move, cancel-on-damage,
     success sound, titles and actionbars all still fire.
   - Verify `/cpsmpadmin info` reports the new platform and teleport backend.
   - Verify OP and `cpsmp.rtp.bypasscooldown` holders skip the RTP cooldown.

6. If `Sound` or `Particle` enum constants used in config were renamed in
   the new version, migrate the affected config values to namespaced keys
   (e.g. `minecraft:block.beacon.activate`). `RegistryLookup` already
   accepts both formats.

## Known boundaries (intentional)

- **Adventure is not shaded.** If you want first-class Spigot/CraftBukkit
  support you must add `adventure-platform-bukkit` and adjust
  `MessageManager` accordingly. This is out of scope for V1.
- **Folia is not targeted.** Migrating would touch every scheduler call and
  the `BukkitTeleportAdapter`.
- **Homes, TPA and Claims are still out of scope** and planned for later
  versions.
- **V2.3 Auction House adds the premium German GUI on top of the
  V2.1/V2.2 backend.** The new `auction.gui` package contains
  `AuctionGuiManager`, `AuctionGuiSession` (the `InventoryHolder` used
  to scope all click handling), `AuctionGuiItemFactory` (clone-only
  display items so the stored `ItemStack`s are never exposed),
  `AuctionGuiClickListener` (cancels every click in our inventories
  and accepts only LEFT/RIGHT/MIDDLE click types), and a thin set of
  screen builders for Main / Browse / Listings / Collect / Confirm.
  No buy / sell / cancel / collect logic is duplicated in the GUI;
  every click delegates to the same `AuctionHouseManager` methods
  the text commands use. V2.5 adds browse search, configurable sort
  modes and admin cleanup of old terminal listing rows; V2.6 adds
  unified reload handling, config health warnings, RTP radius
  self-correction and finer `cpsmp.ah.listings` gating. Bidding
  remains out of scope.
- **Auction House uses SQLite via Paper's `libraries:` loader.** No NMS
  or `org.bukkit.craftbukkit.*` is used. On Spigot / CraftBukkit the
  loader directive is ignored; the Auction House detects the missing
  JDBC driver during `init()`, logs a German error and disables itself
  without affecting spawn, RTP, portals, zones or the economy bridge.
- **Race protection for `/ah buy` is enforced in SQL.** A single
  `UPDATE auction_listings SET status='SOLD', buyer_uuid=?, buyer_name=?
  WHERE listing_id=? AND status='ACTIVE' AND expires_at>?` atomically
  claims the listing for one buyer. Concurrent buyers see zero
  affected rows and receive the `auction.buy-already-sold` German
  message. Withdraw / deposit failures roll back the claim via
  `revertSoldIfBuyer(listing_id, buyer_uuid)`, which is itself guarded
  so a stale rollback can never undo a later successful sale.
- **Auction House GUI uses Bukkit inventory APIs only.** No NMS, no
  CraftBukkit internals, no reflection on internal server packages.
  The GUI relies on Paper's `Bukkit.createInventory(InventoryHolder,
  int, Component)` overload to set rich Adventure titles. If the
  overload is missing (pure Spigot), the GUI falls back to the legacy
  `String` title overload after serializing the Component to plain
  text - the GUI still works, just without gradient titles. If the
  `gui.enabled` flag in `auctionhouse.yml` is `false`, or any
  unexpected error happens during inventory creation, `/ah` silently
  falls back to the clean German text help and every subcommand keeps
  working unchanged. Every GUI click is cancelled before logic runs;
  only LEFT/RIGHT/MIDDLE clicks dispatch a screen action, which
  blocks shift-click moves, number-key swaps, double-click hoover,
  drop keys, offhand swap and creative clicks at the source. Drag
  events are cancelled the moment any of their raw slots overlap
  with an Auction House inventory.
- **V2.4 GUI sell flow** adds `AuctionGuiSession.Screen.SELL` /
  `SELL_CONFIRM`, session escrow for the offered stack and a virtual
  anvil step. The typed price is read only through
  `AnvilView.getRenameText()` (never `AnvilInventory.getRenameText()`).
  After confirmation listing creation uses
  `AuctionHouseManager.createListing(Player, ItemStack, double)` so
  the GUI never misuses the player's main hand. Aborts and disconnects
  return items via `AuctionHouseManager.safeReturnItemOrCollect`.
- **V2.5 Browse UX** adds `/ah search` (substring on seller name,
  material name, item display name), configurable browse sort modes
  (SQL `ORDER BY` when unfiltered; full ACTIVE scan + in-memory filter
  on the dedicated AH DB thread when searching), **Sortieren** and
  **Aktualisieren** buttons on the browse nav row,
  `AuctionItemCategory` as a passive Material→category helper for future
  filters, and `/ah admin cleanup` to delete historic terminal listing
  rows (never ACTIVE listings; never `auction_collect_items`).
