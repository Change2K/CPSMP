# CPSMP manual test checklist (V3.0+ / V4.0)

Use this list before promoting a build to production. Player-facing strings are German (`messages.yml`); this document is English for operators.

V3.0 adds **Homes**, **TPA**, optional **`/back`**, and `teleports.yml` (plus `teleports.db` when Homes or `/back` are enabled). V3.1 hardens those paths. **V4.0** adds **Claims** / base protection (`claims.yml`, `claims.db` SQLite). `/spawn` remains **unregistered** by CPSMP.

## Local server setup

1. Java **21**, **Paper 1.21.4** (or your supported target from `COMPATIBILITY.md`).
2. Drop `CPSMP-*.jar` in `plugins/`.
3. Start once to generate default configs.
4. Configure `config.yml` (spawn, RTP, portals, zones), `teleports.yml` (Homes / TPA / back), `claims.yml` (V4.0 claims), `economy.yml`, `auctionhouse.yml`, `portals.yml`, `zones.yml` as needed.

## Required plugins (by scenario)

| Scenario | Plugins |
|----------|---------|
| Core only (spawn, RTP, portals, zones) | None |
| V4.0 Claims (default) | None (SQLite via Paper `libraries:`) |
| Auction sell with fees / economy gate | Vault + a Vault economy (e.g. EssentialsX + EssentialsX Economy) |
| Auction buy | Vault + economy (hard requirement) |

## Basic CPSMP commands

- `/smpspawn` — teleport to configured SMP spawn (`cpsmp.spawn`).
- `/rtp` — random teleport (`cpsmp.rtp`).
- `/cpsmpadmin` — admin tools (`cpsmp.admin`; `reload` also needs `cpsmp.reload`).
- `/ah` — Auction House hub / GUI (`cpsmp.ah` + sub-permissions).
- **V4.0**: `/claim`, `/claiminfo`, `/claims`, `/plots`, `/trust`, `/untrust`, `/trustlist`, `/abandonclaim`, `/claimadmin`, **`/plot`**, **`/cpplot`** (+ `cp*` aliases). Permissions: `cpsmp.claim`, `cpsmp.claim.show`, `cpsmp.claims.*`, `cpsmp.claim.bypass`, `cpsmp.claim.admin`.

Confirm `/spawn` is **not** registered by CPSMP (by design).

## Homes / TPA (V3.0)

- **`/sethome` / `/cpsethome`**: set a named home in an allowed world; invalid names rejected; blocked worlds reject with `home.set-disabled-world`.
- **`/home` / `/cphome`**: delayed teleport; cancel on move/damage/combat when configured; unloaded home world → `general.world-missing`.
- **`/homes` / `/cphomes`**: GUI when `homes.gui-enabled` and `cpsmp.home.gui`; otherwise chat list (`home.list-*`).
- **`/delhome` / `/cpdelhome`**: delete a home.
- **Limits**: `cpsmp.homes.*` and `homes.default-limit` (highest permission wins).
- **Cooldowns**: home teleport, sethome, TPA request; OP + `cpsmp.home.bypasscooldown` / `cpsmp.tpa.bypasscooldown` bypass.
- **TPA**: `/tpa`, `/cptpa`, `/tpaccept`, `/tpdeny`, optional `/tpahere` / `/cptpahere`; expiry; one pending incoming per target.
- **World rules**: `teleports.yml` allow/block lists + optional CPSMP zone world integration.
- **Command fallback**: if another plugin owns `/home`, verify `/cphome`; check console for `admin.log.command-conflict`.
- **Admin**: `/cpsmpadmin homes info|delete|reload`.

### Homes / TPA (V3.1 hardening)

- **TPA accept**: decline paths (expired, sender offline, bad destination world, blocked world for mover or destination, mover in combat) must **remove** the pending request; sender should be able to send again after a fix. Successful accept still notifies both players before the teleport countdown.
- **TPA + combat**: with combat tagging and `combat.block-tpa: true` in `teleports.yml`, put the **moving** player in combat and run `/tpaccept`; expect `tpa.teleport-combat` for both sides and no teleport.
- **TPA + logout**: disconnect sender or target with a pending request; the other side should not keep a stale paired request (cancel / expiry messaging as designed).
- **TPA + reload**: `/cpsmpadmin reload` clears pending TPA; only **one** expiry ticker runs after reload (watch logs / no duplicate “expired” spam).
- **Tab completion**: player **without** `cpsmp.tpa` gets empty `/tpa <tab>`; with permission, sees online player names (respect `canSee`). Same for `/tpahere` vs `cpsmp.tpa.here`.
- **Homes GUI**: with GUI open, **Q (drop)** and **swap hands** must not move items; shift-click / number keys on the top inventory should remain ineffective for CPSMP slots (parity with Auction GUI expectations).
- **Delete confirm GUI**: only explicit confirm/cancel slots act; closing the inventory should not delete a home.
- **`/back`**: if enabled, start a delayed teleport, disconnect before completion; no errors on reconnect. Async DB read completes when player is offline → no messages or teleport applied (`player.isOnline()` guard).
- **Persistence**: restart server and `/cpsmpadmin reload` — home rows in SQLite unchanged; TPA only lives in memory across reload.

