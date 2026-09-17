package dev.wareworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.TerminalScreenStatus;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.terminal.CountFormat;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.terminal.StockListModel;
import dev.wareworks.core.terminal.TerminalAmounts;
import dev.wareworks.core.terminal.TerminalSearch;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.network.ProductionCancelPayload;
import dev.wareworks.network.TerminalOrdersPayload;
import dev.wareworks.network.TerminalRequestPayload;
import dev.wareworks.network.TerminalResultPayload;
import dev.wareworks.network.TerminalStatusPayload;
import dev.wareworks.network.TerminalStockPayload;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.gui.UIRenderHelper;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The warehouse terminal's screen ({@code docs/warehouse-system.md} §3.4.2): a searchable, sortable grid of everything
 * the aisle holds, the terminal's own buffer as real slots, the player inventory and one status line.
 * <p>
 * <b>What it may do.</b> The screen never decides anything: it renders what the server pushed
 * ({@code TerminalStockPayload}, {@code TerminalStatusPayload}) and sends {@code TerminalRequestPayload} when a player
 * clicks an item. Which item, how much and whether it is allowed at all is settled on the server.
 * <p>
 * <b>Requesting.</b> The amount input (a Create scroll input, scroll to modify, shift scrolls faster) holds the amount
 * a plain click asks for; shift-click asks for one stack and control-click for everything that is available
 * ({@code core.terminal.TerminalAmounts}). The answer appears in the status line for a few seconds, and the line then
 * keeps showing what this terminal is still waiting for. That line owns its row and is cut with an ellipsis if it is
 * still too long ({@code docs/warehouse-system.md} §3.4.2); "Open requests" sits one row lower, beside the player
 * inventory's title.
 * <p>
 * <b>Size.</b> The window is {@value TerminalMenuLayout#WIDTH} pixels wide and as tall as
 * {@link TerminalMenuLayout#height()} says for the terminal's buffer, which always fits the 320 ×
 * {@value TerminalMenuLayout#MAX_HEIGHT} pixels vanilla guarantees at every GUI scale: a bigger buffer takes its rows
 * from the stock grid, which scrolls anyway ({@link TerminalMenuLayout}).
 */
public class WarehouseTerminalScreen extends AbstractSimiContainerScreen<WarehouseTerminalMenu> {
    /** How long the answer to a request stays in the status line. */
    private static final int FEEDBACK_TICKS = 80;
    private static final int SEARCH_MAX_LENGTH = 50;
    private static final int WIDGET_GAP = 3;
    private static final int BUTTON_SIZE = 18;
    private static final int AMOUNT_WIDTH = 34;
    private static final int TEXT_INSET = 4;
    private static final int TEXT_ROW_INSET = 5;
    private static final int ITEM_INSET = 1;
    /** Amounts are drawn at three quarters of the normal size, so four characters fit into an 18 pixel cell. */
    private static final float COUNT_SCALE = 0.75F;
    /** Width of the edit box's blinking cursor, which the empty-box hint starts behind. */
    private static final int CURSOR_WIDTH = 5;
    private static final int SCROLLBAR_WIDTH = 3;
    private static final int SCROLLBAR_INSET = 5;
    private static final int TITLE_Y = 2;
    private static final int COUNT_SHIFT_Z = 200;
    /** Usable width of a row of text: the window without its two margins. */
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    /** Free space kept between two texts that share a row. */
    private static final int TEXT_GAP = 4;
    /** One vanilla stack: how far one shift-scroll step moves the amount input (clamped to the configured maximum). */
    private static final int AMOUNT_SHIFT_STEP = 64;

    private static final int COLOR_HEADER = 0xFFFBDC7D;
    private static final int COLOR_TEXT = 0xFFEEEEEE;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_SUCCESS = 0xFF89F189;
    private static final int COLOR_ERROR = 0xFFFF8080;
    private static final int COLOR_PANEL_INSET = 0xFF1B1710;
    private static final int COLOR_SCROLLBAR_TRACK = 0x40000000;
    private static final int COLOR_SCROLLBAR_KNOB = 0xFF9A7B4F;
    private static final int COLOR_CELL_HOVER = 0x60FFFFFF;
    /** An item that is not in stock but can be produced here (M11, ADR-024). */
    private static final int COLOR_PRODUCIBLE = 0xFF89C7F1;
    /** Background of such a cell: the same blue, faint enough to leave the item itself readable. */
    private static final int COLOR_PRODUCIBLE_CELL = 0x3389C7F1;
    /** A production order that ended with ingredients already handed to a machine ({@code §3.5}). */
    private static final int COLOR_LOST = 0xFFFFAA33;
    /** The cancel affordance at the end of an open order line: a plain glyph, so every font and language has it. */
    private static final String CANCEL_MARK = "x";

    /** Search, order and filter survive closing the screen, like a storage mod's terminal. */
    private static String rememberedQuery = "";
    private static TerminalSort rememberedSort = TerminalSort.AMOUNT;
    private static boolean rememberedInStockOnly;
    private static int rememberedAmount = TerminalAmounts.MIN_AMOUNT;

    private final StockListModel<ItemKey> model = new StockListModel<>();
    private TerminalScreenStatus status = TerminalScreenStatus.NONE;
    /** The aisle's production orders, newest last, as the server last pushed them (M11, ADR-024). */
    private List<ProductionScreenState.OrderView> orders = List.of();
    private TerminalMenuLayout layout;
    private boolean stockReceived;
    private int scrollRow;
    private int selectedAmount = rememberedAmount;

    @Nullable
    private Component feedback;
    private int feedbackColor = COLOR_TEXT;
    private int feedbackTicks;

    private EditBox searchBox;
    private IconButton sortButton;
    private IconButton filterButton;
    private ScrollInput amountInput;
    private Label amountLabel;

    public WarehouseTerminalScreen(WarehouseTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        model.setSort(rememberedSort);
        model.setInStockOnly(rememberedInStockOnly);
        model.setQuery(rememberedQuery);
    }

    @Override
    protected void init() {
        layout = menu.layout();
        setWindowSize(TerminalMenuLayout.WIDTH, layout.height());
        super.init();
        clearWidgets();

        int searchWidth = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN - AMOUNT_WIDTH - 2 * BUTTON_SIZE
                - 3 * WIDGET_GAP;
        int rowY = topPos + layout.searchY();
        int searchX = leftPos + TerminalMenuLayout.MARGIN;
        int amountX = searchX + searchWidth + WIDGET_GAP;
        int sortX = amountX + AMOUNT_WIDTH + WIDGET_GAP;
        int filterX = sortX + BUTTON_SIZE + WIDGET_GAP;

        searchBox = new EditBox(font, searchX + TEXT_INSET, rowY + TEXT_ROW_INSET, searchWidth - 2 * TEXT_INSET, 9,
                WareworksLang.translateDirect(WareworksLang.TERMINAL_SEARCH));
        searchBox.setBordered(false);
        searchBox.setMaxLength(SEARCH_MAX_LENGTH);
        searchBox.setTextColor(COLOR_TEXT);
        searchBox.setValue(rememberedQuery);
        searchBox.setResponder(this::onSearchChanged);
        // Renderable, not just a child: a plain addWidget would take the typing but never draw the text.
        addRenderableWidget(searchBox);
        setFocused(searchBox);
        searchBox.setFocused(true);

        amountLabel = new Label(amountX + TEXT_INSET, rowY + TEXT_ROW_INSET, Component.empty()).colored(COLOR_TEXT)
                .withShadow();
        amountInput = new ScrollInput(amountX, rowY, AMOUNT_WIDTH, BUTTON_SIZE)
                .withRange(TerminalAmounts.MIN_AMOUNT, maxRequestAmount() + 1)
                .withShiftStep(Math.max(1, Math.min(maxRequestAmount(), AMOUNT_SHIFT_STEP)))
                .titled(WareworksLang.translateDirect(WareworksLang.OUTPUT_REQUEST_AMOUNT))
                .addHint(WareworksLang.translateDirect(WareworksLang.TERMINAL_AMOUNT_HINT))
                .format(amount -> Component.literal("x" + LangNumberFormat.format(amount)))
                .writingTo(amountLabel)
                .calling(amount -> selectedAmount = rememberedAmount = amount);
        amountInput.setState(TerminalAmounts.clamp(selectedAmount, maxRequestAmount()));
        addRenderableWidget(amountInput);
        addRenderableWidget(amountLabel);

        sortButton = new IconButton(sortX, rowY, AllIcons.I_PRIORITY_VERY_HIGH);
        sortButton.withCallback(() -> {
            setSort(model.sort().next());
            playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
        });
        addRenderableWidget(sortButton);

        filterButton = new IconButton(filterX, rowY, AllIcons.I_ACTIVE);
        filterButton.withCallback(() -> {
            setInStockOnly(!model.inStockOnly());
            playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
        });
        addRenderableWidget(filterButton);

        updateOptionButtons();
        if (menu.terminal() == null && minecraft != null && minecraft.player != null)
            minecraft.player.closeContainer(); // the block entity is gone: nothing to show and nothing to request from
    }

    // --- server updates --------------------------------------------------------------------------------------------

    /** The server sent the whole list (reset) or the entries that changed. */
    public void onStock(TerminalStockPayload payload) {
        List<StockLine<ItemKey>> lines = new ArrayList<>(payload.entries().size());
        for (StockCount<ItemKey> entry : payload.entries())
            lines.add(StockLine.of(entry, displayName(entry.key()), modId(entry.key())));
        if (payload.reset())
            model.replaceAll(lines);
        else
            model.apply(lines);
        stockReceived = true;
        clampScroll();
    }

    /** The server sent the aisle and crane state. */
    public void onStatus(TerminalStatusPayload payload) {
        status = payload.status();
    }

    /**
     * The server answered a request: show it in the status line for a few seconds. A request that was merged into this
     * terminal's open request for that item ({@code docs/warehouse-system.md} §7.2) also names the pending total, so a
     * player clicking the same item repeatedly watches one request grow instead of reading the same line ten times.
     */
    public void onResult(TerminalResultPayload payload) {
        if (payload.isAccepted()) {
            feedback = acceptedLine(payload);
            feedbackColor = COLOR_SUCCESS;
            playUiSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.6F);
        } else {
            feedback = WareworksLang.translateDirect(WareworksLang.TERMINAL_REFUSED,
                    WareworksLang.translateDirect(payload.rejection().orElseThrow().langKey()));
            feedbackColor = COLOR_ERROR;
            playUiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
        }
        feedbackTicks = FEEDBACK_TICKS;
    }

    /** The server sent the production orders of this terminal's aisle (M11, ADR-024). */
    public void onOrders(TerminalOrdersPayload payload) {
        orders = payload.orders();
    }

    /**
     * "Requested Andesite x1, waiting for 74" for an accepted request — and if the item's name makes that wider than
     * the status row, the <b>name</b> is what gets shortened ({@code docs/warehouse-system.md} §3.4.2): the two amounts
     * are what a merged answer is about (§7.2), so they must survive, while a name is still recognizable from its
     * beginning and stands in full in the grid's tooltip.
     */
    private Component acceptedLine(TerminalResultPayload payload) {
        Component name = payload.key().toStack().getHoverName();
        Component line = acceptedLine(payload, name);
        int over = font.width(line) - ROW_WIDTH;
        if (over <= 0)
            return line;
        int room = font.width(name) - over - font.width(CommonComponents.ELLIPSIS);
        // Nothing left to give: the whole line is trimmed instead, in the one place every status line passes through.
        return room <= 0 ? line : acceptedLine(payload, shorten(name.getString(), room));
    }

    private static Component acceptedLine(TerminalResultPayload payload, Component name) {
        // A request that started a production order says so: the items are not in the warehouse yet, they are being
        // made (M11, ADR-024). Otherwise a player would watch "Requested Planks x4" and see nothing arrive for a while.
        if (payload.producing() > 0)
            return WareworksLang.translateDirect(WareworksLang.TERMINAL_PRODUCING, name,
                    LangNumberFormat.format(payload.amount()), LangNumberFormat.format(payload.producing()));
        return payload.pending() > payload.amount()
                ? WareworksLang.translateDirect(WareworksLang.TERMINAL_REQUESTED_MERGED, name,
                        LangNumberFormat.format(payload.amount()), LangNumberFormat.format(payload.pending()))
                : WareworksLang.translateDirect(WareworksLang.TERMINAL_REQUESTED, name,
                        LangNumberFormat.format(payload.amount()));
    }

    // --- interaction -----------------------------------------------------------------------------------------------

    /** Sets the search text (also used by the dev harness to type into the box). */
    public void setSearch(String text) {
        searchBox.setValue(text == null ? "" : text);
    }

    /** Puts the keyboard focus back into the search box, so typed characters reach it. */
    public void focusSearch() {
        setFocused(searchBox);
        searchBox.setFocused(true);
    }

    /** Whether the answer to a request is currently shown in the status line. */
    public boolean hasFeedback() {
        return feedback != null;
    }

    /** The answer currently shown in the status line, if any (dev harness and tests of the client side). */
    public Optional<Component> feedbackLine() {
        return Optional.ofNullable(feedback);
    }

    /** The entries the grid currently shows, in the order they are drawn. */
    public List<StockLine<ItemKey>> visibleEntries() {
        return model.window(scrollRow, TerminalMenuLayout.GRID_COLUMNS, layout.gridRows());
    }

    /** All entries the search and filter keep, not only the ones on screen. */
    public List<StockLine<ItemKey>> matchingEntries() {
        return model.visible();
    }

    /** Whether the first stock payload has arrived. */
    public boolean hasStock() {
        return stockReceived;
    }

    /** The status line's data, as last pushed by the server. */
    public TerminalScreenStatus status() {
        return status;
    }

    /**
     * Requests the entry at {@code index} of the visible grid (dev harness and click handling).
     *
     * @return whether a request was sent
     */
    public boolean requestVisible(int index, TerminalAmounts.Click click) {
        List<StockLine<ItemKey>> visible = visibleEntries();
        if (index < 0 || index >= visible.size())
            return false;
        request(visible.get(index), click);
        return true;
    }

    private void request(StockLine<ItemKey> line, TerminalAmounts.Click click) {
        // "Everything" is what is available plus what the aisle could still make of it, both numbers computed on the
        // server and only reported here (M11, ADR-024): the screen knows neither the patterns nor their promises.
        int amount = TerminalAmounts.amountFor(click, selectedAmount, line.key().getMaxStackSize(), line.available(),
                line.producibleAmount(), maxRequestAmount());
        PacketDistributor.sendToServer(new TerminalRequestPayload(menu.containerId, line.key(), amount));
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.2F);
    }

    private void onSearchChanged(String text) {
        rememberedQuery = text;
        if (model.setQuery(text)) {
            scrollRow = 0;
            clampScroll();
        }
    }

    private void setSort(TerminalSort sort) {
        if (model.setSort(sort)) {
            rememberedSort = sort;
            scrollRow = 0;
            clampScroll();
        }
        updateOptionButtons();
    }

    private void setInStockOnly(boolean only) {
        if (model.setInStockOnly(only)) {
            rememberedInStockOnly = only;
            scrollRow = 0;
            clampScroll();
        }
        updateOptionButtons();
    }

    private void updateOptionButtons() {
        sortButton.setIcon(model.sort() == TerminalSort.AMOUNT ? AllIcons.I_PRIORITY_VERY_HIGH : AllIcons.I_WHITELIST);
        sortButton.setToolTip(WareworksLang.translateDirect(WareworksLang.TERMINAL_SORT,
                WareworksLang.translateDirect(model.sort().langKey())));
        filterButton.setIcon(model.inStockOnly() ? AllIcons.I_ACTIVE : AllIcons.I_PASSIVE);
        filterButton.green = model.inStockOnly();
        filterButton.setToolTip(WareworksLang.translateDirect(
                model.inStockOnly() ? WareworksLang.TERMINAL_ONLY_IN_STOCK : WareworksLang.TERMINAL_SHOW_ALL));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (feedbackTicks > 0 && --feedbackTicks == 0)
            feedback = null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && searchBox.isMouseOver(mouseX, mouseY)) {
            searchBox.setValue("");
            searchBox.setFocused(true);
            setFocused(searchBox);
            return true;
        }
        int cell = cellAt(mouseX, mouseY);
        if (cell >= 0 && button == 0) {
            TerminalAmounts.Click click = hasShiftDown() ? TerminalAmounts.Click.STACK
                    : hasControlDown() ? TerminalAmounts.Click.ALL : TerminalAmounts.Click.SELECTED;
            if (requestVisible(cell, click))
                return true;
        }
        int order = orderAt(mouseX, mouseY);
        if (order >= 0 && button == 0 && cancelVisibleOrder(order))
            return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * The search box keeps the keyboard focus while the screen is open, and Create's base class then routes every key
     * but Escape into it. That would swallow the container hotkeys on the real slots this menu has, so while the mouse
     * hovers a slot the keys that act on it — hotbar 1-9, swap, drop and pick — are handed to the container screen
     * instead, exactly as in any other inventory.
     * <p>
     * The inventory key is deliberately <b>not</b> forwarded: it is a plain letter on most keyboards and has to stay
     * usable for typing a search. Escape closes the screen, here as everywhere else.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (hoveredSlot != null && minecraft != null && getFocused() == searchBox && isSlotHotkey(keyCode, scanCode)) {
            searchBox.setFocused(false);
            setFocused(null);
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            focusSearch();
            if (handled)
                return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Whether the key is one of the vanilla bindings that act on the slot under the mouse. */
    private boolean isSlotHotkey(int keyCode, int scanCode) {
        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        Options options = minecraft.options;
        if (options.keyDrop.isActiveAndMatches(key) || options.keySwapOffhand.isActiveAndMatches(key)
                || options.keyPickItem.isActiveAndMatches(key))
            return true;
        for (KeyMapping hotbarSlot : options.keyHotbarSlots) {
            if (hotbarSlot.isActiveAndMatches(key))
                return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverGrid(mouseX, mouseY) && scrollY != 0) {
            scrollRow = model.clampScrollRow(scrollRow - (int) Math.signum(scrollY), TerminalMenuLayout.GRID_COLUMNS,
                    layout.gridRows());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        super.removed();
        rememberedAmount = selectedAmount;
    }

    // --- rendering -------------------------------------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int panelHeight = layout.height();

        // One panel for everything: grid, terminal buffer and player inventory share the brass frame.
        UIRenderHelper.drawStretched(graphics, x + 3, y + 3, TerminalMenuLayout.WIDTH - 6, panelHeight - 6, 0,
                AllGuiTextures.VALUE_SETTINGS_OUTER_BG);
        renderBrassFrame(graphics, x, y, TerminalMenuLayout.WIDTH, panelHeight);

        // Search field and amount input sit in inset boxes, so their text reads as input and not as a label.
        int rowY = y + layout.searchY();
        int searchWidth = searchBox.getWidth() + 2 * TEXT_INSET;
        graphics.fill(searchBox.getX() - TEXT_INSET, rowY + 2, searchBox.getX() - TEXT_INSET + searchWidth,
                rowY + BUTTON_SIZE - 2, COLOR_PANEL_INSET);
        graphics.fill(amountInput.getX(), rowY + 2, amountInput.getX() + AMOUNT_WIDTH, rowY + BUTTON_SIZE - 2,
                COLOR_PANEL_INSET);
        if (searchBox.getValue().isEmpty()) {
            // While the box has the keyboard focus its cursor blinks at the start, so the hint starts behind it.
            int hintX = searchBox.getX() + (searchBox.isFocused() ? CURSOR_WIDTH : 0);
            graphics.drawString(font, searchBox.getMessage(), hintX, searchBox.getY(), COLOR_DIM, false);
        }

        renderGrid(graphics, mouseX, mouseY);
        renderSlotBackgrounds(graphics);
        renderTexts(graphics);
    }

    /** Create's brass window frame ({@code ValueSettingsScreen#renderBrassFrame}), stretched to this window. */
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

    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int gridX = leftPos + layout.gridX();
        int gridY = topPos + layout.gridY();
        int cells = layout.gridCells();
        for (int cell = 0; cell < cells; cell++)
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, gridX + cellX(cell), gridY + cellY(cell));

        List<StockLine<ItemKey>> visible = visibleEntries();
        if (visible.isEmpty()) {
            Component message = !stockReceived ? WareworksLang.translateDirect(WareworksLang.TERMINAL_LOADING)
                    : model.size() == 0 ? WareworksLang.translateDirect(WareworksLang.TERMINAL_EMPTY)
                            : WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_MATCH);
            graphics.drawString(font, message, gridX + (layout.gridWidth() - font.width(message)) / 2,
                    gridY + layout.gridHeight() / 2 - font.lineHeight / 2, COLOR_DIM, false);
        }
        int hovered = cellAt(mouseX, mouseY);
        for (int cell = 0; cell < visible.size(); cell++) {
            int itemX = gridX + cellX(cell) + ITEM_INSET;
            int itemY = gridY + cellY(cell) + ITEM_INSET;
            // An item the aisle can only make gets a tinted cell as well as its "+" (M11, ADR-024): at a normal GUI
            // scale a four-pixel glyph is not what a player notices, the cell's colour is.
            if (visible.get(cell).isProducibleOnly())
                graphics.fill(itemX - ITEM_INSET, itemY - ITEM_INSET, itemX - ITEM_INSET + TerminalMenuLayout.SLOT,
                        itemY - ITEM_INSET + TerminalMenuLayout.SLOT, COLOR_PRODUCIBLE_CELL);
            if (cell == hovered)
                graphics.fill(itemX - ITEM_INSET, itemY - ITEM_INSET, itemX - ITEM_INSET + TerminalMenuLayout.SLOT,
                        itemY - ITEM_INSET + TerminalMenuLayout.SLOT, COLOR_CELL_HOVER);
            renderEntry(graphics, visible.get(cell), itemX, itemY);
        }
        renderScrollbar(graphics);
    }

    /** One item cell: the item as a Create GUI element plus its amount, compacted to at most four characters. */
    private void renderEntry(GuiGraphics graphics, StockLine<ItemKey> line, int x, int y) {
        ItemStack stack = line.key().toStack();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        GuiGameElement.of(stack).render(graphics);
        pose.popPose();
        pose.pushPose();
        pose.translate(0, 0, COUNT_SHIFT_Z);
        graphics.renderItemDecorations(font, stack, x, y, ""); // durability and cooldown, but not the stack's own count
        pose.popPose();
        // An item the aisle can produce but does not hold shows a "+" instead of a "0": it can be ordered, and the
        // warehouse will make it (M11, ADR-024).
        if (line.total() > 0L)
            renderCount(graphics, CountFormat.compact(line.total()), x, y, COLOR_TEXT);
        else if (line.producible())
            renderCount(graphics, "+", x, y, COLOR_PRODUCIBLE);
    }

    /**
     * The amount, right-aligned in the cell's lower corner and drawn at {@value #COUNT_SCALE} of the normal size: four
     * characters of the normal font are wider than a cell, so neighbouring amounts would run into each other.
     */
    private void renderCount(GuiGraphics graphics, String text, int x, int y, int color) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, COUNT_SHIFT_Z + 1);
        pose.scale(COUNT_SCALE, COUNT_SCALE, 1.0F);
        int right = Math.round((x + TerminalMenuLayout.SLOT - 2) / COUNT_SCALE) - font.width(text);
        int bottom = Math.round((y + TerminalMenuLayout.SLOT - 2) / COUNT_SCALE) - font.lineHeight;
        graphics.drawString(font, text, right, bottom, color, true);
        pose.popPose();
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int maxScroll = model.maxScrollRow(TerminalMenuLayout.GRID_COLUMNS, layout.gridRows());
        if (maxScroll <= 0)
            return;
        int trackX = leftPos + TerminalMenuLayout.WIDTH - SCROLLBAR_INSET;
        int trackTop = topPos + layout.gridY();
        int trackHeight = layout.gridHeight();
        graphics.fill(trackX, trackTop, trackX + SCROLLBAR_WIDTH, trackTop + trackHeight, COLOR_SCROLLBAR_TRACK);
        int rows = model.rowCount(TerminalMenuLayout.GRID_COLUMNS);
        int knobHeight = Math.max(TerminalMenuLayout.SLOT / 2, trackHeight * layout.gridRows() / rows);
        int knobTop = trackTop + (trackHeight - knobHeight) * scrollRow / maxScroll;
        graphics.fill(trackX, knobTop, trackX + SCROLLBAR_WIDTH, knobTop + knobHeight, COLOR_SCROLLBAR_KNOB);
    }

    /** The cell behind every real slot (terminal buffer and player inventory); vanilla draws the items on top of it. */
    private void renderSlotBackgrounds(GuiGraphics graphics) {
        for (Slot slot : menu.slots)
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    }

    private void renderTexts(GuiGraphics graphics) {
        int x = leftPos;
        int y = topPos;
        graphics.drawString(font, title, x + TerminalMenuLayout.MARGIN, y + TITLE_Y, COLOR_HEADER, false);
        Component aisle = status.hasAisle()
                ? WareworksLang.translateDirect(WareworksLang.GOGGLES_AISLE_LETTER, String.valueOf(status.aisleLetter()))
                : WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_AISLE);
        graphics.drawString(font, aisle, x + TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN - font.width(aisle),
                y + TITLE_Y, COLOR_DIM, false);

        graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.TERMINAL_BUFFER),
                x + TerminalMenuLayout.MARGIN, y + layout.bufferLabelY(), COLOR_DIM, false);
        // The server reports at most maxTerminalStockEntries item types; say so instead of silently showing fewer.
        int notShown = Math.max(0, status.itemTypes() - model.size());
        if (notShown > 0) {
            Component more = WareworksLang.translateDirect(WareworksLang.TERMINAL_NOT_SHOWN,
                    LangNumberFormat.format(notShown));
            graphics.drawString(font, more, x + TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN - font.width(more),
                    y + layout.bufferLabelY(), COLOR_DIM, false);
        }
        graphics.drawString(font, playerInventoryTitle, x + layout.playerSlotsX() - 1, y + layout.playerLabelY(),
                COLOR_DIM, false);

        // The status line owns its row: it carries an item name and up to two amounts in every language, which no
        // width budget shared with a second text could hold (§3.4.2, M7 review fix).
        int color = feedback != null ? feedbackColor : COLOR_TEXT;
        graphics.drawString(font, fitToRow(shownStatusLine()), x + TerminalMenuLayout.MARGIN, y + layout.statusY(),
                color, false);
        renderProduction(graphics, x, y);
        // "Open requests" is short and constant, so it shares the row below with the player inventory's title.
        Component requests = openRequestsLine();
        graphics.drawString(font, requests, x + openRequestsX(requests), y + layout.playerLabelY(), COLOR_DIM, false);
    }

    /** "Open requests: N" for the whole aisle, right-aligned in the player inventory's label row. */
    private Component openRequestsLine() {
        return WareworksLang.translateDirect(WareworksLang.GOGGLES_OPEN_REQUESTS,
                LangNumberFormat.format(status.openRequests()));
    }

    private int openRequestsX(Component requests) {
        return TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN - font.width(requests);
    }

    // --- production orders (M11, ADR-024) ----------------------------------------------------------------------------

    /**
     * The production section: what this aisle is currently making. An order started at a terminal runs at a production
     * station and takes as long as the player's machine takes, so without this section a player would order something
     * producible and then watch an empty status line ({@code docs/warehouse-system.md} §3.4.2).
     * <p>
     * The section is always drawn, even when there is no order, because its rows are part of the fixed window layout
     * ({@link TerminalMenuLayout}); only a terminal whose buffer left no room for it has none at all.
     */
    private void renderProduction(GuiGraphics graphics, int x, int y) {
        if (layout.orderLines() <= 0)
            return;
        graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.TERMINAL_PRODUCTION),
                x + TerminalMenuLayout.MARGIN, y + layout.productionLabelY(), COLOR_DIM, false);
        List<ProductionScreenState.OrderView> shown = visibleOrders();
        if (shown.isEmpty()) {
            graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_NO_ORDERS),
                    x + TerminalMenuLayout.MARGIN, y + layout.orderLineY(0), COLOR_DIM, false);
            return;
        }
        for (int line = 0; line < shown.size(); line++) {
            ProductionScreenState.OrderView order = shown.get(line);
            int top = y + layout.orderLineY(line);
            int color = order.lostIngredients() ? COLOR_LOST : COLOR_TEXT;
            // The line is drawn in two parts, and the state is the right-aligned one: it is what a player reads the
            // section for and what actually changes, so an item with a long name must never be able to cut it off.
            // The item and its amount are trimmed into whatever is left, and the tooltip holds both in full.
            Component state = orderStateText(order);
            // A finished order has no cancel mark, so its line gets that column back for its own text.
            int reserved = order.state().isFinished() ? 0 : cancelColumnWidth();
            int stateX = Math.max(TerminalMenuLayout.MARGIN,
                    TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN - reserved - font.width(state));
            graphics.drawString(font, state, x + stateX, top, color, false);
            graphics.drawString(font, fitTo(orderItemText(order), stateX - TerminalMenuLayout.MARGIN - TEXT_GAP),
                    x + TerminalMenuLayout.MARGIN, top, color, false);
            // Only an open order can be given up on; a finished line stays for a while so it can be read.
            if (!order.state().isFinished())
                graphics.drawString(font, CANCEL_MARK, x + cancelMarkX(), top, COLOR_ERROR, false);
        }
    }

    /** "Oak Planks x128": what the order makes, the part that gives way when the row is too narrow. */
    private static Component orderItemText(ProductionScreenState.OrderView order) {
        return WareworksLang.translateDirect(WareworksLang.GOGGLES_ITEM_COUNT, order.result().toStack().getHoverName(),
                LangNumberFormat.format(order.amount()));
    }

    /**
     * Where the order stands. An order that ended with ingredients already handed to a machine carries "not recovered"
     * here, so the boundary is on the line itself and not only in a tooltip ({@code docs/warehouse-system.md} §3.5.4).
     */
    private static Component orderStateText(ProductionScreenState.OrderView order) {
        Component state = WareworksLang.translateDirect(order.state().langKey());
        return order.lostIngredients() ? WareworksLang.translateDirect(WareworksLang.TERMINAL_ORDER_LOST, state)
                : state;
    }

    private static Component orderText(ProductionScreenState.OrderView order, Component name, Component amount,
            Component state) {
        return order.lostIngredients()
                ? WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER_LOST, name, amount, state)
                : WareworksLang.translateDirect(WareworksLang.PRODUCTION_ORDER, name, amount, state);
    }

    /** The orders the window has room for, newest first (the ones a player just placed). */
    public List<ProductionScreenState.OrderView> visibleOrders() {
        List<ProductionScreenState.OrderView> shown = new ArrayList<>(Math.max(0, layout.orderLines()));
        for (int line = 0; line < layout.orderLines() && line < orders.size(); line++)
            shown.add(orders.get(orders.size() - 1 - line));
        return List.copyOf(shown);
    }

    /** Every production order the server last pushed, newest last (dev harness and tests of the client side). */
    public List<ProductionScreenState.OrderView> productionOrders() {
        return orders;
    }

    /**
     * Asks the server to give up on the order shown in line {@code index} (dev harness and click handling). The server
     * decides: the payload carries only the order's id, and the terminal re-validates reach, aisle and order
     * ({@code WarehouseTerminalBlockEntity#cancelProductionOrder}).
     *
     * @return whether a cancellation was sent
     */
    public boolean cancelVisibleOrder(int index) {
        List<ProductionScreenState.OrderView> shown = visibleOrders();
        if (index < 0 || index >= shown.size())
            return false;
        ProductionScreenState.OrderView order = shown.get(index);
        if (order.state().isFinished())
            return false;
        PacketDistributor.sendToServer(new ProductionCancelPayload(menu.containerId, order.id()));
        playUiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
        return true;
    }

    /** The index within {@link #visibleOrders()} of the order line under the mouse, or -1. */
    private int orderAt(double mouseX, double mouseY) {
        if (layout.orderLines() <= 0 || mouseX < leftPos + TerminalMenuLayout.MARGIN
                || mouseX >= leftPos + TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN)
            return -1;
        int shown = visibleOrders().size();
        for (int line = 0; line < shown; line++) {
            int top = topPos + layout.orderLineY(line);
            if (mouseY >= top && mouseY < top + TerminalMenuLayout.LABEL_HEIGHT)
                return line;
        }
        return -1;
    }

    private int cancelMarkX() {
        return TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN - font.width(CANCEL_MARK);
    }

    /** Room the cancel mark takes at the end of an order line, including the gap before it. */
    private int cancelColumnWidth() {
        return font.width(CANCEL_MARK) + TEXT_GAP;
    }

    /**
     * Shortens a line that is wider than its row. The status line is the only text of the window whose width nothing
     * bounds — item names, translations and four-digit amounts are all unbounded — so this is the backstop that keeps
     * it inside the frame in every language ({@code docs/warehouse-system.md} §3.4.2).
     */
    private Component fitToRow(Component line) {
        return fitTo(line, ROW_WIDTH);
    }

    /** {@code line} cut to {@code width} pixels if it is wider, with an ellipsis marking what was cut off. */
    private Component fitTo(Component line, int width) {
        return font.width(line) <= width ? line
                : shorten(line.getString(), width - font.width(CommonComponents.ELLIPSIS));
    }

    /** {@code text} cut to {@code width} pixels, with an ellipsis marking what was cut off. */
    private Component shorten(String text, int width) {
        return Component.literal(width <= 0 ? "" : font.plainSubstrByWidth(text, width))
                .append(CommonComponents.ELLIPSIS);
    }

    /** The status line as it is drawn: the answer to the last request while one shows, else the terminal's state. */
    public Component shownStatusLine() {
        return feedback != null ? feedback : statusLine();
    }

    /**
     * Whether both texts below the buffer are drawn in full: the status line in its own row, and "Open requests" beside
     * the player inventory's title without touching it. The visual scenario asserts it — before M7's longer merged
     * answer the two shared one row, and the status line then rendered straight through the request count.
     */
    public boolean statusTextsFit() {
        Component requests = openRequestsLine();
        int titleRight = layout.playerSlotsX() - 1 + font.width(playerInventoryTitle);
        return font.width(shownStatusLine()) <= ROW_WIDTH && openRequestsX(requests) >= titleRight + TEXT_GAP;
    }

    /** "Crane: travelling to the source", "Crane: paused (no rotation)" or what this terminal is still waiting for. */
    private Component statusLine() {
        if (status.requestsHere() > 0)
            return WareworksLang.translateDirect(WareworksLang.TERMINAL_WAITING,
                    LangNumberFormat.format(status.requestedHere()), LangNumberFormat.format(status.deliveredHere()));
        if (!status.hasAisle())
            return WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_AISLE);
        if (!status.craneLinked())
            return WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_CRANE);
        Component state = status.isPaused() ? WareworksLang.translateDirect(status.cranePause().langKey())
                : WareworksLang.translateDirect(WareworksLang.cranePhaseKey(status.cranePhase()));
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_CRANE, state);
    }

    @Override
    protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderForeground(graphics, mouseX, mouseY, partialTicks);
        int cell = cellAt(mouseX, mouseY);
        List<StockLine<ItemKey>> visible = visibleEntries();
        if (cell >= 0 && cell < visible.size()) {
            graphics.renderComponentTooltip(font, itemTooltip(visible.get(cell)), mouseX, mouseY);
            return;
        }
        int order = orderAt(mouseX, mouseY);
        List<ProductionScreenState.OrderView> shownOrders = visibleOrders();
        if (order >= 0 && order < shownOrders.size())
            graphics.renderComponentTooltip(font, orderTooltip(shownOrders.get(order)), mouseX, mouseY);
    }

    /** The exact amounts behind a cell's compacted number, and what the aisle could make of the item. */
    private List<Component> itemTooltip(StockLine<ItemKey> line) {
        List<Component> tooltip = new ArrayList<>(getTooltipFromItem(minecraft, line.key().toStack()));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_STORED,
                LangNumberFormat.format(line.total())).copy().withStyle(ChatFormatting.GRAY));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_AVAILABLE,
                LangNumberFormat.format(line.available())).copy().withStyle(ChatFormatting.GOLD));
        if (line.reserved() > 0)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_RESERVED,
                    LangNumberFormat.format(line.reserved())).copy().withStyle(ChatFormatting.DARK_GRAY));
        if (line.producible()) {
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_PRODUCIBLE).copy()
                    .withStyle(ChatFormatting.AQUA));
            // "Can be produced here" and "can be made right now" are different statements: a pattern whose ingredients
            // are missing says the first and not the second (M11, ADR-024).
            if (line.producibleAmount() > 0)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_PRODUCIBLE_AMOUNT,
                        LangNumberFormat.format(line.producibleAmount())).copy().withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_AMOUNT_HINT).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    /** The full order line (the drawn one may be cut), what it still waits for, and how to give up on it. */
    private List<Component> orderTooltip(ProductionScreenState.OrderView order) {
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(orderText(order, order.result().toStack().getHoverName(),
                Component.literal(LangNumberFormat.format(order.amount())),
                WareworksLang.translateDirect(order.state().langKey())));
        if (order.missing() > 0)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_MISSING,
                    LangNumberFormat.format(order.missing())).copy().withStyle(ChatFormatting.GRAY));
        if (order.lostIngredients())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_INGREDIENTS_LOST).copy()
                    .withStyle(ChatFormatting.GOLD));
        if (!order.state().isFinished())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_CANCEL_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private void clampScroll() {
        scrollRow = model.clampScrollRow(scrollRow, TerminalMenuLayout.GRID_COLUMNS, layout.gridRows());
    }

    private boolean isOverGrid(double mouseX, double mouseY) {
        int gridX = leftPos + layout.gridX();
        int gridY = topPos + layout.gridY();
        return mouseX >= gridX && mouseX < gridX + layout.gridWidth() && mouseY >= gridY
                && mouseY < gridY + layout.gridHeight();
    }

    /** The index of the grid cell under the mouse, or -1. */
    private int cellAt(double mouseX, double mouseY) {
        if (!isOverGrid(mouseX, mouseY))
            return -1;
        int column = ((int) mouseX - leftPos - layout.gridX()) / TerminalMenuLayout.SLOT;
        int row = ((int) mouseY - topPos - layout.gridY()) / TerminalMenuLayout.SLOT;
        return row * TerminalMenuLayout.GRID_COLUMNS + column;
    }

    private static int cellX(int cell) {
        return cell % TerminalMenuLayout.GRID_COLUMNS * TerminalMenuLayout.SLOT;
    }

    private static int cellY(int cell) {
        return cell / TerminalMenuLayout.GRID_COLUMNS * TerminalMenuLayout.SLOT;
    }

    private static int maxRequestAmount() {
        return Math.max(TerminalAmounts.MIN_AMOUNT, WareworksConfig.maxTerminalRequestAmount());
    }

    /** The item's name as the player reads it; the search matches on it ({@link TerminalSearch}). */
    private static String displayName(ItemKey key) {
        return key.toStack().getHoverName().getString();
    }

    private static String modId(ItemKey key) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(key.getItem());
        return id == null ? "" : id.getNamespace();
    }

    /** The screen's list model (dev harness and tests of the client side). */
    public Optional<StockLine<ItemKey>> entry(ItemKey key) {
        return model.find(key);
    }
}
