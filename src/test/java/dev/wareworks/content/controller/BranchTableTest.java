package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;

/**
 * The aisle letters and origin ends a warehouse pins to its lines of rails ({@link BranchTable}, ADR-033).
 * <p>
 * What these tests are really about is that <b>ordinary building never reshuffles a player's addresses</b>: extending
 * an aisle, shortening it, a junction appearing in its middle and a reload all have to leave every letter and every
 * position number exactly where it was, because a player writes those on signs.
 */
class BranchTableTest {
    private static final int HEIGHT = 4;

    @Test
    @DisplayName("a warehouse of one aisle pins nothing at all, so it saves nothing at all")
    void oneAislePinsNothing() {
        BranchTable table = new BranchTable();
        BranchTable.Assignment assignment = table.assign(NetworkGeometry.single(Heading.EAST, 6, HEIGHT), 'A');

        assertEquals(List.of(Optional.of('A')), assignment.letters(), "the controller's own letter, and no other");
        assertTrue(table.isEmpty(), "the aisle at the dock has nothing a table could pin");
        assertEquals(NetworkGeometry.single(Heading.EAST, 6, HEIGHT), assignment.network(),
                "and the geometry is handed back unchanged");
    }

    @Test
    @DisplayName("the further aisles take the lowest free letters, skipping the controller's own")
    void furtherAislesTakeTheLowestFreeLetters() {
        BranchTable table = new BranchTable();
        BranchTable.Assignment assignment = table.assign(lShape(6, 4), 'C');

        assertEquals(List.of(Optional.of('C'), Optional.of('A')), assignment.letters(),
                "the second aisle takes A, because C is taken by the first one");
        assertEquals(2, table.size() + 1, "one line pinned per aisle beyond the first");
    }

    @Test
    @DisplayName("extending an aisle at its far end keeps its letter and renumbers nothing")
    void extendingAtTheFarEndChangesNothing() {
        BranchTable table = new BranchTable();
        BranchTable.Assignment before = table.assign(lShape(6, 4), 'A');
        BranchTable.Assignment after = table.assign(lShape(6, 9), 'A');

        assertEquals(before.letters(), after.letters(), "the same letters");
        assertEquals(before.network().branch(1).originDx(), after.network().branch(1).originDx(), "the same origin");
        assertEquals(before.network().branch(1).originDz(), after.network().branch(1).originDz(), "the same origin");
        assertEquals(before.network().branch(1).heading(), after.network().branch(1).heading(), "the same heading");
    }

    @Test
    @DisplayName("an aisle discovered from its other end keeps its saved numbering instead of turning round")
    void aSavedOriginSurvivesBeingDiscoveredFromTheOtherEnd() {
        BranchTable table = new BranchTable();
        table.assign(lShape(6, 4), 'A');

        // The same line of rails, but now reached at its far end: without pinning, position 0 would move by four
        // blocks and every address on that aisle would mean another chest.
        NetworkGeometry fromTheOtherEnd = new NetworkGeometry(List.of(
                BranchGeometry.first(Heading.EAST, 6),
                new BranchGeometry(1, 6, 4, Heading.NORTH, 4)), HEIGHT);
        BranchTable.Assignment assignment = table.assign(fromTheOtherEnd, 'A');

        BranchGeometry pinned = assignment.network().branch(1);
        assertEquals(6, pinned.originDx(), "position 0 is still where it was");
        assertEquals(0, pinned.originDz(), "position 0 is still where it was");
        assertEquals(Heading.SOUTH, pinned.heading(), "so the aisle counts the way it always did");
        assertEquals(Optional.of('B'), assignment.letters().get(1), "and keeps its letter");
    }

