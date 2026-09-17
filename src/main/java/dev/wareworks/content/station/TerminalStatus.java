package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.core.address.StorageAddress;

/**
 * What a warehouse terminal reports about its aisle ({@code docs/warehouse-system.md} §3.4): the controller's link
 * status and totals, this terminal's own open requests, and the crane's current state.
 * <p>
 * Built on demand on the server ({@link WarehouseTerminalBlockEntity#status()}); the screen renders it. Everything in it
 * is a number, an enum name or the already bounded {@link CraneGoggleInfo}, so it stays small whatever the warehouse
 * holds.
 *
 * @param hasController    whether the terminal is an aligned member of an aisle with a loaded controller
 * @param controllerStatus the controller's link status ({@link ControllerStatus#NO_DOCK} without controller)
 * @param aisleLetter      the aisle letter used in addresses
 * @param itemTypes        distinct item types in stock
 * @param totalItems       items stored over all storage locations
 * @param openRequests     open retrieval requests of the whole aisle
 * @param requestsHere     open retrieval requests whose destination is this terminal
 * @param requestedHere    items those requests still wait for
 * @param deliveredHere    items of those requests that already arrived in this terminal's buffer
 * @param crane            the crane's phase, pause reason and job (empty without a loaded, linked dock)
 */
public record TerminalStatus(boolean hasController, ControllerStatus controllerStatus, char aisleLetter, int itemTypes,
                             long totalItems, int openRequests, int requestsHere, long requestedHere,
                             long deliveredHere, Optional<CraneGoggleInfo> crane) {
    /** No aisle: the terminal stands outside one, is misaligned, or its controller is not loaded. */
    public static final TerminalStatus NONE = new TerminalStatus(false, ControllerStatus.NO_DOCK,
            StorageAddress.FIRST_AISLE, 0, 0L, 0, 0, 0L, 0L, Optional.empty());

    public TerminalStatus {
        if (controllerStatus == null)
            controllerStatus = ControllerStatus.NO_DOCK;
        Objects.requireNonNull(crane, "crane");
        if (!StorageAddress.isValidAisle(aisleLetter))
            aisleLetter = StorageAddress.FIRST_AISLE;
        itemTypes = Math.max(0, itemTypes);
        totalItems = Math.max(0L, totalItems);
        openRequests = Math.max(0, openRequests);
        requestsHere = Math.max(0, requestsHere);
        requestedHere = Math.max(0L, requestedHere);
        deliveredHere = Math.max(0L, deliveredHere);
    }

    /** Whether the aisle is complete enough to serve requests ({@link ControllerStatus#READY}). */
    public boolean isReady() {
        return hasController && controllerStatus == ControllerStatus.READY;
    }
}
