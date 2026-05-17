package de.deinserver.cpsmp.teleport;

/**
 * Permission nodes for Homes / TPA /back (V3.0).
 */
public final class TeleportPermission {

    public static final String HOME_ROOT = "cpsmp.home";
    public static final String HOME_SET = "cpsmp.home.set";
    public static final String HOME_DELETE = "cpsmp.home.delete";
    public static final String HOME_GUI = "cpsmp.home.gui";

    public static final String HOMES_1 = "cpsmp.homes.1";
    public static final String HOMES_3 = "cpsmp.homes.3";
    public static final String HOMES_5 = "cpsmp.homes.5";
    public static final String HOMES_10 = "cpsmp.homes.10";
    public static final String HOMES_UNLIMITED = "cpsmp.homes.unlimited";

    public static final String HOME_BYPASS_COOLDOWN = "cpsmp.home.bypasscooldown";

    public static final String TPA_ROOT = "cpsmp.tpa";
    public static final String TPA_ACCEPT = "cpsmp.tpa.accept";
    public static final String TPA_DENY = "cpsmp.tpa.deny";
    public static final String TPA_HERE = "cpsmp.tpa.here";
    public static final String TPA_BYPASS_COOLDOWN = "cpsmp.tpa.bypasscooldown";

    public static final String BACK = "cpsmp.back";

    private TeleportPermission() {
    }
}
