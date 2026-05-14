package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Enforces the per-zone rule set (pvp / keep-inventory / build / item-drop)
 * and shows the entry title when a player enters one of the zone worlds. A
 * short cooldown prevents the entry feedback from looping when a player
 * world-hops repeatedly in a short window.
 */
public final class ZoneListener implements Listener {

    private static final String ENTRY_COOLDOWN_KEY = "zone_entry";

    private final CPSMPPlugin plugin;

    public ZoneListener(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
    }

    private ZoneManager.ZoneKind zoneOf(Player player) {
        return plugin.getZoneManager().zoneOfWorld(player.getWorld().getName());
    }

    // --- Entry feedback --------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ZoneManager.ZoneKind kind = zoneOf(event.getPlayer());
        if (kind != null) {
            showEntry(event.getPlayer(), kind);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        ZoneManager.ZoneKind kind = zoneOf(event.getPlayer());
        if (kind != null) {
            showEntry(event.getPlayer(), kind);
        }
    }

    private void showEntry(Player player, ZoneManager.ZoneKind kind) {
        long cooldownMs = plugin.getConfig().getLong("zones.enter-message-cooldown-ms", 8000L);
        if (plugin.getCooldowns().isOnCooldown(ENTRY_COOLDOWN_KEY, player.getUniqueId())) {
            return;
        }
        plugin.getCooldowns().set(ENTRY_COOLDOWN_KEY, player.getUniqueId(), cooldownMs);
        plugin.getMessageManager().sendTitle(player,
                "zones." + kind.key + ".enter-title",
                "zones." + kind.key + ".enter-subtitle");
        plugin.getMessageManager().sendActionBar(player,
                "zones." + kind.key + ".enter-actionbar");
    }

    // --- Rule enforcement ------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        ZoneManager.ZoneKind kind = zoneOf(victim);
        if (kind == null) {
            return;
        }
        if (!plugin.getZoneManager().get(kind).pvp) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ZoneManager.ZoneKind kind = zoneOf(event.getPlayer());
        if (kind == null) return;
        if (!plugin.getZoneManager().get(kind).allowBuild && !event.getPlayer().hasPermission("cpsmp.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        ZoneManager.ZoneKind kind = zoneOf(event.getPlayer());
        if (kind == null) return;
        if (!plugin.getZoneManager().get(kind).allowBuild && !event.getPlayer().hasPermission("cpsmp.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        ZoneManager.ZoneKind kind = zoneOf(event.getPlayer());
        if (kind == null) return;
        if (!plugin.getZoneManager().get(kind).allowItemDrop) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        ZoneManager.ZoneKind kind = zoneOf(event.getEntity());
        if (kind == null) return;
        if (plugin.getZoneManager().get(kind).keepInventory) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }
}
