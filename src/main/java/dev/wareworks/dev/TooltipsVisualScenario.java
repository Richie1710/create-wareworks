package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.foundation.item.ItemDescription;

import dev.wareworks.Wareworks;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Scenario "tooltips": every Wareworks item's Shift tooltip, in English and in German, on a 5120x1440 screen at the
 * automatic GUI scale (issue #22).
 * <p>
 * Create wraps a description at a fixed 200 px per line, so a long one grows <b>downwards</b>, and vanilla's
 * {@code DefaultTooltipPositioner} only pushes a tooltip up from the bottom edge: one taller than the screen loses its
 * top. A wide screen does not help, its height does: the automatic GUI scale is the largest that leaves 320 x 240, so
 * 1440 px runs at scale 6 with 240 px — less room than 1080 px at scale 4. {@code ItemTooltipLengthTest} keeps the
 * descriptions short; this run checks the result on the real client, with the real font, the real tooltip modifier and
 * the real lang files.
 * <p>
 * A 5120x1440 window does not fit on a 5120x1440 monitor (the title bar and the task bar take some of it), so the run
 * goes <b>fullscreen</b> and requires the screen to be exactly that size; on another monitor it fails and says so. The
 * size can be changed with {@code -Dwareworks.visualTest.tooltipWidth}/{@code tooltipHeight}.
 * <p>
 * For every item with a Create description it asserts that the Shift tooltip, drawn where vanilla draws it for an item
 * hovered at the bottom of the screen, keeps its top border on screen with {@value #RESERVED_LINES} line to spare —
 * the mod name JEI appends in every pack that has it, which this dev client does not load. Then it shows every
 * tooltip of each language that way and photographs it, for a reader who has to judge the wording.
 */
public final class TooltipsVisualScenario implements VisualScenario {
    public static final String NAME = "tooltips";

    private static final int TARGET_WIDTH = Integer.getInteger("wareworks.visualTest.tooltipWidth", 5120);
    private static final int TARGET_HEIGHT = Integer.getInteger("wareworks.visualTest.tooltipHeight", 1440);
    /** Lines a real pack adds that this dev client does not: JEI's mod name under every item tooltip. */
    private static final int RESERVED_LINES = 1;
    /** {@code ClientTextTooltip#getHeight}. */
    private static final int LINE_HEIGHT = 10;
    /** {@code DefaultTooltipPositioner} keeps 3 px below a tooltip; {@code TooltipRenderUtil} draws 4 px above it. */
    private static final int BELOW = 3;
    private static final int BORDER_ABOVE = 4;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 10;
    private static final List<String> LANGUAGES = List.of("en_us", "de_de");

    private final VisualLanguage language = new VisualLanguage(1200, 10);
    /**
     * Every item with a Create description, in creative tab order. The shots are planned before the registries are
     * bound, so the list is spelled out; {@link #checkAll} fails when an item with a description is missing from it.
     */
    private static final List<String> ITEM_IDS = List.of("stacker_crane", "warehouse_rail", "warehouse_controller",
            "warehouse_interface", "rack_bay_wood", "rack_bay_andesite", "rack_bay_brass", "fluid_bay_copper",
            "fluid_bay_brass", "warehouse_input", "warehouse_output", "warehouse_terminal", "warehouse_production",
            "warehouse_stock_keeper", "warehouse_home_point");

    /** The Shift tooltip of every item in the language measured last, by item path, for the shots after it. */
    private final Map<String, List<Component>> measured = new HashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    /** Tooltips need no scene; only the origin the scenario contract expects is recorded. */
    @Override
    public void setup(VisualScript script) {
        script.server("tooltips: remember the scene origin", TooltipsVisualScenario::rememberOrigin);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Tooltips are drawn by the GUI, never by Flywheel: one pass checks everything.
        if (pass != VisualPass.FLYWHEEL)
            return;
        // Fullscreen goes to the monitor holding most of the window (ScreenManager#findBestMonitor), so a window that
        // WindowPlacement opened on another monitor is first moved onto the one with the target size, if there is one.
        script.client("tooltips: move the window to a " + TARGET_WIDTH + "x" + TARGET_HEIGHT + " monitor", context -> {
            long monitor = WindowPlacement.monitorWithMode(TARGET_WIDTH, TARGET_HEIGHT);
            Window window = context.minecraft().getWindow();
            if (monitor != 0L && !window.isFullscreen() && !WindowPlacement.isOn(window, monitor))
                WindowPlacement.moveTo(window, monitor);
        });
        script.until("tooltips: the window is on that monitor", context -> {
            long monitor = WindowPlacement.monitorWithMode(TARGET_WIDTH, TARGET_HEIGHT);
            return monitor == 0L || context.minecraft().getWindow().isFullscreen()
                    || WindowPlacement.isOn(context.minecraft().getWindow(), monitor);
        }, RESIZE_TIMEOUT_TICKS);
        script.client("tooltips: automatic GUI scale and fullscreen", context -> {
            context.minecraft().options.guiScale().set(0);
            Window window = context.minecraft().getWindow();
            // A fullscreen window that is not in front would minimise itself (GLFW's default) and report a 0x0
            // framebuffer, which vanilla ignores; this run is started from a terminal and rarely has the focus.
            GLFW.glfwSetWindowAttrib(window.getWindow(), GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
            GLFW.glfwFocusWindow(window.getWindow());
            if (!window.isFullscreen())
                window.toggleFullScreen();
        });
        ArmVisualScenario.untilOrFail(script, "tooltips: the screen is " + TARGET_WIDTH + "x" + TARGET_HEIGHT,
                context -> restoredAt(context, TARGET_WIDTH, TARGET_HEIGHT),
                RESIZE_TIMEOUT_TICKS, context -> describe(context.minecraft().getWindow())
                        + "; this check needs a monitor of exactly " + TARGET_WIDTH + "x" + TARGET_HEIGHT);
        script.waitTicks(SETTLE_TICKS).client("tooltips: check the GUI scale", TooltipsVisualScenario::checkScale);

        for (String code : LANGUAGES) {
            if (!code.equals("en_us"))
                language.switchTo(script, "tooltips: ", code);
            script.client("tooltips: CHECK every Shift tooltip fits [" + code + "]", this::checkAll);
            for (String id : ITEM_IDS)
                script.client("tooltips: show the Shift tooltip of " + id + " [" + code + "]",
                                context -> context.minecraft().setScreen(new BottomTooltipScreen(measured.get(id))))
                        .until("tooltips: the tooltip screen is open",
                                context -> context.minecraft().screen instanceof BottomTooltipScreen, SETTLE_TICKS * 10)
                        .shotWithGui(code + "-" + id);
            script.client("tooltips: close the tooltip screen", context -> context.minecraft().setScreen(null));
        }
        language.switchTo(script, "tooltips: ", "en_us");
        script.client("tooltips: leave fullscreen", context -> {
            if (context.minecraft().getWindow().isFullscreen())
                context.minecraft().getWindow().toggleFullScreen();
        });
        script.until("tooltips: the window is back", context -> !context.minecraft().getWindow().isFullscreen()
                && synced(context) && !framebufferIs(context.minecraft().getWindow(), TARGET_WIDTH, TARGET_HEIGHT),
                RESIZE_TIMEOUT_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        Window window = context.minecraft().getWindow();
        return String.format(Locale.ROOT, "screen=%dx%d guiScale=%.0f gui=%dx%d lang=%s", window.getWidth(),
                window.getHeight(), window.getGuiScale(), window.getGuiScaledWidth(), window.getGuiScaledHeight(),
                context.minecraft().getLanguageManager().getSelected());
    }

    // --- client actions -----------------------------------------------------------------------------------------------

    private static String describe(Window window) {
        long monitor = GLFW.glfwGetWindowMonitor(window.getWindow());
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window.getWindow(), width, height);
        return String.format(Locale.ROOT, "fullscreen=%s (GLFW monitor %s), framebuffer %dx%d (GLFW %dx%d, minimised "
                        + "%s), window %dx%d at %d,%d", window.isFullscreen(),
                monitor == 0L ? "none" : GLFW.glfwGetMonitorName(monitor), window.getWidth(), window.getHeight(),
                width[0], height[0], GLFW.glfwGetWindowAttrib(window.getWindow(), GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE,
                window.getScreenWidth(), window.getScreenHeight(), window.getX(), window.getY());
    }

    /** Whether the fullscreen window has reached the target size (see {@link #synced}). */
    private static boolean restoredAt(VisualContext context, int width, int height) {
        Window window = context.minecraft().getWindow();
        return window.isFullscreen() && synced(context) && framebufferIs(window, width, height);
    }

    /**
     * Whether the window knows the framebuffer size GLFW reports. A window switched into or out of fullscreen while
     * another one has the focus does not always get GLFW's framebuffer callback, so {@link Window} keeps the old size
     * although GLFW already reports the new one; a minimised window reports none at all. So a minimised window is
     * restored, and a stale size is read with the window's own {@code refreshFramebufferSize} (private, reached by
     * reflection like the input in {@link ScreenInput}) before the client resizes the way the callback would have.
     * The craft scenario's windowed sizes use it too ({@code CraftVisualScenario}).
     */
    static boolean synced(VisualContext context) {
        Window window = context.minecraft().getWindow();
        if (GLFW.glfwGetWindowAttrib(window.getWindow(), GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
            GLFW.glfwRestoreWindow(window.getWindow());
            return false;
        }
        int[] glfwWidth = new int[1];
        int[] glfwHeight = new int[1];
        GLFW.glfwGetFramebufferSize(window.getWindow(), glfwWidth, glfwHeight);
        if (glfwWidth[0] <= 0 || glfwHeight[0] <= 0)
            return false;
        if (framebufferIs(window, glfwWidth[0], glfwHeight[0]))
            return true;
        try {
            Method refresh = Window.class.getDeclaredMethod("refreshFramebufferSize");
            refresh.setAccessible(true);
            refresh.invoke(window);
        } catch (ReflectiveOperationException e) {
            throw new VisualTestException("cannot make the window read its framebuffer size: " + e);
        }
        context.minecraft().resizeDisplay();
        LOGGER.info(PREFIX + "{}: the window missed the framebuffer callback and read {}x{} itself",
                context.scenarioName(), window.getWidth(), window.getHeight());
        return framebufferIs(window, glfwWidth[0], glfwHeight[0]);
    }

    static boolean framebufferIs(Window window, int width, int height) {
        return window.getWidth() == width && window.getHeight() == height;
    }

    /** The scale vanilla picks for the target screen, from its own rule, and the GUI height that leaves. */
    private static void checkScale(VisualContext context) {
        Window window = context.minecraft().getWindow();
        int expected = window.calculateScale(0, context.minecraft().isEnforceUnicode());
        if (window.getGuiScale() != expected)
            throw new VisualTestException("the GUI scale is " + window.getGuiScale() + ", automatic would be " + expected);
        LOGGER.info(PREFIX + "tooltips: {}x{} at the automatic GUI scale {} leaves {}x{} GUI px", window.getWidth(),
                window.getHeight(), expected, window.getGuiScaledWidth(), window.getGuiScaledHeight());
    }

    /**
     * Measures the Shift tooltip of every Wareworks item that has a Create description, the way vanilla lays it out:
     * {@value #LINE_HEIGHT} px per line, {@value #BELOW} px kept below it, the border {@value #BORDER_ABOVE} px above.
     */
    private void checkAll(VisualContext context) {
        String code = context.minecraft().getLanguageManager().getSelected();
        int screen = context.minecraft().getWindow().getGuiScaledHeight();
        List<String> tooTall = new ArrayList<>();
        measured.clear();
        String tallest = "";
        int tallestLines = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (!Wareworks.ID.equals(BuiltInRegistries.ITEM.getKey(item).getNamespace())
                    || ItemDescription.create(item, FontHelper.Palette.STANDARD_CREATE) == null)
                continue;
            ItemStack stack = new ItemStack(item);
            List<Component> lines = ArmVisualScenario.shiftTooltip(context, stack);
            measured.put(BuiltInRegistries.ITEM.getKey(item).getPath(), lines);
            int needed = BORDER_ABOVE + (lines.size() + RESERVED_LINES) * LINE_HEIGHT + BELOW;
            String name = stack.getHoverName().getString();
            LOGGER.info(PREFIX + "tooltips: [{}] {}: {} lines, {} of {} px with {} line to spare", code, name,
                    lines.size(), needed, screen, RESERVED_LINES);
            if (needed > screen)
                tooTall.add(name + " (" + lines.size() + " lines, " + needed + " px)");
            if (lines.size() > tallestLines) {
                tallestLines = lines.size();
                tallest = name;
            }
        }
        if (!measured.keySet().equals(new HashSet<>(ITEM_IDS)))
            throw new VisualTestException("the items with a Create description are " + new TreeSet<>(measured.keySet())
                    + ", but the scenario shoots " + ITEM_IDS);
        if (!tooTall.isEmpty())
            throw new VisualTestException("[" + code + "] these Shift tooltips do not fit " + screen + " GUI px: "
                    + String.join(", ", tooTall));
        LOGGER.info(PREFIX + "tooltips: CHECK PASS [{}]: all {} Shift tooltips fit {} GUI px; the tallest is {} with {} "
                + "lines", code, measured.size(), screen, tallest, tallestLines);
    }

    // --- server actions -----------------------------------------------------------------------------------------------

    private static void rememberOrigin(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        context.setOrigin(new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0));
    }

    /**
     * Draws one tooltip exactly where vanilla draws it for an item hovered at the very bottom of the screen, the worst
     * case: the positioner pushes it up by its own height, and nothing scales it down.
     */
    private static final class BottomTooltipScreen extends Screen {
        private final List<Component> lines;

        BottomTooltipScreen(List<Component> lines) {
            super(Component.empty());
            this.lines = List.copyOf(lines);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.renderComponentTooltip(font, lines, 8, height);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
