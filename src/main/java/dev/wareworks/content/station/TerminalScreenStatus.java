package dev.wareworks.content.station;

import java.util.Objects;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.crane.CranePhase;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The bounded form of {@link TerminalStatus} a terminal sends to an open screen ({@code docs/warehouse-system.md}
 * §3.4.2, ADR-019): numbers, one character and three enum ordinals — never an item, a stack or a component.
 * <p>
 * The screen shows the aisle's totals, this terminal's open requests and the crane's line ("working", "paused: no
 * rotation"), so only the crane's phase and pause reason are taken from {@code CraneGoggleInfo}; its job and held items
 * stay on the server. Reading never throws: unknown ordinals fall back to the first value.
 *
 * @param hasAisle         whether the terminal is an aligned member of an aisle with a loaded controller
 * @param controllerStatus the controller's link status
 * @param aisleLetter      the aisle letter used in addresses
 * @param itemTypes        distinct item types in stock
 * @param totalItems       items stored over all storage locations
 * @param openRequests     open retrieval requests of the whole aisle
 * @param requestsHere     open retrieval requests whose destination is this terminal
 * @param requestedHere    items those requests still wait for
 * @param deliveredHere    items of those requests that already arrived in this terminal's buffer
 * @param craneLinked      whether a loaded, linked stacker crane reported its state
 * @param cranePhase       the crane's phase
 * @param cranePause       why the crane is paused ({@link CranePauseReason#NONE} if it is not)
 */
public record TerminalScreenStatus(boolean hasAisle, ControllerStatus controllerStatus, char aisleLetter, int itemTypes,
                                   long totalItems, int openRequests, int requestsHere, long requestedHere,
                                   long deliveredHere, boolean craneLinked, CranePhase cranePhase,
                                   CranePauseReason cranePause) {
    /** No aisle: the terminal stands outside one, is misaligned, or its controller is not loaded. */
    public static final TerminalScreenStatus NONE = new TerminalScreenStatus(false, ControllerStatus.NO_DOCK,
            StorageAddress.FIRST_AISLE, 0, 0L, 0, 0, 0L, 0L, false, CranePhase.IDLE, CranePauseReason.NONE);

    public TerminalScreenStatus {
        controllerStatus = controllerStatus == null ? ControllerStatus.NO_DOCK : controllerStatus;
        cranePhase = cranePhase == null ? CranePhase.IDLE : cranePhase;
        cranePause = cranePause == null ? CranePauseReason.NONE : cranePause;
        if (!StorageAddress.isValidAisle(aisleLetter))
            aisleLetter = StorageAddress.FIRST_AISLE;
        itemTypes = Math.max(0, itemTypes);
        totalItems = Math.max(0L, totalItems);
        openRequests = Math.max(0, openRequests);
        requestsHere = Math.max(0, requestsHere);
        requestedHere = Math.max(0L, requestedHere);
        deliveredHere = Math.max(0L, deliveredHere);
    }

    /** The screen form of a terminal's {@link TerminalStatus}. */
    public static TerminalScreenStatus of(TerminalStatus status) {
        Objects.requireNonNull(status, "status");
        return new TerminalScreenStatus(status.hasController(), status.controllerStatus(), status.aisleLetter(),
                status.itemTypes(), status.totalItems(), status.openRequests(), status.requestsHere(),
                status.requestedHere(), status.deliveredHere(), status.crane().isPresent(),
                status.crane().map(crane -> crane.phase()).orElse(CranePhase.IDLE),
                status.crane().map(crane -> crane.pauseReason()).orElse(CranePauseReason.NONE));
    }

    /** Whether the aisle is complete enough to serve requests. */
    public boolean isReady() {
        return hasAisle && controllerStatus == ControllerStatus.READY;
    }

    /** Whether the crane is paused for a reason the screen should name. */
    public boolean isPaused() {
        return craneLinked && cranePause != CranePauseReason.NONE;
    }

    /** Whether the crane is carrying out a job right now. */
    public boolean isWorking() {
        return craneLinked && cranePhase != CranePhase.IDLE;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(hasAisle);
        buffer.writeVarInt(controllerStatus.ordinal());
        buffer.writeVarInt(aisleLetter);
        buffer.writeVarInt(itemTypes);
        buffer.writeVarLong(totalItems);
        buffer.writeVarInt(openRequests);
        buffer.writeVarInt(requestsHere);
        buffer.writeVarLong(requestedHere);
        buffer.writeVarLong(deliveredHere);
        buffer.writeBoolean(craneLinked);
        buffer.writeVarInt(cranePhase.ordinal());
        buffer.writeVarInt(cranePause.ordinal());
    }

    /** Reads a status written by {@link #write}; unknown ordinals and out-of-range values fall back to defaults. */
    public static TerminalScreenStatus read(FriendlyByteBuf buffer) {
        boolean hasAisle = buffer.readBoolean();
        ControllerStatus controllerStatus = byOrdinal(ControllerStatus.values(), buffer.readVarInt(),
                ControllerStatus.NO_DOCK);
        char aisleLetter = (char) buffer.readVarInt();
        int itemTypes = buffer.readVarInt();
        long totalItems = buffer.readVarLong();
        int openRequests = buffer.readVarInt();
        int requestsHere = buffer.readVarInt();
        long requestedHere = buffer.readVarLong();
        long deliveredHere = buffer.readVarLong();
        boolean craneLinked = buffer.readBoolean();
        CranePhase phase = byOrdinal(CranePhase.values(), buffer.readVarInt(), CranePhase.IDLE);
        CranePauseReason pause = byOrdinal(CranePauseReason.values(), buffer.readVarInt(), CranePauseReason.NONE);
        return new TerminalScreenStatus(hasAisle, controllerStatus, aisleLetter, itemTypes, totalItems, openRequests,
                requestsHere, requestedHere, deliveredHere, craneLinked, phase, pause);
    }

    private static <E> E byOrdinal(E[] values, int ordinal, E fallback) {
        return ordinal < 0 || ordinal >= values.length ? fallback : values[ordinal];
    }
}
