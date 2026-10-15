package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.compat.jei.JeiRuntimeAccess;
import dev.wareworks.client.gui.TerminalCraftPanel;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalCraftOrderState;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.network.TerminalCraftAnswerPayload;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Scenario "jei": JEI's "+" on a crafting recipe lays the recipe into the warehouse terminal's crafting panel (issue
 * #24, slice 4, ADR-063, {@code docs/warehouse-system.md} §3.4.5), with the real JEI in the client and a real click on
 * its button. It needs JEI at runtime — {@code ./gradlew runVisualTest -Pwareworks.visualTest=jei -Pwareworks.jei}
 * (the version the mod compiles against) or {@code -Pwareworks.jei=<version>} — and <b>fails at once</b> without it,
 * so a run that forgot the property cannot pass by testing nothing.
 * <p>
 * It builds a short powered aisle with a warehouse terminal beside the dock and racks holding three kinds of planks —
 * far more spruce than oak or birch — and the fire charge's gunpowder, blaze powder, coal and charcoal, more charcoal
 * than coal. It opens the real terminal screen through {@code WarehouseTerminalBlockEntity#openScreen} and plays one
 * <b>round</b> ({@link #round}) in English and one in German:
 * <ol>
 * <li>JEI's recipe screen is opened on the Chest ({@link JeiRuntimeAccess#showRecipesMaking}, JEI's "show recipes"
 * with the terminal open), and its one "+" must be shown and usable ({@code jei-recipe}). A real click on it closes
 * JEI's screen, the terminal comes back already folded out, and its grid is laid with the planks the <b>stock</b>
 * prefers: spruce, although {@code #planks} names oak first. The server previews the Chest, and the answer rings the
 * success bell once ({@code jei-laid}).</li>
 * <li>The fire charge, a <b>shapeless</b> recipe, the same way: JEI draws its three ingredients in the cells 0, 1 and
 * 3, and the coal-or-charcoal cell is laid with charcoal, which the racks hold more of
 * ({@code jei-shapeless-recipe}, {@code jei-shapeless-laid}).</li>
 * <li>A Fetch for one fire charge opens a craft order, which locks the grid. With the ticks frozen while it runs,
 * JEI's "+" on the Chest must be greyed out — visible, not usable — and a click on it must lay nothing; the shot
 * {@code jei-locked} hovers it, so JEI draws the reason the transfer handler gives. Then the ticks run again with
 * JEI's screen still open: the terminal it covers must hear that the fetch is done and see the stock the crane took,
 * and the "+" must turn usable without JEI closing. Escape goes back to the terminal.</li>
 * <li>Eight birch planks — one chest's worth — are requested into the terminal's buffer by a click on the stock list,
 * and the Chest is laid once more: now with birch, because what the <b>buffer</b> holds comes first
 * ({@code jei-laid-buffer}).</li>
 * </ol>
 * <p>
 * <b>Nothing moves while laying.</b> A {@link SceneItemCensus} of the aisle and the player brackets every lay and
 * every crane trip, and after every lay the terminal has no open request: laying only gives the panel a grid. The only
 * declared change is the emptying of the buffer between the two rounds ({@link #emptyForNextRound}).
 * <p>
 * <b>Exactly one lay per click, none per look.</b> JEI asks the transfer handler on every tick a recipe is on screen;
 * only a click may lay. Every time JEI's recipe screen opens, the scenario keeps it open for a few ticks and checks
 * that neither the server's menu nor the covered terminal saw a lay, and after every click on "+" that the server took
 * exactly one.
 * <p>
 * <b>Where JEI's button is.</b> JEI's "+" is not one of its screen's widgets, and the scenario names no JEI type, so it
 * finds the button the way {@link JeiScreens} explains: it reads JEI's recipe screen by reflection, recognises the
 * button by the simple names of JEI's own classes, and checks the point it clicks with JEI's own hit test of that
 * button and with the screen's. Everything else it asks of JEI goes through {@link JeiRuntimeAccess}, JEI's public API
 * behind methods that name no JEI type. With the terminal on screen it also checks that JEI keeps its ingredient list
 * right of the folded-out panel: JEI's own idea of the window must end where the panel ends.
 * <p>
 * The second render pass opens JEI's recipe screen once more and shoots it, since a GUI does not depend on Flywheel.
 */
public final class JeiVisualScenario implements VisualScenario {
    public static final String NAME = "jei";
    /** JEI's mod id, as its {@code neoforge.mods.toml} declares it. */
    private static final String JEI_MOD_ID = "jei";
    /** Two rounds with crane trips and JEI restarting after each language switch take longer than the default. */
    private static final long RUN_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 4;
    private static final int MOTOR_RPM = 128;
    /** The terminal sits beside the dock, on the right rack side, as in the craft scenario. */
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 2;
    private static final int STORAGE_LEVELS = 2;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    private static final ItemKey SPRUCE = ItemKey.of(Items.SPRUCE_PLANKS);
    private static final ItemKey OAK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey BIRCH = ItemKey.of(Items.BIRCH_PLANKS);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    private static final ItemKey GUNPOWDER = ItemKey.of(Items.GUNPOWDER);
    private static final ItemKey BLAZE_POWDER = ItemKey.of(Items.BLAZE_POWDER);
    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey CHARCOAL = ItemKey.of(Items.CHARCOAL);
    private static final ItemKey FIRE_CHARGE = ItemKey.of(Items.FIRE_CHARGE);

    /**
     * One item type per rack position. Spruce is a full chest of planks and oak and birch a stack each, so the stock
     * prefers spruce although {@code #planks} lists oak first; there is far more charcoal than coal, so the fire charge
     * takes charcoal although its recipe lists coal first. The rest only makes the list look like a store.
     */
    private static final List<ItemStack> STOCK = List.of(new ItemStack(Items.SPRUCE_PLANKS, 64),
            new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.BIRCH_PLANKS, 32),
            new ItemStack(Items.GUNPOWDER, 16), new ItemStack(Items.BLAZE_POWDER, 16),
            new ItemStack(Items.CHARCOAL, 32), new ItemStack(Items.COAL, 4), new ItemStack(Items.OAK_LOG, 32),
            new ItemStack(Items.GLASS, 48), new ItemStack(Items.STRING, 16), new ItemStack(Items.REDSTONE, 64),
            new ItemStack(Items.LEATHER, 8));
    private static final int ITEM_TYPES = 12;

    /** JEI draws a shapeless recipe of three ingredients in the upper left 2 x 2 of its grid, in the recipe's order. */
    private static final CraftGrid<ItemKey> FIRE_CHARGE_GRID = CraftGrid.of(GUNPOWDER, BLAZE_POWDER, null, CHARCOAL);
    /** One chest's worth of planks: what the buffer must hold for the buffer to decide. */
    private static final int PLANKS_PER_CHEST = 8;
    /** The fire charges the Fetch that locks the grid is for. */
    private static final int FIRE_CHARGES = 1;

    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int JEI_TIMEOUT_TICKS = 600;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int LAY_TIMEOUT_TICKS = 200;
    private static final int ANSWER_TIMEOUT_TICKS = 200;
    private static final int AMOUNT_TIMEOUT_TICKS = 200;
    private static final int DELIVERY_TIMEOUT_TICKS = 1200;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    /** Client ticks after the click on the greyed-out "+" in which a lay, had one been sent, would have arrived. */
    private static final int NO_LAY_TICKS = 10;
    /** Where the mouse is parked on the terminal for a shot without a tooltip: its title row. */
    private static final int TITLE_CORNER = 5;
    /** Where the mouse is parked on JEI's recipe screen: the screen's corner, on none of JEI's overlays. */
    private static final ScreenInput.Point JEI_CORNER = new ScreenInput.Point(1.0, 1.0);
    /** The success bell and the refusal's bass the terminal plays for a craft answer, as the harness counts them. */
    private static final String BELL = SoundEvents.NOTE_BLOCK_BELL.value().getLocation().toString();
    private static final String BASS = SoundEvents.NOTE_BLOCK_BASS.value().getLocation().toString();
    /** What the Fetch that locks the grid takes out of the racks: the fire charge's ingredients. */
    private static final List<ItemKey> FIRE_CHARGE_INGREDIENTS = List.of(GUNPOWDER, BLAZE_POWDER, CHARCOAL);

    /** In the aisle, two blocks from the terminal, looking at its screen; inside the vanilla container range. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.1, 1.6, 0.5, 1.4, 0.9, 1.2);

    /** The aisle the census counts, around the whole scene; set by the build. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Server thread: what the census of the aisle and the player has to be right now. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Server thread: what the terminal's buffer has to hold right now, and what it is to hold after a delivery. */
    private volatile Map<ItemKey, Long> buffer = Map.of();
    private volatile Map<ItemKey, Long> bufferTarget = Map.of();
    /** Client thread: the terminal screen JEI's recipe screen was opened over, which it must come back to. */
    @Nullable
    private WarehouseTerminalScreen terminalUnderJei;
    /**
     * Client thread: the last lay the covered panel had seen, the answer it showed and whether it was folded out when
     * JEI's recipe screen was last opened over it — what JEI's every-tick check must leave alone, and what a click on
     * "+" changes by exactly one lay.
     */
    private int adoptedBefore;
    @Nullable
    private TerminalCraftAnswerPayload answerBefore;
    private boolean panelOpenBefore;
    /** Client thread: the bells and basses heard before the last click on "+". */
    private int bellsBefore;
    private int bassesBefore;
    /** Client thread: the terminal screen as JEI handed it back after the last click on "+", recorded at once. */
    @Nullable
    private Returned returned;
    /** Client thread: the Fetch answer on screen before the last click on Fetch. */
    @Nullable
    private TerminalCraftAnswerPayload fetchAnswerBefore;
    /** Server thread: the menu's lay number when JEI's recipe screen was last opened. */
    private volatile int serverLayBefore;
    /** Client thread: the covered stock list's totals of the fire charge's ingredients while the ticks were frozen. */
    private Map<ItemKey, Long> stockAtFreeze = Map.of();
    /** Server thread: the warehouse's totals of them once the fetch is done. */
    private volatile Map<ItemKey, Long> stockAfterFetch = Map.of();
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Fails before anything else when JEI is not in the game: a run without it would test nothing at all. The shots of
     * an earlier run are deleted first, so the failed run does not leave another scenario's shots beside its index.
     */
    @Override
    public void prepare(VisualScript script) {
        script.client("jei: delete screenshots of an earlier run", VisualContext::cleanScreenshots);
        script.client("jei: CHECK JEI is loaded", context -> {
            if (!ModList.get().isLoaded(JEI_MOD_ID))
                throw new VisualTestException("the jei scenario needs JEI in the game, and it is not loaded: run it "
                        + "with -Pwareworks.jei (or -Pwareworks.jei=<version>)");
            LOGGER.info(PREFIX + "jei: CHECK PASS JEI {} is loaded", ModList.get().getModContainerById(JEI_MOD_ID)
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse("?"));
        });
        VisualScenario.super.prepare(script);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("jei: arm the watchdog for the whole run",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "jei run"))
                .server("jei: clear the area and place the creative motor", this::placeMotor)
                .server("jei: build the aisle, the terminal and the stocked racks", JeiVisualScenario::buildAisle)
                .serverUntil("jei: wait until the controller has indexed every rack", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("jei: the census every lay is measured against", (server, context) -> {
                    expected = SceneItemCensus.take(server.overworld(), censusBox, context.serverPlayer(server));
                    LOGGER.info(PREFIX + "jei: baseline census {}", SceneItemCensus.describe(expected));
                });
        ArmVisualScenario.untilOrFail(script, "jei: JEI has started in the world", context -> JeiRuntimeAccess
                .available(), JEI_TIMEOUT_TICKS, context -> "JEI handed no runtime to the plugin");
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("jei: open the terminal screen", JeiVisualScenario::openScreen)
                .until("jei: wait for the screen with its stock", JeiVisualScenario::screenReady, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
        if (pass == VisualPass.FLYWHEEL) {
            round(script, "");
            emptyForNextRound(script);
            language.switchTo(script, "jei: ", GERMAN);
            round(script, "-de");
            language.switchTo(script, "jei: ", ENGLISH);
            script.client("jei: every check passed",
                    context -> LOGGER.info(PREFIX + "jei: ALL CHECKS PASSED in English and German"));
        } else {
            // A GUI does not depend on Flywheel: the second pass only shows JEI's recipe screen over the terminal once
            // more, with its "+" usable, and goes back.
            openJei(script, "the Chest", Items.CHEST, true);
            jeiShot(script, "jei-recipe");
            closeJei(script);
        }
        script.client("jei: close the screen", JeiVisualScenario::closeScreen)
                .until("jei: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        Window window = minecraft.getWindow();
        String size = String.format(Locale.ROOT, "gui=%dx%d scale=%.0f lang=%s", window.getGuiScaledWidth(),
                window.getGuiScaledHeight(), window.getGuiScale(), minecraft.getLanguageManager().getSelected());
        Screen open = minecraft.screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return String.format(Locale.ROOT, "screen=terminal %s left=%d width=%d lay=%d %s status='%s' %s",
                    terminal.isCraftPanelOpen() ? "panel=open" : "panel=closed", terminal.getGuiLeft(),
                    terminal.getXSize(), terminal.craftPanel().adoptedLay(),
                    CraftVisualScenario.describePanel(terminal),
                    terminal.feedbackLine().map(Component::getString).orElse(""), size);
        if (open != null && JeiRuntimeAccess.isRecipeScreen(open)) {
            List<JeiScreens.TransferButton> buttons = JeiScreens.transferButtons(open);
            return "screen=jei-recipes plus=" + buttons.stream().map(JeiScreens.TransferButton::describe).toList()
                    + " " + size;
        }
        return "screen=" + (open == null ? "none" : open.getClass().getSimpleName()) + " " + size;
    }

    // --- one round ---------------------------------------------------------------------------------------------------

    /** Every lay of the scenario once, with a shot of every moment worth seeing; {@code suffix} ends every label. */
    private void round(VisualScript script, String suffix) {
        script.client("jei: CHECK the round starts with the panel folded in and an empty grid", context -> {
            WarehouseTerminalScreen screen = screen(context);
            if (screen.isCraftPanelOpen() || !screen.craftPanel().grid().isEmpty())
                throw new VisualTestException("the round does not start folded in and empty: "
                        + CraftVisualScenario.describePanel(screen));
        });

        // The stock decides: far more spruce than oak, although the tag names oak first.
        openJei(script, "the Chest", Items.CHEST, true);
        jeiShot(script, "jei-recipe" + suffix);
        clickPlus(script, "the Chest");
        laid(script, "the Chest from the stock", chest(SPRUCE), Items.CHEST);
        terminalShot(script, "jei-laid" + suffix);

        // A shapeless recipe: JEI's cells 0, 1 and 3, and the charcoal the racks hold more of than coal.
        openJei(script, "the Fire Charge", Items.FIRE_CHARGE, true);
        jeiShot(script, "jei-shapeless-recipe" + suffix);
        clickPlus(script, "the Fire Charge");
        laid(script, "the shapeless Fire Charge", FIRE_CHARGE_GRID, Items.FIRE_CHARGE);
        terminalShot(script, "jei-shapeless-laid" + suffix);

        lockedSteps(script, suffix);

        // The buffer decides: one chest's worth of birch delivered beats the racks' spruce.
        requestIntoBuffer(script, BIRCH, PLANKS_PER_CHEST);
        openJei(script, "the Chest", Items.CHEST, true);
        clickPlus(script, "the Chest");
        laid(script, "the Chest from the buffer", chest(BIRCH), Items.CHEST);
        terminalShot(script, "jei-laid-buffer" + suffix);
    }

    /**
     * A Fetch for one fire charge opens a craft order, which locks the grid. With the ticks frozen while it runs, JEI's
     * "+" on the Chest is visible and greyed out, a click on it lays nothing, and the shot hovers it so JEI draws the
     * transfer handler's reason. Then the ticks run again with JEI's screen still open, and the fetch finishes behind
     * it: the terminal it covers hears that the order is done and gets the stock the crane took, so its stock list
     * shows the warehouse's new totals and JEI's "+" turns usable without JEI closing. Escape goes back to the
     * terminal: the buffer holds what the fetch was for, the census has not moved and the grid is free again, still
     * the fire charge.
     */
    private void lockedSteps(VisualScript script, String suffix) {
        amount(script, FIRE_CHARGES);
        script.server("jei: census before the Fetch", (server, context) -> assertCensus(server, context,
                        "before the Fetch"))
                .client("jei: click Fetch for one fire charge", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    if (screen.craftOrderAction() != TerminalCraftOrderPayload.Action.FETCH
                            || screen.craftPanel().shownResult().isEmpty())
                        throw new VisualTestException("Fetch cannot start now: "
                                + CraftVisualScenario.describePanel(screen));
                    fetchAnswerBefore = screen.craftFetchAnswer();
                    ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftFetch(screen));
                });
        ArmVisualScenario.untilOrFail(script, "jei: the server answered the Fetch",
                context -> screen(context).craftFetchAnswer() != fetchAnswerBefore, ANSWER_TIMEOUT_TICKS,
                context -> CraftVisualScenario.describePanel(screen(context)));
        script.client("jei: CHECK the Fetch started the craft order", context -> {
            TerminalCraftAnswerPayload answer = screen(context).craftFetchAnswer();
            if (answer == null || answer.result() != TerminalCraftResult.STARTED || answer.crafted() != FIRE_CHARGES)
                throw new VisualTestException("the Fetch was answered " + answer + ", not STARTED x" + FIRE_CHARGES);
        });
        ArmVisualScenario.untilOrFail(script, "jei: the screen shows the craft order", context -> {
            TerminalCraftOrderState order = screen(context).craftOrderState();
            return order.isOpen() && order.crafts() == FIRE_CHARGES && order.cells().equals(FIRE_CHARGE_GRID)
                    && screen(context).isCraftGridLocked();
        }, SCREEN_TIMEOUT_TICKS, context -> CraftVisualScenario.describePanel(screen(context)));
        script.freeze(true)
                .server("jei: CHECK the craft order still runs with the ticks frozen", (server, context) -> {
                    TerminalCraftOrderState order = terminal(server.overworld(), context.origin()).craftOrderState();
                    if (!order.isOpen())
                        throw new VisualTestException("the fetch ended (" + order.state() + ") before the ticks "
                                + "were frozen");
                });

        openJei(script, "the Chest while the fetch runs", Items.CHEST, false);
        script.client("jei: click the greyed-out +", context -> ScreenInput.click(context.minecraft(),
                        onlyPlus(context).hitTested(context.minecraft().screen, false)))
                .waitTicks(NO_LAY_TICKS)
                .client("jei: CHECK the click on the greyed-out + laid nothing", context -> {
                    if (!JeiRuntimeAccess.isRecipeScreen(context.minecraft().screen))
                        throw new VisualTestException("the click on the greyed-out + closed JEI's recipe screen");
                    if (screen(terminalUnderJei).craftPanel().adoptedLay() != adoptedBefore)
                        throw new VisualTestException("the click on the greyed-out + laid a recipe");
                })
                .server("jei: CHECK the server got no lay", (server, context) -> {
                    int number = serverMenu(server, context).craftLay().number();
                    if (number != serverLayBefore)
                        throw new VisualTestException("the server took lay " + number + " from the greyed-out +");
                    LOGGER.info(PREFIX + "jei: CHECK PASS the greyed-out + laid nothing (lay {} on the server)",
                            number);
                })
                .client("jei: hover the greyed-out +", context -> ScreenInput.hover(context.minecraft(),
                        onlyPlus(context).hitTested(context.minecraft().screen, false)))
                .waitTicks(SETTLE_TICKS)
                .shot("jei-locked" + suffix)
                .client("jei: CHECK the covered terminal still shows the locked fire charge", context -> {
                    WarehouseTerminalScreen covered = screen(terminalUnderJei);
                    if (!covered.isCraftGridLocked() || !covered.craftPanel().grid().equals(FIRE_CHARGE_GRID))
                        throw new VisualTestException("under JEI the panel is "
                                + CraftVisualScenario.describePanel(covered));
                    stockAtFreeze = stockTotals(covered);
                    ScreenInput.hover(context.minecraft(), JEI_CORNER);
                })
                .freeze(false);

        // The fetch finishes behind JEI's screen, which stays open: the covered terminal must still get its updates.
        ArmVisualScenario.untilOrFail(script, "jei: the covered terminal hears that the fetch is done", context -> {
            requireRecipeScreen(context, "while the fetch ran");
            TerminalCraftOrderState order = screen(terminalUnderJei).craftOrderState();
            return order.active() && order.state() == ListOrderState.DONE;
        }, DELIVERY_TIMEOUT_TICKS, context -> CraftVisualScenario.describePanel(screen(terminalUnderJei)));
        expectBuffer(script, "the fire charge's ingredients", now -> SceneItemCensus.plus(SceneItemCensus.plus(
                SceneItemCensus.plus(now, GUNPOWDER, FIRE_CHARGES), BLAZE_POWDER, FIRE_CHARGES), CHARCOAL,
                FIRE_CHARGES));
        script.server("jei: the warehouse's stock of the fire charge's ingredients after the fetch",
                (server, context) -> stockAfterFetch = serverStockTotals(serverMenu(server, context)));
        ArmVisualScenario.untilOrFail(script, "jei: the covered stock list shows what the fetch left", context -> {
            requireRecipeScreen(context, "while the stock was pushed");
            return stockTotals(screen(terminalUnderJei)).equals(stockAfterFetch);
        }, SCREEN_TIMEOUT_TICKS, context -> "the covered stock list shows " + stockTotals(screen(terminalUnderJei))
                + ", the warehouse holds " + stockAfterFetch);
        ArmVisualScenario.untilOrFail(script, "jei: JEI's + on the Chest turns usable without JEI closing", context -> {
            requireRecipeScreen(context, "before its + turned usable");
            JeiScreens.TransferButton plus = onlyPlus(context);
            return plus.button().visible && plus.button().active;
        }, SCREEN_TIMEOUT_TICKS, context -> "the screen is " + describeScreen(context.minecraft().screen));
        script.client("jei: CHECK the covered terminal got the fetch's end and its stock behind JEI", context -> {
            requireRecipeScreen(context, "before the check");
            WarehouseTerminalScreen covered = screen(terminalUnderJei);
            if (covered.isCraftGridLocked() || !covered.craftPanel().grid().equals(FIRE_CHARGE_GRID))
                throw new VisualTestException("behind JEI the panel is " + CraftVisualScenario.describePanel(covered));
            if (stockAtFreeze.equals(stockAfterFetch))
                throw new VisualTestException("the crane took the fire charge's ingredients before the ticks were "
                        + "frozen, so the stock behind JEI proves nothing: " + stockAtFreeze);
            LOGGER.info(PREFIX + "jei: CHECK PASS behind JEI's screen the terminal heard the fetch end, its stock list "
                    + "went from {} to {} and JEI's + turned usable", stockAtFreeze, stockAfterFetch);
        });
        closeJei(script);
        script.client("jei: CHECK the finished fetch gave the grid back", context -> {
            WarehouseTerminalScreen screen = screen(context);
            if (screen.isCraftGridLocked() || !screen.craftPanel().grid().equals(FIRE_CHARGE_GRID))
                throw new VisualTestException("after the fetch the panel is "
                        + CraftVisualScenario.describePanel(screen));
        });
    }

    /**
     * {@code count} of {@code key} into the terminal's buffer, the way a player asks for anything: the amount field,
     * then a left click on the item in the stock list. The crane carries them inside the census box, so the census does
     * not move.
     */
    private void requestIntoBuffer(VisualScript script, ItemKey key, int count) {
        amount(script, count);
        script.server("jei: census before the request for " + id(key), (server, context) -> assertCensus(server,
                        context, "before the request for " + id(key)))
                .client("jei: click " + id(key) + " in the stock list", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    if (screen.craftPanel().hasGhost())
                        throw new VisualTestException("a ghost is on the cursor, so the click would pick, not request");
                    ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(screen, stockCell(context, key)));
                    if (screen.isAskingSomething())
                        throw new VisualTestException("the request for " + id(key) + " raised a question");
                });
        expectBuffer(script, count + " " + id(key), now -> SceneItemCensus.plus(now, key, count));
    }

    // --- JEI's recipe screen -----------------------------------------------------------------------------------------

    /**
     * Opens JEI's recipe screen on the recipes that make {@code output}, over the terminal screen, and waits until it
     * shows exactly one "+" in the state {@code usable} names: usable, or greyed out because a craft order locks the
     * grid. The "+" must belong to a crafting recipe making {@code output}. Then JEI's screen stays open for a few
     * ticks, in which JEI asks the transfer handler on every tick, and neither the server's menu nor the covered
     * terminal may have seen a lay.
     */
    private void openJei(VisualScript script, String what, Item output, boolean usable) {
        ArmVisualScenario.untilOrFail(script, "jei: JEI is running", context -> JeiRuntimeAccess.available(),
                JEI_TIMEOUT_TICKS, context -> "JEI has no runtime");
        script.server("jei: the menu's lay before JEI shows " + what, (server, context) ->
                        serverLayBefore = serverMenu(server, context).craftLay().number())
                .client("jei: show JEI's recipes for " + what + " over the terminal", context -> {
            terminalUnderJei = screen(context);
            adoptedBefore = terminalUnderJei.craftPanel().adoptedLay();
            answerBefore = terminalUnderJei.craftAnswer();
            panelOpenBefore = terminalUnderJei.isCraftPanelOpen();
            if (!JeiRuntimeAccess.showRecipesMaking(new ItemStack(output)))
                throw new VisualTestException("JEI did not take the request to show the recipes for " + what);
        });
        ArmVisualScenario.untilOrFail(script, "jei: JEI shows " + what + " with its + " + (usable ? "usable"
                : "greyed out"), context -> {
                    Screen open = context.minecraft().screen;
                    if (!JeiRuntimeAccess.isRecipeScreen(open))
                        return false;
                    List<JeiScreens.TransferButton> buttons = JeiScreens.transferButtons(open);
                    return buttons.size() == 1 && buttons.getFirst().button().visible
                            && buttons.getFirst().button().active == usable;
                }, SCREEN_TIMEOUT_TICKS, context -> "the screen is " + describeScreen(context.minecraft().screen));
        script.client("jei: CHECK JEI's recipe screen for " + what, context -> {
            Screen open = context.minecraft().screen;
            Optional<Screen> parent;
            try {
                parent = JeiRuntimeAccess.recipeScreenParent();
            } catch (NoSuchMethodError e) {
                // IRecipesGui#getParentScreen is younger than some 1.21.1 JEIs this run is pointed at
                // (-Pwareworks.jei=<version>); without it only this one check is skipped, the "+" is still clicked.
                parent = null;
                LOGGER.info(PREFIX + "jei: this JEI has no IRecipesGui#getParentScreen; the parent check is skipped");
            }
            if (parent != null && parent.orElse(null) != terminalUnderJei)
                throw new VisualTestException("JEI's recipe screen does not go back to the terminal screen but to "
                        + parent);
            JeiScreens.TransferButton plus = onlyPlus(context);
            ResourceLocation recipe = BuiltInRegistries.ITEM.getKey(output);
            if (!plus.recipe().equals(Optional.of(recipe)) || !plus.category().equals(Optional.of(
                    ResourceLocation.withDefaultNamespace("crafting"))))
                throw new VisualTestException("the + belongs to " + plus.describe() + ", not to the crafting recipe "
                        + recipe);
            ScreenInput.Point centre = plus.hitTested(open, usable);
            LOGGER.info(PREFIX + "jei: CHECK PASS JEI shows {} with its + {} at {}: {}", what,
                    usable ? "usable" : "greyed out", centre, plus.describe());
        });
        script.waitTicks(NO_LAY_TICKS)
                .client("jei: CHECK JEI's every-tick check laid nothing while it showed " + what, context -> {
                    requireRecipeScreen(context, "while it showed " + what);
                    WarehouseTerminalScreen covered = screen(terminalUnderJei);
                    if (covered.craftPanel().adoptedLay() != adoptedBefore || covered.craftAnswer() != answerBefore
                            || covered.isCraftPanelOpen() != panelOpenBefore)
                        throw new VisualTestException("JEI's every-tick check (doTransfer false) laid a recipe: "
                                + CraftVisualScenario.describePanel(covered));
                })
                .server("jei: CHECK the server got no lay while JEI showed " + what, (server, context) -> {
                    int number = serverMenu(server, context).craftLay().number();
                    if (number != serverLayBefore)
                        throw new VisualTestException("the server took lays up to " + number + " while JEI only "
                                + "showed " + what + " (was " + serverLayBefore + ")");
                    LOGGER.info(PREFIX + "jei: CHECK PASS JEI's every-tick check laid nothing in {} ticks on {} "
                            + "(lay {} on the server)", NO_LAY_TICKS, what, number);
                });
    }

    /**
     * A real click on the one usable "+", after the census. JEI hands the terminal screen back inside the click
     * ({@code Minecraft#setScreen}); how it came back is recorded at the first moment it is on top again, before the
     * server's answer can have arrived, and it must already be folded out: the lay marks the fold at the click.
     */
    private void clickPlus(VisualScript script, String what) {
        script.server("jei: census before the + on " + what, (server, context) -> assertCensus(server, context,
                        "before the + on " + what))
                .client("jei: click the + on " + what, context -> {
                    WarehouseTerminalScreen terminal = screen(terminalUnderJei);
                    bellsBefore = context.soundCount(BELL);
                    bassesBefore = context.soundCount(BASS);
                    returned = null;
                    ScreenInput.click(context.minecraft(), onlyPlus(context).hitTested(context.minecraft().screen,
                            true));
                    if (context.minecraft().screen == terminal)
                        returned = Returned.of(terminal, true);
                });
        ArmVisualScenario.untilOrFail(script, "jei: JEI hands the terminal back after the + on " + what, context -> {
            if (context.minecraft().screen != terminalUnderJei)
                return false;
            if (returned == null)
                returned = Returned.of(terminalUnderJei, false);
            return true;
        }, SCREEN_TIMEOUT_TICKS, context -> "the screen is " + describeScreen(context.minecraft().screen));
        script.client("jei: CHECK the terminal came back from the + on " + what + " already folded out", context -> {
            Returned back = returned;
            if (back == null || !back.foldedOut())
                throw new VisualTestException("the terminal came back from JEI's + on " + what + " folded in ("
                        + back + "): it would be laid out again when the grid arrives");
            LOGGER.info(PREFIX + "jei: CHECK PASS the terminal came back from the + on {} folded out, {} px wide at "
                    + "leftPos {}, {}, the lay {}", what, back.width(), back.left(), back.inClick() ? "inside the click"
                    : "a tick later", back.adoptedLay() == adoptedBefore ? "not arrived yet" : "already there");
        });
    }

    /** The terminal screen as JEI handed it back: folded out, how wide, where, which lay it had, and when. */
    private record Returned(boolean foldedOut, int width, int left, int adoptedLay, boolean inClick) {
        static Returned of(WarehouseTerminalScreen screen, boolean inClick) {
            return new Returned(screen.isCraftPanelOpen() && screen.getXSize() == TerminalMenuLayout.FOLDED_WIDTH,
                    screen.getXSize(), screen.getGuiLeft(), screen.craftPanel().adoptedLay(), inClick);
        }
    }

    /** Fails unless JEI's recipe screen is the screen on top. */
    private static void requireRecipeScreen(VisualContext context, String when) {
        Screen open = context.minecraft().screen;
        if (!JeiRuntimeAccess.isRecipeScreen(open))
            throw new VisualTestException("JEI's recipe screen closed " + when + ": the screen is "
                    + describeScreen(open));
    }

    /**
     * After the click on "+": JEI's screen is gone, the terminal screen it was opened over is back, the panel has
     * taken the server's lay — exactly one, on both sides — and is folded out, the server answered LAID about
     * {@code result} with one success bell, previews it, and the grid holds exactly {@code grid}. JEI knows the window
     * is folded out, so its list stays right of the panel. The census has not moved and nothing is requested.
     */
    private void laid(VisualScript script, String what, CraftGrid<ItemKey> grid, Item result) {
        ArmVisualScenario.untilOrFail(script, "jei: the terminal shows " + what + " laid", context -> {
            if (!(context.minecraft().screen instanceof WarehouseTerminalScreen screen))
                return false;
            TerminalCraftAnswerPayload answer = screen.craftAnswer();
            return screen.craftPanel().adoptedLay() > adoptedBefore && answer != answerBefore && answer != null
                    && CraftVisualScenario.previewShows(context, grid, result);
        }, LAY_TIMEOUT_TICKS, context -> "the screen is " + describeScreen(context.minecraft().screen));
        script.client("jei: CHECK " + what, context -> checkLaid(context, what, grid, result))
                .server("jei: CHECK nothing moved and nothing is requested after " + what, (server, context) -> {
                    assertCensus(server, context, "after " + what);
                    checkNothingRequested(server, context, what);
                    int number = serverMenu(server, context).craftLay().number();
                    if (number != serverLayBefore + 1)
                        throw new VisualTestException("the click on + made the server take "
                                + (number - serverLayBefore) + " lays for " + what + ", not one");
                    if (!bufferIs(server, context, buffer))
                        throw new VisualTestException("the buffer changed while laying " + what + ": "
                                + terminal(server.overworld(), context.origin()).bufferedItems());
                });
    }

    private void checkLaid(VisualContext context, String what, CraftGrid<ItemKey> grid, Item result) {
        WarehouseTerminalScreen screen = screen(context);
        if (screen != terminalUnderJei)
            throw new VisualTestException("JEI went back to another screen than the terminal it was opened over");
        TerminalCraftPanel panel = screen.craftPanel();
        TerminalCraftViewPayload.Lay lay = panel.view().lay();
        ResourceLocation recipe = BuiltInRegistries.ITEM.getKey(result);
        if (lay.number() != panel.adoptedLay() || !lay.cells().equals(grid) || !lay.recipe().equals(Optional.of(recipe))
                || !lay.foldOut())
            throw new VisualTestException("the server laid " + lay + " for " + what + ", not " + grid);
        if (!panel.view().recipe().equals(Optional.of(recipe)))
            throw new VisualTestException("the server previewed the recipe " + panel.view().recipe() + " for " + what);
        TerminalCraftAnswerPayload answer = screen.craftAnswer();
        ItemKey made = ItemKey.of(result);
        if (answer == null || answer.result() != TerminalCraftResult.LAID || answer.crafted() != 0
                || !answer.about().equals(Optional.of(made)))
            throw new VisualTestException("the server answered " + answer + " to " + what + ", not LAID");
        if (panel.adoptedLay() != adoptedBefore + 1)
            throw new VisualTestException("the panel took " + (panel.adoptedLay() - adoptedBefore) + " lays for " + what
                    + ", not one");
        int bells = context.soundCount(BELL) - bellsBefore;
        int basses = context.soundCount(BASS) - bassesBefore;
        if (bells != 1 || basses != 0)
            throw new VisualTestException("the LAID answer to " + what + " played " + bells + " bells and " + basses
                    + " basses, not the one success bell; heard " + context.soundCounts());
        String wanted = WarehouseTerminalScreen.craftAnswerText(TerminalCraftResult.LAID, 0,
                made.toStack().getHoverName(), Component.empty()).getString();
        String shown = screen.feedbackLine().map(Component::getString).orElse("");
        if (!shown.equals(wanted) || !screen.statusTextsFit())
            throw new VisualTestException("the status row says '" + shown + "', not '" + wanted + "' inside the row");
        if (!screen.isCraftPanelOpen() || screen.getXSize() != TerminalMenuLayout.FOLDED_WIDTH)
            throw new VisualTestException("the panel is not folded out after " + what + ": " + screen.getXSize()
                    + " px wide");
        // JEI places its ingredient list right of the window it believes in; that window has to include the panel.
        Rect2i window = JeiRuntimeAccess.windowArea(screen)
                .orElseThrow(() -> new VisualTestException("JEI has no idea of the terminal's window"));
        if (window.getX() != screen.getGuiLeft() || window.getY() != screen.getGuiTop()
                || window.getWidth() != TerminalMenuLayout.FOLDED_WIDTH || window.getHeight() != screen.getYSize())
            throw new VisualTestException("JEI believes the window is " + describe(window) + ", the screen is at "
                    + screen.getGuiLeft() + "," + screen.getGuiTop() + " " + screen.getXSize() + "x"
                    + screen.getYSize());
        List<Rect2i> panelArea = screen.getExtraAreas();
        List<Rect2i> excluded = JeiRuntimeAccess.exclusionAreas(screen);
        // The panel reaches JEI as an exclusion area through Create's own JEI plugin (SlotMover), which does not load
        // under a JEI older than the one Create is built against; the window checked above still covers the panel.
        boolean createPluginMissing = excluded.isEmpty() && jeiOlderThanCreatesPin();
        if (createPluginMissing)
            LOGGER.info(PREFIX + "jei: JEI {} reports no exclusion areas (Create's JEI plugin did not load); the window"
                    + " JEI believes in covers the panel", jeiVersion());
        else if (panelArea.size() != 1 || excluded.stream().noneMatch(area -> same(area, panelArea.getFirst())))
            throw new VisualTestException("JEI keeps clear of " + excluded.stream().map(JeiVisualScenario::describe)
                    .toList() + ", not of the panel " + panelArea.stream().map(JeiVisualScenario::describe).toList());
        LOGGER.info(PREFIX + "jei: CHECK PASS {}: lay {} cells {}, the status row says '{}' with {} bell, JEI's "
                + "window {} ends at {}", what, lay.number(), CraftVisualScenario.describePanel(screen), shown, bells,
                describe(window), window.getX() + window.getWidth());
    }

    /** Escape closes JEI's recipe screen, which goes back to the terminal screen it was opened over. */
    private void closeJei(VisualScript script) {
        script.client("jei: press Escape on JEI's recipe screen", context -> {
            if (!JeiRuntimeAccess.isRecipeScreen(context.minecraft().screen))
                throw new VisualTestException("JEI's recipe screen is not open: " + context.minecraft().screen);
            ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE);
        });
        ArmVisualScenario.untilOrFail(script, "jei: the terminal screen is back",
                context -> context.minecraft().screen != null && context.minecraft().screen == terminalUnderJei,
                SCREEN_TIMEOUT_TICKS, context -> "the screen is " + describeScreen(context.minecraft().screen));
    }

    /** The one "+" on JEI's recipe screen; fails when there is none or more than one. */
    private static JeiScreens.TransferButton onlyPlus(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (!JeiRuntimeAccess.isRecipeScreen(open))
            throw new VisualTestException("JEI's recipe screen is not open: " + describeScreen(open));
        List<JeiScreens.TransferButton> buttons = JeiScreens.transferButtons(open);
        if (buttons.size() != 1)
            throw new VisualTestException("JEI's recipe screen shows " + buttons.size() + " + buttons: "
                    + buttons.stream().map(JeiScreens.TransferButton::describe).toList());
        return buttons.getFirst();
    }

    // --- shots and gestures (client thread) --------------------------------------------------------------------------

    /** A shot of JEI's recipe screen with the mouse on none of its parts. */
    private static void jeiShot(VisualScript script, String label) {
        script.client("jei: park the mouse in the corner of JEI's screen",
                        context -> ScreenInput.hover(context.minecraft(), JEI_CORNER))
                .waitTicks(SETTLE_TICKS)
                .shot(label);
    }

    /** A shot of the terminal with the mouse in its title row. */
    private static void terminalShot(VisualScript script, String label) {
        script.client("jei: park the mouse in the terminal's title row", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    ScreenInput.hover(context.minecraft(), new ScreenInput.Point(screen.getGuiLeft() + TITLE_CORNER,
                            screen.getGuiTop() + TITLE_CORNER));
                })
                .waitTicks(SETTLE_TICKS)
                .shot(label);
    }

    /** The amount field to {@code amount}, one notch of the wheel per tick, as in the craft scenario. */
    private static void amount(VisualScript script, int amount) {
        ArmVisualScenario.untilOrFail(script, "jei: scroll the amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(screen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, AMOUNT_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
    }

    /**
     * Puts the screen back where the first round found it: the grid cleared and the panel folded in by real clicks,
     * and the buffer emptied on the server thread, declared in the census.
     */
    private void emptyForNextRound(VisualScript script) {
        script.client("jei: click the Clear button", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalCraftClear(screen(context))))
                .until("jei: the grid is empty", context -> screen(context).craftPanel().grid().isEmpty(),
                        SETTLE_TICKS)
                .client("jei: click the crafting toggle to fold the panel in", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.terminalCraftToggle(screen(context))))
                .until("jei: the panel is folded in", context -> !screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .server("jei: empty the buffer for the next round (declared in the census)", this::emptyBuffer)
                .until("jei: the screen shows the empty buffer", JeiVisualScenario::clientBufferEmpty,
                        SCREEN_TIMEOUT_TICKS);
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    // --- server thread -----------------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-CLEAR_MARGIN, -1, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        motor.generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()), WareworksBlocks.WAREHOUSE_CONTROLLER
                .getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));
        int next = 0;
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                for (int y = 0; y < STORAGE_LEVELS; y++) {
                    BlockPos rack = layout.rackPos(RackPosition.of(x, y, side));
                    BlockPos chest = rack.relative(outward);
                    level.setBlockAndUpdate(chest,
                            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                    level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                            .setValue(WarehouseInterfaceBlock.FACING, outward));
                    if (next < STOCK.size())
                        CraftVisualScenario.fillChest(level, chest, STOCK.get(next++));
                }
            }
        }
        if (next < STOCK.size())
            throw new VisualTestException("only " + next + " of " + STOCK.size() + " item types fit into the racks");
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null || !SceneItemCensus.isFullyLoaded(level, censusBox))
            return false;
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == 1 && crane.isControllerLinked()
                && controller.stockIndex().distinctKeys() >= ITEM_TYPES;
    }

    private static void openScreen(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        if (!terminal.openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
    }

    /**
     * Waits until the buffer holds what it held plus the delivery {@code delivered} adds to it, and nothing is left
     * requested; then the census must not have moved, since the crane carries only inside the census box.
     */
    private void expectBuffer(VisualScript script, String what, UnaryOperator<Map<ItemKey, Long>> delivered) {
        script.server("jei: what the buffer holds after the delivery of " + what,
                        (server, context) -> bufferTarget = Map.copyOf(delivered.apply(buffer)))
                .serverUntil("jei: wait until the crane delivered " + what, (server, context) -> {
                    WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
                    return bufferIs(server, context, bufferTarget)
                            && controller(server, context).requestsFor(terminal.getBlockPos()).isEmpty();
                }, DELIVERY_TIMEOUT_TICKS)
                .server("jei: CHECK the census after the delivery of " + what, (server, context) -> {
                    buffer = bufferTarget;
                    assertCensus(server, context, "after the delivery of " + what);
                    LOGGER.info(PREFIX + "jei: CHECK PASS the buffer holds {}", SceneItemCensus.describe(buffer));
                });
    }

    private void checkNothingRequested(MinecraftServer server, VisualContext context, String what) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        BlockPos terminal = layout(context.origin()).rackPos(TERMINAL);
        if (!controller.requestsFor(terminal).isEmpty())
            throw new VisualTestException(what + " requested something: " + controller.requestsFor(terminal));
        if (!controller.openProductionOrders().isEmpty())
            throw new VisualTestException(what + " ordered something: " + controller.openProductionOrders());
    }

    /** Whether the buffer holds exactly {@code counts} and nothing else. */
    private static boolean bufferIs(MinecraftServer server, VisualContext context, Map<ItemKey, Long> counts) {
        InventorySnapshot<ItemKey> buffered = terminal(server.overworld(), context.origin()).bufferedItems();
        if (!buffered.keys().equals(counts.keySet()))
            return false;
        for (Map.Entry<ItemKey, Long> entry : counts.entrySet())
            if (buffered.count(entry.getKey()) != entry.getValue())
                return false;
        return true;
    }

    /** Takes everything out of the buffer; declared in the census. */
    private void emptyBuffer(MinecraftServer server, VisualContext context) {
        Map<ItemKey, Long> next = expected;
        IItemHandler handler = terminal(server.overworld(), context.origin()).menuBuffer();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            for (ItemStack taken = handler.extractItem(slot, Item.ABSOLUTE_MAX_STACK_SIZE, false); !taken.isEmpty();
                    taken = handler.extractItem(slot, Item.ABSOLUTE_MAX_STACK_SIZE, false))
                next = SceneItemCensus.plus(next, ItemKey.of(taken), -taken.getCount());
        }
        expected = next;
        buffer = Map.of();
        assertCensus(server, context, "the scenario emptied the buffer for the next round");
    }

    private void assertCensus(MinecraftServer server, VisualContext context, String step) {
        SceneItemCensus.assertEquals(server.overworld(), censusBox, expected, "jei: " + step,
                context.serverPlayer(server));
    }

    private static WarehouseTerminalMenu serverMenu(MinecraftServer server, VisualContext context) {
        if (!(context.serverPlayer(server).containerMenu instanceof WarehouseTerminalMenu menu))
            throw new VisualTestException("the player has no terminal menu open on the server");
        return menu;
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseControllerBlockEntity controller(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), context.origin().relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    // --- client helpers ----------------------------------------------------------------------------------------------

    private static boolean screenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && terminal.matchingEntries().size() >= ITEM_TYPES;
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + describeScreen(open) + ")");
    }

    /** The terminal screen JEI was opened over, which must still be the player's open menu's. */
    private static WarehouseTerminalScreen screen(@Nullable WarehouseTerminalScreen covered) {
        if (covered == null)
            throw new VisualTestException("JEI was not opened over a terminal screen");
        WarehouseTerminalScreen found = WarehouseTerminalScreen.forMenu(covered.getMenu().containerId).orElse(null);
        if (found != covered)
            throw new VisualTestException("the terminal screen under JEI is no longer the player's open screen");
        return covered;
    }

    private static boolean clientBufferEmpty(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        for (int slot = 0; slot < screen.getMenu().bufferSlots(); slot++)
            if (!screen.getMenu().slots.get(slot).getItem().isEmpty())
                return false;
        return true;
    }

    /** The visible stock cell showing {@code key}. */
    private static int stockCell(VisualContext context, ItemKey key) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++)
            if (visible.get(cell).key().equals(key))
                return cell;
        throw new VisualTestException("the stock list does not show " + id(key));
    }

    /** The totals the stock list of {@code screen} shows for the fire charge's ingredients (0 for one it lacks). */
    private static Map<ItemKey, Long> stockTotals(WarehouseTerminalScreen screen) {
        Map<ItemKey, Long> totals = new LinkedHashMap<>();
        for (ItemKey key : FIRE_CHARGE_INGREDIENTS)
            totals.put(key, 0L);
        for (StockLine<ItemKey> line : screen.matchingEntries())
            if (totals.containsKey(line.key()))
                totals.put(line.key(), line.total());
        return totals;
    }

    /** The totals the server's menu would push for the fire charge's ingredients (0 for one it lacks). */
    private static Map<ItemKey, Long> serverStockTotals(WarehouseTerminalMenu menu) {
        Map<ItemKey, Long> totals = new LinkedHashMap<>();
        for (ItemKey key : FIRE_CHARGE_INGREDIENTS)
            totals.put(key, 0L);
        for (StockCount<ItemKey> count : menu.stockCounts())
            if (totals.containsKey(count.key()))
                totals.put(count.key(), count.total());
        return Map.copyOf(totals);
    }

    /** The chest's ring of eight planks of one kind. */
    private static CraftGrid<ItemKey> chest(ItemKey planks) {
        return CraftGrid.of(planks, planks, planks, planks, null, planks, planks, planks, planks);
    }

    private static String describeScreen(@Nullable Screen screen) {
        if (screen == null)
            return "none";
        if (screen instanceof WarehouseTerminalScreen terminal)
            return "the terminal: " + CraftVisualScenario.describePanel(terminal);
        if (JeiRuntimeAccess.isRecipeScreen(screen))
            return "JEI's recipe screen with " + JeiScreens.transferButtons(screen).stream()
                    .map(JeiScreens.TransferButton::describe).toList();
        return screen.getClass().getName();
    }

    private static String describe(Rect2i area) {
        return String.format(Locale.ROOT, "[%d,%d %dx%d]", area.getX(), area.getY(), area.getWidth(),
                area.getHeight());
    }

    /** The loaded JEI's version, as NeoForge read it from JEI's mods.toml. */
    private static String jeiVersion() {
        return ModList.get().getModContainerById("jei").map(container -> container.getModInfo().getVersion()
                .toString()).orElse("none");
    }

    /** Whether the loaded JEI is older than 19.21.0.247, the JEI Create 6.0.10 is built against. */
    private static boolean jeiOlderThanCreatesPin() {
        return ModList.get().getModContainerById("jei").map(container -> container.getModInfo().getVersion()
                .compareTo(new DefaultArtifactVersion("19.21.0.247")) < 0).orElse(false);
    }

    private static boolean same(Rect2i one, Rect2i other) {
        return one.getX() == other.getX() && one.getY() == other.getY() && one.getWidth() == other.getWidth()
                && one.getHeight() == other.getHeight();
    }

    private static String id(ItemKey key) {
        return BuiltInRegistries.ITEM.getKey(key.getItem()).getPath();
    }

    /**
     * JEI's "+" on its recipe screen, found without naming a JEI type.
     * <p>
     * The button is not one of the screen's widgets: JEI draws it as part of a recipe's layout, inside its own
     * structures, which differ between versions — in JEI 19.21 a {@code RecipeTransferButton} holding a vanilla
     * {@link Button}, in 19.57 an {@code IconButton} whose controller is a {@code RecipeTransferButtonController} and
     * which holds a vanilla {@link Button} as well. This walks the recipe screen's object graph by reflection, a few
     * steps from the screen's {@code RecipeGuiLayouts} and only through JEI's own GUI classes — the package the recipe
     * screen's own package sits in, read from the screen at runtime — and takes an object as the "+" when its class,
     * or the class of one of its fields, has a simple name starting with
     * {@code RecipeTransferButton} and it holds exactly one vanilla button. What it reads of JEI's public API — the
     * recipe and its category, through {@code IRecipeLayoutDrawable#getRecipe} and {@code #getRecipeCategory} — it
     * calls by name as well. Dev tooling only (ADR-014).
     */
    static final class JeiScreens {
        private static final String LAYOUTS = "RecipeGuiLayouts";
        private static final String TRANSFER = "RecipeTransferButton";
        /** Steps from the layouts the walk looks into; the button sits three steps down in 19.21 and 19.57. */
        private static final int DEPTH = 3;
        /** Collections larger than this are not JEI's layouts or buttons and are not looked into. */
        private static final int MAX_ELEMENTS = 256;

        private JeiScreens() {
        }

        /**
         * One "+": the object JEI keeps it in, the vanilla button it draws, and the recipe and category its layout
         * shows.
         */
        record TransferButton(Object holder, Button button, Optional<ResourceLocation> recipe,
                Optional<ResourceLocation> category) {
            /**
             * The button's centre, checked with JEI's own hit test of the "+" ({@code isMouseOver} of the object JEI
             * keeps it in), with the vanilla button's own one when the button is {@code usable}, and with the recipe
             * screen's: the point lies on JEI's window.
             */
            ScreenInput.Point hitTested(@Nullable Screen screen, boolean usable) {
                ScreenInput.Point point = new ScreenInput.Point(button.getX() + button.getWidth() / 2.0,
                        button.getY() + button.getHeight() / 2.0);
                if (!(Boolean) invoke(holder, "isMouseOver", point.x(), point.y()))
                    throw new VisualTestException("JEI's hit test does not put " + point + " on its + (" + describe()
                            + ")");
                if (usable && !button.isMouseOver(point.x(), point.y()))
                    throw new VisualTestException("the + button's own hit test does not put " + point + " on it");
                if (screen == null || !screen.isMouseOver(point.x(), point.y()))
                    throw new VisualTestException("JEI's recipe screen does not claim " + point);
                return point;
            }

            String describe() {
                return String.format(Locale.ROOT, "%s visible=%s active=%s at [%d,%d %dx%d] recipe=%s category=%s",
                        holder.getClass().getSimpleName(), button.visible, button.active, button.getX(),
                        button.getY(), button.getWidth(), button.getHeight(),
                        recipe.map(ResourceLocation::toString).orElse("?"),
                        category.map(ResourceLocation::toString).orElse("?"));
            }
        }

        /** Every "+" on JEI's recipe screen {@code screen}, in the order JEI lays its recipes out. */
        static List<TransferButton> transferButtons(Screen screen) {
            // JEI's own GUI classes, the only ones the walk looks into: the package the screen's package sits in.
            String screenPackage = screen.getClass().getPackageName();
            String gui = screenPackage.substring(0, Math.max(0, screenPackage.lastIndexOf('.'))) + ".";
            Object layouts = null;
            for (Object value : fieldValues(screen, gui))
                if (value.getClass().getSimpleName().equals(LAYOUTS))
                    layouts = value;
            if (layouts == null)
                throw new VisualTestException("JEI's recipe screen " + screen.getClass().getName() + " holds no "
                        + LAYOUTS);
            Map<Object, Object> parents = new IdentityHashMap<>();
            Map<Object, Integer> depths = new IdentityHashMap<>();
            Deque<Object> queue = new ArrayDeque<>();
            depths.put(layouts, 0);
            queue.add(layouts);
            List<TransferButton> found = new ArrayList<>();
            while (!queue.isEmpty()) {
                Object current = queue.poll();
                int depth = depths.get(current);
                List<Object> values = fieldValues(current, gui);
                List<Button> buttons = values.stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .toList();
                boolean transfer = current.getClass().getSimpleName().startsWith(TRANSFER)
                        || values.stream().anyMatch(value -> value.getClass().getSimpleName().startsWith(TRANSFER));
                if (transfer && buttons.size() == 1) {
                    Optional<Object> layout = recipeLayout(current, parents.get(current), gui);
                    found.add(new TransferButton(current, buttons.getFirst(), layout.flatMap(JeiScreens::recipeId),
                            layout.flatMap(JeiScreens::categoryId)));
                    continue;
                }
                if (depth >= DEPTH)
                    continue;
                for (Object value : expand(values)) {
                    if (depths.containsKey(value) || !value.getClass().getName().startsWith(gui))
                        continue;
                    depths.put(value, depth + 1);
                    parents.put(value, current);
                    queue.add(value);
                }
            }
            return found;
        }

        /** The recipe layout a "+" belongs to: a field of the object holding it, or of the one holding that. */
        private static Optional<Object> recipeLayout(Object holder, @Nullable Object parent, String gui) {
            for (Object owner : parent == null ? List.of(holder) : List.of(holder, parent))
                for (Object value : fieldValues(owner, gui))
                    if (hasMethod(value, "getRecipe") && hasMethod(value, "getRecipeCategory"))
                        return Optional.of(value);
            return Optional.empty();
        }

        private static Optional<ResourceLocation> recipeId(Object layout) {
            return invoke(layout, "getRecipe") instanceof RecipeHolder<?> holder ? Optional.of(holder.id())
                    : Optional.empty();
        }

        private static Optional<ResourceLocation> categoryId(Object layout) {
            Object type = invoke(invoke(layout, "getRecipeCategory"), "getRecipeType");
            return invoke(type, "getUid") instanceof ResourceLocation uid ? Optional.of(uid) : Optional.empty();
        }

        /** Collections, arrays and optionals in {@code values} replaced by what they hold; small ones only. */
        private static List<Object> expand(List<Object> values) {
            List<Object> expanded = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Collection<?> collection) {
                    if (collection.size() <= MAX_ELEMENTS)
                        collection.stream().filter(Objects::nonNull).forEach(expanded::add);
                } else if (value instanceof Optional<?> optional) {
                    optional.ifPresent(expanded::add);
                } else if (value instanceof Object[] array) {
                    if (array.length <= MAX_ELEMENTS)
                        for (Object element : array)
                            if (element != null)
                                expanded.add(element);
                } else {
                    expanded.add(value);
                }
            }
            return expanded;
        }

        /**
         * The non-null values of every instance field of {@code owner} that its class or one of its superclasses in
         * the package {@code gui} declares. Fields JEI's module does not open are skipped.
         */
        private static List<Object> fieldValues(Object owner, String gui) {
            List<Object> values = new ArrayList<>();
            for (Class<?> declaring = owner.getClass(); declaring != null && declaring.getName().startsWith(gui);
                    declaring = declaring.getSuperclass()) {
                for (Field field : declaring.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                            || !field.trySetAccessible())
                        continue;
                    try {
                        Object value = field.get(owner);
                        if (value != null)
                            values.add(value);
                    } catch (IllegalAccessException error) {
                        throw new VisualTestException("cannot read " + field + ": " + error);
                    }
                }
            }
            return values;
        }

        private static boolean hasMethod(Object owner, String name) {
            try {
                owner.getClass().getMethod(name);
                return true;
            } catch (NoSuchMethodException error) {
                return false;
            }
        }

        /** Calls the public method {@code name} of {@code owner} with {@code arguments}: doubles, or none. */
        private static Object invoke(Object owner, String name, Object... arguments) {
            Class<?>[] types = new Class<?>[arguments.length];
            Arrays.fill(types, double.class);
            try {
                Method method = owner.getClass().getMethod(name, types);
                if (!method.trySetAccessible())
                    throw new VisualTestException("cannot call " + method);
                return method.invoke(owner, arguments);
            } catch (NoSuchMethodException | IllegalAccessException error) {
                throw new VisualTestException("cannot call " + owner.getClass().getName() + "#" + name + ": " + error);
            } catch (InvocationTargetException error) {
                throw new VisualTestException("calling " + owner.getClass().getName() + "#" + name + " failed: "
                        + error.getCause());
            }
        }
    }
}
