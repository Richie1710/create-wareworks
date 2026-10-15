package dev.wareworks.client.gui;

import java.util.Optional;

import dev.wareworks.network.TerminalConfirmPayload;
import dev.wareworks.network.TerminalCraftAnswerPayload;
import dev.wareworks.network.TerminalCraftOrderStatePayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import dev.wareworks.network.TerminalListAnswerPayload;
import dev.wareworks.network.TerminalListPayload;
import dev.wareworks.network.TerminalOrdersPayload;
import dev.wareworks.network.TerminalResultPayload;
import dev.wareworks.network.TerminalStatusPayload;
import dev.wareworks.network.TerminalStockPayload;
import dev.wareworks.network.TerminalUsagePayload;

/**
 * Where the terminal payloads land on the client ({@code docs/architecture.md} ADR-019).
 * <p>
 * The server sends them to exactly one player, and only while that player has a terminal menu open, but a payload can
 * still arrive one tick after the screen was closed or replaced. Every update is therefore matched against the terminal
 * screen of the menu that is open <b>right now</b> and its container id; anything else is dropped without a trace.
 * That screen may be covered by a recipe viewer's recipe screen, which leaves the menu open, and still gets its
 * updates then ({@link WarehouseTerminalScreen#forMenu}, issue #24, slice 4).
 * <p>
 * Client only: this class is named from {@code network.WareworksNetwork} inside handler bodies that never run on a
 * dedicated server.
 */
public final class TerminalScreenUpdates {
    private TerminalScreenUpdates() {
    }

    /** New, changed or removed item types for the open screen. */
    public static void onStock(TerminalStockPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onStock(payload));
    }

    /** The aisle and crane state for the open screen. */
    public static void onStatus(TerminalStatusPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onStatus(payload));
    }

    /** The answer to a request the open screen sent. */
    public static void onResult(TerminalResultPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onResult(payload));
    }

    /** The production orders of the open screen's aisle (M11, ADR-024). */
    public static void onOrders(TerminalOrdersPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onOrders(payload));
    }

    /**
     * What a request of the open screen would cross, which the server answered instead of making it (M15 part 2). A
     * question that arrives after the screen was closed is dropped like every other late payload — and because nothing
     * was requested, dropping it loses nothing.
     */
    public static void onConfirm(TerminalConfirmPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onConfirm(payload));
    }

    /**
     * The order the server has stored for this player and how often they have asked for each item type (M24, issue
     * #17, ADR-037). It arrives with the first stock page of a screen and whenever a request changed a count.
     */
    public static void onUsage(TerminalUsagePayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onUsage(payload));
    }

    /** How far the open screen's clipboard order has got (M23, issue #19). */
    public static void onList(TerminalListPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onList(payload));
    }

    /**
     * The answer to a clipboard-order action the open screen sent (M23, issue #19): the sentence, and the question when
     * the server is asking about the whole list first. A late payload is dropped like every other one, and because
     * nothing was started, dropping a question loses nothing.
     */
    public static void onListAnswer(TerminalListAnswerPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onListAnswer(payload));
    }

    /**
     * What the open screen's crafting grid makes and what the aisle says about its items (M32, issue #24). A view that
     * arrives after the screen was closed is dropped like every other late payload; the next screen asks again.
     */
    public static void onCraftView(TerminalCraftViewPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onCraftView(payload));
    }

    /**
     * The answer to a craft click of the open screen (M32, issue #24), or to its Fetch and the craft order's buttons
     * (slice 2): the two kinds go to their own screen methods, a Fetch's whenever it carries a question or a value only
     * a Fetch can end in. The crafted items arrive through the menu's slots either way, and a Fetch's question that
     * arrives late requested nothing, so dropping a late answer loses only its sentence.
     */
    public static void onCraftAnswer(TerminalCraftAnswerPayload payload) {
        boolean fetch = payload.result().isFetch() || payload.question().isPresent();
        screen(payload.containerId()).ifPresent(screen -> {
            if (fetch)
                screen.onCraftFetchAnswer(payload);
            else
                screen.onCraftAnswer(payload);
        });
    }

    /**
     * How far the open screen's terminal has got with its craft order, and the grid it was started for, which the
     * screen shows and locks while the order is open (issue #24, slice 2).
     */
    public static void onCraftOrder(TerminalCraftOrderStatePayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onCraftOrder(payload));
    }

    /**
     * The terminal screen of the menu {@code containerId}: the one the player is looking at, or the one a recipe
     * viewer's recipe screen covers while the menu stays open ({@link WarehouseTerminalScreen#forMenu}, issue #24,
     * slice 4).
     */
    private static Optional<WarehouseTerminalScreen> screen(int containerId) {
        return WarehouseTerminalScreen.forMenu(containerId);
    }
}
