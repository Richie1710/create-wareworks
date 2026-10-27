package dev.wareworks.content.kinetics;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.wareworks.core.kinetics.OverstressRecheck;

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
 * Create's recomputation drops a generator whose block entity is no longer the one in the world (its chunk unloaded)
 * and looks it up with {@code Level#getBlockEntity}, as on every network change; since the check only runs while the
 * member is already overstressed, it never drops a generator from a network that was fine.
 */
public final class StaleOverstressCheck {
    private final OverstressRecheck schedule = new OverstressRecheck();

    /** One server tick of {@code be}; call it only on the server and only for a real (not virtual) block entity. */
    public void serverTick(KineticBlockEntity be) {
        if (!schedule.tick(be.isOverStressed()) || !be.hasNetwork())
            return;
        KineticNetwork network = be.getOrCreateNetwork();
        network.updateNetwork();
        // Short-circuit: the totals are only recomputed a second time when the flag survived updateNetwork.
        if (be.isOverStressed() && OverstressRecheck.staleAfterRecompute(true, network.calculateCapacity(),
                network.calculateStress()))
            network.sync();
    }
}
