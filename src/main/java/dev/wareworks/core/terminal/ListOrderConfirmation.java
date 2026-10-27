package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.wareworks.core.production.PlanBudget;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionPlan;
import dev.wareworks.core.production.ProductionPlanInput;
import dev.wareworks.core.production.ProductionPlanResult;
import dev.wareworks.core.production.ProductionPlanner;
import dev.wareworks.core.production.SequentialPlans;
import dev.wareworks.core.production.StationPattern;

/**
 * What pressing Fetch on a clipboard would really mean, measured entry by entry before anything is requested
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19, ADR-036, amended by issue #36, ADR-069).
 * <p>
 * This is the question the project owner settled on: <b>partial stock asks first</b> ("the list wants 2000 cobblestone
 * and the warehouse holds 1300 — fetch what there is?") and <b>producible items ask too</b>. Since issue #36 the answer
 * is one {@link Line} per entry that still wants something — its split into what the racks give, what production
 * makes and what is missing, the planner's reason for what is missing with the item it names, and the materials it is
 * short of — which the terminal shows as a table, and every total is <b>derived</b> from those lines, so the numbers
 * and the rows can never disagree.
 * <p>
 * <b>Measured with the click's planner</b> ({@link #measure}). Each entry, in list order, first takes what the racks
 * still hold of its item and then has the rest planned by the very {@link ProductionPlanner} a click runs, against one
 * shared ledger ({@link SequentialPlans#forList}): an entry whose ingredients have to be made by further patterns first
 * (a lantern from nuggets made of ingots) is producible exactly when a click would make it, a stopped item is refused
 * exactly as a click refuses it, and a chain's ingredients are spent before the next entry is measured, so "Iron Nugget
 * x16" and then "Lantern x2" are not both promised the same nuggets. That is the one invariant this record is worth
 * having: {@code wanted == serveable + producing + missing}, so no total can ever name an amount no order could reach.
 * <p>
 * The answer travels back as three numbers — the production one counted in whole runs ({@link #madeTotal()}) — and
 * is <b>measured again</b> before the order starts ({@link #covers}): a warehouse that gained stock in the meantime
 * passes, one that lost stock asks again. That is the M15/M20 round trip applied to a list, not a second mechanism.
 *
 * @param lines          one per entry that still wants something, in list order, at most {@value #MAX_LINES}
 * @param entriesDropped orderable entries the entry cap left on the clipboard ({@link ListOrder#dropped()}): they are
 *                       <b>not</b> part of any line here, because the order never took them. They are part of the
 *                       question all the same — a player presses Fetch on "this list" and has to be told in the
 *                       dialog they consent in that part of their list was left out (M23 review fix)
 * @param <K>            item key type
 */
public record ListOrderConfirmation<K>(List<Line<K>> lines, int entriesDropped) {
    /** Most lines a question holds: the hard ceiling of a list order's entries ({@link ListOrder#MAX_ENTRIES}). */
    public static final int MAX_LINES = ListOrder.MAX_ENTRIES;
    /** Most materials one line names for the Missing section. */
    public static final int MAX_LINE_MATERIALS = 3;
    /**
     * Most chains one entry is planned with. A plan the stock or an item's maximum clamped is followed by another for
     * the rest, which then names why the rest cannot be had; the bound keeps a list's measurement linear in its entries.
     */
    public static final int MAX_CHAINS_PER_LINE = 4;
    /** What a raw material is worth in the what-if plan that measures how much of it is missing. */
    static final long UNLIMITED = Long.MAX_VALUE / 4L;

    public ListOrderConfirmation {
        lines = lines == null ? List.of()
                : List.copyOf(lines.size() <= MAX_LINES ? lines : lines.subList(0, MAX_LINES));
        entriesDropped = Math.max(0, entriesDropped);
    }

