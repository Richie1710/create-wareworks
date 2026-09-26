package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.MAX_INTERFACE_SYNC_BYTES;

import java.util.List;
import java.util.Map;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of storage location <b>priorities</b> (M16, issue #11, {@code docs/warehouse-system.md} §3.1, ADR-028): a
 * warehouse interface carries a priority 0..9 on the same value box as its store filter, and among the locations that
 * are <b>equally suitable</b> the crane fills the highest one first.
 * <p>
 * The world-behaviour half of the milestone; the ranking itself is pinned in {@code JobPlannerTest}, which also proves
 * that priority 0 everywhere reproduces the old order exactly. Here:
 * {@code prioritytwolocationsprefersthepreferredone} is the headline case (the crane really drives past the nearer
 * rack), {@code priorityfullpreferredfallsbacktothenextone} that a preferred rack without room never stalls the
 * warehouse, {@code priorityrejectingfilterbeatsthepriority} that a filter is the hard rule and the priority only the
 * soft preference, {@code prioritychangedmidjob} that a running job keeps its target and nothing is ever re-shuffled,
 * {@code prioritypersistenceroundtrip} saves with and without a priority (a pre-M16 world reads as 0) plus the
 * schematic path, {@code prioritycoldcacheafterreload} that a controller restored from a save already knows the
 * priorities of locations it has not read back yet, {@code priorityonsharedaliasisnotcounted} the shared-inventory
 * limitation, {@code prioritygoggles} the goggle data and the update-tag budget on both sides of the network, and
 * {@code prioritydropsandclipboard} the drop and all four clipboard directions.
 * <p>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1 at z = 3 along +X,
 * {@value #RAILS} rails), storage locations on the left rack plane and the stations on the right. Every test that moves
 * items asserts the item conservation invariant on each tick ({@link ItemCensus}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StoragePriorityGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    /** Items of each type fed through the input. */
    private static final int BATCH = 8;
    /** Long enough for several dispatch intervals and a hold retry: proof that nothing is re-shuffled. */
    private static final int SETTLE_TICKS = 60;
    /** The priority a preferred location gets in these tests; any value above 0 would do. */
    private static final int PREFERRED = 3;

    /** Nearest storage location to the crane's parking position, and the one travel time alone would choose. */
    private static final RackPosition NEAR = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition MIDDLE = new RackPosition(3, 0, Side.LEFT);
    /** Far end of the aisle: only a priority ever sends new items here. */
    private static final RackPosition FAR = new RackPosition(5, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    /**
     * Storage locations of {@link #priorityColdCacheAfterReload}: deliberately <b>more</b> than
     * {@code maxSnapshotsPerTick} (default 4), because the controller drains its restored locations at that rate while
     * dispatch already runs, so with four or fewer there would be no cold window to test at all. The last one is the
     * prioritised, far location.
     */
    private static final List<RackPosition> RELOAD_RACKS = List.of(new RackPosition(1, 0, Side.LEFT),
            new RackPosition(2, 0, Side.LEFT), new RackPosition(3, 0, Side.LEFT), new RackPosition(4, 0, Side.LEFT),
            new RackPosition(1, 1, Side.LEFT), new RackPosition(5, 0, Side.LEFT));
    /** Blocks around a broken interface searched for its dropped items. */
    private static final double DROP_RADIUS = 2.0;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey REDSTONE = ItemKey.of(Items.REDSTONE);

    /** Create's filter key and Create's generic value-settings keys, as a funnel would write them to a clipboard. */
    private static final String FILTER_TAG = "Filter";
    private static final String CLIPBOARD_VALUE_TAG = "Value";
    private static final String CLIPBOARD_ROW_TAG = "Row";
    /** The extracted amount on a funnel's clipboard; anything but {@link #PREFERRED}, so a mix-up would show. */
    private static final int FUNNEL_AMOUNT = 7;
    /** Our own clipboard, save and sync key for the priority. */
    private static final String PRIORITY_TAG = StorageFilterBehaviour.PRIORITY_TAG;

    private StoragePriorityGameTests() {
    }

    // --- the player-facing scenario -----------------------------------------------------------------------------------

    /**
     * The headline case and the user's own example: two storage locations the rules above the priority leave equal (both
     * unfiltered, both empty), so today only travel time decides — and travel time is measured crane → input →
     * location, which has nothing to do with where the player stands. The priority overrides it, and the items really
     * arrive at the far rack, which can only happen by the crane driving there (no item ever teleports).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityTwoLocationsPrefersThePreferredOne(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.storage(FAR);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a priority steers the stream"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStorePriority(FAR, PREFERRED);
                    helper.assertValueEqual(aisle.controller().prioritisedLocationCount(), 1,
                            "one prioritised location");
                    helper.assertTrue(aisle.controller().isStoragePrioritised(FAR), "the controller knows it");
                    helper.assertFalse(aisle.controller().isStoragePrioritised(NEAR), "and knows the other is neutral");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) BATCH,
                        "the items went to the preferred location"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), 0L,
                            "the crane drove past the nearer location");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A preferred location with no room left must not stall the warehouse: the estimate drops it before any live call and
     * the items go to the next candidate. The reason must never become {@code WAREHOUSE_FULL} while space is left.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityFullPreferredFallsBackToTheNextOne(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        // A chest filled to the last slot: it consolidates and is compatible, so only the missing room can drop it.
        ItemStack[] full = new ItemStack[27];
        for (int slot = 0; slot < full.length; slot++)
            full[slot] = IRON.toStack(64);
        aisle.storage(FAR, full);
        aisle.storage(NEAR);
        aisle.input(INPUT_RACK);
        long stored = 27L * 64L;
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, (int) stored);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the preferred location is full"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStorePriority(FAR, PREFERRED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) BATCH,
                        "the items go to the next candidate"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), stored, "the full location is unchanged");
                    helper.assertFalse(aisle.controller().lastPlanReason().filter(NoJobReason.WAREHOUSE_FULL::equals)
                            .isPresent(), "a full preferred location is not a full warehouse");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A filter is the <b>hard</b> rule ("may this item live here at all"), a priority only the <b>soft</b> preference
     * ("which of the permitted locations first"), so a rejecting filter drops the location however high its priority is.
     * {@code JobPlannerTest} pins that this costs neither a live simulation nor a remembered refusal; here only a real
     * Create filter item can prove that the content layer maps it to a rejection in the first place.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityRejectingFilterBeatsThePriority(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FAR);
        aisle.storage(NEAR);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a filter overrules a priority"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(FAR, listFilter(GOLD.toStack()));
                    aisle.setStorePriority(FAR, StorageFilterBehaviour.MAX_PRIORITY);
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), 1, "one filtered location");
                    helper.assertValueEqual(aisle.controller().prioritisedLocationCount(), 1, "and it is prioritised");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) BATCH,
                        "the iron goes to the location that accepts it"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), 0L,
                            "priority 9 never puts an item into a location whose filter rejects it");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Where the priority sits in the ranking, end to end and in one aisle — the decision the milestone calls
     * load-bearing, in the order a player would notice it:
     * <ol>
     * <li>a <b>dedicated</b> location (an allow list for the item) fills before a <b>prioritised unfiltered</b> one,
     * however high that priority is: otherwise dedicated storage would never fill while a general vault has room, the
     * failure ADR-021 forbids;</li>
     * <li>between two locations that are otherwise equal the priority really does decide (the positive case);</li>
     * <li><b>item-type grouping</b> still decides before the priority: a new item type goes to an empty location rather
     * than into the prioritised one that already holds another type. A priority is per <i>location</i>, not per item, so
     * above this key one prioritised location would attract every item type — exactly the "filled with a mix of
     * everything" defect the M8 review found for deny lists and fixed.</li>
     * </ol>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityDoesNotOutrankFiltersOrGrouping(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.storage(MIDDLE);
        aisle.storage(FAR);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the ranking is exercised"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 0))
                .thenExecute(() -> {
                    aisle.setStorePriority(NEAR, StorageFilterBehaviour.MAX_PRIORITY);
                    aisle.setStoreFilter(FAR, listFilter(IRON.toStack()));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) BATCH,
                        "the dedicated location fills although the nearest one is prioritised 9"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), 0L, "a priority is no dedication");
                    // Both remaining locations are empty and unfiltered, so now the priority is what decides.
                    feed(helper, aisle, conserved, GOLD.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(NEAR, GOLD), (long) BATCH,
                        "between two equally suitable locations the priority decides"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> feed(helper, aisle, conserved, REDSTONE.toStack(BATCH)))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(MIDDLE, REDSTONE), (long) BATCH,
                        "a new item type gets an empty location instead of the prioritised, occupied one"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, REDSTONE), 0L,
                            "item-type grouping still ranks above the priority");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Raising a priority while a store job is in flight changes nothing about that job — its items are already in the
     * handling head — and nothing that is already stored ever moves (ADR-021). Only the <b>next</b> trip goes to the new
     * preferred location.
     * <p>
     * Two item types on purpose: the second type cannot consolidate into the location the first one filled, so the
     * priority is what decides between the two remaining empty ones (item-type grouping and consolidation both rank
     * above it by design).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityChangedMidJob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.storage(MIDDLE);
        aisle.storage(FAR);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a priority changes mid job"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.dock().currentJob().isPresent(), "the crane has a store job");
                    helper.assertValueEqual(aisle.dock().currentJob().orElseThrow().target(), NEAR,
                            "without a priority the nearest location is chosen");
                })
                .thenExecute(() -> aisle.setStorePriority(FAR, PREFERRED))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) BATCH,
                        "the running job kept its target"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> feed(helper, aisle, conserved, GOLD.toStack(BATCH)))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(FAR, GOLD), (long) BATCH,
                        "the next trip goes to the new preferred location"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(MIDDLE, GOLD), 0L, "not into the nearer empty location");
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) BATCH,
                            "and a raised priority never re-shuffles what is already stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- persistence and goggles --------------------------------------------------------------------------------------

    /**
     * A world saved before storage priorities existed (no {@value #PRIORITY_TAG} key) loads unchanged as "no
     * preference", a priority round-trips through a save, and it travels in a schematic ({@code writeSafe}) exactly like
     * the filter.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void priorityPersistenceRoundTrip(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, BASE_Y, 3);
        helper.setBlock(pos.north(), Blocks.CHEST);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        WarehouseInterfaceBlockEntity be = interfaceAt(helper, pos);

        helper.assertValueEqual(be.storePriority(), 0, "a fresh interface has no preference");
        helper.assertFalse(be.hasStorePriority(), "and says so");
        // A pre-M16 save simply has no key at all, which is what almost every existing world looks like.
        CompoundTag legacyTag = be.saveWithFullMetadata(registries);
        helper.assertFalse(legacyTag.contains(PRIORITY_TAG), "priority 0 is not even written");
        WarehouseInterfaceBlockEntity legacy = loadCopy(helper, be, legacyTag, WarehouseInterfaceBlockEntity.class);
        helper.assertValueEqual(legacy.storePriority(), 0, "a save without a priority loads as no preference");

        helper.assertTrue(be.setStorePriority(PREFERRED), "the interface takes a priority");
        helper.assertTrue(be.hasStorePriority(), "the priority is set");
        CompoundTag saved = be.saveWithFullMetadata(registries);
        helper.assertTrue(saved.contains(PRIORITY_TAG), "the priority is saved");
        helper.assertValueEqual(loadCopy(helper, be, saved, WarehouseInterfaceBlockEntity.class).storePriority(),
                PREFERRED, "the priority survives a save");

        // Schematics: Create writes a behaviour's safe NBT, which defaults to the save form.
        CompoundTag schematic = new CompoundTag();
        be.writeSafe(schematic, registries);
        helper.assertValueEqual(schematic.getInt(PRIORITY_TAG), PREFERRED, "a schematic carries the priority");

        // Out-of-range values are clamped on the way in, so a wider range later never rewrites a player's number.
        be.setStorePriority(StorageFilterBehaviour.MAX_PRIORITY + 5);
        helper.assertValueEqual(be.storePriority(), StorageFilterBehaviour.MAX_PRIORITY, "clamped to the maximum");
        be.setStorePriority(-3);
        helper.assertValueEqual(be.storePriority(), StorageFilterBehaviour.MIN_PRIORITY, "clamped to the minimum");
        helper.succeed();
    }

    /**
     * A controller restored from a save honours priorities it has <b>not read back yet</b>.
     * <p>
     * Neither filters nor priorities are persisted at the controller — they live in the interfaces — so after a load its
     * cache is empty while the restored locations are still queued for background snapshots, drained at
     * {@code maxSnapshotsPerTick} per tick. Dispatch already runs in that window, and the first plan must already use
     * the right preference, which is why the one-shot resolve for an unread rack reads filter <b>and</b> priority in one
     * block entity lookup. Six locations, only the far one prioritised, so a cold cache would be visible immediately:
     * the items would land in the nearest rack.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityColdCacheAfterReload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        for (RackPosition rack : RELOAD_RACKS)
            aisle.storage(rack);
        aisle.input(INPUT_RACK);
        RackPosition preferred = RELOAD_RACKS.get(RELOAD_RACKS.size() - 1);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reloaded controller plans"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(RELOAD_RACKS.size(), 1, 0))
                .thenExecute(() -> {
                    aisle.setStorePriority(preferred, PREFERRED);
                    helper.assertValueEqual(aisle.controller().prioritisedLocationCount(), 1,
                            "only the far location is preferred");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenExecute(() -> {
                    // The items arrive and the controller is replaced by a copy loaded from its save in the same tick,
                    // so the very next dispatch plans with a cold store-settings cache.
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                    level.setBlockEntity(loadCopy(helper, controller, saved, WarehouseControllerBlockEntity.class));
                    helper.assertTrue(controller.isRemoved(), "the controller block entity was replaced");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(preferred, IRON), (long) BATCH,
                        "the first plan after a reload already used the priority"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    for (RackPosition rack : RELOAD_RACKS) {
                        if (!rack.equals(preferred))
                            helper.assertValueEqual(aisle.storedAt(rack, IRON), 0L,
                                    "items went to the unprioritised location at " + rack + " after a reload");
                    }
                    helper.assertValueEqual(aisle.controller().prioritisedLocationCount(), 1,
                            "and the reloaded controller knows the priority again");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /** Goggle data: the interface syncs its priority, the controller counts the prioritised locations, both bounded. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityGoggles(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(FAR);
        aisle.storage(NEAR);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.prioritisedLocationCount(), 0, "nothing prioritised yet");

                    // An interface with neither a filter nor a priority still writes nothing at all (§3.1.1).
                    CompoundTag neutral = aisle.interfaceAt(NEAR).getUpdateTag(registries);
                    helper.assertFalse(neutral.contains(FILTER_TAG), "a neutral interface syncs no filter");
                    helper.assertFalse(neutral.contains(PRIORITY_TAG), "and no priority");

                    aisle.setStorePriority(FAR, PREFERRED);
                    controller.onGoggleObserved();
                    helper.assertValueEqual(controller.summary().prioritisedLocations(), 1,
                            "the controller goggle summary counts prioritised locations");
                    WarehouseControllerBlockEntity controllerCopy = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                            .create(controller.getBlockPos(), controller.getBlockState());
                    if (controllerCopy == null) {
                        helper.fail("could not create a detached controller block entity");
                        return;
                    }
                    controllerCopy.handleUpdateTag(controller.getUpdateTag(registries), registries);
                    helper.assertValueEqual(controllerCopy.summary().prioritisedLocations(), 1,
                            "and syncs the count to clients");

                    // The number itself reaches clients, because goggles and the block renderer both run there.
                    WarehouseInterfaceBlockEntity storage = aisle.interfaceAt(FAR);
                    CompoundTag updateTag = storage.getUpdateTag(registries);
                    helper.assertValueEqual(updateTag.getInt(PRIORITY_TAG), PREFERRED,
                            "a prioritised interface syncs its priority");
                    helper.assertFalse(updateTag.contains(FILTER_TAG),
                            "and still no filter block, which would cost 215 bytes for nothing");
                    helper.assertTrue(updateTag.sizeInBytes() < MAX_INTERFACE_SYNC_BYTES,
                            "the interface update tag stays inside its budget");
                    WarehouseInterfaceBlockEntity storageCopy = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                            .create(storage.getBlockPos(), storage.getBlockState());
                    if (storageCopy == null) {
                        helper.fail("could not create a detached interface block entity");
                        return;
                    }
                    storageCopy.handleUpdateTag(updateTag, registries);
                    helper.assertValueEqual(storageCopy.storePriority(), PREFERRED, "the number reaches clients");

                    // Clearing it removes the number from the packet again.
                    aisle.setStorePriority(FAR, 0);
                    helper.assertFalse(storage.getUpdateTag(registries).contains(PRIORITY_TAG),
                            "a cleared priority syncs nothing again");
                    helper.assertValueEqual(controller.prioritisedLocationCount(), 0, "and is not counted any more");
                })
                .thenSucceed();
    }

    /**
     * Known limitation of §3.1.1, made visible instead of silently wrong: for an inventory several locations read (a
     * double chest here) the planner only asks the location that <b>counts</b> it, so a priority on the other one can do
     * nothing. It must therefore not be counted, and its own interface says so in its goggles — even though no filter is
     * set, which is the case a priority adds.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void priorityOnSharedAliasIsNotCounted(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        // Chest halves along the aisle, fronts towards their interfaces: the lower x connects east, the higher x west.
        helper.setBlock(aisle.inventoryPos(NEAR), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        helper.setBlock(aisle.inventoryPos(new RackPosition(2, 0, Side.LEFT)), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        RackPosition second = new RackPosition(2, 0, Side.LEFT);
        aisle.placeInterface(NEAR);
        aisle.placeInterface(second);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RackPosition counting = controller.sharedInventoryOf(NEAR).orElse(NEAR);
                    helper.assertValueEqual(controller.locationsSharingInventory(counting).size(), 2,
                            "both interfaces read one double chest");
                    RackPosition alias = counting.equals(NEAR) ? second : NEAR;

                    aisle.setStorePriority(alias, PREFERRED);
                    helper.assertValueEqual(controller.prioritisedLocationCount(), 0,
                            "a priority the planner never asks about is not counted");
                    helper.assertFalse(controller.isStoragePrioritised(alias), "and is not reported as active");
                    helper.assertTrue(controller.isStorageFilterShadowed(alias),
                            "the controller knows it is shadowed, although no filter is set");
                    WarehouseInterfaceBlockEntity shadowed = aisle.interfaceAt(alias);
                    shadowed.onGoggleObserved();
                    helper.assertTrue(shadowed.isStoreFilterShadowed(), "the interface shows the hint in its goggles");

                    // The priority of the location that counts the inventory is the one that applies, and is counted.
                    aisle.setStorePriority(counting, PREFERRED);
                    helper.assertValueEqual(controller.prioritisedLocationCount(), 1,
                            "the effective priority is counted");
                    helper.assertTrue(controller.isStoragePrioritised(counting), "and is reported as active");
                    helper.assertFalse(controller.isStorageFilterShadowed(counting), "it is not shadowed itself");
                })
                .thenSucceed();
    }

    /**
     * Breaking a prioritised interface drops its filter item (the number itself is not an item and is lost with the
     * block, like the mast height and the aisle letter), and the clipboard carries filter <b>and</b> priority between
     * interfaces without ever confusing the priority with a funnel's extracted amount.
     * <p>
     * All four directions matter. Create's generic {@code Value}/{@code Row} pair means "extracted amount" to every
     * other filter block, so carrying the priority in it would let a funnel's amount become a priority and a priority
     * become a funnel's amount. Keeping Create's {@code "Filtering"} clipboard key is what makes copying a funnel's
     * filter onto an interface keep working.
     * <p>
     * And a clipboard must be able to say <b>0</b>. Create writes its {@code Filter} entry unconditionally, so a copy of
     * a neutral interface clears the target's filter; a priority written only while it is not 0 would leave the target's
     * number in place in that very paste, and the player would believe they had reset a location they had not
     * (M16 review fix). The last step therefore pastes an unprioritised, unfiltered interface onto a prioritised,
     * filtered one and demands that <b>both</b> halves end up neutral.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void priorityDropsAndClipboard(GameTestHelper helper) {
        BlockPos source = new BlockPos(2, BASE_Y, 3);
        BlockPos target = new BlockPos(4, BASE_Y, 3);
        // A third location that is never given a filter or a priority: the clipboard source of the resetting paste.
        BlockPos untouched = new BlockPos(6, BASE_Y, 3);
        for (BlockPos pos : List.of(source, target, untouched)) {
            helper.setBlock(pos.north(), Blocks.CHEST);
            helper.setBlock(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));
        }
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        WarehouseInterfaceBlockEntity from = interfaceAt(helper, source);
        WarehouseInterfaceBlockEntity to = interfaceAt(helper, target);
        WarehouseInterfaceBlockEntity neutral = interfaceAt(helper, untouched);
        helper.assertTrue(from.setStoreFilter(listFilter(IRON.toStack())), "the interface accepts a list filter");
        helper.assertTrue(from.setStorePriority(PREFERRED), "and a priority");

        // Interface → interface: both settings travel, and Create's generic pair is not used for it.
        CompoundTag copied = new CompoundTag();
        helper.assertTrue(settingsOf(helper, from).writeToClipboard(registries, copied, Direction.SOUTH),
                "the interface offers a clipboard copy");
        helper.assertTrue(copied.contains(FILTER_TAG), "the filter is copied");
        helper.assertValueEqual(copied.getInt(PRIORITY_TAG), PREFERRED, "the priority is copied");
        helper.assertFalse(copied.contains(CLIPBOARD_VALUE_TAG), "and not through Create's amount pair");
        helper.assertFalse(copied.contains(CLIPBOARD_ROW_TAG), "in either half");
        helper.assertTrue(settingsOf(helper, to).readFromClipboard(registries, copied, player, Direction.SOUTH, false),
                "the other interface takes the paste");
        helper.assertValueEqual(to.storePriority(), PREFERRED, "the priority was pasted");
        helper.assertTrue(to.hasStoreFilter(), "and so was the filter");

        // Funnel → interface: a clipboard with Create's amount pair sets the filter and leaves the priority untouched —
        // which only means anything while the target's priority is not 0 to begin with.
        CompoundTag funnelLike = new CompoundTag();
        funnelLike.put(FILTER_TAG, listFilter(GOLD.toStack()).saveOptional(registries));
        funnelLike.putInt(CLIPBOARD_VALUE_TAG, FUNNEL_AMOUNT);
        funnelLike.putInt(CLIPBOARD_ROW_TAG, 1);
        helper.assertTrue(settingsOf(helper, to).readFromClipboard(registries, funnelLike, player, Direction.SOUTH,
                false), "a funnel clipboard still sets the filter");
        helper.assertValueEqual(to.storePriority(), PREFERRED,
                "a funnel's extracted amount never becomes a priority, and never clears one");

        // Interface(0) → interface(prioritised): the paste that resets a location. Create clears the filter here
        // whatever we do, so the priority has to be cleared with it (M16 review fix).
        CompoundTag blank = new CompoundTag();
        helper.assertTrue(settingsOf(helper, neutral).writeToClipboard(registries, blank, Direction.SOUTH),
                "an unprioritised interface offers a clipboard copy too");
        helper.assertTrue(blank.contains(PRIORITY_TAG), "a clipboard can say \"no priority\"");
        helper.assertValueEqual(blank.getInt(PRIORITY_TAG), 0, "and says exactly 0");
        helper.assertTrue(settingsOf(helper, to).readFromClipboard(registries, blank, player, Direction.SOUTH, false),
                "the prioritised interface takes that paste");
        helper.assertValueEqual(to.storePriority(), 0, "the priority is reset, not silently kept");
        helper.assertFalse(to.hasStoreFilter(), "and the filter with it");

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.getLevel().destroyBlock(helper.absolutePos(source), true);
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_INTERFACE.asItem(), source, DROP_RADIUS);
                    helper.assertItemEntityPresent(AllItems.FILTER.get(), source, DROP_RADIUS);
                })
                .thenSucceed();
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    /** The store filter and priority behaviour of an interface. */
    private static StorageFilterBehaviour settingsOf(GameTestHelper helper, WarehouseInterfaceBlockEntity be) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(be, FilteringBehaviour.TYPE);
        if (!(behaviour instanceof StorageFilterBehaviour settings)) {
            helper.fail("the interface has no store settings behaviour", be.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        return settings;
    }

    /** A Create list filter (whitelist) holding {@code items}. */
    private static ItemStack listFilter(ItemStack... items) {
        ItemStack filter = AllItems.FILTER.asStack();
        filter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(List.of(items)));
        return filter;
    }

    /** Puts {@code stacks} into the input station's buffer and adds them to the conservation expectation. */
    private static void feed(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> conserved,
            ItemStack... stacks) {
        IItemHandler buffer = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
        for (ItemStack stack : stacks) {
            aisle.insertAll(buffer, stack.copy());
            ItemCensus.change(conserved, ItemKey.of(stack), stack.getCount());
        }
    }

    private static WarehouseInterfaceBlockEntity interfaceAt(GameTestHelper helper, BlockPos pos) {
        WarehouseInterfaceBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse interface block entity", pos);
        return be;
    }

    /** A fresh block entity of the same type loaded from {@code tag}, standing in for a world reload. */
    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }
}
