package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TravelTimeModelTest {
    /** The config defaults of {@code docs/warehouse-system.md} §9. */
    private static final CraneKinematics.Params DEFAULTS = new CraneKinematics.Params(1.0 / 384.0, 1.0 / 512.0,
            1.0 / 192.0, 1.0);
    private static final double DELTA = 1e-12;

    @Test
    void speedsFollowRpmAndFactors() {
        CraneSpeeds speeds = CraneKinematics.speeds(DEFAULTS, 64);
        assertEquals(64.0 / 384.0, speeds.vx(), DELTA);
        assertEquals(64.0 / 512.0, speeds.vy(), DELTA);
        assertEquals(64.0 / 192.0, speeds.va(), DELTA);
        assertEquals(speeds, CraneKinematics.speeds(DEFAULTS, -64), "direction does not matter");
        assertFalse(speeds.isStopped());
    }

    @Test
    void speedsAreCapped() {
        CraneSpeeds fast = CraneKinematics.speeds(DEFAULTS, 1024);
        assertEquals(1.0, fast.vx(), DELTA, "1024/384 exceeds maxBlocksPerTick");
        assertEquals(1.0, fast.vy(), DELTA);
        assertEquals(CraneKinematics.MAX_ARM_SPEED, fast.va(), DELTA);
        CraneSpeeds capped = CraneKinematics.speeds(new CraneKinematics.Params(0.5, 0.5, 0.5, 0.25), 1);
        assertEquals(0.25, capped.vx(), DELTA);
        assertEquals(0.5, capped.va(), DELTA, "the arm has its own cap");
        CraneSpeeds infinite = CraneKinematics.speeds(DEFAULTS, Double.POSITIVE_INFINITY);
        assertEquals(new CraneSpeeds(1.0, 1.0, 1.0), infinite);
    }

    @Test
    void noRotationStopsTheCrane() {
        assertEquals(CraneSpeeds.STOPPED, CraneKinematics.speeds(DEFAULTS, 0));
        assertEquals(CraneSpeeds.STOPPED, CraneKinematics.speeds(DEFAULTS, Double.NaN));
        assertTrue(CraneSpeeds.STOPPED.isStopped());
        CraneSpeeds noLift = CraneKinematics.speeds(new CraneKinematics.Params(0.01, 0.0, 0.01, 1.0),
                Double.POSITIVE_INFINITY);
        assertEquals(0.0, noLift.vy(), "a zero factor stays zero even for infinite rpm");
        assertThrows(IllegalArgumentException.class, () -> new CraneKinematics.Params(-1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CraneSpeeds(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CraneSpeeds(0, -0.1, 0));
    }

    @Test
    void ticksToCoverRoundsUpWithSnapTolerance() {
        assertEquals(0, TravelTimeModel.ticksToCover(0, 0), "no distance needs no time, even without speed");
        assertEquals(TravelTimeModel.UNAVAILABLE, TravelTimeModel.ticksToCover(5, 0));
        assertEquals(36, TravelTimeModel.ticksToCover(6, 64.0 / 384.0), "exact multiple despite rounding of 1/6");
        assertEquals(3, TravelTimeModel.ticksToCover(1, 64.0 / 192.0));
        assertEquals(4, TravelTimeModel.ticksToCover(10, 3));
        assertEquals(4, TravelTimeModel.ticksToCover(-10, 3), "sign does not matter");
        assertEquals(1, TravelTimeModel.ticksToCover(0.5, 1));
        assertEquals(1, TravelTimeModel.ticksToCover(1e-9, 1), "a snap still takes a tick");
        assertThrows(IllegalArgumentException.class, () -> TravelTimeModel.ticksToCover(Double.NaN, 1));
    }

    @Test
    void travelUsesTheSlowerOfTheSimultaneousAxes() {
        CraneSpeeds speeds = CraneKinematics.speeds(DEFAULTS, 64); // vx 1/6, vy 1/8
        assertEquals(36, TravelTimeModel.travelTicks(speeds, 0, 0, 6, 2), "X: 36, Y: 16");
        assertEquals(40, TravelTimeModel.travelTicks(speeds, 3, 5, 4, 0), "X: 6, Y: 40");
        assertEquals(40, TravelTimeModel.travelTicks(speeds, 4, 0, 3, 5), "symmetric");
        assertEquals(0, TravelTimeModel.travelTicks(speeds, 2, 1, 2, 1));
        CraneSpeeds liftOnly = new CraneSpeeds(0, 0.5, 1);
        assertEquals(4, TravelTimeModel.travelTicks(liftOnly, 3, 0, 3, 2), "an axis without distance needs no speed");
        assertEquals(TravelTimeModel.UNAVAILABLE, TravelTimeModel.travelTicks(CraneSpeeds.STOPPED, 0, 0, 1, 0));
    }

    @Test
    void stopAndTripTicks() {
        CraneSpeeds speeds = CraneKinematics.speeds(DEFAULTS, 64);
        assertEquals(3, TravelTimeModel.armTicks(speeds));
        assertEquals(16, TravelTimeModel.stopTicks(speeds, 10), "extend 3 + transfer 10 + retract 3");
        // crane (0,0) → source (2,1): max(12, 8) = 12; source → target (5,0): max(18, 8) = 18
        assertEquals(12 + 16 + 18 + 16, TravelTimeModel.tripTicks(speeds, 10, 0, 0, 2, 1, 5, 0));
        assertEquals(TravelTimeModel.UNAVAILABLE, TravelTimeModel.tripTicks(CraneSpeeds.STOPPED, 10, 0, 0, 0, 0, 0, 0),
                "the arm cannot move without rotation");
        assertThrows(IllegalArgumentException.class, () -> TravelTimeModel.stopTicks(speeds, -1));
    }

    @Test
    void sumsSaturate() {
        assertEquals(7, TravelTimeModel.add(3, 4));
        assertEquals(TravelTimeModel.UNAVAILABLE, TravelTimeModel.add(TravelTimeModel.UNAVAILABLE, 1));
        assertEquals(TravelTimeModel.UNAVAILABLE, TravelTimeModel.add(Long.MAX_VALUE - 1, 5));
        assertTrue(TravelTimeModel.isAvailable(0));
        assertFalse(TravelTimeModel.isAvailable(TravelTimeModel.UNAVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> TravelTimeModel.add(-1, 1));
    }

    /**
     * The config allows a speed factor of 0 ({@code docs/warehouse-system.md} §9), which would stall one axis for ever
     * while the others move. The content layer treats such a configuration as "paused" and names the guilty keys.
     */
    @Test
    void zeroSpeedFactorsAreDetectedAndNamed() {
        assertFalse(DEFAULTS.hasZeroFactor());
        assertTrue(DEFAULTS.zeroFactorNames().isEmpty());

        CraneKinematics.Params noTravel = new CraneKinematics.Params(0.0, 1.0 / 512.0, 1.0 / 192.0, 1.0);
        assertTrue(noTravel.hasZeroFactor());
        assertEquals(List.of("crane.travelBlocksPerTickPerRpm"), noTravel.zeroFactorNames());
        assertEquals(0.0, CraneKinematics.speeds(noTravel, 256).vx(), DELTA, "the travel axis never moves");
        assertTrue(CraneKinematics.speeds(noTravel, 256).vy() > 0.0, "the other axes would still move");

        CraneKinematics.Params dead = new CraneKinematics.Params(0.0, 0.0, 0.0, 0.0);
        assertEquals(List.of("crane.travelBlocksPerTickPerRpm", "crane.liftBlocksPerTickPerRpm",
                "crane.armExtendPerTickPerRpm", "crane.maxBlocksPerTick"), dead.zeroFactorNames());
        assertTrue(CraneKinematics.speeds(dead, 256).isStopped());
    }

    @Test
    void fasterRotationNeverTakesLonger() {
        long previous = TravelTimeModel.UNAVAILABLE;
        for (int rpm = 1; rpm <= 256; rpm++) {
            CraneSpeeds speeds = CraneKinematics.speeds(DEFAULTS, rpm);
            long ticks = TravelTimeModel.tripTicks(speeds, 10, 0, 0, 7, 3, 1, 2);
            assertTrue(ticks <= previous, "rpm " + rpm);
            previous = ticks;
        }
    }
}