    /**
     * One entry of the list as the question measures it.
     * <p>
     * The canonical constructor keeps it consistent whatever it is given — a decoded payload included: every amount is
     * at least 0, the racks never give more than is wanted, production never more than the rest, and
     * {@link #missing()} is <b>derived</b> from the other three (the value passed is ignored). A reason and its item
     * are kept only while something is missing, an item only with a reason, and the materials only while something is
     * missing, at most {@value #MAX_LINE_MATERIALS} with distinct keys.
     *
     * @param order     the entry's index in the list order's lines ({@link ListOrder#lines()}), 0-based: what the
     *                  table sorts ties by and what the Missing section names entries by
     * @param key       the item the entry asks for
     * @param wanted    how many of it are still outstanding
     * @param fromStock what the racks give of that
     * @param produced  what production would make for it: the promise, part of what the Yes agrees to
     * @param made      what the chains' roots make in whole runs, at least {@code produced}; the surplus lands in a rack
     * @param missing   what cannot be had at all, {@code wanted - fromStock - produced}
     * @param refusal   why the missing part cannot be made, as the planner gave it; empty when nothing was refused
     *                  (the racks simply hold too little and no plan was tried or every plan was clamped)
     * @param about     the item {@link #refusal()} names, regularly not {@link #key()}: "Iron Ingot is missing"
     * @param materials what the entry is short of, for the Missing section
     * @param <K>       item key type
     */
    public record Line<K>(int order, K key, long wanted, long fromStock, long produced, long made, long missing,
                          Optional<PlanRefusal> refusal, Optional<K> about, List<Material<K>> materials) {
        public Line {
            Objects.requireNonNull(key, "key");
            order = Math.max(0, order);
            wanted = Math.max(0L, wanted);
            fromStock = Math.max(0L, Math.min(fromStock, wanted));
            produced = Math.max(0L, Math.min(produced, wanted - fromStock));
            missing = wanted - fromStock - produced;
            made = Math.max(made, produced);
            refusal = missing > 0L && refusal != null ? refusal : Optional.empty();
            about = refusal.isPresent() && about != null ? about : Optional.empty();
            materials = missing > 0L && materials != null ? distinct(materials) : List.of();
        }

        private static <K> List<Material<K>> distinct(List<Material<K>> materials) {
            List<Material<K>> kept = new ArrayList<>(Math.min(materials.size(), MAX_LINE_MATERIALS));
            Set<K> seen = new HashSet<>();
            for (Material<K> material : materials) {
                if (kept.size() >= MAX_LINE_MATERIALS)
                    break;
                if (material != null && seen.add(material.key()))
                    kept.add(material);
            }
            return List.copyOf(kept);
        }

        /** What the warehouse can do for this entry. */
        public ListEntryStatus status() {
            return ListEntryStatus.of(fromStock, produced, missing);
        }

        /** Whether the warehouse falls short of this entry, i.e. whether it is one "fetch what there is" is about. */
        public boolean isShort() {
            return missing > 0L;
        }

        /** What the entry gets: from the racks and from production. */
        public long obtainable() {
            return fromStock + produced;
        }
    }

    /**
     * A material one entry is short of: a raw material its chain cannot get past, or the entry's own item when no
     * chain could make it at all.
     *
     * @param key    the material
     * @param amount how many of it are missing, at least 1
     * @param <K>    item key type
     */
    public record Material<K>(K key, long amount) {
        public Material {
            Objects.requireNonNull(key, "key");
            amount = Math.max(1L, amount);
        }
    }

    /** The question for a list with nothing left to ask about. */
    public static <K> ListOrderConfirmation<K> none() {
        return new ListOrderConfirmation<>(List.of(), 0);
    }

