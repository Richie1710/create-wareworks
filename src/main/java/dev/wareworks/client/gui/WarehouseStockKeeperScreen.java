package dev.wareworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperMenu;
import dev.wareworks.content.station.StockKeeperMenuLayout;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.StockKeeperScreenState;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleAdjustment;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.CountFormat;
import dev.wareworks.network.StockKeeperRulePayload;
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
 * The warehouse stock keeper's screen ({@code docs/warehouse-system.md} §3.6, M15, issue #3): one line per rule — an
 * item cell, the item's name, the three numbers and a status pip — plus the player inventory.
 * <p>
 * <b>The rows are ghosts, not slots.</b> Clicking a cell with an item on the cursor sets that rule's item, clicking it
 * with an empty hand clears the row, and scrolling over one of the three numbers changes it — none of which moves an
 * item. Every change sends a {@code StockKeeperRulePayload}; the server writes the rule, clamps it and pushes the
 * whole list back, so the screen never has to guess what it changed and can never show a value nobody stored.
 * Shift-clicking an item in the player inventory fills the first free row, which is the quick way to write a rule
 * without picking items up.
 * <p>
 * <b>The three numbers govern three directions</b>, which is the one sentence a player has to learn, and the column
 * header repeats it: the minimum what comes <i>in</i>, the maximum what may be <i>stored</i>, the reserve what may go
 * <i>out to automation</i>. A number that is switched off reads as "{@value #OFF}" — scrolling one step below 0 and a
 * <b>right-click</b> switch it off, a left-click switches an off number on at 0 and leaves a set one alone, and for the
 * maximum 0 is a real setting ("accept none of this any more").
 */
public class WarehouseStockKeeperScreen extends AbstractSimiContainerScreen<StockKeeperMenu> {
    /** How an unset number reads. */
    public static final String OFF = "–";

    private static final int TITLE_Y = 2;
    private static final int ITEM_INSET = 1;
    /** Nothing is under the mouse. */
    private static final int NONE = -1;
    /** Amount one scroll step changes a number by; Shift takes a whole stack at a time. */
    private static final int FINE_STEP = 1;
    private static final int COARSE_STEP = 64;
    private static final int NUMBER_PADDING = 3;

    private static final int COLOR_HEADER = 0xFFFBDC7D;
    private static final int COLOR_TEXT = 0xFFEEEEEE;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_OFF = 0xFF707070;
    private static final int COLOR_CELL_HOVER = 0x60FFFFFF;
    private static final int COLOR_FIELD = 0x40000000;
    private static final int COLOR_FIELD_HOVER = 0x60FFFFFF;
    private static final int COLOR_WARNING = 0xFFFFAA33;
    private static final int COLOR_SATISFIED = 0xFF5CD65C;
    private static final int COLOR_BELOW = 0xFFFBDC7D;
    private static final int COLOR_MAXIMUM = 0xFFFF6B4A;
    private static final int COLOR_RESERVE = 0xFF6FA8FF;
    private static final int COLOR_ORDERING = 0xFF5FD3E0;
    private static final int COLOR_PAUSED = 0xFFFF4040;

    private StockKeeperScreenState state = StockKeeperScreenState.NONE;
    private StockKeeperMenuLayout layout;
    private int scroll;

    public WarehouseStockKeeperScreen(StockKeeperMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        layout = menu.layout();
        setWindowSize(StockKeeperMenuLayout.WIDTH, layout.height());
        super.init();
        clampScroll();
        if (menu.keeper() == null && minecraft != null && minecraft.player != null)
            minecraft.player.closeContainer(); // the block entity is gone: nothing to edit
    }

    /** The server sent the keeper's rules and what the warehouse holds of them. */
    public void onState(dev.wareworks.network.StockKeeperScreenPayload payload) {
        state = payload.state();
    }

