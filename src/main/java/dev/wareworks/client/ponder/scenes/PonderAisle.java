package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.logistics.funnel.AbstractDirectionalFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.Side;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/**
 * The shared stage of the Wareworks Ponder scenes: one aisle laid out exactly by the rules of
 * {@code docs/warehouse-system.md} §1, in scene coordinates.
 * <p>
 * The aisle runs along <b>+X</b> ({@link Direction#EAST}), so {@link Side#LEFT} (counter-clockwise) is north / −Z and
 * {@link Side#RIGHT} (clockwise) is south / +Z, matching {@code AisleLayout#sideDirection}. Everything sits one block
 * above the base plate ({@link #FLOOR_Y}).
 * <pre>
 *   z = aisleZ - 2   inventories LEFT  (barrels)
 *   z = aisleZ - 1   rack plane LEFT   (interfaces, facing north = away from the aisle)
 *   z = aisleZ       controller, dock, rails            (the crane travels here)
 *   z = aisleZ + 1   rack plane RIGHT  (interfaces facing south, stations facing north)
 *   z = aisleZ + 2   inventories RIGHT (barrels)
 * </pre>
 * The schematics contain nothing but the base plate and air ({@code scripts/gen_ponder_schematics.py}), so every block
 * of a scene is placed with {@code scene.world().setBlock(...)}. That keeps block states and block entity data out of
 * the {@code .nbt} files, so those never need regenerating when a block changes.
 * <p>
 * <b>Aisle positions.</b> Scene X and the crane's aisle position differ by the dock: position {@code p} is scene
 * {@code dockX + p}, so the dock itself is position 0 and the first rail is position 1 ({@code warehouse-system.md} §2).
 * {@link #rack(SceneBuildingUtil, int, int, Side)} takes the <b>aisle position</b>, the level and the side, exactly
 * like a {@code RackPosition}, so scene code and crane poses use the same numbers.
 * <p>
 * Inventories are <b>barrels</b>, not chests: a row of chests with the same facing would merge into double chests,
 * which changes both the model and the inventory identity.
 */
public record PonderAisle(int aisleZ, int dockX, int lastRailX, int plateSize) {
    /** Y of everything standing on the base plate. */
    public static final int FLOOR_Y = 1;
    /** Aisle direction of every scene. */
    public static final Direction AISLE = Direction.EAST;
    /**
     * Mast height the scenes set on the dock (levels 0 and 1 are reachable). The default of 4 makes a mast five blocks
     * tall, which on a nine-block plate reads as a bare pole and hides the carriage and arm; two levels keep the whole
     * machine in frame while still showing that the crane lifts.
     */
    public static final int SCENE_MAST_HEIGHT = 2;
    /** NBT key of Create's {@code ScrollValueBehaviour}, which is what the dock's "Mast Height" value box stores. */
    private static final String SCROLL_VALUE = "ScrollValue";

    /** Stage of the aisle scenes: 9 x 7 x 9 volume, square base plate of 9, six rails (positions 1..6). */
    public static final PonderAisle WIDE = new PonderAisle(4, 1, 7, 9);
    /** Stage of the interface close-up: 7 x 6 x 7 volume, square base plate of 7, four rails (positions 1..4). */
    public static final PonderAisle SMALL = new PonderAisle(3, 1, 5, 7);

    /** The direction pointing from the aisle out of {@code side}: north for LEFT, south for RIGHT. */
    public static Direction outward(Side side) {
        return side == Side.LEFT ? AISLE.getCounterClockWise() : AISLE.getClockWise();
    }

    /** Number of rails, which is the aisle length L. */
    public int railCount() {
        return lastRailX - dockX;
    }

    public BlockPos controller(SceneBuildingUtil util) {
        return util.grid().at(dockX - 1, FLOOR_Y, aisleZ);
    }

    public BlockPos dock(SceneBuildingUtil util) {
        return util.grid().at(dockX, FLOOR_Y, aisleZ);
    }

    /** The creative motor in the base plate layer that drives the dock from below. */
    public BlockPos motor(SceneBuildingUtil util) {
        return util.grid().at(dockX, FLOOR_Y - 1, aisleZ);
    }

    public Selection rails(SceneBuildingUtil util) {
        return util.select().fromTo(dockX + 1, FLOOR_Y, aisleZ, lastRailX, FLOOR_Y, aisleZ);
    }

    /** The rack position {@code (position, level, side)} in scene coordinates. */
    public BlockPos rack(SceneBuildingUtil util, int position, int level, Side side) {
        return util.grid().at(dockX + position, FLOOR_Y + level, aisleZ + side.lateralOffset());
    }

    /** The inventory behind the rack position, one block further out. */
    public BlockPos inventory(SceneBuildingUtil util, int position, int level, Side side) {
        return util.grid().at(dockX + position, FLOOR_Y + level, aisleZ + 2 * side.lateralOffset());
    }

    // --- placement (all while the positions are still hidden) -------------------------------------------------------

