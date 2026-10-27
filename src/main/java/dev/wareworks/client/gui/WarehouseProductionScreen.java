package dev.wareworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.StoppedProduct;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.network.ProductionCancelPayload;
import dev.wareworks.network.ProductionPatternPayload;
import dev.wareworks.network.ProductionResumePayload;
import dev.wareworks.network.ProductionScreenPayload;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.gui.UIRenderHelper;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The warehouse production station's screen ({@code docs/warehouse-system.md} §3.5, ADR-024): the 3 x 3 pattern grid
 * of the selected pattern, its result, a tab per pattern slot, the station's buffer and the production orders running
 * here.
 * <p>
 * <b>The pattern cells are ghosts, not slots.</b> Clicking one with an item on the cursor sets that cell, clicking it
 * with an empty hand clears it, and scrolling over it changes the amount — none of which moves an item. Every click
 * sends a {@code ProductionPatternPayload}; the server writes the entry and pushes the whole pattern list back, so the
 * screen never has to guess what it changed. Shift-clicking an item in the player inventory fills the first free grid
 * cell, which is the quick way to write a pattern without picking items up.
 * <p>
 * <b>The grid is for reading, not for arranging.</b> Several cells may name the same item; the server merges them into
 * one ingredient ({@code ProductionPattern#fromGrid}), because the machine arranges the items itself and the crane
 * carries one item key per trip.
 * <p>
 * The station's own buffer is shown as real slots below the pattern: a player can take delivered ingredients back out
 * by hand, and nothing can be put in — the same rule a funnel sees. Clicking a production order line cancels it.
 * <p>
 * <b>The safety stop is shown here, and lifted here</b> (M20, issue #4, ADR-032). While the warehouse has stopped making
 * something this station's patterns make, the first order line becomes the <b>stopped row</b> — red, naming the item and
 * what it cost in its tooltip — and the tab of every stopped product is marked in the same colour. A click on that row
 * says the machine is worth another batch ({@link ProductionResumePayload}); it is the way back that is reachable
 * whether or not a stock keeper's rule happens to govern the item, and it is the same action a sneak-click on the block
 * performs. A station whose machines work has no such row and reads exactly as it did before.
 * <p>
 * <b>A Crafter Feeder at the back checks the patterns</b> (issue #34, ADR-067). While one is attached, the "Patterns"
 * row says so at its right end, and a pattern that is no crafting recipe the feeder can lay — no recipe at all, a cell
 * that is not exactly one item, or a recipe for something else — is drawn <b>red</b>: a red frame around its grid and
 * result with a red cross in place of the arrow, and a red bar under its tab, so it can be found without selecting it.
 * The arrow's and the tab's tooltip give the reason and the consequence: the warehouse never orders that pattern, so
 * nothing is fetched for it. The window's height budget is unchanged; the feeder adds no row.
 * <p>
 * <b>Up to 36 patterns</b> (issue #35, ADR-068). The tab strip widens from three to six columns as the station holds
 * more patterns, and above 18 it shows three rows at a time and <b>scrolls by rows</b> with the mouse wheel over it,
 * with a slim scroll bar beside it. The scroll position is this screen's own view state: it is never sent or saved.
 * Every change of the selection goes through {@link #select}, which scrolls the selected tab into view, and so does
 * every (re)opening of the window; the wheel itself may scroll the selected tab out of view again, so that a player can
 * browse. A hidden row that holds a stopped or refused pattern is marked with a red tick on the scroll bar, so those
 * can still be found without scrolling.
 */
public class WarehouseProductionScreen extends AbstractSimiContainerScreen<ProductionMenu> {
    private static final int TITLE_Y = 2;
    private static final int ITEM_INSET = 1;
    private static final int COUNT_SHIFT_Z = 200;
    private static final float COUNT_SCALE = 0.75F;
    /** Nothing is under the mouse. */
    private static final int NONE = -1;

    private static final int COLOR_HEADER = 0xFFFBDC7D;
    private static final int COLOR_TEXT = 0xFFEEEEEE;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_ARROW = 0xFF9A7B4F;
    private static final int COLOR_LOST = 0xFFFFAA33;
    /** The safety stop, in the colour the stock keeper's paused row and lamp already use. */
    private static final int COLOR_STOPPED = 0xFFFF4040;
    /** The same colour over a pattern tab or the result cell of a stopped product. */
    private static final int COLOR_STOPPED_TINT = 0x60FF4040;
    private static final int COLOR_CELL_HOVER = 0x60FFFFFF;
    private static final int COLOR_TAB_SELECTED = 0x80FBDC7D;
    /** A pattern the Crafter Feeder refuses (issue #34): the safety stop's red, because both mean "not made". */
    private static final int COLOR_REFUSED = 0xFFFF4040;
    /** Height of the red bar under the tab of a refused pattern. */
    private static final int REFUSED_BAR = 2;
    /** What stands in the arrow's place while the selected pattern is refused. */
    private static final String REFUSED_ARROW = "x";
    /** The tab strip's scroll bar track (issue #35). */
    private static final int COLOR_SCROLL_TRACK = 0xFF2B2B2B;
    /** The tab strip's scroll bar thumb. */
    private static final int COLOR_SCROLL_THUMB = 0xFFC8C8C8;
    /** The scroll bar's tick for the selected pattern's row while the wheel has scrolled it out of view. */
    private static final int COLOR_SCROLL_SELECTED = 0xFFFBDC7D;

    /** Usable width of a row of text: the window without its two margins — 216 px, as in the terminal. */
    public static final int ROW_WIDTH = ProductionMenuLayout.WIDTH - 2 * ProductionMenuLayout.MARGIN;
    /** Free space kept between two texts that share a row. */
    public static final int TEXT_GAP = 4;
    /**
     * Smallest width the item half of an order line keeps, the same floor the terminal's own order lines use
     * ({@code WarehouseTerminalScreen#MIN_ITEM_WIDTH}): a name cut down to three letters names nothing at all.
     */
    public static final int MIN_ITEM_WIDTH = 54;

    private ProductionScreenState state = ProductionScreenState.NONE;
    private ProductionMenuLayout layout;
    private int selectedPattern;
    /** First visible tab row of a scrolling tab strip (issue #35); client-only view state, never sent or saved. */
    private int tabScroll;

    public WarehouseProductionScreen(ProductionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        layout = menu.layout();
        setWindowSize(ProductionMenuLayout.WIDTH, layout.height());
        super.init();
        // A (re)opened or resized window always starts with the selected tab in view.
        select(selectedPattern);
        if (menu.station() == null && minecraft != null && minecraft.player != null)
            minecraft.player.closeContainer(); // the block entity is gone: nothing to edit
    }

    /** The server sent the station's patterns and orders. */
    public void onState(ProductionScreenPayload payload) {
        state = payload.state();
        clampSelection();
    }

    /** The patterns and orders as last pushed by the server (dev harness and tests of the client side). */
    public ProductionScreenState state() {
        return state;
    }

    /** The pattern slot the grid is showing. */
    public int selectedPattern() {
        return selectedPattern;
    }

    /** The first visible row of the pattern tab strip (0 unless it scrolls; issue #35). */
    public int tabScroll() {
        return tabScroll;
    }

    /**
     * The rows of a scrolling tab strip that are out of view and hold a stopped or a refused pattern: the rows the
     * scroll bar marks with a red tick (issue #35). Empty while the strip does not scroll.
     */
    public List<Integer> hiddenRedRows() {
        List<Integer> rows = new ArrayList<>();
        if (layout == null || !layout.scrolls())
            return rows;
        for (int row = 0; row < layout.tabRows(); row++) {
            if (!rowVisible(row) && rowIsRed(row))
                rows.add(row);
        }
        return rows;
    }

    /**
     * The selected pattern's tab row while the wheel has scrolled it out of view, or -1 while it is visible or the
     * strip does not scroll. The scroll bar marks it, so the pattern the grid is showing can always be found.
     */
    public int hiddenSelectedRow() {
        if (layout == null || !layout.scrolls())
            return -1;
        int row = layout.tabRow(selectedPattern);
        return rowVisible(row) ? -1 : row;
    }

    private boolean rowVisible(int row) {
        return row >= tabScroll && row < tabScroll + layout.visibleTabRows();
    }

    /**
     * Selects pattern slot {@code pattern} (clamped to the station's slots) and scrolls its tab into view. Every change
     * of the selection goes through here — a click on a tab, a selection the server's state made invalid, and the dev
     * harness standing in for a selection made elsewhere — so the selected tab is never left hidden by a selection.
     */
    public void select(int pattern) {
        selectedPattern = Math.max(0, Math.min(pattern, menu.patternSlots() - 1));
        if (layout != null)
            tabScroll = layout.clampTabScroll(layout.scrollToShow(selectedPattern, tabScroll));
    }

    // --- interaction ---------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int tab = tabAt(mouseX, mouseY);
        if (tab != NONE) {
            if (button == 1) {
                send(ProductionPatternPayload.clearPattern(menu.containerId, tab));
                return true;
            }
            select(tab);
            playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.4F);
            return true;
        }
        int cell = cellAt(mouseX, mouseY);
        if (cell != NONE) {
            setEntry(selectedPattern, cell, menu.getCarried());
            return true;
        }
        // The stopped row is the way back from the safety stop, and it sits where the first order line would be, so it
        // is tested before them (M20).
        if (button == 0 && isOverStoppedRow(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new ProductionResumePayload(menu.containerId));
            playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.0F);
            return true;
        }
        // Only a left click on an order that is still running is a cancellation, exactly as on the terminal's own
        // order lines. A right or middle click, or a click on a finished line, falls through instead of sending a
        // payload the server refuses anyway — which would spend the menu's per-tick edit budget and play the
        // confirming sound over a no-op.
        int order = orderAt(mouseX, mouseY);
        if (order != NONE && button == 0) {
            ProductionScreenState.OrderView view = shownOrders().get(order);
            if (!view.state().isFinished()) {
                send(new ProductionCancelPayload(menu.containerId, view.id()));
                return true;
            }
        }
        // Shift-clicking an item in the player inventory writes it into the first free grid cell instead of moving it:
        // the station's buffer accepts nothing by hand, so writing a pattern is what that click can mean here. The
        // item stays where it is — a pattern cell is a ghost.
        if (hasShiftDown() && hoveredSlot != null && hoveredSlot.hasItem()
                && menu.slots.indexOf(hoveredSlot) >= menu.bufferSlots()) {
            int free = firstFreeCell();
            if (free != NONE) {
                setEntry(selectedPattern, free, hoveredSlot.getItem());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Scrolling over a filled pattern cell changes that cell's amount. An empty cell has no amount to change. Scrolling
     * over a tab strip that has more rows than it shows moves it by one row per notch (issue #35); the event is taken
     * there even at either end, so the wheel never reaches what lies behind the strip.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0)
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int cell = cellAt(mouseX, mouseY);
        if (cell != NONE) {
            Optional<ProductionScreenState.EntryView> view = state.entry(selectedPattern, cell);
            if (view.isPresent()) {
                int step = hasShiftDown() ? ProductionEntry.MAX_PER_CELL / 4 : 1;
                int count = ProductionEntry.clampCellCount(view.get().count() + (int) Math.signum(scrollY) * step);
                send(ProductionPatternPayload.set(menu.containerId, selectedPattern, cell, view.get().key(), count));
                return true;
            }
        }
        if (layout.scrolls() && isOverTabStrip(mouseX, mouseY)) {
            tabScroll = layout.clampTabScroll(tabScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The first free grid cell of the selected pattern, or {@link #NONE}. */
    private int firstFreeCell() {
        for (int cell = 0; cell < ProductionPatterns.GRID_CELLS; cell++) {
            if (state.entry(selectedPattern, cell).isEmpty())
                return cell;
        }
        return NONE;
    }

    /** Sets a pattern cell from {@code stack}, or clears it when the stack is empty. */
    private void setEntry(int pattern, int cell, ItemStack stack) {
        if (pattern < 0 || pattern >= menu.patternSlots())
            return;
        if (stack.isEmpty()) {
            if (state.entry(pattern, cell).isEmpty())
                return;
            send(ProductionPatternPayload.clear(menu.containerId, pattern, cell));
            return;
        }
        int count = state.entry(pattern, cell).map(ProductionScreenState.EntryView::count)
                .orElse(ProductionEntry.MIN_COUNT);
        send(ProductionPatternPayload.set(menu.containerId, pattern, cell, ItemKey.of(stack), count));
    }

    private void send(ProductionPatternPayload payload) {
        PacketDistributor.sendToServer(payload);
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.2F);
    }

    private void send(ProductionCancelPayload payload) {
        PacketDistributor.sendToServer(payload);
        playUiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
    }

    // --- rendering -----------------------------------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int panelHeight = layout.height();
        UIRenderHelper.drawStretched(graphics, x + 3, y + 3, ProductionMenuLayout.WIDTH - 6, panelHeight - 6, 0,
                AllGuiTextures.VALUE_SETTINGS_OUTER_BG);
        renderBrassFrame(graphics, x, y, ProductionMenuLayout.WIDTH, panelHeight);
        renderPattern(graphics, mouseX, mouseY);
        renderTabs(graphics, mouseX, mouseY);
        renderSlotBackgrounds(graphics);
        renderTexts(graphics);
    }

    /** Create's brass window frame, stretched to this window (the terminal's frame). */
    private void renderBrassFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        AllGuiTextures.BRASS_FRAME_TL.render(graphics, x, y);
        AllGuiTextures.BRASS_FRAME_TR.render(graphics, x + w - 4, y);
        AllGuiTextures.BRASS_FRAME_BL.render(graphics, x, y + h - 4);
        AllGuiTextures.BRASS_FRAME_BR.render(graphics, x + w - 4, y + h - 4);
        UIRenderHelper.drawStretched(graphics, x, y + 4, 3, h - 8, 0, AllGuiTextures.BRASS_FRAME_LEFT);
        UIRenderHelper.drawStretched(graphics, x + w - 3, y + 4, 3, h - 8, 0, AllGuiTextures.BRASS_FRAME_RIGHT);
        UIRenderHelper.drawCropped(graphics, x + 4, y, w - 8, 3, 0, AllGuiTextures.BRASS_FRAME_TOP);
        UIRenderHelper.drawCropped(graphics, x + 4, y + h - 3, w - 8, 3, 0, AllGuiTextures.BRASS_FRAME_BOTTOM);
    }

    /** The selected pattern: its 3 x 3 grid, the arrow and the result cell. */
    private void renderPattern(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = cellAt(mouseX, mouseY);
        for (int cell = 0; cell < ProductionMenuLayout.PATTERN_CELLS; cell++) {
            int cellX = leftPos + layout.patternCellX(cell);
            int cellY = topPos + layout.patternCellY(cell);
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, cellX, cellY);
            if (hovered == cell)
                graphics.fill(cellX, cellY, cellX + ProductionMenuLayout.SLOT, cellY + ProductionMenuLayout.SLOT,
                        COLOR_CELL_HOVER);
            Optional<ProductionScreenState.EntryView> entry = state.entry(selectedPattern, cell);
            if (cell == ProductionMenuLayout.RESULT_CELL && entry.isPresent()
                    && state.stoppedOf(entry.get().key()).isPresent())
                graphics.fill(cellX, cellY, cellX + ProductionMenuLayout.SLOT, cellY + ProductionMenuLayout.SLOT,
                        COLOR_STOPPED_TINT);
            entry.ifPresent(view -> renderEntry(graphics, view, cellX + ITEM_INSET, cellY + ITEM_INSET));
        }
        // The arrow between the grid and the result: this is a pattern, not a crafting grid. A pattern the Crafter
        // Feeder refuses gets a red frame round grid and result and a red cross where the arrow was (issue #34).
        boolean refused = refusedAt(selectedPattern).isPresent();
        String arrow = refused ? REFUSED_ARROW : ">";
        int arrowX = leftPos + layout.arrowX() + ProductionMenuLayout.ARROW_WIDTH / 2 - font.width(arrow) / 2;
        int arrowY = topPos + layout.patternCellY(ProductionMenuLayout.RESULT_CELL)
                + ProductionMenuLayout.SLOT / 2 - font.lineHeight / 2;
        graphics.drawString(font, arrow, arrowX, arrowY, refused ? COLOR_REFUSED : COLOR_ARROW, false);
        if (refused) {
            int left = leftPos + layout.patternCellX(0) - 1;
            int top = topPos + layout.patternCellY(0) - 1;
            int right = leftPos + layout.patternCellX(ProductionMenuLayout.RESULT_CELL) + ProductionMenuLayout.SLOT + 1;
            int bottom = topPos + layout.patternCellY(ProductionMenuLayout.PATTERN_CELLS - 2)
                    + ProductionMenuLayout.SLOT + 1;
            graphics.renderOutline(left, top, right - left, bottom - top, COLOR_REFUSED);
        }
    }

    /** One tab per pattern slot, showing that pattern's result; only the visible rows of a scrolling strip. */
    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = tabAt(mouseX, mouseY);
        for (int pattern = 0; pattern < menu.patternSlots(); pattern++) {
            if (!layout.tabVisible(pattern, tabScroll))
                continue;
            int tabX = leftPos + layout.tabX(pattern);
            int tabY = topPos + layout.tabY(pattern, tabScroll);
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, tabX, tabY);
            if (pattern == selectedPattern)
                graphics.fill(tabX, tabY, tabX + ProductionMenuLayout.SLOT, tabY + ProductionMenuLayout.SLOT,
                        COLOR_TAB_SELECTED);
            else if (hovered == pattern)
                graphics.fill(tabX, tabY, tabX + ProductionMenuLayout.SLOT, tabY + ProductionMenuLayout.SLOT,
                        COLOR_CELL_HOVER);
            // A stopped product is marked on its own tab, whichever pattern is selected: the stopped row names one item,
            // and this is what says which of several patterns it belongs to (M20).
            if (stoppedAt(pattern).isPresent())
                graphics.fill(tabX, tabY, tabX + ProductionMenuLayout.SLOT, tabY + ProductionMenuLayout.SLOT,
                        COLOR_STOPPED_TINT);
            state.result(pattern).ifPresent(view -> renderEntry(graphics, view, tabX + ITEM_INSET, tabY + ITEM_INSET));
            // A pattern the Crafter Feeder refuses is marked under its own tab, whichever pattern is selected, as a
            // stopped product is (issue #34); a bar rather than a tint, so a tab can say both at once.
            if (refusedAt(pattern).isPresent())
                graphics.fill(tabX, tabY + ProductionMenuLayout.SLOT - REFUSED_BAR, tabX + ProductionMenuLayout.SLOT,
                        tabY + ProductionMenuLayout.SLOT, COLOR_REFUSED);
        }
        if (layout.scrolls())
            renderScrollbar(graphics);
    }

    /**
     * The tab strip's scroll bar (issue #35): a dark track the height of the pattern block, a light thumb for the
     * visible rows, and a red tick at every <b>hidden</b> row that holds a stopped or a refused pattern — the promise
     * that a red tab can be found without selecting it holds for tabs scrolled out of view too. A gold tick marks the
     * selected pattern's row while the wheel has scrolled it out of view, drawn last so it wins over a red one.
     */
    private void renderScrollbar(GuiGraphics graphics) {
        int x = leftPos + layout.scrollbarX();
        int top = topPos + layout.scrollbarY();
        int width = ProductionMenuLayout.SCROLLBAR_WIDTH;
        graphics.fill(x, top, x + width, top + layout.scrollbarHeight(), COLOR_SCROLL_TRACK);
        for (int row : hiddenRedRows())
            renderScrollTick(graphics, x, row, COLOR_REFUSED);
        int selectedRow = hiddenSelectedRow();
        if (selectedRow >= 0)
            renderScrollTick(graphics, x, selectedRow, COLOR_SCROLL_SELECTED);
        int thumbY = topPos + layout.thumbY(tabScroll);
        graphics.fill(x, thumbY, x + width, thumbY + layout.thumbHeight(), COLOR_SCROLL_THUMB);
    }

    private void renderScrollTick(GuiGraphics graphics, int x, int row, int color) {
        int tickY = topPos + layout.scrollbarRowY(row) + (layout.scrollbarRowHeight() - REFUSED_BAR) / 2;
        graphics.fill(x, tickY, x + ProductionMenuLayout.SCROLLBAR_WIDTH, tickY + REFUSED_BAR, color);
    }

    /** Whether tab row {@code row} holds a stopped product or a pattern the Crafter Feeder refuses. */
    private boolean rowIsRed(int row) {
        int first = row * layout.tabColumns();
        for (int pattern = first; pattern < first + layout.tabColumns() && pattern < menu.patternSlots(); pattern++) {
            if (stoppedAt(pattern).isPresent() || refusedAt(pattern).isPresent())
                return true;
        }
        return false;
    }

    /** One ghost item with its per-run amount. */
    private void renderEntry(GuiGraphics graphics, ProductionScreenState.EntryView view, int x, int y) {
        ItemStack stack = view.key().toStack();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        GuiGameElement.of(stack).render(graphics);
        pose.popPose();
        pose.pushPose();
        pose.translate(0, 0, COUNT_SHIFT_Z);
        pose.scale(COUNT_SCALE, COUNT_SCALE, 1.0F);
        String text = Integer.toString(view.count());
        int right = Math.round((x + ProductionMenuLayout.SLOT - 2 - ITEM_INSET) / COUNT_SCALE) - font.width(text);
        int bottom = Math.round((y + ProductionMenuLayout.SLOT - 2 - ITEM_INSET) / COUNT_SCALE) - font.lineHeight;
        graphics.drawString(font, text, right, bottom, COLOR_TEXT, true);
        pose.popPose();
    }

    private void renderSlotBackgrounds(GuiGraphics graphics) {
        for (Slot slot : menu.slots)
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    }

    private void renderTexts(GuiGraphics graphics) {
        int x = leftPos;
        int y = topPos;
        graphics.drawString(font, title, x + ProductionMenuLayout.MARGIN, y + TITLE_Y, COLOR_HEADER, false);
        graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.PRODUCTION_PATTERNS),
                x + ProductionMenuLayout.MARGIN, y + layout.patternLabelY(), COLOR_DIM, false);
        // Why patterns may be red: the Crafter Feeder at the back, at the right end of the same row (issue #34).
        if (state.feeder()) {
            Component feeder = WareworksLang.translateDirect(WareworksLang.PRODUCTION_FEEDER);
            graphics.drawString(font, feeder, x + ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN
                    - font.width(feeder), y + layout.patternLabelY(), COLOR_DIM, false);
        }
        // The stopped row takes the buffer's label row in the one configuration that has no order line at all (see
        // renderOrders): the slots below it say what they are, while the stop is the only thing here that asks for an act.
        if (!(showsStoppedRow() && layout.orderLines() == 0))
            graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.PRODUCTION_BUFFER),
                    x + ProductionMenuLayout.MARGIN, y + layout.bufferLabelY(), COLOR_DIM, false);
        renderOrders(graphics, x, y);
        graphics.drawString(font, playerInventoryTitle, x + layout.playerSlotsX() - 1, y + layout.playerLabelY(),
                COLOR_DIM, false);
    }

    /**
     * The stopped row and the production orders running at this station, newest first, cut to the rows the window has.
     * <p>
     * The <b>safety stop takes the first line</b> when it is holding something (M20): it is the only thing here that asks
     * a player to act, while an order line only reports. The window's height budget is fixed
     * ({@link ProductionMenuLayout}), so the row costs the oldest visible order its line rather than making every station's
     * window taller for a state almost no station is ever in.
     * <p>
     * With a buffer of 25 slots or more the window has <b>no</b> order line ({@link ProductionMenuLayout#orderLines()}),
     * and the stopped row then takes the <b>buffer's label row</b> instead (M20 review fix). Dropping it there was the
     * worse trade by far: the screen would name the stop in a tab's tooltip, offer no way out of it, and contradict the
     * block's own description, which teaches the red row. A buffer label is a word for slots that are self-evident.
     */
    private void renderOrders(GuiGraphics graphics, int x, int y) {
        if (showsStoppedRow())
            graphics.drawString(font, stoppedRow(), x + ProductionMenuLayout.MARGIN, y + layout.stoppedRowY(),
                    COLOR_STOPPED, false);
        if (layout.orderLines() == 0)
            return;
        int first = firstOrderLine();
        List<ProductionScreenState.OrderView> orders = shownOrders();
        if (orders.isEmpty()) {
            if (first < layout.orderLines())
                graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_NO_ORDERS),
                        x + ProductionMenuLayout.MARGIN, y + layout.orderLineY(first), COLOR_DIM, false);
            return;
        }
        for (int line = 0; line < orders.size(); line++)
            renderOrderLine(graphics, x, y + layout.orderLineY(first + line), orders.get(line));
    }

    /**
     * One order line, drawn in two parts so that the <b>state wins the row</b>: the item and its amount on the left,
     * where a long name gives way, and where the order stands on the right.
     * <p>
     * It used to be one string ({@link #orderTooltipLine}) trimmed from the end, which put the item's name in front of
     * the state and therefore cut the state first — the half that actually changes. The row is
     * {@value #ROW_WIDTH} px and fixed, while an item name is bounded by nothing at all, so a single modded name was
     * enough to leave a player reading "Some Very Long Item Name x64 - waiting…" with no state at all. This is the
     * split the terminal's own order lines have had since M20 ({@code WarehouseTerminalScreen#renderOrderLine}), down
     * to the {@link #MIN_ITEM_WIDTH} floor below which the state gives way instead. The tooltip holds both in full.
     */
    private void renderOrderLine(GuiGraphics graphics, int x, int top, ProductionScreenState.OrderView order) {
        int color = order.lostIngredients() ? COLOR_LOST : COLOR_TEXT;
        Component item = orderItemText(order);
        int floor = Math.min(font.width(item), MIN_ITEM_WIDTH);
        Component state = fitTo(orderStateText(order), Math.max(0, ROW_WIDTH - floor - TEXT_GAP));
        int stateX = Math.max(ProductionMenuLayout.MARGIN,
                ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN - font.width(state));
        graphics.drawString(font, state, x + stateX, top, color, false);
        int room = stateX - ProductionMenuLayout.MARGIN - TEXT_GAP;
        graphics.drawString(font, fitTo(item, Math.max(0, room)), x + ProductionMenuLayout.MARGIN, top, color, false);
    }

    /** "Oak Planks x128": what the order makes, the part that gives way when the row is too narrow. */
    private static Component orderItemText(ProductionScreenState.OrderView order) {
        return WareworksLang.translateDirect(WareworksLang.GOGGLES_ITEM_COUNT, order.result().toStack().getHoverName(),
                LangNumberFormat.format(order.amount()));
    }

    /**
     * Where the order stands, as the row's right-hand column shows it.
     * <p>
     * An order that ended with ingredients already handed to a machine carries the short marker the terminal's own
     * order line carries ({@code gui.terminal.order_lost}), from that one shared key: it is the same boundary on the
     * same kind of row, and one wording for both screens is one thing for a player to learn. The sentence that says
     * what it means is in this line's tooltip, in full ({@code gui.production.ingredients_lost}).
     */
    private static Component orderStateText(ProductionScreenState.OrderView order) {
        return orderStateText(order.state(), order.waitingForStep(), order.lostIngredients());
    }

    /**
     * Whether the window is showing the stopped row. It shows it whenever something is stopped: a station always has a row
     * for it, because it is the screen's own way back from the safety stop ({@link #renderOrders}).
     */
    private boolean showsStoppedRow() {
        return state.anyStopped();
    }

    /** The line the first order is drawn in: the second one while the stopped row has the first. */
    private int firstOrderLine() {
        return showsStoppedRow() && layout.orderLines() > 0 ? 1 : 0;
    }

    /**
     * The stopped row: the item itself while one product is held, their number while several are, and in both cases what
     * a click does. What it cost is one hover away, because the row is 216 px and the item's name has to fit.
     */
    private Component stoppedRow() {
        List<StoppedProduct> stopped = state.stopped();
        return fitToRow(stoppedRowText(stopped.size(),
                stopped.size() == 1 ? stopped.getFirst().key().toStack().getHoverName() : Component.empty()));
    }

    /** What the safety stop is holding of the product of pattern slot {@code pattern}, if anything (M20). */
    private Optional<StoppedProduct> stoppedAt(int pattern) {
        return state.result(pattern).flatMap(view -> state.stoppedOf(view.key()));
    }

    /**
     * One order line as a <b>tooltip</b> holds it: one whole sentence, uncut, where there is room for it. An order
     * that ended without producing everything although ingredients had already been handed over says so in full here
     * — those items are not coming back ({@code warehouse-system.md} §3.5) — while the row itself carries the short
     * marker ({@link #orderStateText}).
     */
    private Component orderTooltipLine(ProductionScreenState.OrderView order) {
        Component name = order.result().toStack().getHoverName();
        Component amount = Component.literal(LangNumberFormat.format(order.amount()));
        // An order that is waiting for an earlier step of its own chain says that rather than its own state (M20,
        // issue #4): it is nominally waiting for ingredients, but the controller hands it nothing at all until the
        // step below it is done, so "waiting for ingredients" would read as a crane that has forgotten it. It is the
        // sentence the terminal's step panel and this station's goggle line use, from the one shared key.
        Component status = WareworksLang.translateDirect(order.waitingForStep()
                ? WareworksLang.PRODUCTION_WAITING_FOR_STEP : order.state().langKey());
        return order.lostIngredients()
                ? WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER_LOST, name, amount, status)
                : WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER, name, amount, status);
    }

    /** The orders the window has room for, newest first — one fewer while the stopped row has a line. */
    private List<ProductionScreenState.OrderView> shownOrders() {
        List<ProductionScreenState.OrderView> orders = state.orders();
        int room = Math.max(0, layout.orderLines() - firstOrderLine());
        List<ProductionScreenState.OrderView> shown = new ArrayList<>(room);
        for (int line = 0; line < room && line < orders.size(); line++)
            shown.add(orders.get(orders.size() - 1 - line));
        return shown;
    }

    private Component fitToRow(Component line) {
        return fitTo(line, ROW_WIDTH);
    }

    /** {@code line} cut to {@code width} pixels if it is wider, with an ellipsis marking what was cut off. */
    private Component fitTo(Component line, int width) {
        if (font.width(line) <= width)
            return line;
        int fits = width - font.width(CommonComponents.ELLIPSIS);
        return Component.literal(fits <= 0 ? "" : font.plainSubstrByWidth(line.getString(), fits))
                .append(CommonComponents.ELLIPSIS);
    }

    // --- what the dev harness measures -------------------------------------------------------------------------------

    /**
     * Room the <b>state column</b> of an order line has when the item's name is long enough to be cut to its floor:
     * the worst case every state text of this screen has to fit, in every language ({@link #renderOrderLine}).
     * <p>
     * Public because the {@code terminal} visual run measures this screen's vocabulary against it. A translation that
     * does not fit is a defect no screenshot of one station's one order can be trusted to show — the German
     * "wartet auf einen fr&uuml;heren Schritt" was 172 px against this budget and nothing failed.
     */
    public static int orderStateBudget() {
        return ROW_WIDTH - MIN_ITEM_WIDTH - TEXT_GAP;
    }

    /** The state column's text for an order in {@code state}, as {@link #renderOrderLine} would draw it. */
    public static Component orderStateText(ProductionOrderState state, boolean waitingForStep, boolean lost) {
        Component text = WareworksLang.translateDirect(waitingForStep
                ? WareworksLang.PRODUCTION_WAITING_FOR_STEP : state.langKey());
        return lost ? WareworksLang.translateDirect(WareworksLang.TERMINAL_ORDER_LOST, text) : text;
    }

    /**
     * The stopped row as it is drawn, for {@code stopped} products — the row that leads with the item's name, so what
     * follows the name is what a long name pushes off the end ({@link #stoppedRow}).
     */
    public static Component stoppedRowText(int stopped, Component name) {
        return stopped == 1 ? WareworksLang.translateDirect(WareworksLang.PRODUCTION_STOPPED_LINE, name)
                : WareworksLang.translateDirect(WareworksLang.PRODUCTION_STOPPED_LINE_MANY,
                        LangNumberFormat.format(stopped));
    }

    @Override
    protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderForeground(graphics, mouseX, mouseY, partialTicks);
        List<Component> tooltip = tooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty())
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    }

    private List<Component> tooltipAt(int mouseX, int mouseY) {
        List<Component> tooltip = new ArrayList<>();
        int tab = tabAt(mouseX, mouseY);
        if (tab != NONE) {
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_TAB,
                    Component.literal(Integer.toString(tab + 1))));
            // A tab that names a stopped product also names the way back (M20 review fix): the tooltip that states a
            // problem must not end with "Click to edit, Right-click to clear" and nothing about lifting it.
            stoppedAt(tab).ifPresent(stopped -> {
                addStoppedLines(tooltip, stopped);
                tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_RESUME_HINT).copy()
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            });
            refusedAt(tab).ifPresent(check -> tooltip.addAll(refusalLines(check)));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_TAB_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            return tooltip;
        }
        if (isOverStoppedRow(mouseX, mouseY)) {
            tooltip.add(stoppedRow().copy().withStyle(ChatFormatting.RED));
            for (StoppedProduct stopped : state.stopped())
                addStoppedLines(tooltip, stopped);
            return tooltip;
        }
        // The arrow of a refused pattern says why it is red (issue #34); a pattern that is fine has nothing to say there.
        if (isOverArrow(mouseX, mouseY)) {
            refusedAt(selectedPattern).ifPresent(check -> tooltip.addAll(refusalLines(check)));
            return tooltip;
        }
        int cell = cellAt(mouseX, mouseY);
        if (cell != NONE) {
            state.entry(selectedPattern, cell)
                    .ifPresent(view -> tooltip.addAll(getTooltipFromItem(minecraft, view.key().toStack())));
            tooltip.add(WareworksLang.translateDirect(cell == ProductionMenuLayout.RESULT_CELL
                    ? WareworksLang.PRODUCTION_RESULT : WareworksLang.PRODUCTION_INGREDIENT).copy()
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            return tooltip;
        }
        int order = orderAt(mouseX, mouseY);
        if (order != NONE) {
            ProductionScreenState.OrderView view = shownOrders().get(order);
            tooltip.add(orderTooltipLine(view));
            if (view.missing() > 0)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_MISSING,
                        Component.literal(LangNumberFormat.format(view.missing()))).copy()
                        .withStyle(ChatFormatting.GRAY));
            if (view.lostIngredients())
                tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_INGREDIENTS_LOST).copy()
                        .withStyle(ChatFormatting.GOLD));
            if (!view.state().isFinished())
                tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_CANCEL_HINT).copy()
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        }
        return tooltip;
    }

    /**
     * Why the warehouse stopped making {@code stopped} and what it cost, for the stopped row's tooltip and for the tab of
     * that product. The cause sentence is the stock keeper's own ({@code gui.keeper.paused.*}), because it is the same
     * safety stop — a player who has read one of the two screens has read both.
     */
    private void addStoppedLines(List<Component> tooltip, StoppedProduct stopped) {
        tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_STOPPED_ITEM,
                stopped.key().toStack().getHoverName(),
                WareworksLang.translateDirect(stopped.causeKey())).copy().withStyle(ChatFormatting.RED));
        if (stopped.unrecovered() > 0)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_PAUSED_LOST,
                    Component.literal(LangNumberFormat.format(stopped.unrecovered()))).copy()
                    .withStyle(ChatFormatting.GOLD));
    }

    /**
     * The Crafter Feeder's check of pattern slot {@code pattern} if it refuses that pattern (issue #34, ADR-067): only
     * while a feeder is attached, and only for a complete pattern that is no crafting recipe it can lay.
     */
    private Optional<ProductionScreenState.PatternCheckView> refusedAt(int pattern) {
        if (!state.feeder())
            return Optional.empty();
        ProductionScreenState.PatternCheckView check = state.check(pattern);
        return check.check().refused() ? Optional.of(check) : Optional.empty();
    }

    /** Whether the Crafter Feeder refuses pattern slot {@code pattern}, as the screen draws it (dev harness). */
    public boolean isRefused(int pattern) {
        return refusedAt(pattern).isPresent();
    }

    /** The reason a refused pattern is red, in red, and what that means for it, as a tooltip shows them. */
    private static List<Component> refusalLines(ProductionScreenState.PatternCheckView check) {
        return List.of(refusalText(check).copy().withStyle(ChatFormatting.RED),
                WareworksLang.translateDirect(WareworksLang.PRODUCTION_CHECK_SKIPPED).copy()
                        .withStyle(ChatFormatting.GRAY));
    }

    /**
     * The reason the Crafter Feeder refuses a pattern, as the tooltips say it: "Not a crafting recipe", "Each cell must
     * hold exactly 1 item", or "The recipe makes Torch x4" with what the recipe really makes. Public for the dev
     * harness.
     */
    public static Component refusalText(ProductionScreenState.PatternCheckView check) {
        if (check.check() != CrafterPatternCheck.RESULT_DIFFERS)
            return WareworksLang.translateDirect(WareworksLang.crafterCheckKey(check.check()));
        Component name = check.recipeResult().map(view -> view.key().toStack().getHoverName())
                .orElse(Component.empty());
        String amount = LangNumberFormat.format(check.recipeResult().map(ProductionScreenState.EntryView::count)
                .orElse(0));
        return WareworksLang.translateDirect(WareworksLang.PRODUCTION_CHECK_RESULT_DIFFERS, name, amount);
    }

    // --- hit testing ---------------------------------------------------------------------------------------------

    /**
     * After a state push: a selection the station no longer has is moved (and its tab shown, through {@link #select});
     * an unchanged selection keeps the strip where the player scrolled it.
     */
    private void clampSelection() {
        int clamped = Math.max(0, Math.min(selectedPattern, menu.patternSlots() - 1));
        if (clamped != selectedPattern)
            select(clamped);
        else if (layout != null)
            tabScroll = layout.clampTabScroll(tabScroll);
    }

    /** The pattern cell under the mouse ({@code 0..8} grid, {@value ProductionMenuLayout#RESULT_CELL} result). */
    private int cellAt(double mouseX, double mouseY) {
        for (int cell = 0; cell < ProductionMenuLayout.PATTERN_CELLS; cell++) {
            if (isOver(mouseX, mouseY, leftPos + layout.patternCellX(cell), topPos + layout.patternCellY(cell)))
                return cell;
        }
        return NONE;
    }

    /** The pattern tab under the mouse; a tab scrolled out of view is never hit. Public for the dev harness. */
    public int tabAt(double mouseX, double mouseY) {
        for (int pattern = 0; pattern < menu.patternSlots(); pattern++) {
            if (layout.tabVisible(pattern, tabScroll)
                    && isOver(mouseX, mouseY, leftPos + layout.tabX(pattern), topPos + layout.tabY(pattern, tabScroll)))
                return pattern;
        }
        return NONE;
    }

    /** Whether the mouse is over the tab strip or its scroll bar: the mouse wheel's target. Public for the dev harness. */
    public boolean isOverTabStrip(double mouseX, double mouseY) {
        int left = leftPos + layout.tabStripX();
        int top = topPos + layout.tabStripY();
        return mouseX >= left && mouseX < left + layout.tabStripWidth() && mouseY >= top
                && mouseY < top + layout.tabStripHeight();
    }

    /** The index within {@link #shownOrders()} of the order line under the mouse. */
    private int orderAt(double mouseX, double mouseY) {
        if (!isOverTextRows(mouseX))
            return NONE;
        int first = firstOrderLine();
        int shown = shownOrders().size();
        for (int line = 0; line < shown; line++) {
            int top = topPos + layout.orderLineY(first + line);
            if (mouseY >= top && mouseY < top + ProductionMenuLayout.LABEL_HEIGHT)
                return line;
        }
        return NONE;
    }

    /** Whether the mouse is over the arrow between the selected pattern's grid and its result. */
    private boolean isOverArrow(double mouseX, double mouseY) {
        int left = leftPos + layout.arrowX();
        int top = topPos + layout.patternCellY(ProductionMenuLayout.RESULT_CELL);
        return mouseX >= left && mouseX < left + ProductionMenuLayout.ARROW_WIDTH && mouseY >= top
                && mouseY < top + ProductionMenuLayout.SLOT;
    }

    /** Whether the mouse is over the stopped row — the resume affordance of a station that has lost a batch (M20). */
    private boolean isOverStoppedRow(double mouseX, double mouseY) {
        if (!showsStoppedRow() || !isOverTextRows(mouseX))
            return false;
        int top = topPos + layout.stoppedRowY();
        return mouseY >= top && mouseY < top + ProductionMenuLayout.LABEL_HEIGHT;
    }

    /** Whether {@code mouseX} is inside the text column the stopped row and the order lines are drawn in. */
    private boolean isOverTextRows(double mouseX) {
        return mouseX >= leftPos + ProductionMenuLayout.MARGIN
                && mouseX < leftPos + ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN;
    }

    private static boolean isOver(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + ProductionMenuLayout.SLOT && mouseY >= y
                && mouseY < y + ProductionMenuLayout.SLOT;
    }
}
