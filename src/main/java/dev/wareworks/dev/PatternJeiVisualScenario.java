package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import dev.wareworks.client.compat.jei.JeiRuntimeAccess;
import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.ModList;

/**
 * Scenario "patternjei" (ADR-076): JEI's real "+" lays recipes into a production station's patterns, with the real JEI
 * in the client. It needs {@code -Pwareworks.jei} and fails at once without it.
 * <p>
 * A station on its own, with no aisle: laying a pattern reads stock only to choose among candidates. Tab by tab, with a
 * real click on the tab and on the "+" ({@link JeiVisualScenario.JeiScreens}): a crafting recipe (the Chest), Create's
 * saw (Andesite Alloy to Shafts, {@link #SAWN}), and
 * the "+" of a recipe with a fluid (Chocolate Glazed Berries at the spout), which must be greyed out, lay nothing on a
 * click and say why in both languages. JEI's every-tick check must lay nothing on any recipe, and the tab selected at
 * the click is the one written: the others stay empty.
 */
public final class PatternJeiVisualScenario implements VisualScenario {
    public static final String NAME = "patternjei";

    private static final String WORLD_FOLDER = "wareworks_visual_patternjei";
    private static final String JEI_MOD_ID = "jei";
    private static final Direction FRONT = Direction.SOUTH;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 5;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int JEI_TIMEOUT_TICKS = 1200;
    private static final int LANGUAGE_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 6;
    private static final int NO_LAY_TICKS = 10;
    private static final long RUN_TIMEOUT_MILLIS = 6L * 60L * 1000L;

    private static final ResourceLocation CRAFTING = ResourceLocation.withDefaultNamespace("crafting");
    private static final ResourceLocation SAWING = ResourceLocation.fromNamespaceAndPath("create", "sawing");
    private static final ResourceLocation FILLING = ResourceLocation.fromNamespaceAndPath("create", "spout_filling");
    /** What the saw makes in the run. */
    private static final ResourceLocation SAWN = ResourceLocation.fromNamespaceAndPath("create", "shaft");
    private static final ResourceLocation GLAZED = ResourceLocation.fromNamespaceAndPath("create",
            "chocolate_glazed_berries");

