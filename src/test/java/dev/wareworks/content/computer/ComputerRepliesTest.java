package dev.wareworks.content.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.core.port.DoorState;

/**
 * The words a computer reads back (issue #9, ADR-074). The lists below are the ones {@code docs/computercraft.md}
 * documents: a new enum constant fails here until the documentation and this test name it.
 */
class ComputerRepliesTest {
    @Test
    void refusalReasonsAreTheDocumentedWords() {
        assertEquals(List.of("no_controller", "no_filter", "not_in_stock", "queue_full", "output_full", "out_of_reach",
                "invalid_amount", "request_full", "production_busy", "reserved", "production_paused", "not_a_door",
                "door_ignores_redstone"),
                Arrays.stream(RequestRejection.values()).map(ComputerReplies::rejection).toList());
    }

    @Test
    void statusWordsAreTheDocumentedWords() {
        assertEquals(List.of("no_dock", "dock_misaligned", "no_rails", "ready"),
                Arrays.stream(ControllerStatus.values()).map(status -> ComputerReplies.status(true, status)).toList());
        for (ControllerStatus status : ControllerStatus.values())
            assertEquals("no_controller", ComputerReplies.status(false, status));
    }

    @Test
    void doorStatesAreTheDocumentedWords() {
        assertEquals(List.of("ready", "no_address", "port_full", "box_waiting", "ignores_redstone", "not_loaded"),
                Arrays.stream(DoorState.values()).map(ComputerReplies::doorState).toList());
    }
}
