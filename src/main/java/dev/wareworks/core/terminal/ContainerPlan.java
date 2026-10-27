package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Which empty containers a fluid request at a warehouse terminal fills, and how much fluid that serves (M31, issue #21,
 * ADR-073, {@code docs/warehouse-system.md} §3.9.6).
 * <p>
 * <b>Greedy, largest container first, never over-serving.</b> The rows are sorted by capacity, largest first (ties by
 * the caller's key order, so the plan is the same after a restart), and every row takes as many of its containers as
 * still fit <b>wholly</b> into what is left to serve: {@code n = min(available, left / capacity)}. A container is filled
 * whole or not at all, so the served amount is never more than was asked for, and it is always the largest
 * <b>greedy</b> whole amount below it.
 * <p>
 * <b>Greedy is not optimal, on purpose.</b> From one 1 000 mB container and two 750 mB containers it serves 1 000 of
 * 1 500 mB, although the two smaller ones would serve all of it. "Largest first" is the owner's own rule, it is
 * predictable, and with buckets — the container every player has, and the only one in vanilla — it is exact. The dialog
 * says exactly what is served, so nothing is hidden. {@code ContainerPlanTest} pins that counter-example, so nobody
 * "fixes" it by accident.
 * <p>
 * Pure Java with no Minecraft types: the rows are measured by the content side (a real fill on a probe, never a table).
 *
 * @param askedMb          what the player asked for, in millibuckets
 * @param fluidAvailableMb what the bays hold that is not promised yet
 * @param servedMb         what the portions serve together; never more than {@code askedMb} or
 *                         {@code fluidAvailableMb}
 * @param portions         the containers that are filled, in plan order (largest first); never a portion of 0
 * @param shortfall        why {@code servedMb} is less than {@code askedMb}, or {@link Shortfall#NONE}
 * @param containersInStock every candidate container together, whatever its capacity
 * @param smallestStep     capacity of the smallest candidate container in mB, 0 without any
 * @param <K>              item key type
 */
public record ContainerPlan<K>(long askedMb, long fluidAvailableMb, long servedMb, List<Portion<K>> portions,
                               Shortfall shortfall, long containersInStock, int smallestStep) {
    /** Most container kinds one plan names; the dialog and the payload are bounded by it. */
    public static final int MAX_PORTIONS = 8;

    public ContainerPlan {
        askedMb = Math.max(0L, askedMb);
        fluidAvailableMb = Math.max(0L, fluidAvailableMb);
        servedMb = Math.max(0L, servedMb);
        portions = List.copyOf(Objects.requireNonNull(portions, "portions"));
        Objects.requireNonNull(shortfall, "shortfall");
        containersInStock = Math.max(0L, containersInStock);
        smallestStep = Math.max(0, smallestStep);
    }

    /**
     * One kind of empty container the warehouse could fill.
     *
     * @param container  the empty container
     * @param filled     what it becomes once filled with the fluid
     * @param capacityMb how much of the fluid it takes, measured; at least 1
     * @param available  how many of it a new request may still be promised
     */
    public record Row<K>(K container, K filled, int capacityMb, long available) {
        public Row {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(filled, "filled");
            if (capacityMb < 1)
                throw new IllegalArgumentException("capacity must be at least 1 mB: " + capacityMb);
            available = Math.max(0L, available);
        }
    }

    /**
     * How many containers of one kind the plan fills.
     *
     * @param container  the empty container
     * @param filled     what it becomes
     * @param capacityMb how much each takes
     * @param count      how many; at least 1
     */
    public record Portion<K>(K container, K filled, int capacityMb, int count) {
        public Portion {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(filled, "filled");
            if (capacityMb < 1)
                throw new IllegalArgumentException("capacity must be at least 1 mB: " + capacityMb);
            if (count < 1)
                throw new IllegalArgumentException("a portion holds at least one container: " + count);
        }

        /** The fluid this portion takes, in mB. */
        public long millibuckets() {
            return (long) capacityMb * count;
        }
    }

    /** Why a plan serves less than was asked for; the dialog names it with the served amount. */
    public enum Shortfall {
        /** Everything asked for is served. */
        NONE,
        /** The warehouse has fewer usable empty containers than one; nothing can be served. */
        NO_CONTAINERS,
        /** The bays hold less of the fluid than was asked for, after what the open requests still need. */
        NOT_ENOUGH_FLUID,
        /** Every usable empty container is used and the asked amount is still not reached. */
        NOT_ENOUGH_CONTAINERS,
        /** Containers are left, but none of them is small enough for what is left to serve. */
        NO_CONTAINER_FITS
    }

    /**
     * The greedy plan.
     *
     * @param rows             the candidate containers; rows with nothing available are skipped
     * @param askedMb          what the player asked for
     * @param fluidAvailableMb what the bays hold that is not promised yet
     * @param order            the tie-break between two containers of the same capacity
     */
    public static <K> ContainerPlan<K> of(List<Row<K>> rows, long askedMb, long fluidAvailableMb,
            Comparator<? super K> order) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(order, "order");
        long asked = Math.max(0L, askedMb);
        long fluid = Math.max(0L, fluidAvailableMb);
        List<Row<K>> sorted = new ArrayList<>(rows.size());
        long containers = 0L;
        int smallest = 0;
        for (Row<K> row : rows) {
            if (row.available() <= 0L)
                continue;
            sorted.add(row);
            containers = saturatedAdd(containers, row.available());
            smallest = smallest == 0 ? row.capacityMb() : Math.min(smallest, row.capacityMb());
        }
        sorted.sort(Comparator.comparingInt((Row<K> row) -> row.capacityMb()).reversed()
                .thenComparing(Row::container, order));
        long target = Math.min(asked, fluid);
        long served = 0L;
        boolean containersLeft = false;
        List<Portion<K>> portions = new ArrayList<>();
        for (Row<K> row : sorted) {
            if (portions.size() >= MAX_PORTIONS) {
                containersLeft = true;
                break;
            }
            long fits = (target - served) / row.capacityMb();
            long count = Math.min(Math.min(row.available(), fits), Integer.MAX_VALUE);
            if (count < row.available())
                containersLeft = true;
            if (count <= 0L)
                continue;
            portions.add(new Portion<>(row.container(), row.filled(), row.capacityMb(), (int) count));
            served += count * row.capacityMb();
        }
        Shortfall shortfall;
        if (served >= asked)
            shortfall = Shortfall.NONE;
        else if (sorted.isEmpty())
            shortfall = Shortfall.NO_CONTAINERS;
        else if (fluid < asked)
            shortfall = Shortfall.NOT_ENOUGH_FLUID;
        else if (!containersLeft)
            shortfall = Shortfall.NOT_ENOUGH_CONTAINERS;
        else
            shortfall = Shortfall.NO_CONTAINER_FITS;
        return new ContainerPlan<>(asked, fluid, served, portions, shortfall, containers, smallest);
    }

    /** No container, nothing served: what a warehouse without the fluid or without containers answers. */
    public static <K> ContainerPlan<K> none(long askedMb, long fluidAvailableMb) {
        return new ContainerPlan<>(askedMb, fluidAvailableMb, 0L, List.of(), askedMb <= 0L ? Shortfall.NONE
                : Shortfall.NO_CONTAINERS, 0L, 0);
    }

    /** How many containers the plan fills, over all portions. */
    public long containers() {
        long total = 0L;
        for (Portion<K> portion : portions)
            total += portion.count();
        return total;
    }

    /** Whether the plan serves anything at all. */
    public boolean servesAnything() {
        return servedMb > 0L;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }
}
