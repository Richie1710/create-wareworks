package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The escape invariant I3 of ADR-075 §5.1 on hand-made states: no crane may ever cut another off from its dock.
 */
class EscapeCheckTest {
    private static BitSet cut(TestNetworks.Shape shape, int[]... settled) {
        int[] docks = new int[settled.length];
        boolean[] required = new boolean[settled.length];
        for (int i = 0; i < settled.length; i++) {
            docks[i] = shape.dock(i + 1);
            required[i] = true;
        }
        return EscapeCheck.cutOff(shape.graph(), List.of(settled), docks, required);
    }

    private static BitSet set(int... indices) {
        BitSet set = new BitSet();
        for (int index : indices)
            set.set(index);
        return set;
    }

    @Test
    void onALineTheCranesKeepTheirOrder() {
        TestNetworks.Shape line = TestNetworks.line(8);
        assertTrue(cut(line, new int[] {line.cell(3, 0)}, new int[] {line.cell(5, 0)}).isEmpty(),
                "crane 1 west of crane 2: both reach their docks");
        assertEquals(set(0, 1), cut(line, new int[] {line.cell(5, 0)}, new int[] {line.cell(3, 0)}),
                "crane 1 east of crane 2: neither does");
    }

    @Test
    void theCrossedTeeIsCaught() {
        TestNetworks.Shape tee = TestNetworks.crossedTee();
        // Crane 1 stands in the stem, in front of crane 2's dock; crane 2 stands on the main run east of the junction.
        assertEquals(set(1), cut(tee, new int[] {tee.cell(2, 2)}, new int[] {tee.cell(3, 0)}),
                "crane 2 cannot get home past crane 1");
        assertTrue(cut(tee, new int[] {tee.cell(3, 0)}, new int[] {tee.cell(2, 2)}).isEmpty(),
                "with the two the other way round both can");
    }

    @Test
    void aCraneBetweenTwoBlocksBlocksBoth() {
        TestNetworks.Shape comb = TestNetworks.comb();
        int[] tooth = {comb.cell(3, 2)};
        assertEquals(set(0), cut(comb, tooth, comb.graph().standingCells(0, 2.5)),
                "crane 2 between blocks 2 and 3 stands on the tooth's junction too");
        assertEquals(set(0), cut(comb, tooth, comb.graph().standingCells(0, 3.5)),
                "and so it does between blocks 3 and 4");
        assertTrue(cut(comb, tooth, comb.graph().standingCells(0, 4.0)).isEmpty(),
                "on block 4 alone it is out of the way");
    }

    @Test
    void aHomePointBetweenAnotherCraneAndItsDockCutsItOff() {
        TestNetworks.Shape comb = TestNetworks.comb();
        // Crane 1 waits in the first tooth; crane 2 parks on the main run between that tooth and crane 1's dock.
        assertEquals(set(0), cut(comb, new int[] {comb.cell(3, 2)}, new int[] {comb.cell(2, 0)}));
        assertTrue(cut(comb, new int[] {comb.cell(3, 2)}, new int[] {comb.cell(4, 0)}).isEmpty(),
                "a park beyond the tooth cuts nobody off");
    }

    @Test
    void aCraneThatIsNotRequiredToEscapeIsOnlyAnObstacle() {
        TestNetworks.Shape line = TestNetworks.line(8);
        BitSet cut = EscapeCheck.cutOff(line.graph(), List.of(new int[] {line.cell(5, 0)}, new int[] {line.cell(3, 0)}),
                new int[] {line.dock(1), line.dock(2)}, new boolean[] {true, false});
        assertEquals(set(0), cut, "the stopped crane 2 is not asked for an escape, but it still blocks crane 1");
    }

    @Test
    void aCraneWithoutADockHasNoEscape() {
        TestNetworks.Shape line = TestNetworks.line(8);
        assertFalse(EscapeCheck.hasEscape(line.graph(), new int[] {line.cell(4, 0)}, TrackGraph.NONE, new BitSet()));
        assertFalse(EscapeCheck.hasEscape(line.graph(), new int[0], line.dock(1), new BitSet()));
        assertTrue(EscapeCheck.hasEscape(line.graph(), new int[] {line.dock(1)}, line.dock(1), new BitSet()));
    }

    /**
     * Lemma 2 and 4 of ADR-075: a dock is a dead end, so a crane parked on its own dock is on nobody's way. Over random
     * states of every shape, sending one crane home to its dock never cuts off a crane that had an escape.
     */
    @Test
    void aCraneOnItsDockNeverCutsAnybodyOff() {
        Random random = new Random(20261027L);
        for (TestNetworks.Shape shape : TestNetworks.all()) {
            TrackGraph graph = shape.graph();
            List<Integer> free = new ArrayList<>();
            for (int cell = 0; cell < graph.cellCount(); cell++) {
                if (!graph.isDock(cell))
                    free.add(cell);
            }
            int cranes = shape.cranes();
            for (int round = 0; round < 300; round++) {
                List<int[]> settled = new ArrayList<>();
                List<Integer> pool = new ArrayList<>(free);
                for (int k = 0; k < cranes; k++)
                    settled.add(new int[] {pool.remove(random.nextInt(pool.size()))});
                int[] docks = graph.docks();
                boolean[] required = new boolean[cranes];
                java.util.Arrays.fill(required, true);
                BitSet before = EscapeCheck.cutOff(graph, settled, docks, required);
                int home = random.nextInt(cranes);
                List<int[]> after = new ArrayList<>(settled);
                after.set(home, new int[] {docks[home]});
                BitSet worse = EscapeCheck.cutOff(graph, after, docks, required);
                worse.andNot(before);
                assertTrue(worse.isEmpty(), shape + ": crane " + (home + 1) + " on its dock cut off crane "
                        + (worse.nextSetBit(0) + 1));
            }
        }
    }
}
