package dev.wareworks.core.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * How a warehouse's package doors are listed (M27, issue #18, ADR-065 D9): in order, bounded, never looking at what is
 * not loaded, and remembering the doors it saw so an unloaded one turns grey instead of vanishing.
 * <p>
 * This is the half of the door rows no GameTest can reach — a GameTest area is force-loaded, so none of its positions
 * is ever unloaded — and the robustness run's warehouse is too short for a door to stand in another chunk than its
 * controller's neighbours.
 */
class DoorRowsTest {
    private static final String GREY = " (not loaded)";

    /** The candidates the test treats as unloaded; everything else is loaded. */
    private final Set<Integer> unloaded = new HashSet<>();
    /** The candidates that are doors when looked at. */
    private final Set<Integer> doors = new HashSet<>();
    /** Every candidate that was looked at, in order. */
    private final List<Integer> looks = new ArrayList<>();

    private final Function<Integer, Optional<String>> look = candidate -> {
        looks.add(candidate);
        return doors.contains(candidate) ? Optional.of("door " + candidate) : Optional.empty();
    };

    @Test
    void listsTheDoorsInCandidateOrder() {
        DoorRows<Integer, String> rows = new DoorRows<>(64, 16);
        doors.addAll(List.of(5, 2, 9));
        DoorRows.Built<String> built = build(rows, List.of(9, 1, 5, 2));
        assertEquals(List.of("door 9", "door 5", "door 2"), built.rows(), "in the order the candidates came");
        assertEquals(0, built.notListed());
        assertFalse(built.unchecked(), "and nothing was left unchecked");
        assertEquals(List.of(9, 1, 5, 2), looks, "every loaded candidate was looked at once");
    }

    @Test
    void anUnloadedDoorThatWasSeenIsListedGreyAndNeverLookedAt() {
        DoorRows<Integer, String> rows = new DoorRows<>(64, 16);
        doors.addAll(List.of(1, 2));
        build(rows, List.of(1, 2));
        looks.clear();

        unloaded.add(2);
        DoorRows.Built<String> built = build(rows, List.of(1, 2));
        assertEquals(List.of("door 1", "door 2" + GREY), built.rows(), "the door whose chunk unloaded turns grey");
        assertEquals(List.of(1), looks, "and is not looked at, which would load it");

        unloaded.clear();
        assertEquals(List.of("door 1", "door 2"), build(rows, List.of(1, 2)).rows(), "and is itself again once loaded");
    }

    @Test
    void anUnloadedCandidateNeverSeenIsSkipped() {
        DoorRows<Integer, String> rows = new DoorRows<>(64, 16);
        doors.addAll(List.of(1, 2));
        unloaded.add(2);
        DoorRows.Built<String> built = build(rows, List.of(1, 2));
        assertEquals(List.of("door 1"), built.rows(), "a door nobody has seen is unknown, and not guessed at");
        assertEquals(List.of(1), looks);
    }

    @Test
    void aDoorThatStoppedBeingOneIsForgotten() {
        DoorRows<Integer, String> rows = new DoorRows<>(64, 16);
        doors.addAll(List.of(1, 2));
        build(rows, List.of(1, 2));
        doors.remove(2);
        assertEquals(List.of("door 1"), build(rows, List.of(1, 2)).rows(), "a port that is no door any more");
        unloaded.add(2);
        assertEquals(List.of("door 1"), build(rows, List.of(1, 2)).rows(),
                "and is not remembered as a door when its chunk unloads afterwards");
        assertEquals(1, rows.remembered());
    }

    @Test
    void aCandidateThatLeftTakesItsMemoryWithIt() {
        DoorRows<Integer, String> rows = new DoorRows<>(64, 16);
        doors.addAll(List.of(1, 2));
        build(rows, List.of(1, 2));
        assertEquals(2, rows.remembered());
        build(rows, List.of(1));
        assertEquals(1, rows.remembered(), "the door that left the warehouse is forgotten");
        unloaded.add(2);
        assertEquals(List.of("door 1"), build(rows, List.of(1, 2)).rows(),
                "so it does not come back grey when the record does");
        rows.forget();
        assertEquals(0, rows.remembered(), "and forget drops everything");
    }

