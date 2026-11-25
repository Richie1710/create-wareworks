package dev.wareworks.client.gui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.packagemode.PackageModeGate;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.content.selector.SelectorTexts;
import dev.wareworks.content.station.PatternFilterCells;
import dev.wareworks.content.station.PatternFluids;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.SelectorHold;
import dev.wareworks.content.station.StoppedProduct;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.FluidCell;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.network.ProductionCancelPayload;
import dev.wareworks.network.ProductionPatternPayload;
import dev.wareworks.network.ProductionResumePayload;
import dev.wareworks.network.ProductionScreenPayload;
import dev.wareworks.network.ProductionSelectorPayload;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.gui.UIRenderHelper;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
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
 * <p>
 * <b>A recipe viewer writes into the selected tab</b> (ADR-076). JEI's recipe screen opens over this one without closing
 * the menu and hands it back, so {@link #forMenu} finds the screen, and with it the selection, while it is covered.
 * <p>
 * <b>A cell may hold a filter</b> (ADR-082). Clicking a configured List or Attribute Filter into a grid cell makes it a
 * filter cell: the server decides that from the item, and any item the filter allows pays for it, the largest stock
 * first. Such a cell is drawn as what an order would take now (the first stock match the server sent, else the first
 * item the filter names) with the filter as a small badge in its corner and a thin gold frame; its amount turns red
 * while no kind in stock is enough for one run. Its tooltip names the filter with Create's own summary, then the stock
 * matches in pick order with what a player may have of each. An order that took items for a filter says which in its
 * tooltip ({@link #usesLines}), here and at the terminal.
 * <p>
 * <b>A pattern without a result says so</b> (issue #62). While a slot holds an ingredient but no result, its empty
 * result cell and its tab show a gold "?", the arrow is gold, and the tooltips of the three say that the pattern is
 * never ordered. A filled bucket in the grid whose fluid a fluid cell of the same pattern asks for too is framed in
 * orange with a "!", and the tooltips of that cell, of the fluid cell and of the arrow say that an order pays both;
 * nothing is converted.
 * <p>
 * <b>A Recipe Selector has a row of its own</b> (ADR-085). While the station selects (a local selector or recipe
 * slips), one text row says what the line is set to, or the fault that stops it, or that the station holds; a click on
 * a held row releases the hold like Resume, and the red mark at its right end switches the selector off (refused by
 * the station while an order is in the machine). The row takes an order line, after the stopped row; where the window
 * has no line left it takes the buffer's label row, and failing that the right end of the "Patterns" row. A pattern the
 * selector's machine would not make exactly is drawn red like a refused Crafter pattern, with the reason in its
 * tooltip, and an order waiting at the selector says why in its state column, as at the terminal.
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
    /** The Recipe Selector row (ADR-085): the line's own colour, the screen's header gold. */
    private static final int COLOR_SELECTOR = COLOR_HEADER;
    /** The mark at the selector row's right end that switches the selector off, as an order line's cancel mark. */
    public static final String SELECTOR_OFF_MARK = "x";
    /** The tab strip's scroll bar track (issue #35). */
    private static final int COLOR_SCROLL_TRACK = 0xFF2B2B2B;
    /** The tab strip's scroll bar thumb. */
    private static final int COLOR_SCROLL_THUMB = 0xFFC8C8C8;
    /** The scroll bar's tick for the selected pattern's row while the wheel has scrolled it out of view. */
    private static final int COLOR_SCROLL_SELECTED = 0xFFFBDC7D;
    /** The frame of a filter cell (ADR-082): the scroll bar's gold tick, the screen's one colour for "chosen for you". */
    private static final int COLOR_FILTER_FRAME = COLOR_SCROLL_SELECTED;
    /** How large a filter cell's badge (the filter itself) is drawn in its corner. */
    private static final float FILTER_BADGE_SCALE = 0.5F;
    /** The amount of a four-digit fluid cell ("1000") is drawn smaller, so it stays inside the cell (issue #55). */
    private static final float FLUID_COUNT_SCALE = 0.6F;
    private static final int COLOR_FLUID_FRAME = 0xFF4F8FD8;
    /** The drop drawn in an empty fluid cell, so it does not read as one more grid cell (issue #62). */
    private static final int COLOR_FLUID_EMPTY = 0x904F8FD8;
    /** What stands in the empty result cell and on the tab of a pattern without a result (issue #62). */
    private static final String NO_RESULT_MARK = "?";
    /** The corner mark of a grid cell whose filled bucket a fluid cell pays a second time (issue #62). */
    private static final String PAID_TWICE_MARK = "!";
    /** The badge lies above the item and below the amount ({@link #COUNT_SHIFT_Z}). */
    private static final int FILTER_BADGE_Z = 60;

    /** Usable width of a row of text: the window without its two margins — 216 px, as in the terminal. */
    public static final int ROW_WIDTH = ProductionMenuLayout.WIDTH - 2 * ProductionMenuLayout.MARGIN;
    /** Free space kept between two texts that share a row. */
    public static final int TEXT_GAP = 4;
    /**
     * Smallest width the item half of an order line keeps, the same floor the terminal's own order lines use
     * ({@code WarehouseTerminalScreen#MIN_ITEM_WIDTH}): a name cut down to three letters names nothing at all.
     */
    public static final int MIN_ITEM_WIDTH = 54;

    /** The screen laid out last, which {@link #forMenu} finds while a recipe viewer's screen covers it. */
    private static WeakReference<WarehouseProductionScreen> laidOut = new WeakReference<>(null);

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
        laidOut = new WeakReference<>(this);
        // A (re)opened or resized window always starts with the selected tab in view.
        select(selectedPattern);
        if (menu.station() == null && minecraft != null && minecraft.player != null)
            minecraft.player.closeContainer(); // the block entity is gone: nothing to edit
    }

    /**
     * The screen of menu {@code containerId}, shown or covered by a recipe viewer's screen, which opens over this one
     * without closing the menu and hands it back when it closes.
     */
    public static Optional<WarehouseProductionScreen> forMenu(int containerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WarehouseProductionScreen shown)
            return shown.getMenu().containerId == containerId ? Optional.of(shown) : Optional.empty();
        WarehouseProductionScreen covered = laidOut.get();
        if (covered == null || minecraft.player == null || minecraft.player.containerMenu != covered.getMenu()
                || covered.getMenu().containerId != containerId)
            return Optional.empty();
        return Optional.of(covered);
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
        int fluidCell = fluidCellAt(mouseX, mouseY);
        if (fluidCell != NONE) {
            clickFluidCell(fluidCell, button);
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
        // The Recipe Selector row (ADR-085): its red mark switches the selector off, a click on a held row releases the
        // hold as Resume does. Tested before the order lines, because the row takes one of their lines.
        if (button == 0 && isOverSelectorOffMark(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new ProductionSelectorPayload(menu.containerId,
                    ProductionSelectorPayload.Action.SWITCH_OFF));
            playUiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
            return true;
        }
        if (button == 0 && state.selector().held() && isOverSelectorRow(mouseX, mouseY)) {
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
        int fluidCell = fluidCellAt(mouseX, mouseY);
        if (fluidCell != NONE) {
            Optional<FluidCell> fluid = state.fluid(selectedPattern, fluidCell);
            if (fluid.isPresent()) {
                send(ProductionPatternPayload.setFluid(menu.containerId, selectedPattern, fluidCell,
                        fluid.get().fluid(), fluid.get().stepped((int) Math.signum(scrollY), fluidWheelStep())));
                return true;
            }
        }
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

    /** Millibuckets one wheel notch changes a fluid cell by: 50, with Shift 250, with Ctrl 1000 (issue #55). */
    public int fluidWheelStep() {
        if (hasControlDown())
            return FluidCell.STEP_LARGE;
        return hasShiftDown() ? FluidCell.STEP_MEDIUM : FluidCell.STEP_SMALL;
    }

    /**
     * A click on fluid cell {@code cell} of the selected pattern (issue #55, ADR-089): a left click with a filled bucket
     * (or any item that holds a fluid) sets the cell to that fluid and keeps the amount when it is the same fluid, else
     * takes the container's content; a right click, or a left click with an empty hand, clears the cell. Nothing is taken
     * from the cursor: the cell is a ghost like every pattern cell.
     */
    private void clickFluidCell(int cell, int button) {
        if (selectedPattern < 0 || selectedPattern >= menu.patternSlots())
            return;
        Optional<FluidCell> current = state.fluid(selectedPattern, cell);
        if (button == 1 || (button == 0 && menu.getCarried().isEmpty())) {
            if (current.isPresent())
                send(ProductionPatternPayload.clearFluid(menu.containerId, selectedPattern, cell));
            return;
        }
        if (button != 0)
            return;
        Optional<PatternFluids.Contained> held = PatternFluids.contained(menu.getCarried());
        if (held.isEmpty())
            return;
        int amount = current.filter(existing -> existing.fluid().equals(held.get().fluid()))
                .map(FluidCell::millibuckets).orElse(FluidCell.clampMb(held.get().millibuckets()));
        send(ProductionPatternPayload.setFluid(menu.containerId, selectedPattern, cell, held.get().fluid(), amount));
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
            entry.ifPresent(view -> renderEntry(graphics, selectedPattern, view, cellX + ITEM_INSET,
                    cellY + ITEM_INSET));
            if (entry.isPresent() && entry.get().filter())
                graphics.renderOutline(cellX, cellY, ProductionMenuLayout.SLOT, ProductionMenuLayout.SLOT,
                        COLOR_FILTER_FRAME);
            if (state.paidTwice(selectedPattern, cell).isPresent())
                renderPaidTwice(graphics, cellX, cellY);
        }
        renderFluidCells(graphics, mouseX, mouseY);
        boolean noResult = state.lacksResult(selectedPattern);
        if (noResult) {
            int cellX = leftPos + layout.patternCellX(ProductionMenuLayout.RESULT_CELL);
            int cellY = topPos + layout.patternCellY(ProductionMenuLayout.RESULT_CELL);
            renderNoResultMark(graphics, cellX, cellY);
            graphics.renderOutline(cellX, cellY, ProductionMenuLayout.SLOT, ProductionMenuLayout.SLOT, COLOR_LOST);
        }
        // The arrow between the ingredients and the result: this is a pattern, not a crafting grid. A pattern the Crafter
        // Feeder refuses gets a red frame round grid and result and a red cross where the arrow was (issue #34).
        boolean refused = isRed(selectedPattern);
        String arrow = refused ? REFUSED_ARROW : ">";
        int arrowX = leftPos + layout.arrowX() + ProductionMenuLayout.ARROW_WIDTH / 2 - font.width(arrow) / 2;
        int arrowY = topPos + layout.patternCellY(ProductionMenuLayout.RESULT_CELL)
                + ProductionMenuLayout.SLOT / 2 - font.lineHeight / 2;
        graphics.drawString(font, arrow, arrowX, arrowY,
                refused ? COLOR_REFUSED : noResult ? COLOR_LOST : COLOR_ARROW, false);
        if (refused) {
            int left = leftPos + layout.patternCellX(0) - 1;
            int top = topPos + layout.patternCellY(0) - 1;
            int right = leftPos + layout.patternCellX(ProductionMenuLayout.RESULT_CELL) + ProductionMenuLayout.SLOT + 1;
            int bottom = topPos + layout.patternCellY(ProductionMenuLayout.PATTERN_CELLS - 2)
                    + ProductionMenuLayout.SLOT + 1;
            graphics.renderOutline(left, top, right - left, bottom - top, COLOR_REFUSED);
        }
    }

    /** The gold "?" of a pattern without a result, centred in the slot at {@code x}, {@code y} (issue #62). */
    private void renderNoResultMark(GuiGraphics graphics, int x, int y) {
        graphics.drawString(font, NO_RESULT_MARK, x + (ProductionMenuLayout.SLOT - font.width(NO_RESULT_MARK)) / 2 + 1,
                y + (ProductionMenuLayout.SLOT - font.lineHeight) / 2 + 1, COLOR_LOST, true);
    }

    /** The orange frame and the "!" in the top-right corner of a bucket cell that is paid twice (issue #62). */
    private void renderPaidTwice(GuiGraphics graphics, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, COUNT_SHIFT_Z);
        graphics.renderOutline(x, y, ProductionMenuLayout.SLOT, ProductionMenuLayout.SLOT, COLOR_LOST);
        graphics.drawString(font, PAID_TWICE_MARK, x + ProductionMenuLayout.SLOT - font.width(PAID_TWICE_MARK) - 1,
                y + 2, COLOR_LOST, true);
        pose.popPose();
    }

    /** A small drop: what an empty fluid cell shows (issue #62). */
    private static void renderEmptyDrop(GuiGraphics graphics, int x, int y) {
        // Left and right edge of each pixel row of the drop, inside the 18 px cell.
        int[][] rows = {{8, 10}, {8, 10}, {7, 11}, {6, 12}, {5, 13}, {5, 13}, {5, 13}, {6, 12}, {7, 11}};
        for (int row = 0; row < rows.length; row++)
            graphics.fill(x + rows[row][0], y + 4 + row, x + rows[row][1], y + 5 + row, COLOR_FLUID_EMPTY);
    }

    /**
     * The two fluid cells of the selected pattern (issue #55): the fluid's own sprite in its colour with the amount in
     * millibuckets, in their column left of the arrow (issue #62). An empty cell shows a drop; an unknown fluid is drawn
     * as a red question mark.
     */
    private void renderFluidCells(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = fluidCellAt(mouseX, mouseY);
        for (int cell = 0; cell < ProductionPatterns.FLUID_CELLS; cell++) {
            int cellX = leftPos + layout.fluidCellX(cell);
            int cellY = topPos + layout.fluidCellY(cell);
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, cellX, cellY);
            if (hovered == cell)
                graphics.fill(cellX, cellY, cellX + ProductionMenuLayout.SLOT, cellY + ProductionMenuLayout.SLOT,
                        COLOR_CELL_HOVER);
            Optional<FluidCell> fluid = state.fluid(selectedPattern, cell);
            if (fluid.isEmpty()) {
                renderEmptyDrop(graphics, cellX, cellY);
                continue;
            }
            renderFluid(graphics, fluid.get(), cellX + ITEM_INSET, cellY + ITEM_INSET);
            graphics.renderOutline(cellX, cellY, ProductionMenuLayout.SLOT, ProductionMenuLayout.SLOT,
                    COLOR_FLUID_FRAME);
        }
    }

    /** One fluid sprite with its amount, drawn like an item cell's count (issue #55). */
    private void renderFluid(GuiGraphics graphics, FluidCell cell, int x, int y) {
        Optional<FluidStack> stack = PatternFluids.stackOf(cell.fluid(), cell.millibuckets());
        if (stack.isEmpty()) {
            graphics.drawString(font, "?", x + 5, y + 4, COLOR_REFUSED, false);
        } else {
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(stack.get().getFluid());
            ResourceLocation still = extensions.getStillTexture(stack.get());
            if (still != null && minecraft != null) {
                TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
                int tint = extensions.getTintColor(stack.get());
                float alpha = (tint >>> 24 & 0xFF) / 255.0F;
                graphics.blit(x, y, 0, 16, 16, sprite, (tint >> 16 & 0xFF) / 255.0F, (tint >> 8 & 0xFF) / 255.0F,
                        (tint & 0xFF) / 255.0F, alpha <= 0.0F ? 1.0F : alpha);
            }
        }
        String text = Integer.toString(cell.millibuckets());
        float scale = text.length() > 3 ? FLUID_COUNT_SCALE : COUNT_SCALE;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, COUNT_SHIFT_Z);
        pose.scale(scale, scale, 1.0F);
        int right = Math.round((x + ProductionMenuLayout.SLOT - 2 - ITEM_INSET) / scale) - font.width(text);
        int bottom = Math.round((y + ProductionMenuLayout.SLOT - 2 - ITEM_INSET) / scale) - font.lineHeight;
        graphics.drawString(font, text, right, bottom, COLOR_TEXT, true);
        pose.popPose();
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
            int tabPattern = pattern;
            state.result(pattern).ifPresent(view -> renderEntry(graphics, tabPattern, view, tabX + ITEM_INSET,
                    tabY + ITEM_INSET));
            // A pattern with ingredients but no result has nothing to show on its tab but the question (issue #62).
            if (state.lacksResult(pattern))
                renderNoResultMark(graphics, tabX, tabY);
            // A pattern the Crafter Feeder refuses is marked under its own tab, whichever pattern is selected, as a
            // stopped product is (issue #34); a bar rather than a tint, so a tab can say both at once.
            if (isRed(pattern))
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
            if (stoppedAt(pattern).isPresent() || isRed(pattern))
                return true;
        }
        return false;
    }

    /**
     * One ghost item with its per-run amount. A filter cell (ADR-082) is drawn as what an order would take now, with the
     * filter as a badge in its top-left corner, and its amount in red while no kind in stock pays one run; without a
     * view from the server it shows the filter itself.
     */
    private void renderEntry(GuiGraphics graphics, int pattern, ProductionScreenState.EntryView view, int x, int y) {
        Optional<ProductionScreenState.ChoiceView> choice = view.filter()
                ? state.choice(pattern, view.key()) : Optional.empty();
        Optional<ItemKey> shown = choice.flatMap(ProductionScreenState.ChoiceView::representative);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        GuiGameElement.of(shown.orElse(view.key()).toStack()).render(graphics);
        pose.popPose();
        if (shown.isPresent()) {
            pose.pushPose();
            pose.translate(x, y, FILTER_BADGE_Z);
            GuiGameElement.of(view.key().toStack()).scale(FILTER_BADGE_SCALE).render(graphics);
            pose.popPose();
        }
        boolean shortOfRun = choice.map(ProductionScreenState.ChoiceView::shortOfRun).orElse(false);
        pose.pushPose();
        pose.translate(0, 0, COUNT_SHIFT_Z);
        pose.scale(COUNT_SCALE, COUNT_SCALE, 1.0F);
        String text = Integer.toString(view.count());
        int right = Math.round((x + ProductionMenuLayout.SLOT - 2 - ITEM_INSET) / COUNT_SCALE) - font.width(text);
        int bottom = Math.round((y + ProductionMenuLayout.SLOT - 2 - ITEM_INSET) / COUNT_SCALE) - font.lineHeight;
        graphics.drawString(font, text, right, bottom, shortOfRun ? WarehouseTerminalScreen.COLOR_ERROR : COLOR_TEXT,
                true);
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
        // Why patterns may be red: the Crafter Feeder at the back, at the right end of the same row (issue #34). A
        // Recipe Selector row pushed up into this row takes its place (ADR-085).
        if (state.feeder() && selectorRowPlace() != SelectorRowPlace.PATTERN_LABEL) {
            Component feeder = WareworksLang.translateDirect(WareworksLang.PRODUCTION_FEEDER);
            graphics.drawString(font, feeder, x + ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN
                    - font.width(feeder), y + layout.patternLabelY(), COLOR_DIM, false);
        }
        // The stopped row takes the buffer's label row in the one configuration that has no order line at all (see
        // renderOrders): the slots below it say what they are, while the stop is the only thing here that asks for an act.
        if (!(showsStoppedRow() && layout.orderLines() == 0) && selectorRowPlace() != SelectorRowPlace.BUFFER_LABEL)
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
        renderSelectorRow(graphics);
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
        graphics.drawString(font, state, x + stateX, top, selectorWaitColor(order, color), false);
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
        if (order.selectorWait() != SelectorWait.NONE)
            return SelectorTexts.waitText(order.selectorWait(), order.selectorItem());
        return orderStateText(order.state(), order.waitingForStep(), order.lostIngredients());
    }

    /**
     * The colour of an order's state column (ADR-085): a selector fault or hold in the error red, a queue behind another
     * result in grey, anything else in {@code otherwise}. The terminal colours its rows the same way.
     */
    public static int selectorWaitColor(ProductionScreenState.OrderView order, int otherwise) {
        if (order.selectorWait() == SelectorWait.NONE)
            return otherwise;
        if (order.selectorFault())
            return WarehouseTerminalScreen.COLOR_ERROR;
        return order.selectorWait().queues() ? COLOR_DIM : otherwise;
    }

    /**
     * Whether the window is showing the stopped row. It shows it whenever something is stopped: a station always has a row
     * for it, because it is the screen's own way back from the safety stop ({@link #renderOrders}).
     */
    private boolean showsStoppedRow() {
        return state.anyStopped();
    }

    /**
     * The line the first order is drawn in: past the stopped row and the Recipe Selector row, each of which takes an
     * order line while there is one left.
     */
    private int firstOrderLine() {
        int taken = (showsStoppedRow() ? 1 : 0) + (state.selector().on() ? 1 : 0);
        return Math.min(taken, layout.orderLines());
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
        Component status = order.selectorWait() != SelectorWait.NONE
                ? SelectorTexts.waitText(order.selectorWait(), order.selectorItem())
                : WareworksLang.translateDirect(order.waitingForStep()
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
            tooltip.addAll(selectorRefusalLines(tab));
            tooltip.addAll(noResultLines(tab));
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
        if (isOverSelectorOffMark(mouseX, mouseY)) {
            tooltip.add(WareworksLang.translateDirect(state.selector().mode() == SelectorMode.SLIPS
                    ? WareworksLang.PRODUCTION_SELECTOR_SWITCH_OFF_SLIPS : WareworksLang.PRODUCTION_SELECTOR_SWITCH_OFF)
                    .copy().withStyle(ChatFormatting.RED));
            if (state.selector().engaged())
                tooltip.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_SWITCH_OFF_ENGAGED)));
            return tooltip;
        }
        if (isOverSelectorRow(mouseX, mouseY))
            return selectorRowTooltip(state.selector());
        // The arrow of a refused pattern says why it is red (issue #34), and of a pattern without a result why it is
        // gold (issue #62), with the double payment warning; a pattern that is fine has nothing to say there.
        if (isOverArrow(mouseX, mouseY)) {
            refusedAt(selectedPattern).ifPresent(check -> tooltip.addAll(refusalLines(check)));
            tooltip.addAll(selectorRefusalLines(selectedPattern));
            tooltip.addAll(noResultLines(selectedPattern));
            for (int cell = 0; cell < ProductionPatterns.GRID_CELLS; cell++) {
                if (state.paidTwice(selectedPattern, cell).isPresent()) {
                    tooltip.addAll(paidTwiceLines(WareworksLang.PRODUCTION_FLUID_ALSO_IN_GRID));
                    break;
                }
            }
            return tooltip;
        }
        int fluidCell = fluidCellAt(mouseX, mouseY);
        if (fluidCell != NONE) {
            Optional<FluidCell> shown = state.fluid(selectedPattern, fluidCell);
            tooltip.addAll(fluidLines(shown, refusedAt(selectedPattern), PackageModeGate.enabledOnClient()));
            if (shown.filter(fluid -> state.fluidAlsoInGrid(selectedPattern, fluid.fluid())).isPresent())
                tooltip.addAll(paidTwiceLines(WareworksLang.PRODUCTION_FLUID_ALSO_IN_GRID));
            return tooltip;
        }
        int cell = cellAt(mouseX, mouseY);
        if (cell != NONE) {
            Optional<ProductionScreenState.EntryView> entry = state.entry(selectedPattern, cell);
            entry.ifPresent(view -> tooltip.addAll(getTooltipFromItem(minecraft, view.key().toStack())));
            entry.filter(ProductionScreenState.EntryView::filter).ifPresent(view -> tooltip.addAll(
                    filterLines(view, state.choice(selectedPattern, view.key()))));
            tooltip.add(WareworksLang.translateDirect(cell == ProductionMenuLayout.RESULT_CELL
                    ? WareworksLang.PRODUCTION_RESULT : WareworksLang.PRODUCTION_INGREDIENT).copy()
                    .withStyle(ChatFormatting.GRAY));
            if (cell == ProductionMenuLayout.RESULT_CELL)
                tooltip.addAll(noResultLines(selectedPattern));
            if (state.paidTwice(selectedPattern, cell).isPresent())
                tooltip.addAll(paidTwiceLines(WareworksLang.PRODUCTION_FLUID_PAID_TWICE));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            // An empty grid cell also teaches that a filter may go in (ADR-082).
            if (entry.isEmpty() && cell != ProductionMenuLayout.RESULT_CELL)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_HINT).copy()
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
            tooltip.addAll(usesLines(view));
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
     * A fluid cell's tooltip (issue #55, ADR-089): the fluid's name, that it is a fluid ingredient and how much one run
     * takes; how many runs one bucket serves, or in gold that the amount does not divide 1000 mB and a rest stays in the
     * player's machine; that fluid reaches the station only in whole filled buckets; the reason in red when a Crafter
     * Feeder skips the pattern or package mode makes nothing; and what a click, the wheel and a right click do. An empty
     * cell says only what sets it. Public for the dev harness.
     *
     * @param refused the Crafter Feeder's refusal of the pattern, if it has one
     */
    public static List<Component> fluidLines(Optional<FluidCell> cell,
            Optional<ProductionScreenState.PatternCheckView> refused, boolean packageMode) {
        List<Component> lines = new ArrayList<>();
        if (cell.isEmpty()) {
            lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_CELL).copy()
                    .withStyle(ChatFormatting.GRAY));
            lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_SET_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            return lines;
        }
        FluidCell fluid = cell.get();
        Optional<FluidStack> stack = PatternFluids.stackOf(fluid.fluid(), fluid.millibuckets());
        if (stack.isPresent()) {
            lines.add(stack.get().getHoverName());
        } else {
            lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_UNKNOWN).copy()
                    .withStyle(ChatFormatting.RED));
            lines.add(Component.literal(fluid.fluid()).withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_CELL).copy()
                .withStyle(ChatFormatting.GRAY));
        lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_AMOUNT,
                LangNumberFormat.format(fluid.millibuckets())).copy().withStyle(ChatFormatting.WHITE));
        if (fluid.dividesBucket())
            lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_BUCKETS,
                    LangNumberFormat.format(FluidCell.BUCKET_MB / fluid.millibuckets()))));
        else
            lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_REST).copy()
                    .withStyle(ChatFormatting.GOLD));
        lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_WHOLE)));
        refused.ifPresent(check -> lines.addAll(refusalLines(check)));
        if (packageMode)
            lines.add(WareworksLang.translateDirect(WareworksLang.GOGGLES_PACKAGE_MODE_NO_PRODUCTION).copy()
                    .withStyle(ChatFormatting.RED));
        lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_WHEEL_HINT).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_CLEAR_HINT).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** Why pattern slot {@code pattern} is never ordered, while it has ingredients but no result (issue #62). */
    private List<Component> noResultLines(int pattern) {
        if (!state.lacksResult(pattern))
            return List.of();
        return List.of(WareworksLang.translateDirect(WareworksLang.PRODUCTION_NO_RESULT).copy()
                        .withStyle(ChatFormatting.GOLD),
                gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_NO_RESULT_HINT)));
    }

    /** The double payment warning (issue #62): {@code key} says it for what is hovered, then what to do. */
    private static List<Component> paidTwiceLines(String key) {
        return List.of(WareworksLang.translateDirect(key).copy().withStyle(ChatFormatting.GOLD),
                gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FLUID_TWICE_HINT)));
    }

    /**
     * What a filter cell's tooltip says under the filter's own name and summary (ADR-082, {@code warehouse-system.md}
     * §3.5.8): that any allowed item pays, the stock matches in the order an order would pick them with what a player
     * may have of each (or that nothing in stock matches), and, when they apply, that no kind alone pays one run, that a
     * deny filter allows anything else, and that a Crafter Feeder narrows the matches. Without a view from the server
     * only the first line and the deny warning are known. Public for the dev harness.
     */
    public static List<Component> filterLines(ProductionScreenState.EntryView entry,
            Optional<ProductionScreenState.ChoiceView> choice) {
        List<Component> lines = new ArrayList<>();
        lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_CELL)));
        if (choice.isPresent()) {
            ProductionScreenState.ChoiceView view = choice.get();
            if (view.matches().isEmpty()) {
                lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_NONE).copy()
                        .withStyle(ChatFormatting.GOLD));
            } else {
                lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_MATCHES)));
                for (ProductionScreenState.MatchView match : view.matches())
                    lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_MATCH,
                            match.key().toStack().getHoverName(), LangNumberFormat.format(match.available()))));
                if (view.more() > 0)
                    lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_MORE,
                            LangNumberFormat.format(view.more()))));
                if (view.shortOfRun())
                    lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_SHORT).copy()
                            .withStyle(ChatFormatting.GOLD));
            }
        }
        if (choice.map(ProductionScreenState.ChoiceView::deny).orElseGet(() -> PatternFilterCells.denies(entry.key())))
            lines.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_DENY).copy()
                    .withStyle(ChatFormatting.GOLD));
        if (choice.map(ProductionScreenState.ChoiceView::crafterLimited).orElse(false))
            lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_FILTER_CRAFTER)));
        return lines;
    }

    /**
     * The tooltip lines of an order a filter chose items for (ADR-082): "Uses Birch Planks x8" per concrete item, and how
     * many more there are. Nothing for an order of exact ingredients. Shared with the terminal's order lines, so the two
     * screens name the same items the same way.
     */
    public static List<Component> usesLines(ProductionScreenState.OrderView order) {
        if (order.uses().isEmpty())
            return List.of();
        List<Component> lines = new ArrayList<>(order.uses().size() + 1);
        for (ProductionScreenState.EntryView use : order.uses())
            lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER_USES,
                    WareworksLang.translateDirect(WareworksLang.GOGGLES_ITEM_COUNT, use.key().toStack().getHoverName(),
                            LangNumberFormat.format(use.count())))));
        if (order.usesMore() > 0)
            lines.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER_USES_MORE,
                    LangNumberFormat.format(order.usesMore()))));
        return lines;
    }

    private static Component gray(Component line) {
        return line.copy().withStyle(ChatFormatting.GRAY);
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

    /** Whether pattern slot {@code pattern} is drawn red: refused by the Crafter Feeder or by the selector check. */
    public boolean isRed(int pattern) {
        return refusedAt(pattern).isPresent() || state.selectorRefusal(pattern).isPresent();
    }

    /** Why the Recipe Selector's machine would not make pattern slot {@code pattern} exactly, and what follows. */
    private List<Component> selectorRefusalLines(int pattern) {
        Optional<SelectorRecipeCheck.Verdict> verdict = state.selectorRefusal(pattern);
        if (verdict.isEmpty())
            return List.of();
        return List.of(SelectorTexts.checkText(verdict.get(), state.selector().kind()).copy()
                        .withStyle(ChatFormatting.RED),
                gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_CHECK_SKIPPED)));
    }

    // --- the Recipe Selector row (ADR-085) ---------------------------------------------------------------------------

    /** Where the Recipe Selector row is drawn; {@code NOWHERE} for a station without a selector. */
    private enum SelectorRowPlace {
        NOWHERE, ORDER_LINE, BUFFER_LABEL, PATTERN_LABEL
    }

    /**
     * Where the selector row goes: the order line after the stopped row while there is one; else the buffer's label row
     * while the stopped row does not have it; else the right end of the "Patterns" row.
     */
    private SelectorRowPlace selectorRowPlace() {
        if (layout == null || !state.selector().on())
            return SelectorRowPlace.NOWHERE;
        int line = showsStoppedRow() ? 1 : 0;
        if (line < layout.orderLines())
            return SelectorRowPlace.ORDER_LINE;
        if (!(showsStoppedRow() && layout.orderLines() == 0))
            return SelectorRowPlace.BUFFER_LABEL;
        return SelectorRowPlace.PATTERN_LABEL;
    }

    /** Top of the selector row relative to the window, or -1 without one. Public for the dev harness. */
    public int selectorRowY() {
        return switch (selectorRowPlace()) {
            case ORDER_LINE -> layout.orderLineY(showsStoppedRow() ? 1 : 0);
            case BUFFER_LABEL -> layout.bufferLabelY();
            case PATTERN_LABEL -> layout.patternLabelY();
            case NOWHERE -> -1;
        };
    }

    /** Left edge of the selector row's text, relative to the window. */
    private int selectorRowX() {
        if (selectorRowPlace() != SelectorRowPlace.PATTERN_LABEL)
            return ProductionMenuLayout.MARGIN;
        return ProductionMenuLayout.MARGIN
                + font.width(WareworksLang.translateDirect(WareworksLang.PRODUCTION_PATTERNS)) + TEXT_GAP;
    }

    /** Left edge of the selector row's red mark, relative to the window. Public for the dev harness. */
    public int selectorOffMarkX() {
        return ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN - font.width(SELECTOR_OFF_MARK);
    }

    /** What the selector row says: the hold, the missing selector, the fault, or what the line is set to. */
    public Component selectorRowText() {
        ProductionScreenState.SelectorView view = state.selector();
        if (view.held())
            return WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_HOLD);
        if (view.mode() == SelectorMode.LOCAL && view.linked().isEmpty())
            return WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_ROW_MISSING);
        if (view.fault().isPresent())
            return SelectorTexts.waitText(view.fault().get(), Optional.empty());
        return selectionText(view);
    }

    /** "Recipe Selector set to Deepslate Tile Stairs" or "Recipe slips on: making ...": what the line is set to. */
    private static Component selectionText(ProductionScreenState.SelectorView view) {
        Component selection = view.selection().map(key -> key.toStack().getHoverName())
                .orElseGet(() -> WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_NOTHING_YET));
        return WareworksLang.translateDirect(view.mode() == SelectorMode.SLIPS
                ? WareworksLang.PRODUCTION_SELECTOR_ROW_SLIPS : WareworksLang.PRODUCTION_SELECTOR_ROW_LOCAL, selection);
    }

    /** Whether the selector row reads as something to act on (red) rather than the line's state (gold). */
    private boolean selectorRowIsRed() {
        ProductionScreenState.SelectorView view = state.selector();
        return view.held() || view.fault().isPresent()
                || view.mode() == SelectorMode.LOCAL && view.linked().isEmpty();
    }

    private void renderSelectorRow(GuiGraphics graphics) {
        int rowY = selectorRowY();
        if (rowY < 0)
            return;
        int left = selectorRowX();
        int markX = selectorOffMarkX();
        Component text = fitTo(selectorRowText(), Math.max(0, markX - TEXT_GAP - left));
        graphics.drawString(font, text, leftPos + left, topPos + rowY,
                selectorRowIsRed() ? COLOR_STOPPED : COLOR_SELECTOR, false);
        graphics.drawString(font, SELECTOR_OFF_MARK, leftPos + markX, topPos + rowY,
                state.selector().engaged() ? COLOR_DIM : WarehouseTerminalScreen.COLOR_ERROR, false);
    }

    /** Whether the mouse is over the selector row's text (not its red mark). Public for the dev harness. */
    public boolean isOverSelectorRow(double mouseX, double mouseY) {
        int rowY = selectorRowY();
        if (rowY < 0)
            return false;
        int top = topPos + rowY;
        return mouseY >= top && mouseY < top + ProductionMenuLayout.LABEL_HEIGHT && mouseX >= leftPos + selectorRowX()
                && mouseX < leftPos + selectorOffMarkX() - TEXT_GAP / 2;
    }

    /** Whether the mouse is over the selector row's red mark. Public for the dev harness. */
    public boolean isOverSelectorOffMark(double mouseX, double mouseY) {
        int rowY = selectorRowY();
        if (rowY < 0)
            return false;
        int top = topPos + rowY;
        int left = leftPos + selectorOffMarkX() - TEXT_GAP / 2;
        return mouseY >= top && mouseY < top + ProductionMenuLayout.LABEL_HEIGHT && mouseX >= left
                && mouseX < leftPos + ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN + TEXT_GAP / 2;
    }

    /**
     * The selector row's tooltip: what the line is set to, what a hold is about and how to release it, the fault, where
     * the selector and the return input are, the machine, how often the line switched and the probe of a far factory.
     */
    public static List<Component> selectorRowTooltip(ProductionScreenState.SelectorView view) {
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(selectionText(view).copy().withStyle(ChatFormatting.GOLD));
        if (view.held()) {
            Component item = view.holdItem().map(key -> key.toStack().getHoverName()).orElse(Component.empty());
            tooltip.add(WareworksLang.translateDirect(view.hold().get() == SelectorHold.Cause.UNEXPECTED_RETURN
                    ? WareworksLang.PRODUCTION_SELECTOR_HOLD_RETURN : WareworksLang.PRODUCTION_SELECTOR_HOLD_LOST,
                    item).copy().withStyle(ChatFormatting.RED));
            if (view.hold().get() == SelectorHold.Cause.UNEXPECTED_RETURN)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_HOLD_TAKE_OUT).copy()
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_HOLD_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        }
        if (view.mode() == SelectorMode.LOCAL && view.linked().isEmpty())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_ROW_MISSING).copy()
                    .withStyle(ChatFormatting.RED));
        view.fault().ifPresent(fault -> tooltip.add(SelectorTexts.waitText(fault, Optional.empty()).copy()
                .withStyle(ChatFormatting.RED)));
        view.linked().ifPresent(pos -> tooltip.add(gray(WareworksLang.translateDirect(
                WareworksLang.PRODUCTION_SELECTOR_AT, SelectorTexts.position(pos)))));
        view.kind().ifPresent(kind -> tooltip.add(gray(WareworksLang.translateDirect(
                WareworksLang.PRODUCTION_SELECTOR_MACHINE, SelectorTexts.machineName(kind)))));
        view.returnInput().ifPresent(pos -> tooltip.add(gray(WareworksLang.translateDirect(
                WareworksLang.PRODUCTION_SELECTOR_RETURN_INPUT, SelectorTexts.position(pos)))));
        tooltip.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_SWITCHES,
                LangNumberFormat.format(view.epoch()))));
        if (view.mode() == SelectorMode.SLIPS && !view.slipsTrusted())
            tooltip.add(gray(WareworksLang.translateDirect(WareworksLang.PRODUCTION_SELECTOR_UNTRUSTED)));
        return tooltip;
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

    /** The fluid cell (0 or 1) under the mouse, or {@link #NONE} (issue #55). Public for the dev harness. */
    public int fluidCellAt(double mouseX, double mouseY) {
        for (int cell = 0; cell < ProductionPatterns.FLUID_CELLS; cell++) {
            if (isOver(mouseX, mouseY, leftPos + layout.fluidCellX(cell), topPos + layout.fluidCellY(cell)))
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
