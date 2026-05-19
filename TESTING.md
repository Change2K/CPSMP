# CPSMP manual test checklist (V3.0+ / V4.0 / V4.0.2)

Use this list before promoting a build to production. Player-facing strings are German (`messages.yml`); this document is English for operators.

V3.0 adds **Homes**, **TPA**, optional **`/back`**, and `teleports.yml` (plus `teleports.db` when Homes or `/back` are enabled). V3.1 hardens those paths. **V4.0** adds **Claims** / base protection (`claims.yml`, `claims.db` SQLite). **V4.0.2** polishes display claim outlines, admin claim TP, and merge. **V4.0.3** adds **anti-encasement** (direct edge + access integrity), **`/claimexit`**, and default **24×24** claims. `/spawn` remains **unregistered** by CPSMP.

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
- **V4.0+**: `/claim`, `/claiminfo`, `/claims`, `/plots`, `/trust`, `/untrust`, `/trustlist`, `/abandonclaim`, `/claimadmin`, **`/merge all`**, **`/cpmerge all`**, **`/claim merge all`**, **`/plot`**, **`/cpplot`** (+ `cp*` aliases). Permissions: `cpsmp.claim`, `cpsmp.claim.show`, `cpsmp.claim.merge`, `cpsmp.claim.teleport.admin`, `cpsmp.claims.*`, `cpsmp.claim.bypass`, `cpsmp.claim.admin`.

Confirm `/spawn` is **not** registered by CPSMP (by design).

## Homes / TPA (V3.0)

