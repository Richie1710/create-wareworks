package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.catnip.math.Pointing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "showcase": a complete, working warehouse in a world that is <b>kept</b>, so a player can open it and walk
 * around in it (M6, extended for M11).
 * <p>
 * Unlike the screenshot scenarios this one uses {@link VisualWorldProfile#playable}: the player stays in
 * {@link GameType#CREATIVE} with the vanilla interaction ranges, the world spawn is set in front of the warehouse, and
 * the run ends with {@link VisualWorld#saveAndQuit} rather than leaving a half-written save behind. The world folder is
 * {@value #WORLD_FOLDER}; the world list shows {@value #LEVEL_NAME}.
 * <p>
 * What it builds, with the dock as the origin on the superflat surface and the aisle running east:
 * <ul>
 *   <li>a creative motor below the dock at {@value #MOTOR_RPM} RPM, {@value #RAILS} rails, the controller behind the
 *       dock;</li>
 *   <li>storage locations (warehouse interface + chest) on both sides, positions {@value #STORAGE_FIRST_POSITION} to
 *       {@value #RAILS} and {@value #STORAGE_LEVELS} levels, prefilled with a varied assortment so the terminal list
 *       looks alive;</li>
 *   <li>the <b>input</b> side: a chest feeding a hopper that drops into the warehouse input;</li>
 *   <li>the <b>output</b> side: a warehouse output one level up, a hopper below it pulling into a chest, and a lever
 *       beside it for the redstone request path (its filter is preset, so the lever works straight away);</li>
 *   <li>the <b>warehouse terminal</b> beside the dock, with its screen facing away from the aisle (ADR-022);</li>
 *   <li><b>two production loops</b> (M11, ADR-024), see below;</li>
 *   <li>a starter chest at the spawn point with Engineer's Goggles, a Wrench and a stack of every Wareworks block, and
 *       signs labelling each part.</li>
 * </ul>
 *
 * <h2>The two production loops</h2>
 * Both are the same shape — <b>Wareworks delivers and collects, it never crafts</b> — with a different machine of the
 * player's in the middle:
 * <ul>
 *   <li><b>Sawmill</b> (right-hand rack wall): a production station holds the pattern "1 andesite alloy &rarr; 6
 *       shafts". A hopper under the station drops what the crane delivers onto a short <b>belt</b>, which carries it
 *       into a <b>Mechanical Saw</b> standing face up; the saw pushes its shafts straight into a <b>warehouse
 *       input</b> one block away, from where the crane stores them. The feed is a belt rather than hoppers because
 *       that is the path Create designs for a saw — a hopper feed made the ingredient vanish from every inventory
 *       without ever being cut. The saw carries a <b>recipe filter set to Shaft</b>, for the reason below.</li>
 *   <li><b>Mechanical crafter</b> (left-hand rack wall): a production station holds the pattern "1 gunpowder + 1 blaze
 *       powder + 1 coal &rarr; 3 fire charges". A hopper drops the delivered ingredients onto a <b>belt</b>; three
 *       <b>brass belt funnels</b>, each filtered to one ingredient, take them off the belt into three <b>hoppers</b>,
 *       and each hopper feeds the <b>Mechanical Crafter</b> behind it. The crafter the chain ends at inserts the fire
 *       charges into a <b>warehouse input</b>.</li>
 * </ul>
 * <b>Why andesite alloy and not a log</b> (instead of the log → planks example):
 * Create 6.0.10 ships <i>no</i> {@code create:cutting} recipe that turns a vanilla log into planks — the only
 * non-compat {@code create:cutting} recipes in the jar are {@code andesite_alloy → 6 shaft} and
 * {@code bamboo_planks → bamboo_mosaic}. A sawmill loop built on "log → planks" would therefore never produce
 * anything, so the showcase uses the recipe the saw really performs.
 * <p>
 * <b>Why the saw needs a filter.</b> {@code create:cutting} is not all a saw accepts:
 * {@code CRecipes.allowStonecuttingOnSaw} defaults to {@code true}, so {@code SawBlockEntity#getRecipes()} also returns
 * every <b>stonecutting</b> recipe whose first ingredient matches — and 6.0.10 ships four of those for andesite alloy
 * (bars, ladder, scaffolding, table cloth). With five candidates {@code SawBlockEntity#start} <i>cycles</i> through
 * them ({@code recipeIndex++}), so only one run in five yields shafts; the showcase saw cut andesite <b>ladders</b>
 * into the warehouse and the order for shafts timed out. Setting the saw's own {@link FilteringBehaviour} — installed
 * by {@code SawBlockEntity#addBehaviours} and applied in {@code getRecipes} through
 * {@code RecipeConditions.outputMatchesFilter} — to Shaft collapses the five candidates to the one cutting recipe.
 * That is exactly the filter a player puts into the saw's filter slot.
 * <p>
 * <b>Why the crafters are fed through hoppers.</b> A mechanical crafter refuses every insertion while its group is
 * working ({@code MechanicalCrafterBlockEntity.Inventory#insertItem} hands the stack back unless
 * {@code phase == IDLE}), and a belt funnel that cannot insert leaves the item on the belt, where it rides past all
 * three funnels to the end. An order of more than one run therefore delivered its second set of ingredients into
 * nothing at all. A hopper between each funnel and its crafter is the buffer that waits: the funnel can always take
 * the item off the belt, and the hopper pushes it in the moment the crafter is idle again.
 * <p>
 * The funnel filters are not decoration either: a crafter slot holds exactly <b>one</b> item, so an unfiltered feed
 * would put two gunpowder into two crafters as soon as an order runs the pattern twice, and the group would try to
 * craft a recipe that does not exist. Filter plus hopper make the routing deterministic for any order size.
 * <p>
 * Before the shots the scenario proves the warehouse and <b>both</b> loops: it feeds stacks through the input and waits
 * until the crane stored them, flips the output lever and waits for the redstone delivery, then orders the product of
 * each loop at the terminal and waits until both production orders read {@code COMPLETE} and the crane has delivered.
 * A loop that did not work fails the Gradle task instead of being saved into the world.
 */
public final class ShowcaseVisualScenario implements VisualScenario {
    public static final String NAME = "showcase";
    /** Save folder under {@code run/showcase/saves}; copied to {@code run/saves} to open it with {@code runClient}. */
    public static final String WORLD_FOLDER = "wareworks_showcase";
    /** The name the world list shows. */
    public static final String LEVEL_NAME = "Wareworks Showcase";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 14;
    private static final int MOTOR_RPM = 96;
    /**
     * Speed of the three machine motors of the production loops.
     * <p>
     * It is deliberately higher than the crane's: a mechanical crafter chain spends a fixed countdown in each of its
     * phases ({@code MechanicalCrafterBlockEntity#getCountDownSpeed}, which is the rotation speed clamped to 4..250),
     * and a belt funnel waits out an extraction cooldown per item. At 64 RPM one run of the three-ingredient pattern
     * took about two minutes, which is uncomfortably close to {@code productionOrderTimeoutTicks} (6000). The saw's
     * cutting time scales the same way, as {@code |speed| / 24}.
     */
    private static final int MACHINE_RPM = 128;

    /** The terminal sits beside the dock, so a player standing at the spawn point is already in reach of it. */
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition INPUT = RackPosition.of(1, 0, Side.RIGHT);
    /** Hopper above the input and the chest feeding it: a player drops items in at the top and watches them stored. */
    private static final RackPosition INPUT_HOPPER = RackPosition.of(1, 1, Side.RIGHT);
    private static final RackPosition INPUT_CHEST = RackPosition.of(1, 2, Side.RIGHT);
    /**
     * The output stands one level up, so a hopper fits <b>below</b> it: hoppers only pull out of the container above
     * them (GameTest {@code outputextractonly} uses exactly this geometry).
     */
    private static final RackPosition OUTPUT = RackPosition.of(3, 1, Side.LEFT);
    private static final RackPosition OUTPUT_HOPPER = RackPosition.of(3, 0, Side.LEFT);
    private static final RackPosition OUTPUT_CHEST = RackPosition.of(2, 0, Side.LEFT);

    // --- the sawmill loop (right-hand rack wall) --------------------------------------------------------------------
    /** The production station the crane delivers andesite alloy to; the hopper below drops it onto the saw's belt. */
    private static final RackPosition SAW_STATION = RackPosition.of(5, 1, Side.RIGHT);
    private static final RackPosition SAW_DRAIN = RackPosition.of(5, 0, Side.RIGHT);
    /**
     * The saw is fed by a short belt rather than by hoppers. A mechanical saw is built to be belt-fed — its
     * {@code DirectBeltInputBehaviour} is the path Create designs for it — and a belt is the one feed mechanism this
     * world already proves works, in the crafter loop. A two-hopper feed left the alloy neither in any inventory, nor
     * back in storage, nor on the floor.
     */
    private static final RackPosition SAW_BELT_START = RackPosition.of(6, 0, Side.RIGHT);
    private static final RackPosition SAW_BELT_END = RackPosition.of(4, 0, Side.RIGHT);
    /** Where the saw hands its shafts back to the warehouse. */
    private static final RackPosition SAW_RETURN = RackPosition.of(3, 0, Side.RIGHT);

    // --- the mechanical crafter loop (left-hand rack wall) ------------------------------------------------------------
    /** Where the finished fire charges go back into the warehouse; the crafter chain ends pointing at it. */
    private static final RackPosition CRAFTER_RETURN = RackPosition.of(5, 1, Side.LEFT);
    /** The production station of the crafter loop; the hopper below it drops the ingredients onto the belt. */
    private static final RackPosition CRAFTER_STATION = RackPosition.of(7, 2, Side.LEFT);
    private static final RackPosition CRAFTER_DRAIN = RackPosition.of(7, 1, Side.LEFT);
    /**
     * First belt block; the belt runs away from the aisle, under the three funnels.
     * <p>
     * The whole belt lane sits <b>two</b> positions along the aisle from the crafters, not one: the column between
     * them carries the hoppers that buffer one ingredient each (class comment).
     */
    private static final RackPosition CRAFTER_BELT_START = RackPosition.of(7, 0, Side.LEFT);
    /**
     * Column of the crafter loop's second label. It may <b>not</b> be the station's own column: that column is the
     * belt lane, and a sign there replaces the chest at the end of the belt — which is how the loop used to drop its
     * ingredients on the floor.
     */
    private static final int CRAFTER_LABEL_X = CRAFTER_STATION.x() - 1;
    /** Blocks of belt after the start; the funnels sit over the first three of them. */
    private static final int CRAFTER_BELT_LENGTH = 4;
    private static final int CRAFTERS = 3;

    private static final int STORAGE_FIRST_POSITION = 8;
    private static final int STORAGE_LEVELS = 3;

    private static final int CLEAR_MARGIN = 9;
    private static final int CLEAR_HEIGHT = 10;
    /** How far outside the rack wall the labels stand, so they never replace a chest. */
    private static final int SIGN_OFFSET = 2;

    /**
     * The showcase builds a whole warehouse, indexes it and then runs a store job, a redstone request and <b>two</b>
     * complete production loops before the shots, so it needs a far larger run budget than a screenshot scenario. It is
     * started in the background and polled, so no tool timeout sits under it.
     */
    private static final long RUN_TIMEOUT_MILLIS = 16L * 60L * 1000L;
    private static final int SCENE_READY_TIMEOUT_TICKS = 1200;
    private static final int STORED_TIMEOUT_TICKS = 2400;
    /** A whole production loop is several crane trips plus a machine run, so it gets its own, larger budget. */
    private static final int PRODUCTION_TIMEOUT_TICKS = 9000;
    private static final int POWER_TIMEOUT_TICKS = 200;
    /** Ticks between starting the machine motors and checking that their networks really turn. */
    private static final int MACHINE_SETTLE_TICKS = 60;
    private static final int SETTLE_TICKS = 10;

    private static final int FULL_STACK = 64;
    private static final int PICKAXE_DAMAGE = 120;
    /** The output's preset request: one stack of iron, so the lever does something the moment a player flips it. */
    private static final int OUTPUT_REQUEST_AMOUNT = 16;

    // --- the two patterns ---------------------------------------------------------------------------------------------
    // Create's own items are reached through a method, never through a static field: the harness builds its scenario
    // list while the mod is still being constructed, and a Registrate entry cannot be resolved that early ("Trying to
    // access unbound value: ResourceKey[minecraft:item / create:andesite_alloy]"). Vanilla Items are bootstrapped long
    // before mod construction and are safe as constants.
    private static final int SAW_PRODUCT_PER_RUN = 6;
    /** Mechanical crafter: a shapeless vanilla recipe with three clearly different ingredients. */
    private static final List<ItemKey> CRAFTER_INGREDIENTS = List.of(ItemKey.of(Items.GUNPOWDER),
            ItemKey.of(Items.BLAZE_POWDER), ItemKey.of(Items.COAL));
    private static final ItemKey CRAFTER_PRODUCT = ItemKey.of(Items.FIRE_CHARGE);
    private static final int CRAFTER_PRODUCT_PER_RUN = 3;
    /** One run of the sawmill and <b>two</b> of the crafter, so the filtered funnels are proven on a repeat. */
    private static final int SAW_ORDER = SAW_PRODUCT_PER_RUN;
    private static final int CRAFTER_ORDER = 2 * CRAFTER_PRODUCT_PER_RUN;

    /** Polls of the production wait between two progress snapshots in the log (one scenario instance per run). */
    private static final int PRODUCTION_LOG_INTERVAL = 100;
    private static int productionPolls;

    /** Sawmill ingredient: the one cutting recipe of Create 6.0.10 a warehouse can usefully feed (class comment). */
    private static ItemKey sawIngredient() {
        return ItemKey.of(AllItems.ANDESITE_ALLOY.get());
    }

    /** What one run of the sawmill pattern is expected to yield. */
    private static ItemKey sawProduct() {
        return ItemKey.of(AllBlocks.SHAFT.get());
    }

    // Player-eye cameras, relative to the dock's lower corner. Every one of them is a real standing eye position, so
    // the shots show what a player actually sees; a one-block-tall station is therefore always looked at from a few
    // blocks away, never from directly above, or the shot shows its lid instead of its face.
    private static final double EYE_HEIGHT = 1.62;
    /**
     * The spawn view, and the world spawn itself: far enough back on the ground for the whole warehouse to fit, looking
     * diagonally at the front of it.
     */
    private static final CameraView SPAWN = CameraView.of("spawn", -7.5, EYE_HEIGHT, 8.5, 5.0, 1.8, 0.0);
    /**
     * Standing at the far end of the aisle and looking <b>back</b> down it: rack walls on both sides, the rail line
     * between them, and the crane with the stations and the dock at the end.
     */
    private static final CameraView ALONG = CameraView.of("aisle", 17.5, EYE_HEIGHT, 0.5, 0.5, 1.3, 0.5);
    /**
     * The warehouse terminal from the side a player stands at (M10, ADR-022): outside the rack wall, at eye height,
     * looking at its screen. The pre-M10 view stood in the aisle and therefore photographed the arm port.
     */
    private static final CameraView TERMINAL_VIEW = CameraView.of("terminal", 1.5, 1.45, -3.2, 1.5, 0.8, -1.4);
    /** The input tower: the feed chest and the hopper that drops into the warehouse input. */
    private static final CameraView STATIONS = CameraView.of("stations", 5.5, 1.7, 0.5, 2.0, 1.3, -0.3);
    /** The output cluster from outside the rack wall: output, lever, the hopper below and the chest it fills. */
    private static final CameraView OUTPUT_VIEW = CameraView.of("output", 0.0, EYE_HEIGHT, -5.0, 2.8, 1.1, -1.2);
    /** Between the racks, where the crane stores and fetches. */
    private static final CameraView RACKS = CameraView.of("racks", 9.5, EYE_HEIGHT, 0.5, 14.0, 1.6, -1.0);
    /** The sawmill loop: production station, the drain hopper, the feed belt, the saw and the input it fills. */
    private static final CameraView SAWMILL = CameraView.of("sawmill", 9.0, 2.6, 7.0, 4.2, 1.1, 1.8);
    /**
     * The crafter loop: production station, belt, the three filtered funnels, the buffer hoppers, the crafters and the
     * input.
     */
    private static final CameraView CRAFTER = CameraView.of("crafters", 1.5, 2.6, -7.5, 6.4, 1.5, -3.0);

    /** One label in the world: where it stands, which way it faces and its lines. */
    private record Label(BlockPos pos, Direction facing, List<String> lines) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, LEVEL_NAME, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("showcase: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "showcase run"))
                .server("showcase: clear the area and place the creative motor", ShowcaseVisualScenario::placeMotor)
                .server("showcase: build the aisle, stations, terminal and racks", ShowcaseVisualScenario::buildAisle)
                .server("showcase: build the sawmill loop", ShowcaseVisualScenario::buildSawmill)
                .server("showcase: build the mechanical crafter loop", ShowcaseVisualScenario::buildCrafterLoop)
                .server("showcase: label the parts and fill the starter chest", ShowcaseVisualScenario::placeLabels)
                .serverUntil("showcase: wait until the controller has indexed every rack",
                        ShowcaseVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("showcase: write the two production patterns", ShowcaseVisualScenario::writePatterns)
                .server("showcase: preset the output filter", ShowcaseVisualScenario::presetOutputFilter)
                // The motors are started a step after they are placed, like the dock's: a creative motor pushes its
                // speed into the kinetic network the moment the value changes, and the network of a block placed in
                // the same server task is not built yet.
                .server("showcase: start the three machine motors", ShowcaseVisualScenario::startMachineMotors)
                .waitTicks(MACHINE_SETTLE_TICKS)
                .server("showcase: check that saw, belt and crafters turn",
                        ShowcaseVisualScenario::checkMachinesPowered)
                // The saw hands its results to whatever lies in its item movement direction, and that direction
                // depends on the sign of its rotation. Reading it and flipping this loop's own motor is deterministic;
                // deriving it from the kinetic network would be a guess.
                .server("showcase: aim the saw at the warehouse input", ShowcaseVisualScenario::aimSaw)
                .serverUntil("showcase: the saw really pushes towards the warehouse input",
                        ShowcaseVisualScenario::sawAimed, POWER_TIMEOUT_TICKS)
                // A belt carries items towards its movement facing, which also follows the sign of its rotation: the
                // same read-and-reverse as the saw, or the ingredients would ride back into the rack wall.
                .server("showcase: aim both belts", ShowcaseVisualScenario::aimBelts)
                .serverUntil("showcase: both belts really carry the right way",
                        ShowcaseVisualScenario::beltsAimed, POWER_TIMEOUT_TICKS)
                .server("showcase: feed a few stacks through the input", ShowcaseVisualScenario::feedInput)
                .serverUntil("showcase: wait until the crane has stored them", ShowcaseVisualScenario::allStored,
                        STORED_TIMEOUT_TICKS)
                // Prove the redstone request path of the showcase itself, not just the storing side.
                .server("showcase: flip the lever to request through the output",
                        (server, context) -> setLever(server, context, true))
                .serverUntil("showcase: wait until the output request reached the chest",
                        ShowcaseVisualScenario::outputDelivered, STORED_TIMEOUT_TICKS)
                .server("showcase: flip the lever back", (server, context) -> setLever(server, context, false))
                .serverUntil("showcase: wait until the crane is idle again", ShowcaseVisualScenario::craneIdle,
                        STORED_TIMEOUT_TICKS)
                // Prove both production loops end to end: order at the terminal, let the player's machines work, and
                // wait until the warehouse has the products back and has delivered them.
                .server("showcase: order the product of both production loops", ShowcaseVisualScenario::orderProducts)
                .serverUntil("showcase: wait until both production orders are complete",
                        ShowcaseVisualScenario::productionComplete, PRODUCTION_TIMEOUT_TICKS)
                .serverUntil("showcase: wait until both products were delivered to the terminal",
                        ShowcaseVisualScenario::productsDelivered, PRODUCTION_TIMEOUT_TICKS)
                .serverUntil("showcase: wait until the crane is idle after production",
                        ShowcaseVisualScenario::craneIdle, STORED_TIMEOUT_TICKS)
                // Both loops used to lose ingredients silently while the orders still looked healthy, so the run says
                // so out loud instead of saving a world in which the second run of a pattern quietly fails.
                .server("showcase: check that neither loop lost an ingredient",
                        ShowcaseVisualScenario::loopsLostNothing)
                .server("showcase: place the player and the world spawn", ShowcaseVisualScenario::placePlayer)
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            for (CameraView view : List.of(SPAWN, ALONG, TERMINAL_VIEW, STATIONS, OUTPUT_VIEW, RACKS, SAWMILL, CRAFTER))
                script.shotFrom(view, "showcase");
            return;
        }
        // Last pass: one shot with Flywheel off, then put the player back on the ground and leave a complete save.
        script.shotFrom(SPAWN, "showcase");
        script.server("showcase: land the player at the spawn point", ShowcaseVisualScenario::landPlayer)
                .waitTicks(SETTLE_TICKS);
        VisualWorld.saveAndQuit(script);
    }

    @Override
    public String status(VisualContext context) {
        return String.format(Locale.ROOT, "world=%s rails=%d rpm=%d loops=2", WORLD_FOLDER, RAILS, MOTOR_RPM);
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motorAt(level, dock.below()).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        placeStations(level, layout);
        placeRacks(level, layout);
    }

    /** Terminal, input with its feed chest, output with its hopper, pull chest and lever. */
    private static void placeStations(ServerLevel level, AisleLayout layout) {
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));

        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        // Chest on top of a downward hopper: the player drops items into the chest and the hopper feeds the input.
        level.setBlockAndUpdate(layout.rackPos(INPUT_HOPPER),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(layout.rackPos(INPUT_CHEST), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, layout.sideDirection(INPUT_CHEST.side())));

        Direction outward = layout.sideDirection(OUTPUT.side());
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
        // Hopper directly below the output pulls delivered items out and pushes them along the aisle into the chest.
        level.setBlockAndUpdate(layout.rackPos(OUTPUT_HOPPER),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, AISLE.getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(OUTPUT_CHEST),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward));
        // A solid block outside the rack wall carries the lever; the lever sits beside the output, and a lever reports
        // signal 15 to every side, so the output sees a neighbour signal (LeverBlock#getSignal).
        level.setBlockAndUpdate(layout.rackPos(OUTPUT_HOPPER).relative(outward), Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(layout.rackPos(OUTPUT).relative(outward), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, AISLE)
                .setValue(LeverBlock.POWERED, false));
    }

    /** Storage locations on both sides: a chest behind the rack wall and a warehouse interface facing it. */
    private static void placeRacks(ServerLevel level, AisleLayout layout) {
        List<ItemStack> stock = stock();
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
                    if (next < stock.size())
                        insertAll(level, chest, stock.get(next++));
                }
            }
        }
        if (next < stock.size())
            throw new VisualTestException("only " + next + " of " + stock.size() + " item types fit into the racks");
        LOGGER.info(PREFIX + "showcase: filled {} of {} storage locations", next,
                (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length);
    }

    /**
     * A varied, recognisable assortment: ores and raw materials, ingots, blocks, building materials, food, one damaged
     * tool and the ingredients of the two production patterns, so the terminal's list shows different icons, very
     * different amounts, an item with components and something the warehouse can actually <b>make</b>.
     */
    private static List<ItemStack> stock() {
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.set(DataComponents.DAMAGE, PICKAXE_DAMAGE);
        return List.of(new ItemStack(Items.IRON_INGOT, FULL_STACK), new ItemStack(Items.COPPER_INGOT, FULL_STACK),
                new ItemStack(Items.GOLD_INGOT, 48), new ItemStack(Items.DIAMOND, 24), new ItemStack(Items.EMERALD, 12),
                new ItemStack(Items.REDSTONE, FULL_STACK), new ItemStack(Items.LAPIS_LAZULI, 32),
                new ItemStack(Items.COAL, FULL_STACK), new ItemStack(Items.QUARTZ, 40),
                new ItemStack(Items.RAW_IRON, 48), new ItemStack(Items.RAW_COPPER, 32), new ItemStack(Items.RAW_GOLD, 16),
                new ItemStack(Items.IRON_BLOCK, 16), new ItemStack(Items.GOLD_BLOCK, 8),
                new ItemStack(Items.DIAMOND_BLOCK, 4), new ItemStack(Items.OAK_LOG, FULL_STACK),
                new ItemStack(Items.OAK_PLANKS, FULL_STACK), new ItemStack(Items.COBBLESTONE, FULL_STACK),
                new ItemStack(Items.ANDESITE, FULL_STACK), new ItemStack(Items.GLASS, 32),
                new ItemStack(Items.BREAD, 32), new ItemStack(Items.COOKED_BEEF, 24), new ItemStack(Items.CARROT, 40),
                new ItemStack(Items.GOLDEN_APPLE, 5), pickaxe,
                // The ingredients of the two production patterns (coal is already above).
                AllItems.ANDESITE_ALLOY.asStack().copyWithCount(32), new ItemStack(Items.GUNPOWDER, 24),
                new ItemStack(Items.BLAZE_POWDER, 24));
    }

    // --- the sawmill loop -------------------------------------------------------------------------------------------

    /**
     * Production station, two hoppers, the mechanical saw and its motor. The saw stands face up, which is the only
     * orientation that processes items ({@code SawBlockEntity#canProcess}); its rotation axis is then the aisle axis,
     * so its motor sits in line with it and never collides with the hoppers.
     */
    private static void buildSawmill(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        Direction outward = layout.sideDirection(SAW_STATION.side());

        level.setBlockAndUpdate(layout.rackPos(SAW_STATION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, outward.getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(SAW_RETURN), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, outward.getOpposite()));
        // Below the station: pulls what the crane delivered out of it and pushes it sideways onto the feed belt.
        level.setBlockAndUpdate(layout.rackPos(SAW_DRAIN),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, outward));

        // The feed belt runs one block outside the rack wall, towards the dock, and hands the alloy to the saw at its
        // end. A belt accepts a hopper's push from the side as long as it is not running back into it.
        BlockPos beltStart = sawBeltStartPos(layout);
        BlockPos beltEnd = sawBeltEndPos(layout);
        BeltConnectorItem.createBelts(level, beltStart, beltEnd);
        if (!AllBlocks.BELT.has(level.getBlockState(beltStart)) || !AllBlocks.BELT.has(level.getBlockState(beltEnd)))
            throw new VisualTestException("the sawmill's feed belt was not created between " + beltStart + " and "
                    + beltEnd);
        level.setBlockAndUpdate(sawBeltMotorPos(layout), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, outward.getOpposite()));

        BlockPos saw = sawPos(layout);
        level.setBlockAndUpdate(saw, AllBlocks.MECHANICAL_SAW.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        outward.getAxis() == Direction.Axis.Z));
        // Without this the saw is not a shaft machine at all: stonecutting is allowed on a saw by default, so andesite
        // alloy has five candidate recipes and the saw cycles through them, cutting ladders four runs out of five
        // (class comment). The filter is exactly what a player drops into the saw's filter slot.
        setSawRecipeFilter(level, saw, sawProduct());
        level.setBlockAndUpdate(sawMotorPos(layout),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, AISLE));
    }

    /** The mechanical saw: one block outside the rack wall, beside the warehouse input it hands its shafts to. */
    private static BlockPos sawPos(AisleLayout layout) {
        return layout.rackPos(SAW_RETURN).relative(layout.sideDirection(SAW_RETURN.side()));
    }

    private static BlockPos sawMotorPos(AisleLayout layout) {
        return sawPos(layout).relative(AISLE.getOpposite());
    }

    /** First block of the saw's feed belt, one block outside the rack wall. */
    private static BlockPos sawBeltStartPos(AisleLayout layout) {
        return layout.rackPos(SAW_BELT_START).relative(layout.sideDirection(SAW_BELT_START.side()));
    }

    /** Last block of the saw's feed belt; the block beyond it, in the belt's direction, is the saw. */
    private static BlockPos sawBeltEndPos(AisleLayout layout) {
        return layout.rackPos(SAW_BELT_END).relative(layout.sideDirection(SAW_BELT_END.side()));
    }

    /** A belt running along the aisle turns on the axis across it, so its motor sits beside the belt's first pulley. */
    private static BlockPos sawBeltMotorPos(AisleLayout layout) {
        return sawBeltStartPos(layout).relative(layout.sideDirection(SAW_BELT_START.side()));
    }

    // --- the mechanical crafter loop ---------------------------------------------------------------------------------

    /**
     * Production station, the hopper that drops onto the belt, the belt and its motor, three filtered brass belt
     * funnels, the three mechanical crafters they feed and the crafters' motor.
     * <p>
     * The crafters face along the aisle, so their rotation axis is the aisle axis (their motor sits in line with them)
     * and the directions they may point at are the two perpendicular horizontal ones. Every one of them points back
     * towards the aisle, so the chain runs inwards and the last crafter inserts into the warehouse input.
     */
    private static void buildCrafterLoop(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        Direction outward = layout.sideDirection(CRAFTER_RETURN.side());
        Direction inward = outward.getOpposite();

        level.setBlockAndUpdate(layout.rackPos(CRAFTER_RETURN), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward));
        level.setBlockAndUpdate(layout.rackPos(CRAFTER_STATION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, inward));
        level.setBlockAndUpdate(layout.rackPos(CRAFTER_DRAIN),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));

        BlockPos beltStart = layout.rackPos(CRAFTER_BELT_START);
        BlockPos beltEnd = crafterBeltEndPos(layout);
        BeltConnectorItem.createBelts(level, beltStart, beltEnd);
        if (!AllBlocks.BELT.has(level.getBlockState(beltStart)) || !AllBlocks.BELT.has(level.getBlockState(beltEnd)))
            throw new VisualTestException("the crafter loop's belt was not created between " + beltStart + " and "
                    + beltEnd);
        // A safety net that must stay empty: with the buffer hoppers below, no ingredient should ever reach the end of
        // the belt. A chest catches whatever does, because a belt end that is neither an inventory nor a solid face
        // *ejects* its items as entities (`BeltInventory#resolveEnding`), and the run asserts the chest is empty.
        level.setBlockAndUpdate(crafterBeltCatchPos(layout),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward));
        level.setBlockAndUpdate(beltMotorPos(layout), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE));

        for (int i = 0; i < CRAFTERS; i++) {
            BlockPos crafter = crafterPos(layout, i);
            level.setBlockAndUpdate(crafter, crafterState(AISLE, inward));
            // The buffer that makes an order of more than one run work: a crafter refuses every insertion while its
            // group is working, so without this hopper the second set of ingredients rides past all three funnels and
            // is lost at the end of the belt (class comment). The hopper faces its crafter and holds until it is idle.
            level.setBlockAndUpdate(crafterFeedHopperPos(layout, i),
                    Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, AISLE.getOpposite()));
            // Directly above the belt, facing away from its crafter, so the block it hands items to is that hopper.
            BlockPos funnel = crafterFunnelPos(layout, i);
            level.setBlockAndUpdate(funnel, AllBlocks.BRASS_BELT_FUNNEL.getDefaultState()
                    .setValue(BeltFunnelBlock.HORIZONTAL_FACING, AISLE)
                    .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.Shape.PULLING));
            setFunnelFilter(level, funnel, CRAFTER_INGREDIENTS.get(i));
        }
        // A mechanical crafter *is* a cogwheel, and `KineticBlock#hasShaftTowards` is false for it, so a motor's shaft
        // can never drive one directly. What drives it is a cogwheel meshing with it: a small cog adjacent
        // **perpendicular** to the crafter's rotation axis and turning on the same axis (RotationPropagator's
        // "Gear <-> Gear" case). The motor then drives that cog along its own axis, which a cog does accept
        // (`AbstractShaftBlock#hasShaftTowards`). This is exactly how a player powers a row of crafters.
        level.setBlockAndUpdate(crafterCogPos(layout),
                AllBlocks.COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(crafterMotorPos(layout),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, AISLE));
    }

    /**
     * The belt's motor sits on the belt's rotation axis, which for a belt running away from the aisle is the aisle
     * axis — on the <b>dock</b> side of the belt, because the aisle position beyond the belt lane is already the first
     * storage location.
     */
    private static BlockPos beltMotorPos(AisleLayout layout) {
        return layout.rackPos(CRAFTER_BELT_START).relative(AISLE.getOpposite());
    }

    /** Last block of the crafter loop's belt. */
    private static BlockPos crafterBeltEndPos(AisleLayout layout) {
        return layout.rackPos(CRAFTER_BELT_START).relative(layout.sideDirection(CRAFTER_BELT_START.side()),
                CRAFTER_BELT_LENGTH);
    }

    /** The chest beyond the end of the crafter loop's belt; nothing may ever arrive in it. */
    private static BlockPos crafterBeltCatchPos(AisleLayout layout) {
        return crafterBeltEndPos(layout).relative(layout.sideDirection(CRAFTER_BELT_START.side()));
    }

    /** The hopper that buffers one ingredient between its belt funnel and its crafter (class comment). */
    private static BlockPos crafterFeedHopperPos(AisleLayout layout, int index) {
        return crafterPos(layout, index).relative(AISLE);
    }

    /** The filtered belt funnel of a crafter: one position further along the aisle, directly above the belt. */
    private static BlockPos crafterFunnelPos(AisleLayout layout, int index) {
        return crafterPos(layout, index).relative(AISLE, 2);
    }

    /** The cogwheel that drives the crafter chain: directly above the crafter nearest the aisle, on the aisle axis. */
    private static BlockPos crafterCogPos(AisleLayout layout) {
        return crafterPos(layout, 0).above();
    }

    /** The crafters' motor turns their cogwheel along the aisle axis, in line with it. */
    private static BlockPos crafterMotorPos(AisleLayout layout) {
        return crafterCogPos(layout).relative(AISLE.getOpposite());
    }

    /** Crafter {@code index}: the chain runs from the far one back towards the aisle, so index 0 is the last one. */
    private static BlockPos crafterPos(AisleLayout layout, int index) {
        return layout.rackPos(CRAFTER_RETURN).relative(layout.sideDirection(CRAFTER_RETURN.side()), index + 1);
    }

    /**
     * The crafter block state whose arrow really points at {@code target}. {@code Pointing} is relative to the block's
     * own face, so the mapping is looked up through Create's own {@code getTargetDirection} instead of being re-derived
     * here — a wrong arrow would silently break the chain rather than fail.
     */
    private static BlockState crafterState(Direction facing, Direction target) {
        for (Pointing pointing : Pointing.values()) {
            BlockState state = AllBlocks.MECHANICAL_CRAFTER.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing)
                    .setValue(MechanicalCrafterBlock.POINTING, pointing);
            if (MechanicalCrafterBlock.getTargetDirection(state) == target)
                return state;
        }
        throw new VisualTestException("no crafter pointing makes a crafter facing " + facing + " target " + target);
    }

    /** Gives a brass belt funnel the filter that decides which ingredient it takes off the belt. */
    private static void setFunnelFilter(ServerLevel level, BlockPos pos, ItemKey key) {
        FunnelBlockEntity funnel = AllBlockEntityTypes.FUNNEL.getNullable(level, pos);
        if (funnel == null)
            throw new VisualTestException("no funnel block entity at " + pos);
        setFilter(funnel, pos, key, "the funnel");
    }

    /**
     * Gives the mechanical saw the recipe filter that picks one of its candidate recipes. Without it the saw cycles
     * through the cutting recipe <b>and</b> the four stonecutting recipes of andesite alloy (class comment).
     */
    private static void setSawRecipeFilter(ServerLevel level, BlockPos pos, ItemKey key) {
        SawBlockEntity saw = AllBlockEntityTypes.SAW.getNullable(level, pos);
        if (saw == null)
            throw new VisualTestException("no mechanical saw at " + pos);
        setFilter(saw, pos, key, "the mechanical saw");
    }

    /**
     * Sets the {@link FilteringBehaviour} of a Create block entity, exactly as a player's click on its filter slot
     * does. The behaviour is reached through {@link BlockEntityBehaviour#get} because Create keeps the field private.
     */
    private static void setFilter(BlockEntity be, BlockPos pos, ItemKey key, String what) {
        FilteringBehaviour filtering = BlockEntityBehaviour.get(be, FilteringBehaviour.TYPE);
        if (filtering == null)
            throw new VisualTestException(what + " at " + pos + " has no filtering behaviour");
        if (!filtering.setFilter(key.toStack()))
            throw new VisualTestException(what + " at " + pos + " refused the filter " + key);
    }

    // --- patterns, power and aim ---------------------------------------------------------------------------------------

    /** Writes both production patterns into their stations, exactly as a player would in the pattern screen. */
    private static void writePatterns(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());

        WarehouseProductionBlockEntity saw = productionAt(level, layout, SAW_STATION);
        if (!saw.setPatternEntry(0, 0, sawIngredient(), 1)
                || !saw.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, sawProduct(), SAW_PRODUCT_PER_RUN))
            throw new VisualTestException("the sawmill pattern could not be written");

        WarehouseProductionBlockEntity crafter = productionAt(level, layout, CRAFTER_STATION);
        for (int cell = 0; cell < CRAFTER_INGREDIENTS.size(); cell++) {
            if (!crafter.setPatternEntry(0, cell, CRAFTER_INGREDIENTS.get(cell), 1))
                throw new VisualTestException("crafter pattern cell " + cell + " could not be written");
        }
        if (!crafter.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, CRAFTER_PRODUCT, CRAFTER_PRODUCT_PER_RUN))
            throw new VisualTestException("the crafter pattern's result could not be written");
        LOGGER.info(PREFIX + "showcase: patterns written, {} ingredient cells at the crafter station",
                CRAFTER_INGREDIENTS.size());
    }

    /** Runs the three machine motors of the production loops up to {@value #MACHINE_RPM} RPM. */
    private static void startMachineMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        for (BlockPos motor : List.of(sawMotorPos(layout), sawBeltMotorPos(layout), beltMotorPos(layout),
                crafterMotorPos(layout)))
            motorAt(level, motor).generatedSpeed.setValue(MACHINE_RPM);
    }

    /**
     * Every machine of both loops really turns. Nothing below can work without it, and a machine that silently stands
     * still would only show up much later as a production order that times out — so the run stops here instead, and
     * names the machine, its position, its block state and the speed its motor failed to deliver.
     */
    private static void checkMachinesPowered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        List<String> dead = new ArrayList<>();
        report(level, "saw", sawPos(layout), sawMotorPos(layout),
                AllBlockEntityTypes.SAW.getNullable(level, sawPos(layout)), dead);
        report(level, "saw belt", sawBeltStartPos(layout), sawBeltMotorPos(layout),
                AllBlockEntityTypes.BELT.getNullable(level, sawBeltStartPos(layout)), dead);
        report(level, "belt", layout.rackPos(CRAFTER_BELT_START), beltMotorPos(layout),
                AllBlockEntityTypes.BELT.getNullable(level, layout.rackPos(CRAFTER_BELT_START)), dead);
        for (int i = 0; i < CRAFTERS; i++)
            report(level, "crafter " + i, crafterPos(layout, i), crafterMotorPos(layout),
                    AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level, crafterPos(layout, i)), dead);
        if (!dead.isEmpty())
            throw new VisualTestException("machines of the production loops do not turn: " + String.join("; ", dead));
    }

    /** Logs one machine's speed, position and block state, and adds it to {@code dead} when it does not turn. */
    private static void report(ServerLevel level, String name, BlockPos pos, BlockPos motorPos,
            KineticBlockEntity machine, List<String> dead) {
        float motorSpeed = AllBlockEntityTypes.MOTOR.getNullable(level, motorPos) == null ? 0f
                : motorAt(level, motorPos).getGeneratedSpeed();
        float speed = machine == null ? 0f : machine.getSpeed();
        LOGGER.info(PREFIX + "showcase: {} at {} turns at {} ({}); its motor at {} generates {}", name, pos, speed,
                level.getBlockState(pos), motorPos, motorSpeed);
        if (machine == null)
            dead.add(name + " at " + pos + " is missing");
        else if (speed == 0f)
            dead.add(name + " at " + pos + " (" + level.getBlockState(pos) + ") stands still; its motor at " + motorPos
                    + " (" + level.getBlockState(motorPos) + ") generates " + motorSpeed);
    }

    /**
     * The saw hands its results to the block in its item movement direction, and that direction flips with the sign of
     * its rotation. The direction is read from the saw itself and this loop's own motor is reversed if it points the
     * wrong way, so the sawmill works whatever the kinetic network hands it.
     */
    private static void aimSaw(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        Direction wanted = layout.sideDirection(SAW_RETURN.side()).getOpposite();
        Direction actual = sawMovement(level, layout);
        LOGGER.info(PREFIX + "showcase: the saw pushes {} and must push {}", actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motorAt(level, sawMotorPos(layout));
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static boolean sawAimed(MinecraftServer server, VisualContext context) {
        AisleLayout layout = layout(context.origin());
        return sawMovement(server.overworld(), layout)
                == layout.sideDirection(SAW_RETURN.side()).getOpposite();
    }

    private static Direction sawMovement(ServerLevel level, AisleLayout layout) {
        SawBlockEntity saw = AllBlockEntityTypes.SAW.getNullable(level, sawPos(layout));
        if (saw == null)
            throw new VisualTestException("the mechanical saw of the sawmill loop is missing");
        Vec3 movement = saw.getItemMovementVec();
        return Direction.getNearest(movement.x, movement.y, movement.z);
    }

    /**
     * Both belts must carry their items the right way: the crafter loop's <b>away from the aisle</b>, under the three
     * funnels, and the sawmill's <b>towards the dock</b>, into the saw at its end. Which way a belt runs follows the
     * sign of its rotation just as the saw's output does, so each direction is read back from the belt itself and that
     * belt's own motor reversed when it is wrong.
     */
    private static void aimBelts(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        aimBelt(level, "the crafter loop's belt", layout.rackPos(CRAFTER_BELT_START), beltMotorPos(layout),
                layout.sideDirection(CRAFTER_BELT_START.side()));
        aimBelt(level, "the sawmill's feed belt", sawBeltStartPos(layout), sawBeltMotorPos(layout),
                AISLE.getOpposite());
    }

    private static void aimBelt(ServerLevel level, String name, BlockPos beltPos, BlockPos motorPos, Direction wanted) {
        Direction actual = beltMovement(level, beltPos);
        LOGGER.info(PREFIX + "showcase: {} carries {} and must carry {}", name, actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motorAt(level, motorPos);
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static boolean beltsAimed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        return beltMovement(level, layout.rackPos(CRAFTER_BELT_START))
                == layout.sideDirection(CRAFTER_BELT_START.side())
                && beltMovement(level, sawBeltStartPos(layout)) == AISLE.getOpposite();
    }

    private static Direction beltMovement(ServerLevel level, BlockPos beltPos) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, beltPos);
        if (belt == null)
            throw new VisualTestException("no belt at " + beltPos);
        return belt.getMovementFacing();
    }

    // --- labels and starter chest ----------------------------------------------------------------------------------

    private static void placeLabels(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        AisleLayout layout = layout(dock);
        Direction left = layout.sideDirection(Side.LEFT);
        Direction right = layout.sideDirection(Side.RIGHT);

        // Beside the spawn point and well outside the line from it to the warehouse, so it never blocks the first view.
        BlockPos starterChest = dock.relative(AISLE.getOpposite(), 6).relative(right, 8);
        level.setBlockAndUpdate(starterChest,
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, AISLE.getOpposite()));
        fillStarterChest(level, starterChest);

        for (Label label : List.of(
                new Label(dock.relative(right, SIGN_OFFSET), right,
                        List.of("Stacker Crane", "dock + creative", "motor below", MOTOR_RPM + " RPM")),
                new Label(dock.relative(AISLE.getOpposite()).relative(right, SIGN_OFFSET), right,
                        List.of("Warehouse", "Controller", "aisle A")),
                new Label(layout.rackPos(TERMINAL).relative(left, SIGN_OFFSET), left,
                        List.of("Warehouse", "Terminal", "right-click to", "request items")),
                new Label(layout.rackPos(INPUT).relative(right, SIGN_OFFSET), right,
                        List.of("Warehouse Input", "drop items into", "the chest above")),
                new Label(layout.rackPos(OUTPUT_HOPPER).relative(left, SIGN_OFFSET), left,
                        List.of("Warehouse Output", "flip the lever,", "hopper fills", "the chest")),
                new Label(layout.rackPos(SAW_RETURN).relative(right, SIGN_OFFSET + 2), right,
                        List.of("Sawmill loop", "order a Shaft:", "crane brings", "andesite alloy")),
                new Label(layout.rackPos(SAW_STATION.x(), 0, Side.RIGHT).relative(right, SIGN_OFFSET + 2), right,
                        List.of("Production", "Station", "a belt feeds", "the saw")),
                new Label(layout.rackPos(CRAFTER_RETURN.x(), 0, Side.LEFT).relative(left, SIGN_OFFSET + 3), left,
                        List.of("Crafter loop", "order a Fire", "Charge: 3 items", "on the belt")),
                new Label(layout.rackPos(CRAFTER_LABEL_X, 0, Side.LEFT).relative(left, SIGN_OFFSET + 3), left,
                        List.of("Production", "Station", "funnels sort,", "hoppers buffer")),
                new Label(layout.rackPos(RackPosition.of(STORAGE_FIRST_POSITION, 0, Side.RIGHT))
                        .relative(right, SIGN_OFFSET), right,
                        List.of("Warehouse", "Interface", "+ chest =", "storage location")),
                new Label(starterChest.relative(AISLE.getOpposite()), AISLE.getOpposite(),
                        List.of("Starter kit", "goggles, wrench", "and every", "Wareworks block"))))
            placeSign(level, label);
    }

    /** Everything a player needs to experiment straight away. */
    private static void fillStarterChest(ServerLevel level, BlockPos chest) {
        List<ItemStack> kit = new ArrayList<>();
        kit.add(AllItems.GOGGLES.asStack());
        kit.add(AllItems.WRENCH.asStack());
        kit.add(WareworksBlocks.STACKER_CRANE.asStack().copyWithCount(FULL_STACK));
        kit.add(WareworksBlocks.WAREHOUSE_RAIL.asStack().copyWithCount(FULL_STACK));
        kit.add(WareworksBlocks.WAREHOUSE_CONTROLLER.asStack().copyWithCount(FULL_STACK));
        kit.add(WareworksBlocks.WAREHOUSE_INTERFACE.asStack().copyWithCount(FULL_STACK));
        kit.add(WareworksBlocks.WAREHOUSE_INPUT.asStack().copyWithCount(FULL_STACK));
        kit.add(WareworksBlocks.WAREHOUSE_OUTPUT.asStack().copyWithCount(FULL_STACK));
        kit.add(WareworksBlocks.WAREHOUSE_TERMINAL.asStack().copyWithCount(FULL_STACK));
        kit.add(WareworksBlocks.WAREHOUSE_PRODUCTION.asStack().copyWithCount(FULL_STACK));
        for (ItemStack stack : kit)
            insertAll(level, chest, stack);
    }

    /**
     * A standing oak sign with up to four lines.
     * <p>
     * {@code ROTATION} is the segment a player <b>looking at</b> the sign would produce: vanilla's
     * {@code StandingSignBlock#getStateForPlacement} uses {@code convertToSegment(playerYaw + 180)}, so the front of a
     * sign with segment {@code convertToSegment(d)} faces {@code d.getOpposite()}.
     */
    private static void placeSign(ServerLevel level, Label label) {
        // A label that silently replaces a machine block is a whole class of showcase bug, and it cost one red run:
        // the crafter loop's second sign landed exactly on the chest at the end of its belt, which turned that end
        // into an ejecting one and dropped on the floor every ingredient that rode past a busy funnel.
        BlockState replaced = level.getBlockState(label.pos());
        if (!replaced.isAir())
            throw new VisualTestException("the label " + label.lines() + " at " + label.pos() + " would replace "
                    + replaced + "; labels must not be placed into the machinery");
        level.setBlockAndUpdate(label.pos(), Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(label.facing().getOpposite())));
        if (!(level.getBlockEntity(label.pos()) instanceof SignBlockEntity sign))
            throw new VisualTestException("no sign block entity at " + label.pos());
        SignText text = new SignText();
        for (int line = 0; line < label.lines().size(); line++)
            text = text.setMessage(line, Component.literal(label.lines().get(line)));
        sign.setText(text, true);
    }

    // --- readiness, request path and the proving jobs ---------------------------------------------------------------

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controllerAt(level, dock);
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == expectedStorage
                // The warehouse input plus the return input of each production loop.
                && controller.inputStations().size() == 3
                // The terminal reports LocationKind.OUTPUT as well (ADR-018), so an aisle with both counts two.
                && controller.outputStations().size() == 2 && controller.productionStations().size() == 2
                && crane.isControllerLinked() && crane.aisleLength() == RAILS;
    }

    /** Presets the output's request, so flipping the lever fetches something without any setup by the player. */
    private static void presetOutputFilter(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT
                .getNullable(server.overworld(), layout(context.origin()).rackPos(OUTPUT));
        if (output == null)
            throw new VisualTestException("the warehouse output of the showcase aisle is missing");
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output has no filtering behaviour");
        if (!filter.setFilter(new ItemStack(Items.IRON_INGOT)))
            throw new VisualTestException("the warehouse output refused the preset filter");
        filter.count = OUTPUT_REQUEST_AMOUNT; // after setFilter, which may clamp the count
    }

    /** Inserts through the input's item capability, exactly as the hopper above it does. */
    private static void feedInput(MinecraftServer server, VisualContext context) {
        BlockPos input = layout(context.origin()).rackPos(INPUT);
        for (ItemStack stack : List.of(new ItemStack(Items.IRON_INGOT, FULL_STACK),
                new ItemStack(Items.GOLD_INGOT, 32), new ItemStack(Items.REDSTONE, FULL_STACK)))
            insertAll(server.overworld(), input, stack);
    }

    /** The proving job is done: the crane is idle and empty and the input buffer has been cleared. */
    private static boolean allStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout(dock).rackPos(INPUT));
        if (crane == null || input == null)
            throw new VisualTestException("the dock or the input of the showcase aisle is missing");
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty() && input.bufferedItems().isEmpty();
    }

    /**
     * Flips the showcase's lever. {@code Block.UPDATE_ALL} notifies the neighbours, which is what makes the warehouse
     * output see the rising edge and submit the request of its preset filter.
     */
    private static void setLever(MinecraftServer server, VisualContext context, boolean powered) {
        ServerLevel level = server.overworld();
        Direction outward = layout(context.origin()).sideDirection(OUTPUT.side());
        BlockPos pos = layout(context.origin()).rackPos(OUTPUT).relative(outward);
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER))
            throw new VisualTestException("no lever beside the warehouse output at " + pos + ", found " + state);
        level.setBlock(pos, state.setValue(LeverBlock.POWERED, powered), Block.UPDATE_ALL);
    }

    /** The redstone request really arrived: the hopper below the output has filled the pull chest. */
    private static boolean outputDelivered(MinecraftServer server, VisualContext context) {
        BlockPos chest = layout(context.origin()).rackPos(OUTPUT_CHEST);
        return countAt(server.overworld(), chest, Items.IRON_INGOT) > 0;
    }

    private static boolean craneIdle(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(server.overworld(),
                context.origin());
        return crane != null && crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    // --- the production loops, proven end to end -----------------------------------------------------------------------

    /**
     * Orders the product of each loop at the terminal, exactly as a player's click does: neither item is in stock, so
     * both orders are entirely <b>produced</b>.
     */
    private static void orderProducts(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        WarehouseControllerBlockEntity controller = controllerAt(level, context.origin());
        if (controller == null)
            throw new VisualTestException("the controller of the showcase aisle is missing");
        BlockPos terminal = layout.rackPos(TERMINAL);
        order(controller, terminal, sawProduct(), SAW_ORDER);
        order(controller, terminal, CRAFTER_PRODUCT, CRAFTER_ORDER);
    }

    private static void order(WarehouseControllerBlockEntity controller, BlockPos terminal, ItemKey key, int amount) {
        if (!controller.producibleKeys().contains(key))
            throw new VisualTestException("the aisle does not offer " + key + " as producible");
        RequestResult result = controller.request(terminal, key, amount);
        if (!result.isAccepted())
            throw new VisualTestException("the order for " + amount + " x " + key + " was refused: "
                    + result.rejection().map(Enum::name).orElse("?"));
        if (result.producing() != amount)
            throw new VisualTestException("the order for " + amount + " x " + key + " only produces "
                    + result.producing());
        LOGGER.info(PREFIX + "showcase: ordered {} x {}, all of it produced", amount, key);
    }

    /** Both production orders reached {@code COMPLETE}: each machine ran and its product arrived in the warehouse. */
    private static boolean productionComplete(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseControllerBlockEntity controller = controllerAt(level, context.origin());
        if (controller == null)
            throw new VisualTestException("the controller of the showcase aisle is missing");
        // A loop can only fail by standing still somewhere, and "the order timed out" does not say where. Snapshotting
        // every inventory of both chains while the run waits turns a five-minute timeout into a readable trail.
        if (productionPolls++ % PRODUCTION_LOG_INTERVAL == 0)
            logProductionProgress(level, layout(context.origin()), controller);
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
        if (orders.size() != 2)
            throw new VisualTestException("expected two production orders, found " + orders.size());
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            if (order.state() == ProductionOrderState.TIMED_OUT || order.state() == ProductionOrderState.CANCELLED)
                throw new VisualTestException("the production order for " + order.result() + " ended as "
                        + order.state() + "; the machine of that loop did not work");
            if (order.state() != ProductionOrderState.COMPLETE)
                return false;
        }
        return true;
    }

    /** Logs both orders and the contents of every inventory of both loops, so a stalled chain names its own culprit. */
    private static void logProductionProgress(ServerLevel level, AisleLayout layout,
            WarehouseControllerBlockEntity controller) {
        for (ProductionOrder<ItemKey, RackPosition> order : controller.productionOrders())
            LOGGER.info(PREFIX + "showcase: order {} is {} (delivered {}, still owed {}, still awaited {})",
                    order.result(), order.state(), order.deliveredIngredients(), order.outstandingIngredients(),
                    order.outstandingResult());
        LOGGER.info(PREFIX + "showcase: sawmill {} | {} | {} | {} | {}",
                contentsAt(level, "station", layout.rackPos(SAW_STATION)),
                contentsAt(level, "drain", layout.rackPos(SAW_DRAIN)),
                contentsAt(level, "beltStart", sawBeltStartPos(layout)),
                contentsAt(level, "saw", sawPos(layout)),
                contentsAt(level, "input", layout.rackPos(SAW_RETURN)));
        StringBuilder crafters = new StringBuilder();
        for (int i = 0; i < CRAFTERS; i++)
            crafters.append(contentsAt(level, "hopper" + i, crafterFeedHopperPos(layout, i))).append(' ')
                    .append(contentsAt(level, "crafter" + i, crafterPos(layout, i))).append(' ');
        LOGGER.info(PREFIX + "showcase: crafter loop {} | {} | {} | {}| {} | {}",
                contentsAt(level, "station", layout.rackPos(CRAFTER_STATION)),
                contentsAt(level, "drain", layout.rackPos(CRAFTER_DRAIN)),
                contentsAt(level, "belt", layout.rackPos(CRAFTER_BELT_START)), crafters,
                contentsAt(level, "input", layout.rackPos(CRAFTER_RETURN)),
                contentsAt(level, "catch", crafterBeltCatchPos(layout)));
        // An empty chain says nothing on its own: the items may have been ejected onto the floor, or they may have gone
        // through a machine unchanged and been stored again. The aisle's own stock separates the two.
        LOGGER.info(PREFIX + "showcase: stock alloy={} shaft={} gunpowder={} blaze={} coal={} firecharge={}",
                controller.countOf(sawIngredient()), controller.countOf(sawProduct()),
                controller.countOf(CRAFTER_INGREDIENTS.get(0)), controller.countOf(CRAFTER_INGREDIENTS.get(1)),
                controller.countOf(CRAFTER_INGREDIENTS.get(2)), controller.countOf(CRAFTER_PRODUCT));
        LOGGER.info(PREFIX + "showcase: loose items in the whole scene {}", looseItems(level, layout.dock()));
    }

    /**
     * Every item entity in the built scene, which is what a rejected insertion or an ejected crafter grid looks like.
     * <p>
     * It scans the whole cleared area rather than a box around one machine: an earlier version looked only four blocks
     * around the saw, and an ingredient that vanished from every inventory was therefore indistinguishable from one
     * lying just outside that box.
     */
    private static String looseItems(ServerLevel level, BlockPos dock) {
        AABB scene = AABB.encapsulatingFullBlocks(dock.offset(-CLEAR_MARGIN, -2, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN));
        List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class, scene);
        if (entities.isEmpty())
            return "[]";
        StringBuilder items = new StringBuilder();
        for (ItemEntity entity : entities) {
            if (!items.isEmpty())
                items.append(", ");
            items.append(entity.getItem().getItem()).append(" x").append(entity.getItem().getCount());
        }
        return "[" + items + "]";
    }

    /** {@code name=[item xN, ...]} read through the item capability, or {@code name=none} where there is no inventory. */
    private static String contentsAt(ServerLevel level, String name, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return name + "=none";
        StringBuilder items = new StringBuilder();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty())
                continue;
            if (!items.isEmpty())
                items.append(", ");
            items.append(stack.getItem()).append(" x").append(stack.getCount());
        }
        return name + "=[" + items + "]";
    }

    /**
     * Neither loop lost an ingredient: nothing rode past the crafter loop's funnels into the chest at the end of its
     * belt, and nothing lies on the floor of the scene.
     * <p>
     * Both are how the crafter loop failed before, while the production orders still looked healthy: a busy crafter
     * makes its funnel leave the ingredient on the belt, and a belt end that is neither an inventory nor a solid face
     * ejects what reaches it as an item entity ({@code BeltInventory#resolveEnding}). The order then waits for a
     * result no machine will ever make. An order of a single run cannot show this, so the run checks it explicitly
     * rather than trusting that two green orders mean two working loops.
     */
    private static void loopsLostNothing(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        BlockPos catchPos = crafterBeltCatchPos(layout);
        String caught = contentsAt(level, "catch", catchPos);
        if (!caught.endsWith("=[]"))
            throw new VisualTestException("an ingredient rode past the crafter loop's funnels into the chest at "
                    + catchPos + ": " + caught + "; the buffer hoppers did not hold it back");
        String loose = looseItems(level, layout.dock());
        if (!"[]".equals(loose))
            throw new VisualTestException("the production loops left items lying in the world: " + loose);
        LOGGER.info(PREFIX + "showcase: both loops kept every ingredient — nothing caught at {}, nothing on the floor",
                catchPos);
    }

    /** The crane served the two original requests: both products lie in the terminal a player ordered them at. */
    private static boolean productsDelivered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos terminal = layout(context.origin()).rackPos(TERMINAL);
        return countAt(level, terminal, sawProduct().toStack().getItem()) >= SAW_ORDER
                && countAt(level, terminal, CRAFTER_PRODUCT.toStack().getItem()) >= CRAFTER_ORDER;
    }

    private static int countAt(ServerLevel level, BlockPos pos, Item item) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return 0;
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    // --- the player ------------------------------------------------------------------------------------------------

    /**
     * Puts the player at the spawn view and makes that the world spawn, so opening the world drops a player in front of
     * the warehouse. Flight is enabled for the camera tour only: a creative player would otherwise fall out of every
     * camera position before the shot is taken.
     */
    private static void placePlayer(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ServerPlayer player = context.serverPlayer(server);
        CameraView.Placement spawn = SPAWN.placement(context.origin(), player.getEyeHeight());
        player.teleportTo(level, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        level.setDefaultSpawnPos(BlockPos.containing(spawn.x(), spawn.y(), spawn.z()), spawn.yaw());
        setFlying(player, true);
        LOGGER.info(PREFIX + "showcase: world spawn at {} {} {} (yaw {})", spawn.x(), spawn.y(), spawn.z(), spawn.yaw());
    }

    /** Back to the spawn point, standing rather than flying, so the saved player is exactly what a visitor gets. */
    private static void landPlayer(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ServerPlayer player = context.serverPlayer(server);
        CameraView.Placement spawn = SPAWN.placement(context.origin(), player.getEyeHeight());
        player.teleportTo(level, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        setFlying(player, false);
    }

    private static void setFlying(ServerPlayer player, boolean flying) {
        player.getAbilities().flying = flying;
        player.onUpdateAbilities();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static WarehouseControllerBlockEntity controllerAt(ServerLevel level, BlockPos dock) {
        return WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level, dock.relative(AISLE.getOpposite()));
    }

    private static WarehouseProductionBlockEntity productionAt(ServerLevel level, AisleLayout layout,
            RackPosition rack) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                layout.rackPos(rack));
        if (station == null)
            throw new VisualTestException("the production station at " + rack + " is missing");
        return station;
    }

    private static CreativeMotorBlockEntity motorAt(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static void insertAll(ServerLevel level, BlockPos pos, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }
}
