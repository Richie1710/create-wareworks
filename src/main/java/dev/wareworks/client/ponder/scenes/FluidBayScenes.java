package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.content.storage.TieredBay;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Ponder scene of the fluid bay (M30, issue #21).
 * <p>
 * <b>One scene, and the reason is the loop rather than the block.</b> The fluid bay's own three ways in — a bucket in
 * your hand, a pipe at the back, and the crane — are each one beat of a single story, because the thing a player
 * cannot guess is not what the block is but what happens to the <i>container</i>: a filled bucket goes to the bay, the
 * fluid stays there, and the <b>empty bucket comes back as ordinary stock</b>. Nothing in the game says that anywhere
 * else, and a player who has not seen it will look for the lava in the stock list and for the bucket in the tank. The
 * rack bay could afford three scenes ({@link RackBayScenes}) because its own block is the lesson; here the block is
 * the easy half.
 * <p>
 * It is registered on both fluid bays <b>and on the warehouse terminal</b> ({@code WareworksPonderScenes}), which is
 * where a player holding a stocked warehouse meets the one thing M30 does not do: there is no fluid row to click, and
 * the closing beats say where the fluid really goes in and out.
 * <p>
 * As in {@link CraneScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen, so everything that touches a real level goes inside an instruction. The order of the {@code .text(...)}
 * calls defines the {@code text_1 … text_n} lang keys of the scene id.
 *
 * <h2>Four things a Ponder level does not do for a fluid bay</h2>
 * <ul>
 * <li><b>The contents can simply be written</b>, with no display hook of the kind a pallet or a crane pose needs:
 * {@code FluidBayBlockEntity#fill} is a plain handler call, and the change callback behind it returns at once on
 * anything that is not a {@code ServerLevel}, so nothing is synced, no controller is told and no block state is
 * rewritten. {@code client.render.FluidBayRenderer} then draws whatever the block entity holds, which is the whole of
 * a fluid bay's readout (ADR-053).</li>
 * <li><b>The shared uprights do not follow the world.</b> {@link TieredBay#LEFT} and {@link TieredBay#RIGHT} are
 * derived by {@code BayColumn.withJoins} from a <i>neighbour update</i>, and a scene's {@code setBlock} runs none — so
 * the rack bay and the fluid bay standing side by side would each draw an upright into the other's seam. The scene
 * writes both flags itself ({@link #joinedTowardsWest}, {@link #joinedTowardsEast}), which is also the one picture that
 * says a tank and a rack are one wall (ADR-050).</li>
 * <li><b>A rack bay's fill level does not follow its contents</b> either, for {@link RackBayScenes}' own reason, so the
 * bay that receives the empty bucket is given {@link TieredBay#FILL} by hand beside the item itself.</li>
 * <li><b>Nothing drives the plumbing.</b> The pipe and the tank are a <b>diagram</b>: only the dock's own column is
 * given a kinetic speed, so no pump runs and no fluid really flows through them, and the bay's level is raised by the
 * scene in readable steps instead. A live Create pipe network would otherwise keep filling the bay through every later
 * beat, and the one beat that matters — the crane's own bucket — would land in a bay that was already full.</li>
 * </ul>
 *
 * <h2>Every caption idles at least as long as it is shown</h2>
 * {@link RackBayScenes}' rule, and this scene needs it more than that one: Ponder draws a caption until its own
 * duration runs out, so a caption shown for longer than the scene afterwards idles is still on screen when the next
 * one appears and two captions are drawn at the same anchor at once. Every pair of beats here is anchored on
 * neighbouring blocks of one rack plane, which is exactly the case where it is unmissable. {@link #TEXT_IDLE} is
 * therefore larger than {@link #TEXT_TICKS} and {@link #SHORT_IDLE} larger than {@link #SHORT_TICKS}, and the three
 * beats that ride the crane's own motion state the ticks they are covered by.
 */
public final class FluidBayScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int SHORT_TICKS = 55;
    private static final int SHORT_IDLE = 65;
    private static final int FADE_IDLE = 15;
    private static final int CONTROL_TICKS = 40;
    /** Ticks between a {@code showControls} and the caption that explains it, so the icon is seen first. */
    private static final int CONTROL_LEAD = 7;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read and to screenshot. */
    private static final int TRANSFER_TICKS = 40;
    /** Ticks each visible step is held while the pipe beat raises the level. */
    private static final int FILL_STEP_TICKS = 9;
    /** Steps the pipe beat raises the level in. */
    private static final int FILL_STEPS = 8;

    /** Y of everything standing on a base plate, as in {@link PonderAisle#FLOOR_Y}. */
    private static final int FLOOR_Y = PonderAisle.FLOOR_Y;

    /** One bucket, in millibuckets: what a hand and a crane move in one go, and what a copper bay holds 64 of. */
    private static final int ONE_BUCKET_MB = FluidBayTier.MILLIBUCKETS_PER_BUCKET;
    /** How full the plumbing beat leaves the bay: enough to read as a level rather than as a film. */
    private static final int PIPED_MB = 24 * ONE_BUCKET_MB;
    /** What the source tank behind the pipe holds, so it reads as a tank with lava in it rather than as glass. */
    private static final int SOURCE_TANK_MB = 6 * ONE_BUCKET_MB;

    private static final ItemStack FILLED_BUCKET = new ItemStack(Items.LAVA_BUCKET);
    private static final ItemStack EMPTY_BUCKET = new ItemStack(Items.BUCKET);

    private FluidBayScenes() {
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    /** Pours {@code millibuckets} of lava into the bay at {@code pos}; see the class comment for why this is enough. */
    private static void pour(CreateSceneBuilder scene, BlockPos pos, int millibuckets) {
        scene.world().modifyBlockEntity(pos, FluidBayBlockEntity.class,
                bay -> bay.fill(new FluidStack(Fluids.LAVA, millibuckets), false));
    }

    /**
     * Puts one {@code item} into the rack bay at {@code pos}, so the renderer draws the item the scene is talking
     * about rather than an anonymous carton — {@code RackBayScenes#store}'s own helper and its own argument.
     */
    private static void store(CreateSceneBuilder scene, BlockPos pos, ItemStack item) {
        scene.world().modifyBlockEntity(pos, RackBayBlockEntity.class, bay -> bay.insert(item.copyWithCount(1), false));
    }

    /**
     * A bay state facing into the rack depth whose upright on the <b>west</b> is shared with its neighbour. With
     * {@code facing} pointing south, which is what {@link Side#RIGHT} means in these scenes, west is
     * {@code facing.getClockWise()} and therefore {@link TieredBay#RIGHT}.
     */
    private static BlockState joinedTowardsWest(BlockState state) {
        return state.setValue(TieredBay.RIGHT, true);
    }

    /** {@link #joinedTowardsWest}'s mirror: the shared upright is on the <b>east</b>, which is {@link TieredBay#LEFT}. */
    private static BlockState joinedTowardsEast(BlockState state) {
        return state.setValue(TieredBay.LEFT, true);
    }

    /**
     * The store filter slot of a bay as a scene vector: centred on its aisle face, but
     * {@link StorageFilterValueBox#CENTER_Y_PIXELS} px above the block's bottom edge instead of at the face's centre,
     * so an arrow or a caption points at the box a player really clicks — {@code RackBayScenes#filterSlot} for the same
     * behaviour on the same transform.
     */
    private static Vec3 filterSlot(SceneBuildingUtil util, BlockPos bay, Direction front) {
        double belowCenter = (8.0 - StorageFilterValueBox.CENTER_Y_PIXELS) / 16.0;
        return util.vector().blockSurface(bay, front).subtract(0, belowCenter, 0);
    }

    // --- the scene --------------------------------------------------------------------------------------------------

    /**
     * How a fluid gets into a warehouse and what becomes of the container that brought it: the bay by hand, the pipe
     * that does the bulk work, and then the loop the whole milestone rests on — a filled bucket at an input, the fluid
     * left in the bay, and the <b>empty bucket shelved in an ordinary rack bay</b> as stock.
     * <p>
     * The last three beats carry the two things a fluid bay is deliberately worse at than its item sibling, and they
     * are in the scene because nothing else a player meets <i>before</i> the loss says so: a <b>funnel cannot fill
     * it</b> (§3.9, ADR-052) and <b>breaking a full one loses the fluid</b> (ADR-054, whose third of four warnings
     * this caption is).
     */
    public static void fluidsTravelInContainers(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("fluid_bay", "Fluids Travel in Containers");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 is left empty: the parked crane stands in front of it and would hide whatever is there. The rest
        // of the row sits as close to the dock as that allows and not at the far end of the plate, because Ponder draws
        // a caption up and to the right of what it points at — anchored on the far blocks of a nine-plate, a caption
        // lands on the very members it is naming (RackBayScenes#baysInAnAisle recorded the same measurement).
        int inputPosition = 2;
        int rackPosition = 3;
        int bayPosition = 4;
        int pipePosition = 5;
        int tankPosition = 6;
        Direction depth = PonderAisle.outward(Side.RIGHT);
        Direction aisleFace = depth.getOpposite();

        BlockPos input = aisle.rack(util, inputPosition, 0, Side.RIGHT);
        BlockPos rack = aisle.rack(util, rackPosition, 0, Side.RIGHT);
        BlockPos bay = aisle.rack(util, bayPosition, 0, Side.RIGHT);
        BlockPos pipe = aisle.rack(util, pipePosition, 0, Side.RIGHT);
        BlockPos tank = aisle.rack(util, tankPosition, 0, Side.RIGHT);
        BlockPos funnel = bay.above();

        Vec3 bayFront = util.vector().blockSurface(bay, aisleFace);
        Vec3 bayTop = util.vector().topOf(bay);
        Vec3 rackFront = util.vector().blockSurface(rack, aisleFace);
        Vec3 inputFront = util.vector().blockSurface(input, aisleFace);

        Selection baySelection = util.select().position(bay);
        Selection plumbing = util.select().fromTo(pipe.getX(), pipe.getY(), pipe.getZ(),
                tank.getX(), tank.getY(), tank.getZ());
        Selection funnelSelection = util.select().position(funnel);
        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), FLOOR_Y, aisle.aisleZ());
        Selection stationAndRack = util.select().fromTo(input.getX(), input.getY(), input.getZ(),
                rack.getX(), rack.getY(), rack.getZ());

        // --- the stage, all of it while the positions are still hidden ----------------------------------------------
        aisle.placeAisle(scene, util);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        scene.world().setBlock(bay, joinedTowardsWest(WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(HorizontalDirectionalBlock.FACING, depth)), false);
        scene.world().setBlock(rack, joinedTowardsEast(WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState()
                .setValue(HorizontalDirectionalBlock.FACING, depth)), false);
        // A fluid pipe between the bay and a tank of lava, with both of its connections written: a PonderLevel runs no
        // neighbour updates, so Create's own pipe would stand there as a stub with no ends (PipeScenes does the same).
        scene.world().setBlock(pipe, AllBlocks.FLUID_PIPE.getDefaultState()
                .setValue(PipeBlock.WEST, true)
                .setValue(PipeBlock.EAST, true), false);
        scene.world().setBlock(tank, AllBlocks.FLUID_TANK.getDefaultState(), false);
        scene.world().modifyBlockEntity(tank, FluidTankBlockEntity.class, be -> be.getTankInventory()
                .fill(new FluidStack(Fluids.LAVA, SOURCE_TANK_MB), FluidAction.EXECUTE));
        aisle.placeInsertingFunnelAbove(scene, bay);
        // Only the dock's own column turns: see the class comment. The motor sits under the dock, so the two together
        // are the whole drive train of this scene and the plumbing beside it stays a diagram.
        scene.world().setKineticSpeed(util.select().fromTo(aisle.dockX(), FLOOR_Y - 1, aisle.aisleZ(),
                aisle.dockX(), FLOOR_Y, aisle.aisleZ()), CraneScript.PONDER_RPM);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(baySelection, Direction.DOWN);
        scene.idle(FADE_IDLE);

        // --- 1. what the block is -----------------------------------------------------------------------------------
        scene.overlay().showText(TEXT_TICKS)
                .text("A Fluid Bay is a storage location that is a tank: one fluid, and 64 buckets of it in copper")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(bayFront);
        scene.idle(TEXT_IDLE);

        // --- 2. a bucket in hand, and what one bucket looks like in it ----------------------------------------------
        scene.overlay().showControls(bayFront, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(FILLED_BUCKET);
        scene.idle(CONTROL_LEAD);
        pour(scene, bay, ONE_BUCKET_MB);
        scene.overlay().showText(TEXT_TICKS)
                .text("A bucket in your hand works both ways, and the level you see is the whole readout")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(bayFront);
        scene.idle(TEXT_IDLE);

        // --- 3. the pipe does the bulk work -------------------------------------------------------------------------
        scene.world().showSection(plumbing, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("Create's pipes reach every face but the one towards the aisle, and a pump fills a whole bay")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(pipe));
        for (int step = 0; step < FILL_STEPS; step++) {
            pour(scene, bay, (PIPED_MB - ONE_BUCKET_MB) / FILL_STEPS);
            scene.idle(FILL_STEP_TICKS);
        }
        scene.idle(TEXT_IDLE - FILL_STEPS * FILL_STEP_TICKS);

        // --- 4. the aisle arrives -----------------------------------------------------------------------------------
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(stationAndRack, Direction.NORTH);
        scene.idle(FADE_IDLE + 5);
        scene.overlay().showFilterSlotInput(filterSlot(util, bay, aisleFace), aisleFace, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("In a warehouse aisle it is an ordinary storage location with its own address and priority")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot(util, bay, aisleFace));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(SHORT_TICKS)
                .text("Its filter is a fluid: click the slot with a filled bucket and that fluid belongs here")
                .placeNearTarget()
                .pointAt(bayTop);
        scene.idle(SHORT_IDLE);

        // --- 5. the loop: a filled bucket in, an empty one back out -------------------------------------------------
        scene.overlay().showControls(inputFront, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(FILLED_BUCKET);
        scene.idle(CONTROL_LEAD);
        scene.world().createItemOnBeltLike(input, Direction.UP, FILLED_BUCKET.copy());
        scene.idle(10);
        scene.overlay().showText(TEXT_TICKS)
                .text("Put a bucket of lava into a warehouse input and the crane carries the whole container to it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(inputFront);
        scene.idle(TEXT_IDLE - 17);

        CraneScript crane = CraneScript.parkedAt(scene, aisle.dock(util));
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.LAVA_BUCKET, 1);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(bayPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(bayPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);

        // The caption is shown for TEXT_TICKS and covered by the two dwells and the retraction below, which together
        // are longer than that: the class comment's rule, stated where it is paid.
        scene.overlay().showText(TEXT_TICKS)
                .text("The bay drains the whole bucket, and the crane is left holding the empty one")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(bayFront);
        // The swap itself, as two icons at one anchor: the grabber is at the far end of an extended arm, behind the
        // mast from the only camera a scene has, so the one thing this whole milestone is about would otherwise be
        // carried by the caption alone. A filled bucket over the bay, then an empty one over the same spot.
        scene.overlay().showControls(bayTop, Pointing.DOWN, TRANSFER_TICKS).withItem(FILLED_BUCKET);
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        pour(scene, bay, ONE_BUCKET_MB);
        crane.hold(Items.BUCKET, 1);
        scene.effects().indicateSuccess(bay);
        scene.overlay().showControls(bayTop, Pointing.DOWN, TRANSFER_TICKS).withItem(EMPTY_BUCKET);
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(bayPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);

        crane.moveTo(CranePose.at(rackPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(rackPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        scene.overlay().showOutline(PonderPalette.GREEN, "stock", util.select().position(rack), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The empty bucket is then shelved as ordinary stock, in a rack bay or a chest")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(rackFront);
        scene.overlay().showControls(util.vector().topOf(rack), Pointing.DOWN, TRANSFER_TICKS).withItem(EMPTY_BUCKET);
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.release();
        scene.world().modifyBlock(rack, state -> state.setValue(TieredBay.FILL, 1), false);
        store(scene, rack, EMPTY_BUCKET);
        scene.effects().indicateSuccess(rack);
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(rackPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.RIGHT), CranePhase.IDLE);
        scene.idle(10);

        scene.overlay().showText(SHORT_TICKS)
                .text("So the warehouse holds lava as a fluid and the bucket as stock, and counts both")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(aisle.controller(util)));
        scene.idle(SHORT_IDLE);

        // --- 6. the funnel that cannot ------------------------------------------------------------------------------
        scene.world().showSection(funnelSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.RED, "no funnel", funnelSelection, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Funnel, a Chute, a Belt or a Hopper cannot fill one: it would keep your empty bucket")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(funnel));
        scene.idle(TEXT_IDLE);
        scene.world().hideSection(funnelSelection, Direction.UP);
        scene.idle(FADE_IDLE);

        // --- 7. and breaking it loses the fluid ---------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.RED, "the loss", baySelection, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("And breaking a bay loses whatever is in it, as breaking any tank does: pump it out first")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(bayFront);
        scene.idle(TEXT_IDLE);

        scene.world().destroyBlock(bay);
        scene.idle(FADE_IDLE);
        scene.world().setBlock(bay, joinedTowardsWest(WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(HorizontalDirectionalBlock.FACING, depth)), false);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("The empty bay comes back, and the goggles and the first hit warn you while it holds anything")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(bayFront);
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }
}
