package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.ZoneManager;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Typed view of {@code teleports.yml}.
 */
public final class TeleportConfig {

    private final String storageFile;

    private final int combatTagSeconds;
    private final boolean combatBlocksHomes;
    private final boolean combatBlocksTpa;

    private final boolean homesEnabled;
    private final boolean homesGuiEnabled;
    private final int homesDefaultLimit;
    private final int homeNameMaxLength;
    private final int sethomeCooldownSeconds;
    private final int homeTeleportDelaySeconds;
    private final int homeTeleportCooldownSeconds;
    private final List<String> homesAllowedWorlds;
    private final List<String> homesBlockedWorlds;
    private final boolean homesIntegrateZoneWorlds;

    private final boolean tpaEnabled;
    private final boolean tpaHereEnabled;
    private final int tpaExpireSeconds;
    private final int tpaTeleportDelaySeconds;
    private final int tpaCooldownSeconds;
    private final List<String> tpaAllowedWorlds;
    private final List<String> tpaBlockedWorlds;
    private final boolean tpaIntegrateZoneWorlds;

    private final boolean backEnabled;
    private final int backCooldownSeconds;

    private final boolean worldInventoryWarnExternal;

    public TeleportConfig(CPSMPPlugin plugin) {
        FileConfiguration cfg = plugin.getConfigManager().getTeleports();
        if (cfg == null) {
            this.storageFile = "teleports.db";
            this.combatTagSeconds = 15;
            this.combatBlocksHomes = true;
            this.combatBlocksTpa = true;
            this.homesEnabled = false;
            this.homesGuiEnabled = false;
            this.homesDefaultLimit = 3;
            this.homeNameMaxLength = 16;
            this.sethomeCooldownSeconds = 0;
            this.homeTeleportDelaySeconds = 3;
            this.homeTeleportCooldownSeconds = 10;
            this.homesAllowedWorlds = List.of();
            this.homesBlockedWorlds = List.of();
            this.homesIntegrateZoneWorlds = true;
            this.tpaEnabled = false;
            this.tpaHereEnabled = false;
            this.tpaExpireSeconds = 60;
            this.tpaTeleportDelaySeconds = 3;
            this.tpaCooldownSeconds = 15;
            this.tpaAllowedWorlds = List.of();
            this.tpaBlockedWorlds = List.of();
            this.tpaIntegrateZoneWorlds = true;
            this.backEnabled = false;
            this.backCooldownSeconds = 30;
            this.worldInventoryWarnExternal = true;
            return;
        }
        this.storageFile = cfg.getString("storage.file", "teleports.db");
        this.combatTagSeconds = Math.max(1, cfg.getInt("combat.tag-seconds", 15));
        this.combatBlocksHomes = cfg.getBoolean("combat.block-homes", true);
        this.combatBlocksTpa = cfg.getBoolean("combat.block-tpa", true);

        this.homesEnabled = cfg.getBoolean("homes.enabled", true);
        this.homesGuiEnabled = cfg.getBoolean("homes.gui-enabled", true);
        this.homesDefaultLimit = Math.max(0, cfg.getInt("homes.default-limit", 3));
        this.homeNameMaxLength = Math.max(1, Math.min(cfg.getInt("homes.name-max-length", 16), 48));
        this.sethomeCooldownSeconds = Math.max(0, cfg.getInt("homes.sethome-cooldown-seconds", 0));
        this.homeTeleportDelaySeconds = Math.max(0, cfg.getInt("homes.teleport-delay-seconds", 3));
        this.homeTeleportCooldownSeconds = Math.max(0, cfg.getInt("homes.cooldown-seconds", 10));
        this.homesAllowedWorlds = lowerList(cfg.getStringList("homes.allowed-worlds"));
        this.homesBlockedWorlds = lowerList(cfg.getStringList("homes.blocked-worlds"));
        this.homesIntegrateZoneWorlds = cfg.getBoolean("homes.integrate-cpsmp-zone-worlds", true);

        this.tpaEnabled = cfg.getBoolean("tpa.enabled", true);
        this.tpaHereEnabled = cfg.getBoolean("tpa.tpahere-enabled", true);
        this.tpaExpireSeconds = Math.max(5, cfg.getInt("tpa.request-expire-seconds", 60));
        this.tpaTeleportDelaySeconds = Math.max(0, cfg.getInt("tpa.teleport-delay-seconds", 3));
        this.tpaCooldownSeconds = Math.max(0, cfg.getInt("tpa.cooldown-seconds", 15));
        this.tpaAllowedWorlds = lowerList(cfg.getStringList("tpa.allowed-worlds"));
        this.tpaBlockedWorlds = lowerList(cfg.getStringList("tpa.blocked-worlds"));
        this.tpaIntegrateZoneWorlds = cfg.getBoolean("tpa.integrate-cpsmp-zone-worlds", true);

        this.backEnabled = cfg.getBoolean("back.enabled", false);
        this.backCooldownSeconds = Math.max(0, cfg.getInt("back.cooldown-seconds", 30));

        FileConfiguration zonesRoot = plugin.getConfigManager().getZones();
        boolean warnFromZones = true;
        if (zonesRoot != null) {
            warnFromZones = zonesRoot.getBoolean("world-inventory.warn-if-external-inventory-plugin-detected", true);
        }
        this.worldInventoryWarnExternal = warnFromZones;
    }

