package de.deinserver.cpsmp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * One-shot migration for high-contrast GUI MiniMessage defaults bundled in {@code messages.yml}.
 * Only overwrites known keys; leaves custom copy intact elsewhere.
 */
public final class MessagesGuiStyleMigration {

    public static final int CURRENT_GUI_STYLE_VERSION = 4;

    /**
     * Keys merged from the JAR default when {@code meta.gui-style-version} is missing or lower than
     * {@link #CURRENT_GUI_STYLE_VERSION}.
     */
    public static final List<String> GUI_VISUAL_KEYS = List.of(
            "auction.gui.main-title",
            "auction.gui.browse-title",
            "auction.gui.listings-title",
            "auction.gui.collect-title",
            "auction.gui.confirm-title",
            "auction.gui.sell-title",
            "auction.gui.sell-confirm-title",
            "claim.show-enabled",
            "claim.show-disabled",
            "claim.show-not-in-claim",
            "claim.show-usage-plot",
            "claim.show-usage-claim",
            "claim.gui-title",
            "claim.gui-empty-title",
            "claim.gui-empty-lore",
            "claim.gui-item-name",
            "claim.gui-lore-world",
            "claim.gui-lore-position",
            "claim.gui-lore-size",
            "claim.gui-lore-trusted",
            "claim.gui-lore-left",
            "claim.gui-lore-right",
            "claim.gui-lore-delete",
            "claim.gui-page-prev",
            "claim.gui-page-next",
            "claim.gui-details-title",
            "claim.gui-details-info-title",
            "claim.gui-details-owner",
            "claim.gui-details-trusted-line",
            "claim.gui-btn-show",
            "claim.gui-btn-delete",
            "claim.gui-btn-back",
            "claim.gui-delete-title",
            "claim.gui-delete-confirm",
            "claim.gui-delete-cancel",
            "claim.gui-deleted",
            "claim.gui-delete-not-owner",
            "claim.gui-border-wrong-world",
            "claim.gui-claim-not-found",
            "claim.created",
            "claim.info",
            "claim.list-entry",
            "claim.abandon-warning",
            "claim.abandoned",
            "claim.trustlist-header",
            "claim.admin-usage",
            "claim.admin-info-usage",
            "claim.admin-delete-usage",
            "claim.admin-deleteglobal-usage",
            "claim.admin-delete-bad-number",
            "claim.admin-delete-bad-id",
            "claim.admin-info-header",
            "claim.admin-info-entry",
            "claim.admin-delete-success",
            "claim.admin-delete-missing",
            "claim.admin-deleteglobal-success",
            "claim.show-worldborder-unavailable",
            "claim.command-usage",
            "claim.admin-teleport-usage",
            "claim.admin-teleport-success",
            "claim.admin-teleport-not-found",
            "claim.admin-teleport-world-missing",
            "claim.admin-teleport-unsafe",
            "claim.merge-usage",
            "claim.merge-disabled",
            "claim.merge-not-in-claim",
            "claim.merge-not-enough",
            "claim.merge-overlap",
            "claim.merge-too-large",
            "claim.merge-success",
            "claim.merge-storage-error",
            "claim.gui-merge-name",
            "claim.gui-merge-lore-1",
            "claim.gui-merge-lore-2"
    );

    private MessagesGuiStyleMigration() {
    }

    public static void migrateIfNeeded(JavaPlugin plugin, File messagesFile) {
        if (messagesFile == null || !messagesFile.isFile()) {
            return;
        }
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(messagesFile);
        int v = onDisk.getInt("meta.gui-style-version", 0);
        if (v >= CURRENT_GUI_STYLE_VERSION) {
            return;
        }
        YamlConfiguration defaults = loadDefaultMessages(plugin);
        if (defaults == null) {
            plugin.getLogger().warning("[CPSMP] messages.yml Migration: Standard-Ressource fehlt.");
            return;
        }
        try {
            File backup = new File(messagesFile.getParentFile(), "messages.backup-before-v4-visual-update.yml");
            Files.copy(messagesFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[CPSMP] Konnte messages.yml nicht sichern — Migration abgebrochen.", e);
            return;
        }
        applyKeys(onDisk, defaults, GUI_VISUAL_KEYS);
        onDisk.set("meta.gui-style-version", CURRENT_GUI_STYLE_VERSION);
        try {
            onDisk.save(messagesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[CPSMP] Konnte messages.yml nach Migration nicht speichern.", e);
            return;
        }
        plugin.getLogger().warning(
                "CPSMP hat veraltete GUI-Texte aktualisiert. Backup wurde erstellt (messages.backup-before-v4-visual-update.yml).");
    }

    /**
     * @return backup file name on success, or null on failure
     */
    public static @Nullable String refreshGuiKeysForced(CPSMPPlugin plugin, File messagesFile) {
        if (messagesFile == null || !messagesFile.isFile()) {
            return null;
        }
        YamlConfiguration defaults = loadDefaultMessages(plugin);
        if (defaults == null) {
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File backup = new File(messagesFile.getParentFile(), "messages.backup-refreshmessages-gui-" + stamp + ".yml");
        try {
            Files.copy(messagesFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[CPSMP] refreshmessages: Backup fehlgeschlagen.", e);
            return null;
        }
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(messagesFile);
        applyKeys(disk, defaults, GUI_VISUAL_KEYS);
        disk.set("meta.gui-style-version", CURRENT_GUI_STYLE_VERSION);
        try {
            disk.save(messagesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[CPSMP] refreshmessages: Speichern fehlgeschlagen.", e);
            return null;
        }
        plugin.getLogger().info("[CPSMP] refreshmessages gui: " + backup.getName());
        return backup.getName();
    }

    private static @Nullable YamlConfiguration loadDefaultMessages(JavaPlugin plugin) {
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(plugin.getResource("messages.yml"), "messages.yml"),
                StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[CPSMP] messages.yml Standard konnte nicht gelesen werden.", e);
            return null;
        }
    }

    private static void applyKeys(YamlConfiguration target, YamlConfiguration defaults, Iterable<String> keys) {
        for (String key : keys) {
            if (!defaults.contains(key)) {
                continue;
            }
            Object val = defaults.get(key);
            if (val != null) {
                target.set(key, val);
            }
        }
    }
}
