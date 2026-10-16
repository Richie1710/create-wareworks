package dev.wareworks.core.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a recipe shown by a recipe viewer into a terminal's crafting grid (issue #24, slice 4): for each of the nine
 * cells the recipe names a list of candidate items (every item its ingredient accepts, in the recipe's own order), and
 * the grid needs exactly one key per cell, because a cell is an exact key and never a tag ({@link CraftGrid}).
 * <p>
 * <b>Never fails.</b> A cell whose candidates are all missing gets its first candidate: laying a recipe is not
 * fetching it, and the panel then tints and names what is missing exactly as it does for a hand-laid grid. Whether the
 * picked grid matches a recipe at all is the server's own question afterwards; the picker never runs recipe code.
 * <p>
 * <b>Cells with the same candidate list get the same key.</b> A chest laid from {@code #planks} takes eight oak planks
 * or eight birch planks, never a mix: the cells of one list are picked as one group, so a recipe laid from one
 * ingredient uses one item type, and Fetch then orders one stack instead of a few of everything.
 * <p>
 * <b>Ranking.</b> Within a group the candidates are compared, best first, by
 * <ol>
 * <li>vanilla's unused-item rule ({@code Inventory#findSlotMatchingUnusedItem},
 * {@code StackedContents#accountSimpleStack}): a plain item before a damaged, enchanted or renamed one, which vanilla
 * never takes for a recipe. The caller decides what is plain ({@link Supply#plain}); a worn candidate is still picked
 * when every candidate of the cell is worn;</li>
 * <li>how many times the terminal's buffer already holds the group's cells, more first: what has been delivered can
 * be crafted right away;</li>
 * <li>how many times the buffer and the stock available to the terminal together hold them, more first: what Fetch
 * can bring;</li>
 * <li>producible before not producible: a production station of the warehouse could make it first;</li>
 * <li>a key an earlier group already took before one it did not: groups whose lists differ but overlap still settle
 * on one item type where nothing else tells them apart;</li>
 * <li>the recipe's own order: the first candidate.</li>
 * </ol>
 * Steps 2 and 3 count whole crafts of the group ({@code count / cells}), so three oak planks are no better than none
 * for the four cells of a crafting table, and a group is not drawn to an item it cannot finish with.
 * <p>
 * <b>Earlier groups claim their items.</b> Groups are picked one after the other, those with the fewest candidates
 * first (ties in cell order), and each pick takes one craft's worth of its key out of the counts the later groups are
 * ranked by: from the buffer first, the rest from the available stock. A cell that accepts only oak planks therefore
 * keeps the one delivered oak plank for itself, and a {@code #planks} cell beside it is steered to the delivered birch
 * plank instead.
 * <p>
 * <b>Bounds.</b> At most {@value CraftGrid#SIZE} cells, and per cell the first {@value #MAX_CANDIDATES} distinct
 * candidates ({@link #considered}); a longer list is cut, never refused. The {@link Supply} is asked about a key at
 * most once per call and only when the key has to be compared, i.e. when it is a candidate of a cell with at least two
 * candidates: a single-candidate cell is decided without a lookup.
 * <p>
 * <b>A client chooses the keys, and their hash codes.</b> On the server the candidates arrive from a client, which can
 * make any number of distinct keys with one hash code without even searching: an item key's hash reads only what each
 * component value's {@code equals} is known to compare (ADR-064), so keys that differ only in what it does not read —
 * a name's colour, a mod's own component — share one by design. Every hash set and map in here compares keys of one
 * hash code one by one, so a lay of nothing but such keys would cost the square of its candidates. Before anything
 * else, {@link #pick} therefore keeps at most {@value #MAX_SAME_HASH} distinct candidates per hash code across the
 * whole lay and drops the rest ({@link #spreadHashes}): the work stays linear in the candidates, whatever their hashes.
 * The bound re-derived: at most {@value CraftGrid#SIZE} × {@value #MAX_CANDIDATES} candidates, each compared with at
 * most {@value #MAX_SAME_HASH} kept keys of its hash code in each set and map. One comparison of two item keys is not
 * linear in their bytes but costs about k² reference probes for k component-patch entries: the patch is a fastutil
 * array map, whose {@code equals} is {@code entrySet().containsAll}, a {@code containsKey} and a {@code get} per entry,
 * each a linear scan. k is bounded only by the registered component types, about 110 with vanilla and Create, so a
 * pass costs a few times what decoding the same payload costs (vanilla's decode of a patch is about k² as well) and
 * grows with a pack's component types; the menu takes one lay per tick. The {@link Supply}'s lookups come on top:
 * each costs as many comparisons as the stock holds keys with the candidate's hash code, which this bound does not
 * cap, so a lay pays at most {@value #MAX_SAME_HASH} lookups per hash code times the size of that bucket. Vanilla
 * variants hash apart — every potion, tipped arrow, enchanted book, suspicious stew, rocket, goat horn, banner and dyed
 * or trimmed piece — so a cell that lists every potion keeps every potion; real candidates share a hash code only when
 * they are one item that differs just in such a value, and a cell listing more than {@value #MAX_SAME_HASH} of those
 * keeps the first ones in the recipe's order. A hostile lay loses candidates, and a cell that loses all of them stays
 * empty.
 * <p>
 * <b>Deterministic.</b> The answer depends only on the cells and on what the {@link Supply} says, never on the
 * iteration order of a hash map: every tie ends at the recipe's order. Pure Java with no Minecraft types; {@code K}
 * must have value equality, as {@code content.item.ItemKey} has.
 */
public final class IngredientPicker {
    /**
     * How many distinct candidates of one cell are considered. A large modded tag ({@code #c:ingots} in a big pack)
     * stays within it, and it keeps the work per lay, and what a client may send for it, at nine times this many keys.
     */
    public static final int MAX_CANDIDATES = 64;
    /**
     * How many distinct candidates of one lay may share a hash code; the rest are dropped ({@link #spreadHashes}). It
     * keeps the comparisons per lookup in every hash set and map of a pick at a handful, so a lay costs at most a few
     * comparisons per candidate however a client chose their hash codes (what one comparison costs: the class
     * description).
     */
    public static final int MAX_SAME_HASH = 4;

    private IngredientPicker() {
    }

    /**
     * What the picker may ask about a candidate key. Counts below 0 are read as 0.
     *
     * @param <K> item key type
     */
    public interface Supply<K> {
        /** How many of {@code key} the terminal's buffer ("Delivered here") holds. */
        long inBuffer(K key);

        /** How many of {@code key} the warehouse can hand this terminal on request, beyond the buffer. */
        long available(K key);

        /** Whether a production station of the warehouse has a pattern for {@code key}. */
        boolean producible(K key);

        /**
         * Whether {@code key} is a plain item in the sense of vanilla's unused-item rule: not damaged, not enchanted,
         * not renamed. Every key is plain unless this is overridden.
         */
        default boolean plain(K key) {
            return true;
        }

        /**
         * A supply read from count maps; a key a map does not name counts 0, and every key is plain.
         *
         * @param inBuffer   what the buffer holds per key
         * @param available  what the warehouse can hand the terminal per key
         * @param producible keys a production station has a pattern for
         */
        static <K> Supply<K> of(Map<K, Long> inBuffer, Map<K, Long> available, Set<K> producible) {
            return of(inBuffer, available, producible, Set.of());
        }

        /**
         * A supply read from count maps, like {@link #of(Map, Map, Set)}, in which the keys of {@code worn} are not
         * plain.
         */
        static <K> Supply<K> of(Map<K, Long> inBuffer, Map<K, Long> available, Set<K> producible, Set<K> worn) {
            Objects.requireNonNull(inBuffer, "inBuffer");
            Objects.requireNonNull(available, "available");
            Objects.requireNonNull(producible, "producible");
            Objects.requireNonNull(worn, "worn");
            return new Supply<>() {
                @Override
                public long inBuffer(K key) {
                    return inBuffer.getOrDefault(key, 0L);
                }

                @Override
                public long available(K key) {
                    return available.getOrDefault(key, 0L);
                }

                @Override
                public boolean producible(K key) {
                    return producible.contains(key);
                }

                @Override
                public boolean plain(K key) {
                    return !worn.contains(key);
                }
            };
        }
    }

    /**
     * The candidates of one cell the picker considers: the distinct entries of {@code candidates} in their order,
     * each kept at its first occurrence, cut after {@value #MAX_CANDIDATES}. Unmodifiable; empty for an empty cell.
     *
     * @throws NullPointerException if the list or one of its entries is {@code null}
     */
    public static <K> List<K> considered(List<? extends K> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Set<K> distinct = new LinkedHashSet<>();
        for (K candidate : candidates) {
            distinct.add(Objects.requireNonNull(candidate, "candidate"));
            if (distinct.size() == MAX_CANDIDATES)
                break;
        }
        return List.copyOf(distinct);
    }

    /**
     * {@code cells} with every candidate dropped whose hash code {@value #MAX_SAME_HASH} distinct candidates before it
     * already have — earlier in its own cell or in an earlier cell — in their order; a candidate equal to one already
     * kept is always kept. Each candidate costs at most {@value #MAX_SAME_HASH} comparisons, so this is linear in the
     * candidates whatever their hash codes are, and so is every hash structure that only ever sees what it kept.
     *
     * @throws NullPointerException if a cell or a candidate is {@code null}
     */
    static <K> List<List<K>> spreadHashes(List<? extends List<? extends K>> cells) {
        Objects.requireNonNull(cells, "cells");
        // Keyed by an int: distinct hash codes never call equals on a key, and a crowded bin of Integers is a tree.
        Map<Integer, List<K>> byHash = new HashMap<>();
        List<List<K>> spread = new ArrayList<>(cells.size());
        for (List<? extends K> cell : cells) {
            Objects.requireNonNull(cell, "cell");
            List<K> kept = new ArrayList<>(cell.size());
            for (K candidate : cell) {
                Objects.requireNonNull(candidate, "candidate");
                List<K> same = byHash.computeIfAbsent(candidate.hashCode(), hash -> new ArrayList<>(1));
                if (same.contains(candidate)) {
                    kept.add(candidate);
                } else if (same.size() < MAX_SAME_HASH) {
                    same.add(candidate);
                    kept.add(candidate);
                }
            }
            spread.add(kept);
        }
        return spread;
    }

    /**
     * Picks one key per cell.
     *
     * @param cells  up to {@value CraftGrid#SIZE} cells in row-major order, each the list of candidates its ingredient
     *               accepts in the recipe's order; an empty list is an empty cell, and the cells past the end of the
     *               list stay empty. At most {@value #MAX_SAME_HASH} distinct candidates per hash code are kept
     *               ({@link #spreadHashes})
     * @param supply what the terminal knows about the candidates
     * @param <K>    item key type
     * @return the grid with the picked keys, empty where a cell has no candidate
     * @throws IllegalArgumentException if there are more than {@value CraftGrid#SIZE} cells
     * @throws NullPointerException     if a cell or a candidate is {@code null}
     */
    public static <K> CraftGrid<K> pick(List<? extends List<? extends K>> cells, Supply<K> supply) {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(supply, "supply");
        if (cells.size() > CraftGrid.SIZE)
            throw new IllegalArgumentException("a crafting grid has at most " + CraftGrid.SIZE + " cells: "
                    + cells.size());
        // First of all, so no hash structure below ever sees more than a handful of keys with one hash code.
        List<List<K>> spread = spreadHashes(cells);

        // Cells with the same candidate list form one group, in the order of their first cell.
        Map<List<K>, Group<K>> byList = new LinkedHashMap<>();
        for (int cell = 0; cell < spread.size(); cell++) {
            List<K> candidates = considered(spread.get(cell));
            if (!candidates.isEmpty())
                byList.computeIfAbsent(candidates, Group::new).cells.add(cell);
        }
        List<Group<K>> groups = new ArrayList<>(byList.values());
        // Fewest candidates first; the sort is stable, so equal groups keep their cell order.
        groups.sort(Comparator.comparingInt(group -> group.candidates.size()));

        // Only a key some group has to choose about is ever looked up, and so only such a key is ever claimed.
        Set<K> compared = new HashSet<>();
        for (Group<K> group : groups) {
            if (group.candidates.size() > 1)
                compared.addAll(group.candidates);
        }

        Ledger<K> ledger = new Ledger<>(supply);
        Set<K> taken = new HashSet<>();
        List<Optional<K>> picked = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, Optional.empty()));
        for (Group<K> group : groups) {
            int uses = group.cells.size();
            K key = group.candidates.size() == 1 ? group.candidates.get(0) : best(group, uses, ledger, taken);
            if (compared.contains(key))
                ledger.claim(key, uses);
            taken.add(key);
            for (int cell : group.cells)
                picked.set(cell, Optional.of(key));
        }
        return new CraftGrid<>(picked);
    }

    /** The best candidate of a group with at least two, by the ranking in the class description. */
    private static <K> K best(Group<K> group, int uses, Ledger<K> ledger, Set<K> taken) {
        K best = null;
        Facts bestFacts = null;
        boolean bestTaken = false;
        // The candidates are walked in the recipe's order and a later one only wins when it is strictly better, so
        // every tie ends at the earlier candidate.
        for (K candidate : group.candidates) {
            Facts facts = ledger.facts(candidate);
            boolean wasTaken = taken.contains(candidate);
            if (best == null || better(facts, wasTaken, bestFacts, bestTaken, uses)) {
                best = candidate;
                bestFacts = facts;
                bestTaken = wasTaken;
            }
        }
        return best;
    }

    /** Whether {@code a} ranks strictly before {@code b} for a group of {@code uses} cells. */
    private static boolean better(Facts a, boolean aTaken, Facts b, boolean bTaken, int uses) {
        if (a.plain != b.plain)
            return a.plain;
        long aBuffer = a.buffer / uses;
        long bBuffer = b.buffer / uses;
        if (aBuffer != bBuffer)
            return aBuffer > bBuffer;
        long aTotal = saturatedSum(a.buffer, a.available) / uses;
        long bTotal = saturatedSum(b.buffer, b.available) / uses;
        if (aTotal != bTotal)
            return aTotal > bTotal;
        if (a.producible != b.producible)
            return a.producible;
        return aTaken && !bTaken;
    }

    private static long saturatedSum(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /** The cells that share one candidate list. */
    private static final class Group<K> {
        final List<K> candidates;
        final List<Integer> cells = new ArrayList<>(CraftGrid.SIZE);

        Group(List<K> candidates) {
            this.candidates = candidates;
        }
    }

    /** What is known about one key, with what earlier groups claimed already taken out of the counts. */
    private static final class Facts {
        long buffer;
        long available;
        final boolean producible;
        final boolean plain;

        Facts(long buffer, long available, boolean producible, boolean plain) {
            this.buffer = Math.max(0L, buffer);
            this.available = Math.max(0L, available);
            this.producible = producible;
            this.plain = plain;
        }
    }

    /** Looks every key up once and keeps the counts the groups picked so far leave. */
    private static final class Ledger<K> {
        private final Supply<K> supply;
        private final Map<K, Facts> known = new HashMap<>();

        Ledger(Supply<K> supply) {
            this.supply = supply;
        }

        Facts facts(K key) {
            return known.computeIfAbsent(key, k -> new Facts(supply.inBuffer(k), supply.available(k),
                    supply.producible(k), supply.plain(k)));
        }

        /** Takes one craft's worth of {@code key} for a group of {@code uses} cells: buffer first, then stock. */
        void claim(K key, int uses) {
            Facts facts = facts(key);
            long fromBuffer = Math.min(facts.buffer, uses);
            facts.buffer -= fromBuffer;
            facts.available -= Math.min(facts.available, uses - fromBuffer);
        }
    }
}
