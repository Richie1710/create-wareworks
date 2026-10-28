package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;

/** The warehouse decides who does a job (ADR-075 §8): the nearest free crane that may serve both of its stops. */
class CraneChoiceTest {
    @Test
    void theNearestFreeCraneTakesTheJob() {
        assertEquals(OptionalInt.of(2), CraneChoice.choose(List.of(new CraneChoice.Offer(1, true, 120, 6),
                new CraneChoice.Offer(2, true, 80, 9), new CraneChoice.Offer(3, true, 95, 2))),
                "by its own approach time, not by blocks: crane 2's dock turns faster");
    }

    @Test
    void tiesGoToFewerBlocksThenToTheLowerNumber() {
        assertEquals(OptionalInt.of(3), CraneChoice.choose(List.of(new CraneChoice.Offer(2, true, 80, 9),
                new CraneChoice.Offer(3, true, 80, 4))));
        assertEquals(OptionalInt.of(2), CraneChoice.choose(List.of(new CraneChoice.Offer(4, true, 80, 4),
                new CraneChoice.Offer(2, true, 80, 4))));
    }

    @Test
    void aCraneThatMayNotServeTheJobIsNeverChosen() {
        assertEquals(OptionalInt.of(3), CraneChoice.choose(List.of(new CraneChoice.Offer(1, false, 10, 1),
                new CraneChoice.Offer(3, true, 400, 20))));
        assertEquals(OptionalInt.empty(), CraneChoice.choose(List.of(new CraneChoice.Offer(1, false, 10, 1))));
        assertEquals(OptionalInt.empty(), CraneChoice.choose(List.of()));
    }

    @Test
    void aSingleCraneIsTheChoiceWhenItMayDoTheJob() {
        assertEquals(OptionalInt.of(1), CraneChoice.choose(List.of(new CraneChoice.Offer(1, true, 9_000, 120))),
                "however far away: what a warehouse of one crane always did");
        assertThrows(IllegalArgumentException.class, () -> new CraneChoice.Offer(0, true, 1, 1));
    }

    @Test
    void aCraneServesOnlyLevelsBelowItsOwnMast() {
        TestNetworks.Shape line = TestNetworks.line(8);
        CraneChoice.Eligibility low = CraneChoice.Eligibility.of(line.graph(), new int[] {line.dock(2)}, 2,
                line.dock(2), line.graph().docks(), List.of());
        assertTrue(low.allows(RackPosition.of(0, 4, 1, Side.LEFT)));
        assertFalse(low.allows(RackPosition.of(0, 4, 2, Side.LEFT)), "levels from 2 up are another crane's");
    }

    @Test
    void thePositionsBesideAnotherCranesDockAreThatCranesOwn() {
        TestNetworks.Shape line = TestNetworks.line(8);
        int[] docks = line.graph().docks();
        CraneChoice.Eligibility one = CraneChoice.Eligibility.of(line.graph(), new int[] {docks[0]}, 4, docks[0],
                docks, List.of());
        assertTrue(one.allows(RackPosition.of(0, 0, 0, Side.RIGHT)), "beside its own dock");
        assertFalse(one.allows(RackPosition.of(0, 9, 0, Side.RIGHT)), "never beside crane 2's");
        assertFalse(one.allowsCell(docks[1]));
        assertFalse(one.allows(RackPosition.of(0, 10, 0, Side.RIGHT)), "nor anywhere off the rails");
    }

    @Test
    void noFreeCraneIsGivenWorkBehindABusyOne() {
        TestNetworks.Shape line = TestNetworks.line(8);
        int[] docks = line.graph().docks();
        // Crane 2 has a job and will stand at block 5; crane 1 is free at its dock.
        CraneChoice.Eligibility one = CraneChoice.Eligibility.of(line.graph(), new int[] {docks[0]}, 4, docks[0],
                docks, List.of(new int[] {line.cell(5, 0)}));
        assertTrue(one.allows(RackPosition.of(0, 4, 0, Side.LEFT)));
        assertFalse(one.allows(RackPosition.of(0, 5, 0, Side.LEFT)));
        assertFalse(one.allows(RackPosition.of(0, 7, 0, Side.LEFT)), "behind the busy crane");

        TestNetworks.Shape comb = TestNetworks.comb();
        CraneChoice.Eligibility round = CraneChoice.Eligibility.of(comb.graph(), new int[] {comb.graph().docks()[0]},
                4, comb.graph().docks()[0], comb.graph().docks(), List.of(new int[] {comb.cell(3, 1)}));
        assertTrue(round.allows(RackPosition.of(0, 12, 0, Side.LEFT)),
                "a busy crane in a tooth is in nobody's way on the main run");
    }
}
