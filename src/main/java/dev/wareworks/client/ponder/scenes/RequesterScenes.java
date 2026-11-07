package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.requester.SchematicannonRequesterBlock;
import dev.wareworks.content.requester.SchematicannonRequesterBlockEntity;
import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ponder scene of the Schematicannon Requester (issue #45, ADR-080): a Schematicannon fed from the warehouse.
 * <p>
 * Three steps of the setup are not obvious, and the tooltip has no room for them, so the scene is built around them:
 * the remote is linked at a terminal and then put into the requester, a cannon placed by hand skips missing blocks
 * until that option is turned off, and the way from the terminal's buffer to the cannon's chest is the player's own
 * funnel and belt. The rest is the loop: the cannon misses an item, the lamp lights, the crane delivers into the
 * buffer, the belt carries it to the chest and the cannon prints; and the red lamp when the warehouse has none.
 * <p>
 * <b>The stage.</b> The shared aisle ({@link PonderAisle#WIDE}) with the stone on the {@link Side#RIGHT} rack plane and
 * the terminal on the {@link Side#LEFT} one, whose back row is the one nearest Ponder's camera (north and west faces,
 * see {@link TerminalScenes}). The player's route leaves the terminal's back face: an andesite belt funnel on its back
 * puts the buffer onto the start of a belt sunk into the base plate along the rack wall, and an andesite belt funnel at
 * the belt's end, facing back along it, hands everything into the chest. That is the build of the {@code requester}
 * visual scenario turned along the wall, so its middle segment, with the stone riding it, is not under a funnel. The
 * cannon stands west of the chest and the requester north of the cannon, facing south, so its back face with the
 * remote's cradle and the lamp is the north face the camera reads, and the belt stays clear of both.
 * <p>
 * A {@code PonderLevel} runs no server, so nothing here happens by itself: the lamp is the block state the requester
 * would set, the missing item is an item icon over the cannon, the stone rides the belt as Ponder's belt items, and the
 * cannon's shots are its own client-side {@code flyingBlocks} with the printed blocks set where they land. The belt
 * is built the way {@code BeltBlock#initBelt} would link it, because that only runs on a server. Storyboards stay
 * level-free (they also run during lang datagen), and the order of the {@code .text(...)} calls defines the
 * {@code text_n} keys.
 */
public final class RequesterScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers. */
    private static final int TRANSFER_TICKS = 30;
    private static final int CONTROL_TICKS = 40;
    private static final int CLICK_LEAD = 7;
    /** One stack: what the requester orders when the schematic needs at least that much. */
    private static final int ORDER_AMOUNT = 64;
    /** Stones the scene shows riding the belt; an andesite funnel hands the belt one item at a time. */
    private static final int BELT_ITEMS = 3;
    /** The belt's segments: one under each funnel and one between them, where the stone is seen riding. */
    private static final int BELT_LENGTH = 3;
    /**
     * Ticks between two stones going onto the belt. A belt runs {@code rpm / 480} blocks a tick, so at
     * {@link CraneScript#PONDER_RPM} a stone needs 30 ticks from the middle of the first segment to the funnel at the
     * end, two steps: each stone is taken off two steps after it went on, while the next is still a block behind it.
     */
    private static final int BELT_STEP_TICKS = 16;
    private static final int BELT_STEPS_TO_FUNNEL = 2;
    /** How long the crane's trip from the stone to the terminal and back to its parking place takes, about. */
    private static final int CRANE_TRIP_TICKS = 150;
    /** {@code LaunchedItem#ticksForDistance} for the shots of this stage: never below 10 ticks. */
    private static final int SHOT_TICKS = 10;
    private static final int SHOT_GAP_TICKS = 6;

    /** Rack position of the terminal on the {@link Side#LEFT} plane, and the stone's on the {@link Side#RIGHT} one. */
    private static final int TERMINAL_POSITION = 4;
    private static final int STONE_POSITION = 6;
    private static final int FIRST_RACK = 5;
    private static final int LAST_RACK = 6;
    /**
     * The terminal's intake port faces the aisle (south on the left plane) and its screen the west, the face the camera
     * reads; the north face, towards the camera, is where the player's funnel sits.
     */
    private static final TerminalDisplaySide SCREEN_SIDE = TerminalDisplaySide.RIGHT;
    private static final Direction SCREEN = Direction.WEST;
    /** The way the player's belt runs: along the rack wall, from behind the terminal towards the chest. */
    private static final Direction BELT_MOVEMENT = Direction.WEST;
    /** The requester's back face, where the remote stands and the lamp is (it faces the cannon to its south). */
    private static final Direction BACK = Direction.NORTH;

    private RequesterScenes() {
    }

    /** Ordering what a Schematicannon is missing from the warehouse (issue #45). */
    public static void ordering(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("schematicannon_requester", "Printing from the Warehouse");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        BlockPos dock = aisle.dock(util);
        BlockPos terminal = aisle.rack(util, TERMINAL_POSITION, 0, Side.LEFT);
        BlockPos stone = aisle.rack(util, STONE_POSITION, 0, Side.RIGHT);
        // The belt lies in the base plate behind the terminal, so its funnels stand level with the terminal, and its
        // motor turns the first pulley from the front, set into the plate as the dock's motor is.
        Direction behind = PonderAisle.outward(Side.LEFT);
        BlockPos outFunnel = terminal.relative(behind);
        BlockPos beltStart = outFunnel.below();
        BlockPos beltEnd = beltStart.relative(BELT_MOVEMENT, BELT_LENGTH - 1);
        BlockPos beltMotor = beltStart.relative(behind);
        BlockPos inFunnel = beltEnd.above();
        BlockPos chest = inFunnel.relative(BELT_MOVEMENT);
        BlockPos cannon = chest.west();
        BlockPos requester = cannon.relative(BACK);
        // A 2 x 2 stone floor the cannon prints, in the free corner in front of the terminal.
        BlockPos siteFrom = util.grid().at(terminal.getX() + 1, PonderAisle.FLOOR_Y, 0);
        BlockPos siteTo = siteFrom.offset(1, 0, 1);
        BlockPos[] siteBlocks = {siteFrom, siteFrom.south(), siteFrom.east(), siteTo};

        aisle.placeAisle(scene, util);
        for (int position = FIRST_RACK; position <= LAST_RACK; position++)
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        aisle.placeTerminal(scene, util, TERMINAL_POSITION, 0, Side.LEFT, SCREEN_SIDE);
        placeBelt(scene, beltStart, BELT_MOVEMENT, BELT_LENGTH);
        scene.world().setBlock(beltMotor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, behind.getOpposite()), false);
        // The first funnel faces away from the terminal it is attached to, across the belt: an extracting funnel
        // across a belt is the pushing shape. The last one faces back along the belt, attached to the chest after it.
        scene.world().setBlock(outFunnel, beltFunnel(behind, BeltFunnelBlock.Shape.PUSHING), false);
        scene.world().setBlock(inFunnel, beltFunnel(BELT_MOVEMENT.getOpposite(), BeltFunnelBlock.Shape.RETRACTED),
                false);
        scene.world().setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH),
                false);
        scene.world().setBlock(cannon, AllBlocks.SCHEMATICANNON.getDefaultState(), false);
        scene.world().setBlock(requester, WareworksBlocks.SCHEMATICANNON_REQUESTER.getDefaultState()
                .setValue(SchematicannonRequesterBlock.FACING, BACK.getOpposite()), false);
        // A printer with no schematic loaded rests at its default yaw; this points the barrel at the floor it prints,
        // with the renderer's own formula for a real target (SchematicannonRenderer#getCannonAngles).
        double towardsSiteX = (siteFrom.getX() + siteTo.getX() + 1) / 2.0 - (cannon.getX() + 0.5);
        double towardsSiteZ = (siteFrom.getZ() + siteTo.getZ() + 1) / 2.0 - (cannon.getZ() + 0.5);
        float siteYaw = (float) Math.toDegrees(Math.atan2(towardsSiteX, towardsSiteZ));
        scene.world().modifyBlockEntity(cannon, SchematicannonBlockEntity.class, be -> be.defaultYaw = siteYaw);
        // A belt along X moves west at a positive speed (BeltBlockEntity#getMovementFacing).
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + FIRST_RACK, PonderAisle.FLOOR_Y,
                aisle.aisleZ() + 1, aisle.dockX() + LAST_RACK, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection printer = util.select().position(cannon).add(util.select().position(chest));
        // Air until the cannon prints into it; shown now, so every block set there later is drawn at once.
        Selection site = util.select().fromTo(siteFrom, siteTo);
        Selection funnels = util.select().position(outFunnel).add(util.select().position(inFunnel));
        Selection requesterSelection = util.select().position(requester);
        Vec3 back = util.vector().blockSurface(requester, BACK);
        ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(printer, Direction.DOWN);
        scene.world().showSection(site, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);

        // --- 1: the requester against the cannon --------------------------------------------------------------------
        // Fading south means it moves south into place: it comes in from the north, where the player stands.
        scene.world().showSection(requesterSelection, BACK.getOpposite());
        scene.idle(FADE_IDLE);
        scene.overlay().showControls(back, Pointing.RIGHT, CONTROL_TICKS)
                .rightClick()
                .withItem(WareworksBlocks.SCHEMATICANNON_REQUESTER.asStack());
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Place a Schematicannon Requester against a Schematicannon; it turns to face it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(back);
        scene.idle(TEXT_IDLE);

        // --- 2: the remote, linked at a terminal and put in ------------------------------------------------------------
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(util.select().position(terminal), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showControls(util.vector().blockSurface(terminal, SCREEN), Pointing.LEFT, CONTROL_TICKS)
                .rightClick()
                .whileSneaking()
                .withItem(remote);
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Sneak and right-click a Warehouse Terminal with a Warehouse Remote to link it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(back, Pointing.RIGHT, CONTROL_TICKS)
                .rightClick()
                .withItem(remote);
        scene.idle(CLICK_LEAD);
        scene.world().modifyBlockEntity(requester, SchematicannonRequesterBlockEntity.class,
                be -> be.showRemote(remote.copy()));
        // The cannon was placed by hand, so it skips missing blocks: the first thing the requester reports is red.
        lamp(scene, requester, false, true);
        scene.overlay().showText(TEXT_TICKS)
                .text("Then right-click the requester with the remote: it stays there, and the requester orders "
                        + "through that terminal")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(back);
        scene.idle(TEXT_IDLE);

        // --- 3: Skip missing Blocks ----------------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.RED, "cannon", util.select().position(cannon), TEXT_TICKS + 20);
        scene.overlay().showControls(util.vector().topOf(cannon), Pointing.DOWN, CONTROL_TICKS).rightClick();
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("A Schematicannon placed by hand skips missing blocks: turn off Skip missing Blocks in its "
                        + "screen, or it never asks for anything")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(cannon));
        scene.idle(TEXT_IDLE + 20);
        lamp(scene, requester, false, false);
        scene.effects().indicateSuccess(requester);
        scene.idle(FADE_IDLE);

        // --- 4: the cannon misses stone, the crane delivers, the belt carries ---------------------------------------
        scene.overlay().showControls(util.vector().topOf(cannon), Pointing.DOWN, TEXT_TICKS)
                .withItem(new ItemStack(Items.STONE));
        scene.idle(CLICK_LEAD);
        lamp(scene, requester, true, false);
        scene.overlay().showText(TEXT_TICKS)
                .text("When the cannon is missing an item, the lamp lights and the requester orders it at the terminal")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(back);
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.INPUT, "stock", util.select().position(stone), TEXT_TICKS);
        scene.overlay().showText(CRANE_TRIP_TICKS)
                .text("From stock only, up to a stack at a time; the crane delivers it "
                        + "into the terminal's buffer")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(terminal));
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        crane.moveTo(CranePose.at(STONE_POSITION, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(STONE_POSITION, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.STONE, ORDER_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(STONE_POSITION, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(TERMINAL_POSITION, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(TERMINAL_POSITION, 0, LocationKind.OUTPUT.armStroke(), Side.LEFT),
                CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(terminal);
        crane.moveTo(CranePose.at(TERMINAL_POSITION, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        scene.world().showSection(funnels, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "route", funnels.copy()
                .add(util.select().position(terminal)).add(util.select().position(chest)), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A funnel and a belt you build take it from the buffer to a chest beside the cannon")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(inFunnel));
        scene.idle(CLICK_LEAD);
        // Each step puts one stone on (the first funnel flaps outward) and hands the one that reached the end into the
        // chest. A funnel on a client-side belt only stops an item (BeltFunnelInteractionHandler), so the hand-over is
        // the item taken off plus the flap.
        int beltSteps = BELT_ITEMS + BELT_STEPS_TO_FUNNEL;
        for (int step = 0; step < beltSteps; step++) {
            if (step < BELT_ITEMS)
                scene.world().createItemOnBelt(beltStart, Direction.DOWN, new ItemStack(Items.STONE));
            if (step >= BELT_STEPS_TO_FUNNEL) {
                scene.world().removeItemsFromBelt(beltEnd);
                scene.world().flapFunnel(inFunnel, false);
            }
            scene.idle(BELT_STEP_TICKS);
        }
        scene.idle(Math.max(0, TEXT_IDLE - CLICK_LEAD - beltSteps * BELT_STEP_TICKS));

        scene.overlay().showText(TEXT_TICKS)
                .text("Nothing is ordered twice while it is on its way, lies in the buffer or rides the belt")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(back);
        scene.idle(CLICK_LEAD);
        // The lamp stays lit until the grace after the buffer is over; then the cannon has what it needs and prints.
        for (BlockPos target : siteBlocks)
            shoot(scene, cannon, target);
        lamp(scene, requester, false, false);
        scene.idle(Math.max(0, TEXT_IDLE - CLICK_LEAD - siteBlocks.length * (SHOT_TICKS + SHOT_GAP_TICKS)));

        // --- 5: out of stock ---------------------------------------------------------------------------------------
        scene.overlay().showControls(util.vector().topOf(cannon), Pointing.DOWN, TEXT_TICKS)
                .withItem(new ItemStack(Items.GLASS));
        scene.idle(CLICK_LEAD);
        lamp(scene, requester, false, true);
        scene.overlay().showText(TEXT_TICKS)
                .text("If the warehouse has none, the lamp turns red and it tries again later; it never starts "
                        + "production")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(back);
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(back, Pointing.RIGHT, CONTROL_TICKS)
                .withItem(AllItems.GOGGLES.asStack());
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Goggles show what it ordered and what it is waiting for")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(back);
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    /**
     * A straight belt of {@code length} segments from {@code start} towards {@code facing}, linked the way
     * {@code BeltBlock#initBelt} links a placed belt on a server: the start is the controller and index 0
     * ({@code BeltConnectorItem#createBelts} faces a belt from its start to its end).
     */
    private static void placeBelt(CreateSceneBuilder scene, BlockPos start, Direction facing, int length) {
        BlockState belt = AllBlocks.BELT.getDefaultState()
                .setValue(BeltBlock.HORIZONTAL_FACING, facing)
                .setValue(BeltBlock.SLOPE, BeltSlope.HORIZONTAL)
                .setValue(BeltBlock.CASING, false);
        for (int index = 0; index < length; index++) {
            BeltPart part = index == 0 ? BeltPart.START : index == length - 1 ? BeltPart.END : BeltPart.MIDDLE;
            int segment = index;
            BlockPos pos = start.relative(facing, index);
            scene.world().setBlock(pos, belt.setValue(BeltBlock.PART, part), false);
            scene.world().modifyBlockEntity(pos, BeltBlockEntity.class, be -> {
                be.setController(start);
                be.beltLength = length;
                be.index = segment;
            });
        }
    }

    /** An andesite belt funnel facing {@code facing} on the belt below it, in {@code shape}. */
    private static BlockState beltFunnel(Direction facing, BeltFunnelBlock.Shape shape) {
        return AllBlocks.ANDESITE_BELT_FUNNEL.getDefaultState()
                .setValue(BeltFunnelBlock.HORIZONTAL_FACING, facing)
                .setValue(BeltFunnelBlock.SHAPE, shape);
    }

    /** The cannon fires one stone at {@code target} and the block is set once it lands. */
    private static void shoot(CreateSceneBuilder scene, BlockPos cannon, BlockPos target) {
        scene.world().modifyBlockEntity(cannon, SchematicannonBlockEntity.class,
                be -> be.flyingBlocks.add(new LaunchedItem.ForBlockState(be.getBlockPos(), target,
                        new ItemStack(Items.STONE), Blocks.STONE.defaultBlockState(), null)));
        scene.idle(SHOT_TICKS);
        scene.world().setBlock(target, Blocks.STONE.defaultBlockState(), false);
        scene.idle(SHOT_GAP_TICKS);
    }

    /** The requester's lamps as its block entity would set them; red wins over lit, as on the block. */
    private static void lamp(CreateSceneBuilder scene, BlockPos requester, boolean lit, boolean refused) {
        scene.world().modifyBlock(requester, state -> state.setValue(SchematicannonRequesterBlock.LIT, lit)
                .setValue(SchematicannonRequesterBlock.REFUSED, refused), false);
    }
}
