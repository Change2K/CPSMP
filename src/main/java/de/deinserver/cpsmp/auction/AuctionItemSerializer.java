package de.deinserver.cpsmp.auction;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Base64;

/**
 * Safe round-trip serializer for {@link ItemStack} values stored in the
 * Auction House SQLite database.
 *
 * <p>Paper-first encoding:
 * <ol>
 *     <li>If Paper's {@code ItemStack#serializeAsBytes()} /
 *         {@code ItemStack#deserializeBytes(byte[])} are available, they
 *         are used. This is the modern, version-aware, NBT-stable
 *         encoding and replaces the deprecated
 *         {@link BukkitObjectOutputStream} path on Paper 1.20+.</li>
 *     <li>Otherwise (Spigot / older API), falls back to
 *         {@link BukkitObjectOutputStream}/{@link BukkitObjectInputStream}.
 *         The Bukkit serialization layer is still supported everywhere
 *         and goes through Bukkit's {@code ConfigurationSerialization}
 *         registry, so all standard item meta round-trips correctly.</li>
 * </ol>
 *
 * <p>The chosen byte array is then Base64-encoded so the payload fits
 * cleanly into a SQLite {@code TEXT} column. Corrupt or
 * version-incompatible payloads are surfaced as exceptions to the
 * caller, which can then route the affected row to a quarantine log
 * instead of crashing the query.
 */
public final class AuctionItemSerializer {

    /**
     * Reflectively-resolved Paper API. Looked up exactly once at class
     * load. {@code null} on Spigot / older Paper without this method.
     */
    private static final Method SERIALIZE_AS_BYTES;
    private static final Method DESERIALIZE_BYTES;

    static {
        Method serialise = null;
        Method deserialise = null;
        try {
            serialise = ItemStack.class.getMethod("serializeAsBytes");
            deserialise = ItemStack.class.getMethod("deserializeBytes", byte[].class);
        } catch (NoSuchMethodException ignored) {
            // Pre-Paper API; we fall back to BukkitObjectOutputStream.
        }
        SERIALIZE_AS_BYTES = serialise;
        DESERIALIZE_BYTES = deserialise;
    }

    private AuctionItemSerializer() {
    }

    /**
     * Serialises {@code item} to a Base64 string. Never returns
     * {@code null}; callers should reject air / null items before
     * persistence, but if they don't, this method still produces a
     * valid payload.
     */
    public static String serialize(ItemStack item) throws IOException {
        byte[] bytes = serialiseBytes(item);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Inverse of {@link #serialize(ItemStack)}. Throws on malformed
     * payloads so the caller can mark the row as quarantined.
     */
    public static ItemStack deserialize(String base64) throws IOException, ClassNotFoundException {
        if (base64 == null || base64.isEmpty()) {
            throw new IOException("empty payload");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid base64 payload", ex);
        }
        return deserialiseBytes(raw);
    }

    private static byte[] serialiseBytes(ItemStack item) throws IOException {
        if (SERIALIZE_AS_BYTES != null) {
            try {
                Object result = SERIALIZE_AS_BYTES.invoke(item);
                if (result instanceof byte[] bytes) {
                    return bytes;
                }
                throw new IOException("ItemStack#serializeAsBytes returned non-byte[]");
            } catch (ReflectiveOperationException ex) {
                throw new IOException("ItemStack#serializeAsBytes failed: " + ex.getMessage(), ex);
            }
        }
        return legacySerialise(item);
    }

    private static ItemStack deserialiseBytes(byte[] raw) throws IOException, ClassNotFoundException {
        if (DESERIALIZE_BYTES != null) {
            try {
                Object result = DESERIALIZE_BYTES.invoke(null, (Object) raw);
                if (result instanceof ItemStack stack) {
                    return stack;
                }
                throw new IOException("ItemStack#deserializeBytes returned " + result);
            } catch (ReflectiveOperationException ex) {
                throw new IOException("ItemStack#deserializeBytes failed: " + ex.getMessage(), ex);
            }
        }
        return legacyDeserialise(raw);
    }

    // Pre-Paper-1.20 Spigot fallback. The Bukkit object streams are marked
    // deprecated on modern Paper because Paper prefers serializeAsBytes,
    // but they remain the only portable option on older Spigot. Used only
    // when the reflective Paper API is absent (see the static initializer).
    @SuppressWarnings("deprecation")
    private static byte[] legacySerialise(ItemStack item) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(baos)) {
            out.writeObject(item);
        }
        return baos.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static ItemStack legacyDeserialise(byte[] raw) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
            Object obj = in.readObject();
            if (!(obj instanceof ItemStack stack)) {
                throw new IOException("payload does not contain an ItemStack");
            }
            return stack;
        }
    }
}
