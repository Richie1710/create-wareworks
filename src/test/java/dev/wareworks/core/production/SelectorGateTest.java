package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * The one scheduling rule of a Recipe Selector station (ADR-085, design §5.2): one result in the line, a switch only
 * when it is drained, FIFO between results, and deadlines that run only where something physical is awaited.
 */
class SelectorGateTest {
    private static final String LOG = "log";
    private static final String STAIRS = "stairs";
    private static final String SLAB = "slab";
    private static final String WALL = "wall";
    private static final String STATION = "station";
    private static final String RETURN = "return";
    private static final long START = 1000L;
    private static final long TIMEOUT = 100L;
    private static final Predicate<UUID> NOT_BLOCKED = id -> false;

    private final Supplier<UUID> lineIds = sequentialIds();

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1};
        return () -> new UUID(0L, next[0]++);
    }

    /** A selector order of {@code runs} runs of one log to one {@code result}. */
    private ProductionOrder<String, String> order(String result, int runs) {
        return ProductionOrder.start(UUID.randomUUID(), STATION, ProductionPattern.of(LOG, 1, result, 1), runs,
                lineIds, START, TIMEOUT, 0L, null).withSelector(RETURN, runs);
    }

    /** {@code order} after the crane dropped {@code amount} logs at the station: it has handed something over. */
    private static ProductionOrder<String, String> delivered(ProductionOrder<String, String> order, int amount) {
        return order.withDelivered(order.lines().getFirst().id(), amount, START, TIMEOUT);
    }

    /** A drained, armed local station set to {@code selection}. */
    private static SelectorGate.StationFacts<String> local(Optional<String> selection) {
        return facts(SelectorMode.LOCAL, selection, true, Optional.empty(), false, Optional.empty(), true, true, true);
    }

    private static SelectorGate.StationFacts<String> facts(SelectorMode mode, Optional<String> selection, boolean armed,
            Optional<SelectorWait> fault, boolean hold, Optional<UUID> probe, boolean bufferEmpty,
            boolean inboundZero, boolean packagersIdle) {
        return new SelectorGate.StationFacts<>(mode, selection, armed, fault, hold, probe, bufferEmpty, inboundZero,
                packagersIdle);
    }

    private static SelectorGate.Decision<String> decide(List<ProductionOrder<String, String>> open,
            SelectorGate.StationFacts<String> facts) {
        return SelectorGate.decide(open, NOT_BLOCKED, facts);
    }

    @Test
    void noOrderDecidesNothing() {
        SelectorGate.Decision<String> decision = decide(List.of(), local(Optional.of(STAIRS)));
        assertEquals(SelectorGate.Decision.empty(), decision);
        assertEquals(SelectorGate.Decision.empty(),
                decide(List.of(order(STAIRS, 1).cancelled(START)), local(Optional.empty())),
                "a finished order is no candidate");
    }

    @Test
    void anEmptySelectionSwitchesToTheHeadFirst() {
        ProductionOrder<String, String> stairs = order(STAIRS, 4);
        SelectorGate.Decision<String> decision = decide(List.of(stairs), local(Optional.empty()));
        assertEquals(Set.of(), decision.admitted(), "nothing fetches before the machine is set");
        assertEquals(Optional.of(STAIRS), decision.switchTo());
        assertEquals(SelectorWait.SWITCHING, decision.waitOf(stairs.id()));
        assertEquals(Set.of(), decision.paused(), "the head waits for something physical: its deadline runs");
        assertEquals(Optional.of(STAIRS), decision.firstResult());

        SelectorGate.Decision<String> set = decide(List.of(stairs), local(Optional.of(STAIRS)));
        assertEquals(Set.of(stairs.id()), set.admitted());
        assertEquals(Optional.empty(), set.switchTo());
        assertEquals(SelectorWait.NONE, set.waitOf(stairs.id()));
    }

    @Test
    void sameResultNeighboursFetchTogether() {
        ProductionOrder<String, String> first = order(STAIRS, 2);
        ProductionOrder<String, String> second = order(STAIRS, 3);
        SelectorGate.Decision<String> decision = decide(List.of(first, second), local(Optional.of(STAIRS)));
        assertEquals(Set.of(first.id(), second.id()), decision.admitted());
        assertEquals(Map.of(), decision.waits());
        assertEquals(Set.of(), decision.paused());
    }

    @Test
    void resultsTakeTurnsInCreationOrder() {
        ProductionOrder<String, String> a1 = order(STAIRS, 2);
        ProductionOrder<String, String> b1 = order(SLAB, 2);
        ProductionOrder<String, String> a2 = order(STAIRS, 2);

        SelectorGate.Decision<String> first = decide(List.of(a1, b1, a2), local(Optional.of(STAIRS)));
        assertEquals(Set.of(a1.id()), first.admitted(), "A2 is not next to A1, so it waits for B1");
        assertEquals(SelectorWait.BEHIND, first.waitOf(b1.id()));
        assertEquals(SelectorWait.BEHIND, first.waitOf(a2.id()));
        assertEquals(Set.of(b1.id(), a2.id()), first.paused(), "a queue never times out");

        // A1 has come back and completed: B1 is the head, nothing of another result is in the line.
        SelectorGate.Decision<String> second = decide(List.of(b1, a2), local(Optional.of(STAIRS)));
        assertEquals(Set.of(), second.admitted());
        assertEquals(Optional.of(SLAB), second.switchTo());
        assertEquals(SelectorWait.SWITCHING, second.waitOf(b1.id()));
        assertEquals(SelectorWait.BEHIND, second.waitOf(a2.id()), "A2 runs after B1, not before it");

        SelectorGate.Decision<String> third = decide(List.of(b1, a2), local(Optional.of(SLAB)));
        assertEquals(Set.of(b1.id()), third.admitted());
        assertEquals(SelectorWait.BEHIND, third.waitOf(a2.id()));

        SelectorGate.Decision<String> fourth = decide(List.of(a2), local(Optional.of(SLAB)));
        assertEquals(Optional.of(STAIRS), fourth.switchTo());
    }

    @Test
    void aNewOrderOfAnotherResultIsHeldInTheSameCall() {
        ProductionOrder<String, String> stairs = delivered(order(STAIRS, 4), 1);
        ProductionOrder<String, String> slab = order(SLAB, 4);
        SelectorGate.Decision<String> decision = decide(List.of(stairs, slab), local(Optional.of(STAIRS)));
        assertEquals(Set.of(stairs.id()), decision.admitted());
        assertFalse(decision.admitted().contains(slab.id()), "no tick passes before the newcomer is held");
        assertEquals(SelectorWait.BEHIND, decision.waitOf(slab.id()));
        assertEquals(Optional.empty(), decision.switchTo());
    }

    @Test
    void aSwitchWaitsForTheLineToDrainInReasonOrder() {
        ProductionOrder<String, String> slab = order(SLAB, 2);
        ProductionOrder<String, String> engagedStairs = delivered(order(STAIRS, 4), 2);
        // The stairs order is engaged but behind the slab order (another station's chain could do that): its lane is
        // kept, and the slab order may not switch while stairs are in the line.
        SelectorGate.Decision<String> busy = decide(List.of(slab, engagedStairs),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.empty(), false, Optional.empty(), false,
                        false, false));
        assertEquals(SelectorWait.LINE_BUSY, busy.waitOf(slab.id()), "first reason: another result in the line");
        assertEquals(Set.of(engagedStairs.id()), busy.admitted());

        ProductionOrder<String, String> trimmed = engagedStairs.trimmedToDelivered(START, TIMEOUT);
        assertTrue(trimmed.isOpen() && trimmed.engaged());
        assertEquals(SelectorWait.LINE_BUSY, decide(List.of(slab, trimmed), local(Optional.of(STAIRS)))
                .waitOf(slab.id()), "a trimmed order still holds the line");

        assertEquals(SelectorWait.BUFFER_NOT_EMPTY, decide(List.of(slab),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.empty(), false, Optional.empty(), false,
                        false, false)).waitOf(slab.id()));
        assertEquals(SelectorWait.CRANE_INBOUND, decide(List.of(slab),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.empty(), false, Optional.empty(), true,
                        false, false)).waitOf(slab.id()));
        SelectorGate.Decision<String> packager = decide(List.of(slab),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.empty(), false, Optional.empty(), true,
                        true, false));
        assertEquals(SelectorWait.PACKAGER_BUSY, packager.waitOf(slab.id()));
        assertEquals(Optional.empty(), packager.switchTo());
        assertEquals(Set.of(), packager.paused(), "the head runs its deadline while it waits for something physical");

        SelectorGate.Decision<String> localIgnoresPackagers = decide(List.of(slab),
                facts(SelectorMode.LOCAL, Optional.of(STAIRS), true, Optional.empty(), false, Optional.empty(), true,
                        true, false));
        assertEquals(Optional.of(SLAB), localIgnoresPackagers.switchTo());

        SelectorGate.Decision<String> drained = decide(List.of(slab),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.empty(), false, Optional.empty(), true,
                        true, true));
        assertEquals(Optional.of(SLAB), drained.switchTo());
        assertEquals(SelectorWait.SWITCHING, drained.waitOf(slab.id()));
    }

    @Test
    void aStationFaultStopsTheSwitchAfterThePhysicalReasons() {
        ProductionOrder<String, String> slab = order(SLAB, 2);
        SelectorGate.Decision<String> noPackager = decide(List.of(slab),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.of(SelectorWait.NO_PACKAGER), false,
                        Optional.empty(), true, true, true));
        assertEquals(SelectorWait.NO_PACKAGER, noPackager.waitOf(slab.id()));
        assertEquals(Optional.empty(), noPackager.switchTo());
        assertEquals(Set.of(), noPackager.paused(), "a fault at the non-engaged head keeps its deadline running");

        assertEquals(SelectorWait.BUFFER_NOT_EMPTY, decide(List.of(slab),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.of(SelectorWait.NO_PACKAGER), false,
                        Optional.empty(), false, true, true)).waitOf(slab.id()));
    }

    @Test
    void aLocalStationThatIsNotArmedAdmitsNothing() {
        ProductionOrder<String, String> stairs = order(STAIRS, 2);
        SelectorGate.Decision<String> arming = decide(List.of(stairs),
                facts(SelectorMode.LOCAL, Optional.of(STAIRS), false, Optional.empty(), false, Optional.empty(), true,
                        true, true));
        assertEquals(Set.of(), arming.admitted());
        assertEquals(SelectorWait.ARMING, arming.waitOf(stairs.id()));
        assertEquals(Set.of(), arming.paused(), "the head runs its deadline");

        SelectorGate.Decision<String> noSelector = decide(List.of(stairs),
                facts(SelectorMode.LOCAL, Optional.of(STAIRS), false, Optional.of(SelectorWait.NO_SELECTOR), false,
                        Optional.empty(), true, true, true));
        assertEquals(Set.of(), noSelector.admitted());
        assertEquals(SelectorWait.NO_SELECTOR, noSelector.waitOf(stairs.id()));

        SelectorGate.Decision<String> firstSwitch = decide(List.of(stairs),
                facts(SelectorMode.LOCAL, Optional.empty(), false, Optional.empty(), false, Optional.empty(), true,
                        true, true));
        assertEquals(Optional.of(STAIRS), firstSwitch.switchTo(), "arming is what a switch does, so it may start one");
        assertEquals(SelectorWait.SWITCHING, firstSwitch.waitOf(stairs.id()));
    }

    @Test
    void aBusyMachineKeepsTheSwitchPendingAndTheLaneRunning() {
        ProductionOrder<String, String> stairs = order(STAIRS, 2);
        ProductionOrder<String, String> slab = order(SLAB, 2);
        SelectorGate.StationFacts<String> busy = facts(SelectorMode.LOCAL, Optional.of(STAIRS), false,
                Optional.of(SelectorWait.MACHINE_BUSY), false, Optional.empty(), true, true, true);
        SelectorGate.Decision<String> lane = decide(List.of(stairs, slab), busy);
        assertEquals(Set.of(stairs.id()), lane.admitted(), "a working machine does not stop its own lane");

        SelectorGate.Decision<String> pending = decide(List.of(slab), busy);
        assertEquals(Optional.of(SLAB), pending.switchTo(), "kept, so the switch is retried once the saw runs empty");
        assertEquals(SelectorWait.MACHINE_BUSY, pending.waitOf(slab.id()));
        assertEquals(Set.of(), pending.paused());
    }

    @Test
    void aChainWithTwoResultsAtOneStationNeverDeadlocks() {
        ProductionOrder<String, String> parent = order(WALL, 1);
        ProductionOrder<String, String> child = order(SLAB, 2);
        Predicate<UUID> parentBlocked = parent.id()::equals;
        for (List<ProductionOrder<String, String>> open : List.of(List.of(parent, child), List.of(child, parent))) {
            SelectorGate.Decision<String> first = SelectorGate.decide(open, parentBlocked, local(Optional.empty()));
            assertEquals(Optional.of(SLAB), first.switchTo(), "the child gets the lane");
            assertFalse(first.waits().containsKey(parent.id()), "a blocked parent is no candidate");

            ProductionOrder<String, String> working = delivered(child, 1);
            List<ProductionOrder<String, String>> running = open.getFirst() == parent ? List.of(parent, working)
                    : List.of(working, parent);
            assertEquals(Set.of(working.id()),
                    SelectorGate.decide(running, parentBlocked, local(Optional.of(SLAB))).admitted());

            // The child completed: the parent is unblocked and nothing of another result is engaged.
            SelectorGate.Decision<String> after = SelectorGate.decide(List.of(parent), NOT_BLOCKED,
                    local(Optional.of(SLAB)));
            assertEquals(Optional.of(WALL), after.switchTo());
        }
    }

    @Test
    void anEngagedOrderOfTheSelectionKeepsFetchingWhenAnOlderOrderBecomesHead() {
        ProductionOrder<String, String> parent = order(WALL, 1);
        ProductionOrder<String, String> stairs = delivered(order(STAIRS, 4), 1);
        SelectorGate.Decision<String> decision = decide(List.of(parent, stairs), local(Optional.of(STAIRS)));
        assertEquals(Set.of(stairs.id()), decision.admitted());
        assertEquals(SelectorWait.LINE_BUSY, decision.waitOf(parent.id()));
        assertEquals(Set.of(parent.id()), decision.paused(), "waiting for another result's line is a queue");
        assertEquals(Optional.empty(), decision.switchTo());
        assertEquals(Optional.of(STAIRS), decision.firstResult());
    }

    /**
     * Review fix (conservation 2): a station that lost its selection while an order is engaged (a selector registered
     * again mid-run) restores the selection to the engaged order's result at once, although its buffer still holds that
     * order's logs. Without the rule the engaged order waited BEHIND the older head, which waited LINE_BUSY for it, and
     * neither ever moved nor timed out.
     */
    @Test
    void anEmptySelectionWithAnEngagedOrderGoesBackToItsResult() {
        ProductionOrder<String, String> parent = order(WALL, 1);
        ProductionOrder<String, String> stairs = delivered(order(STAIRS, 4), 1);
        SelectorGate.StationFacts<String> lost = facts(SelectorMode.LOCAL, Optional.empty(), false, Optional.empty(),
                false, Optional.empty(), false, false, true);
        SelectorGate.Decision<String> decision = decide(List.of(parent, stairs), lost);
        assertEquals(Optional.of(STAIRS), decision.switchTo(), "back to the result in the line, buffer or not");
        assertEquals(Set.of(), decision.admitted(), "nothing fetches before the machine is confirmed");
        assertEquals(SelectorWait.SWITCHING, decision.waitOf(stairs.id()));
        assertEquals(SelectorWait.BEHIND, decision.waitOf(parent.id()));
        assertEquals(Set.of(parent.id(), stairs.id()), decision.paused());
        assertEquals(Optional.of(STAIRS), decision.firstResult());

        SelectorGate.Decision<String> faulted = decide(List.of(parent, stairs), facts(SelectorMode.LOCAL,
                Optional.empty(), false, Optional.of(SelectorWait.NO_SELECTOR), false, Optional.empty(), false, true,
                true));
        assertEquals(SelectorWait.NO_SELECTOR, faulted.waitOf(stairs.id()), "a fault still says why");

        SelectorGate.Decision<String> restored = decide(List.of(parent, stairs), local(Optional.of(STAIRS)));
        assertEquals(Set.of(stairs.id()), restored.admitted(), "and once restored the engaged order fetches again");
        assertEquals(SelectorWait.LINE_BUSY, restored.waitOf(parent.id()));
    }

    @Test
    void aHoldStopsTheWholeStation() {
        ProductionOrder<String, String> stairs = delivered(order(STAIRS, 4), 1);
        ProductionOrder<String, String> slab = order(SLAB, 2);
        SelectorGate.Decision<String> decision = decide(List.of(stairs, slab),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.empty(), true, Optional.empty(), true,
                        true, true));
        assertEquals(Set.of(), decision.admitted());
        assertEquals(Map.of(stairs.id(), SelectorWait.HOLD, slab.id(), SelectorWait.HOLD), decision.waits());
        assertEquals(Set.of(stairs.id(), slab.id()), decision.paused(), "no deadline runs under a hold");
        assertEquals(Optional.empty(), decision.switchTo());
        assertEquals(Optional.of(STAIRS), decision.firstResult(), "the selection is kept");
    }

    @Test
    void anEngagedOrderStoppedByAFaultIsPaused() {
        ProductionOrder<String, String> stairs = delivered(order(STAIRS, 4), 1);
        SelectorGate.Decision<String> decision = decide(List.of(stairs),
                facts(SelectorMode.SLIPS, Optional.of(STAIRS), true, Optional.of(SelectorWait.PACKAGER_LINKED), false,
                        Optional.empty(), true, true, true));
        assertEquals(Set.of(), decision.admitted());
        assertEquals(SelectorWait.PACKAGER_LINKED, decision.waitOf(stairs.id()));
        assertEquals(Set.of(stairs.id()), decision.paused(), "its ingredients are in the line: it waits for the player");
    }

    @Test
    void theProbeFetchesOneRunAndHoldsTheRestUntilItsFirstResult() {
        ProductionOrder<String, String> probe = order(STAIRS, 8);
        ProductionOrder<String, String> next = order(STAIRS, 8);
        SelectorGate.StationFacts<String> probing = facts(SelectorMode.SLIPS, Optional.of(STAIRS), true,
                Optional.empty(), false, Optional.of(probe.id()), true, true, true);
        SelectorGate.Decision<String> first = decide(List.of(probe, next), probing);
        assertEquals(Set.of(probe.id()), first.admitted());
        assertEquals(Map.of(probe.id(), 1), first.runCaps());
        assertEquals(SelectorWait.PROBE_ONE_RUN, first.waitOf(probe.id()));
        assertEquals(SelectorWait.PROBE_BEHIND, first.waitOf(next.id()));
        assertEquals(Set.of(next.id()), first.paused());

        ProductionOrder<String, String> cameBack = delivered(probe, 1).withStored(1L, START, TIMEOUT);
        assertEquals(1L, cameBack.produced());
        SelectorGate.Decision<String> lifted = decide(List.of(cameBack, next), probing);
        assertEquals(Set.of(cameBack.id(), next.id()), lifted.admitted());
        assertEquals(Map.of(), lifted.runCaps());
        assertEquals(Set.of(), lifted.paused());
    }

    @Test
    void onlyTheNonEngagedHeadWaitingForSomethingPhysicalRunsItsDeadline() {
        ProductionOrder<String, String> slab = order(SLAB, 2);
        ProductionOrder<String, String> wall = order(WALL, 2);
        ProductionOrder<String, String> stairs = order(STAIRS, 2);
        SelectorGate.Decision<String> decision = decide(List.of(slab, wall, stairs),
                facts(SelectorMode.LOCAL, Optional.of(STAIRS), true, Optional.empty(), false, Optional.empty(), false,
                        true, true));
        assertEquals(SelectorWait.BUFFER_NOT_EMPTY, decision.waitOf(slab.id()));
        assertEquals(SelectorWait.BEHIND, decision.waitOf(wall.id()));
        assertEquals(SelectorWait.BEHIND, decision.waitOf(stairs.id()), "not next to the head: no lane of its own");
        assertEquals(Set.of(wall.id(), stairs.id()), decision.paused());
        assertEquals(Set.of(), decision.admitted());
        assertEquals(Optional.of(SLAB), decision.firstResult());
    }

    @Test
    void aStationWithoutSelectorAdmitsEveryCandidate() {
        ProductionOrder<String, String> stairs = order(STAIRS, 2);
        ProductionOrder<String, String> slab = order(SLAB, 2);
        SelectorGate.Decision<String> decision = decide(List.of(stairs, slab),
                facts(SelectorMode.NONE, Optional.empty(), false, Optional.empty(), false, Optional.empty(), false,
                        false, false));
        assertEquals(Set.of(stairs.id(), slab.id()), decision.admitted());
        assertEquals(Map.of(), decision.waits());
        assertEquals(Optional.empty(), decision.switchTo());
    }
}
