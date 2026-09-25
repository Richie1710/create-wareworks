package dev.wareworks.client.ponder.scenes;

import java.util.List;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
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
import net.minecraft.world.phys.Vec3;

/**
 * Ponder scenes of the warehouse itself: storage locations, storage filters, storing and retrieving.
 * <p>
 * As in {@link CraneScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen. The order of the {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys of each scene id.
 * <p>
 * <b>Which faces the viewer sees</b> (derived in {@link TerminalScenes}): Ponder's camera draws the <b>north</b> face of
 * a block on the left half of the screen and the <b>west</b> face on the right half, so south and east point away. The
 * {@link Side#LEFT} rack plane is therefore the one <i>nearest</i> the camera and its aisle face — the face that carries
 * the store filter slot — looks away from the viewer. {@link #storageFilters} consequently builds its storage row on the
 * {@link Side#RIGHT} plane, where the aisle face is north and its filter slot is readable, and leaves the left plane
 * empty so that nothing stands in front of the row.
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
    /** Amount the filter scene feeds into its input per trip, twice: enough to fill the grabber visibly. */
    private static final int STORE_AMOUNT = 32;

    private WarehouseScenes() {
    }

    /**
     * The store filter slot of a storage location as a scene vector: centred on the interface's aisle face, but
     * {@link StorageFilterValueBox#CENTER_Y_PIXELS} px above the block's bottom edge instead of at the face's centre,
     * so an arrow or a text line points at the box a player really clicks (§3.1.1, ADR-021).
     */
    private static Vec3 filterSlot(SceneBuildingUtil util, BlockPos rack, Direction aisleFace) {
        double belowCenter = (8.0 - StorageFilterValueBox.CENTER_Y_PIXELS) / 16.0;
        return util.vector().blockSurface(rack, aisleFace).subtract(0, belowCenter, 0);
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

    // --- storage filters -------------------------------------------------------------------------------------------

    /**
     * What the filter slot on a storage location's aisle face is for: it dedicates that location to an item (M8,
     * ADR-021). A scene of its own rather than a beat inside {@link #warehouseInterface}, because a text inserted into
     * an existing scene renumbers every later {@code text_n} key in both lang files.
     * <p>
     * The storage row stands on the {@link Side#RIGHT} plane (see the class comment) and the left plane stays empty, so
     * the filter slots face the viewer and nothing is drawn in front of them.
     */
    public static void storageFilters(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_filters", "Dedicating Storage Locations");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 is left empty: the parked crane stands in front of it and would hide whatever is there.
        int inputPosition = 2;
        int freePosition = 4;
        int dedicatedPosition = 5;
        int lastRack = 6;
        // The face a storage location on the right-hand rack plane turns towards the aisle, which is the only face a
        // player can use and therefore the one that carries the filter slot.
        Direction aisleFace = PonderAisle.outward(Side.RIGHT).getOpposite();

        BlockPos dock = aisle.dock(util);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.RIGHT);
        BlockPos free = aisle.rack(util, freePosition, 0, Side.RIGHT);
        BlockPos dedicated = aisle.rack(util, dedicatedPosition, 0, Side.RIGHT);
        BlockPos dedicatedInventory = aisle.inventory(util, dedicatedPosition, 0, Side.RIGHT);
        ItemStack dedication = new ItemStack(Items.IRON_INGOT);
        ItemStack rededication = new ItemStack(Items.GOLD_INGOT);

        aisle.placeAisle(scene, util);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        aisle.placeInsertingFunnelAbove(scene, input);
        for (int position = freePosition; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + freePosition, PonderAisle.FLOOR_Y,
                aisle.aisleZ() + 1, aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection inputColumn = util.select().fromTo(input.getX(), input.getY(), input.getZ(), input.getX(),
                input.getY() + 1, input.getZ());

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(inputColumn, Direction.DOWN);
        scene.idle(FADE_IDLE);

        // The two opening beats follow each other without a breath, and the dedication below is held longer than a
        // normal beat: that is what puts the visual test's 20 % moment on the click instead of between two texts.
        scene.overlay().showFilterSlotInput(filterSlot(util, free, aisleFace), aisleFace, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Every storage location has a filter slot on its aisle side")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot(util, free, aisleFace));
        scene.idle(TEXT_TICKS);

        scene.overlay().showFilterSlotInput(filterSlot(util, free, aisleFace), aisleFace, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("An empty slot accepts everything, which is how every warehouse starts")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(free, Direction.WEST));
        scene.idle(TEXT_TICKS);

        // Writes the filtering behaviour's NBT; the filter item itself is drawn by WarehouseInterfaceRenderer.
        scene.world().setFilterData(util.select().position(dedicated), WarehouseInterfaceBlockEntity.class, dedication);
        scene.overlay().showControls(filterSlot(util, dedicated, aisleFace), Pointing.UP, CONTROL_TICKS)
                .rightClick()
                .withItem(dedication);
        scene.idle(7);
        scene.overlay().showOutline(PonderPalette.GREEN, "dedicated", util.select().position(dedicated),
                TEXT_TICKS + 40);
        scene.overlay().showText(TEXT_TICKS + 40)
                .text("Click it with an item, and only that item is stored here")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot(util, dedicated, aisleFace));
        scene.idle(TEXT_IDLE + 40);

        scene.overlay().showText(TEXT_TICKS + 30)
                .text("List, attribute and package filters work the same way")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(dedicated, Direction.WEST));
        // The three Create filter items of ADR-021, shown in turn rather than named only in the text.
        for (ItemStack filterItem : List.of(AllItems.FILTER.asStack(), AllItems.ATTRIBUTE_FILTER.asStack(),
                AllItems.PACKAGE_FILTER.asStack())) {
            scene.overlay().showControls(filterSlot(util, dedicated, aisleFace), Pointing.UP, 30)
                    .rightClick()
                    .withItem(filterItem);
            scene.idle(30);
        }

        // Inserts through the input's DirectBeltInputBehaviour and flaps the funnel above it.
        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.IRON_INGOT, STORE_AMOUNT));
        scene.idle(15);
        // Outline and text both stay up for the whole trip below, which is far longer than one text beat, so the
        // location the crane drives past its nearer neighbour for stays marked while it does it.
        scene.overlay().showOutline(PonderPalette.OUTPUT, "target", util.select().position(dedicated),
                TEXT_TICKS + 150);
        scene.overlay().showText(TEXT_TICKS + 150)
                .text("Dedicated locations fill before any unfiltered one")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(dedicated));
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.IRON_INGOT, STORE_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        // Drives past the nearer, unfiltered location: that is the whole point of this beat.
        crane.moveTo(CranePose.at(dedicatedPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(dedicatedPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(dedicated);
        crane.moveTo(CranePose.at(dedicatedPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        // Parks again, so the filter beat below is not read past the crane's mast.
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        scene.world().setFilterData(util.select().position(dedicated), WarehouseInterfaceBlockEntity.class,
                rededication);
        scene.overlay().showControls(filterSlot(util, dedicated, aisleFace), Pointing.UP, CONTROL_TICKS)
                .rightClick()
                .withItem(rededication);
        scene.idle(7);
        scene.overlay().showOutline(PonderPalette.GREEN, "kept", util.select().position(dedicated)
                .add(util.select().position(dedicatedInventory)), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Changing a filter moves nothing, and what is inside can always be retrieved")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(dedicatedInventory));
        scene.idle(TEXT_IDLE);

        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.COPPER_INGOT, STORE_AMOUNT));
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "free", util.select().position(free), TEXT_TICKS + 170);
        scene.overlay().showText(TEXT_TICKS + 170)
                .text("An item that matches no filter needs a location without one")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(free));
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.COPPER_INGOT, STORE_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(freePosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(freePosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(free);
        crane.moveTo(CranePose.at(freePosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(20);

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
        aisle.placeInsertingFunnelAbove(scene, input);
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
                .text("Belts, funnels, chutes, hoppers and Mechanical Arms can put items in; nothing comes back out")
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
        // Extracting, because that is the funnel text_6 talks about: the one that really empties the output.
        aisle.placeExtractingFunnelAbove(scene, output);
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
                .text("and drops them into the output, where funnels, chutes, hoppers and Mechanical Arms pull them out")
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

        // Only the funnel sound: a vertical funnel has no flap geometry (see PonderAisle#placeInsertingFunnelAbove),
        // so the beat is carried by the outline and the success particles, not by the funnel itself.
        scene.world().flapFunnel(output.above(), true);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "pickup", util.select().position(output)
                .add(util.select().position(output.above())), TEXT_TICKS);
        scene.effects().indicateSuccess(output);
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }
}
