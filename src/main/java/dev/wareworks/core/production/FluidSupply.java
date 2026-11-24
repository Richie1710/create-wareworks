package dev.wareworks.core.production;

import java.util.Optional;

/**
 * What the warehouse can offer a production plan of fluid (issue #55, ADR-089): how the fluid of one {@link FluidCell}
 * reaches a station, and how much of the station's buffer a plan may fill. The content layer answers it from the fluid
 * index, the fill ledger and the container recipes; the planner only does the arithmetic ({@link #delivery}).
 * <p>
 * <b>Fluid travels in whole filled containers.</b> A fluid is never carried as fluid: the crane picks empty containers
 * out of a rack, a fluid bay fills them and the same trip drops them into the station's item buffer
 * ({@code docs/warehouse-system.md} §3.5). A cell is therefore planned as a <i>supply line of filled containers</i>
 * ({@link NodeLine#fluid}), counted in containers and sized with the real content of the container the supply picked
 * ({@link FluidCell#containersFor}).
 * <p>
 * Asked at most once per fluid per plan; never per tick. Pure Java, so the planner's fluid arithmetic is a unit test.
 *
 * @param <K> item key type
 */
public interface FluidSupply<K> {
    /** Containers that may be on their way to or waiting in one station's buffer at the same time. */
    int MAX_IN_FLIGHT = 2;

    /**
     * How {@code fluid} (a registry id) reaches a station right now, or empty when it cannot at all: an unknown fluid (its
     * mod was removed) or one no container is filled with.
     */
    Optional<Delivery<K>> delivery(String fluid);

    /**
     * Slots of a production station's item buffer ({@code productionBufferSlots}); {@link Integer#MAX_VALUE} when the
     * caller does not know it, which switches the slot check off.
     */
    default int bufferSlots() {
        return Integer.MAX_VALUE;
    }

    /** How many items of {@code key} one buffer slot holds (a filled bucket: 1). */
    default int stackSize(K key) {
        return 64;
    }

    /**
     * How one fluid reaches a station.
     *
     * @param container   the filled container that is delivered, e.g. a water bucket
     * @param empty       the empty container the crane takes out of a rack to fill it
     * @param containerMb millibuckets one filled container holds, at least 1: the real content, never a constant
     * @param fluidMb     millibuckets of the fluid the bays hold that this plan may still claim (after what open fill
     *                    requests and fluid lines have promised), never negative
     * @param <K>         item key type
     */
    record Delivery<K>(K container, K empty, int containerMb, long fluidMb) {
        public Delivery {
            java.util.Objects.requireNonNull(container, "container");
            java.util.Objects.requireNonNull(empty, "empty");
            if (containerMb < 1)
                throw new IllegalArgumentException("containerMb must be at least 1: " + containerMb);
            fluidMb = Math.max(0L, fluidMb);
        }
    }

    /** Nothing can be delivered: the answer of an input built without fluid, which refuses every fluid cell. */
    static <K> FluidSupply<K> none() {
        return fluid -> Optional.empty();
    }
}
