package dev.wareworks.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ListTableLayout;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.terminal.ListEntryStatus;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.ListShortage;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The clipboard list question as a table (issue #36, ADR-069, {@code docs/warehouse-system.md} §3.4.4): one row per
 * entry — its icon, its name, the wanted amount and its status, with a grey detail line under them — sorted with the
 * problems first, then a Missing section with every material the list is short of. The rows scroll by whole rows with
 * the mouse wheel and the arrow and page keys; the question and the summary above and the two buttons below stay where
 * they are ({@code WarehouseTerminalScreen#panel}).
 * <p>
 * <b>Built once per question.</b> Every row's stack, shortened texts and tooltips are made when the question arrives
 * ({@link #of}), never per frame — the M15 review rule for the confirm panel. Only the vertical geometry is laid out
 * again when the window changes ({@link #layOut}); the columns depend on the measured texts alone. Every text is
 * shortened to its column with an ellipsis, and the full text is one hover away: a name in the icon's or the name's
 * tooltip, the numbers and the reason in the tooltip of the rest of the row.
 * <p>
 * The geometry is {@link ListTableLayout}'s, which the JUnit test holds to the smallest window every buffer size
 * allows; this class only measures, draws and hit-tests.
 */
public final class TerminalListTable {
    /** Grey of the header and the detail lines. */
    private static final int COLOR_DETAIL = 0xFFA0A0A0;
    private static final int COLOR_PARTLY = 0xFFFFAA00;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_SECTION = 0xFFFFAA00;
    private static final int COLOR_ROW_ALTERNATE = 0x18FFFFFF;
    private static final int COLOR_HEADER_LINE = 0xFF56565F;
    private static final int COLOR_TRACK = 0xFF1C1C22;
    private static final int COLOR_THUMB = 0xFF8A8A96;
    /** Entries a material's tooltip names before it counts the rest. */
    private static final int MAX_TOOLTIP_ENTRIES = 8;
    /** Distance of the first text line from a row's top; the second follows one line and 2 px lower. */
    private static final int TEXT_INSET = 1;
    private static final int ICON_INSET = 2;

    /** What a row of the table is. */
    public enum Kind {
        /** One entry of the list. */
        ENTRY,
        /** The heading of the Missing section. */
        SECTION,
        /** One material the list is short of. */
        SHORTAGE
    }

    /**
     * One row exactly as it is drawn: the texts after shortening and the full ones they were cut from, for the visual
     * harness, which reads them instead of photographing them.
     *
     * @param kind       what the row is
     * @param nameShown  the name as drawn, shortened to its column
     * @param nameFull   the full name
     * @param wanted     the amount as drawn
     * @param status     the status word as drawn; empty for the Missing section's rows
     * @param statusKind the entry's status, {@code null} for the Missing section's rows
     * @param detail     the grey detail line as drawn
     * @param detailFull the detail line before shortening
     */
    public record RowText(Kind kind, String nameShown, String nameFull, String wanted, String status,
                          @Nullable ListEntryStatus statusKind, String detail, String detailFull) {
    }

    /** A point in window coordinates, for the harness's real mouse input. */
    public record Spot(int x, int y) {
    }

    /** The panel's rectangle in window coordinates. */
    public record Bounds(int x, int y, int width, int height) {
    }

    private record Row(Kind kind, ItemStack icon, Component name, String nameShown, String wanted, String status,
                       int statusColor, @Nullable ListEntryStatus statusKind, String detail, String detailFull,
                       boolean overflows, List<Component> nameTooltip, List<Component> infoTooltip) {
    }

    private final List<Row> rows;
    private final int wantedHeaderWidth;
    private final int widestWanted;
    private final int widestStatus;
    private final int statusHeaderWidth;
    private final Component headerItem;
    private final Component headerWanted;
    private final Component headerStatus;
    private ListTableLayout layout;
    private int panelX;
    private int panelY;
    private int scroll;

    private TerminalListTable(List<Row> rows, int wantedHeaderWidth, int widestWanted, int widestStatus,
            int statusHeaderWidth, Component headerItem, Component headerWanted, Component headerStatus,
            ListTableLayout layout) {
        this.rows = rows;
        this.wantedHeaderWidth = wantedHeaderWidth;
        this.widestWanted = widestWanted;
        this.widestStatus = widestStatus;
        this.statusHeaderWidth = statusHeaderWidth;
        this.headerItem = headerItem;
        this.headerWanted = headerWanted;
        this.headerStatus = headerStatus;
        this.layout = layout;
    }

    /** The table for {@code question}, measured with {@code font}; laid out for a window when {@link #layOut} runs. */
    public static TerminalListTable of(ListOrderConfirmation<ItemKey> question, Font font) {
        Component headerItem = WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_HEADER_ITEM);
        Component headerWanted = WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_HEADER_WANTED);
        Component headerStatus = WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_HEADER_STATUS);
        List<ListOrderConfirmation.Line<ItemKey>> entries = question.tableOrder();
        List<ListShortage<ItemKey>> shortages = question.shortages();
        // Every status word is measured, not only those that occur, so the columns do not move between two lists.
        int widestStatus = 0;
        for (ListEntryStatus status : ListEntryStatus.values())
            widestStatus = Math.max(widestStatus, font.width(WareworksLang.translateDirect(status.langKey())));
        int widestWanted = 0;
        for (ListOrderConfirmation.Line<ItemKey> line : entries)
            widestWanted = Math.max(widestWanted, font.width(LangNumberFormat.format(line.wanted())));
        for (ListShortage<ItemKey> shortage : shortages)
            widestWanted = Math.max(widestWanted, font.width(LangNumberFormat.format(shortage.amount())));
        int wantedHeaderWidth = font.width(headerWanted);
        int statusHeaderWidth = font.width(headerStatus);
        int rowCount = entries.size() + (shortages.isEmpty() ? 0 : 1 + shortages.size());
        ListTableLayout columns = ListTableLayout.of(wantedHeaderWidth, widestWanted, widestStatus, statusHeaderWidth,
                Integer.MAX_VALUE / 4, 0, rowCount);

        // The entries' names by their position in the list, for the Missing section's tooltips.
        Map<Integer, ListOrderConfirmation.Line<ItemKey>> byOrder = new HashMap<>();
        for (ListOrderConfirmation.Line<ItemKey> line : question.lines())
            byOrder.put(line.order(), line);
        List<Row> rows = new ArrayList<>(rowCount);
        for (ListOrderConfirmation.Line<ItemKey> line : entries)
            rows.add(entryRow(line, font, columns));
        if (!shortages.isEmpty()) {
            Component title = WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_MISSING_TITLE,
                    LangNumberFormat.format(shortages.size()));
            String shown = fit(font, title.getString(), ListTableLayout.CONTENT_WIDTH);
            rows.add(new Row(Kind.SECTION, ItemStack.EMPTY, title, shown, "", "", COLOR_SECTION, null, "", "",
                    font.width(shown) > ListTableLayout.CONTENT_WIDTH, List.of(), List.of()));
            for (ListShortage<ItemKey> shortage : shortages)
                rows.add(shortageRow(shortage, byOrder, font, columns));
        }
        return new TerminalListTable(List.copyOf(rows), wantedHeaderWidth, widestWanted, widestStatus,
                statusHeaderWidth, headerItem, headerWanted, headerStatus, columns);
    }

    private static Row entryRow(ListOrderConfirmation.Line<ItemKey> line, Font font, ListTableLayout columns) {
        ItemStack icon = line.key().toStack();
        Component name = icon.getHoverName();
        ListEntryStatus status = line.status();
        Component statusWord = WareworksLang.translateDirect(status.langKey());
        String wanted = LangNumberFormat.format(line.wanted());
        Component reason = reason(line);
        Component detail = switch (status) {
            case AVAILABLE -> WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_DETAIL_AVAILABLE,
                    LangNumberFormat.format(line.fromStock()));
            case CRAFTED -> WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_DETAIL_CRAFTED,
                    LangNumberFormat.format(line.fromStock()), LangNumberFormat.format(line.produced()));
            case PARTLY -> WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_DETAIL_PARTLY,
                    LangNumberFormat.format(line.obtainable()), wanted, reason);
            case IMPOSSIBLE -> reason;
        };
        int color = statusColor(status);
        String nameShown = fit(font, name.getString(), columns.nameWidth());
        String wantedShown = fit(font, wanted, columns.wantedWidth());
        String statusShown = fit(font, statusWord.getString(), columns.statusWidth());
        String detailShown = fit(font, detail.getString(), columns.detailWidth());
        boolean overflows = font.width(nameShown) > columns.nameWidth()
                || font.width(wantedShown) > columns.wantedWidth() || font.width(statusShown) > columns.statusWidth()
                || font.width(detailShown) > columns.detailWidth();

        List<Component> info = new ArrayList<>(5);
        info.add(fullName(name, wanted, wantedShown));
        info.add(statusWord.copy().withColor(color));
        info.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_TOOLTIP_SPLIT,
                LangNumberFormat.format(line.fromStock()), LangNumberFormat.format(line.produced()),
                LangNumberFormat.format(line.missing())).copy().withStyle(ChatFormatting.GRAY));
        if (line.made() > line.produced())
            info.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_TOOLTIP_MADE,
                    LangNumberFormat.format(line.made())).copy().withStyle(ChatFormatting.GRAY));
        if (line.isShort())
            info.add(reason.copy().withStyle(ChatFormatting.GOLD));
        return new Row(Kind.ENTRY, icon, name, nameShown, wantedShown, statusShown, color, status, detailShown,
                detail.getString(), overflows, List.of(name.copy().withColor(COLOR_NAME)), List.copyOf(info));
    }

    private static Row shortageRow(ListShortage<ItemKey> shortage,
            Map<Integer, ListOrderConfirmation.Line<ItemKey>> byOrder, Font font, ListTableLayout columns) {
        ItemStack icon = shortage.key().toStack();
        Component name = icon.getHoverName();
        String amount = LangNumberFormat.format(shortage.amount());
        Component detail = WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_MISSING_ENTRIES,
                LangNumberFormat.format(shortage.entries().size()));
        String nameShown = fit(font, name.getString(), columns.nameWidth());
        String amountShown = fit(font, amount, columns.wantedWidth());
        String detailShown = fit(font, detail.getString(), columns.detailWidth());
        boolean overflows = font.width(nameShown) > columns.nameWidth()
                || font.width(amountShown) > columns.wantedWidth() || font.width(detailShown) > columns.detailWidth();
        List<Component> info = new ArrayList<>(3 + Math.min(shortage.entries().size(), MAX_TOOLTIP_ENTRIES));
        info.add(fullName(name, amount, amountShown));
        info.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_MISSING_NEEDED_BY).copy()
                .withStyle(ChatFormatting.GOLD));
        int named = 0;
        for (int order : shortage.entries()) {
            if (named >= MAX_TOOLTIP_ENTRIES)
                break;
            ListOrderConfirmation.Line<ItemKey> line = byOrder.get(order);
            if (line == null)
                continue;
            info.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_MISSING_ENTRY,
                    line.key().toStack().getHoverName(), LangNumberFormat.format(line.wanted())).copy()
                    .withStyle(ChatFormatting.GRAY));
            named++;
        }
        int rest = shortage.entries().size() - named;
        if (rest > 0)
            info.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_MISSING_MORE,
                    LangNumberFormat.format(rest)).copy().withStyle(ChatFormatting.DARK_GRAY));
        return new Row(Kind.SHORTAGE, icon, name, nameShown, amountShown, "", COLOR_DETAIL, null, detailShown,
                detail.getString(), overflows, List.of(name.copy().withColor(COLOR_NAME)), List.copyOf(info));
    }

    /**
     * The first line of a row's info tooltip: the name, and with it the full amount — "Cobblestone (1,000,000)" — when
     * the amount column had to shorten it ({@link ListTableLayout#MAX_WANTED_WIDTH}), so no number is only ever shown
     * cut short.
     */
    private static Component fullName(Component name, String amount, String amountShown) {
        Component line = amountShown.equals(amount) ? name
                : WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_MISSING_ENTRY, name, amount);
        return line.copy().withColor(COLOR_NAME);
    }

    /** Why an entry is short: the planner's sentence about the item it names, or "Not in stock" without one. */
    private static Component reason(ListOrderConfirmation.Line<ItemKey> line) {
        PlanRefusal refusal = line.refusal().orElse(null);
        if (refusal == null)
            return WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_REASON_NONE);
        return WareworksLang.translateDirect(refusal.langKey(), line.about().orElse(line.key()).toStack()
                .getHoverName());
    }

    static int statusColor(ListEntryStatus status) {
        return switch (status) {
            case AVAILABLE -> WarehouseTerminalScreen.COLOR_SUCCESS;
            case CRAFTED -> WarehouseTerminalScreen.COLOR_PRODUCIBLE;
            case PARTLY -> COLOR_PARTLY;
            case IMPOSSIBLE -> WarehouseTerminalScreen.COLOR_ERROR;
        };
    }

    /** {@code text} as it fits {@code width} pixels: whole, or cut with an ellipsis. */
    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width)
            return text;
        String ellipsis = CommonComponents.ELLIPSIS.getString();
        int room = width - font.width(ellipsis);
        return room <= 0 ? "" : font.plainSubstrByWidth(text, room) + ellipsis;
    }

    // --- layout -----------------------------------------------------------------------------------------------------

    /**
     * Lays the table out for a window whose top edge is at {@code topPos} and that is {@code layoutHeight} tall, below
     * {@code textRowsAbove} lines of text, with the panel's left edge at {@code panelX}. The scroll row is kept and
     * clamped, so a resize does not throw the player back to the top.
     */
    public void layOut(int textRowsAbove, int panelX, int topPos, int layoutHeight) {
        layout = ListTableLayout.of(wantedHeaderWidth, widestWanted, widestStatus, statusHeaderWidth, layoutHeight,
                textRowsAbove, rows.size());
        this.panelX = panelX;
        this.panelY = topPos + layout.offsetY();
        scroll = layout.clampScroll(scroll);
    }

    public int panelX() {
        return panelX;
    }

    public int panelY() {
        return panelY;
    }

    public int panelHeight() {
        return layout.height();
    }

    /** Top of the two buttons, in window coordinates. */
    public int buttonY() {
        return panelY + layout.buttonY();
    }

    private int innerX() {
        return panelX + ListTableLayout.PADDING;
    }

    // --- input ------------------------------------------------------------------------------------------------------

    /** One wheel event: one row per notch, down to later rows. */
    public void scroll(double wheel) {
        if (wheel != 0)
            scroll = layout.scrollBy(scroll, wheel);
    }

    /**
     * The table's keys: the arrows move one row, the page keys one page, Home and End to either end. Whether the key
     * was one of them; Enter and Escape are not, so the question's own keys still answer it.
     */
    public boolean keyPressed(int keyCode) {
        int page = Math.max(1, layout.visibleRows());
        int target = switch (keyCode) {
            case InputConstants.KEY_UP -> scroll - 1;
            case InputConstants.KEY_DOWN -> scroll + 1;
            case InputConstants.KEY_PAGEUP -> scroll - page;
            case InputConstants.KEY_PAGEDOWN -> scroll + page;
            case InputConstants.KEY_HOME -> 0;
            case InputConstants.KEY_END -> layout.maxScroll();
            default -> Integer.MIN_VALUE;
        };
        if (target == Integer.MIN_VALUE)
            return false;
        scroll = layout.clampScroll(target);
        return true;
    }

    // --- drawing ----------------------------------------------------------------------------------------------------

    /** Draws the header, the visible rows and the scroll bar; called inside the panel's pushed pose. */
    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int x = innerX();
        int headerY = panelY + layout.headerY();
        graphics.drawString(font, fit(font, headerItem.getString(), layout.nameX() + layout.nameWidth()), x,
                headerY + TEXT_INSET, COLOR_DETAIL, false);
        String wantedHeader = fit(font, headerWanted.getString(), layout.wantedWidth());
        graphics.drawString(font, wantedHeader, x + layout.wantedRight() - font.width(wantedHeader),
                headerY + TEXT_INSET, COLOR_DETAIL, false);
        graphics.drawString(font, fit(font, headerStatus.getString(), layout.statusWidth()), x + layout.statusX(),
                headerY + TEXT_INSET, COLOR_DETAIL, false);
        graphics.fill(x, headerY + ListTableLayout.HEADER_HEIGHT - 1, x + ListTableLayout.CONTENT_WIDTH,
                headerY + ListTableLayout.HEADER_HEIGHT, COLOR_HEADER_LINE);

        int rowsY = panelY + layout.rowsY();
        for (int visible = 0; visible < layout.visibleRows(); visible++) {
            int index = scroll + visible;
            if (index >= rows.size())
                break;
            Row row = rows.get(index);
            int top = rowsY + visible * ListTableLayout.ROW_HEIGHT;
            if (index % 2 == 1)
                graphics.fill(x, top, x + ListTableLayout.CONTENT_WIDTH, top + ListTableLayout.ROW_HEIGHT,
                        COLOR_ROW_ALTERNATE);
            renderRow(graphics, font, row, x, top);
        }
        if (layout.scrolls()) {
            int barX = x + layout.scrollbarX();
            graphics.fill(barX, rowsY, barX + ListTableLayout.SCROLLBAR_WIDTH, rowsY + layout.rowsHeight(),
                    COLOR_TRACK);
            int thumbY = rowsY + layout.thumbY(scroll);
            graphics.fill(barX, thumbY, barX + ListTableLayout.SCROLLBAR_WIDTH, thumbY + layout.thumbHeight(),
                    COLOR_THUMB);
        }
    }

    private void renderRow(GuiGraphics graphics, Font font, Row row, int x, int top) {
        int first = top + TEXT_INSET;
        int second = first + font.lineHeight + 1;
        if (row.kind() == Kind.SECTION) {
            graphics.drawString(font, row.nameShown(), x, top + (ListTableLayout.ROW_HEIGHT - font.lineHeight) / 2 + 1,
                    row.statusColor(), false);
            return;
        }
        // An icon like an inventory slot's: a block as a block, no count on it — the amount has its own column.
        graphics.renderItem(row.icon(), x + layout.iconX(), top + ICON_INSET);
        graphics.drawString(font, row.nameShown(), x + layout.nameX(), first, COLOR_NAME, false);
        graphics.drawString(font, row.wanted(), x + layout.wantedRight() - font.width(row.wanted()), first,
                COLOR_NAME, false);
        if (!row.status().isEmpty())
            graphics.drawString(font, row.status(), x + layout.statusX(), first, row.statusColor(), false);
        graphics.drawString(font, row.detail(), x + layout.detailX(), second, COLOR_DETAIL, false);
    }

    /**
     * The tooltip for a mouse at {@code mouseX}/{@code mouseY}: over an entry's or a material's icon or name its full
     * name; over the rest of an entry's row its status, its split and why it is short; over the rest of a material's
     * row the entries that need it. Empty elsewhere.
     */
    public List<Component> tooltipAt(int mouseX, int mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row < 0)
            return List.of();
        Row shown = rows.get(row);
        if (shown.kind() == Kind.SECTION)
            return List.of();
        int column = mouseX - innerX();
        return column < layout.nameX() + layout.nameWidth() ? shown.nameTooltip() : shown.infoTooltip();
    }

    /** The content row under the mouse, or -1 when it is not over the table's rows. */
    public int rowAt(double mouseX, double mouseY) {
        int column = (int) Math.floor(mouseX) - innerX();
        if (column < 0 || column >= ListTableLayout.CONTENT_WIDTH)
            return -1;
        return layout.rowAt(scroll, (int) Math.floor(mouseY) - panelY);
    }

    // --- for the dev harness ----------------------------------------------------------------------------------------

    public int rowCount() {
        return rows.size();
    }

    public int visibleRows() {
        return layout.visibleRows();
    }

    public int scrollRow() {
        return scroll;
    }

    public int maxScroll() {
        return layout.maxScroll();
    }

    /** The middle of the rows' area, where a wheel scrolls the table. */
    public Spot tableCentre() {
        return new Spot(innerX() + ListTableLayout.CONTENT_WIDTH / 2,
                panelY + layout.rowsY() + layout.rowsHeight() / 2);
    }

    /** The middle of content row {@code row}'s name, or {@code null} while it is scrolled out of view. */
    @Nullable
    public Spot nameCentre(int row) {
        int y = rowCentreY(row);
        return y < 0 ? null : new Spot(innerX() + layout.nameX() + layout.nameWidth() / 2, y);
    }

    /** The middle of content row {@code row}'s status column, or {@code null} while it is scrolled out of view. */
    @Nullable
    public Spot statusCentre(int row) {
        int y = rowCentreY(row);
        return y < 0 ? null : new Spot(innerX() + layout.statusX() + layout.statusWidth() / 2, y);
    }

    private int rowCentreY(int row) {
        if (row < scroll || row >= scroll + layout.visibleRows() || row >= rows.size())
            return -1;
        return panelY + layout.rowsY() + (row - scroll) * ListTableLayout.ROW_HEIGHT + ListTableLayout.ROW_HEIGHT / 2;
    }

    /** Every row exactly as it is drawn, in table order. */
    public List<RowText> rowTexts() {
        List<RowText> texts = new ArrayList<>(rows.size());
        for (Row row : rows)
            texts.add(new RowText(row.kind(), row.nameShown(), row.name().getString(), row.wanted(), row.status(),
                    row.statusKind(), row.detail(), row.detailFull()));
        return texts;
    }

    /** Whether any drawn text is wider than its column; it never may be. */
    public boolean overflows() {
        for (Row row : rows) {
            if (row.overflows())
                return true;
        }
        return layout.nameWidth() < ListTableLayout.MIN_NAME_WIDTH;
    }

    /** The column widths, for the run's log. */
    public String describeColumns() {
        return "name=" + layout.nameWidth() + " wanted=" + layout.wantedWidth() + " status=" + layout.statusWidth()
                + " detail=" + layout.detailWidth() + " visible=" + layout.visibleRows() + "/" + rows.size();
    }

    public Bounds panelBounds() {
        return new Bounds(panelX, panelY, ListTableLayout.PANEL_WIDTH, layout.height());
    }
}
