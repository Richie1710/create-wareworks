package dev.wareworks.dev;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.TerminalMenuLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Mouse and key input into an open screen, delivered through the methods GLFW's input callbacks call: a click is
 * {@code MouseHandler#onPress} with a press and then a release, a scroll is {@code MouseHandler#onScroll}, a key is
 * {@code KeyboardHandler#keyPress}. So everything between the device and the screen runs as for a person (NeoForge's
 * screen input events, {@code Screen#afterMouseAction}, the GUI scale conversion, the screen's own hit testing); only the
 * GLFW event itself is missing. The mouse position is written into the mouse handler right before, in the same client
 * thread task, so a real mouse over the window cannot move it in between. No modifier key is ever held, and no input is
 * ever sent while no screen is open (the mouse handler would grab the mouse then).
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
    private static final Field PRODUCTION_LAYOUT = field(WarehouseProductionScreen.class, "layout");
    private static final Method PRODUCTION_CELL_AT = method(WarehouseProductionScreen.class, "cellAt", double.class,
            double.class);
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
        requireScreen(minecraft, "click");
        moveMouse(minecraft, point);
        long window = minecraft.getWindow().getWindow();
        invoke(ON_PRESS, minecraft.mouseHandler, window, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        moveMouse(minecraft, point);
        invoke(ON_PRESS, minecraft.mouseHandler, window, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
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
