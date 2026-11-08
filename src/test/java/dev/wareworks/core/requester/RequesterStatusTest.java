package dev.wareworks.core.requester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The four statuses about production (issue #45, ADR-081): "being made" is lit, "cannot be made" and "has stopped
 * making" need the player and are red, "busy making other things" passes by itself and is dark; each travels by name,
 * and the made order outranks the stock refusals as the working statuses always did.
 */
class RequesterStatusTest {
    @Test
    void theLampsOfTheStatusesAboutProduction() {
        assertEquals(RequesterStatus.Lamp.LIT, RequesterStatus.MAKING.lamp());
        assertEquals(RequesterStatus.Lamp.REFUSED, RequesterStatus.CANNOT_MAKE.lamp());
        assertEquals(RequesterStatus.Lamp.REFUSED, RequesterStatus.MAKING_STOPPED.lamp());
        assertEquals(RequesterStatus.Lamp.DARK, RequesterStatus.PRODUCTION_BUSY.lamp());
        assertTrue(RequesterStatus.MAKING.isLit());
        assertFalse(RequesterStatus.PRODUCTION_BUSY.isLit() || RequesterStatus.PRODUCTION_BUSY.isRefused());
    }

    @Test
    void theyTravelByName() {
        assertEquals("making", RequesterStatus.MAKING.serializedName());
        assertEquals("cannot_make", RequesterStatus.CANNOT_MAKE.serializedName());
        assertEquals("making_stopped", RequesterStatus.MAKING_STOPPED.serializedName());
        assertEquals("production_busy", RequesterStatus.PRODUCTION_BUSY.serializedName());
        for (RequesterStatus status : new RequesterStatus[] { RequesterStatus.MAKING, RequesterStatus.CANNOT_MAKE,
                RequesterStatus.MAKING_STOPPED, RequesterStatus.PRODUCTION_BUSY })
            assertEquals(Optional.of(status), RequesterStatus.byName(status.serializedName().toUpperCase()));
    }

    @Test
    void theirPrecedence() {
        assertEquals(RequesterStatus.ORDERED, RequesterStatus.first(RequesterStatus.MAKING, RequesterStatus.ORDERED));
        assertEquals(RequesterStatus.MAKING, RequesterStatus.first(RequesterStatus.IN_BUFFER, RequesterStatus.MAKING));
        assertEquals(RequesterStatus.RESERVED,
                RequesterStatus.first(RequesterStatus.CANNOT_MAKE, RequesterStatus.RESERVED));
        assertEquals(RequesterStatus.TERMINAL_BUSY,
                RequesterStatus.first(RequesterStatus.PRODUCTION_BUSY, RequesterStatus.TERMINAL_BUSY));
    }
}
