package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Where a terminal click delivers (M27, issue #18, ADR-065 D8): here by default, a door chosen by cycling, and back to
 * here whenever the chosen door cannot be chosen any more.
 */
class DoorSelectionTest {
    private static final DoorSelection.Door<String> NORTH = new DoorSelection.Door<>("north", true);
    private static final DoorSelection.Door<String> LINKED = new DoorSelection.Door<>("linked", false);
    private static final DoorSelection.Door<String> SOUTH = new DoorSelection.Door<>("south", true);
    private static final DoorSelection.Door<String> FAR = new DoorSelection.Door<>("far", false);

    @Test
    void startsHereWithNothingToShow() {
        DoorSelection<String> selection = new DoorSelection<>();
        assertTrue(selection.isHere(), "a new selection is here");
        assertEquals(Optional.empty(), selection.selected());
        assertFalse(selection.visible(), "without a door there is no choice to show");
        assertFalse(selection.next(), "and nothing to cycle to");
        assertFalse(selection.previous());
        assertTrue(selection.isHere());
    }

    @Test
    void nextSkipsWhatCannotBeChosenAndWrapsThroughHere() {
        DoorSelection<String> selection = withDoors(NORTH, LINKED, SOUTH, FAR);
        assertTrue(selection.visible());
        assertTrue(selection.next());
        assertEquals(Optional.of("north"), selection.selected(), "the first choosable door after here");
        assertTrue(selection.next());
        assertEquals(Optional.of("south"), selection.selected(), "the linked door is skipped");
        assertTrue(selection.next());
        assertTrue(selection.isHere(), "the door that is not loaded is skipped, and the cycle wraps to here");
        assertTrue(selection.next());
        assertEquals(Optional.of("north"), selection.selected(), "and round again");
    }

    @Test
    void previousRunsTheSameCycleBackwards() {
        DoorSelection<String> selection = withDoors(NORTH, LINKED, SOUTH, FAR);
        assertTrue(selection.previous());
        assertEquals(Optional.of("south"), selection.selected(), "the last choosable door before here");
        assertTrue(selection.previous());
        assertEquals(Optional.of("north"), selection.selected());
        assertTrue(selection.previous());
        assertTrue(selection.isHere());
    }

    @Test
    void onlyUnselectableDoorsLeaveHereAlone() {
        DoorSelection<String> selection = withDoors(LINKED, FAR);
        assertTrue(selection.visible(), "the rows are still shown, so the player sees why");
        assertFalse(selection.next(), "but nothing can be chosen");
        assertFalse(selection.previous());
        assertTrue(selection.isHere());
    }

    @Test
    void hereGoesBackAndSaysWhetherItMoved() {
        DoorSelection<String> selection = withDoors(NORTH, SOUTH);
        assertFalse(selection.here(), "already here");
        selection.next();
        assertTrue(selection.here(), "from a door");
        assertTrue(selection.isHere());
    }

    @Test
    void selectTakesOnlyAChoosableDoorOfTheList() {
        DoorSelection<String> selection = withDoors(NORTH, LINKED);
        assertFalse(selection.select("linked"), "a door that ignores redstone cannot be chosen");
        assertFalse(selection.select("elsewhere"), "nor one that is not on the list");
        assertFalse(selection.select(null));
        assertTrue(selection.isHere());
        assertTrue(selection.select("north"));
        assertEquals(Optional.of("north"), selection.selected());
    }

    @Test
    void updateKeepsADoorByItsIdentity() {
        DoorSelection<String> selection = withDoors(NORTH, SOUTH);
        selection.select("south");
        // The same door in another place of the list, with another door before it: still the same door.
        assertFalse(selection.update(List.of(new DoorSelection.Door<>("west", true), SOUTH, NORTH)),
                "nothing was lost");
        assertEquals(Optional.of("south"), selection.selected());
        assertEquals(3, selection.doors().size());
    }

    @Test
    void updateGoesBackHereWhenTheDoorIsGoneOrCannotBeChosen() {
        DoorSelection<String> selection = withDoors(NORTH, SOUTH);
        selection.select("south");
        assertTrue(selection.update(List.of(NORTH)), "the chosen door disappeared, and that is reported");
        assertTrue(selection.isHere());

        selection.select("north");
        assertTrue(selection.update(List.of(new DoorSelection.Door<>("north", false))),
                "the chosen door can no longer be chosen");
        assertTrue(selection.isHere());

        assertFalse(selection.update(List.of(NORTH)), "a selection that is here loses nothing");
        selection.select("north");
        assertTrue(selection.update(List.of()), "an empty list");
        assertTrue(selection.isHere());
        assertFalse(selection.visible(), "is here, and shows nothing");
    }

    @Test
    void twoSelectionsNeverShareAChoice() {
        DoorSelection<String> first = withDoors(NORTH, SOUTH);
        DoorSelection<String> second = withDoors(NORTH, SOUTH);
        first.next();
        assertEquals(Optional.of("north"), first.selected());
        assertTrue(second.isHere(), "one screen's choice is nobody else's");
    }

    @SafeVarargs
    private static DoorSelection<String> withDoors(DoorSelection.Door<String>... doors) {
        DoorSelection<String> selection = new DoorSelection<>();
        selection.update(List.of(doors));
        return selection;
    }
}
