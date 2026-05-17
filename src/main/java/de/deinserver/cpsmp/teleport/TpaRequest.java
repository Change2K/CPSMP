package de.deinserver.cpsmp.teleport;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record TpaRequest(
        UUID senderId,
        UUID targetId,
        TpaKind kind,
        long createdAtMillis,
        long expiresAtMillis
) {
    boolean isExpired(long now) {
        return now >= expiresAtMillis;
    }

    @Nullable
    public Player senderPlayer() {
        return Bukkit.getPlayer(senderId);
    }

    @Nullable
    public Player targetPlayer() {
        return Bukkit.getPlayer(targetId);
    }
}