## Claims / base protection (V4.0)

- **World rules**: SMP (`smp`) allowed by default; `danger_zone` and `attack_zone` blocked in bundled `claims.yml` — adjust to your real world names.
- **`/claim` / `/cpclaim`**: creates a centered claim; overlap → `claim.overlap`; wrong world → `claim.world-disabled`; at limit → `claim.limit-reached`. OP / `cpsmp.claims.unlimited` bypass count limits.
- **`/claiminfo`**: in-claim details + optional particle border; standing outside → `claim.not-in-claim`.
- **`/claims` / `/cpclaims` / `/plots` / `/cpplots`**: GUI overview of your claims (`cpsmp.claim.list`); empty state when none; left-click toggles per-claim border in-world (same world), right-click details, shift-right opens delete confirmation; delete re-checks ownership.
- **Trust**: stand in **own** claim; `/trust <online player>` / `/untrust`; `/trustlist` (requires same permission node as trust).
- **`/abandonclaim`**: two-step within 10s; moving to another claim resets confirmation.
- **Protection** (non-trusted visitors): break/place, configured containers/doors/redstone, living entities, hanging entities, vehicles (when enabled), explosions (blocks removed from explosion list inside claims), fire spread / burn across claim edge, fluid flow into claim from outside, bucket use.
- **Bypass**: `cpsmp.claim.bypass` or OP — subtle actionbar `claim.bypass-actionbar` (throttled).
- **Admin**: `/claimadmin info <player>`, `/claimadmin delete <id>`, `/claimadmin reload` (`cpsmp.claim.admin`). `/cpsmpadmin info` includes a Claims line.
- **Reload**: `/cpsmpadmin reload` reloads `claims.yml` and refills the claim cache. `/claimadmin reload` reloads only `claims.yml` + claim cache. Turning claims **off** in `claims.yml` unregisters protection listeners (SQLite may stay open). Turning claims **on** again via reload opens SQLite and registers listeners without a full server restart when JDBC is available.
- **Persistence**: `claims.db` survives restarts; verify after reboot that `/claims` still lists rows.
- **`/plot show`** / **`/cpplot show`** / **`/claim show`** (and `show toggle`, treated the same): **toggle** your personal outline — first activation while **standing in a claim** shows the border; activate again to **hide immediately**. While **not** in a claim, first use → `claim.show-not-in-claim`. With **`claims.visuals.enabled: false`**, the command clears any leftover per-player WorldBorder/particles and sends `claim.show-disabled`. Requires `cpsmp.claim.info` **or** `cpsmp.claim.show`.
- **`claims.visuals.mode`**: default **`worldborder_if_safe`** uses Paper/Bukkit `createWorldBorder()` + `Player#setWorldBorder` (approximate **square** using the larger of width/depth for rectangular claims); **`particles`** block is the fallback path with **`END_ROD`** (or config) at low density (`max-visible-particles-per-tick`). Does **not** change the world’s global border.
- **Logout / world change / plugin reload**: personal claim visuals are cleared (no duplicate tasks).
- **`claims.plot-alias.enabled`**: when `false`, **`/plot`** is rejected with `claim.plot-alias-disabled` — use **`/cpplot show`** or **`/claim show`** (safe when another plugin owns `/plot`, e.g. PlotSquared).
- **COMMAND_CONFLICT**: If `/plot` is owned by another plugin, CPSMP’s executor may not run — verify **`/cpplot show`** and **`/claim show`** still work; watch console for `admin.log.command-conflict` where applicable.
- **Messages migration**: first start on an old `messages.yml` without `meta.gui-style-version: 2` copies **`messages.backup-before-v4-visual-update.yml`** and patches known GUI/title keys; console logs a German warning. **`/cpsmpadmin refreshmessages gui`** forces the same key set from the JAR default with a timestamped backup.
- **Auction GUI titles**: inventory titles use high-contrast `<bold><gold>…` strings (`auction.gui.*-title`); verify readability against the vanilla chest background.

## Inventory transfer (V3.0)

