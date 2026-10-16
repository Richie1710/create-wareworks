package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.core.port.DoorFacts;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.port.PackagerSignAddress;
import dev.wareworks.core.port.PortDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Whether a position is a <b>package door</b> of a warehouse, and how ready it is (M27, issue #18, ADR-065 D1): the one
 * place that reads it, for the door rows of a terminal's screen ({@code DoorDirectory}) and for the check of a door
 * click ({@link WarehouseTerminalBlockEntity#requestFromTerminal}) alike, so the list a player picks from and the answer
 * the server gives can never disagree.
 * <p>
 * A door is a <b>requesting</b> warehouse port of the controller's own warehouse, loaded, with a plain Create Packager
 * whose back touches it — the M26 out door ({@link PackageHandover}). A terminal is never a door (it is "here"), nor is an
 * accepting port (an accepting port must never receive items somebody requested), a collecting port (it takes no
 * delivery), a port without a Packager or with a Repackager, or a port of another warehouse.
 * <p>
 * <b>Only reads, never loads.</b> The membership check stops at an unloaded position before any block entity is asked
 * for ({@code isRequestDestination}), and every neighbour read of {@link PackageHandover} is guarded by
 * {@code isLoaded}, so a look can never pull in a chunk. It moves nothing, changes nothing in the world and reads
 * nothing of Create's but block states, block entities, the signs' text and a Packager's held box and tray, and it
 * calls none of Create's actions: Wareworks never packs, triggers or addresses a box (ADR-065 D3). The one thing a
 * look leaves behind is in the port's own transient memory of how long a box has waited at its Packager
 * ({@link WarehouseOutputBlockEntity#boxWaitingShown}), which is what makes a waiting box a fact at all.
 * <p>
 * Server-safe: no client class is named. The address is read off the signs unfiltered, exactly as Create reads it, and
 * is display-only — it is never matched, saved or trusted.
 */
public final class PackageDoors {
    /** The look at a position that is no door. */
    private static final Sighting NO_DOOR = new Sighting(DoorFacts.NONE, PackagerSignAddress.NONE);

    private PackageDoors() {
    }

    /**
     * One look at a position.
     *
     * @param facts   what was read, and with it whether the position is a door and how it stands
     * @param address the sign address at its Packager, uncut; {@link PackagerSignAddress#NONE} when there is none or the
     *                position is no door
     */
    public record Sighting(DoorFacts facts, String address) {
        public Sighting {
            Objects.requireNonNull(facts, "facts");
            address = address == null ? PackagerSignAddress.NONE : address;
        }

        /** How the door stands; empty when the position is no door. */
        public Optional<DoorState> state() {
            return facts.state();
        }

        /** Whether the position is a door, whatever its state. */
        public boolean isDoor() {
            return facts.isDoor();
        }
    }

    /**
     * Looks at {@code pos} as a door of {@code controller}'s warehouse.
     * <p>
     * The reads stop as soon as the answer is "no door": a position that is no request destination costs the
     * controller's own check and nothing else, a terminal one block entity more. A door costs its Packager's six
     * neighbour states, up to six signs, the Packager's block entity and the port's slots in memory.
     *
     * @return the look; {@link Sighting#isDoor()} is false for everything that is no door, including a {@code null}
     *         argument
     */
    public static Sighting look(Level level, WarehouseControllerBlockEntity controller, BlockPos pos) {
        if (level == null || level.isClientSide || controller == null || pos == null)
            return NO_DOOR;
        // Loaded, aligned, an output member of this warehouse and, for a port, requesting (G-dest, ADR-065 D4). The
        // check refuses an unloaded position before anything is read there.
        if (!controller.isRequestDestination(pos))
            return NO_DOOR;
        if (!(level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port) || port.isRemoved()
                || port.portDirection() != PortDirection.REQUEST)
            return new Sighting(new DoorFacts(true, false, false, false, false, false, false), PackagerSignAddress.NONE);
        Optional<BlockPos> packager = PackageHandover.packagerFor(level, pos);
        if (packager.isEmpty())
            return new Sighting(new DoorFacts(true, true, false, false, false, false, false), PackagerSignAddress.NONE);
        boolean linked = PackageHandover.ignoresRedstone(level, packager.get());
        // Read whatever the link says: a linked door still has a name on its row, and a red row with a name tells the
        // player which Packager to look at.
        String address = PackageHandover.addressAt(level, packager.get());
        // A box at rest in the Packager that has not been taken for ten seconds: this look is one of the port's looks
        // at it (ADR-065 D14), so a door row turns gold at a build that still sees the same box that long after an
        // earlier one.
        boolean boxWaiting = port.boxWaitsAt(packager.get(), level.getGameTime());
        return new Sighting(new DoorFacts(true, true, true, linked, !address.isEmpty(), !port.hasEmptySlot(),
                boxWaiting), address);
    }

    /** {@link #look} without the address: what was read about {@code pos}. */
    public static DoorFacts facts(Level level, WarehouseControllerBlockEntity controller, BlockPos pos) {
        return look(level, controller, pos).facts();
    }

    /**
     * Why a click may not deliver to {@code door}, or empty when it may (G-door, ADR-065 D4, D6, D7):
     * {@link RequestRejection#NOT_A_DOOR} for everything that is no door, {@link RequestRejection#DOOR_IGNORES_REDSTONE}
     * for a door whose Packager carries a Stock Link. A door without a sign, or with a full port, may be chosen: the
     * box leaves unaddressed, or the order waits, and the crane stays free either way.
     * <p>
     * It is the same rule the door rows are built by ({@link DoorState#selectable()} over {@link #look}), except that
     * a door that is not loaded is simply no door here: a click must be checked against the world as it is.
     */
    public static Optional<RequestRejection> refusal(Level level, WarehouseControllerBlockEntity controller,
            BlockPos door) {
        Optional<DoorState> state = look(level, controller, door).state();
        if (state.isEmpty())
            return Optional.of(RequestRejection.NOT_A_DOOR);
        if (state.get() == DoorState.IGNORES_REDSTONE)
            return Optional.of(RequestRejection.DOOR_IGNORES_REDSTONE);
        // A live look never answers "not loaded" (an unloaded position is no request destination), so this is only the
        // guard that keeps every unselectable state a refusal.
        if (!state.get().selectable())
            return Optional.of(RequestRejection.NOT_A_DOOR);
        return Optional.empty();
    }
}
