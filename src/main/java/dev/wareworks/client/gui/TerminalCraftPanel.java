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

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.crafting.CraftGrid;
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
import net.minecraft.network.chat.Component;
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
 * Slice 1 of the issue: the progress row and its cancel mark ({@code TerminalMenuLayout#craftProgressY}) and the Fetch
 * button's place ({@link TerminalMenuLayout#FETCH_BUTTON}) stay empty until the craft fetch comes.
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
    /** Behind a cell the warehouse has some of, but too few for the next craft: the screen's gold, faint. */
    private static final int COLOR_SHORT = 0x40FFAA33;
    /** Laid over a result limited crafting keeps this player from, in front of the item. */
    private static final int COLOR_LOCKED = 0x60FF0000;
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
     * How one grid cell is drawn, decided cell by cell for the <b>next</b> craft ({@code docs/warehouse-system.md}
     * §3.4.5): the buffer's items cover the cells of their key in grid order, the warehouse's available items the
     * cells after those.
     */
    public enum CellState {
        /** The cell names nothing. */
        EMPTY,
        /** The buffer holds this cell's item for the next craft: drawn solid. */
        HERE,
        /** Not here, but the warehouse could grant it to a click in the list: a ghost. */
        AVAILABLE,
        /** Not here, and the warehouse has some but too few for the next craft: a ghost on gold. */
        SHORT,
        /** Not here, and the warehouse has none to give, or the terminal belongs to no warehouse: a ghost on red. */
        MISSING,
        /** Not here, and the server has not described this item yet (its view is of an earlier grid): a ghost. */
        UNKNOWN
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

    /** The grid as this player laid it. */
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
     * Shift as often as the delivered ingredients allow — once the server's view of this grid is in ({@link #craft}).
     * Every other click is not the panel's.</li>
     * </ul>
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
        return false;
    }

    /** Puts {@code key} on the cursor as a ghost: the right-click on a stock cell. */
    public void pickGhost(ItemKey key) {
        ghost = Objects.requireNonNull(key, "key");
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
     * @return whether a cell was free
     */
    public boolean fillNextFree(ItemKey key) {
        Objects.requireNonNull(key, "key");
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

    /** A changed grid: remembered, and announced to the server once the player pauses. */
    private void edit(CraftGrid<ItemKey> changed) {
        if (changed.equals(grid))
            return;
        boolean cleared = changed.occupiedCells() < grid.occupiedCells();
        grid = changed;
        previewIn = PREVIEW_DELAY_TICKS;
        onEdit.accept(grid);
        screen.uiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, cleared ? 0.8F : 1.2F);
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

    /**
     * How every cell is drawn, in row-major order. The buffer's items cover the cells of their key first, in grid
     * order, so a recipe that takes eight planks with five delivered shows five solid cells and three ghosts; the
     * warehouse's available items cover the cells after those.
     */
    public List<CellState> cellStates() {
        Map<ItemKey, Long> hereLeft = new HashMap<>(hereCounts());
        Map<ItemKey, Long> availableLeft = new HashMap<>();
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
            if (view.noAisle() || described.available() <= 0L) {
                states.add(CellState.MISSING);
                continue;
            }
            long left = availableLeft.computeIfAbsent(key, ignored -> described.available());
            if (left > 0L) {
                availableLeft.put(key, left - 1L);
                states.add(CellState.AVAILABLE);
            } else {
                states.add(CellState.SHORT);
            }
        }
        return List.copyOf(states);
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
     * ready count under it.
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
            if (state == CellState.SHORT)
                fillCell(graphics, x, y, COLOR_SHORT);
            else if (state == CellState.MISSING)
                fillCell(graphics, x, y, COLOR_MISSING);
            renderItem(graphics, font, key.toStack(), x + ITEM_INSET, y + ITEM_INSET, state != CellState.HERE, false);
        }

        // The tip, not the texture, is centred on the middle column, so it points at the middle of the result.
        int arrowX = left + layout.craftArrowX() + TerminalMenuLayout.SLOT / 2 - ARROW_TIP_COLUMN;
        AllGuiTextures.JEI_DOWN_ARROW.render(graphics, arrowX, top + layout.craftArrowY());
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
        renderReady(graphics, font, left, top, ready);
    }

    /** "x3" under the result: green while the buffer holds a craft, grey while it holds none. */
    private void renderReady(GuiGraphics graphics, Font font, int left, int top, int ready) {
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
     */
    public List<Component> tooltipAt(double mouseX, double mouseY) {
        if (ghost != null)
            return List.of();
        int cell = cellAt(mouseX, mouseY);
        if (cell >= 0)
            return cellTooltip(cell);
        if (resultAt(mouseX, mouseY))
            return resultTooltip();
        return List.of();
    }

    /**
     * A grid cell's tooltip: how to fill it while it is empty; otherwise the item, what one craft takes of it and what
     * is here, what the warehouse could grant of the rest, and how to clear it.
     */
    public List<Component> cellTooltip(int cell) {
        ItemKey key = grid.cell(cell).orElse(null);
        if (key == null)
            return List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CELL_EMPTY)
                            .withStyle(ChatFormatting.GRAY),
                    WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CELL_EMPTY_SHIFT)
                            .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), key.toStack()));
        int need = grid.perCraft().getOrDefault(key, 0);
        long here = hereCounts().getOrDefault(key, 0L);
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_PER_CRAFT,
                LangNumberFormat.format(need), LangNumberFormat.format(here)).withStyle(ChatFormatting.GRAY));
        stockLine(key, need, here).ifPresent(tooltip::add);
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CELL_CLEAR)
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /**
     * What the warehouse says about the part of one craft's {@code key} the buffer does not hold, or nothing when the
     * buffer holds all of it or the server has not described the item yet.
     */
    private Optional<Component> stockLine(ItemKey key, int need, long here) {
        long lacking = need - here;
        TerminalCraftViewPayload.KeyView described = view.keyView(key).orElse(null);
        if (lacking <= 0L || described == null)
            return Optional.empty();
        if (view.noAisle())
            return Optional.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_AISLE)
                    .withStyle(ChatFormatting.RED));
        if (described.available() <= 0L)
            return Optional.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_MISSING)
                    .withStyle(ChatFormatting.RED));
        if (described.available() < lacking)
            return Optional.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_SHORT,
                    LangNumberFormat.format(described.available()), LangNumberFormat.format(lacking))
                    .withStyle(ChatFormatting.GOLD));
        return Optional.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_AVAILABLE,
                LangNumberFormat.format(described.available())).withStyle(ChatFormatting.GRAY));
    }

    /**
     * The result cell's tooltip: the result, how many crafts the buffer holds, the lock, and the two clicks; or, for a
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
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_READY, LangNumberFormat.format(ready))
                .withStyle(ready > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
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
}
