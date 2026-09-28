package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The grouping behind a terminal's production section (M20, issue #4, ADR-032): the orders a payload carries folded into
 * one line per production plan.
 * <p>
 * It is the model a player reads a chain from, so the cases that matter are the honest ones — which order names the
 * line, which step is shown as the one that is working, and what happens when a payload was cut off before the rest of a
 * chain arrived.
 */
class PlanLinesTest {
    private static final UUID CHEST = UUID.nameUUIDFromBytes("chest".getBytes());
    private static final UUID PLANKS = UUID.nameUUIDFromBytes("planks".getBytes());
    private static final UUID LOGS = UUID.nameUUIDFromBytes("logs".getBytes());
    private static final UUID NAILS = UUID.nameUUIDFromBytes("nails".getBytes());
    private static final UUID SINGLE = UUID.nameUUIDFromBytes("single".getBytes());

    /** The chain of the feature request as a payload carries it: children first, the ordered item last. */
    private static List<PlanMember> chain() {
        return List.of(PlanMember.of(PLANKS, CHEST, 1, true), PlanMember.of(CHEST, CHEST, 0, true));
    }

    @Test
    void anOrdinaryOrderIsALineOfItsOwn() {
        List<PlanLine> lines = PlanLines.of(List.of(PlanMember.single(SINGLE, true)));
        assertEquals(1, lines.size());
        PlanLine line = lines.getFirst();
        assertEquals(SINGLE, line.head());
        assertEquals(Optional.empty(), line.plan(), "an order in no plan names no plan");
        assertFalse(line.isChain(), "and is therefore drawn exactly as it was before M20");
        assertEquals(List.of(SINGLE), line.members());
        assertEquals(Optional.of(SINGLE), line.frontier(), "an open order is its own frontier");
        assertEquals(1, line.openMembers());
        assertTrue(line.frontierIsHead());
    }

    @Test
    void aFinishedOrderHasNoFrontier() {
        PlanLine line = PlanLines.of(List.of(PlanMember.single(SINGLE, false))).getFirst();
        assertEquals(Optional.empty(), line.frontier());
        assertEquals(0, line.openMembers());
        assertFalse(line.frontierIsHead());
    }

    @Test
    void aChainIsOneLineNamedByTheOrderedItem() {
        List<PlanLine> lines = PlanLines.of(chain());
        assertEquals(1, lines.size(), "two orders of one plan are one line");
        PlanLine line = lines.getFirst();
        assertEquals(CHEST, line.head(), "the line names the item a player asked for, not the step");
        assertEquals(Optional.of(CHEST), line.plan());
        assertTrue(line.isChain());
        assertEquals(List.of(CHEST, PLANKS), line.members(), "the head first, then the steps under it");
        assertEquals(Optional.of(PLANKS), line.frontier(), "the deepest open order is where the work is");
        assertFalse(line.frontierIsHead());
        assertEquals(2, line.openMembers());
    }

    @Test
    void theFrontierMovesUpAsStepsFinish() {
        List<PlanLine> lines = PlanLines.of(List.of(PlanMember.of(PLANKS, CHEST, 1, false),
                PlanMember.of(CHEST, CHEST, 0, true)));
        PlanLine line = lines.getFirst();
        assertEquals(Optional.of(CHEST), line.frontier(), "with its step done the ordered item's own order is working");
        assertTrue(line.frontierIsHead());
        assertEquals(1, line.openMembers());
        assertEquals(List.of(CHEST, PLANKS), line.members(), "and the finished step is still shown in the panel");
    }

    @Test
    void threeLevelsAreOrderedShallowestFirstAndTheDeepestOpenOneWorks() {
        List<PlanLine> lines = PlanLines.of(List.of(PlanMember.of(LOGS, CHEST, 2, true),
                PlanMember.of(PLANKS, CHEST, 1, true), PlanMember.of(CHEST, CHEST, 0, true)));
        PlanLine line = lines.getFirst();
        assertEquals(List.of(CHEST, PLANKS, LOGS), line.members());
        assertEquals(Optional.of(LOGS), line.frontier());
        assertEquals(3, line.openMembers());
    }

    /** Two branches at the same depth: the first one to arrive is the frontier, so the line never flickers. */
    @Test
    void tiesAtTheSameDepthKeepTheOrderTheyArrivedIn() {
        List<PlanLine> lines = PlanLines.of(List.of(PlanMember.of(PLANKS, CHEST, 1, true),
                PlanMember.of(NAILS, CHEST, 1, true), PlanMember.of(CHEST, CHEST, 0, true)));
        PlanLine line = lines.getFirst();
        assertEquals(List.of(CHEST, PLANKS, NAILS), line.members());
        assertEquals(Optional.of(PLANKS), line.frontier());
    }

