package de.deinserver.cpsmp.auction;

/**
 * Centralised permission node strings for the Auction House. Kept in one
 * place so the command, the manager and the plugin manifest never drift
 * apart.
 *
 * <ul>
 *     <li>{@link #BASE} - gate for the {@code /ah} command itself.</li>
 *     <li>{@link #SELL} - required for {@code /ah sell &lt;price&gt;}.</li>
 *     <li>{@link #LISTINGS} - required for {@code /ah listings} and the
 *         listings screen in the GUI.</li>
 *     <li>{@link #CANCEL} - required for {@code /ah cancel &lt;id&gt;}.</li>
 *     <li>{@link #COLLECT} - required for {@code /ah collect}.</li>
 *     <li>{@link #BROWSE} - required for {@code /ah browse [page]}.</li>
 *     <li>{@link #BUY} - required for {@code /ah buy &lt;id&gt;}.</li>
 *     <li>{@link #ADMIN} - required for the {@code /ah admin} subtree and
 *         for bypassing the per-player active-listing limit. Implied by
 *         {@code cpsmp.admin}.</li>
 * </ul>
 */
public final class AuctionPermission {

    public static final String BASE = "cpsmp.ah";
    public static final String SELL = "cpsmp.ah.sell";
    public static final String LISTINGS = "cpsmp.ah.listings";
    public static final String CANCEL = "cpsmp.ah.cancel";
    public static final String COLLECT = "cpsmp.ah.collect";
    public static final String BROWSE = "cpsmp.ah.browse";
    public static final String BUY = "cpsmp.ah.buy";
    public static final String ADMIN = "cpsmp.ah.admin";

    private AuctionPermission() {
    }
}