    private static List<String> lowerList(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }

    public Set<String> zoneWorldNames(CPSMPPlugin plugin) {
        Set<String> s = new HashSet<>();
        ZoneManager zm = plugin.getZoneManager();
        if (zm == null) return s;
        String dw = zm.get(ZoneManager.ZoneKind.DANGER).worldName;
        String aw = zm.get(ZoneManager.ZoneKind.ATTACK).worldName;
        if (dw != null && !dw.isBlank()) {
            s.add(dw.toLowerCase(Locale.ROOT));
        }
        if (aw != null && !aw.isBlank()) {
            s.add(aw.toLowerCase(Locale.ROOT));
        }
        return s;
    }

    public boolean canHomesInWorld(String worldName, CPSMPPlugin plugin) {
        String w = worldName.toLowerCase(Locale.ROOT);
        Set<String> z = zoneWorldNames(plugin);
        if (homesBlockedWorlds.contains(w)) return false;
        if (homesIntegrateZoneWorlds && z.contains(w)) return false;
        if (!homesAllowedWorlds.isEmpty() && !homesAllowedWorlds.contains(w)) return false;
        return true;
    }

    public boolean canSetHomeInWorld(String worldName, CPSMPPlugin plugin) {
        return canHomesInWorld(worldName, plugin);
    }

    public boolean canTpaFromWorld(String worldName, CPSMPPlugin plugin) {
        String w = worldName.toLowerCase(Locale.ROOT);
        Set<String> z = zoneWorldNames(plugin);
        if (tpaBlockedWorlds.contains(w)) return false;
        if (tpaIntegrateZoneWorlds && z.contains(w)) return false;
        if (!tpaAllowedWorlds.isEmpty() && !tpaAllowedWorlds.contains(w)) return false;
        return true;
    }

    public boolean canTpaToWorld(String worldName, CPSMPPlugin plugin) {
        return canTpaFromWorld(worldName, plugin);
    }

    public String getStorageFile() {
        return storageFile;
    }

    public int getCombatTagSeconds() {
        return combatTagSeconds;
    }

    public boolean isCombatBlocksHomes() {
        return combatBlocksHomes;
    }

    public boolean isCombatBlocksTpa() {
        return combatBlocksTpa;
    }

    public boolean isHomesEnabled() {
        return homesEnabled;
    }

    public boolean isHomesGuiEnabled() {
        return homesGuiEnabled;
    }

    public int getHomesDefaultLimit() {
        return homesDefaultLimit;
    }

    public int getHomeNameMaxLength() {
        return homeNameMaxLength;
    }

    public int getSethomeCooldownSeconds() {
        return sethomeCooldownSeconds;
    }

    public int getHomeTeleportDelaySeconds() {
        return homeTeleportDelaySeconds;
    }

    public int getHomeTeleportCooldownSeconds() {
        return homeTeleportCooldownSeconds;
    }

    public boolean isTpaEnabled() {
        return tpaEnabled;
    }

    public boolean isTpaHereEnabled() {
        return tpaHereEnabled;
    }

    public int getTpaExpireSeconds() {
        return tpaExpireSeconds;
    }

    public int getTpaTeleportDelaySeconds() {
        return tpaTeleportDelaySeconds;
    }

    public int getTpaCooldownSeconds() {
        return tpaCooldownSeconds;
    }

    public boolean isBackEnabled() {
        return backEnabled;
    }

    public int getBackCooldownSeconds() {
        return backCooldownSeconds;
    }

    public boolean isWorldInventoryWarnExternal() {
        return worldInventoryWarnExternal;
    }
}
