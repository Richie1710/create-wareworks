package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Create: Electro Energetics' Electric Motor drives the stacker crane (issue #9, ADR-074), present only in a run with
 * CEE ({@code ./gradlew runGameTestServer -Pwareworks.cee}).
 * <p>
 * Wareworks has no code against CEE: its motor is an ordinary Create rotation source. These tests build the circuit the
 * way a player does, with CEE's own blocks and items found by id: a white Electric Motor facing up where
 * {@link AisleFixture} puts its creative motor (under the dock), a Creative Battery under the controller with its
 * voltage set through Create's {@link ScrollValueBehaviour} (the value box a player scrolls), and two wires laid with
 * the Creative Wire Spool by a player clicking the battery's and the motor's terminals ({@code Item#useOn}). The one
 * thing read from CEE is where those terminals are, through the public {@code getNodePositions} its blocks implement,
 * called by reflection so that this class names no CEE type and loads in every run. Without CEE the generator returns
 * no tests.
 * <p>
 * With CEE's default config the motor stands still below 80 V ({@code motorMinVoltage}) and offers V&sup2;/30 SU
 * ({@code motorResistance} 30 &Omega;, {@code rotorStressMultiplier} 1). At 230 V that is about 1760 SU, plenty for the
 * crane at {@value #MOTOR_RPM} RPM; at 70 V the motor gives no rotation at all and the crane must wait with every item
 * where it was.
 */
@GameTestHolder(Wareworks.ID)
public final class CeeMotorGameTests {
    /** CEE's mod id. */
    static final String CEE_MOD_ID = "electroenergetics";

    private static final String BATCH = "wareworkscee";
    private static final String AISLE_TEMPLATE = Wareworks.ID + ":" + AISLE_16X10X7;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final RackPosition INPUT = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition NEAR = new RackPosition(2, 0, Side.LEFT);
    /** Under the dock, where {@link AisleFixture#placeMotor()} puts the creative motor. */
    private static final BlockPos MOTOR = new BlockPos(1, FLOOR_Y, AISLE_Z);
    /** Under the controller, one block from the motor. */
    private static final BlockPos BATTERY = new BlockPos(0, FLOOR_Y, AISLE_Z);

    private static final ResourceLocation MOTOR_ID = ResourceLocation.fromNamespaceAndPath(CEE_MOD_ID, "white_electric_motor");
    private static final ResourceLocation BATTERY_ID = ResourceLocation.fromNamespaceAndPath(CEE_MOD_ID, "creative_battery");
    private static final ResourceLocation SPOOL_ID = ResourceLocation.fromNamespaceAndPath(CEE_MOD_ID, "creative_wire_spool");
    /** The Creative Battery's terminals: node 0 is its negative pole, node 1 its positive one. */
    private static final int NEGATIVE = 0;
    private static final int POSITIVE = 1;
    /** The Electric Motor's two terminals (it turns one way or the other with the polarity; the crane takes either). */
    private static final int MOTOR_A = 0;
    private static final int MOTOR_B = 1;

    /** The battery's value box counts millivolts. */
    private static final int MILLIVOLTS_PER_VOLT = 1000;
    private static final int RUNNING_VOLTS = 230;
    /** Below CEE's default {@code motorMinVoltage} of 80 V. */
    private static final int LOW_VOLTS = 70;
    private static final int MOTOR_RPM = 128;

    private static final int STORED_IRON = 32;
    private static final int REQUESTED_IRON = 10;
    /** Long enough for CEE's averaged voltage and its 12-tick speed update to have settled several times over. */
    private static final int LOW_VOLTAGE_IDLE_TICKS = 100;
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);

    private CeeMotorGameTests() {
    }

    /** The motor tests, or none without Create: Electro Energetics. */
    @GameTestGenerator
    public static Collection<TestFunction> ceeMotorTests() {
        if (!ModList.get().isLoaded(CEE_MOD_ID))
            return List.of();
        return List.of(test("ceemotordrivescrane", CeeMotorGameTests::motorDrivesCrane),
                test("ceemotorbelow80vstopscrane", CeeMotorGameTests::motorBelowMinimumStopsCrane));
    }

    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction(BATCH, name, AISLE_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    /**
     * At 230 V the Electric Motor turns the crane: 32 iron go from the input into storage, then 10 come back to an output
     * on a redstone request, with every item accounted for on every tick.
     */
    private static void motorDrivesCrane(GameTestHelper helper) {
        AisleFixture aisle = poweredAisle(helper, RUNNING_VOLTS);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON, "iron stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    helper.assertTrue(Math.abs(dockSpeed(aisle)) > 0, "the dock turns at 230 V");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), 0L, "input emptied");
                    aisle.requestAt(OUTPUT, IRON.toStack(), REQUESTED_IRON, outputTrigger(aisle));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(OUTPUT, IRON), (long) REQUESTED_IRON,
                            "iron delivered to the output");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> helper.assertValueEqual(aisle.storedAt(NEAR, IRON),
                        (long) (STORED_IRON - REQUESTED_IRON), "iron left in storage"))
                .thenSucceed();
    }

    /**
     * At 70 V the Electric Motor gives no rotation: the crane waits with no job, the input keeps its iron and nothing is
     * stored or lost. Raised to 230 V, the same motor starts and the crane stores the iron.
     */
    private static void motorBelowMinimumStopsCrane(GameTestHelper helper) {
        AisleFixture aisle = poweredAisle(helper, LOW_VOLTS);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenIdle(LOW_VOLTAGE_IDLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(dockSpeed(aisle), 0f, "no rotation at 70 V");
                    helper.assertValueEqual(aisle.dock().pauseReason(), CranePauseReason.NO_ROTATION, "pause reason");
                    helper.assertTrue(aisle.dock().currentJob().isEmpty(), "no job without rotation");
                    helper.assertValueEqual(aisle.stationCount(INPUT, IRON), (long) STORED_IRON, "input untouched");
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), 0L, "nothing stored");
                    setVolts(helper, RUNNING_VOLTS);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(NEAR, IRON), (long) STORED_IRON, "stored once the voltage is back");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- the circuit -------------------------------------------------------------------------------------------------

    /**
     * An aisle without the creative motor, an input holding 32 iron, a storage location and an output; the Electric
     * Motor under the dock, the Creative Battery at {@code volts} and two wires between them. The census check runs on
     * every tick from here on.
     */
    private static AisleFixture poweredAisle(GameTestHelper helper, int volts) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(NEAR);
        aisle.input(INPUT);
        aisle.output(OUTPUT);
        aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, STORED_IRON);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the Electric Motor drives the crane"));

        helper.setBlock(MOTOR, ceeBlock(helper, MOTOR_ID).defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        helper.setBlock(BATTERY, ceeBlock(helper, BATTERY_ID).defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        setVolts(helper, volts);
        scrollValue(helper, MOTOR).setValue(MOTOR_RPM);

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ceeItem(helper, SPOOL_ID)));
        wire(helper, player, BATTERY, POSITIVE, MOTOR, MOTOR_A);
        wire(helper, player, BATTERY, NEGATIVE, MOTOR, MOTOR_B);
        return aisle;
    }

    private static void setVolts(GameTestHelper helper, int volts) {
        scrollValue(helper, BATTERY).setValue(volts * MILLIVOLTS_PER_VOLT);
    }

    /** One wire from terminal {@code fromNode} of the block at {@code from} to terminal {@code toNode} at {@code to}. */
    private static void wire(GameTestHelper helper, Player player, BlockPos from, int fromNode, BlockPos to, int toNode) {
        clickNode(helper, player, from, fromNode, "select the first terminal");
        clickNode(helper, player, to, toNode, "connect the second terminal");
    }

    /** The player uses the held spool on the block at {@code pos}, exactly at its terminal {@code node}. */
    private static void clickNode(GameTestHelper helper, Player player, BlockPos pos, int node, String step) {
        BlockPos absolute = helper.absolutePos(pos);
        Vec3 hit = Vec3.atLowerCornerOf(absolute).add(nodePosition(helper, pos, node));
        ItemStack spool = player.getItemInHand(InteractionHand.MAIN_HAND);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, absolute, false));
        InteractionResult result = spool.getItem().useOn(context);
        if (!result.consumesAction())
            helper.fail("wire spool: " + step + " answered " + result, pos);
    }

    /**
     * Where terminal {@code node} of the CEE block at {@code pos} sits inside its block, from the block's own public
     * {@code getNodePositions(Level, BlockPos, BlockState)} (CEE's {@code ElectricalDeviceBlock}), called by reflection.
     */
    private static Vec3 nodePosition(GameTestHelper helper, BlockPos pos, int node) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockState state = helper.getLevel().getBlockState(absolute);
        Object nodes;
        try {
            Method method = state.getBlock().getClass().getMethod("getNodePositions", Level.class, BlockPos.class,
                    BlockState.class);
            nodes = method.invoke(state.getBlock(), helper.getLevel(), absolute, state);
        } catch (ReflectiveOperationException e) {
            helper.fail("no getNodePositions on " + state.getBlock() + ": " + e, pos);
            return Vec3.ZERO;
        }
        if (!(nodes instanceof Map<?, ?> map) || !(map.get(node) instanceof Vec3 local)) {
            helper.fail("no terminal " + node + " on " + state.getBlock() + ": " + nodes, pos);
            return Vec3.ZERO;
        }
        return local;
    }

    private static ScrollValueBehaviour scrollValue(GameTestHelper helper, BlockPos pos) {
        BlockEntity be = helper.getBlockEntity(pos);
        ScrollValueBehaviour behaviour = be instanceof SmartBlockEntity smart
                ? BlockEntityBehaviour.get(smart, ScrollValueBehaviour.TYPE)
                : null;
        if (behaviour == null)
            helper.fail("no value box on " + be, pos);
        return behaviour;
    }

    private static float dockSpeed(AisleFixture aisle) {
        return aisle.dock().getSpeed();
    }

    /** Behind the output, away from the aisle (where a player puts the lever). */
    private static BlockPos outputTrigger(AisleFixture aisle) {
        return aisle.rackPos(OUTPUT).relative(aisle.sideDirection(OUTPUT));
    }

    private static Block ceeBlock(GameTestHelper helper, ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR)
            helper.fail("CEE block " + id + " is not registered");
        return block;
    }

    private static Item ceeItem(GameTestHelper helper, ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR)
            helper.fail("CEE item " + id + " is not registered");
        return item;
    }
}
