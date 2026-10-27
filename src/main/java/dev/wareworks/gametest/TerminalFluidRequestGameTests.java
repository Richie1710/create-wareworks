package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalFluidOutcome;
import dev.wareworks.content.station.TerminalFluids;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.RequestKind;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.terminal.ContainerPlan;
import dev.wareworks.core.terminal.FluidAcknowledgement;
import dev.wareworks.core.terminal.FluidQuestion;
import dev.wareworks.core.terminal.FluidRowState;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.network.TerminalFluidQuestionPayload;
import dev.wareworks.network.TerminalFluidRequestPayload;
import dev.wareworks.network.TerminalRequestAnswers;
import dev.wareworks.network.TerminalResultPayload;
import dev.wareworks.network.TerminalStockPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of <b>asking the warehouse for a fluid at a terminal</b> (M31, issue #21, ADR-073): the fluid cell,
 * the question every fluid click asks first, the answer that has to name exactly that question, partial service, the
 * container nothing fits, the offer to make containers, the click's bound, and the empty containers a fill request has
 * promised.
 * <p>
 * <b>Conservation.</b> Every test keeps the fluid census on every tick and the joint item-and-fluid census at rest
 * ({@link FluidCensus#assertConserved}); what a test does to the world on purpose — taking a bucket away, playing the
 * player's machine — is declared in the same step. Most of these tests run with the motor at rest: a question and a
 * request move nothing, and the crane would only blur what the numbers say.
 * <p>
 * <b>Teeth.</b> Each test names the change to the code it exists to catch.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalFluidRequestGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int LONG_TIMEOUT_TICKS = 4000;
    private static final int SETTLE_TICKS = 40;
    /** Its own batch: {@link ConfigOverrides} writes into the loaded config, and one batch runs at the same time. */
    private static final String CONFIG_BATCH = "wareworksfluidrequestclamp";

    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition BAY_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition CHEST_RACK = new RackPosition(5, 0, Side.LEFT);

    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final int BUCKET_MB = FluidType.BUCKET_VOLUME;
    /** A vanilla bucket takes three iron ingots. */
    private static final int IRON_PER_BUCKET = 3;

    private TerminalFluidRequestGameTests() {
    }

    // --- 10. the question comes first --------------------------------------------------------------------------------

    /**
     * A first click asks: ten empty buckets, ten trips, ten lava buckets the terminal cannot hold at once. An answer to
     * a warehouse that has since lost a bucket is asked about again, with nine; the answer to that question makes
     * exactly one fill request for nine. The payloads carry every number of it.
     * <p>
     * <b>Teeth:</b> make {@code FluidAcknowledgement#covers} accept a smaller question ({@code servedMb <=} instead of
     * {@code ==}). The stale answer is then carried out against nine buckets and no second question is asked.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidrequestquestionfirst(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 10);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 10);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        AtomicReference<FluidQuestion<ItemKey, FluidKey>> first = new AtomicReference<>();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "a question moves no fluid"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 16 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 16L * BUCKET_MB))
                .thenExecute(() -> {
                    TerminalFluids.Row row = onlyRow(helper, aisle);
                    helper.assertValueEqual(row.state(), FluidRowState.OK, "the cell can be asked");
                    helper.assertValueEqual(row.servableMb(), 10L * BUCKET_MB, "Ctrl would take ten buckets");
                    helper.assertValueEqual(row.container(), Optional.of(BUCKET), "filled into buckets");
                    helper.assertValueEqual(row.containerMb(), BUCKET_MB, "of a bucket each");

                    FluidQuestion<ItemKey, FluidKey> question = asked(helper, aisle, 10 * BUCKET_MB,
                            FluidAcknowledgement.NONE);
                    helper.assertValueEqual(question.servedMb(), 10L * BUCKET_MB, "all ten buckets are served");
                    helper.assertValueEqual(question.portions(), List.of(new ContainerPlan.Portion<>(BUCKET,
                            LAVA_BUCKET, BUCKET_MB, 10)), "into ten empty buckets");
                    helper.assertValueEqual(question.trips(), 10, "in ten crane trips");
                    helper.assertValueEqual(question.perTrip(), 1, "one filled bucket at a time");
                    helper.assertValueEqual(question.shortfall(), ContainerPlan.Shortfall.NONE, "nothing short");
                    helper.assertTrue(question.overflowsStation(), "the terminal holds fewer than ten at a time");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "and nothing was requested");
                    first.set(question);

                    // The payload path: the answer to a payload is the question, and every codec reads what it wrote.
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, aisle.terminalAt(TERMINAL_RACK));
                    TerminalFluidRequestPayload click = new TerminalFluidRequestPayload(menu.containerId, LAVA,
                            10 * BUCKET_MB, Optional.empty(), FluidAcknowledgement.NONE);
                    helper.assertValueEqual(roundTrip(helper, click, TerminalFluidRequestPayload.STREAM_CODEC), click,
                            "a first click survives the wire");
                    TerminalFluidRequestPayload answered = new TerminalFluidRequestPayload(menu.containerId, LAVA,
                            10 * BUCKET_MB, Optional.of(new BlockPos(1, 2, 3)), question.acknowledgement());
                    helper.assertValueEqual(roundTrip(helper, answered, TerminalFluidRequestPayload.STREAM_CODEC),
                            answered, "and so does an answer with a door");
                    TerminalFluidOutcome outcome = WarehouseTerminalMenu.submitFluidRequest(player, menu.containerId,
                            LAVA, 10 * BUCKET_MB, FluidAcknowledgement.NONE, Optional.empty()).orElseThrow();
                    CustomPacketPayload reply = TerminalRequestAnswers.answer(click, outcome);
                    if (!(reply instanceof TerminalFluidQuestionPayload payload)) {
                        helper.fail("a first fluid click is answered with the question, not " + reply);
                        return;
                    }
                    helper.assertValueEqual(roundTrip(helper, payload, TerminalFluidQuestionPayload.STREAM_CODEC)
                            .question(), question, "the question survives the wire");
                    TerminalFluids half = aisle.terminalAt(TERMINAL_RACK).fluidSnapshot();
                    TerminalStockPayload stock = new TerminalStockPayload(menu.containerId, true,
                            List.of(new StockCount<>(BUCKET, 10, 10, false, 0)), Optional.of(half));
                    helper.assertValueEqual(roundTrip(helper, stock, TerminalStockPayload.STREAM_CODEC), stock,
                            "the fluid half survives the stock push");

                    // A player takes one bucket while the dialog is up.
                    helper.assertValueEqual(aisle.handlerAt(aisle.inventoryPos(CHEST_RACK)).extractItem(0, 1, false)
                            .getCount(), 1, "a bucket is taken away");
                    ItemCensus.change(items, BUCKET, -1);
                    aisle.controller().refreshLocation(CHEST_RACK);
                })
                .thenExecute(() -> {
                    FluidQuestion<ItemKey, FluidKey> again = asked(helper, aisle, 10 * BUCKET_MB,
                            first.get().acknowledgement());
                    helper.assertValueEqual(again.servedMb(), 9L * BUCKET_MB, "the warehouse moved: asked again");
                    helper.assertValueEqual(again.shortfall(), ContainerPlan.Shortfall.NOT_ENOUGH_CONTAINERS,
                            "and it says why");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "the stale answer made nothing");

                    RequestResult result = resolved(helper, aisle, 10 * BUCKET_MB, again.acknowledgement());
                    helper.assertTrue(result.isAccepted(), "the answer to the new question is carried out: " + result);
                    helper.assertValueEqual(result.granted(), 9, "for nine buckets");
                    List<RetrievalRequest<ItemKey, BlockPos>> open = aisle.controller().openRequests();
                    helper.assertValueEqual(open.size(), 1, "as one request");
                    helper.assertValueEqual(open.getFirst().kind(), RequestKind.FILL, "a fill request");
                    helper.assertValueEqual(open.getFirst().key(), LAVA_BUCKET, "for lava buckets");
                    helper.assertValueEqual(aisle.controller().availableStock(BUCKET), 0L, "every empty is promised");
                    helper.assertValueEqual(aisle.controller().availableFluid(LAVA), 7L * BUCKET_MB,
                            "and nine buckets of the lava");
                    TerminalFluids.FillLine line = aisle.terminalAt(TERMINAL_RACK).fluidSnapshot().fills().getFirst();
                    helper.assertValueEqual(line.remaining(), 9, "the order line names the nine");
                    helper.assertValueEqual(line.fluid(), LAVA, "filled from lava");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing moved");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 11. partial service -----------------------------------------------------------------------------------------

    /**
     * Five buckets asked, two empties in stock: the question serves 2 000 mB and names why, and the cell's Ctrl amount
     * is exactly that.
     * <p>
     * <b>Teeth:</b> let {@code ContainerPlan} take {@code ceil} instead of the integer division, or drop the
     * {@code available} bound: the question then promises five buckets of fluid from two containers.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidrequestpartial(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 2);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 2);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 8 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 8L * BUCKET_MB))
                .thenExecute(() -> {
                    FluidQuestion<ItemKey, FluidKey> question = asked(helper, aisle, 5 * BUCKET_MB,
                            FluidAcknowledgement.NONE);
                    helper.assertValueEqual(question.askedMb(), 5L * BUCKET_MB, "five buckets were asked for");
                    helper.assertValueEqual(question.servedMb(), 2L * BUCKET_MB, "two can be served");
                    helper.assertValueEqual(question.shortfall(), ContainerPlan.Shortfall.NOT_ENOUGH_CONTAINERS,
                            "because only two empty buckets are in stock");
                    helper.assertValueEqual(question.containersInStock(), 2L, "which the question names");
                    helper.assertValueEqual(onlyRow(helper, aisle).servableMb(), 2L * BUCKET_MB,
                            "Ctrl asks for what can be served");
                    RequestResult result = resolved(helper, aisle, 5 * BUCKET_MB, question.acknowledgement());
                    helper.assertValueEqual(result.granted(), 2, "the answer brings the two");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing moved");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 12. no container fits ---------------------------------------------------------------------------------------

    /**
     * 750 mB asked with nothing but buckets in stock: nothing can be served, the question says that no container holds
     * 750 mB and that the smallest step is a bucket, and it has no Fetch — an answer to it makes nothing.
     * <p>
     * <b>Teeth:</b> round the plan up to a whole container. The request would then fill a bucket for 750 mB asked.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidrequestnocontainerfits(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 4);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 4);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 4 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 4L * BUCKET_MB))
                .thenExecute(() -> {
                    FluidQuestion<ItemKey, FluidKey> question = asked(helper, aisle, 750, FluidAcknowledgement.NONE);
                    helper.assertFalse(question.canFetch(), "nothing can be served");
                    helper.assertValueEqual(question.shortfall(), ContainerPlan.Shortfall.NO_CONTAINER_FITS,
                            "no container holds 750 mB");
                    helper.assertValueEqual(question.smallestStep(), BUCKET_MB, "the smallest step is a bucket");
                    helper.assertValueEqual(question.unservedMb(), 750L, "and the whole 750 mB are left");
                    helper.assertFalse(question.acknowledgement().covers(question), "a question with no Fetch");
                    asked(helper, aisle, 750, question.acknowledgement());
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "and its answer makes nothing");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing moved");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 13. no empties: the offer to make them ------------------------------------------------------------------------

    /**
     * No empty bucket in stock, iron and a production pattern iron × 3 → bucket: the cell is red with a make offer, the
     * question offers four buckets made first, and "Make and fetch" starts the chain with <b>no backing request</b>.
     * The fill request waits with {@code NO_EMPTY_CONTAINERS} until the made buckets arrive, then four lava buckets
     * reach the terminal. The test plays the player's machine.
     * <p>
     * <b>Teeth:</b> start the chain backed by the fill request ({@code startProductionPlan(plan, request)}). The order
     * then promises its buckets to a request for lava buckets, which this test sees on the order at once.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void fluidrequestmakeoffer(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        int buckets = 4;
        int iron = buckets * IRON_PER_BUCKET;
        aisle.storage(CHEST_RACK, new ItemStack(Items.IRON_INGOT, iron));
        aisle.terminal(TERMINAL_RACK);
        aisle.input(INPUT_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, IRON, IRON_PER_BUCKET), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, BUCKET, 1), "and its result");
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(IRON, iron);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        AtomicBoolean waitedForContainers = new AtomicBoolean();
        helper.onEachTick(() -> {
            FluidCensus.assertEquals(helper, fluid, "the lava is in the bay or in a bucket");
            if (aisle.controllerIfPresent().flatMap(found -> found.lastPlanReason())
                    .filter(reason -> reason == NoJobReason.NO_EMPTY_CONTAINERS).isPresent())
                waitedForContainers.set(true);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 8 * BUCKET_MB))
                .thenWaitUntil(() -> {
                    assertLavaIndexed(helper, aisle, 8L * BUCKET_MB);
                    helper.assertTrue(aisle.controller().producibleKeys().contains(BUCKET), "the aisle makes buckets");
                })
                .thenExecute(() -> {
                    TerminalFluids.Row row = onlyRow(helper, aisle);
                    helper.assertValueEqual(row.state(), FluidRowState.NO_EMPTY_CONTAINERS_MAKEABLE,
                            "the cell is red, with an offer");
                    helper.assertValueEqual(row.makeable(), Optional.of(BUCKET), "to make buckets");
                    FluidQuestion<ItemKey, FluidKey> question = asked(helper, aisle, buckets * BUCKET_MB,
                            FluidAcknowledgement.NONE);
                    helper.assertTrue(question.portions().isEmpty(), "no empty container comes from stock");
                    helper.assertValueEqual(question.make(), Optional.of(new FluidQuestion.MakeOffer<>(BUCKET,
                            LAVA_BUCKET, BUCKET_MB, buckets)), "four buckets are offered");
                    helper.assertValueEqual(question.servedMb(), (long) buckets * BUCKET_MB, "for all four buckets");
                    helper.assertValueEqual(question.trips(), buckets, "in four trips");
                    helper.assertTrue(aisle.controller().productionOrders().isEmpty(), "the offer started nothing");

                    RequestResult result = resolved(helper, aisle, buckets * BUCKET_MB, question.acknowledgement());
                    helper.assertTrue(result.isAccepted(), "Make and fetch: " + result);
                    helper.assertValueEqual(result.granted(), buckets, "four lava buckets are promised");
                    List<ProductionOrder<ItemKey, RackPosition>> orders = aisle.controller().productionOrders();
                    helper.assertValueEqual(orders.size(), 1, "one production order");
                    helper.assertValueEqual(orders.getFirst().backingRequest(), Optional.empty(),
                            "backed by no request: the made buckets land in stock");
                    helper.assertValueEqual(aisle.controller().openRequests().getFirst().kind(), RequestKind.FILL,
                            "and the fill request waits for them");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(PRODUCTION_RACK, IRON), (long) iron,
                            "the iron reached the machine");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(),
                            Optional.of(NoJobReason.NO_EMPTY_CONTAINERS),
                            "and the fill request waits for the made buckets");
                })
                .thenExecute(() -> {
                    // The test is the machine: it takes the iron and hands back buckets through the warehouse input.
                    int taken = 0;
                    var handler = aisle.handlerAt(aisle.rackPos(PRODUCTION_RACK));
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        if (IRON.matches(handler.getStackInSlot(slot)))
                            taken += handler.extractItem(slot, Integer.MAX_VALUE, false).getCount();
                    }
                    helper.assertValueEqual(taken, iron, "the machine took every ingot");
                    ItemCensus.change(items, IRON, -taken);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), BUCKET.toStack(buckets));
                    ItemCensus.change(items, BUCKET, buckets);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, LAVA_BUCKET),
                        (long) buckets, "four lava buckets reached the terminal"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertTrue(waitedForContainers.get(),
                            "the fill request waited with NO_EMPTY_CONTAINERS for the made buckets");
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, buckets);
                    FluidCensus.assertConserved(helper, items, fluid, "made, filled and delivered");
                    helper.assertValueEqual(bay.millibuckets(), 4 * BUCKET_MB, "the bay gave four buckets");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "the request is served");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * The terminal already waits for four requests (the per-output cap): accepting the offer to make buckets is refused
     * with {@code OUTPUT_FULL} up front, and no production order is started, so no iron is spent on buckets nobody gets.
     * <p>
     * <b>Teeth:</b> drop the {@code roomFor} check from {@code WarehouseControllerBlockEntity#requestFill} and start the
     * chain before the made portion's request is accepted, as it once was: a production order then appears although the
     * click was refused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidrequestmakeofferqueuefull(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        int buckets = 2;
        int iron = buckets * IRON_PER_BUCKET;
        List<ItemKey> waiting = List.of(ItemKey.of(Items.DIRT), ItemKey.of(Items.STONE), ItemKey.of(Items.SAND),
                ItemKey.of(Items.GRAVEL));
        aisle.storage(CHEST_RACK, new ItemStack(Items.IRON_INGOT, iron), new ItemStack(Items.DIRT),
                new ItemStack(Items.STONE), new ItemStack(Items.SAND), new ItemStack(Items.GRAVEL));
        aisle.terminal(TERMINAL_RACK);
        aisle.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, IRON, IRON_PER_BUCKET), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, BUCKET, 1), "and its result");
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(IRON, iron, waiting.get(0), 1, waiting.get(1), 1, waiting.get(2), 1,
                waiting.get(3), 1);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 4 * BUCKET_MB))
                .thenWaitUntil(() -> {
                    assertLavaIndexed(helper, aisle, 4L * BUCKET_MB);
                    helper.assertTrue(aisle.controller().producibleKeys().contains(BUCKET), "the aisle makes buckets");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(WareworksConfig.maxOpenRequestsPerOutput(), 4, "the default cap");
                    for (ItemKey key : waiting) {
                        RequestResult click = aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                                playerAt(helper, aisle), key, 1, RequestAcknowledgement.ANY).result().orElseThrow();
                        helper.assertTrue(click.isAccepted(), "the terminal waits for " + key + ": " + click);
                    }
                    FluidQuestion<ItemKey, FluidKey> question = asked(helper, aisle, buckets * BUCKET_MB,
                            FluidAcknowledgement.NONE);
                    helper.assertValueEqual(question.make(), Optional.of(new FluidQuestion.MakeOffer<>(BUCKET,
                            LAVA_BUCKET, BUCKET_MB, buckets)), "two buckets are offered");
                    RequestResult result = resolved(helper, aisle, buckets * BUCKET_MB, question.acknowledgement());
                    helper.assertValueEqual(result.rejection(), Optional.of(RequestRejection.OUTPUT_FULL),
                            "Make and fetch is refused: the terminal waits for four requests already");
                    helper.assertTrue(aisle.controller().productionOrders().isEmpty(),
                            "and no chain was started for it");
                    helper.assertValueEqual(aisle.controller().openRequests().size(), waiting.size(),
                            "only the four item requests are open");
                    helper.assertValueEqual(aisle.controller().availableStock(IRON), (long) iron,
                            "the iron is nobody's");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing moved");
                    aisle.controller().openRequests().forEach(open -> aisle.controller().cancelRequest(open.id()));
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 14. the click's bound -----------------------------------------------------------------------------------------

    /**
     * {@code maxTerminalFluidRequestBuckets} bounds a click, and a hostile payload of 10⁹ mB is clamped to it on the
     * server.
     * <p>
     * <b>Teeth:</b> leave out the clamp in {@code WarehouseControllerBlockEntity#requestFill}. The question then asks
     * about every bucket in stock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = CONFIG_BATCH)
    public static void fluidrequestclamped(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 12);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 12);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTerminalFluidRequestBuckets, 4);
                    fill(helper, bay, fluid, 16 * BUCKET_MB);
                })
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 16L * BUCKET_MB))
                .thenExecute(() -> {
                    FluidQuestion<ItemKey, FluidKey> question = asked(helper, aisle, 10 * BUCKET_MB,
                            FluidAcknowledgement.NONE);
                    helper.assertValueEqual(question.askedMb(), 4L * BUCKET_MB, "ten buckets are clamped to four");
                    helper.assertValueEqual(question.servedMb(), 4L * BUCKET_MB, "and four are served");
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, aisle.terminalAt(TERMINAL_RACK));
                    TerminalFluidOutcome hostile = WarehouseTerminalMenu.submitFluidRequest(player, menu.containerId,
                            LAVA, 1_000_000_000L, FluidAcknowledgement.NONE, Optional.empty()).orElseThrow();
                    helper.assertValueEqual(hostile.question().orElseThrow().askedMb(), 4L * BUCKET_MB,
                            "a hostile payload is clamped on the server");
                    TerminalFluidOutcome nothing = WarehouseTerminalMenu.submitFluidRequest(player, menu.containerId,
                            LAVA, 0, FluidAcknowledgement.NONE, Optional.empty()).orElseThrow();
                    helper.assertValueEqual(nothing.result().orElseThrow().rejection(),
                            Optional.of(RequestRejection.INVALID_AMOUNT), "and a click for nothing is refused");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "nothing was requested");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing moved");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 15. promised empties are not promised twice ---------------------------------------------------------------------

    /**
     * Twelve empty buckets, a fill request for five: an item click for ten empty buckets is clamped to the seven nobody
     * has promised. And with every bucket promised, a fluid click is refused with {@code NO_EMPTY_CONTAINERS}.
     * <p>
     * <b>Teeth:</b> leave the empties a fill request owes out of {@code remainingRequestedByKey}. The item click is then
     * granted all ten, and the fill request finds three buckets missing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidrequestemptiesnotdoublepromised(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, 12);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 12);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 16 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 16L * BUCKET_MB))
                .thenExecute(() -> {
                    FluidQuestion<ItemKey, FluidKey> question = asked(helper, aisle, 5 * BUCKET_MB,
                            FluidAcknowledgement.NONE);
                    helper.assertValueEqual(resolved(helper, aisle, 5 * BUCKET_MB, question.acknowledgement())
                            .granted(), 5, "five buckets of lava are promised");
                    RequestResult click = aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                            playerAt(helper, aisle), BUCKET, 10, RequestAcknowledgement.ANY).result().orElseThrow();
                    helper.assertTrue(click.isAccepted(), "the item click is accepted: " + click);
                    helper.assertValueEqual(click.granted(), 7, "for the seven empty buckets nobody has promised");
                    helper.assertValueEqual(aisle.controller().availableStock(BUCKET), 0L, "now every bucket is");
                    helper.assertValueEqual(onlyRow(helper, aisle).state(), FluidRowState.NO_EMPTY_CONTAINERS,
                            "the cell turns red");
                    RequestResult refused = resolved(helper, aisle, BUCKET_MB, FluidAcknowledgement.NONE);
                    helper.assertValueEqual(refused.rejection(), Optional.of(RequestRejection.NO_EMPTY_CONTAINERS),
                            "a fluid click is refused for want of a container, not of lava");
                    TerminalResultPayload answer = TerminalResultPayload.of(3, TerminalRequestAnswers.bucketOf(LAVA),
                            refused);
                    helper.assertValueEqual(roundTrip(helper, answer, TerminalResultPayload.STREAM_CODEC).rejection(),
                            Optional.of(RequestRejection.NO_EMPTY_CONTAINERS), "and the screen is told so");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing moved");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    /** A warehouse of a copper fluid bay, a chest with {@code emptyBuckets} empty buckets and a terminal. */
    private static AisleFixture warehouse(GameTestHelper helper, int emptyBuckets) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        if (emptyBuckets > 0)
            aisle.storage(CHEST_RACK, new ItemStack(Items.BUCKET, emptyBuckets));
        else
            aisle.storage(CHEST_RACK);
        aisle.terminal(TERMINAL_RACK);
        return aisle;
    }

    /** A fluid click at the terminal, by a player standing in front of it. */
    private static TerminalFluidOutcome click(GameTestHelper helper, AisleFixture aisle, long amountMb,
            FluidAcknowledgement acknowledged) {
        WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
        return terminal.requestFluidFromTerminal(playerAt(helper, aisle), LAVA, amountMb, acknowledged,
                Optional.empty());
    }

    /** The question a fluid click raises; fails the test when the terminal resolved it instead. */
    private static FluidQuestion<ItemKey, FluidKey> asked(GameTestHelper helper, AisleFixture aisle, long amountMb,
            FluidAcknowledgement acknowledged) {
        TerminalFluidOutcome outcome = click(helper, aisle, amountMb, acknowledged);
        if (outcome.question().isEmpty()) {
            helper.fail("the terminal resolved the fluid click instead of asking: " + outcome.result().orElseThrow());
            throw new IllegalStateException("unreachable");
        }
        return outcome.question().get();
    }

    /** The answer to a fluid click; fails the test when the terminal asked instead. */
    private static RequestResult resolved(GameTestHelper helper, AisleFixture aisle, long amountMb,
            FluidAcknowledgement acknowledged) {
        TerminalFluidOutcome outcome = click(helper, aisle, amountMb, acknowledged);
        if (outcome.result().isEmpty()) {
            helper.fail("the terminal asked instead of resolving: " + outcome.question().get());
            throw new IllegalStateException("unreachable");
        }
        return outcome.result().get();
    }

    /** The terminal's one fluid cell. */
    private static TerminalFluids.Row onlyRow(GameTestHelper helper, AisleFixture aisle) {
        List<TerminalFluids.Row> rows = aisle.terminalAt(TERMINAL_RACK).fluidSnapshot().rows();
        if (rows.size() != 1) {
            helper.fail("expected one fluid cell, found " + rows);
            throw new IllegalStateException("unreachable");
        }
        return rows.getFirst();
    }

    private static Player playerAt(GameTestHelper helper, AisleFixture aisle) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(aisle.rackPos(TERMINAL_RACK)));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    private static <T> T roundTrip(GameTestHelper helper, T payload, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        codec.encode(buffer, payload);
        T decoded = codec.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the codec read everything it wrote");
        return decoded;
    }

    private static void assertLavaIndexed(GameTestHelper helper, AisleFixture aisle, long millibuckets) {
        helper.assertValueEqual(aisle.controller().fluidStockIndex().count(LAVA), millibuckets,
                "the controller has indexed the lava");
    }

    private static void fill(GameTestHelper helper, FluidBayBlockEntity bay, Map<FluidKey, Long> fluid, int amount) {
        helper.assertValueEqual(bay.fill(new FluidStack(Fluids.LAVA, amount), false), amount, "the bay took the lava");
        FluidCensus.change(fluid, LAVA, amount);
    }

    private static FluidBayBlockEntity bayAt(GameTestHelper helper, AisleFixture aisle) {
        FluidBayBlockEntity be = WareworksBlockEntityTypes.FLUID_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(aisle.rackPos(BAY_RACK)));
        if (be == null) {
            helper.fail("missing fluid bay block entity", aisle.rackPos(BAY_RACK));
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    /** Empties and removes the bay at the end of a test, declaring the fluid it still held. */
    private static void retire(GameTestHelper helper, AisleFixture aisle, FluidBayBlockEntity bay,
            Map<FluidKey, Long> fluid) {
        int held = bay.millibuckets();
        FluidKey stored = bay.storedFluidOrNull();
        BlockPos pos = aisle.rackPos(BAY_RACK);
        Clearable.tryClear(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
        helper.setBlock(pos, Blocks.AIR);
        if (stored != null)
            FluidCensus.change(fluid, stored, -held);
    }
}
