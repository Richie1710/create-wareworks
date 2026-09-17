package dev.wareworks.client.ponder.scenes;

import java.util.List;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.CraneSpeeds;
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
    private CranePose pose;
    private List<KeyCount<Item>> held = List.of();

    public CraneScript(CreateSceneBuilder scene, BlockPos dock, int rpm, CranePose start) {
        this.scene = scene;
        this.dock = dock;
        this.speeds = speedsAt(rpm);
        this.pose = start;
    }

    /** A crane script starting at the parking pose, driven at {@link #PONDER_RPM}. */
    public static CraneScript parkedAt(CreateSceneBuilder scene, BlockPos dock) {
        return new CraneScript(scene, dock, PONDER_RPM, StackerCraneBlockEntity.HOME_POSE);
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
        if (speeds.isStopped())
            return 0;
        CranePose current = from;
        for (int ticks = 1; ticks <= MAX_SIMULATED_TICKS; ticks++) {
            current = CraneMotion.step(current, to, speeds);
            if (CraneMotion.isAt(current, to))
                return ticks;
        }
        return MAX_SIMULATED_TICKS;
    }

    /** The pose the crane stands at after everything enqueued so far. */
    public CranePose pose() {
        return pose;
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
        CranePose shown = pose;
        List<KeyCount<Item>> shownHeld = held;
        scene.world().modifyBlockEntity(dock, StackerCraneBlockEntity.class,
                crane -> crane.showClientPose(shown, shown, phase, shownHeld));
    }

    /**
     * Moves the crane to {@code target} in {@code phase} and idles exactly as long as the motion takes.
     *
     * @return the ticks idled
     */
    public int moveTo(CranePose target, CranePhase phase) {
        CranePose from = pose;
        List<KeyCount<Item>> shownHeld = held;
        scene.world().modifyBlockEntity(dock, StackerCraneBlockEntity.class,
                crane -> crane.showClientPose(from, target, phase, shownHeld));
        int ticks = Math.max(MIN_MOVE_TICKS, ticksBetween(from, target, speeds));
        scene.idle(ticks);
        pose = target;
        return ticks;
    }

    /** Stands still in {@code phase} for {@code ticks}, e.g. while the grabber transfers items. */
    public int dwell(CranePhase phase, int ticks) {
        show(phase);
        scene.idle(ticks);
        return ticks;
    }
}
