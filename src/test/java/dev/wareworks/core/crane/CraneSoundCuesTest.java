package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneSoundCues.Cue;
import dev.wareworks.core.crane.CraneSoundCues.Sound;
import dev.wareworks.core.job.CraneSpeeds;

class CraneSoundCuesTest {
    private static final CraneSpeeds FAST = new CraneSpeeds(0.5, 0.5, 0.5);
    private static final CraneSpeeds SLOW = new CraneSpeeds(0.05, 0.05, 0.05);
    private static final long START_TICK = 1000;

    private final CraneSoundCues cues = new CraneSoundCues();

    /** One tick-by-tick run of {@link CraneMotion} from {@code start} to {@code target}; returns the cues per tick. */
    private List<List<Cue>> travel(long firstTick, CranePose start, CranePose target, CraneSpeeds speeds) {
        List<List<Cue>> perTick = new ArrayList<>();
        CranePose pose = start;
        long tick = firstTick;
        while (true) {
            CranePose next = CraneMotion.step(pose, target, speeds);
            perTick.add(cuesOf(cues.motion(tick++, pose, next, target, speeds, false)));
            if (next.equals(pose))
                return perTick;
            pose = next;
        }
    }

    private static List<Cue> cuesOf(List<Sound> sounds) {
        return sounds.stream().map(Sound::cue).toList();
    }

    private static long count(List<List<Cue>> perTick, Cue cue) {
        return perTick.stream().flatMap(List::stream).filter(cue::equals).count();
    }

    @Test
    void standingStillIsSilent() {
        CranePose pose = CranePose.at(3, 1, Side.LEFT);
        for (long tick = START_TICK; tick < START_TICK + 100; tick++)
            assertEquals(List.of(), cues.motion(tick, pose, pose, pose, FAST, false));
    }

    @Test
    void pausedTicksAreSilentAndKeepTheTravelState() {
        CranePose start = CranePose.at(0, 0, Side.LEFT);
        CranePose target = CranePose.at(4, 0, Side.LEFT);
        // SLOW does not reach the first rail joint (0.5) in one tick, so the start cue is alone.
        CranePose moved = CraneMotion.step(start, target, SLOW);
        assertEquals(List.of(Cue.TRAVEL_START), cuesOf(cues.motion(START_TICK, start, moved, target, SLOW, false)));
        // Paused (no rotation): nothing, even for many ticks, and no stop cue although the pose did not change.
        for (long tick = START_TICK + 1; tick < START_TICK + 200; tick++)
            assertEquals(List.of(), cues.motion(tick, moved, moved, target, CraneSpeeds.STOPPED, true));
        // Resumed mid-travel: no second start cue.
        CranePose next = CraneMotion.step(moved, target, SLOW);
        assertEquals(List.of(), cues.motion(START_TICK + 200, moved, next, target, SLOW, false));
    }

    @Test
    void travelStartsOnceAndStopsAtTheTarget() {
        List<List<Cue>> perTick = travel(START_TICK, CranePose.at(0, 0, Side.LEFT), CranePose.at(3, 0, Side.LEFT), FAST);
        assertEquals(Cue.TRAVEL_START, perTick.getFirst().getFirst());
        assertEquals(1, count(perTick, Cue.TRAVEL_START));
        assertTrue(perTick.get(perTick.size() - 2).contains(Cue.TRAVEL_STOP), "stops in the tick it arrives");
        assertEquals(List.of(), perTick.getLast(), "standing at the target afterwards is silent");
        assertEquals(1, count(perTick, Cue.TRAVEL_STOP));
    }

    @Test
    void travelStartIsRateLimited() {
        CranePose a = CranePose.at(0, 0, Side.LEFT);
        CranePose b = CranePose.at(1, 0, Side.LEFT);
        List<List<Cue>> first = travel(START_TICK, a, b, FAST);
        long end = START_TICK + first.size();
        // Back again right away: travel starts within the interval, so no start cue, but it still stops.
        List<List<Cue>> quickReturn = travel(end, b, a, FAST);
        assertEquals(0, count(quickReturn, Cue.TRAVEL_START));
        assertEquals(1, count(quickReturn, Cue.TRAVEL_STOP));
        // Much later: the start cue plays again.
        List<List<Cue>> later = travel(end + CraneSoundCues.TRAVEL_START_MIN_INTERVAL_TICKS * 2L, a, b, FAST);
        assertEquals(1, count(later, Cue.TRAVEL_START));
    }

    @Test
    void railClacksAtJointsAndAreRateLimited() {
        // 0.25 blocks per tick from 0 to 4: joints at 0.5, 1.5, 2.5, 3.5, one every 4 ticks, exactly the minimum interval.
        CraneSpeeds speeds = new CraneSpeeds(0.25, 0.25, 0.25);
        List<List<Cue>> perTick = travel(START_TICK, CranePose.at(0, 0, Side.LEFT), CranePose.at(4, 0, Side.LEFT), speeds);
        assertEquals(4, count(perTick, Cue.RAIL_CLACK));
        assertTrue(perTick.get(1).contains(Cue.RAIL_CLACK), "the step from 0.25 to 0.5 reaches the first joint");

        // 1 block per tick crosses a joint every tick; only every RAIL_CLACK_MIN_INTERVAL_TICKS-th tick clacks.
        CraneSoundCues fast = new CraneSoundCues();
        CraneSpeeds veryFast = new CraneSpeeds(1.0, 1.0, 1.0);
        int clacks = 0;
        CranePose pose = CranePose.at(0, 0, Side.LEFT);
        CranePose target = CranePose.at(20, 0, Side.LEFT);
        for (int tick = 0; tick < 20; tick++) {
            CranePose next = CraneMotion.step(pose, target, veryFast);
            clacks += (int) fast.motion(tick, pose, next, target, veryFast, false).stream()
                    .filter(sound -> sound.cue() == Cue.RAIL_CLACK).count();
            pose = next;
        }
        assertEquals(20 / CraneSoundCues.RAIL_CLACK_MIN_INTERVAL_TICKS, clacks);
    }

