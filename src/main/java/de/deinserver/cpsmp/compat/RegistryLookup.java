package de.deinserver.cpsmp.compat;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Version-tolerant resolver for {@link Sound} and {@link Particle} values.
 *
 * <p>The Bukkit/Paper enums {@code Sound} and {@code Particle} have been
 * gradually replaced by {@link Registry}-based, key-driven lookups. The legacy
 * {@code valueOf} entry point still works on Paper 1.21.4 but may eventually
 * be removed. This helper accepts both formats so existing configs keep
 * working while future configs can use namespaced keys:
 *
 * <ul>
 *     <li>Legacy enum name: {@code ENTITY_ENDERMAN_TELEPORT} (case-insensitive)</li>
 *     <li>Namespaced key: {@code minecraft:entity.enderman.teleport}</li>
 * </ul>
 *
 * <p>Returns {@code null} when the value cannot be resolved. Callers should
 * silently fall back, never throw, so unknown sounds/particles never spam the
 * console after a Minecraft update.
 */
public final class RegistryLookup {

    private RegistryLookup() {
    }

    @Nullable
    public static Sound sound(@Nullable String input) {
        return lookup(Sound.class, input, raw -> {
            try {
                return Sound.valueOf(raw);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        });
    }

    @Nullable
    public static Particle particle(@Nullable String input) {
        return lookup(Particle.class, input, raw -> {
            try {
                return Particle.valueOf(raw);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        });
    }

    /**
     * Generic resolver. Tries the registry first when {@code input} looks like
     * a namespaced key, then falls back to {@code legacyEnumLookup}. The
     * legacy lookup is supplied by the caller because {@code Enum.valueOf} is
     * type-specific and cannot be expressed generically.
     */
    private static <T extends Keyed> T lookup(Class<T> type,
                                              @Nullable String input,
                                              LegacyResolver<T> legacyEnumLookup) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        // 1) Try as NamespacedKey via the registry. Forward-compatible path.
        if (looksLikeNamespacedKey(trimmed)) {
            NamespacedKey key = NamespacedKey.fromString(trimmed.toLowerCase(Locale.ROOT));
            if (key != null) {
                T resolved = registryGet(type, key);
                if (resolved != null) return resolved;
            }
        }

        // 2) Fall back to the legacy enum name.
        T enumResolved = legacyEnumLookup.apply(trimmed.toUpperCase(Locale.ROOT));
        if (enumResolved != null) return enumResolved;

        // 3) Last resort: assume the value is a bare minecraft:<key> identifier
        //    written in legacy snake_case (e.g. "ENTITY_ENDERMAN_TELEPORT" →
        //    "entity.enderman.teleport"). This rescues configs after a future
        //    Paper release removes the enum constants.
        NamespacedKey fallback = toMinecraftKey(trimmed);
        if (fallback != null) {
            return registryGet(type, fallback);
        }
        return null;
    }

    private static boolean looksLikeNamespacedKey(String s) {
        // Either contains a namespace separator, or already uses the
        // lowercase.dotted form used by the Mojang registry.
        return s.indexOf(':') >= 0 || (s.equals(s.toLowerCase(Locale.ROOT)) && s.indexOf('.') >= 0);
    }

    private static <T extends Keyed> T registryGet(Class<T> type, NamespacedKey key) {
        try {
            Registry<T> registry = Bukkit.getRegistry(type);
            return registry != null ? registry.get(key) : null;
        } catch (Throwable t) {
            // Registry not available on this server flavor; treat as missing.
            return null;
        }
    }

    private static NamespacedKey toMinecraftKey(String legacy) {
        try {
            String path = legacy.toLowerCase(Locale.ROOT).replace('_', '.');
            return NamespacedKey.minecraft(path);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface LegacyResolver<T> {
        T apply(String upperCaseEnumName);
    }
}
