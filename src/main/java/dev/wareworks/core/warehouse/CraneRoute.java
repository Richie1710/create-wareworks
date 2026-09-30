package dev.wareworks.core.warehouse;

import java.util.List;
import java.util.Objects;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;

/**
 * The way one crane drives from one point of a rail network to another: a sequence of straight
 * {@link Leg legs}, one per branch, joined at the shared block of a {@link dev.wareworks.core.address.BranchLink}
 * ({@code docs/stacker-crane.md} §4, ADR-033).
 * <p>
 * <b>A route is derived, never saved and never synced.</b> {@link RouteModel} recomputes it every tick from the pose,
 * the target and the network the dock knows, so a crane follows a network a player just changed and a crane whose route
 * disappeared simply has none.
 * <p>
 * A hand-over at a link is a <b>rename of one world block</b>: the last position of a leg and the first position of the
 * next name the same block on their two branches, so no coordinate outside a branch is ever needed. Consecutive legs
 * therefore lie on different, perpendicular branches — two collinear touching rails are one branch (ADR-033), so a
 * route never has two legs of the same heading in a row and every turn is exactly a quarter.
 * <p>
 * A leg may be <b>empty</b> ({@code fromX == toX}): the crane stands on the link it hands over at, or the target is the
 * link block itself. Such a leg still costs its turn, because the machine has to face the branch it is named on.
 *
 * @param legs the legs in driving order, at least one
 */
public record CraneRoute(List<Leg> legs) {
    /**
     * One straight stretch along a single branch.
     *
     * @param branch  branch index the crane is named on while it drives this leg
     * @param fromX   position it enters the leg at
     * @param toX     position it leaves the leg at; equal to {@code fromX} for an empty leg
     * @param heading direction of the branch — the way the machine faces while it drives the leg, in either direction
     */
    public record Leg(int branch, double fromX, double toX, Heading heading) {
        public Leg {
            if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH)
                throw new IllegalArgumentException("branch must be in " + RackPosition.FIRST_BRANCH + ".."
                        + RackPosition.MAX_BRANCH + ": " + branch);
            requirePosition("fromX", fromX);
            requirePosition("toX", toX);
            Objects.requireNonNull(heading, "heading");
        }

        /** Blocks the crane travels along this leg. */
        public double blocks() {
            return Math.abs(toX - fromX);
        }

        /** Whether {@code x} lies on this leg, ends included, in whichever direction it runs. */
        public boolean contains(double x) {
            return x >= Math.min(fromX, toX) && x <= Math.max(fromX, toX);
        }

        /** Whether the leg runs towards higher positions; an empty leg counts as forward. */
        public boolean isForward() {
            return toX >= fromX;
        }

        private static void requirePosition(String name, double value) {
            if (!Double.isFinite(value))
                throw new IllegalArgumentException(name + " must be finite: " + value);
            if (value < 0.0 || value > AisleGeometry.MAX_LENGTH)
                throw new IllegalArgumentException(
                        name + " must be in 0.." + AisleGeometry.MAX_LENGTH + ": " + value);
        }
    }

    public CraneRoute {
        legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
        if (legs.isEmpty())
            throw new IllegalArgumentException("a route has at least one leg");
        for (int i = 1; i < legs.size(); i++) {
            Leg previous = legs.get(i - 1);
            Leg next = legs.get(i);
            if (previous.branch() == next.branch())
                throw new IllegalArgumentException("two legs in a row on branch " + next.branch()
                        + ": collinear rails are one branch, so a route never doubles back on one");
            if (!previous.heading().isPerpendicularTo(next.heading()))
                throw new IllegalArgumentException("legs " + (i - 1) + " and " + i
                        + " are not perpendicular: every hand-over is a quarter turn (" + previous.heading() + " → "
                        + next.heading() + ")");
        }
    }

    /** A route of the given legs. */
    public static CraneRoute of(Leg... legs) {
        return new CraneRoute(List.of(legs));
    }

    /** The one-leg route along a single branch — what every route on a warehouse that does not bend looks like. */
    public static CraneRoute straight(int branch, double fromX, double toX, Heading heading) {
        return new CraneRoute(List.of(new Leg(branch, fromX, toX, heading)));
    }

    public int legCount() {
        return legs.size();
    }

    public Leg leg(int index) {
        return legs.get(Objects.checkIndex(index, legs.size()));
    }

    public Leg firstLeg() {
        return legs.get(0);
    }

    public Leg lastLeg() {
        return legs.get(legs.size() - 1);
    }

    /** Quarter turns on the way: one per hand-over, so {@code legCount() - 1}. */
    public int turns() {
        return legs.size() - 1;
    }

    /** Whether the whole route runs on one branch, i.e. the crane never turns. */
    public boolean isStraight() {
        return legs.size() == 1;
    }

    /** Blocks travelled, turns not counted. */
    public double blocks() {
        double blocks = 0.0;
        for (Leg leg : legs)
            blocks += leg.blocks();
        return blocks;
    }

    /**
     * What the route costs in blocks: the blocks travelled plus {@code turnPenaltyBlocks} per quarter turn
     * ({@code crane.turnPenaltyBlocks}). One scalar in blocks, which is what lets
     * {@link dev.wareworks.core.job.TravelTimeModel} keep one formula for the whole trip.
     *
     * @throws IllegalArgumentException if {@code turnPenaltyBlocks} is not finite or negative
     */
    public double costBlocks(double turnPenaltyBlocks) {
        requirePenalty(turnPenaltyBlocks);
        return blocks() + turns() * turnPenaltyBlocks;
    }

    /** Branch the route starts on. */
    public int fromBranch() {
        return firstLeg().branch();
    }

    /** Position the route starts at. */
    public double fromX() {
        return firstLeg().fromX();
    }

    /** Branch the route ends on. */
    public int toBranch() {
        return lastLeg().branch();
    }

    /** Position the route ends at. */
    public double toX() {
        return lastLeg().toX();
    }

    /**
     * Index of the leg the point {@code (branch, x)} lies on, or {@code -1} if the route does not pass it. A link block
     * lies on two legs; the branch the crane is named on decides which, which is exactly why a hand-over is a rename.
     */
    public int legIndexAt(int branch, double x) {
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            if (leg.branch() == branch && leg.contains(x))
                return i;
        }
        return -1;
    }

    private static void requirePenalty(double turnPenaltyBlocks) {
        if (!Double.isFinite(turnPenaltyBlocks) || turnPenaltyBlocks < 0.0)
            throw new IllegalArgumentException(
                    "turnPenaltyBlocks must be finite and not negative: " + turnPenaltyBlocks);
    }
}