    @Test
    void looksAtNoMoreThanTheBoundOfCandidates() {
        DoorRows<Integer, String> rows = new DoorRows<>(64, 16);
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 100; i++)
            many.add(i);
        DoorRows.Built<String> built = build(rows, many);
        assertEquals(64, looks.size(), "64 looks at most, however many outputs the warehouse has");
        assertEquals(0, looks.getFirst());
        assertEquals(63, looks.getLast(), "the first 64 in order");
        assertTrue(built.unchecked(), "and the build says that it stopped before the rest");
    }

    /**
     * A door behind the spent bound is neither listed nor counted — nothing looked at it — but the build says that it
     * stopped, so the screen can tell the player that more was not checked instead of showing nothing.
     */
    @Test
    void aDoorBehindTheBoundIsNotGuessedAtButTheCutIsSaid() {
        DoorRows<Integer, String> rows = new DoorRows<>(2, 16);
        doors.add(3);
        DoorRows.Built<String> built = build(rows, List.of(1, 2, 3));
        assertEquals(List.of(), built.rows(), "the door behind the bound is not listed");
        assertEquals(0, built.notListed(), "nor counted");
        assertTrue(built.unchecked(), "but the cut is said");
        assertEquals(List.of(1, 2), looks, "two looks, the bound");
        assertFalse(build(rows, List.of(1, 2)).unchecked(), "a build that checked every candidate says nothing");
    }

    /**
     * Only a look spends the bound: an unloaded candidate is never looked at, whether it is skipped as unknown or
     * listed grey from memory, so it takes none of the looks the doors behind it need.
     */
    @Test
    void anUnloadedCandidateSpendsNoneOfTheBound() {
        DoorRows<Integer, String> rows = new DoorRows<>(2, 16);
        doors.addAll(List.of(1, 3, 4));
        build(rows, List.of(1));
        looks.clear();

        unloaded.addAll(List.of(1, 2));
        DoorRows.Built<String> built = build(rows, List.of(1, 2, 3, 4));
        assertEquals(List.of("door 1" + GREY, "door 3", "door 4"), built.rows(),
                "a remembered door listed grey and an unknown one skipped, and both doors behind them looked at");
        assertEquals(List.of(3, 4), looks, "the two looks went to the loaded candidates");
        assertFalse(built.unchecked(), "and every candidate was checked");
    }

    @Test
    void listsNoMoreThanTheBoundOfRowsAndCountsTheRest() {
        DoorRows<Integer, String> rows = new DoorRows<>(64, 16);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(i);
            doors.add(i);
        }
        DoorRows.Built<String> built = build(rows, candidates);
        assertEquals(16, built.rows().size(), "at most 16 rows");
        assertEquals("door 15", built.rows().getLast());
        assertEquals(4, built.notListed(), "and the rest counted");
        assertEquals(20, rows.remembered(), "every door looked at is remembered, listed or not");
    }

    @Test
    void duplicateCandidatesCountOnce() {
        DoorRows<Integer, String> rows = new DoorRows<>(2, 16);
        doors.addAll(List.of(1, 2, 3));
        DoorRows.Built<String> built = build(rows, List.of(1, 1, 2, 3));
        assertEquals(List.of("door 1", "door 2"), built.rows(), "a repeated candidate is one candidate");
        assertEquals(List.of(1, 2), looks);
        assertFalse(built.rows().contains("door 3"), "and the bound is of distinct candidates");
        assertTrue(new DoorRows<Integer, String>(0, 0).build(List.of(1), c -> true, c -> Optional.of("x"),
                r -> r).rows().size() == 1, "a bound below one is one");
    }

    private DoorRows.Built<String> build(DoorRows<Integer, String> rows, List<Integer> candidates) {
        return rows.build(candidates, candidate -> !unloaded.contains(candidate), look, row -> row + GREY);
    }
}
