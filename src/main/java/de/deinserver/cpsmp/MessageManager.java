package de.deinserver.cpsmp;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * Provides all player-facing strings. Reads {@code messages.yml} and parses
 * values through MiniMessage. The plugin never hardcodes a player-facing
 * string anywhere else.
 */
public final class MessageManager {

    private final CPSMPPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private FileConfiguration messages;
    private Component prefix = Component.empty();

    public MessageManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        this.messages = plugin.getConfigManager().getMessages();
        String prefixRaw = plugin.getConfig().getString("settings.prefix", "");
        this.prefix = prefixRaw.isEmpty() ? Component.empty() : miniMessage.deserialize(prefixRaw);
    }

    /**
     * Looks up a raw MiniMessage string by dotted path. Returns the path itself
     * as a visible warning when the key is missing, which makes accidentally
     * unconfigured strings easy to spot in-game.
     */
    public String raw(String path) {
        String value = messages != null ? messages.getString(path) : null;
        if (value == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return "<red>[missing:" + path + "]</red>";
        }
        return value;
    }

    public Component component(String path) {
        return miniMessage.deserialize(raw(path));
    }

    public Component component(String path, Map<String, String> placeholders) {
        String raw = raw(path);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return miniMessage.deserialize(raw);
    }

    public void sendPrefixed(CommandSender target, String path) {
        target.sendMessage(prefix.append(component(path)));
    }

    public void sendPrefixed(CommandSender target, String path, Map<String, String> placeholders) {
        target.sendMessage(prefix.append(component(path, placeholders)));
    }

    public void sendActionBar(Audience target, String path) {
        target.sendActionBar(component(path));
    }

    public void sendActionBar(Audience target, String path, Map<String, String> placeholders) {
        target.sendActionBar(component(path, placeholders));
    }

    /**
     * Sends an actionbar from an already-resolved raw string (useful when the
     * raw string was loaded from a portal definition, not messages.yml).
     */
    public void sendActionBarRaw(Audience target, @Nullable String rawMiniMessage) {
        if (rawMiniMessage == null || rawMiniMessage.isBlank()) {
            return;
        }
        target.sendActionBar(miniMessage.deserialize(rawMiniMessage));
    }

    public void sendTitle(Audience target, String titlePath, String subtitlePath) {
        Title title = Title.title(
                component(titlePath),
                component(subtitlePath),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1500), Duration.ofMillis(500))
        );
        target.showTitle(title);
    }

    /**
     * Sends a title from raw strings (used by portals and zones whose copy
     * lives in their own yaml files).
     */
    public void sendTitleRaw(Audience target, @Nullable String titleRaw, @Nullable String subtitleRaw) {
        Component titleComp = titleRaw == null || titleRaw.isBlank()
                ? Component.empty()
                : miniMessage.deserialize(titleRaw);
        Component subComp = subtitleRaw == null || subtitleRaw.isBlank()
                ? Component.empty()
                : miniMessage.deserialize(subtitleRaw);
        Title title = Title.title(
                titleComp,
                subComp,
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1800), Duration.ofMillis(500))
        );
        target.showTitle(title);
    }

    public Component prefix() {
        return prefix;
    }

    /**
     * Returns the shared {@link MiniMessage} instance. Used by the V2.3
     * Auction House GUI for ad-hoc parsing of free-form admin-supplied
     * strings (e.g. {@code gui.filler.name}) that aren't keyed in
     * {@code messages.yml}.
     */
    public MiniMessage miniMessage() {
        return miniMessage;
    }

    /**
     * Serializes a message to plain text with all MiniMessage tags stripped.
     * Used for console / logger output where colour codes would just be
     * noise, while still letting admins translate the strings centrally
     * in {@code messages.yml}.
     */
    public String plain(String path) {
        return PlainTextComponentSerializer.plainText().serialize(component(path));
    }

    public String plain(String path, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(path, placeholders));
    }
}
