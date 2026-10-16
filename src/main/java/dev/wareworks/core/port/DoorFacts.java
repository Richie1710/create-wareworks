package dev.wareworks.core.port;

import java.util.Optional;

/**
 * What was read about one position when it was asked whether it is a <b>package door</b> (M27, issue #18, ADR-065 D1),
 * and the one rule that turns those facts into an answer.
 * <p>
 * A position is a door exactly when the first three facts hold: it is a request destination of the terminal's own
 * warehouse ({@code WarehouseControllerBlockEntity#isRequestDestination}), it is a warehouse port that <b>requests</b>,
 * and a plain Create Packager stands with its back to it. Everything else only says how ready the door is. The same
 * rule decides a door row and the refusal of a door click, so the list a player picks from and the answer the server
 * gives can never disagree.
 * <p>
 * Pure Java, no Minecraft: the block entity reads live in {@code content.station.PackageDoors}, which only fills these
 * in.
 *
 * @param member         a loaded, aligned output member of the warehouse that may be a request's destination
 * @param requestingPort a warehouse port (not a terminal) whose direction is {@link PortDirection#REQUEST}
 * @param packager       a Create Packager, not a Repackager, whose back touches the port
 * @param linked         the Packager carries a Stock Link and ignores redstone
 * @param addressed      a sign at the Packager spells an address
 * @param bufferFull     the port has no empty slot left
 * @param boxWaiting     a box has waited at rest in the Packager, not taken, for at least
 *                       {@value BoxWait#REPORT_AFTER_TICKS} ticks, as far as the looks at the door can tell
 *                       ({@link BoxWait}); a working taker can be that slow, so it is a reason to look at the door,
 *                       not proof that nothing takes the box
 */
public record DoorFacts(boolean member, boolean requestingPort, boolean packager, boolean linked, boolean addressed,
                        boolean bufferFull, boolean boxWaiting) {
    /** The facts of a position that is no door at all. */
    public static final DoorFacts NONE = new DoorFacts(false, false, false, false, false, false, false);

    /**
     * How the door stands, the most actionable cause first; empty when the position is no door. A Stock Link wins over
     * everything else, because nothing else matters while the door never sends; a waiting box comes before a full port,
     * because it is the reason the port is full; a missing sign comes last, because the door still works.
     */
    public Optional<DoorState> state() {
        if (!member || !requestingPort || !packager)
            return Optional.empty();
        if (linked)
            return Optional.of(DoorState.IGNORES_REDSTONE);
        if (boxWaiting)
            return Optional.of(DoorState.BOX_WAITING);
        if (bufferFull)
            return Optional.of(DoorState.PORT_FULL);
        if (!addressed)
            return Optional.of(DoorState.NO_ADDRESS);
        return Optional.of(DoorState.READY);
    }

    /** Whether these are the facts of a door, whatever its state. */
    public boolean isDoor() {
        return member && requestingPort && packager;
    }
}
