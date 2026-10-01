package dev.wareworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.CountFormat;
import dev.wareworks.core.terminal.PlanCancelCost;
import dev.wareworks.core.terminal.PlanLine;
import dev.wareworks.core.terminal.PlanLines;
import dev.wareworks.core.terminal.PlanMember;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.terminal.StockListModel;
import dev.wareworks.core.terminal.TerminalAmounts;
import dev.wareworks.core.terminal.TerminalSearch;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.network.ProductionCancelPayload;
import dev.wareworks.network.TerminalConfirmPayload;
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
import net.minecraft.util.FormattedCharSequence;
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
    /**
     * The badge of a cell a stock rule governs (M15, issue #3), in the corner opposite the amount: gold while the
     * warehouse is short of the item, red while it accepts no more of it, and a quiet blue-grey while the rule is
     * simply there. It is a square rather than a glyph so that it reads at every GUI scale and in every language.
     */
    private static final int RULE_BADGE_SIZE = 3;
    private static final int COLOR_RULE = 0xFF9FB4C7;
    private static final int COLOR_RULE_BELOW_MINIMUM = 0xFFFBDC7D;
    private static final int COLOR_RULE_AT_MAXIMUM = 0xFFFF8080;
    private static final int COLOR_RULE_AT_RESERVE = 0xFF89C7F1;
    /** The cancel affordance at the end of an open order line: a plain glyph, so every font and language has it. */
    private static final String CANCEL_MARK = "x";
    /**
     * Smallest width the item half of an order line keeps. The state half wins the row (see {@link #renderOrderLine}),
     * but a chain's line has a badge between them, and a name cut down to three letters names nothing at all — so below
     * this the <b>state</b> is what gives way instead, and the tooltip still holds both in full.
     */
    private static final int MIN_ITEM_WIDTH = 54;

    // --- the confirmation panel (M15 part 2, issue #3) -------------------------------------------------------------
    /** Dim behind the panel, so the grid underneath is visibly out of reach while the question is up. */
    private static final int COLOR_CONFIRM_SHADE = 0xC0101014;
    private static final int COLOR_CONFIRM_BG = 0xFF2A2A31;
    private static final int COLOR_CONFIRM_BORDER = 0xFFFF8040;
    private static final int COLOR_CONFIRM_BUTTON = 0xFF3C3C46;
    private static final int COLOR_CONFIRM_BUTTON_HOVER = 0xFF56565F;
    private static final int CONFIRM_PADDING = 6;
    private static final int CONFIRM_BUTTON_HEIGHT = 14;
    private static final int CONFIRM_BUTTON_PADDING = 8;
    private static final int CONFIRM_BUTTON_GAP = 6;

    // --- the step panel of a production plan (M20, issue #4, ADR-032) ----------------------------------------------
    /**
     * The step panel is the confirmation's device — grid dimmed and out of reach, Escape drops it — and deliberately
     * reuses its padding and buttons, so the two dialogs of this screen are one shape. Only the border differs: this
     * panel <b>informs</b> about a chain rather than asking a question, so it takes the blue of a producible item
     * instead of the question's orange.
     */
    private static final int COLOR_STEPS_BORDER = COLOR_PRODUCIBLE;
    /**
     * How far in front of the window both modal panels are drawn.
     * <p>
     * A stock cell draws its item, its amount and its rule badge in front of the window ({@link #COUNT_SHIFT_Z}), and a
     * panel at depth 0 therefore had the grid's <b>items and numbers on top of its own text</b> — which the step panel
     * made plain the moment a third cost line pushed it over a filled row. The value is deliberately far past every one
     * of them rather than a little above: a cell's amount is drawn as text, and text is batched separately from a fill,
     * so the margin has to hold for the batch as well as for the depth test. A panel is modal, so nothing that would
     * belong above it is drawn at all while it is up ({@link #renderForeground}).
     */
    private static final int PANEL_Z = 1000;
    /** Step rows the panel lists at most, however many orders a chain has; the rest are counted in one line. */
    private static final int MAX_STEP_ROWS = 12;
    /**
     * Widest indent a step row is given, in spaces. A deeper step keeps the deepest indent instead of a wider one: past
     * about a dozen levels the panel's own width is the limit, and this keeps a depth a payload claims from ever becoming
     * a length ({@link ProductionScreenState#MAX_DEPTH} bounds the number, this bounds the string).
     */
    private static final int MAX_STEP_INDENT = 2 * MAX_STEP_ROWS;

    /** Search, order and filter survive closing the screen, like a storage mod's terminal. */
    private static String rememberedQuery = "";
    private static TerminalSort rememberedSort = TerminalSort.AMOUNT;
    private static boolean rememberedInStockOnly;
    private static int rememberedAmount = TerminalAmounts.MIN_AMOUNT;

    private final StockListModel<ItemKey> model = new StockListModel<>();
    private TerminalScreenStatus status = TerminalScreenStatus.NONE;
    /** The aisle's production orders, newest last, as the server last pushed them (M11, ADR-024). */
    private List<ProductionScreenState.OrderView> orders = List.of();
    /**
     * The same orders folded into the lines the section draws: one line per production plan, one per ordinary order
     * (M20, issue #4, ADR-032). Built whenever a payload arrives, never per frame.
     */
    private List<PlanLine> planLines = List.of();
    /**
     * The same orders as the models the grouping and the cancel price are computed from ({@link PlanMember}), built with
     * {@link #planLines} and from the same payload, so a line and its price can never be read from different orders.
     */
    private List<PlanMember> planMembers = List.of();
    /** The production plan whose step panel is up, or {@code null} while none is (M20). */
    @Nullable
    private UUID openPlan;
    private TerminalMenuLayout layout;
    private boolean stockReceived;
    private int scrollRow;
    private int selectedAmount = rememberedAmount;

    @Nullable
    private Component feedback;
    private int feedbackColor = COLOR_TEXT;
    private int feedbackTicks;
    /**
     * The question the <b>server</b> put up, or {@code null} while it is asking nothing (M15 part 2, issue #3). While it
     * is set the grid is out of reach and every click and key belongs to the question.
     */
    @Nullable
    private RequestConfirmation<ItemKey> confirming;
    /**
     * The question the player has just answered, kept until the server has answered in turn. A second question for the
     * same item means the warehouse moved in between and the request now costs more than was accepted, which the panel
     * says in a line of its own instead of silently asking the same thing twice.
     */
    @Nullable
    private RequestConfirmation<ItemKey> confirmed;
    private boolean costChanged;
    /**
     * The wrapped text and the geometry of {@link #confirming}, built once and dropped whenever anything it is built
     * from changes: the question itself, {@link #costChanged}, and the window layout ({@link #init()}).
     */
    @Nullable
    private ConfirmPanel panel;
    /** The rows and geometry of {@link #openPlan}'s panel, built once per order payload like {@link #panel}. */
    @Nullable
    private StepPanel stepPanel;

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
        // Both panels' rectangles are absolute and built from leftPos/topPos, which super.init() has just recomputed: a
        // resize or a GUI-scale change would otherwise leave a modal dialog drawn and hit-tested where the window was.
        panel = null;
        stepPanel = null;

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
        // The answer to a confirmed request: whatever it says, the question is settled (M15 part 2).
        confirmed = null;
        costChanged = false;
        // A click the racks served only part of carries the reason the rest could not be made (M20, issue #4): the
        // chain behind it was refused, and that sentence is what a player can act on, while "Requested Chest x1" is
        // already confirmed by the chest arriving. The row holds one line, so the surprising half wins it — in gold,
        // because something *was* ordered, and with the sound of an accepted click.
        Component planned = payload.isAccepted() ? planRefusalLine(payload) : null;
        if (payload.isAccepted()) {
            feedback = planned != null ? planned : acceptedLine(payload);
            feedbackColor = planned != null ? COLOR_LOST : COLOR_SUCCESS;
            playUiSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.6F);
        } else {
            feedback = refusedLine(payload);
            feedbackColor = COLOR_ERROR;
            playUiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
        }
        feedbackTicks = FEEDBACK_TICKS;
    }

    /**
     * The server sent the production orders of this terminal's aisle (M11, ADR-024).
     * <p>
     * They are folded into lines here and not while drawing (M20, ADR-032): a chain is one line, and the section, the
     * step panel and the tooltips all read that same grouping, so the badge, the panel and the cancel affordance can
     * never disagree about which orders belong to which chain. An open panel whose chain is no longer in the payload —
     * finished and pruned, or pushed out by newer orders — closes by itself rather than showing a chain that is gone.
     */
    public void onOrders(TerminalOrdersPayload payload) {
        orders = payload.orders();
        planMembers = ProductionScreenState.members(orders);
        planLines = PlanLines.of(planMembers);
        stepPanel = null;
        if (openPlan != null && PlanLines.byPlan(planLines, openPlan).isEmpty())
            openPlan = null;
    }

    /**
     * Why a request was refused, in the one line a player gets.
     * <p>
     * A refusal that walked a <b>production chain</b> says what the chain really ran into and <b>names the item</b> it
     * ran into it with (M20, issue #4, ADR-032) — which is regularly not the item that was clicked: ordering a chest
     * is refused because oak logs are missing, and "not in stock" would send the player looking at the chest. Every
     * other refusal keeps the sentence it has had since M6.
     * <p>
     * The chain's sentence replaces the {@code "Request refused: …"} frame instead of filling it, because it has to
     * fit the status row with an item's name inside it ({@code docs/warehouse-system.md} §3.4.2): the row is red
     * already, so the frame would spend a third of the row on saying what the colour says. If even the short sentence
     * is too wide, the <b>name</b> gives way, exactly as it does in an accepted line — the reason is what a player
     * acts on, and a name is still recognizable from its beginning.
     */
    private Component refusedLine(TerminalResultPayload payload) {
        Component planned = planRefusalLine(payload);
        return planned != null ? planned
                : WareworksLang.translateDirect(WareworksLang.TERMINAL_REFUSED,
                        WareworksLang.translateDirect(payload.rejection().orElseThrow().langKey()));
    }

    /**
     * The chain's own sentence with the item it names, or {@code null} when this answer walked no plan. If the line is
     * too wide for the status row the <b>name</b> gives way, exactly as it does in an accepted line.
     */
    private Component planRefusalLine(TerminalResultPayload payload) {
        PlanRefusal refusal = payload.refusal().orElse(null);
        ItemKey about = payload.about().orElse(null);
        if (refusal == null || about == null)
            return null;
        Component name = about.toStack().getHoverName();
        Component line = WareworksLang.translateDirect(refusal.langKey(), name);
        int over = font.width(line) - ROW_WIDTH;
        if (over <= 0)
            return line;
        int room = font.width(name) - over - font.width(CommonComponents.ELLIPSIS);
        return room <= 0 ? line
                : WareworksLang.translateDirect(refusal.langKey(), shorten(name.getString(), room));
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
        return requestVisible(index, click, false);
    }

    /**
     * Requests the entry at {@code index} of the visible grid, optionally skipping the confirmation (Alt).
     *
     * @return whether a request was sent
     */
    public boolean requestVisible(int index, TerminalAmounts.Click click, boolean skipQuestion) {
        List<StockLine<ItemKey>> visible = visibleEntries();
        if (index < 0 || index >= visible.size())
            return false;
        request(visible.get(index), click, skipQuestion);
        return true;
    }

    private void request(StockLine<ItemKey> line, TerminalAmounts.Click click) {
        request(line, click, false);
    }

    /**
     * Requests one item.
     * <p>
     * Whether the click crosses a boundary a player set themselves is decided on the server, which alone knows what a
     * production order would spend (M15 part 2). The screen only says what the player has agreed to so far: nothing on
     * a plain click, "whatever it costs" when they held the skip modifier.
     * <p>
     * <b>The skip is Alt, and not Ctrl.</b> Ctrl already means "everything available" here, so a hint that sent a player
     * to it would quietly change the amount as well as skipping the question — two things from one key, one of them
     * unmentioned (M15 review fix). Alt does one thing, Shift and Ctrl keep doing theirs, and the three combine.
     *
     * @param skipQuestion whether the player held the skip modifier, i.e. said "do not ask" in advance
     */
    private void request(StockLine<ItemKey> line, TerminalAmounts.Click click, boolean skipQuestion) {
        // "Everything" is what is available plus what the aisle could still make of it, both numbers computed on the
        // server and only reported here (M11, ADR-024): the screen knows neither the patterns nor their promises.
        int amount = TerminalAmounts.amountFor(click, selectedAmount, line.key().getMaxStackSize(), line.available(),
                line.producibleAmount(), maxRequestAmount());
        send(line.key(), amount, skipQuestion ? RequestAcknowledgement.ANY : RequestAcknowledgement.NONE);
    }

    /** Sends a request with what the player has accepted for it; the server decides what that is worth. */
    private void send(ItemKey key, int amount, RequestAcknowledgement acknowledged) {
        PacketDistributor.sendToServer(new TerminalRequestPayload(menu.containerId, key, amount, acknowledged));
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
        // The question owns every click while it is up: the grid behind it is deliberately out of reach, so a player
        // cannot answer it by accident with the click they were about to make (M15 part 2).
        if (confirming != null) {
            if (button == 0 && isOverConfirmButton(mouseX, mouseY, true))
                confirmRequest();
            else if (button == 0 && isOverConfirmButton(mouseX, mouseY, false))
                cancelConfirmation();
            return true;
        }
        // The step panel owns every click while it is up, for the same reason (M20): one of its two buttons ends a whole
        // chain, and a click meant for the grid must not be able to land on it.
        if (openPlan != null) {
            if (button == 0 && isOverStepButton(mouseX, mouseY, true))
                cancelOpenPlan();
            else if (button == 0 && isOverStepButton(mouseX, mouseY, false))
                closeStepPanel();
            return true;
        }
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
            // Alt is the skip and nothing else, so it composes with either amount modifier (M15 review fix).
            if (requestVisible(cell, click, hasAltDown()))
                return true;
        }
        int order = orderAt(mouseX, mouseY);
        if (order >= 0 && button == 0 && clickOrderLine(order, mouseX))
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
        // Enter confirms, Escape drops it — and Escape must not close the whole screen while a question is up.
        if (confirming != null) {
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER)
                confirmRequest();
            else if (keyCode == InputConstants.KEY_ESCAPE)
                cancelConfirmation();
            return true;
        }
        // Escape drops the step panel instead of closing the whole screen. Enter does deliberately nothing here: the
        // panel's first button gives up on a chain, and that must be a click a player aimed at (M20).
        if (openPlan != null) {
            if (keyCode == InputConstants.KEY_ESCAPE)
                closeStepPanel();
            return true;
        }
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
        // warehouse will make it (M11, ADR-024). An item a stock rule governs shows a plain, dimmed "0", because an
        // empty cell reads as "nothing here" while the truth is "none, and the warehouse wants some" (M15 part 2).
        if (line.total() > 0L)
            renderCount(graphics, CountFormat.compact(line.total()), x, y, COLOR_TEXT);
        else if (line.producible())
            renderCount(graphics, "+", x, y, COLOR_PRODUCIBLE);
        else if (line.ruled())
            renderCount(graphics, "0", x, y, COLOR_RULE);
        line.rule().ifPresent(status -> renderRuleBadge(graphics, status, x, y));
    }

    /**
     * The stock rule badge in the cell's upper left corner — the opposite corner from the amount, so the two never
     * overlap however wide the number is. The colour says which of the three numbers is biting; the tooltip says it
     * in words (M15, issue #3).
     */
    private void renderRuleBadge(GuiGraphics graphics, StockRuleStatus status, int x, int y) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, COUNT_SHIFT_Z + 1);
        graphics.fill(x, y, x + RULE_BADGE_SIZE, y + RULE_BADGE_SIZE, ruleColor(status));
        pose.popPose();
    }

    private static int ruleColor(StockRuleStatus status) {
        return switch (status) {
            case BELOW_MINIMUM -> COLOR_RULE_BELOW_MINIMUM;
            case AT_MAXIMUM -> COLOR_RULE_AT_MAXIMUM;
            case AT_RESERVE -> COLOR_RULE_AT_RESERVE;
            default -> COLOR_RULE;
        };
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
                ? WareworksLang.translateDirect(WareworksLang.GOGGLES_WAREHOUSE_LETTER, String.valueOf(status.aisleLetter()))
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
        List<PlanLine> shown = visibleOrderLines();
        if (shown.isEmpty()) {
            graphics.drawString(font, WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_NO_ORDERS),
                    x + TerminalMenuLayout.MARGIN, y + layout.orderLineY(0), COLOR_DIM, false);
            return;
        }
        for (int line = 0; line < shown.size(); line++)
            renderOrderLine(graphics, x, y + layout.orderLineY(line), shown.get(line));
    }

    /**
     * One line of the section: an ordinary order exactly as before M20, or a whole chain in one line.
     * <p>
     * The line is drawn in up to four parts, right to left, because the right-hand ones are what a player reads the
     * section for and what actually changes: the cancel mark, the state, the step badge of a chain, and finally the item
     * and its amount trimmed into whatever is left. An item with a long name can therefore never cut off the part that
     * says how far the order has got — but only down to {@link #MIN_ITEM_WIDTH}, past which the state gives way
     * instead, because a chain's line carries a badge as well and a name cut to three letters names nothing. The
     * tooltip and the step panel hold everything in full either way.
     * <p>
     * For a chain the state column holds the <b>frontier</b> — the item the step where something is really happening is
     * making — and not the ordered item's own state. "Waiting for ingredients" about a chest is the one thing a player
     * clicking a chest already knows; "now: Oak Planks" is what changes.
     */
    private void renderOrderLine(GuiGraphics graphics, int x, int top, PlanLine line) {
        ProductionScreenState.OrderView head = order(line.head()).orElse(null);
        if (head == null)
            return; // the payload no longer holds the order this line names: nothing honest to draw
        // Once every step is done and only the ordered item's own order is left, its own state is the whole story again.
        ProductionScreenState.OrderView frontier = line.isChain() && !line.frontierIsHead()
                ? line.frontier().flatMap(this::order).orElse(null)
                : null;
        int color = head.lostIngredients() ? COLOR_LOST : COLOR_TEXT;
        int stateColor = frontier == null ? color : frontier.lostIngredients() ? COLOR_LOST : COLOR_PRODUCIBLE;
        // A finished order has no cancel mark, so its line gets that column back for its own text.
        int reserved = head.state().isFinished() ? 0 : cancelColumnWidth();
        Component badge = line.isChain() ? stepBadge(line) : null;
        int badgeWidth = badge == null ? 0 : font.width(badge) + TEXT_GAP;
        Component item = orderItemText(head);
        // The state wins the row, but never past the item's floor: a chain's badge sits between the two, and a name cut
        // to three letters names nothing.
        int floor = Math.min(font.width(item), MIN_ITEM_WIDTH);
        Component state = fitTo(frontier == null ? orderStateText(head) : frontierText(frontier),
                Math.max(0, ROW_WIDTH - reserved - badgeWidth - floor - TEXT_GAP));
        int stateX = Math.max(TerminalMenuLayout.MARGIN,
                TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN - reserved - font.width(state));
        graphics.drawString(font, state, x + stateX, top, stateColor, false);
        // The badge takes the window's own label colour and not the blue of the frontier beside it: the two sit next to
        // each other on the row, and one colour for both would read as a single sentence.
        if (badge != null)
            graphics.drawString(font, badge, x + Math.max(TerminalMenuLayout.MARGIN, stateX - badgeWidth), top,
                    COLOR_HEADER, false);
        int room = stateX - badgeWidth - TerminalMenuLayout.MARGIN - TEXT_GAP;
        graphics.drawString(font, fitTo(item, Math.max(0, room)), x + TerminalMenuLayout.MARGIN, top, color, false);
        // Only an open order can be given up on; a finished line stays for a while so it can be read.
        if (!head.state().isFinished())
            graphics.drawString(font, CANCEL_MARK, x + cancelMarkX(), top, COLOR_ERROR, false);
    }

    /** {@code "steps: 3"}: the badge of a chain's line, which opens its step panel. */
    private static Component stepBadge(PlanLine line) {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_STEPS,
                LangNumberFormat.format(line.members().size()));
    }

    /**
     * {@code "now: Oak Planks"}: what the chain's frontier step is making, or — once the chain is down to the ordered
     * item's own order — that order's own state, because then there is no earlier step left to name.
     */
    private static Component frontierText(ProductionScreenState.OrderView frontier) {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_FRONTIER,
                frontier.result().toStack().getHoverName());
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

    /** The lines the window has room for, newest first: one per chain, one per ordinary order (M20). */
    public List<PlanLine> visibleOrderLines() {
        List<PlanLine> shown = new ArrayList<>(Math.max(0, layout.orderLines()));
        for (int line = 0; line < layout.orderLines() && line < planLines.size(); line++)
            shown.add(planLines.get(planLines.size() - 1 - line));
        return List.copyOf(shown);
    }

    /** Every line the server's orders were folded into, oldest first (dev harness and tests of the client side). */
    public List<PlanLine> orderLines() {
        return planLines;
    }

    /**
     * The orders the window has room for, newest first (the ones a player just placed): for a chain the order a player
     * actually asked for, since that is the one its line names and the one a cancellation is sent for.
     */
    public List<ProductionScreenState.OrderView> visibleOrders() {
        List<ProductionScreenState.OrderView> shown = new ArrayList<>(Math.max(0, layout.orderLines()));
        for (PlanLine line : visibleOrderLines())
            order(line.head()).ifPresent(shown::add);
        return List.copyOf(shown);
    }

    /** Every production order the server last pushed, newest last (dev harness and tests of the client side). */
    public List<ProductionScreenState.OrderView> productionOrders() {
        return orders;
    }

    /** The order row with this id, if the last payload holds it. */
    private Optional<ProductionScreenState.OrderView> order(UUID id) {
        if (id == null)
            return Optional.empty();
        for (ProductionScreenState.OrderView order : orders) {
            if (order.id().equals(id))
                return Optional.of(order);
        }
        return Optional.empty();
    }

    /**
     * Asks the server to give up on the line shown at {@code index} (dev harness and click handling). The server
     * decides: the payload carries only the order's id, and the terminal re-validates reach, aisle and order
     * ({@code WarehouseTerminalBlockEntity#cancelProductionOrder}).
     * <p>
     * For a chain this gives up on the <b>whole</b> plan, because that is what cancelling one of its orders does on the
     * server (M20, ADR-032, {@code ProductionOrders#failPlan}): an order above the cancelled one waits for something
     * that will never be made, and one below it makes something nobody will use. The line's own order is the one named,
     * unless it has already finished — then the chain is ended from its frontier instead, so a plan whose root ended
     * badly can still be stopped.
     *
     * @return whether a cancellation was sent
     */
    public boolean cancelVisibleOrder(int index) {
        List<PlanLine> shown = visibleOrderLines();
        if (index < 0 || index >= shown.size())
            return false;
        return cancelLine(shown.get(index));
    }

    /** Sends the cancellation for one line; see {@link #cancelVisibleOrder}. */
    private boolean cancelLine(PlanLine line) {
        UUID target = order(line.head()).filter(order -> !order.state().isFinished())
                .map(ProductionScreenState.OrderView::id)
                .or(() -> line.frontier().flatMap(this::order)
                        .filter(order -> !order.state().isFinished())
                        .map(ProductionScreenState.OrderView::id))
                .orElse(null);
        if (target == null)
            return false;
        PacketDistributor.sendToServer(new ProductionCancelPayload(menu.containerId, target));
        playUiSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
        return true;
    }

    /**
     * What a click on the line at {@code index} does: for a chain, the {@code x} column gives up on the whole plan and
     * the rest of the line opens its step panel; for an ordinary order, the whole line cancels it, exactly as before M20.
     * <p>
     * The two are deliberately not the same click. Giving up on a chain costs more than giving up on one order, and the
     * panel is where a player can read what it would cost before they do it.
     */
    private boolean clickOrderLine(int index, double mouseX) {
        List<PlanLine> shown = visibleOrderLines();
        if (index < 0 || index >= shown.size())
            return false;
        PlanLine line = shown.get(index);
        if (line.isChain() && mouseX < leftPos + cancelMarkX() - TEXT_GAP)
            return openStepPanel(line);
        return cancelLine(line);
    }

    /** The index within {@link #visibleOrderLines()} of the order line under the mouse, or -1. */
    private int orderAt(double mouseX, double mouseY) {
        if (layout.orderLines() <= 0 || mouseX < leftPos + TerminalMenuLayout.MARGIN
                || mouseX >= leftPos + TerminalMenuLayout.WIDTH - TerminalMenuLayout.MARGIN)
            return -1;
        int shown = visibleOrderLines().size();
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
        if (confirming != null) {
            renderConfirmation(graphics, mouseX, mouseY);
            return; // no tooltip from the grid underneath: it is not what the player is answering
        }
        if (openPlan != null) {
            renderStepPanel(graphics, mouseX, mouseY);
            return; // likewise: while the chain is on screen, nothing behind it is being pointed at
        }
        int cell = cellAt(mouseX, mouseY);
        List<StockLine<ItemKey>> visible = visibleEntries();
        if (cell >= 0 && cell < visible.size()) {
            graphics.renderComponentTooltip(font, itemTooltip(visible.get(cell)), mouseX, mouseY);
            return;
        }
        int order = orderAt(mouseX, mouseY);
        List<PlanLine> shownLines = visibleOrderLines();
        if (order >= 0 && order < shownLines.size())
            graphics.renderComponentTooltip(font, orderTooltip(shownLines.get(order)), mouseX, mouseY);
    }

    // --- the confirmation panel (M15 part 2, issue #3) -------------------------------------------------------------

    /**
     * The server sent the question a click raised: nothing was requested, and the panel goes up (M15 part 2).
     * <p>
     * A question for the item the player has just confirmed means the cost <b>changed</b> between the answer and the
     * re-check — the server measures every confirmed request again — so the panel says so rather than putting the same
     * dialog up twice for what looks like no reason.
     */
    public void onConfirm(TerminalConfirmPayload payload) {
        RequestConfirmation<ItemKey> question = payload.question();
        costChanged = confirmed != null && confirmed.key().equals(question.key());
        confirmed = null;
        confirming = question;
        panel = null;
        // Only one dialog at a time: a question the server is asking wins over a chain a player was reading (M20).
        openPlan = null;
        stepPanel = null;
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 0.8F);
    }

    /**
     * What the terminal is currently asking about, as one line of text with every number it names (the dev harness and
     * the tests of the client side), or {@code null} while it is asking nothing.
     */
    @Nullable
    public Component confirmationQuestion() {
        RequestConfirmation<ItemKey> question = confirming;
        if (question == null)
            return null;
        Component joined = null;
        for (Component cost : confirmationCosts(question))
            joined = joined == null ? cost : joined.copy().append(" ").append(cost);
        return joined == null ? Component.empty() : joined;
    }

    /** The question the terminal is asking, for the dev harness and the tests of the client side. */
    @Nullable
    public RequestConfirmation<ItemKey> confirmation() {
        return confirming;
    }

    /**
     * Carries the pending request out (the Confirm button and Enter): the same request again, now saying what the player
     * accepted. The server measures the cost once more before it acts on it, so this is a statement of consent and not
     * a command.
     */
    public void confirmRequest() {
        RequestConfirmation<ItemKey> question = confirming;
        confirming = null;
        costChanged = false;
        panel = null;
        if (question == null)
            return;
        confirmed = question;
        send(question.key(), (int) Math.min(Integer.MAX_VALUE, question.amount()), question.acknowledgement());
    }

    /** Drops the pending request without making it (the Cancel button and Escape). Nothing was ever requested. */
    public void cancelConfirmation() {
        if (confirming == null)
            return;
        confirming = null;
        confirmed = null;
        costChanged = false;
        panel = null;
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 0.8F);
    }

    /**
     * The title, the sentences with the numbers, and the line that says how to skip the question next time. The numbers
     * are named rather than described: "this takes 10 of the 64 items held in reserve" is a fact a player can act on,
     * while "are you sure?" alone is not.
     */
    private List<Component> confirmationLines(RequestConfirmation<ItemKey> question) {
        List<Component> lines = new ArrayList<>(4 + question.ingredients().size());
        lines.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_TITLE).copy()
                .withStyle(ChatFormatting.GOLD));
        if (costChanged)
            lines.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_CHANGED).copy()
                    .withStyle(ChatFormatting.GOLD));
        for (Component cost : confirmationCosts(question))
            lines.add(cost.copy().withStyle(ChatFormatting.WHITE));
        lines.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_SKIP).copy()
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return lines;
    }

    /**
     * One sentence per boundary the request crosses, in the order a player meets them: what leaves the reserve of the
     * item itself, what a production order would spend out of <b>another</b> item's reserve — which only the server can
     * know, because it alone has the patterns — and what would be stored above the maximum.
     */
    private List<Component> confirmationCosts(RequestConfirmation<ItemKey> question) {
        List<Component> costs = new ArrayList<>(2 + question.ingredients().size());
        if (question.fromReserve() > 0L)
            costs.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_RESERVE,
                    Component.literal(LangNumberFormat.format(question.fromReserve())),
                    Component.literal(LangNumberFormat.format(question.reserved()))));
        for (RequestConfirmation.ReservedIngredient<ItemKey> ingredient : question.ingredients())
            costs.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_INGREDIENT,
                    Component.literal(LangNumberFormat.format(ingredient.fromReserve())),
                    Component.literal(LangNumberFormat.format(ingredient.reserved())),
                    ingredient.key().toStack().getHoverName()));
        if (question.pastMaximum() > 0L)
            costs.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_MAXIMUM,
                    Component.literal(LangNumberFormat.format(question.pastMaximum())),
                    Component.literal(LangNumberFormat.format(question.made())),
                    Component.literal(LangNumberFormat.format(question.maximum()))));
        return costs;
    }

    /** Widest a line of the panel may be: the window without its margins and the panel's own padding. */
    private int confirmTextWidth() {
        return TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN - 2 * CONFIRM_PADDING;
    }

    /**
     * The panel as it is drawn: the wrapped rows and every rectangle derived from them.
     * <p>
     * It is built <b>once per question</b> and not per frame ({@link #panel}). Before that the geometry helpers called
     * each other, and one frame rebuilt the text about twenty times and re-wrapped it about ten — thousands of
     * components, item stacks and resolved item names a second for a dialog whose content changes only when a new
     * payload arrives (M15 review fix).
     *
     * @param rows     the wrapped lines, in order
     * @param x        left edge of the panel
     * @param y        top edge of the panel
     * @param width    outer width
     * @param height   outer height
     * @param confirmX left edge of the Confirm button
     * @param cancelX  left edge of the Cancel button
     * @param buttonY  top edge of both buttons
     * @param confirmWidth  width of the Confirm button
     * @param cancelWidth   width of the Cancel button
     */
    private record ConfirmPanel(List<FormattedCharSequence> rows, int x, int y, int width, int height, int confirmX,
                                int cancelX, int buttonY, int confirmWidth, int cancelWidth) {
        int buttonX(boolean confirm) {
            return confirm ? confirmX : cancelX;
        }

        int buttonWidth(boolean confirm) {
            return confirm ? confirmWidth : cancelWidth;
        }
    }

    /** The panel of {@link #confirming}, built on demand and dropped whenever anything it is built from changes. */
    @Nullable
    private ConfirmPanel panel() {
        RequestConfirmation<ItemKey> question = confirming;
        if (question == null)
            return null;
        if (panel != null)
            return panel;
        List<Component> lines = confirmationLines(question);
        List<FormattedCharSequence> rows = new ArrayList<>();
        int text = 0;
        for (Component line : lines) {
            text = Math.max(text, Math.min(font.width(line), confirmTextWidth()));
            rows.addAll(font.split(line, confirmTextWidth()));
        }
        int confirmWidth = confirmButtonWidth(true);
        int cancelWidth = confirmButtonWidth(false);
        int buttons = confirmWidth + cancelWidth + CONFIRM_BUTTON_GAP;
        int width = Math.max(text, buttons) + 2 * CONFIRM_PADDING;
        int height = confirmHeight(rows);
        int x = leftPos + (TerminalMenuLayout.WIDTH - width) / 2;
        int y = topPos + Math.max(TerminalMenuLayout.MARGIN, (layout.height() - height) / 2);
        int buttonStart = x + (width - buttons) / 2;
        panel = new ConfirmPanel(List.copyOf(rows), x, y, width, height, buttonStart,
                buttonStart + confirmWidth + CONFIRM_BUTTON_GAP, y + height - CONFIRM_PADDING - CONFIRM_BUTTON_HEIGHT,
                confirmWidth, cancelWidth);
        return panel;
    }

    private void renderConfirmation(GuiGraphics graphics, int mouseX, int mouseY) {
        ConfirmPanel shown = panel();
        if (shown == null)
            return;
        // The whole window is dimmed, not only the grid: while the question is up nothing else on this screen can be
        // clicked, and the shade is what says so.
        // Flushed first and then drawn far in front (PANEL_Z): everything the grid drew is on the screen before the
        // panel starts, so nothing of it can come through a modal dialog's text.
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, PANEL_Z);
        graphics.fill(0, 0, this.width, this.height, COLOR_CONFIRM_SHADE);
        graphics.fill(shown.x() - 1, shown.y() - 1, shown.x() + shown.width() + 1, shown.y() + shown.height() + 1,
                COLOR_CONFIRM_BORDER);
        graphics.fill(shown.x(), shown.y(), shown.x() + shown.width(), shown.y() + shown.height(), COLOR_CONFIRM_BG);
        int textY = shown.y() + CONFIRM_PADDING;
        for (FormattedCharSequence line : shown.rows()) {
            graphics.drawString(font, line, shown.x() + CONFIRM_PADDING, textY, COLOR_TEXT, false);
            textY += font.lineHeight + 2;
        }
        renderConfirmButton(graphics, shown, mouseX, mouseY, true);
        renderConfirmButton(graphics, shown, mouseX, mouseY, false);
        graphics.pose().popPose();
    }

    private void renderConfirmButton(GuiGraphics graphics, ConfirmPanel shown, int mouseX, int mouseY,
            boolean confirm) {
        Component label = WareworksLang.translateDirect(
                confirm ? WareworksLang.TERMINAL_CONFIRM_YES : WareworksLang.TERMINAL_CONFIRM_NO);
        int x = shown.buttonX(confirm);
        int y = shown.buttonY();
        int width = shown.buttonWidth(confirm);
        boolean hovered = isOverConfirmButton(mouseX, mouseY, confirm);
        graphics.fill(x, y, x + width, y + CONFIRM_BUTTON_HEIGHT,
                hovered ? COLOR_CONFIRM_BUTTON_HOVER : COLOR_CONFIRM_BUTTON);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2,
                y + (CONFIRM_BUTTON_HEIGHT - font.lineHeight) / 2 + 1,
                confirm ? COLOR_CONFIRM_BORDER : COLOR_TEXT, false);
    }

    private int confirmHeight(List<FormattedCharSequence> lines) {
        return 2 * CONFIRM_PADDING + lines.size() * (font.lineHeight + 2) + CONFIRM_BUTTON_HEIGHT + CONFIRM_PADDING;
    }

    private int confirmButtonWidth(boolean confirm) {
        Component label = WareworksLang.translateDirect(
                confirm ? WareworksLang.TERMINAL_CONFIRM_YES : WareworksLang.TERMINAL_CONFIRM_NO);
        return font.width(label) + 2 * CONFIRM_BUTTON_PADDING;
    }

    /**
     * The centre of one of the panel's two buttons, in window coordinates, for the dev harness's real mouse input; -1
     * while nothing is being asked. It is a method of the screen rather than something the harness reflects together
     * out of three private ones, because the panel's geometry is built once and kept ({@link ConfirmPanel}).
     *
     * @param confirm {@code true} for "Confirm", {@code false} for "Cancel"
     */
    public int confirmButtonCenterX(boolean confirm) {
        ConfirmPanel shown = panel();
        return shown == null ? -1 : shown.buttonX(confirm) + shown.buttonWidth(confirm) / 2;
    }

    /** The vertical centre of both buttons; -1 while nothing is being asked ({@link #confirmButtonCenterX}). */
    public int confirmButtonCenterY() {
        ConfirmPanel shown = panel();
        return shown == null ? -1 : shown.buttonY() + CONFIRM_BUTTON_HEIGHT / 2;
    }

    /** Whether the mouse is over one of the two buttons; false while nothing is being asked. */
    public boolean isOverConfirmButton(double mouseX, double mouseY, boolean confirm) {
        ConfirmPanel shown = panel();
        if (shown == null)
            return false;
        int x = shown.buttonX(confirm);
        int y = shown.buttonY();
        return mouseX >= x && mouseX < x + shown.buttonWidth(confirm) && mouseY >= y
                && mouseY < y + CONFIRM_BUTTON_HEIGHT;
    }

    /**
     * The exact amounts behind a cell's compacted number, what the aisle could make of the item, and what a stock
     * rule says about it. Public because it is the only place several of those numbers are ever put into words, and a
     * tooltip is drawn only while a mouse hovers: the visual harness reads it instead of photographing it.
     */
    public List<Component> itemTooltip(StockLine<ItemKey> line) {
        List<Component> tooltip = new ArrayList<>(getTooltipFromItem(minecraft, line.key().toStack()));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_STORED,
                LangNumberFormat.format(line.total())).copy().withStyle(ChatFormatting.GRAY));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_AVAILABLE,
                LangNumberFormat.format(line.available())).copy().withStyle(ChatFormatting.GOLD));
        if (line.reserved() > 0)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_RESERVED,
                    LangNumberFormat.format(line.reserved())).copy().withStyle(ChatFormatting.DARK_GRAY));
        // What a stock rule says about this item, and which of the two reserve cases the player is in (M15, issue #3).
        // "Available" above is a player's own number — a reserve holds items back from the warehouse's automation, not
        // from the player standing here — so the reserve is named as a part of it and never subtracted from it.
        line.rule().ifPresent(status -> {
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_RULE,
                    WareworksLang.translateDirect(WareworksLang.keeperStatusKey(status))).copy()
                    .withStyle(status.bites() ? ChatFormatting.GOLD : ChatFormatting.GRAY));
            if (line.ruleMaximum() >= 0L)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_RULE_MAXIMUM,
                        LangNumberFormat.format(line.ruleMaximum())).copy().withStyle(ChatFormatting.AQUA));
            if (line.ruleReserved() > 0)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_RULE_RESERVED,
                        LangNumberFormat.format(line.ruleReserved())).copy().withStyle(ChatFormatting.AQUA));
            // Both hints are measured against the amount a plain click asks for, which is the one the screen can name
            // before it is clicked; a shift- or control-click asks for at least as much and therefore reaches at least
            // as deep. They are hints, not decisions: what a click really costs is measured on the server when it is
            // made, and named in the confirmation panel (M15 part 2, §3.6.6).
            int clicked = TerminalAmounts.amountFor(TerminalAmounts.Click.SELECTED, selectedAmount,
                    line.key().getMaxStackSize(), line.available(), line.producibleAmount(), maxRequestAmount());
            if (line.fromReserve(clicked) > 0)
                tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_BELOW_RESERVE).copy()
                        .withStyle(ChatFormatting.GOLD));
            // No hint about the maximum here: what a request would leave above a cap is the whole-run surplus of a
            // pattern, and the size of a run is not something this screen knows. The server asks exactly, before the
            // request is made (M15 review fix); the cap itself is on the "Stored at most" line above.
        });
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

    /**
     * The full order line (the drawn one may be cut), what it still waits for, and how to give up on it.
     * <p>
     * For a chain the frontier's own row is added underneath, with the address of the machine it runs at (M20): that is
     * the line a player needs before they walk anywhere, and it is the one the drawn line has no room for.
     */
    private List<Component> orderTooltip(PlanLine line) {
        ProductionScreenState.OrderView order = order(line.head()).orElse(null);
        if (order == null)
            return List.of();
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(orderText(order, order.result().toStack().getHoverName(),
                Component.literal(LangNumberFormat.format(order.amount())), stepStateText(order)));
        if (line.isChain() && !line.frontierIsHead())
            line.frontier().flatMap(this::order).ifPresent(frontier -> tooltip.add(stepRow(frontier).copy()
                    .withStyle(ChatFormatting.AQUA)));
        if (order.missing() > 0)
            tooltip.add(WareworksLang.translateDirect(WareworksLang.GOGGLES_PRODUCTION_MISSING,
                    LangNumberFormat.format(order.missing())).copy().withStyle(ChatFormatting.GRAY));
        if (order.lostIngredients())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.PRODUCTION_INGREDIENTS_LOST).copy()
                    .withStyle(ChatFormatting.GOLD));
        if (line.isChain())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        // Only while the mark is really drawn, which is exactly while the line's own order is still open.
        if (!order.state().isFinished())
            tooltip.add(WareworksLang.translateDirect(line.isChain() ? WareworksLang.TERMINAL_PLAN_CANCEL_HINT
                    : WareworksLang.PRODUCTION_CANCEL_HINT).copy()
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    // --- the step panel of a production plan (M20, issue #4, ADR-032) -----------------------------------------------

    /**
     * Opens the step panel of {@code line}'s chain: every order of the plan with its item, its amount, its state and the
     * <b>address of the station it runs at</b>, plus what giving up on the whole chain would cost.
     * <p>
     * Naming the address is the point of the panel. A chain that has stopped has stopped at exactly one machine, and
     * until a screen says which one a player can only walk the aisle and guess.
     *
     * @return whether a panel was opened
     */
    public boolean openStepPanel(PlanLine line) {
        if (line == null || !line.isChain())
            return false; // an ordinary order has no steps to show, and no panel: it is the line it always was
        openPlan = line.plan().get();
        stepPanel = null;
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.2F);
        return true;
    }

    /** Opens the step panel of the visible line at {@code index} (dev harness); false when it is no chain. */
    public boolean openStepPanel(int index) {
        List<PlanLine> shown = visibleOrderLines();
        return index >= 0 && index < shown.size() && openStepPanel(shown.get(index));
    }

    /** Drops the step panel; nothing about the chain changes. */
    public void closeStepPanel() {
        if (openPlan == null)
            return;
        openPlan = null;
        stepPanel = null;
        playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 0.8F);
    }

    /** The chain whose step panel is up, or empty while none is (dev harness and tests of the client side). */
    public Optional<UUID> openStepPanelPlan() {
        return Optional.ofNullable(openPlan);
    }

    /**
     * Gives up on the whole chain the panel is showing and closes it. The server decides: it re-checks the order this
     * names against the aisle the player really has open and ends the plan from there
     * ({@code WarehouseTerminalBlockEntity#cancelProductionOrder}, {@code ProductionOrders#failPlan}).
     *
     * @return whether a cancellation was sent
     */
    public boolean cancelOpenPlan() {
        UUID plan = openPlan;
        if (plan == null)
            return false;
        PlanLine line = PlanLines.byPlan(planLines, plan).orElse(null);
        boolean sent = line != null && cancelLine(line);
        openPlan = null;
        stepPanel = null;
        return sent;
    }

    /**
     * The panel's text as the player reads it, title and cost lines included (dev harness and tests of the client side):
     * empty while no panel is up. A harness reads this instead of photographing the dialog.
     */
    public List<Component> stepPanelLines() {
        UUID plan = openPlan;
        if (plan == null)
            return List.of();
        return PlanLines.byPlan(planLines, plan).map(line -> stepPanelLines(line, fittingSteps(line)))
                .orElse(List.of());
    }

    /**
     * The title, up to {@code maxSteps} orders of the chain in display order, the steps that did not fit, and what
     * giving up on the chain would cost.
     * <p>
     * The title and the cost lines are never the ones that give way: what a chain is for and what stopping it costs are
     * the two things a player has to be able to read, while one more machine in the middle of a long list is not.
     */
    private List<Component> stepPanelLines(PlanLine line, int maxSteps) {
        List<UUID> members = line.members();
        List<Component> lines = new ArrayList<>(Math.min(members.size(), maxSteps) + 4);
        ProductionScreenState.OrderView head = order(line.head()).orElse(null);
        lines.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_TITLE,
                head == null ? Component.empty() : head.result().toStack().getHoverName(),
                Component.literal(LangNumberFormat.format(head == null ? 0 : head.amount()))).copy()
                .withStyle(ChatFormatting.GOLD));
        int shown = 0;
        for (int i = 0; i < members.size(); i++) {
            if (shown >= maxSteps) {
                lines.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_NOT_SHOWN,
                        LangNumberFormat.format(members.size() - i)).copy().withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            ProductionScreenState.OrderView view = order(members.get(i)).orElse(null);
            if (view == null)
                continue; // the payload no longer holds this step: one row fewer, never a wrong one
            shown++;
            lines.add(stepRow(view).copy().withStyle(stepStyle(line, view)));
        }
        for (Component cost : cancelCosts(line))
            lines.add(cost.copy().withStyle(ChatFormatting.GRAY));
        return List.copyOf(lines);
    }

    /**
     * The most step rows that still fit into the window next to the panel's title and cost lines. A long item name wraps
     * to two rows, so this is measured on the <b>wrapped</b> text rather than counted; it runs once per order payload,
     * with the panel itself.
     */
    private int fittingSteps(PlanLine line) {
        int budget = Math.max(1, (layout.height() - 2 * TerminalMenuLayout.MARGIN - 3 * CONFIRM_PADDING
                - CONFIRM_BUTTON_HEIGHT) / (font.lineHeight + 2));
        int most = Math.min(MAX_STEP_ROWS, line.members().size());
        for (int steps = most; steps > 1; steps--) {
            if (wrap(stepPanelLines(line, steps)).size() <= budget)
                return steps;
        }
        return 1;
    }

    /** The lines of a panel wrapped to its text width, which is what its height is really made of. */
    private List<FormattedCharSequence> wrap(List<Component> lines) {
        List<FormattedCharSequence> rows = new ArrayList<>(lines.size());
        for (Component line : lines)
            rows.addAll(font.split(line, confirmTextWidth()));
        return rows;
    }

    /**
     * One row of the panel: {@code "  A-05-01R: Oak Planks x8 - waiting for the machine"}. The address comes first
     * because the rows then read as a list of machines, which is what a player walking the aisle needs, and the
     * indentation is the order's depth in the chain, so a branch is visible at a glance.
     */
    private static Component stepRow(ProductionScreenState.OrderView view) {
        Component line = orderText(view, view.result().toStack().getHoverName(),
                Component.literal(LangNumberFormat.format(view.amount())), stepStateText(view));
        Component address = view.address().<Component>map(Component::literal)
                .orElseGet(() -> WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_NO_ADDRESS));
        Component row = WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_STEP_AT, address, line);
        // The depth is bounded on the wire (ProductionScreenState#MAX_DEPTH); the indent is bounded again because a row
        // wider than the panel says nothing anyway, and this is the one place a number becomes a length.
        int indent = Math.min(2 * view.depth(), MAX_STEP_INDENT);
        return indent <= 0 ? row : Component.literal(" ".repeat(indent)).append(row);
    }

    /**
     * Where one order of a chain stands. An order the server marked as waiting for an earlier step says <b>that</b>
     * rather than its own state: it is nominally waiting for ingredients, but it is fetching nothing at all until the
     * step below it is done, and a screen that showed "waiting for ingredients" would look like a stuck crane
     * ({@code docs/warehouse-system.md} §3.5.6).
     */
    private static Component stepStateText(ProductionScreenState.OrderView view) {
        return view.waitingForStep() ? WareworksLang.translateDirect(WareworksLang.PRODUCTION_WAITING_FOR_STEP)
                : WareworksLang.translateDirect(view.state().langKey());
    }

    /** Gold for a row that lost ingredients, aqua for the step that is working, grey for one that has finished. */
    private static ChatFormatting stepStyle(PlanLine line, ProductionScreenState.OrderView view) {
        if (view.lostIngredients())
            return ChatFormatting.GOLD;
        if (line.frontier().filter(view.id()::equals).isPresent())
            return ChatFormatting.AQUA;
        return view.state().isFinished() ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE;
    }

    /**
     * What giving up on this chain would cost, in the three things a player can act on: how many of its orders would end,
     * how many ingredient items were already delivered to a machine and never come back, and that the click stops the
     * warehouse from making the item until somebody resumes it at that machine ({@code docs/warehouse-system.md} §3.5.6).
     * <p>
     * <b>Every number is the one the server would really produce</b> ({@link PlanCancelCost}, which mirrors
     * {@code ProductionOrders#failPlan}), not a sum over the open members: a step whose batch is already in a machine is
     * <b>detached and left running</b>, so it neither ends nor loses anything. Counting it would overstate both numbers in
     * exactly the state a player opens this panel in — a broken machine running down its timeout, with the root blocked
     * and one step at a machine — and the panel's one job is to state the cost before an irreversible click.
     * <p>
     * The last two lines appear together and only when something was really delivered, which is also the condition the
     * safety stop is armed under ({@code ProductionOrder#endedWithLostIngredients}). The first line is always there, with a
     * <b>0</b> for a chain every order of which has finished: "giving up would end nothing" is the answer a player wants
     * for a chain that failed, and an empty space where a number was is not.
     */
    private List<Component> cancelCosts(PlanLine line) {
        PlanCancelCost cost = PlanCancelCost.of(line, planMembers).orElse(null);
        List<Component> costs = new ArrayList<>(3);
        costs.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_CANCEL_COST,
                Component.literal(LangNumberFormat.format(cost == null ? 0 : cost.endedOrders()))));
        if (cost == null || !cost.armsSafetyStop())
            return List.copyOf(costs);
        costs.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_CANCEL_LOST,
                Component.literal(LangNumberFormat.format(cost.lostIngredients()))));
        costs.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_PLAN_CANCEL_STOP,
                order(cost.target()).map(view -> view.result().toStack().getHoverName())
                        .orElseGet(Component::empty)));
        return List.copyOf(costs);
    }

    /**
     * The panel as it is drawn, built <b>once per order payload</b> and not per frame, exactly like the confirmation's
     * ({@link ConfirmPanel}): its rows are wrapped item names and resolved states, which is far too much work for sixty
     * frames a second of a dialog that changes only when a payload arrives.
     */
    private record StepPanel(List<FormattedCharSequence> rows, int x, int y, int width, int height, int cancelX,
                             int closeX, int buttonY, int cancelWidth, int closeWidth) {
        int buttonX(boolean cancel) {
            return cancel ? cancelX : closeX;
        }

        int buttonWidth(boolean cancel) {
            return cancel ? cancelWidth : closeWidth;
        }
    }

    /** The panel of {@link #openPlan}, built on demand and dropped whenever the orders change. */
    @Nullable
    private StepPanel stepPanel() {
        UUID plan = openPlan;
        if (plan == null)
            return null;
        if (stepPanel != null)
            return stepPanel;
        PlanLine line = PlanLines.byPlan(planLines, plan).orElse(null);
        if (line == null)
            return null;
        // A chain of many steps must not grow the panel past the window: the steps in the middle give way, and the
        // panel's own "+N not shown" line says how many did.
        List<Component> lines = stepPanelLines(line, fittingSteps(line));
        List<FormattedCharSequence> rows = wrap(lines);
        int text = 0;
        for (Component row : lines)
            text = Math.max(text, Math.min(font.width(row), confirmTextWidth()));
        int cancelWidth = stepButtonWidth(true);
        int closeWidth = stepButtonWidth(false);
        int buttons = cancelWidth + closeWidth + CONFIRM_BUTTON_GAP;
        int width = Math.max(text, buttons) + 2 * CONFIRM_PADDING;
        int height = confirmHeight(rows);
        int x = leftPos + (TerminalMenuLayout.WIDTH - width) / 2;
        int y = topPos + Math.max(TerminalMenuLayout.MARGIN, (layout.height() - height) / 2);
        int buttonStart = x + (width - buttons) / 2;
        stepPanel = new StepPanel(List.copyOf(rows), x, y, width, height, buttonStart,
                buttonStart + cancelWidth + CONFIRM_BUTTON_GAP, y + height - CONFIRM_PADDING - CONFIRM_BUTTON_HEIGHT,
                cancelWidth, closeWidth);
        return stepPanel;
    }

    private void renderStepPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        StepPanel shown = stepPanel();
        if (shown == null)
            return;
        // The same flush the confirmation does, and for the same reason: see PANEL_Z.
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, PANEL_Z);
        graphics.fill(0, 0, this.width, this.height, COLOR_CONFIRM_SHADE);
        graphics.fill(shown.x() - 1, shown.y() - 1, shown.x() + shown.width() + 1, shown.y() + shown.height() + 1,
                COLOR_STEPS_BORDER);
        graphics.fill(shown.x(), shown.y(), shown.x() + shown.width(), shown.y() + shown.height(), COLOR_CONFIRM_BG);
        int textY = shown.y() + CONFIRM_PADDING;
        for (FormattedCharSequence row : shown.rows()) {
            graphics.drawString(font, row, shown.x() + CONFIRM_PADDING, textY, COLOR_TEXT, false);
            textY += font.lineHeight + 2;
        }
        renderStepButton(graphics, shown, mouseX, mouseY, true);
        renderStepButton(graphics, shown, mouseX, mouseY, false);
        graphics.pose().popPose();
    }

    private void renderStepButton(GuiGraphics graphics, StepPanel shown, int mouseX, int mouseY, boolean cancel) {
        Component label = stepButtonLabel(cancel);
        int x = shown.buttonX(cancel);
        int y = shown.buttonY();
        int width = shown.buttonWidth(cancel);
        boolean hovered = isOverStepButton(mouseX, mouseY, cancel);
        graphics.fill(x, y, x + width, y + CONFIRM_BUTTON_HEIGHT,
                hovered ? COLOR_CONFIRM_BUTTON_HOVER : COLOR_CONFIRM_BUTTON);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2,
                y + (CONFIRM_BUTTON_HEIGHT - font.lineHeight) / 2 + 1, cancel ? COLOR_ERROR : COLOR_TEXT, false);
    }

    private static Component stepButtonLabel(boolean cancel) {
        return WareworksLang.translateDirect(
                cancel ? WareworksLang.TERMINAL_PLAN_CANCEL : WareworksLang.TERMINAL_PLAN_CLOSE);
    }

    private int stepButtonWidth(boolean cancel) {
        return font.width(stepButtonLabel(cancel)) + 2 * CONFIRM_BUTTON_PADDING;
    }

    /** Whether the mouse is over one of the panel's two buttons; false while no panel is up. */
    public boolean isOverStepButton(double mouseX, double mouseY, boolean cancel) {
        StepPanel shown = stepPanel();
        if (shown == null)
            return false;
        int x = shown.buttonX(cancel);
        int y = shown.buttonY();
        return mouseX >= x && mouseX < x + shown.buttonWidth(cancel) && mouseY >= y
                && mouseY < y + CONFIRM_BUTTON_HEIGHT;
    }

    /** The centre of one of the panel's buttons for the dev harness's real mouse input; -1 while no panel is up. */
    public int stepButtonCenterX(boolean cancel) {
        StepPanel shown = stepPanel();
        return shown == null ? -1 : shown.buttonX(cancel) + shown.buttonWidth(cancel) / 2;
    }

    /** The vertical centre of both of the panel's buttons; -1 while no panel is up. */
    public int stepButtonCenterY() {
        StepPanel shown = stepPanel();
        return shown == null ? -1 : shown.buttonY() + CONFIRM_BUTTON_HEIGHT / 2;
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
