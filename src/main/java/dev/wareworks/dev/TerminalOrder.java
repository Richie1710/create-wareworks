package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.network.chat.Component;

/**
 * Orders at the real warehouse terminal screen through {@link ScreenInput} (dev tooling, ADR-014; shared by
 * {@code robobee}, {@code selector} and {@code selector-remote}): the amount set with the mouse wheel, the stock cell of
 * an item found in the visible grid, and the "producing" sentence read from the screen's feedback line.
 */
final class TerminalOrder {
    private static final int MAX_SCROLL_NOTCHES = 1100;

    private TerminalOrder() {
    }

    static WarehouseTerminalScreen terminalScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open: " + context.minecraft().screen);
    }

    /** Turns the wheel over the amount field until the terminal asks for {@code amount} per click. */
    static void scrollAmount(VisualContext context, String prefix, int amount) {
        WarehouseTerminalScreen terminal = terminalScreen(context);
        var field = ScreenInput.terminalAmount(terminal);
        ScreenInput.Point point = ScreenInput.centre(field);
        int notches = 0;
        for (; notches < MAX_SCROLL_NOTCHES && field.getState() != amount; notches++)
            ScreenInput.scroll(context.minecraft(), point, field.getState() < amount ? 1.0 : -1.0);
        if (field.getState() != amount)
            throw new VisualTestException("the requested amount stayed at " + field.getState() + " after " + notches
                    + " notches, expected " + amount);
        LOGGER.info(PREFIX + "{}: the terminal asks for {} per click after {} notches of the wheel", prefix,
                field.getState(), notches);
    }

    /** The index of {@code key}'s cell in the terminal's visible grid. */
    static int cellOf(WarehouseTerminalScreen terminal, ItemKey key) {
        var visible = terminal.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++)
            if (visible.get(cell).key().equals(key))
                return cell;
        throw new VisualTestException(key + " is not in the terminal's visible grid");
    }

    /** One real click on {@code key}'s stock cell. */
    static void clickCell(VisualContext context, String prefix, ItemKey key) {
        WarehouseTerminalScreen terminal = terminalScreen(context);
        int cell = cellOf(terminal, key);
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(terminal, cell));
        LOGGER.info(PREFIX + "{}: clicked cell {} ({}) asking for {}", prefix, cell, key,
                ScreenInput.terminalAmount(terminal).getState());
    }

    /**
     * "Requested &lt;item&gt; x&lt;n&gt;, producing &lt;n&gt;", where the screen may shorten the item's name with an ellipsis to
     * fit its row: everything around the name must match exactly.
     */
    static boolean isProducingSentence(String shown, int amount) {
        String marker = "@@NAME@@";
        String sentence = WareworksLang.translateDirect(WareworksLang.TERMINAL_PRODUCING, Component.literal(marker),
                LangNumberFormat.format(amount), LangNumberFormat.format(amount)).getString();
        int at = sentence.indexOf(marker);
        if (at < 0)
            return false;
        String before = sentence.substring(0, at);
        String after = sentence.substring(at + marker.length());
        return shown.length() > before.length() + after.length() && shown.startsWith(before) && shown.endsWith(after);
    }
}
