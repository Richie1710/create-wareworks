package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.job.ItemHome.Holder;

/** The second half of the home rule (issue #57, ADR-088): priority, then the most held, then the index. */
class ItemHomeTest {
    @Test
    void theHighestPriorityBeatsTheLargerCount() {
        Holder small = new Holder(5, 10, 3);
        Holder big = new Holder(0, 500, 0);
        assertEquals(Optional.of(small), ItemHome.pick(List.of(big, small)));
    }

    @Test
    void theLargerCountBeatsTheLowerIndexAtOnePriority() {
        Holder near = new Holder(2, 10, 0);
        Holder fuller = new Holder(2, 30, 4);
        assertEquals(Optional.of(fuller), ItemHome.pick(List.of(near, fuller)));
    }

    @Test
    void aTieGoesToTheLowerIndexWhateverTheListOrder() {
        List<Holder> holders = new ArrayList<>(List.of(new Holder(1, 20, 7), new Holder(1, 20, 2),
                new Holder(1, 20, 5)));
        for (int shuffle = 0; shuffle < 6; shuffle++) {
            Collections.rotate(holders, 1);
            assertEquals(Optional.of(new Holder(1, 20, 2)), ItemHome.pick(holders));
        }
    }

    @Test
    void noHolderMeansNoHome() {
        assertEquals(Optional.empty(), ItemHome.pick(List.of()));
    }

    @Test
    void holdingOrderIgnoresTheIndexOfPlacesHoldingNothing() {
        assertEquals(0, ItemHome.compareHolding(0, 1, 0, 9), "two empty places tie, travel decides later");
        assertTrue(ItemHome.compareHolding(5, 9, 0, 0) < 0, "a holder beats an empty place");
        assertTrue(ItemHome.compareHolding(5, 9, 5, 3) > 0);
    }
}
