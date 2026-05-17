# CPSMP Dependencies

This document lists every dependency, optional integration and runtime
expectation for CPSMP. For broader platform and version policy see
[`COMPATIBILITY.md`](COMPATIBILITY.md).

CPSMP is **Paper-first**. Spigot is best-effort only and must never reduce
the quality of the Paper experience.

---

## 1. Runtime Requirements

| Requirement | Version |
|---|---|
| Java | **21** |
| Paper | **1.21.4+** (primary target) |
| Purpur | Best-effort, only when based on a supported Paper version |
| Spigot | Best-effort only |
| CraftBukkit / vanilla Bukkit | Best-effort only |
| Folia | **Not supported** |

Future Minecraft updates may raise the Java baseline; in that case bump
`<java.version>` in `pom.xml` together with `<paper.api.version>`.

---

## 2. Build Dependencies

All build dependencies are declared in `pom.xml` with `<scope>provided</scope>`
so nothing is shaded into the final jar.

| Dependency | Scope | Purpose |
|---|---|---|
| `io.papermc.paper:paper-api:${paper.api.version}` | provided | Server API. The single source of truth for Bukkit + Paper APIs. |
| `com.github.MilkBowl:VaultAPI:${vault.api.version}` | provided | Economy abstraction used by `VaultEconomyBridge`. Only referenced at runtime when Vault is detected; never shipped inside our jar. |

VaultAPI is fetched through the **JitPack** Maven repository, declared
alongside the PaperMC repo in `pom.xml`.

To upgrade Paper or VaultAPI, edit the corresponding property in
`<properties>`; no other change is required:

```xml
<paper.api.version>1.21.X-R0.1-SNAPSHOT</paper.api.version>
<vault.api.version>1.7.1</vault.api.version>
```

### 2.1 Runtime-loaded libraries

The CPSMP jar does not shade or bundle any third-party libraries. One
runtime-only dependency is resolved by Paper itself through the
`libraries:` directive in `plugin.yml`:

| Library | Version | Purpose | Loader |
|---|---|---|---|
| `org.xerial:sqlite-jdbc` | `3.46.1.0` | JDBC driver for the Auction House SQLite backend (`SQLiteAuctionStorage`). | Paper library loader (Maven Central). |

CPSMP code touches only the JDK's `java.sql.*` API; the driver class
itself is found at runtime via the standard JDBC `ServiceLoader`
mechanism. On Spigot / CraftBukkit the `libraries:` directive is
ignored; if no SQLite driver is on the classpath, `AuctionHouseManager`
catches the missing-driver error during init, logs a clear German
message and disables itself without taking the rest of CPSMP down.

---

## 3. Required Runtime Plugins

| Feature scope | Required plugins |
|---|---|
| **CPSMP core** (spawn, RTP, portals, zones, admin) | **None** |
| **Auction House (V2.1)** - selling with `fees.listing-fee > 0` or `require-economy-for-auction-house: true` | A Vault-compatible economy setup (Vault + one provider) |
| **Auction House (V2.1)** - selling with no fee and `require-economy-for-auction-house: false` | None |
| **Auction House (V2.2)** - buying (`/ah buy`, including the offline-seller payout) | A Vault-compatible economy setup (Vault + one provider) - hard requirement; `/ah buy` refuses with `auction.economy-required` when no bridge is available |
| **Auction House (V2.2)** - browsing (`/ah browse`) | None |
| **Auction House (V2.3+)** - premium German GUI (`/ah` hub, browse/listings/collect, buy confirm, V2.4 sell + anvil price) | None beyond the backend tier above; listing creation still uses `AuctionHouseManager` with Paper's `Player#openAnvil` + `AnvilView` for rename-based price input |

---

## 4. Optional Runtime Plugins

CPSMP does **not** depend on any of these directly; it only consumes them
through Vault.

| Plugin | Role | Notes |
|---|---|---|
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | Service registry for economy providers | Soft-dependency. CPSMP runs without it; only economy-gated features (planned AH) need it. |
| EssentialsX (`Essentials` + `EssentialsXEconomy`) | Economy provider (via Vault) | Most common pairing. |
| CMI / CMIEconomy | Economy provider (via Vault) | Works as long as CMI registers a Vault provider. |
| XConomy | Economy provider (via Vault) | |
| GemsEconomy | Economy provider (via Vault) | |
| TheNewEconomy | Economy provider (via Vault) | Exposes a Vault hook; CPSMP also reserves a slot for a direct Reserve API bridge in the future. |
| Any other Vault-compatible economy plugin | Economy provider (via Vault) | Should work without CPSMP-side changes. |

**Reserve API** is reserved for a future best-effort bridge
(`EconomyProviderType.RESERVE`). It is **not** implemented yet to keep the
dependency surface small. Reserve users should run a TheNewEconomy
Vault hook in the meantime.

---

## 5. Soft Dependencies

Listed in `plugin.yml`:

| Plugin | Reason |
|---|---|
| `Vault` | Used by `VaultEconomyBridge` when present. CPSMP gracefully falls back to `NoEconomyBridge` when absent. |

---

