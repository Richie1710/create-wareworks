package dev.wareworks.core.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import dev.wareworks.core.crafting.SlotPlan.Stack;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.production.ChainPreview;
import dev.wareworks.core.production.FetchPreview;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.SequentialPlans;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.RequestAcknowledgement;

/**
 * What pressing Fetch on a warehouse terminal's crafting grid would really mean, measured before anything is requested
 * (issue #24, slices 2 and 3, {@code docs/warehouse-system.md} §3.4.5).
 * <p>
 * <b>Whole crafts only.</b> Fetch for {@code xN} brings what the buffer is missing for {@code N} crafts — per key
 * {@code max(0, uses × N − inBuffer)} ({@link CraftGrid#toFetch}), topped up against the buffer and nothing else —
 * and a craft cannot be cut in half, so every bound here is a number of crafts:
 * <ul>
 * <li>{@link #stockRuns()} — the most crafts the racks can supply: the largest {@code r} with
 * {@code need(r) <= available} for every key, where {@code available} is what a player may take
 * ({@code StockAccess.PLAYER}: a reserve never holds a player back; crossing one is asked about per portion);</li>
 * <li>{@link #madeRuns()} — the same with what production chains at the warehouse's own machines would add
 * ({@link Promises}, {@link Chains}): an ingredient the racks are short of, and that a production station of the same
 * warehouse has a pattern for, can be made first (ADR-032). Equal to {@link #stockRuns()} when nothing would be
 * made;</li>
 * <li>{@link #roomRuns()} — the most crafts whose ingredients still fit into the buffer once every open request to this
 * terminal has put its remaining items down first ({@link BufferFit}). Max stack sizes count: a milk bucket takes a
 * slot of its own.</li>
 * </ul>
 * {@link #possible()} is the smaller of the second and third. It is never more than {@link #asked()}.
 * <p>
 * <b>What follows from it</b> ({@link #verdict}):
 * <ul>
 * <li>the buffer already holds {@code N} crafts — nothing to fetch;</li>
 * <li>nothing beyond what the buffer holds can be fetched — refused, naming the reason: the item the warehouse is short
 * of ({@link Bound#STOCK}, {@link #about()}), and when a chain was tried for it, why that chain does not hold
 * ({@link #chainRefusal()}: "Oak Log is missing"), or the buffer's room ({@link Bound#ROOM}). Nothing is
 * requested;</li>
 * <li>{@code N} crafts are possible and nothing would be made — the fetch starts without a question;</li>
 * <li>otherwise the player is asked once, with the numbers the Yes will start: "Only x2 of x4 fit the buffer", which
 * items fall short or would be made first ({@link #named()}), and what the player gets without having anything made
 * ({@link #withoutMaking()}).</li>
 * </ul>
 * <b>A Yes is measured again.</b> It comes back as a Fetch for exactly {@link #possible()} crafts and the production
 * total it accepted, and the server measures that request afresh. It starts only if every craft it asks for is still
 * possible and nothing more would be made than was agreed to ({@link #covers}); a warehouse that changed in between is
 * asked about again ({@link Verdict#CHANGED}) — the {@code RequestAcknowledgement} discipline of §3.6.6. A client never
 * decides a cost.
 * <p>
 * <b>Only the player crafts.</b> A chain makes <b>ingredients</b> at the player's own machines; the grid's recipe is
 * never a pattern, a production order or an order's completion (ADR-024, ADR-059). What is measured here is how many of
 * the ingredients the racks and those chains can bring; the craft itself stays the player's click.
 * <p>
 * Pure and immutable; the record is also what travels to the screen, so its canonical constructor clamps instead of
 * throwing and derives {@link #bound()} from the numbers rather than trusting a separate field.
 *
 * @param asked        how many crafts the player asked for, at least 1
 * @param ready        how many crafts the buffer already holds ({@link CraftGrid#craftsFrom}); may exceed
 *                     {@code asked}
 * @param stockRuns    the most crafts the racks can supply, {@code 0..asked}
 * @param madeRuns     the most crafts the racks and production chains together can supply, {@code stockRuns..asked}.
 *                     Chains are only measured as far as it matters: when the buffer's room binds, up to one craft
 *                     past what fits, which is enough to tell the two bounds apart
 * @param roomRuns     the most crafts whose ingredients fit into the buffer after every open request, {@code 0..asked}
 * @param about        the key that binds the stock bound — the one with the fewest crafts, first in grid order on a
 *                     tie; when the largest fetch the chains hold had to be searched for, or the supply tied with the
 *                     room, the one the smallest fetch that does not hold falls short of — when that fetch was capped
 *                     by an ingredient nothing in the aisle makes, or one that is stopped, that ingredient. A
 *                     measurement names it whenever {@link #bound()} is {@link Bound#STOCK}, and it is dropped for
 *                     every other bound
 * @param producing    items production chains would make for this fetch in total — each chain's whole runs
 *                     ({@link ChainPreview#made()}) — and so the number a Yes agrees to ({@link #budget()})
 * @param named        at most {@value #MAX_NAMED} keys, in grid order, that the warehouse falls short of for
 *                     {@code asked} crafts or that would be made, for the question's sentences
 * @param chainRefusal why no chain makes up for {@link #about()}, when one was tried for it: the refusal of its preview
 *                     ({@link ChainPreview#chainRefused()}). Empty when {@link #about()} is, and for an ingredient
 *                     nothing in the aisle makes — that one is simply missing
 * @param chainAbout   the item {@link #chainRefusal()} names: an ingredient further down the chain ("Oak Log is
 *                     missing") or {@link #about()} itself ("Making Oak Planks is stopped"); empty without a refusal
 * @param <K>          item key type
 */
