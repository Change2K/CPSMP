package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory TPA state. One pending incoming per target; one outgoing per sender.
 */
public final class TpaManager {

    private final CPSMPPlugin plugin;
    private final Map<UUID, TpaRequest> incomingByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, TpaRequest> outgoingBySender = new ConcurrentHashMap<>();
    private @Nullable BukkitTask expireTask;

    public TpaManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void startExpiryTicker() {
        stopExpiryTicker();
        this.expireTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickExpire, 20L, 20L);
    }

    public void stopExpiryTicker() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
    }

    public void clearAll() {
        incomingByTarget.clear();
        outgoingBySender.clear();
    }

    private void tickExpire() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, TpaRequest> e : incomingByTarget.entrySet()) {
            if (e.getValue().isExpired(now)) {
                UUID target = e.getKey();
                TpaRequest r = incomingByTarget.remove(target);
                if (r != null) {
                    outgoingBySender.remove(r.senderId());
                    notifyExpired(r);
                }
            }
        }
    }

    private void notifyExpired(TpaRequest r) {
        Player sender = r.senderPlayer();
        Player target = r.targetPlayer();
        if (sender != null && sender.isOnline()) {
            plugin.getMessageManager().sendPrefixed(sender, "tpa.expired");
        }
        if (target != null && target.isOnline()) {
            plugin.getMessageManager().sendPrefixed(target, "tpa.expired");
        }
    }

    public enum SendResult {
        OK,
        SELF,
        TARGET_OFFLINE,
        TARGET_BUSY
    }

    public SendResult trySend(Player sender, Player target, TpaKind kind, TeleportConfig cfg) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            return SendResult.SELF;
        }
        if (!target.isOnline()) {
            return SendResult.TARGET_OFFLINE;
        }
        if (incomingByTarget.containsKey(target.getUniqueId())) {
            return SendResult.TARGET_BUSY;
        }
        TpaRequest prev = outgoingBySender.get(sender.getUniqueId());
        if (prev != null) {
            incomingByTarget.remove(prev.targetId());
            notifyCancelled(prev);
        }
        long now = System.currentTimeMillis();
        long exp = now + cfg.getTpaExpireSeconds() * 1000L;
        TpaRequest req = new TpaRequest(sender.getUniqueId(), target.getUniqueId(), kind, now, exp);
        incomingByTarget.put(target.getUniqueId(), req);
        outgoingBySender.put(sender.getUniqueId(), req);
        return SendResult.OK;
    }

    private void notifyCancelled(TpaRequest prev) {
        Player s = prev.senderPlayer();
        Player t = prev.targetPlayer();
        if (s != null && s.isOnline()) {
            plugin.getMessageManager().sendPrefixed(s, "tpa.cancelled");
        }
        if (t != null && t.isOnline()) {
            plugin.getMessageManager().sendPrefixed(t, "tpa.cancelled");
        }
    }

    @Nullable
    public TpaRequest removeIncomingFor(UUID targetId) {
        TpaRequest r = incomingByTarget.remove(targetId);
        if (r != null) {
            outgoingBySender.remove(r.senderId());
        }
        return r;
    }

    @Nullable
    public TpaRequest peekIncoming(UUID targetId) {
        return incomingByTarget.get(targetId);
    }

    public void deny(UUID targetId) {
        TpaRequest r = removeIncomingFor(targetId);
        if (r == null) return;
        Player s = r.senderPlayer();
        if (s != null && s.isOnline()) {
            plugin.getMessageManager().sendPrefixed(s, "tpa.denied-by-target");
        }
        Player t = r.targetPlayer();
        if (t != null && t.isOnline()) {
            plugin.getMessageManager().sendPrefixed(t, "tpa.denied");
        }
    }

    /**
     * @param accepter the player who runs /tpaccept (always the target of TPA / HERE)
     */
    public void accept(Player accepter, TeleportConfig cfg) {
        TpaRequest req = peekIncoming(accepter.getUniqueId());
        if (req == null) {
            plugin.getMessageManager().sendPrefixed(accepter, "tpa.no-request");
            return;
        }
        long now = System.currentTimeMillis();
        if (req.isExpired(now)) {
            removeIncomingFor(accepter.getUniqueId());
            plugin.getMessageManager().sendPrefixed(accepter, "tpa.expired");
            return;
        }
        Player sender = req.senderPlayer();
        if (sender == null || !sender.isOnline()) {
            removeIncomingFor(accepter.getUniqueId());
            plugin.getMessageManager().sendPrefixed(accepter, "tpa.player-not-found");
            return;
        }

        Location dest;
        Player mover;
        if (req.kind() == TpaKind.TPA) {
            mover = sender;
            dest = accepter.getLocation().clone();
        } else {
            mover = accepter;
            dest = sender.getLocation().clone();
        }
        if (dest.getWorld() == null) {
            removeIncomingFor(accepter.getUniqueId());
            plugin.getMessageManager().sendPrefixed(accepter, "tpa.target-disabled-world");
            return;
        }
        CpsmpTeleportSubsystem sub = plugin.getTeleportSubsystem();
        if (sub == null) {
            removeIncomingFor(accepter.getUniqueId());
            plugin.getMessageManager().sendPrefixed(accepter, "tpa.subsystem-unavailable");
            return;
        }
        if (!sub.getTeleportConfig().canTpaToWorld(dest.getWorld().getName(), plugin)) {
            removeIncomingFor(accepter.getUniqueId());
            plugin.getMessageManager().sendPrefixed(mover, "tpa.target-disabled-world");
            plugin.getMessageManager().sendPrefixed(accepter, "tpa.target-disabled-world");
            return;
        }
        if (!sub.getTeleportConfig().canTpaFromWorld(mover.getWorld().getName(), plugin)) {
            removeIncomingFor(accepter.getUniqueId());
            plugin.getMessageManager().sendPrefixed(mover, "tpa.disabled-world");
            return;
        }
        if (sub.isCombatBlockedForTpa(mover)) {
            removeIncomingFor(accepter.getUniqueId());
            plugin.getMessageManager().sendPrefixed(mover, "tpa.teleport-combat");
            plugin.getMessageManager().sendPrefixed(accepter, "tpa.teleport-combat");
            return;
        }

        removeIncomingFor(accepter.getUniqueId());

        plugin.getMessageManager().sendPrefixed(accepter, "tpa.accepted");
        plugin.getMessageManager().sendPrefixed(sender, "tpa.accepted-sender");

        int delay = cfg.getTpaTeleportDelaySeconds();
        sub.recordBackIfEnabled(mover, mover.getLocation().clone());
        sub.beginTrackedTeleport(mover, CpsmpTeleportSubsystem.CpsmpTeleportKind.TPA);
        plugin.getTeleportService().requestTeleport(mover, dest, delay,
                sub::endTrackedTeleport,
                sub::endTrackedTeleport);
    }

    public void cancelForQuit(UUID playerId) {
        TpaRequest out = outgoingBySender.remove(playerId);
        if (out != null) {
            incomingByTarget.remove(out.targetId());
        }
        TpaRequest in = incomingByTarget.remove(playerId);
        if (in != null) {
            outgoingBySender.remove(in.senderId());
            Player s = in.senderPlayer();
            if (s != null && s.isOnline()) {
                plugin.getMessageManager().sendPrefixed(s, "tpa.cancelled");
            }
        }
    }
}