    /** Controller behind the dock, the dock itself, the creative motor below it and the rails in front of it. */
    public void placeAisle(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().setBlock(controller(util), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, AISLE), false);
        scene.world().setBlock(dock(util), WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE), false);
        // Must happen before any pose is scripted: this round-trips the block entity's NBT, which would undo a pose.
        scene.world().modifyBlockEntityNBT(util.select().position(dock(util)), StackerCraneBlockEntity.class,
                nbt -> nbt.putInt(SCROLL_VALUE, SCENE_MAST_HEIGHT));
        scene.world().setBlock(motor(util), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP), false);
        scene.world().setBlocks(rails(util), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()), false);
    }

    /** A storage location: a warehouse interface facing away from the aisle with a barrel behind it. */
    public void placeStorage(CreateSceneBuilder scene, SceneBuildingUtil util, int position, int level, Side side) {
        scene.world().setBlock(inventory(util, position, level, side), Blocks.BARREL.defaultBlockState(), false);
        scene.world().setBlock(rack(util, position, level, side), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, outward(side)), false);
    }

    /** An input station; its opening faces the aisle. */
    public void placeInput(CreateSceneBuilder scene, SceneBuildingUtil util, int position, int level, Side side) {
        scene.world().setBlock(rack(util, position, level, side), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, outward(side).getOpposite()), false);
    }

    /** A warehouse terminal whose intake port already faces the aisle, with its screen on {@code display}. */
    public void placeTerminal(CreateSceneBuilder scene, SceneBuildingUtil util, int position, int level, Side side,
            TerminalDisplaySide display) {
        placeTerminal(scene, util, position, level, side, outward(side).getOpposite(), display);
    }

    /**
     * A warehouse terminal with an explicit intake port, for the scene that shows a misaligned port being corrected.
     * Unlike the other stations the terminal has two independent directions: {@code intake} is the face the crane
     * reaches through and {@code display} places the screen relative to it (ADR-022).
     */
    public void placeTerminal(CreateSceneBuilder scene, SceneBuildingUtil util, int position, int level, Side side,
            Direction intake, TerminalDisplaySide display) {
        scene.world().setBlock(rack(util, position, level, side), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, intake)
                .setValue(WarehouseTerminalBlock.DISPLAY, display), false);
    }

    /** A production station; its opening faces the aisle. */
    public void placeProduction(CreateSceneBuilder scene, SceneBuildingUtil util, int position, int level, Side side) {
        scene.world().setBlock(rack(util, position, level, side), WareworksBlocks.WAREHOUSE_PRODUCTION
                .getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, outward(side).getOpposite()), false);
    }

    /** An output station; its opening faces the aisle. */
    public void placeOutput(CreateSceneBuilder scene, SceneBuildingUtil util, int position, int level, Side side) {
        scene.world().setBlock(rack(util, position, level, side), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward(side).getOpposite()), false);
    }

    /**
     * An andesite funnel standing on {@code below} and putting items <b>into</b> it: the inventory a funnel is attached
     * to is {@code pos.relative(FACING.getOpposite())}, so a funnel that fills the block underneath points <b>up</b>,
     * exactly like one a player drops onto the top of a chest ({@code FunnelBlock#getStateForPlacement} turns the
     * player's look direction around the same way). It is the visible connection to the rest of a factory and the
     * funnel {@code createItemOnBeltLike} looks for, which is always {@code position.above()}.
     * <p>
     * <b>A vertical funnel has no flap.</b> {@code FunnelBlockEntity#hasFlap()} is true only while the funnel faces
     * horizontally, and both render paths leave immediately without it ({@code FunnelRenderer#renderSafe},
     * {@code FunnelVisual}), so {@code flapFunnel} on this block plays the funnel sound and moves nothing. A beat that
     * needs to be seen therefore needs an overlay or an effect of its own.
     */
    public void placeInsertingFunnelAbove(CreateSceneBuilder scene, BlockPos below) {
        scene.world().setBlock(below.above(), AllBlocks.ANDESITE_FUNNEL.getDefaultState()
                .setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP)
                .setValue(FunnelBlock.EXTRACTING, false), false);
    }

    /**
     * An andesite funnel standing on {@code below} and pulling items <b>out</b> of it: the same upward mouth as
     * {@link #placeInsertingFunnelAbove}, because both are attached to the block underneath, but in extracting mode
     * ({@code FunnelBlockEntity#determineCurrentMode}), which is the state that really empties a station.
     * <p>
     * It has no flap either (see {@link #placeInsertingFunnelAbove}). Note that an extracting funnel drops what it takes
     * as an item entity and never inserts into a neighbouring block, so it may not stand directly under a machine.
     */
    public void placeExtractingFunnelAbove(CreateSceneBuilder scene, BlockPos below) {
        scene.world().setBlock(below.above(), AllBlocks.ANDESITE_FUNNEL.getDefaultState()
                .setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP)
                .setValue(FunnelBlock.EXTRACTING, true), false);
    }
}