public record CraftFetchQuestion<K>(int asked, int ready, int stockRuns, int madeRuns, int roomRuns,
                                    Optional<K> about, long producing, List<Line<K>> named,
                                    Optional<PlanRefusal> chainRefusal, Optional<K> chainAbout) {
    /** How many keys the question names, the same as a clipboard's question ({@link ListOrderConfirmation}). */
    public static final int MAX_NAMED = ListOrderConfirmation.MAX_NAMED;

    public CraftFetchQuestion {
        Objects.requireNonNull(about, "about");
        Objects.requireNonNull(chainRefusal, "chainRefusal");
        Objects.requireNonNull(chainAbout, "chainAbout");
        asked = Math.max(1, asked);
        ready = Math.max(0, ready);
        stockRuns = clamp(stockRuns, 0, asked);
        madeRuns = clamp(madeRuns, stockRuns, asked);
        roomRuns = clamp(roomRuns, 0, asked);
        producing = Math.max(0L, producing);
        named = named == null ? List.of()
                : List.copyOf(named.size() <= MAX_NAMED ? named : named.subList(0, MAX_NAMED));
        if (bound(asked, madeRuns, roomRuns) != Bound.STOCK)
            about = Optional.empty();
        // A chain's refusal explains why the bottleneck cannot be made up for, so it goes wherever the bottleneck goes.
        if (about.isEmpty() || chainRefusal.isEmpty()) {
            chainRefusal = Optional.empty();
            chainAbout = Optional.empty();
        }
    }

    /** A question no chain refusal is attached to: everything a fetch measured without production carries. */
    public CraftFetchQuestion(int asked, int ready, int stockRuns, int madeRuns, int roomRuns, Optional<K> about,
            long producing, List<Line<K>> named) {
        this(asked, ready, stockRuns, madeRuns, roomRuns, about, producing, named, Optional.empty(), Optional.empty());
    }

    /** What keeps a fetch below the number of crafts the player asked for. */
    public enum Bound {
        /** Nothing: every craft asked for is possible. */
        NONE,
        /**
         * The warehouse holds too few of an ingredient ({@link #about()}), and no chain makes up for it. Stock wins a
         * tie with {@link #ROOM}, because taking items out of the buffer would not help.
         */
        STOCK,
        /** The buffer has no room for more crafts' ingredients once every open request has put its items down. */
        ROOM
    }

    /**
     * What the terminal does about a Fetch, decided by {@link #verdict}. The names match the terminal's answers to the
     * player ({@code STARTED}, {@code ASKING}, ...), so the screen can name each one.
     */
    public enum Verdict {
        /** Every craft asked for is possible and nothing is made beyond what was agreed to: the fetch starts. */
        START,
        /** Fewer crafts are possible, or something would be made: the player is asked once before anything starts. */
        ASK,
        /**
         * A Yes arrived, but the warehouse has changed since the question was asked — fewer crafts are possible now,
         * or more would be made. Nothing starts; the player is asked again with the new numbers.
         */
        CHANGED,
        /** The buffer already holds every craft asked for. Nothing is requested: the player can craft right away. */
        NOTHING_TO_FETCH,
        /** Not one craft more than the buffer holds can be supplied; {@link #about()} names the item. */
        NOT_ENOUGH,
        /** The buffer has no room for one more craft's ingredients. */
        NO_ROOM_FOR_ONE
    }

    /**
     * One key the question names.
     *
     * @param key     the ingredient
     * @param needed  what has to be fetched of it for {@link #asked()} crafts, after what the buffer holds
     * @param inStock what a player may take of it out of the racks right now
     * @param made    what a production chain would make of it in whole runs ({@link ChainPreview#made()}), the
     *                "x8 will be made first" of the question; 0 for an ingredient nothing makes
     * @param <K>     item key type
     */
    public record Line<K>(K key, long needed, long inStock, long made) {
        public Line {
            Objects.requireNonNull(key, "key");
            needed = Math.max(0L, needed);
            inStock = Math.max(0L, inStock);
            made = Math.max(0L, made);
        }

        /** Whether the racks and a chain together fall short of what this key needs. */
        public boolean isShort() {
            return saturatedAdd(inStock, made) < needed;
        }
    }

    /**
     * What production chains would add to a fetch, per key: the previews of the chains that would make what the racks
     * cannot give a line at its own turn ({@link SequentialPlans}, walked in line order against one shared budget),
     * and what each of those lines takes straight out of the racks first. {@link #none()} for a fetch nothing is made
     * for.
     * <p>
     * An accepted chain makes {@link ChainPreview#promised()} items available to this fetch on top of what its line
     * takes from the racks ({@link #taken()}) and {@link ChainPreview#made()} in whole runs, which is what a Yes agrees
     * to; a refused one adds nothing and is kept for the sentence that names its item.
     * <p>
     * A line's take can be less than the racks hold of its item, because an earlier line's chain may have used some
     * of it: a fence gate's sticks come first and are made of the planks its planks' line then cannot have. So a key a
     * chain was planned for counts with what its line took ({@link #fromRacks}), never with the racks as a whole, or
     * the same planks would be counted twice.
     *
     * @param chains one preview per key a chain was planned for; a key without one has nothing made
     * @param taken  per key a chain was planned for, what its line took straight out of the racks before the chain was
     *               asked for the rest; a key without an entry counts with everything the racks hold of it
     * @param <K>    item key type
     */
    public record Promises<K>(Map<K, ChainPreview<K>> chains, Map<K, Long> taken) {
        public Promises {
            chains = chains == null ? Map.of() : Map.copyOf(chains);
            taken = taken == null ? Map.of() : Map.copyOf(taken);
        }

        /** Chains whose lines' takes are not recorded: every key counts with what the racks hold of it. */
        public Promises(Map<K, ChainPreview<K>> chains) {
            this(chains, Map.of());
        }

        /** No production: what a fetch is measured with when nothing is short or nothing makes it. */
        public static <K> Promises<K> none() {
            return new Promises<>(Map.of(), Map.of());
        }

        /**
         * The promises a fetch's preview describes: every chain it planned, each with what its line took out of the
         * racks first. A line the racks served alone has no chain, and counts with what the racks hold.
         */
        public static <K> Promises<K> of(FetchPreview<K> preview) {
            Objects.requireNonNull(preview, "preview");
            Map<K, Long> taken = new LinkedHashMap<>();
            preview.chains().keySet().forEach(key -> {
                Long took = preview.taken().get(key);
                if (took != null)
                    taken.put(key, took);
            });
            return new Promises<>(preview.chains(), taken);
        }

        /**
         * What the racks give {@code key}'s line in the fetch these promises were planned for: what it took at its own
         * turn when a chain was planned for the rest — an earlier chain may have used the rest of its stock — and
         * otherwise all of {@code inStock}.
         *
         * @param inStock what a player may take of {@code key} out of the racks right now, at least 0
         */
        public long fromRacks(K key, long inStock) {
            Long took = taken.get(key);
            return took == null ? inStock : Math.min(inStock, Math.max(0L, took));
        }

        /** Items of {@code key} a chain would make available to this fetch on top of stock. */
        public long promisedOf(K key) {
            ChainPreview<K> chain = chains.get(key);
            return chain == null ? 0L : chain.promised();
        }

        /** Items of {@code key} a chain would make in whole runs, i.e. what a Yes agrees to having made. */
        public long madeOf(K key) {
            ChainPreview<K> chain = chains.get(key);
            return chain == null ? 0L : chain.made();
        }

        /**
         * The preview of the chain that was tried for {@code key} and does not hold
         * ({@link ChainPreview#chainRefused()}), if there is one: a paused ingredient, a missing raw material, busy
         * order slots. An ingredient nothing here makes has none.
         */
        public Optional<ChainPreview<K>> refusalOf(K key) {
            return Optional.ofNullable(chains.get(key)).filter(ChainPreview::chainRefused);
        }

        /**
         * These promises, keeping every refusal {@code earlier} gave for a key these do not preview at all. A fetch
         * that is measured again for fewer crafts previews only what is still short then; an ingredient the stock
         * covers at that number, but whose chain could not raise it any higher, still says why.
         */
        Promises<K> orRefusalsOf(Promises<K> earlier) {
            Map<K, ChainPreview<K>> merged = new LinkedHashMap<>(chains);
            earlier.chains.forEach((key, chain) -> {
                if (!chain.accepted())
                    merged.putIfAbsent(key, chain);
            });
            // A refusal kept from the earlier fetch adds no take: at these crafts the racks served that line alone.
            return new Promises<>(merged, taken);
        }
    }

    /**
     * Previews the production chains a fetch would need: the warehouse's {@code previewProduction}, which walks the
     * craft order's lines in the order its portions will be requested, against one shared budget
     * ({@link SequentialPlans}) — each line taking what the racks still hold of its item at its own turn, and a chain
     * planned for the rest — and orders nothing. Called by
     * {@link CraftFetchQuestion#measure(CraftGrid, int, List, ToLongFunction, List, ToIntFunction, Chains)} only for a
     * fetch the racks cannot serve — once when the chains make up for everything that fits, and a bounded number of
     * times while the largest number of crafts they hold for is searched for — and once, one craft past what fits,
     * when the racks or the chains supply exactly the crafts that fit and fewer fit than were asked for, to settle
     * whether the room or the stock binds. Every call has to be answered from the same warehouse, which is what makes
     * the answers of one measurement comparable.
     *
     * @param <K> item key type
     */
    @FunctionalInterface
    public interface Chains<K> {
        /**
         * What the lines of a fetch would meet.
         *
         * @param needs per ingredient, in line order (the grid's first-cell order), what its line asks for: what the
         *              buffer is missing of it for the crafts being measured ({@link CraftGrid#toFetch}). Never
         *              empty, every amount at least 1, and at least one of them more than the racks hold. Unmodifiable
         * @return per line what it takes out of the racks at its own turn, and per line that falls short the chain
         *         previewed for the rest; a line the answer says nothing about counts with the racks alone
         */
        FetchPreview<K> preview(SequencedMap<K, Long> needs);

        /** No production at all: every fetch is measured against the racks alone. */
        static <K> Chains<K> none() {
            return needs -> FetchPreview.none();
        }
    }

    // --- measuring ---------------------------------------------------------------------------------------------------

    /**
     * The question a Fetch of {@code crafts} crafts of {@code grid} raises, without production
     * ({@link Promises#none()}).
     *
     * @see #measure(CraftGrid, int, List, ToLongFunction, List, ToIntFunction, Promises)
     */
    public static <K> CraftFetchQuestion<K> measure(CraftGrid<K> grid, int crafts, List<SlotView<K>> buffer,
            ToLongFunction<? super K> available, List<KeyCount<K>> openRequests,
            ToIntFunction<? super K> maxStackSize) {
        return measure(grid, crafts, buffer, available, openRequests, maxStackSize, Promises.none());
    }

    /**
     * The question a Fetch of {@code crafts} crafts of {@code grid} raises against the warehouse, the buffer and what
     * production chains would make (issue #24, slice 3) — the measurement a Fetch and its Yes are decided by. Nothing
     * is promised or ordered by measuring.
     * <p>
     * <b>Chains are previewed only when they could help.</b> The racks alone are measured first; when they already
     * cover every craft that fits into the buffer, or the buffer already holds every craft, that is the answer and no
     * chain is planned, except the one preview one craft past what fits that settles a tie with the room (below).
     * Otherwise the lines of the crafts that <b>fit</b> — {@code min(crafts, roomRuns)}, since making more than fits
     * would only spend raw material the others could use — are previewed ({@link Chains}) in the order the craft
     * order's portions will be requested: each line takes what the racks still hold of its item at its own turn, and a
     * chain is planned for the rest. When the chains make up for all of them, that one preview is the answer.
     * <p>
     * <b>Otherwise the largest number of crafts whose chains hold is searched for</b>, and the numbers shown are those
     * of that fetch — the one a Yes starts. What a Yes agrees to having made ({@link #producing()}, every named line's
     * {@link Line#made()}) has to be what a fetch for those crafts makes, not what a fetch for more would have made,
     * and a chain that does not hold for more crafts can hold for fewer: the craft's own take of a raw material grows
     * with the crafts and leaves the chain less of it (a campfire takes logs, and its sticks are made from logs too),
     * and two chains that share a raw material each need less of it. The first guesses are what the chains offered for
     * the crafts that fit and the craft just above it — a chain that was simply short of raw material is settled by
     * those two, three previews in all — and then the range is halved. Every guess is one preview of at most
     * {@value CraftGrid#SIZE} plans, so a measurement costs at most {@code 3 + log2(fits)} previews, and at most one
     * for a fetch the racks serve (the tie with the room). The item the question names ({@link #about()},
     * {@link #chainRefusal()}) is the one the smallest fetch that does not hold falls short of: why one craft more is
     * not possible.
     * <p>
     * <b>Which of supply and room binds</b> is settled with one more preview when the two tie: when the racks and the
     * chains supply exactly the crafts that fit, and fewer fit than were asked for, a preview one craft past what fits
     * tells whether the chains could have made more. If they could, the question is about the buffer's room ("Only x2
     * of x4 fit the buffer"), and {@link #madeRuns()} says one craft more than fits; if not, the stock wins the tie as
     * it always does, and the question names what that craft past what fits falls short of, with its chain's refusal
     * when one was tried — a full buffer and no logs is "Oak Log is missing", not the room.
     * <p>
     * <b>What no smaller fetch can change is not searched.</b> An ingredient nothing in the aisle makes, or one the
     * warehouse has stopped making, is refused before any chain is walked, whatever the amount
     * ({@link PlanRefusal#NO_PATTERN}, {@link PlanRefusal#PAUSED} about the ingredient itself). It caps the crafts at
     * what the racks hold of it, so a fetch short of slime balls costs one preview and not a search. When nothing
     * smaller fails before that cap, the craft one past it is never previewed, and the ingredient that caps it is
     * what it falls short of, whatever any chain does: it is the one named ("not enough Coal", or "Making Oak Planks
     * is stopped"), not a chain that was only refused for the larger fetch it was planned for.
     * <p>
     * Each preview plans greedily in grid order, so two short ingredients that share one raw material can be offered a
     * little less than an optimal split would allow. The answer is never more than the chains really hold.
     *
     * @param chains the warehouse's preview of production chains; {@link Chains#none()} measures the racks alone
     * @see #measure(CraftGrid, int, List, ToLongFunction, List, ToIntFunction, Promises)
     */
    public static <K> CraftFetchQuestion<K> measure(CraftGrid<K> grid, int crafts, List<SlotView<K>> buffer,
            ToLongFunction<? super K> available, List<KeyCount<K>> openRequests,
            ToIntFunction<? super K> maxStackSize, Chains<K> chains) {
        requireMeasurable(grid, crafts, buffer, available, openRequests, maxStackSize);
        Objects.requireNonNull(chains, "chains");
        return new WithChains<>(grid, crafts, buffer, available, openRequests, maxStackSize, chains).measure();
    }

    /**
     * The question a Fetch of {@code crafts} crafts of {@code grid} raises against the warehouse and the buffer as they
     * are right now, with what chains would make already previewed ({@code promises}). Nothing is promised by
     * measuring.
     * <p>
     * The stock bound is closed form — per key {@code (available + promised + inBuffer) / uses}, rounded down, where
     * a key a chain was planned for counts only what its line took out of the racks
     * ({@link Promises#fromRacks}) — and
     * the room bound is one {@link BufferFit} plan when everything fits and a short binary search when it does not, so
     * a measurement costs at most {@code 9} lookups and about {@code 18} plans over the buffer's slots.
     *
     * @param grid         the recipe as laid; must not be empty
     * @param crafts       how many crafts the player asked for (the screen's amount field rounded up to whole
     *                     crafts), at least 1
     * @param buffer       the terminal's buffer slots as they are now: what it holds of each key is read from them
     * @param available    what a player may take of a key out of the racks; a negative answer counts as 0
     * @param openRequests the remaining amount of every open request to this terminal, in the order to simulate
     *                     them; a count of 0 brings nothing
     * @param maxStackSize a key's max stack size, what an empty slot can take of it; less than 1 counts as 1
     * @param promises     what production chains would add, per key
     * @throws IllegalArgumentException if the grid is empty or {@code crafts} is less than 1
     */
    public static <K> CraftFetchQuestion<K> measure(CraftGrid<K> grid, int crafts, List<SlotView<K>> buffer,
            ToLongFunction<? super K> available, List<KeyCount<K>> openRequests,
            ToIntFunction<? super K> maxStackSize, Promises<K> promises) {
        requireMeasurable(grid, crafts, buffer, available, openRequests, maxStackSize);
        Objects.requireNonNull(promises, "promises");
        Map<K, Integer> uses = grid.perCraft();
        Map<K, Long> inBuffer = grid.countsIn(buffer);
        int ready = grid.craftsFrom(inBuffer);
        // The stock bounds, per key and as a whole. The bottleneck is the key with the fewest crafts, the first in grid
        // order on a tie, because that is the item the player has to do something about. (A measurement with chains
        // names what one craft more falls short of instead, WithChains#blamedOn.)
        int stockRuns = crafts;
        int madeRuns = crafts;
        K bottleneck = null;
        int bottleneckRuns = Integer.MAX_VALUE;
        long producing = 0L;
        List<Line<K>> named = new ArrayList<>(Math.min(uses.size(), MAX_NAMED));
        Map<K, Integer> neededForAsked = grid.toFetch(crafts, inBuffer);
        for (Map.Entry<K, Integer> use : uses.entrySet()) {
            K key = use.getKey();
            long held = inBuffer.getOrDefault(key, 0L);
            long inStock = Math.max(0L, available.applyAsLong(key));
            long promised = promises.promisedOf(key);
            long made = promises.madeOf(key);
            int fromStock = runs(saturatedAdd(inStock, held), use.getValue(), crafts);
            // With a chain, the line counts what it took of the racks at its own turn: an earlier chain may have used
            // the rest, and counting it again would promise the same items twice.
            int withMade = runs(saturatedAdd(saturatedAdd(promises.fromRacks(key, inStock), promised), held),
                    use.getValue(), crafts);
            stockRuns = Math.min(stockRuns, fromStock);
            madeRuns = Math.min(madeRuns, withMade);
            if (withMade < bottleneckRuns) {
                bottleneckRuns = withMade;
                bottleneck = key;
            }
            producing = saturatedAdd(producing, made);
            if ((withMade < crafts || made > 0L) && named.size() < MAX_NAMED)
                named.add(new Line<>(key, neededForAsked.getOrDefault(key, 0), inStock, made));
        }
        List<Stack<K>> ahead = requestStacks(openRequests, maxStackSize);
        int roomRuns = BufferFit.largest(buffer, ahead, crafts,
                runs -> demandStacks(grid.toFetch(runs, inBuffer), maxStackSize));
        // Why the bottleneck cannot be made up for, when a chain was tried for it. The record keeps it only while the
        // stock is what binds, exactly as it keeps the bottleneck.
        Optional<ChainPreview<K>> blocked = bottleneck == null ? Optional.empty() : promises.refusalOf(bottleneck);
        return new CraftFetchQuestion<>(crafts, ready, stockRuns, madeRuns, roomRuns, Optional.ofNullable(bottleneck),
                producing, named, blocked.flatMap(ChainPreview::refusal), blocked.flatMap(ChainPreview::about));
    }

    /**
     * The lines of the craft order a fetch of {@code crafts} crafts starts: one per key with something to fetch, in
     * first-cell order, each asking for {@code max(0, uses × crafts − inBuffer)} ({@link CraftGrid#toFetch}). A line's
     * position is the key's first cell, on page 0 — a grid has no clipboard, and the position only has to tell the
     * at most {@value CraftGrid#SIZE} lines apart. Empty when the buffer already holds every craft.
     * <p>
     * An ingredient a chain makes first is a line like any other: its portion is an ordinary request, which takes what
     * the racks hold and has the rest made by the chain behind it (ADR-032), so the order never asks for an
     * intermediate or a raw material of its own.
     *
     * @param buffer the terminal's buffer slots as they are now
     */
    public static <K> List<ListEntry<K>> entries(CraftGrid<K> grid, int crafts, List<SlotView<K>> buffer) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(buffer, "buffer");
        List<ListEntry<K>> entries = new ArrayList<>(CraftGrid.SIZE);
        grid.toFetch(crafts, grid.countsIn(buffer)).forEach((key, amount) ->
                entries.add(ListEntry.of(0, grid.firstCell(key).orElseThrow(), key, amount)));
        return Collections.unmodifiableList(entries);
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    /** The crafts this fetch can bring the buffer up to: the smaller of {@link #madeRuns()} and {@link #roomRuns()}. */
    public int possible() {
        return Math.min(madeRuns, roomRuns);
    }

    /**
     * The crafts possible without having anything made — what the player gets by setting that amount instead: "Without
     * making: x1".
     */
    public int withoutMaking() {
        return Math.min(stockRuns, roomRuns);
    }

    /** Whether a Yes to this question would have something made first. */
    public boolean makesSomething() {
        return producing > 0L;
    }

    /**
     * Whether the question offers the smaller fetch the racks serve on their own — "Without making: x1" and "Set x1 to
     * fetch only that." — beside what a Yes would have made: something would be made, and the racks alone serve more
     * crafts than the buffer already holds, so that setting {@link #withoutMaking()} really fetches something, but
     * fewer than the Yes starts.
     */
    public boolean offersWithoutMaking() {
        int without = withoutMaking();
        return makesSomething() && without > ready && without < possible();
    }

    /** What keeps the fetch below {@link #asked()}, derived from the three bounds. */
    public Bound bound() {
        return bound(asked, madeRuns, roomRuns);
    }

    /**
     * The item a refusal of this fetch names: the one a chain could not get past ({@link #chainAbout()}) when a chain
     * was tried for the bottleneck — "Oak Log is missing" rather than "Oak Planks is missing" — and otherwise the
     * bottleneck itself ({@link #about()}). Empty when the buffer's room is what binds.
     */
    public Optional<K> reasonItem() {
        return chainRefusal.isPresent() ? chainAbout : about;
    }

    /** Whether the buffer already holds every craft asked for, so there is nothing to fetch at all. */
    public boolean nothingToFetch() {
        return ready >= asked;
    }

    /**
     * Whether the fetch is refused outright: there is something to fetch, but not one craft more than the buffer
     * already holds can be supplied, or fit. Nothing is requested.
     */
    public boolean refused() {
        return !nothingToFetch() && possible() <= ready;
    }

    /**
     * Whether an answer that accepted {@code acceptedProducing} made items authorises this measurement: every craft
     * asked for is possible, and nothing more would be made than was agreed to. A Fetch nobody was asked about carries
     * 0, so a fetch the warehouse serves in full from its racks is covered by it and starts without a question.
     */
    public boolean covers(long acceptedProducing) {
        return possible() == asked && producing <= Math.max(0L, acceptedProducing);
    }

    /**
     * Whether the player has to be asked before this fetch starts: something can be fetched, but either fewer crafts
     * than asked for or only with production.
     */
    public boolean required() {
        return verdict(false, 0L) == Verdict.ASK;
    }

    /**
     * What the terminal does about this measurement, in this order: nothing to fetch; refused (naming the binding
     * reason); covered, so it starts; otherwise a question — {@link Verdict#CHANGED} when the request was itself the
     * Yes to an earlier question, which therefore no longer holds.
     *
     * @param answering         whether this Fetch is the Yes to a question the player was shown
     * @param acceptedProducing the production total that Yes agreed to; 0 for a Fetch nobody was asked about
     */
    public Verdict verdict(boolean answering, long acceptedProducing) {
        if (nothingToFetch())
            return Verdict.NOTHING_TO_FETCH;
        if (refused())
            return bound() == Bound.ROOM ? Verdict.NO_ROOM_FOR_ONE : Verdict.NOT_ENOUGH;
        if (covers(acceptedProducing))
            return Verdict.START;
        return answering ? Verdict.CHANGED : Verdict.ASK;
    }

    /**
     * The consent budget the craft order starts with ({@code ListOrder#budget()}): the production total a Yes agreed
     * to, and nothing else — the same budget a clipboard's Fetch creates ({@link ListOrderConfirmation#budget()}). Each
     * portion whose request has an ingredient made spends it down by its chain's whole-run output, the number a
     * {@code RequestScope#CRAFT} question compares against, so a chain the Fetch question already named does not ask a
     * second time: the preview walked the lines in the order the portions are requested, each taking from the racks at
     * its own turn, so the chains it added up are the ones the portions meet. A reserve or a maximum a portion would
     * cross is asked about per portion, against this budget.
     */
    public RequestAcknowledgement budget() {
        return new RequestAcknowledgement(false, 0L, 0L, 0L, producing);
    }

    // --- arithmetic --------------------------------------------------------------------------------------------------

    private static <K> void requireMeasurable(CraftGrid<K> grid, int crafts, List<SlotView<K>> buffer,
            ToLongFunction<? super K> available, List<KeyCount<K>> openRequests,
            ToIntFunction<? super K> maxStackSize) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(openRequests, "openRequests");
        Objects.requireNonNull(maxStackSize, "maxStackSize");
        if (grid.isEmpty())
            throw new IllegalArgumentException("a fetch needs a recipe: the grid is empty");
        if (crafts < 1)
            throw new IllegalArgumentException("a fetch is for at least one craft: " + crafts);
    }

    /**
     * One measurement of a Fetch with production chains
     * ({@link #measure(CraftGrid, int, List, ToLongFunction, List, ToIntFunction, Chains)}): the racks are asked once
     * per ingredient, so every measurement and every preview below talks about the same warehouse.
     */
    private static final class WithChains<K> {
        private final CraftGrid<K> grid;
        private final int crafts;
        private final List<SlotView<K>> buffer;
        private final List<KeyCount<K>> openRequests;
        private final ToIntFunction<? super K> maxStackSize;
        private final Chains<K> chains;
        private final Map<K, Long> inStock = new LinkedHashMap<>();
        private final Map<K, Long> inBuffer;

        private WithChains(CraftGrid<K> grid, int crafts, List<SlotView<K>> buffer,
                ToLongFunction<? super K> available, List<KeyCount<K>> openRequests,
                ToIntFunction<? super K> maxStackSize, Chains<K> chains) {
            this.grid = grid;
            this.crafts = crafts;
            this.buffer = buffer;
            this.openRequests = openRequests;
            this.maxStackSize = maxStackSize;
            this.chains = chains;
            for (K key : grid.perCraft().keySet())
                inStock.put(key, Math.max(0L, available.applyAsLong(key)));
            this.inBuffer = grid.countsIn(buffer);
        }

        private CraftFetchQuestion<K> measure() {
            CraftFetchQuestion<K> fromStock = at(crafts, Promises.none());
            int fits = Math.min(crafts, fromStock.roomRuns());
            if (fromStock.nothingToFetch())
                return fromStock;
            if (fromStock.stockRuns() >= fits)
                return settleRoom(fromStock, fits);
            Promises<K> atFits = promises(fits);
            CraftFetchQuestion<K> offered = at(crafts, atFits);
            if (offered.possible() >= fits)
                return settleRoom(offered, fits);
            // The search, between the crafts the racks serve without any chain (they hold) and the crafts that fit
            // (they do not). An ingredient no chain can ever raise lowers the upper end to what the racks hold of it.
            int held = fromStock.stockRuns();
            Promises<K> atHeld = Promises.none();
            Cap<K> cap = neverRaised(fits, atFits);
            int failed = Math.min(fits, cap.runs() + 1);
            Promises<K> atFailed = atFits;
            // Whether the chains of the smallest fetch that does not hold were really planned for it: not when only the
            // cap says it does not hold, because atFits was planned for more crafts.
            boolean failedPreviewed = failed == fits;
            // The first two guesses: what the chains offered for the crafts that fit, and the craft just above it.
            // When a chain was simply short of raw material, that is where the answer lies, and the two settle it.
            int offer = offered.possible();
            int[] guesses = {offer, offer + 1};
            int guess = 0;
            while (failed - held > 1) {
                int next = guess < guesses.length ? guesses[guess++] : -1;
                int tried = next > held && next < failed ? next : held + (failed - held) / 2;
                Promises<K> at = promises(tried);
                if (at(tried, at).madeRuns() >= tried) {
                    held = tried;
                    atHeld = at;
                } else {
                    failed = tried;
                    atFailed = at;
                    failedPreviewed = true;
                }
            }
            // The numbers of the fetch that holds, and the reasons of the smallest one that does not: why one craft
            // more is not possible is what the player is told.
            CraftFetchQuestion<K> question = at(crafts, atHeld.orRefusalsOf(atFailed));
            if (!failedPreviewed) {
                // Only the cap says that one craft more does not hold, and atFits was planned for more crafts: a chain
                // refused there because the larger fetch's own take ate its raw material may well hold one craft past
                // the cap. The capping ingredient is short there whatever any chain does — nothing makes it, or its
                // making is stopped — so it is what one craft more falls short of. Costs no preview.
                Optional<ChainPreview<K>> stopped = atFits.refusalOf(cap.key());
                return blamedOn(question, Optional.of(cap.key()), stopped.flatMap(ChainPreview::refusal),
                        stopped.flatMap(ChainPreview::about));
            }
            return blamedOn(question, at(failed, atFailed));
        }

        /**
         * {@code question} — the numbers of the fetch that holds — naming what the smallest fetch that does not hold
         * ({@code beyond}) falls short of: its bottleneck and, when a chain was tried for it, why that chain does not
         * hold. Measured at the fetch that holds, every short ingredient ties at the crafts it gets, because each
         * chain is offered exactly what that fetch needs; the first in grid order would win the tie although it may
         * well be another one that cannot be had for one craft more. Fences x2 with two logs are "Only x1 of x2"
         * because the sticks' chain finds no log once the planks' chain has both — so the sticks are what binds, and
         * "Oak Log is missing" is the sentence, not that the planks fall short. Costs no preview: {@code beyond} is
         * measured with the chains the search, or the tie with the room, already asked for.
         */
        private static <K> CraftFetchQuestion<K> blamedOn(CraftFetchQuestion<K> question,
                CraftFetchQuestion<K> beyond) {
            return blamedOn(question, beyond.about(), beyond.chainRefusal(), beyond.chainAbout());
        }

        /**
         * {@code question} naming {@code about} as what binds it, with {@code chainRefusal} about {@code chainAbout}
         * as why no chain makes up for it; unchanged while the stock does not bind it or there is nothing to name.
         */
        private static <K> CraftFetchQuestion<K> blamedOn(CraftFetchQuestion<K> question, Optional<K> about,
                Optional<PlanRefusal> chainRefusal, Optional<K> chainAbout) {
            if (question.bound() != Bound.STOCK || about.isEmpty())
                return question;
            return new CraftFetchQuestion<>(question.asked(), question.ready(), question.stockRuns(),
                    question.madeRuns(), question.roomRuns(), about, question.producing(), question.named(),
                    chainRefusal, chainAbout);
        }

        /**
         * {@code question} once it is clear which of the two binds when the racks and the chains supply exactly the
         * crafts that fit, and fewer crafts fit than were asked for. Measured only for what fits, the supply ties with
         * the room, and the stock would win the tie ({@link Bound#STOCK}) — right when the chains could not make more
         * either, wrong when they could, because then it is the buffer the player has to empty. One craft past what
         * fits settles it: when that one could be had as well, the room is what binds; when not, the stock wins the
         * tie, and the question names what that craft falls short of, with its chain's refusal when one was tried —
         * the same preview, so it costs nothing more. That is the one preview a fetch the racks serve can cost.
         */
        private CraftFetchQuestion<K> settleRoom(CraftFetchQuestion<K> question, int fits) {
            if (fits >= question.asked() || question.madeRuns() != fits)
                return question;
            int beyond = fits + 1;
            CraftFetchQuestion<K> past = at(beyond, promises(beyond));
            if (past.madeRuns() < beyond)
                return blamedOn(question, past);
            return new CraftFetchQuestion<>(question.asked(), question.ready(), question.stockRuns(), beyond,
                    question.roomRuns(), question.about(), question.producing(), question.named(),
                    question.chainRefusal(), question.chainAbout());
        }

        /** The fetch of {@code asked} crafts measured with {@code promises}. */
        private CraftFetchQuestion<K> at(int asked, Promises<K> promises) {
            return CraftFetchQuestion.measure(grid, asked, buffer, this::stockOf, openRequests, maxStackSize,
                    promises);
        }

        private long stockOf(K key) {
            return inStock.getOrDefault(key, 0L);
        }

        /**
         * What chains would make for a fetch of {@code runs} crafts: the craft order's lines for it — per ingredient
         * what the buffer is missing, in line order — walked as its portions will be requested, each taking what the
         * racks still hold of its item and having the rest made ({@link Chains}). {@link Promises#none()} without
         * asking the chains when the racks hold every line in full: no line then needs a chain, and none can take
         * another's items.
         */
        private Promises<K> promises(int runs) {
            LinkedHashMap<K, Long> needs = new LinkedHashMap<>();
            boolean anyShort = false;
            for (Map.Entry<K, Integer> line : grid.toFetch(runs, inBuffer).entrySet()) {
                long need = line.getValue();
                if (need < 1L)
                    continue;
                needs.put(line.getKey(), need);
                anyShort |= need > stockOf(line.getKey());
            }
            if (!anyShort)
                return Promises.none();
            return Promises.of(chains.preview(Collections.unmodifiableSequencedMap(needs)));
        }

        /**
         * The most crafts the ingredients no chain can ever raise allow, and the one that allows them: every
         * ingredient short of {@code fits} crafts whose preview refused it outright ({@link #neverMade}), or that was
         * not previewed at all, counts with what the buffer and the racks hold of it, and the first in grid order of
         * those that allow the fewest is the cap. {@code fits} and no key when there is none.
         */
        private Cap<K> neverRaised(int fits, Promises<K> atFits) {
            int cap = fits;
            K capped = null;
            for (Map.Entry<K, Integer> use : grid.perCraft().entrySet()) {
                K key = use.getKey();
                int fromStock = runs(saturatedAdd(stockOf(key), inBuffer.getOrDefault(key, 0L)), use.getValue(),
                        fits);
                ChainPreview<K> chain = atFits.chains().get(key);
                if (fromStock < cap && (chain == null || neverMade(key, chain))) {
                    cap = fromStock;
                    capped = key;
                }
            }
            return new Cap<>(cap, capped);
        }

        /**
         * The crafts the ingredients no chain can raise allow ({@link #neverRaised}), and the ingredient that allows
         * that few: it is short one craft past the cap whatever any chain does. {@code key} is null exactly when
         * nothing caps the fetch below what fits.
         */
        private record Cap<K>(int runs, K key) {
        }

        /**
         * Whether no fetch, however small, can have a chain make {@code key}: the planner refused it about the
         * ingredient itself before walking anything — nothing in the aisle makes it ({@link PlanRefusal#NO_PATTERN}),
         * or the warehouse has stopped making it ({@link PlanRefusal#PAUSED}). Every other refusal depends on the
         * amount or on what is left for it, and a smaller fetch can cure it.
         */
        private static <K> boolean neverMade(K key, ChainPreview<K> chain) {
            return chain.refusal().filter(reason -> reason == PlanRefusal.NO_PATTERN || reason == PlanRefusal.PAUSED)
                    .isPresent() && chain.about().filter(key::equals).isPresent();
        }
    }

    private static Bound bound(int asked, int madeRuns, int roomRuns) {
        if (Math.min(madeRuns, roomRuns) >= asked)
            return Bound.NONE;
        return madeRuns <= roomRuns ? Bound.STOCK : Bound.ROOM;
    }

    /** Whole crafts {@code supply} items cover at {@code uses} per craft, at most {@code cap}. */
    private static int runs(long supply, int uses, int cap) {
        return (int) Math.min(cap, Math.max(0L, supply) / Math.max(1, uses));
    }

    private static <K> List<Stack<K>> demandStacks(Map<K, Integer> amounts, ToIntFunction<? super K> maxStackSize) {
        List<Stack<K>> stacks = new ArrayList<>(amounts.size());
        amounts.forEach((key, amount) -> {
            if (amount > 0)
                stacks.add(new Stack<>(key, amount, Math.max(1, maxStackSize.applyAsInt(key))));
        });
        return stacks;
    }

    private static <K> List<Stack<K>> requestStacks(List<KeyCount<K>> counts, ToIntFunction<? super K> maxStackSize) {
        List<Stack<K>> stacks = new ArrayList<>(counts.size());
        for (KeyCount<K> count : counts) {
            Objects.requireNonNull(count, "openRequest");
            if (count.count() > 0L)
                stacks.add(new Stack<>(count.key(), (int) Math.min(Integer.MAX_VALUE, count.count()),
                        Math.max(1, maxStackSize.applyAsInt(count.key()))));
        }
        return stacks;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0L ? Long.MAX_VALUE : sum;
    }
}
