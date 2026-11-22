package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.inventory.KeyCount;

/**
 * Which boxes an address order fetches (issue #40, owner decision E11): picked at the click, in the racks' retrieval
 * order (lowest storage priority first, issue #47, ADR-078), then by key value.
 */
class PackagePickTest {
    private static PackagePick.Candidate<String> box(String key, long available, int priority) {
        return new PackagePick.Candidate<>(key, available, priority);
    }

    @Test
    void theLowestPriorityGoesFirstThenTheKeyOrder() {
        List<KeyCount<String>> picked = PackagePick.pick(List.of(box("c", 1, 0), box("a", 1, 5), box("b", 1, 0)), 2,
                Comparator.naturalOrder());
        assertEquals(List.of(new KeyCount<>("b", 1L), new KeyCount<>("c", 1L)), picked,
                "the priority-5 box is a box the player wants kept; equal priorities go by key");
    }

    @Test
    void identicalBoxesAreTakenTogetherUpToWhatTheClickWants() {
        List<KeyCount<String>> picked = PackagePick.pick(List.of(box("same", 4, 0), box("other", 3, 1)), 6,
                Comparator.naturalOrder());
        assertEquals(List.of(new KeyCount<>("same", 4L), new KeyCount<>("other", 2L)), picked);
    }

    @Test
    void neverMoreThanIsAvailable() {
        List<KeyCount<String>> picked = PackagePick.pick(List.of(box("a", 1, 0), box("b", 0, 0), box("c", -2, 0)),
                20, Comparator.naturalOrder());
        assertEquals(List.of(new KeyCount<>("a", 1L)), picked, "a box promised elsewhere is not picked");
    }

    @Test
    void nothingWantedOrNothingThereIsNothingPicked() {
        assertTrue(PackagePick.pick(List.of(box("a", 1, 0)), 0, Comparator.naturalOrder()).isEmpty());
        assertTrue(PackagePick.<String>pick(List.of(), 5, Comparator.naturalOrder()).isEmpty());
    }

    @Test
    void theInputOrderDoesNotMatter() {
        List<PackagePick.Candidate<String>> one = List.of(box("x", 1, 2), box("y", 1, 1), box("z", 1, 1));
        List<PackagePick.Candidate<String>> other = List.of(box("z", 1, 1), box("x", 1, 2), box("y", 1, 1));
        assertEquals(PackagePick.pick(one, 3, Comparator.naturalOrder()),
                PackagePick.pick(other, 3, Comparator.naturalOrder()));
    }
}
