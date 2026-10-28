package dev.wareworks.core.traffic;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.RouteTable;

/**
 * Who may drive where on a warehouse with several cranes: path reservation like a railway's signals, no passing
 * ({@code docs/stacker-crane.md} §4.8, issue #14, ADR-075). One ledger per warehouse, owned by its controller; it never
 * moves a machine and never touches an item — it only decides <b>when</b> a crane may leave the blocks it stands on.
 *
 * <h2>Claims</h2>
 * Every crane holds a <b>claim</b>: the one or two cells its chassis covers, plus — while it holds a grant — every cell
 * of the route to its stop. Two guarantees make the safety theorem, <i>two cranes never share a block</i>:
 * <ul>
 * <li><b>I1</b> claims are pairwise disjoint: a grant adds only cells nobody else claims;</li>
 * <li><b>I2</b> a crane moves only inside its claim: {@link #gate} lets it leave its blocks only along a route inside
 * it.</li>
 * </ul>
 * A crane asks for the <b>whole</b> route to its next stop ({@link TrafficWant}) and gets all of it or nothing. A
 * granted route is <b>committed</b>: the crane keeps it until it stands on the stop, even if its own target changes
 * meanwhile, and keeps driving it ({@link Gate#DETOUR}) until a new want is granted, which swaps the claim in one step.
 *
 * <h2>Progress</h2>
 * A grant is only given if <b>I3</b> still holds with the crane settled at its stop: no crane is cut off from its own
 * dock ({@link EscapeCheck}). Requests are served in {@link TrafficPriority} order; the oldest job request that can ever
 * be granted is the <b>top</b> request, and while it waits, nothing below it may take a rail it needs or stop where it
 * would be in its way ({@link TrafficWait.Reason#GIVING_WAY}). Cranes standing in its way are ordered to <b>move
 * aside</b> with its priority: to their home point the first time, to their own dock the second time — a dead end that
 * is on nobody's path. ADR-075 carries the proof that every job request is then granted after finitely many passes,
 * and why ordering the locks alone could not have done it: a crane always holds the block it stands on.
 *
 * <h2>One pass per tick, independent of tick order</h2>
 * The ledger decides in a {@link #pass}, at most once per game tick ({@link #ensurePass}), at the first access in
 * that tick — the controller's tick or any of its cranes' — before anything moves. So the pass in tick {@code t} reads
 * every crane as it stood at the end of tick {@code t - 1}, and its result does not depend on the order block entities
 * tick in. Cranes are always handled by number, never in the order they are handed in. A pass whose inputs did not
 * change since the last one, and after which nothing changed, is skipped.
 * <p>
 * <b>Unhealthy cranes</b> ({@link TrafficHealth}) keep their claims, so safety holds whatever happens to them, but they
 * ask for nothing and are never ordered aside; a request that would need them waits and says so.
 * <p>
 * Pure Java, not thread-safe (server thread only). Nothing here is saved: the content layer saves each crane's claim
 * (its pose and committed route) and {@link #restore restores} it.
 */
public final class TrafficLedger {
    /** What a crane may do this tick ({@link #gate}). */
    public enum Gate {
        /** An ordinary tick: it stays on its blocks, or drives a route its claim covers. */
        NORMAL,
        /** One motion tick along its committed route towards its committed stop, everything else unchanged. */
        DETOUR,
        /** No motion at all: the tick is sent with stopped speeds. */
        HOLD
    }

    /**
     * A committed route: driven to its end.
     *
     * @param route      the legs from where the crane stood when it was granted
     * @param stopCell   the cell the route ends on
     * @param stopBranch the branch the stop is named on
     * @param stopX      the stop's position on that branch
     * @param forCrane   for a route that moves the crane aside, the crane it moves aside for; otherwise 0
     */
    public record Commitment(CraneRoute route, int stopCell, int stopBranch, int stopX, int forCrane) {
        public Commitment {
            Objects.requireNonNull(route, "route");
        }

        /** Whether this route moves the crane aside for another one. */
        public boolean isYield() {
            return forCrane != 0;
        }
    }

    private static final int NONE = TrackGraph.NONE;

    private final TrackGraph graph;
    private final RouteTable routes;
    private final double turnPenaltyBlocks;
    private final Map<Integer, Slot> slots = new TreeMap<>();
    private long lastPass = Long.MIN_VALUE;
    /** How many passes had to repair a claim that overlapped where a crane really stands ({@link #repairOverlaps}). */
    private long repairs;
    private List<Fingerprint> lastFingerprint = List.of();
    private boolean lastChanged = true;

    // per pass
    private long now;
    @Nullable
    private Slot top;
    private BitSet protectedCells = new BitSet();
    private int topStopCell = NONE;

