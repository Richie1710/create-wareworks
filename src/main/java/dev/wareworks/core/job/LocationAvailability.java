package dev.wareworks.core.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Whether a location can be used in <b>this</b> planning pass, asked of the world once per location ({@code
 * docs/warehouse-system.md} §7, M22, issue #2).
 * <p>
 * <b>Why this exists.</b> "Available" is the most expensive question the planner asks about a location: the content
 * layer answers it with a route question from the crane's own point plus a chunk and block-entity lookup. The planner
 * asks it per <b>(input station × item type)</b> over every storage location, so a warehouse with four inputs holding
 * three item types each asked it twelve times about every chest — and since M22 a route question on a warehouse that
 * splits is a graph search rather than a subtraction, which is what makes paying for it twelve times the wrong shape.
 * <p>
 * Nothing about availability can change <b>during</b> one pass: no item moves, no chunk loads and no rail is laid
 * while the planner ranks. So the answer is taken once per location and kept for the rest of the pass, and the list of
 * storage locations that passed is built once as well — which is exactly the "build the candidate list once per
 * dispatch run and cache each location's availability for the run" the M5 release audit put on the list and a
 * warehouse of many aisles makes due.
 * <p>
 * <b>One pass, one instance.</b> A {@link PlannerInput} is the input of a single planning pass and wraps its
 * {@code available} predicate in one of these when it is built, so "per run" is a property of the type rather than a
 * rule somebody has to remember. An instance is not thread-safe and is not meant to outlive its pass.
 * <p>
 * <b>Measured</b>, in the run that pays for it — 64 storage locations all dedicated to something else, four input
 * stations holding three item types each, so every one of the twelve combinations ranks the whole warehouse: <b>772</b>
 * questions put to the world before, <b>68</b> after, and the candidate list derived <b>once</b>
 * ({@link #questions()}, {@link #probes()}, {@link #listWalks()};
 * {@code JobPlannerTest#theWorkOfOneDispatchRunDoesNotGrowWithTheItemTypesItRanks}).
 *
 * @param <L> location type
 */
public final class LocationAvailability<L> implements Predicate<L> {
    private final Predicate<? super L> probe;
    private final Map<L, Boolean> known = new HashMap<>();
    private int probes;
    private int questions;
    private int listWalks;

    /** The source list {@link #available(List)} last answered about, compared by identity, and its answer. */
    private List<L> lastSource;
    private Available<L> lastAnswer;

    private LocationAvailability(Predicate<? super L> probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /**
     * Wraps a predicate for <b>one</b> pass: a fresh cache every time, and a wrapper handed in is unwrapped rather
     * than nested, so no pass ever reads what another pass was told.
     * <p>
     * Deliberately not "hand back the wrapper you were given": {@link PlannerInput.Builder#build()} calls this, and a
     * builder that is kept and built twice — which nothing in {@code src/main} does, and which a caller planning
     * twice in one tick would do by accident — would then plan its second pass on the first pass's answers about a
     * world that has moved on since. Wrapping again costs that caller the questions it would have asked before M22;
     * sharing would cost it a wrong answer, and only one of those two failures is safe.
     */
    @SuppressWarnings("unchecked")
    public static <L> LocationAvailability<L> of(Predicate<? super L> probe) {
        Objects.requireNonNull(probe, "probe");
        if (probe instanceof LocationAvailability<?> wrapped)
            return new LocationAvailability<>((Predicate<? super L>) wrapped.probe);
        return new LocationAvailability<>(probe);
    }

    @Override
    public boolean test(L location) {
        questions++;
        Boolean answer = known.get(location);
        if (answer != null)
            return answer;
        probes++;
        boolean available = probe.test(location);
        known.put(location, available);
        return available;
    }

    /**
     * The locations of {@code source} that are available, with the index each of them has in {@code source} — which is
     * the planner's last ranking key, so filtering the list cannot reorder equal candidates.
     * <p>
     * Derived once per list: the planner hands in the same {@link PlannerInput#storageLocations()} instance on every
     * call of one pass, so the walk happens on the first question about it and the rest read the answer.
     */
    public Available<L> available(List<L> source) {
        Objects.requireNonNull(source, "source");
        if (lastSource == source) {
            // What the walk this answer replaces would have asked the world, counted rather than estimated: the
            // pre-M22 loop tested every location of the list again for every (station x item type) it ranked.
            questions += source.size();
            return lastAnswer;
        }
        listWalks++;
        List<L> kept = new ArrayList<>(source.size());
        int[] ranks = new int[source.size()];
        boolean skipped = false;
        for (int rank = 0; rank < source.size(); rank++) {
            L location = source.get(rank);
            if (!test(location)) {
                skipped = true;
                continue;
            }
            ranks[kept.size()] = rank;
            kept.add(location);
        }
        lastSource = source;
        lastAnswer = new Available<>(List.copyOf(kept), ranks, skipped);
        return lastAnswer;
    }

    /** How many locations the world was really asked about — what a test asserts "once per run" with. */
    public int probes() {
        return probes;
    }

    /**
     * How often this pass <b>needed</b> an availability answer — which is exactly how often the pre-M22 planner asked
     * the world, because it had nowhere to keep one. A cached list answer counts as the whole walk it replaces.
     * <p>
     * It exists so that "the work of one dispatch run" is a measured pair of numbers ({@code questions} before,
     * {@link #probes()} after) in a test rather than a claim in a commit message.
     */
    public int questions() {
        return questions;
    }

    /**
     * How often the list of available storage locations was really derived. One per planning pass, however many item
     * types and input stations that pass ranks — the other half of the M22 scaling item, and the number that goes up
     * again if {@link #available(List)} is ever handed a freshly built list per candidate scan.
     */
    public int listWalks() {
        return listWalks;
    }

    @Override
    public String toString() {
        return "LocationAvailability[" + known.size() + " known, " + probes + " asked of " + questions + " questions, "
                + listWalks + " list walks]";
    }

    /**
     * The available part of a list of locations, in its order.
     *
     * @param <L> location type
     */
    public static final class Available<L> {
        private final List<L> locations;
        private final int[] ranks;
        private final boolean anySkipped;

        private Available(List<L> locations, int[] ranks, boolean anySkipped) {
            this.locations = locations;
            this.ranks = ranks;
            this.anySkipped = anySkipped;
        }

        public List<L> locations() {
            return locations;
        }

        /** The index the location at {@code index} has in the list this was derived from. */
        public int rankOf(int index) {
            return ranks[index];
        }

        /**
         * Whether at least one location of the source list was left out. The planner notes that in its survey, so a
         * warehouse whose chests the crane cannot reach still reports "full" rather than "no matching filter".
         */
        public boolean anySkipped() {
            return anySkipped;
        }
    }
}
