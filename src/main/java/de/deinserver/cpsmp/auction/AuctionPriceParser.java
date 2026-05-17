package de.deinserver.cpsmp.auction;

import java.util.Locale;

/**
 * Lenient price input parser for {@code /ah sell &lt;price&gt;}. Accepts both
 * the German decimal comma ({@code "1234,50"}) and the JSON-style period
 * ({@code "1234.50"}). Rejects negative numbers, NaN, infinities and
 * non-numeric input.
 *
 * <p>Returned price is always a finite, non-negative {@code double}. The
 * caller is responsible for enforcing min/max bounds from {@code
 * auctionhouse.yml}.
 */
public final class AuctionPriceParser {

    private AuctionPriceParser() {
    }

    /**
     * Tries to parse {@code raw} as a price.
     *
     * @return {@link Result#ok(double)} when parsing succeeded with a
     *         finite non-negative value, otherwise {@link Result#invalid()}.
     */
    public static Result parse(String raw) {
        return parseInternal(raw, false);
    }

    /**
     * Like {@link #parse(String)} but rejects zero as well as negatives.
     * Used by the V2.4 Anvil rename field so {@code "0"} never reaches
     * {@link AuctionHouseManager#createListing} with a pointless round-trip.
     */
    public static Result parseStrictPositive(String raw) {
        return parseInternal(raw, true);
    }

    private static Result parseInternal(String raw, boolean rejectZero) {
        if (raw == null) {
            return Result.invalid();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Result.invalid();
        }
        String normalized = normalizePriceInput(trimmed);
        if (normalized == null) {
            return Result.invalid();
        }

        double value;
        try {
            value = Double.parseDouble(normalized.toLowerCase(Locale.ROOT));
        } catch (NumberFormatException ex) {
            return Result.invalid();
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0D) {
            return Result.invalid();
        }
        if (rejectZero && value <= 0.0D) {
            return Result.invalid();
        }
        // Round to two decimal places so the persisted value matches what
        // the seller typed and what we display back. Avoids 0.30000000004
        // style drift on later formatting.
        double rounded = Math.round(value * 100.0D) / 100.0D;
        if (rejectZero && rounded <= 0.0D) {
            return Result.invalid();
        }
        return Result.ok(rounded);
    }

    /**
     * Normalizes free-form GUI / Anvil text into a string suitable for
     * {@link Double#parseDouble(String)}:
     * <ul>
     *     <li>{@code "1,000"} / {@code "12,345.67"} (US thousands) &mdash;
     *         commas stripped</li>
     *     <li>{@code "12,50"} / {@code "3,5"} (decimal comma, no dot)
     *         &mdash; single comma becomes the decimal separator</li>
     *     <li>{@code "1234.50"} &mdash; period decimal as usual</li>
     * </ul>
     */
    static String normalizePriceInput(String trimmed) {
        String work = trimmed.replace(" ", "").replace("\u202F", "");
        if (work.contains(",")) {
            int lastComma = work.lastIndexOf(',');
            String afterComma = work.substring(lastComma + 1);
            boolean noDot = !work.contains(".");
            // European-style decimal: at most two digits after the last comma,
            // no dot elsewhere (e.g. 12,50 or 3,5 but not 1,000).
            if (noDot
                    && afterComma.length() <= 2
                    && afterComma.chars().allMatch(Character::isDigit)) {
                return work.replace(',', '.');
            }
            // Otherwise treat commas as US-style thousands separators.
            return work.replace(",", "");
        }
        return work;
    }

    public record Result(boolean ok, double value) {
        public static Result ok(double value) {
            return new Result(true, value);
        }

        public static Result invalid() {
            return new Result(false, 0.0D);
        }
    }
}
