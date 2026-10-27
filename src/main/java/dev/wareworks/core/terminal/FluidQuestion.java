package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a fluid request at a warehouse terminal would mean, asked <b>before</b> anything is promised (M31, issue #21,
 * ADR-073, {@code docs/warehouse-system.md} §3.9.6): which empty containers it fills, how many crane trips that takes,
 * how much of the asked amount is served and why not all of it, whether the station can hold what arrives, what it
 * takes out of a reserve, and whether containers would be made first.
 * <p>
 * <b>A fluid request always asks.</b> There is no click that skips this question, unlike an item click's Alt: the
 * dialog is the one place the container economy is explained — a bucket of lava is a crane trip and an empty bucket
 * out of the racks — and a player who asked for 16 buckets should know it takes 16 trips. The answer is a
 * {@link FluidAcknowledgement} that names the numbers, and the server measures the question again for every answer, so
 * an old "yes" never covers a changed warehouse.
 * <p>
 * Pure Java with no Minecraft types; the numbers are the server's own.
 *
 * @param fluid              the fluid asked for
 * @param askedMb            what the player asked for, in mB
 * @param servedMb           what the request would really serve: the containers from stock plus any made first
 * @param portions           the empty containers taken from stock, largest first
 * @param make               the containers that would be made first, if the warehouse has too few and can make more
 * @param trips              crane trips over all containers, made ones included
 * @param perTrip            containers one trip carries (of the largest container)
 * @param shortfall          why {@code servedMb} is less than {@code askedMb}, or {@link ContainerPlan.Shortfall#NONE}
 * @param fluidAvailableMb   what the bays hold of the fluid that is not promised yet
 * @param containersInStock  usable empty containers in stock, whatever their size
 * @param smallestStep       capacity of the smallest usable container in mB, 0 without any
 * @param stationSlots       how many of the filled containers the destination holds right now
 * @param fluidFromReserve   mB of the fluid that would come out of a stock rule's reserve
 * @param fluidReserved      mB of the fluid that reserve holds back in all
 * @param emptiesFromReserve per container kind, what would come out of a reserve on that container
 * @param <K>                item key type
 * @param <F>                fluid key type
 */
public record FluidQuestion<K, F>(F fluid, long askedMb, long servedMb, List<ContainerPlan.Portion<K>> portions,
                                  Optional<MakeOffer<K>> make, int trips, int perTrip,
                                  ContainerPlan.Shortfall shortfall, long fluidAvailableMb, long containersInStock,
                                  int smallestStep, int stationSlots, long fluidFromReserve, long fluidReserved,
                                  List<ReservedEmpties<K>> emptiesFromReserve) {
    public FluidQuestion {
        Objects.requireNonNull(fluid, "fluid");
        askedMb = Math.max(0L, askedMb);
        servedMb = Math.max(0L, servedMb);
        portions = bounded(portions);
        make = make == null ? Optional.empty() : make;
        trips = Math.max(0, trips);
        perTrip = Math.max(1, perTrip);
        Objects.requireNonNull(shortfall, "shortfall");
        fluidAvailableMb = Math.max(0L, fluidAvailableMb);
        containersInStock = Math.max(0L, containersInStock);
        smallestStep = Math.max(0, smallestStep);
        stationSlots = Math.max(0, stationSlots);
        fluidFromReserve = Math.max(0L, fluidFromReserve);
        fluidReserved = Math.max(0L, fluidReserved);
        emptiesFromReserve = bounded(emptiesFromReserve);
    }

    /**
     * Containers the warehouse would make first and then fill (the owner's decision 3: no empty containers in stock,
     * but a production pattern makes them).
     *
     * @param container  the empty container a pattern makes
     * @param filled     what it becomes once filled
     * @param capacityMb how much each takes
     * @param count      how many would be made; at least 1
     */
    public record MakeOffer<K>(K container, K filled, int capacityMb, int count) {
        public MakeOffer {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(filled, "filled");
            capacityMb = Math.max(1, capacityMb);
            if (count < 1)
                throw new IllegalArgumentException("an offer makes at least one container: " + count);
        }

        /** The fluid the made containers take, in mB. */
        public long millibuckets() {
            return (long) capacityMb * count;
        }
    }

    /**
     * What a request would take of one container kind out of a stock rule's reserve (M15): named, never held back,
     * because a reserve holds nothing back from a player at the terminal.
     *
     * @param container   the empty container
     * @param fromReserve how many of it would come out of the reserve
     * @param reserved    how many of it the reserve holds back in all
     */
    public record ReservedEmpties<K>(K container, long fromReserve, long reserved) {
        public ReservedEmpties {
            Objects.requireNonNull(container, "container");
            fromReserve = Math.max(0L, fromReserve);
            reserved = Math.max(0L, reserved);
        }
    }

    /** The question about a click nothing can be served for at all. */
    public static <K, F> FluidQuestion<K, F> nothing(F fluid, long askedMb, ContainerPlan<K> plan, int stationSlots) {
        return new FluidQuestion<>(fluid, askedMb, 0L, List.of(), Optional.empty(), 0, 1, plan.shortfall(),
                plan.fluidAvailableMb(), plan.containersInStock(), plan.smallestStep(), stationSlots, 0L, 0L,
                List.of());
    }

    /** Whether a Fetch can carry this request out at all, i.e. whether it serves anything. */
    public boolean canFetch() {
        return servedMb > 0L;
    }

    /** How many containers would be filled in all, made ones included. */
    public long containers() {
        long total = make.map(MakeOffer::count).orElse(0);
        for (ContainerPlan.Portion<K> portion : portions)
            total += portion.count();
        return total;
    }

    /** The containers per portion, in plan order: what an answer names. */
    public List<Integer> portionCounts() {
        List<Integer> counts = new ArrayList<>(portions.size());
        for (ContainerPlan.Portion<K> portion : portions)
            counts.add(portion.count());
        return List.copyOf(counts);
    }

    /** How many containers would be made first; 0 without an offer. */
    public int makeCount() {
        return make.map(MakeOffer::count).orElse(0);
    }

    /**
     * What is left of the asked amount that no container in stock is small enough for:
     * {@code min(asked, fluidAvailable) − served}; the number the "smallest step" line names.
     */
    public long unservedMb() {
        return Math.max(0L, Math.min(askedMb, fluidAvailableMb) - servedMb);
    }

    /** Whether more containers would arrive than the destination holds right now, so the crane would wait. */
    public boolean overflowsStation() {
        return containers() > stationSlots;
    }

    /** The answer that accepts exactly this question. */
    public FluidAcknowledgement acknowledgement() {
        return FluidAcknowledgement.of(this);
    }

    private static <T> List<T> bounded(List<T> list) {
        if (list == null || list.isEmpty())
            return List.of();
        return List.copyOf(list.size() <= ContainerPlan.MAX_PORTIONS ? list
                : list.subList(0, ContainerPlan.MAX_PORTIONS));
    }
}
