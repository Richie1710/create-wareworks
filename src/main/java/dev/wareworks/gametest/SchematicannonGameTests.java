package dev.wareworks.gametest;

import static dev.wareworks.gametest.CannonFixture.blockEntityAt;
import static dev.wareworks.gametest.CannonFixture.blocksOf;
import static dev.wareworks.gametest.CannonFixture.cannonFor;
import static dev.wareworks.gametest.CannonFixture.forEachSpot;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;

import dev.wareworks.Wareworks;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.gametest.CannonFixture.Site;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Create's Schematicannon prints Wareworks blocks from a schematic, taking the items from a chest beside it (issue #23).
 * <p>
 * Every test runs the real cannon end to end ({@link CannonFixture}): the blocks are placed, saved into a structure file
 * the way Schematic and Quill saves one, written where the server keeps uploaded schematics, and cleared again; the
 * cannon is handed a deployed blueprint of it, gunpowder, and a chest. A failure names the blocks still missing and what the cannon says
 * it is doing, because a cannon that skips a block says nothing more than "Searching".
 * <p>
 * The warehouse tests also keep the books: the chest is stocked with <b>exactly</b> what Create's own
 * {@link ItemRequirement} asks for, so a block printed twice runs the chest dry and stalls the cannon on "Missing
 * Item", and at the end the chest has to be empty, every block of the schematic standing and no item lying on the
 * floor. Nothing may be duplicated and nothing lost.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class SchematicannonGameTests {
    /** The small site: one block per spot of a 7 x 3 x 5 grid, the cannon and its chest in front of it. */
    private static final Site GRID = new Site(new BlockPos(0, BASE_Y, 2), new Vec3i(7, 3, 5),
            new BlockPos(0, BASE_Y, 0), new BlockPos(1, BASE_Y, 0));

    /** The warehouse site on the aisle template: the aisle at z = 3, eight rails, the cannon beyond the rail end. */
    private static final int AISLE_Z = 3;
    private static final int RAILS = 8;
    private static final Site WAREHOUSE = new Site(new BlockPos(0, BASE_Y, AISLE_Z - 2), new Vec3i(RAILS + 2, 2, 5),
            new BlockPos(13, BASE_Y, AISLE_Z), new BlockPos(14, BASE_Y, AISLE_Z));

    private SchematicannonGameTests() {
    }

    // --- every block ---------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY_7X5X7, timeoutTicks = 1600)
    public static void schematicannonPrintsEveryWareworksBlock(GameTestHelper helper) {
        Map<BlockPos, Block> targets = gridLayout();
        for (Map.Entry<BlockPos, Block> target : targets.entrySet())
            helper.setBlock(target.getKey(), target.getValue().defaultBlockState());
        SchematicannonBlockEntity cannon = cannonFor(helper, GRID, true);
        helper.succeedWhen(() -> assertPrinted(helper, cannon, targets));
    }

    /**
     * The cannon first reports the terminal missing, then the item arrives in the chest: it has to carry on and print
     * the terminal, not sit on "Searching" with the terminal still shown as missing.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = 800)
    public static void schematicannonPrintsATerminalThatArrivesLater(GameTestHelper helper) {
        BlockPos target = GRID.min().offset(2, 0, 2);
        Block terminal = WareworksBlocks.WAREHOUSE_TERMINAL.get();
        helper.setBlock(target, terminal.defaultBlockState());
        SchematicannonBlockEntity cannon = cannonFor(helper, GRID, false);
        Container chest = (Container) helper.getBlockEntity(GRID.chest());
        boolean[] added = { false };
        helper.onEachTick(() -> {
            if (!added[0] && "missingBlock".equals(cannon.statusMsg)) {
                chest.setItem(0, new ItemStack(terminal.asItem()));
                added[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(added[0], "the cannon never reported the terminal missing; it says '" + cannon.statusMsg
                    + "'");
            assertPrinted(helper, cannon, Map.of(target, terminal));
        });
    }

    /**
     * Reproduces the stall of issue #23: the cannon asks for the terminal, then a chest is put down on the terminal's own
     * spot (the second half of a large chest beside the cannon) and the terminal goes into it. Create then skips the
     * spot (a block entity stands there and "Replace block entities" is off) without forgetting the missing item, so the
     * cannon sits on "Searching" with the terminal shown for good. This pins that behaviour of Create's, so the
     * workaround in the issue (stop and start the cannon) stays true; it does not test Wareworks.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = 400)
    public static void schematicannonStallsWhenAChestStandsOnTheTerminalSpot(GameTestHelper helper) {
        BlockPos target = GRID.min().offset(2, 0, 2);
        Block terminal = WareworksBlocks.WAREHOUSE_TERMINAL.get();
        helper.setBlock(target, terminal.defaultBlockState());
        SchematicannonBlockEntity cannon = cannonFor(helper, GRID, false);
        boolean[] blocked = { false };
        helper.onEachTick(() -> {
            if (!blocked[0] && "missingBlock".equals(cannon.statusMsg)) {
                helper.setBlock(target, Blocks.CHEST);
                ((Container) helper.getBlockEntity(target)).setItem(0, new ItemStack(terminal.asItem()));
                ((Container) helper.getBlockEntity(GRID.chest())).setItem(0, new ItemStack(terminal.asItem()));
                blocked[0] = true;
            }
        });
        helper.runAfterDelay(300, () -> {
            helper.assertTrue(blocked[0], "the cannon never reported the terminal missing");
            helper.assertTrue("searching".equals(cannon.statusMsg) && cannon.missingItem != null
                    && cannon.missingItem.is(terminal.asItem()), "expected Create's stall on 'searching' with the "
                    + "terminal missing, but the cannon says '" + cannon.statusMsg + "', missing " + cannon.missingItem);
            helper.succeed();
        });
    }

    // --- a whole warehouse ---------------------------------------------------------------------------------------------

    /**
     * A working warehouse — dock, rails, controller, a chest behind an interface, rack and fluid bays side by side and
     * stacked, and every station — printed from a chest stocked with exactly the items it needs. The chest ends empty,
     * every block stands, and nothing lies on the floor.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = 2400)
    public static void schematicannonPrintsAWarehouseFromExactlyItsItems(GameTestHelper helper) {
        Map<BlockPos, Block> targets = buildWarehouse(helper);
        Map<Item, Integer> needed = requiredItems(helper, WAREHOUSE);
        SchematicannonBlockEntity cannon = cannonFor(helper, WAREHOUSE, false);
        stock(helper, WAREHOUSE, needed);
        helper.succeedWhen(() -> assertBooksBalance(helper, cannon, targets));
    }

    /**
     * The same warehouse, but the cannon is stopped half way — with blocks still in flight — and started again, which
     * makes it walk the whole schematic once more. Blocks it already printed must be left alone: a block whose state
     * changed after it was placed (a rail joining its neighbours, a bay joining the bay beside it) must not be printed
     * a second time. The chest holds exactly one run's items, so a second print stalls on "Missing Item"; and at the end
     * the chest is empty, every block stands and nothing lies on the floor.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = 3600)
    public static void schematicannonStoppedAndRestartedNeitherDuplicatesNorLoses(GameTestHelper helper) {
        Map<BlockPos, Block> targets = buildWarehouse(helper);
        Map<Item, Integer> needed = requiredItems(helper, WAREHOUSE);
        SchematicannonBlockEntity cannon = cannonFor(helper, WAREHOUSE, false);
        stock(helper, WAREHOUSE, needed);
        int[] phase = { 0 };
        helper.onEachTick(() -> {
            if (phase[0] == 0 && cannon.blocksPlaced >= targets.size() / 2 && !cannon.flyingBlocks.isEmpty()) {
                // The stop button: the printer is reset and the next start walks the schematic from the beginning.
                cannon.state = SchematicannonBlockEntity.State.STOPPED;
                cannon.statusMsg = "stopped";
                phase[0] = 1;
            } else if (phase[0] == 1 && cannon.state != SchematicannonBlockEntity.State.STOPPED) {
                phase[0] = 2; // started again already
            } else if (phase[0] == 1 && "stopped".equals(cannon.statusMsg) && cannon.flyingBlocks.isEmpty()) {
                // The play button, once everything in flight has landed.
                cannon.state = SchematicannonBlockEntity.State.RUNNING;
                cannon.statusMsg = "running";
                phase[0] = 2;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] == 2, "the cannon was never stopped and started again (phase " + phase[0]
                    + ", placed " + cannon.blocksPlaced + " of " + targets.size() + ")");
            assertBooksBalance(helper, cannon, targets);
        });
    }

    // --- building the warehouse ----------------------------------------------------------------------------------------

    /** Builds the warehouse with {@link AisleFixture} and returns every block of the site, by position. */
    private static Map<BlockPos, Block> buildWarehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(new RackPosition(0, 0, Side.LEFT));
        bay(helper, aisle, new RackPosition(1, 0, Side.LEFT), WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        bay(helper, aisle, new RackPosition(2, 0, Side.LEFT), WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        bay(helper, aisle, new RackPosition(1, 1, Side.LEFT), WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        bay(helper, aisle, new RackPosition(3, 0, Side.LEFT), WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState());
        bay(helper, aisle, new RackPosition(4, 0, Side.LEFT), WareworksBlocks.RACK_BAY_BRASS.getDefaultState());
        fluidBay(helper, aisle, new RackPosition(5, 0, Side.LEFT), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState());
        fluidBay(helper, aisle, new RackPosition(6, 0, Side.LEFT), WareworksBlocks.FLUID_BAY_BRASS.getDefaultState());
        aisle.input(new RackPosition(0, 0, Side.RIGHT));
        aisle.output(new RackPosition(1, 0, Side.RIGHT));
        aisle.terminal(new RackPosition(2, 0, Side.RIGHT));
        aisle.production(new RackPosition(3, 0, Side.RIGHT));
        aisle.stockKeeper(new RackPosition(4, 0, Side.RIGHT));
        aisle.homePoint(new RackPosition(5, 0, Side.RIGHT));
        return blocksOf(helper, WAREHOUSE);
    }

    private static void bay(GameTestHelper helper, AisleFixture aisle, RackPosition rack, BlockState bay) {
        helper.setBlock(aisle.rackPos(rack), bay.setValue(RackBayBlock.FACING, aisle.sideDirection(rack)));
    }

    private static void fluidBay(GameTestHelper helper, AisleFixture aisle, RackPosition rack, BlockState bay) {
        helper.setBlock(aisle.rackPos(rack), bay.setValue(FluidBayBlock.FACING, aisle.sideDirection(rack)));
    }

    /** What Create's cannon will ask for, item by item, summed over the site — the way its material list counts. */
    private static Map<Item, Integer> requiredItems(GameTestHelper helper, Site site) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        forEachSpot(site, pos -> {
            BlockState state = helper.getBlockState(pos);
            if (state.isAir())
                return;
            ItemRequirement requirement = ItemRequirement.of(state, blockEntityAt(helper, pos));
            helper.assertFalse(requirement.isInvalid(), "the cannon cannot print " + state + " at " + pos);
            for (ItemRequirement.StackRequirement required : requirement.getRequiredItems())
                needed.merge(required.stack.getItem(), required.stack.getCount(), Integer::sum);
        });
        return needed;
    }

    private static void stock(GameTestHelper helper, Site site, Map<Item, Integer> needed) {
        Container chest = (Container) helper.getBlockEntity(site.chest());
        int slot = 0;
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            int max = entry.getKey().getDefaultMaxStackSize();
            for (int left = entry.getValue(); left > 0; left -= max)
                chest.setItem(slot++, new ItemStack(entry.getKey(), Math.min(left, max)));
        }
    }

    // --- assertions ----------------------------------------------------------------------------------------------------

    private static void assertPrinted(GameTestHelper helper, SchematicannonBlockEntity cannon,
            Map<BlockPos, Block> targets) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<BlockPos, Block> target : targets.entrySet())
            if (!helper.getBlockState(target.getKey()).is(target.getValue()))
                missing.add(BuiltInRegistries.BLOCK.getKey(target.getValue()).getPath() + " at " + target.getKey());
        helper.assertTrue(missing.isEmpty(), "the Schematicannon has not printed " + missing + "; it says '"
                + cannon.statusMsg + "'" + (cannon.missingItem == null ? "" : ", missing " + cannon.missingItem));
    }

    /**
     * The cannon has finished, every block stands, the chest is empty and no item lies anywhere near the test area:
     * every item the chest held became exactly one block.
     */
    private static void assertBooksBalance(GameTestHelper helper, SchematicannonBlockEntity cannon,
            Map<BlockPos, Block> targets) {
        assertPrinted(helper, cannon, targets);
        helper.assertTrue("finished".equals(cannon.statusMsg), "every block stands, but the cannon says '"
                + cannon.statusMsg + "'");
        Container chest = (Container) helper.getBlockEntity(WAREHOUSE.chest());
        Map<String, Integer> left = new TreeMap<>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty())
                left.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
        }
        helper.assertTrue(left.isEmpty(), "the cannon printed everything but left " + left + " in the chest");
        AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(32);
        List<ItemEntity> dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area);
        helper.assertTrue(dropped.isEmpty(), "items lie on the floor: " + dropped.stream()
                .map(entity -> entity.getItem().toString()).toList());
    }

    /** Every Wareworks block that has an item, each on its own spot of the grid site, with a gap to the next. */
    private static Map<BlockPos, Block> gridLayout() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK)
            if (Wareworks.ID.equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())
                    && block.asItem() instanceof BlockItem)
                blocks.add(block);
        Map<BlockPos, Block> layout = new LinkedHashMap<>();
        int index = 0;
        Vec3i size = GRID.size();
        for (int y = 0; y < size.getY() && index < blocks.size(); y += 2)
            for (int z = 0; z < size.getZ() && index < blocks.size(); z += 2)
                for (int x = 0; x < size.getX() && index < blocks.size(); x += 2)
                    layout.put(GRID.min().offset(x, y, z), blocks.get(index++));
        if (index < blocks.size())
            throw new IllegalStateException("the grid has room for " + index + " blocks, but Wareworks has "
                    + blocks.size());
        return layout;
    }
}
