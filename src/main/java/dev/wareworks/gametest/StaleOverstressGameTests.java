package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.concurrent.atomic.AtomicLong;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.station.WarehouseCrafterFeederBlock;
import dev.wareworks.content.station.WarehouseCrafterFeederBlockEntity;
import dev.wareworks.core.kinetics.OverstressRecheck;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A stale overstress clears by itself (issue #38, ADR-071): a Wareworks kinetic consumer that is flagged overstressed
 * while its network has the capacity recomputes that network within {@link OverstressRecheck#FIRST_DELAY_TICKS} ticks
 * and runs again.
 * <p>
 * The stale flag is set the way Create sets it after a load, through the member's own
 * {@code KineticBlockEntity#updateFromNetwork} with capacity 0, while the network's stored totals stay as they are.
 * That is the harder of the two stale states: {@code KineticNetwork#updateNetwork} finds the same totals and syncs
 * nothing, so only the explicit sync of {@code StaleOverstressCheck} clears it. The other one (a network whose stored
 * capacity is 0 too), the real chunk unload, save and rejoin that produce it, and a really overstressed network that
 * stays overstressed need generators that unload, which no GameTest can do; the visual scenarios {@code restart-stale}
 * and {@code restart-late} cover them ({@code dev.RestartPowerVisualScenario}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StaleOverstressGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 4;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 400;
    /** The stale flag must be gone this many ticks after it was set, at the latest. */
    private static final int CLEARED_WITHIN_TICKS = OverstressRecheck.FIRST_DELAY_TICKS + 2;

    private StaleOverstressGameTests() {
    }

    /** The dock flagged overstressed by a stale capacity of 0 resumes on its own; the goggles say so in between. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void staleCraneOverstressClears(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        AtomicLong staleAt = new AtomicLong();
        helper.startSequence()
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> {
                    StackerCraneBlockEntity dock = aisle.dock();
                    helper.assertTrue(dock.getSpeed() != 0 && !dock.isOverStressed(), "the crane turns");
                })
                .thenExecute(() -> staleAt.set(makeStale(helper, aisle.dock())))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().pauseReason(), CranePauseReason.OVERSTRESSED,
                        "the stale flag pauses the crane"))
                .thenWaitUntil(() -> helper.assertFalse(aisle.dock().isOverStressed(), "the stale flag is cleared"))
                .thenExecute(() -> {
                    assertClearedInTime(helper, staleAt.get());
                    helper.assertTrue(aisle.dock().getSpeed() != 0, "the crane turns again");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().pauseReason(), CranePauseReason.NONE,
                        "the crane runs again"))
                .thenSucceed();
    }

    /** The same for the Warehouse Crafter Feeder, the other Wareworks kinetic consumer. */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void staleFeederOverstressClears(GameTestHelper helper) {
        BlockPos feederPos = new BlockPos(3, BASE_Y, 3);
        BlockPos motorPos = feederPos.above();
        helper.setBlock(feederPos, WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                .setValue(WarehouseCrafterFeederBlock.FACING, Direction.EAST)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        helper.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.DOWN));
        AtomicLong staleAt = new AtomicLong();
        helper.startSequence()
                .thenExecute(() -> {
                    if (helper.getBlockEntity(motorPos) instanceof CreativeMotorBlockEntity motor)
                        motor.generatedSpeed.setValue(TEST_RPM);
                    else
                        helper.fail("missing creative motor block entity", motorPos);
                })
                .thenWaitUntil(() -> {
                    WarehouseCrafterFeederBlockEntity feeder = feeder(helper, feederPos);
                    helper.assertTrue(feeder.getSpeed() != 0 && !feeder.isOverStressed(), "the feeder turns");
                })
                .thenExecute(() -> staleAt.set(makeStale(helper, feeder(helper, feederPos))))
                .thenWaitUntil(() -> helper.assertFalse(feeder(helper, feederPos).isOverStressed(),
                        "the stale flag is cleared"))
                .thenExecute(() -> {
                    assertClearedInTime(helper, staleAt.get());
                    helper.assertTrue(feeder(helper, feederPos).getSpeed() != 0, "the feeder turns again");
                })
                .thenSucceed();
    }

    /**
     * Flags {@code be} overstressed as a stale save does (capacity 0 against some stress), leaving the network's
     * stored totals untouched; returns the game time.
     */
    private static long makeStale(GameTestHelper helper, KineticBlockEntity be) {
        helper.assertTrue(be.hasNetwork(), "the member has a network");
        be.updateFromNetwork(0, 1, be.getOrCreateNetwork().getSize());
        helper.assertTrue(be.isOverStressed(), "the member is flagged overstressed");
        return helper.getLevel().getGameTime();
    }

    private static void assertClearedInTime(GameTestHelper helper, long staleAt) {
        long took = helper.getLevel().getGameTime() - staleAt;
        helper.assertTrue(took <= CLEARED_WITHIN_TICKS,
                "the stale flag was cleared after " + took + " ticks, expected at most " + CLEARED_WITHIN_TICKS);
    }

    private static WarehouseCrafterFeederBlockEntity feeder(GameTestHelper helper, BlockPos pos) {
        WarehouseCrafterFeederBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_CRAFTER_FEEDER
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing crafter feeder block entity", pos);
        return be;
    }
}
