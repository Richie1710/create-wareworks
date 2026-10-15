package dev.wareworks.dev;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.slf4j.Logger;

/**
 * Dev convenience: opens the client window on a chosen monitor, so the windows of {@code runClient}, the visual
 * scenarios and the robustness run do not cover the monitor someone is working on. The number comes from the system
 * property {@value #PROPERTY} (1 is the primary monitor, 2 the next one GLFW lists, and so on), which
 * {@code build.gradle} sets from the Gradle property {@code wareworks.windowMonitor} or the environment variable
 * {@code WAREWORKS_WINDOW_MONITOR}; without it the game places its window itself.
 * <p>
 * Like the visual harness it is dev tooling (ADR-014): {@code WareworksClient} installs it only outside production,
 * and release jars do not contain it. The window is moved once client setup runs on the main thread, so the early
 * loading window still shows on the primary monitor for the first seconds.
 * <p>
 * The game keeps its windowed position in two private fields of {@link Window} that its move callback does not
 * update, and {@link Window#setWindowed} moves the window back there. {@link #moveTo} therefore writes them as well,
 * so a scenario that resizes the window (the craft scenario's scale-4 sizes) stays on the chosen monitor.
 */
public final class WindowPlacement {
    /** System property naming the monitor; a compile-time constant, so reading it does not load this class. */
    public static final String PROPERTY = "wareworks.windowMonitor";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = VisualTestHarness.PREFIX + "window: ";

    private WindowPlacement() {
    }

    /** Moves the window to monitor {@code value} once client setup runs; a value that is no number is reported. */
    public static void install(IEventBus modEventBus, String value) {
        int number;
        try {
            number = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn(PREFIX + "ignoring {}={}: not a monitor number (1 is the primary monitor)", PROPERTY, value);
            return;
        }
        modEventBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> moveToMonitor(number)));
    }

    /** Monitor {@code number} as GLFW lists it (1 is the primary monitor), or 0 when there is no such monitor. */
    static long monitor(int number) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null || number < 1 || number > monitors.limit())
            return 0L;
        return monitors.get(number - 1);
    }

    /** The first monitor whose current video mode is {@code width} x {@code height}, or 0 when none is. */
    static long monitorWithMode(int width, int height) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null)
            return 0L;
        for (int i = 0; i < monitors.limit(); i++) {
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitors.get(i));
            if (mode != null && mode.width() == width && mode.height() == height)
                return monitors.get(i);
        }
        return 0L;
    }

    /** Whether the window's top-left corner lies on {@code monitor}. */
    static boolean isOn(Window window, long monitor) {
        int[] x = new int[1];
        int[] y = new int[1];
        GLFW.glfwGetMonitorPos(monitor, x, y);
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        return mode != null && window.getX() >= x[0] && window.getX() < x[0] + mode.width()
                && window.getY() >= y[0] && window.getY() < y[0] + mode.height();
    }

    private static void moveToMonitor(int number) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        int count = monitors == null ? 0 : monitors.limit();
        long monitor = monitor(number);
        if (monitor == 0L) {
            LOGGER.warn(PREFIX + "there is no monitor {} ({} connected); the window stays where it is", number, count);
            return;
        }
        moveTo(Minecraft.getInstance().getWindow(), monitor);
    }

    /**
     * Centres the windowed window in {@code monitor}'s work area (top-left when it does not fit) and makes that the
     * game's windowed position. A fullscreen window is left alone.
     */
    static void moveTo(Window window, long monitor) {
        if (window.isFullscreen()) {
            LOGGER.info(PREFIX + "the window is fullscreen; not moved");
            return;
        }
        long handle = window.getWindow();
        int[] areaX = new int[1];
        int[] areaY = new int[1];
        int[] areaWidth = new int[1];
        int[] areaHeight = new int[1];
        GLFW.glfwGetMonitorWorkarea(monitor, areaX, areaY, areaWidth, areaHeight);
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetWindowSize(handle, width, height);
        int[] left = new int[1];
        int[] top = new int[1];
        int[] right = new int[1];
        int[] bottom = new int[1];
        GLFW.glfwGetWindowFrameSize(handle, left, top, right, bottom);
        int outerWidth = width[0] + left[0] + right[0];
        int outerHeight = height[0] + top[0] + bottom[0];
        int x = areaX[0] + Math.max(0, (areaWidth[0] - outerWidth) / 2) + left[0];
        int y = areaY[0] + Math.max(0, (areaHeight[0] - outerHeight) / 2) + top[0];
        GLFW.glfwSetWindowPos(handle, x, y);
        setWindowedPosition(window, x, y);
        LOGGER.info(String.format(Locale.ROOT, PREFIX + "moved to monitor '%s' (work area %dx%d at %d,%d): window %dx%d"
                        + " at %d,%d", GLFW.glfwGetMonitorName(monitor), areaWidth[0], areaHeight[0], areaX[0],
                areaY[0], width[0], height[0], x, y));
    }

    /** {@code Window#windowedX}/{@code windowedY}, which {@link Window#setWindowed} moves the window back to. */
    private static void setWindowedPosition(Window window, int x, int y) {
        try {
            Field windowedX = Window.class.getDeclaredField("windowedX");
            Field windowedY = Window.class.getDeclaredField("windowedY");
            windowedX.setAccessible(true);
            windowedY.setAccessible(true);
            windowedX.setInt(window, x);
            windowedY.setInt(window, y);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn(PREFIX + "could not record the windowed position ({}); a resize may move the window back", e
                    .toString());
        }
    }
}