1. Fill inventory, armor, and offhand; move SMP → Danger (portal) → SMP → Attack (portal).
2. Items should persist. CPSMP does not implement per-world inventory; external plugins must use a shared group for gameplay worlds.

## Homes reload / persistence

- `/cpsmpadmin reload` or `homes reload`: TPA cleared, Homes GUI closed, `teleports.yml` re-read; DB file not duplicated.
- Restart server: homes rows persist in `teleports.db`.

## Portal test

1. Configure a portal with `pos1` / `pos2` in the **same world** and a valid `target` (for `TELEPORT`).
2. Enable via `/cpsmpadmin portal <name> enable`.
3. Walk into the cuboid: should trigger once per entry (not from adjacent blocks outside the cuboid).
4. Verify cooldown prevents tight loops when overlapping destinations.
5. Sneak as OP or with `cpsmp.portal.bypass`: should **not** teleport; actionbar hint may show.
6. Invalid `sound` / `particles` in `portals.yml` should not crash the server (effect may be silent).

## RTP test

1. Run `/rtp` in an allowed world (`rtp.allowed-worlds`).
2. Confirm safe surface: two air blocks above solid ground, no lava/water in configured unsafe set.
3. Cooldown: second `/rtp` within cooldown should be denied unless OP or `cpsmp.rtp.bypasscooldown`.
4. Set `rtp.min-radius` **greater** than `rtp.max-radius` in config: server should log a German warning and `/rtp` should still pick a valid ring (values swapped at runtime).

## Economy test

1. `/cpsmpadmin info` — economy line matches expectation (`VAULT` + provider name when installed).
2. With economy disabled in `economy.yml`, buying should fail with the configured German message.

## Auction sell test

1. `/ah sell <price>` with item in main hand (`cpsmp.ah.sell`).
2. Listing fee and max listings respect `auctionhouse.yml`.
3. Blocked materials rejected.

## Auction buy test

1. `/ah browse` or GUI browse; `/ah buy <id>` (`cpsmp.ah.buy`).
2. Two clients buying the same listing: one succeeds; other gets `auction.buy-already-sold` (or equivalent).
3. Full inventory: remainder should go to buyer collect storage, not drop on the ground.

## Auction GUI test

1. `/ah` opens main hub when `gui.enabled` is true.
2. Shift-click, number keys, drop, offhand, double-click collect-to-cursor, creative actions on **display** slots should not move GUI chrome into the player inventory.
3. Browse **Sortieren** / **Aktualisieren**: list updates without stale ordering (see V2.5 sort fix).
4. After another player buys a listing still shown on your page, buy confirm should fail safely (no duplication).
5. **Readability (post–V4.0 polish)**: hub buttons, browse listing lore (price/seller/expiry/hints), sort/refresh labels, empty state, collect tiles, and buy-confirm / cancel panes use high-contrast MiniMessage from `messages.yml` (gold/white values, gradient titles); no illegible dark-on-dark lore on defaults.

## Anvil sell test

1. Sell flow: place item, set price in anvil, confirm.
2. Close inventory mid-flow: escrow item returned or parked in collect storage; no duplicate items.
3. Repair cost should stay **0** for the virtual price anvil (no XP charge).

## Search / sort test

1. `/ah search <text>` with GUI enabled opens browse with filter; without GUI, chat results.
2. Cycle sort modes; verify order matches `auctionhouse.yml` defaults and SQL / in-memory paths.

## Collect storage test

1. `/ah collect` with full inventory: partial delivery, no row deletion until delivered amount matches.
2. GUI collect single stack mirrors the same rules.

## Reload test

1. `/cpsmpadmin reload` (or `/ah admin reload` — same full reload path as of V2.6).
2. Confirm **one** portal scan task (no duplicate teleport triggers / doubled polling).
3. Confirm **one** TPA expiry task (no duplicate expirations per second).
4. Auction expiry: still a single periodic pass (hot reload restarts the task).
5. Open Auction GUI before reload: inventory should close; reopening shows fresh config (rows, filler, thresholds).

## Permission test

1. Remove `cpsmp.ah.listings`: `/ah listings` and GUI listings button should deny with `auction.no-permission`.
2. `/ah admin` subcommands require `cpsmp.ah.admin` (included under `cpsmp.admin` for ops).
3. Tab-complete should not offer subcommands the sender cannot use.

## Cleanup test

1. `/ah admin cleanup` with retention in `auctionhouse.yml` removes **old terminal** listing rows only; never ACTIVE rows; never collect items (verify counts via `/ah admin info`).

## Build verification

From the project root:

```bash
mvn -DskipTests clean package
```

Expect **BUILD SUCCESS**; ship the jar from `target/`.
