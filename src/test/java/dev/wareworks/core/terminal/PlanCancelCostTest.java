package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * What the terminal tells a player giving up on a chain would cost (M20, issue #4, ADR-032) — the last thing they read
 * before an irreversible click, so every case here is a state a real chain sits in.
 * <p>
 * The arithmetic must match {@code ProductionOrders#failPlan}: the named order plus every other open member that has been
 * handed nothing end, a member with a batch already at its machine is detached and runs on, and the loss is the named
 * order's own delivered count. The case that made this class necessary is {@link #aStepAtAMachineNeitherEndsNorIsLost()}.
 */
class PlanCancelCostTest {
    private static final UUID CHEST = UUID.nameUUIDFromBytes("chest".getBytes());
    private static final UUID PLANKS = UUID.nameUUIDFromBytes("planks".getBytes());
    private static final UUID LOGS = UUID.nameUUIDFromBytes("logs".getBytes());
    private static final UUID NAILS = UUID.nameUUIDFromBytes("nails".getBytes());
    private static final UUID SINGLE = UUID.nameUUIDFromBytes("single".getBytes());

    private static PlanLine lineOf(List<PlanMember> members) {
        List<PlanLine> lines = PlanLines.of(members);
        assertEquals(1, lines.size(), "the members must make exactly one line");
        return lines.getFirst();
    }

    private static PlanCancelCost costOf(List<PlanMember> members) {
        return PlanCancelCost.of(lineOf(members), members).orElseThrow();
    }

    /**
     * <b>The state a player really clicks in.</b> The root is blocked and has therefore been handed nothing, while the step
     * under it has its batch at the saw. The server cancels the root, detaches the step and loses nothing at all — the
     * step's planks still come back — so the panel must say one order and no loss.
     */
    @Test
    void aStepAtAMachineNeitherEndsNorIsLost() {
        PlanCancelCost cost = costOf(List.of(PlanMember.of(PLANKS, CHEST, 1, true, 1L),
                PlanMember.of(CHEST, CHEST, 0, true, 0L)));
        assertEquals(CHEST, cost.target(), "the click names the head while it is open");
        assertEquals(1, cost.endedOrders(), "only the blocked root ends: the step is detached and runs on");
        assertEquals(0L, cost.lostIngredients(), "and nothing is lost, because the step still makes its planks");
        assertFalse(cost.armsSafetyStop(), "so nothing arms the safety stop either");
    }

    /** Every open member that has been handed nothing ends with the named order, however deep it is. */
    @Test
    void openMembersWithNothingDeliveredAllEnd() {
        PlanCancelCost cost = costOf(List.of(PlanMember.of(LOGS, CHEST, 2, true, 0L),
                PlanMember.of(PLANKS, CHEST, 1, true, 0L), PlanMember.of(CHEST, CHEST, 0, true, 0L)));
        assertEquals(3, cost.endedOrders(), "the whole chain collapses when nothing has been committed");
        assertEquals(0L, cost.lostIngredients());
    }

    /**
     * The named order's <b>own</b> batch is the loss, and it is what arms the safety stop. This is the chain down to its
     * last step: the root is the frontier, its ingredients are at the machine, and cancelling it is what a player pays for.
     */
    @Test
    void theNamedOrdersOwnBatchIsTheWholeLoss() {
        PlanCancelCost cost = costOf(List.of(PlanMember.of(PLANKS, CHEST, 1, false, 4L),
                PlanMember.of(CHEST, CHEST, 0, true, 6L)));
        assertEquals(CHEST, cost.target());
        assertEquals(1, cost.endedOrders(), "the completed step is not touched by a cancellation");
        assertEquals(6L, cost.lostIngredients(), "and the finished step's own 4 planks came back as the chest's");
        assertTrue(cost.armsSafetyStop(), "a cancellation with items at a machine stops the warehouse making the item");
    }

    /** A head that has already ended badly hands the click to the frontier, exactly as the screen's cancel does. */
    @Test
    void aFinishedHeadIsCancelledFromTheFrontier() {
        List<PlanMember> members = List.of(PlanMember.of(LOGS, CHEST, 2, true, 3L),
                PlanMember.of(PLANKS, CHEST, 1, true, 0L), PlanMember.of(CHEST, CHEST, 0, false, 0L));
        PlanCancelCost cost = costOf(members);
        assertEquals(LOGS, cost.target(), "the deepest open step is where the chain can still be ended");
        assertEquals(2, cost.endedOrders(), "itself and the open step above it, which has nothing at a machine");
        assertEquals(3L, cost.lostIngredients(), "only its own batch");
    }

    /** A plan every member of which has finished can be given up on by nobody, and prices nothing. */
    @Test
    void aFinishedPlanHasNoCost() {
        List<PlanMember> members = List.of(PlanMember.of(PLANKS, CHEST, 1, false, 4L),
                PlanMember.of(CHEST, CHEST, 0, false, 6L));
        assertEquals(Optional.empty(), PlanCancelCost.of(lineOf(members), members));
    }

    /** A branch beside the frontier is judged on its own delivered count, not on where it sits in the tree. */
    @Test
    void aSecondBranchIsJudgedOnItsOwnBatch() {
        PlanCancelCost busy = costOf(List.of(PlanMember.of(PLANKS, CHEST, 1, true, 2L),
                PlanMember.of(NAILS, CHEST, 1, true, 0L), PlanMember.of(CHEST, CHEST, 0, true, 0L)));
        assertEquals(2, busy.endedOrders(), "the root and the empty branch; the branch with a batch runs on");
        assertEquals(0L, busy.lostIngredients());
    }

    /**
     * A payload that was cut short before part of the chain understates the count instead of inventing a member — the same
     * rule the grouping follows, because a screen may only ever say less than the server knows.
     */
    @Test
    void aCutShortPayloadCountsOnlyWhatItHas() {
        List<PlanMember> members = List.of(PlanMember.of(PLANKS, CHEST, 1, true, 0L),
                PlanMember.of(CHEST, CHEST, 0, true, 0L));
        PlanLine full = lineOf(members);
        PlanCancelCost cost = PlanCancelCost.of(full, List.of(members.get(1))).orElseThrow();
        assertEquals(1, cost.endedOrders(), "the step the screen no longer has is not counted");
    }

    /** A line whose head the screen does not hold at all prices nothing, exactly as its cancel affordance does nothing. */
    @Test
    void aLineWithoutItsOwnOrdersPricesNothing() {
        List<PlanMember> members = List.of(PlanMember.of(PLANKS, CHEST, 1, true, 0L),
                PlanMember.of(CHEST, CHEST, 0, true, 0L));
        assertEquals(Optional.empty(), PlanCancelCost.of(lineOf(members), List.of()));
        assertEquals(Optional.empty(), PlanCancelCost.of(null, members), "and neither does no line at all");
        assertEquals(Optional.empty(), PlanCancelCost.of(lineOf(members), null));
    }

    /** An ordinary single order is priced too: one order ends, and its own batch is the loss. */
    @Test
    void anOrdinaryOrderIsPricedAsOne() {
        List<PlanMember> members = List.of(PlanMember.single(SINGLE, true, 5L));
        PlanCancelCost cost = costOf(members);
        assertEquals(SINGLE, cost.target());
        assertEquals(1, cost.endedOrders());
        assertEquals(5L, cost.lostIngredients());
        assertTrue(cost.armsSafetyStop());
    }

    /** A member the line does not own cannot add to its price, whatever a caller passes in. */
    @Test
    void onlyTheLinesOwnMembersCount() {
        List<PlanMember> chain = List.of(PlanMember.of(PLANKS, CHEST, 1, true, 0L),
                PlanMember.of(CHEST, CHEST, 0, true, 0L));
        List<PlanMember> everything = new ArrayList<>(chain);
        everything.add(PlanMember.single(SINGLE, true, 0L));
        PlanCancelCost cost = PlanCancelCost.of(lineOf(chain), everything).orElseThrow();
        assertEquals(2, cost.endedOrders(), "the unrelated order is nobody's step");
    }

    /** Nonsense in the record is answered, not thrown: a negative loss is none and a count below one is one. */
    @Test
    void theRecordAnswersNonsense() {
        PlanCancelCost cost = new PlanCancelCost(CHEST, -3, -7L);
        assertEquals(1, cost.endedOrders(), "the named order always ends");
        assertEquals(0L, cost.lostIngredients());
        assertFalse(cost.armsSafetyStop());
    }
}
