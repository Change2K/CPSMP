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
        if (raw == null) {
            return Result.invalid();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Result.invalid();
        }
        // Accept German "1234,50" as well as English "1234.50".
        String normalized = trimmed.replace(',', '.');
        // Strip thousands separators in either notation ("1.000" / "1 000").
        // This is intentionally simple; the dot is already the decimal
        // separator after the replace above, so we only strip ASCII
        // whitespace and the narrow no-break space sometimes copied from
        // websites.
        normalized = normalized.replace(" ", "").replace("\u202F", "");

        double value;
        try {
            value = Double.parseDouble(normalized.toLowerCase(Locale.ROOT));
        } catch (NumberFormatException ex) {
            return Result.invalid();
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0D) {
            return Result.invalid();
        }
        // Round to two decimal places so the persisted value matches what
        // the seller typed and what we display back. Avoids 0.30000000004
        // style drift on later formatting.
        double rounded = Math.round(value * 100.0D) / 100.0D;
        return Result.ok(rounded);
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
