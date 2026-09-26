package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PortRankBehaviour;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.StationGoggleSummary;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlock;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the warehouse <b>port</b> settings (M17, issue #12, {@code docs/warehouse-system.md} §3.2): the warehouse
 * output gains a direction (request or accept), a rank and a redstone behaviour, and the requesting direction learns to
 * keep a machine supplied without a clock.
 * <p>
 * The load-bearing test is {@link #portWorldCompatibility}: a port saved before M17 has neither the block state property
 * nor any of the new block entity keys, and it has to load as "requests, on a rising edge" and run the whole request and
 * delivery loop exactly as it did. It is proved by writing the old shapes <b>by hand</b> rather than by trusting a
 * default. {@link #portRequestPulse} pins the same behaviour for a port placed now.
 * <p>
 * The three redstone behaviours have one test each — {@link #portRequestPulse},
 * {@link #portRequestWhilePoweredFeedsAMachine}, {@link #portRequestUnlessPoweredWorksUnwired} — and the two rows of the
 * user's table that M17 already answers in full are the last two of those.
 * {@link #portAcceptPulseIsOneToken} and {@link #portDirectionFlipCancelsRequests} cover the accepting direction as far
 * as it exists: it is a <b>setting</b> here, and the store plan that ranks it arrives in the next step.
 * <p>
 * Two tests cover what changes a port's policy or spends its token <b>without</b> a player:
 * {@link #portRerouteIntoAPulsePortSpendsTheToken} (a store reroute is the second way a job reaches a port, and it must
 * spend the same pulse token) and {@link #portDataReplacedInPlaceIsReRead} (a command, a schematic or the zapper
 * overwriting the block entity, which runs no callback and no {@code onLoad}).
 * <p>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1 at z = 3 along +X,
 * {@value #RAILS} rails), a chest behind an interface on the left rack plane, the port on the right with a redstone
 * trigger block south of it. Every test that moves items asserts the item conservation invariant on each tick
 * ({@link ItemCensus}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehousePortGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    /** Long enough for several dispatch intervals and a retry back-off: proof that nothing happens any more. */
    private static final int SETTLE_TICKS = 60;

    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    /** A storage location at the far end of the aisle, three racks from the input station. */
    private static final RackPosition FAR_STORAGE_RACK = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition PORT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition SECOND_PORT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    /** A warehouse output outside the aisle geometry: a valid block, and a member of nothing (test-relative). */
    private static final BlockPos OUTSIDE_PORT = new BlockPos(6, BASE_Y, 0);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    /** One item per port buffer slot, so as many of them as the buffer has slots fill it completely. */
    private static final ItemKey FILLER_ITEM = ItemKey.of(Items.WATER_BUCKET);

    /** Requested amount used throughout: small enough that one stock needs several trips. */
    private static final int PER_TRIP = 8;
    /** Iron in the chest: five trips of {@value #PER_TRIP}, so "topped up without a clock" is unmistakable. */
    private static final int IRON_IN_STOCK = 40;
    /** A second helping of iron, added while the port is switched off. */
    private static final int IRON_ADDED = 16;
    private static final int DIAMONDS_IN_STOCK = 20;
    /** A rank with a magnitude, so a mix-up with the default 0 would show. */
    private static final int OVERFLOW_RANK = -4;
    private static final int DIVERSION_RANK = 3;
    /** Items of a stock rule's maximum, the reason an overflow exists at all (M15, issue #3). */
    private static final int IRON_MAXIMUM = 8;
    /** Fed into the input at once: more than {@link #IRON_MAXIMUM}, so the surplus has to go somewhere. */
    private static final int IRON_FED = 24;
    /** What one trip carries of a stackable item ({@code grabberStacks} × one stack, the config default). */
    private static final int CARRY_LIMIT = 64;

    /** Create's clipboard keys, as a funnel writes them. */
    private static final String FILTER_TAG = "Filter";
    private static final String CLIPBOARD_VALUE_TAG = "Value";
    private static final String CLIPBOARD_ROW_TAG = "Row";
    /** The extracted amount on a funnel's clipboard; anything but {@link #PER_TRIP}, so a mix-up would show. */
    private static final int FUNNEL_AMOUNT = 7;
    /** Board row a funnel's clipboard carries for "exactly", which must never become a redstone behaviour. */
    private static final int FUNNEL_EXACTLY_ROW = 1;
    /** Create's own board key for the filter amount, which the output has always kept at "up to". */
    private static final String UP_TO_TAG = "UpTo";
    private static final String FILTER_AMOUNT_TAG = "FilterAmount";

    private WarehousePortGameTests() {
    }

    // --- the settings themselves --------------------------------------------------------------------------------------

    /**
     * A port placed now is a plain warehouse output: it <b>requests</b>, it acts on a rising edge, its block state says
     * so, and none of the three new block entity keys is written at all — which is what makes a save from before M17 and
     * a save written now the same bytes for an unconfigured port.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portDefaultsToAPlainOutput(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.output(PORT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 0, 1))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    helper.assertValueEqual(port.portSettings(), PortSettings.DEFAULT, "an unconfigured port");
                    helper.assertValueEqual(port.portDirection(), PortDirection.REQUEST, "it requests");
                    helper.assertValueEqual(port.redstoneMode(), PortRedstone.PULSE, "on a rising edge");
                    helper.assertValueEqual(port.portRank(), PortSettings.REQUEST_RANK, "rank 0");
                    helper.assertFalse(port.isArmed(), "and holds no pulse token");
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.ACCEPTING, false);

                    CompoundTag tag = port.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.assertFalse(tag.contains(PortRankBehaviour.RANK_TAG), "no rank key");
                    helper.assertFalse(tag.contains(RequestFilterBehaviour.REDSTONE_MODE_TAG), "no redstone mode key");
                    helper.assertFalse(tag.contains(WarehouseOutputBlockEntity.ARMED_TAG), "no pulse token key");
                })
                .thenSucceed();
    }

    /**
     * Both settings, all three redstone behaviours and the whole rank range go in and come back out — through the
     * value-box boards a player really uses, not through the setters, so the row/column encoding is what is tested
     * ({@link PortSettings#rankOf}).
     * <p>
     * The block state follows the rank, and only the rank: it is derived display state, so a {@code /setblock} with the
     * wrong value is corrected on the next load instead of being believed.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portSettingsThroughTheBoards(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.output(PORT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 0, 1))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    RequestFilterBehaviour filter = filterOf(helper, port);
                    PortRankBehaviour rank = rankOf(helper, port);
                    helper.assertTrue(filter.setFilter(IRON.toStack()), "the port takes a filter");

                    // The filter board: rows are the redstone behaviour, the column is the requested amount.
                    for (PortRedstone mode : PortRedstone.values()) {
                        filter.setValueSettings(player, new ValueSettings(mode.ordinal(), PER_TRIP), false);
                        helper.assertValueEqual(port.redstoneMode(), mode, "row " + mode.ordinal() + " is " + mode);
                        helper.assertValueEqual(port.requestAmount(), PER_TRIP, "the column stays the amount");
                        helper.assertValueEqual(filter.getValueSettings(), new ValueSettings(mode.ordinal(), PER_TRIP),
                                "the board opens on the current setting");
                    }

                    // The wrench board: the row carries the sign, the column the magnitude.
                    for (int magnitude = 0; magnitude <= PortSettings.MAX_STRENGTH; magnitude++) {
                        rank.setValueSettings(player, new ValueSettings(PortSettings.OVERFLOW_ROW, magnitude), false);
                        helper.assertValueEqual(port.portRank(), -(magnitude + 1), "overflow " + magnitude);
                        helper.assertValueEqual(port.portDirection(), PortDirection.ACCEPT, "it accepts");
                        rank.setValueSettings(player, new ValueSettings(PortSettings.DIVERSION_ROW, magnitude), false);
                        helper.assertValueEqual(port.portRank(), magnitude + 1, "diversion " + magnitude);
                    }
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.ACCEPTING, true);
                    helper.assertValueEqual(rank.getValueSettings(),
                            new ValueSettings(PortSettings.DIVERSION_ROW, PortSettings.MAX_STRENGTH),
                            "the board opens on the current rank");

                    rank.setValueSettings(player, new ValueSettings(PortSettings.REQUEST_ROW, 5), false);
                    helper.assertValueEqual(port.portRank(), PortSettings.REQUEST_RANK, "the request row ignores the column");
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.ACCEPTING, false);

                    // In the accepting direction the amount is not a setting: the row still changes, the number does not.
                    helper.assertTrue(port.setPortRank(OVERFLOW_RANK), "accepting again");
                    filter.setValueSettings(player, new ValueSettings(PortRedstone.PULSE.ordinal(), 1), false);
                    helper.assertValueEqual(port.redstoneMode(), PortRedstone.PULSE, "the row still applies");
                    helper.assertValueEqual(port.requestAmount(), PER_TRIP, "the amount a player set is not eaten");
                    helper.assertTrue(port.setPortRank(PortSettings.REQUEST_RANK), "back to requesting");
                    helper.assertValueEqual(port.requestAmount(), PER_TRIP, "and it is still there");
                })
                .thenExecute(() -> {
                    // A /setblock that claims the wrong direction is corrected by the block entity, not believed.
                    BlockPos pos = aisle.rackPos(PORT_RACK);
                    helper.setBlock(pos, helper.getBlockState(pos).setValue(WarehouseOutputBlock.ACCEPTING, true));
                })
                .thenExecute(() -> {
                    aisle.outputAt(PORT_RACK).onLoad();
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.ACCEPTING, false);
                })
                .thenSucceed();
    }

    // --- the redstone behaviours --------------------------------------------------------------------------------------

    /**
     * The compatibility row of the user's table, for a port placed now: filter plus a rising edge hands out once, a held
     * signal repeats nothing, and a second edge asks again. Byte for byte the behaviour of every warehouse output before
     * M17 — the M2 path, unchanged.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portRequestPulse(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.output(PORT_RACK);
        BlockPos trigger = trigger(aisle);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a pulse port hands out"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    setRequest(helper, aisle, IRON.toStack(), PER_TRIP);
                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "one pulse, one trip"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                            "a held signal repeats nothing");
                    helper.assertValueEqual(openRequests(aisle), 0, "and queues nothing");
                })
                .thenExecute(() -> {
                    helper.setBlock(trigger, Blocks.AIR);
                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) (2 * PER_TRIP),
                        "the next edge asks again"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * The user's row "Request / while powered / iron ingot": a machine is kept supplied with <b>no clock at all</b>. One
     * signal, and the port is topped up trip by trip until the stock runs out, with at most <b>one</b> open request at
     * any moment (asserted on every tick, which is what "never queues up" means).
     * <p>
     * The falling edge is proved not to recall anything: the signal is taken away while a request is open, and those items
     * still arrive. Nothing more follows until the signal is back.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portRequestWhilePoweredFeedsAMachine(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.output(PORT_RACK);
        BlockPos trigger = trigger(aisle);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while a continuous port is topped up");
            helper.assertTrue(openRequests(aisle) <= 1, "at most one open request at a time");
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    setRequest(helper, aisle, IRON.toStack(), PER_TRIP);
                    helper.assertTrue(aisle.outputAt(PORT_RACK).setRedstoneMode(PortRedstone.WHILE_POWERED), "mode set");
                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(openRequests(aisle), 1, "the edge submits at once"))
                .thenExecute(() -> helper.setBlock(trigger, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "switching off never recalls what is already promised"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                            "and nothing follows while the signal is gone");
                    helper.assertValueEqual(openRequests(aisle), 0, "no open request either");
                })
                .thenExecute(() -> helper.setBlock(trigger, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) IRON_IN_STOCK,
                        "the rest arrives trip by trip, with no further signal"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), 0L, "the chest is empty");
                    helper.assertValueEqual(aisle.outputAt(PORT_RACK).lastRejection(),
                            Optional.of(RequestRejection.NOT_IN_STOCK), "and the port says why it stopped");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The user's row "off while powered": a port that acts <b>unless</b> powered works with no wiring at all — nothing is
     * ever pulsed here — and a lever switches it off. Setting the mode is the only event, and the controller's own pass
     * takes it from there.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portRequestUnlessPoweredWorksUnwired(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.output(PORT_RACK);
        BlockPos trigger = trigger(aisle);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an unwired port hands out"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    setRequest(helper, aisle, IRON.toStack(), PER_TRIP);
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.POWERED, false);
                    helper.assertTrue(aisle.outputAt(PORT_RACK).setRedstoneMode(PortRedstone.UNLESS_POWERED), "mode set");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) IRON_IN_STOCK,
                        "everything in stock leaves without a single redstone signal"))
                .thenExecute(() -> {
                    // The lever: from now on nothing more, however much stock arrives.
                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.POWERED, true);
                    aisle.insertAll(aisle.handlerAt(aisle.inventoryPos(STORAGE_RACK)), IRON.toStack(IRON_ADDED));
                    ItemCensus.change(conserved, IRON, IRON_ADDED);
                    helper.assertTrue(aisle.controller().refreshLocation(STORAGE_RACK), "stock refreshed");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) IRON_IN_STOCK,
                            "a powered port acts no more");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_ADDED,
                            "the new stock stays in the chest");
                    helper.assertValueEqual(openRequests(aisle), 0, "and nothing is promised");
                })
                .thenExecute(() -> helper.setBlock(trigger, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON),
                        (long) (IRON_IN_STOCK + IRON_ADDED), "the falling edge starts it again"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- the accepting direction as a setting -------------------------------------------------------------------------

    /**
     * An accepting port in pulse mode is <b>armed</b> by a rising edge, and a clock cannot accumulate anything: the token
     * is a boolean, so three edges are one token, and taking it takes it once. Flipping back to requesting clears it, so
     * a pulse a player gave to an accepting port can never come back as an export after they changed their mind.
     * <p>
     * The token also has to <b>reach a client</b>, because the goggle line that says whether the port may act is built
     * there: the rank, the mode and the signal travel by themselves, the token only in the goggle summary. Without it an
     * accepting pulse port read "waiting for a signal" for ever — the one combination that line exists for.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portAcceptPulseIsOneToken(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.output(PORT_RACK);
        BlockPos trigger = trigger(aisle);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 0, 1))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    helper.assertTrue(port.setPortRank(OVERFLOW_RANK), "the port accepts now");
                    helper.assertValueEqual(port.portSettings(),
                            new PortSettings(OVERFLOW_RANK, PortRedstone.PULSE), "overflow, on a pulse");
                    helper.assertFalse(port.isArmed(), "and holds no token yet");
                    helper.assertFalse(port.isGateOpen(), "so its gate is shut");
                    port.onGoggleObserved();
                    helper.assertFalse(tokenInClientPacket(helper, port), "and a client is told nothing about one");

                    for (int pulse = 0; pulse < 3; pulse++) {
                        helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                        helper.setBlock(trigger, Blocks.AIR);
                    }
                    helper.assertTrue(port.isArmed(), "a rising edge arms it");
                    helper.assertTrue(port.isGateOpen(), "the gate is open while the token is there");
                    port.onGoggleObserved();
                    helper.assertTrue(port.summary().portArmed(), "the token is in the goggle summary");
                    helper.assertTrue(tokenInClientPacket(helper, port),
                            "and travels in the client packet, which is the only way a goggle tooltip can read it");
                    helper.assertTrue(port.consumeArmed(), "and the token can be taken");
                    helper.assertFalse(port.consumeArmed(), "exactly once: three pulses were never three tokens");
                    helper.assertFalse(port.isGateOpen(), "the gate shuts again");

                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                    helper.assertTrue(port.isArmed(), "armed again");
                    helper.assertTrue(port.setPortRank(PortSettings.REQUEST_RANK), "back to requesting");
                    helper.assertFalse(port.isArmed(), "a requesting port holds no export token");
                })
                .thenSucceed();
    }

    /**
     * A port that stops requesting loses what it still waits for: nothing may be delivered to a port that no longer asks
     * for anything, and nothing is over-delivered. The diamonds stay where they were.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portDirectionFlipCancelsRequests(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.output(PORT_RACK);
        BlockPos trigger = trigger(aisle);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a port changes its mind"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    setRequest(helper, aisle, DIAMOND.toStack(), PER_TRIP);
                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                    helper.assertValueEqual(openRequests(aisle), 1, "the pulse was accepted");
                    // Same tick, before the crane can have picked anything up.
                    helper.assertTrue(aisle.outputAt(PORT_RACK).setPortRank(DIVERSION_RANK), "it accepts now");
                    helper.assertValueEqual(openRequests(aisle), 0, "and its request is gone");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, DIAMOND), 0L,
                            "nothing was delivered to a port that stopped asking");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) DIAMONDS_IN_STOCK,
                            "the diamonds never left the chest");
                    helper.assertValueEqual(openRequests(aisle), 0, "and nothing was queued again");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- world compatibility ------------------------------------------------------------------------------------------

    /**
     * <b>An existing world must not change.</b> A warehouse output saved before M17 carries
     * <ul>
     * <li>a block state with {@code facing} and {@code powered} and <b>no</b> {@code accepting} — a missing property
     * resolves to the block's default, and the default is "requests";</li>
     * <li>a block entity tag with {@code Filter}, {@code FilterAmount}, {@code UpTo}, {@code Buffer} and
     * {@code LastRejection} and <b>none</b> of {@code PortRank}, {@code RedstoneMode} or {@code PortArmed} — all three
     * read back as the plain output's values.</li>
     * </ul>
     * Both shapes are written <b>by hand</b> here rather than taken from a default, and the whole request and delivery
     * loop then runs on that port. Its block state is never rewritten afterwards, because it is already right.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portWorldCompatibility(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        Direction towardsAisle = aisle.sideDirection(PORT_RACK).getOpposite();
        BlockPos trigger = trigger(aisle);

        // Exactly what a chunk palette written before M17 holds: the block id, "facing" and "powered", no "accepting".
        CompoundTag saved = NbtUtils.writeBlockState(WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseStationBlock.FACING, towardsAisle));
        saved.getCompound("Properties").remove(WarehouseOutputBlock.ACCEPTING.getName());
        BlockState restored = NbtUtils.readBlockState(helper.getLevel().holderLookup(Registries.BLOCK), saved);
        helper.assertValueEqual(restored.getBlock(), WareworksBlocks.WAREHOUSE_OUTPUT.get(),
                "an old state still resolves to this block, not to air");
        helper.assertValueEqual(restored.getValue(WarehouseStationBlock.FACING), towardsAisle, "the opening is unchanged");
        helper.assertValueEqual(restored.getValue(WarehouseOutputBlock.ACCEPTING), false,
                "the missing property resolves to the direction every old output had");
        helper.assertValueEqual(WarehouseOutputBlock.directionOf(restored), PortDirection.REQUEST, "it requests");
        helper.setBlock(aisle.rackPos(PORT_RACK), restored);

        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an old port hands out"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    setRequest(helper, aisle, IRON.toStack(), PER_TRIP);

                    // A pre-M17 block entity tag: what M16 wrote, plus an "exactly" amount from an even older save.
                    CompoundTag old = port.saveWithoutMetadata(registries);
                    old.remove(PortRankBehaviour.RANK_TAG);
                    old.remove(RequestFilterBehaviour.REDSTONE_MODE_TAG);
                    old.remove(WarehouseOutputBlockEntity.ARMED_TAG);
                    old.putBoolean(UP_TO_TAG, false);
                    helper.assertTrue(old.contains(FILTER_TAG), "the old tag still carries the filter");
                    helper.assertValueEqual(old.getInt(FILTER_AMOUNT_TAG), PER_TRIP, "and the amount");

                    port.loadWithComponents(old, registries);
                    helper.assertValueEqual(port.portSettings(), PortSettings.DEFAULT,
                            "an old port requests, on a rising edge");
                    helper.assertFalse(port.isArmed(), "and holds no export token");
                    helper.assertValueEqual(port.requestAmount(), PER_TRIP, "its amount survived");
                    helper.assertValueEqual(ItemKey.of(port.requestedItem()), IRON, "and its filter");
                    helper.assertValueEqual(filterOf(helper, port).getValueSettings(),
                            new ValueSettings(PortRedstone.PULSE.ordinal(), PER_TRIP),
                            "an \"exactly\" setting from an old save loads as \"up to\"");

                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.setBlock(trigger, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "an old port still hands out on a pulse"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    BlockState state = helper.getBlockState(aisle.rackPos(PORT_RACK));
                    helper.assertValueEqual(state.getValue(WarehouseStationBlock.FACING), towardsAisle,
                            "the opening was never rewritten");
                    helper.assertValueEqual(state.getValue(WarehouseOutputBlock.ACCEPTING), false, "nor the direction");
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                            "and a held signal repeats nothing, exactly as before");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * Settings survive a save and a reload, and a configured port writes exactly the keys it needs — into its save, into
     * a schematic ({@code writeSafe}) and nowhere else.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portPersistence(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.output(PORT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 0, 1))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    helper.assertTrue(filterOf(helper, port).setFilter(IRON.toStack()), "filter set");
                    helper.assertTrue(port.setPortRank(OVERFLOW_RANK), "rank set");
                    helper.assertTrue(port.setRedstoneMode(PortRedstone.WHILE_POWERED), "mode set");

                    CompoundTag tag = port.saveWithoutMetadata(registries);
                    helper.assertValueEqual(tag.getInt(PortRankBehaviour.RANK_TAG), OVERFLOW_RANK, "saved rank");
                    helper.assertValueEqual(tag.getString(RequestFilterBehaviour.REDSTONE_MODE_TAG),
                            PortRedstone.WHILE_POWERED.name(), "saved mode");
                    helper.assertFalse(tag.contains(WarehouseOutputBlockEntity.ARMED_TAG),
                            "no token key while none is held");

                    CompoundTag schematic = new CompoundTag();
                    port.writeSafe(schematic, registries);
                    helper.assertValueEqual(schematic.getInt(PortRankBehaviour.RANK_TAG), OVERFLOW_RANK,
                            "a schematic carries the rank");
                    helper.assertValueEqual(schematic.getString(RequestFilterBehaviour.REDSTONE_MODE_TAG),
                            PortRedstone.WHILE_POWERED.name(), "and the mode");

                    port.loadWithComponents(tag, registries);
                    helper.assertValueEqual(port.portSettings(),
                            new PortSettings(OVERFLOW_RANK, PortRedstone.WHILE_POWERED), "both settings came back");
                    helper.assertValueEqual(ItemKey.of(port.requestedItem()), IRON, "and the filter with them");

                    // An armed accepting pulse port keeps its token across a reload, and only then is the key written.
                    helper.assertTrue(port.setRedstoneMode(PortRedstone.PULSE), "pulse mode");
                    port.onRedstoneChanged(true);
                    helper.assertTrue(port.isArmed(), "armed");
                    CompoundTag armedTag = port.saveWithoutMetadata(registries);
                    helper.assertTrue(armedTag.getBoolean(WarehouseOutputBlockEntity.ARMED_TAG), "the token is saved");
                    port.loadWithComponents(armedTag, registries);
                    helper.assertTrue(port.isArmed(), "and comes back");

                    // A clamped rank: a wider range later must never rewrite a player's number into something else.
                    CompoundTag wide = port.saveWithoutMetadata(registries);
                    wide.putInt(PortRankBehaviour.RANK_TAG, 4711);
                    port.loadWithComponents(wide, registries);
                    helper.assertValueEqual(port.portRank(), PortSettings.MAX_RANK, "clamped on read");
                })
                .thenSucceed();
    }

    /**
     * A controller restored from its own save has read no port policy at all — they live in the ports' block entities and
     * are not saved with the aisle — and a restored port is no member that "joined", so nothing would ever ask it. It has
     * to be found again, or a continuous port would stop for ever after a restart (the M8 cold-cache lesson).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portColdCacheAfterReload(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.output(PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the cache is cold"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    setRequest(helper, aisle, IRON.toStack(), PER_TRIP);
                    helper.assertTrue(aisle.outputAt(PORT_RACK).setRedstoneMode(PortRedstone.UNLESS_POWERED), "mode set");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) IRON_IN_STOCK,
                        "the unwired port empties the chest"))
                .thenExecute(() -> {
                    // A world reload: the controller is replaced by a copy loaded from its save, so the next pass runs
                    // with a port cache that has never read anything.
                    ServerLevel level = helper.getLevel();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
                    level.setBlockEntity(loadCopy(helper, controller, saved, WarehouseControllerBlockEntity.class));
                    helper.assertTrue(controller.isRemoved(), "the controller block entity was replaced");
                    aisle.insertAll(aisle.handlerAt(aisle.inventoryPos(STORAGE_RACK)), IRON.toStack(IRON_ADDED));
                    ItemCensus.change(conserved, IRON, IRON_ADDED);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON),
                        (long) (IRON_IN_STOCK + IRON_ADDED),
                        "the restored controller found the continuous port again, with no redstone event at all"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- clipboard and the two value boxes ----------------------------------------------------------------------------

    /**
     * The clipboard, in all the directions that matter. Create's generic {@code Value}/{@code Row} pair means "amount"
     * and "up to / exactly" on every funnel and the redstone behaviour here, so it must carry neither across:
     * <ul>
     * <li>funnel → port sets the filter and the amount and leaves the behaviour alone;</li>
     * <li>port → funnel offers an amount and an "up to" row, never "exactly";</li>
     * <li>port → port copies the whole policy, and a plain port's clipboard resets a configured one — which is what makes
     * a rack wall of ports one clipboard's work.</li>
     * </ul>
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portClipboard(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.output(PORT_RACK);
        aisle.output(SECOND_PORT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 0, 2))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    Player player = helper.makeMockPlayer(GameType.CREATIVE);
                    WarehouseOutputBlockEntity from = aisle.outputAt(PORT_RACK);
                    WarehouseOutputBlockEntity to = aisle.outputAt(SECOND_PORT_RACK);
                    helper.assertTrue(filterOf(helper, from).setFilter(IRON.toStack()), "filter set");
                    helper.assertTrue(from.setRedstoneMode(PortRedstone.UNLESS_POWERED), "mode set");
                    helper.assertTrue(from.setPortRank(DIVERSION_RANK), "rank set");

                    // Port → clipboard: the amount stays where a funnel expects it, the row is forced to "up to", and
                    // the behaviour travels under its own key. The rank has a clipboard of its own.
                    CompoundTag copied = new CompoundTag();
                    helper.assertTrue(filterOf(helper, from).writeToClipboard(registries, copied, Direction.SOUTH),
                            "the port offers a filter clipboard");
                    helper.assertValueEqual(copied.getInt(CLIPBOARD_ROW_TAG), RequestFilterBehaviour.UP_TO_ROW,
                            "a port never makes a funnel \"exactly\"");
                    helper.assertValueEqual(copied.getString(RequestFilterBehaviour.CLIPBOARD_REDSTONE_TAG),
                            PortRedstone.UNLESS_POWERED.name(), "the behaviour has its own key");
                    CompoundTag copiedRank = new CompoundTag();
                    helper.assertTrue(rankOf(helper, from).writeToClipboard(registries, copiedRank, Direction.SOUTH),
                            "and the rank its own clipboard");
                    helper.assertValueEqual(copiedRank.getInt(PortRankBehaviour.RANK_TAG), DIVERSION_RANK, "copied rank");

                    // Port → port: the whole policy.
                    helper.assertTrue(filterOf(helper, to).readFromClipboard(registries, copied, player, Direction.SOUTH,
                            false), "the other port takes the filter paste");
                    helper.assertTrue(rankOf(helper, to).readFromClipboard(registries, copiedRank, player,
                            Direction.SOUTH, false), "and the rank paste");
                    helper.assertValueEqual(to.portSettings(), from.portSettings(), "the whole policy travelled");
                    helper.assertValueEqual(ItemKey.of(to.requestedItem()), IRON, "and the filter with it");

                    // Funnel → port: a clipboard with Create's generic pair sets the filter and the amount, and the
                    // behaviour is untouched — which only means anything while it is not the default to begin with.
                    CompoundTag funnelLike = new CompoundTag();
                    funnelLike.put(FILTER_TAG, DIAMOND.toStack().saveOptional(registries));
                    funnelLike.putInt(CLIPBOARD_VALUE_TAG, FUNNEL_AMOUNT);
                    funnelLike.putInt(CLIPBOARD_ROW_TAG, FUNNEL_EXACTLY_ROW);
                    helper.assertTrue(to.setPortRank(PortSettings.REQUEST_RANK), "a requesting port, so the amount counts");
                    helper.assertTrue(filterOf(helper, to).readFromClipboard(registries, funnelLike, player,
                            Direction.SOUTH, false), "a funnel clipboard still sets the filter");
                    helper.assertValueEqual(ItemKey.of(to.requestedItem()), DIAMOND, "the filter was pasted");
                    helper.assertValueEqual(to.requestAmount(), FUNNEL_AMOUNT, "and the amount");
                    helper.assertValueEqual(to.redstoneMode(), PortRedstone.UNLESS_POWERED,
                            "a funnel's \"exactly\" never becomes a redstone behaviour");

                    // A plain port's clipboard resets a configured one: an omitted key would make "on a pulse"
                    // unsayable, and Create clears the filter on such a paste whatever we do.
                    CompoundTag plain = new CompoundTag();
                    WarehouseOutputBlockEntity neutral = from;
                    helper.assertTrue(neutral.setPortRank(PortSettings.REQUEST_RANK), "neutral rank");
                    helper.assertTrue(neutral.setRedstoneMode(PortRedstone.PULSE), "neutral mode");
                    helper.assertTrue(filterOf(helper, neutral).setFilter(ItemStack.EMPTY), "and no filter");
                    helper.assertTrue(filterOf(helper, neutral).writeToClipboard(registries, plain, Direction.SOUTH),
                            "a plain port offers a clipboard too");
                    helper.assertValueEqual(plain.getString(RequestFilterBehaviour.CLIPBOARD_REDSTONE_TAG),
                            PortRedstone.PULSE.name(), "a clipboard can say \"on a pulse\"");
                    CompoundTag plainRank = new CompoundTag();
                    helper.assertTrue(rankOf(helper, neutral).writeToClipboard(registries, plainRank, Direction.SOUTH),
                            "and one for the rank");
                    helper.assertValueEqual(plainRank.getInt(PortRankBehaviour.RANK_TAG), PortSettings.REQUEST_RANK,
                            "which can say \"it requests\"");
                    helper.assertTrue(to.setPortRank(DIVERSION_RANK), "configure the target again");
                    helper.assertTrue(filterOf(helper, to).readFromClipboard(registries, plain, player, Direction.SOUTH,
                            false), "the configured port takes the plain paste");
                    helper.assertTrue(rankOf(helper, to).readFromClipboard(registries, plainRank, player,
                            Direction.SOUTH, false), "in both halves");
                    helper.assertValueEqual(to.portSettings(), PortSettings.DEFAULT,
                            "the policy is reset, not silently kept");
                    helper.assertFalse(to.hasRequestFilter(), "and the filter with it, as Create does anyway");
                })
                .thenSucceed();
    }

    /**
     * <b>Exactly one value box is eligible per hand state</b>, which is what lets the port's settings box share the
     * filter slot's faces: the port box is wrench-only, and the filter slot refuses a player holding a wrench. The two
     * are also told apart on the wire by Create's {@code netId}, or a board sent from the client would land on the wrong
     * one.
     * <p>
     * A {@code FakePlayer} can never flip a port: Create's input handler skips the 4 px hit test entirely for one, so a
     * deployer holding a wrench aimed anywhere at the block would otherwise turn a warehouse into one that hands its
     * stock out — and exporting is irreversible.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portValueBoxesPerHandState(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.output(PORT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 0, 1))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    RequestFilterBehaviour filter = filterOf(helper, port);
                    PortRankBehaviour rank = rankOf(helper, port);
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

                    helper.assertTrue(rank.onlyVisibleWithWrench(), "the port box needs a wrench");
                    helper.assertFalse(filter.onlyVisibleWithWrench(), "the filter slot does not");
                    helper.assertTrue(filter.mayInteract(player), "an empty hand sets the filter");
                    player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
                    helper.assertFalse(filter.mayInteract(player), "a wrench belongs to the port box");
                    helper.assertTrue(rank.mayInteract(player), "which does take it");
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            AllBlocks.MECHANICAL_ARM.asStack());
                    helper.assertFalse(filter.mayInteract(player), "the arm item still passes through the slot (M12)");

                    FakePlayer deployer = FakePlayerFactory.getMinecraft(helper.getLevel());
                    deployer.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
                    helper.assertFalse(rank.mayInteract(deployer), "no automation may flip a port");

                    // The board must stay reachable whatever sits in the slot, or a non-stackable filter item would make
                    // the redstone behaviour unsettable.
                    helper.assertTrue(filter.setFilter(new ItemStack(Items.WATER_BUCKET)), "a non-stackable filter");
                    helper.assertTrue(filter.acceptsValueSettings(), "the board is still reachable");
                    helper.assertTrue(rank.acceptsValueSettings(), "and so is the port's");
                    helper.assertFalse(filter.netId() == rank.netId(),
                            "the two boards are told apart on the wire, or a setting would land on the wrong one");
                })
                .thenExecute(() -> {
                    // A wrench outside the box still turns the station: nothing was taken away from the wrench.
                    BlockPos pos = aisle.rackPos(PORT_RACK);
                    Direction before = helper.getBlockState(pos).getValue(WarehouseStationBlock.FACING);
                    aisle.wrenchTopFace(pos);
                    helper.assertValueEqual(helper.getBlockState(pos).getValue(WarehouseStationBlock.FACING),
                            before.getClockWise(), "the wrench still rotates the port");
                })
                .thenSucceed();
    }


    // --- the accepting direction in the planner (M17 part 2) ---------------------------------------------------------

    /**
     * The user's first row: <b>general overflow, works unwired, a lever turns it off</b>. An accepting port with a
     * negative rank, no filter and no redstone at all takes what the warehouse cannot store; a lever stops it dead, and
     * the items then back up in the input exactly as they do when a warehouse is full.
     * <p>
     * It also pins the two sentences the whole feature rests on: what sits in a port is <b>not stock</b> (the aisle's
     * counts never change), and nothing is destroyed — every ingot is in the input, in the crane's head or in the port
     * on every single tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portOverflowUnwired(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        BlockPos lever = trigger(aisle, PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an overflow port hands items over"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 1, 1))
                .thenExecute(() -> {
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "the warehouse hands over what it cannot store, with no wiring at all"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), 0L, "the input is empty");
                    helper.assertValueEqual(aisle.controller().countOf(IRON), 0L,
                            "and what sits in a port is not stock: it is never counted and never re-stored");
                    helper.assertValueEqual(aisle.outputAt(PORT_RACK).exportedItems(), (long) PER_TRIP,
                            "the port reports what it handed over");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    // The lever: from here on the port acts no more, and the items stay where they are.
                    helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.POWERED, true);
                    feed(aisle, conserved, IRON.toStack(IRON_ADDED));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) IRON_ADDED,
                            "a switched-off port takes nothing, so the input backs up");
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP, "nothing left");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> helper.setBlock(lever, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON),
                        (long) (PER_TRIP + IRON_ADDED), "and the falling edge starts it again"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * The interaction the milestone was built around: <b>a stock rule's maximum is what makes an overflow necessary</b>.
     * The rule caps iron at {@value #IRON_MAXIMUM}; exactly that much is stored, and everything above it leaves through
     * the port instead of backing up in the input, which is what an overflow is for.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portOverflowTakesItemsAtTheMaximum(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a maximum feeds an overflow"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, (long) IRON_MAXIMUM);
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(IRON_FED));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON),
                        (long) (IRON_FED - IRON_MAXIMUM), "everything above the maximum leaves through the port"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_MAXIMUM,
                            "the warehouse kept exactly its maximum");
                    helper.assertValueEqual(aisle.controller().storeHeadroom(IRON), 0L, "and may store no more");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), 0L,
                            "nothing had to back up in the input");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The other reason a port receives something: <b>no storage location accepts the items at all</b>. The only chest of
     * this aisle is dedicated to diamonds, so the diamonds are stored and the iron leaves through the overflow — the
     * items a partitioned warehouse used to jam an input with (ADR-021).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portOverflowTakesWhatNoFilterAccepts(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a dedication feeds an overflow"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    aisle.setStoreFilter(STORAGE_RACK, DIAMOND.toStack());
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, DIAMOND.toStack(PER_TRIP), IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) PER_TRIP,
                            "the diamonds go where they belong");
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                            "and the iron no chest accepts leaves instead of jamming the input");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, DIAMOND), 0L,
                            "a storage location always wins over an overflow");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), 0L, "the input is empty");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The user's second row: <b>only surplus cobblestone leaves, everything else backs up as before</b>. The port's filter
     * is a hard rule, so an item it does not name is not exported however stuck it is — and the controller says why in
     * the words it always used.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portOverflowFilteredCobblestone(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a filtered overflow sorts"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    aisle.setStoreFilter(STORAGE_RACK, DIAMOND.toStack());
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    helper.assertTrue(filterOf(helper, aisle.outputAt(PORT_RACK)).setFilter(COBBLESTONE.toStack()),
                            "the port takes cobblestone only");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, COBBLESTONE.toStack(PER_TRIP), IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, COBBLESTONE),
                        (long) PER_TRIP, "the surplus cobblestone leaves"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), 0L,
                            "and nothing else, however stuck it is");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) PER_TRIP,
                            "the iron backs up in the input, exactly as before M17");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(),
                            Optional.of(NoJobReason.NO_MATCHING_FILTER),
                            "reported as a filter mismatch, not as a full port");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The user's third row: <b>everything incoming is diverted out while the signal is high</b>. A positive rank outranks
     * every storage location, including a near empty chest, and the moment the signal goes the warehouse stores again —
     * the switch is the whole interface.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portDiversionWhilePowered(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        BlockPos lever = trigger(aisle, PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a diversion is switched on and off"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    accept(helper, aisle, PORT_RACK, DIVERSION_RANK, PortRedstone.WHILE_POWERED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) PER_TRIP,
                        "an unpowered diversion diverts nothing: the warehouse stores as always"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), 0L, "the port got nothing");
                    helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
                    feed(aisle, conserved, IRON.toStack(IRON_ADDED));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) IRON_ADDED,
                        "while powered everything incoming goes straight out"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) PER_TRIP,
                            "and nothing was stored while the signal was high");
                    helper.setBlock(lever, Blocks.AIR);
                    feed(aisle, conserved, IRON.toStack(IRON_ADDED));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON),
                        (long) (PER_TRIP + IRON_ADDED), "the moment it is unpowered the warehouse stores again"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) IRON_ADDED,
                            "and the port takes no more");
                    helper.assertValueEqual(aisle.outputAt(PORT_RACK).exportedItems(), (long) IRON_ADDED,
                            "which is exactly what it reports having handed over");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>Storage always wins over a negative rank</b>, and a diversion always wins over storage: the strongest overflow
     * there is, standing right next to the input, loses to an empty chest at the far end, and the weakest diversion beats
     * a chest dedicated to the very item. The two sentences of the user's brief, in one world.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portRankAgainstStorage(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FAR_STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(SECOND_PORT_RACK); // right next to the input, where the chest is three blocks away
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a rank is weighed against storage"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    aisle.setStoreFilter(FAR_STORAGE_RACK, IRON.toStack());
                    accept(helper, aisle, SECOND_PORT_RACK, PortSettings.MIN_RANK, PortRedstone.UNLESS_POWERED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(FAR_STORAGE_RACK, IRON), (long) PER_TRIP,
                        "the far dedicated chest beats the strongest overflow at the door"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(SECOND_PORT_RACK, IRON), 0L,
                            "the overflow got nothing at all");
                    helper.assertValueEqual(aisle.outputAt(SECOND_PORT_RACK).exportedItems(), 0L, "and reports so");
                    // The same scene with the sign flipped: now the port takes the items before they are stored.
                    helper.assertTrue(aisle.outputAt(SECOND_PORT_RACK).setPortRank(1),
                            "the weakest diversion there is");
                    feed(aisle, conserved, IRON.toStack(IRON_ADDED));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SECOND_PORT_RACK, IRON),
                        (long) IRON_ADDED, "a diversion beats even a location dedicated to the item"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.storedAt(FAR_STORAGE_RACK, IRON), (long) PER_TRIP,
                            "nothing more was stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>A full accepting port backs the input up</b>, exactly as an input does today: the items stay where they are,
     * nothing is destroyed and nothing is dropped. It is reported as {@link NoJobReason#PORT_FULL} rather than as a
     * maximum, because a backed-up overflow is the thing to go and fix while a maximum is not a fault at all.
     * <p>
     * Then the maximum is raised, and the very items that had nowhere to go are stored — proof that a full port jams
     * nothing but itself.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portFullBacksUpTheInput(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a full port backs an input up"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 0L);
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    fillPort(helper, aisle, PORT_RACK, conserved);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) PER_TRIP,
                            "the items stay in the input: nothing is destroyed and nothing is dropped");
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), 0L, "the port took nothing");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), 0L, "and the maximum still holds");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(), Optional.of(NoJobReason.PORT_FULL),
                            "a full port is the more specific answer than a maximum");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> aisle.stockKeeperAt(KEEPER_RACK)
                        .editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, (long) IRON_MAXIMUM))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) PER_TRIP,
                        "a full port jams nothing but itself: with room again the items are stored"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>The crane must never wait at a full port.</b> The port fills up while the crane is already carrying items to it,
     * which for a retrieve means "wait until a funnel drains it" — and would mean, for a store into an overflow, parking
     * in front of it and blocking the whole aisle. A store job therefore drops, delivers nothing and has its leftovers
     * rerouted, so the iron comes back and the crane is free.
     * <p>
     * This is the sharpest edge of the milestone: without the fix the crane holds the items for ever and this test times
     * out.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portFullWhileCarryingReroutes(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a port fills up mid trip"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    // No chest may take the iron, so the port is the only target the planner can pick.
                    aisle.setStoreFilter(STORAGE_RACK, DIAMOND.toStack());
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(IRON) == PER_TRIP,
                        "the crane is carrying the iron to the port"))
                .thenExecute(() -> fillPort(helper, aisle, PORT_RACK, conserved))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) PER_TRIP,
                            "the leftovers went back into the input, and the crane is free again");
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), 0L, "the full port took none of it");
                    helper.assertValueEqual(aisle.outputAt(PORT_RACK).exportedItems(), 0L, "so it exported nothing");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) PER_TRIP,
                            "and it stays there while the port is full");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>Two ports compete</b>, by exactly the keys the ranking names: a port dedicated to the item beats an unfiltered
     * one however much further away it stands (ADR-021's dedication argument, applied to ports), and within one filter
     * class the stronger rank fills first. Travel time only decides what those leave equal.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portTwoPortsCompete(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK); // far from the input
        aisle.output(SECOND_PORT_RACK); // right next to it
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while two ports compete"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 1, 2))
                .thenExecute(() -> {
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    accept(helper, aisle, SECOND_PORT_RACK, OVERFLOW_RANK, PortRedstone.UNLESS_POWERED);
                    helper.assertTrue(filterOf(helper, aisle.outputAt(PORT_RACK)).setFilter(IRON.toStack()),
                            "the far port is dedicated to iron");
                    helper.assertValueEqual(aisle.controller().acceptingPortCount(), 2,
                            "the controller counts both of them, which is what its goggles show");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "the dedicated port wins although it stands further away"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(SECOND_PORT_RACK, IRON), 0L,
                            "the unfiltered one got nothing");
                    // Same filter class from here on, and the near port is given the stronger rank.
                    helper.assertTrue(filterOf(helper, aisle.outputAt(PORT_RACK)).setFilter(ItemStack.EMPTY),
                            "the far port takes anything now");
                    helper.assertTrue(aisle.outputAt(SECOND_PORT_RACK).setPortRank(PortSettings.MIN_RANK),
                            "and the near one is the stronger overflow");
                    feed(aisle, conserved, IRON.toStack(IRON_ADDED));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(SECOND_PORT_RACK, IRON),
                        (long) IRON_ADDED, "within one filter class the stronger rank fills first"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP, "and only it");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>A port that belongs to no aisle takes nothing.</b> An accepting warehouse output standing outside the aisle
     * geometry is no member, so no controller has ever read its policy — and "not read" must never mean "assume it
     * accepts", because exporting items is irreversible. The aisle's items back up instead, with the reason they always
     * had.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portOutsideAnAisleTakesNothing(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        // A warehouse output three blocks away from the aisle, facing nothing: a valid block, no member of anything.
        helper.setBlock(OUTSIDE_PORT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseStationBlock.FACING, Direction.NORTH));
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a stray port stands by"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.setStoreFilter(STORAGE_RACK, DIAMOND.toStack());
                    WarehouseOutputBlockEntity stray = strayPort(helper);
                    helper.assertTrue(stray.setPortRank(DIVERSION_RANK), "the stray port would take anything");
                    helper.assertTrue(stray.setRedstoneMode(PortRedstone.UNLESS_POWERED), "and needs no signal");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    WarehouseOutputBlockEntity stray = strayPort(helper);
                    helper.assertValueEqual(stray.bufferedItems().count(IRON), 0L,
                            "a port of no aisle receives nothing, however it is configured");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) PER_TRIP,
                            "the items back up in the input instead");
                    helper.assertValueEqual(aisle.controller().lastPlanReason(),
                            Optional.of(NoJobReason.NO_MATCHING_FILTER),
                            "and the reason is the one it always was: no port was ever a candidate");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * An accepting port in pulse mode exports <b>one trip per rising edge</b>: the token is a boolean, so a clock cannot
     * accumulate an export promise, and a pulse is spent when the job is planned. More than one carry is fed on purpose,
     * so "one trip" is visible as a number rather than as an absence.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portAcceptPulseIsOneTrip(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        BlockPos button = trigger(aisle, PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a pulse port exports one trip"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 1, 1))
                .thenExecute(() -> {
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.PULSE);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(CARRY_LIMIT), IRON.toStack(IRON_ADDED));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), 0L,
                            "no edge, no export: a pulse port waits to be told");
                    helper.setBlock(button, Blocks.REDSTONE_BLOCK);
                    // Three more edges while the token is unused must not become three more trips.
                    helper.setBlock(button, Blocks.AIR);
                    helper.setBlock(button, Blocks.REDSTONE_BLOCK);
                    helper.setBlock(button, Blocks.AIR);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) CARRY_LIMIT,
                        "one trip left"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) CARRY_LIMIT,
                            "and exactly one, however many edges there were");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) IRON_ADDED,
                            "the rest waits in the input for the next pulse");
                    helper.assertFalse(aisle.outputAt(PORT_RACK).isArmed(), "the token was spent");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> helper.setBlock(button, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON),
                        (long) (CARRY_LIMIT + IRON_ADDED), "the next edge takes the rest"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * The <b>other</b> way a store job reaches an accepting pulse port: not as its plan, but as the last resort of a
     * reroute. The plan sends the iron to a storage location (a negative rank always loses to storage), that location
     * disappears mid-carry, and neither storage nor an input station can take the leftovers — so the armed port receives
     * a whole trip. Its token must be spent all the same, or one rising edge would fund this trip <b>and</b> a plan of
     * its own.
     * <p>
     * The bug this pins was invisible from the plan side: the token was spent from the <b>job's</b> target kind, which on
     * a reroute still names the target that failed ({@code STORAGE} here), so the port stayed armed.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portRerouteIntoAPulsePortSpendsTheToken(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        BlockPos button = trigger(aisle, PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a reroute lands in a pulse port"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    accept(helper, aisle, PORT_RACK, OVERFLOW_RANK, PortRedstone.PULSE);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                    // One edge, so the port is armed while the plan is made — and storage still wins, because an
                    // overflow ranks behind every storage location.
                    helper.setBlock(button, Blocks.REDSTONE_BLOCK);
                    helper.setBlock(button, Blocks.AIR);
                    helper.assertTrue(aisle.outputAt(PORT_RACK).isArmed(), "the port holds the edge");
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.dock().heldItems().count(IRON) == PER_TRIP, "the crane picked the iron up");
                    helper.assertValueEqual(aisle.dock().currentJob().map(job -> job.target()),
                            Optional.of(STORAGE_RACK), "and is carrying it to the storage location, not to the port");
                })
                // The storage location goes missing mid-carry, and so does the input the leftovers would go back into:
                // the armed port is the only target the reroute has left.
                .thenExecute(() -> {
                    aisle.breakBlock(aisle.rackPos(STORAGE_RACK));
                    aisle.breakBlock(aisle.rackPos(INPUT_RACK));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "the leftovers were rerouted into the port"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.outputAt(PORT_RACK).exportedItems(), (long) PER_TRIP,
                            "and counted as an export");
                    helper.assertFalse(aisle.outputAt(PORT_RACK).isArmed(),
                            "the edge that funded this trip is spent, whichever path chose the port");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                // With the token gone the port's gate is shut, so a new helping of iron has nowhere to go and stays put:
                // one edge, one trip, never a trip and a plan.
                .thenExecute(() -> {
                    aisle.input(INPUT_RACK);
                    feed(aisle, conserved, IRON.toStack(IRON_ADDED));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                            "the port exported nothing more");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) IRON_ADDED,
                            "the new iron waits in the input for the next edge");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A port's settings can change <b>without</b> a player touching a value box: {@code /data merge block}, a schematic
     * print and Create's zapper all overwrite an existing block entity in place, which runs neither {@code onLoad} nor any
     * callback. The controller caches port policies and never re-reads them periodically, so such a change has to
     * announce itself — otherwise the aisle plans against a policy the block no longer has, and because a requesting port
     * writes no rank key at all, the cached one can be the <b>permissive</b> one.
     * <p>
     * Both directions are proved by what the warehouse does, not by reading the cache: a port turned into a diversion by
     * hand-written data really receives items, and the same port turned back into a plain output really stops.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void portDataReplacedInPlaceIsReRead(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a port's data is rewritten"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(0, 1, 1))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    helper.assertValueEqual(aisle.controller().acceptingPortCount(), 0, "a plain output to begin with");

                    // Exactly what a command or a schematic hands the block entity: its own data with a rank and a mode.
                    CompoundTag diverting = port.saveWithoutMetadata(registries);
                    diverting.putInt(PortRankBehaviour.RANK_TAG, DIVERSION_RANK);
                    diverting.putString(RequestFilterBehaviour.REDSTONE_MODE_TAG, PortRedstone.UNLESS_POWERED.name());
                    port.loadWithComponents(diverting, registries);

                    helper.assertValueEqual(port.portSettings(),
                            new PortSettings(DIVERSION_RANK, PortRedstone.UNLESS_POWERED), "the block holds the policy");
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.ACCEPTING, true);
                    helper.assertValueEqual(aisle.controller().acceptingPortCount(), 1,
                            "and the controller read it without anybody clicking anything");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feed(aisle, conserved, IRON.toStack(PER_TRIP));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "the diversion really diverts"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                // And back: a plain output's data carries no rank key at all, which is the dangerous direction — a stale
                // cache would keep exporting through a block that requests again.
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    CompoundTag plain = port.saveWithoutMetadata(registries);
                    plain.remove(PortRankBehaviour.RANK_TAG);
                    plain.remove(RequestFilterBehaviour.REDSTONE_MODE_TAG);
                    port.loadWithComponents(plain, registries);

                    helper.assertValueEqual(port.portSettings(), PortSettings.DEFAULT, "a plain output again");
                    helper.assertBlockProperty(aisle.rackPos(PORT_RACK), WarehouseOutputBlock.ACCEPTING, false);
                    helper.assertValueEqual(aisle.controller().acceptingPortCount(), 0, "and the cache followed");
                    feed(aisle, conserved, IRON.toStack(IRON_ADDED));
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                            "nothing was exported through a port that requests");
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, IRON), (long) IRON_ADDED,
                            "the iron backs up in the input, as it does without any port");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    /** The redstone trigger block south of the port. */
    private static BlockPos trigger(AisleFixture aisle) {
        return trigger(aisle, PORT_RACK);
    }

    /** The redstone trigger block south of the port at {@code rack}; two racks apart never share one. */
    private static BlockPos trigger(AisleFixture aisle, RackPosition rack) {
        return aisle.rackPos(rack).relative(Direction.SOUTH);
    }

    /** Makes the port at {@code rack} an accepting one with a signed rank and a redstone behaviour (M17). */
    private static void accept(GameTestHelper helper, AisleFixture aisle, RackPosition rack, int rank,
            PortRedstone mode) {
        WarehouseOutputBlockEntity port = aisle.outputAt(rack);
        helper.assertTrue(port.setPortRank(rank), "the port at " + rack + " accepts now");
        port.setRedstoneMode(mode);
        helper.assertValueEqual(port.portSettings(), new PortSettings(rank, mode), "the port's policy");
    }

    /** Feeds items into the aisle's input station and keeps the item census in step. */
    private static void feed(AisleFixture aisle, Map<ItemKey, Long> conserved, ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), stack.copy());
            ItemCensus.change(conserved, ItemKey.of(stack), stack.getCount());
        }
    }

    /**
     * Fills the buffer of the port at {@code rack} completely — one non-stackable item per slot — so it accepts nothing at
     * all. That is what a port whose funnel stopped draining it looks like, and the only way to make one full without
     * carrying a buffer's worth of items through it.
     */
    private static void fillPort(GameTestHelper helper, AisleFixture aisle, RackPosition rack,
            Map<ItemKey, Long> conserved) {
        WarehouseOutputBlockEntity port = aisle.outputAt(rack);
        for (int slot = 0; slot < WareworksConfig.outputBufferSlots(); slot++) {
            helper.assertTrue(port.insert(FILLER_ITEM.toStack(), false).isEmpty(), "the port took a filler item");
            ItemCensus.change(conserved, FILLER_ITEM, 1);
        }
        helper.assertValueEqual(port.insert(IRON.toStack(), true).getCount(), 1,
                "and now the port accepts nothing at all");
    }

    /**
     * The pulse token as a <b>client</b> receives it: read out of the goggle summary of the block entity's own update tag,
     * which is the packet {@code SyncedBlockEntity} sends. The token is a server field, so this is the only channel that
     * can carry it to the tooltip.
     */
    private static boolean tokenInClientPacket(GameTestHelper helper, WarehouseOutputBlockEntity port) {
        CompoundTag packet = port.getUpdateTag(helper.getLevel().registryAccess());
        return StationGoggleSummary.read(packet.getCompound(WarehouseStationBlockEntity.SUMMARY_TAG)).portArmed();
    }

    /** The warehouse output standing outside the aisle ({@link #OUTSIDE_PORT}). */
    private static WarehouseOutputBlockEntity strayPort(GameTestHelper helper) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(helper.getLevel(),
                helper.absolutePos(OUTSIDE_PORT));
        if (port == null) {
            helper.fail("missing warehouse output block entity", OUTSIDE_PORT);
            throw new IllegalStateException("unreachable");
        }
        return port;
    }

    /** Sets the port's filter and requested amount, as a player click and a held click would. */
    private static void setRequest(GameTestHelper helper, AisleFixture aisle, ItemStack filter, int amount) {
        RequestFilterBehaviour behaviour = filterOf(helper, aisle.outputAt(PORT_RACK));
        helper.assertTrue(behaviour.setFilter(filter), "the port accepts the filter " + filter);
        behaviour.count = amount; // after setFilter, which may clamp the count
    }

    /** Open requests for the port. */
    private static int openRequests(AisleFixture aisle) {
        return aisle.controller().requestsFor(aisle.absoluteRackPos(PORT_RACK)).size();
    }

    private static RequestFilterBehaviour filterOf(GameTestHelper helper, WarehouseOutputBlockEntity port) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(port, FilteringBehaviour.TYPE);
        if (!(behaviour instanceof RequestFilterBehaviour filter)) {
            helper.fail("the port has no request filter behaviour", port.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        return filter;
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

    private static PortRankBehaviour rankOf(GameTestHelper helper, WarehouseOutputBlockEntity port) {
        ScrollValueBehaviour behaviour = BlockEntityBehaviour.get(port, ScrollValueBehaviour.TYPE);
        if (!(behaviour instanceof PortRankBehaviour rank)) {
            helper.fail("the port has no rank behaviour", port.getBlockPos());
            throw new IllegalStateException("unreachable");
        }
        return rank;
    }
}
