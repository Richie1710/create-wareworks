package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The production station's window has to fit vanilla's smallest scaled screen at <b>every</b> configuration the server
 * config allows (buffer 1–27 slots, 1–8 pattern slots), and the safety stop's row has to have somewhere to go in every
 * one of them (M20 review fix).
 * <p>
 * The row is the screen's own way back from the stop, so a configuration without it would leave the screen naming a
 * problem and offering no way out of it — which is exactly what a buffer of 25 slots or more did, because the height
 * budget had spent every order line on buffer rows.
 */
class ProductionMenuLayoutTest {
    /** The whole range {@code productionBufferSlots} and {@code maxProductionPatterns} allow. */
    @Test
    void everyConfigurationFitsTheSmallestScreen() {
        for (int buffer = 1; buffer <= 27; buffer++) {
            for (int patterns = 1; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
                ProductionMenuLayout layout = new ProductionMenuLayout(buffer, patterns);
                assertTrue(layout.height() <= ProductionMenuLayout.MAX_HEIGHT,
                        "buffer " + buffer + ", patterns " + patterns + " needs " + layout.height() + " pixels");
            }
        }
    }

    /** A large buffer really does leave no order line: the configuration the stopped row had to be given a home in. */
    @Test
    void theLargestBuffersLeaveNoOrderLine() {
        assertEquals(0, new ProductionMenuLayout(25, 1).orderLines(), "25 slots are three buffer rows");
        assertEquals(0, new ProductionMenuLayout(27, 8).orderLines());
        assertTrue(new ProductionMenuLayout(24, 8).orderLines() > 0, "two buffer rows still leave a line");
    }

    /** Wherever it goes, the stopped row is a real row of the window and never lands on a slot. */
    @Test
    void theStoppedRowAlwaysHasARow() {
        for (int buffer = 1; buffer <= 27; buffer++) {
            for (int patterns = 1; patterns <= ProductionPatterns.MAX_SLOTS; patterns++) {
                ProductionMenuLayout layout = new ProductionMenuLayout(buffer, patterns);
                int row = layout.stoppedRowY();
                String where = "buffer " + buffer + ", patterns " + patterns;
                assertEquals(layout.orderLines() > 0 ? layout.orderLineY(0) : layout.bufferLabelY(), row,
                        where + ": the row takes the buffer's label only when there is no order line");
                assertTrue(row >= layout.bufferLabelY(), where + ": never above the buffer's label");
                assertTrue(row + ProductionMenuLayout.LABEL_HEIGHT <= layout.playerLabelY()
                        || layout.orderLines() == 0, where + ": never into the player inventory's label");
                assertTrue(row + ProductionMenuLayout.LABEL_HEIGHT <= layout.bufferY() || layout.orderLines() > 0,
                        where + ": and it stays clear of the buffer slots when it takes their label");
            }
        }
    }
}