    /** The rules as last pushed by the server (dev harness and tests of the client side). */
    public StockKeeperScreenState state() {
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
            int field = numberFieldAt(mouseX, row);
            if (field != NONE) {
                // A right-click switches a number off — the one off switch, and the one the hint under the list and the
                // block's tooltip both name. A left-click switches a number that is off on at 0, which is where
                // scrolling starts, and does nothing at all to one that is already set: a plain click must never delete
                // a live maximum or reserve, because that is one unannounced click away from storing past a cap or
                // handing a reserve to automation, and scrolling it back costs 32 steps (M15 review fix).
                boolean set = state.row(row).map(view -> number(view, field) != StockRule.UNSET).orElse(false);
                if (button != 1 && set)
                    return true; // consumed: the number stays exactly as it is
                send(StockKeeperRulePayload.setNumber(menu.containerId, row, payloadField(field),
                        button == 1 ? StockRule.UNSET : 0L));
                return true;
            }
            // The status mark is the row's own affordance, and a paused row is the only one where clicking it
            // means anything: it is the way back from the safety stop (M15 part 2). A click anywhere else on the row
            // keeps doing exactly what it did.
            if (isOverStatus(mouseX, mouseY, row)
                    && state.row(row).map(StockKeeperScreenState.RowView::paused).orElse(false)) {
                send(StockKeeperRulePayload.resume(menu.containerId, row));
                return true;
            }
            if (isOverCell(mouseX, mouseY, row)) {
                setItem(row, menu.getCarried());
                return true;
            }
        }
        // Shift-clicking an item in the player inventory writes it into the first free row instead of moving it: the
        // keeper takes no items at all, so writing a rule is what that click can mean here. The item stays where it
        // is — a rule's item is a ghost.
        if (hasShiftDown() && hoveredSlot != null && hoveredSlot.hasItem()) {
            int free = firstFreeRow();
            if (free != NONE) {
                setItem(free, hoveredSlot.getItem());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Scrolling over one of the three numbers changes it (Shift takes a whole stack at a time); scrolling anywhere
     * else moves the row list, on a keeper configured with more rows than the window shows.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0)
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int direction = (int) Math.signum(scrollY);
        int row = rowAt(mouseX, mouseY);
        if (row != NONE) {
            int field = numberFieldAt(mouseX, row);
            Optional<StockKeeperScreenState.RowView> view = state.row(row);
            if (field != NONE && view.isPresent() && view.get().key().isPresent()) {
                long next = stepped(number(view.get(), field), direction);
                send(StockKeeperRulePayload.setNumber(menu.containerId, row, payloadField(field), next));
                return true;
            }
        }
        if (layout.scrolls()) {
            int before = scroll;
            scroll -= direction;
            clampScroll();
            if (scroll != before)
                return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * One scroll step on a number: one item, or a whole stack with Shift held. One step below 0 switches the number
     * off, and one step up from "off" lands on 0 — so every value including 0 is reachable and nothing is a dead end.
     */
    private long stepped(long current, int direction) {
        long step = hasShiftDown() ? COARSE_STEP : FINE_STEP;
        if (current == StockRule.UNSET)
            return direction > 0 ? 0L : StockRule.UNSET;
        long next = current + direction * step;
        if (next < 0L)
            return StockRule.UNSET;
        return Math.min(next, StockRule.MAX_AMOUNT);
    }

    /** The first row without an item, or {@link #NONE}. */
    private int firstFreeRow() {
        for (int row = 0; row < menu.rowCount(); row++) {
            if (state.row(row).map(view -> view.key().isEmpty()).orElse(true))
                return row;
        }
        return NONE;
    }

    /** Sets a row's item from {@code stack}, or clears the whole row when the stack is empty. */
    private void setItem(int row, ItemStack stack) {
        if (row < 0 || row >= menu.rowCount())
            return;
        if (stack.isEmpty()) {
            if (state.row(row).map(view -> view.key().isEmpty()).orElse(true))
                return;
            send(StockKeeperRulePayload.clearRow(menu.containerId, row));
            return;
        }
        send(StockKeeperRulePayload.setItem(menu.containerId, row, ItemKey.of(stack)));
    }

    private void send(StockKeeperRulePayload payload) {
        PacketDistributor.sendToServer(payload);
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.2F);
    }

    // --- rendering -----------------------------------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int panelHeight = layout.height();
        UIRenderHelper.drawStretched(graphics, x + 3, y + 3, StockKeeperMenuLayout.WIDTH - 6, panelHeight - 6, 0,
                AllGuiTextures.VALUE_SETTINGS_OUTER_BG);
        renderBrassFrame(graphics, x, y, StockKeeperMenuLayout.WIDTH, panelHeight);
        renderRows(graphics, mouseX, mouseY);
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

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int hoveredRow = rowAt(mouseX, mouseY);
        int hoveredField = hoveredRow == NONE ? NONE : numberFieldAt(mouseX, hoveredRow);
        for (int line = 0; line < layout.visibleRows(); line++) {
            int row = scroll + line;
            if (row >= menu.rowCount())
                break;
            int cellX = leftPos + layout.slotX();
            int rowY = topPos + layout.rowY(line);
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, cellX, rowY);
            if (hoveredRow == row && hoveredField == NONE && isOverCell(mouseX, mouseY, row))
                graphics.fill(cellX, rowY, cellX + StockKeeperMenuLayout.SLOT, rowY + StockKeeperMenuLayout.SLOT,
                        COLOR_CELL_HOVER);
            Optional<StockKeeperScreenState.RowView> view = state.row(row);
            view.flatMap(StockKeeperScreenState.RowView::key)
                    .ifPresent(key -> renderItem(graphics, key, cellX + ITEM_INSET, rowY + ITEM_INSET));
            renderName(graphics, view, rowY);
            for (int field = 0; field < StockKeeperMenuLayout.NUMBER_FIELDS; field++)
                renderNumber(graphics, view, field, rowY, hoveredRow == row && hoveredField == field);
            renderStatusPip(graphics, view, rowY);
        }
    }

    private void renderItem(GuiGraphics graphics, ItemKey key, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        GuiGameElement.of(key.toStack()).render(graphics);
        pose.popPose();
    }

    private void renderName(GuiGraphics graphics, Optional<StockKeeperScreenState.RowView> view, int rowY) {
        int x = leftPos + layout.nameX();
        int y = rowY + (StockKeeperMenuLayout.SLOT - font.lineHeight) / 2;
        Component name = view.flatMap(StockKeeperScreenState.RowView::key)
                .map(key -> (Component) key.toStack().getHoverName())
                .orElseGet(() -> WareworksLang.translateDirect(WareworksLang.KEEPER_EMPTY_ROW));
        boolean empty = view.flatMap(StockKeeperScreenState.RowView::key).isEmpty();
        graphics.drawString(font, fitTo(name, layout.nameWidth()), x, y, empty ? COLOR_OFF : COLOR_TEXT, false);
    }

    private void renderNumber(GuiGraphics graphics, Optional<StockKeeperScreenState.RowView> view, int field, int rowY,
            boolean hovered) {
        int x = leftPos + layout.numberX(field);
        int top = rowY + 2;
        int bottom = rowY + StockKeeperMenuLayout.SLOT - 2;
        graphics.fill(x + 1, top, x + StockKeeperMenuLayout.NUMBER_WIDTH - 1, bottom, COLOR_FIELD);
        if (hovered)
            graphics.fill(x + 1, top, x + StockKeeperMenuLayout.NUMBER_WIDTH - 1, bottom, COLOR_FIELD_HOVER);
        boolean editable = view.flatMap(StockKeeperScreenState.RowView::key).isPresent();
        long value = view.map(row -> number(row, field)).orElse(StockRule.UNSET);
        String text = !editable || value == StockRule.UNSET ? OFF : amountText(value);
        int textX = x + StockKeeperMenuLayout.NUMBER_WIDTH - NUMBER_PADDING - font.width(text);
        int textY = rowY + (StockKeeperMenuLayout.SLOT - font.lineHeight) / 2;
        graphics.drawString(font, text, textX, textY,
                !editable || value == StockRule.UNSET ? COLOR_OFF : COLOR_TEXT, false);
    }

    /**
     * A number as it fits into a field: the plain, locale-formatted amount while it fits, and the compact form the
     * terminal's cells use otherwise ("12K"). Without that, a maximum of a million would be drawn straight across the
     * column beside it; the exact value is always in the tooltip.
     */
    private String amountText(long value) {
        String plain = LangNumberFormat.format(value);
        int room = StockKeeperMenuLayout.NUMBER_WIDTH - 2 * NUMBER_PADDING;
        return font.width(plain) <= room ? plain : CountFormat.compact(value);
    }

    /** The one coloured mark that says what a rule is doing, in the same colours the tooltip names. */
    private void renderStatusPip(GuiGraphics graphics, Optional<StockKeeperScreenState.RowView> view, int rowY) {
        StockRuleStatus status = view.map(StockKeeperScreenState.RowView::status).orElse(StockRuleStatus.NO_ITEM);
        if (status == StockRuleStatus.NO_ITEM)
            return;
        int x = leftPos + layout.statusX();
        int y = rowY + StockKeeperMenuLayout.SLOT / 2 - 2;
        graphics.fill(x, y, x + 4, y + 4, statusColor(status));
    }

    private static int statusColor(StockRuleStatus status) {
        return switch (status) {
            case BELOW_MINIMUM -> COLOR_BELOW;
            case AT_MAXIMUM -> COLOR_MAXIMUM;
            case AT_RESERVE -> COLOR_RESERVE;
            case SATISFIED -> COLOR_SATISFIED;
            case ORDERING -> COLOR_ORDERING;
            case WAITING_FOR_INGREDIENTS -> COLOR_BELOW;
            case PAUSED -> COLOR_PAUSED;
            case SHADOWED, INERT, NO_WAREHOUSE -> COLOR_WARNING;
            default -> COLOR_OFF;
        };
    }

    private void renderSlotBackgrounds(GuiGraphics graphics) {
        for (Slot slot : menu.slots)
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    }

    private void renderTexts(GuiGraphics graphics) {
        int x = leftPos;
        int y = topPos;
        graphics.drawString(font, title, x + StockKeeperMenuLayout.MARGIN, y + TITLE_Y, COLOR_HEADER, false);
        int headerY = y + layout.headerY();
        graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.KEEPER_RULES),
                x + StockKeeperMenuLayout.MARGIN, headerY, COLOR_DIM, false);
        columnHeader(graphics, WareworksLang.KEEPER_MINIMUM_SHORT, StockKeeperMenuLayout.FIELD_MINIMUM, headerY);
        columnHeader(graphics, WareworksLang.KEEPER_MAXIMUM_SHORT, StockKeeperMenuLayout.FIELD_MAXIMUM, headerY);
        columnHeader(graphics, WareworksLang.KEEPER_RESERVE_SHORT, StockKeeperMenuLayout.FIELD_RESERVE, headerY);
        graphics.drawString(font, statusLine(), x + StockKeeperMenuLayout.MARGIN, y + layout.statusLineY(),
                statusLineColor(), false);
        graphics.drawString(font, playerInventoryTitle, x + layout.playerSlotsX() - 1, y + layout.playerLabelY(),
                COLOR_DIM, false);
    }

    /** Gold for a correction, red while a rule is paused, quiet otherwise. */
    private int statusLineColor() {
        if (state.adjustment() != StockRuleAdjustment.NONE)
            return COLOR_WARNING;
        for (StockKeeperScreenState.RowView row : state.rows()) {
            if (row.paused())
                return COLOR_PAUSED;
        }
        return COLOR_DIM;
    }

    private void columnHeader(GuiGraphics graphics, String key, int field, int headerY) {
        Component label = WareworksLang.translateDirect(key);
        int x = leftPos + layout.numberX(field) + StockKeeperMenuLayout.NUMBER_WIDTH - NUMBER_PADDING
                - font.width(label);
        graphics.drawString(font, label, x, headerY, COLOR_DIM, false);
    }

    /** What the last edit had to correct, or the hint that says how a rule is written; always cut to the window. */
    private Component statusLine() {
        int room = StockKeeperMenuLayout.WIDTH - 2 * StockKeeperMenuLayout.MARGIN;
        if (state.adjustment() != StockRuleAdjustment.NONE)
            return fitTo(WareworksLang.translateDirect(WareworksLang.keeperAdjustmentKey(state.adjustment())), room);
        // Re-arming an automatic order is never silent: an edit that lifted a safety stop says so, whether the player
        // clicked the mark or simply rewrote the rule (M15 review fix).
        if (state.resumed())
            return fitTo(WareworksLang.translateDirect(WareworksLang.KEEPER_RESUMED), room);
        if (!state.linked())
            return fitTo(WareworksLang.translateDirect(WareworksLang.KEEPER_NO_WAREHOUSE), room);
        // The safety stop pushes the ordinary hint aside: it is the one thing on this screen that is waiting for the
        // player, and a hint about scrolling numbers is not what they need to read then (M15 part 2).
        for (StockKeeperScreenState.RowView row : state.rows()) {
            if (row.paused())
                return fitTo(WareworksLang.translateDirect(WareworksLang.KEEPER_PAUSED_LINE,
                        row.key().map(key -> (Component) key.toStack().getHoverName())
                                .orElseGet(() -> Component.literal(""))), room);
        }
        return fitTo(WareworksLang.translateDirect(WareworksLang.KEEPER_HINT), room);
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
        int row = rowAt(mouseX, mouseY);
        if (row == NONE)
            return tooltip;
        Optional<StockKeeperScreenState.RowView> view = state.row(row);
        int field = numberFieldAt(mouseX, row);
        if (field != NONE) {
            tooltip.add(WareworksLang.translateDirect(fieldTitleKey(field)));
            tooltip.add(WareworksLang.translateDirect(fieldHintKey(field)).copy().withStyle(ChatFormatting.GRAY));
            if (view.isPresent() && view.get().key().isPresent())
                tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_NUMBER_HINT).copy()
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            return tooltip;
        }
        if (isOverStatus(mouseX, mouseY, row) && view.isPresent() && view.get().key().isPresent()) {
            StockKeeperScreenState.RowView shown = view.get();
            tooltip.add(WareworksLang.translateDirect(WareworksLang.keeperStatusKey(shown.status())).copy()
                    .withStyle(statusFormat(shown.status())));
            addRestockLines(tooltip, shown);
            addPausedLines(tooltip, shown);
            return tooltip;
        }
        if (!isOverCell(mouseX, mouseY, row))
            return tooltip;
        if (view.isPresent() && view.get().key().isPresent()) {
            StockKeeperScreenState.RowView shown = view.get();
            tooltip.addAll(getTooltipFromItem(minecraft, shown.key().orElseThrow().toStack()));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_LIMITS,
                    amount(shown.minimum()), amount(shown.maximum()), amount(shown.reserve())).copy()
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_IN_STOCK,
                    Component.literal(LangNumberFormat.format(shown.stocked()))).copy().withStyle(ChatFormatting.GRAY));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_AVAILABLE,
                    Component.literal(LangNumberFormat.format(shown.available()))).copy()
                    .withStyle(ChatFormatting.GRAY));
            if (shown.heldBack() > 0)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_HELD_BACK,
                        Component.literal(LangNumberFormat.format(shown.heldBack()))).copy()
                        .withStyle(ChatFormatting.GRAY));
            if (shown.shortfall() > 0)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_SHORTFALL,
                        Component.literal(LangNumberFormat.format(shown.shortfall()))).copy()
                        .withStyle(ChatFormatting.GRAY));
            tooltip.add(WareworksLang.translateDirect(WareworksLang.keeperStatusKey(shown.status())).copy()
                    .withStyle(statusFormat(shown.status())));
            addRestockLines(tooltip, shown);
            addPausedLines(tooltip, shown);
        }
        tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_ITEM_HINT).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /**
     * What automatic restocking last decided about this rule, and the ingredient it is waiting for (M15 part 2,
     * issue #3).
     * <p>
     * The status above it folds several different answers back into a bare "below the minimum" — restocking switched
     * off, no pattern for the item, the order queue busy, no room for a whole run under the maximum — and each of them
     * needs a different thing from the player. This is the line that says which one it is, and for
     * {@code WAITING_FOR_INGREDIENTS} it names the item they have to supply (M15 review fix). A warehouse that never
     * restocks has nothing to say here and gets no extra line.
     */
    private void addRestockLines(List<Component> tooltip, StockKeeperScreenState.RowView shown) {
        if (!shown.hasRestockLine() || shown.restock() == RestockOutcome.PAUSED)
            return; // the paused lines below say it better, and say what it cost
        tooltip.add(WareworksLang.translateDirect(WareworksLang.keeperRestockKey(shown.restock())).copy()
                .withStyle(restockFormat(shown.restock())));
        shown.missingIngredient().ifPresent(missing -> tooltip.add(WareworksLang.translateDirect(
                WareworksLang.KEEPER_MISSING_INGREDIENT, missing.toStack().getHoverName()).copy()
                .withStyle(ChatFormatting.GOLD)));
    }

    private static ChatFormatting restockFormat(RestockOutcome outcome) {
        if (outcome == RestockOutcome.ORDERED || outcome == RestockOutcome.ORDER_OPEN
                || outcome == RestockOutcome.DEFERRED)
            return ChatFormatting.AQUA;
        return outcome.isBlocked() ? ChatFormatting.GOLD : ChatFormatting.GRAY;
    }

    /**
     * What the safety stop cost and how to lift it (M15 part 2, issue #3). Only for a paused row, and always both
     * lines: a player who is told that the warehouse stopped ordering has to be told in the same breath what it cost
     * and what to click.
     */
    private void addPausedLines(List<Component> tooltip, StockKeeperScreenState.RowView shown) {
        if (!shown.paused())
            return;
        if (shown.unrecovered() > 0)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_PAUSED_LOST,
                    Component.literal(LangNumberFormat.format(shown.unrecovered()))).copy()
                    .withStyle(ChatFormatting.RED));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.KEEPER_RESUME_HINT).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
    }

    /** One of the three numbers in a tooltip: the exact amount, or the "off" dash. */
    private static Component amount(long value) {
        return Component.literal(value == StockRule.UNSET ? OFF : LangNumberFormat.format(value));
    }

    private static ChatFormatting statusFormat(StockRuleStatus status) {
        return switch (status) {
            case BELOW_MINIMUM -> ChatFormatting.GOLD;
            case AT_MAXIMUM -> ChatFormatting.RED;
            case AT_RESERVE -> ChatFormatting.AQUA;
            case SATISFIED -> ChatFormatting.GREEN;
            case ORDERING -> ChatFormatting.AQUA;
            case WAITING_FOR_INGREDIENTS -> ChatFormatting.GOLD;
            case PAUSED -> ChatFormatting.RED;
            case SHADOWED, INERT, NO_WAREHOUSE -> ChatFormatting.GOLD;
            default -> ChatFormatting.DARK_GRAY;
        };
    }

    // --- hit testing ---------------------------------------------------------------------------------------------

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, layout.maxScroll()));
    }

    /** The rule row under the mouse, or {@link #NONE}. */
    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < leftPos + StockKeeperMenuLayout.MARGIN
                || mouseX >= leftPos + StockKeeperMenuLayout.WIDTH - StockKeeperMenuLayout.MARGIN)
            return NONE;
        for (int line = 0; line < layout.visibleRows(); line++) {
            int row = scroll + line;
            if (row >= menu.rowCount())
                break;
            int top = topPos + layout.rowY(line);
            if (mouseY >= top && mouseY < top + StockKeeperMenuLayout.ROW_HEIGHT)
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
        return mouseX >= x && mouseX < x + StockKeeperMenuLayout.SLOT && mouseY >= y
                && mouseY < y + StockKeeperMenuLayout.SLOT;
    }

    /** Whether the mouse is over the status mark of {@code row} — the resume affordance of a paused rule. */
    private boolean isOverStatus(double mouseX, double mouseY, int row) {
        int line = row - scroll;
        if (line < 0 || line >= layout.visibleRows())
            return false;
        int x = leftPos + layout.statusX();
        int y = topPos + layout.rowY(line);
        return mouseX >= x && mouseX < x + StockKeeperMenuLayout.STATUS_WIDTH && mouseY >= y
                && mouseY < y + StockKeeperMenuLayout.SLOT;
    }

    /** The number field under the mouse within {@code row}, or {@link #NONE}. */
    private int numberFieldAt(double mouseX, int row) {
        if (row == NONE)
            return NONE;
        for (int field = 0; field < StockKeeperMenuLayout.NUMBER_FIELDS; field++) {
            int x = leftPos + layout.numberX(field);
            if (mouseX >= x && mouseX < x + StockKeeperMenuLayout.NUMBER_WIDTH)
                return field;
        }
        return NONE;
    }

    private static long number(StockKeeperScreenState.RowView view, int field) {
        return switch (field) {
            case StockKeeperMenuLayout.FIELD_MINIMUM -> view.minimum();
            case StockKeeperMenuLayout.FIELD_MAXIMUM -> view.maximum();
            default -> view.reserve();
        };
    }

    private static int payloadField(int field) {
        return switch (field) {
            case StockKeeperMenuLayout.FIELD_MINIMUM -> StockKeeperRules.FIELD_MINIMUM;
            case StockKeeperMenuLayout.FIELD_MAXIMUM -> StockKeeperRules.FIELD_MAXIMUM;
            default -> StockKeeperRules.FIELD_RESERVE;
        };
    }

    private static String fieldTitleKey(int field) {
        return switch (field) {
            case StockKeeperMenuLayout.FIELD_MINIMUM -> WareworksLang.KEEPER_MINIMUM;
            case StockKeeperMenuLayout.FIELD_MAXIMUM -> WareworksLang.KEEPER_MAXIMUM;
            default -> WareworksLang.KEEPER_RESERVE;
        };
    }

    private static String fieldHintKey(int field) {
        return switch (field) {
            case StockKeeperMenuLayout.FIELD_MINIMUM -> WareworksLang.KEEPER_MINIMUM_HINT;
            case StockKeeperMenuLayout.FIELD_MAXIMUM -> WareworksLang.KEEPER_MAXIMUM_HINT;
            default -> WareworksLang.KEEPER_RESERVE_HINT;
        };
    }

    private Component fitTo(Component line, int room) {
        if (font.width(line) <= room)
            return line;
        int fits = room - font.width(CommonComponents.ELLIPSIS);
        return Component.literal(fits <= 0 ? "" : font.plainSubstrByWidth(line.getString(), fits))
                .append(CommonComponents.ELLIPSIS);
    }
}
