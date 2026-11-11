package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.selector.RecipeSelectorBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Ponder scenes of the Recipe Selector (ADR-085, design §16): the local tier on a saw behind a production station, and
 * the Ender tier at a far factory, where the recipe travels as a slip in each package.
 * <p>
 * <b>What the scenes must not imply.</b> The selector crafts nothing and moves no item: the crane carries, the machine
 * makes, and the selector only sets the machine's recipe filter, one product at a time. A {@code PonderLevel} runs no
 * warehouse, so the filter is set with {@code setFilterData} exactly when the real selector would set it, the products
 * coming back are items put into the Warehouse Input, and a package is an item icon: no scene puts a box into a
 * Packager, writes a slip or touches Create's logistics network ({@code PackageOrderBoundaryTest}).
 * <p>
 * Storyboards stay level-free (they also run during lang datagen), and the order of the {@code .text(...)} calls
 * defines the {@code text_n} keys, which {@code de_de.json} translates one by one.
 */
public final class RecipeSelectorScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    private static final int CONTROL_TICKS = 40;
    private static final int CLICK_LEAD = 7;
    private static final int TRANSFER_TICKS = 30;
    /** The faces the camera reads: the back of the left rack wall, and the factory's west side. */
    private static final Direction FRONT = Direction.NORTH;

    private static final ItemStack STAIRS = new ItemStack(Items.DEEPSLATE_TILE_STAIRS);
    private static final ItemStack SLAB = new ItemStack(Items.DEEPSLATE_TILE_SLAB);
    private static final ItemStack WALL = new ItemStack(Items.DEEPSLATE_TILE_WALL);

    private RecipeSelectorScenes() {
    }

    /** The local tier: one saw behind a production station makes stairs, then slabs, one recipe at a time. */
    public static void local(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("recipe_selector", "Making Several Products on One Saw");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        int inputPosition = 3;
        int stationPosition = 4;
        int stonePosition = 2;
        int firstRack = 4;
        int lastRack = 6;

        BlockPos dock = aisle.dock(util);
        BlockPos controller = aisle.controller(util);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.LEFT);
        BlockPos station = aisle.rack(util, stationPosition, 0, Side.LEFT);
        // The saw stands behind the station, facing up; the selector against its side, behind the input.
        BlockPos saw = aisle.inventory(util, stationPosition, 0, Side.LEFT);
        BlockPos selector = aisle.inventory(util, inputPosition, 0, Side.LEFT);
        BlockPos mixer = saw.above(2);
        BlockPos stone = aisle.rack(util, stonePosition, 0, Side.RIGHT);
        Direction towardsSaw = Direction.EAST;

        aisle.placeAisle(scene, util);
        aisle.placeInput(scene, util, inputPosition, 0, Side.LEFT);
        aisle.placeProduction(scene, util, stationPosition, 0, Side.LEFT);
        aisle.placeStorage(scene, util, stonePosition, 0, Side.RIGHT);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        scene.world().setBlock(saw, AllBlocks.MECHANICAL_SAW.getDefaultState()
                .setValue(SawBlock.FACING, Direction.UP), false);
        scene.world().setBlock(selector, WareworksBlocks.RECIPE_SELECTOR.getDefaultState()
                .setValue(RecipeSelectorBlock.FACING, towardsSaw), false);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + stonePosition, PonderAisle.FLOOR_Y,
                aisle.aisleZ() + 1, aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection stations = util.select().position(input).add(util.select().position(station));
        Selection sawSelection = util.select().position(saw);
        Selection selectorSelection = util.select().position(selector);
        Vec3 filterSlot = util.vector().blockSurface(saw, Direction.UP);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(stations, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(sawSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("A Mechanical Saw without a filter cycles through every recipe of its input")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot);
        for (ItemStack product : new ItemStack[] {STAIRS, SLAB, WALL}) {
            scene.overlay().showControls(filterSlot, Pointing.DOWN, 20).withItem(product);
            scene.idle(25);
        }
        scene.idle(TEXT_IDLE - 75);

        scene.overlay().showControls(util.vector().topOf(station), Pointing.DOWN, CONTROL_TICKS).rightClick()
                .whileSneaking().withItem(new ItemStack(WareworksBlocks.RECIPE_SELECTOR.asItem()));
        scene.idle(CLICK_LEAD);
        scene.overlay().showOutline(PonderPalette.GREEN, "station", util.select().position(station), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Sneak-click the Production Station with a Recipe Selector to link it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(station));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(selectorSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("Then place it against the saw: the station now makes one recipe at a time")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(selector, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().topOf(input), Pointing.DOWN, CONTROL_TICKS).rightClick()
                .whileSneaking().withItem(new ItemStack(WareworksBlocks.RECIPE_SELECTOR.asItem()));
        scene.idle(CLICK_LEAD);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "input", util.select().position(input), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Sneak-click the Warehouse Input the products come back through: only it completes the orders")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(input));
        scene.idle(TEXT_IDLE);

        // Two orders: stairs first, slabs after. The filter is set before the first stone leaves the warehouse.
        scene.world().setFilterData(sawSelection, SawBlockEntity.class, STAIRS);
        scene.effects().indicateSuccess(saw);
        scene.overlay().showText(TEXT_TICKS)
                .text("For each order the selector sets the saw's recipe before the crane brings the stone")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot);
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS + 2 * TRANSFER_TICKS)
                .text("An order for slabs waits for its turn: the saw switches only when every stair is back")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        deliver(scene, crane, stonePosition, stationPosition, Items.COBBLED_DEEPSLATE, 8);
        scene.effects().indicateSuccess(station);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.world().createItemOnBeltLike(input, FRONT, STAIRS.copyWithCount(8));
        scene.effects().indicateSuccess(input);
        scene.idle(20);

        scene.world().setFilterData(sawSelection, SawBlockEntity.class, SLAB);
        scene.effects().indicateSuccess(saw);
        scene.overlay().showControls(filterSlot, Pointing.DOWN, CONTROL_TICKS).withItem(SLAB);
        scene.overlay().showText(TEXT_TICKS)
                .text("Then the selector switches the saw to slabs, and the next order runs")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot);
        scene.idle(TEXT_IDLE);

        // The quarantine's way out: an empty-hand click on the return input (no item in the hand).
        scene.overlay().showControls(util.vector().topOf(input), Pointing.DOWN, CONTROL_TICKS).rightClick();
        scene.idle(CLICK_LEAD);
        scene.overlay().showOutline(PonderPalette.RED, "input", util.select().position(input), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Anything else that comes back through the return input stays there and holds the station: click "
                        + "the input with an empty hand to take it out, then sneak-click the station")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(input));
        scene.idle(TEXT_IDLE);

        // The Basin variant: the selector goes against the Basin, never against the Mixer above it.
        scene.world().hideSection(sawSelection.add(selectorSelection), Direction.UP);
        scene.idle(FADE_IDLE + 5);
        scene.world().setBlock(saw, AllBlocks.BASIN.getDefaultState(), false);
        scene.world().setBlock(mixer, AllBlocks.MECHANICAL_MIXER.getDefaultState(), false);
        scene.world().setKineticSpeed(util.select().position(mixer), CraneScript.PONDER_RPM);
        scene.world().showSection(sawSelection.add(util.select().position(mixer)), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(selectorSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().setFilterData(sawSelection, BasinBlockEntity.class, new ItemStack(Items.LIGHT_GRAY_DYE));
        scene.overlay().showOutline(PonderPalette.GREEN, "basin", sawSelection, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("With a Mixer or a Press, place the selector against the Basin below it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(selector, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("A filter item in the machine is never overwritten, and the selector crafts nothing itself")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().topOf(saw));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    /**
     * The Ender tier: a far factory whose saw is set by the recipe slip in each package, before the Packager opens it.
     * No aisle: the warehouse's station, its Packager and its return input stand at the back right, the factory at the
     * front left, where the camera reads it.
     */
    public static void ender(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("ender_recipe_selector", "Setting a Far Factory's Recipe with Recipe Slips");
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(0.9f);

        // The warehouse side.
        BlockPos station = util.grid().at(6, 1, 6);
        BlockPos sendingPackager = util.grid().at(6, 1, 7);
        BlockPos returnInput = util.grid().at(5, 1, 6);
        // The factory: the Packager unpacks into the chest north of it, the saw beside the chest is set.
        BlockPos packager = util.grid().at(2, 1, 3);
        BlockPos chest = util.grid().at(2, 1, 2);
        BlockPos saw = util.grid().at(3, 1, 2);
        BlockPos selector = util.grid().at(1, 1, 3);

        scene.world().setBlock(station, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, Direction.NORTH), false);
        scene.world().setBlock(sendingPackager, AllBlocks.PACKAGER.getDefaultState()
                .setValue(PackagerBlock.FACING, Direction.SOUTH), false);
        scene.world().setBlock(returnInput, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, Direction.NORTH), false);
        scene.world().setBlock(packager, AllBlocks.PACKAGER.getDefaultState()
                .setValue(PackagerBlock.FACING, Direction.SOUTH), false);
        scene.world().setBlock(chest, Blocks.CHEST.defaultBlockState(), false);
        scene.world().setBlock(saw, AllBlocks.MECHANICAL_SAW.getDefaultState()
                .setValue(SawBlock.FACING, Direction.UP), false);
        scene.world().setBlock(selector, WareworksBlocks.ENDER_RECIPE_SELECTOR.getDefaultState()
                .setValue(RecipeSelectorBlock.FACING, Direction.EAST), false);
        scene.world().setKineticSpeed(util.select().position(saw), CraneScript.PONDER_RPM);

        Selection warehouse = util.select().position(station).add(util.select().position(sendingPackager))
                .add(util.select().position(returnInput));
        Selection factory = util.select().position(packager).add(util.select().position(chest))
                .add(util.select().position(saw));
        Selection sawSelection = util.select().position(saw);
        Vec3 filterSlot = util.vector().blockSurface(saw, Direction.UP);
        ItemStack enderSelector = new ItemStack(WareworksBlocks.ENDER_RECIPE_SELECTOR.asItem());
        ItemStack box = PackageStyles.getDefaultBox();

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(factory, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(warehouse, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.overlay().showControls(util.vector().topOf(saw), Pointing.DOWN, CONTROL_TICKS).rightClick()
                .whileSneaking().withItem(enderSelector);
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("At the factory, sneak-click the saw or basin with an Ender Recipe Selector")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(saw));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().topOf(station), Pointing.DOWN, CONTROL_TICKS).rightClick()
                .whileSneaking().withItem(enderSelector);
        scene.idle(CLICK_LEAD);
        scene.overlay().showOutline(PonderPalette.GREEN, "station", util.select().position(station), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("At the warehouse, sneak-click the Production Station: every package it sends now carries a "
                        + "recipe slip")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(station));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().topOf(returnInput), Pointing.DOWN, CONTROL_TICKS).rightClick()
                .whileSneaking().withItem(enderSelector);
        scene.idle(CLICK_LEAD);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "input", util.select().position(returnInput), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Then sneak-click the Warehouse Input the products come back through")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(returnInput));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().blockSurface(packager, Direction.WEST), Pointing.RIGHT,
                CONTROL_TICKS).rightClick().whileSneaking().withItem(enderSelector);
        scene.idle(CLICK_LEAD);
        scene.world().showSection(util.select().position(selector), Direction.EAST);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("Back at the factory, sneak-place it on the Packager that unpacks the station's packages")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(selector, Direction.WEST));
        scene.idle(TEXT_IDLE);

        // A box for stairs arrives: the selector sets the saw first, then the Packager opens it into the chest.
        scene.overlay().showControls(util.vector().topOf(sendingPackager), Pointing.DOWN, CONTROL_TICKS)
                .withItem(box);
        scene.idle(CONTROL_TICKS / 2);
        scene.overlay().showControls(util.vector().topOf(packager), Pointing.DOWN, CONTROL_TICKS).withItem(box);
        scene.idle(CLICK_LEAD);
        scene.world().setFilterData(sawSelection, SawBlockEntity.class, STAIRS);
        scene.effects().indicateSuccess(saw);
        scene.overlay().showText(TEXT_TICKS)
                .text("When a package arrives, the selector reads its slip and sets the saw before the box is opened")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot);
        scene.idle(TEXT_IDLE);
        scene.overlay().showControls(util.vector().topOf(chest), Pointing.DOWN, CONTROL_TICKS)
                .withItem(new ItemStack(Items.COBBLED_DEEPSLATE, 8));
        scene.idle(CONTROL_TICKS / 2);
        scene.overlay().showControls(filterSlot, Pointing.DOWN, CONTROL_TICKS).withItem(STAIRS);
        scene.idle(CONTROL_TICKS);

        scene.overlay().showOutline(PonderPalette.RED, "waiting", util.select().position(packager), TEXT_TICKS);
        scene.overlay().showControls(util.vector().topOf(packager), Pointing.DOWN, TEXT_TICKS).withItem(box);
        scene.overlay().showText(TEXT_TICKS)
                .text("A package for slabs waits in its port until the saw and the chest are empty")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(packager, Direction.WEST));
        scene.idle(TEXT_IDLE);
        scene.world().setFilterData(sawSelection, SawBlockEntity.class, SLAB);
        scene.effects().indicateSuccess(saw);
        scene.idle(20);

        scene.overlay().showText(TEXT_TICKS)
                .text("Never feed a package straight into a saw: it cuts it open. A port touching the chest bypasses "
                        + "the selector")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(chest));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    /** One trip of the crane from a rack on the right plane to the station on the left one, holding {@code count}. */
    private static void deliver(CreateSceneBuilder scene, CraneScript crane, int from, int to, Item item, int count) {
        crane.moveTo(CranePose.at(from, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(from, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(item, count);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(from, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(to, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(to, 0, LocationKind.PRODUCTION.armStroke(), Side.LEFT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(to, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
    }
}
