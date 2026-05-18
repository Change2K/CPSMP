package de.deinserver.cpsmp.claims;

import java.util.Locale;

/**
 * How claim border previews are rendered for a player.
 */
public enum ClaimVisualMode {
    DISPLAY,
    PARTICLES,
    WORLDBORDER_IF_SAFE;

    public static ClaimVisualMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return DISPLAY;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("worldborder_if_safe".equals(s)) {
            return WORLDBORDER_IF_SAFE;
        }
        if ("particles".equals(s)) {
            return PARTICLES;
        }
        if ("display".equals(s)) {
            return DISPLAY;
        }
        return DISPLAY;
    }
}