- **`/sethome` / `/cpsethome`**: set a named home in an allowed world; invalid names rejected; blocked worlds reject with `home.set-disabled-world`.
- **`/home` / `/cphome`**: delayed teleport; cancel on move/damage/combat when configured; unloaded home world → `general.world-missing`.
- **`/homes` / `/cphomes`**: GUI when `homes.gui-enabled` and `cpsmp.home.gui`; otherwise chat list (`home.list-*`).
- **`/delhome` / `/cpdelhome`**: delete a home.
- **Limits**: bundled default **`homes.default-limit: 3`**; `cpsmp.homes.*` (incl. `cpsmp.homes.3`) and OP / `cpsmp.homes.unlimited` — highest permission wins with the configured default floor.
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
- **`/claim` / `/cpclaim`**: creates a centered claim; default **24×24** (`claims.creation.default-size-x/z: 24`). Even sizes: standing block is inside; one axis uses `center - half + 1` .. `center + half` (exactly 24 blocks, not 25). Legacy `default-radius-x/z` still works if size keys are absent (console migration hint). Overlap → `claim.overlap`; wrong world → `claim.world-disabled`; at limit → `claim.limit-reached`. Default bundle: **4** claims (`claims.limits.default-claim-limit`). Existing claims are **not** resized on upgrade.
- **Admin teleport**: `/claim <Spieler> <Claim>` (visible number) with `cpsmp.claim.admin` **or** `cpsmp.claim.teleport.admin` — safe center teleport via chunk load + surface scan; `claim.admin-teleport-*` messages. Non-admins get `general.no-permission`.
- **Merge**: `/merge all` or `/claim merge all` (permission `cpsmp.claim.merge`): stand inside **your** claim; merges the **connected component** of your claims in that world (edge/overlap; diagonal only if `claims.merge.allow-diagonal-touch`). Fails on foreign overlap, oversize vs `max-size-*`, or single-claim cluster (`claim.merge-not-enough`). Trust is unioned; lowest visible claim number kept; SQLite transaction rolls back on error.
- **`/claims` GUI**: slot **49** — **Claims zusammenfuehren** runs the same merge logic; refreshes the list on success.
- **`/claiminfo`**: in-claim details show **per-player claim number** (`#1`, `#2`, …) + optional particle outline; standing outside → `claim.not-in-claim`.
- **`/claims` / `/cpclaims` / `/plots` / `/cpplots`**: GUI shows **Claim #1, #2, …** per owner (not global DB IDs); empty state when none; left-click toggles per-claim **border visualization** in-world (same world; default **`claims.visuals.mode: display`** uses **BlockDisplay**; **`particles`** uses periodic particles), right-click details, shift-right opens delete confirmation; delete re-checks ownership.
- **Trust**: stand in **own** claim; `/trust <online player>` / `/untrust`; `/trustlist` (requires same permission node as trust).
- **`/abandonclaim`**: two-step within 10s; moving to another claim resets confirmation.
- **Protection** (non-trusted visitors): break/place, configured containers/doors/redstone, living entities, hanging entities, vehicles (when enabled), explosions (blocks removed from explosion list inside claims), fire spread / burn across claim edge, fluid flow into claim from outside, bucket use.
- **Bypass**: `cpsmp.claim.bypass` or OP — subtle actionbar `claim.bypass-actionbar` (throttled).
- **Admin**: `/claimadmin info <player>`, `/claimadmin delete <player> <Claim>` (visible per-player claim number), `/claimadmin deleteglobal <internalId>` (emergency), `/claimadmin reload` (`cpsmp.claim.admin`). `/cpsmpadmin info` includes a Claims line.
- **Reload**: `/cpsmpadmin reload` reloads `claims.yml` and refills the claim cache. `/claimadmin reload` reloads only `claims.yml` + claim cache. Turning claims **off** in `claims.yml` unregisters protection listeners (SQLite may stay open). Turning claims **on** again via reload opens SQLite and registers listeners without a full server restart when JDBC is available.
- **Persistence**: `claims.db` survives restarts; verify after reboot that `/claims` still lists rows.
- **`/plot show`** / **`/cpplot show`** / **`/claim show`** (and `show toggle`, treated the same): **toggle** a **visual-only** outline. Default **`claims.visuals.mode: display`**: **BlockDisplay** markers on the rectangle (`claims.visuals.display.*`: materials, `y-offsets` from feet block Y, `line-step-blocks`, `scale`, entity cap). No real blocks, no global world border, no movement block. Outline is **hidden from other players** (`hideEntity`); joining players re-hide all outline entities. While **standing in a claim**, first use **locks** that claim until toggle off, **world change**, **logout**, **reload**, or **2D distance** beyond `claims.visuals.show-radius-blocks` outside the rectangle (`claim.show-too-far`, `claim.show-disabled`). **`/plot show` / `/claim show`** also turns off a **GUI-pinned** outline. Second toggle **hides immediately**. Walk through the border — movement must stay free. Set client **particles to minimal** — border should **stay visible** in **display** mode. While **not** in a claim (and no GUI outline to turn off), first use → `claim.show-not-in-claim`. With **`claims.visuals.enabled: false`**, clears entities/tasks and sends `claim.show-disabled`. Requires `cpsmp.claim.info` **or** `cpsmp.claim.show`.
- **`claims.visuals.particles`**: primary mode when `mode: particles`, or **fallback** when display spawn fails (`enabled-as-fallback`; optional `claim.show-display-unavailable`). Prefer `static: true`, `count=1`, zero offset/speed. **`allow-worldborder-mode`** + `mode: worldborder_if_safe` is **opt-in** (can affect movement; not recommended).
- **Logout / world change / plugin reload**: personal claim visuals and BlockDisplays are removed (no leaked entities / duplicate tasks).
- **`claims.plot-alias.enabled`**: when `false`, **`/plot`** is rejected with `claim.plot-alias-disabled` — use **`/cpplot show`** or **`/claim show`** (safe when another plugin owns `/plot`, e.g. PlotSquared).
- **COMMAND_CONFLICT**: If `/plot` is owned by another plugin, CPSMP’s executor may not run — verify **`/cpplot show`** and **`/claim show`** still work; watch console for `admin.log.command-conflict` where applicable.
- **Messages migration**: first start on an old `messages.yml` without `meta.gui-style-version: 4` copies **`messages.backup-before-v4-visual-update.yml`** (same filename as prior migrations) and patches known GUI/title keys; console logs a German warning. **`/cpsmpadmin refreshmessages gui`** forces the same key set from the JAR default with a timestamped backup.
- **Per-player claim numbers**: after **`claims.backup-before-owner-claim-number-migration.db`**, existing rows get `owner_claim_number` (1..n per owner). `/claims` GUI and chat list use **#1, #2**, not global SQLite IDs.
- **Migration test**: upgrade from a pre–V4.0.1 `claims.db` without `owner_claim_number`; verify backup file exists, numbers stable across restart, `/claimadmin delete <player> <n>` targets the correct polygon.
- **Claim entry display (V4.0.5)**: walk into a claim → title **Privates Gebiet** + subtitle **Claim von {owner}** (~4s, `fade-in` 5 / `stay` 80 / `fade-out` 10 ticks). Walk inside same claim → **no repeat**. Leave and re-enter → shows again. Walk claim A → claim B → new owner subtitle. `claim-entry-display.show-to-owner/trusted/visitors` toggles audience. Reload → no duplicate listeners/titles.
- **Claim flags (V4.1)**:
  - `/claimflags`, `/cpclaimflags`, `/claim flags`, `/plot flags` open the flags GUI for the claim underfoot (or `/claimflags <Claim-Nr.>` for an owned claim).
  - Claims GUI → Details → **Flags** button (slot 24): toggle flags; locked flags show red lore; storage errors do not update cache.
  - **PvP** `false`: cancel player-vs-player damage inside the claim (bypass still works).
  - **Mob damage** `false`: visitors cannot hurt mobs/entities; owner/trusted/bypass can.
  - **Mob spawning** `false`: cancel natural/patrol/raid spawns inside the claim.
  - **Container/door/redstone** `false` with global protection on: visitors blocked; `true` allows visitors when global protection is on.
  - **Entry display** `false`: no enter title for that claim.
  - **Border display** `false`: visitors cannot `/plot show` or GUI border; owner/admin still can.
  - `/claimadmin flags <Spieler> <Claim-Nr.>` (admin).
  - Toggle a flag → `/cpsmpadmin reload` or restart → value persists. Delete claim → flags removed (FK cascade).
- **Anti-encasement (V4.0.3)**: untrusted player cannot place blocks/liquids within **`direct-edge.radius-blocks`** outside a foreign claim (`claim.anti-edge-*`). Larger rings blocked when **access-integrity** would drop open exits below `required-open-exits` (`claim.access-*`). **Owner** building castles/walls **inside** own claim: allowed. **Trusted** inside trusted claim: allowed. **Bypass** (`cpsmp.claim.bypass` / OP): allowed. Obsidian/lava/piston encase tests; normal nearby builds that leave ≥2 exit sectors: allowed. Performance: bounded BFS (`max-nodes-per-check`, `max-claims-checked-per-event`).
- **`/claimexit` / `/cpclaimexit`** (`cpsmp.claim.exit`): inside own claim (or trusted if enabled) → safe teleport outside after delay; messages `claim.exit-*`; GUI **Claim verlassen** in details (slot 20).
- **V4.0.2 spot checks**: display border + merge/delete/reload cleanup; admin TP; 4 claims / 3 homes defaults.
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
