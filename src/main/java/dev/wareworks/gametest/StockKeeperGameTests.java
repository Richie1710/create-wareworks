package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.Map;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperMenu;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.StockKeeperScreenState;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleAdjustment;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.stock.StockRules;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the warehouse stock keeper ({@code docs/warehouse-system.md} §3.6, M15, issue #3).
 * <ul>
 * <li>{@code stockkeeperregistration} — it is a member that holds no items: the right kind, no item capability, no
 * drops of its own, and a keeper outside a warehouse says so instead of pretending to govern;</li>
 * <li>{@code stockkeeperpersistenceandbreak} — the rules round-trip through a save, crafted save data loads clamped
 * and never throws, a schematic carries the rules (but never the comparator value), and breaking the block drops a
 * plain keeper that carries no rules at all;</li>
 * <li>{@code stockkeeperhostilepayloads} — a rule payload for no menu, another menu, another player, an out-of-reach
 * player, an unknown row or field, absurd numbers, and a flood beyond the per-tick budget;</li>
 * <li>{@code stockkeepermembership} — it joins the aisle like a station, a turned one leaves again, and the
 * controller's copy of the rules follows every join, edit and removal;</li>
 * <li>{@code stockkeepercoldreload} — a controller replaced by a copy loaded from its save knows every rule
 * <b>before</b> its first tick, so nothing is stored past a maximum in that window;</li>
 * <li>{@code stockkeepermaximumstopsstoring} — the maximum caps storing exactly, reports {@code AT_MAXIMUM} rather
 * than "warehouse full", and never blocks another item type;</li>
 * <li>{@code stockkeeperreserveblocksautomation} — the reserve stops a redstone-style request with {@code RESERVED}
 * and lets a player take the same items;</li>
 * <li>{@code stockkeepershadowedduplicate} — a second rule for one item applies nothing and says why;</li>
 * <li>{@code stockkeeperminimumsignal} — the lamp and the comparator follow the minimum;</li>
 * <li>{@code stockkeepersignalneedsawarehouse} — a keeper turned away from the aisle or left without a controller stops
 * signalling instead of calling for an item nothing enforces.</li>
 * </ul>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1 at z = 3 along +X,
 * {@value #RAILS} rails), storage on the left rack plane, the keepers and the stations on the right.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StockKeeperGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    /** Long enough for several dispatch intervals and more than one stock rule tick. */
    private static final int SETTLE_TICKS = 80;
    private static final double DROP_RADIUS = 2.0;
    private static final int FAR_AWAY_BLOCKS = 40;
    private static final int OTHER_CONTAINER_ID = 4321;

    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition STORAGE_C = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition SECOND_KEEPER_RACK = new RackPosition(5, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(0, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    /** Iron fed into the input of {@link #stockKeeperMaximumStopsStoring}, far beyond its maximum. */
    private static final int FED_IRON = 24;
    private static final int IRON_MAXIMUM = 8;
    private static final int FED_GOLD = 8;
    private static final int DIAMONDS_IN_STOCK = 32;
    private static final int DIAMOND_RESERVE = 10;
    private static final int IRON_MINIMUM = 64;

    private StockKeeperGameTests() {
    }

    // --- the block, standing alone ---------------------------------------------------------------------------------

    /**
     * A stock keeper is a member that holds <b>no items</b>: it reports {@link LocationKind#KEEPER}, exposes no item
     * capability at all, drops only itself, and outside a warehouse it reports
     * {@link StockRuleStatus#NO_WAREHOUSE} for its rules instead of pretending to govern anything.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stockKeeperRegistration(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, BASE_Y, 3);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, Direction.NORTH));
        WarehouseStockKeeperBlockEntity keeper = keeperAt(helper, pos);

        helper.assertValueEqual(keeper.locationKind(), LocationKind.KEEPER, "location kind");
        helper.assertValueEqual(keeper.facing(), Direction.NORTH, "facing");
        helper.assertValueEqual(keeper.rowCount(), WareworksConfig.stockKeeperRows(), "configured rule rows");
        helper.assertFalse(helper.getBlockState(pos).getValue(WarehouseStockKeeperBlock.LIT), "the lamp starts dark");
        helper.assertValueEqual(keeper.comparatorSignal(), 0, "an unconfigured keeper powers no comparator");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null) == null, "a stock keeper must expose no item capability at all");

        // A rule without a warehouse behind it governs nothing, and the screen says exactly that.
        helper.assertTrue(keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L).changed(), "the item was set");
        helper.assertTrue(keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM).changed(),
                "the maximum was set");
        StockKeeperScreenState state = keeper.screenState(StockRuleAdjustment.NONE, StockKeeperScreenState.NO_ROW);
        helper.assertFalse(state.linked(), "no controller reads this keeper");
        helper.assertValueEqual(state.row(0).orElseThrow().status(), StockRuleStatus.NO_WAREHOUSE, "row status");
        helper.assertValueEqual(state.row(0).orElseThrow().maximum(), (long) IRON_MAXIMUM, "the stored maximum");

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asItem(), pos, DROP_RADIUS);
            helper.assertValueEqual(ItemCensus.take(helper),
                    Map.of(ItemKey.of(WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asItem()), 1L),
                    "a broken keeper drops itself and nothing else");
        }).thenSucceed();
    }

    /**
     * The rules survive a save and load, crafted save data can only ever produce a sane rule, a schematic carries them
     * along while a broken or wrenched keeper drops a plain block, and the numbers are corrected on the way in rather
     * than stored as written.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stockKeeperPersistenceAndBreak(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, BASE_Y, 3);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState());
        WarehouseStockKeeperBlockEntity keeper = keeperAt(helper, pos);
        ServerLevel level = helper.getLevel();

        keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
        keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, 64L);
        keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 512L);
        keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 32L);
        keeper.editRule(1, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);

        // "Keep at least 64 but store at most 32" cannot be obeyed either way, so the cap gives way and the player is
        // told about it; the reserve is then clamped against the raised maximum, never the original one.
        StockKeeperRules.Edit raised = keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 32L);
        helper.assertValueEqual(raised.adjustment(), StockRuleAdjustment.MAXIMUM_RAISED_TO_MINIMUM, "cross-clamp");
        helper.assertValueEqual(keeper.rules().ruleAt(0).orElseThrow().maximum(), 64L, "the maximum was raised");
        StockKeeperRules.Edit reserve = keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, Long.MAX_VALUE);
        helper.assertValueEqual(reserve.adjustment(), StockRuleAdjustment.RESERVE_CLAMPED_TO_MAXIMUM,
                "a reserve the warehouse could never reach");
        helper.assertValueEqual(keeper.rules().ruleAt(0).orElseThrow().reserve(), 64L, "clamped to the maximum");
        // A plain range clamp, on a row with no maximum to cross-clamp against.
        StockKeeperRules.Edit clamped = keeper.editRule(1, StockKeeperRules.FIELD_MINIMUM, null, Long.MAX_VALUE);
        helper.assertValueEqual(clamped.adjustment(), StockRuleAdjustment.VALUE_CLAMPED, "a value out of range");
        helper.assertValueEqual(keeper.rules().ruleAt(1).orElseThrow().minimum(), StockRule.MAX_AMOUNT, "the cap");
        keeper.editRule(1, StockKeeperRules.FIELD_MINIMUM, null, StockRule.UNSET);
        keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 512L);
        keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 32L);

        // A save and a load bring back exactly what was configured.
        CompoundTag saved = keeper.saveWithFullMetadata(level.registryAccess());
        WarehouseStockKeeperBlockEntity copy = loadCopy(helper, keeper, saved);
        StockRule<ItemKey> restored = copy.rules().ruleAt(0).orElseThrow();
        helper.assertValueEqual(restored.key(), IRON, "restored item");
        helper.assertValueEqual(restored.minimum(), 64L, "restored minimum");
        helper.assertValueEqual(restored.maximum(), 512L, "restored maximum");
        helper.assertValueEqual(restored.reserve(), 32L, "restored reserve");
        helper.assertValueEqual(copy.rules().ruleAt(1).orElseThrow().key(), DIAMOND, "the second row too");
        helper.assertTrue(copy.rules().ruleAt(2).isEmpty(), "an untouched row stays empty");

        // What a schematic carries: Create reads PartialSafeNBT for a block outside create:safe_nbt, so this is exactly
        // what a printed copy starts with — the rules, and not a comparator value from another warehouse.
        CompoundTag safe = new CompoundTag();
        keeper.writeSafe(safe, level.registryAccess());
        helper.assertFalse(safe.getList(StockKeeperRules.RULES_TAG, Tag.TAG_COMPOUND).isEmpty(),
                "a schematic carries the rules along");
        helper.assertFalse(safe.contains("BelowMinimum"),
                "a printed copy must not start out calling for items: no comparator value in the schematic data");

        // Crafted save data, part one: absurd numbers and row indices the keeper does not have. Loading must never
        // throw, and every number must come back inside its range.
        CompoundTag hostileValues = saved.copy();
        ListTag extremes = new ListTag();
        extremes.add(ruleTag(helper, 0, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE));
        extremes.add(ruleTag(helper, 1, 900L, 100L, 900L));
        extremes.add(ruleTag(helper, Integer.MAX_VALUE, 1L, 1L, 1L));
        extremes.add(ruleTag(helper, -5, 1L, 1L, 1L));
        hostileValues.put(StockKeeperRules.RULES_TAG, extremes);
        WarehouseStockKeeperBlockEntity crafted = loadCopy(helper, keeper, hostileValues);
        assertRulesInRange(helper, crafted);
        StockRule<ItemKey> first = crafted.rules().ruleAt(0).orElseThrow();
        helper.assertValueEqual(first.minimum(), StockRule.UNSET, "a negative minimum reads as off");
        helper.assertValueEqual(first.maximum(), StockRule.MAX_AMOUNT, "a huge maximum is clamped to the cap");
        helper.assertValueEqual(first.reserve(), StockRule.UNSET, "a negative reserve reads as off");
        StockRule<ItemKey> second = crafted.rules().ruleAt(1).orElseThrow();
        helper.assertValueEqual(second.maximum(), 900L, "a saved maximum below its minimum is raised, not obeyed");
        helper.assertValueEqual(second.reserve(), 900L, "and the reserve is clamped against the raised maximum");

        // Crafted save data, part two: far more rows than the keeper has. The list stays bounded and nothing throws.
        CompoundTag hostileFlood = saved.copy();
        ListTag flood = new ListTag();
        for (int i = 0; i < 4 * StockKeeperRules.MAX_ROWS; i++)
            flood.add(ruleTag(helper, i, i, i, i));
        hostileFlood.put(StockKeeperRules.RULES_TAG, flood);
        WarehouseStockKeeperBlockEntity flooded = loadCopy(helper, keeper, hostileFlood);
        helper.assertTrue(flooded.rules().size() <= StockKeeperRules.MAX_ROWS, "the row list stays bounded");
        assertRulesInRange(helper, flooded);

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asItem(), pos, DROP_RADIUS);
            // A broken or wrenched keeper drops a plain block, exactly like a production station with patterns and a
            // warehouse interface with a store filter: the drop is the loot table, which carries no block entity data.
            // The rules live in the world, not in the item, and only a schematic carries them along (writeSafe above).
            ItemStack dropped = droppedKeeper(helper, pos);
            helper.assertFalse(dropped.has(DataComponents.BLOCK_ENTITY_DATA),
                    "the dropped keeper carries no block entity data");
            helper.assertTrue(ItemStack.isSameItemSameComponents(dropped,
                    WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asStack()),
                    "and stacks with any other keeper, so no rule is hidden in it");
        }).thenSucceed();
    }

    /**
     * Hostile rule payloads: no menu at all, a menu id that does not match, another player's menu, a player who walked
     * away, a row or field that does not exist, and more edits in one tick than the menu answers. None of them throws
     * and none of them writes a rule.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stockKeeperHostilePayloads(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, BASE_Y, 3);
        helper.setBlock(pos, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState());
        WarehouseStockKeeperBlockEntity keeper = keeperAt(helper, pos);
        Player player = playerAt(helper, pos);
        StockKeeperMenu menu = openMenu(player, keeper);

        // Each group gets a tick of its own: the per-tick edit budget is exactly what the last group measures, and a
        // group before it would have spent it.
        helper.startSequence().thenExecute(() -> {
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(setItem(player, menu.containerId, 0).isEmpty(),
                    "a player without a keeper menu cannot write a rule");
            player.containerMenu = menu;
            helper.assertTrue(setItem(player, OTHER_CONTAINER_ID, 0).isEmpty(),
                    "a payload for another menu is ignored");
            helper.assertTrue(setItem(null, menu.containerId, 0).isEmpty(), "no player, no rule");
            Player stranger = playerAt(helper, pos);
            helper.assertTrue(setItem(stranger, menu.containerId, 0).isEmpty(),
                    "another player cannot use this player's menu");

            // A player who walked away keeps their menu for a moment; the keeper refuses them anyway.
            Player far = helper.makeMockPlayer(GameType.SURVIVAL);
            Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
            far.moveTo(center.x + FAR_AWAY_BLOCKS, center.y, center.z);
            StockKeeperMenu farMenu = openMenu(far, keeper);
            helper.assertValueEqual(setItem(far, farMenu.containerId, 0), Optional.of(false), "too far away: refused");
            helper.assertFalse(farMenu.stillValid(far), "and their menu is closed on the next tick");
            helper.assertTrue(keeper.rules().isEmpty(), "nothing was written so far");
        }).thenIdle(1).thenExecute(() -> {
            // Indices and fields the keeper does not have simply do nothing.
            helper.assertValueEqual(setItem(player, menu.containerId, -1), Optional.of(false), "a negative row");
            helper.assertValueEqual(setItem(player, menu.containerId, Integer.MAX_VALUE), Optional.of(false),
                    "a row far outside the keeper");
            helper.assertValueEqual(StockKeeperMenu.submitRule(player, menu.containerId, 0, 99, Optional.of(IRON), 1L),
                    Optional.of(false), "an unknown field");
            // A number without an item governs nothing, so the item has to come first.
            helper.assertValueEqual(StockKeeperMenu.submitRule(player, menu.containerId, 0,
                    StockKeeperRules.FIELD_MINIMUM, Optional.empty(), 64L), Optional.of(false),
                    "a number on a row without an item");
            helper.assertTrue(keeper.rules().isEmpty(), "and none of it wrote a rule");
        }).thenIdle(1).thenExecute(() -> {
            // The per-tick budget: the first edits land, the flood behind them is dropped without an answer.
            helper.assertValueEqual(setItem(player, menu.containerId, 0), Optional.of(true), "the first edit lands");
            for (int edit = 1; edit < StockKeeperMenu.MAX_EDITS_PER_TICK; edit++)
                helper.assertTrue(setItem(player, menu.containerId, 0).isPresent(), "edit " + edit + " is answered");
            helper.assertTrue(setItem(player, menu.containerId, 1).isEmpty(),
                    "the edit beyond the per-tick budget is dropped");
            helper.assertTrue(keeper.rules().ruleAt(1).isEmpty(), "and it wrote nothing");
        }).thenIdle(1).thenExecute(() -> {
            // Absurd numbers reach the keeper and are corrected there, never stored as written.
            StockKeeperMenu.submitRule(player, menu.containerId, 0, StockKeeperRules.FIELD_MINIMUM,
                    Optional.empty(), Long.MAX_VALUE);
            helper.assertValueEqual(keeper.rules().ruleAt(0).orElseThrow().minimum(), StockRule.MAX_AMOUNT,
                    "a huge minimum is clamped");
            StockKeeperMenu.submitRule(player, menu.containerId, 0, StockKeeperRules.FIELD_MINIMUM,
                    Optional.empty(), Long.MIN_VALUE);
            helper.assertValueEqual(keeper.rules().ruleAt(0).orElseThrow().minimum(), StockRule.UNSET,
                    "a negative minimum reads as off");
        }).thenSucceed();
    }

    // --- the aisle -------------------------------------------------------------------------------------------------

    /**
     * A stock keeper joins its aisle like a station and the controller's copy of the rules follows it: an edit is in
     * the copy at once, a keeper turned away from the aisle leaves it again, and breaking it takes its rules with it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockKeeperMembership(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.locationAt(aisle.absoluteRackPos(KEEPER_RACK))
                            .map(record -> record.kind()), Optional.of(LocationKind.KEEPER), "the keeper is a member");
                    helper.assertTrue(controller.stockRules().isEmpty(), "an unconfigured keeper adds no rule");

                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    // Not on some later tick: an edit is in the controller's copy before the next plan.
                    StockRules<ItemKey> rules = controller.stockRules();
                    helper.assertValueEqual(rules.size(), 1, "the controller copied the rule at once");
                    helper.assertValueEqual(rules.ruleFor(IRON).map(StockRule::maximum), Optional.of((long) IRON_MAXIMUM),
                            "and with its maximum");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), (long) IRON_MAXIMUM, "headroom");
                    helper.assertValueEqual(controller.stockRuleStatus(aisle.absoluteRackPos(KEEPER_RACK), 0),
                            StockRuleStatus.SATISFIED, "nothing of it is stored yet");
                })
                .thenExecute(() -> {
                    // Turned away from the aisle it is misaligned, so it is no member any more and governs nothing.
                    helper.setBlock(aisle.rackPos(KEEPER_RACK), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                            .setValue(WarehouseStockKeeperBlock.FACING, aisle.sideDirection(KEEPER_RACK)));
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                    helper.assertTrue(controller.stockRules().isEmpty(), "a misaligned keeper governs nothing");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), Long.MAX_VALUE, "and caps nothing");
                })
                .thenExecute(() -> {
                    aisle.stockKeeper(KEEPER_RACK);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().stockRules().size(), 1,
                        "a keeper placed again governs again"))
                .thenExecute(() -> aisle.breakBlock(aisle.rackPos(KEEPER_RACK)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.controller().stockRules().isEmpty(), "a broken keeper takes its rules");
                    helper.assertValueEqual(aisle.controller().storeHeadroom(IRON), Long.MAX_VALUE, "no cap left");
                })
                .thenSucceed();
    }

    /**
     * The one failure that would be permanent: a controller that plans before it has read its keepers back. The copy
     * is therefore saved <b>with the controller</b>, so a controller replaced by a copy loaded from its save knows
     * every maximum and every reserve before its first tick — and the iron that arrives in that very tick is not
     * stored past the maximum.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockKeeperColdReload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A);
        aisle.storage(STORAGE_B);
        aisle.storage(STORAGE_C);
        aisle.input(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reloaded controller plans"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(3, 1, 0))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    helper.assertValueEqual(aisle.controller().stockRules().size(), 1, "the rule is in the copy");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenExecute(() -> {
                    // The iron arrives and the controller is replaced by a copy loaded from its save in the same tick,
                    // so the very next dispatch plans with a controller that has never seen the keeper.
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
                    feed(aisle, conserved, IRON.toStack(FED_IRON));
                    BlockEntity loaded = BlockEntity.loadStatic(controller.getBlockPos(), controller.getBlockState(),
                            saved, level.registryAccess());
                    if (!(loaded instanceof WarehouseControllerBlockEntity reloaded)) {
                        helper.fail("a saved controller must load again as one");
                        return;
                    }
                    // Before the first tick, before any keeper was read: the rules are simply there.
                    helper.assertValueEqual(reloaded.stockRules().ruleFor(IRON).map(StockRule::maximum),
                            Optional.of((long) IRON_MAXIMUM), "the reloaded controller already knows the maximum");
                    level.setBlockEntity(reloaded);
                    helper.assertTrue(controller.isRemoved(), "the controller block entity was replaced");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) IRON_MAXIMUM,
                            "nothing was stored past the maximum in the cold window");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) (FED_IRON - IRON_MAXIMUM),
                            "the rest stays in the input on purpose");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- enforcement -----------------------------------------------------------------------------------------------

    /**
     * The maximum caps storing <b>exactly</b>, and only for its own item: 24 iron with a maximum of 8 leave 16 in the
     * input, the gold in the same buffer is stored in the same run, and the planner reports {@code AT_MAXIMUM} rather
     * than "warehouse full" — a player standing in front of eleven empty chests must not be told the warehouse is
     * full.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockKeeperMaximumStopsStoring(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_A);
        aisle.storage(STORAGE_B);
        aisle.input(INPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a maximum caps storing"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 1, 0))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    feed(aisle, conserved, IRON.toStack(FED_IRON), GOLD.toStack(FED_GOLD));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(IRON), (long) IRON_MAXIMUM, "iron stored");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) (FED_IRON - IRON_MAXIMUM),
                            "the surplus stays in the input");
                    helper.assertValueEqual(controller.countOf(GOLD), (long) FED_GOLD,
                            "a capped item never blocks another item type");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, GOLD), 0L, "the gold left the input");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), 0L, "no headroom left");
                    helper.assertValueEqual(controller.lastPlanReason(), Optional.of(NoJobReason.AT_MAXIMUM),
                            "reported as a maximum, never as a full warehouse");
                    helper.assertValueEqual(controller.stockRuleStatus(aisle.absoluteRackPos(KEEPER_RACK), 0),
                            StockRuleStatus.AT_MAXIMUM, "and the keeper says so too");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The reserve holds the last items back from the warehouse's own automation and from nobody else: a
     * redstone-style request stops at it and is told {@link RequestRejection#RESERVED}, a player at a terminal is
     * served down to the last item.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockKeeperReserveBlocksAutomation(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.output(OUTPUT_RACK);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) DIAMONDS_IN_STOCK, "stock");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, DIAMOND_RESERVE);

                    helper.assertValueEqual(controller.availableTo(StockAccess.PLAYER, DIAMOND),
                            (long) DIAMONDS_IN_STOCK, "a player may have everything");
                    helper.assertValueEqual(controller.availableTo(StockAccess.AUTOMATION, DIAMOND),
                            (long) (DIAMONDS_IN_STOCK - DIAMOND_RESERVE), "automation stops at the reserve");

                    BlockPos output = aisle.absoluteRackPos(OUTPUT_RACK);
                    RequestResult automated = controller.request(output, DIAMOND, DIAMONDS_IN_STOCK,
                            RequestQueue.NO_AMOUNT_LIMIT, StockAccess.AUTOMATION);
                    helper.assertTrue(automated.isAccepted(), "accepted: " + automated);
                    helper.assertValueEqual(automated.request().orElseThrow().requested(),
                            DIAMONDS_IN_STOCK - DIAMOND_RESERVE, "clamped to what is not reserved");

                    RequestResult refused = controller.request(output, DIAMOND, DIAMONDS_IN_STOCK,
                            RequestQueue.NO_AMOUNT_LIMIT, StockAccess.AUTOMATION);
                    helper.assertValueEqual(refused.rejection(), Optional.of(RequestRejection.RESERVED),
                            "refused with the reason a player can act on, never 'not in stock'");
                    helper.assertValueEqual(controller.stockRuleStatus(aisle.absoluteRackPos(KEEPER_RACK), 0),
                            StockRuleStatus.AT_RESERVE, "the keeper reports the reserve");

                    // The same items, asked for by a player: served, because a reserve protects them from the
                    // warehouse's own automation and not from their owner (M15 user decision).
                    RequestResult byPlayer = controller.request(output, DIAMOND, DIAMOND_RESERVE,
                            RequestQueue.NO_AMOUNT_LIMIT, StockAccess.PLAYER);
                    helper.assertTrue(byPlayer.isAccepted(), "a player is served from the reserve: " + byPlayer);
                })
                .thenSucceed();
    }

    /**
     * Two rules for one item: the first one governs and the second applies nothing and says why. Merging them
     * ("strictest wins") is deliberately not done — it can produce a minimum above a maximum, and a player could not
     * see which row produced the number.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockKeeperShadowedDuplicate(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A);
        aisle.stockKeeper(KEEPER_RACK);
        aisle.stockKeeper(SECOND_KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity first = aisle.stockKeeperAt(KEEPER_RACK);
                    first.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    first.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, IRON_MAXIMUM);
                    first.editRule(1, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    first.editRule(1, StockKeeperRules.FIELD_MAXIMUM, null, 999L);
                    WarehouseStockKeeperBlockEntity second = aisle.stockKeeperAt(SECOND_KEEPER_RACK);
                    second.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    second.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 999L);

                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.stockRules().size(), 3, "every rule is listed");
                    helper.assertValueEqual(controller.stockRules().governingCount(), 1, "exactly one governs");
                    helper.assertValueEqual(controller.storeHeadroom(IRON), (long) IRON_MAXIMUM,
                            "the first rule of the first keeper governs");
                    BlockPos firstPos = aisle.absoluteRackPos(KEEPER_RACK);
                    helper.assertValueEqual(controller.stockRuleStatus(firstPos, 0), StockRuleStatus.SATISFIED,
                            "the governing row");
                    helper.assertValueEqual(controller.stockRuleStatus(firstPos, 1), StockRuleStatus.SHADOWED,
                            "the duplicate in the same keeper");
                    helper.assertValueEqual(controller.stockRuleStatus(aisle.absoluteRackPos(SECOND_KEEPER_RACK), 0),
                            StockRuleStatus.SHADOWED, "and the duplicate in the keeper further along the aisle");
                })
                .thenSucceed();
    }

    /**
     * The minimum is a signal: while the warehouse holds less than it asks for, the keeper's lamp burns and its
     * comparator calls for the item; once the stock is there, both go quiet again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockKeeperMinimumSignal(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        BlockPos chest = aisle.storage(STORAGE_A);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, IRON_MINIMUM);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT), "the lamp burns below the minimum");
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).comparatorSignal(), 1,
                            "and the comparator calls for the item");
                    helper.assertValueEqual(aisle.controller().stockRuleStatus(aisle.absoluteRackPos(KEEPER_RACK), 0),
                            StockRuleStatus.BELOW_MINIMUM, "status");
                })
                .thenExecute(() -> aisle.insertAll(aisle.handlerAt(chest), IRON.toStack(IRON_MINIMUM)))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) IRON_MINIMUM, "the stock is in");
                    helper.assertFalse(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                            .getValue(WarehouseStockKeeperBlock.LIT), "the lamp goes out at the minimum");
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).comparatorSignal(), 0,
                            "and the comparator goes quiet");
                })
                .thenSucceed();
    }

    /**
     * A keeper only ever signals for a warehouse that really reads it. Turning it away from the aisle and breaking its
     * controller each take its rules away, and both put its lamp out and drop its comparator to 0 — the signal says
     * "this warehouse is short of an item", so a keeper nothing enforces must not keep a farm running. While it is
     * turned away, an edit in its own screen must not put its rules back into the controller's copy either.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void stockKeeperSignalNeedsAWarehouse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_A);
        aisle.stockKeeper(KEEPER_RACK);
        BlockPos rack = aisle.rackPos(KEEPER_RACK);
        Direction towardsAisle = aisle.sideDirection(KEEPER_RACK).getOpposite();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 0))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, IRON_MINIMUM);
                })
                .thenWaitUntil(() -> assertSignalling(helper, aisle, true, "while the warehouse is short of iron"))
                .thenExecute(() -> {
                    // The same block entity, turned away from the aisle: its rules stay written, but this warehouse
                    // does not read them any more.
                    helper.setBlock(rack, helper.getBlockState(rack)
                            .setValue(WarehouseStockKeeperBlock.FACING, towardsAisle.getOpposite()));
                    helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).rules().ruleCount(), 1,
                            "the keeper still holds its own rule");
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertFalse(controller.isMembershipDirty(), "membership processed");
                    helper.assertTrue(controller.stockRules().isEmpty(), "a misaligned keeper governs nothing");
                    assertSignalling(helper, aisle, false, "after it was turned away from the aisle");
                })
                .thenExecute(() -> {
                    // An edit, a chunk load or a /data merge all notify the controller for this position. None of them
                    // may put the rules of a keeper that does not face the aisle back into the copy.
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_MINIMUM, null, 32L);
                    helper.assertTrue(aisle.controller().stockRules().isEmpty(),
                            "an edit must not put a misaligned keeper's rules back");
                })
                .thenExecute(() -> helper.setBlock(rack, helper.getBlockState(rack)
                        .setValue(WarehouseStockKeeperBlock.FACING, towardsAisle)))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().stockRules().size(), 1, "turned back it governs again");
                    assertSignalling(helper, aisle, true, "after it was turned back towards the aisle");
                })
                .thenExecute(() -> aisle.breakBlock(aisle.controllerPos()))
                .thenWaitUntil(() -> assertSignalling(helper, aisle, false, "after the controller was broken"))
                .thenSucceed();
    }

    /** The keeper's two redstone-visible states, which must always say the same thing. */
    private static void assertSignalling(GameTestHelper helper, AisleFixture aisle, boolean expected, String when) {
        helper.assertValueEqual(helper.getBlockState(aisle.rackPos(KEEPER_RACK))
                .getValue(WarehouseStockKeeperBlock.LIT), expected, "the lamp " + when);
        helper.assertValueEqual(aisle.stockKeeperAt(KEEPER_RACK).comparatorSignal(), expected ? 1 : 0,
                "the comparator " + when);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static void feed(AisleFixture aisle, Map<ItemKey, Long> conserved, ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), stack.copy());
            ItemCensus.change(conserved, ItemKey.of(stack), stack.getCount());
        }
    }

    /** The dropped keeper block item near {@code pos}. */
    private static ItemStack droppedKeeper(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(absolute).inflate(DROP_RADIUS))) {
            if (entity.getItem().is(WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asItem()))
                return entity.getItem();
        }
        helper.fail("no dropped stock keeper item", pos);
        return ItemStack.EMPTY;
    }

    private static WarehouseStockKeeperBlockEntity keeperAt(GameTestHelper helper, BlockPos pos) {
        WarehouseStockKeeperBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse stock keeper block entity", pos);
        return be;
    }

    private static WarehouseStockKeeperBlockEntity loadCopy(GameTestHelper helper,
            WarehouseStockKeeperBlockEntity live, CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof WarehouseStockKeeperBlockEntity keeper)) {
            helper.fail("a saved stock keeper must load again as one");
            return live;
        }
        return keeper;
    }

    /** One saved rule row, as a crafted save file could contain it. */
    private static CompoundTag ruleTag(GameTestHelper helper, int row, long minimum, long maximum, long reserve) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Row", row);
        tag.put("Item", IRON.save(helper.getLevel().registryAccess()));
        tag.putLong("Min", minimum);
        tag.putLong("Max", maximum);
        tag.putLong("Reserve", reserve);
        return tag;
    }

    private static void assertInRange(GameTestHelper helper, long value, String what) {
        helper.assertTrue(value >= StockRule.UNSET && value <= StockRule.MAX_AMOUNT,
                what + " is outside its range: " + value);
    }

    /** Every loaded rule is a sane one, whatever the save data said. */
    private static void assertRulesInRange(GameTestHelper helper, WarehouseStockKeeperBlockEntity keeper) {
        for (int row = 0; row < keeper.rules().size(); row++) {
            Optional<StockRule<ItemKey>> rule = keeper.rules().ruleAt(row);
            if (rule.isEmpty())
                continue;
            assertInRange(helper, rule.get().minimum(), "minimum of row " + row);
            assertInRange(helper, rule.get().maximum(), "maximum of row " + row);
            assertInRange(helper, rule.get().reserve(), "reserve of row " + row);
            helper.assertTrue(!rule.get().hasMaximum() || rule.get().minimum() <= rule.get().maximum(),
                    "a crafted minimum above the maximum is resolved in row " + row);
            helper.assertTrue(!rule.get().hasMaximum() || rule.get().reserve() <= rule.get().maximum(),
                    "a crafted reserve above the maximum is clamped in row " + row);
        }
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static StockKeeperMenu openMenu(Player player, WarehouseStockKeeperBlockEntity keeper) {
        StockKeeperMenu menu = StockKeeperMenu.create(player.containerMenu.containerId + 1, player.getInventory(),
                keeper);
        player.containerMenu = menu;
        return menu;
    }

    /** Sends "put iron into that row" through the payload entry point. */
    private static Optional<Boolean> setItem(Player player, int containerId, int row) {
        return StockKeeperMenu.submitRule(player, containerId, row, StockKeeperRules.FIELD_ITEM, Optional.of(IRON), 0L);
    }
}
