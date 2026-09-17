package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of request batching (M7, {@code docs/warehouse-system.md} §7.2 "merging", ADR-020): a repeated request for an
 * item a station already waits for grows that request instead of queueing a second one, so the crane makes <b>one</b>
 * trip instead of one per click.
 * <p>
 * Covered end to end, each with the item conservation invariant on every tick ({@link ItemCensus}):
 * <ul>
 * <li>{@code terminalclicksmergeintoonetrip} — the reported bug: ten clicks of one item before the crane starts are one
 * open request and one crane trip delivering ten;</li>
 * <li>{@code terminalinflightrequestmerges} — a request added while a job for it is already running: nothing is lost and
 * the rest arrives with the next trip (at most two, which is physical: the running trip cannot grow);</li>
 * <li>{@code twoterminalskeeptheirownrequests} — merging is per destination, and the older request is still served
 * first;</li>
 * <li>{@code outputpulsesmergeintoonerequest} — the same for repeated redstone pulses at a warehouse output, while a
 * different item at the same output stays a request of its own;</li>
 * <li>{@code outputpulsesstaybounded} — the bound the redstone path needs once pulses merge: a pulse clock grows its
 * request only up to what its request slots could hold before, and the rest of the stock stays available to the other
 * stations of the aisle;</li>
 * <li>{@code storebatchesbufferedstacks} — the input side needs no merging: the planner already takes the whole buffered
 * amount of one item key per trip, so eight small stacks are one trip as well.</li>
 * </ul>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1 at z = 3 along +X,
 * {@value #RAILS} rails), a chest behind an interface as the only storage location, and the delivery stations at rack
 * positions beside the dock.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RequestBatchingGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition SECOND_TERMINAL_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition SECOND_OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    /** Near the dock: short trips for the tests that only count them. */
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    /** Far from the dock: a trip long enough to add a request while the crane is on its way. */
    private static final RackPosition FAR_STORAGE_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int STOCK = 20;
    /** Ten clicks of one item: the bug this milestone fixes. */
    private static final int CLICKS = 10;
    private static final int IN_FLIGHT_EXTRA = 9;
    private static final int MAX_TRIPS_IN_FLIGHT = 2;
    private static final int PER_TERMINAL = 4;
    private static final int PULSE_AMOUNT = 8;
    private static final int PULSED_IRON = 16;
    /** More than one output's whole request bound, so the bound refuses a pulse before the stock clamp does. */
    private static final int BOUNDED_STOCK = 64;
    private static final int STORE_STACKS = 8;
    private static final int STORE_STACK_SIZE = 4;

    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);

    private RequestBatchingGameTests() {
    }

    // --- terminal ----------------------------------------------------------------------------------------------------

    /**
     * The reported bug: clicking the same item ten times at a terminal made the crane fetch ten times, one item per
     * trip. The ten requests are now one open request of ten, which one crane trip delivers (the carry limit is a full
     * stack), and every answer names the pending total so the screen can show it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalClicksMergeIntoOneTrip(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(STOCK));
        aisle.terminal(TERMINAL_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, STOCK);
        Set<UUID> trips = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while the merged request is served");
            aisle.dock().currentJob().ifPresent(job -> trips.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    for (int click = 1; click <= CLICKS; click++) {
                        RequestResult result = terminal.requestFromTerminal(player, DIAMOND, 1);
                        helper.assertTrue(result.isAccepted(), "click " + click + " accepted: " + result);
                        helper.assertValueEqual(result.granted(), 1, "click " + click + " granted");
                        helper.assertValueEqual(result.merged(), click > 1, "click " + click + " merged");
                        helper.assertValueEqual(result.pending(), click, "pending total after click " + click);
                    }
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    BlockPos terminalPos = aisle.absoluteRackPos(TERMINAL_RACK);
                    helper.assertValueEqual(controller.openRequestCount(), 1, "ten clicks are one open request");
                    helper.assertValueEqual(controller.requestsFor(terminalPos).size(), 1, "one request at the terminal");
                    helper.assertValueEqual(controller.requestedFor(terminalPos), (long) CLICKS, "the merged amount");
                    helper.assertValueEqual(controller.availableStock(DIAMOND), (long) (STOCK - CLICKS),
                            "only the merged total is promised");
                    helper.assertValueEqual(terminal.status().requestsHere(), 1, "the screen shows one open request");
                    helper.assertValueEqual(terminal.status().openRequests(), 1, "and one for the whole aisle");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), (long) CLICKS,
                        "all ten delivered into the terminal"))
                .thenExecute(() -> {
                    helper.assertValueEqual(trips.size(), 1, "one crane trip for ten clicks: " + trips);
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) (STOCK - CLICKS),
                            "left in the chest");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "the request is finished");
                    ItemCensus.assertEquals(helper, conserved, "after the delivery");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * A request added while a job for it is already in flight: the running trip cannot grow (the items are in the
     * grabber), so the added amount is served by the next trip. Nothing is lost, everything arrives, and it takes at
     * most two trips.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalInFlightRequestMerges(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FAR_STORAGE_RACK, DIAMOND.toStack(STOCK));
        aisle.terminal(TERMINAL_RACK);
        int total = 1 + IN_FLIGHT_EXTRA;
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, STOCK);
        Set<UUID> trips = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while the in-flight request grows");
            aisle.dock().currentJob().ifPresent(job -> trips.add(job.id()));
            helper.assertTrue(aisle.stationCount(TERMINAL_RACK, DIAMOND) <= total, "the terminal was over-delivered");
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    RequestResult first = aisle.terminalAt(TERMINAL_RACK)
                            .requestFromTerminal(playerAt(helper, aisle.rackPos(TERMINAL_RACK)), DIAMOND, 1);
                    helper.assertTrue(first.isAccepted() && !first.merged(), "the first request: " + first);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().craneState().heldAmount() > 0,
                        "the crane picked the first diamond"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    RequestResult more = terminal.requestFromTerminal(playerAt(helper, aisle.rackPos(TERMINAL_RACK)),
                            DIAMOND, IN_FLIGHT_EXTRA);
                    helper.assertTrue(more.isAccepted(), "the added request: " + more);
                    helper.assertTrue(more.merged(), "it merged into the request the crane is already serving");
                    helper.assertValueEqual(more.granted(), IN_FLIGHT_EXTRA, "granted");
                    helper.assertValueEqual(more.pending(), total, "pending total, the item in the grabber included");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 1, "still one open request");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), (long) total,
                        "everything delivered"))
                .thenExecute(() -> {
                    helper.assertTrue(trips.size() <= MAX_TRIPS_IN_FLIGHT,
                            "the running trip plus one more: " + trips);
                    helper.assertValueEqual(aisle.storedAt(FAR_STORAGE_RACK, DIAMOND), (long) (STOCK - total),
                            "left in the chest");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "the request is finished");
                    ItemCensus.assertEquals(helper, conserved, "after the delivery");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Merging is per destination: two terminals asking for the same item keep their own requests, each gets exactly what
     * it asked for, and the older request is served first (FIFO fairness is not changed by merging).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void twoTerminalsKeepTheirOwnRequests(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(STOCK));
        aisle.terminal(TERMINAL_RACK);
        aisle.terminal(SECOND_TERMINAL_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, STOCK);
        List<TransportJob<ItemKey, RackPosition>> trips = new ArrayList<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while two terminals are served");
            helper.assertTrue(aisle.stationCount(TERMINAL_RACK, DIAMOND) <= PER_TERMINAL, "first terminal over-delivered");
            helper.assertTrue(aisle.stationCount(SECOND_TERMINAL_RACK, DIAMOND) <= PER_TERMINAL,
                    "second terminal over-delivered");
            aisle.dock().currentJob().filter(job -> trips.stream().noneMatch(seen -> seen.id().equals(job.id())))
                    .ifPresent(trips::add);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 2))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    // The first terminal clicks twice: one request of four. The second terminal asks once for four.
                    request(helper, aisle, TERMINAL_RACK, PER_TERMINAL / 2, false);
                    request(helper, aisle, TERMINAL_RACK, PER_TERMINAL / 2, true);
                    request(helper, aisle, SECOND_TERMINAL_RACK, PER_TERMINAL, false);

                    helper.assertValueEqual(controller.openRequestCount(), 2, "one request per terminal");
                    helper.assertValueEqual(controller.requestedFor(aisle.absoluteRackPos(TERMINAL_RACK)),
                            (long) PER_TERMINAL, "the first terminal's merged amount");
                    helper.assertValueEqual(controller.requestedFor(aisle.absoluteRackPos(SECOND_TERMINAL_RACK)),
                            (long) PER_TERMINAL, "the second terminal's amount");
                    helper.assertValueEqual(controller.oldestOpenRequest().map(RetrievalRequest::destination),
                            java.util.Optional.of(aisle.absoluteRackPos(TERMINAL_RACK)),
                            "the merged request kept its queue position");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), (long) PER_TERMINAL,
                            "first terminal served");
                    helper.assertValueEqual(aisle.stationCount(SECOND_TERMINAL_RACK, DIAMOND), (long) PER_TERMINAL,
                            "second terminal served");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(trips.size(), 2, "one trip per terminal: " + trips);
                    helper.assertValueEqual(trips.getFirst().target(), TERMINAL_RACK,
                            "the older request is served first");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "both requests finished");
                    ItemCensus.assertEquals(helper, conserved, "after both deliveries");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- redstone output ---------------------------------------------------------------------------------------------

    /**
     * The same merging on the redstone path: repeated pulses at one warehouse output grow its open request instead of
     * queueing one per pulse, while a pulse for a different item is a request of its own. The crane is unpowered here,
     * so the queue can be read before anything is served.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void outputPulsesMergeIntoOneRequest(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(STOCK), IRON.toStack(PULSED_IRON));
        aisle.output(OUTPUT_RACK);
        BlockPos trigger = aisle.rackPos(OUTPUT_RACK).relative(aisle.sideDirection(OUTPUT_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseOutputBlockEntity output = aisle.outputAt(OUTPUT_RACK);
                    BlockPos outputPos = aisle.absoluteRackPos(OUTPUT_RACK);

                    aisle.requestAt(OUTPUT_RACK, DIAMOND.toStack(), PULSE_AMOUNT, trigger);
                    helper.assertValueEqual(controller.requestsFor(outputPos).size(), 1, "one request after one pulse");
                    UUID id = controller.requestsFor(outputPos).getFirst().id();

                    aisle.requestAt(OUTPUT_RACK, DIAMOND.toStack(), PULSE_AMOUNT, trigger);
                    List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(outputPos);
                    helper.assertValueEqual(requests.size(), 1, "the second pulse merged instead of queueing");
                    helper.assertValueEqual(requests.getFirst().id(), id, "the open request kept its identity");
                    helper.assertValueEqual(requests.getFirst().remaining(), 2 * PULSE_AMOUNT, "the merged amount");
                    helper.assertTrue(output.lastRejection().isEmpty(), "accepted: " + output.lastRejection());
                    helper.assertValueEqual(controller.availableStock(DIAMOND), (long) (STOCK - 2 * PULSE_AMOUNT),
                            "only the merged total is promised");

                    // Another item at the same output is a request of its own; the queue stays first in, first out.
                    aisle.requestAt(OUTPUT_RACK, IRON.toStack(), PULSE_AMOUNT, trigger);
                    helper.assertValueEqual(controller.requestsFor(outputPos).size(), 2, "a different item, a new request");
                    helper.assertValueEqual(controller.oldestOpenRequest().map(RetrievalRequest::id),
                            java.util.Optional.of(id), "the diamonds stay first in the queue");

                    output.onGoggleObserved();
                    helper.assertValueEqual(output.summary().openRequests(), 2, "output goggles: open requests");
                    helper.assertValueEqual(output.summary().requestedItems(), (long) (3 * PULSE_AMOUNT),
                            "output goggles: requested items");
                })
                .thenSucceed();
    }

    /**
     * Merging removed the bound the per-output slot cap used to give the redstone path, so the output brings its own
     * ({@link WarehouseOutputBlockEntity#maxRequestAmount()}): {@code maxOpenRequestsPerOutput} times the filter amount,
     * exactly what this output's request slots could promise before pulses merged. A pulse clock beyond it is refused
     * with {@code REQUEST_FULL} instead of growing one request until it holds the aisle's whole stock of that item —
     * which would show every other station "not in stock" for it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void outputPulsesStayBounded(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(BOUNDED_STOCK));
        aisle.output(OUTPUT_RACK);
        aisle.output(SECOND_OUTPUT_RACK);
        BlockPos trigger = aisle.rackPos(OUTPUT_RACK).relative(aisle.sideDirection(OUTPUT_RACK));
        BlockPos secondTrigger = aisle.rackPos(SECOND_OUTPUT_RACK).relative(aisle.sideDirection(SECOND_OUTPUT_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 2))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseOutputBlockEntity output = aisle.outputAt(OUTPUT_RACK);
                    BlockPos outputPos = aisle.absoluteRackPos(OUTPUT_RACK);
                    int pulses = Math.max(RequestQueue.MIN_OPEN_REQUESTS, WareworksConfig.maxOpenRequestsPerOutput());
                    int bound = pulses * PULSE_AMOUNT;
                    helper.assertTrue(BOUNDED_STOCK > bound, "the test stock must outlast the bound, or the stock "
                            + "clamp would refuse the clock before the bound does");

                    for (int pulse = 1; pulse <= pulses; pulse++) {
                        aisle.requestAt(OUTPUT_RACK, IRON.toStack(), PULSE_AMOUNT, trigger);
                        helper.assertValueEqual(controller.requestsFor(outputPos).size(), 1,
                                "pulse " + pulse + ": still one request");
                        helper.assertValueEqual(controller.requestedFor(outputPos), (long) (pulse * PULSE_AMOUNT),
                                "pulse " + pulse + ": the merged amount");
                    }
                    helper.assertValueEqual(output.maxRequestAmount(), bound, "the bound this output applies");

                    aisle.requestAt(OUTPUT_RACK, IRON.toStack(), PULSE_AMOUNT, trigger);
                    helper.assertValueEqual(output.lastRejection(), java.util.Optional.of(RequestRejection.REQUEST_FULL),
                            "the pulse beyond the bound is refused");
                    helper.assertValueEqual(controller.requestedFor(outputPos), (long) bound,
                            "and promised nothing beyond it");
                    helper.assertValueEqual(controller.availableStock(IRON), (long) (BOUNDED_STOCK - bound),
                            "the stock the clock did not promise is still free");

                    // The point of the bound: another station of the aisle can still get that item.
                    aisle.requestAt(SECOND_OUTPUT_RACK, IRON.toStack(), PULSE_AMOUNT, secondTrigger);
                    WarehouseOutputBlockEntity second = aisle.outputAt(SECOND_OUTPUT_RACK);
                    helper.assertTrue(second.lastRejection().isEmpty(),
                            "the second output was served: " + second.lastRejection());
                    helper.assertValueEqual(controller.requestedFor(aisle.absoluteRackPos(SECOND_OUTPUT_RACK)),
                            (long) PULSE_AMOUNT, "its own request");
                    helper.assertValueEqual(controller.openRequestCount(), 2, "one request per output");
                })
                .thenSucceed();
    }

    // --- input side --------------------------------------------------------------------------------------------------

    /**
     * The input side needs no merging of its own and never made one trip per stack: a store job takes the whole buffered
     * amount of one item key (up to the carry limit), so eight stacks of four iron that arrive in separate slots are
     * stored in a single trip. Documented in {@code docs/warehouse-system.md} §7.2 as the reason the same bug class does
     * not exist there.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void storeBatchesBufferedStacks(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        int total = STORE_STACKS * STORE_STACK_SIZE;
        Map<ItemKey, Long> conserved = ItemCensus.of();
        Set<UUID> trips = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while the buffered stacks are stored");
            aisle.dock().currentJob().ifPresent(job -> trips.add(job.id()));
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    IItemHandler buffer = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    helper.assertTrue(buffer.getSlots() >= STORE_STACKS, "the input buffer has a slot per stack");
                    for (int slot = 0; slot < STORE_STACKS; slot++) {
                        helper.assertTrue(buffer.insertItem(slot, IRON.toStack(STORE_STACK_SIZE), false).isEmpty(),
                                "stack " + slot + " accepted");
                    }
                    ItemCensus.change(conserved, IRON, total);
                    helper.assertValueEqual(aisle.inputAt(INPUT_RACK).bufferedItems().count(IRON), (long) total,
                            "eight small stacks in the buffer");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) total,
                        "everything stored"))
                .thenExecute(() -> {
                    helper.assertValueEqual(trips.size(), 1, "one trip for eight stacks of the same item: " + trips);
                    helper.assertFalse(aisle.inputAt(INPUT_RACK).hasBufferedItems(), "the input is empty");
                    ItemCensus.assertEquals(helper, conserved, "after storing");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** One terminal request of {@code amount} diamonds that must be accepted, and must (not) have been merged. */
    private static void request(GameTestHelper helper, AisleFixture aisle, RackPosition rack, int amount,
            boolean expectMerged) {
        RequestResult result = aisle.terminalAt(rack).requestFromTerminal(playerAt(helper, aisle.rackPos(rack)), DIAMOND,
                amount);
        helper.assertTrue(result.isAccepted(), "request at " + rack + " accepted: " + result);
        helper.assertValueEqual(result.granted(), amount, "granted at " + rack);
        helper.assertValueEqual(result.merged(), expectMerged, "merged at " + rack);
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
