package dev.wareworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.OptimizerAction;
import dev.wareworks.content.station.OptimizerMenu;
import dev.wareworks.content.station.OptimizerMenuLayout;
import dev.wareworks.content.station.OptimizerScreenState;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.core.terminal.CountFormat;
import dev.wareworks.network.OptimizerActionPayload;
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
 * The Warehouse Optimizer's screen (issue #46, ADR-079), a copy of the stock keeper's ({@link WarehouseStockKeeperScreen})
 * with three read-only numbers per row: one line per item — an item cell, the item's name, <b>Best</b> (what lies at the
 * highest priority that holds the item), <b>Below</b> (what lies at the priorities under it), <b>Move</b> (what could
 * move to a better place now) and a status pip — plus a status row and the player inventory.
 * <p>
 * <b>The rows are ghosts, not slots.</b> Clicking a cell with an item on the cursor sets that row's item, clicking it
 * with an empty hand clears the row, and Shift-clicking an item in the inventory fills the first free row. Clicking a
 * filled-in clipboard onto the <b>list cell</b> at the left of the status row adds the items it lists. None of it moves
 * an item: every change sends an {@code OptimizerActionPayload}, and the server writes the row and pushes the whole list
 * back.
 * <p>
 * <b>Optimise</b> is the button at the right of the status row; it asks the warehouse to move every listed item, and a
 * click on a row's pip asks for that item alone. While the order runs, the status row says what it moved and how many
 * trips it may still cost, with a red cancel mark at its right end.
 * <p>
 * The numbers are the server's, computed only while this screen is open ({@code OptimizerMenu}); the Move column is an
 * estimate from the inventory snapshots, and a "?" beside it says that some better place could not be measured yet.
 */
public class WarehouseOptimizerScreen extends AbstractSimiContainerScreen<OptimizerMenu> {
    /** How a number of an empty row reads. */
    public static final String OFF = "–";
    /** Beside the Move number while some better place could not be measured from its snapshot. */
    public static final String UNKNOWN_MARK = "?";

    private static final int TITLE_Y = 2;
    private static final int ITEM_INSET = 1;
    /** Edge length of the drawn status pip, centred in its clickable column. */
    private static final int PIP_SIZE = 6;
    /** Nothing is under the mouse. */
    private static final int NONE = -1;

    private static final int COLOR_HEADER = 0xFFFBDC7D;
    private static final int COLOR_TEXT = 0xFFEEEEEE;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_OFF = 0xFF707070;
    private static final int COLOR_CELL_HOVER = 0x60FFFFFF;
    private static final int COLOR_FIELD = 0x40000000;
    private static final int COLOR_FIELD_HOVER = 0x60FFFFFF;
    private static final int COLOR_WARNING = 0xFFFFAA33;
    private static final int COLOR_RUNNING = 0xFF5FD3E0;
    private static final int COLOR_DONE = 0xFF5CD65C;
    private static final int COLOR_NOTHING = 0xFF8A8A8A;
    private static final int COLOR_CANCEL = 0xFFFF5555;
    /** Laid over the clipboard in the list cell, so it reads as a place for one rather than as one held there. */
    private static final int COLOR_GHOST = 0x90202020;

    private OptimizerScreenState state = OptimizerScreenState.NONE;
    private OptimizerMenuLayout layout;
    private IconButton optimiseButton;
    private int scroll;

