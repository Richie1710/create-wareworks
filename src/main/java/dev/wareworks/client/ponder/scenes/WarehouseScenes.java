package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * Ponder scenes of the warehouse itself: storage locations, storing and retrieving.
 * <p>
 * As in {@link CraneScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen. The order of the {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys of each scene id.
 */
public final class WarehouseScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read (and to screenshot). */
    private static final int TRANSFER_TICKS = 40;
    private static final int CONTROL_TICKS = 40;
    /** Amount the retrieving scene requests; also the number shown in the output's filter. */
    private static final int REQUEST_AMOUNT = 16;

    private WarehouseScenes() {
    }

    // --- storage locations -------------------------------------------------------------------------------------------

    /** What a Warehouse Interface does: it turns an inventory into an addressable storage location. */
    public static void warehouseInterface(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_interface", "Turning Inventories into Storage Locations");

        PonderAisle aisle = PonderAisle.SMALL;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.95f);

        int firstRack = 2;
        int lastRack = 4;
        BlockPos sampleRack = aisle.rack(util, firstRack + 1, 0, Side.LEFT);
        BlockPos sampleInventory = aisle.inventory(util, firstRack + 1, 0, Side.LEFT);

        aisle.placeAisle(scene, util);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
        aisle.placeStorage(scene, util, firstRack + 1, 1, Side.LEFT);
        scene.world().setKineticSpeed(util.select().everywhere(), 0);

        scene.showBasePlate();
        scene.idle(10);

        Selection inventories = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 2,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y + 1, aisle.aisleZ() - 2);
        Selection interfaces = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 1,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y + 1, aisle.aisleZ() - 1);
        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());

        scene.world().showSection(inventories, Direction.SOUTH);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("Any inventory beside the aisle can become a storage location")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(sampleInventory, Direction.EAST));
        scene.idle(TEXT_IDLE);

        // Fading north means the interfaces move north into place, i.e. they come in from the aisle side.
        scene.world().showSection(interfaces, Direction.NORTH);
        scene.idle(FADE_IDLE);
        scene.overlay().showControls(util.vector().blockSurface(sampleRack, Direction.EAST), Pointing.DOWN,
                        CONTROL_TICKS)
                .rightClick()
                .withItem(WareworksBlocks.WAREHOUSE_INTERFACE.asStack());
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Warehouse Interface in front of it turns it into one")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(sampleRack, Direction.EAST));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Its brass port faces the inventory, its framed plate with the arm slot faces the aisle")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(sampleRack, Direction.SOUTH));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.overlay().showOutline(PonderPalette.GREEN, "address", util.select().position(sampleRack), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Together with the crane's aisle, every location gets an address: aisle letter, level and position")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(sampleRack));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().blockSurface(sampleRack, Direction.EAST), Pointing.RIGHT,
                        CONTROL_TICKS)
                .withItem(AllItems.GOGGLES.asStack());
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("Goggles show that address, the attached inventory and what it stores")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(sampleRack, Direction.EAST));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    // --- storing ---------------------------------------------------------------------------------------------------

    /** Items enter through an input station; the controller plans a job and the crane stores them. */
    public static void storing(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_storing", "Storing Items in a Warehouse");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        int inputPosition = 1;
        int firstRack = 4;
        int lastRack = 6;
        int targetPosition = 5;

        BlockPos dock = aisle.dock(util);
        BlockPos controller = aisle.controller(util);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.RIGHT);
        BlockPos target = aisle.rack(util, targetPosition, 0, Side.LEFT);

        aisle.placeAisle(scene, util);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        aisle.placeFunnelAbove(scene, input);
        for (int position = firstRack; position <= lastRack; position++) {
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        }
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ()), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y,
                aisle.aisleZ() - 2, aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2),
                Direction.DOWN);
        scene.idle(FADE_IDLE + 5);

        scene.world().showSection(util.select().fromTo(input.getX(), input.getY(), input.getZ(), input.getX(),
                input.getY() + 1, input.getZ()), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("Items enter a warehouse through a Warehouse Input")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, Direction.EAST));
        scene.idle(TEXT_IDLE);

        // Inserts through our DirectBeltInputBehaviour and flaps the funnel above the station.
        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.COPPER_INGOT, 32));
        scene.idle(10);
        scene.overlay().showText(TEXT_TICKS)
                .text("Belts, funnels, chutes and hoppers can put items in; nothing can be taken back out")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(input.above()));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("The Warehouse Controller looks for a free storage location and orders a job")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        scene.overlay().showOutline(PonderPalette.OUTPUT, "target", util.select().position(target), TEXT_TICKS);
        scene.idle(TEXT_IDLE);

        CraneScript crane = CraneScript.parkedAt(scene, dock);
        scene.overlay().showText(TEXT_TICKS)
                .text("The crane drives to the input and its grabber takes the items")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, Direction.WEST));
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.COPPER_INGOT, 32);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        scene.idle(10);

        scene.overlay().showText(TEXT_TICKS)
                .text("It carries them to the chosen location and puts them into the inventory behind it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(target, Direction.EAST));
        crane.moveTo(CranePose.at(targetPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(targetPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(target);
        crane.moveTo(CranePose.at(targetPosition, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        scene.overlay().showText(TEXT_TICKS)
                .text("While free locations are left, every item type gets one of its own")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    // --- retrieving ------------------------------------------------------------------------------------------------

    /** A filter plus a redstone pulse on an output station makes the crane fetch items. */
    public static void retrieving(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_retrieving", "Retrieving Items from a Warehouse");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        int outputPosition = 2;
        int firstRack = 4;
        int lastRack = 6;
        int sourcePosition = 5;

        BlockPos dock = aisle.dock(util);
        BlockPos output = aisle.rack(util, outputPosition, 0, Side.RIGHT);
        BlockPos lever = aisle.inventory(util, outputPosition, 0, Side.RIGHT);
        BlockPos source = aisle.rack(util, sourcePosition, 0, Side.LEFT);
        ItemStack requested = new ItemStack(Items.GOLD_INGOT);

        aisle.placeAisle(scene, util);
        aisle.placeOutput(scene, util, outputPosition, 0, Side.RIGHT);
        aisle.placeFunnelAbove(scene, output);
        scene.world().setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.SOUTH), false);
        for (int position = firstRack; position <= lastRack; position++) {
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        }
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ()), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y,
                aisle.aisleZ() - 2, aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2),
                Direction.DOWN);
        scene.idle(FADE_IDLE + 5);

        scene.world().showSection(util.select().fromTo(output.getX(), output.getY(), output.getZ(), output.getX(),
                output.getY() + 1, output.getZ()), Direction.DOWN);
        scene.world().showSection(util.select().position(lever), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("Items leave a warehouse through a Warehouse Output")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(output, Direction.EAST));
        scene.idle(TEXT_IDLE);

        // Both write the filtering behaviour's NBT; the filter item is drawn by Create's SmartBlockEntityRenderer.
        scene.world().setFilterData(util.select().position(output), WarehouseOutputBlockEntity.class, requested);
        scene.overlay().showFilterSlotInput(util.vector().blockSurface(output, Direction.UP), Direction.UP, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Click its filter slot with the item you want")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(output, Direction.UP));
        scene.idle(TEXT_IDLE);

        scene.world().modifyBlockEntityNBT(util.select().position(output), WarehouseOutputBlockEntity.class,
                nbt -> nbt.putInt("FilterAmount", REQUEST_AMOUNT));
        scene.overlay().showControls(util.vector().blockSurface(output, Direction.UP), Pointing.DOWN, CONTROL_TICKS)
                .rightClick();
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("Hold Right-Click on it to set the amount, at most one stack")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(output, Direction.UP));
        scene.idle(TEXT_IDLE);

        scene.world().toggleRedstonePower(util.select().position(lever).add(util.select().position(output)));
        scene.effects().indicateRedstone(lever);
        scene.overlay().showText(TEXT_TICKS)
                .text("Every redstone pulse asks the controller for that amount")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(lever));
        scene.idle(TEXT_IDLE);
        scene.world().toggleRedstonePower(util.select().position(lever).add(util.select().position(output)));

        CraneScript crane = CraneScript.parkedAt(scene, dock);
        scene.overlay().showOutline(PonderPalette.INPUT, "source", util.select().position(source), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The crane fetches the items from the location that holds them")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(source, Direction.EAST));
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(sourcePosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.GOLD_INGOT, REQUEST_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);
        scene.idle(10);

        scene.overlay().showText(TEXT_TICKS)
                .text("and drops them into the output, where funnels, chutes and hoppers pull them out")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(output, Direction.WEST));
        crane.moveTo(CranePose.at(outputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(outputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(outputPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);

        scene.world().flapFunnel(output.above(), true);
        scene.effects().indicateSuccess(output);
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }
}
