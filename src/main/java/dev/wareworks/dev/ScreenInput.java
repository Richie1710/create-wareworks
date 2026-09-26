package dev.wareworks.dev;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseStockKeeperScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.StockKeeperMenuLayout;
import dev.wareworks.content.station.TerminalMenuLayout;
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
    private static final Method TERMINAL_CELL_AT = method(WarehouseTerminalScreen.class, "cellAt", double.class,
            double.class);
    private static final Method TERMINAL_CONFIRM_LINES = method(WarehouseTerminalScreen.class, "confirmationLines",
            RequestConfirmation.class);
    private static final Field PRODUCTION_LAYOUT = field(WarehouseProductionScreen.class, "layout");
    private static final Method PRODUCTION_CELL_AT = method(WarehouseProductionScreen.class, "cellAt", double.class,
            double.class);
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
     *
     * @param confirm {@code true} for "Confirm", {@code false} for "Cancel"
     */
    static Point terminalConfirmButton(WarehouseTerminalScreen screen, boolean confirm) {
        if (screen.confirmation() == null)
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
            Point point = new Point(screen.getGuiLeft() + slot.x + 8.0, screen.getGuiTop() + slot.y + 8.0);
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