    /**
     * @param graph             the warehouse's track graph
     * @param routes            the route table of the same shape, which is what the cranes drive by
     * @param turnPenaltyBlocks {@code crane.turnPenaltyBlocks}, the price the cranes' own routes are chosen at
     */
    public TrafficLedger(TrackGraph graph, RouteTable routes, double turnPenaltyBlocks) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.routes = Objects.requireNonNull(routes, "routes");
        if (!Double.isFinite(turnPenaltyBlocks) || turnPenaltyBlocks < 0.0)
            throw new IllegalArgumentException("turnPenaltyBlocks must be finite and not negative: " + turnPenaltyBlocks);
        if (!routes.network().equals(graph.network()))
            throw new IllegalArgumentException("the route table and the track graph describe different shapes");
        this.turnPenaltyBlocks = turnPenaltyBlocks;
    }

    public TrackGraph graph() {
        return graph;
    }

    // --- the pass ------------------------------------------------------------------------------------------------

    /**
     * Runs a pass for {@code gameTime} unless one already ran in that tick: the first access of a tick decides, every
     * later access in the same tick reads its result.
     *
     * @param cranes read only when a pass runs
     * @return whether the pass changed a claim, a commitment or an order
     */
    public boolean ensurePass(long gameTime, Supplier<List<TrafficCrane>> cranes) {
        if (gameTime == lastPass)
            return false;
        return pass(gameTime, cranes.get());
    }

    /**
     * One pass over every crane of the warehouse as it stands now. Cranes missing from {@code cranes} are forgotten
     * (their dock was broken or left the warehouse); cranes seen for the first time claim the blocks they stand on.
     *
     * @return whether the pass changed a claim, a commitment or an order
     */
    public boolean pass(long gameTime, List<TrafficCrane> cranes) {
        Objects.requireNonNull(cranes, "cranes");
        lastPass = gameTime;
        now = gameTime;
        boolean changed = sync(cranes);
        List<Fingerprint> fingerprint = fingerprint();
        if (!changed && !lastChanged && fingerprint.equals(lastFingerprint))
            return false;
        changed |= settle();
        readWants();
        top = chooseTop();
        changed |= dropStaleOrders();
        List<Slot> requests = new ArrayList<>();
        for (Slot slot : slots.values()) {
            if (slot.order != null && !slot.order.granted && !slot.order.done)
                requests.add(slot);
            else if (slot.order == null && slot.waiting)
                requests.add(slot);
        }
        requests.sort(Comparator.comparing(this::priorityOf));
        for (Slot slot : requests)
            changed |= evaluate(slot);
        if (top != null && top.waiting)
            changed |= orderBlockersAside();
        for (Slot slot : slots.values())
            slot.verdict = verdictOf(slot);
        lastFingerprint = fingerprint();
        lastChanged = changed;
        return changed;
    }

    /** Takes in the cranes of this pass, by number; returns whether a crane came or went. */
    private boolean sync(List<TrafficCrane> cranes) {
        boolean changed = false;
        Map<Integer, TrafficCrane> byNumber = new TreeMap<>();
        for (TrafficCrane crane : cranes)
            byNumber.put(crane.number(), crane);
        changed |= slots.keySet().retainAll(byNumber.keySet());
        for (TrafficCrane crane : byNumber.values()) {
            Slot slot = slots.get(crane.number());
            if (slot == null) {
                slot = new Slot(crane.number());
                slots.put(crane.number(), slot);
                changed = true;
            }
            slot.input = crane;
            if (crane.health() != TrafficHealth.UNLOADED || slot.standing == null)
                slot.standing = graph.standingCells(crane.branch(), crane.x());
            slot.health = crane.health();
            if (slot.health == TrafficHealth.HEALTHY && !reachesDock(slot))
                slot.health = TrafficHealth.STRANDED;
            if (slot.claim.isEmpty()) {
                for (int cell : slot.standing)
                    slot.claim.set(cell);
            } else if (slot.health != TrafficHealth.UNLOADED) {
                // A crane always holds the blocks it stands on (I2). Anything else is a repaired load.
                for (int cell : slot.standing)
                    slot.claim.set(cell);
            }
        }
        changed |= repairOverlaps();
        return changed;
    }

    /**
     * Keeps I1 when a crane is found standing where the ledger did not put it: a crane whose chunk was away was last
     * seen one motion step earlier, or a claim was rebuilt from a save that knew less than the machine. The blocks a
     * loaded crane really stands on win: every other crane that claims one of them for its route (not for its own
     * standing) loses that commitment and holds on its own blocks until it is granted again, so nothing drives into the
     * machine. Two machines on one block cannot be repaired here and are left as they are. A healthy pass never gets
     * here, because a crane only ever stands inside its own claim.
     *
     * @return whether a commitment was dropped
     */
    private boolean repairOverlaps() {
        boolean repaired = false;
        for (Slot slot : slots.values()) {
            if (slot.health == TrafficHealth.UNLOADED)
                continue;
            for (int cell : slot.standing) {
                for (Slot other : slots.values()) {
                    if (other == slot || !other.claim.get(cell) || contains(other.standing, cell))
                        continue;
                    other.commitment = null;
                    other.claim.clear();
                    for (int own : other.standing)
                        other.claim.set(own);
                    if (other.order != null)
                        other.order.granted = false;
                    repaired = true;
                }
            }
        }
        if (repaired)
            repairs++;
        return repaired;
    }

    private boolean reachesDock(Slot slot) {
        int dock = slot.input.dockCell();
        if (dock == NONE)
            return false;
        for (int cell : slot.standing) {
            if (graph.connected(cell, dock))
                return true;
        }
        return false;
    }

    /** A crane standing on its committed stop gives up the rest of its route. */
    private boolean settle() {
        boolean changed = false;
        for (Slot slot : slots.values()) {
            if (slot.commitment == null || slot.health == TrafficHealth.UNLOADED
                    || !standsOn(slot.standing, slot.commitment.stopCell()))
                continue;
            if (slot.commitment.isYield() && slot.order != null)
                slot.order.done = true;
            slot.commitment = null;
            slot.claim.clear();
            for (int cell : slot.standing)
                slot.claim.set(cell);
            changed = true;
        }
        return changed;
    }

    /** Resolves every want into a stop cell and keeps how long it has waited. */
    private void readWants() {
        for (Slot slot : slots.values()) {
            slot.waiting = false;
            slot.refusal = null;
            Optional<TrafficWant> want = slot.input.want();
            if (!slot.health.canMove() || want.isEmpty()) {
                slot.wantCell = NONE;
                slot.wantKind = null;
                continue;
            }
            TrafficWant w = want.get();
            int stop = graph.cellAt(w.branch(), w.x());
            if (stop != NONE && contains(slot.standing, stop)) {
                slot.wantCell = NONE;
                slot.wantKind = null;
                continue; // already there: nothing to grant
            }
            if (stop != NONE && slot.commitment != null && slot.commitment.stopCell() == stop) {
                slot.wantCell = stop;
                slot.wantKind = w.kind();
                continue; // granted and on its way
            }
            if (slot.wantCell != stop || slot.wantKind != w.kind() || slot.wantCell == NONE) {
                slot.wantCell = stop;
                slot.wantKind = w.kind();
                if (!slot.sinceRestored)
                    slot.since = now;
            }
            slot.sinceRestored = false;
            slot.waiting = true;
            if (stop == NONE)
                slot.refusal = TrafficWait.of(TrafficWait.Reason.NO_PATH);
        }
    }

    /**
     * The top request: the oldest waiting job request of a healthy crane that can ever be granted — one whose route
     * exists and whose route and escape no crane that cannot move stands on. While it waits, everything below it gives
     * way to it.
     */
    private @Nullable Slot chooseTop() {
        protectedCells = new BitSet();
        topStopCell = NONE;
        Slot best = null;
        BitSet bestProtected = null;
        for (Slot slot : slots.values()) {
            if (!slot.waiting || slot.wantKind != TrafficWant.Kind.JOB || slot.health != TrafficHealth.HEALTHY
                    || slot.order != null || slot.wantCell == NONE)
                continue;
            if (best != null && priorityOf(slot).compareTo(priorityOf(best)) >= 0)
                continue;
            Optional<CraneRoute> route = wantedRoute(slot);
            if (route.isEmpty())
                continue;
            Optional<int[]> escape = graph.path(new int[] {slot.wantCell}, slot.input.dockCell(), null, null);
            if (escape.isEmpty())
                continue;
            BitSet cells = new BitSet();
            BitSet routeCells = new BitSet();
            for (int cell : graph.cellsOf(route.get()))
                routeCells.set(cell);
            for (int cell : slot.standing)
                routeCells.set(cell);
            cells.or(routeCells);
            for (int cell : escape.get())
                cells.set(cell);
            if (blockedForGood(slot, routeCells, cells))
                continue;
            if (cutsOffAnObstacle(slot))
                continue;
            best = slot;
            bestProtected = cells;
        }
        if (best != null) {
            protectedCells = bestProtected;
            topStopCell = best.wantCell;
        }
        return best;
    }

    /** Whether a crane that cannot be ordered aside claims a rail of the route, or stands where the request needs. */
    private boolean blockedForGood(Slot slot, BitSet routeCells, BitSet protectedSet) {
        for (Slot other : slots.values()) {
            if (other == slot || other.health == TrafficHealth.HEALTHY)
                continue;
            if (other.claim.intersects(routeCells))
                return true;
            for (int cell : settled(other)) {
                if (protectedSet.get(cell))
                    return true;
            }
        }
        return false;
    }

    /**
     * Whether the request standing at its stop would cut off a crane that cannot be ordered aside: a stopped or
     * unloaded one, which keeps its escape so that it can go on once it turns again, but cannot make room.
     */
    private boolean cutsOffAnObstacle(Slot slot) {
        List<int[]> settled = settledAll();
        BitSet worse = cutOff(settledWith(slot, new int[] {slot.wantCell}));
        worse.andNot(cutOff(settled));
        int index = 0;
        for (Slot other : slots.values()) {
            if (worse.get(index++) && other != slot && other.health != TrafficHealth.HEALTHY)
                return true;
        }
        return false;
    }

    /** Orders for a request that is no longer the top one end; the cranes that moved aside ask again. */
    private boolean dropStaleOrders() {
        boolean changed = false;
        for (Slot slot : slots.values()) {
            if (slot.yieldCount > 0) {
                // How often a crane moved aside counts per request, for as long as that request waits.
                Slot served = slots.get(slot.yieldTop);
                if (served == null || !served.waiting || served.since != slot.yieldTopSince) {
                    slot.yieldCount = 0;
                    slot.yieldTop = 0;
                    slot.yieldTopSince = 0;
                }
            }
        }
        for (Slot slot : slots.values()) {
            Order order = slot.order;
            if (order == null)
                continue;
            boolean stillTop = top != null && top.number == order.forCrane && top.since == order.topSince;
            if (!stillTop || slot.health != TrafficHealth.HEALTHY) {
                slot.order = null;
                changed = true;
            }
        }
        return changed;
    }

    /** One request: granted whole, or refused with the reason. */
    private boolean evaluate(Slot slot) {
        boolean isYield = slot.order != null;
        CraneRoute route;
        int stopCell;
        int stopBranch;
        int stopX;
        if (isYield) {
            Order order = slot.order;
            stopCell = order.parkCell;
            stopBranch = order.parkBranch;
            stopX = order.parkX;
            BitSet blocked = othersClaims(slot);
            BitSet discouraged = top != null && top != slot ? protectedCells : null;
            Optional<CraneRoute> found = graph.path(slot.standing, stopCell, blocked, discouraged)
                    .flatMap(path -> graph.routeAlong(path, slot.input.branch(), slot.input.x(), stopBranch));
            if (found.isEmpty())
                return refuse(slot, TrafficWait.of(TrafficWait.Reason.NO_PATH));
            route = found.get();
        } else {
            if (slot.wantCell == NONE)
                return refuse(slot, TrafficWait.of(TrafficWait.Reason.NO_PATH));
            TrafficWant want = slot.input.want().orElseThrow();
            stopCell = slot.wantCell;
            stopBranch = want.branch();
            stopX = want.x();
            Optional<CraneRoute> found = wantedRoute(slot);
            if (found.isEmpty())
                return refuse(slot, TrafficWait.of(TrafficWait.Reason.NO_PATH));
            route = found.get();
        }
        int[] routeCells = graph.cellsOf(route);
        // PATH_HELD: the first cell along the way that another crane claims, and whose claim it is.
        for (int cell : routeCells) {
            for (Slot other : slots.values()) {
                if (other != slot && other.claim.get(cell))
                    return refuse(slot, new TrafficWait(heldReason(other), other.number, cell));
            }
        }
        boolean belowTop = !isYield && top != null && top != slot && top.waiting;
        if (belowTop) {
            for (int cell : routeCells) {
                if (!slot.claim.get(cell) && protectedCells.get(cell))
                    return refuse(slot, TrafficWait.of(TrafficWait.Reason.GIVING_WAY, top.number));
            }
        }
        List<int[]> before = settledAll();
        List<int[]> after = settledWith(slot, new int[] {stopCell});
        BitSet cutBefore = cutOff(before);
        BitSet cutAfter = cutOff(after);
        BitSet newlyCut = (BitSet) cutAfter.clone();
        newlyCut.andNot(cutBefore);
        if (!newlyCut.isEmpty())
            return refuse(slot, TrafficWait.of(TrafficWait.Reason.WOULD_CUT_OFF, numberAt(newlyCut.nextSetBit(0))));
        if (belowTop) {
            BitSet blockersNow = blockers(before, cutBefore);
            BitSet blockersAfter = blockers(after, cutAfter);
            blockersAfter.andNot(blockersNow);
            if (!blockersAfter.isEmpty())
                return refuse(slot, TrafficWait.of(TrafficWait.Reason.GIVING_WAY, top.number));
        }
        // Grant: standing cells plus the whole route, the rest of an older route released in the same step.
        slot.claim.clear();
        for (int cell : slot.standing)
            slot.claim.set(cell);
        for (int cell : routeCells)
            slot.claim.set(cell);
        slot.commitment = new Commitment(route, stopCell, stopBranch, stopX, isYield ? slot.order.forCrane : 0);
        if (isYield)
            slot.order.granted = true;
        else
            slot.waiting = false;
        slot.refusal = null;
        return true;
    }

    private boolean refuse(Slot slot, TrafficWait wait) {
        if (slot.order != null)
            slot.order.refusal = wait;
        else
            slot.refusal = wait;
        return false;
    }

    private static TrafficWait.Reason heldReason(Slot holder) {
        return switch (holder.health) {
            case STOPPED -> TrafficWait.Reason.CRANE_STOPPED;
            case UNLOADED -> TrafficWait.Reason.CRANE_UNLOADED;
            case HEALTHY, STRANDED -> TrafficWait.Reason.PATH_HELD;
        };
    }

    /**
     * The cranes in the top request's way are ordered aside, with its priority: every healthy crane with its arm in
     * that is not driving a route of its own and has no unfinished order. Each order is tried at once.
     */
    private boolean orderBlockersAside() {
        List<int[]> settled = settledAll();
        BitSet cut = cutOff(settled);
        BitSet blockers = blockers(settled, cut);
        List<Slot> ordered = new ArrayList<>();
        int index = 0;
        for (Slot slot : slots.values()) {
            int at = index++;
            if (!blockers.get(at) || slot.health != TrafficHealth.HEALTHY || !slot.input.armRetracted())
                continue;
            if (slot.commitment != null)
                continue; // a mover finishes first: it will stand where it was granted to stand
            if (slot.order != null && !slot.order.done)
                continue;
            int park = parkFor(slot, settled, cut);
            if (park == NONE || contains(slot.standing, park))
                continue;
            boolean again = slot.yieldTop == top.number && slot.yieldTopSince == top.since;
            slot.yieldCount = again ? slot.yieldCount + 1 : 1;
            slot.yieldTop = top.number;
            slot.yieldTopSince = top.since;
            slot.order = new Order(top.number, top.since, park, graph.branchOf(park, 0), graph.xOf(park, 0));
            ordered.add(slot);
        }
        boolean changed = !ordered.isEmpty();
        for (Slot slot : ordered)
            changed |= evaluate(slot);
        return changed;
    }

    /**
     * Where a crane in the top request's way goes: its home point if it has one, has not moved aside for this request
     * before, the home point is not on the request's route or escape, and nobody loses an escape by it — otherwise its
     * own dock, a dead end on nobody's path.
     */
    private int parkFor(Slot slot, List<int[]> settled, BitSet cut) {
        int home = slot.input.homeCell();
        boolean yieldedBefore = slot.yieldTop == top.number && slot.yieldTopSince == top.since && slot.yieldCount > 0;
        if (home != NONE && !yieldedBefore && !protectedCells.get(home) && !claimedByOthers(slot, home)
                && reachable(slot, home)) {
            List<int[]> there = settledWith(slot, new int[] {home});
            BitSet cutThere = cutOff(there);
            BitSet worse = (BitSet) cutThere.clone();
            worse.andNot(cut);
            if (worse.isEmpty() && !blockers(there, cutThere).get(indexOf(slot)))
                return home;
        }
        return slot.input.dockCell();
    }

    private boolean reachable(Slot slot, int cell) {
        for (int standing : slot.standing) {
            if (graph.connected(standing, cell))
                return true;
        }
        return false;
    }

    /**
     * The cranes in the top request's way, by index: every other crane settled on its route or escape, and every crane
     * that would lose its escape if the top request stood at its stop.
     */
    private BitSet blockers(List<int[]> settled, BitSet cut) {
        BitSet blockers = new BitSet();
        if (top == null)
            return blockers;
        int topIndex = indexOf(top);
        for (int i = 0; i < settled.size(); i++) {
            if (i == topIndex)
                continue;
            for (int cell : settled.get(i)) {
                if (protectedCells.get(cell)) {
                    blockers.set(i);
                    break;
                }
            }
        }
        List<int[]> topThere = new ArrayList<>(settled);
        topThere.set(topIndex, new int[] {topStopCell});
        BitSet cutThere = cutOff(topThere);
        cutThere.andNot(cut);
        cutThere.clear(topIndex);
        blockers.or(cutThere);
        return blockers;
    }

    private TrafficVerdict verdictOf(Slot slot) {
        Commitment commitment = slot.commitment;
        if (commitment != null) {
            if (commitment.isYield())
                return new TrafficVerdict.Yield(commitment.route(), commitment.stopCell(), commitment.forCrane());
            if (!slot.health.canMove())
                return new TrafficVerdict.Granted(commitment.route(), commitment.stopCell());
            if (slot.order != null)
                return new TrafficVerdict.Detour(commitment.route(), commitment.stopCell(),
                        TrafficWait.of(TrafficWait.Reason.GIVING_WAY, slot.order.forCrane));
            if (slot.waiting)
                return new TrafficVerdict.Detour(commitment.route(), commitment.stopCell(),
                        slot.refusal != null ? slot.refusal : TrafficWait.of(TrafficWait.Reason.NO_PATH));
            return new TrafficVerdict.Granted(commitment.route(), commitment.stopCell());
        }
        if (!slot.health.canMove())
            return TrafficVerdict.CLEAR;
        if (slot.order != null) {
            if (!slot.order.done && slot.order.refusal != null)
                return new TrafficVerdict.Hold(slot.order.refusal);
            return new TrafficVerdict.Hold(TrafficWait.of(TrafficWait.Reason.GIVING_WAY, slot.order.forCrane));
        }
        if (slot.waiting)
            return new TrafficVerdict.Hold(slot.refusal != null ? slot.refusal
                    : TrafficWait.of(TrafficWait.Reason.NO_PATH));
        return TrafficVerdict.CLEAR;
    }

    // --- the gate ------------------------------------------------------------------------------------------------

    /**
     * What crane {@code crane} may do this tick, asked by its dock before it moves (ADR-075 §4.1). A crane only needs
     * a grant to <b>leave</b> the blocks it stands on: extending, transferring, retracting, lifting and turning in its
     * block never wait.
     * <ul>
     * <li>While it is committed to a route whose stop it has not reached, it drives that route: a normal tick if its
     * own target is that stop and the way there lies inside its claim, otherwise a detour tick along the committed
     * route.</li>
     * <li>Without a commitment it may move only within its own blocks; anything else is a hold.</li>
     * <li>An extended arm is always a normal tick, because a machine with its arm out never travels: it retracts
     * first, or it works at the stop it stands on.</li>
     * </ul>
     *
     * @param branch       the branch its pose is named on
     * @param x            its position
     * @param armRetracted whether its arm is fully in
     * @param targetBranch the branch of the pose its phase moves towards
     * @param targetX      that pose's position
     * @param motionRoute  the route its motion would take there ({@code null} on one branch)
     */
    public Gate gate(int crane, int branch, double x, boolean armRetracted, int targetBranch, double targetX,
            @Nullable CraneRoute motionRoute) {
        Slot slot = slots.get(crane);
        if (slot == null)
            return Gate.HOLD;
        int[] standing = graph.standingCells(branch, x);
        int[] targetCells = graph.standingCells(targetBranch, targetX);
        if (!armRetracted)
            return Gate.NORMAL; // a machine with its arm out never travels: it retracts first, or works at its stop
        Commitment commitment = slot.commitment;
        if (commitment != null && !standsOn(standing, commitment.stopCell())) {
            if (targetCells.length == 1 && targetCells[0] == commitment.stopCell()
                    && insideClaim(slot, branch, x, targetX, targetBranch, motionRoute))
                return Gate.NORMAL;
            return Gate.DETOUR;
        }
        if (containsAll(standing, targetCells) && targetCells.length > 0)
            return Gate.NORMAL;
        if (insideClaim(slot, branch, x, targetX, targetBranch, motionRoute))
            return Gate.NORMAL;
        return Gate.HOLD;
    }

    private boolean insideClaim(Slot slot, int branch, double x, double targetX, int targetBranch,
            @Nullable CraneRoute motionRoute) {
        int[] cells;
        if (motionRoute != null) {
            cells = graph.cellsOf(motionRoute);
        } else {
            if (branch != targetBranch || branch < 0 || branch >= graph.network().branchCount())
                return false;
            cells = graph.cellsOf(CraneRoute.straight(branch, x, targetX,
                    graph.network().branch(branch).heading()));
        }
        for (int cell : cells) {
            if (!slot.claim.get(cell))
                return false;
        }
        return true;
    }

    /**
     * The way from where crane {@code crane} stands now to its committed stop, inside its own claim: what a detour
     * tick drives along. Empty without an unreached commitment.
     */
    public Optional<CraneRoute> detourRoute(int crane, int branch, double x) {
        Slot slot = slots.get(crane);
        if (slot == null || slot.commitment == null)
            return Optional.empty();
        int[] standing = graph.standingCells(branch, x);
        if (standsOn(standing, slot.commitment.stopCell()))
            return Optional.empty();
        BitSet outside = new BitSet(graph.cellCount());
        outside.set(0, graph.cellCount());
        outside.andNot(slot.claim);
        int stopBranch = slot.commitment.stopBranch();
        return graph.path(standing, slot.commitment.stopCell(), outside, null)
                .flatMap(path -> graph.routeAlong(path, branch, x, stopBranch));
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    /**
     * Restores what a crane held when it was saved: the blocks it stands on, and the route it was committed to and
     * how long its want had waited. A route that would overlap a claim already restored is dropped (the crane then
     * holds its blocks and asks again), so a save that was edited by hand can never break I1.
     *
     * @param route    the committed route, or empty
     * @param stopX    the committed stop's position on {@code stopBranch} (ignored without a route)
     * @param forCrane the crane the route moved it aside for, or 0
     * @param since    the tick its waiting want was first posted, or empty
     * @return whether the committed route was restored
     */
    public boolean restore(TrafficCrane crane, Optional<CraneRoute> route, int stopBranch, int stopX, int forCrane,
            OptionalLong since) {
        Objects.requireNonNull(crane, "crane");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(since, "since");
        Slot slot = slots.computeIfAbsent(crane.number(), Slot::new);
        slot.input = crane;
        slot.health = crane.health();
        slot.standing = graph.standingCells(crane.branch(), crane.x());
        slot.claim.clear();
        for (int cell : slot.standing)
            slot.claim.set(cell);
        slot.commitment = null;
        if (since.isPresent()) {
            slot.since = since.getAsLong();
            slot.sinceRestored = true;
            crane.want().ifPresent(want -> {
                slot.wantCell = graph.cellAt(want.branch(), want.x());
                slot.wantKind = want.kind();
            });
        }
        lastChanged = true;
        int stopCell = graph.cellAt(stopBranch, stopX);
        if (route.isEmpty() || stopCell == NONE)
            return false;
        int[] cells = graph.cellsOf(route.get());
        for (int cell : cells) {
            for (Slot other : slots.values()) {
                if (other != slot && other.claim.get(cell))
                    return false;
            }
        }
        for (int cell : cells)
            slot.claim.set(cell);
        slot.commitment = new Commitment(route.get(), stopCell, stopBranch, stopX, forCrane);
        return true;
    }

    /**
     * What one crane held when it was saved, for {@link #restoreAll}.
     *
     * @param crane      the crane as it stands now
     * @param route      the committed route, or empty
     * @param stopBranch the committed stop's branch (ignored without a route)
     * @param stopX      the committed stop's position on that branch (ignored without a route)
     * @param forCrane   the crane the route moved it aside for, or 0
     * @param since      the tick its waiting want was first posted, or empty
     */
    public record Saved(TrafficCrane crane, Optional<CraneRoute> route, int stopBranch, int stopX, int forCrane,
                        OptionalLong since) {
        public Saved {
            Objects.requireNonNull(crane, "crane");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(since, "since");
        }
    }

    /**
     * Restores every crane of a save in two steps: first the blocks each one stands on, then the committed routes in
     * crane order. A route is so checked against the standing blocks of <b>every</b> other crane, the later ones
     * included, and one that crosses another crane's blocks or a route restored before it is dropped ({@link #restore}).
     *
     * @return the numbers of the cranes whose committed route was restored
     */
    public List<Integer> restoreAll(List<Saved> saved) {
        Objects.requireNonNull(saved, "saved");
        for (Saved each : saved)
            restore(each.crane(), Optional.empty(), each.stopBranch(), each.stopX(), 0, each.since());
        List<Integer> restored = new ArrayList<>();
        for (Saved each : saved) {
            if (each.route().isPresent() && restore(each.crane(), each.route(), each.stopBranch(), each.stopX(),
                    each.forCrane(), each.since()))
                restored.add(each.crane().number());
        }
        return restored;
    }

    /** Forgets crane {@code crane}: its dock was broken or left the warehouse. */
    public void forget(int crane) {
        if (slots.remove(crane) != null)
            lastChanged = true;
    }

    /** Forgets every crane. */
    public void clear() {
        slots.clear();
        lastChanged = true;
        lastPass = Long.MIN_VALUE;
    }

    // --- queries -------------------------------------------------------------------------------------------------

    /** The cranes this ledger knows, by number. */
    public List<Integer> cranes() {
        return List.copyOf(slots.keySet());
    }

    /** What the last pass told crane {@code crane}; {@link TrafficVerdict#CLEAR} for a crane it does not know. */
    public TrafficVerdict verdict(int crane) {
        Slot slot = slots.get(crane);
        return slot == null ? TrafficVerdict.CLEAR : slot.verdict;
    }

    /** The cells crane {@code crane} claims (a copy; empty for a crane this ledger does not know). */
    public BitSet claim(int crane) {
        Slot slot = slots.get(crane);
        return slot == null ? new BitSet() : (BitSet) slot.claim.clone();
    }

    /** Whether crane {@code crane} claims {@code cell}. */
    public boolean claims(int crane, int cell) {
        Slot slot = slots.get(crane);
        return slot != null && cell >= 0 && slot.claim.get(cell);
    }

    /** The route crane {@code crane} is committed to, if it has not reached its stop yet. */
    public Optional<Commitment> commitment(int crane) {
        Slot slot = slots.get(crane);
        return slot == null ? Optional.empty() : Optional.ofNullable(slot.commitment);
    }

    /** The tick crane {@code crane}'s waiting want was first posted, or empty when it waits for nothing. */
    public OptionalLong since(int crane) {
        Slot slot = slots.get(crane);
        return slot == null || !slot.waiting ? OptionalLong.empty() : OptionalLong.of(slot.since);
    }

    /** The number of the crane whose request is the top one after the last pass, or 0. */
    public int topCrane() {
        return top == null ? 0 : top.number;
    }

    /** The tick the last pass ran in. */
    public long lastPassTick() {
        return lastPass;
    }

    /** How many passes so far found a claim over blocks another crane really stands on and dropped it. */
    public long repairs() {
        return repairs;
    }

    // --- the exhaustive check (package-private, tests only) ----------------------------------------------------

    /**
     * A deep copy of this ledger whose next pass is never skipped, so a state search can branch from any state
     * ({@code TrafficModelCheckTest}).
     */
    TrafficLedger copy() {
        TrafficLedger copy = new TrafficLedger(graph, routes, turnPenaltyBlocks);
        for (Slot slot : slots.values())
            copy.slots.put(slot.number, slot.copy());
        copy.lastPass = lastPass;
        copy.lastChanged = true;
        return copy;
    }

    /**
     * Everything that decides what this ledger does next, as text: claims, commitments, wants and orders, with the
     * ticks wants were posted replaced by their rank, because only their order and equality are ever read. Two
     * ledgers with the same key behave the same in every later pass.
     */
    String stateKey() {
        TreeSet<Long> ticks = new TreeSet<>();
        for (Slot slot : slots.values()) {
            if (slot.waiting)
                ticks.add(slot.since);
            if (slot.order != null)
                ticks.add(slot.order.topSince);
            if (slot.yieldCount > 0)
                ticks.add(slot.yieldTopSince);
        }
        List<Long> ranks = new ArrayList<>(ticks);
        StringBuilder key = new StringBuilder();
        for (Slot slot : slots.values()) {
            key.append(slot.number).append('{').append(slot.claim);
            if (slot.commitment != null)
                key.append(" c").append(slot.commitment.stopCell()).append('/').append(slot.commitment.forCrane())
                        .append(graph.cellsOf(slot.commitment.route()).length);
            if (slot.waiting)
                key.append(" w").append(slot.wantCell).append(slot.wantKind).append('@')
                        .append(ranks.indexOf(slot.since));
            else if (slot.wantCell != NONE)
                key.append(" w").append(slot.wantCell).append(slot.wantKind);
            if (slot.order != null)
                key.append(" o").append(slot.order.forCrane).append('@').append(ranks.indexOf(slot.order.topSince))
                        .append('>').append(slot.order.parkCell).append(slot.order.granted ? 'g' : '-')
                        .append(slot.order.done ? 'd' : '-');
            if (slot.yieldCount > 0)
                key.append(" y").append(slot.yieldTop).append('@').append(ranks.indexOf(slot.yieldTopSince))
                        .append('x').append(slot.yieldCount);
            key.append('}');
        }
        return key.toString();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private Optional<CraneRoute> wantedRoute(Slot slot) {
        TrafficWant want = slot.input.want().orElse(null);
        if (want == null)
            return Optional.empty();
        return routes.route(slot.input.branch(), slot.input.x(), want.branch(), want.x(), turnPenaltyBlocks);
    }

    private TrafficPriority priorityOf(Slot slot) {
        if (slot.order != null)
            return TrafficPriority.yieldFor(slot.order.topSince, slot.number);
        if (slot.wantKind == TrafficWant.Kind.REST)
            return TrafficPriority.rest(slot.number);
        return TrafficPriority.job(slot.since, slot.number);
    }

    private int[] settled(Slot slot) {
        return slot.commitment != null ? new int[] {slot.commitment.stopCell()} : slot.standing;
    }

    private List<int[]> settledAll() {
        List<int[]> settled = new ArrayList<>(slots.size());
        for (Slot slot : slots.values())
            settled.add(settled(slot));
        return settled;
    }

    private List<int[]> settledWith(Slot moved, int[] cells) {
        List<int[]> settled = new ArrayList<>(slots.size());
        for (Slot slot : slots.values())
            settled.add(slot == moved ? cells : settled(slot));
        return settled;
    }

    private BitSet cutOff(List<int[]> settled) {
        int[] docks = new int[slots.size()];
        boolean[] required = new boolean[slots.size()];
        int i = 0;
        for (Slot slot : slots.values()) {
            docks[i] = slot.input.dockCell();
            // A stopped or unloaded crane keeps its escape too, so it can go on once it turns again; only a crane
            // whose dock is cut away has none to keep.
            required[i] = slot.health != TrafficHealth.STRANDED;
            i++;
        }
        return EscapeCheck.cutOff(graph, settled, docks, required);
    }

    private BitSet othersClaims(Slot slot) {
        BitSet blocked = new BitSet(graph.cellCount());
        for (Slot other : slots.values()) {
            if (other != slot)
                blocked.or(other.claim);
        }
        return blocked;
    }

    private boolean claimedByOthers(Slot slot, int cell) {
        for (Slot other : slots.values()) {
            if (other != slot && (other.claim.get(cell) || contains(settled(other), cell)))
                return true;
        }
        return false;
    }

    private int indexOf(Slot slot) {
        int i = 0;
        for (Slot each : slots.values()) {
            if (each == slot)
                return i;
            i++;
        }
        return -1;
    }

    private int numberAt(int index) {
        int i = 0;
        for (Slot slot : slots.values()) {
            if (i++ == index)
                return slot.number;
        }
        return 0;
    }

    private List<Fingerprint> fingerprint() {
        List<Fingerprint> prints = new ArrayList<>(slots.size());
        for (Slot slot : slots.values()) {
            TrafficCrane in = slot.input;
            boolean moving = slot.commitment != null && !standsOn(slot.standing, slot.commitment.stopCell());
            long where = moving ? -2L : encode(slot.standing);
            prints.add(new Fingerprint(slot.number, slot.health, in.armRetracted(), in.want(), in.dockCell(),
                    in.homeCell(), where));
        }
        return prints;
    }

    private static long encode(int[] cells) {
        long code = -1L;
        for (int cell : cells)
            code = code * 4096L + cell;
        return code;
    }

    private static boolean standsOn(int[] standing, int cell) {
        return standing.length == 1 && standing[0] == cell;
    }

    private static boolean contains(int[] cells, int cell) {
        for (int each : cells) {
            if (each == cell)
                return true;
        }
        return false;
    }

    private static boolean containsAll(int[] cells, int[] wanted) {
        for (int cell : wanted) {
            if (!contains(cells, cell))
                return false;
        }
        return true;
    }

    private record Fingerprint(int number, TrafficHealth health, boolean armRetracted, Optional<TrafficWant> want,
                               int dock, int home, long where) {
    }

    /** An order to move aside for the top request. */
    private static final class Order {
        final int forCrane;
        final long topSince;
        final int parkCell;
        final int parkBranch;
        final int parkX;
        boolean granted;
        boolean done;
        @Nullable
        TrafficWait refusal;

        Order(int forCrane, long topSince, int parkCell, int parkBranch, int parkX) {
            this.forCrane = forCrane;
            this.topSince = topSince;
            this.parkCell = parkCell;
            this.parkBranch = parkBranch;
            this.parkX = parkX;
        }
    }

    /** Everything the ledger keeps about one crane between passes. */
    private static final class Slot {
        final int number;
        TrafficCrane input;
        int[] standing;
        TrafficHealth health = TrafficHealth.HEALTHY;
        final BitSet claim = new BitSet();
        @Nullable
        Commitment commitment;
        int wantCell = NONE;
        TrafficWant.Kind wantKind;
        long since;
        boolean sinceRestored;
        boolean waiting;
        @Nullable
        TrafficWait refusal;
        @Nullable
        Order order;
        int yieldTop;
        long yieldTopSince;
        int yieldCount;
        TrafficVerdict verdict = TrafficVerdict.CLEAR;

        Slot(int number) {
            this.number = number;
        }

        Slot copy() {
            Slot copy = new Slot(number);
            copy.input = input;
            copy.standing = standing;
            copy.health = health;
            copy.claim.or(claim);
            copy.commitment = commitment;
            copy.wantCell = wantCell;
            copy.wantKind = wantKind;
            copy.since = since;
            copy.sinceRestored = sinceRestored;
            copy.waiting = waiting;
            copy.refusal = refusal;
            if (order != null) {
                copy.order = new Order(order.forCrane, order.topSince, order.parkCell, order.parkBranch,
                        order.parkX);
                copy.order.granted = order.granted;
                copy.order.done = order.done;
                copy.order.refusal = order.refusal;
            }
            copy.yieldTop = yieldTop;
            copy.yieldTopSince = yieldTopSince;
            copy.yieldCount = yieldCount;
            copy.verdict = verdict;
            return copy;
        }
    }
}