    @Test
    @DisplayName("an aisle that grew past its old origin cannot keep it, and says so by taking the new one")
    void anOriginThatIsNoLongerAnEndIsDropped() {
        BranchTable table = new BranchTable();
        table.assign(lShape(6, 4), 'A');

        // The line now starts two blocks before where it did, so the saved origin is no longer an end of it. There is
        // no position before position 0, so the branch has to be renumbered - which is exactly what makes the
        // controller remap its records through their world positions.
        NetworkGeometry grown = new NetworkGeometry(List.of(
                BranchGeometry.first(Heading.EAST, 6),
                new BranchGeometry(1, 6, -2, Heading.SOUTH, 6)), HEIGHT);
        BranchGeometry pinned = table.assign(grown, 'A').network().branch(1);

        assertEquals(-2, pinned.originDz(), "the discovered origin wins");
        assertEquals(Heading.SOUTH, pinned.heading(), "with its discovered heading");
    }

    @Test
    @DisplayName("a junction appearing in the middle of an aisle leaves its line, and therefore its letter, alone")
    void aJunctionInTheMiddleKeepsTheLetter() {
        BranchTable table = new BranchTable();
        table.assign(zigZag(), 'A');
        List<Map.Entry<Long, BranchTable.Entry>> pinned = table.entries();

        // The middle aisle is split by nothing and simply keeps running; its line key is its axis and its one fixed
        // coordinate, neither of which a junction changes.
        BranchTable.Assignment again = table.assign(zigZag(), 'A');
        assertEquals(List.of(Optional.of('A'), Optional.of('B'), Optional.of('C')), again.letters(), "stable letters");
        assertEquals(pinned, table.entries(), "and a stable table");
    }

    @Test
    @DisplayName("two aisles on one line keep their own letters instead of trading them on every refresh")
    void twoAislesOnOneLineDoNotShareAnEntry() {
        BranchTable table = new BranchTable();
        List<Optional<Character>> first = table.assign(serpentine(), 'A').letters();
        assertEquals(List.of(Optional.of('A'), Optional.of('B'), Optional.of('C'), Optional.of('D'),
                Optional.of('E'), Optional.of('F')), first);

        // The line key is an axis plus one coordinate, so the two east-west aisles of a serpentine share it. While
        // one entry stood for the whole line they took turns owning it: every refresh swapped their letters, and
        // every address a player had written down meant another chest on alternate refreshes (M21 review fix).
        for (int refresh = 0; refresh < 5; refresh++) {
            BranchTable.Assignment again = table.assign(serpentine(), 'A');
            assertEquals(first, again.letters(), "refresh " + refresh + " must not move a letter");
            assertEquals(serpentine(), again.network(), "and must not renumber a position either");
        }

        assertEquals(5, table.size(), "one entry per aisle beyond the first, not one per line");
    }

    @Test
    @DisplayName("a table restored from a save reproduces the letters of two aisles that share a line")
    void restoringKeepsTwoAislesOnOneLineApart() {
        BranchTable table = new BranchTable();
        BranchTable.Assignment before = table.assign(serpentine(), 'A');

        BranchTable restored = new BranchTable();
        restored.restore(table.entries());
        BranchTable.Assignment after = restored.assign(serpentine(), 'A');

        assertEquals(before.letters(), after.letters(), "the same letters after a reload");
        assertEquals(before.network(), after.network(), "and the same numbering");
    }

    @Test
    @DisplayName("a table restored from a save assigns exactly what it assigned before the save")
    void restoringReproducesTheSameLetters() {
        BranchTable table = new BranchTable();
        BranchTable.Assignment before = table.assign(zigZag(), 'D');
        List<Map.Entry<Long, BranchTable.Entry>> saved = table.entries();

        BranchTable restored = new BranchTable();
        restored.restore(saved);
        BranchTable.Assignment after = restored.assign(zigZag(), 'D');

        assertEquals(before.letters(), after.letters(), "the same letters after a reload");
        assertEquals(before.network(), after.network(), "and the same numbering");
    }

    @Test
    @DisplayName("two aisles never share a letter, even when both have the same one pinned")
    void aClaimedLetterIsNeverHandedOutTwice() {
        BranchTable table = new BranchTable();
        table.restore(List.of(
                Map.entry(BranchTable.lineOf(zigZag().branch(1)), new BranchTable.Entry('B', 6, 0)),
                Map.entry(BranchTable.lineOf(zigZag().branch(2)), new BranchTable.Entry('B', 6, 4))));

        List<Optional<Character>> letters = table.assign(zigZag(), 'A').letters();
        assertEquals(List.of(Optional.of('A'), Optional.of('B'), Optional.of('C')), letters,
                "the first claim wins, the second takes the lowest free letter");
    }

