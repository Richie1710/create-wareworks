package dev.wareworks.core.production;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * The lines of a craft fetch walked one after another against <b>one</b> availability snapshot, each line taking what
 * the racks still hold of its item and each chain paying out of what the earlier lines and chains left
 * ({@code docs/warehouse-system.md} §3.4.5, issue #24, slice 3): the measurement behind the crafting grid's chain offer
 * — "Oak Planks x8 will be made first".
 * <p>
 * <b>Why one line after another, and not one plan each.</b> A craft fetch can be short of several ingredients at once,
 * and the chains that would make them draw on the same racks: planks and charcoal are both made from logs. Planned on
 * their own, each would be told about every log in the warehouse, and the offer would promise the same logs twice.
 * This ledger holds what the earlier lines have <b>taken</b> out of the racks and what the earlier chains have
 * <b>spent</b> ({@link ProductionPlan#leafDemand()}), and how many production orders they would open
 * ({@link ProductionPlan#steps()}); every later line and chain is measured against the rest: the snapshot minus that
 * spend, and the free order slots minus those orders.
 * <p>
 * <b>Each line takes from the racks at its own turn, in line order</b> ({@link #preview(ProductionPlanInput,
 * SequencedMap)}). That is exactly what the craft order's portions will meet: they are requested in line order, each
 * one claims what the racks hold of its item first and has only the rest made, and once a portion's request and its
 * chain exist, its claim and its chain's ingredient lines are subtracted from the warehouse's availability, and its
 * orders take their slots, before the next portion is planned. A fence gate (stick, plank, stick in two rows) with two
 * planks in the racks has its sticks made of those two planks, because the sticks' line comes first — and its planks'
 * line then finds none and has its planks made too. Taking every line's stock first would promise those two planks
 * twice, and the planks' portion would then ask for production nobody had agreed to.
 * <p>
 * <b>The planner is unchanged.</b> Each chain is one ordinary {@link ProductionPlanner#plan} on an input derived from
 * the caller's — the same patterns, bounds, paused items and headroom, a budget over what is left and fewer free order
 * slots — so every refusal is the planner's own and <b>names its item</b>: {@link PlanRefusal#PAUSED} about a stopped
 * ingredient, {@link PlanRefusal#NO_PATTERN} about one nothing here makes, {@link PlanRefusal#MISSING_INGREDIENT}
 * about the raw material a chain cannot get past ("Oak Log is missing"), {@link PlanRefusal#ORDERS_BUSY} once the
 * earlier chains have taken the free order slots. A refused chain spends nothing, so the next one is planned as if it
 * had never been tried.
 * <p>
 * <b>What it does not share.</b> Two chains do not pay out of each other's surplus — a plan is a tree, and the whole
 * runs of one chain land in a rack as items nobody was promised (ADR-032) — and an intermediate's own maximum
 * ({@link ProductionPlanInput#headroom()}) is measured per chain, as each portion's request measures it.
 * <p>
 * <b>Nothing is ordered.</b> A preview is bookkeeping inside one Fetch: it creates no order, claims nothing and is
 * thrown away. Mutable and single-threaded like a {@link PlanBudget}; pure Java with no Minecraft types.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class SequentialPlans<K, L> {
    private final ProductionPlanInput<K, L> base;
    /**
     * Whether the accepted chains take free order slots away from the later ones: {@code true} for a craft fetch,
     * {@code false} for a clipboard list ({@link #forList}).
     */
    private final boolean shareSlots;
    /** Items taken out of the racks so far: the lines' own takes and every accepted chain's leaf demand. */
    private final Map<K, Long> spent = new LinkedHashMap<>();
    /** Production orders the accepted chains would open. */
    private int usedSlots;

    private SequentialPlans(ProductionPlanInput<K, L> base, boolean shareSlots) {
        this.base = Objects.requireNonNull(base, "base");
        this.shareSlots = shareSlots;
    }

    /**
     * An empty ledger over {@code base}: nothing taken, nothing spent, no order slot used.
     *
     * @param base the aisle's one snapshot: its patterns, the availability every line and chain pays from (asked at
     *             most once per key, however many chains are planned), the bounds, the free order slots, the paused
     *             items and the headroom. It is never mutated
     */
    public static <K, L> SequentialPlans<K, L> over(ProductionPlanInput<K, L> base) {
        return new SequentialPlans<>(base, true);
    }

    /**
     * An empty ledger for the entries of a <b>clipboard list</b> ({@code docs/warehouse-system.md} §3.4.4, issue #36,
     * ADR-069): items are shared exactly as {@link #over} shares them — every entry's take and every accepted chain's
     * leaf demand are spent — but the accepted chains <b>do not use up order slots</b> for the later entries
     * ({@link #freeOrderSlots()} stays {@link ProductionPlanInput#freeOrderSlots()}; {@link #usedSlots()} still counts).
     * <p>
     * The difference is when the requests are made. A craft fetch's lines are requested in the same tick, so their
     * chains really compete for the free slots. A list order keeps at most {@code terminalListOpenRequests} portions
     * open and plans each portion when it is offered, so the slots one entry's chain takes are free again before a
     * later entry is planned. The items, on the other hand, are really consumed, which is why the stock ledger is
     * shared all the same.
     */
    public static <K, L> SequentialPlans<K, L> forList(ProductionPlanInput<K, L> base) {
        return new SequentialPlans<>(base, false);
    }

    /**
     * What the lines of a craft fetch would meet, walked in {@code needs}' iteration order — the craft order's line
     * order, the grid's first-cell order — against one shared ledger: each line takes what is left of its item
     * ({@link #take}), and when that is less than it needs, the chain for the rest is planned against what is left
     * then ({@link #preview(Object, long)}).
     * <p>
     * At most one {@link ProductionPlanner#plan} per line, so a whole 3 × 3 grid costs at most nine plans.
     *
     * @param needs per key, in the order the craft order's portions will be requested, what its line asks for: what
     *              the buffer is missing of it for the crafts being measured. An amount of 0 or less is skipped and
     *              has no entry in the answer
     * @return per line what it takes out of the racks, and per line the racks cannot serve in full its chain's preview
     */
    public static <K, L> FetchPreview<K> preview(ProductionPlanInput<K, L> base, SequencedMap<K, Long> needs) {
        Objects.requireNonNull(needs, "needs");
        SequentialPlans<K, L> plans = over(base);
        Map<K, Long> taken = new LinkedHashMap<>();
        Map<K, ChainPreview<K>> chains = new LinkedHashMap<>();
        needs.forEach((key, need) -> {
            Objects.requireNonNull(key, "needs key");
            if (need == null || need < 1L)
                return;
            long took = plans.take(key, need);
            taken.put(key, took);
            if (need - took > 0L)
                chains.put(key, plans.preview(key, need - took));
        });
        return new FetchPreview<>(taken, chains);
    }

    /**
     * One line's claim on the racks: takes up to {@code amount} items of {@code key} out of what is left
     * ({@link #available}) and records them as spent, exactly as a portion's request claims the racks first.
     *
     * @param amount what the line asks for; 0 or less takes nothing
     * @return what was taken, {@code 0..amount}
     */
    public long take(K key, long amount) {
        Objects.requireNonNull(key, "key");
        long took = Math.min(Math.max(0L, amount), available(key));
        if (took > 0L)
            spent.merge(key, took, SequentialPlans::saturatedAdd);
        return took;
    }

    /**
     * Plans the chain that would make {@code amount} items of {@code key} against what is left, and on success records
     * what it spends and the orders it would open, so the next chain is planned against the rest. A refusal records
     * nothing.
     *
     * @param amount items the racks cannot supply, at least 1. The plan may promise less
     *               ({@link ProductionPlan#rootPromise()}) when what is left or a bound does not allow more
     * @throws IllegalArgumentException if {@code amount < 1}
     */
    public ProductionPlanResult<K, L> plan(K key, long amount) {
        Objects.requireNonNull(key, "key");
        if (amount < 1L)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        ProductionPlanResult<K, L> result = ProductionPlanner.plan(remaining(), key, amount);
        result.plan().ifPresent(plan -> {
            plan.leafDemand().forEach((leaf, items) -> spent.merge(leaf, items, SequentialPlans::saturatedAdd));
            usedSlots += plan.steps();
        });
        return result;
    }

    /** {@link #plan} as the preview a craft fetch is measured with. */
    public ChainPreview<K> preview(K key, long amount) {
        ProductionPlanResult<K, L> result = plan(key, amount);
        return result.plan().map(ChainPreview::<K>of)
                .orElseGet(() -> ChainPreview.refused(result.refusal().orElseThrow(), result.about().orElse(null)));
    }

    /**
     * What is left of {@code key} for the next line or chain: the snapshot minus every earlier line's take and every
     * accepted chain's spend, never negative.
     */
    public long available(K key) {
        Objects.requireNonNull(key, "key");
        return Math.max(0L, base.budget().snapshot(key) - spent(key));
    }

    /** Items of {@code key} taken out of the racks so far, by the lines and by the accepted chains. */
    public long spent(K key) {
        Objects.requireNonNull(key, "key");
        return spent.getOrDefault(key, 0L);
    }

    /** Everything taken out of the racks so far, per key, in the order the keys were first spent. Unmodifiable. */
    public Map<K, Long> spentByKey() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(spent));
    }

    /** Production orders the accepted chains would open between them. */
    public int usedSlots() {
        return usedSlots;
    }

    /**
     * Production orders the aisle could still open for the next chain: the free slots minus what the accepted chains
     * would open, or the free slots as they are for a list ({@link #forList}).
     */
    public int freeOrderSlots() {
        return shareSlots ? Math.max(0, base.freeOrderSlots() - usedSlots) : base.freeOrderSlots();
    }

    /**
     * The input the next chain is planned with: the caller's, with a budget over {@link #available} and the free order
     * slots the accepted chains have left. The configured order cap stays what it is, so a chain the earlier ones
     * crowded out is {@link PlanRefusal#ORDERS_BUSY} — waiting for an order to finish would let it through — and not
     * {@link PlanRefusal#TOO_MANY_STEPS} ({@link ProductionPlanInput#slotsBind()}).
     */
    private ProductionPlanInput<K, L> remaining() {
        return new ProductionPlanInput<>(base.patterns(), PlanBudget.of(this::available), base.limits(),
                freeOrderSlots(), base.orderSlots(), base.pausedResults(), base.headroom());
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0L ? Long.MAX_VALUE : sum;
    }
}
