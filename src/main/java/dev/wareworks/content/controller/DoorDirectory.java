package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.station.PackageDoors;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.TerminalDoors;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.port.DoorRows;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.port.PackagerSignAddress;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The package doors of one warehouse, as its terminals' screens list them (M27, issue #18, ADR-065 D9): built on
 * demand, never scanned for, never saved.
 * <p>
 * <b>Pulled, not pushed.</b> There is one directory per controller and its only caller is a terminal menu's push
 * ({@code WarehouseTerminalBlockEntity#doorRows}), which runs at most every few ticks per open screen and only for a
 * player who really has that screen open. With no terminal screen open on the warehouse, nothing here ever runs. Every
 * screen of the warehouse shares one build, and a build is reused for {@value #MAX_AGE_TICKS} ticks, so an accepted
 * click that pushes on the very next tick reads the same rows instead of walking the warehouse again.
 * <p>
 * <b>Dirty hooks</b> cost one boolean each and read nothing: a member that changed, a port that was turned, a layout
 * that moved ({@link #markDirty}). A sign edit or a Packager placed or broken fires no warehouse event at all; it shows
 * within the maximum age, the same order of lag as Create's own ten-tick check of a Stock Link. A box that has waited
 * at a door's Packager without being taken ({@link DoorState#BOX_WAITING}) shows at the first build that still sees the
 * same box at least {@value dev.wareworks.core.port.BoxWait#REPORT_AFTER_TICKS} ticks after an earlier one: every look
 * of a build is one of the port's own looks at its Packager ({@code WarehouseOutputBlockEntity#boxWaitingShown}), so
 * the directory keeps no memory of boxes itself.
 * <p>
 * <b>One build</b> walks the warehouse's output records in rack order — in memory, the membership's own list — that
 * can be a door at all: an accepting or a collecting port never is, and the controller's port cache says so without a
 * look (an unread port answers "requesting", so it stays a candidate; a terminal cannot be told apart there, so it
 * stays one too and is refused by its look). It looks at most {@value #MAX_DOOR_CANDIDATES} of them
 * ({@link PackageDoors#look}). A position that is not loaded is never loaded and costs no look: a door seen there
 * earlier is listed again from memory as {@link DoorState#NOT_LOADED}, so a door whose chunk unloaded turns grey
 * instead of vanishing, and anything else is skipped. A build that spends its looks before the last candidate says so
 * ({@link Rows#unchecked()}), and the screen tells the player that more outputs were not checked: doors behind the
 * {@value #MAX_DOOR_CANDIDATES}th looked-at candidate in rack order are neither listed nor counted. The memory is
 * transient and bounded by the candidates; after a restart an unloaded door is unknown until its chunk has been loaded
 * once while a screen was open. At most {@value #MAX_DOOR_ROWS} rows are listed and the rest only counted. The rule
 * itself — bounds, order and memory — is the pure {@link DoorRows}; this class feeds it the warehouse's records and
 * looks.
 * <p>
 * Server thread only. No inventory is read beyond a port's slots in memory, and there is no search radius.
 */
public final class DoorDirectory {
    /** Ticks a build is reused for, unless a hook marked it dirty. */
    public static final int MAX_AGE_TICKS = 20;
    /** Candidates one build looks at, at most; a candidate that is not loaded costs no look. */
    public static final int MAX_DOOR_CANDIDATES = 64;
    /** Rows one build lists, at most; the rest are counted. */
    public static final int MAX_DOOR_ROWS = TerminalDoors.MAX_ROWS;

    private static final long NEVER = Long.MIN_VALUE;

    /**
     * One door as the directory saw it.
     *
     * @param pos         its world position
     * @param rack        its rack position, which orders the rows
     * @param rackAddress its rack address, e.g. {@code "A-12-03R"}
     * @param label       the sign address at its Packager, cut for display
     * @param labelCut    whether the label lost characters
     * @param state       how it stood
     */
    public record DoorRow(BlockPos pos, RackPosition rack, String rackAddress, String label, boolean labelCut,
                          DoorState state) {
        public DoorRow {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(rack, "rack");
            Objects.requireNonNull(state, "state");
            rackAddress = rackAddress == null ? "" : rackAddress;
            label = label == null ? PackagerSignAddress.NONE : label;
        }

        DoorRow withState(DoorState next) {
            return new DoorRow(pos, rack, rackAddress, label, labelCut, next);
        }
    }

    /**
     * The rows of one build.
     *
     * @param rows      at most {@value #MAX_DOOR_ROWS} doors, in rack order
     * @param notListed doors found beyond them
     * @param unchecked whether the build spent its {@value #MAX_DOOR_CANDIDATES} looks before the last candidate, so
     *                  doors further down the warehouse may be neither listed nor counted
     */
    public record Rows(List<DoorRow> rows, int notListed, boolean unchecked) {
        public static final Rows NONE = new Rows(List.of(), 0, false);

        public Rows {
            rows = List.copyOf(rows);
            notListed = Math.max(0, notListed);
        }
    }

    private final WarehouseControllerBlockEntity controller;
    /** The bounded listing and the memory of every door seen at its last look, by rack position. */
    private final DoorRows<RackPosition, DoorRow> listing = new DoorRows<>(MAX_DOOR_CANDIDATES, MAX_DOOR_ROWS);
    private Rows cached = Rows.NONE;
    private boolean dirty = true;
    private long builtAt = NEVER;
    private int builds;

    DoorDirectory(WarehouseControllerBlockEntity controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    /** Something a door depends on changed: the next call builds again. One boolean, nothing read. */
    void markDirty() {
        dirty = true;
    }

    /**
     * The doors as of {@code now}: the last build while it is younger than {@value #MAX_AGE_TICKS} ticks and nothing
     * marked it dirty, otherwise a new one — but never more than one build per tick, whatever marks it dirty.
     */
    public Rows rows(long now) {
        boolean fresh = builtAt != NEVER && now >= builtAt && now - builtAt < MAX_AGE_TICKS;
        if ((fresh && !dirty) || now == builtAt)
            return cached;
        dirty = false;
        builtAt = now;
        builds++;
        cached = build();
        return cached;
    }

    /** How often a build ran: for the GameTests, which pin that nobody looking means nothing built. */
    public int builds() {
        return builds;
    }

    /**
     * The doors as a terminal's screen is told about them: every row of {@link #rows} with what the warehouse's open
     * requests to it still wait for and have delivered, which are read from the request queue in memory on every call
     * and never cached.
     */
    public TerminalDoors terminalRows(long now) {
        Rows built = rows(now);
        if (built.rows().isEmpty())
            return built.notListed() == 0 && !built.unchecked() ? TerminalDoors.NONE
                    : new TerminalDoors(List.of(), built.notListed(), built.unchecked());
        List<TerminalDoorRow> rows = new ArrayList<>(built.rows().size());
        for (DoorRow row : built.rows())
            rows.add(new TerminalDoorRow(row.pos(), row.rackAddress(), row.label(), row.labelCut(), row.state(),
                    controller.requestedFor(row.pos()), controller.deliveredFor(row.pos())));
        return new TerminalDoors(rows, built.notListed(), built.unchecked());
    }

    private Rows build() {
        Level level = controller.getLevel();
        Optional<WarehouseLayout> warehouse = controller.warehouse();
        if (level == null || level.isClientSide || controller.isRemoved() || warehouse.isEmpty()) {
            listing.forget();
            return Rows.NONE;
        }
        WarehouseLayout layout = warehouse.get();
        // The membership's own output records, in rack order: memory, not a walk of the world. An accepting or a
        // collecting port is never a door, and the controller's port cache says so without a look (as
        // CraneDispatch#rerouteOutputs asks it), so neither spends a look of the bound. An unread port answers the
        // request rank and stays a candidate; a terminal answers it too and is refused by its look. The cache follows
        // the world: onPortChanged rereads a turned port and marks this directory dirty, and the look still reads the
        // port's direction itself.
        List<RackPosition> candidates = new ArrayList<>();
        for (LocationRecord record : controller.outputStations()) {
            RackPosition rack = record.position();
            if (record.kind() == LocationKind.OUTPUT && controller.portRankAt(rack) == PortSettings.REQUEST_RANK
                    && !controller.isCollectingPort(rack))
                candidates.add(rack);
        }
        // Never load anything: a door seen earlier stays on the list, greyed out, and nothing else is looked at. A record
        // whose aisle the warehouse no longer has stands for no block, which is no door.
        DoorRows.Built<DoorRow> built = listing.build(candidates,
                rack -> layout.worldPosOf(rack).map(level::isLoaded).orElse(true),
                rack -> layout.worldPosOf(rack).flatMap(pos -> rowAt(level, rack, pos)),
                row -> row.withState(DoorState.NOT_LOADED));
        return new Rows(built.rows(), built.notListed(), built.unchecked());
    }

    /** The row of the door at {@code pos}, or empty when it is none: one {@link PackageDoors#look}. */
    private Optional<DoorRow> rowAt(Level level, RackPosition rack, BlockPos pos) {
        PackageDoors.Sighting sighting = PackageDoors.look(level, controller, pos);
        String address = sighting.address();
        return sighting.state().map(state -> new DoorRow(pos.immutable(), rack,
                controller.stationAddress(rack).orElse(""), PackagerSignAddress.shorten(address),
                PackagerSignAddress.wouldShorten(address), state));
    }
}