    @Test
    @DisplayName("the controller's own letter is never taken by another aisle")
    void theControllersLetterIsReserved() {
        BranchTable table = new BranchTable();
        table.restore(List.of(Map.entry(BranchTable.lineOf(lShape(6, 4).branch(1)),
                new BranchTable.Entry('A', 6, 0))));

        assertEquals(List.of(Optional.of('A'), Optional.of('B')), table.assign(lShape(6, 4), 'A').letters(),
                "the pinned A is refused because the controller is A");
    }

    @Test
    @DisplayName("the table is bounded by the number of letters, dropping the line it has not seen for longest")
    void theTableIsBounded() {
        BranchTable table = new BranchTable();
        for (int i = 0; i < BranchTable.MAX_ENTRIES + 10; i++)
            table.assign(lShapeAt(i), 'A');

        assertTrue(table.size() <= BranchTable.MAX_ENTRIES, "bounded: " + table.size());
        assertFalse(table.isEmpty(), "and it still remembers the most recent lines");
    }

    @Test
    @DisplayName("the aisle at the dock is never renumbered, whatever the rest of the warehouse does")
    void theFirstAisleIsNeverRenumbered() {
        BranchTable table = new BranchTable();
        table.restore(List.of(Map.entry(BranchTable.lineOf(BranchGeometry.first(Heading.EAST, 6)),
                new BranchTable.Entry('Z', 6, 0))));

        for (NetworkGeometry network : List.of(lShape(6, 4), zigZag(), NetworkGeometry.single(Heading.EAST, 6, HEIGHT))) {
            BranchGeometry first = table.assign(network, 'A').network().firstBranch();
            assertEquals(0, first.originDx(), "position 0 of the first aisle is the dock");
            assertEquals(0, first.originDz(), "position 0 of the first aisle is the dock");
            assertEquals(Heading.EAST, first.heading(), "running the way the dock faces");
        }
    }

    /** Dock facing east for {@code straight} rails, then south for {@code leg} rails. */
    private static NetworkGeometry lShape(int straight, int leg) {
        return new NetworkGeometry(List.of(
                BranchGeometry.first(Heading.EAST, straight),
                new BranchGeometry(1, straight, 0, Heading.SOUTH, leg)), HEIGHT);
    }

    /** An L whose second leg sits on its own line, so every call produces a new line key. */
    private static NetworkGeometry lShapeAt(int index) {
        return new NetworkGeometry(List.of(
                BranchGeometry.first(Heading.EAST, index + 1),
                new BranchGeometry(1, index + 1, 0, Heading.SOUTH, 3)), HEIGHT);
    }

    /**
     * A serpentine — the shape a player builds towards a comb: north, east, south, east, north, east. Its second and
     * its sixth aisle are two <b>disjoint</b> runs on one and the same east-west line, which is the case a key of
     * "axis plus one coordinate" cannot tell apart on its own.
     */
    private static NetworkGeometry serpentine() {
        return new NetworkGeometry(List.of(
                BranchGeometry.first(Heading.NORTH, 2),
                new BranchGeometry(1, 0, -2, Heading.EAST, 2),
                new BranchGeometry(2, 2, -2, Heading.SOUTH, 2),
                new BranchGeometry(3, 2, 0, Heading.EAST, 2),
                new BranchGeometry(4, 4, 0, Heading.NORTH, 2),
                new BranchGeometry(5, 4, -2, Heading.EAST, 2)), HEIGHT);
    }

    /** East, then south, then east again: three aisles, three lines. */
    private static NetworkGeometry zigZag() {
        List<BranchGeometry> branches = new ArrayList<>();
        branches.add(BranchGeometry.first(Heading.EAST, 6));
        branches.add(new BranchGeometry(1, 6, 0, Heading.SOUTH, 4));
        branches.add(new BranchGeometry(2, 6, 4, Heading.EAST, 5));
        return new NetworkGeometry(branches, HEIGHT);
    }
}