    public WarehouseOptimizerScreen(OptimizerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        layout = menu.layout();
        setWindowSize(OptimizerMenuLayout.WIDTH, layout.height());
        super.init();
        clampScroll();
        optimiseButton = new IconButton(leftPos + layout.optimiseX(), topPos + layout.statusRowY(), AllIcons.I_PLAY);
        optimiseButton.withCallback(() -> send(OptimizerActionPayload.of(menu.containerId, OptimizerAction.OPTIMISE)));
        optimiseButton.getToolTip().add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_OPTIMISE));
        optimiseButton.getToolTip().add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_OPTIMISE_HINT).copy()
                .withStyle(ChatFormatting.GRAY));
        optimiseButton.getToolTip().add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_OPTIMISE_NOW).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        addRenderableWidget(optimiseButton);
        if (menu.optimizer() == null && minecraft != null && minecraft.player != null)
            minecraft.player.closeContainer(); // the block entity is gone: nothing to edit
    }

    /** The server sent the optimizer's rows and what the warehouse holds of their items. */
    public void onState(dev.wareworks.network.OptimizerScreenPayload payload) {
        state = payload.state();
    }

    /** The rows as last pushed by the server (dev harness and tests of the client side). */
    public OptimizerScreenState state() {
        return state;
    }

    /** The first row the window is showing. */
    public int scroll() {
        return scroll;
    }

    // --- interaction ---------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int row = rowAt(mouseX, mouseY);
        if (row != NONE) {
            // The pip is the row's own Optimise: it starts an order for this item alone.
            if (isOverStatus(mouseX, mouseY, row) && state.row(row).flatMap(OptimizerScreenState.RowView::key)
                    .isPresent()) {
                send(OptimizerActionPayload.ofRow(menu.containerId, OptimizerAction.OPTIMISE_ROW, row));
                return true;
            }
            if (isOverCell(mouseX, mouseY, row)) {
                setItem(row, menu.getCarried());
                return true;
            }
        }
        // The list cell reads the clipboard on the cursor. It stays on the cursor: the server only reads its entries.
        if (isOverListCell(mouseX, mouseY) && !menu.getCarried().isEmpty()) {
            send(OptimizerActionPayload.of(menu.containerId, OptimizerAction.IMPORT_CLIPBOARD));
            return true;
        }
        if (state.order().running() && isOverCancel(mouseX, mouseY)) {
            send(OptimizerActionPayload.of(menu.containerId, OptimizerAction.CANCEL));
            return true;
        }
        // Shift-clicking an item in the player inventory writes it into the first free row instead of moving it: the
        // optimizer takes no items at all, so listing one is what that click can mean here.
        if (hasShiftDown() && hoveredSlot != null && hoveredSlot.hasItem()) {
            int free = firstFreeRow();
            if (free != NONE) {
                setItem(free, hoveredSlot.getItem());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Scrolling anywhere moves the row list. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && layout.scrolls()) {
            int before = scroll;
            scroll -= (int) Math.signum(scrollY);
            clampScroll();
            if (scroll != before)
                return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The first row without an item, or {@link #NONE}. */
    private int firstFreeRow() {
        for (int row = 0; row < menu.rowCount(); row++) {
            if (state.row(row).map(view -> view.key().isEmpty()).orElse(true))
                return row;
        }
        return NONE;
    }

    /** Sets a row's item from {@code stack}, or clears the row when the stack is empty. */
    private void setItem(int row, ItemStack stack) {
        if (row < 0 || row >= menu.rowCount())
            return;
        if (stack.isEmpty()) {
            if (state.row(row).map(view -> view.key().isEmpty()).orElse(true))
                return;
            send(OptimizerActionPayload.ofRow(menu.containerId, OptimizerAction.CLEAR_ROW, row));
            return;
        }
        send(OptimizerActionPayload.setItem(menu.containerId, row, ItemKey.of(stack)));
    }

    private void send(OptimizerActionPayload payload) {
        PacketDistributor.sendToServer(payload);
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.2F);
    }

    // --- rendering -----------------------------------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int panelHeight = layout.height();
        UIRenderHelper.drawStretched(graphics, x + 3, y + 3, OptimizerMenuLayout.WIDTH - 6, panelHeight - 6, 0,
                AllGuiTextures.VALUE_SETTINGS_OUTER_BG);
        renderBrassFrame(graphics, x, y, OptimizerMenuLayout.WIDTH, panelHeight);
        renderRows(graphics, mouseX, mouseY);
        renderListCell(graphics, mouseX, mouseY);
        renderSlotBackgrounds(graphics);
        renderTexts(graphics, mouseX, mouseY);
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

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int hoveredRow = rowAt(mouseX, mouseY);
        int hoveredField = hoveredRow == NONE ? NONE : numberFieldAt(mouseX);
        for (int line = 0; line < layout.visibleRows(); line++) {
            int row = scroll + line;
            if (row >= menu.rowCount())
                break;
            int cellX = leftPos + layout.slotX();
            int rowY = topPos + layout.rowY(line);
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, cellX, rowY);
            if (hoveredRow == row && isOverCell(mouseX, mouseY, row))
                graphics.fill(cellX, rowY, cellX + OptimizerMenuLayout.SLOT, rowY + OptimizerMenuLayout.SLOT,
                        COLOR_CELL_HOVER);
            Optional<OptimizerScreenState.RowView> view = state.row(row);
            view.flatMap(OptimizerScreenState.RowView::key)
                    .ifPresent(key -> renderItem(graphics, key.toStack(), cellX + ITEM_INSET, rowY + ITEM_INSET));
            renderName(graphics, view, rowY);
            for (int field = 0; field < OptimizerMenuLayout.NUMBER_FIELDS; field++)
                renderNumber(graphics, view, field, rowY, hoveredRow == row && hoveredField == field);
            renderStatusPip(graphics, view, rowY, hoveredRow == row && isOverStatus(mouseX, mouseY, row));
        }
    }

    private void renderItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        GuiGameElement.of(stack).render(graphics);
        pose.popPose();
    }

    private void renderName(GuiGraphics graphics, Optional<OptimizerScreenState.RowView> view, int rowY) {
        int x = leftPos + layout.nameX();
        int y = rowY + (OptimizerMenuLayout.SLOT - font.lineHeight) / 2;
        Component name = view.flatMap(OptimizerScreenState.RowView::key)
                .map(key -> (Component) key.toStack().getHoverName())
                .orElseGet(() -> WareworksLang.translateDirect(WareworksLang.OPTIMIZER_EMPTY_ROW));
        boolean empty = view.flatMap(OptimizerScreenState.RowView::key).isEmpty();
        graphics.drawString(font, fitTo(name, layout.nameWidth()), x, y, empty ? COLOR_OFF : COLOR_TEXT, false);
    }

    private void renderNumber(GuiGraphics graphics, Optional<OptimizerScreenState.RowView> view, int field, int rowY,
            boolean hovered) {
        int x = leftPos + layout.numberX(field);
        int top = rowY + 2;
        int bottom = rowY + OptimizerMenuLayout.SLOT - 2;
        graphics.fill(x + 1, top, x + OptimizerMenuLayout.NUMBER_WIDTH - 1, bottom, COLOR_FIELD);
        if (hovered)
            graphics.fill(x + 1, top, x + OptimizerMenuLayout.NUMBER_WIDTH - 1, bottom, COLOR_FIELD_HOVER);
        boolean filled = view.flatMap(OptimizerScreenState.RowView::key).isPresent() && state.linked();
        String text = filled ? numberText(view.get().breakdown(), field) : OFF;
        int textX = x + OptimizerMenuLayout.NUMBER_WIDTH - OptimizerMenuLayout.NUMBER_PADDING - font.width(text);
        int textY = rowY + (OptimizerMenuLayout.SLOT - font.lineHeight) / 2;
        int color = !filled ? COLOR_OFF
                : field == OptimizerMenuLayout.FIELD_MOVABLE && view.get().breakdown().movable() > 0 ? COLOR_RUNNING
                : COLOR_TEXT;
        graphics.drawString(font, text, textX, textY, color, false);
    }

    /** One of the three numbers of a row as it fits into its field; Move carries the "?" of an unmeasured room. */
    private String numberText(PriorityBreakdown breakdown, int field) {
        return switch (field) {
            case OptimizerMenuLayout.FIELD_BEST -> amountText(breakdown.best(), 0);
            case OptimizerMenuLayout.FIELD_BELOW -> amountText(breakdown.below(), 0);
            default -> breakdown.movableUnknown()
                    ? breakdown.movable() == 0 ? UNKNOWN_MARK
                            : amountText(breakdown.movable(), font.width(UNKNOWN_MARK)) + UNKNOWN_MARK
                    : amountText(breakdown.movable(), 0);
        };
    }

    /**
     * A number as it fits into a field beside {@code reserved} pixels: the plain, locale-formatted amount while it fits,
     * and the compact form the terminal's cells use otherwise ("12K"); the exact value is always in the tooltip.
     */
    private String amountText(long value, int reserved) {
        String plain = LangNumberFormat.format(value);
        int room = OptimizerMenuLayout.NUMBER_WIDTH - 2 * OptimizerMenuLayout.NUMBER_PADDING - reserved;
        return font.width(plain) <= room ? plain : CountFormat.compact(value);
    }

    /** The one coloured mark that says what the order is doing with a row's item; nothing while it does nothing. */
    private void renderStatusPip(GuiGraphics graphics, Optional<OptimizerScreenState.RowView> view, int rowY,
            boolean hovered) {
        if (view.flatMap(OptimizerScreenState.RowView::key).isEmpty())
            return;
        int x = leftPos + layout.statusX() + (OptimizerMenuLayout.STATUS_WIDTH - PIP_SIZE) / 2;
        int y = rowY + (OptimizerMenuLayout.SLOT - PIP_SIZE) / 2;
        OptimizerScreenState.Pip pip = view.get().pip();
        if (pip == OptimizerScreenState.Pip.IDLE) {
            // An idle row still shows where to click: an outline, filled while the mouse is over it.
            int outline = hovered ? COLOR_TEXT : COLOR_OFF;
            graphics.fill(x, y, x + PIP_SIZE, y + 1, outline);
            graphics.fill(x, y + PIP_SIZE - 1, x + PIP_SIZE, y + PIP_SIZE, outline);
            graphics.fill(x, y, x + 1, y + PIP_SIZE, outline);
            graphics.fill(x + PIP_SIZE - 1, y, x + PIP_SIZE, y + PIP_SIZE, outline);
            return;
        }
        graphics.fill(x, y, x + PIP_SIZE, y + PIP_SIZE, pipColor(pip));
        if (hovered)
            graphics.fill(x, y, x + PIP_SIZE, y + PIP_SIZE, COLOR_CELL_HOVER);
    }

    private static int pipColor(OptimizerScreenState.Pip pip) {
        return switch (pip) {
            case RUNNING -> COLOR_RUNNING;
            case DONE -> COLOR_DONE;
            case NOTHING_TO_MOVE -> COLOR_NOTHING;
            case IDLE -> COLOR_OFF;
        };
    }

    /** The list cell: a slot with a clipboard in it, dimmed, so it reads as the place one is clicked onto. */
    private void renderListCell(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + layout.listCellX();
        int y = topPos + layout.statusRowY();
        AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, x, y);
        renderItem(graphics, AllBlocks.CLIPBOARD.asStack(), x + ITEM_INSET, y + ITEM_INSET);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        // Above the item, which the GUI item renderer draws well in front of the background.
        pose.translate(0, 0, 200);
        graphics.fill(x + ITEM_INSET, y + ITEM_INSET, x + OptimizerMenuLayout.SLOT - ITEM_INSET,
                y + OptimizerMenuLayout.SLOT - ITEM_INSET, isOverListCell(mouseX, mouseY) ? COLOR_CELL_HOVER
                        : COLOR_GHOST);
        pose.popPose();
    }

    private void renderSlotBackgrounds(GuiGraphics graphics) {
        for (Slot slot : menu.slots)
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    }

    private void renderTexts(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.drawString(font, title, x + OptimizerMenuLayout.MARGIN, y + TITLE_Y, COLOR_HEADER, false);
        int headerY = y + layout.headerY();
        graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.OPTIMIZER_ITEMS),
                x + OptimizerMenuLayout.MARGIN, headerY, COLOR_DIM, false);
        columnHeader(graphics, WareworksLang.OPTIMIZER_BEST_SHORT, OptimizerMenuLayout.FIELD_BEST, headerY);
        columnHeader(graphics, WareworksLang.OPTIMIZER_BELOW_SHORT, OptimizerMenuLayout.FIELD_BELOW, headerY);
        columnHeader(graphics, WareworksLang.OPTIMIZER_MOVABLE_SHORT, OptimizerMenuLayout.FIELD_MOVABLE, headerY);
        boolean running = state.order().running();
        int textY = y + layout.statusRowY() + (OptimizerMenuLayout.STATUS_HEIGHT - font.lineHeight) / 2 + 1;
        int room = running ? layout.statusTextWidthWithCancel() : layout.statusTextWidth();
        graphics.drawString(font, fitTo(statusLine(), room), x + layout.statusTextX(), textY, statusLineColor(),
                false);
        if (running) {
            String mark = WarehouseTerminalScreen.CANCEL_MARK;
            int markX = x + layout.cancelX() + (OptimizerMenuLayout.CANCEL_WIDTH - font.width(mark)) / 2;
            graphics.drawString(font, mark, markX, textY, isOverCancel(mouseX, mouseY) ? COLOR_TEXT : COLOR_CANCEL,
                    false);
        }
        graphics.drawString(font, playerInventoryTitle, x + layout.playerSlotsX() - 1, y + layout.playerLabelY(),
                COLOR_DIM, false);
    }

    private void columnHeader(GuiGraphics graphics, String key, int field, int headerY) {
        Component label = WareworksLang.translateDirect(key);
        int x = leftPos + layout.numberX(field) + OptimizerMenuLayout.NUMBER_WIDTH - OptimizerMenuLayout.NUMBER_PADDING
                - font.width(label);
        graphics.drawString(font, label, x, headerY, COLOR_DIM, false);
    }

    /**
     * What the status row says: the last action's message first, then whether a warehouse serves this optimizer at all,
     * then where its order is, and the hint while nothing happened yet.
     */
    private Component statusLine() {
        if (state.message() != OptimizerScreenState.Message.NONE)
            return WareworksLang.translateDirect(state.message().langKey(), LangNumberFormat.format(state.first()),
                    LangNumberFormat.format(state.second()));
        if (!state.linked())
            return WareworksLang.translateDirect(WareworksLang.OPTIMIZER_NO_WAREHOUSE);
        OptimizerScreenState.OrderLine order = state.order();
        String moved = LangNumberFormat.format(order.moved());
        return switch (order.state()) {
            case RUNNING -> WareworksLang.translateDirect(WareworksLang.OPTIMIZER_RUNNING, moved,
                    LangNumberFormat.format(order.tripsLeft()));
            case FINISHED -> WareworksLang.translateDirect(WareworksLang.OPTIMIZER_FINISHED, moved);
            case OUT_OF_TRIPS -> WareworksLang.translateDirect(WareworksLang.OPTIMIZER_OUT_OF_TRIPS, moved);
            case CANCELLED -> WareworksLang.translateDirect(WareworksLang.OPTIMIZER_CANCELLED, moved);
            case NONE -> WareworksLang.translateDirect(WareworksLang.OPTIMIZER_HINT);
        };
    }

    /** Gold for a refusal or no warehouse, aqua while an order runs, quiet otherwise. */
    private int statusLineColor() {
        OptimizerScreenState.Message message = state.message();
        if (message == OptimizerScreenState.Message.ADDED)
            return COLOR_DONE;
        if (message != OptimizerScreenState.Message.NONE || !state.linked())
            return COLOR_WARNING;
        return switch (state.order().state()) {
            case RUNNING -> COLOR_RUNNING;
            case FINISHED -> COLOR_DONE;
            case OUT_OF_TRIPS, CANCELLED -> COLOR_WARNING;
            case NONE -> COLOR_DIM;
        };
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
        if (isOverListCell(mouseX, mouseY)) {
            tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_LIST));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_LIST_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            return tooltip;
        }
        if (state.order().running() && isOverCancel(mouseX, mouseY)) {
            tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_CANCEL).copy()
                    .withStyle(ChatFormatting.RED));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_CANCEL_HINT).copy()
                    .withStyle(ChatFormatting.GRAY));
            return tooltip;
        }
        int row = rowAt(mouseX, mouseY);
        if (row == NONE)
            return tooltip;
        Optional<OptimizerScreenState.RowView> view = state.row(row);
        boolean filled = view.flatMap(OptimizerScreenState.RowView::key).isPresent();
        int field = numberFieldAt(mouseX);
        if (field != NONE) {
            tooltip.add(WareworksLang.translateDirect(fieldTitleKey(field)));
            tooltip.add(WareworksLang.translateDirect(fieldHintKey(field)).copy().withStyle(ChatFormatting.GRAY));
            if (field == OptimizerMenuLayout.FIELD_MOVABLE) {
                tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_MOVABLE_RESERVED).copy()
                        .withStyle(ChatFormatting.GRAY));
                if (filled && view.get().breakdown().movableUnknown())
                    tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_MOVABLE_UNKNOWN).copy()
                            .withStyle(ChatFormatting.GOLD));
            }
            return tooltip;
        }
        if (isOverStatus(mouseX, mouseY, row) && filled) {
            OptimizerScreenState.Pip pip = view.get().pip();
            tooltip.add(WareworksLang.translateDirect(pip.langKey()).copy().withStyle(pipFormat(pip)));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_PIP_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            return tooltip;
        }
        if (!isOverCell(mouseX, mouseY, row))
            return tooltip;
        if (filled) {
            OptimizerScreenState.RowView shown = view.get();
            tooltip.addAll(getTooltipFromItem(minecraft, shown.key().orElseThrow().toStack()));
            if (state.linked())
                addBreakdownLines(tooltip, shown.breakdown());
            tooltip.add(WareworksLang.translateDirect(shown.pip().langKey()).copy()
                    .withStyle(pipFormat(shown.pip())));
        }
        tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_ITEM_HINT).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /**
     * Every priority that holds the item, from the highest down, what lies where nothing reads it, and what could move:
     * the exact numbers behind the three columns.
     */
    private static void addBreakdownLines(List<Component> tooltip, PriorityBreakdown breakdown) {
        if (breakdown.total() == 0) {
            tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_NONE_STORED).copy()
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        for (int priority = PriorityBreakdown.MAX_PRIORITY; priority >= PriorityBreakdown.MIN_PRIORITY; priority--) {
            long count = breakdown.countAt(priority);
            if (count > 0)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_PRIORITY, priority,
                        LangNumberFormat.format(count)).copy().withStyle(ChatFormatting.GRAY));
        }
        if (breakdown.unloaded() > 0)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_UNLOADED,
                    LangNumberFormat.format(breakdown.unloaded())).copy().withStyle(ChatFormatting.GOLD));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.OPTIMIZER_MOVABLE_NOW,
                LangNumberFormat.format(breakdown.movable())
                        + (breakdown.movableUnknown() ? UNKNOWN_MARK : "")).copy()
                .withStyle(ChatFormatting.AQUA));
    }

    private static ChatFormatting pipFormat(OptimizerScreenState.Pip pip) {
        return switch (pip) {
            case RUNNING -> ChatFormatting.AQUA;
            case DONE -> ChatFormatting.GREEN;
            case NOTHING_TO_MOVE, IDLE -> ChatFormatting.GRAY;
        };
    }

    private static String fieldTitleKey(int field) {
        return switch (field) {
            case OptimizerMenuLayout.FIELD_BEST -> WareworksLang.OPTIMIZER_BEST;
            case OptimizerMenuLayout.FIELD_BELOW -> WareworksLang.OPTIMIZER_BELOW;
            default -> WareworksLang.OPTIMIZER_MOVABLE;
        };
    }

    private static String fieldHintKey(int field) {
        return switch (field) {
            case OptimizerMenuLayout.FIELD_BEST -> WareworksLang.OPTIMIZER_BEST_HINT;
            case OptimizerMenuLayout.FIELD_BELOW -> WareworksLang.OPTIMIZER_BELOW_HINT;
            default -> WareworksLang.OPTIMIZER_MOVABLE_HINT;
        };
    }

    // --- hit testing ---------------------------------------------------------------------------------------------

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, layout.maxScroll()));
    }

    /** The item row under the mouse, or {@link #NONE}. */
    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < leftPos + OptimizerMenuLayout.MARGIN
                || mouseX >= leftPos + OptimizerMenuLayout.WIDTH - OptimizerMenuLayout.MARGIN)
            return NONE;
        for (int line = 0; line < layout.visibleRows(); line++) {
            int row = scroll + line;
            if (row >= menu.rowCount())
                break;
            int top = topPos + layout.rowY(line);
            if (mouseY >= top && mouseY < top + OptimizerMenuLayout.ROW_HEIGHT)
                return row;
        }
        return NONE;
    }

    /** Whether the mouse is over the item cell of {@code row}. */
    private boolean isOverCell(double mouseX, double mouseY, int row) {
        int line = row - scroll;
        if (line < 0 || line >= layout.visibleRows())
            return false;
        int x = leftPos + layout.slotX();
        int y = topPos + layout.rowY(line);
        return mouseX >= x && mouseX < x + OptimizerMenuLayout.SLOT && mouseY >= y
                && mouseY < y + OptimizerMenuLayout.SLOT;
    }

    /** Whether the mouse is over the status pip of {@code row}, which optimises that row alone. */
    private boolean isOverStatus(double mouseX, double mouseY, int row) {
        int line = row - scroll;
        if (line < 0 || line >= layout.visibleRows())
            return false;
        int x = leftPos + layout.statusX();
        int y = topPos + layout.rowY(line);
        return mouseX >= x && mouseX < x + OptimizerMenuLayout.STATUS_WIDTH && mouseY >= y
                && mouseY < y + OptimizerMenuLayout.SLOT;
    }

    /** Whether the mouse is over the list cell, which takes a clipboard's items. */
    private boolean isOverListCell(double mouseX, double mouseY) {
        int x = leftPos + layout.listCellX();
        int y = topPos + layout.statusRowY();
        return mouseX >= x && mouseX < x + OptimizerMenuLayout.SLOT && mouseY >= y
                && mouseY < y + OptimizerMenuLayout.SLOT;
    }

    /** Whether the mouse is over the cancel mark at the right end of the status text. */
    private boolean isOverCancel(double mouseX, double mouseY) {
        int x = leftPos + layout.cancelX();
        int y = topPos + layout.statusRowY();
        return mouseX >= x && mouseX < x + OptimizerMenuLayout.CANCEL_WIDTH && mouseY >= y
                && mouseY < y + OptimizerMenuLayout.STATUS_HEIGHT;
    }

    /** The number field under the mouse, or {@link #NONE}. */
    private int numberFieldAt(double mouseX) {
        for (int field = 0; field < OptimizerMenuLayout.NUMBER_FIELDS; field++) {
            int x = leftPos + layout.numberX(field);
            if (mouseX >= x && mouseX < x + OptimizerMenuLayout.NUMBER_WIDTH)
                return field;
        }
        return NONE;
    }

    /** Where the item cell of {@code row} is drawn, for real clicks of the dev harness; empty while it is scrolled away. */
    public Optional<int[]> cellCentre(int row) {
        int line = row - scroll;
        if (line < 0 || line >= layout.visibleRows())
            return Optional.empty();
        return Optional.of(new int[] { leftPos + layout.slotX() + OptimizerMenuLayout.SLOT / 2,
                topPos + layout.rowY(line) + OptimizerMenuLayout.SLOT / 2 });
    }

    /** Where the status pip of {@code row} is drawn (dev harness); empty while it is scrolled away. */
    public Optional<int[]> pipCentre(int row) {
        int line = row - scroll;
        if (line < 0 || line >= layout.visibleRows())
            return Optional.empty();
        return Optional.of(new int[] { leftPos + layout.statusX() + OptimizerMenuLayout.STATUS_WIDTH / 2,
                topPos + layout.rowY(line) + OptimizerMenuLayout.SLOT / 2 });
    }

    /** Where the list cell is drawn (dev harness). */
    public int[] listCellCentre() {
        return new int[] { leftPos + layout.listCellX() + OptimizerMenuLayout.SLOT / 2,
                topPos + layout.statusRowY() + OptimizerMenuLayout.SLOT / 2 };
    }

    /** Where the Optimise button is drawn (dev harness). */
    public int[] optimiseCentre() {
        return new int[] { leftPos + layout.optimiseX() + OptimizerMenuLayout.SLOT / 2,
                topPos + layout.statusRowY() + OptimizerMenuLayout.SLOT / 2 };
    }

    /** Where the cancel mark is drawn (dev harness); it only answers while an order runs. */
    public int[] cancelCentre() {
        return new int[] { leftPos + layout.cancelX() + OptimizerMenuLayout.CANCEL_WIDTH / 2,
                topPos + layout.statusRowY() + OptimizerMenuLayout.STATUS_HEIGHT / 2 };
    }

    private Component fitTo(Component line, int room) {
        if (font.width(line) <= room)
            return line;
        int fits = room - font.width(CommonComponents.ELLIPSIS);
        return Component.literal(fits <= 0 ? "" : font.plainSubstrByWidth(line.getString(), fits))
                .append(CommonComponents.ELLIPSIS);
    }
}
