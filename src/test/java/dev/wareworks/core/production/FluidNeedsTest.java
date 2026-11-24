package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FluidNeedsTest {
    private static FluidNeeds.Need need(int mb, String... fluids) {
        Set<String> accepted = Set.of(fluids);
        return new FluidNeeds.Need(accepted::contains, mb);
    }

    @Test
    void aRecipeWithoutFluidsFitsOnlyAPatternWithout() {
        assertTrue(FluidNeeds.fit(List.of(), List.of()));
        assertFalse(FluidNeeds.fit(List.of(), List.of(new FluidCell("minecraft:water", 250))),
                "a cell the recipe never takes is left over");
    }

    @Test
    void eachIngredientNeedsItsOwnCellWithTheSameAmount() {
        List<FluidNeeds.Need> mud = List.of(need(250, "minecraft:water"));
        assertTrue(FluidNeeds.fit(mud, List.of(new FluidCell("minecraft:water", 250))));
        assertFalse(FluidNeeds.fit(mud, List.of(new FluidCell("minecraft:water", 500))), "another amount");
        assertFalse(FluidNeeds.fit(mud, List.of(new FluidCell("minecraft:lava", 250))), "another fluid");
        assertFalse(FluidNeeds.fit(mud, List.of()), "a missing cell");
        assertFalse(FluidNeeds.fit(mud, List.of(new FluidCell("minecraft:water", 250),
                new FluidCell("minecraft:lava", 250))), "a cell too many");
    }

    @Test
    void anIngredientThatAcceptsSeveralFluidsTakesAnyOfThem() {
        List<FluidNeeds.Need> tagged = List.of(need(100, "a:one", "a:two"));
        assertTrue(FluidNeeds.fit(tagged, List.of(new FluidCell("a:two", 100))));
        assertFalse(FluidNeeds.fit(tagged, List.of(new FluidCell("a:three", 100))));
    }

    @Test
    void twoIngredientsPairUpWhateverTheOrderOfTheCells() {
        List<FluidNeeds.Need> both = List.of(need(500, "x:a", "x:b"), need(500, "x:a"));
        // The first ingredient must not grab x:a when the second can only take x:a.
        assertTrue(FluidNeeds.fit(both, List.of(new FluidCell("x:a", 500), new FluidCell("x:b", 500))));
        assertTrue(FluidNeeds.fit(both, List.of(new FluidCell("x:b", 500), new FluidCell("x:a", 500))));
        assertFalse(FluidNeeds.fit(both, List.of(new FluidCell("x:b", 500), new FluidCell("x:c", 500))));
    }

    @Test
    void oneCellNeverPaysTwoIngredients() {
        List<FluidNeeds.Need> twice = List.of(need(250, "x:a"), need(250, "x:a"));
        assertFalse(FluidNeeds.fit(twice, List.of(new FluidCell("x:a", 250))), "two ingredients, one cell");
    }

    @Test
    void moreThanTwoIngredientsNeverFit() {
        List<FluidNeeds.Need> three = List.of(need(1, "x:a"), need(1, "x:b"), need(1, "x:c"));
        assertFalse(FluidNeeds.fit(three, List.of(new FluidCell("x:a", 1), new FluidCell("x:b", 1))));
    }

    @Test
    void aNeedHasAtLeastOneMillibucket() {
        assertThrows(IllegalArgumentException.class, () -> new FluidNeeds.Need(fluid -> true, 0));
    }
}
