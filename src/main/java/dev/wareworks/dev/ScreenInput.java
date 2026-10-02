package dev.wareworks.dev;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseStockKeeperScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.StockKeeperMenuLayout;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.RequestConfirmation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Mouse and key input into an open screen, delivered through the methods GLFW's input callbacks call: a click is
 * {@code MouseHandler#onPress} with a press and then a release, a scroll is {@code MouseHandler#onScroll}, a key is
 * {@code KeyboardHandler#keyPress}. So everything between the device and the screen runs as for a person (NeoForge's
 * screen input events, {@code Screen#afterMouseAction}, the GUI scale conversion, the screen's own hit testing); only the
 * GLFW event itself is missing. The mouse position is written into the mouse handler right before, in the same client
 * thread task, so a real mouse over the window cannot move it in between. No input is ever sent while no screen is open
 * (the mouse handler would grab the mouse then).
 * <p>
 * <b>No modifier key is ever held, and none can be.</b> {@code Screen#hasShiftDown} and {@code hasControlDown} poll
 * GLFW's key state rather than reading the modifiers of the click event, so only a physical key can set one — which is
 * why {@link #click} instead <i>fails</i> when it finds one held ({@link #requireNoModifiers}) and why a scenario that
 * needs a modified click calls the branch the screen takes for it and says so.
 * <p>
 * The helpers that find where something is drawn read the screens' private layouts by reflection and check the result
 * against the screens' own hit tests before anything is clicked. Dev tooling only (ADR-014).
 */
final class ScreenInput {
    private static final Field MOUSE_X = field(MouseHandler.class, "xpos");
    private static final Field MOUSE_Y = field(MouseHandler.class, "ypos");
    private static final Method ON_PRESS = method(MouseHandler.class, "onPress", long.class, int.class, int.class,
            int.class);
    private static final Method ON_SCROLL = method(MouseHandler.class, "onScroll", long.class, double.class,
            double.class);
    private static final Field TERMINAL_LAYOUT = field(WarehouseTerminalScreen.class, "layout");
    private static final Field TERMINAL_AMOUNT = field(WarehouseTerminalScreen.class, "amountInput");
    private static final Field TERMINAL_LIST_BUTTON = field(WarehouseTerminalScreen.class, "listButton");
    private static final Method TERMINAL_CELL_AT = method(WarehouseTerminalScreen.class, "cellAt", double.class,
            double.class);
    private static final Method TERMINAL_ORDER_AT = method(WarehouseTerminalScreen.class, "orderAt", double.class,
            double.class);
    private static final Method TERMINAL_CANCEL_MARK_X = method(WarehouseTerminalScreen.class, "cancelMarkX");
    private static final Method TERMINAL_CONFIRM_LINES = method(WarehouseTerminalScreen.class, "confirmationLines",
            RequestConfirmation.class);
    private static final Method TERMINAL_LIST_CONFIRM_LINES = method(WarehouseTerminalScreen.class,
            "listConfirmationLines", ListOrderConfirmation.class);
    private static final Field PRODUCTION_LAYOUT = field(WarehouseProductionScreen.class, "layout");
    private static final Method PRODUCTION_CELL_AT = method(WarehouseProductionScreen.class, "cellAt", double.class,
            double.class);
    private static final Method PRODUCTION_OVER_STOPPED = method(WarehouseProductionScreen.class, "isOverStoppedRow",
            double.class, double.class);
    private static final Method PRODUCTION_TOOLTIP_AT = method(WarehouseProductionScreen.class, "tooltipAt", int.class,
            int.class);
    private static final Method KEEPER_ROW_AT = method(WarehouseStockKeeperScreen.class, "rowAt", double.class,
            double.class);
    private static final Method KEEPER_FIELD_AT = method(WarehouseStockKeeperScreen.class, "numberFieldAt",
            double.class, int.class);
    private static final Method KEEPER_OVER_STATUS = method(WarehouseStockKeeperScreen.class, "isOverStatus",
            double.class, double.class, int.class);
    private static final Method KEEPER_TOOLTIP_AT = method(WarehouseStockKeeperScreen.class, "tooltipAt", int.class,
            int.class);
    /** What the keeper screen's own hit tests return for "nothing here" ({@code WarehouseStockKeeperScreen.NONE}). */
    private static final int NO_NUMBER_FIELD = -1;
    /** Middle of a vanilla 16x16 menu slot, measured from {@code Slot#x}/{@code y}. */
    private static final double SLOT_CENTRE = 8.0;
    private static final Method FIND_SLOT = method(AbstractContainerScreen.class, "findSlot", double.class,
            double.class);

    private ScreenInput() {
    }

    /** A point in GUI coordinates (the ones a screen's {@code mouseClicked} receives). */
    record Point(double x, double y) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "(%.1f, %.1f)", x, y);
        }
    }

    // --- input ---------------------------------------------------------------------------------------------------

    /** A left click at {@code point}: press, then release. */
    static void click(Minecraft minecraft, Point point) {
        click(minecraft, point, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** A right click at {@code point}, which several screens read as "switch this off". */
    static void rightClick(Minecraft minecraft, Point point) {
        click(minecraft, point, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    /** A click with {@code button} at {@code point}: press, then release. */
    static void click(Minecraft minecraft, Point point, int button) {
        requireScreen(minecraft, "click");
        requireNoModifiers("click");
        moveMouse(minecraft, point);
        long window = minecraft.getWindow().getWindow();
        invoke(ON_PRESS, minecraft.mouseHandler, window, button, GLFW.GLFW_PRESS, 0);
        moveMouse(minecraft, point);
        invoke(ON_PRESS, minecraft.mouseHandler, window, button, GLFW.GLFW_RELEASE, 0);
    }

    /**
     * Puts the cursor on {@code point} without clicking, so the open screen draws what it draws for a player whose
     * mouse rests there — the tooltip of the cell under it, above all.
     */
    static void hover(Minecraft minecraft, Point point) {
        requireScreen(minecraft, "hover");
        moveMouse(minecraft, point);
    }

    /** One notch of the mouse wheel at {@code point}: positive scrolls up, negative down. */
    static void scroll(Minecraft minecraft, Point point, double notches) {
        requireScreen(minecraft, "scroll");
        moveMouse(minecraft, point);
        invoke(ON_SCROLL, minecraft.mouseHandler, minecraft.getWindow().getWindow(), 0.0, notches);
    }

    /** A key press and release, e.g. {@link GLFW#GLFW_KEY_ESCAPE}. */
    static void key(Minecraft minecraft, int key) {
        requireScreen(minecraft, "key");
        long window = minecraft.getWindow().getWindow();
        int scanCode = GLFW.glfwGetKeyScancode(key);
        minecraft.keyboardHandler.keyPress(window, key, scanCode, GLFW.GLFW_PRESS, 0);
        minecraft.keyboardHandler.keyPress(window, key, scanCode, GLFW.GLFW_RELEASE, 0);
    }

    /** Writes the cursor position the mouse handler converts back to {@code point} (window pixels from GUI units). */
    private static void moveMouse(Minecraft minecraft, Point point) {
        double toWindowX = (double) minecraft.getWindow().getScreenWidth() / minecraft.getWindow().getGuiScaledWidth();
        double toWindowY = (double) minecraft.getWindow().getScreenHeight() / minecraft.getWindow().getGuiScaledHeight();
        set(MOUSE_X, minecraft.mouseHandler, point.x() * toWindowX);
        set(MOUSE_Y, minecraft.mouseHandler, point.y() * toWindowY);
    }

    private static void requireScreen(Minecraft minecraft, String what) {
        if (minecraft.screen == null)
            throw new VisualTestException("no screen is open for the " + what);
    }

    /**
     * Fails while a modifier key is physically held down.
     * <p>
     * {@code Screen#hasShiftDown} and {@code hasControlDown} poll GLFW's key state rather than reading the modifiers of
     * the click event, so a screen reads the <b>real</b> keyboard of whoever is at the machine — and every screen this
     * class clicks means something different with Shift or Ctrl held (a terminal asks for a stack or for everything, a
     * stock keeper writes an item into a row, a production screen scrolls in coarse steps). A run in which somebody
     * happens to rest a hand on Shift would therefore assert something other than what its step says, which has to be a
     * loud failure rather than a silently different result. It is also the reason no method here can <i>hold</i> a
     * modifier: nothing but a physical key can make GLFW report one.
     */
    static void requireNoModifiers(String what) {
        if (Screen.hasShiftDown() || Screen.hasControlDown() || Screen.hasAltDown())
            throw new VisualTestException("a modifier key is held down while the harness sends a " + what
                    + " (shift=" + Screen.hasShiftDown() + " ctrl=" + Screen.hasControlDown() + " alt="
                    + Screen.hasAltDown() + "); keep your hands off the keyboard while a visual test runs");
    }

    // --- where things are drawn ------------------------------------------------------------------------------------

    /** The centre of a widget. */
    static Point centre(AbstractWidget widget) {
        return new Point(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
    }

    /** The terminal's amount scroll input. */
    static ScrollInput terminalAmount(WarehouseTerminalScreen screen) {
        return (ScrollInput) get(TERMINAL_AMOUNT, screen);
    }

    /** The centre of cell {@code index} of the terminal's visible stock grid, checked with the screen's own hit test. */
    static Point terminalCell(WarehouseTerminalScreen screen, int index) {
        TerminalMenuLayout layout = (TerminalMenuLayout) get(TERMINAL_LAYOUT, screen);
        int column = index % TerminalMenuLayout.GRID_COLUMNS;
        int row = index / TerminalMenuLayout.GRID_COLUMNS;
        Point point = new Point(screen.getGuiLeft() + layout.gridX() + column * TerminalMenuLayout.SLOT
                + TerminalMenuLayout.SLOT / 2.0, screen.getGuiTop() + layout.gridY() + row * TerminalMenuLayout.SLOT
                + TerminalMenuLayout.SLOT / 2.0);
        int hit = (Integer) invoke(TERMINAL_CELL_AT, screen, point.x(), point.y());
        if (hit != index)
            throw new VisualTestException("the terminal's hit test puts " + point + " on cell " + hit + ", not " + index);
        return point;
    }

    /**
     * The centre of one of the two buttons of the terminal's confirmation panel (M15 part 2), checked with the
     * screen's own hit test.
     * <p>
     * The panel is the same one for both of the terminal's questions since M23 (issue #19) — a click's cost and a
     * whole clipboard list's — so this asks the screen whether it is asking <i>anything</i>
     * ({@code WarehouseTerminalScreen#isAskingSomething}) rather than only about a click's question.
     *
     * @param confirm {@code true} for "Confirm", {@code false} for "Cancel"
     */
    static Point terminalConfirmButton(WarehouseTerminalScreen screen, boolean confirm) {
        if (!screen.isAskingSomething())
            throw new VisualTestException("the terminal is not asking anything, so it draws no confirm button");
        // The screen hands out the centre itself: its panel is laid out once per question and kept, so reflecting
        // three private helpers back together would be reading a geometry that no longer exists (M15 review fix).
        Point point = new Point(screen.confirmButtonCenterX(confirm), screen.confirmButtonCenterY());
        if (!screen.isOverConfirmButton(point.x(), point.y(), confirm))
            throw new VisualTestException("the terminal's hit test does not put " + point + " on the "
                    + (confirm ? "confirm" : "cancel") + " button");
        return point;
    }

    /**
     * The centre of the terminal's <b>list button</b> — the one button of a clipboard order (Fetch, Cancel, Resume or
     * Answer, M23, issue #19) — checked with the widget's own hit test.
     */
    static Point terminalListButton(WarehouseTerminalScreen screen) {
        IconButton button = (IconButton) get(TERMINAL_LIST_BUTTON, screen);
        if (button == null)
            throw new VisualTestException("the terminal screen has no list button yet (not laid out)");
        Point point = centre(button);
        if (!button.isMouseOver(point.x(), point.y()))
            throw new VisualTestException("the list button's own hit test does not put " + point + " on it");
        return point;
    }

    /**
     * Every line of the terminal's panel while a whole <b>clipboard list</b> is being asked about (M23, issue #19), as
     * it is drawn: the totals, the entries worth naming and the question itself.
     * <p>
     * The counterpart of {@link #terminalConfirmationLines} for the other of the two questions the one panel shows. A
     * screenshot cannot be trusted to prove which numbers a dialog names, so a run reads them.
     */
    static List<Component> terminalListConfirmationLines(WarehouseTerminalScreen screen) {
        ListOrderConfirmation<ItemKey> question = screen.listConfirmation();
        if (question == null)
            throw new VisualTestException("the terminal is not asking about a list, so it draws no list panel");
        @SuppressWarnings("unchecked")
        List<Component> lines = (List<Component>) invoke(TERMINAL_LIST_CONFIRM_LINES, screen, question);
        return lines;
    }

    /**
     * The centre of menu slot {@code index} of any container screen, checked with the screen's own slot lookup.
     * <p>
     * This is how an item is really carried from one slot to another: a left click picks the stack up onto the cursor
     * and a second one puts it down, which is what a player does when no shift-click is available
     * ({@link #requireNoModifiers}).
     */
    static Point menuSlot(AbstractContainerScreen<?> screen, int index) {
        List<Slot> slots = screen.getMenu().slots;
        if (index < 0 || index >= slots.size())
            throw new VisualTestException("the screen has no menu slot " + index + " (it has " + slots.size() + ")");
        Slot slot = slots.get(index);
        Point point = new Point(screen.getGuiLeft() + slot.x + SLOT_CENTRE, screen.getGuiTop() + slot.y + SLOT_CENTRE);
        if (invoke(FIND_SLOT, screen, point.x(), point.y()) != slot)
            throw new VisualTestException("the screen's slot lookup does not find menu slot " + index + " at " + point);
        return point;
    }

    /**
     * The centre of the <b>item half</b> of the visible production line {@code index} of the terminal, i.e. the part of a
     * chain's line that a click opens its step panel with (M20, issue #4), checked with the screen's own hit test.
     * <p>
     * A chain's line splits the click: the {@code x} column at its end gives up on the whole plan, everything left of it
     * asks for the steps ({@code WarehouseTerminalScreen#clickOrderLine}). This point is therefore also checked to lie a
     * whole margin left of the cancel mark, so a run can never take the one click for the other.
     */
    static Point terminalOrderLine(WarehouseTerminalScreen screen, int index) {
        TerminalMenuLayout layout = (TerminalMenuLayout) get(TERMINAL_LAYOUT, screen);
        Point point = new Point(screen.getGuiLeft() + TerminalMenuLayout.MARGIN + 1.0,
                screen.getGuiTop() + layout.orderLineY(index) + TerminalMenuLayout.LABEL_HEIGHT / 2.0);
        int hit = (Integer) invoke(TERMINAL_ORDER_AT, screen, point.x(), point.y());
        if (hit != index)
            throw new VisualTestException("the terminal's hit test puts " + point + " on order line " + hit + ", not "
                    + index);
        int cancelMarkX = (Integer) invoke(TERMINAL_CANCEL_MARK_X, screen);
        if (point.x() >= screen.getGuiLeft() + cancelMarkX - TerminalMenuLayout.MARGIN)
            throw new VisualTestException("the terminal draws its cancel mark at " + cancelMarkX + ", so " + point
                    + " would be read as giving up on the order instead of opening its steps");
        return point;
    }

    /**
     * The centre of one of the two buttons of the terminal's step panel (M20): "Give up on the chain" or "Close",
     * checked with the screen's own hit test.
     *
     * @param cancel {@code true} for the button that ends the whole plan, {@code false} for the one that only closes
     */
    static Point terminalStepButton(WarehouseTerminalScreen screen, boolean cancel) {
        if (screen.openStepPanelPlan().isEmpty())
            throw new VisualTestException("the terminal shows no step panel, so it draws no "
                    + (cancel ? "\"give up\"" : "\"close\"") + " button");
        // The screen hands out the centre itself, for the reason the confirmation panel's buttons do: its panel is laid
        // out once per order payload and kept, so reflecting the geometry back together would read a stale one.
        Point point = new Point(screen.stepButtonCenterX(cancel), screen.stepButtonCenterY());
        if (!screen.isOverStepButton(point.x(), point.y(), cancel))
            throw new VisualTestException("the terminal's hit test does not put " + point + " on the "
                    + (cancel ? "\"give up\"" : "\"close\"") + " button of the step panel");
        return point;
    }

    /**
     * The centre of the <b>stopped row</b> of a production station's screen — the safety stop's way back at the machine
     * that lost the batch (M20, issue #4) — checked with the screen's own hit test.
     * <p>
     * The row takes the first order line, and a station whose window has no order line at all draws it nowhere: the hit
     * test therefore answers whether a player could click it, and a point it rejects is a failure rather than a click
     * into the void.
     */
    static Point productionStoppedRow(WarehouseProductionScreen screen) {
        ProductionMenuLayout layout = (ProductionMenuLayout) get(PRODUCTION_LAYOUT, screen);
        Point point = new Point(screen.getGuiLeft() + ProductionMenuLayout.MARGIN + 1.0,
                screen.getGuiTop() + layout.orderLineY(0) + ProductionMenuLayout.LABEL_HEIGHT / 2.0);
        if (!(Boolean) invoke(PRODUCTION_OVER_STOPPED, screen, point.x(), point.y()))
            throw new VisualTestException("the production screen's hit test does not put " + point
                    + " on its stopped row (order lines: " + layout.orderLines() + ", stopped: "
                    + screen.state().stopped() + ")");
        return point;
    }

    /**
     * The tooltip a production station's screen draws for a mouse at {@code point} — the words of the stopped row or of
     * a pattern tab, which only exist while a cursor rests on them. The scenario hovers the point first, so the shot
     * shows what this reads.
     */
    static List<Component> productionTooltip(WarehouseProductionScreen screen, Point point) {
        @SuppressWarnings("unchecked")
        List<Component> tooltip = (List<Component>) invoke(PRODUCTION_TOOLTIP_AT, screen, (int) point.x(),
                (int) point.y());
        return tooltip;
    }

    /**
     * The centre of the status mark of row {@code row} in the stock keeper's screen — the mark a click on lets a paused
     * rule order again (M15 part 2) — checked with the screen's own hit tests for the row and for the mark.
     */
    static Point keeperStatusMark(WarehouseStockKeeperScreen screen, int row) {
        StockKeeperMenuLayout layout = screen.getMenu().layout();
        int line = row - screen.scroll();
        if (line < 0 || line >= layout.visibleRows())
            throw new VisualTestException("row " + row + " is not on screen (scroll " + screen.scroll() + ")");
        Point point = new Point(
                screen.getGuiLeft() + layout.statusX() + StockKeeperMenuLayout.STATUS_WIDTH / 2.0,
                screen.getGuiTop() + layout.rowY(line) + StockKeeperMenuLayout.SLOT / 2.0);
        int hitRow = (Integer) invoke(KEEPER_ROW_AT, screen, point.x(), point.y());
        if (hitRow != row)
            throw new VisualTestException("the keeper screen's hit test puts " + point + " on row " + hitRow + ", not "
                    + row);
        // The number fields end exactly where the mark starts, so a point the field hit test still claims would be
        // read as "a click on the reserve" instead of "a click on the mark".
        int hitField = (Integer) invoke(KEEPER_FIELD_AT, screen, point.x(), row);
        if (hitField != NO_NUMBER_FIELD)
            throw new VisualTestException("the keeper screen's hit test puts " + point + " on number field " + hitField
                    + " rather than on the status mark");
        if (!(Boolean) invoke(KEEPER_OVER_STATUS, screen, point.x(), point.y(), row))
            throw new VisualTestException("the keeper screen's hit test does not put " + point + " on the status mark "
                    + "of row " + row);
        return point;
    }

    /** The centre of pattern cell {@code cell} of the production screen, checked with the screen's own hit test. */
    static Point productionCell(WarehouseProductionScreen screen, int cell) {
        ProductionMenuLayout layout = (ProductionMenuLayout) get(PRODUCTION_LAYOUT, screen);
        Point point = new Point(screen.getGuiLeft() + layout.patternCellX(cell) + ProductionMenuLayout.SLOT / 2.0,
                screen.getGuiTop() + layout.patternCellY(cell) + ProductionMenuLayout.SLOT / 2.0);
        int hit = (Integer) invoke(PRODUCTION_CELL_AT, screen, point.x(), point.y());
        if (hit != cell)
            throw new VisualTestException("the production screen's hit test puts " + point + " on cell " + hit
                    + ", not " + cell);
        return point;
    }

    /**
     * The centre of one of the three number fields of row {@code row} in the stock keeper's screen, checked with the
     * screen's own hit tests for the row and the field.
     *
     * @param field {@link StockKeeperMenuLayout#FIELD_MINIMUM}, {@code FIELD_MAXIMUM} or {@code FIELD_RESERVE}
     */
    static Point keeperNumberField(WarehouseStockKeeperScreen screen, int row, int field) {
        StockKeeperMenuLayout layout = screen.getMenu().layout();
        int line = row - screen.scroll();
        if (line < 0 || line >= layout.visibleRows())
            throw new VisualTestException("row " + row + " is not on screen (scroll " + screen.scroll() + ")");
        Point point = new Point(
                screen.getGuiLeft() + layout.numberX(field) + StockKeeperMenuLayout.NUMBER_WIDTH / 2.0,
                screen.getGuiTop() + layout.rowY(line) + StockKeeperMenuLayout.SLOT / 2.0);
        int hitRow = (Integer) invoke(KEEPER_ROW_AT, screen, point.x(), point.y());
        if (hitRow != row)
            throw new VisualTestException("the keeper screen's hit test puts " + point + " on row " + hitRow + ", not "
                    + row);
        int hitField = (Integer) invoke(KEEPER_FIELD_AT, screen, point.x(), row);
        if (hitField != field)
            throw new VisualTestException("the keeper screen's hit test puts " + point + " on field " + hitField
                    + ", not " + field);
        return point;
    }

    /**
     * Every line of the terminal's confirmation panel as it is drawn: the title, the costs, the note that Ctrl skips
     * the question, and the "the warehouse has changed" line of a repeated one.
     * <p>
     * {@code WarehouseTerminalScreen#confirmationQuestion} is the public one-component form for a log line; the panel
     * itself draws these, so a check about what a player <b>reads</b> asks for these.
     */
    static List<Component> terminalConfirmationLines(WarehouseTerminalScreen screen) {
        RequestConfirmation<ItemKey> question = screen.confirmation();
        if (question == null)
            throw new VisualTestException("the terminal is not asking anything, so it draws no panel");
        @SuppressWarnings("unchecked")
        List<Component> lines = (List<Component>) invoke(TERMINAL_CONFIRM_LINES, screen, question);
        return lines;
    }

    /**
     * The tooltip the stock keeper's screen draws for a mouse at {@code point} — the row's own words, which only exist
     * while a cursor rests on it. The scenario hovers the point first, so the shot shows what this reads.
     */
    static List<Component> keeperTooltip(WarehouseStockKeeperScreen screen, Point point) {
        @SuppressWarnings("unchecked")
        List<Component> tooltip = (List<Component>) invoke(KEEPER_TOOLTIP_AT, screen, (int) point.x(), (int) point.y());
        return tooltip;
    }

    /**
     * The centre of the menu slot showing hotbar slot {@code hotbarIndex} of the player's inventory, checked with the
     * container screen's own slot lookup.
     */
    static Point hotbarSlot(AbstractContainerScreen<?> screen, int hotbarIndex) {
        for (Slot slot : screen.getMenu().slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() != hotbarIndex)
                continue;
            Point point = new Point(screen.getGuiLeft() + slot.x + SLOT_CENTRE,
                    screen.getGuiTop() + slot.y + SLOT_CENTRE);
            if (invoke(FIND_SLOT, screen, point.x(), point.y()) != slot)
                throw new VisualTestException("the screen's slot lookup does not find hotbar slot " + hotbarIndex + " at "
                        + point);
            return point;
        }
        throw new VisualTestException("the screen shows no slot for hotbar slot " + hotbarIndex);
    }

    // --- reflection ------------------------------------------------------------------------------------------------

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach " + owner.getName() + "#" + name, error);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach " + owner.getName() + "#" + name, error);
        }
    }

    /** The height of a confirmation button, read from the screen's own constant. */
    private static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read " + field + ": " + error);
        }
    }

    private static void set(Field field, Object owner, double value) {
        try {
            field.setDouble(owner, value);
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot write " + field + ": " + error);
        }
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot call " + method + ": " + error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime)
                throw runtime;
            throw new VisualTestException("calling " + method + " failed: " + cause);
        }
    }
}
