package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiConsumer;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksCreativeTabs;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Scenario "blocks": close-ups of every static Wareworks block model and the item icons (M4, visual
 * polish).
 * <p>
 * A row of exhibits along +X, {@value #SPACING} blocks apart, each turned so that the side a player stands at faces the
 * front camera (+Z): the dock with its parked crane (aisle side), two rails in a line (joint), the controller (display),
 * the interface (framed aisle plate; brass port at the back), input and output (aisle openings; intake on top, pull port at
 * the back) and, since M11, the copper-bodied production station (aisle opening). The warehouse terminal is the one
 * exhibit whose front is <b>not</b> its aisle side: since ADR-022 a player stands at its screen and the crane loads it
 * through the opposite face, so the front camera sees the screen and the back camera sees the arm port.
 * <p>
 * Shots per pass: the row from above and from far away (distant z-fighting shows there), a front and a back
 * close-up of every exhibit, two <b>player-eye</b> views of the terminal (M10 look pass: the screen side and the aisle side
 * as a standing player sees them, which the slightly raised close-ups above flatten), and a chest screen with every
 * Wareworks item in creative tab order (item icons). Before the screen the client rebuilds the creative tabs and checks
 * that the Wareworks tab lists the items in building order with the stacker crane as icon.
 */
public final class BlocksVisualScenario implements VisualScenario {
    public static final String NAME = "blocks";

    /** Blocks between two exhibits along X. */
    private static final int SPACING = 3;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 200;
    private static final int SCREEN_TIMEOUT_TICKS = 100;
    /** Ticks with the screen open before the shot, so item icons and the background have rendered. */
    private static final int SCREEN_SETTLE_TICKS = 10;
    private static final int CHEST_SLOTS = 27;
    /** Second slot of the chest's middle row. */
    private static final int FIRST_ITEM_SLOT = 10;

    /** Close-up cameras relative to an exhibit's block centre: to the side, above and in front of or behind it. */
    private static final double CLOSE_SIDE = 1.4;
    private static final double CLOSE_HEIGHT = 1.8;
    private static final double FRONT_DISTANCE = 2.3;
    private static final double BACK_DISTANCE = 2.1;
    private static final double LOOK_HEIGHT = 0.45;
    private static final double BLOCK_CENTER = 0.5;
    /**
     * Overview cameras: above the row in front of it, and far away.
     * <p>
     * The distance is what the widest exhibit row needs, not a round number: with the terminal as the seventh exhibit
     * (M6) the row spanned {@code 6 · }{@value #SPACING}{@code  + 1} blocks, and from 8 blocks away the dock's mast ran
     * out of the frame at the left edge — which is what the committed {@code docs/screenshots/blocks.png} showed until
     * M8. Since M11 the production station is the <b>eighth</b> exhibit and the row spans
     * {@code 7 · }{@value #SPACING}{@code  + 1} blocks, so these two distances are the widest the row has ever needed;
     * the {@code blocks-row} and {@code blocks-far} shots are what to check after adding a ninth.
     */
    private static final double ROW_HEIGHT = 5.5;
    private static final double ROW_DISTANCE = 10.0;
    private static final double FAR_HEIGHT = 10.0;
    private static final double FAR_DISTANCE = 26.0;

    /**
     * Player-eye views of the warehouse terminal (M10 look pass). The close-ups above stand 1.8 blocks up and look down
     * at the block, which is exactly the angle that hides whether a screen reads as a screen; these two stand on the
     * ground at vanilla eye height and look almost straight at the face a player would be reading.
     */
    private static final String TERMINAL_LABEL = "terminal";
    private static final double EYE_HEIGHT = 1.62;
    private static final double EYE_DISTANCE = 2.0;
    /** Looked at slightly above the block centre, so the screen rather than the floor sits in the middle of the frame. */
    private static final double EYE_LOOK_HEIGHT = 0.62;

    /** One exhibit: its label and how it is placed at its position (server thread). */
    private record Exhibit(String label, BiConsumer<ServerLevel, BlockPos> placer) {
    }

    private static final List<Exhibit> EXHIBITS = List.of(
            new Exhibit("dock", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.STACKER_CRANE.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.SOUTH))),
            new Exhibit("rail", (level, pos) -> {
                level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                        .setValue(WarehouseRailBlock.AXIS, Direction.Axis.Z));
                level.setBlockAndUpdate(pos.north(), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                        .setValue(WarehouseRailBlock.AXIS, Direction.Axis.Z));
            }),
            new Exhibit("controller", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_CONTROLLER
                    .getDefaultState().setValue(WarehouseControllerBlock.FACING, Direction.NORTH))),
            new Exhibit("interface", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INTERFACE
                    .getDefaultState().setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH))),
            new Exhibit("input", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INPUT
                    .getDefaultState().setValue(WarehouseInputBlock.FACING, Direction.SOUTH))),
            new Exhibit("output", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_OUTPUT
                    .getDefaultState().setValue(WarehouseOutputBlock.FACING, Direction.SOUTH))),
            // The terminal's FACING is its intake port, and its screen sits opposite (display = back, the default), so a
            // port towards -Z turns the screen towards the front camera: the side a player reads (ADR-022).
            new Exhibit(TERMINAL_LABEL, (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_TERMINAL
                    .getDefaultState().setValue(WarehouseTerminalBlock.FACING, Direction.NORTH))),
            // The production station faces the aisle like input and output, so its opening looks at the front camera.
            new Exhibit("production", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_PRODUCTION
                    .getDefaultState().setValue(WarehouseProductionBlock.FACING, Direction.SOUTH))));

    /** The creative tab contents as checked on the client; the chest screen shows them in this order. */
    private volatile List<Item> tabItems = List.of();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("blocks: clear the area and place the exhibits", BlocksVisualScenario::build)
                .until("blocks: wait until the client has every exhibit", BlocksVisualScenario::clientReady,
                        SCENE_READY_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        double rowCenter = (EXHIBITS.size() - 1) * SPACING / 2.0 + BLOCK_CENTER;
        script.shotFrom(CameraView.of("row", rowCenter, ROW_HEIGHT, ROW_DISTANCE, rowCenter, BLOCK_CENTER, BLOCK_CENTER),
                "blocks");
        script.shotFrom(CameraView.of("far", rowCenter, FAR_HEIGHT, FAR_DISTANCE, rowCenter, BLOCK_CENTER, BLOCK_CENTER),
                "blocks");
        for (int i = 0; i < EXHIBITS.size(); i++) {
            double x = i * SPACING + BLOCK_CENTER;
            String label = EXHIBITS.get(i).label();
            script.shotFrom(CameraView.of("front", x - CLOSE_SIDE, CLOSE_HEIGHT, BLOCK_CENTER + FRONT_DISTANCE, x, LOOK_HEIGHT,
                    BLOCK_CENTER), label);
            script.shotFrom(CameraView.of("back", x + CLOSE_SIDE, CLOSE_HEIGHT, BLOCK_CENTER - BACK_DISTANCE, x, LOOK_HEIGHT,
                    BLOCK_CENTER), label);
            if (label.equals(TERMINAL_LABEL))
                terminalEyeShots(script, x);
        }
        script.client("blocks: check the creative tab on the client", this::checkCreativeTab)
                .server("blocks: open a chest screen with every Wareworks item", this::openItemScreen)
                .until("blocks: the client shows the chest screen",
                        context -> context.minecraft().screen instanceof ContainerScreen, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SCREEN_SETTLE_TICKS)
                .shot("items-gui")
                .client("blocks: close the chest screen", context -> {
                    LocalPlayer player = context.minecraft().player;
                    if (player != null)
                        player.closeContainer();
                })
                .until("blocks: the chest screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    /**
     * The two player-eye views of the terminal at column {@code x}: {@code eye-display} from the screen side (+Z, where
     * the player stands) and {@code eye-aisle} from the intake side (-Z, where the crane's arm comes from).
     */
    private static void terminalEyeShots(VisualScript script, double x) {
        script.shotFrom(CameraView.of("eye-display", x, EYE_HEIGHT, BLOCK_CENTER + EYE_DISTANCE, x, EYE_LOOK_HEIGHT,
                BLOCK_CENTER), TERMINAL_LABEL);
        script.shotFrom(CameraView.of("eye-aisle", x, EYE_HEIGHT, BLOCK_CENTER - EYE_DISTANCE, x, EYE_LOOK_HEIGHT,
                BLOCK_CENTER), TERMINAL_LABEL);
    }

    @Override
    public String status(VisualContext context) {
        return "exhibits=" + EXHIBITS.size() + " screen="
                + (context.minecraft().screen == null ? "none" : context.minecraft().screen.getClass().getSimpleName());
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos origin = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(origin);
        int lastX = (EXHIBITS.size() - 1) * SPACING;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                origin.offset(lastX + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (int i = 0; i < EXHIBITS.size(); i++)
            EXHIBITS.get(i).placer().accept(level, origin.offset(i * SPACING, 0, 0));
    }

    private void openItemScreen(MinecraftServer server, VisualContext context) {
        List<Item> items = tabItems;
        if (items.isEmpty())
            throw new VisualTestException("the creative tab was not checked before the item screen");
        SimpleContainer container = new SimpleContainer(CHEST_SLOTS);
        for (int i = 0; i < items.size(); i++)
            container.setItem(FIRST_ITEM_SLOT + i, new ItemStack(items.get(i)));
        ServerPlayer player = context.serverPlayer(server);
        OptionalInt menu = player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, owner) -> ChestMenu.threeRows(containerId, inventory, container),
                Component.literal(Wareworks.NAME)));
        if (menu.isEmpty())
            throw new VisualTestException("the chest screen with the Wareworks items could not be opened");
    }

    // --- client ---------------------------------------------------------------------------------------------------------

    private static boolean clientReady(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        for (int i = 0; i < EXHIBITS.size(); i++) {
            if (level.getBlockState(context.origin().offset(i * SPACING, 0, 0)).isAir())
                return false;
        }
        return true;
    }

    /** Rebuilds the creative tabs like the creative inventory does and checks the Wareworks tab's order and icon. */
    private void checkCreativeTab(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("no client level for the creative tab check");
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true, level.registryAccess());
        CreativeModeTab tab = WareworksCreativeTabs.BASE.get();
        List<Item> shown = tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        Item icon = tab.getIconItem().getItem();
        LOGGER.info(PREFIX + "blocks: creative tab icon {}, items {}", BuiltInRegistries.ITEM.getKey(icon),
                shown.stream().map(BuiltInRegistries.ITEM::getKey).toList());
        List<Item> expected = List.of(WareworksBlocks.STACKER_CRANE.asItem(), WareworksBlocks.WAREHOUSE_RAIL.asItem(),
                WareworksBlocks.WAREHOUSE_CONTROLLER.asItem(), WareworksBlocks.WAREHOUSE_INTERFACE.asItem(),
                WareworksBlocks.WAREHOUSE_INPUT.asItem(), WareworksBlocks.WAREHOUSE_OUTPUT.asItem(),
                WareworksBlocks.WAREHOUSE_TERMINAL.asItem(), WareworksBlocks.WAREHOUSE_PRODUCTION.asItem());
        if (!shown.equals(expected) || icon != WareworksBlocks.STACKER_CRANE.asItem())
            throw new VisualTestException("unexpected creative tab: icon " + icon + ", items " + shown);
        tabItems = shown;
    }
}
