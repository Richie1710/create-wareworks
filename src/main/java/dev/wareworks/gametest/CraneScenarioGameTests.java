package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.ReservationLedger;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.job.TravelTimeModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Scenario GameTests of the MVP gameplay loop: real player setups (hopper-fed input, hopper-emptied output, filter plus
 * redstone requests, several storage locations on both sides and levels) and the robustness cases of
 * {@code docs/warehouse-system.md} §8 that a player runs into (full warehouse, removed target, broken controller,
 * wrench during a job, competing outputs).
 * <p>
 * <b>Item conservation</b> is checked every tick of every scenario with an {@link ItemCensus} of the whole test area:
 * all inventories, station buffers, crane heads and item entities, by exact item identity (item and components). Items
 * only enter or leave the census where the test itself plays the player (filling a hopper or input, taking a stack out
 * of a chest), and the expectation changes in the same step.
 * <p>
 * Layouts use {@link AisleFixture} (aisle along +X at z = {@value #AISLE_Z}; the pair template adds a second aisle at
 * z = {@value #SECOND_AISLE_Z}). Motors run at {@value #TEST_RPM} RPM unless a scenario compares speeds.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class CraneScenarioGameTests {
    private static final int AISLE_Z = 3;
    private static final int SECOND_AISLE_Z = 9;
    private static final int RAILS = 6;
    private static final int LONG_RAILS = 12;

    private static final int TEST_RPM = 128;
    private static final int SLOW_RPM = 32;
    private static final int FAST_RPM = 256;

    private static final int SCENARIO_TIMEOUT_TICKS = 2400;
    /** Hoppers move one item per 8 ticks: 81 items in and out plus the crane trips. */
    private static final int FULL_LOOP_TIMEOUT_TICKS = 6000;
    /** A measured job may end at most this many ticks after the travel time model's trip (the one-tick COMPLETE phase). */
    private static final int TIMING_SLACK_TICKS = 2;
    /** Observation window of the full warehouse: several dispatch back-offs ({@code fullBackoffTicks} = 40). */
    private static final int FULL_OBSERVE_TICKS = 130;
    /** Observation windows for "nothing happens": at least two dispatch back-offs or hold retries. */
    private static final int QUIET_OBSERVE_TICKS = 100;
    private static final int SHORT_OBSERVE_TICKS = 40;

    private static final int STACK = 64;
    private static final int PEARL_STACK = 16;
    private static final int CHEST_SLOTS = 27;
    private static final int STORED_IRON = 32;
    private static final int REFILL_IRON = 16;
    private static final int SWORD_DAMAGE = 37;

    private static final RackPosition INPUT = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition NEAR = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition FAR = new RackPosition(5, 0, Side.LEFT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey PEARL = ItemKey.of(Items.ENDER_PEARL);

    private CraneScenarioGameTests() {
    }

    // --- 1. full loop ----------------------------------------------------------------------------------------------

    /** The full-loop output sits one level up, so a hopper below it can empty it into a chest. */
    private static final RackPosition LOOP_OUTPUT = new RackPosition(1, 1, Side.RIGHT);
    private static final int LOOP_FIRST_POSITION = 2;
    private static final int LOOP_LAST_POSITION = 4;
    private static final int LOOP_LEVELS = 2;
    private static final int OTHER_SWORD_DAMAGE = 5;

    /**
     * The MVP scenario end to end: a hopper feeds 64 cobblestone, 16 ender pearls (stack size 16) and two iron swords
     * with different damage (unstackable, components matter) into the input. The crane stores everything into an aisle
     * with twelve storage locations (positions 2-4, two levels, both sides): each key is consolidated in one location,
     * each item type has a location of its own, and both swords share theirs. Then the output requests cobblestone,
     * pearls and one of the swords with its filter and a redstone pulse each; exactly those items (that sword with its
     * damage) arrive in the output and a hopper below it moves them into a chest, while the other sword stays stored.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = FULL_LOOP_TIMEOUT_TICKS)
    public static void scenarioFullLoop(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        List<RackPosition> storage = new ArrayList<>();
        for (int x = LOOP_FIRST_POSITION; x <= LOOP_LAST_POSITION; x++) {
            for (int y = 0; y < LOOP_LEVELS; y++) {
                for (Side side : Side.values()) {
                    RackPosition rack = new RackPosition(x, y, side);
                    aisle.storage(rack);
                    storage.add(rack);
                }
            }
        }
        aisle.input(INPUT);
        aisle.output(LOOP_OUTPUT);
        BlockPos feedHopper = aisle.rackPos(INPUT).above();
        BlockPos drainHopper = aisle.rackPos(LOOP_OUTPUT).below();
        BlockPos drainChest = drainHopper.relative(aisle.sideDirection(LOOP_OUTPUT));
        BlockPos trigger = aisle.rackPos(LOOP_OUTPUT).above();
        helper.setBlock(feedHopper, AisleFixture.hopperState(Direction.DOWN));
        helper.setBlock(drainChest, Blocks.CHEST);
        helper.setBlock(drainHopper, AisleFixture.hopperState(aisle.sideDirection(LOOP_OUTPUT)));

        ItemKey sword = ItemKey.of(damagedSword(SWORD_DAMAGE));
        ItemKey otherSword = ItemKey.of(damagedSword(OTHER_SWORD_DAMAGE));
        List<ItemKey> fed = List.of(COBBLE, PEARL, sword, otherSword);
        List<ItemKey> requested = List.of(COBBLE, PEARL, sword);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "full loop"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(storage.size(), 1, 1))
                .thenExecute(() -> {
                    helper.assertFalse(sword.equals(ItemKey.of(Items.IRON_SWORD)), "the damage is part of the key");
                    helper.assertFalse(sword.equals(otherSword), "different damage, different keys");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    HopperBlockEntity hopper = hopperAt(helper, feedHopper);
                    for (int slot = 0; slot < fed.size(); slot++) {
                        ItemKey key = fed.get(slot);
                        int amount = key.getMaxStackSize();
                        hopper.setItem(slot, key.toStack(amount));
                        ItemCensus.change(expected, key, amount);
                    }
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(hopperAt(helper, feedHopper).isEmpty(), "feed hopper emptied");
                    helper.assertFalse(aisle.inputAt(INPUT).hasBufferedItems(), "input emptied");
                    for (ItemKey key : fed)
                        helper.assertValueEqual(storedTotal(aisle, storage, key), expected.get(key), "stored " + key);
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    Map<ItemKey, RackPosition> locationOf = new HashMap<>();
                    for (ItemKey key : fed) {
                        List<RackPosition> holding = storage.stream().filter(rack -> aisle.storedAt(rack, key) > 0)
                                .toList();
                        helper.assertValueEqual(holding.size(), 1, key + " is consolidated in one storage location");
                        locationOf.put(key, holding.getFirst());
                        helper.assertValueEqual(controller.countOf(key), expected.get(key), "stock index count of " + key);
                        helper.assertValueEqual(controller.stockIndex().locationsOf(key).size(), 1,
                                "stock index locations of " + key);
                    }
                    helper.assertValueEqual(locationOf.get(otherSword), locationOf.get(sword),
                            "swords with different damage share the location of their item type");
                    helper.assertValueEqual(Set.of(locationOf.get(COBBLE), locationOf.get(PEARL), locationOf.get(sword))
                            .size(), requested.size(), "each item type has a location of its own: " + locationOf);
                    helper.assertValueEqual(controller.countOf(ItemKey.of(Items.IRON_SWORD)), 0L,
                            "no undamaged sword indexed");

                    for (ItemKey key : requested)
                        aisle.requestAt(LOOP_OUTPUT, key.toStack(), key.getMaxStackSize(), trigger);
                    WarehouseOutputBlockEntity output = aisle.outputAt(LOOP_OUTPUT);
                    helper.assertTrue(output.lastRejection().isEmpty(), "requests accepted, refused: "
                            + output.lastRejection());
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controller
                            .requestsFor(aisle.absoluteRackPos(LOOP_OUTPUT));
                    helper.assertValueEqual(requests.size(), requested.size(), "one request per pulse");
                    for (int i = 0; i < requested.size(); i++) {
                        helper.assertValueEqual(requests.get(i).key(), requested.get(i), "request " + i + " item");
                        helper.assertValueEqual((long) requests.get(i).requested(), expected.get(requested.get(i)),
                                "request " + i + " amount");
                    }
                })
                .thenWaitUntil(() -> {
                    for (ItemKey key : requested)
                        helper.assertValueEqual(aisle.inventoryCount(drainChest, key), expected.get(key),
                                "arrived in the chest below the output: " + key);
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openRequestCount(), 0, "requests served");
                    helper.assertFalse(aisle.outputAt(LOOP_OUTPUT).hasBufferedItems(), "output emptied by the hopper");
                    for (ItemKey key : requested) {
                        helper.assertValueEqual(storedTotal(aisle, storage, key), 0L, "storage emptied of " + key);
                        helper.assertValueEqual(controller.countOf(key), 0L, "stock index emptied of " + key);
                    }
                    helper.assertValueEqual(aisle.inventoryCount(drainChest, otherSword), 0L, "the other sword stayed");
                    helper.assertValueEqual(storedTotal(aisle, storage, otherSword), 1L, "the other sword is still stored");
                    helper.assertValueEqual(controller.countOf(otherSword), 1L, "and indexed");
                })
                .thenSucceed();
    }

    // --- 2. throughput -------------------------------------------------------------------------------------------

    private static final RackPosition THROUGHPUT_TARGET = new RackPosition(5, 1, Side.LEFT);
    private static final RackPosition LAYOUT_NEAR = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition LAYOUT_FAR = new RackPosition(12, 3, Side.LEFT);

    /**
     * The same store job (input at position 0 to level 2, position 5) in two identical aisles: at {@value #FAST_RPM} RPM
     * it completes in fewer ticks than at {@value #SLOW_RPM} RPM, and both durations match the travel time model.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioThroughputRpm(GameTestHelper helper) {
        TimedStore slow = new TimedStore(new AisleFixture(helper, AISLE_Z, RAILS), THROUGHPUT_TARGET, SLOW_RPM);
        TimedStore fast = new TimedStore(new AisleFixture(helper, SECOND_AISLE_Z, RAILS), THROUGHPUT_TARGET, FAST_RPM);
        runTimedStores(helper, slow, fast, () -> helper.assertTrue(slow.ticks() > fast.ticks(),
                "the job at " + SLOW_RPM + " RPM (" + slow.ticks() + " ticks) must take longer than at " + FAST_RPM
                        + " RPM (" + fast.ticks() + " ticks)"));
    }

    /**
     * Layout matters: in two identical aisles at the same speed, a store job to a location far down the aisle and up the
     * mast takes longer than one to a location next to the input, and both match the travel time model.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioThroughputLayout(GameTestHelper helper) {
        TimedStore near = new TimedStore(new AisleFixture(helper, AISLE_Z, LONG_RAILS), LAYOUT_NEAR, TEST_RPM);
        TimedStore far = new TimedStore(new AisleFixture(helper, SECOND_AISLE_Z, LONG_RAILS), LAYOUT_FAR, TEST_RPM);
        runTimedStores(helper, near, far, () -> helper.assertTrue(far.ticks() > near.ticks(),
                "the far location (" + far.ticks() + " ticks) must take longer than the near one (" + near.ticks()
                        + " ticks)"));
    }

    /** A store job of {@value #STORED_IRON} iron timed from its assignment to the tick the crane is idle again. */
    private static final class TimedStore {
        final AisleFixture aisle;
        final RackPosition target;
        final int rpm;
        final Set<UUID> jobs = new HashSet<>();
        long assignedTick = -1;
        long finishedTick = -1;
        long modelTicks = -1;

        TimedStore(AisleFixture aisle, RackPosition target, int rpm) {
            this.aisle = aisle;
            this.target = target;
            this.rpm = rpm;
        }

        void build() {
            aisle.build(true);
            aisle.storage(target);
            aisle.input(INPUT);
        }

        void observe(GameTestHelper helper) {
            Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob();
            if (job.isPresent()) {
                jobs.add(job.get().id());
                if (assignedTick < 0)
                    assignedTick = helper.getTick();
            } else if (assignedTick >= 0 && finishedTick < 0) {
                finishedTick = helper.getTick();
            }
        }

        long ticks() {
            return finishedTick - assignedTick;
        }
    }

    private static void runTimedStores(GameTestHelper helper, TimedStore first, TimedStore second, Runnable comparison) {
        List<TimedStore> stores = List.of(first, second);
        stores.forEach(TimedStore::build);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "throughput");
            stores.forEach(store -> store.observe(helper));
        });

        helper.startSequence()
                .thenWaitUntil(() -> stores.forEach(store -> store.aisle.assertReady(1, 1, 0)))
                .thenExecute(() -> stores.forEach(store -> store.aisle.motor().generatedSpeed.setValue(store.rpm)))
                .thenWaitUntil(() -> stores.forEach(store -> {
                    StackerCraneBlockEntity dock = store.aisle.dock();
                    helper.assertValueEqual((int) Math.abs(dock.getSpeed()), store.rpm, "dock speed");
                    helper.assertTrue(dock.canAcceptJob(), "crane ready");
                }))
                .thenExecute(() -> {
                    for (TimedStore store : stores) {
                        StackerCraneBlockEntity dock = store.aisle.dock();
                        helper.assertValueEqual(dock.craneState().pose(), StackerCraneBlockEntity.HOME_POSE, "parked");
                        store.modelTicks = TravelTimeModel.tripTicks(dock.currentSpeeds(), WareworksConfig.transferTicks(),
                                0, 0, INPUT.x(), INPUT.y(), store.target.x(), store.target.y());
                        store.aisle.insertAll(store.aisle.handlerAt(store.aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    }
                    ItemCensus.change(expected, IRON, (long) STORED_IRON * stores.size());
                })
                .thenWaitUntil(() -> stores.forEach(store -> {
                    helper.assertTrue(store.finishedTick >= 0, "job finished at " + store.rpm + " RPM");
                    helper.assertValueEqual(store.aisle.storedAt(store.target, IRON), (long) STORED_IRON, "stored");
                    store.aisle.assertIdleAndEmpty();
                }))
                .thenExecute(() -> {
                    for (TimedStore store : stores) {
                        helper.assertValueEqual(store.jobs.size(), 1, "one job, no reroute");
                        helper.assertTrue(store.ticks() >= store.modelTicks
                                        && store.ticks() <= store.modelTicks + TIMING_SLACK_TICKS,
                                "measured " + store.ticks() + " ticks for a job the travel time model puts at "
                                        + store.modelTicks + " ticks (" + store.rpm + " RPM, target " + store.target + ")");
                    }
                    comparison.run();
                })
                .thenSucceed();
    }

    // --- 3. warehouse full ---------------------------------------------------------------------------------------

    private static final RackPosition FULL_A = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition FULL_B = new RackPosition(4, 0, Side.RIGHT);

    /**
     * Every storage location is full: the input keeps its items, the controller reports "warehouse full" and backs off,
     * and the crane neither starts a job nor moves. A player then takes a stack out of one chest; the crane resumes and
     * stores everything with exactly one job.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioWarehouseFull(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FULL_A, fullChest(COBBLE));
        aisle.storage(FULL_B, fullChest(COBBLE));
        aisle.input(INPUT);
        long fullCount = 2L * CHEST_SLOTS * STACK;
        Map<ItemKey, Long> expected = ItemCensus.of(COBBLE, fullCount);
        Set<UUID> jobs = new HashSet<>();
        boolean[] backedOff = {false};
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "warehouse full");
            aisle.dock().currentJob().ifPresent(job -> jobs.add(job.id()));
            aisle.controllerIfPresent().ifPresent(controller -> backedOff[0] |= controller.isBackingOff(
                    helper.getLevel().getGameTime()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 0);
                    helper.assertValueEqual(aisle.controller().countOf(COBBLE), fullCount, "indexed");
                })
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().lastPlanReason(),
                        Optional.of(NoJobReason.WAREHOUSE_FULL), "last planning result"))
                .thenIdle(FULL_OBSERVE_TICKS)
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertTrue(jobs.isEmpty(), "no job while the warehouse is full: " + jobs);
                    helper.assertTrue(backedOff[0], "the controller backs off after warehouse full");
                    helper.assertValueEqual(dock.craneState().pose(), StackerCraneBlockEntity.HOME_POSE, "crane parked");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), (long) STORED_IRON, "input keeps its items");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(), Optional.of(NoJobReason.WAREHOUSE_FULL),
                            "still full");
                    helper.assertTrue(aisle.controller().reservations().isEmpty(), "nothing reserved");
                    // A player takes one stack out of the second chest.
                    IItemHandler chest = aisle.handlerAt(aisle.inventoryPos(FULL_B));
                    helper.assertValueEqual(chest.extractItem(0, STACK, false).getCount(), STACK, "stack taken out");
                    ItemCensus.change(expected, COBBLE, -STACK);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(FULL_B, IRON), (long) STORED_IRON, "stored into the freed slot");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(jobs.size(), 1, "exactly one job after space was freed");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), 0L, "input emptied");
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) STORED_IRON, "indexed");
                })
                .thenSucceed();
    }

    private static final int BACKOFF_REQUEST = 16;

    /**
     * Review fix: the "warehouse full" back-off only holds back storing. A request made while the controller backs off
     * is served at once instead of waiting until the back-off ends.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioRetrieveDuringFullBackoff(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FULL_A, fullChest(COBBLE));
        aisle.storage(FULL_B, fullChest(COBBLE));
        aisle.input(INPUT);
        aisle.output(OUTPUT);
        long fullCount = 2L * CHEST_SLOTS * STACK;
        Map<ItemKey, Long> expected = ItemCensus.of(COBBLE, fullCount);
        boolean[] retrievedDuringBackoff = {false};
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "retrieve during back-off");
            boolean retrieving = aisle.dock().currentJob().map(job -> job.type() == JobType.RETRIEVE).orElse(false);
            aisle.controllerIfPresent().ifPresent(controller -> retrievedDuringBackoff[0] |= retrieving
                    && controller.isBackingOff(helper.getLevel().getGameTime()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 1);
                    helper.assertValueEqual(aisle.controller().countOf(COBBLE), fullCount, "indexed");
                })
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                    ItemCensus.change(expected, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.isBackingOff(helper.getLevel().getGameTime()), "backing off");
                    helper.assertValueEqual(controller.lastPlanReason(), Optional.of(NoJobReason.WAREHOUSE_FULL),
                            "warehouse full");
                })
                .thenExecute(() -> helper.assertTrue(aisle.controller().request(aisle.absoluteRackPos(OUTPUT), COBBLE,
                        BACKOFF_REQUEST).isAccepted(), "request accepted during the back-off"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT, COBBLE), (long) BACKOFF_REQUEST, "request served");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertTrue(retrievedDuringBackoff[0], "the retrieve ran while the controller backed off");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "request completed");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), (long) STORED_IRON,
                            "still no empty slot for the iron");
                })
                .thenSucceed();
    }

    // --- 4. requests beyond stock ----------------------------------------------------------------------------------

    private static final int STOCKED_DIAMONDS = 10;
    private static final int OVER_REQUEST = 32;
    private static final int LATE_DIAMONDS = 20;

    /**
     * A request for more than is in stock is clamped to the stock and served. Requests while nothing is in stock, and
     * while new stock is still in the input or in the crane's head, are refused ("not in stock", {@code §7.2}) and not
     * remembered: nothing is retrieved once that stock arrives. A new pulse after the arrival is accepted (clamped
     * again).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioRequestClampedAndLateStock(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.storage(FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        aisle.input(INPUT);
        aisle.output(OUTPUT);
        BlockPos trigger = aisle.rackPos(OUTPUT).relative(aisle.sideDirection(OUTPUT));
        BlockPos outputPos = aisle.absoluteRackPos(OUTPUT);
        Map<ItemKey, Long> expected = ItemCensus.of(DIAMOND, STOCKED_DIAMONDS);
        Set<UUID> retrieveJobs = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "requests beyond stock");
            aisle.dock().currentJob().filter(job -> job.type() == JobType.RETRIEVE)
                    .ifPresent(job -> retrieveJobs.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 1, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.requestAt(OUTPUT, DIAMOND.toStack(), OVER_REQUEST, trigger);
                    helper.assertTrue(aisle.outputAt(OUTPUT).lastRejection().isEmpty(), "clamped request accepted");
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = aisle.controller().requestsFor(outputPos);
                    helper.assertValueEqual(requests.size(), 1, "one request");
                    helper.assertValueEqual(requests.getFirst().requested(), STOCKED_DIAMONDS, "clamped to the stock");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT, DIAMOND), (long) STOCKED_DIAMONDS, "delivered");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "request served");
                    aisle.requestAt(OUTPUT, DIAMOND.toStack(), OVER_REQUEST, trigger);
                    assertRefusedNotInStock(helper, aisle, "with nothing in stock");
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), DIAMOND.toStack(LATE_DIAMONDS));
                    ItemCensus.change(expected, DIAMOND, LATE_DIAMONDS);
                    aisle.requestAt(OUTPUT, DIAMOND.toStack(), OVER_REQUEST, trigger);
                    assertRefusedNotInStock(helper, aisle, "while the stock waits in the input");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, DIAMOND, LATE_DIAMONDS))
                .thenExecute(() -> {
                    aisle.requestAt(OUTPUT, DIAMOND.toStack(), OVER_REQUEST, trigger);
                    assertRefusedNotInStock(helper, aisle, "while the stock is in the crane's head");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) LATE_DIAMONDS, "late stock arrived");
                    aisle.assertIdleAndEmpty();
                })
                .thenIdle(SHORT_OBSERVE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(retrieveJobs.size(), 1, "refused requests are not served later");
                    helper.assertValueEqual(aisle.stationCount(OUTPUT, DIAMOND), (long) STOCKED_DIAMONDS,
                            "nothing more delivered");
                    aisle.requestAt(OUTPUT, DIAMOND.toStack(), OVER_REQUEST, trigger);
                    helper.assertTrue(aisle.outputAt(OUTPUT).lastRejection().isEmpty(), "accepted after the arrival");
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = aisle.controller().requestsFor(outputPos);
                    helper.assertValueEqual(requests.size(), 1, "one request");
                    helper.assertValueEqual(requests.getFirst().requested(), LATE_DIAMONDS, "clamped to the new stock");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT, DIAMOND), (long) (STOCKED_DIAMONDS + LATE_DIAMONDS),
                            "late stock delivered");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "request served"))
                .thenSucceed();
    }

    private static void assertRefusedNotInStock(GameTestHelper helper, AisleFixture aisle, String when) {
        helper.assertValueEqual(aisle.outputAt(OUTPUT).lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                "refused " + when);
        helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "no request " + when);
        helper.assertValueEqual(aisle.controller().availableStock(DIAMOND), 0L, "nothing available " + when);
    }

    // --- 5. target removed -----------------------------------------------------------------------------------------

    /** The target interface is broken while the crane carries items to it: the items go to the other location. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioTargetRemovedReroutes(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.storage(FAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        Set<UUID> jobs = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "target removed, reroute");
            aisle.dock().currentJob().ifPresent(job -> jobs.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> startStore(aisle, expected))
                .thenWaitUntil(() -> assertCarryingTo(helper, aisle, NEAR, STORED_IRON))
                .thenExecute(() -> aisle.breakBlock(aisle.rackPos(NEAR)))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) STORED_IRON, "rerouted to the other location");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(jobs.size(), 1, "the same job was rerouted");
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), 0L, "nothing reached the removed location");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.storageLocations().size(), 1, "removed location left the aisle");
                    helper.assertValueEqual(controller.countOf(IRON), (long) STORED_IRON, "indexed");
                })
                .thenSucceed();
    }

    /**
     * The only storage location is broken while the crane carries items to it: the items go back into the input buffer,
     * and the crane does not start the same job again while no location exists.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioTargetRemovedReturnsToInput(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        Set<UUID> jobs = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "target removed, back to input");
            aisle.dock().currentJob().ifPresent(job -> jobs.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> startStore(aisle, expected))
                .thenWaitUntil(() -> assertCarryingTo(helper, aisle, NEAR, STORED_IRON))
                .thenExecute(() -> aisle.breakBlock(aisle.rackPos(NEAR)))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), (long) STORED_IRON, "back in the input");
                    aisle.assertIdleAndEmpty();
                })
                .thenIdle(QUIET_OBSERVE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(jobs.size(), 1, "no new job without a storage location");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), (long) STORED_IRON, "input keeps the items");
                    helper.assertValueEqual(aisle.dock().craneState().phase(), CranePhase.IDLE, "crane idle");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(), Optional.of(NoJobReason.WAREHOUSE_FULL),
                            "nowhere to store");
                })
                .thenSucceed();
    }

    /**
     * The target interface and the input are both broken while the crane carries items: nothing accepts them, so the
     * crane holds them (and retries without moving items). Placing the interface again at the same position resolves it
     * (review fix: hold retries used to exclude the position of the failed target for ever).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioTargetRemovedHoldsUntilLocation(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        Set<UUID> jobs = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "target removed, holding");
            aisle.dock().currentJob().ifPresent(job -> jobs.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> startStore(aisle, expected))
                .thenWaitUntil(() -> assertCarryingTo(helper, aisle, NEAR, STORED_IRON))
                .thenExecute(() -> {
                    helper.assertFalse(aisle.inputAt(INPUT).hasBufferedItems(), "the input was emptied by the pick");
                    aisle.breakBlock(aisle.rackPos(NEAR));
                    aisle.breakBlock(aisle.rackPos(INPUT));
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.HOLDING, IRON, STORED_IRON))
                .thenIdle(QUIET_OBSERVE_TICKS)
                .thenExecute(() -> {
                    aisle.assertCarrying(CranePhase.HOLDING, IRON, STORED_IRON);
                    helper.assertValueEqual(jobs.size(), 1, "still the same job");
                    helper.assertFalse(aisle.dock().canChangeAisleDirection(), "no rotation while holding");
                    aisle.placeInterface(NEAR);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON,
                            "stored in the location placed again at the failed target's position");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(jobs.size(), 1, "the same job");
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) STORED_IRON, "indexed");
                })
                .thenSucceed();
    }

    /** Iron in slot 0 of the only storage location of {@link #scenarioHoldingTargetFreedAgain}; cobblestone fills the rest. */
    private static final int HOLD_START_IRON = STORED_IRON;

    /**
     * Review fix: the only storage location fills up while the crane carries iron to it, and the input is broken, so the
     * crane holds the iron. A player then frees room for exactly the held iron in that location. The next hold retry
     * uses it: the failed target is excluded only right after it failed, and the crane's own reservation there does not
     * count against its own items.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioHoldingTargetFreedAgain(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        ItemStack[] contents = new ItemStack[CHEST_SLOTS];
        contents[0] = IRON.toStack(HOLD_START_IRON);
        for (int slot = 1; slot < CHEST_SLOTS; slot++)
            contents[slot] = COBBLE.toStack(STACK);
        aisle.storage(NEAR, contents);
        aisle.input(INPUT);
        long cobble = (long) (CHEST_SLOTS - 1) * STACK;
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, HOLD_START_IRON, COBBLE, cobble);
        Set<UUID> jobs = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "holding, target freed");
            aisle.dock().currentJob().ifPresent(job -> jobs.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 1, 0);
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) HOLD_START_IRON, "indexed");
                })
                .thenExecute(() -> startStore(aisle, expected))
                .thenWaitUntil(() -> assertCarryingTo(helper, aisle, NEAR, STORED_IRON))
                .thenExecute(() -> {
                    // A player fills the last room of the target, and the input is broken: nothing takes the iron.
                    aisle.insertAll(aisle.handlerAt(aisle.inventoryPos(NEAR)), IRON.toStack(STACK - HOLD_START_IRON));
                    ItemCensus.change(expected, IRON, STACK - HOLD_START_IRON);
                    helper.assertFalse(aisle.inputAt(INPUT).hasBufferedItems(), "the input was emptied by the pick");
                    aisle.breakBlock(aisle.rackPos(INPUT));
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.HOLDING, IRON, STORED_IRON))
                .thenIdle(QUIET_OBSERVE_TICKS)
                .thenExecute(() -> {
                    aisle.assertCarrying(CranePhase.HOLDING, IRON, STORED_IRON);
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STACK, "the target is full");
                    // The player takes out exactly as much iron as the crane holds.
                    IItemHandler chest = aisle.handlerAt(aisle.inventoryPos(NEAR));
                    helper.assertValueEqual(chest.extractItem(0, STORED_IRON, false).getCount(), STORED_IRON,
                            "iron taken out");
                    ItemCensus.change(expected, IRON, -STORED_IRON);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STACK, "the held iron went into the freed room");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(jobs.size(), 1, "the same job");
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) STACK, "indexed");
                })
                .thenSucceed();
    }

    // --- 6. controller broken ------------------------------------------------------------------------------------

    /**
     * The controller is broken while the crane carries items: the crane finishes the job (its target is valid) and starts
     * nothing new without controller. A new controller plans again; it is broken during that job too, and this time the
     * target is broken as well, so the crane holds the items. Placing another controller adopts the job (reservations
     * rebuilt from it), which then reroutes the items to the other location.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioControllerBrokenMidJob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(NEAR);
        aisle.storage(FAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        Set<UUID> jobs = new HashSet<>();
        RackPosition[] firstTarget = new RackPosition[1];
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "controller broken");
            aisle.dock().currentJob().ifPresent(job -> jobs.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> startStore(aisle, expected))
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> {
                    firstTarget[0] = aisle.dock().currentJob().map(TransportJob::target).orElse(null);
                    helper.assertTrue(firstTarget[0] != null, "job has a target");
                    aisle.breakBlock(aisle.controllerPos());
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(firstTarget[0], IRON), (long) STORED_IRON,
                            "the crane finished the job without controller");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertFalse(aisle.dock().isControllerLinked(), "no controller linked");
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(REFILL_IRON));
                    ItemCensus.change(expected, IRON, REFILL_IRON);
                })
                .thenIdle(SHORT_OBSERVE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(jobs.size(), 1, "no new job without controller");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), (long) REFILL_IRON, "input untouched");
                    aisle.placeController();
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, REFILL_IRON))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.dock().currentJob().map(TransportJob::target),
                            Optional.of(firstTarget[0]), "the new controller consolidates");
                    aisle.breakBlock(aisle.controllerPos());
                    aisle.breakBlock(aisle.rackPos(firstTarget[0]));
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.HOLDING, IRON, REFILL_IRON))
                .thenIdle(SHORT_OBSERVE_TICKS)
                .thenExecute(() -> {
                    aisle.assertCarrying(CranePhase.HOLDING, IRON, REFILL_IRON);
                    aisle.placeController();
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.isLinkedTo(helper.absolutePos(aisle.dockPos())), "new controller linked");
                    Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob();
                    helper.assertTrue(job.isPresent(), "the crane still has its job");
                    helper.assertValueEqual(controller.reservations().reservations(),
                            ReservationLedger.reservationsFor(job.get()), "reservations adopted from the crane's job");
                })
                .thenWaitUntil(() -> {
                    RackPosition other = firstTarget[0].equals(NEAR) ? FAR : NEAR;
                    helper.assertValueEqual(aisle.storedAt(other, IRON), (long) REFILL_IRON,
                            "held items rerouted by the adopting controller");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> helper.assertValueEqual(jobs.size(), 2, "two jobs in total"))
                .thenSucceed();
    }

    // --- 7. dock rotation ----------------------------------------------------------------------------------------

    /**
     * The wrench cannot turn the dock while a job runs (travelling to the source, carrying items); once the crane is idle
     * with an empty head it can, and the controller then loses the dock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioDockRotationBlocked(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FAR);
        aisle.input(INPUT);
        Map<ItemKey, Long> expected = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "dock rotation"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> startStore(aisle, expected))
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob().isPresent(), "job assigned"))
                .thenExecute(() -> {
                    aisle.wrenchTopFace(aisle.dockPos());
                    assertDockFacing(helper, aisle, AisleFixture.AISLE, "before the pick");
                    helper.assertTrue(aisle.dock().currentJob().isPresent(), "job kept");
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> {
                    aisle.wrenchTopFace(aisle.dockPos());
                    assertDockFacing(helper, aisle, AisleFixture.AISLE, "while carrying");
                    aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) STORED_IRON, "job completed");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    aisle.wrenchTopFace(aisle.dockPos());
                    assertDockFacing(helper, aisle, AisleFixture.AISLE.getClockWise(), "when idle");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().status(),
                        ControllerStatus.DOCK_MISALIGNED, "the controller lost the turned dock and says why"))
                .thenSucceed();
    }

    private static void assertDockFacing(GameTestHelper helper, AisleFixture aisle, Direction facing, String when) {
        helper.assertValueEqual(aisle.dock().facing(), facing, "dock facing " + when);
    }

    // --- 8. two outputs ------------------------------------------------------------------------------------------

    private static final RackPosition OUTPUT_ONE = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_TWO = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition STOCK_A = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition STOCK_B = new RackPosition(4, 0, Side.LEFT);
    private static final int STOCK_PER_LOCATION = 12;
    private static final int FIRST_REQUEST = 20;
    private static final int SECOND_REQUEST = 10;

    /**
     * Two outputs request the same item: the older request (20 of 24 diamonds, spread over two locations) is served
     * completely before the younger one; the younger request, made while the first job carries diamonds, is clamped to
     * the 4 not promised yet. No output ever holds more than it requested, and the stock ends empty.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioTwoOutputsFifo(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCK_A, DIAMOND.toStack(STOCK_PER_LOCATION));
        aisle.storage(STOCK_B, DIAMOND.toStack(STOCK_PER_LOCATION));
        aisle.output(OUTPUT_ONE);
        aisle.output(OUTPUT_TWO);
        BlockPos triggerOne = aisle.rackPos(OUTPUT_ONE).relative(aisle.sideDirection(OUTPUT_ONE));
        BlockPos triggerTwo = aisle.rackPos(OUTPUT_TWO).relative(aisle.sideDirection(OUTPUT_TWO));
        int stock = 2 * STOCK_PER_LOCATION;
        int secondGranted = stock - FIRST_REQUEST;
        Map<ItemKey, Long> expected = ItemCensus.of(DIAMOND, stock);
        List<TransportJob<ItemKey, RackPosition>> jobs = new ArrayList<>();
        boolean[] secondRequested = {false};
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "two outputs");
            long one = aisle.stationCount(OUTPUT_ONE, DIAMOND);
            long two = aisle.stationCount(OUTPUT_TWO, DIAMOND);
            helper.assertTrue(one <= FIRST_REQUEST, "first output over-delivered: " + one);
            helper.assertTrue(two <= (secondRequested[0] ? secondGranted : 0), "second output over-delivered: " + two);
            if (two > 0)
                helper.assertValueEqual(one, (long) FIRST_REQUEST, "the older request is served first");
            aisle.dock().currentJob().filter(job -> jobs.stream().noneMatch(seen -> seen.id().equals(job.id())))
                    .ifPresent(jobs::add);
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 2);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) stock, "indexed");
                })
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.requestAt(OUTPUT_ONE, DIAMOND.toStack(), FIRST_REQUEST, triggerOne);
                    helper.assertValueEqual(aisle.controller().requestedFor(aisle.absoluteRackPos(OUTPUT_ONE)),
                            (long) FIRST_REQUEST, "first request accepted in full");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().craneState().phase(), CranePhase.TRAVEL_TO_TARGET,
                        "first job carries diamonds"))
                .thenExecute(() -> {
                    aisle.requestAt(OUTPUT_TWO, DIAMOND.toStack(), SECOND_REQUEST, triggerTwo);
                    secondRequested[0] = true;
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.requestedFor(aisle.absoluteRackPos(OUTPUT_TWO)),
                            (long) secondGranted, "second request clamped to what is not promised");
                    helper.assertValueEqual(controller.availableStock(DIAMOND), 0L, "everything promised");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_ONE, DIAMOND), (long) FIRST_REQUEST, "first output");
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_TWO, DIAMOND), (long) secondGranted, "second output");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openRequestCount(), 0, "both requests served");
                    helper.assertValueEqual(controller.countOf(DIAMOND), 0L, "stock used up");
                    int firstTwoIndex = -1;
                    for (int i = 0; i < jobs.size(); i++) {
                        boolean toTwo = jobs.get(i).target().equals(OUTPUT_TWO);
                        if (toTwo && firstTwoIndex < 0)
                            firstTwoIndex = i;
                        helper.assertFalse(!toTwo && firstTwoIndex >= 0,
                                "a job for the first output after the second output's job: " + jobs);
                    }
                    helper.assertTrue(firstTwoIndex > 0, "jobs for both outputs, first output first: " + jobs);
                })
                .thenSucceed();
    }

    /**
     * The first output is broken while the crane carries diamonds for it: its request is cancelled, and the carried
     * diamonds go back into storage instead of into the second output, whose own (clamped) request is then served
     * exactly. The second output never holds more than it requested.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = SCENARIO_TIMEOUT_TICKS)
    public static void scenarioTwoOutputsFirstRemoved(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STOCK_A, DIAMOND.toStack(STOCK_PER_LOCATION));
        aisle.storage(STOCK_B, DIAMOND.toStack(STOCK_PER_LOCATION));
        aisle.output(OUTPUT_ONE);
        aisle.output(OUTPUT_TWO);
        BlockPos triggerOne = aisle.rackPos(OUTPUT_ONE).relative(aisle.sideDirection(OUTPUT_ONE));
        BlockPos triggerTwo = aisle.rackPos(OUTPUT_TWO).relative(aisle.sideDirection(OUTPUT_TWO));
        int stock = 2 * STOCK_PER_LOCATION;
        int secondGranted = stock - FIRST_REQUEST;
        Map<ItemKey, Long> expected = ItemCensus.of(DIAMOND, stock);
        boolean[] secondRequested = {false};
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "first output removed");
            long two = aisle.stationCount(OUTPUT_TWO, DIAMOND);
            helper.assertTrue(two <= (secondRequested[0] ? secondGranted : 0), "second output over-delivered: " + two);
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 2);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) stock, "indexed");
                })
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.requestAt(OUTPUT_ONE, DIAMOND.toStack(), FIRST_REQUEST, triggerOne);
                })
                .thenWaitUntil(() -> {
                    aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, DIAMOND, STOCK_PER_LOCATION);
                    helper.assertValueEqual(aisle.dock().currentJob().map(TransportJob::target), Optional.of(OUTPUT_ONE),
                            "carrying for the first output");
                })
                .thenExecute(() -> {
                    aisle.requestAt(OUTPUT_TWO, DIAMOND.toStack(), SECOND_REQUEST, triggerTwo);
                    secondRequested[0] = true;
                    helper.assertValueEqual(aisle.controller().requestedFor(aisle.absoluteRackPos(OUTPUT_TWO)),
                            (long) secondGranted, "second request clamped");
                    aisle.breakBlock(aisle.rackPos(OUTPUT_ONE));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT_TWO, DIAMOND), (long) secondGranted,
                            "second output served exactly");
                    helper.assertValueEqual(aisle.storedAt(STOCK_A, DIAMOND) + aisle.storedAt(STOCK_B, DIAMOND),
                            (long) (stock - secondGranted), "the carried diamonds went back into storage");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.openRequestCount(), 0, "no request left");
                    helper.assertValueEqual(controller.countOf(DIAMOND), (long) (stock - secondGranted), "indexed");
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** Motor at test speed and {@value #STORED_IRON} iron into the input. */
    private static void startStore(AisleFixture aisle, Map<ItemKey, Long> expected) {
        aisle.motor().generatedSpeed.setValue(TEST_RPM);
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
        ItemCensus.change(expected, IRON, STORED_IRON);
    }

    private static void assertCarryingTo(GameTestHelper helper, AisleFixture aisle, RackPosition target, int amount) {
        aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, amount);
        helper.assertValueEqual(aisle.dock().currentJob().map(TransportJob::target), Optional.of(target), "job target");
    }

    private static long storedTotal(AisleFixture aisle, List<RackPosition> storage, ItemKey key) {
        long total = 0;
        for (RackPosition rack : storage)
            total += aisle.storedAt(rack, key);
        return total;
    }

    private static ItemStack damagedSword(int damage) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.set(DataComponents.DAMAGE, damage);
        return sword;
    }

    private static ItemStack[] fullChest(ItemKey key) {
        ItemStack[] stacks = new ItemStack[CHEST_SLOTS];
        for (int slot = 0; slot < CHEST_SLOTS; slot++)
            stacks[slot] = key.toStack(key.getMaxStackSize());
        return stacks;
    }

    private static HopperBlockEntity hopperAt(GameTestHelper helper, BlockPos pos) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof HopperBlockEntity hopper)) {
            helper.fail("missing hopper", pos);
            return null;
        }
        return hopper;
    }
}
