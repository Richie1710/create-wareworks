package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.OptimizerAction;
import dev.wareworks.content.station.OptimizerMenu;
import dev.wareworks.content.station.OptimizerRows;
import dev.wareworks.content.station.OptimizerScreenState;
import dev.wareworks.content.station.WarehouseOptimizerBlock;
import dev.wareworks.content.station.WarehouseOptimizerBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.optimizer.OptimizerLimits;
import dev.wareworks.core.optimizer.OptimizerOrders;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Warehouse Optimizer as a block (issue #46, ADR-079): a member that holds no items, a list of ghost rows edited
 * through its menu — clipboard import included — and the per-priority view its screen shows. What the crane then does
 * with an order is the planner's business and is tested there.
 * <ul>
 * <li>{@code optimizerblockandmenu} — standing alone: the location kind, no item capability, the rows through the menu's
 * payload entry point (set, refused duplicate, clear, a clipboard on the cursor, a full list), hostile payloads, a save
 * and a schematic copy, and a broken optimizer that drops itself and nothing else;</li>
 * <li>{@code optimizerviewandmembership} — in an aisle without power: the optimizer is a member of its own kind, the
 * view counts each item by priority and finds the room a better place has, equal priorities move nothing, an order is
 * started for the listed items, breaking the optimizer forgets that order, and one placed back facing away from the
 * aisle starts none.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class OptimizerBlockGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 600;
    private static final double DROP_RADIUS = 2.0;
    private static final int OTHER_CONTAINER_ID = 4321;

    private static final RackPosition LOW = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition HIGH = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition OPTIMIZER_RACK = new RackPosition(3, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final int IRON_STORED = 64;
    private static final int HIGH_PRIORITY = 5;

    private OptimizerBlockGameTests() {
    }

    @GameTest(template = EMPTY_7X5X7)
    public static void optimizerBlockAndMenu(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, BASE_Y, 3);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_OPTIMIZER.getDefaultState()
                .setValue(WarehouseOptimizerBlock.FACING, Direction.NORTH));
        WarehouseOptimizerBlockEntity optimizer = optimizerAt(helper, pos);
        helper.assertValueEqual(optimizer.locationKind(), LocationKind.OPTIMIZER, "location kind");
        helper.assertValueEqual(optimizer.facing(), Direction.NORTH, "facing");
        helper.assertValueEqual(optimizer.rows().size(), OptimizerLimits.MAX_ROWS, "item rows");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null) == null, "an optimizer must expose no item capability at all");

        Player player = playerAt(helper, pos);
        OptimizerMenu menu = openMenu(player, optimizer);
        helper.startSequence().thenExecute(() -> {
            helper.assertValueEqual(setItem(player, menu.containerId, 0, IRON), Optional.of(true), "iron is listed");
            helper.assertValueEqual(setItem(player, menu.containerId, 1, IRON), Optional.of(false),
                    "an item stands in one row only");
            helper.assertValueEqual(optimizer.rows().keys(), List.of(IRON), "the duplicate was refused");
            helper.assertValueEqual(setItem(player, menu.containerId, 2, GOLD), Optional.of(true), "gold is listed");
            helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.CLEAR_ROW, 2,
                    Optional.empty()), Optional.of(true), "a row is cleared");
            helper.assertValueEqual(optimizer.rows().keys(), List.of(IRON), "only iron is left");
            // Hostile payloads reach nothing.
            helper.assertTrue(setItem(player, OTHER_CONTAINER_ID, 3, GOLD).isEmpty(), "another menu id");
            helper.assertTrue(setItem(null, menu.containerId, 3, GOLD).isEmpty(), "no player");
            helper.assertTrue(setItem(playerAt(helper, pos), menu.containerId, 3, GOLD).isEmpty(),
                    "another player's menu");
            helper.assertValueEqual(setItem(player, menu.containerId, -1, GOLD), Optional.of(false), "a negative row");
            helper.assertValueEqual(setItem(player, menu.containerId, OptimizerLimits.MAX_ROWS, GOLD),
                    Optional.of(false), "a row past the last one");
            // Without a warehouse Optimise starts nothing, and the screen says that no warehouse serves it.
            helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.OPTIMISE, 0,
                    Optional.empty()), Optional.of(false), "no warehouse, no order");
            OptimizerScreenState state = optimizer.screenState(null, Map.of(), OptimizerScreenState.Message.NONE,
                    0, 0);
            helper.assertFalse(state.linked(), "no controller serves it");
            helper.assertValueEqual(state.row(0).flatMap(OptimizerScreenState.RowView::key), Optional.of(IRON),
                    "the row is shown all the same");
            helper.assertValueEqual(state.rows().size(), OptimizerLimits.MAX_ROWS, "every row is shown");
        }).thenIdle(1).thenExecute(() -> {
            // A clipboard on the cursor: its orderable entries, each item once, the ones listed already left out, and a
            // ticked entry is no item to add. The clipboard stays exactly where it is.
            ItemStack clipboard = clipboard(entry(IRON, false), entry(GOLD, false), entry(GOLD, false),
                    entry(DIAMOND, true));
            menu.setCarried(clipboard);
            helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.IMPORT_CLIPBOARD, 0,
                    Optional.empty()), Optional.of(true), "the clipboard's items were added");
            helper.assertValueEqual(optimizer.rows().keys(), List.of(IRON, GOLD), "gold joined, iron stayed once");
            helper.assertTrue(ItemStack.matches(menu.getCarried(), clipboard), "the clipboard is still on the cursor");
            helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.IMPORT_CLIPBOARD, 0,
                    Optional.empty()), Optional.of(false), "the same clipboard adds nothing new");
            menu.setCarried(Items.IRON_INGOT.getDefaultInstance());
            helper.assertValueEqual(OptimizerMenu.submit(player, menu.containerId, OptimizerAction.IMPORT_CLIPBOARD, 0,
                    Optional.empty()), Optional.of(false), "an ingot is no clipboard");
        }).thenIdle(1).thenExecute(() -> {
            // A material list longer than the rows fills them, and what found no row is not silently dropped.
            List<ClipboardEntry> many = new ArrayList<>();
            for (Item item : List.of(Items.STONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.OAK_LOG, Items.OAK_PLANKS,
                    Items.GLASS, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ, Items.CLAY_BALL,
                    Items.STRING, Items.BONE, Items.FEATHER, Items.LEATHER))
                many.add(entry(ItemKey.of(item), false));
            menu.setCarried(clipboard(many.toArray(ClipboardEntry[]::new)));
            OptimizerRows.Added added = optimizer.importClipboard(menu.getCarried()).orElseThrow();
            helper.assertValueEqual(added.added(), OptimizerLimits.MAX_ROWS - 2, "the free rows are filled");
            helper.assertValueEqual(added.noRoom(), 2, "and the rest is counted");
            helper.assertValueEqual(optimizer.rows().count(), OptimizerLimits.MAX_ROWS, "every row is used");
            menu.setCarried(ItemStack.EMPTY);
            player.containerMenu = player.inventoryMenu;

            // The list survives a save, and a schematic carries it along.
            CompoundTag saved = optimizer.saveWithFullMetadata(helper.getLevel().registryAccess());
            WarehouseOptimizerBlockEntity copy = loadCopy(helper, optimizer, saved);
            helper.assertValueEqual(copy.rows().keys(), optimizer.rows().keys(), "the list after a save");
            CompoundTag safe = new CompoundTag();
            optimizer.writeSafe(safe, helper.getLevel().registryAccess());
            helper.assertTrue(safe.contains(OptimizerRows.ROWS_TAG), "a schematic carries the list");
        }).thenIdle(1).thenExecute(() -> {
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_OPTIMIZER.asItem(), pos, DROP_RADIUS);
            helper.assertValueEqual(ItemCensus.take(helper),
                    Map.of(ItemKey.of(WareworksBlocks.WAREHOUSE_OPTIMIZER.asItem()), 1L),
                    "a broken optimizer drops itself and nothing else");
        }).thenSucceed();
    }

    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void optimizerViewAndMembership(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(LOW, new ItemStack(Items.IRON_INGOT, IRON_STORED));
        aisle.storage(HIGH);
        aisle.optimizer(OPTIMIZER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 0);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(IRON), (long) IRON_STORED, "iron indexed");
                    helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "every location read");
                })
                .thenExecute(() -> aisle.setStorePriority(HIGH, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().isStoragePrioritised(HIGH),
                        "the controller read the priority"))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.locationAt(aisle.absoluteRackPos(OPTIMIZER_RACK))
                            .map(record -> record.kind()), Optional.of(LocationKind.OPTIMIZER), "a member of its own kind");

                    List<PriorityBreakdown> views = controller.optimizerView(List.of(IRON, GOLD));
                    PriorityBreakdown iron = views.get(0);
                    helper.assertValueEqual(iron.countAt(0), (long) IRON_STORED, "iron at priority 0");
                    helper.assertValueEqual(iron.bestPriority(), 0, "nothing of it lies higher");
                    helper.assertValueEqual(iron.best(), (long) IRON_STORED, "Best");
                    helper.assertValueEqual(iron.below(), 0L, "Below");
                    helper.assertValueEqual(iron.unloaded(), 0L, "every location is loaded");
                    helper.assertValueEqual(iron.movable(), (long) IRON_STORED,
                            "an empty chest at priority " + HIGH_PRIORITY + " takes all of it");
                    helper.assertFalse(iron.movableUnknown(), "both chests were read");
                    helper.assertValueEqual(views.get(1).total(), 0L, "no gold at all");
                    helper.assertValueEqual(views.get(1).movable(), 0L, "so none of it moves");

                    // The screen shows the same numbers, through the block entity's own state.
                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    optimizer.editRow(0, IRON);
                    OptimizerScreenState state = optimizer.screenState(controller,
                            controller.optimizerRooms(List.of(IRON)), OptimizerScreenState.Message.NONE, 0, 0);
                    helper.assertTrue(state.linked(), "the warehouse serves it");
                    helper.assertValueEqual(state.row(0).orElseThrow().breakdown(), iron, "the row's breakdown");
                    helper.assertValueEqual(state.row(0).orElseThrow().pip(), OptimizerScreenState.Pip.IDLE,
                            "no order yet");
                })
                .thenExecute(() -> aisle.setStorePriority(LOW, HIGH_PRIORITY))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().optimizerView(List.of(IRON)).get(0)
                        .countAt(HIGH_PRIORITY), (long) IRON_STORED, "the iron is counted at its new priority"))
                .thenExecute(() -> {
                    PriorityBreakdown iron = aisle.controller().optimizerView(List.of(IRON)).get(0);
                    helper.assertValueEqual(iron.movable(), 0L, "an equal priority is no better place");

                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    helper.assertValueEqual(optimizer.optimise(optimizer.rows().keys()),
                            Optional.of(OptimizerOrders.Start.STARTED), "an order for the listed items");
                    helper.assertTrue(optimizer.order(aisle.controller()).isPresent(), "the warehouse keeps it");
                    aisle.breakBlock(aisle.rackPos(OPTIMIZER_RACK));
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().optimizerOrder(
                        aisle.absoluteRackPos(OPTIMIZER_RACK)).isEmpty(), "the order left with its optimizer"))
                // Placed again by a player standing behind the rack, it faces away from the aisle: misaligned, so no
                // member, although its screen still finds the warehouse. Breaking a block that is no member forgets
                // nothing, so an order it started would outlive it with nobody left to cancel it.
                .thenExecute(() -> helper.setBlock(aisle.rackPos(OPTIMIZER_RACK),
                        WareworksBlocks.WAREHOUSE_OPTIMIZER.getDefaultState()
                                .setValue(WarehouseOptimizerBlock.FACING, aisle.sideDirection(OPTIMIZER_RACK))))
                .thenIdle(1)
                .thenWaitUntil(() -> {
                    helper.assertFalse(aisle.controller().isMembershipDirty(), "membership processed");
                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    helper.assertValueEqual(WarehouseRegistry.assignmentOf(helper.getLevel(),
                            aisle.absoluteRackPos(OPTIMIZER_RACK), optimizer).state(),
                            AisleAssignment.State.MISALIGNED, "the optimizer faces away from the aisle");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.locationAt(aisle.absoluteRackPos(OPTIMIZER_RACK)).isEmpty(),
                            "a misaligned optimizer is no member");
                    WarehouseOptimizerBlockEntity optimizer = aisle.optimizerAt(OPTIMIZER_RACK);
                    helper.assertTrue(optimizer.controller().isPresent(), "its screen still finds the warehouse");
                    optimizer.editRow(0, IRON);
                    helper.assertValueEqual(optimizer.optimise(optimizer.rows().keys()), Optional.empty(),
                            "but it starts no order");
                    helper.assertTrue(controller.optimizerOrder(aisle.absoluteRackPos(OPTIMIZER_RACK)).isEmpty(),
                            "and the warehouse keeps none for it");
                })
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static WarehouseOptimizerBlockEntity optimizerAt(GameTestHelper helper, BlockPos pos) {
        WarehouseOptimizerBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OPTIMIZER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse optimizer block entity", pos);
        return be;
    }

    private static WarehouseOptimizerBlockEntity loadCopy(GameTestHelper helper, WarehouseOptimizerBlockEntity live,
            CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof WarehouseOptimizerBlockEntity optimizer)) {
            helper.fail("a saved optimizer must load again as one");
            return live;
        }
        return optimizer;
    }

    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static OptimizerMenu openMenu(Player player, WarehouseOptimizerBlockEntity optimizer) {
        OptimizerMenu menu = OptimizerMenu.create(player.containerMenu.containerId + 1, player.getInventory(),
                optimizer);
        player.containerMenu = menu;
        return menu;
    }

    /** Sends "put this item into that row" through the payload entry point. */
    private static Optional<Boolean> setItem(Player player, int containerId, int row, ItemKey key) {
        return OptimizerMenu.submit(player, containerId, OptimizerAction.SET_ITEM, row, Optional.of(key));
    }

    /** One entry of a hand-written clipboard, ticked off or not. */
    private static ClipboardEntry entry(ItemKey key, boolean checked) {
        return new ClipboardEntry(checked, Component.literal(BuiltInRegistries.ITEM.getKey(key.getItem()).getPath()))
                .displayItem(key.toStack(), 1);
    }

    /** A written clipboard with {@code entries} on one page, the shape the Schematicannon writes. */
    private static ItemStack clipboard(ClipboardEntry... entries) {
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        stack.set(AllDataComponents.CLIPBOARD_CONTENT,
                new ClipboardContent(ClipboardType.WRITTEN, List.of(List.of(entries)), true));
        return stack;
    }
}
