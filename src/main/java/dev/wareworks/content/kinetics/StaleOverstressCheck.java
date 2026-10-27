package dev.wareworks.content.kinetics;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.wareworks.core.kinetics.OverstressRecheck;
import net.minecraft.world.level.Level;

/**
 * Clears an overstress that Create keeps after its network has the capacity again (issue #38, ADR-071). One instance
 * per Wareworks kinetic consumer (stacker crane dock, Warehouse Crafter Feeder), called from its server tick.
 * <p>
 * While the block entity is overstressed it recomputes its network on the schedule of {@link OverstressRecheck}:
 * {@code KineticNetwork#updateNetwork} recomputes the totals from the members that are loaded and syncs them into every
 * member when they changed; if the totals did not change but this member is still flagged overstressed against them,
 * {@code KineticNetwork#sync} hands the totals to every member. A really overstressed network keeps its flag. A member
 * that is not overstressed costs one counter reset per tick. No mixin and nothing of Create's is replaced; both calls
 * are public and are what Create itself runs after a member joins or leaves.
 * <p>
 * Create's recomputation looks every member up with {@code Level#getBlockEntity}, which on the server loads a chunk
 * that is not loaded, and drops a member whose block entity is not the one found there. A generator whose chunk is
 * unloaded would so be loaded for a moment without ticking, dropped, and its capacity lost until its chunk ticks
 * again. The check therefore skips a recheck while any member's chunk is not loaded and tries again on the next one.
 * A member whose chunk was unloaded and is loaded again is dropped as Create always does: its new block entity has
 * already joined in its place.
 */
public final class StaleOverstressCheck {
    private final OverstressRecheck schedule = new OverstressRecheck();

    /** One server tick of {@code be}; call it only on the server and only for a real (not virtual) block entity. */
    public void serverTick(KineticBlockEntity be) {
        if (!schedule.tick(be.isOverStressed()) || !be.hasNetwork())
            return;
        KineticNetwork network = be.getOrCreateNetwork();
        if (!allMembersLoaded(be.getLevel(), network))
            return;
        network.updateNetwork();
        // Short-circuit: the totals are only recomputed a second time when the flag survived updateNetwork.
        if (be.isOverStressed() && OverstressRecheck.staleAfterRecompute(true, network.calculateCapacity(),
                network.calculateStress()))
            network.sync();
    }

    /** Whether every member's chunk is loaded, so a recomputation loads none (members include the sources). */
    private static boolean allMembersLoaded(Level level, KineticNetwork network) {
        for (KineticBlockEntity member : network.members.keySet())
            if (!level.isLoaded(member.getBlockPos()))
                return false;
        return true;
    }
}
