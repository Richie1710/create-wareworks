package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalFluidOutcome;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.RequestKind;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.stock.RuleUnit;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleAdjustment;
import dev.wareworks.core.stock.StockRuleEvaluation;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.FluidAcknowledgement;
import dev.wareworks.core.terminal.FluidQuestion;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of <b>fluid stock rules</b> and of <b>redstone ports with a filled container in their filter</b> (M31,
 * issue #21, ADR-073, {@code docs/warehouse-system.md} §3.6 and §3.9.6): what a fluid rule's maximum and reserve hold
 * back, how an older item rule on a filled container loads, and what a port asks for and is told.
 * <p>
 * <b>Rules.</b> A stock keeper row whose item is a filled container counts the fluid of it in the fluid bays, in
 * millibuckets, unless a player switches it to counting the containers. Its maximum caps what the crane stores into a
 * bay — the container is shelved as an item at the cap, and a pipe is not held to it — its reserve holds fluid back from
 * a redstone port and is named in a player's question, and a row an older version saved with a lava bucket in it stays
 * the item rule it always was.
 * <p>
 * <b>Ports.</b> A requesting port whose filter carries fluid keeps asking for the containers on the shelves while there
 * are any, and asks the warehouse to fill empty containers at its bays once there are none; an empty bucket keeps
 * meaning the item.
 * <p>
 * <b>Conservation.</b> Every test that moves anything keeps the joint census: the fluid half on every tick (a fill or a
 * drain moves millibuckets between a tank and a container in one call, so the fluid total never moves at all) and both
 * halves at rest, with every fill and every exchange declared through {@link ItemCensus#fill} and
 * {@link ItemCensus#exchange}, which check the declaration against the routine the bay itself uses.
 * <p>
 * <b>Teeth.</b> Each test names the change to the code it exists to catch.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidRuleGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 2400;
    private static final int SETTLE_TICKS = 40;

    private static final RackPosition INPUT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition PORT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition BAY_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition CHEST_RACK = new RackPosition(5, 0, Side.LEFT);

    private static final ItemKey LAVA_BUCKET = ItemKey.of(Items.LAVA_BUCKET);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final int BUCKET_MB = FluidType.BUCKET_VOLUME;

    private FluidRuleGameTests() {
    }

    // --- 17. rules: the maximum --------------------------------------------------------------------------------------

    /**
     * A lava bay holding two buckets, a fluid rule of 3 000 mB, three lava buckets at an input: the first is emptied
     * into the bay up to the cap, the other two are shelved as items — the bay at its cap answers the crane 0. A pipe
     * filling the bay past the cap is not held to the rule.
     * <p>
     * <b>Teeth:</b> drop the fluid headroom from {@code CraneDispatch}'s live insert estimate. The dedicated bay then
     * outranks the shelf for all three, and the bay ends at 5 000 mB.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidrulemaximumshelvescontainer(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        aisle.input(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of();
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "the lava is in the bay or in a bucket"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 1))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, LAVA_BUCKET, 0L);
                    helper.assertValueEqual(keeper.rules().unitAt(0), RuleUnit.FLUID,
                            "a lava bucket row starts out counting lava");
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 3L * BUCKET_MB);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.fluidStockRules().ruleFor(LAVA).map(StockRule::maximum),
                            Optional.of(3L * BUCKET_MB), "the controller enforces 3 000 mB of lava");
                    helper.assertValueEqual(controller.stockRules().ruleFor(LAVA_BUCKET), Optional.empty(),
                            "and no rule on the lava buckets themselves");
                    helper.assertValueEqual(controller.fluidContainerHeadroom(LAVA_BUCKET), 1,
                            "room for one more bucket under the cap");
                    for (int i = 0; i < 3; i++) {
                        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), LAVA_BUCKET.toStack(1));
                        ItemCensus.change(items, LAVA_BUCKET, 1);
                        FluidCensus.change(fluid, LAVA, BUCKET_MB);
                    }
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, LAVA_BUCKET), 0L, "the input is empty");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, LAVA_BUCKET), 2L,
                            "two lava buckets were shelved as items");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 1L, "beside the one emptied");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(bay.millibuckets(), 3 * BUCKET_MB, "the bay stopped at the cap");
                    ItemCensus.exchange(helper, items, LAVA_BUCKET, BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "one emptied, two shelved");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.fluidContainerHeadroom(LAVA_BUCKET), 0, "no more room");
                    StockRuleEvaluation<ItemKey> row = controller.stockRuleViewsAt(
                            aisle.absoluteRackPos(KEEPER_RACK)).get(0);
                    helper.assertValueEqual(row.status(), StockRuleStatus.AT_MAXIMUM,
                            "the keeper's row is judged in millibuckets of lava");
                    helper.assertValueEqual(row.levels().stocked(), 3L * BUCKET_MB, "against what the bays hold");
                    aisle.assertIdleAndEmpty();
                    // A pipe is the player's own plumbing and the bulk path: the rule does not hold it back.
                    fill(helper, bay, fluid, BUCKET_MB);
                    helper.assertValueEqual(bay.millibuckets(), 4 * BUCKET_MB, "a pipe fills past the cap");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 17. rules: the reserve --------------------------------------------------------------------------------------

    /**
     * A fluid rule's reserve of 2 000 mB in a bay of 3 000: a redstone port is filled one bucket and then refused with
     * {@code RESERVED}, while a player's question for a bucket names the reserve it would take from.
     * <p>
     * <b>Teeth:</b> pass {@code availableFluid} instead of {@code availableFluidTo} in {@code addFill}: the port's second
     * pulse is then accepted.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidrulereserveholdsbackport(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, new ItemStack(Items.BUCKET, 3));
        aisle.output(PORT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 3);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "the lava is in the bay or in a bucket"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 3 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 3L * BUCKET_MB))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, LAVA_BUCKET, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 2L * BUCKET_MB);
                    helper.assertValueEqual(aisle.controller().availableFluidTo(StockAccess.AUTOMATION, LAVA),
                            (long) BUCKET_MB, "automation may have one bucket of the three");
                    helper.assertTrue(aisle.filterOf(PORT_RACK).setFilter(new ItemStack(Items.LAVA_BUCKET)),
                            "the port asks for lava buckets");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    RequestResult first = aisle.outputAt(PORT_RACK).submitRequest();
                    helper.assertTrue(first.isAccepted(), "the first pulse is filled: " + first);
                    helper.assertValueEqual(first.request().map(RetrievalRequest::kind), Optional.of(RequestKind.FILL),
                            "at the bay");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, LAVA_BUCKET), 1L,
                        "one lava bucket reached the port"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "one bucket filled for the port");
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    RequestResult second = port.submitRequest();
                    helper.assertValueEqual(second.rejection(), Optional.of(RequestRejection.RESERVED),
                            "the rest is the reserve, and the port is told so");
                    helper.assertValueEqual(port.lastRejection(), Optional.of(RequestRejection.RESERVED),
                            "and remembers it");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    TerminalFluidOutcome asked = controller.requestFill(aisle.absoluteRackPos(PORT_RACK), LAVA,
                            BUCKET_MB, 64L * BUCKET_MB, StockAccess.PLAYER, FluidAcknowledgement.NONE);
                    FluidQuestion<ItemKey, FluidKey> question = asked.question().orElse(null);
                    helper.assertTrue(question != null, "a player is asked first: " + asked);
                    helper.assertValueEqual(question.servedMb(), (long) BUCKET_MB, "a player may take the reserve");
                    helper.assertValueEqual(question.fluidFromReserve(), (long) BUCKET_MB,
                            "and is told that all of it comes from the reserve");
                    helper.assertValueEqual(question.fluidReserved(), 2L * BUCKET_MB, "of the 2 000 mB held back");
                    StockRuleEvaluation<ItemKey> row = controller.stockRuleViewsAt(
                            aisle.absoluteRackPos(KEEPER_RACK)).get(0);
                    helper.assertValueEqual(row.status(), StockRuleStatus.AT_RESERVE, "the keeper's row is at reserve");
                    helper.assertValueEqual(row.heldBack(), 2L * BUCKET_MB, "holding 2 000 mB back");
                    helper.assertTrue(controller.openRequests().isEmpty(), "nothing else was promised");
                    FluidCensus.assertConserved(helper, items, fluid, "the refusal and the question move nothing");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- 17. rules: an old lava bucket row ---------------------------------------------------------------------------

    /**
     * A keeper row an older version saved with a lava bucket in it has no unit, and stays the item rule it always was:
     * it caps lava buckets, governs no lava, and is saved again without a unit. A new row with the same lava bucket
     * starts out counting lava, and the two govern side by side.
     * <p>
     * <b>Teeth:</b> read a missing {@code Unit} as {@code FLUID} in {@code StockKeeperRules#load}: the old row then
     * stops capping the lava buckets.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void oldlavabucketrowstaysitems(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    // The row as 0.17 wrote it: no Unit.
                    CompoundTag row = new CompoundTag();
                    row.putInt("Row", 0);
                    row.put("Item", LAVA_BUCKET.save(registries));
                    row.putLong("Min", StockRule.UNSET);
                    row.putLong("Max", 2L);
                    row.putLong("Reserve", StockRule.UNSET);
                    ListTag rows = new ListTag();
                    rows.add(row);
                    CompoundTag saved = keeper.saveWithFullMetadata(registries);
                    saved.put(StockKeeperRules.RULES_TAG, rows);
                    keeper.loadWithComponents(saved, registries);

                    helper.assertValueEqual(keeper.rules().unitAt(0), RuleUnit.ITEMS, "an old row counts items");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.stockRules().ruleFor(LAVA_BUCKET).map(StockRule::maximum),
                            Optional.of(2L), "it caps the lava buckets as it did");
                    helper.assertTrue(controller.fluidStockRules().isEmpty(), "and governs no lava");
                    helper.assertFalse(keeper.saveWithFullMetadata(registries).getList(StockKeeperRules.RULES_TAG,
                            Tag.TAG_COMPOUND).getCompound(0).contains("Unit"), "it is saved again without a unit");
                    helper.assertFalse(controllerRule(controller, registries, 0).contains("Unit"),
                            "and so is the controller's copy of it");

                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, LAVA_BUCKET, 0L);
                    keeper.editRule(1, StockKeeperRules.FIELD_MAXIMUM, null, 5L * BUCKET_MB);
                    helper.assertValueEqual(keeper.rules().unitAt(1), RuleUnit.FLUID,
                            "a lava bucket written now counts lava");
                    helper.assertValueEqual(controller.fluidStockRules().ruleFor(LAVA).map(StockRule::maximum),
                            Optional.of(5L * BUCKET_MB), "it governs the lava");
                    helper.assertValueEqual(controller.stockRules().ruleFor(LAVA_BUCKET).map(StockRule::maximum),
                            Optional.of(2L), "while the old row still governs the lava buckets");
                    List<StockRuleEvaluation<ItemKey>> views = controller.stockRuleViewsAt(
                            aisle.absoluteRackPos(KEEPER_RACK));
                    helper.assertValueEqual(views.get(1).status(), StockRuleStatus.SATISFIED,
                            "neither shadows the other");
                    helper.assertValueEqual(controllerRule(controller, registries, 1).getString("Unit"), "fluid",
                            "only the fluid row says so in the save");
                })
                .thenSucceed();
    }

    /**
     * The unit switch on a row converts its numbers with the container's capacity — a maximum of 5 000 mB becomes 5
     * lava buckets and back — and a fluid rule survives a cold reload of the controller, so a bay's cap holds before
     * the first tick after a load.
     * <p>
     * <b>Teeth:</b> leave the {@code Unit} out of {@code ControllerPersistence#writeStockRules}: the reloaded controller
     * then reads the row as an item rule and governs no lava.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidruleunitswitchsurvivesreload(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, LAVA_BUCKET, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 5L * BUCKET_MB);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 2_500L);

                    helper.assertTrue(keeper.editRule(0, StockKeeperRules.FIELD_UNIT, null, 0L).changed(),
                            "the row is switched to counting buckets");
                    StockRule<ItemKey> items = keeper.rules().ruleAt(0).orElseThrow();
                    helper.assertValueEqual(keeper.rules().unitAt(0), RuleUnit.ITEMS, "it counts items now");
                    helper.assertValueEqual(items.maximum(), 5L, "5 000 mB are 5 buckets");
                    helper.assertValueEqual(items.reserve(), 2L, "2 500 mB are 2 whole buckets, never more");
                    helper.assertValueEqual(aisle.controller().stockRules().ruleFor(LAVA_BUCKET).map(StockRule::maximum),
                            Optional.of(5L), "and the controller caps lava buckets");
                    helper.assertTrue(aisle.controller().fluidStockRules().isEmpty(), "and no lava");

                    helper.assertTrue(keeper.editRule(0, StockKeeperRules.FIELD_UNIT, null, 1L).changed(),
                            "and back to counting lava");
                    helper.assertValueEqual(keeper.rules().ruleAt(0).orElseThrow().maximum(), 5L * BUCKET_MB,
                            "5 buckets are 5 000 mB again");
                    helper.assertFalse(keeper.editRule(0, StockKeeperRules.FIELD_UNIT, null, 1L).changed(),
                            "switching to what it already counts changes nothing");
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    helper.assertFalse(keeper.editRule(1, StockKeeperRules.FIELD_UNIT, null, 1L).changed(),
                            "iron carries no fluid, so its row cannot count one");
                    helper.assertValueEqual(keeper.rules().unitAt(1), RuleUnit.ITEMS, "it stays an item rule");

                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
                    BlockEntity loaded = BlockEntity.loadStatic(controller.getBlockPos(), controller.getBlockState(),
                            saved, level.registryAccess());
                    if (!(loaded instanceof WarehouseControllerBlockEntity reloaded)) {
                        helper.fail("a saved controller must load again as one");
                        return;
                    }
                    helper.assertValueEqual(reloaded.fluidStockRules().ruleFor(LAVA).map(StockRule::maximum),
                            Optional.of(5L * BUCKET_MB), "the reloaded controller caps the lava before its first tick");
                    helper.assertValueEqual(reloaded.stockRules().ruleFor(LAVA_BUCKET), Optional.empty(),
                            "and still caps no lava bucket");
                })
                .thenSucceed();
    }

    /**
     * The cap of every rule number is one billion (M31): a fluid rule of ten brass bays, 2 560 000 mB, is stored as
     * given, a larger number is clamped and the player is told, and both survive the keeper's save.
     * <p>
     * <b>Teeth:</b> put {@code StockRule.MAX_AMOUNT} back to a million: the 2 560 000 mB come back clamped.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockrulemaxamountbillion(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, LAVA_BUCKET, 0L);
                    StockKeeperRules.Edit brass = keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 2_560_000L);
                    helper.assertValueEqual(brass.adjustment(), StockRuleAdjustment.NONE, "ten brass bays fit");
                    keeper.editRule(1, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    StockKeeperRules.Edit huge = keeper.editRule(1, StockKeeperRules.FIELD_MAXIMUM, null,
                            Long.MAX_VALUE);
                    helper.assertValueEqual(huge.adjustment(), StockRuleAdjustment.VALUE_CLAMPED, "told it was clamped");
                    helper.assertValueEqual(keeper.rules().ruleAt(1).orElseThrow().maximum(), 1_000_000_000L,
                            "to one billion");
                    helper.assertValueEqual(aisle.controller().fluidStockRules().ruleFor(LAVA).map(StockRule::maximum),
                            Optional.of(2_560_000L), "the controller enforces all of it");

                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    BlockEntity copy = BlockEntity.loadStatic(keeper.getBlockPos(), keeper.getBlockState(),
                            keeper.saveWithFullMetadata(registries), registries);
                    if (!(copy instanceof WarehouseStockKeeperBlockEntity reloaded)) {
                        helper.fail("a saved keeper must load again as one");
                        return;
                    }
                    helper.assertValueEqual(reloaded.rules().ruleAt(0).orElseThrow().maximum(), 2_560_000L,
                            "the millibuckets survive the save");
                    helper.assertValueEqual(reloaded.rules().unitAt(0), RuleUnit.FLUID, "as a fluid rule");
                    helper.assertValueEqual(reloaded.rules().ruleAt(1).orElseThrow().maximum(), 1_000_000_000L,
                            "and the cap too");
                })
                .thenSucceed();
    }

    // --- 16. ports ---------------------------------------------------------------------------------------------------

    /**
     * A port with a lava bucket in its filter and no lava bucket on any shelf: its pulse becomes a fill request, the
     * port's goggles say it is filled from the bays, and the crane fills an empty bucket there and brings it.
     * <p>
     * <b>Teeth:</b> remove the fill branch of {@code WarehouseOutputBlockEntity#resolveRequest}: the pulse is refused
     * with {@code NOT_IN_STOCK} and nothing moves.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portfluidfilterfillswhenshelfempty(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, new ItemStack(Items.BUCKET, 2));
        aisle.output(PORT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 2);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "the lava is in the bay or in a bucket"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.filterOf(PORT_RACK).setFilter(new ItemStack(Items.LAVA_BUCKET)),
                            "the port asks for lava buckets");
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    RequestResult result = port.submitRequest();
                    helper.assertTrue(result.isAccepted(), "the pulse is accepted: " + result);
                    helper.assertValueEqual(result.request().map(RetrievalRequest::kind),
                            Optional.of(RequestKind.FILL), "as a fill request: no lava bucket lies on a shelf");
                    helper.assertValueEqual(result.granted(), 1, "for the one bucket on the filter");
                    port.onGoggleObserved();
                    helper.assertTrue(port.summary().filling(), "the goggles say it is filled from the bays");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, LAVA_BUCKET), 1L,
                        "a lava bucket reached the port"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    ItemCensus.fill(helper, items, BUCKET, LAVA_BUCKET, 1);
                    FluidCensus.assertConserved(helper, items, fluid, "filled at the bay for the port");
                    helper.assertValueEqual(bay.millibuckets(), BUCKET_MB, "the bay gave one bucket");
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    port.onGoggleObserved();
                    helper.assertFalse(port.summary().filling(), "and the request is served");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * The same port with no empty bucket in the warehouse: the pulse is refused with {@code NO_EMPTY_CONTAINERS} — not
     * "not in stock", because the lava is there — and the port remembers it for the gold goggle line.
     * <p>
     * <b>Teeth:</b> answer {@code NOT_IN_STOCK} in {@code addFill} whatever the fluid: the remembered refusal changes.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portfluidfilternoemptiessaysso(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper);
        aisle.output(PORT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<FluidKey, Long> fluid = FluidCensus.of();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.filterOf(PORT_RACK).setFilter(new ItemStack(Items.LAVA_BUCKET)),
                            "the port asks for lava buckets");
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    RequestResult result = port.submitRequest();
                    helper.assertValueEqual(result.rejection(), Optional.of(RequestRejection.NO_EMPTY_CONTAINERS),
                            "the lava is there and no container is");
                    port.onGoggleObserved();
                    helper.assertValueEqual(port.summary().lastRejection(),
                            Optional.of(RequestRejection.NO_EMPTY_CONTAINERS), "which the goggles say");
                    helper.assertTrue(aisle.controller().openRequests().isEmpty(), "nothing was promised");
                    FluidCensus.assertConserved(helper, ItemCensus.of(), fluid, "nothing moved");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * Two empty buckets on the shelf, both held back by an item rule's reserve, and lava in the bay: the port's pulse is
     * refused with {@code RESERVED} — the empties are there, a rule keeps them from automation — and the port remembers
     * that, not "no empty containers in stock", so its goggles do not send the player looking for buckets.
     * <p>
     * <b>Teeth:</b> drop the {@code RESERVED} branch of {@code addFill}'s {@code NOTHING_AVAILABLE} answer: the port then
     * remembers {@code NO_EMPTY_CONTAINERS} while two buckets stand on the shelf.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portemptiesreservedsaysreserved(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, new ItemStack(Items.BUCKET, 2));
        aisle.output(PORT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 2);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, BUCKET, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 2L);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.availableStock(BUCKET), 2L, "both buckets are on the shelf");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, BUCKET), 0L,
                            "and both are the reserve");
                    helper.assertTrue(aisle.filterOf(PORT_RACK).setFilter(new ItemStack(Items.LAVA_BUCKET)),
                            "the port asks for lava buckets");
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    RequestResult result = port.submitRequest();
                    helper.assertValueEqual(result.rejection(), Optional.of(RequestRejection.RESERVED),
                            "the empties are there and reserved");
                    port.onGoggleObserved();
                    helper.assertValueEqual(port.summary().lastRejection(), Optional.of(RequestRejection.RESERVED),
                            "which the goggles say");
                    helper.assertTrue(controller.openRequests().isEmpty(), "nothing was promised");
                    FluidCensus.assertConserved(helper, items, fluid, "nothing moved");
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * A lava bucket on a shelf and lava in the bay: the port asks for the lava bucket on the shelf, exactly as it did
     * before M31, and the bay is not touched.
     * <p>
     * <b>Teeth:</b> drop the "shelves have it" test from {@code WarehouseOutputBlockEntity#fillsFromBays}: the port then
     * fills an empty bucket instead and the bay loses a bucket.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portfluidfilterstockwhenshelfhas(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, new ItemStack(Items.LAVA_BUCKET), new ItemStack(Items.BUCKET, 2));
        aisle.output(PORT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(LAVA_BUCKET, 1, BUCKET, 2);
        Map<FluidKey, Long> fluid = FluidCensus.of(LAVA, BUCKET_MB);
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled or drained"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.filterOf(PORT_RACK).setFilter(new ItemStack(Items.LAVA_BUCKET)),
                            "the port asks for lava buckets");
                    RequestResult result = aisle.outputAt(PORT_RACK).submitRequest();
                    helper.assertTrue(result.isAccepted(), "the pulse is accepted: " + result);
                    helper.assertValueEqual(result.request().map(RetrievalRequest::kind),
                            Optional.of(RequestKind.STOCK), "for the lava bucket on the shelf");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, LAVA_BUCKET), 1L,
                        "the shelf's lava bucket reached the port"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "a lava bucket moved, nothing was filled");
                    helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET_MB, "the bay was not touched");
                    helper.assertValueEqual(aisle.storedAt(CHEST_RACK, BUCKET), 2L, "nor were the empties");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    /**
     * An empty bucket in the filter keeps meaning the item, beside a bay of lava: the port gets an empty bucket.
     * <p>
     * <b>Teeth:</b> test "is a fluid container" instead of "carries fluid" in {@code fillsFromBays}: the empty bucket's
     * pulse then has no fill recipe and the port is refused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portemptybucketfilterisitem(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, new ItemStack(Items.BUCKET, 2));
        aisle.output(PORT_RACK);
        FluidBayBlockEntity bay = bayAt(helper, aisle);
        Map<ItemKey, Long> items = ItemCensus.of(BUCKET, 2);
        Map<FluidKey, Long> fluid = FluidCensus.of();
        helper.onEachTick(() -> FluidCensus.assertEquals(helper, fluid, "nothing is filled or drained"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 2))
                .thenExecute(() -> fill(helper, bay, fluid, 2 * BUCKET_MB))
                .thenWaitUntil(() -> assertLavaIndexed(helper, aisle, 2L * BUCKET_MB))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.filterOf(PORT_RACK).setFilter(new ItemStack(Items.BUCKET)),
                            "the port asks for empty buckets");
                    aisle.filterOf(PORT_RACK).count = 1; // after setFilter, which may clamp the count
                    RequestResult result = aisle.outputAt(PORT_RACK).submitRequest();
                    helper.assertTrue(result.isAccepted(), "the pulse is accepted: " + result);
                    helper.assertValueEqual(result.granted(), 1, "for one empty bucket");
                    helper.assertValueEqual(result.request().map(RetrievalRequest::kind),
                            Optional.of(RequestKind.STOCK), "for an empty bucket, the item");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, BUCKET), 1L,
                        "an empty bucket reached the port"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    FluidCensus.assertConserved(helper, items, fluid, "an empty bucket moved");
                    helper.assertValueEqual(bay.millibuckets(), 2 * BUCKET_MB, "the bay was not touched");
                    aisle.assertIdleAndEmpty();
                    retire(helper, aisle, bay, fluid);
                })
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** A warehouse of a copper fluid bay, a chest holding {@code shelf} and a terminal; each test adds the rest. */
    private static AisleFixture warehouse(GameTestHelper helper, ItemStack... shelf) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        helper.setBlock(aisle.rackPos(BAY_RACK), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, aisle.sideDirection(BAY_RACK)));
        aisle.build(true);
        aisle.storage(CHEST_RACK, shelf);
        aisle.terminal(TERMINAL_RACK);
        return aisle;
    }

    /** The controller's saved copy of the rule at {@code index} of the first keeper. */
    private static CompoundTag controllerRule(WarehouseControllerBlockEntity controller,
            HolderLookup.Provider registries, int index) {
        return controller.saveWithFullMetadata(registries).getList("StockRules", Tag.TAG_COMPOUND).getCompound(0)
                .getList("Rules", Tag.TAG_COMPOUND).getCompound(index);
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
