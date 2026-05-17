package de.deinserver.cpsmp.auction;

/**
 * Formats positive millisecond durations as short German strings used by
 * {@code /ah listings} (e.g. {@code "2T 5h 30m"} or {@code "47s"}). Returns
 * {@code "0s"} for non-positive inputs so the display never shows negative
 * times after a listing has expired but before the expiry task ran.
 */
public final class AuctionTimeFormatter {

    private AuctionTimeFormatter() {
    }

    public static String formatRemaining(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        long totalSeconds = millis / 1000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder(16);
        if (days > 0L) {
            sb.append(days).append('T');
        }
        if (hours > 0L) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(hours).append('h');
        }
        if (minutes > 0L) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(minutes).append('m');
        }
        // Show seconds only when the remaining time is below one minute -
        // listings live for hours, so per-second precision would be noise.
        if (sb.length() == 0) {
            sb.append(seconds).append('s');
        }
        return sb.toString();
    }
}
