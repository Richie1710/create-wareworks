package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Package mode (issue #40, review fix 2026-11-22): a door delivery no loaded terminal claimed is kept until a terminal's
 * pass claims it, so that pass does not ask for the box again; bounded in age and count.
 */
class PendingDoorCreditsTest {
    private final PendingDoorCredits credits = new PendingDoorCredits();

    @Test
    void aKeptDeliveryIsHandedToTheNextClaimAndForgottenOnceTaken() {
        UUID request = new UUID(0L, 1L);
        credits.remember(request, 1, 100L);
        credits.remember(request, 2, 110L);
        List<String> offered = new ArrayList<>();
        credits.claim((id, amount) -> {
            offered.add(id + ":" + amount);
            return false;
        }, 120L);
        assertEquals(List.of(request + ":3"), offered, "both boxes of the request, as one credit");
        credits.claim((id, amount) -> true, 130L);
        assertTrue(credits.isEmpty(), "a taken credit is forgotten");
    }

    @Test
    void aDeliveryNobodyClaimsAgesOut() {
        credits.remember(new UUID(0L, 2L), 1, 0L);
        credits.claim((id, amount) -> false, PendingDoorCredits.MAX_AGE_TICKS);
        assertTrue(!credits.isEmpty(), "kept within its age");
        credits.claim((id, amount) -> false, PendingDoorCredits.MAX_AGE_TICKS + 1);
        assertTrue(credits.isEmpty(), "and dropped after it");
    }

    @Test
    void theOldestGoesFirstWhenFull() {
        for (int i = 0; i <= PendingDoorCredits.MAX_ENTRIES; i++)
            credits.remember(new UUID(0L, i), 1, 0L);
        List<UUID> kept = new ArrayList<>();
        credits.claim((id, amount) -> kept.add(id) && false, 0L);
        assertEquals(PendingDoorCredits.MAX_ENTRIES, kept.size());
        assertTrue(!kept.contains(new UUID(0L, 0L)), "the oldest went");
    }
}
