package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
import com.simibubi.create.content.logistics.item.filter.attribute.attributes.InTagAttribute;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of storage location filters (M8, {@code docs/warehouse-system.md} §3.1, ADR-021): a warehouse interface
 * carries a Create filter slot that decides which items may be <b>stored</b> at that location, so a player can partition
 * a warehouse by item.
 * <p>
 * All three Create filter items go through the one code path Create provides ({@code FilterItemStack.of} resolves list,
 * attribute and package filters), and each gets a test of its own: {@code filterlistwhitelist},
 * {@code filterlistblacklist}, {@code filterattributerule} and {@code filterpackageaddress}. On top of those,
 * {@code filtermixedstreamperchest} sorts a mixed stream into three dedicated chests plus a general one,
 * {@code filterunmatcheditemisnotstored} pins what happens when nothing accepts an item,
 * {@code filterchangedkeepsstock} pins that a changed filter moves nothing and never blocks retrieval,
 * {@code filterpersistenceroundtrip} covers saves with and without a filter,
 * {@code filterdenylistdoesnotoutrankconsolidation} pins that a deny list is not a dedication,
 * {@code filtercoldcacheafterreload} pins that a controller restored from a save honours filters it has not read back
 * yet,
 * {@code filterdropswhenbroken} the item conservation invariant of the filter slot itself,
 * {@code filteronsharedaliasisnotcounted} the shared-inventory limitation, and {@code filtergoggles} the goggle data
 * on both sides of the network.
 * <p>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1 at z = 3 along +X,
 * {@value #RAILS} rails), storage locations on the left rack plane and the stations on the right. Every test that moves
 * items asserts the item conservation invariant on each tick ({@link ItemCensus}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StorageFilterGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    /** Items of each type fed through the input. */
    private static final int BATCH = 8;
    /**
     * Items pre-seeded into the <b>unfiltered</b> chest of the single-filter tests. Without it those tests would pass
     * with the filter disabled, because item-type grouping alone already puts two item types into two empty chests; with
     * it, consolidation would pull the filtered item into the unfiltered chest, so only the dedicated ranking of
     * ADR-021 can produce the asserted layout.
     */
    private static final int SEED = 4;
    /** Long enough for several dispatch intervals and a hold retry: proof that nothing is re-shuffled. */
    private static final int SETTLE_TICKS = 60;
    private static final int REQUEST_AMOUNT = 5;

    private static final RackPosition DEDICATED_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition DEDICATED_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition DEDICATED_C = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition GENERAL = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    /**
     * Storage locations of {@link #filterColdCacheAfterReload}: deliberately <b>more</b> than
     * {@code maxSnapshotsPerTick} (default 4), because the controller drains its restored locations at that rate while
     * dispatch already runs, so with four or fewer there would be no cold window to test at all.
     */
    private static final List<RackPosition> RELOAD_RACKS = List.of(new RackPosition(1, 0, Side.LEFT),
            new RackPosition(2, 0, Side.LEFT), new RackPosition(3, 0, Side.LEFT), new RackPosition(4, 0, Side.LEFT),
            new RackPosition(5, 0, Side.LEFT), new RackPosition(1, 1, Side.LEFT));
    /** Blocks around a broken interface searched for its dropped items. */
    private static final double DROP_RADIUS = 2.0;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey REDSTONE = ItemKey.of(Items.REDSTONE);
    private static final ItemKey OAK_LOG = ItemKey.of(Items.OAK_LOG);

    /** Create's three NBT keys of a {@code FilteringBehaviour}; a pre-M8 save carries none of them. */
    private static final String FILTER_TAG = "Filter";
    private static final String FILTER_AMOUNT_TAG = "FilterAmount";
    private static final String UP_TO_TAG = "UpTo";
    private static final String WANTED_ADDRESS = "wares/iron";
    private static final String OTHER_ADDRESS = "wares/gold";

    private StorageFilterGameTests() {
    }

    // --- one test per Create filter item ------------------------------------------------------------------------------

    /** A list filter used as a whitelist: only the listed item is stored there, everything else goes elsewhere. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterListWhitelist(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        // The unfiltered chest already holds iron, so without a filter consolidation would pull the incoming iron here.
        aisle.storage(GENERAL, IRON.toStack(SEED));
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, SEED);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a list filter sorts the stream"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, listFilter(false, IRON.toStack()));
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), 1, "one filtered location");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH), GOLD.toStack(BATCH));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON), (long) BATCH, "iron in its own chest");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, GOLD), (long) BATCH, "gold in the general chest");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, GOLD), 0L, "the whitelist never took gold");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, IRON), (long) SEED,
                            "a dedicated chest outranks consolidation in an unfiltered one");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /** The same list filter as a blacklist: that chest takes everything <b>except</b> the listed item. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterListBlacklist(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        aisle.storage(GENERAL);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a blacklist sorts the stream"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, listFilter(true, IRON.toStack()));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH), GOLD.toStack(BATCH));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, GOLD), (long) BATCH,
                            "a blacklist accepts what it does not list");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, IRON), (long) BATCH,
                            "the blacklisted item goes to the general chest");
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON), 0L,
                        "the blacklisted item never entered"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /** An attribute filter with a rule ("in the logs tag"): only items the rule matches are stored there. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterAttributeRule(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        // As above: without the rule, consolidation would send the incoming logs to the unfiltered chest.
        aisle.storage(GENERAL, OAK_LOG.toStack(SEED));
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(OAK_LOG, SEED);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an attribute filter sorts"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, attributeFilter(new InTagAttribute(ItemTags.LOGS)));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, OAK_LOG.toStack(BATCH), IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, OAK_LOG), (long) BATCH,
                            "the attribute rule matches oak logs");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, IRON), (long) BATCH,
                            "iron does not match the rule");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON), 0L,
                            "the attribute filter never took iron");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, OAK_LOG), (long) SEED,
                            "the rule chest outranks consolidation in the unfiltered one");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /** A package filter matches Create packages by address: the matching address is stored there, another one is not. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterPackageAddress(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        aisle.storage(GENERAL);
        aisle.input(INPUT_RACK);
        ItemStack matching = addressedPackage(WANTED_ADDRESS);
        ItemStack other = addressedPackage(OTHER_ADDRESS);
        ItemKey matchingKey = ItemKey.of(matching);
        ItemKey otherKey = ItemKey.of(other);
        helper.assertFalse(matchingKey.equals(otherKey), "the two packages differ by address");
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a package filter sorts"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, packageFilter(WANTED_ADDRESS));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, matching.copy(), other.copy());
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, matchingKey), 1L,
                            "the package with the filtered address");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, otherKey), 1L,
                            "the package with another address goes elsewhere");
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.storedAt(DEDICATED_A, otherKey), 0L,
                        "a non-matching address never entered"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- the player-facing scenario -----------------------------------------------------------------------------------

    /** A mixed stream of three item types with three dedicated chests: each type lands in its own chest. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterMixedStreamPerChest(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        aisle.storage(DEDICATED_B);
        aisle.storage(DEDICATED_C);
        aisle.storage(GENERAL);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the mixed stream is sorted"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(4, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, listFilter(false, IRON.toStack()));
                    aisle.setStoreFilter(DEDICATED_B, listFilter(false, GOLD.toStack()));
                    aisle.setStoreFilter(DEDICATED_C, listFilter(false, DIAMOND.toStack()));
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), 3, "three filtered locations");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH), GOLD.toStack(BATCH), DIAMOND.toStack(BATCH),
                            REDSTONE.toStack(BATCH));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON), (long) BATCH, "iron in chest A");
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_B, GOLD), (long) BATCH, "gold in chest B");
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_C, DIAMOND), (long) BATCH, "diamonds in chest C");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, REDSTONE), (long) BATCH,
                            "an item that matches no filter goes to the unfiltered chest");
                })
                .thenExecute(() -> {
                    // No chest holds anything it was not dedicated to.
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, REDSTONE), 0L, "chest A stayed pure");
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_B, IRON), 0L, "chest B stayed pure");
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_C, GOLD), 0L, "chest C stayed pure");
                    helper.assertValueEqual(aisle.storedAt(GENERAL, IRON), 0L, "the general chest took only redstone");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Without an unfiltered location, an item that matches no filter cannot be stored: the controller reports
     * {@code NO_MATCHING_FILTER} and the input keeps the items instead of dropping them anywhere.
     * <p>
     * The reason is deliberately not {@code WAREHOUSE_FULL} (M8 review fix): a full warehouse is relieved by a
     * retrieval, a filter mismatch never is, and the player's fix is an unfiltered location rather than more space.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterUnmatchedItemIsNotStored(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while nothing accepts the item"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, listFilter(false, IRON.toStack()));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, GOLD.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().lastPlanReason(),
                        Optional.of(NoJobReason.NO_MATCHING_FILTER), "the only chest refuses the item"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, GOLD), (long) BATCH,
                            "the input keeps the items");
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, GOLD), 0L, "and nothing was forced in");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Changing a filter never moves, drops or deletes what is already stored, and never blocks retrieval: the iron
     * stays where it is and can still be fetched after the location was re-dedicated to gold.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterChangedKeepsStock(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        BlockPos trigger = aisle.rackPos(OUTPUT_RACK).relative(aisle.sideDirection(OUTPUT_RACK));
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the filter is changed"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, listFilter(false, IRON.toStack()));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON), (long) BATCH,
                        "the iron is stored"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    // Re-dedicate the location to a different item while its stock is inside.
                    aisle.setStoreFilter(DEDICATED_A, listFilter(false, GOLD.toStack()));
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), 1, "still one filter");
                })
                .thenExecuteFor(SETTLE_TICKS, () -> helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON),
                        (long) BATCH, "a changed filter must never re-shuffle stored items"))
                .thenExecute(() -> {
                    aisle.assertIdleAndEmpty();
                    aisle.requestAt(OUTPUT_RACK, IRON.toStack(), REQUEST_AMOUNT, trigger);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(OUTPUT_RACK, IRON),
                        (long) REQUEST_AMOUNT, "retrieval still works from a location whose filter no longer matches"))
                .thenExecute(() -> helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON),
                        (long) (BATCH - REQUEST_AMOUNT), "the rest stays stored"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- persistence and goggles --------------------------------------------------------------------------------------

    /**
     * A world saved before storage filters existed (no {@code Filter} tag) loads unchanged as "accepts everything", and
     * a filter round-trips through a save.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void filterPersistenceRoundTrip(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, BASE_Y, 3);
        helper.setBlock(pos.north(), Blocks.CHEST);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        WarehouseInterfaceBlockEntity be = interfaceAt(helper, pos);

        helper.assertFalse(be.hasStoreFilter(), "a fresh interface accepts everything");
        helper.assertTrue(be.storeFilter().isEmpty(), "and its filter stack is empty");

        // A pre-M8 save has none of Create's three filter keys, not just a missing stack: its `read` takes a migration
        // branch when the count is absent, so removing only `Filter` would exercise the post-M8 path instead.
        CompoundTag legacyTag = be.saveWithFullMetadata(registries);
        legacyTag.remove(FILTER_TAG);
        legacyTag.remove(FILTER_AMOUNT_TAG);
        legacyTag.remove(UP_TO_TAG);
        WarehouseInterfaceBlockEntity legacy = loadCopy(helper, be, legacyTag, WarehouseInterfaceBlockEntity.class);
        helper.assertFalse(legacy.hasStoreFilter(), "a save without a filter loads unchanged");
        helper.assertTrue(legacy.storeFilter().isEmpty(), "and still accepts everything");

        ItemStack filter = listFilter(false, IRON.toStack());
        helper.assertTrue(be.setStoreFilter(filter.copy()), "the interface accepts a list filter");
        helper.assertTrue(be.hasStoreFilter(), "the filter is set");
        CompoundTag saved = be.saveWithFullMetadata(registries);
        helper.assertTrue(saved.contains(FILTER_TAG), "the filter is saved");
        WarehouseInterfaceBlockEntity loaded = loadCopy(helper, be, saved, WarehouseInterfaceBlockEntity.class);
        helper.assertValueEqual(loaded.storeFilter().getItem(), filter.getItem(), "the filter item survives the save");
        assertFilters(helper, loaded.storeFilter(), "after a save and load");

        helper.assertTrue(be.setStoreFilter(ItemStack.EMPTY), "the filter can be cleared again");
        helper.assertFalse(be.hasStoreFilter(), "cleared means accepts everything");
        helper.succeed();
    }

    /** Goggle data: the interface syncs its filter, and the controller counts the filtered locations. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterGoggles(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(DEDICATED_A, IRON.toStack(BATCH));
        aisle.storage(GENERAL);
        ItemStack filter = listFilter(false, IRON.toStack());

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), 0, "nothing filtered yet");
                    aisle.setStoreFilter(DEDICATED_A, filter.copy());
                    helper.assertTrue(aisle.interfaceAt(DEDICATED_A).hasStoreFilter(), "the filtered location");
                    helper.assertFalse(aisle.interfaceAt(GENERAL).hasStoreFilter(), "the unfiltered one");
                    helper.assertTrue(aisle.controller().isStorageFiltered(DEDICATED_A), "controller knows the filter");
                    helper.assertFalse(aisle.controller().isStorageFiltered(GENERAL), "and knows the other is free");

                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    controller.onGoggleObserved();
                    helper.assertValueEqual(controller.summary().filteredLocations(), 1,
                            "the controller goggle summary counts filtered locations");
                    WarehouseControllerBlockEntity controllerCopy = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                            .create(controller.getBlockPos(), controller.getBlockState());
                    if (controllerCopy == null) {
                        helper.fail("could not create a detached controller block entity");
                        return;
                    }
                    controllerCopy.handleUpdateTag(controller.getUpdateTag(registries), registries);
                    helper.assertValueEqual(controllerCopy.summary().filteredLocations(), 1,
                            "and syncs the count to clients");

                    // The filter stack itself reaches clients through Create's FilteringBehaviour, so goggles can name it.
                    WarehouseInterfaceBlockEntity storage = aisle.interfaceAt(DEDICATED_A);
                    WarehouseInterfaceBlockEntity storageCopy = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE
                            .create(storage.getBlockPos(), storage.getBlockState());
                    if (storageCopy == null) {
                        helper.fail("could not create a detached interface block entity");
                        return;
                    }
                    storageCopy.handleUpdateTag(storage.getUpdateTag(registries), registries);
                    helper.assertValueEqual(storageCopy.storeFilter().getItem(), filter.getItem(),
                            "the filter item reaches clients");
                    assertFilters(helper, storageCopy.storeFilter(), "after a client sync");

                    // Bounded sync (§3.1.1): only a filtered interface pays for its filter stack.
                    helper.assertTrue(storage.getUpdateTag(registries).contains(FILTER_TAG),
                            "a filtered interface syncs its filter");
                    helper.assertFalse(aisle.interfaceAt(GENERAL).getUpdateTag(registries).contains(FILTER_TAG),
                            "an unfiltered interface syncs no filter at all");
                })
                .thenSucceed();
    }

    /**
     * A <b>deny</b> list ("this chest takes anything but diamonds") accepts an item without selecting it, so it must
     * not outrank consolidation the way a real dedication does — one such chest would otherwise win <b>every</b> item
     * in the warehouse and fill with a mix of everything, the mixing item-type grouping was added in M3 to prevent.
     * <p>
     * The end-to-end half of the M8 review fix: {@code JobPlannerTest} pins that the planner ranks
     * {@code FilterMatch.ALLOWED} like an unfiltered location, but only a real Create filter item proves that a deny
     * list is mapped to {@code ALLOWED} in the first place ({@code AisleFilters}). The far chest already holds the
     * incoming item, so nothing but the ranking can decide: the iron must consolidate there while the <b>nearer</b>
     * deny-list chest, which does accept iron, stays empty.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterDenyListDoesNotOutrankConsolidation(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(DEDICATED_A);
        aisle.storage(GENERAL, IRON.toStack(SEED));
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, SEED);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a deny list is ranked"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(DEDICATED_A, listFilter(true, DIAMOND.toStack()));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(helper, aisle, conserved, IRON.toStack(BATCH));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(GENERAL, IRON), (long) (SEED + BATCH),
                        "the iron consolidated where it already was"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(DEDICATED_A, IRON), 0L,
                            "a deny list that merely does not exclude the item is no dedication");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A controller restored from a save honours filters it has <b>not read back yet</b>.
     * <p>
     * Filters are not persisted — they live in the interfaces — so after a load the controller's cache is empty while
     * its restored locations are still queued for background snapshots, drained at {@code maxSnapshotsPerTick} per
     * tick. Dispatch already runs in that window, and a cache miss that answered "no filter" would mean "accepts
     * everything": the crane would fill chests the player dedicated to something else, permanently, because nothing is
     * ever re-shuffled. Every location here is dedicated to iron and gold is fed, so any misplacement is visible.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterColdCacheAfterReload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        for (RackPosition rack : RELOAD_RACKS)
            aisle.storage(rack);
        aisle.input(INPUT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reloaded controller plans"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(RELOAD_RACKS.size(), 1, 0))
                .thenExecute(() -> {
                    for (RackPosition rack : RELOAD_RACKS)
                        aisle.setStoreFilter(rack, listFilter(false, IRON.toStack()));
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), RELOAD_RACKS.size(),
                            "every location is dedicated to iron");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenExecute(() -> {
                    // The gold arrives and the controller is replaced by a copy loaded from its save in the same tick,
                    // so the very next dispatch plans with a cold filter cache.
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
                    feed(helper, aisle, conserved, GOLD.toStack(BATCH));
                    level.setBlockEntity(loadCopy(helper, controller, saved, WarehouseControllerBlockEntity.class));
                    helper.assertTrue(controller.isRemoved(), "the controller block entity was replaced");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    for (RackPosition rack : RELOAD_RACKS)
                        helper.assertValueEqual(aisle.storedAt(rack, GOLD), 0L,
                                "gold was stored into the iron chest at " + rack + " after a reload");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, GOLD), (long) BATCH,
                            "the input keeps the items instead");
                    helper.assertValueEqual(aisle.controller().filteredLocationCount(), RELOAD_RACKS.size(),
                            "and the reloaded controller knows every filter again");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The filter slot holds a real item, so breaking the interface must drop it (§8, item conservation). Create does
     * that in {@code FilteringBehaviour#destroy()}, reached through the block's {@code onRemove} — a path no test
     * pinned before M8, so a change to it would silently have deleted a player's configured filter on every break.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void filterDropsWhenBroken(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, BASE_Y, 3);
        helper.setBlock(pos.north(), Blocks.CHEST);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH));
        helper.assertTrue(interfaceAt(helper, pos).setStoreFilter(listFilter(false, IRON.toStack())),
                "the interface accepts a list filter");

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_INTERFACE.asItem(), pos, DROP_RADIUS);
                    helper.assertItemEntityPresent(AllItems.FILTER.get(), pos, DROP_RADIUS);
                    // Not just "a filter item": the configured one, still selecting what it selected.
                    assertFilters(helper, droppedFilter(helper, pos), "after the interface was broken");
                })
                .thenSucceed();
    }

    /**
     * Known limitation of §3.1.1, made visible instead of silently wrong: for an inventory several locations read (a
     * double chest here) the planner only asks the location that <b>counts</b> it, so a filter on the other one can do
     * nothing. It must therefore not be counted as an active partition, and its own interface says so in its goggles.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void filterOnSharedAliasIsNotCounted(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        // Chest halves along the aisle, fronts towards their interfaces: the lower x connects east, the higher x west.
        helper.setBlock(aisle.inventoryPos(DEDICATED_A), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        helper.setBlock(aisle.inventoryPos(DEDICATED_B), Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        aisle.placeInterface(DEDICATED_A);
        aisle.placeInterface(DEDICATED_B);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 0))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    RackPosition counting = controller.sharedInventoryOf(DEDICATED_A).orElse(DEDICATED_A);
                    helper.assertValueEqual(controller.locationsSharingInventory(counting).size(), 2,
                            "both interfaces read one double chest");
                    RackPosition alias = counting.equals(DEDICATED_A) ? DEDICATED_B : DEDICATED_A;

                    aisle.setStoreFilter(alias, listFilter(false, IRON.toStack()));
                    helper.assertValueEqual(controller.filteredLocationCount(), 0,
                            "a filter the planner never asks about is not counted as a partition");
                    helper.assertFalse(controller.isStorageFiltered(alias), "and is not reported as active");
                    helper.assertTrue(controller.isStorageFilterShadowed(alias), "the controller knows it is shadowed");
                    WarehouseInterfaceBlockEntity shadowed = aisle.interfaceAt(alias);
                    shadowed.onGoggleObserved();
                    helper.assertTrue(shadowed.isStoreFilterShadowed(), "the interface shows the hint in its goggles");

                    // The filter of the location that counts the inventory is the one that applies, and is counted.
                    aisle.setStoreFilter(counting, listFilter(false, GOLD.toStack()));
                    helper.assertValueEqual(controller.filteredLocationCount(), 1, "the effective filter is counted");
                    helper.assertTrue(controller.isStorageFiltered(counting), "and is reported as active");
                    helper.assertFalse(controller.isStorageFilterShadowed(counting), "it is not shadowed itself");
                })
                .thenSucceed();
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    /**
     * Asserts that {@code filter} still selects what the {@link #IRON} whitelist of these tests selects.
     * <p>
     * Deliberately behavioural instead of {@code ItemStack.isSameItemSameComponents}: Create's
     * {@code FilterItemStack.of} calls {@code trimFilterComponents}, which <b>removes</b> {@code ENCHANTMENTS} and
     * {@code ATTRIBUTE_MODIFIERS}. On a stack whose prototype carries those, {@code remove} records an explicit removal
     * in the component patch, so a stored filter never compares equal to a pristine one although it filters identically.
     * What has to survive a save or a sync is the <b>selection</b>, so that is what is checked.
     */
    private static void assertFilters(GameTestHelper helper, ItemStack filter, String context) {
        helper.assertFalse(filter.isEmpty(), context + ": the filter is still set");
        FilterItemStack resolved = FilterItemStack.of(filter.copy());
        helper.assertTrue(resolved.test(helper.getLevel(), IRON.toStack()), context + ": still accepts iron");
        helper.assertFalse(resolved.test(helper.getLevel(), GOLD.toStack()), context + ": still rejects gold");
    }

    /** A Create list filter holding {@code items}, as a whitelist or a blacklist. */
    private static ItemStack listFilter(boolean blacklist, ItemStack... items) {
        ItemStack filter = AllItems.FILTER.asStack();
        filter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(List.of(items)));
        if (blacklist)
            filter.set(AllDataComponents.FILTER_ITEMS_BLACKLIST, true);
        return filter;
    }

    /** A Create attribute filter that accepts everything {@code attribute} applies to. */
    private static ItemStack attributeFilter(ItemAttribute attribute) {
        ItemStack filter = AllItems.ATTRIBUTE_FILTER.asStack();
        filter.set(AllDataComponents.ATTRIBUTE_FILTER_WHITELIST_MODE, AttributeFilterWhitelistMode.WHITELIST_DISJ);
        filter.set(AllDataComponents.ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES,
                List.of(new ItemAttribute.ItemAttributeEntry(attribute, false)));
        return filter;
    }

    /** A Create package filter for one package address. */
    private static ItemStack packageFilter(String address) {
        ItemStack filter = AllItems.PACKAGE_FILTER.asStack();
        PackageItem.addAddress(filter, address);
        return filter;
    }

    /** A Create package addressed to {@code address} (the default box style, so the test is deterministic). */
    private static ItemStack addressedPackage(String address) {
        ItemStack box = PackageStyles.getDefaultBox();
        PackageItem.addAddress(box, address);
        return box;
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

    /** The Create filter item lying on the ground around {@code pos}; fails the test when there is none. */
    private static ItemStack droppedFilter(GameTestHelper helper, BlockPos pos) {
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM, pos, DROP_RADIUS)) {
            if (entity.getItem().is(AllItems.FILTER.get()))
                return entity.getItem().copy();
        }
        helper.fail("the configured filter item was not dropped", pos);
        return ItemStack.EMPTY;
    }
}
