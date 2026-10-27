package dev.wareworks.content.controller;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;

/**
 * What the open <b>fill requests</b> of a warehouse have promised, worked out from the requests and the cranes' jobs
 * (M31, issue #21, ADR-073, design D5). Never saved: it is rebuilt per question from the request queue and the jobs the
 * dispatch tracks, which is M30's "always rebuilt from the crane's job" rule applied once more, and it costs O(jobs +
 * fill requests).
 * <p>
 * An open fill request promises three things, and this ledger answers for two of them (the third, the station's room,
 * is the planner's own question):
 * <ul>
 * <li><b>empty containers</b> in the racks — {@link #owedEmpties}, which the item availability of the empty key
 * subtracts like the remaining amount of an item request, so that an item click on "Bucket" cannot take the buckets a
 * fill request still needs;</li>
 * <li><b>fluid</b> in the bays — {@link #owedFluid} for the warehouse as a whole, and {@link #reservedAtBay} for the
 * fluid a planned trip will take out of one bay.</li>
 * </ul>
 * <b>Each empty container is owed exactly once in every phase of its trip</b>, which is the property
 * {@code FillLedgerTest} walks: planned (its stock reservation backs the request and is not subtracted on its own;
 * the request owes it), picked (it left the index, so the request must stop owing it: {@link #heldEmpty}), filled
 * ({@link #heldFilled}: it is no empty container any more) and delivered (the request's remaining amount went down).
 * <p>
 * Pure Java over generic keys, so it is unit tested without a world; the content layer supplies the recipe of a filled
 * key ({@code FillRecipes}).
 *
 * @param <K> item key type
 * @param <F> fluid key type
 * @param <L> location type
 */
public final class FillLedger<K, F, L> {
    /**
     * What one filled container of a fill request is made of.
     *
     * @param emptyKey     the empty container that is filled
     * @param fluid        the fluid it is filled with
     * @param millibuckets how much of it one container takes, at least 1
     * @param <K>          item key type
     * @param <F>          fluid key type
     */
    public record Recipe<K, F>(K emptyKey, F fluid, int millibuckets) {
        public Recipe {
            Objects.requireNonNull(emptyKey, "emptyKey");
            Objects.requireNonNull(fluid, "fluid");
            if (millibuckets < 1)
                throw new IllegalArgumentException("millibuckets must be at least 1: " + millibuckets);
        }
    }

    private final Map<K, Long> owedEmpties = new HashMap<>();
    private final Map<F, Long> owedFluid = new HashMap<>();
    private final Map<L, Long> reservedAtBay = new HashMap<>();
    private final Map<UUID, Integer> heldEmpty = new HashMap<>();
    private final Map<UUID, Integer> heldFilled = new HashMap<>();

    private FillLedger() {
    }

    /** A ledger with nothing promised: the answer of a warehouse without fill requests. */
    public static <K, F, L> FillLedger<K, F, L> empty() {
        return new FillLedger<>();
    }

