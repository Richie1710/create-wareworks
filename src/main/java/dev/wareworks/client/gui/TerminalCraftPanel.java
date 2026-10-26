package dev.wareworks.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalCraftOrderState;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.TerminalPortionRefusal;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.crafting.CraftAmounts;
import dev.wareworks.core.crafting.CraftFetchQuestion;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.terminal.CountFormat;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalCraftPayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The crafting panel of the warehouse terminal's screen (M32, issue #24, {@code docs/warehouse-system.md} §3.4.5): a
 * crafting table's 3 x 3 grid, the result the server resolved for it, and how many crafts the terminal's buffer holds
 * right now. The screen owns the window around it — the fold, the brass frame, the buttons and the order in which a
 * click is offered to everything ({@code WarehouseTerminalScreen#mouseClicked}) — and only delegates to this class.
 * <p>
 * <b>A grid of ghosts.</b> A cell is an {@link ItemKey}, never an item and never a menu slot
 * ({@link CraftGrid}): the ingredients stay in the terminal's buffer ("Delivered here") until the moment a player
 * crafts, so laying a recipe moves nothing, orders nothing and needs no hand-back when the screen closes. The grid is
 * this player's own and lives on the client, remembered per terminal by the screen for as long as the player stays
 * connected; the server only ever sees it in a payload.
 * <p>
 * <b>The client never runs a recipe.</b> {@value #PREVIEW_DELAY_TICKS} client ticks after the last edit the panel sends
 * the grid as a {@link TerminalCraftPayload.Action#PREVIEW}, and the server answers with a
 * {@link TerminalCraftViewPayload}: the grid it read, the recipe, its result, whether limited crafting locks it, and
 * what the warehouse can grant of each grid item. Until a view of exactly the grid on screen has arrived, the panel
 * shows no result and a click on the result crafts nothing ({@link #describesGrid()}): a view of an earlier grid —
 * even one of the same items, moved or one more of them — may name another recipe altogether.
 * <p>
 * <b>What the panel counts itself</b> is what is <i>here</i>: the buffer's slots are menu slots, which vanilla syncs,
 * so the cells turn solid and the ready count moves the moment an item arrives or is taken out. The server stays
 * authoritative at the craft, which resolves the recipe again and takes from the real buffer.
 * <p>
 * <b>Fetch</b> (slice 2 of the issue, ADR-062). The button at {@link TerminalMenuLayout#FETCH_BUTTON} — the screen's
 * widget, whose action and tooltip come from here ({@link #fetch()}, {@link #fetchTooltip()}) — sends the grid with the
 * window's amount field turned into crafts, and the server starts the terminal's <b>craft order</b>, which brings
 * what the buffer is missing for them by ordinary crane trips. While that order is open its grid is the one every
 * screen at the terminal shows ({@link #adopt}), and no edit of it is taken: the screen says why instead. The progress
 * row under the buttons counts what has arrived, and its cancel mark gives the order up ({@link #cancelAt}).
 * <p>
 * <b>Missing ingredients</b> (slice 3). A cell the warehouse cannot fill from its racks says what can be done about
 * it, from the server's view of the item ({@link TerminalCraftViewPayload.KeyView}): a production station of the aisle
 * has a pattern for it — the stock list's blue "+", and a Fetch offers to have it made first — or the safety stop
 * holds the making of it, or nothing makes it at all ({@link CellState}). Only the ingredients are ever made, at the
 * player's own machines; the grid's recipe is crafted by the player and nobody else (ADR-024, ADR-059).
 * <p>
 * <b>A recipe from a recipe viewer</b> (slice 4, ADR-063). A viewer's transfer button hands its recipe over as nine
 * lists of candidate items ({@link TerminalRecipeLay}); the server picks one item per cell, checks the grid against its
 * own recipes and sends the grid it accepted back with the crafting view, which the panel then takes like an edit
 * ({@link #adoptLay}). Laying moves nothing and orders nothing, exactly like laying a grid by hand.
 * <p>
 * <b>Items, not crafts</b> (issue #33). The amount field counts result items, as it does for a click in the list; the
 * panel rounds it up to whole crafts of the result the server resolved ({@link #resultPerCraft()},
 * {@link CraftAmounts}), and every "xN" it shows about that result is a number of result items, with a
 * {@code "Crafts: 2 (4 each)"} line where a craft makes more than one. Everything sent to and kept by the server stays
 * in crafts.
 */
public final class TerminalCraftPanel {
    /** Client ticks between a player's last edit of the grid and the preview that tells the server about it. */
    public static final int PREVIEW_DELAY_TICKS = 5;
    /** {@link #previewIn} while no preview is waiting to be sent. */
    private static final int NO_PREVIEW = -1;
    private static final int LEFT_BUTTON = 0;
    private static final int RIGHT_BUTTON = 1;
    /** Item cells are drawn one pixel inside their 18 pixel texture, as everywhere in this screen. */
    private static final int ITEM_INSET = 1;
    private static final int ITEM_SIZE = 16;
    /**
     * Vanilla's ghost: the white the recipe book lays over an ingredient it only shows
     * ({@code GhostRecipe#render}), drawn with {@link RenderType#guiGhostRecipeOverlay()} so that it tints the item's
     * own pixels and not the cell behind them.
     */
    private static final int COLOR_GHOST = 0x30FFFFFF;
    /** The recipe book's red behind an ingredient the player has none of ({@code GhostRecipe#render}). */
    private static final int COLOR_MISSING = 0x30FF0000;
    /** Behind a cell the warehouse has some of, but too few for the fetch: the screen's gold, faint. */
    private static final int COLOR_SHORT = 0x40FFAA33;
    /**
     * Behind a cell whose item the safety stop keeps the warehouse from making (slice 3): a lighter red than a missing
     * item's, though on the panel's dark slots the two tints are close. What tells them apart at a glance is the red
     * {@link #STOPPED_MARK} in the count corner, as the blue "+" tells a makeable cell — this one is lifted at the
     * production station.
     */
    private static final int COLOR_STOPPED = 0x50FF5050;
    /** The mark on a cell a production station could make up for: the stock list's own, for the same meaning. */
    private static final String MAKEABLE_MARK = "+";
    /** The mark on a stopped cell, in the window's red: the warehouse would make it, but the safety stop holds it. */
    private static final String STOPPED_MARK = "!";
    /** How far in front the mark is drawn: above the item, where the stock list draws its amounts and its "+". */
    private static final int MARK_Z = 201;
    /** Laid over a result limited crafting keeps this player from, in front of the item. */
    private static final int COLOR_LOCKED = 0x60FF0000;
    /**
     * The empty part of the progress bar: the dark of the window's inset boxes (search and amount field), so an order
     * at 0 still shows where its bar is.
     */
    private static final int COLOR_PROGRESS_TRACK = 0xFF1B1710;
    /** The cancel mark under the mouse: the window's red, lighter. */
    private static final int COLOR_CANCEL_HOVER = 0xFFFFB0B0;
    /** How far in front a locked result's red is drawn: above the item, like a cell's amount. */
    private static final int LOCKED_Z = 200;
    /** The ghost on the cursor is drawn where vanilla draws a carried stack ({@code renderFloatingItem}). */
    private static final int GHOST_CURSOR_Z = 232;
    /**
     * The column of Create's down arrow ({@link AllGuiTextures#JEI_DOWN_ARROW}) its tip is in. The arrow bends in from
     * the left, so its tip is not in the middle of its 18 columns; drawn at the middle column's x, it pointed at the
     * right half of the result cell.
     */
    private static final int ARROW_TIP_COLUMN = 13;
    /**
     * Width of the cancel mark's target at the right end of the progress row. The mark is one glyph, five pixels wide,
     * so the target is a little wider than what is drawn and still leaves the row's numbers all the room they need.
     */
    private static final int CANCEL_TARGET_WIDTH = 8;
    /**
     * Rows between where a lowercase glyph of the default font is drawn and the top of its ink: an {@code x} fills rows
     * 2 to 6 of its cell and sits on the same baseline as a digit, which fills rows 0 to 6.
     */
    private static final int LOWERCASE_INK_TOP = 2;

    /**
     * How one grid cell is drawn, decided cell by cell ({@code docs/warehouse-system.md} §3.4.5): the buffer's items
     * cover the cells of their key for the <b>next</b> craft, in grid order; every other cell says whether the
     * warehouse could supply the fetch the amount field asks for ({@link WarehouseTerminalScreen#fetchCrafts()}).
     */
    public enum CellState {
        /** The cell names nothing. */
        EMPTY,
        /** The buffer holds this cell's item for the next craft: drawn solid. */
        HERE,
        /**
         * Not here, but the warehouse holds enough for the fetch, or the craft order is bringing it right now: a
         * ghost.
         */
        AVAILABLE,
        /**
         * Not here, and the warehouse has some, but stock and buffer together are below what the fetch takes of it,
         * and nothing in the aisle makes it: a ghost on gold.
         */
        SHORT,
        /**
         * Not here, the warehouse holds too few of it or none for the fetch, but a production station of the aisle
         * has a pattern for it (slice 3): a ghost with the stock list's blue "+". A Fetch measures the chain that would
         * make it and asks before anything is made. Never while an open craft order's last portion was refused for it:
         * then no Fetch can ask anything, and the cell is short or missing with the refusal's sentence.
         */
        MAKEABLE,
        /**
         * Not here, the warehouse holds too few of it or none for the fetch, and the safety stop holds the making of it
         * (slice 3): a ghost on red with a red "!" in the count corner. No chain makes it until the stop is lifted at
         * the production station, so it counts with what the racks hold and nothing more.
         */
        STOPPED,
        /**
         * Not here, the warehouse has none to give and nothing in the aisle makes it, or the terminal belongs to no
         * warehouse: a ghost on the recipe book's red.
         */
        MISSING,
        /** Not here, and the server has not described this item yet (its view is of an earlier grid): a ghost. */
        UNKNOWN
    }

    /**
     * What the last Fetch was sent for: the grid, the recipe the panel showed and the result it named. The Yes to the
     * question that Fetch raised is sent for exactly this grid, and the question names this result.
     */
    private record SentFetch(CraftGrid<ItemKey> grid, Optional<ResourceLocation> hint, ItemStack result) {
    }

    private final WarehouseTerminalScreen screen;
    private final WarehouseTerminalMenu menu;
    private final TerminalMenuLayout layout;
    /** Told about every change of the grid, so the screen can remember it for this terminal. */
    private final Consumer<CraftGrid<ItemKey>> onEdit;
    private CraftGrid<ItemKey> grid;
    /** The item a right-click in the stock list picked up, which left clicks stamp into cells; {@code null} if none. */
    @Nullable
    private ItemKey ghost;
    /** The server's reading of the grid, as last pushed; the empty view until the first one arrives. */
    private TerminalCraftViewPayload view;
    /** Client ticks until the next preview is sent, or {@link #NO_PREVIEW}. */
    private int previewIn = NO_PREVIEW;
    /** Whether the screen has been laid out once, i.e. whether a remembered grid was already announced. */
    private boolean opened;
    /** The last Fetch this panel sent, or {@code null} before the first one. */
    @Nullable
    private SentFetch lastFetch;
    /**
     * The number of the last recipe a recipe viewer laid that this panel has seen ({@link #adoptLay}); 0 before the
     * first. The menu numbers its lays from 1, and panel and menu live exactly as long as each other.
     */
    private int adoptedLay;

    /**
     * @param grid   the grid this player last laid at this terminal, or an empty one
     * @param onEdit told about every change of the grid
     */
    TerminalCraftPanel(WarehouseTerminalScreen screen, WarehouseTerminalMenu menu, CraftGrid<ItemKey> grid,
            Consumer<CraftGrid<ItemKey>> onEdit) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.layout = menu.layout();
        this.grid = Objects.requireNonNull(grid, "grid");
        this.onEdit = Objects.requireNonNull(onEdit, "onEdit");
        this.view = TerminalCraftViewPayload.none(menu.containerId);
    }

    // --- state -------------------------------------------------------------------------------------------------------

    /** The grid as this player laid it, or as the terminal's open craft order locks it. */
    public CraftGrid<ItemKey> grid() {
        return grid;
    }

    /** The item a right-click in the list picked up, if one is on the cursor. */
    public Optional<ItemKey> ghost() {
        return Optional.ofNullable(ghost);
    }

    /** Whether a ghost is on the cursor; while one is, every click is the panel's ({@link #mouseClicked}). */
    public boolean hasGhost() {
        return ghost != null;
    }

    /** The server's reading of the grid as last pushed; the empty view until the first one arrived. */
    public TerminalCraftViewPayload view() {
        return view;
    }

    /** Whether an edit is waiting for its preview to be sent ({@value #PREVIEW_DELAY_TICKS} ticks after it). */
    public boolean previewPending() {
        return previewIn != NO_PREVIEW;
    }

    /** The server sent its reading of the grid. */
    void onView(TerminalCraftViewPayload payload) {
        view = Objects.requireNonNull(payload, "payload");
    }

    /**
     * The screen was laid out. The first time, a grid remembered from an earlier visit is announced to the server at
     * once, so its result is there without the player touching anything; every later layout (a fold, a resize) changes
     * nothing the server knows.
     */
    void opened() {
        if (opened)
            return;
        opened = true;
        if (!grid.isEmpty())
            send(TerminalCraftPayload.Action.PREVIEW);
    }

    /** One client tick: sends the waiting preview once the player has stopped editing for a moment. */
    void tick() {
        if (previewIn > 0 && --previewIn == 0) {
            previewIn = NO_PREVIEW;
            send(TerminalCraftPayload.Action.PREVIEW);
        }
    }

    /**
     * The terminal's open craft order locks the grid to {@code cells} (slice 2): every screen at the terminal shows
     * the recipe being fetched for, whoever laid it, so nobody fetches for one grid and crafts another. The grid
     * becomes this player's own, remembered like an edit and announced to the server at once, so its result is on
     * screen within a tick; it stays when the order finishes, so the player crafts what was fetched. A ghost on the
     * cursor has nowhere to go any more and is dropped.
     * <p>
     * The preview goes without a hint of this screen's own, and the server resolves it with the recipe the order was
     * started for ({@code WarehouseTerminalBlockEntity#craftOrderHint}), so a grid two recipes match shows — and
     * crafts — what is being fetched for. A grid that already was the order's is announced once more when the lock
     * starts, for the same reason.
     *
     * @param starting whether the order has only now started locking the grid
     */
    void adopt(CraftGrid<ItemKey> cells, boolean starting) {
        Objects.requireNonNull(cells, "cells");
        ghost = null;
        if (cells.equals(grid)) {
            if (starting && !grid.isEmpty()) {
                previewIn = NO_PREVIEW;
                send(TerminalCraftPayload.Action.PREVIEW);
            }
            return;
        }
        grid = cells;
        onEdit.accept(grid);
        previewIn = NO_PREVIEW;
        send(TerminalCraftPayload.Action.PREVIEW);
    }

    /**
     * A recipe a recipe viewer laid into this panel, as the server accepted it (slice 4,
     * {@link TerminalCraftViewPayload#lay()}): the grid becomes the one the server picked from the viewer's candidates,
     * remembered like an edit, and is announced at once with the recipe the server matched as the hint, so the panel
     * shows — and a click crafts — exactly that recipe, even for a grid two recipes match. The server already resolved
     * it while laying, so that preview costs it no second lookup. A ghost on the cursor has nowhere to go any more and
     * is dropped.
     * <p>
     * <b>Each lay is taken once.</b> Every view carries the menu's last accepted lay for as long as the menu is open,
     * under a number that grows with each one, and the panel keeps the highest number it has seen. The lay is read from
     * there and never from the view's own grid, which can still be an older one: a preview of this panel that reached
     * the server after the lay is answered for its own grid.
     * <p>
     * <b>Not while a craft order locks the grid.</b> The order's grid is the one every screen at the terminal shows and
     * the one its player is to craft, so the lay is not taken and the status row says why, as for any other edit of the
     * locked grid. It counts as seen all the same, so it cannot replace the order's grid later, when the order ends.
     *
     * @return whether the grid became the laid one
     */
    boolean adoptLay(TerminalCraftViewPayload.Lay lay) {
        Objects.requireNonNull(lay, "lay");
        if (!lay.isPresent() || lay.number() <= adoptedLay)
            return false;
        adoptedLay = lay.number();
        if (lay.cells().isEmpty() || refusedByLock())
            return false;
        ghost = null;
        previewIn = NO_PREVIEW;
        if (!lay.cells().equals(grid)) {
            grid = lay.cells();
            onEdit.accept(grid);
        }
        PacketDistributor.sendToServer(new TerminalCraftPayload(menu.containerId, TerminalCraftPayload.Action.PREVIEW,
                grid, lay.recipe()));
        return true;
    }

    /** The number of the last laid recipe this panel has seen, taken or not; 0 before the first (the dev harness). */
    public int adoptedLay() {
        return adoptedLay;
    }

    /**
     * Sends a preview still waiting for its delay at once, and nothing when none waits: a recipe viewer is about to
     * lay a recipe ({@link TerminalRecipeLay#lay}), and the server reads the grid this panel shows before the lay and
     * not after it. Otherwise the waiting preview would follow the lay as soon as the screen ticks again, and the
     * server would answer a grid the lay is about to replace; and a lay the server refuses still leaves the grid on
     * screen with its answer.
     */
    void flushPreview() {
        if (previewIn == NO_PREVIEW)
            return;
        previewIn = NO_PREVIEW;
        send(TerminalCraftPayload.Action.PREVIEW);
    }

    // --- editing -----------------------------------------------------------------------------------------------------

    /**
     * A click while the panel is folded out, in the order {@code docs/warehouse-system.md} §3.4.5 gives (the modal
     * question and the step panel have had theirs already):
     * <ul>
     * <li><b>With a ghost on the cursor</b> every click is the panel's. A left click on a grid cell stamps the ghost
     * into it and keeps it on the cursor, so one pick paints several cells; a right click on a grid cell clears it; a
     * left click on a stock cell picks that item instead — and never requests it. Any other click drops the ghost.</li>
     * <li><b>Without one</b>, a left click on a grid cell writes the key of the real item on the cursor, which stays
     * there, or clears the cell with an empty hand; a right click clears it; a click on the result crafts once, with
     * Shift as often as the delivered ingredients allow — once the server's view of this grid is in ({@link #craft});
     * a click on the progress row's cancel mark gives the craft order up. Every other click is not the panel's.</li>
     * </ul>
     * While a craft order locks the grid every edit is refused, and the status row says why ({@link #edit}).
     *
     * @param shift     whether Shift is held
     * @param stockItem the item of the stock cell under the mouse, or {@code null} when the mouse is over none
     * @return whether the panel took the click
     */
    boolean mouseClicked(double mouseX, double mouseY, int button, boolean shift, @Nullable ItemKey stockItem) {
        int cell = cellAt(mouseX, mouseY);
        if (ghost != null) {
            if (cell >= 0 && button == LEFT_BUTTON)
                edit(grid.with(cell, ghost));
            else if (cell >= 0 && button == RIGHT_BUTTON)
                edit(grid.without(cell));
            else if (stockItem != null && button == LEFT_BUTTON)
                pickGhost(stockItem);
            else
                dropGhost();
            return true;
        }
        if (cell >= 0 && button == LEFT_BUTTON) {
            edit(grid.withCell(cell, ItemKey.fromStack(menu.getCarried())));
            return true;
        }
        if (cell >= 0 && button == RIGHT_BUTTON) {
            edit(grid.without(cell));
            return true;
        }
        if (button == LEFT_BUTTON && resultAt(mouseX, mouseY)) {
            craft(shift);
            return true;
        }
        if (button == LEFT_BUTTON && cancelAt(mouseX, mouseY)) {
            screen.cancelCraftOrder();
            return true;
        }
        return false;
    }

    /**
     * Puts {@code key} on the cursor as a ghost: the right-click on a stock cell. Refused while a craft order locks the
     * grid, since there is no cell the ghost could be stamped into.
     */
    public void pickGhost(ItemKey key) {
        Objects.requireNonNull(key, "key");
        if (refusedByLock())
            return;
        ghost = key;
        screen.uiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.2F);
    }

    /**
     * Drops the ghost on the cursor (Escape, or a click that is not the panel's).
     *
     * @return whether there was one
     */
    public boolean dropGhost() {
        if (ghost == null)
            return false;
        ghost = null;
        screen.uiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 0.8F);
        return true;
    }

    /**
     * Writes {@code key} into the first free cell in row-major order, without a ghost: Shift + right-click on a stock
     * cell.
     *
     * @return whether a cell was free and the grid could be edited
     */
    public boolean fillNextFree(ItemKey key) {
        Objects.requireNonNull(key, "key");
        if (refusedByLock())
            return false;
        OptionalInt free = grid.nextFreeCell();
        if (free.isEmpty()) {
            screen.uiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.8F);
            return false;
        }
        edit(grid.with(free.getAsInt(), key));
        return true;
    }

    /** Empties the whole grid: the Clear button. */
    public void clear() {
        edit(CraftGrid.empty());
    }

    /**
     * Asks the server to craft the grid from the buffer, once or as often as the delivered ingredients allow. The
     * payload carries the grid, which the server previews first, so a preview still waiting for its delay is not sent
     * on top of it.
     * <p>
     * <b>Only for a grid the server has answered.</b> The server crafts whatever the grid it is sent makes, so a click
     * while the view is of an earlier grid would craft something the player has not been shown — two planks laid side
     * by side where sticks were shown make a pressure plate. Such a click crafts nothing: it sends the waiting preview
     * at once instead and sounds the refusal, and the next click, with the result on screen, crafts. A grid the server
     * found no recipe for is answered, so a click on it still goes out and the status row says why nothing was made.
     *
     * @return whether the craft was sent
     */
    public boolean craft(boolean all) {
        previewIn = NO_PREVIEW;
        if (!describesGrid()) {
            send(TerminalCraftPayload.Action.PREVIEW);
            screen.uiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.8F);
            return false;
        }
        send(all ? TerminalCraftPayload.Action.CRAFT_ALL : TerminalCraftPayload.Action.CRAFT);
        return true;
    }

    /**
     * The Fetch button (slice 2): asks the server to bring what the buffer is missing for the crafts of this grid the
     * amount field's items round up to ({@link WarehouseTerminalScreen#fetchCrafts()}, issue #33). Nothing is decided
     * here — the server measures the fetch against the warehouse and the buffer, starts it, refuses it or asks first,
     * and the answer comes back to the screen.
     * <p>
     * Like a craft, <b>only for a grid the server has answered</b>: the Fetch tooltip names the result the player is
     * fetching for, and a press while the view is of an earlier grid would fetch for something not on screen. Such a
     * press sends the waiting preview at once and sounds the refusal instead.
     *
     * @return whether the Fetch was sent
     */
    public boolean fetch() {
        if (grid.isEmpty()) {
            screen.uiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.8F);
            return false;
        }
        previewIn = NO_PREVIEW;
        if (!describesGrid()) {
            send(TerminalCraftPayload.Action.PREVIEW);
            screen.uiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.8F);
            return false;
        }
        Optional<ResourceLocation> hint = hint();
        lastFetch = new SentFetch(grid, hint, shownResult().copy());
        PacketDistributor.sendToServer(TerminalCraftOrderPayload.fetch(menu.containerId, grid, hint,
                screen.fetchCrafts(), 0L, false));
        screen.uiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
        return true;
    }

    /**
     * The Yes to the question a Fetch raised: the same grid again, for exactly the crafts the question said are
     * possible and with the production total it named, marked as the answer. The server measures it once more and
     * asks again if the warehouse moved in between, so this is consent and not a command.
     *
     * @return whether the answer was sent
     */
    boolean confirmFetch(CraftFetchQuestion<ItemKey> question) {
        Objects.requireNonNull(question, "question");
        // An order that opened here in the meantime makes the question stale (the screen drops it on the state push);
        // a Yes pressed in the same frame must not re-point the server menu's grid away from the locked one either.
        if (screen.isCraftGridLocked())
            return false;
        SentFetch sent = lastFetch;
        CraftGrid<ItemKey> cells = sent != null ? sent.grid() : grid;
        if (cells.isEmpty())
            return false;
        PacketDistributor.sendToServer(TerminalCraftOrderPayload.fetch(menu.containerId, cells,
                sent != null ? sent.hint() : hint(), Math.max(1, question.possible()), question.producing(), true));
        return true;
    }

    /** The result the last Fetch was sent for, which its question names; the shown result before the first one. */
    public ItemStack fetchedResult() {
        SentFetch sent = lastFetch;
        return sent != null ? sent.result() : shownResult();
    }

    /**
     * A changed grid: remembered, and announced to the server once the player pauses. While a craft order locks the
     * grid nothing changes, and the status row says how to change it ({@link #refusedByLock()}).
     */
    private void edit(CraftGrid<ItemKey> changed) {
        if (changed.equals(grid) || refusedByLock())
            return;
        boolean cleared = changed.occupiedCells() < grid.occupiedCells();
        grid = changed;
        previewIn = PREVIEW_DELAY_TICKS;
        onEdit.accept(grid);
        screen.uiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, cleared ? 0.8F : 1.2F);
    }

    /**
     * Whether the terminal's open craft order locks the grid right now; if it does, the screen has just been told to
     * say so, so a refused edit is never a silent one.
     */
    private boolean refusedByLock() {
        if (!screen.isCraftGridLocked())
            return false;
        screen.refuseLockedEdit();
        return true;
    }

    private void send(TerminalCraftPayload.Action action) {
        PacketDistributor.sendToServer(new TerminalCraftPayload(menu.containerId, action, grid, hint()));
    }

    /**
     * The recipe the panel shows for this grid, which the server tries first, so that of two recipes matching the same
     * grid the one the player saw is the one that is crafted. Only a view of this very grid can name it.
     */
    private Optional<ResourceLocation> hint() {
        return describesGrid() ? view.recipe() : Optional.empty();
    }

    // --- what the panel shows ----------------------------------------------------------------------------------------

    /**
     * Whether the server's view was resolved for exactly the grid as it is now, cell by cell
     * ({@link TerminalCraftViewPayload#describes}). After an edit it is not — unless the edit led back to that very
     * grid — until the view of the new grid arrives: the edit is still waiting for its preview, or the answer is on
     * the way. In that time the panel shows no result, sends no hint and crafts nothing ({@link #craft}), because the
     * old view may be of the same items in another arrangement or number, which can make something else entirely.
     */
    public boolean describesGrid() {
        return view.describes(grid);
    }

    /** What one craft makes, as the server resolved it for this grid; empty while it resolved nothing for it. */
    public ItemStack shownResult() {
        return grid.isEmpty() || !describesGrid() ? ItemStack.EMPTY : view.result();
    }

    /** Whether limited crafting keeps this player from the recipe the panel shows. */
    public boolean locked() {
        return !shownResult().isEmpty() && view.locked();
    }

    /**
     * How many of each grid item the terminal's buffer holds, from the synced buffer slots; every grid item is named,
     * in the grid's order, with 0 for one the buffer lacks.
     */
    public Map<ItemKey, Long> hereCounts() {
        Map<ItemKey, Long> counts = new LinkedHashMap<>();
        int bufferSlots = Math.min(menu.bufferSlots(), menu.slots.size());
        for (ItemKey key : grid.perCraft().keySet()) {
            long count = 0L;
            for (int slot = 0; slot < bufferSlots; slot++) {
                ItemStack stack = menu.slots.get(slot).getItem();
                if (key.matches(stack))
                    count += stack.getCount();
            }
            counts.put(key, count);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** How many crafts the buffer holds right now ({@link CraftGrid#craftsFrom}); 0 for an empty grid. */
    public int readyCrafts() {
        return grid.craftsFrom(hereCounts());
    }

    /** The result items the crafts the buffer holds would make: the "x8" under the result (issue #33). */
    public long readyItems() {
        return CraftAmounts.itemsFor(readyCrafts(), resultPerCraft());
    }

    /**
     * How many result items one craft of the grid makes (issue #33): the count of the result the server resolved for
     * exactly this grid ({@link #shownResult()}), and 1 while none is known — an edit waiting for its view, a grid
     * without a recipe, an empty grid. The amount field's items are turned into crafts with it
     * ({@link WarehouseTerminalScreen#fetchCrafts()}); a Fetch is only ever sent while the view describes the grid, so
     * the crafts it sends are always rounded with the real count.
     */
    public int resultPerCraft() {
        ItemStack result = shownResult();
        return result.isEmpty() ? 1 : Math.max(1, result.getCount());
    }

    /**
     * How many result items one craft of the result the last Fetch was sent for makes ({@link #fetchedResult()}), 1
     * if it named none: the count the question and the answers of that Fetch, which the server counts in crafts, are
     * shown in items with.
     */
    public int fetchedPerCraft() {
        ItemStack result = fetchedResult();
        return result.isEmpty() ? 1 : Math.max(1, result.getCount());
    }

    /**
     * {@code "Crafts: 2 (4 each)"}: what rounding an amount of result items up to whole crafts did (issue #33). The
     * panel and the question show it only for a recipe that makes more than one item, so a one-item recipe reads
     * exactly as it did. Public and static for the visual run, like the screen's sentence builders.
     */
    public static Component craftsText(int crafts, int perCraft) {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CRAFTS, LangNumberFormat.format(crafts),
                LangNumberFormat.format(perCraft));
    }

    /**
     * What a Fetch of {@code crafts} crafts would ask for, per grid item in first-cell order: what the buffer is
     * missing for them, topped up against the buffer and nothing else ({@link CraftGrid#toFetch}) — the very number the
     * server requests. Empty when the buffer already holds every craft.
     */
    public Map<ItemKey, Integer> toFetch(int crafts) {
        return grid.toFetch(crafts, hereCounts());
    }

    /**
     * How every cell is drawn, in row-major order. The buffer's items cover the cells of their key first, in grid
     * order, so a recipe that takes eight planks with five delivered shows five solid cells and three ghosts. A cell
     * the buffer does not cover is available when the warehouse's stock and the buffer together hold what the fetch
     * takes of its item ({@code uses x crafts}, {@link WarehouseTerminalScreen#fetchCrafts()}); otherwise it says what
     * can be done about the rest ({@link #stockState}).
     * <p>
     * While the craft order is open its items are on their way, partly in the crane's head and so neither here nor in
     * stock: those cells are drawn as plain ghosts rather than flickering to short, unless the order's last portion was
     * refused for that very item. Such a cell is never drawn makeable: its portion was just refused, no Fetch can be
     * pressed while the order is open, and the cell marks the item as what the order is waiting for.
     */
    public List<CellState> cellStates() {
        Map<ItemKey, Long> here = hereCounts();
        Map<ItemKey, Long> hereLeft = new HashMap<>(here);
        Map<ItemKey, Integer> uses = grid.perCraft();
        long crafts = Math.max(1, screen.fetchCrafts());
        TerminalCraftOrderState order = screen.craftOrderState();
        Optional<ItemKey> refused = order.refusal().map(TerminalPortionRefusal::about);
        List<CellState> states = new ArrayList<>(CraftGrid.SIZE);
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            ItemKey key = grid.cell(cell).orElse(null);
            if (key == null) {
                states.add(CellState.EMPTY);
                continue;
            }
            if (hereLeft.getOrDefault(key, 0L) > 0L) {
                hereLeft.merge(key, -1L, Long::sum);
                states.add(CellState.HERE);
                continue;
            }
            TerminalCraftViewPayload.KeyView described = view.keyView(key).orElse(null);
            if (described == null) {
                states.add(CellState.UNKNOWN);
                continue;
            }
            boolean refusedHere = order.locksGrid() && refused.equals(Optional.of(key));
            if (order.locksGrid() && !refusedHere) {
                states.add(CellState.AVAILABLE);
                continue;
            }
            if (view.noAisle()) {
                states.add(CellState.MISSING);
                continue;
            }
            states.add(stockState(described, here.getOrDefault(key, 0L), uses.getOrDefault(key, 0) * crafts,
                    refusedHere));
        }
        return List.copyOf(states);
    }

    /**
     * What the warehouse says about a cell the buffer does not cover, for a fetch that takes {@code needed} of its item
     * with {@code here} of it delivered: available when the racks hold the rest; otherwise, in this order, stopped,
     * makeable or — by what the racks hold — short or missing. The safety stop comes first because a chain for a
     * stopped item is refused whatever patterns the aisle has ({@link PlanRefusal#PAUSED}, the planner's first test).
     * An item the open craft order's last portion was refused for ({@code refused}) is never makeable: the order is
     * waiting for it, and there is no Fetch to ask.
     */
    private static CellState stockState(TerminalCraftViewPayload.KeyView described, long here, long needed,
            boolean refused) {
        if (described.available() > 0L && here + described.available() >= needed)
            return CellState.AVAILABLE;
        if (described.paused())
            return CellState.STOPPED;
        if (described.producible() && !refused)
            return CellState.MAKEABLE;
        return described.available() > 0L ? CellState.SHORT : CellState.MISSING;
    }

    // --- hit tests ---------------------------------------------------------------------------------------------------

    /** The grid cell under the mouse, {@code 0..8}, or -1; always -1 while the panel is folded in. */
    public int cellAt(double mouseX, double mouseY) {
        if (!screen.isCraftPanelOpen())
            return -1;
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            if (isOver(mouseX, mouseY, layout.craftCellX(cell), layout.craftCellY(cell)))
                return cell;
        }
        return -1;
    }

    /** Whether the mouse is over the result cell; always false while the panel is folded in. */
    public boolean resultAt(double mouseX, double mouseY) {
        return screen.isCraftPanelOpen()
                && isOver(mouseX, mouseY, layout.craftResultX(), layout.craftResultY());
    }

    /** Whether the mouse is over the Fetch button's place, whatever the button shows right now. */
    public boolean fetchButtonAt(double mouseX, double mouseY) {
        return screen.isCraftPanelOpen() && isOver(mouseX, mouseY,
                layout.craftButtonX(TerminalMenuLayout.FETCH_BUTTON), layout.craftButtonY());
    }

    /**
     * Whether the mouse is over the progress row — the bar and the numbers under it — while the craft order is open and
     * the row is drawn; always false otherwise.
     */
    public boolean progressAt(double mouseX, double mouseY) {
        if (!screen.isCraftPanelOpen() || !screen.craftOrderState().isOpen())
            return false;
        int left = screen.getGuiLeft() + TerminalMenuLayout.PANEL_CONTENT_X;
        int top = screen.getGuiTop() + layout.craftProgressY();
        int bottom = screen.getGuiTop() + layout.craftProgressTextY() + TerminalMenuLayout.LABEL_HEIGHT;
        return mouseX >= left && mouseX < left + TerminalMenuLayout.PANEL_CONTENT_WIDTH && mouseY >= top
                && mouseY < bottom;
    }

    /** Whether the mouse is over the cancel mark at the right end of the progress row, which gives the order up. */
    public boolean cancelAt(double mouseX, double mouseY) {
        if (!progressAt(mouseX, mouseY))
            return false;
        int right = screen.getGuiLeft() + TerminalMenuLayout.PANEL_CONTENT_X + TerminalMenuLayout.PANEL_CONTENT_WIDTH;
        int top = screen.getGuiTop() + layout.craftProgressTextY();
        return mouseX >= right - CANCEL_TARGET_WIDTH && mouseY >= top;
    }

    /** Whether the mouse is over the 18 pixel cell at {@code x}/{@code y}, relative to the window. */
    private boolean isOver(double mouseX, double mouseY, int x, int y) {
        int left = screen.getGuiLeft() + x;
        int top = screen.getGuiTop() + y;
        return mouseX >= left && mouseX < left + TerminalMenuLayout.SLOT && mouseY >= top
                && mouseY < top + TerminalMenuLayout.SLOT;
    }

    // --- drawing -----------------------------------------------------------------------------------------------------

    /**
     * The panel's content inside the frame the screen drew: the title, the nine cells, the arrow, the result and the
     * ready count under it, and the craft order's progress row while it is open. The buttons are the screen's widgets.
     */
    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        Component title = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_TITLE);
        // One measured word (TerminalCraftTextWidthTest); the cut is only the backstop of a translation that is not.
        String shown = font.width(title) <= TerminalMenuLayout.MAX_PANEL_TITLE_WIDTH ? title.getString()
                : font.plainSubstrByWidth(title.getString(), TerminalMenuLayout.MAX_PANEL_TITLE_WIDTH);
        graphics.drawString(font, shown, left + TerminalMenuLayout.PANEL_CONTENT_X,
                top + TerminalMenuLayout.PANEL_TITLE_Y, WarehouseTerminalScreen.COLOR_HEADER, false);

        List<CellState> states = cellStates();
        int hovered = cellAt(mouseX, mouseY);
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            int x = left + layout.craftCellX(cell);
            int y = top + layout.craftCellY(cell);
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, x, y);
            if (cell == hovered)
                fillCell(graphics, x, y, WarehouseTerminalScreen.COLOR_CELL_HOVER);
            CellState state = states.get(cell);
            ItemKey key = grid.cell(cell).orElse(null);
            if (key == null)
                continue;
            int tint = tint(state);
            if (tint != 0)
                fillCell(graphics, x, y, tint);
            renderItem(graphics, font, key.toStack(), x + ITEM_INSET, y + ITEM_INSET, state != CellState.HERE, false);
            if (state == CellState.MAKEABLE)
                renderCornerMark(graphics, font, MAKEABLE_MARK, WarehouseTerminalScreen.COLOR_PRODUCIBLE,
                        x + ITEM_INSET, y + ITEM_INSET);
            else if (state == CellState.STOPPED)
                renderCornerMark(graphics, font, STOPPED_MARK, WarehouseTerminalScreen.COLOR_ERROR, x + ITEM_INSET,
                        y + ITEM_INSET);
        }

        // The tip, not the texture, is centred on the middle column, so it points at the middle of the result.
        int arrowX = left + layout.craftArrowX() + TerminalMenuLayout.SLOT / 2 - ARROW_TIP_COLUMN;
        AllGuiTextures.JEI_DOWN_ARROW.render(graphics, arrowX, top + layout.craftArrowY());
        renderResult(graphics, font, left, top, mouseX, mouseY);
        renderProgress(graphics, font, left, top, mouseX, mouseY);
    }

    /**
     * What is laid behind a cell's item, or 0 for nothing: gold for short, the stock list's faint blue for makeable
     * (its cells look the same for the same meaning), a lighter red for stopped, and the recipe book's red for missing.
     * Makeable and stopped also carry a mark in the count corner, so neither depends on telling two tints apart.
     */
    private static int tint(CellState state) {
        return switch (state) {
            case SHORT -> COLOR_SHORT;
            case MAKEABLE -> WarehouseTerminalScreen.COLOR_PRODUCIBLE_CELL;
            case STOPPED -> COLOR_STOPPED;
            case MISSING -> COLOR_MISSING;
            default -> 0;
        };
    }

    /**
     * A cell's mark, drawn as the stock list draws its "+" on an item the aisle can only make: at three quarters size,
     * in the corner a stack's count is in — the blue "+" on a makeable cell, the red "!" on a stopped one.
     *
     * @param x the item's left edge
     * @param y the item's top edge
     */
    private static void renderCornerMark(GuiGraphics graphics, Font font, String mark, int color, int x, int y) {
        float scale = WarehouseTerminalScreen.COUNT_SCALE;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, MARK_Z);
        pose.scale(scale, scale, 1.0F);
        int right = Math.round((x + ITEM_SIZE) / scale) - font.width(mark);
        int top = Math.round((y + ITEM_SIZE) / scale) - font.lineHeight;
        graphics.drawString(font, mark, right, top, color, true);
        pose.popPose();
    }

    /** The result cell, the result in it and the ready count under it; the cell alone while there is no result. */
    private void renderResult(GuiGraphics graphics, Font font, int left, int top, int mouseX, int mouseY) {
        int resultX = left + layout.craftResultX();
        int resultY = top + layout.craftResultY();
        AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, resultX, resultY);
        if (resultAt(mouseX, mouseY))
            fillCell(graphics, resultX, resultY, WarehouseTerminalScreen.COLOR_CELL_HOVER);
        ItemStack result = shownResult();
        if (result.isEmpty())
            return;
        int ready = readyCrafts();
        // Solid once the buffer holds a craft, a ghost before: the result cell says at a glance whether a click works.
        renderItem(graphics, font, result, resultX + ITEM_INSET, resultY + ITEM_INSET, ready <= 0, true);
        if (locked()) {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(0.0F, 0.0F, LOCKED_Z);
            graphics.fill(resultX + ITEM_INSET, resultY + ITEM_INSET, resultX + ITEM_INSET + ITEM_SIZE,
                    resultY + ITEM_INSET + ITEM_SIZE, COLOR_LOCKED);
            pose.popPose();
        }
        renderReady(graphics, font, left, top, CraftAmounts.itemsFor(ready, resultPerCraft()));
    }

    /**
     * "x8" under the result: the result items the buffer's crafts make (issue #33), green while it holds a craft, grey
     * while it holds none. The widest, nine slots of 64 crafts of 64, is "x36,864", well inside the panel.
     */
    private void renderReady(GuiGraphics graphics, Font font, int left, int top, long ready) {
        String text = "x" + LangNumberFormat.format(ready);
        float scale = WarehouseTerminalScreen.COUNT_SCALE;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0F);
        int x = Math.round((left + layout.craftReadyCenterX()) / scale) - font.width(text) / 2;
        int y = Math.round((top + layout.craftReadyY()
                + (TerminalMenuLayout.LABEL_HEIGHT - TerminalMenuLayout.SMALL_TEXT_HEIGHT) / 2) / scale);
        graphics.drawString(font, text, x, y,
                ready > 0 ? WarehouseTerminalScreen.COLOR_SUCCESS : WarehouseTerminalScreen.COLOR_DIM, true);
        pose.popPose();
    }

    /**
     * The craft order's progress row while the order is open (slice 2): a bar of what has arrived, in green while the
     * crane works and in gold while the order waits for a player, or stands still because its last portion was
     * refused (the status row then says why); under it
     * {@code "6/16"} in compact numbers, which the row's tooltip gives in full, and the cancel mark at the right end.
     * Nothing at all while no order is open — a finished one is told in the status row and then gives the row back.
     */
    private void renderProgress(GuiGraphics graphics, Font font, int left, int top, int mouseX, int mouseY) {
        TerminalCraftOrderState order = screen.craftOrderState();
        if (!order.isOpen())
            return;
        int x = left + TerminalMenuLayout.PANEL_CONTENT_X;
        int width = TerminalMenuLayout.PANEL_CONTENT_WIDTH;
        int barY = top + layout.craftProgressY();
        graphics.fill(x, barY, x + width, barY + TerminalMenuLayout.PROGRESS_HEIGHT, COLOR_PROGRESS_TRACK);
        boolean waiting = order.waitsForPlayer()
                || WarehouseTerminalScreen.standsStill(order.refusal(), order.inFlight()).isPresent();
        graphics.fill(x, barY, x + progressWidth(order.delivered(), order.wanted(), width),
                barY + TerminalMenuLayout.PROGRESS_HEIGHT,
                waiting ? WarehouseTerminalScreen.COLOR_LOST : WarehouseTerminalScreen.COLOR_SUCCESS);

        int rowY = top + layout.craftProgressTextY();
        int numbersY = rowY + (TerminalMenuLayout.LABEL_HEIGHT - TerminalMenuLayout.SMALL_TEXT_HEIGHT) / 2;
        String numbers = CountFormat.compact(order.delivered()) + "/" + CountFormat.compact(order.wanted());
        float scale = WarehouseTerminalScreen.COUNT_SCALE;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(font, numbers, Math.round(x / scale), Math.round(numbersY / scale),
                WarehouseTerminalScreen.COLOR_TEXT, true);
        pose.popPose();

        // The mark is a lowercase glyph at full size, whose ink starts LOWERCASE_INK_TOP rows below where it is drawn,
        // beside digits at COUNT_SCALE whose ink starts where they are drawn: drawn that much higher, its ink lines up
        // with the numbers' top and baseline instead of hanging below them.
        String mark = WarehouseTerminalScreen.CANCEL_MARK;
        int markX = x + width - CANCEL_TARGET_WIDTH + (CANCEL_TARGET_WIDTH - font.width(mark)) / 2;
        graphics.drawString(font, mark, markX, numbersY - LOWERCASE_INK_TOP,
                cancelAt(mouseX, mouseY) ? COLOR_CANCEL_HOVER : WarehouseTerminalScreen.COLOR_ERROR, false);
    }

    /** The filled part of a {@code width} pixel bar for {@code delivered} of {@code wanted}; never wider than it. */
    static int progressWidth(long delivered, long wanted, int width) {
        if (wanted <= 0L || delivered <= 0L)
            return 0;
        return (int) Math.min(width, width * Math.min(delivered, wanted) / wanted);
    }

    /**
     * The ghost on the cursor, drawn the way vanilla draws a carried stack and the recipe book a ghost ingredient.
     * Nothing to draw while there is none.
     */
    void renderGhostCursor(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (ghost == null)
            return;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, GHOST_CURSOR_Z);
        renderItem(graphics, font, ghost.toStack(), mouseX - ITEM_SIZE / 2, mouseY - ITEM_SIZE / 2, true, false);
        pose.popPose();
    }

    /** An item as the panel draws it: solid, or as vanilla's ghost, with or without its count. */
    private static void renderItem(GuiGraphics graphics, Font font, ItemStack stack, int x, int y, boolean ghosted,
            boolean count) {
        graphics.renderFakeItem(stack, x, y);
        if (ghosted)
            graphics.fill(RenderType.guiGhostRecipeOverlay(), x, y, x + ITEM_SIZE, y + ITEM_SIZE, COLOR_GHOST);
        if (count)
            graphics.renderItemDecorations(font, stack, x, y);
    }

    private static void fillCell(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + ITEM_INSET, y + ITEM_INSET, x + ITEM_INSET + ITEM_SIZE, y + ITEM_INSET + ITEM_SIZE, color);
    }

    // --- tooltips ----------------------------------------------------------------------------------------------------

    /**
     * The tooltip of whatever part of the panel is under the mouse, or an empty list. Nothing while a ghost is on the
     * cursor, as vanilla shows no slot's tooltip under a carried stack. Public, because a tooltip is drawn only while a
     * mouse hovers and the visual harness reads it instead of photographing it.
     * <p>
     * The Fetch button's tooltip is the panel's too ({@link #fetchTooltip()}): it names the result and every item a
     * Fetch would ask for, which only the panel knows, and it is shown even while the button is greyed out, where it
     * says why.
     */
    public List<Component> tooltipAt(double mouseX, double mouseY) {
        if (ghost != null)
            return List.of();
        int cell = cellAt(mouseX, mouseY);
        if (cell >= 0)
            return cellTooltip(cell);
        if (resultAt(mouseX, mouseY))
            return resultTooltip();
        if (fetchButtonAt(mouseX, mouseY))
            return fetchTooltip();
        if (progressAt(mouseX, mouseY))
            return progressTooltip(cancelAt(mouseX, mouseY));
        return List.of();
    }

    /**
     * A grid cell's tooltip: how to fill it while it is empty; otherwise the item, what one craft takes of it and what
     * is here, what the warehouse could grant of the rest for the fetch and what can be done about what it cannot
     * ({@link #stockLines}), and how to clear it — or, while a craft order locks the grid, how to change it.
     */
    public List<Component> cellTooltip(int cell) {
        boolean lockedGrid = screen.isCraftGridLocked();
        ItemKey key = grid.cell(cell).orElse(null);
        if (key == null) {
            if (lockedGrid)
                return List.of(lockedLine());
            return List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CELL_EMPTY)
                            .withStyle(ChatFormatting.GRAY),
                    WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CELL_EMPTY_SHIFT)
                            .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        }
        List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), key.toStack()));
        int need = grid.perCraft().getOrDefault(key, 0);
        long here = hereCounts().getOrDefault(key, 0L);
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_PER_CRAFT,
                LangNumberFormat.format(need), LangNumberFormat.format(here)).withStyle(ChatFormatting.GRAY));
        tooltip.addAll(stockLines(key, need, here));
        tooltip.add(lockedGrid ? lockedLine()
                : WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CELL_CLEAR)
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    private static Component lockedLine() {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_GRID_LOCKED).withStyle(ChatFormatting.GOLD);
    }

    /**
     * What the warehouse says about the part of the fetch's {@code key} the buffer does not hold — {@code need} per
     * craft times the crafts the amount field rounds up to — the way {@link #stockState} draws it: what is available,
     * or the shortfall and then what can be done about it — a production station makes it, and the Fetch will ask
     * first; the safety stop holds the making of it, in the very sentence a Fetch is refused with; or nothing here
     * makes it. Nothing when the buffer holds all of it, when the server has not described the item yet, or while the
     * craft order is bringing it (the progress row counts that), unless the order's last portion was refused for this
     * very item. A producible item the open order was refused for gets that refusal's sentence instead of the
     * makeable hint — the status row's "No free order for Oak Planks" — because no Fetch can ask anything while the
     * order is open.
     */
    private List<Component> stockLines(ItemKey key, int need, long here) {
        long lacking = (long) need * Math.max(1, screen.fetchCrafts()) - here;
        TerminalCraftViewPayload.KeyView described = view.keyView(key).orElse(null);
        if (lacking <= 0L || described == null)
            return List.of();
        TerminalCraftOrderState order = screen.craftOrderState();
        Optional<TerminalPortionRefusal> refusedHere = order.locksGrid()
                ? order.refusal().filter(refusal -> refusal.about().equals(key)) : Optional.empty();
        if (order.locksGrid() && refusedHere.isEmpty())
            return List.of();
        if (view.noAisle())
            return List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_AISLE)
                    .withStyle(ChatFormatting.RED));
        long available = described.available();
        if (available >= lacking)
            return List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_AVAILABLE,
                    LangNumberFormat.format(available)).withStyle(ChatFormatting.GRAY));
        List<Component> lines = new ArrayList<>(2);
        if (available > 0L)
            lines.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_SHORT,
                    LangNumberFormat.format(available), LangNumberFormat.format(lacking))
                    .withStyle(ChatFormatting.GOLD));
        if (described.paused())
            lines.add(fitName(name -> WareworksLang.translateDirect(PlanRefusal.PAUSED.langKey(), name),
                    key.toStack().getHoverName()).copy().withStyle(ChatFormatting.RED));
        else if (described.producible())
            // While the order is open there is no Fetch to ask anything: its own refusal says why the item is not being
            // made, in the sentence the status row gives.
            lines.add(refusedHere.isPresent()
                    ? screen.portionRefusalLine(refusedHere.get()).copy().withStyle(ChatFormatting.RED)
                    : WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_MAKEABLE)
                            .withColor(WarehouseTerminalScreen.COLOR_PRODUCIBLE));
        else if (available <= 0L)
            lines.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_MISSING)
                    .withStyle(ChatFormatting.RED));
        return lines;
    }

    /**
     * The result cell's tooltip: the result, how many result items the buffer's crafts make — with the crafts line
     * when a craft makes more than one and the buffer holds any (issue #33) — the lock, and the two clicks; or, for a
     * grid the server found no recipe for, that sentence; or nothing while there is no answer for this grid yet.
     */
    public List<Component> resultTooltip() {
        ItemStack result = shownResult();
        if (result.isEmpty()) {
            if (grid.isEmpty() || !describesGrid())
                return List.of();
            return List.of(WareworksLang.translateDirect(TerminalCraftResult.NO_RECIPE.langKey())
                    .withStyle(ChatFormatting.RED));
        }
        List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), result));
        int ready = readyCrafts();
        int perCraft = resultPerCraft();
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_READY,
                LangNumberFormat.format(CraftAmounts.itemsFor(ready, perCraft)))
                .withStyle(ready > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        if (ready > 0 && perCraft > 1)
            tooltip.add(craftsText(ready, perCraft).copy().withStyle(ChatFormatting.GRAY));
        if (locked()) {
            tooltip.add(WareworksLang.translateDirect(TerminalCraftResult.LOCKED.langKey())
                    .withStyle(ChatFormatting.RED));
            return tooltip;
        }
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_ONCE)
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_ALL)
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /**
     * The Fetch button's tooltip, which follows what the button does right now
     * ({@link WarehouseTerminalScreen#craftOrderAction()}): Answer, Resume or Cancel while the craft order is open, one
     * line each; otherwise {@code "Fetch the ingredients for Chest x3"} — the result items the Fetch is for, the amount
     * field rounded up to whole crafts, followed by {@code "Crafts: 2 (4 each)"} for a recipe that makes more than one
     * (issue #33) — and one line per item the Fetch would ask for, topped up against the buffer — in the colour of its
     * cells: gold where the warehouse holds too few of it, blue where a production station would make up for it, red
     * where it holds none or the safety stop holds the making of it — then how to set the number of items. A grid the
     * server answered with no recipe, or with a locked one, shows only that refusal and the hint: the server refuses
     * such a Fetch before it measures anything. Every line keeps to the window's row: an item's name gives way first.
     */
    public List<Component> fetchTooltip() {
        return switch (screen.craftOrderAction()) {
            case ANSWER -> List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_ANSWER));
            case RESUME -> List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_RESUME));
            case CANCEL -> List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CANCEL));
            default -> fetchLines();
        };
    }

    private List<Component> fetchLines() {
        if (grid.isEmpty())
            return List.of(WareworksLang.translateDirect(TerminalCraftResult.GRID_EMPTY.langKey())
                    .withStyle(ChatFormatting.GRAY));
        int crafts = screen.fetchCrafts();
        int perCraft = resultPerCraft();
        String count = LangNumberFormat.format(CraftAmounts.itemsFor(crafts, perCraft));
        List<Component> tooltip = new ArrayList<>(CraftGrid.SIZE + 4);
        ItemStack result = shownResult();
        if (!result.isEmpty()) {
            tooltip.add(fitName(name -> WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_FETCH, name, count),
                    result.getHoverName()));
            if (perCraft > 1)
                tooltip.add(craftsText(crafts, perCraft).copy().withStyle(ChatFormatting.GRAY));
        } else if (describesGrid())
            tooltip.add(WareworksLang.translateDirect(TerminalCraftResult.NO_RECIPE.langKey())
                    .withStyle(ChatFormatting.RED));
        if (locked())
            tooltip.add(WareworksLang.translateDirect(TerminalCraftResult.LOCKED.langKey())
                    .withStyle(ChatFormatting.RED));
        // The server refuses a Fetch for a grid without a recipe or with a locked one before it measures anything
        // (WarehouseTerminalBlockEntity#fetchCraft): nothing would be fetched, and "click the result to craft" would
        // point at a result that is not there (issue #24 review fix).
        boolean refused = describesGrid() && (result.isEmpty() || locked());
        Map<ItemKey, Integer> missing = refused ? Map.of() : toFetch(crafts);
        if (!refused && missing.isEmpty())
            tooltip.add(WareworksLang.translateDirect(TerminalCraftResult.NOTHING_TO_FETCH.langKey())
                    .withStyle(ChatFormatting.GREEN));
        missing.forEach((key, amount) -> tooltip.add(fitName(name -> WareworksLang.translateDirect(
                WareworksLang.TERMINAL_CRAFT_TO_FETCH, name, LangNumberFormat.format(amount)),
                key.toStack().getHoverName()).copy().withStyle(fetchLineStyle(key, amount))));
        // The ingredients of a hand craft always come into this buffer (M27, ADR-065 D10), whichever door the clicks
        // in the list go to: said while one is chosen, so the gold title row is not read as covering the Fetch too.
        if (screen.isDoorChosen())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_DOOR_FETCH_HERE)
                    .withStyle(ChatFormatting.GRAY));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_FETCH_HINT)
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /**
     * The colour of an item's line in the Fetch tooltip, the one its cells are drawn in ({@link #stockState}): grey for
     * an item the warehouse can supply; for one it cannot, red while the safety stop holds the making of it, the stock
     * list's blue while a production station would make it, gold while the racks hold some and red while they hold
     * none.
     */
    private Style fetchLineStyle(ItemKey key, int amount) {
        TerminalCraftViewPayload.KeyView described = describesGrid() ? view.keyView(key).orElse(null) : null;
        if (described == null || described.available() >= amount)
            return Style.EMPTY.withColor(ChatFormatting.GRAY);
        if (view.noAisle() || described.paused())
            return Style.EMPTY.withColor(ChatFormatting.RED);
        if (described.producible())
            return Style.EMPTY.withColor(WarehouseTerminalScreen.COLOR_PRODUCIBLE);
        return Style.EMPTY.withColor(described.available() > 0L ? ChatFormatting.GOLD : ChatFormatting.RED);
    }

    /**
     * The progress row's tooltip while the craft order is open: what has arrived of what it asks for and what is on
     * the way, in full where the row shows compact numbers; over the cancel mark also what a click on it does.
     */
    public List<Component> progressTooltip(boolean overCancelMark) {
        TerminalCraftOrderState order = screen.craftOrderState();
        if (!order.isOpen())
            return List.of();
        List<Component> tooltip = new ArrayList<>(3);
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_PROGRESS,
                LangNumberFormat.format(order.delivered()), LangNumberFormat.format(order.wanted())));
        if (order.inFlight() > 0L)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_IN_FLIGHT,
                    LangNumberFormat.format(order.inFlight())).withStyle(ChatFormatting.GRAY));
        if (overCancelMark)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CANCEL_MARK)
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /**
     * The line {@code build} makes of {@code name}, with the name cut until the line fits the window's row: the
     * numbers around it are what the line is about, and a name is still recognizable from its beginning.
     */
    private static Component fitName(Function<Component, Component> build, Component name) {
        Font font = Minecraft.getInstance().font;
        Component line = build.apply(name);
        int over = font.width(line) - WarehouseTerminalScreen.ROW_WIDTH;
        if (over <= 0)
            return line;
        int room = font.width(name) - over - font.width(CommonComponents.ELLIPSIS);
        if (room <= 0)
            return line;
        return build.apply(Component.literal(font.plainSubstrByWidth(name.getString(), room))
                .append(CommonComponents.ELLIPSIS));
    }
}
