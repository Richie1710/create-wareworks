package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The crafting recipe check of a pattern while a Crafter Feeder is attached (issue #34, ADR-067): the ordinal is the
 * wire format of the station screen, and which checks keep a pattern from the planner.
 */
class CrafterPatternCheckTest {
    @Test
    void theWireOrdinalsAreStable() {
        assertEquals(List.of(CrafterPatternCheck.NOT_CHECKED, CrafterPatternCheck.INCOMPLETE, CrafterPatternCheck.VALID,
                CrafterPatternCheck.CELL_NOT_ONE, CrafterPatternCheck.NOT_A_RECIPE, CrafterPatternCheck.RESULT_DIFFERS),
                List.of(CrafterPatternCheck.values()), "append new checks, never insert them");
        for (CrafterPatternCheck check : CrafterPatternCheck.values())
            assertEquals(check, CrafterPatternCheck.byOrdinal(check.ordinal()));
    }

    @Test
    void anUnknownOrdinalIsNotChecked() {
        assertEquals(CrafterPatternCheck.NOT_CHECKED, CrafterPatternCheck.byOrdinal(-1));
        assertEquals(CrafterPatternCheck.NOT_CHECKED, CrafterPatternCheck.byOrdinal(99));
    }

    @Test
    void onlyACompleteButWrongPatternIsRefused() {
        for (CrafterPatternCheck check : List.of(CrafterPatternCheck.CELL_NOT_ONE, CrafterPatternCheck.NOT_A_RECIPE,
                CrafterPatternCheck.RESULT_DIFFERS)) {
            assertTrue(check.refused(), check.name());
            assertFalse(check.usable(), check.name());
        }
        assertFalse(CrafterPatternCheck.INCOMPLETE.refused(), "a draft is not red, it is simply no pattern yet");
        assertTrue(CrafterPatternCheck.NOT_CHECKED.usable(), "without a feeder every pattern is used as it always was");
        assertTrue(CrafterPatternCheck.VALID.usable());
    }

    @Test
    void feederStatusesKeepTheirSyncNames() {
        for (FeederStatus status : FeederStatus.values())
            assertEquals(status, FeederStatus.byName(status.name()).orElseThrow());
        assertTrue(FeederStatus.byName("NO_SUCH_STATUS").isEmpty());
        assertFalse(FeederStatus.IDLE.blocked());
        assertFalse(FeederStatus.FEEDING.blocked());
        assertTrue(FeederStatus.OUTPUT_FULL.blocked());
        assertEquals("gui.crafter_feeder.status.no_crafter", FeederStatus.NO_CRAFTER.langKey());
    }
}
