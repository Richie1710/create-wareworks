package dev.wareworks.core.port;

import org.jetbrains.annotations.Nullable;

/**
 * Whether a box has waited at a package door's Packager long enough to <b>say so</b> (M27, issue #18, ADR-065 D14): the
 * Packager holds a box with its tray at rest, and has done so for at least {@value #REPORT_AFTER_TICKS} ticks. The case
 * it is there for is the build with the funnel forgotten, or a belt that stopped: the port fills, one box is made, and
 * then the door stands still for ever, because a Packager that holds a box makes no other.
 * <p>
 * <b>What it reports, and why {@value #REPORT_AFTER_TICKS} ticks.</b> A box can only be taken once the tray is at rest
 * — Create's {@code PackagerItemHandler#extractItem} hands out nothing while {@code animationTicks != 0} — but a taker
 * that works is not always quick: a hopper below pulls every 8 ticks, yet a belt funnel takes nothing while its belt's
 * input is still occupied ({@code FunnelBlockEntity#activateExtractingBeltFunnel}), which on a slow belt is
 * {@code 480 / RPM} ticks per box, a hopper whose own slots are full takes as fast as its next stage, and a Frogport
 * as fast as its chain. So a box at rest is reported only after ten seconds, longer than a belt at 3 RPM needs per box,
 * and what is reported is the wait that was seen, with a question — not a claim that nothing takes the box.
 * <p>
 * <b>Looks, not ticks.</b> Nothing here runs by itself. Whoever wants the answer looks at the Packager and passes what
 * it saw; this class only remembers what the looks saw, so a box nobody looks at costs nothing. Between two looks the
 * box may have been taken and another one made, and the wait goes on only when the two looks provably saw the same box
 * waiting, which is the case when either holds:
 * <ul>
 * <li><b>the very same object</b> waits at both looks. Create makes every box as a new object, and a client replaces
 * the object with every block entity packet the Packager sends — and taking a box always sends one. So the same object
 * means nobody took it, however far apart the looks are. This is what the door rows of a terminal's screen rely on,
 * which look once per build, twenty ticks or more apart;</li>
 * <li>the last look was <b>less than {@value #SAME_BOX_GAP_TICKS} ticks ago</b>. A box that is taken can only be
 * followed by one at rest after the Packager's 20-tick tray animation ({@code PackagerBlockEntity.CYCLE}), so two looks
 * closer together than that cannot have seen two different boxes at rest. This is what a goggle tooltip relies on,
 * which is built in every frame while a player points at the port, on a client whose box object changes with every
 * packet. The gap is a proof about the tray and must never exceed its cycle, which is why it is a constant of its own
 * and not the reporting time.</li>
 * </ul>
 * A look that sees no box at rest — none held, or the tray moving — ends the wait. A game time that went backwards does
 * not: a client's clock runs ahead of a server below 20 TPS, or behind a slow connection, and the server's time packet
 * (every 20 server ticks) sets it back by a tick or a few. Such a step moves the wait's clock back with it, so the
 * looks keep counting the ticks they saw; server time never goes back. The box is kept only to be compared by identity:
 * it is never read, changed or saved.
 * <p>
 * Pure Java, no Minecraft: {@code core.*} is JUnit-testable.
 */
public final class BoxWait {
    /**
     * Ticks a box must have waited at rest before it is reported: ten seconds, longer than a working taker needs per
     * box, a belt funnel on a belt at 3 RPM included.
     */
    public static final int REPORT_AFTER_TICKS = 200;
    /**
     * Two looks closer together than this many ticks cannot have seen two different boxes at rest, so another box
     * object at such a look continues the wait. It mirrors {@code PackagerBlockEntity.CYCLE}, the tray animation a new
     * box has to pass before it is at rest, and must never be larger than that.
     */
    public static final int SAME_BOX_GAP_TICKS = 20;

    /** The box the last look saw at rest, compared by identity only; {@code null} while none waits. */
    @Nullable
    private Object box;
    /** Game time of the first look of the current wait. */
    private long since;
    /** Game time of the last look that saw a box at rest. */
    private long lastLook;

    /**
     * Records one look and answers whether the box has waited long enough to report.
     *
     * @param now        the game time of the look
     * @param restingBox the box the Packager holds with its tray at rest, or {@code null} when it holds none or the
     *                   tray is moving
     * @return whether a box has waited at rest for at least {@value #REPORT_AFTER_TICKS} ticks, as far as the looks can
     *         tell
     */
    public boolean look(long now, @Nullable Object restingBox) {
        if (restingBox == null) {
            box = null;
            return false;
        }
        if (box != null && now < lastLook) {
            // The client's clock was set back by the server's time sync: keep the wait and its count of ticks.
            since -= lastLook - now;
            lastLook = now;
        }
        boolean sameWait = box != null && (restingBox == box || now - lastLook < SAME_BOX_GAP_TICKS);
        if (!sameWait)
            since = now;
        box = restingBox;
        lastLook = now;
        return now - since >= REPORT_AFTER_TICKS;
    }
}
