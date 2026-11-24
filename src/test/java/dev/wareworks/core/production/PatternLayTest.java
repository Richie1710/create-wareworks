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

    private static PatternLay.FluidInput fluid(int mb, String... candidates) {
        return new PatternLay.FluidInput(List.of(candidates), mb);
    }

    @Test
    void aFluidInputBecomesAFluidCellAndNeverAnItemCell() {
        PatternLay.Layout<String> layout = PatternLay.layout(List.of(input(1, "dirt")),
                List.of(fluid(250, "minecraft:water")), "mud");
        assertTrue(layout.refusal().isEmpty());
        assertEquals(List.of("dirt"), layout.cells().get(0));
        assertTrue(layout.cells().get(1).isEmpty(), "no item cell for the fluid");
        assertEquals(List.of(new FluidCell("minecraft:water", 250)), layout.fluids());
    }

    @Test
    void aTagOfFluidsLaysItsFirstAlternative() {
        PatternLay.Layout<String> layout = PatternLay.layout(List.of(),
                List.of(fluid(100, "a:first", "a:second")), "thing");
        assertEquals(List.of(new FluidCell("a:first", 100)), layout.fluids());
    }

    @Test
    void theSameFluidTwiceIsOneCellWithBothAmounts() {
        PatternLay.Layout<String> layout = PatternLay.layout(List.of(input(1, "dirt")),
                List.of(fluid(250, "minecraft:water"), fluid(500, "minecraft:water")), "mud");
        assertEquals(List.of(new FluidCell("minecraft:water", 750)), layout.fluids());
        assertEquals(Optional.of(PatternLay.Refusal.FLUID_TOO_MUCH), PatternLay.layout(List.of(input(1, "dirt")),
                List.of(fluid(600, "minecraft:water"), fluid(600, "minecraft:water")), "mud").refusal());
    }

    @Test
    void moreThanTwoFluidsAndMoreThanABasinSegmentAreRefused() {
        assertEquals(Optional.of(PatternLay.Refusal.TOO_MANY_FLUIDS), PatternLay.layout(List.of(input(1, "dirt")),
                List.of(fluid(1, "a:a"), fluid(1, "a:b"), fluid(1, "a:c")), "mud").refusal());
        assertEquals(Optional.of(PatternLay.Refusal.FLUID_TOO_MUCH), PatternLay.layout(List.of(input(1, "dirt")),
                List.of(fluid(1001, "a:a")), "mud").refusal());
        assertTrue(PatternLay.layout(List.of(input(1, "dirt")), List.of(fluid(1000, "a:a")), "mud").refusal()
                .isEmpty(), "1000 mB is the limit and fits");
    }

    @Test
    void aFluidInputNoSideCanNameIsRefusedLikeAnyOtherInput() {
        assertEquals(Optional.of(PatternLay.Refusal.NOT_ONLY_ITEMS), PatternLay.layout(List.of(input(1, "dirt")),
                List.of(fluid(250)), "mud").refusal());
    }

    @Test
    void aRecipeWithOnlyAFluidIsALayout() {
        PatternLay.Layout<String> layout = PatternLay.layout(List.of(), List.of(fluid(100, "a:a")), "thing");
        assertTrue(layout.refusal().isEmpty());
        assertTrue(layout.cells().stream().allMatch(List::isEmpty));
    }

    @Test
    void theRefusalsKeepTheirOrdinalsAndNewOnesComeLast() {
        assertEquals(List.of("NOT_ONLY_ITEMS", "TOO_MANY_INPUTS", "MAKES_ITS_INGREDIENT", "TOO_MANY_FLUIDS",
                "FLUID_TOO_MUCH", "FLUID_RESULT"),
                java.util.Arrays.stream(PatternLay.Refusal.values()).map(Enum::name).toList());
    }

    @Test
    void aRecipeWithoutFluidsLaysNoFluidCell() {
        assertTrue(PatternLay.layout(List.of(input(1, "log")), "stripped_log").fluids().isEmpty());
    }
}
