package dev.wareworks.content.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The verdicts of the Recipe Selector's pattern check travel to the client by ordinal
 * ({@code ProductionScreenState}), so a constant may only ever be appended: a reorder would show another reason to a
 * client of the same version. Issue #55 appended {@code FLUID_MISMATCH} and {@code FLUID_FAR}.
 */
class SelectorVerdictOrdinalTest {
    @Test
    void theVerdictsKeepTheirOrdinalsAndTheFluidOnesComeLast() {
        assertEquals(List.of("VALID", "NO_RECIPE", "WRONG_COUNT", "CHANCE", "AMBIGUOUS", "STONECUTTING_OFF", "FLUID",
                "FLUID_MISMATCH", "FLUID_FAR"),
                Arrays.stream(SelectorRecipeCheck.Verdict.values()).map(Enum::name).toList());
    }

    @Test
    void everyVerdictHasItsOwnLangKey() {
        assertEquals("gui.production.selector.check.fluid_mismatch",
                SelectorRecipeCheck.Verdict.FLUID_MISMATCH.langKey());
        assertEquals("gui.production.selector.check.fluid_far", SelectorRecipeCheck.Verdict.FLUID_FAR.langKey());
    }
}