    /**
     * The ledger of {@code fillRequests} and the jobs the cranes carry.
     *
     * @param fillRequests the open fill requests ({@link RetrievalRequest#isFill()}); any other request is ignored
     * @param jobs         the jobs the dispatch tracks (the crane's current job); only {@code FILL} jobs that still
     *                     serve one of {@code fillRequests} count
     * @param recipes      the recipe of a filled key, or empty when it cannot be filled; such a request owes nothing
     */
    public static <K, F, L> FillLedger<K, F, L> of(Collection<? extends RetrievalRequest<K, ?>> fillRequests,
            Collection<? extends TransportJob<K, L>> jobs, Function<? super K, Optional<Recipe<K, F>>> recipes) {
        Objects.requireNonNull(fillRequests, "fillRequests");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(recipes, "recipes");
        FillLedger<K, F, L> ledger = new FillLedger<>();
        Map<UUID, RetrievalRequest<K, ?>> byId = new HashMap<>();
        for (RetrievalRequest<K, ?> request : fillRequests) {
            if (request != null && request.isFill())
                byId.put(request.id(), request);
        }
        if (byId.isEmpty())
            return ledger;
        Map<UUID, Recipe<K, F>> recipeOf = new HashMap<>();
        for (RetrievalRequest<K, ?> request : byId.values())
            recipes.apply(request.key()).ifPresent(recipe -> recipeOf.put(request.id(), recipe));
        for (TransportJob<K, L> job : jobs) {
            if (job == null || job.type() != JobType.FILL || job.requestId().isEmpty())
                continue;
            UUID id = job.requestId().get();
            RetrievalRequest<K, ?> request = byId.get(id);
            Recipe<K, F> recipe = recipeOf.get(id);
            if (request == null || recipe == null)
                continue;
            if (job.key().equals(request.key())) {
                ledger.heldFilled.merge(id, job.heldAmount(), Integer::sum);
            } else if (job.key().equals(recipe.emptyKey())) {
                if (job.picked())
                    ledger.heldEmpty.merge(id, job.heldAmount(), Integer::sum);
                // The fluid a trip on its way to the bay will take out of it, planned or picked: the bay must not be
                // offered to a second trip for the same millibuckets.
                int containers = job.picked() ? job.heldAmount() : job.plannedAmount();
                if (containers > 0)
                    ledger.reservedAtBay.merge(job.target(), (long) containers * recipe.millibuckets(), Long::sum);
            }
        }
        for (RetrievalRequest<K, ?> request : byId.values()) {
            Recipe<K, F> recipe = recipeOf.get(request.id());
            if (recipe == null)
                continue;
            long filled = ledger.heldFilled(request.id());
            long empties = Math.max(0L, request.remaining() - ledger.heldEmpty(request.id()) - filled);
            if (empties > 0)
                ledger.owedEmpties.merge(recipe.emptyKey(), empties, Long::sum);
            long fluid = Math.max(0L, request.remaining() - filled) * recipe.millibuckets();
            if (fluid > 0)
                ledger.owedFluid.merge(recipe.fluid(), fluid, Long::sum);
        }
        return ledger;
    }

    /** Empty containers of request {@code requestId} already picked and still empty, in a handling head. */
    public int heldEmpty(UUID requestId) {
        return heldEmpty.getOrDefault(Objects.requireNonNull(requestId, "requestId"), 0);
    }

    /** Containers of request {@code requestId} already filled and in a handling head, not delivered yet. */
    public int heldFilled(UUID requestId) {
        return heldFilled.getOrDefault(Objects.requireNonNull(requestId, "requestId"), 0);
    }

    /**
     * Empty containers of {@code emptyKey} the open fill requests still need <b>out of the racks</b>: their remaining
     * amounts minus what is already on a head, empty or filled.
     */
    public long owedEmpties(K emptyKey) {
        return owedEmpties.getOrDefault(Objects.requireNonNull(emptyKey, "emptyKey"), 0L);
    }

    /** {@link #owedEmpties} of every empty key that is owed at all (unmodifiable; an absent key owes 0). */
    public Map<K, Long> owedEmptiesByKey() {
        return Collections.unmodifiableMap(owedEmpties);
    }

    /**
     * Millibuckets of {@code fluid} the open fill requests still need <b>out of the bays</b>: their remaining amounts
     * minus what is already filled, in millibuckets.
     */
    public long owedFluid(F fluid) {
        return owedFluid.getOrDefault(Objects.requireNonNull(fluid, "fluid"), 0L);
    }

    /** Millibuckets the fill trips on their way to {@code bay} will take out of it. */
    public long reservedAtBay(L bay) {
        return reservedAtBay.getOrDefault(Objects.requireNonNull(bay, "bay"), 0L);
    }

    /** Whether nothing at all is promised. */
    public boolean isEmpty() {
        return owedEmpties.isEmpty() && owedFluid.isEmpty() && reservedAtBay.isEmpty();
    }
}
