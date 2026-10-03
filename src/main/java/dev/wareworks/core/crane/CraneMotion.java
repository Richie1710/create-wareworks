package dev.wareworks.core.crane;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TravelTimeModel;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * Deterministic crane motion ({@code docs/stacker-crane.md} §4-5), shared by server and client so the client can
 * animate between sync packets with the same result.
 * <p>
 * One {@link #step} moves the pose one tick towards the target:
 * <ol>
 *   <li>While the crane is not standing exactly where it has to stand and face — same branch, X, Y and yaw — an
 *       extended arm first retracts; nothing else moves in any tick in which the arm is not fully retracted.</li>
 *   <li>With the arm retracted, Y moves at its own speed in every such tick, and the crane spends one tick's worth of
 *       travel on its route: turning where the route hands it from one branch to the next, driving along X in
 *       between. <b>X does not move while the machine turns</b>, and a hand-over at a link is a rename of one world
 *       block, so no position outside a branch is ever needed.</li>
 *   <li>Only when branch, X, Y and yaw equal the target exactly does the arm move towards the target extension,
 *       retracting first if it points to the other side.</li>
 * </ol>
 * <b>One tick is one budget of {@code vx} blocks.</b> A quarter turn costs {@code turnPenaltyBlocks} of it
 * ({@code crane.turnPenaltyBlocks}), which is what makes the whole route a single axis of length
 * {@link CraneRoute#costBlocks}: a crane crossing three corners takes exactly as many ticks as
 * {@link TravelTimeModel#ticksToCover} says, instead of rounding up once per leg. A penalty of 0 is a documented
 * instant turn. An axis snaps exactly onto its target once the remaining distance is at most {@code speed +}
 * {@value TravelTimeModel#SNAP_EPSILON}, so it never overshoots and "at target" is an exact comparison.
 * <p>
 * <b>Without a route</b> ({@code null}) the crane behaves exactly as it did before M21 as long as it is already on the
 * target's branch: X and Y move straight towards the target and the yaw turns towards the target's. On another branch
 * it has no way to get there, so it only retracts its arm and waits — the content layer turns "no route" into the
 * existing source- or target-missing ladder rather than letting the machine drive somewhere it cannot reach.
 * <p>
 * Pure functions without allocation beyond the returned records.
 */
public final class CraneMotion {
    /** Turn speed of a crane that turns in one tick, whatever its travel speed ({@code turnPenaltyBlocks == 0}). */
    public static final double INSTANT_TURN = Double.POSITIVE_INFINITY;

    private CraneMotion() {
    }

    /**
     * The pose after one tick towards {@code target} on a warehouse of one aisle: no route, so the crane drives
     * straight at it. A speed of 0 leaves that axis where it is.
     * <p>
     * A target that faces another way is turned to in one tick, because without a network there is nothing that says
     * what a turn should cost. Every pose of a warehouse that does not bend faces the same way, so this never happens
     * there — and a crane that really does turn corners is stepped with its route and its penalty.
     */
    public static CranePose step(CranePose pose, CranePose target, CraneSpeeds speeds) {
        return step(pose, target, speeds, null, 0.0);
    }

    /**
     * The pose after one tick towards {@code target} along {@code route}.
     *
     * @param route             the way there, recomputed from the live network every tick, or {@code null} when the
     *                          network does not know one
     * @param turnPenaltyBlocks blocks of travel one quarter turn costs ({@code crane.turnPenaltyBlocks}); 0 turns in
     *                          one tick
     * @throws IllegalArgumentException if {@code turnPenaltyBlocks} is not finite or negative
     */
    public static CranePose step(CranePose pose, CranePose target, CraneSpeeds speeds, @Nullable CraneRoute route,
            double turnPenaltyBlocks) {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(speeds, "speeds");
        if (!Double.isFinite(turnPenaltyBlocks) || turnPenaltyBlocks < 0.0)
            throw new IllegalArgumentException(
                    "turnPenaltyBlocks must be finite and not negative: " + turnPenaltyBlocks);
        int legIndex = route == null ? -1 : route.legIndexAt(pose.branch(), pose.x());
        boolean stranded = route == null ? pose.branch() != target.branch() : legIndex < 0;
        if (stranded)
            // No way there: pull the arm in and stand still. The dock reports it as a missing source or target.
            return pose.arm() > CranePose.RETRACTED
                    ? pose.withArm(approach(pose.arm(), CranePose.RETRACTED, speeds.va()))
                    : pose;
        double yawTarget = yawTarget(target, route, legIndex, pose.x());
        if (!pose.sameCell(target) || pose.yaw() != yawTarget) {
            // Rule one, widened by one word: nothing else moves while the arm is out.
            if (pose.arm() > CranePose.RETRACTED)
                return pose.withArm(approach(pose.arm(), CranePose.RETRACTED, speeds.va()));
            CranePose moved = pose.withXY(pose.x(), approach(pose.y(), target.y(), speeds.vy()))
                    .withSide(target.side());
            return travel(moved, target, speeds.vx(), route, legIndex, turnPenaltyBlocks);
        }
        CranePose current = pose;
        if (current.side() != target.side()) {
            if (current.arm() > CranePose.RETRACTED)
                return current.withArm(approach(current.arm(), CranePose.RETRACTED, speeds.va()));
            current = current.withSide(target.side());
        }
        return current.withArm(approach(current.arm(), target.arm(), speeds.va()));
    }

    /** The state after one motion tick: the previous pose becomes the current one, the pose moves to its target. */
    public static <K, L> CraneState<K, L> step(CraneState<K, L> state, CraneSpeeds speeds) {
        return step(state, speeds, null, 0.0);
    }

    /** The state after one motion tick along {@code route} ({@link #step(CranePose, CranePose, CraneSpeeds, CraneRoute, double)}). */
    public static <K, L> CraneState<K, L> step(CraneState<K, L> state, CraneSpeeds speeds, @Nullable CraneRoute route,
            double turnPenaltyBlocks) {
        Objects.requireNonNull(state, "state");
        return state.withPoses(state.pose(),
                step(state.pose(), state.target(), speeds, route, turnPenaltyBlocks));
    }

    /**
     * Whether {@code pose} has reached {@code target}: the same branch, X, Y, yaw and extension, and the same side
     * unless the arm is retracted. A machine that still has to turn has not arrived, which is what keeps it from
     * extending its arm across the rails it is about to swing over.
     */
    public static boolean isAt(CranePose pose, CranePose target) {
        if (!pose.sameCell(target) || !pose.sameYaw(target) || pose.arm() != target.arm())
            return false;
        return pose.arm() == CranePose.RETRACTED || pose.side() == target.side();
    }

    /**
     * <b>Signed</b> blocks the machine drove in one tick, as its wheels turn them: positive when it moved the way it
     * faces, negative when it rolled back down the aisle it came from.
     * <p>
     * A position is a number along <b>one</b> branch, so two poses named on different branches say nothing about each
     * other and their difference is meaningless: a tick that handed over at a corner is therefore counted leg by leg
     * along {@code route}, each leg contributing the blocks really driven on it. A rename on its own moves the machine
     * by nothing and contributes nothing, which is exactly what the wheels should show.
     *
     * @param route the route the tick was stepped with, or {@code null} for a warehouse of one aisle
     */
    public static double blocksDriven(CranePose before, CranePose after, @Nullable CraneRoute route) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.branch() == after.branch())
            return after.x() - before.x();
        if (route == null)
            return 0.0; // renamed without a route: nothing the wheels could have measured
        int from = route.legIndexAt(before.branch(), before.x());
        int to = route.legIndexAt(after.branch(), after.x());
        if (from < 0 || to < 0 || to < from)
            return 0.0;
        double blocks = 0.0;
        for (int leg = from; leg <= to; leg++) {
            double start = leg == from ? before.x() : route.leg(leg).fromX();
            double end = leg == to ? after.x() : route.leg(leg).toX();
            blocks += end - start;
        }
        return blocks;
    }

    /**
     * Quarter turns the machine really <b>swung</b> in one tick, as a magnitude: never the net rotation between the
     * two poses.
     * <p>
     * The reason is the same one {@link #blocksDriven} exists for, and it bites harder here. A tick's budget is spent
     * along the whole route, so with a small {@code crane.turnPenaltyBlocks} — 0 is a documented instant turn —
     * {@link #step} can finish several legs in one tick. Every hand-over of a route is a quarter turn
     * ({@code CraneRoute} enforces perpendicular legs), so a tick can contain two, three or four of them, while
     * {@link CranePose#yawDelta} answers within {@code (-2, +2]}: two corners in opposite directions net <b>zero</b>
     * — the tick would read as no turn at all — three in one direction read as one, and four read as zero.
     * <p>
     * So the swing is counted leg by leg along {@code route}: the turn onto each leg the tick crossed, and whatever is
     * left of the turn onto the leg it ended on. Without a route — a warehouse of one straight aisle, or a tick whose
     * poses the route cannot place — there is no corner to miss and the net reading is exact.
     *
     * @param route the route the tick was stepped with, or {@code null} for a warehouse of one aisle
     */
    public static double quarterTurnsSwung(CranePose before, CranePose after, @Nullable CraneRoute route) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        double net = Math.abs(CranePose.yawDelta(before.yaw(), after.yaw()));
        if (route == null)
            return net;
        int from = route.legIndexAt(before.branch(), before.x());
        int to = route.legIndexAt(after.branch(), after.x());
        if (from < 0 || to < 0 || to <= from)
            return net;
        // The headings the machine turned towards, in the order travel() turns towards them: the leg it stood on (a
        // turn it may still have been finishing), then every leg it handed over onto. The last of them is left to the
        // hop below, because the tick may have ended part-way through it.
        double swung = 0.0;
        double yaw = before.yaw();
        for (int leg = from; leg < to; leg++) {
            double heading = CranePose.normalizeYaw(CranePose.yawOf(route.leg(leg).heading()));
            swung += Math.abs(CranePose.yawDelta(yaw, heading));
            yaw = heading;
        }
        return swung + Math.abs(CranePose.yawDelta(yaw, after.yaw()));
    }

    /** Quarter turns per tick at these speeds: {@code vx / turnPenaltyBlocks}, {@link #INSTANT_TURN} without penalty. */
    public static double turnSpeed(CraneSpeeds speeds, double turnPenaltyBlocks) {
        Objects.requireNonNull(speeds, "speeds");
        if (turnPenaltyBlocks <= 0.0)
            return INSTANT_TURN;
        return speeds.vx() / turnPenaltyBlocks;
    }

    /** One axis moved by at most {@code speed} towards {@code to}, snapping within speed + epsilon. */
    static double approach(double from, double to, double speed) {
        double delta = to - from;
        if (Math.abs(delta) <= speed + TravelTimeModel.SNAP_EPSILON)
            return to;
        return from + Math.copySign(speed, delta);
    }

    // --- travel ------------------------------------------------------------------------------------------------

    /**
     * The way the machine has to face right now: the heading of the leg it is really about to drive, or the target's
     * own yaw when there is no route. A leg the crane stands at the end of is already behind it — the hand-over is a
     * rename and costs nothing — so the machine turns towards the branch it leaves on and never towards one it only
     * touches.
     */
    private static double yawTarget(CranePose target, @Nullable CraneRoute route, int legIndex, double x) {
        if (route == null || legIndex < 0)
            return target.yaw();
        return CranePose.normalizeYaw(CranePose.yawOf(route.leg(activeLeg(route, legIndex, x)).heading()));
    }

    /** The leg the crane really drives next: the first one from {@code legIndex} it is not already at the end of. */
    private static int activeLeg(CraneRoute route, int legIndex, double x) {
        int leg = legIndex;
        double at = x;
        while (leg + 1 < route.legCount() && at == route.leg(leg).toX()) {
            at = route.leg(++leg).fromX();
        }
        return leg;
    }

    /**
     * One tick's travel budget of {@code vx} blocks, spent along the route: on the hand-over where a leg is already
     * behind the crane, on the turn the leg it drives asks for, then on X — and, if the leg ends within the budget and
     * another follows, on the next hand-over and the next turn, and so on. Y has already moved.
     * <p>
     * One budget for the whole route is what makes the trip tick-exact: a crane that crosses three corners takes as
     * many ticks as {@link TravelTimeModel#ticksToCover} over {@link CraneRoute#costBlocks} says, instead of rounding
     * up once per leg and once per turn.
     */
    private static CranePose travel(CranePose pose, CranePose target, double vx, @Nullable CraneRoute route,
            int legIndex, double turnPenaltyBlocks) {
        double budget = vx;
        CranePose current = pose;
        int leg = legIndex;
        int legs = route == null ? 1 : route.legCount();
        // Every leg is looked at twice at most: once to hand over onto it, once to turn on it and drive it.
        for (int guard = 2 * legs + 2; guard > 0; guard--) {
            double legEnd = route == null ? target.x() : route.leg(leg).toX();
            if (route != null && current.x() == legEnd && leg + 1 < legs) {
                CraneRoute.Leg next = route.leg(++leg);
                current = current.handedOver(next.branch(), next.fromX());
                continue;
            }
            double wantedYaw = route == null ? target.yaw()
                    : CranePose.normalizeYaw(CranePose.yawOf(route.leg(leg).heading()));
            if (current.yaw() != wantedYaw) {
                double delta = CranePose.yawDelta(current.yaw(), wantedYaw);
                double turnBlocks = Math.abs(delta) * turnPenaltyBlocks;
                if (turnBlocks > budget + TravelTimeModel.SNAP_EPSILON)
                    // turnPenaltyBlocks is positive here: a turn of 0 blocks is never more than the budget.
                    return current.withYaw(current.yaw() + Math.copySign(budget, delta) / turnPenaltyBlocks);
                current = current.withYaw(wantedYaw);
                budget = Math.max(0.0, budget - turnBlocks);
            }
            double remaining = Math.abs(legEnd - current.x());
            if (remaining > budget + TravelTimeModel.SNAP_EPSILON) {
                if (budget <= 0.0)
                    return current;
                return current.withXY(current.x() + Math.copySign(budget, legEnd - current.x()), current.y());
            }
            current = current.withXY(legEnd, current.y());
            budget = Math.max(0.0, budget - remaining);
            if (route == null || leg + 1 >= legs)
                return current;
        }
        return current;
    }
}
