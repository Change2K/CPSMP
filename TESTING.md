# CPSMP manual test checklist (V2.6)

Use this list before promoting a build to production. Player-facing strings are German (`messages.yml`); this document is English for operators.

## Local server setup

1. Java **21**, **Paper 1.21.4** (or your supported target from `COMPATIBILITY.md`).
2. Drop `CPSMP-*.jar` in `plugins/`.
3. Start once to generate default configs.
4. Configure `config.yml` (spawn, RTP, portals, zones), `economy.yml`, `auctionhouse.yml`, `portals.yml`, `zones.yml` as needed.

## Required plugins (by scenario)

| Scenario | Plugins |
|----------|---------|
| Core only (spawn, RTP, portals, zones) | None |
| Auction sell with fees / economy gate | Vault + a Vault economy (e.g. EssentialsX + EssentialsX Economy) |
| Auction buy | Vault + economy (hard requirement) |

## Basic CPSMP commands

- `/smpspawn` — teleport to configured SMP spawn (`cpsmp.spawn`).
- `/rtp` — random teleport (`cpsmp.rtp`).
- `/cpsmpadmin` — admin tools (`cpsmp.admin`; `reload` also needs `cpsmp.reload`).
- `/ah` — Auction House hub / GUI (`cpsmp.ah` + sub-permissions).

Confirm `/spawn` is **not** registered by CPSMP (by design).

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
3. Auction expiry: still a single periodic pass (hot reload restarts the task).
4. Open Auction GUI before reload: inventory should close; reopening shows fresh config (rows, filler, thresholds).

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
