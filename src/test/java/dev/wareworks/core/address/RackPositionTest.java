package dev.wareworks.core.address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The branch field a rack position gained in M21 (ADR-033) and, above all, the claim that came with it: on a warehouse
 * with one aisle nothing about a rack position changed.
 */
class RackPositionTest {
    /** The comparator {@code RackPosition.ORDER} was before the branch field existed. */
    private static final Comparator<RackPosition> ORDER_BEFORE_BRANCHES = Comparator.comparingInt(RackPosition::x)
            .thenComparingInt(RackPosition::y)
            .thenComparing(RackPosition::side);

    private static final int LENGTH = 4;
    private static final int HEIGHT = 3;

    @Test
    void everyShortFormMeansTheFirstBranch() {
        assertEquals(0, RackPosition.FIRST_BRANCH);
        assertEquals(RackPosition.FIRST_BRANCH, new RackPosition(7, 2, Side.RIGHT).branch());
        assertEquals(RackPosition.FIRST_BRANCH, RackPosition.of(7, 2, Side.RIGHT).branch());
        assertEquals(new RackPosition(RackPosition.FIRST_BRANCH, 7, 2, Side.RIGHT), new RackPosition(7, 2, Side.RIGHT),
                "the three-argument constructor is the four-argument one on branch 0");
        assertEquals(RackPosition.of(7, 2, Side.RIGHT), RackPosition.of(RackPosition.FIRST_BRANCH, 7, 2, Side.RIGHT));
        assertTrue(RackPosition.of(7, 2, Side.RIGHT).isOnFirstBranch());
        assertFalse(RackPosition.of(1, 7, 2, Side.RIGHT).isOnFirstBranch());
    }

    @Test
    void theBranchIsPartOfTheIdentity() {
        RackPosition onA = RackPosition.of(0, 3, 1, Side.LEFT);
        RackPosition onB = RackPosition.of(1, 3, 1, Side.LEFT);
        assertNotEquals(onA, onB, "the same local position on two branches is two locations");
        Map<RackPosition, String> stock = new HashMap<>();
        stock.put(onA, "a");
        stock.put(onB, "b");
        assertEquals(2, stock.size(), "a stock map must not merge them");
        assertEquals(onB, onA.withBranch(1));
        assertEquals(onA, onA.withBranch(RackPosition.FIRST_BRANCH));
    }

    @Test
    void validatesBounds() {
        assertThrows(IllegalArgumentException.class, () -> RackPosition.of(-1, 0, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class,
                () -> RackPosition.of(RackPosition.MAX_BRANCH + 1, 0, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> RackPosition.of(-1, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> RackPosition.of(0, -1, Side.LEFT));
        assertThrows(NullPointerException.class, () -> RackPosition.of(0, 0, null));
        assertEquals(StorageAddress.AISLE_COUNT - 1, RackPosition.MAX_BRANCH,
                "a branch needs an address letter, so the letter count is the bound");
        RackPosition.of(RackPosition.MAX_BRANCH, 0, 0, Side.LEFT);
    }

    @Test
    void orderIsTodaysOrderOnOneBranch() {
        List<RackPosition> positions = onBranch(RackPosition.FIRST_BRANCH);
        for (RackPosition first : positions)
            for (RackPosition second : positions)
                assertEquals(Integer.signum(ORDER_BEFORE_BRANCHES.compare(first, second)),
                        Integer.signum(RackPosition.ORDER.compare(first, second)),
                        () -> "order changed for " + first + " and " + second);
    }

    @Test
    void branchIsTheFirstKey() {
        List<RackPosition> mixed = new ArrayList<>(onBranch(2));
        mixed.addAll(onBranch(RackPosition.FIRST_BRANCH));
        mixed.sort(RackPosition.ORDER);

        int half = mixed.size() / 2;
        for (int i = 0; i < half; i++)
            assertEquals(RackPosition.FIRST_BRANCH, mixed.get(i).branch(), "branch 0 comes first, whole");
        for (int i = half; i < mixed.size(); i++)
            assertEquals(2, mixed.get(i).branch());
        assertEquals(mixed.subList(0, half), onBranch(RackPosition.FIRST_BRANCH),
                "and within a branch the old order is untouched");
    }

    @Test
    void compareToFollowsTheOrder() {
        RackPosition lower = RackPosition.of(0, 1, 0, Side.LEFT);
        RackPosition higher = RackPosition.of(1, 0, 0, Side.LEFT);
        assertTrue(lower.compareTo(higher) < 0, "a later branch sorts after an earlier one, whatever x is");
        assertEquals(0, lower.compareTo(RackPosition.of(1, 0, Side.LEFT)));
    }

    @Test
    void oneAisleGeometryHoldsOnlyTheFirstBranch() {
        AisleGeometry geometry = AisleGeometry.of(LENGTH, HEIGHT);
        RackPosition here = RackPosition.of(1, 0, Side.LEFT);
        assertTrue(geometry.contains(here));
        assertTrue(geometry.indexOf(here) >= 0);
        assertFalse(geometry.contains(here.withBranch(1)), "a geometry describes one branch, not a network");
        assertEquals(-1, geometry.indexOf(here.withBranch(1)));
        assertFalse(geometry.rackPositions().contains(here.withBranch(1)));
        for (RackPosition position : geometry.rackPositions())
            assertTrue(position.isOnFirstBranch());
    }

    /** Every rack position of a {@code LENGTH} x {@code HEIGHT} aisle on one branch, in the order they were in. */
    private static List<RackPosition> onBranch(int branch) {
        List<RackPosition> positions = new ArrayList<>();
        for (int x = 0; x <= LENGTH; x++)
            for (int y = 0; y < HEIGHT; y++)
                for (Side side : Side.values())
                    positions.add(RackPosition.of(branch, x, y, side));
        return positions;
    }
}
