package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.crane.CraneMembership;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.CraneTrafficClaim;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.traffic.DockSite;
import dev.wareworks.core.traffic.TrackGraph;
import dev.wareworks.core.traffic.TrafficCrane;
import dev.wareworks.core.traffic.TrafficHealth;
import dev.wareworks.core.traffic.TrafficLedger;
import dev.wareworks.core.traffic.TrafficVerdict;
import dev.wareworks.core.traffic.TrafficWant;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.util.Headings;
import dev.wareworks.util.LogThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The traffic of a warehouse with several stacker cranes, owned by its controller (issue #14, ADR-075,
 * {@code docs/stacker-crane.md} §4.8): the {@link TrackGraph} of its rails with every crane's dock marked, and the
 * {@link TrafficLedger} that decides which crane may leave the blocks it stands on.
 * <p>
 * <b>Inactive for one crane.</b> A warehouse with a single crane never configures a ledger, never runs a pass and is
 * never asked anything here, which is what keeps every warehouse built up to 0.17.1 exactly as it was.
 * <p>
 * <b>One pass per game tick, at the first access.</b> The controller's tick and every crane's tick call
 * {@link #ensurePass} before they change anything, so the pass in tick {@code t} reads every crane as it stood at the
 * end of tick {@code t - 1}, whichever block entity ticks first. A crane is read straight from its block entity while
 * its chunk ticks; otherwise its last known pose and claim stand in for it, as an obstacle that cannot move
 * ({@link TrafficHealth#UNLOADED}).
 * <p>
 * <b>Never saved itself.</b> The ledger is rebuilt from the cranes' own claims ({@link CraneTrafficClaim}) and, for
 * cranes whose chunk is not loaded, from the last claims this class knew, which the controller saves
 * ({@link ControllerPersistence#writeCranes}). A rebuild after the rails or the cranes changed drops every committed
 * route — a route in an old numbering means nothing — and the cranes ask again from where they stand.
 */
public final class CraneTraffic {
    /** The last thing known of one crane: where it stood and what it held, at the last pass that read it. */
    record Known(int branch, double x, CraneTrafficClaim claim) {
        Known {
            Objects.requireNonNull(claim, "claim");
        }
    }

    private final WarehouseControllerBlockEntity controller;
    private List<CraneMember> members = List.of();
    @Nullable
    private WarehouseLayout layout;
    @Nullable
    private TrackGraph graph;
    @Nullable
    private TrafficLedger ledger;
    private double ledgerPenalty = Double.NaN;
    /**
     * Per crane number: the home point it parks at ({@code HomeAssignment}). Kept as rack positions and turned into
     * cells of the graph in hand when a crane is read, so a graph rebuilt in between can never be handed a cell of the
     * one before it.
     */
    private Map<Integer, RackPosition> homes = Map.of();
    /**
     * Per dock: its crane's last known pose and claim, for the save and for a crane whose chunk is away. Keyed by the
     * dock block rather than the crane number, because numbers follow the discovery order and a crane leaving renumbers
     * the ones after it.
     */
    private final Map<BlockPos, Known> known = new HashMap<>();
    /** Whether the next ledger restores the cranes' committed routes (after a load) or drops them (after a change). */
    private boolean keepClaims = true;
    /** Repaired overlaps are worth a warning, but not one per tick. */
    private final LogThrottle repairLog = new LogThrottle();
    private long repairsLogged;

    CraneTraffic(WarehouseControllerBlockEntity controller) {
        this.controller = controller;
    }

    // --- configuration -------------------------------------------------------------------------------------------

    /** Whether this warehouse has more than one crane, i.e. whether anything here runs at all. */
    public boolean active() {
        return members.size() > 1 && layout != null;
    }

    /** The cranes, crane 1 first; empty without a warehouse, one entry for a warehouse of one crane. */
    List<CraneMember> members() {
        return members;
    }

    /** The crane with number {@code number}, if this warehouse has it. */
    Optional<CraneMember> member(int number) {
        return number >= 1 && number <= members.size() ? Optional.of(members.get(number - 1)) : Optional.empty();
    }

    /**
     * The number the controller lists the dock at {@code dock} under, or empty if it lists no crane there. A crane asks
     * this rather than trusting its own saved number, which is out of date when a crane before it left while its chunk
     * was away.
     */
    public OptionalInt numberOf(BlockPos dock) {
        for (CraneMember member : members) {
            if (member.dock().equals(dock))
                return OptionalInt.of(member.number());
        }
        return OptionalInt.empty();
    }

    /**
     * Takes in the warehouse and its cranes. Nothing is rebuilt when neither changed; otherwise the next access builds
     * a new ledger, which keeps the committed routes only before the first ledger after a load.
     */
    void configure(@Nullable WarehouseLayout next, List<CraneMember> cranes) {
        Objects.requireNonNull(cranes, "cranes");
        // Only the rails count: an aisle letter changed under the same rails changes no cell and no route.
        boolean shapeChanged = layout == null || next == null ? layout != next
                : !layout.dock().equals(next.dock()) || layout.facing() != next.facing()
                        || !layout.network().equals(next.network());
        if (!shapeChanged && members.equals(cranes)) {
            layout = next;
            return;
        }
        if (ledger != null)
            keepClaims = false; // the shape or the cranes changed under a running ledger: every route starts afresh
        layout = next;
        members = List.copyOf(cranes);
        known.keySet().retainAll(docks());
        graph = null;
        ledger = null;
    }

    /** Restores the last known claims of the save, before the first ledger is built. */
    void restoreKnown(Map<BlockPos, Known> saved) {
        known.clear();
        known.putAll(saved);
        keepClaims = true;
        ledger = null;
    }

    /** The last known pose and claim of every crane, for the save. */
    Map<BlockPos, Known> known() {
        return Map.copyOf(known);
    }

    /** The home point each crane parks at, by crane number; a crane without one parks on its dock. */
    void setHomes(Map<Integer, RackPosition> paired) {
        homes = Map.copyOf(paired);
    }

    /** The track cell of crane {@code number}'s home point, or {@link TrackGraph#NONE} without one. */
    private int homeCell(int number) {
        TrackGraph shape = graph();
        RackPosition home = homes.get(number);
        return shape == null || home == null ? TrackGraph.NONE : shape.cellAt(home.branch(), home.x());
    }

    /** Forgets everything: the warehouse is gone. */
    void clear() {
        members = List.of();
        layout = null;
        graph = null;
        ledger = null;
        known.clear();
        homes = Map.of();
        keepClaims = true;
    }

    // --- the graph and the ledger ----------------------------------------------------------------------------------

    /** The rails as cells, with every crane's dock marked; {@code null} while inactive. */
    @Nullable
    TrackGraph graph() {
        if (!active())
            return null;
        if (graph == null)
            graph = TrackGraph.of(layout.network(), dockSites());
        return graph;
    }

    /** The member docks the graph marks, in crane order: every crane but the first that is not stranded. */
    private List<DockSite> dockSites() {
        List<DockSite> sites = new ArrayList<>();
        BlockPos anchor = layout.dock();
        for (CraneMember member : members) {
            if (!marked(member))
                continue;
            // A member dock lies at one end of its branch and faces along it towards the rest: back from the far end,
            // forward from the near one.
            Heading along = Headings.of(layout.branch(member.siteBranch()).heading());
            Heading facing = member.siteX() == 0 ? along : along.opposite();
            sites.add(new DockSite(member.dock().getX() - anchor.getX(), member.dock().getZ() - anchor.getZ(), facing,
                    member.siteBranch(), member.siteX()));
        }
        return sites;
    }

    /**
     * Whether the graph marks {@code member}'s dock: every crane but the first whose dock is an aisle block of the
     * warehouse. A stranded crane's dock is not, and neither is one a save named on an aisle the warehouse lost.
     */
    private boolean marked(CraneMember member) {
        return member.number() > 1 && !member.stranded() && member.siteBranch() < layout.branchCount()
                && member.siteX() <= layout.branch(member.siteBranch()).geometry().length();
    }

    /** The track cell of crane {@code number}'s own dock, or {@link TrackGraph#NONE} for a stranded one. */
    int dockCell(int number) {
        TrackGraph shape = graph();
        if (shape == null)
            return TrackGraph.NONE;
        if (number == 1)
            return shape.primaryDock();
        int index = 1;
        for (CraneMember member : members) {
            if (!marked(member))
                continue;
            if (member.number() == number)
                return shape.docks()[index];
            index++;
        }
        return TrackGraph.NONE;
    }

    /** The ledger, built on first use after a change; {@code null} while inactive. */
    @Nullable
    private TrafficLedger ledger() {
        TrackGraph shape = graph();
        if (shape == null)
            return null;
        double penalty = Math.max(0.0, WareworksConfig.turnPenaltyBlocks());
        if (ledger != null && penalty == ledgerPenalty)
            return ledger;
        TrafficLedger built = new TrafficLedger(shape, layout.routes(), penalty);
        // Every crane's blocks first, then the routes in crane order: a route that crosses any other crane's blocks, or
        // a route restored before it, is the one dropped.
        List<TrafficLedger.Saved> saved = new ArrayList<>(members.size());
        for (CraneMember member : members) {
            CraneTrafficClaim claim = claimOf(member);
            boolean keep = keepClaims && claim.committed();
            saved.add(new TrafficLedger.Saved(input(member), keep ? claim.route() : Optional.empty(),
                    claim.stopBranch(), claim.stopX(), keep ? claim.forCrane() : 0, claim.since()));
        }
        built.restoreAll(saved);
        keepClaims = true;
        ledger = built;
        ledgerPenalty = penalty;
        return built;
    }

    /**
     * The pass of game tick {@code now}, unless one already ran in it. Called by the controller's tick and by each
     * crane's tick before it moves.
     */
    public void ensurePass(long now) {
        TrafficLedger current = ledger();
        if (current == null)
            return;
        if (current.lastPassTick() == now)
            return;
        current.ensurePass(now, this::inputs);
        boolean dirty = false;
        for (CraneMember member : members) {
            CraneTrafficClaim claim = claimFromLedger(current, member.number());
            Optional<StackerCraneBlockEntity> crane = loaded(member);
            Known before = known.get(member.dock());
            Known after;
            if (crane.isPresent()) {
                CranePose pose = crane.get().craneState().pose();
                after = new Known(pose.branch(), pose.x(), claim);
            } else if (before != null) {
                // Its last pose stays; its claim is the ledger's, so a route a rebuild dropped is never saved again.
                after = new Known(before.branch(), before.x(), claim);
            } else {
                continue;
            }
            known.put(member.dock(), after);
            dirty |= differs(before, after);
        }
        // The controller saves these, so a change a player could ever see after a restart marks its chunk unsaved: the
        // blocks a crane stands on or what it claims, never a fraction of a block.
        if (dirty)
            controller.setChanged();
        if (current.repairs() != repairsLogged) {
            repairsLogged = current.repairs();
            if (repairLog.tryLog(now))
                Wareworks.LOGGER.warn("Warehouse at {}: a crane stood on rails another crane had reserved; that "
                        + "reservation was dropped and its crane asks again", controller.getBlockPos());
        }
    }

    /**
     * Where crane {@code dock} stands after its motion of this tick, while it is in this warehouse's traffic. Recorded
     * at the end of the crane's tick, so the pose a chunk unload leaves behind is the pose it really stopped at, not
     * the one before its last step.
     */
    public void noteStanding(BlockPos dock, CranePose pose) {
        Known before = known.get(dock);
        if (before == null || !numberOf(dock).isPresent())
            return;
        Known after = new Known(pose.branch(), pose.x(), before.claim());
        known.put(dock, after);
        if (differs(before, after))
            controller.setChanged();
    }

    /** Whether two records of one crane differ in anything a save restores: its blocks or its claim. */
    private boolean differs(@Nullable Known before, Known after) {
        if (before == null || !before.claim().equals(after.claim()))
            return true;
        TrackGraph shape = graph();
        if (shape == null)
            return before.branch() != after.branch() || before.x() != after.x();
        return !Arrays.equals(shape.standingCells(before.branch(), before.x()),
                shape.standingCells(after.branch(), after.x()));
    }

    /** What the ledger holds for crane {@code number} right now, as the crane saves it. */
    public CraneTrafficClaim claim(int number) {
        TrafficLedger current = ledger();
        return current == null ? CraneTrafficClaim.NONE : claimFromLedger(current, number);
    }

    private static CraneTrafficClaim claimFromLedger(TrafficLedger ledger, int number) {
        OptionalLong since = ledger.since(number);
        return ledger.commitment(number)
                .map(held -> new CraneTrafficClaim(Optional.of(held.route()), held.stopBranch(), held.stopX(),
                        held.forCrane(), since))
                .orElseGet(() -> new CraneTrafficClaim(Optional.empty(), 0, 0, 0, since));
    }

    /** What the last pass told crane {@code number}. */
    public TrafficVerdict verdict(int number) {
        TrafficLedger current = ledger();
        return current == null ? TrafficVerdict.CLEAR : current.verdict(number);
    }

    /** What crane {@code number} may do this tick ({@link TrafficLedger#gate}). */
    public TrafficLedger.Gate gate(int number, CranePose pose, CranePose target, @Nullable CraneRoute motionRoute) {
        TrafficLedger current = ledger();
        if (current == null)
            return TrafficLedger.Gate.NORMAL;
        return current.gate(number, pose.branch(), pose.x(), pose.arm() <= CranePose.RETRACTED, target.branch(),
                target.x(), motionRoute);
    }

    /** The way from where crane {@code number} stands to its committed stop, inside its claim. */
    public Optional<CraneRoute> detourRoute(int number, CranePose pose) {
        TrafficLedger current = ledger();
        return current == null ? Optional.empty() : current.detourRoute(number, pose.branch(), pose.x());
    }

    /** Whether crane {@code number} is stranded: a closed rail cut its machine off from its own dock. */
    public boolean stranded(int number) {
        return member(number).map(CraneMember::stranded).orElse(false);
    }

    /**
     * The branch and position of a track cell as a player reads it — its first name, for a link block that lies on two
     * aisles — or empty for {@link TrackGraph#NONE} and a cell the current graph does not have (goggles, issue #14).
     */
    public Optional<int[]> railOf(int cell) {
        TrackGraph shape = graph();
        if (shape == null || cell < 0 || cell >= shape.cellCount())
            return Optional.empty();
        return Optional.of(new int[] {shape.branchOf(cell, 0), shape.xOf(cell, 0)});
    }

    /** The committed route of crane {@code number}, if it has not reached its stop. */
    Optional<TrafficLedger.Commitment> commitment(int number) {
        TrafficLedger current = ledger();
        return current == null ? Optional.empty() : current.commitment(number);
    }

    /**
     * Where crane {@code number} will stand once its committed route is driven: its stop, or the blocks it stands on
     * without one. Empty while inactive.
     */
    int[] settledCells(int number) {
        TrackGraph shape = graph();
        if (shape == null)
            return new int[0];
        Optional<TrafficLedger.Commitment> held = commitment(number);
        if (held.isPresent())
            return new int[] {held.get().stopCell()};
        return standingCells(number);
    }

    /** The blocks crane {@code number} stands on, from its block entity or its last known pose. */
    int[] standingCells(int number) {
        TrackGraph shape = graph();
        Optional<CraneMember> member = member(number);
        if (shape == null || member.isEmpty())
            return new int[0];
        Optional<StackerCraneBlockEntity> crane = loaded(member.get());
        if (crane.isPresent()) {
            CranePose pose = crane.get().craneState().pose();
            return shape.standingCells(pose.branch(), pose.x());
        }
        Known last = known.get(member.get().dock());
        if (last != null)
            return shape.standingCells(last.branch(), last.x());
        return shape.standingCells(member.get().siteBranch(), member.get().siteX());
    }

    // --- reading the cranes ----------------------------------------------------------------------------------------

    private List<TrafficCrane> inputs() {
        List<TrafficCrane> cranes = new ArrayList<>(members.size());
        for (CraneMember member : members)
            cranes.add(input(member));
        return cranes;
    }

    private TrafficCrane input(CraneMember member) {
        int number = member.number();
        int dock = dockCell(number);
        int home = homeCell(number);
        Optional<StackerCraneBlockEntity> loaded = loaded(member);
        if (loaded.isEmpty()) {
            Known last = known.get(member.dock());
            int branch = last != null ? last.branch() : member.siteBranch();
            double x = last != null ? last.x() : member.siteX();
            return new TrafficCrane(number, branch, x, true, TrafficHealth.UNLOADED, Optional.empty(), dock, home);
        }
        StackerCraneBlockEntity crane = loaded.get();
        CraneState<ItemKey, RackPosition> state = crane.craneState();
        CranePose pose = state.pose();
        boolean stopped = state.paused() || crane.pauseReason() != CranePauseReason.NONE
                || crane.currentSpeeds().isStopped();
        Optional<TrafficWant> want = stopped ? Optional.empty()
                : TrafficWant.of(state.phase(), pose, state.target(), state.job().map(TransportJob::target));
        return new TrafficCrane(number, pose.branch(), pose.x(), armIn(state),
                stopped ? TrafficHealth.STOPPED : TrafficHealth.HEALTHY, want, dock, home);
    }

    /**
     * Whether the crane may leave its block this tick without interrupting anything: its arm is in and it is not in
     * the middle of a transfer. Only such a crane is ordered to move aside.
     */
    static boolean armIn(CraneState<?, ?> state) {
        if (state.pose().arm() > CranePose.RETRACTED)
            return false;
        return switch (state.phase()) {
            case EXTEND_SOURCE, PICK, RETRACT_SOURCE, EXTEND_TARGET, DROP, RETRACT_TARGET -> false;
            case IDLE, TRAVEL_TO_SOURCE, TRAVEL_TO_TARGET, COMPLETE, REROUTE, HOLDING, WAITING_FOR_TARGET -> true;
        };
    }

    /** The claim a crane holds as it saved it, or as this class last knew it while its chunk is away. */
    private CraneTrafficClaim claimOf(CraneMember member) {
        Optional<StackerCraneBlockEntity> crane = loaded(member);
        if (crane.isPresent())
            return crane.get().trafficClaim();
        Known last = known.get(member.dock());
        return last == null ? CraneTrafficClaim.NONE : last.claim();
    }

    /**
     * The crane's block entity, while its chunk ticks and it still is this crane of this warehouse. One block entity
     * lookup; never loads a chunk.
     */
    Optional<StackerCraneBlockEntity> loaded(CraneMember member) {
        Level level = controller.getLevel();
        BlockPos pos = member.dock();
        if (level == null || level.isClientSide || !level.isLoaded(pos) || !level.shouldTickBlocksAt(pos))
            return Optional.empty();
        if (!(level.getBlockEntity(pos) instanceof StackerCraneBlockEntity crane) || crane.isRemoved())
            return Optional.empty();
        Optional<CraneMembership> membership = crane.membership();
        if (membership.isEmpty() || membership.get().number() != member.number()
                || !membership.get().controller().equals(controller.getBlockPos()))
            return Optional.empty();
        return Optional.of(crane);
    }

    private List<BlockPos> docks() {
        List<BlockPos> docks = new ArrayList<>(members.size());
        for (CraneMember member : members)
            docks.add(member.dock());
        return docks;
    }
}
