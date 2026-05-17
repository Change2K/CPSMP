package de.deinserver.cpsmp.teleport;

import org.jetbrains.annotations.Nullable;

/**
 * Validates home names: letters, digits, underscore, hyphen; max length.
 */
public final class HomeNameValidator {

    private HomeNameValidator() {
    }

    public static boolean isValid(@Nullable String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String n = raw.trim();
        if (n.isEmpty() || n.length() > maxLen) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= 'a' && c <= 'z') continue;
            if (c >= 'A' && c <= 'Z') continue;
            if (c >= '0' && c <= '9') continue;
            if (c == '_' || c == '-') continue;
            return false;
        }
        return true;
    }

    public static String normalize(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }
}
