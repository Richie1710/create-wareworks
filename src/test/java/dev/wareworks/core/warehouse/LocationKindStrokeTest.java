package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.TravelTimeModel;

/** The arm stroke per location kind (issue #39, ADR-072). */
class LocationKindStrokeTest {
    @Test
    void storageAndInputsTakeTheFullStroke() {
        assertEquals(LocationKind.FULL_STROKE, LocationKind.STORAGE.armStroke());
        assertEquals(LocationKind.FULL_STROKE, LocationKind.INPUT.armStroke());
    }

    @Test
    void theTerminalOutputsAndProductionStationsTakeTheStationStroke() {
        assertEquals(LocationKind.STATION_STROKE, LocationKind.OUTPUT.armStroke(),
                "the terminal and every port mode are outputs");
        assertEquals(LocationKind.STATION_STROKE, LocationKind.PRODUCTION.armStroke());
        assertEquals(0.5, LocationKind.STATION_STROKE, "about half a block, the owner's choice");
    }

    @Test
    void everyStrokeIsAValidArmExtension() {
        for (LocationKind kind : LocationKind.values()) {
            double stroke = kind.armStroke();
            assertTrue(stroke > CranePose.RETRACTED && stroke <= CranePose.EXTENDED, kind + ": " + stroke);
            assertEquals(stroke, new CranePose(0, 0, stroke, Side.LEFT).arm());
        }
    }

    @Test
    void theFullStrokeIsTheFullExtension() {
        assertEquals(TravelTimeModel.FULL_EXTENSION, LocationKind.FULL_STROKE);
        assertEquals(CranePose.EXTENDED, LocationKind.FULL_STROKE);
    }
}