    @Test
    void liftChainEveryHalfLevel() {
        CraneSpeeds speeds = new CraneSpeeds(0.125, 0.125, 0.125);
        List<List<Cue>> perTick = travel(START_TICK, CranePose.at(0, 0, Side.LEFT), CranePose.at(0, 2, Side.LEFT), speeds);
        assertEquals(4, count(perTick, Cue.LIFT_CHAIN), "0.5, 1.0, 1.5 and 2.0 levels");
        assertEquals(0, count(perTick, Cue.RAIL_CLACK), "no travel along the aisle");
        List<List<Cue>> down = travel(START_TICK + 1000, CranePose.at(0, 2, Side.LEFT), CranePose.at(0, 0, Side.LEFT), speeds);
        assertEquals(4, count(down, Cue.LIFT_CHAIN), "the same going down");
    }

    @Test
    void motionVolumeGrowsWithSpeed() {
        assertEquals(CraneSoundCues.MIN_VOLUME, CraneSoundCues.motionVolume(0.0));
        assertEquals(CraneSoundCues.MIN_VOLUME, CraneSoundCues.motionVolume(Double.NaN));
        assertEquals(CraneSoundCues.MIN_VOLUME, CraneSoundCues.motionVolume(-1.0));
        assertEquals(CraneSoundCues.FULL_VOLUME, CraneSoundCues.motionVolume(CraneSoundCues.FULL_VOLUME_SPEED));
        assertEquals(CraneSoundCues.FULL_VOLUME, CraneSoundCues.motionVolume(4.0));
        assertTrue(CraneSoundCues.motionVolume(SLOW.vx()) < CraneSoundCues.motionVolume(FAST.vx()));

        CranePose start = CranePose.at(0, 0, Side.LEFT);
        CranePose target = CranePose.at(5, 0, Side.LEFT);
        Sound slowStart = cues.motion(START_TICK, start, CraneMotion.step(start, target, SLOW), target, SLOW, false)
                .getFirst();
        Sound fastStart = new CraneSoundCues().motion(START_TICK, start, CraneMotion.step(start, target, FAST), target,
                FAST, false).getFirst();
        assertTrue(slowStart.volume() < fastStart.volume());
    }

    @Test
    void armCuesOnlyWhenTheArmMoves() {
        CranePose retracted = CranePose.at(2, 1, Side.RIGHT);
        CranePose extended = retracted.withArm(CranePose.EXTENDED);
        assertEquals(Optional.of(Cue.ARM_EXTEND),
                cues.phaseChanged(CranePhase.TRAVEL_TO_SOURCE, CranePhase.EXTEND_SOURCE, retracted, extended)
                        .map(Sound::cue));
        assertEquals(Optional.of(Cue.ARM_RETRACT),
                cues.phaseChanged(CranePhase.DROP, CranePhase.RETRACT_TARGET, extended, retracted).map(Sound::cue));
        assertEquals(Optional.empty(),
                cues.phaseChanged(CranePhase.TRAVEL_TO_TARGET, CranePhase.EXTEND_TARGET, extended, extended),
                "already extended");
        assertEquals(Optional.empty(),
                cues.phaseChanged(CranePhase.PICK, CranePhase.RETRACT_SOURCE, retracted, retracted), "already retracted");
        assertEquals(Optional.empty(), cues.phaseChanged(CranePhase.IDLE, CranePhase.TRAVEL_TO_SOURCE, retracted, retracted));
        assertEquals(Optional.empty(), cues.phaseChanged(CranePhase.EXTEND_TARGET, CranePhase.DROP, extended, extended));
        assertEquals(Optional.empty(), cues.phaseChanged(CranePhase.COMPLETE, CranePhase.IDLE, retracted, retracted));
    }

    @Test
    void transfersOnlyForRealAmounts() {
        assertEquals(Optional.empty(), CraneSoundCues.picked(0));
        assertEquals(Optional.of(Cue.PICK), CraneSoundCues.picked(1).map(Sound::cue));
        assertEquals(Optional.empty(), CraneSoundCues.dropped(0));
        assertEquals(Optional.empty(), CraneSoundCues.dropped(-3));
        assertEquals(Optional.of(Cue.DROP), CraneSoundCues.dropped(64).map(Sound::cue));
    }

    @Test
    void soundVolumeIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Sound(Cue.PICK, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new Sound(Cue.PICK, 1.5));
        assertThrows(NullPointerException.class, () -> new Sound(null, 1.0));
    }
}
