package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Which empty containers a fluid request fills (M31, issue #21, ADR-073): greedy, largest first, never over-serving,
 * with the five shortfalls the question names, and the one known gap of greedy pinned so nobody "fixes" it by accident.
 */
class ContainerPlanTest {
    private static final Comparator<String> ORDER = Comparator.naturalOrder();

    private static ContainerPlan.Row<String> row(String container, int capacity, long available) {
        return new ContainerPlan.Row<>(container, container + "_filled", capacity, available);
    }

    @Test
    void bucketsServeWholeBucketsExactly() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("bucket", 1000, 12)), 10_000, 32_000, ORDER);
        assertEquals(10_000, plan.servedMb());
        assertEquals(ContainerPlan.Shortfall.NONE, plan.shortfall());
        assertEquals(1, plan.portions().size());
        assertEquals(10, plan.portions().getFirst().count());
        assertEquals(10, plan.containers());
        assertEquals("bucket_filled", plan.portions().getFirst().filled());
    }

    @Test
    void largestContainerFirstThenTheRest() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("bucket", 1000, 10), row("barrel", 4000, 2)),
                9_000, 100_000, ORDER);
        assertEquals(9_000, plan.servedMb());
        assertEquals("barrel", plan.portions().get(0).container(), "largest first");
        assertEquals(2, plan.portions().get(0).count());
        assertEquals("bucket", plan.portions().get(1).container());
        assertEquals(1, plan.portions().get(1).count());
    }

    @Test
    void tiesAreBrokenByTheKeyOrder() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("b", 1000, 5), row("a", 1000, 5)), 7_000, 7_000,
                ORDER);
        assertEquals("a", plan.portions().get(0).container());
        assertEquals(5, plan.portions().get(0).count());
        assertEquals("b", plan.portions().get(1).container());
        assertEquals(2, plan.portions().get(1).count());
    }

    @Test
    void integerDivisionNeverOverServes() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("bucket", 1000, 10)), 2_500, 10_000, ORDER);
        assertEquals(2_000, plan.servedMb(), "a container is filled whole or not at all");
        assertTrue(plan.servedMb() <= plan.askedMb());
        assertEquals(ContainerPlan.Shortfall.NO_CONTAINER_FITS, plan.shortfall());
        assertEquals(1000, plan.smallestStep());
    }

    @Test
    void notEnoughFluidServesWhatTheBaysHold() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("bucket", 1000, 10)), 5_000, 3_000, ORDER);
        assertEquals(3_000, plan.servedMb());
        assertEquals(ContainerPlan.Shortfall.NOT_ENOUGH_FLUID, plan.shortfall());
    }

    @Test
    void noContainersServesNothing() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("bucket", 1000, 0)), 5_000, 10_000, ORDER);
        assertEquals(0, plan.servedMb());
        assertEquals(ContainerPlan.Shortfall.NO_CONTAINERS, plan.shortfall());
        assertTrue(plan.portions().isEmpty());
        assertEquals(0, plan.containersInStock());
        assertEquals(ContainerPlan.Shortfall.NO_CONTAINERS, ContainerPlan.<String>none(1000, 1000).shortfall());
    }

    @Test
    void notEnoughContainersUsesThemAll() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("bucket", 1000, 2)), 5_000, 10_000, ORDER);
        assertEquals(2_000, plan.servedMb());
        assertEquals(ContainerPlan.Shortfall.NOT_ENOUGH_CONTAINERS, plan.shortfall());
        assertEquals(2, plan.containersInStock());
    }

    @Test
    void noContainerFitsWhatIsAsked() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("bucket", 1000, 5)), 750, 10_000, ORDER);
        assertEquals(0, plan.servedMb());
        assertEquals(ContainerPlan.Shortfall.NO_CONTAINER_FITS, plan.shortfall());
        assertEquals(1000, plan.smallestStep());
    }

    /**
     * <b>The known gap of greedy, pinned on purpose.</b> From one 1 000 mB and two 750 mB containers, 1 500 mB could be
     * served exactly by the two smaller ones; greedy takes the largest first and serves 1 000. That is the owner's
     * "largest first", predictable, exact with buckets, and the dialog says what is served. If this test fails because
     * someone made the plan optimal, the rule changed: ADR-073 has to change with it.
     */
    @Test
    void greedyCounterExampleIsPinned() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("tank", 1000, 1), row("flask", 750, 2)), 1_500,
                10_000, ORDER);
        assertEquals(1_000, plan.servedMb(), "greedy serves 1 000 of 1 500, not the optimum");
        assertEquals(1, plan.portions().size());
        assertEquals("tank", plan.portions().getFirst().container());
        assertEquals(ContainerPlan.Shortfall.NO_CONTAINER_FITS, plan.shortfall());
    }

    @Test
    void hugeAmountsDoNotOverflow() {
        ContainerPlan<String> plan = ContainerPlan.of(List.of(row("tank", Integer.MAX_VALUE, Long.MAX_VALUE)),
                Long.MAX_VALUE, Long.MAX_VALUE, ORDER);
        assertTrue(plan.servedMb() > 0L);
        assertEquals((long) Integer.MAX_VALUE * plan.portions().getFirst().count(), plan.servedMb());
        assertEquals(Long.MAX_VALUE, plan.containersInStock(), "the container total saturates");
    }

    @Test
    void atMostEightPortions() {
        List<ContainerPlan.Row<String>> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            rows.add(row("c" + (char) ('a' + i), 1000 + i, 1));
        ContainerPlan<String> plan = ContainerPlan.of(rows, 100_000, 100_000, ORDER);
        assertEquals(ContainerPlan.MAX_PORTIONS, plan.portions().size());
        assertTrue(plan.servedMb() < 100_000);
    }

    @Test
    void rowsAndPortionsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> row("bucket", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerPlan.Portion<>("bucket", "lava", 1000, 0));
        assertEquals(0, row("bucket", 1000, -4).available(), "negative availability reads as none");
    }

    @Test
    void fillTripsCountOneTripPerCarriedLoad() {
        List<ContainerPlan.Portion<String>> portions = List.of(new ContainerPlan.Portion<>("bucket", "lava", 1000, 16));
        assertEquals(16, FillTrips.of(portions, portion -> 1), "a filled bucket stacks to one: one per trip");
        assertEquals(1, FillTrips.of(portions, portion -> 16), "a modded container that stacks to 16");
        assertEquals(2, FillTrips.of(portions, portion -> 15));
        assertEquals(16, FillTrips.of(portions, portion -> 0), "below one counts as one");
        assertEquals(0, FillTrips.of(0, 4));
        assertEquals(1, FillTrips.of(Integer.MAX_VALUE, Integer.MAX_VALUE), "no overflow");
        assertEquals(17, FillTrips.of(List.of(new ContainerPlan.Portion<>("a", "a_f", 1000, 16),
                new ContainerPlan.Portion<>("b", "b_f", 500, 2)), portion -> portion.container().equals("a") ? 1 : 2));
    }
}
