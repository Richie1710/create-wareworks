package dev.wareworks.core.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import dev.wareworks.core.crafting.SlotPlan.Stack;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.RequestAcknowledgement;

/**
 * What pressing Fetch on a warehouse terminal's crafting grid would really mean, measured before anything is requested
 * (issue #24, slice 2, {@code docs/warehouse-system.md} §3.4.5).
 * <p>
 * <b>Whole crafts only.</b> Fetch for {@code xN} brings what the buffer is missing for {@code N} crafts — per key
 * {@code max(0, uses × N − inBuffer)} ({@link CraftGrid#toFetch}), topped up against the buffer and nothing else —
 * and a craft cannot be cut in half, so every bound here is a number of crafts:
 * <ul>
 * <li>{@link #stockRuns()} — the most crafts the racks can supply: the largest {@code r} with
 * {@code need(r) <= available} for every key, where {@code available} is what a player may take
 * ({@code StockAccess.PLAYER}: a reserve never holds a player back; crossing one is asked about per portion);</li>
 * <li>{@link #madeRuns()} — the same with what a production chain would add ({@link Promises}). Production at the
 * terminal's grid is a later step of issue #24; until it measures something, this equals {@link #stockRuns()};</li>
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
 * of ({@link Bound#STOCK}, {@link #about()}) or the buffer's room ({@link Bound#ROOM}). Nothing is requested;</li>
 * <li>{@code N} crafts are possible and nothing would be made — the fetch starts without a question;</li>
 * <li>otherwise the player is asked once, with the numbers the Yes will start: "Only x2 of x4 fit the buffer", or
 * which items fall short ({@link #named()}).</li>
 * </ul>
 * <b>A Yes is measured again.</b> It comes back as a Fetch for exactly {@link #possible()} crafts and the production
 * total it accepted, and the server measures that request afresh. It starts only if every craft it asks for is still
 * possible and nothing more would be made than was agreed to ({@link #covers}); a warehouse that changed in between is
 * asked about again ({@link Verdict#CHANGED}) — the {@code RequestAcknowledgement} discipline of §3.6.6. A client never
 * decides a cost.
 * <p>
 * Pure and immutable; the record is also what travels to the screen, so its canonical constructor clamps instead of
 * throwing and derives {@link #bound()} from the numbers rather than trusting a separate field.
 *
 * @param asked     how many crafts the player asked for, at least 1
 * @param ready     how many crafts the buffer already holds ({@link CraftGrid#craftsFrom}); may exceed {@code asked}
 * @param stockRuns the most crafts the racks can supply, {@code 0..asked}
 * @param madeRuns  the most crafts the racks and a production chain together can supply, {@code stockRuns..asked}
 * @param roomRuns  the most crafts whose ingredients fit into the buffer after every open request, {@code 0..asked}
 * @param about     the key that binds the stock bound — the one with the fewest crafts, first in grid order on a tie;
 *                  a measurement names it whenever {@link #bound()} is {@link Bound#STOCK}, and it is dropped for
 *                  every other bound
 * @param producing items a production chain would make for this fetch in total, the number a Yes agrees to; always 0
 *                  until the grid's chain offer is built
 * @param named     at most {@value #MAX_NAMED} keys, in grid order, that the warehouse falls short of for {@code asked}
 *                  crafts or that would be made, for the question's sentences
 * @param <K>       item key type
 */
