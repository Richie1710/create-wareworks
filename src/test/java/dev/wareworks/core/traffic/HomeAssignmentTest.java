package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.warehouse.RouteTable;

/** Home points are paired with cranes by the warehouse (ADR-075 §8.4): nearest first, deterministic, one each. */
class HomeAssignmentTest {
    private static final RouteTable LINE = TestNetworks.line(8).routes();
    private static final HomeAssignment.Dock ONE = new HomeAssignment.Dock(1, 0, 0);
    private static final HomeAssignment.Dock TWO = new HomeAssignment.Dock(2, 0, 9);

    @Test
    void eachCraneParksAtTheHomePointNearestItsDock() {
        RackPosition west = RackPosition.of(0, 2, 0, Side.LEFT);
        RackPosition east = RackPosition.of(0, 7, 1, Side.RIGHT);
        HomeAssignment.Result result = HomeAssignment.assign(List.of(TWO, ONE), List.of(east, west), LINE, 0.0);
        assertEquals(Map.of(1, west, 2, east), result.homes());
        assertEquals(List.of(), result.unassigned());
    }

    @Test
    void tiesGoToTheLowerCraneNumberThenToTheRackOrder() {
        RackPosition middle = RackPosition.of(0, 4, 0, Side.LEFT);
        RackPosition otherSide = RackPosition.of(0, 4, 0, Side.RIGHT);
        HomeAssignment.Dock near = new HomeAssignment.Dock(2, 0, 8);
        HomeAssignment.Dock alsoNear = new HomeAssignment.Dock(1, 0, 0);
        HomeAssignment.Result result = HomeAssignment.assign(List.of(near, alsoNear), List.of(otherSide, middle),
                LINE, 0.0);
        assertEquals(Map.of(1, middle, 2, otherSide), result.homes(),
                "both four blocks away: crane 1 first, and it takes the first in rack order");
    }

    @Test
    void moreHomePointsThanCranesLeavesTheFarthestOver() {
        RackPosition a = RackPosition.of(0, 1, 0, Side.LEFT);
        RackPosition b = RackPosition.of(0, 3, 0, Side.LEFT);
        RackPosition c = RackPosition.of(0, 8, 0, Side.LEFT);
        HomeAssignment.Result result = HomeAssignment.assign(List.of(ONE, TWO), List.of(c, b, a), LINE, 0.0);
        assertEquals(Map.of(1, a, 2, c), result.homes());
        assertEquals(List.of(b), result.unassigned(), "the block says: more home points than cranes");
    }

    @Test
    void aHomePointTheRailsDoNotReachOrOnAnotherDockIsLeftOver() {
        NetworkGeometry split = new NetworkGeometry(List.of(BranchGeometry.first(Heading.EAST, 6),
                new BranchGeometry(1, 0, 2, Heading.EAST, 6)), TestNetworks.HEIGHT);
        RackPosition across = RackPosition.of(1, 3, 0, Side.LEFT);
        HomeAssignment.Result cut = HomeAssignment.assign(List.of(ONE), List.of(across), RouteTable.of(split), 0.0);
        assertEquals(Map.of(), cut.homes());
        assertEquals(List.of(across), cut.unassigned());

        RackPosition onDockTwo = RackPosition.of(0, 9, 2, Side.LEFT);
        HomeAssignment.Result result = HomeAssignment.assign(List.of(ONE, TWO), List.of(onDockTwo), LINE, 0.0);
        assertEquals(Map.of(2, onDockTwo), result.homes(), "only crane 2 may park beside its own dock");
    }
}
