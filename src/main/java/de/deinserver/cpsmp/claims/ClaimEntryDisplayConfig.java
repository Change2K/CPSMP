package de.deinserver.cpsmp.claims;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * Loaded from {@code claims.claim-entry-display} in {@code claims.yml}.
 */
public final class ClaimEntryDisplayConfig {

    private final boolean enabled;
    private final boolean showToOwner;
    private final boolean showToTrusted;
    private final boolean showToVisitors;
    private final int fadeInTicks;
    private final int stayTicks;
    private final int fadeOutTicks;
    private final boolean useTitle;
    private final boolean useActionbar;

    public ClaimEntryDisplayConfig(@Nullable ConfigurationSection root) {
        if (root == null) {
            this.enabled = true;
            this.showToOwner = true;
            this.showToTrusted = true;
            this.showToVisitors = true;
            this.fadeInTicks = 5;
            this.stayTicks = 80;
            this.fadeOutTicks = 10;
            this.useTitle = true;
            this.useActionbar = false;
            return;
        }
        this.enabled = root.getBoolean("enabled", true);
        this.showToOwner = root.getBoolean("show-to-owner", true);
        this.showToTrusted = root.getBoolean("show-to-trusted", true);
        this.showToVisitors = root.getBoolean("show-to-visitors", true);
        this.fadeInTicks = Math.max(0, root.getInt("fade-in-ticks", 5));
        this.stayTicks = Math.max(1, root.getInt("stay-ticks", 80));
        this.fadeOutTicks = Math.max(0, root.getInt("fade-out-ticks", 10));
        this.useTitle = root.getBoolean("use-title", true);
        this.useActionbar = root.getBoolean("use-actionbar", false);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean showToOwner() {
        return showToOwner;
    }

    public boolean showToTrusted() {
        return showToTrusted;
    }

    public boolean showToVisitors() {
        return showToVisitors;
    }

    public int fadeInTicks() {
        return fadeInTicks;
    }

    public int stayTicks() {
        return stayTicks;
    }

    public int fadeOutTicks() {
        return fadeOutTicks;
    }

    public boolean useTitle() {
        return useTitle;
    }

    public boolean useActionbar() {
        return useActionbar;
    }
}