    /**
     * The question {@code lines} raise against the aisle {@code base} describes. Lines that are already delivered in
     * full are not part of it, so resuming a half-finished order asks only about what is left.
     * <p>
     * Each entry, in list order, takes what is left of its item ({@link SequentialPlans#take}) and has the rest planned
     * with the click's planner against what is left then ({@link SequentialPlans#plan}); an accepted chain spends its
     * ingredients before the next entry is measured. A plan that was clamped — the stock or an item's maximum allowed
     * less — is followed by another for the rest, at most {@value #MAX_CHAINS_PER_LINE} per entry, and the refusal that
     * ends the walk is the entry's reason.
     * <p>
     * <b>The materials</b> of an entry refused with {@link PlanRefusal#MISSING_INGREDIENT} come from a <b>what-if</b>
     * plan for its missing part that does not touch the ledger: every raw material (one no pattern of the aisle makes)
     * is worth as much as anyone could ask for, everything else what is left of it. The raw leaves of that plan the
     * ledger cannot pay are what the entry is short of, at most {@value #MAX_LINE_MATERIALS}. For any other reason, or
     * when the what-if finds nothing, the entry's own item is the material: bring the item itself. Each entry's
     * materials are measured against what the earlier entries left; a leftover two short entries could both use is
     * counted for each, so the Missing total can overstate by such leftovers, and never understates.
     * <p>
     * <b>Cost.</b> At most {@value #MAX_CHAINS_PER_LINE} + 1 plans per entry, each the planner's own bounded walk: at
     * the default 128 entries at most 640 plans, at the 1024 ceiling at most 5120, once per Fetch press within the
     * menu's action budget, and never per tick. Nothing is ordered, claimed or saved.
     *
     * @param dropped orderable entries the entry cap left on the clipboard ({@link ListOrder#dropped()}); they are not
     *                among {@code lines} and are reported rather than measured
     * @param base    the aisle's one snapshot, exactly as a click builds it — its patterns, what a player may have of
     *                the racks, the bounds, the free order slots, the paused items and the headroom. It is never mutated
     */
    public static <K, L> ListOrderConfirmation<K> measure(List<ListLine<K>> lines, int dropped,
            ProductionPlanInput<K, L> base) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(base, "base");
        SequentialPlans<K, L> plans = SequentialPlans.forList(base);
        Set<K> made = new HashSet<>();
        for (StationPattern<K, L> pattern : base.patterns())
            made.add(pattern.result());
        List<Line<K>> measured = new ArrayList<>(Math.min(lines.size(), MAX_LINES));
        for (int order = 0; order < lines.size() && measured.size() < MAX_LINES; order++) {
            ListLine<K> line = Objects.requireNonNull(lines.get(order), "line");
            if (line.isComplete())
                continue;
            K key = line.key();
            long want = line.outstanding();
            long fromStock = plans.take(key, want);
            long rest = want - fromStock;
            long produced = 0L;
            long output = 0L;
            Optional<PlanRefusal> refusal = Optional.empty();
            Optional<K> about = Optional.empty();
            for (int chains = 0; rest > 0L && chains < MAX_CHAINS_PER_LINE; chains++) {
                ProductionPlanResult<K, L> result = plans.plan(key, rest);
                if (result.plan().isEmpty()) {
                    refusal = result.refusal();
                    about = result.about();
                    break;
                }
                ProductionPlan<K, L> plan = result.plan().get();
                produced += plan.rootPromise();
                output += plan.root().output();
                rest -= plan.rootPromise();
            }
            List<Material<K>> materials = rest > 0L ? materials(base, plans, made, key, rest, refusal)
                    : List.of();
            measured.add(new Line<>(order, key, want, fromStock, produced, output, rest, refusal, about, materials));
        }
        return new ListOrderConfirmation<>(measured, dropped);
    }

    /** What one short entry is short of ({@link #measure}). */
    private static <K, L> List<Material<K>> materials(ProductionPlanInput<K, L> base, SequentialPlans<K, L> plans,
            Set<K> made, K key, long rest, Optional<PlanRefusal> refusal) {
        List<Material<K>> own = List.of(new Material<>(key, rest));
        if (refusal.filter(found -> found == PlanRefusal.MISSING_INGREDIENT).isEmpty())
            return own;
        ProductionPlanInput<K, L> whatIf = new ProductionPlanInput<>(base.patterns(),
                PlanBudget.of(candidate -> made.contains(candidate) ? plans.available(candidate) : UNLIMITED),
                base.limits(), base.freeOrderSlots(), base.orderSlots(), base.pausedResults(), base.headroom());
        ProductionPlanResult<K, L> result = ProductionPlanner.plan(whatIf, key, rest);
        if (result.plan().isEmpty())
            return own;
        List<Material<K>> found = new ArrayList<>(MAX_LINE_MATERIALS);
        for (Map.Entry<K, Long> leaf : result.plan().get().leafDemand().entrySet()) {
            if (found.size() >= MAX_LINE_MATERIALS)
                break;
            if (made.contains(leaf.getKey()))
                continue;
            long shortOf = leaf.getValue() - plans.available(leaf.getKey());
            if (shortOf > 0L)
                found.add(new Material<>(leaf.getKey(), shortOf));
        }
        return found.isEmpty() ? own : List.copyOf(found);
    }

    // --- totals, all derived from the lines ------------------------------------------------------------------------

    /** Items the whole list still asks for. */
    public long wanted() {
        long total = 0L;
        for (Line<K> line : lines)
            total = saturatedAdd(total, line.wanted());
        return total;
    }

    /** Of those, what the racks can give right now. */
    public long serveable() {
        long total = 0L;
        for (Line<K> line : lines)
            total = saturatedAdd(total, line.fromStock());
        return total;
    }

    /**
     * Of those, what production would make for the list's entries: the promise, the "produced" of the table's split.
     * The machines make it in whole runs ({@link #madeTotal()}), which is what a Yes agrees to.
     */
    public long producing() {
        long total = 0L;
        for (Line<K> line : lines)
            total = saturatedAdd(total, line.produced());
        return total;
    }

    /**
     * What the machines make for the list in whole runs: every line's {@link Line#made()}, at least
     * {@link #producing()}. The surplus of a run lands in a rack. This is the number a Yes agrees to and the consent
     * budget the order then spends down portion by portion ({@link #budget()}), because a portion is charged the whole
     * runs its chain makes ({@code RequestConfirmation#made()}): "Torch x2" from a four-torch pattern costs four, and a
     * budget of two would stop the order with a second question right after the Yes.
     */
    public long madeTotal() {
        long total = 0L;
        for (Line<K> line : lines)
            total = saturatedAdd(total, line.made());
        return total;
    }

    /** Of those, what the warehouse can neither give nor make: left unticked and reported. */
    public long missing() {
        long total = 0L;
        for (Line<K> line : lines)
            total = saturatedAdd(total, line.missing());
        return total;
    }

    /** What the list gets: from the racks and from production — the "210" of "Fetch what there is (210 of 212)?". */
    public long obtainable() {
        return saturatedAdd(serveable(), producing());
    }

    /** Entries the racks cover in full. */
    public int entriesServed() {
        return count(ListEntryStatus.AVAILABLE);
    }

    /** Entries that are covered in full, but only with production. */
    public int entriesProducing() {
        return count(ListEntryStatus.CRAFTED);
    }

    /** Entries that are partly covered, i.e. the ones "fetch what there is" is about. */
    public int entriesShort() {
        return count(ListEntryStatus.PARTLY);
    }

    /** Entries the warehouse can do nothing at all for. */
    public int entriesImpossible() {
        return count(ListEntryStatus.IMPOSSIBLE);
    }

    private int count(ListEntryStatus status) {
        int found = 0;
        for (Line<K> line : lines) {
            if (line.status() == status)
                found++;
        }
        return found;
    }

    /** Entries this question is about, i.e. the lines that still want something. */
    public int entries() {
        return lines.size();
    }

    /**
     * The lines as the table shows them: red, gold, blue, green ({@link ListEntryStatus}), ties in list order.
     * Unmodifiable.
     */
    public List<Line<K>> tableOrder() {
        List<Line<K>> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing((Line<K> line) -> line.status()).thenComparingInt(Line::order));
        return List.copyOf(sorted);
    }

    /** The Missing section: every material the list is short of, summed ({@link ListShortage#of}). */
    public List<ListShortage<K>> shortages() {
        return ListShortage.of(lines);
    }

    /** Whether the clipboard held more orderable entries than the order took. */
    public boolean truncated() {
        return entriesDropped > 0;
    }

    /**
     * Whether the player has to be asked before the order starts: the warehouse falls short somewhere, something would
     * have to be produced, or the entry cap left part of their list behind. A list a stocked warehouse covers from the
     * racks starts without a dialog.
     */
    public boolean required() {
        return missing() > 0L || producing() > 0L || entriesDropped > 0;
    }

    /**
     * Whether an answer that accepted {@code acceptedMissing} items it will not get, {@code acceptedProducing} items
     * made for it in whole runs ({@link #madeTotal()}) and {@code acceptedDropped} entries left on the clipboard
     * authorises this question. A question that asks nothing is covered by every answer.
     * <p>
     * It is the {@link RequestAcknowledgement#covers} discipline for the list: the numbers the player saw are compared
     * with the numbers the server measures again when the Yes arrives, so a warehouse that lost stock in between is
     * asked a second time instead of quietly ordering more production than was agreed to.
     */
    public boolean covers(long acceptedMissing, long acceptedProducing, long acceptedDropped) {
        if (!required())
            return true;
        return acceptedMissing >= missing() && acceptedProducing >= madeTotal() && acceptedDropped >= entriesDropped;
    }

    /**
     * The consent budget a Yes to this question creates ({@link ListOrder#budget()}): what the machines make for the
     * list in whole runs ({@link #madeTotal()}), and nothing else.
     * <p>
     * The other two costs — a reserve and a maximum — are exactly the ones a player must not be able to sign away
     * blindly over a whole list. They are asked about per portion instead, against this budget, by the very panel a
     * click raises ({@code §3.6.6}).
     */
    public RequestAcknowledgement budget() {
        return new RequestAcknowledgement(false, 0L, 0L, 0L, madeTotal());
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0L ? Long.MAX_VALUE : sum;
    }
}
