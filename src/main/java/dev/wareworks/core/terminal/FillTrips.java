package dev.wareworks.core.terminal;

import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * How many crane trips a fluid request takes (M31, issue #21, ADR-073): every trip carries at most as many containers
 * as the handling head may hold of both the empty and the filled container, so one portion of {@code count} containers
 * takes {@code ⌈count / perTrip⌉} trips. A filled bucket stacks to one, so a bucket request takes one trip per bucket
 * (the owner's decision 1: bulk goes through pipes). The dialog names the number, so the pace is never a surprise.
 */
public final class FillTrips {
    private FillTrips() {
    }

    /**
     * The trips of {@code portions}.
     *
     * @param perTrip how many containers of a portion one trip carries; values below 1 count as 1
     */
    public static <K> int of(List<ContainerPlan.Portion<K>> portions, ToIntFunction<ContainerPlan.Portion<K>> perTrip) {
        Objects.requireNonNull(portions, "portions");
        Objects.requireNonNull(perTrip, "perTrip");
        long trips = 0L;
        for (ContainerPlan.Portion<K> portion : portions)
            trips += of(portion.count(), perTrip.applyAsInt(portion));
        return (int) Math.min(Integer.MAX_VALUE, trips);
    }

    /** The trips of {@code count} containers at {@code perTrip} a trip; values below 1 count as 1. */
    public static int of(int count, int perTrip) {
        if (count <= 0)
            return 0;
        int carried = Math.max(1, perTrip);
        return (int) ((count + (long) carried - 1L) / carried);
    }
}