    private final VisualLanguage language = new VisualLanguage(LANGUAGE_TIMEOUT_TICKS, SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void prepare(VisualScript script) {
        script.client("patternjei: delete screenshots of an earlier run", VisualContext::cleanScreenshots);
        script.client("patternjei: CHECK JEI is loaded", context -> {
            if (!ModList.get().isLoaded(JEI_MOD_ID))
                throw new VisualTestException("the patternjei scenario needs JEI: run it with -Pwareworks.jei");
        });
        VisualScenario.super.prepare(script);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("patternjei: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "patternjei run"))
                .server("patternjei: build the station", PatternJeiVisualScenario::build)
                .server("patternjei: lift the player into the air", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    BlockPos above = context.origin().above(4);
                    player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 2.5,
                            player.getYRot(), player.getXRot());
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                })
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass != VisualPass.FLYWHEEL) {
            open(script);
            script.shot("nofw-patternjei-station");
            close(script);
            return;
        }
        open(script);
        crafting(script);
        saw(script);
        fluid(script, "en");
        close(script);
        language.switchTo(script, "patternjei: ", "de_de");
        open(script);
        fluid(script, "de");
        close(script);
        language.switchTo(script, "patternjei: ", "en_us");
        script.client("patternjei: every check passed", context -> LOGGER.info(PREFIX
                + "patternjei: ALL CHECKS PASSED (crafting and saw laid with one click each into the selected tab, "
                + "a fluid recipe greyed out, English and German)"));
    }

    @Override
    public String status(VisualContext context) {
        return context.minecraft().screen == null ? "screen=none"
                : "screen=" + context.minecraft().screen.getClass().getSimpleName();
    }

    // --- chapters ----------------------------------------------------------------------------------------------------

    private void crafting(VisualScript script) {
        selectTab(script, 0);
        showRecipes(script, "the Chest", () -> Items.CHEST, CRAFTING, true);
        checkNoLay(script, 0);
        script.shot("patternjei-chest-recipe");
        clickPlus(script, "the Chest");
        script.until("patternjei: the Chest arrives in tab 1", context -> hasResult(context, 0, Items.CHEST, 1),
                SCREEN_TIMEOUT_TICKS)
                .client("patternjei: CHECK the Chest's grid", context -> {
                    ProductionScreenState state = screen(context).state();
                    if (state.entry(0, 4).isPresent())
                        throw new VisualTestException("the middle cell of the Chest is filled");
                    for (int cell : List.of(0, 1, 2, 3, 5, 6, 7, 8)) {
                        Optional<ProductionScreenState.EntryView> entry = state.entry(0, cell);
                        if (entry.isEmpty() || entry.get().count() != 1)
                            throw new VisualTestException("cell " + cell + " of the Chest is " + entry);
                    }
                    if (state.result(1).isPresent())
                        throw new VisualTestException("tab 2 was written as well");
                    LOGGER.info(PREFIX + "patternjei: CHECK PASS the Chest is laid into tab 1 with its result");
                });
        script.client("patternjei: park the mouse", PatternJeiVisualScenario::parkMouse).waitTicks(SETTLE_TICKS)
                .shot("patternjei-chest-laid");
    }

    private void saw(VisualScript script) {
        selectTab(script, 1);
        showRecipes(script, "the saw's recipe", () -> BuiltInRegistries.ITEM.get(SAWN), SAWING, true);
        script.shot("patternjei-saw-recipe");
        clickPlus(script, "the saw's recipe");
        script.until("patternjei: the saw's recipe arrives in tab 2",
                context -> screen(context).state().result(1).isPresent(), SCREEN_TIMEOUT_TICKS)
                .client("patternjei: CHECK the saw's pattern", context -> {
                    ProductionScreenState state = screen(context).state();
                    Optional<ProductionScreenState.EntryView> in = state.entry(1, 0);
                    Optional<ProductionScreenState.EntryView> out = state.result(1);
                    if (in.isEmpty() || out.isEmpty() || !out.get().key().equals(ItemKey.of(BuiltInRegistries.ITEM.get(SAWN))))
                        throw new VisualTestException("the saw's pattern is " + in + " -> " + out);
                    if (!hasResult(context, 0, Items.CHEST, 1))
                        throw new VisualTestException("tab 1 changed");
                    LOGGER.info(PREFIX + "patternjei: CHECK PASS the saw lays {} x{} -> {} x{}", in.get().key(),
                            in.get().count(), out.get().key(), out.get().count());
                });
        script.client("patternjei: park the mouse", PatternJeiVisualScenario::parkMouse).waitTicks(SETTLE_TICKS)
                .shot("patternjei-saw-laid");
    }

    private void fluid(VisualScript script, String suffix) {
        selectTab(script, 2);
        showRecipes(script, "the recipe with a fluid", () -> BuiltInRegistries.ITEM.get(GLAZED), FILLING, false);
        checkNoLay(script, 2);
        script.client("patternjei: click the greyed-out +", context -> ScreenInput.click(context.minecraft(),
                        plus(context).hitTested(context.minecraft().screen, false)))
                .waitTicks(NO_LAY_TICKS)
                .client("patternjei: rest the cursor on the greyed-out +", context -> ScreenInput.hover(
                        context.minecraft(), plus(context).hitTested(context.minecraft().screen, false)))
                .waitTicks(SETTLE_TICKS)
                .shot("patternjei-fluid-" + suffix)
                .client("patternjei: close JEI", context -> ScreenInput.key(context.minecraft(),
                        GLFW.GLFW_KEY_ESCAPE))
                .until("patternjei: the station's screen is back",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen,
                        SCREEN_TIMEOUT_TICKS)
                .client("patternjei: CHECK the greyed-out + laid nothing", context -> {
                    if (screen(context).state().result(2).isPresent())
                        throw new VisualTestException("the recipe with a fluid was laid");
                    LOGGER.info(PREFIX + "patternjei: CHECK PASS the recipe with a fluid is greyed out and lays nothing");
                });
    }

    // --- steps -------------------------------------------------------------------------------------------------------

    private static void open(VisualScript script) {
        script.camera(CameraView.of("station", 0.5, 1.6, 2.6, 0.5, 0.5, 0.5))
                .server("patternjei: open the station's screen", (server, context) -> {
                    if (!station(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
                        throw new VisualTestException("the station's screen did not open");
                })
                .until("patternjei: the screen shows its patterns",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen screen
                                && !screen.state().patterns().isEmpty(), SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    private static void close(VisualScript script) {
        script.client("patternjei: close the screen", context -> {
            LocalPlayer player = context.minecraft().player;
            if (player != null)
                player.closeContainer();
        }).until("patternjei: no screen is open", context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
    }

    private static void selectTab(VisualScript script, int tab) {
        script.client("patternjei: click tab " + (tab + 1), context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.productionTab(screen(context), tab)))
                .until("patternjei: tab " + (tab + 1) + " is selected",
                        context -> screen(context).selectedPattern() == tab, SCREEN_TIMEOUT_TICKS);
    }

    /** {@code output} is resolved when the step runs: a scenario is built before the registries are complete. */
    private static void showRecipes(VisualScript script, String what, Supplier<Item> output, ResourceLocation category,
            boolean usable) {
        ArmVisualScenario.untilOrFail(script, "patternjei: JEI is running", context -> JeiRuntimeAccess.available(),
                JEI_TIMEOUT_TICKS, context -> "JEI has no runtime");
        script.client("patternjei: show " + what + " in " + category, context -> {
            if (!JeiRuntimeAccess.showRecipesMaking(new ItemStack(output.get()), category))
                throw new VisualTestException("JEI has no recipe for " + output.get() + " in " + category);
        });
        ArmVisualScenario.untilOrFail(script, "patternjei: JEI shows " + what, context -> {
            Screen open = context.minecraft().screen;
            if (!JeiRuntimeAccess.isRecipeScreen(open))
                return false;
            List<JeiVisualScenario.JeiScreens.TransferButton> buttons = JeiVisualScenario.JeiScreens
                    .transferButtons(open);
            return !buttons.isEmpty() && buttons.getFirst().button().visible
                    && buttons.getFirst().button().active == usable;
        }, SCREEN_TIMEOUT_TICKS, context -> "the screen is " + context.minecraft().screen);
    }

    /** JEI's every-tick check over a few ticks: the selected tab stays empty until a click. */
    private static void checkNoLay(VisualScript script, int tab) {
        script.waitTicks(NO_LAY_TICKS).client("patternjei: CHECK JEI's every-tick check laid nothing", context -> {
            WarehouseProductionScreen covered = WarehouseProductionScreen.forMenu(
                    context.minecraft().player.containerMenu.containerId).orElseThrow(
                            () -> new VisualTestException("the station's screen is lost under JEI"));
            if (covered.state().result(tab).isPresent())
                throw new VisualTestException("JEI's every-tick check laid a recipe into tab " + (tab + 1));
            LOGGER.info(PREFIX + "patternjei: CHECK PASS JEI's every-tick check laid nothing");
        });
    }

    private static void clickPlus(VisualScript script, String what) {
        script.client("patternjei: click the + on " + what, context -> ScreenInput.click(context.minecraft(),
                        plus(context).hitTested(context.minecraft().screen, true)))
                .until("patternjei: JEI hands the station's screen back",
                        context -> context.minecraft().screen instanceof WarehouseProductionScreen,
                        SCREEN_TIMEOUT_TICKS);
    }

    private static JeiVisualScenario.JeiScreens.TransferButton plus(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (!JeiRuntimeAccess.isRecipeScreen(open))
            throw new VisualTestException("JEI's recipe screen is not open: " + open);
        List<JeiVisualScenario.JeiScreens.TransferButton> buttons = JeiVisualScenario.JeiScreens.transferButtons(open);
        if (buttons.isEmpty())
            throw new VisualTestException("JEI's recipe screen shows no +");
        return buttons.getFirst();
    }

    private static boolean hasResult(VisualContext context, int tab, Item item, int count) {
        return screen(context).state().result(tab)
                .filter(view -> view.key().equals(ItemKey.of(item)) && view.count() == count).isPresent();
    }

    /** The cursor in the window's title row: on no tab, cell, slot or line. */
    private static void parkMouse(VisualContext context) {
        WarehouseProductionScreen screen = screen(context);
        ScreenInput.hover(context.minecraft(), new ScreenInput.Point(screen.getGuiLeft() + 120.0,
                screen.getGuiTop() + 6.0));
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
                origin.offset(CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(origin, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, FRONT));
        if (station(level, origin).patterns().size() < 3 || ProductionPatterns.MAX_SLOTS < 3)
            throw new VisualTestException("the station has too few patterns for this run");
    }

    private static WarehouseProductionBlockEntity station(ServerLevel level, BlockPos origin) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                origin);
        if (station == null)
            throw new VisualTestException("no production station at " + origin);
        return station;
    }
}
