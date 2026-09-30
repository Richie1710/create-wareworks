package dev.wareworks.core.crane;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;

/**
 * Where a crane with nothing to do waits, and when it drives there ({@code docs/stacker-crane.md} §4.7, M21, ADR-034).
 * <p>
 * <b>Pure, and the whole rule.</b> Everything about "returning home" that a player can argue about — when the machine
 * counts as idle, when it goes back, when it stays where it is and which block it parks on — is decided here, so the
 * behaviour is a JUnit test rather than a world test. The content layer only supplies the numbers and applies the pose
 * it gets back as the resting target of {@link CranePhase#IDLE}.
 * <p>
 * <b>Three rules, in this order.</b>
 * <ol>
 * <li><b>A warehouse of one straight aisle never returns.</b> That is what every warehouse did before M21 and it must
 * keep doing it: a crane on a single aisle stands where its last job left it, whatever a home point says. A home point
 * there is reported as having no effect rather than obeyed, because the alternative is a silent behaviour change in
 * every world built before this version.</li>
 * <li><b>A delay of {@value #OFF} switches it off entirely</b> ({@code crane.returnHomeIdleTicks}), which is the
 * server owner's off switch and reproduces rule one on every warehouse.</li>
 * <li><b>Home is the home point, or the dock.</b> A warehouse without a home point parks its crane at position 0 of
 * the aisle at the dock, which is where a crane has always started; a home point moves that spot to a rack position a
 * player chose. Breaking the home point therefore falls back to the dock without any further rule.</li>
 * </ol>
 * <b>Why the resting target and not a job.</b> A return is the {@code IDLE} phase's own motion target, so it is
 * interrupted by any real job in the very tick the job arrives — mid-turn included, because the next phase simply
 * computes another target from the same pose — it can never delay work, and it is no {@code TransportJob}, so a
 * warehouse whose only remaining activity is a crane rolling home still counts as idle and keeps letting its chunks go
 * ({@code ChunkKeepDecision}).
 *
 * @param delayTicks  ticks a crane waits for work before it drives home; {@value #OFF} never returns
 * @param branchCount aisles of the warehouse the crane drives on, at least 1
 * @param home        the rack position of the warehouse's home point, or empty for the dock
 */
public record HomeReturn(int delayTicks, int branchCount, Optional<RackPosition> home) {
    /** A delay that switches returning home off. */
    public static final int OFF = 0;
    /**
     * Upper bound of the idle counter. It only has to stay above every configurable delay and below overflow, so a
     * crane that waited for a week counts no further and still returns.
     */
    public static final int MAX_IDLE_TICKS = 1 << 24;

    /** A warehouse that never sends its crane home: one aisle, no delay, no home point. */
    public static final HomeReturn NEVER = new HomeReturn(OFF, 1, Optional.empty());

    public HomeReturn {
        Objects.requireNonNull(home, "home");
        if (delayTicks < OFF)
            throw new IllegalArgumentException("delayTicks must not be negative: " + delayTicks);
        if (branchCount < 1)
            throw new IllegalArgumentException("a warehouse has at least one aisle: " + branchCount);
    }

    /** The rule of a warehouse of {@code branchCount} aisles whose home point is {@code home}. */
    public static HomeReturn of(int delayTicks, int branchCount, Optional<RackPosition> home) {
        return new HomeReturn(delayTicks, branchCount, home);
    }

    /**
     * Whether this warehouse sends its crane home at all: more than one aisle and a configured delay. A home point is
     * <b>not</b> required — without one the dock is home.
     */
    public boolean enabled() {
        return delayTicks > OFF && branchCount > 1;
    }

    /**
     * Whether a home point a player placed has any effect here. It has none on a warehouse of one aisle and none while
     * the delay switches returning home off, and in both cases the block says so instead of pretending to work.
     */
    public boolean homePointHasEffect() {
        return enabled() && home.isPresent();
    }

    /**
     * Whether a crane is waiting for work: idle, without a job and not paused. A paused crane is not waiting, it is
     * stopped — so its counter neither runs nor resets, and it carries on where it left off once it has rotation again.
     */
    public static boolean isWaiting(CranePhase phase, boolean hasJob, boolean paused) {
        return phase == CranePhase.IDLE && !hasJob && !paused;
    }

    /**
     * The idle counter one tick on: {@code 0} for a crane that is not waiting for work, otherwise one more, capped at
     * {@value #MAX_IDLE_TICKS} so it can never overflow.
     */
    public static int countIdle(int idleTicks, boolean waiting) {
        if (!waiting)
            return 0;
        int counted = Math.max(0, idleTicks);
        return counted >= MAX_IDLE_TICKS ? MAX_IDLE_TICKS : counted + 1;
    }

    /** Whether a crane that has been waiting for {@code idleTicks} drives home now. */
    public boolean returnsAfter(int idleTicks) {
        return enabled() && idleTicks >= delayTicks;
    }

    /**
     * The pose the crane parks in, or empty when nothing returns here ({@link #enabled()}).
     * <p>
     * The arm is retracted and the machine faces the way its aisle runs, so a crane that arrives is standing exactly as
     * a crane that has just finished a job on that aisle — the next job starts without an extra turn. The side is the
     * home point's own, which costs nothing (a retracted arm has no side a player can see) and makes the parked pose
     * one value rather than "wherever the last job left the arm".
     *
     * @param heading the way the home's aisle runs
     */
    public Optional<CranePose> parkPose(Heading heading) {
        Objects.requireNonNull(heading, "heading");
        if (!enabled())
            return Optional.empty();
        RackPosition rack = home.orElse(null);
        if (rack == null)
            return Optional.of(CranePose.at(RackPosition.FIRST_BRANCH, 0.0, 0.0, CranePose.DEFAULT_SIDE, heading));
        return Optional.of(CranePose.at(rack.branch(), rack.x(), rack.y(), rack.side(), heading));
    }

    /** The aisle the crane parks on: the home point's branch, or the one at the dock. */
    public int homeBranch() {
        return home.map(RackPosition::branch).orElse(RackPosition.FIRST_BRANCH);
    }

    /** The position along {@link #homeBranch()} the crane parks at. */
    public double homeX() {
        return home.map(RackPosition::x).orElse(0);
    }

    /** This rule with another home point (or none, for the dock). */
    public HomeReturn withHome(Optional<RackPosition> newHome) {
        return new HomeReturn(delayTicks, branchCount, newHome);
    }
}
