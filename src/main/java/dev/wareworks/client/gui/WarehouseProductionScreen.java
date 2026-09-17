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
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.network.ProductionCancelPayload;
import dev.wareworks.network.ProductionPatternPayload;
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
    private static final int COLOR_CELL_HOVER = 0x60FFFFFF;
    private static final int COLOR_TAB_SELECTED = 0x80FBDC7D;

    private ProductionScreenState state = ProductionScreenState.NONE;
    private ProductionMenuLayout layout;
    private int selectedPattern;

    public WarehouseProductionScreen(ProductionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        layout = menu.layout();
        setWindowSize(ProductionMenuLayout.WIDTH, layout.height());
        super.init();
        clampSelection();
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

    // --- interaction ---------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int tab = tabAt(mouseX, mouseY);
        if (tab != NONE) {
            if (button == 1) {
                send(ProductionPatternPayload.clearPattern(menu.containerId, tab));
                return true;
            }
            selectedPattern = tab;
            playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.4F);
            return true;
        }
        int cell = cellAt(mouseX, mouseY);
        if (cell != NONE) {
            setEntry(selectedPattern, cell, menu.getCarried());
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

    /** Scrolling over a filled pattern cell changes that cell's amount. An empty cell has no amount to change. */
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
            state.entry(selectedPattern, cell)
                    .ifPresent(view -> renderEntry(graphics, view, cellX + ITEM_INSET, cellY + ITEM_INSET));
        }
        // The arrow between the grid and the result: this is a pattern, not a crafting grid.
        int arrowX = leftPos + layout.arrowX() + ProductionMenuLayout.ARROW_WIDTH / 2 - font.width(">") / 2;
        int arrowY = topPos + layout.patternCellY(ProductionMenuLayout.RESULT_CELL)
                + ProductionMenuLayout.SLOT / 2 - font.lineHeight / 2;
        graphics.drawString(font, ">", arrowX, arrowY, COLOR_ARROW, false);
    }

    /** One tab per pattern slot, showing that pattern's result. */
    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = tabAt(mouseX, mouseY);
        for (int pattern = 0; pattern < menu.patternSlots(); pattern++) {
            int tabX = leftPos + layout.tabX(pattern);
            int tabY = topPos + layout.tabY(pattern);
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, tabX, tabY);
            if (pattern == selectedPattern)
                graphics.fill(tabX, tabY, tabX + ProductionMenuLayout.SLOT, tabY + ProductionMenuLayout.SLOT,
                        COLOR_TAB_SELECTED);
            else if (hovered == pattern)
                graphics.fill(tabX, tabY, tabX + ProductionMenuLayout.SLOT, tabY + ProductionMenuLayout.SLOT,
                        COLOR_CELL_HOVER);
            state.result(pattern).ifPresent(view -> renderEntry(graphics, view, tabX + ITEM_INSET, tabY + ITEM_INSET));
        }
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
        graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.PRODUCTION_BUFFER),
                x + ProductionMenuLayout.MARGIN, y + layout.bufferLabelY(), COLOR_DIM, false);
        renderOrders(graphics, x, y);
        graphics.drawString(font, playerInventoryTitle, x + layout.playerSlotsX() - 1, y + layout.playerLabelY(),
                COLOR_DIM, false);
    }

    /** The production orders running at this station, newest first, cut to the rows the window has. */
    private void renderOrders(GuiGraphics graphics, int x, int y) {
        if (layout.orderLines() == 0)
            return;
        List<ProductionScreenState.OrderView> orders = shownOrders();
        if (orders.isEmpty()) {
            graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_NO_ORDERS),
                    x + ProductionMenuLayout.MARGIN, y + layout.orderLineY(0), COLOR_DIM, false);
            return;
        }
        for (int line = 0; line < orders.size(); line++) {
            ProductionScreenState.OrderView order = orders.get(line);
            graphics.drawString(font, orderLine(order), x + ProductionMenuLayout.MARGIN, y + layout.orderLineY(line),
                    order.lostIngredients() ? COLOR_LOST : COLOR_TEXT, false);
        }
    }

    /**
     * One order line. An order that ended without producing everything although ingredients had already been handed
     * over says so on the line itself, not only in a tooltip: those items are not coming back
     * ({@code warehouse-system.md} §3.5).
     */
    private Component orderLine(ProductionScreenState.OrderView order) {
        Component name = order.result().toStack().getHoverName();
        Component amount = Component.literal(LangNumberFormat.format(order.amount()));
        Component status = WareworksLang.translateDirect(order.state().langKey());
        return fitToRow(order.lostIngredients()
                ? WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER_LOST, name, amount, status)
                : WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER, name, amount, status));
    }

    /** The orders the window has room for, newest first. */
    private List<ProductionScreenState.OrderView> shownOrders() {
        List<ProductionScreenState.OrderView> orders = state.orders();
        List<ProductionScreenState.OrderView> shown = new ArrayList<>(layout.orderLines());
        for (int line = 0; line < layout.orderLines() && line < orders.size(); line++)
            shown.add(orders.get(orders.size() - 1 - line));
        return shown;
    }

    private Component fitToRow(Component line) {
        int room = ProductionMenuLayout.WIDTH - 2 * ProductionMenuLayout.MARGIN;
        if (font.width(line) <= room)
            return line;
        int fits = room - font.width(CommonComponents.ELLIPSIS);
        return Component.literal(fits <= 0 ? "" : font.plainSubstrByWidth(line.getString(), fits))
                .append(CommonComponents.ELLIPSIS);
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
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_TAB_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
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
            tooltip.add(orderLine(view));
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

    // --- hit testing ---------------------------------------------------------------------------------------------

    private void clampSelection() {
        selectedPattern = Math.max(0, Math.min(selectedPattern, menu.patternSlots() - 1));
    }

    /** The pattern cell under the mouse ({@code 0..8} grid, {@value ProductionMenuLayout#RESULT_CELL} result). */
    private int cellAt(double mouseX, double mouseY) {
        for (int cell = 0; cell < ProductionMenuLayout.PATTERN_CELLS; cell++) {
            if (isOver(mouseX, mouseY, leftPos + layout.patternCellX(cell), topPos + layout.patternCellY(cell)))
                return cell;
        }
        return NONE;
    }

    /** The pattern tab under the mouse. */
    private int tabAt(double mouseX, double mouseY) {
        for (int pattern = 0; pattern < menu.patternSlots(); pattern++) {
            if (isOver(mouseX, mouseY, leftPos + layout.tabX(pattern), topPos + layout.tabY(pattern)))
                return pattern;
        }
        return NONE;
    }

    /** The index within {@link #shownOrders()} of the order line under the mouse. */
    private int orderAt(double mouseX, double mouseY) {
        if (mouseX < leftPos + ProductionMenuLayout.MARGIN
                || mouseX >= leftPos + ProductionMenuLayout.WIDTH - ProductionMenuLayout.MARGIN)
            return NONE;
        int shown = shownOrders().size();
        for (int line = 0; line < shown; line++) {
            int top = topPos + layout.orderLineY(line);
            if (mouseY >= top && mouseY < top + ProductionMenuLayout.LABEL_HEIGHT)
                return line;
        }
        return NONE;
    }

    private static boolean isOver(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + ProductionMenuLayout.SLOT && mouseY >= y
                && mouseY < y + ProductionMenuLayout.SLOT;
    }
}
