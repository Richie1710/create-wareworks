package dev.wareworks.client.ponder.scenes;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.crane.CraneNetwork;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.warehouse.CraneRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Drives the crane of a Ponder scene through the client pose API (ADR-013, ADR-016).
 * <p>
 * A scene never animates the crane frame by frame. It only tells the dock's block entity where the crane is and where
 * it is going ({@code StackerCraneBlockEntity#showClientPose(pose, target, phase, held)}); the block entity's own client
 * tick then moves it with the very same {@code CraneMotion} the server uses, at the speeds of its kinetic speed. The
 * arm retracts before X/Y motion and extends only at the target, exactly as in a real job, and the rendering
 * interpolates between ticks, so the motion is smooth.
 * <p>
 * The only thing the scene has to know is <b>how long</b> a move takes, so that its {@code idle(...)} matches. That is
 * computed here by running the same pure {@link CraneMotion} step function ahead of time, which is exact: the block
 * entity ticks once per scene tick while its section is visible, so {@code idle(n)} with {@code n} simulated steps
 * leaves the crane exactly on its target.
 * <p>
 * Only blocking instructions add to a scene's total time, so these {@code idle} calls are what makes the progress bar
 * and the "scene finished" detection correct.
 * <p>
 * The speeds mirror {@code StackerCraneBlockEntity#currentSpeeds()}, including its rule that a configured factor of 0
 * stops the crane entirely. With such a config the crane simply stands still and the scene keeps its pacing through
 * {@link #MIN_MOVE_TICKS}.
 * <p>
 * <b>A warehouse that bends</b> (M21, ADR-033) needs one thing more: the network. A {@code PonderLevel} never runs the
 * server-only {@code refreshGeometry}, so a dock in a scene does not know that its rails turn a corner, and a machine
 * that does not know its own network cannot drive round one however many rails are drawn under it. {@link
 * #onNetwork(CreateSceneBuilder, BlockPos, NetworkGeometry)} hands it over with every pose, and the same network is
 * used to work out how long a move takes — so the {@code idle} of a beat that crosses a corner includes the quarter
 * turn ({@code crane.turnPenaltyBlocks}) exactly as the machine spends it.
 */
public final class CraneScript {
    /** Kinetic speed the scenes drive the dock with: slow enough to read, fast enough not to drag. */
    public static final int PONDER_RPM = 32;
    /** Upper bound of the motion simulation, so a broken config can never spin the storyboard forever. */
    private static final int MAX_SIMULATED_TICKS = 1200;
    /** Every move takes at least this long, so a stopped crane still gives the scene a readable beat. */
    private static final int MIN_MOVE_TICKS = 10;

    private final CreateSceneBuilder scene;
    private final BlockPos dock;
    private final CraneSpeeds speeds;
    /** The rails the shown machine drives on, or {@code null} for a warehouse of one straight aisle. */
    @Nullable
    private final NetworkGeometry network;
    private final CraneNetwork rails;
    private CranePose pose;
    private List<KeyCount<Item>> held = List.of();

    public CraneScript(CreateSceneBuilder scene, BlockPos dock, int rpm, CranePose start) {
        this(scene, dock, rpm, null, start);
    }

    private CraneScript(CreateSceneBuilder scene, BlockPos dock, int rpm, @Nullable NetworkGeometry network,
            CranePose start) {
        this.scene = scene;
        this.dock = dock;
        this.speeds = speedsAt(rpm);
        this.network = network;
        this.rails = network == null ? CraneNetwork.SINGLE_BRANCH
                : CraneNetwork.of(network, WareworksConfig.turnPenaltyBlocks());
        this.pose = start;
    }

    /** A crane script starting at the parking pose, driven at {@link #PONDER_RPM}. */
    public static CraneScript parkedAt(CreateSceneBuilder scene, BlockPos dock) {
        return new CraneScript(scene, dock, PONDER_RPM, StackerCraneBlockEntity.HOME_POSE);
    }

    /**
     * A crane script for a warehouse that bends: parked at position 0 of the branch at the dock, facing the way that
     * branch runs, and driven at {@link #PONDER_RPM} ({@link #onNetwork} in the class comment).
     */
    public static CraneScript onNetwork(CreateSceneBuilder scene, BlockPos dock, NetworkGeometry network) {
        CranePose parked = StackerCraneBlockEntity.HOME_POSE
                .facing(network.firstBranch().heading());
        return new CraneScript(scene, dock, PONDER_RPM, network, parked);
    }

    /**
     * The axis speeds the dock's block entity will use at {@code rpm}, mirroring
     * {@code StackerCraneBlockEntity#currentSpeeds()}: a zero factor in the server config stops every axis.
     */
    public static CraneSpeeds speedsAt(int rpm) {
        return StackerCraneBlockEntity.speedsFor(StackerCraneBlockEntity.kinematicParams(), rpm);
    }

    /** Ticks the shared motion needs to get from {@code from} to {@code to}; 0 for a stopped crane. */
    public static int ticksBetween(CranePose from, CranePose to, CraneSpeeds speeds) {
        return ticksBetween(from, to, speeds, CraneNetwork.SINGLE_BRANCH);
    }

    /**
     * Ticks the shared motion needs to get from {@code from} to {@code to} on {@code rails}; 0 for a stopped crane.
     * <p>
     * The route is recomputed from the pose on every simulated tick, exactly as {@code
     * StackerCraneBlockEntity#tickClientMotion} does it, so this counts the ticks the block entity will really spend —
     * including the quarter turns of the route.
     */
    public static int ticksBetween(CranePose from, CranePose to, CraneSpeeds speeds, CraneNetwork rails) {
        if (speeds.isStopped())
            return 0;
        CranePose current = from;
        for (int ticks = 1; ticks <= MAX_SIMULATED_TICKS; ticks++) {
            CraneRoute route = current.branch() == to.branch() ? null
                    : rails.route(current.branch(), current.x(), to.branch(), to.x()).orElse(null);
            current = CraneMotion.step(current, to, speeds, route, rails.turnPenaltyBlocks());
            if (CraneMotion.isAt(current, to))
                return ticks;
        }
        return MAX_SIMULATED_TICKS;
    }

    /** The pose the crane stands at after everything enqueued so far. */
    public CranePose pose() {
        return pose;
    }

    /**
     * The pose at {@code (branch, x, y, side)} with the arm retracted, facing the way that branch runs — the
     * branch-aware form of {@link CranePose#at(double, double, Side)}.
     *
     * @throws IllegalStateException if this script drives a warehouse of one straight aisle
     */
    public CranePose at(int branch, double x, double y, Side side) {
        return CranePose.at(branch, x, y, side, headingOf(branch));
    }

    /** {@link #at(int, double, double, Side)} with the arm fully extended into the rack. */
    public CranePose extendedAt(int branch, double x, double y, Side side) {
        return at(branch, x, y, side).withArm(CranePose.EXTENDED);
    }

    private Heading headingOf(int branch) {
        Optional<Heading> heading = rails.headingOf(branch);
        if (heading.isEmpty())
            throw new IllegalStateException("this crane script drives no branch " + branch + ": " + rails);
        return heading.get();
    }

    /** Shows {@code count} items of {@code item} in the grabber from the next instruction on. */
    public void hold(Item item, int count) {
        held = List.of(new KeyCount<>(item, count));
    }

    /** Empties the grabber from the next instruction on. */
    public void release() {
        held = List.of();
    }

    /** Re-asserts the current pose and held items in {@code phase} without moving. */
    public void show(CranePhase phase) {
        showPose(pose, pose, phase);
    }

    /**
     * Moves the crane to {@code target} in {@code phase} and idles exactly as long as the motion takes.
     *
     * @return the ticks idled
     */
    public int moveTo(CranePose target, CranePhase phase) {
        CranePose from = pose;
        showPose(from, target, phase);
        int ticks = Math.max(MIN_MOVE_TICKS, ticksBetween(from, target, speeds, rails));
        scene.idle(ticks);
        pose = target;
        return ticks;
    }

    /**
     * Hands pose, target and network to the dock's block entity. A script without a network calls the four-argument
     * form, which faces both poses the way the dock's own aisle runs — exactly what every scene did before M21.
     */
    private void showPose(CranePose from, CranePose target, CranePhase phase) {
        List<KeyCount<Item>> shownHeld = held;
        NetworkGeometry rails = network;
        if (rails == null) {
            scene.world().modifyBlockEntity(dock, StackerCraneBlockEntity.class,
                    crane -> crane.showClientPose(from, target, phase, shownHeld));
            return;
        }
        scene.world().modifyBlockEntity(dock, StackerCraneBlockEntity.class,
                crane -> crane.showClientPose(rails, from, target, phase, shownHeld));
    }

    /** Stands still in {@code phase} for {@code ticks}, e.g. while the grabber transfers items. */
    public int dwell(CranePhase phase, int ticks) {
        show(phase);
        scene.idle(ticks);
        return ticks;
    }
}
