package de.deinserver.cpsmp.ui;

import de.deinserver.cpsmp.MessageManager;
import de.deinserver.cpsmp.teleport.Home;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link Component}s for CPSMP inventories. All visible copy comes from
 * {@code messages.yml}; this class only wires keys and placeholders so item
 * names and lore use {@link MessageManager#component} (MiniMessage) consistently.
 */
public final class CpsmpGuiTexts {

    private CpsmpGuiTexts() {
    }

    public static Component homeItemName(MessageManager messages, String homeName) {
        return messages.component("home.gui-item-name", Map.of("home", homeName));
    }

    public static List<Component> homeItemLore(MessageManager messages, Home home, String formattedCreatedAt) {
        List<Component> lore = new ArrayList<>(5);
        lore.add(messages.component("home.gui-lore-world", Map.of("world", home.worldName())));
        lore.add(messages.component("home.gui-lore-created", Map.of("date", formattedCreatedAt)));
        lore.add(Component.empty());
        lore.add(messages.component("home.gui-lore-left"));
        lore.add(messages.component("home.gui-lore-right"));
        return lore;
    }
}