## 6. Compatibility Notes

- CPSMP must **not** directly depend on EssentialsX, CMI, TheNewEconomy,
  XConomy, GemsEconomy or any other specific economy plugin. Support is
  routed through Vault.
- Economy-dependent CPSMP features (Auction House listing fees, the
  optional `require-economy-for-auction-house` gate, and the future
  buy-flow) must fail safely with a German player message
  (`economy.unavailable`, `auction.economy-required`, etc.) when no
  provider is available. The server stays up; only the gated feature
  is unavailable.
- The Auction House talks **only** to `de.deinserver.cpsmp.economy.EconomyBridge`.
  It never imports `net.milkbowl.vault.*` or any EssentialsX / CMI /
  XConomy / GemsEconomy class directly. Switching the underlying
  economy plugin requires zero changes to Auction House code.
- The server owner is responsible for installing **one** economy provider
  alongside Vault. CPSMP does not pick a provider on the admin's behalf
  and does not attempt to migrate balances between providers.
- The Vault-bound provider is resolved through
  `Bukkit.getServicesManager().getRegistration(Economy.class)`. If two
  providers register, the highest-priority one wins (standard Vault
  behavior).
- All economy operations use **player UUIDs** as the canonical identifier
  and resolve to `OfflinePlayer` only inside `VaultEconomyBridge`.
- Auction House persistence uses **SQLite** through the `java.sql` API
  with the driver supplied at runtime by Paper's `libraries:` loader
  (see section 2.1). No NMS or `org.bukkit.craftbukkit.*` code is used.
- The buy flow (`/ah buy`) deposits the seller payout via
  `EconomyBridge#deposit(UUID, ...)`. Every Vault provider CPSMP
  targets (EssentialsX, CMI, XConomy, GemsEconomy, TheNewEconomy via
  Vault) accepts deposits keyed by an `OfflinePlayer` resolved from a
  UUID, so offline sellers receive their money on the next login the
  same way `/pay <name>` would deliver it.
- The V2.3/V2.4 Auction House GUI is implemented entirely with the public
  Bukkit inventory API (`Bukkit.createInventory`, `InventoryHolder`,
  `InventoryClickEvent`, `InventoryDragEvent`, `InventoryCloseEvent`,
  `ItemMeta#displayName`, `ItemMeta#lore`). No NMS, no
  `org.bukkit.craftbukkit.*`, no internal-package reflection. The
  GUI uses Paper's `createInventory(Holder, int, Component)` overload
  for rich titles; on Spigot the manager catches the missing-overload
  error and falls back to the legacy `String` title overload after
  serializing the title to plain text. If GUI creation fails for any
  other reason, `/ah` cleanly falls back to the German text help so
  no command path ever blocks on a missing API. V2.4's sell flow
  reads the anvil rename field exclusively through
  `AnvilView.getRenameText()` (Paper/Bukkit `AnvilView` API) and never
  calls the deprecated `AnvilInventory.getRenameText()`.

---

## 7. Testing Notes

The active economy bridge is logged at startup and shown in
`/cpsmpadmin info` (line: `Economy: <bridge> | Provider: <name>`).

### Scenario A - Vault + EssentialsX Economy
1. Install `Vault.jar`, `EssentialsX.jar` and `EssentialsXEconomy.jar`.
2. Start the server, then run `/cpsmpadmin info`.
3. Expected: `Economy: VAULT | Provider: Essentials` (provider string is
   whatever EssentialsXEconomy returns from `Economy#getName()`).
4. Startup log should contain `Economy-System erkannt: Essentials`.

### Scenario B - Vault missing
1. Remove `Vault.jar` (and any economy plugin) from `plugins/`.
2. Start the server, then run `/cpsmpadmin info`.
3. Expected: `Economy: NONE | Provider: None`.
4. Startup log should contain:
   - `Kein Economy-System gefunden.`
   - `Vault ist nicht installiert.`
   - `Economy-Funktionen wurden deaktiviert.`

### Scenario C - Vault installed but no economy provider
1. Install only `Vault.jar` (no economy plugin).
2. Start the server, then run `/cpsmpadmin info`.
3. Expected: `Economy: NONE | Provider: None`.
4. Startup log should contain:
   - `Kein Economy-System gefunden.`
   - `Vault gefunden, aber kein Economy-Provider registriert.`
   - `Economy-Funktionen wurden deaktiviert.`

### Scenario D - Economy disabled in config
1. Set `economy.enabled: false` in `economy.yml`.
2. Reload (`/cpsmpadmin reload`).
3. Expected: `Economy: NONE | Provider: None`, regardless of Vault.
4. Startup log should contain `Economy-Integration ist in der
   Konfiguration deaktiviert.`

---

## 8. What CPSMP does **not** claim

- CPSMP does not claim compatibility with every economy plugin
  individually. It supports the **Vault** abstraction and any provider
  that registers itself with Vault.
- CPSMP does not bundle Vault or any economy plugin. Server owners are
  responsible for installing the providers they want.
- CPSMP does not move money between providers, migrate balances, or
  shadow-cache balances. Every read goes through the active bridge.
