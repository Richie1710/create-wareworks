package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PatternLayTest {
    private static PatternLay.Input<String> input(int count, String... candidates) {
        return new PatternLay.Input<>(List.of(candidates), count);
    }

    @Test
    void aSawRecipeFillsTheFirstCellWithItsCount() {
        PatternLay.Layout<String> layout = PatternLay.layout(List.of(input(1, "log")), "stripped_log");
        assertTrue(layout.refusal().isEmpty());
        assertEquals(ProductionPattern.GRID_SIZE, layout.cells().size());
        assertEquals(List.of("log"), layout.cells().get(0));
        assertEquals(1, layout.counts().get(0));
        assertTrue(layout.cells().get(1).isEmpty());
    }

    @Test
    void inputsOfTheSameItemsShareACellAndSpillAtAStack() {
        PatternLay.Layout<String> layout = PatternLay.layout(
                List.of(input(40, "iron"), input(40, "iron"), input(1, "gold"), input(50, "iron")), "block");
        assertEquals(List.of("iron"), layout.cells().get(0));
        assertEquals(64, layout.counts().get(0));
        assertEquals(List.of("iron"), layout.cells().get(1));
        assertEquals(64, layout.counts().get(1));
        assertEquals(List.of("gold"), layout.cells().get(2));
        assertEquals(1, layout.counts().get(2));
        assertEquals(List.of("iron"), layout.cells().get(3));
        assertEquals(2, layout.counts().get(3));
    }

    @Test
    void differentCandidateListsKeepTheirOwnCells() {
        PatternLay.Layout<String> layout = PatternLay.layout(List.of(input(1, "oak", "birch"), input(1, "oak")),
                "thing");
        assertEquals(List.of("oak", "birch"), layout.cells().get(0));
        assertEquals(List.of("oak"), layout.cells().get(1));
    }

    @Test
    void aFluidInputIsRefused() {
        assertEquals(Optional.of(PatternLay.Refusal.NOT_ONLY_ITEMS),
                PatternLay.layout(List.of(input(1, "iron"), input(1)), "block").refusal());
    }

    @Test
    void moreThanNineCellsAreRefused() {
        PatternLay.Layout<String> fits = PatternLay.layout(List.of(input(9 * 64, "iron")), "block");
        assertTrue(fits.refusal().isEmpty());
        assertEquals(Optional.of(PatternLay.Refusal.TOO_MANY_INPUTS),
                PatternLay.layout(List.of(input(9 * 64 + 1, "iron")), "block").refusal());
    }

    @Test
    void theResultLeavesTheCandidatesAndOnlyAnInputOfNothingElseIsRefused() {
        PatternLay.Layout<String> layout = PatternLay.layout(List.of(input(1, "dyed", "white")), "dyed");
        assertEquals(List.of("white"), layout.cells().get(0));
        assertEquals(Optional.of(PatternLay.Refusal.MAKES_ITS_INGREDIENT),
                PatternLay.layout(List.of(input(1, "dyed")), "dyed").refusal());
    }
}
