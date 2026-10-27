package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenuLayout;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.WarehouseCrafterFeederBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Scenario "patterns" (issue #35, ADR-068): the production station's pattern tab strip at the counts where its shape
 * changes, driven with real mouse input.
 * <p>
 * Four stations stand in a row, each placed while {@code maxProductionPatterns} was 9, 12, 18 and 36 (the config is
 * read when the block entity is built) and each with every slot filled. The run, every claim asserted on the open
 * screen before the shot that shows it:
 * <ol>
 *   <li>9, 12 and 18 patterns: 3, 4 and 6 columns, every tab visible, no scroll bar ({@code patterns-<n>});</li>
 *   <li>36 patterns: 6 x 3 visible tabs and a scroll bar ({@code patterns-36-top}); one wheel notch over the strip
 *       scrolls one row ({@code patterns-36-row1}), five more stop at the last row ({@code patterns-36-bottom}); a
 *       click on tab 36 selects it and its grid, the tooltip names "Pattern 36" ({@code patterns-36-selected});</li>
 *   <li>the wheel scrolls the selected tab out of view (the grid keeps showing it), a hidden tab fails its hit test,
 *       the scroll bar carries a gold tick at its row ({@code patterns-36-hidden}), and a selection made elsewhere ({@code WarehouseProductionScreen#select}) scrolls it back
 *       ({@code patterns-36-reselected});</li>
 *   <li>a Warehouse Crafter Feeder behind the 36-pattern station refuses exactly one pattern, in row 6: while that row
 *       is hidden, the scroll bar carries its red tick and no other ({@code patterns-36-top}); at the bottom its tab
 *       has the red bar and the bar no tick;</li>
 *   <li>the window at vanilla's smallest screen, 320 x 240 (1280 x 960 at GUI scale 4): a window resize re-opens the
 *       screen's layout and the selected tab is scrolled into view again ({@code patterns-36-floor});</li>
 *   <li>the tab's tooltip in German ({@code patterns-36-de}), compared against the English text; the language is
 *       switched with the screen closed, as a player does it in the options.</li>
 * </ol>
 * The second pass (Flywheel off) only re-shoots {@code patterns-36-top}. Grep {@code patterns: ALL CHECKS PASSED}.
 */
public final class PatternStripVisualScenario implements VisualScenario {
    public static final String NAME = "patterns";

    private static final String WORLD_FOLDER = "wareworks_visual_patterns";

    /** The pattern counts the stations are built with: where the column rule changes, and the cap. */
    private static final int[] COUNTS = {9, 12, 18, ProductionPatterns.MAX_SLOTS};
    private static final int FULL = COUNTS.length - 1;
    /** Blocks between two stations of the row. */
    private static final int SPACING = 3;
    /** The station front faces the camera, the feeder sits on its back. */
    private static final Direction FRONT = Direction.SOUTH;
    /** The one pattern of the 36-pattern station the feeder refuses: row 6 (index 5), second column. */
    private static final int REFUSED_SLOT = 31;
    private static final int LAST_SLOT = ProductionPatterns.MAX_SLOTS - 1;

    /**
     * One-cell crafting recipes with distinct results, for the 36-pattern station the feeder checks. Each entry is the
     * ingredient, the result and the result count; {@link #REFUSED_SLOT} gets "dirt makes a diamond" instead.
     */
    private static final Object[][] RECIPES = {
            {Items.OAK_LOG, Items.OAK_PLANKS, 4}, {Items.SPRUCE_LOG, Items.SPRUCE_PLANKS, 4},
            {Items.BIRCH_LOG, Items.BIRCH_PLANKS, 4}, {Items.JUNGLE_LOG, Items.JUNGLE_PLANKS, 4},
            {Items.ACACIA_LOG, Items.ACACIA_PLANKS, 4}, {Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS, 4},
            {Items.MANGROVE_LOG, Items.MANGROVE_PLANKS, 4}, {Items.CHERRY_LOG, Items.CHERRY_PLANKS, 4},
            {Items.CRIMSON_STEM, Items.CRIMSON_PLANKS, 4}, {Items.WARPED_STEM, Items.WARPED_PLANKS, 4},
            {Items.BAMBOO_BLOCK, Items.BAMBOO_PLANKS, 2}, {Items.IRON_BLOCK, Items.IRON_INGOT, 9},
            {Items.GOLD_BLOCK, Items.GOLD_INGOT, 9}, {Items.DIAMOND_BLOCK, Items.DIAMOND, 9},
            {Items.EMERALD_BLOCK, Items.EMERALD, 9}, {Items.LAPIS_BLOCK, Items.LAPIS_LAZULI, 9},
            {Items.REDSTONE_BLOCK, Items.REDSTONE, 9}, {Items.COAL_BLOCK, Items.COAL, 9},
            {Items.COPPER_BLOCK, Items.COPPER_INGOT, 9}, {Items.NETHERITE_BLOCK, Items.NETHERITE_INGOT, 9},
            {Items.SLIME_BLOCK, Items.SLIME_BALL, 9}, {Items.HAY_BLOCK, Items.WHEAT, 9},
            {Items.DRIED_KELP_BLOCK, Items.DRIED_KELP, 9}, {Items.RAW_IRON_BLOCK, Items.RAW_IRON, 9},
            {Items.RAW_GOLD_BLOCK, Items.RAW_GOLD, 9}, {Items.RAW_COPPER_BLOCK, Items.RAW_COPPER, 9},
            {Items.BONE, Items.BONE_MEAL, 3}, {Items.BLAZE_ROD, Items.BLAZE_POWDER, 2},
            {Items.SUGAR_CANE, Items.SUGAR, 1}, {Items.DANDELION, Items.YELLOW_DYE, 1},
            {Items.CORNFLOWER, Items.BLUE_DYE, 1}, {Items.DIRT, Items.DIAMOND, 1},
            {Items.LILY_OF_THE_VALLEY, Items.WHITE_DYE, 1}, {Items.WITHER_ROSE, Items.BLACK_DYE, 1},
            {Items.BLUE_ORCHID, Items.LIGHT_BLUE_DYE, 1}, {Items.ALLIUM, Items.MAGENTA_DYE, 1}};

    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int FLOOR_GUI_SCALE = 4;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 5;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int LANGUAGE_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 6;
    private static final long RUN_TIMEOUT_MILLIS = 6L * 60L * 1000L;

    private final VisualLanguage language = new VisualLanguage(LANGUAGE_TIMEOUT_TICKS, SETTLE_TICKS);
    private volatile String englishTab = "";
    private int windowWidth;
    private int windowHeight;
    private int guiScaleOption;

    @Override
    public String name() {
        return NAME;
    }

    /** A real player with a creative inventory: a container screen needs a player within reach of the station. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("patterns: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "patterns run"))
                .server("patterns: build four stations at 9, 12, 18 and 36 patterns", PatternStripVisualScenario::build)
                .serverUntil("patterns: wait until the feeder refuses exactly one pattern",
                        PatternStripVisualScenario::checksReady, SCREEN_TIMEOUT_TICKS)
                .server("patterns: lift the player into the air", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    BlockPos above = context.origin().above(4);
                    player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 2.5,
                            player.getYRot(), player.getXRot());
                })
                .waitTicks(SETTLE_TICKS)
                .server("patterns: the player flies with an empty hand", PatternStripVisualScenario::equipPlayer);
        GoggleShots.reach(script, NAME, 0.0);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass != VisualPass.FLYWHEEL) {
            open(script, FULL);
            script.until("patterns: the strip is at its top", context -> screen(context).tabScroll() == 0,
                    SCREEN_TIMEOUT_TICKS);
            shot(script, "patterns-36-top");
            close(script);
            return;
        }
        for (int index = 0; index < FULL; index++) {
            int count = COUNTS[index];
            open(script, index);
            script.client("patterns: CHECK " + count + " patterns show every tab without a scroll bar",
                    context -> checkUnscrolled(context, count));
            shot(script, "patterns-" + count);
            close(script);
        }
        scrollChapter(script);
        floorChapter(script);
        germanChapter(script);
        script.client("patterns: every check passed",
                context -> LOGGER.info(PREFIX + "patterns: ALL CHECKS PASSED (3/4/6 columns at 9/12/18, the strip "
                        + "scrolls by rows above 18, a hidden tab is not hit, a selection scrolls its tab into view, "
                        + "the hidden red row is ticked on the bar, so is the hidden selected row, 320x240 floor, German tooltip)"));
    }

    @Override
    public void finish(VisualScript script) {
        script.server("patterns: put the config back", (server, context) -> setPatterns(
                WareworksConfig.SERVER.maxProductionPatterns.getDefault()));
    }

    @Override
    public String status(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return String.format(Locale.ROOT, "screen=station slots=%d pattern=%d tabScroll=%d gui=%dx%d",
                    screen.getMenu().patternSlots(), screen.selectedPattern(), screen.tabScroll(),
                    context.minecraft().getWindow().getGuiScaledWidth(),
                    context.minecraft().getWindow().getGuiScaledHeight());
        return context.minecraft().screen == null ? "screen=none"
                : "screen=" + context.minecraft().screen.getClass().getSimpleName();
    }

    // --- the 36-pattern station: wheel, click, hidden tab, selection -------------------------------------------------

    private void scrollChapter(VisualScript script) {
        open(script, FULL);
        script.client("patterns: CHECK 36 patterns: 6 columns, 3 of 6 rows, the hidden red row", context -> {
            WarehouseProductionScreen screen = screen(context);
            ProductionMenuLayout layout = screen.getMenu().layout();
            require(layout.tabColumns() == 6 && layout.tabRows() == 6 && layout.visibleTabRows() == 3
                    && layout.scrolls() && screen.tabScroll() == 0, "the 36-pattern strip", screen);
            require(screen.isRefused(REFUSED_SLOT), "pattern " + (REFUSED_SLOT + 1) + " is drawn red", screen);
            for (int slot = 0; slot < ProductionPatterns.MAX_SLOTS; slot++)
                require(slot == REFUSED_SLOT || !screen.isRefused(slot), "only one pattern is red, not "
                        + (slot + 1), screen);
            require(!layout.tabVisible(REFUSED_SLOT, screen.tabScroll()), "the red tab is hidden at the top", screen);
            int redRow = layout.tabRow(REFUSED_SLOT);
            require(screen.hiddenRedRows().equals(List.of(redRow)), "the bar ticks red row " + (redRow + 1)
                    + " only, not " + screen.hiddenRedRows(), screen);
            require(screen.hiddenSelectedRow() == -1, "no selection tick while the selected tab is visible", screen);
            LOGGER.info(PREFIX + "patterns: CHECK PASS 6 x 3 visible tabs, pattern {} red and hidden, the bar ticks "
                    + "row {} red and nothing else", REFUSED_SLOT + 1, redRow + 1);
        });
        shot(script, "patterns-36-top");
        wheel(script, -1.0, 1);
        shot(script, "patterns-36-row1");
        for (int notch = 0; notch < 5; notch++)
            wheel(script, -1.0, Math.min(3, 2 + notch));
        script.client("patterns: CHECK the strip stops at its last row", context -> {
            WarehouseProductionScreen screen = screen(context);
            require(screen.tabScroll() == screen.getMenu().layout().maxTabScroll(), "scroll 3 after six notches",
                    screen);
            require(screen.hiddenRedRows().isEmpty(), "no red tick once the red row is in view, not "
                    + screen.hiddenRedRows(), screen);
            ScreenInput.productionTab(screen, LAST_SLOT);
            ScreenInput.productionTab(screen, REFUSED_SLOT);
            LOGGER.info(PREFIX + "patterns: CHECK PASS six notches stop at row 4 of 6; tabs 32 and 36 are hit");
        });
        shot(script, "patterns-36-bottom");
        script.client("patterns: click tab 36", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.productionTab(screen(context), LAST_SLOT)))
                .until("patterns: tab 36 is selected", context -> screen(context).selectedPattern() == LAST_SLOT,
                        SCREEN_TIMEOUT_TICKS)
                .client("patterns: rest the cursor on tab 36", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.productionTab(screen(context), LAST_SLOT)))
                .waitTicks(SETTLE_TICKS)
                .client("patterns: CHECK the grid and the tooltip of pattern 36", context -> {
                    WarehouseProductionScreen screen = screen(context);
                    ItemKey result = ItemKey.of((Item) RECIPES[LAST_SLOT][1]);
                    require(screen.state().result(LAST_SLOT).map(view -> view.key().equals(result)).orElse(false),
                            "the grid shows pattern 36's result", screen);
                    englishTab = tabTooltip(context, LAST_SLOT);
                    LOGGER.info(PREFIX + "patterns: CHECK PASS tab 36 selected, its tooltip reads '{}'", englishTab);
                })
                .shot("patterns-36-selected");
        script.client("patterns: park the mouse", PatternStripVisualScenario::parkMouse);
        wheel(script, 1.0, 2);
        wheel(script, 1.0, 1);
        wheel(script, 1.0, 0);
        script.client("patterns: CHECK the wheel hid the selected tab and a hidden tab is not hit", context -> {
            WarehouseProductionScreen screen = screen(context);
            require(screen.selectedPattern() == LAST_SLOT, "the selection stays on pattern 36", screen);
            require(!screen.getMenu().layout().tabVisible(LAST_SLOT, screen.tabScroll()), "tab 36 is hidden",
                    screen);
            int lastRow = screen.getMenu().layout().tabRow(LAST_SLOT);
            require(screen.hiddenSelectedRow() == lastRow, "the bar ticks the hidden selected row " + (lastRow + 1)
                    + ", not " + screen.hiddenSelectedRow(), screen);
            try {
                ScreenInput.productionTab(screen, LAST_SLOT);
            } catch (VisualTestException expected) {
                LOGGER.info(PREFIX + "patterns: CHECK PASS the hidden tab 36 fails its hit test: {}; the bar ticks "
                        + "its row {} gold", expected.getMessage(), lastRow + 1);
                return;
            }
            throw new VisualTestException("tab 36 is hit although it is scrolled out of view");
        });
        shot(script, "patterns-36-hidden");
        script.client("patterns: select pattern 36 from elsewhere", context -> screen(context).select(LAST_SLOT))
                .until("patterns: the selected tab is scrolled back into view",
                        context -> screen(context).tabScroll() == 3, SCREEN_TIMEOUT_TICKS)
                .client("patterns: CHECK a selection shows its tab", context -> {
                    ScreenInput.productionTab(screen(context), LAST_SLOT);
                    LOGGER.info(PREFIX + "patterns: CHECK PASS a selection made elsewhere scrolled tab 36 into view");
                });
        shot(script, "patterns-36-reselected");
    }

    // --- the smallest screen -----------------------------------------------------------------------------------------

    /**
     * Scrolled to the top with pattern 36 still selected, the window shrinks to the 320 x 240 floor: the screen is
     * initialised again for the new size and must bring the selected tab back into view.
     */
    private void floorChapter(VisualScript script) {
        wheel(script, 1.0, 2);
        wheel(script, 1.0, 1);
        wheel(script, 1.0, 0);
        script.client("patterns: remember the window and the GUI scale", this::rememberWindow);
        resize(script, FLOOR_WIDTH, FLOOR_HEIGHT, FLOOR_GUI_SCALE);
        script.client("patterns: CHECK the window at 320x240", context -> {
            WarehouseProductionScreen screen = screen(context);
            Window window = context.minecraft().getWindow();
            require(window.getGuiScaledWidth() == 320 && window.getGuiScaledHeight() == 240,
                    "the GUI is 320x240", screen);
            require(screen.getGuiLeft() >= 0 && screen.getGuiLeft() + screen.getXSize() <= 320
                    && screen.getGuiTop() >= 0 && screen.getGuiTop() + screen.getYSize() <= 240,
                    "the window fits the screen", screen);
            require(screen.tabScroll() == 3 && screen.selectedPattern() == LAST_SLOT,
                    "the resize scrolled the selected tab into view", screen);
            ScreenInput.productionTab(screen, LAST_SLOT);
            LOGGER.info(PREFIX + "patterns: CHECK PASS at 320x240 the window spans [{}, {}) x [{}, {}) and tab 36 "
                    + "is in view", screen.getGuiLeft(), screen.getGuiLeft() + screen.getXSize(), screen.getGuiTop(),
                    screen.getGuiTop() + screen.getYSize());
        });
        shot(script, "patterns-36-floor");
        script.client("patterns: put the window back",
                context -> context.minecraft().getWindow().setWindowed(windowWidth, windowHeight));
        ArmVisualScenario.untilOrFail(script, "patterns: the window is back",
                context -> TooltipsVisualScenario.synced(context)
                        && context.minecraft().getWindow().getScreenWidth() == windowWidth
                        && context.minecraft().getWindow().getScreenHeight() == windowHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("patterns: put the GUI scale back", context -> {
            context.minecraft().options.guiScale().set(guiScaleOption);
            context.minecraft().resizeDisplay();
        }).waitTicks(SETTLE_TICKS);
    }

    private void rememberWindow(VisualContext context) {
        Window window = context.minecraft().getWindow();
        windowWidth = window.getScreenWidth();
        windowHeight = window.getScreenHeight();
        guiScaleOption = context.minecraft().options.guiScale().get();
        LOGGER.info(PREFIX + "patterns: the window is {}x{} (scale option {})", windowWidth, windowHeight,
                guiScaleOption);
    }

    /** {@code Window#setWindowed}, then the GUI scale, in that order (see {@code CraftVisualScenario#resize}). */
    private static void resize(VisualScript script, int width, int height, int scale) {
        script.client("patterns: make the window " + width + "x" + height,
                context -> context.minecraft().getWindow().setWindowed(width, height));
        ArmVisualScenario.untilOrFail(script, "patterns: the framebuffer is " + width + "x" + height,
                context -> TooltipsVisualScenario.synced(context)
                        && TooltipsVisualScenario.framebufferIs(context.minecraft().getWindow(), width, height),
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("patterns: GUI scale " + scale, context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.options.guiScale().set(scale);
            minecraft.resizeDisplay();
            if (minecraft.getWindow().getGuiScale() != scale)
                throw new VisualTestException("the GUI scale is " + minecraft.getWindow().getGuiScale() + ", not "
                        + scale + ", at " + describeWindow(minecraft.getWindow()));
        }).waitTicks(SETTLE_TICKS);
    }

    private static String describeWindow(Window window) {
        return String.format(Locale.ROOT, "window %dx%d, framebuffer %dx%d, GUI %dx%d at scale %.0f",
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale());
    }

    // --- German ------------------------------------------------------------------------------------------------------

    /**
     * The language is switched with no screen open: a resource reload under an open screen lets the screen draw text
     * while the font atlas is being rebuilt, which vanilla does not survive. So the screen is opened again in German
     * and scrolled to the last row with the wheel.
     */
    private void germanChapter(VisualScript script) {
        close(script);
        language.switchTo(script, "patterns: ", "de_de");
        open(script, FULL);
        wheel(script, -1.0, 1);
        wheel(script, -1.0, 2);
        wheel(script, -1.0, 3);
        script.client("patterns: rest the cursor on tab 36", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.productionTab(screen(context), LAST_SLOT)))
                .waitTicks(SETTLE_TICKS)
                .client("patterns: CHECK the German tooltip of tab 36", context -> {
                    String german = tabTooltip(context, LAST_SLOT);
                    if (german.isBlank() || german.equals(englishTab))
                        throw new VisualTestException("'" + german + "' is no German translation of '" + englishTab
                                + "'");
                    LOGGER.info(PREFIX + "patterns: CHECK PASS tab 36 reads '{}' in German", german);
                })
                .shot("patterns-36-de");
        close(script);
        language.switchTo(script, "patterns: ", "en_us");
    }

    // --- steps -------------------------------------------------------------------------------------------------------

    /** The camera in front of station {@code index}, then its screen, opened by the server as a click on it would. */
    private static void open(VisualScript script, int index) {
        int count = COUNTS[index];
        double x = index * SPACING + 0.5;
        script.camera(CameraView.of("station-" + count, x, 1.6, 2.6, x, 0.5, 0.5))
                .server("patterns: open the screen of the " + count + "-pattern station", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    if (!station(server.overworld(), context.origin(), index).openScreen(player))
                        throw new VisualTestException("the screen of the " + count + "-pattern station did not open");
                })
                .until("patterns: the " + count + "-pattern screen shows its patterns",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                                && screen.getMenu().patternSlots() == count
                                && screen.state().patterns().size() == count, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    private static void close(VisualScript script) {
        script.client("patterns: close the screen", context -> {
            LocalPlayer player = context.minecraft().player;
            if (player != null)
                player.closeContainer();
        }).until("patterns: the screen is closed", context -> context.minecraft().screen == null,
                SCREEN_TIMEOUT_TICKS);
    }

    /** One notch of the mouse wheel over the tab strip, then a wait until the strip stands at {@code expected}. */
    private static void wheel(VisualScript script, double notches, int expected) {
        script.client("patterns: one wheel notch " + (notches < 0 ? "down" : "up") + " over the tab strip",
                        context -> ScreenInput.scroll(context.minecraft(),
                                ScreenInput.productionTabStrip(screen(context)), notches))
                .until("patterns: the strip stands at row " + expected,
                        context -> screen(context).tabScroll() == expected, SCREEN_TIMEOUT_TICKS);
    }

    private static void shot(VisualScript script, String label) {
        script.client("patterns: park the mouse", PatternStripVisualScenario::parkMouse)
                .waitTicks(SETTLE_TICKS)
                .shot(label);
    }

    /** The cursor in the window's title row: on no tab, cell, slot or line. */
    private static void parkMouse(VisualContext context) {
        WarehouseProductionScreen screen = screen(context);
        ScreenInput.hover(context.minecraft(), new ScreenInput.Point(screen.getGuiLeft() + ProductionMenuLayout.WIDTH
                - 40.0, screen.getGuiTop() + 4.0));
    }

    // --- checks ------------------------------------------------------------------------------------------------------

    private static void checkUnscrolled(VisualContext context, int count) {
        WarehouseProductionScreen screen = screen(context);
        ProductionMenuLayout layout = screen.getMenu().layout();
        int columns = count <= 9 ? 3 : (count + 2) / 3;
        require(layout.tabColumns() == columns && !layout.scrolls() && screen.tabScroll() == 0,
                count + " patterns in " + columns + " columns without scrolling", screen);
        for (int slot = 0; slot < count; slot++)
            ScreenInput.productionTab(screen, slot);
        ScreenInput.scroll(context.minecraft(), ScreenInput.productionTabStrip(screen), -1.0);
        require(screen.tabScroll() == 0, "the wheel does not move a strip that shows every tab", screen);
        LOGGER.info(PREFIX + "patterns: CHECK PASS {} patterns: {} columns, every tab hit, nothing scrolls", count,
                columns);
    }

    /** The first line of the tooltip of tab {@code slot}, which names the slot. */
    private static String tabTooltip(VisualContext context, int slot) {
        WarehouseProductionScreen screen = screen(context);
        List<Component> tooltip = ScreenInput.productionTooltip(screen, ScreenInput.productionTab(screen, slot));
        String expected = WareworksLang.translateDirect(WareworksLang.PRODUCTION_TAB,
                Component.literal(Integer.toString(slot + 1))).getString();
        if (tooltip.isEmpty() || !tooltip.getFirst().getString().equals(expected))
            throw new VisualTestException("the tooltip of tab " + (slot + 1) + " is " + tooltip + ", not '" + expected
                    + "'");
        return expected;
    }

    private static void require(boolean condition, String what, WarehouseProductionScreen screen) {
        if (!condition)
            throw new VisualTestException(what + " failed (slots " + screen.getMenu().patternSlots() + ", selected "
                    + screen.selectedPattern() + ", tab scroll " + screen.tabScroll() + ")");
    }

    private static WarehouseProductionScreen screen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseProductionScreen screen)
            return screen;
        throw new VisualTestException("the production screen is not open (screen: " + context.minecraft().screen + ")");
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private static void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        if (!level.isLoaded(origin))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        context.setOrigin(origin);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                origin.offset(FULL * SPACING + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        List<ItemKey> items = BuiltInRegistries.ITEM.stream()
                .filter(item -> item != Items.AIR)
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).getPath()))
                .map(ItemKey::of)
                .toList();
        try {
            for (int index = 0; index < COUNTS.length; index++) {
                // The config is read when the block entity is built, so each station is placed under its own count.
                setPatterns(COUNTS[index]);
                BlockPos pos = stationPos(origin, index);
                level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                        .setValue(WarehouseProductionBlock.FACING, FRONT));
                WarehouseProductionBlockEntity station = station(level, origin, index);
                if (station.patterns().size() != COUNTS[index])
                    throw new VisualTestException("the station holds " + station.patterns().size() + " slots, not "
                            + COUNTS[index]);
                if (index == FULL)
                    fillRecipes(station);
                else
                    fillVaried(station, items, index);
            }
        } finally {
            setPatterns(WareworksConfig.SERVER.maxProductionPatterns.getDefault());
        }
        // The feeder on the back of the 36-pattern station; it checks the patterns whether or not it turns.
        level.setBlockAndUpdate(stationPos(origin, FULL).relative(FRONT.getOpposite()),
                WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                        .setValue(WarehouseCrafterFeederBlock.FACING, Direction.EAST)
                        .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        LOGGER.info(PREFIX + "patterns: four stations with {} patterns built", java.util.Arrays.toString(COUNTS));
    }

    /** Slot {@code s} gets {@code s % 9 + 1} cells of different items and a result of its own. */
    private static void fillVaried(WarehouseProductionBlockEntity station, List<ItemKey> items, int index) {
        int stride = items.size() / 400 + 1;
        for (int slot = 0; slot < station.patterns().size(); slot++) {
            int base = (index * 100 + slot * 10) * stride;
            for (int cell = 0; cell <= slot % ProductionPatterns.GRID_CELLS; cell++)
                set(station, slot, cell, items.get((base + (cell + 1) * stride) % items.size()), 1 + cell % 3);
            set(station, slot, ProductionPatterns.RESULT_ENTRY, items.get(base % items.size()), 1);
        }
    }

    /** One crafting recipe per slot, so the feeder passes all of them but {@link #REFUSED_SLOT}. */
    private static void fillRecipes(WarehouseProductionBlockEntity station) {
        for (int slot = 0; slot < RECIPES.length; slot++) {
            set(station, slot, 0, ItemKey.of((Item) RECIPES[slot][0]), 1);
            set(station, slot, ProductionPatterns.RESULT_ENTRY, ItemKey.of((Item) RECIPES[slot][1]),
                    (Integer) RECIPES[slot][2]);
        }
    }

    private static void set(WarehouseProductionBlockEntity station, int slot, int entry, ItemKey key, int count) {
        if (!station.setPatternEntry(slot, entry, key, count))
            throw new VisualTestException("slot " + slot + " entry " + entry + " refused " + key);
    }

    /** The feeder's check of the 36-pattern station: exactly {@link #REFUSED_SLOT} refused, every other one valid. */
    private static boolean checksReady(MinecraftServer server, VisualContext context) {
        WarehouseProductionBlockEntity station = station(server.overworld(), context.origin(), FULL);
        if (station.attachedFeeder().isEmpty())
            return false;
        List<String> wrong = new ArrayList<>();
        for (int slot = 0; slot < ProductionPatterns.MAX_SLOTS; slot++) {
            CrafterPatternCheck check = station.crafterCheck(slot).check();
            CrafterPatternCheck expected = slot == REFUSED_SLOT ? CrafterPatternCheck.NOT_A_RECIPE
                    : CrafterPatternCheck.VALID;
            if (check != expected)
                wrong.add((slot + 1) + "=" + check);
        }
        if (!wrong.isEmpty())
            throw new VisualTestException("the feeder's checks are not as built: " + wrong);
        LOGGER.info(PREFIX + "patterns: CHECK PASS the feeder passes 35 recipes and refuses pattern {}",
                REFUSED_SLOT + 1);
        return true;
    }

    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.inventoryMenu.broadcastChanges();
    }

    private static void setPatterns(int count) {
        ModConfigSpec.IntValue value = WareworksConfig.SERVER.maxProductionPatterns;
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so overriding it would be a silent no-op");
        value.set(count);
        value.clearCache();
    }

    private static BlockPos stationPos(BlockPos origin, int index) {
        return origin.offset(index * SPACING, 0, 0);
    }

    private static WarehouseProductionBlockEntity station(ServerLevel level, BlockPos origin, int index) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                stationPos(origin, index));
        if (station == null)
            throw new VisualTestException("no production station at " + stationPos(origin, index));
        return station;
    }
}