public record CraftFetchQuestion<K>(int asked, int ready, int stockRuns, int madeRuns, int roomRuns,
                                    Optional<K> about, long producing, List<Line<K>> named) {
    /** How many keys the question names, the same as a clipboard's question ({@link ListOrderConfirmation}). */
    public static final int MAX_NAMED = ListOrderConfirmation.MAX_NAMED;

    public CraftFetchQuestion {
        Objects.requireNonNull(about, "about");
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
     * @param made    what a production chain would make of it; 0 for an ingredient nothing makes
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
     * What production chains would add to a fetch, per key: the seam for the grid's chain offer, which a later step of
     * issue #24 measures. Until then every fetch is measured with {@link #none()}.
     *
     * @param promised items of a key a chain would make available to this fetch on top of stock
     * @param made     items of a key the chain makes in whole runs, i.e. what a Yes agrees to having made
     * @param <K>      item key type
     */
    public record Promises<K>(Map<K, Long> promised, Map<K, Long> made) {
        public Promises {
            promised = promised == null ? Map.of() : Map.copyOf(promised);
            made = made == null ? Map.of() : Map.copyOf(made);
        }

        /** No production: what a fetch is measured with until the chain offer exists. */
        public static <K> Promises<K> none() {
            return new Promises<>(Map.of(), Map.of());
        }

        long promisedOf(K key) {
            return Math.max(0L, promised.getOrDefault(key, 0L));
        }

        long madeOf(K key) {
            return Math.max(0L, made.getOrDefault(key, 0L));
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
     * The question a Fetch of {@code crafts} crafts of {@code grid} raises against the warehouse and the buffer as they
     * are right now. Nothing is promised by measuring.
     * <p>
     * The stock bound is closed form — per key {@code (available + promised + inBuffer) / uses}, rounded down — and
     * the room bound is one {@link BufferFit} plan when everything fits and a short binary search when it does not, so
     * a measurement costs at most {@code 9} lookups and about {@code 18} plans over the buffer's slots.
     *
     * @param grid         the recipe as laid; must not be empty
     * @param crafts       how many crafts the player asked for (the screen's amount field), at least 1
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
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(openRequests, "openRequests");
        Objects.requireNonNull(maxStackSize, "maxStackSize");
        Objects.requireNonNull(promises, "promises");
        if (grid.isEmpty())
            throw new IllegalArgumentException("a fetch needs a recipe: the grid is empty");
        if (crafts < 1)
            throw new IllegalArgumentException("a fetch is for at least one craft: " + crafts);
        Map<K, Integer> uses = grid.perCraft();
        Map<K, Long> inBuffer = grid.countsIn(buffer);
        int ready = grid.craftsFrom(inBuffer);
        // The stock bounds, per key and as a whole. The bottleneck is the key with the fewest crafts, the first in grid
        // order on a tie, because that is the item the player has to do something about.
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
            int withMade = runs(saturatedAdd(saturatedAdd(inStock, promised), held), use.getValue(), crafts);
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
        return new CraftFetchQuestion<>(crafts, ready, stockRuns, madeRuns, roomRuns, Optional.ofNullable(bottleneck),
                producing, named);
    }

    /**
     * The lines of the craft order a fetch of {@code crafts} crafts starts: one per key with something to fetch, in
     * first-cell order, each asking for {@code max(0, uses × crafts − inBuffer)} ({@link CraftGrid#toFetch}). A line's
     * position is the key's first cell, on page 0 — a grid has no clipboard, and the position only has to tell the
     * at most {@value CraftGrid#SIZE} lines apart. Empty when the buffer already holds every craft.
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

    /** The crafts possible without having anything made — what the player gets by setting that amount instead. */
    public int withoutMaking() {
        return Math.min(stockRuns, roomRuns);
    }

    /** What keeps the fetch below {@link #asked()}, derived from the three bounds. */
    public Bound bound() {
        return bound(asked, madeRuns, roomRuns);
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
     * to, and nothing else — the same budget a clipboard's Fetch creates ({@link ListOrderConfirmation#budget()}). A
     * reserve or a maximum a portion would cross is asked about per portion, against this budget, with
     * {@code RequestScope#CRAFT}.
     */
    public RequestAcknowledgement budget() {
        return new RequestAcknowledgement(false, 0L, 0L, 0L, producing);
    }

    // --- arithmetic --------------------------------------------------------------------------------------------------

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