    /** A chain takes the place of its root, which is the last of its orders to be created. */
    @Test
    void aLineSitsWhereItsLastMemberDoes() {
        UUID older = UUID.nameUUIDFromBytes("older".getBytes());
        List<PlanLine> lines = PlanLines.of(List.of(PlanMember.single(older, false),
                PlanMember.of(PLANKS, CHEST, 1, true), PlanMember.of(CHEST, CHEST, 0, true),
                PlanMember.single(SINGLE, true)));
        assertEquals(List.of(older, CHEST, SINGLE), lines.stream().map(PlanLine::head).toList(),
                "the chain sits where its root does, so a screen showing the newest lines reads them backwards");
    }

    /**
     * A payload cut off before the root still produces a line a player can read: the shallowest step there is names it.
     * The alternative would be a chain that is running and shown nowhere at all.
     */
    @Test
    void aChainWhoseRootIsMissingIsNamedByItsShallowestStep() {
        List<PlanLine> lines = PlanLines.of(List.of(PlanMember.of(LOGS, CHEST, 2, true),
                PlanMember.of(PLANKS, CHEST, 1, true)));
        assertEquals(1, lines.size());
        PlanLine line = lines.getFirst();
        assertEquals(PLANKS, line.head());
        assertEquals(Optional.of(CHEST), line.plan(), "it is still that plan, so a cancel still ends the whole chain");
        assertEquals(List.of(PLANKS, LOGS), line.members());
    }

    /** A plan whose only surviving member is its root is no chain: it is drawn as the plain order it looks like. */
    @Test
    void aPlanOfOneVisibleOrderIsDrawnAsAPlainLine() {
        PlanLine line = PlanLines.of(List.of(PlanMember.of(CHEST, CHEST, 0, true))).getFirst();
        assertFalse(line.isChain());
        assertEquals(List.of(CHEST), line.members());
        assertEquals(Optional.of(CHEST), line.frontier());
    }

    @Test
    void nothingAndNullAnswerWithNoLines() {
        assertEquals(List.of(), PlanLines.of(List.of()));
        assertEquals(List.of(), PlanLines.of(null));
        assertEquals(Optional.empty(), PlanLines.byPlan(null, CHEST));
        assertEquals(Optional.empty(), PlanLines.byPlan(PlanLines.of(chain()), null));
    }

    @Test
    void aPlanIsFoundByItsId() {
        List<PlanLine> lines = PlanLines.of(chain());
        assertEquals(Optional.of(CHEST), PlanLines.byPlan(lines, CHEST).map(PlanLine::head));
        assertEquals(Optional.empty(), PlanLines.byPlan(lines, PLANKS), "a step's id is not a plan's id");
        assertEquals(Optional.empty(), PlanLines.byPlan(lines, SINGLE));
    }

    /** Hand-edited save data and a repeated row must not be able to make a member appear twice or the walk spin. */
    @Test
    void duplicatesAndSelfReferencesAnswerRatherThanThrow() {
        List<PlanLine> lines = PlanLines.of(List.of(PlanMember.of(PLANKS, CHEST, 1, true),
                PlanMember.of(PLANKS, CHEST, 1, true), PlanMember.of(CHEST, CHEST, 0, true)));
        assertEquals(1, lines.size());
        assertEquals(List.of(CHEST, PLANKS), lines.getFirst().members(), "a repeated id keeps one row");
        PlanLine own = PlanLines.of(List.of(PlanMember.of(PLANKS, PLANKS, 3, true))).getFirst();
        assertEquals(PLANKS, own.head(), "an order that names itself as its plan is its own head");
        assertFalse(own.isChain());
    }

    @Test
    void aMemberKnowsWhetherItIsTheOrderedItem() {
        assertTrue(PlanMember.of(CHEST, CHEST, 0, true).isRoot());
        assertFalse(PlanMember.of(PLANKS, CHEST, 1, true).isRoot());
        assertFalse(PlanMember.single(SINGLE, true).isRoot(), "an order in no plan is nobody's root");
    }

    /** A negative depth or delivered count from a broken payload is clamped, never a negative number on a screen. */
    @Test
    void aNegativeDepthIsClamped() {
        PlanMember member = new PlanMember(PLANKS, Optional.of(CHEST), -3, true, -7L);
        assertEquals(0, member.depth());
        assertEquals(0L, member.delivered());
    }
}
