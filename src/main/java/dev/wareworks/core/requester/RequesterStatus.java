package dev.wareworks.core.requester;

import java.util.Locale;
import java.util.Optional;

/**
 * What a Schematicannon Requester is doing or waiting for (issue #45, ADR-080; the four about production since
 * ADR-081): one status per poll, shown by its lamp and its goggle lines.
 * <p>
 * <b>Precedence is the declaration order</b>: when several hold, the earliest wins ({@link #first}). The ones about the
 * set-up come first (no remote, no cannon, a cannon that never asks), then the link to the terminal, then what the
 * cannon does, then the order rule itself. Each constant knows its lamp ({@link #lamp()}).
 * <p>
 * A status travels to clients in the block entity's packet <b>by name</b> ({@link #serializedName()},
 * {@link #byName}), so reordering or adding constants never shifts what an older packet or save meant.
 */
public enum RequesterStatus {
    /** No linked Warehouse Remote in the slot. */
    NO_REMOTE(Lamp.DARK),
    /** No Schematicannon in front, or its chunk is not loaded. */
    NO_CANNON(Lamp.REFUSED),
    /** Another requester with a lower position already serves this cannon. */
    SECOND_REQUESTER(Lamp.REFUSED),
    /** The cannon skips missing blocks, so it never asks for anything. */
    SKIP_MISSING(Lamp.REFUSED),
    /** The cannon has a creative crate: nothing is ever missing. */
    CREATIVE(Lamp.DARK),
    /** The cannon misses an item but has no inventory attached to deliver into. */
    NO_INVENTORY(Lamp.REFUSED),
    /** The linked terminal is in another dimension and the server does not allow that. */
    LINK_OTHER_DIMENSION(Lamp.REFUSED),
    /** The linked terminal is out of the remote's range. */
    LINK_OUT_OF_RANGE(Lamp.REFUSED),
    /** The linked terminal's chunk is not loaded and ticking. */
    LINK_NOT_LOADED(Lamp.REFUSED),
    /** The linked terminal is gone (or another one stands there). */
    LINK_GONE(Lamp.REFUSED),
    /** The owner may not use a block at the terminal (spawn protection, world border, a claim that hooks it). */
    LINK_NOT_ALLOWED(Lamp.REFUSED),
    /** The terminal belongs to no warehouse whose controller is loaded. */
    NO_CONTROLLER(Lamp.REFUSED),
    /** The cannon is not printing: stopped, idle, finished, or without a loaded schematic. */
    NOT_PRINTING(Lamp.DARK),
    /** The cannon paused for something else than a missing item (gunpowder, an unloaded target, ...). */
    BLOCKED(Lamp.DARK),
    /** The cannon prints and misses nothing. */
    PRINTING(Lamp.DARK),
    /** An order for the missing item runs. */
    ORDERED(Lamp.LIT),
    /** An order for the missing item runs, and production makes part of it through a pattern (ADR-081). */
    MAKING(Lamp.LIT),
    /** The missing item lies in the terminal's buffer. */
    IN_BUFFER(Lamp.LIT),
    /** The missing item left the buffer a moment ago and is on its way to the cannon. */
    GRACE(Lamp.LIT),
    /** {@value RequesterLimits#MAX_ORDERS} orders are open: the requester waits. */
    ORDERS_FULL(Lamp.LIT),
    /** The cannon's inventories have no room for the missing item. */
    CANNON_FULL(Lamp.REFUSED),
    /** The terminal's buffer has no room for the missing item. */
    BUFFER_FULL(Lamp.REFUSED),
    /** The warehouse does not hold the missing item, and no production pattern makes it. */
    OUT_OF_STOCK(Lamp.REFUSED),
    /**
     * The warehouse holds the missing item, or could make it, but a Stock Keeper's reserve holds the item or its
     * ingredients back from automation.
     */
    RESERVED(Lamp.REFUSED),
    /** The missing item is not in stock and a pattern makes it, but the chain cannot be planned (ADR-081). */
    CANNOT_MAKE(Lamp.REFUSED),
    /** The warehouse's safety stop halted making the missing item after a machine lost a batch (ADR-081). */
    MAKING_STOPPED(Lamp.REFUSED),
    /** The terminal's order slots are taken (queue, request or buffer caps); this passes by itself. */
    TERMINAL_BUSY(Lamp.DARK),
    /**
     * The production order slots are taken, or this terminal already runs its one chain for automation (ADR-081);
     * this passes by itself.
     */
    PRODUCTION_BUSY(Lamp.DARK);

    /** What the requester's lamp shows. */
    public enum Lamp {
        /** Nothing to do, or nothing the player can help. */
        DARK,
        /** Working: an order runs or its items are on their way. */
        LIT,
        /** Something needs the player; wins over {@link #LIT}. */
        REFUSED
    }

    private final Lamp lamp;

    RequesterStatus(Lamp lamp) {
        this.lamp = lamp;
    }

    /** The lamp this status shows. */
    public Lamp lamp() {
        return lamp;
    }

    /** Whether the lamp is red: something needs the player. */
    public boolean isRefused() {
        return lamp == Lamp.REFUSED;
    }

    /** Whether the lamp is lit: the requester works on an order. */
    public boolean isLit() {
        return lamp == Lamp.LIT;
    }

    /** The name this status is saved and synced by, lower case, e.g. {@code out_of_stock}. */
    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The status of a {@link #serializedName()} (case is ignored), or empty for anything else. */
    public static Optional<RequesterStatus> byName(String name) {
        if (name == null || name.isEmpty())
            return Optional.empty();
        try {
            return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The one of {@code a} and {@code b} that wins by precedence (declaration order); a {@code null} loses. */
    public static RequesterStatus first(RequesterStatus a, RequesterStatus b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
