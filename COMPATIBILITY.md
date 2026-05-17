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
- **V2.1 Auction House backend is in place.** The `auction` package
  ships the data model, an SQLite-backed `AuctionStorage`, an
  idempotent `AuctionExpiryService`, the dupe-safe sell / cancel /
  collect flows, and the text-only `/ah` command. The premium German
  GUI, the buy flow, bidding, search and sort all land in later
  releases. The Auction House talks to the existing `EconomyBridge`
  only and never imports a specific economy plugin; see
  [`DEPENDENCIES.md`](DEPENDENCIES.md).
- **Auction House uses SQLite via Paper's `libraries:` loader.** No NMS
  or `org.bukkit.craftbukkit.*` is used. On Spigot / CraftBukkit the
  loader directive is ignored; the Auction House detects the missing
  JDBC driver during `init()`, logs a German error and disables itself
  without affecting spawn, RTP, portals, zones or the economy bridge.
