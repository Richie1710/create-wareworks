package dev.wareworks.content.controller;

import java.util.Locale;
import java.util.Optional;

/**
 * Why a retrieval request from a warehouse output was refused ({@code docs/warehouse-system.md} §3.2, §7.2). The output
 * keeps the last reason for its goggle tooltip. {@link #name()} is the stable save name.
 * <p>
 * <b>Declaration order is part of the wire format:</b> {@code TerminalResultPayload} encodes a reason by its
 * {@link #ordinal()}. New constants therefore go at the <b>end</b>, and any change here needs a bump of
 * {@code WareworksNetwork.VERSION}, so that an older client fails the connection instead of reading a shifted reason.
 * Saves are unaffected either way, because they store {@link #name()}.
 */
public enum RequestRejection {
    /** The output is not an aligned output station of an aisle with a linked controller. */
    NO_CONTROLLER,
    /** The output's filter slot is empty. */
    NO_FILTER,
    /** Nothing of the requested item is in stock that is not already promised to another request. */
    NOT_IN_STOCK,
    /** The controller has {@code maxOpenRequests} open requests. */
    QUEUE_FULL,
    /** The output already has {@code maxOpenRequestsPerOutput} open requests, one per item type it waits for. */
    OUTPUT_FULL,
    /**
     * The player who asked is too far away from the block or cannot reach it, as vanilla containers check it
     * ({@code Container#stillValidBlockEntity}). Only terminals can produce this: a redstone request has no player.
     */
    OUT_OF_REACH,
    /**
     * The requested amount was not a positive number. Only terminals can produce this: the redstone path derives its
     * amount from the filter slot, which is always at least 1.
     */
    INVALID_AMOUNT,
    /**
     * The open request of this station for this item already waits for the largest amount one request may ask for, so
     * merging the repeated request into it would exceed that cap ({@code docs/warehouse-system.md} §7.2). Both entry
     * points have such a cap: a terminal passes {@code maxTerminalRequestAmount}, a warehouse output
     * {@code maxOpenRequestsPerOutput} times its filter amount. Delivered items free room again.
     */
    REQUEST_FULL,
    /**
     * The item is only producible, and the controller already runs {@code maxProductionOrders} production orders, so
     * no further one could be started for it ({@code docs/warehouse-system.md} §3.5.3).
     * <p>
     * It is told apart from {@link #NOT_IN_STOCK} because the cure is a different one: the ingredients may all be
     * there, and what the player has to do is wait for an order to finish or give one up — not go looking for an item
     * the warehouse is not missing. This mirrors {@code NoJobReason.PRODUCTION_FULL} on the planner side.
     */
    PRODUCTION_BUSY,
    /**
     * A stock rule keeps the rest of this item back from the warehouse's <b>own automation</b> (M15, issue #3): the
     * items are in stock, but they are below the reserve a warehouse stock keeper set for them, so a
     * redstone-triggered request at a warehouse output gets nothing more of them.
     * <p>
     * It is told apart from {@link #NOT_IN_STOCK} for the same reason {@link #PRODUCTION_BUSY} is: the cure is a
     * different one. Without it a player would read "not in stock" while standing in front of 412 iron ingots — the
     * masking defect M8 fixed for storage filters. A player at a terminal never sees it, because a reserve does not
     * hold anything back from them ({@code StockAccess}).
     */
    RESERVED;

    private static final String LANG_PREFIX = "gui.goggles.request_rejection.";

    /** Relative lang key of the reason text, e.g. {@code gui.goggles.request_rejection.not_in_stock}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The reason with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<RequestRejection> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (RequestRejection rejection : values()) {
            if (rejection.name().equals(name))
                return Optional.of(rejection);
        }
        return Optional.empty();
    }
}
