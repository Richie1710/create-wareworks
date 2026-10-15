package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.terminal.ListOrderState;
import net.minecraft.nbt.CompoundTag;

/**
 * How far a warehouse terminal's craft order has got, in the bounded form the screens and the goggles are given
 * (issue #24, slice 2, {@code docs/warehouse-system.md} §3.4.5, ADR-062).
 * <p>
 * <b>The grid lock travels with it.</b> {@link #cells()} is the recipe the fetch was started for, and every screen open
 * at the terminal shows exactly that recipe while the order is open ({@link #locksGrid()}), so two players can never
 * fetch for one grid and craft another. The grid itself is the player's own and lives on their client; only a running
 * order's cells are the server's.
 * <p>
 * Everything in it is a number or an enum, plus at most eleven items: the nine cells, the ingredient the order is
 * working on ({@link #current()}) and the one a refused portion names ({@link #refusal()}). Those items travel only in
 * the terminal's payload ({@code TerminalCraftOrderStatePayload}); a chunk packet carries the numbers alone
 * ({@link #write(CompoundTag)}), which is all the goggle line shows. It is derived state, rebuilt from the order
 * whenever it is asked for, and compares by value — so a terminal whose order did not move costs no packet.
 *
 * @param active    whether a craft order exists at all, finished ones included. Everything else is empty when not
 * @param state     where the order stands
 * @param cells     the grid the fetch was started for
 * @param crafts    how many crafts it fetches for
 * @param wanted    items the order asks for in total, i.e. what the buffer was missing when it started
 * @param delivered items the crane has really brought for it
 * @param inFlight  items open requests of the order still owe it
 * @param current   the ingredient the order is working on; empty for a finished order
 * @param refusal   why the last portion was refused, while the order still has work to do
 */
public record TerminalCraftOrderState(boolean active, ListOrderState state, CraftGrid<ItemKey> cells, int crafts,
                                      long wanted, long delivered, long inFlight, Optional<ItemKey> current,
                                      Optional<TerminalPortionRefusal> refusal) {
    /** No craft order on this terminal. */
    public static final TerminalCraftOrderState NONE = new TerminalCraftOrderState(false, ListOrderState.DONE,
            CraftGrid.empty(), 0, 0L, 0L, 0L, Optional.empty(), Optional.empty());

    private static final String ACTIVE = "Active";
    private static final String STATE = "State";
    private static final String WANTED = "Wanted";
    private static final String DELIVERED = "Delivered";

    public TerminalCraftOrderState {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(refusal, "refusal");
        if (state == null)
            state = ListOrderState.DONE;
        crafts = Math.max(0, crafts);
        wanted = Math.max(0L, wanted);
        delivered = Math.max(0L, Math.min(delivered, wanted));
        inFlight = Math.max(0L, Math.min(inFlight, wanted - delivered));
        if (!active) {
            state = ListOrderState.DONE;
            cells = CraftGrid.empty();
            crafts = 0;
            wanted = 0L;
            delivered = 0L;
            inFlight = 0L;
            current = Optional.empty();
        }
        if (!active || !state.isOpen())
            refusal = Optional.empty();
    }

    /** Whether the order still has work to do. */
    public boolean isOpen() {
        return active && state.isOpen();
    }

    /**
     * Whether every screen at the terminal shows {@link #cells()} and refuses to edit its grid: exactly while the order
     * is open. A finished order unlocks it, and the grid keeps those cells, so the player crafts what they fetched for.
     */
    public boolean locksGrid() {
        return isOpen();
    }

    /** Whether a question one portion raised is waiting for an answer. */
    public boolean asking() {
        return active && state == ListOrderState.ASKING;
    }

    /** Whether the order is waiting for a player: a question to answer, or a park to resume. */
    public boolean waitsForPlayer() {
        return active && state.waitsForPlayer();
    }

    /** Items the order is still missing. */
    public long outstanding() {
        return wanted - delivered;
    }

    /** The numbers alone, which is all a goggle line shows: no cells, no items. */
    public TerminalCraftOrderState forGoggles() {
        if (!isOpen())
            return NONE;
        return new TerminalCraftOrderState(true, state, CraftGrid.empty(), crafts, wanted, delivered, inFlight,
                Optional.empty(), Optional.empty());
    }

    /**
     * Writes the numbers into {@code tag}, and <b>nothing at all</b> unless an order is open, so no terminal's client
     * packet grows by a byte unless ingredients are really being fetched. No item goes into it. Never throws.
     */
    public void write(CompoundTag tag) {
        if (!isOpen())
            return;
        tag.putBoolean(ACTIVE, true);
        tag.putString(STATE, state.name());
        tag.putLong(WANTED, wanted);
        tag.putLong(DELIVERED, delivered);
    }

    /** Reads what {@link #write(CompoundTag)} wrote; never throws, and anything unreadable reads as {@link #NONE}. */
    public static TerminalCraftOrderState read(CompoundTag tag) {
        if (tag == null || !tag.getBoolean(ACTIVE))
            return NONE;
        return new TerminalCraftOrderState(true,
                ListOrderState.byName(tag.getString(STATE)).orElse(ListOrderState.RUNNING), CraftGrid.empty(), 0,
                tag.getLong(WANTED), tag.getLong(DELIVERED), 0L, Optional.empty(), Optional.empty());
    }
}
