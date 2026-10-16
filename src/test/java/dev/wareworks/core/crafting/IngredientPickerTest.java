package dev.wareworks.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.crafting.IngredientPicker.Supply;

/**
 * Laying a recipe a recipe viewer shows into a terminal's crafting grid (issue #24, slice 4): one exact key per cell,
 * the same key for the cells of one ingredient, ranked by vanilla's unused-item rule, the buffer, the stock, the
 * production stations and the recipe's own order, never failing for a missing ingredient.
 */
class IngredientPickerTest {
    private static final String OAK = "oak_planks";
    private static final String BIRCH = "birch_planks";
    private static final String SPRUCE = "spruce_planks";
    private static final List<String> PLANKS = List.of(OAK, BIRCH, SPRUCE);
    private static final String STICK = "stick";
    private static final String COAL = "coal";
    private static final String CHARCOAL = "charcoal";
    private static final String COBBLE = "cobblestone";
    private static final String DEEPSLATE = "cobbled_deepslate";
    private static final String BLACKSTONE = "blackstone";
    private static final String IRON = "iron_ingot";
    private static final String REDSTONE = "redstone";
    private static final String RENAMED_OAK = "oak_planks{custom_name}";
    private static final String RENAMED_BIRCH = "birch_planks{custom_name}";

    private static final Supply<String> NOTHING = Supply.of(Map.of(), Map.of(), Set.of());

    /** Cells written down row by row; {@code null} is an empty cell. */
    @SafeVarargs
    private static List<List<String>> cells(List<String>... cells) {
        List<List<String>> list = new ArrayList<>(cells.length);
        for (List<String> cell : cells)
            list.add(cell == null ? List.of() : cell);
        return list;
    }

    /** {@code count} copies of one candidate list, e.g. the four cells of a crafting table. */
    private static List<List<String>> repeat(List<String> cell, int count) {
        return new ArrayList<>(Collections.nCopies(count, cell));
    }

    private static Supply<String> buffer(Map<String, Long> inBuffer) {
        return Supply.of(inBuffer, Map.of(), Set.of());
    }

    private static Supply<String> stock(Map<String, Long> available) {
        return Supply.of(Map.of(), available, Set.of());
    }

    /** A supply that counts every question, per method and key. */
    private static final class Counting implements Supply<String> {
        private final Supply<String> inner;
        final Map<String, Integer> asked = new HashMap<>();

        Counting(Supply<String> inner) {
            this.inner = inner;
        }

        private void ask(String method, String key) {
            asked.merge(method + ":" + key, 1, Integer::sum);
        }

        @Override
        public long inBuffer(String key) {
            ask("inBuffer", key);
            return inner.inBuffer(key);
        }

        @Override
        public long available(String key) {
            ask("available", key);
            return inner.available(key);
        }

        @Override
        public boolean producible(String key) {
            ask("producible", key);
            return inner.producible(key);
        }

        @Override
        public boolean plain(String key) {
            ask("plain", key);
            return inner.plain(key);
        }

        Set<String> keysAsked() {
            Set<String> keys = new TreeSet<>();
            for (String question : asked.keySet())
                keys.add(question.substring(question.indexOf(':') + 1));
            return keys;
        }
    }

    // --- cells and bounds --------------------------------------------------------------------------------------------

    @Test
    void emptyCellsStayEmpty() {
        assertEquals(CraftGrid.<String>empty(), IngredientPicker.pick(List.of(), NOTHING), "no cells, no keys");
        assertEquals(CraftGrid.<String>empty(), IngredientPicker.pick(repeat(List.of(), CraftGrid.SIZE), NOTHING));
        assertEquals(CraftGrid.of(null, STICK, null, null, COAL),
                IngredientPicker.pick(cells(null, List.of(STICK), null, List.of(), List.of(COAL)), NOTHING),
                "an empty cell stays empty and the cells past the end of the list stay empty");
    }

    @Test
    void aGridHasNineCellsAtMost() {
        assertThrows(IllegalArgumentException.class,
                () -> IngredientPicker.pick(repeat(List.of(OAK), CraftGrid.SIZE + 1), NOTHING));
        List<List<String>> withNullCell = repeat(List.of(OAK), 2);
        withNullCell.set(1, null);
        assertThrows(NullPointerException.class, () -> IngredientPicker.pick(withNullCell, NOTHING));
        assertThrows(NullPointerException.class,
                () -> IngredientPicker.pick(cells(Arrays.asList(OAK, null)), NOTHING));
        assertThrows(NullPointerException.class, () -> IngredientPicker.pick(cells(List.of(OAK)), null));
    }

    @Test
    void aSingleCandidateIsTakenWithoutALookup() {
        Counting supply = new Counting(Supply.of(Map.of(), Map.of(), Set.of(), Set.of(RENAMED_OAK)));
        CraftGrid<String> grid = IngredientPicker.pick(
                cells(List.of(STICK), List.of(RENAMED_OAK), List.of(COAL, COAL, COAL)), supply);
        assertEquals(CraftGrid.of(STICK, RENAMED_OAK, COAL), grid,
                "the only candidate is taken even when it is missing or worn, and duplicates are one candidate");
        assertEquals(Map.of(), supply.asked, "nothing to compare, nothing to ask");
    }

    // --- the ranking -------------------------------------------------------------------------------------------------

    @Test
    void withNothingKnownTheFirstCandidateWins() {
        assertEquals(CraftGrid.of(OAK, OAK, null, OAK, OAK), IngredientPicker.pick(
                cells(PLANKS, PLANKS, null, PLANKS, PLANKS), NOTHING), "a crafting table laid from nothing");
        assertEquals(CraftGrid.of(BIRCH), IngredientPicker.pick(cells(List.of(BIRCH, OAK, SPRUCE)), NOTHING),
                "the recipe's own order decides, not the key");
    }

    @Test
    void deliveredItemsComeFirst() {
        Supply<String> supply = Supply.of(Map.of(SPRUCE, 2L), Map.of(OAK, 1000L, BIRCH, 500L), Set.of(OAK));
        assertEquals(CraftGrid.of(SPRUCE, null, null, SPRUCE), IngredientPicker.pick(
                cells(PLANKS, null, null, PLANKS), supply), "two delivered spruce planks make the sticks now");
    }

    @Test
    void theBufferCountsInWholeCraftsOfTheCells() {
        List<List<String>> table = repeat(PLANKS, 4);
        assertEquals(BIRCH, IngredientPicker.pick(table,
                Supply.of(Map.of(OAK, 3L, BIRCH, 4L), Map.of(OAK, 1000L), Set.of())).cell(0).orElseThrow(),
                "three oak planks are no better than none for four cells: the four birch planks make a table now");
        assertEquals(OAK, IngredientPicker.pick(table,
                Supply.of(Map.of(OAK, 8L, BIRCH, 4L), Map.of(BIRCH, 1000L), Set.of())).cell(0).orElseThrow(),
                "eight oak planks make two tables now, four birch planks one");
        assertEquals(BIRCH, IngredientPicker.pick(table,
                Supply.of(Map.of(OAK, 5L, BIRCH, 4L), Map.of(BIRCH, 1000L), Set.of())).cell(0).orElseThrow(),
                "one table now either way, so the stock decides");
    }

    @Test
    void thenWhatTheWarehouseCanHand() {
        assertEquals(BIRCH, IngredientPicker.pick(cells(PLANKS), stock(Map.of(OAK, 10L, BIRCH, 64L)))
                .cell(0).orElseThrow(), "nothing delivered: the most stock wins");
        List<List<String>> table = repeat(PLANKS, 4);
        assertEquals(OAK, IngredientPicker.pick(table,
                Supply.of(Map.of(OAK, 3L), Map.of(OAK, 1L, BIRCH, 3L), Set.of())).cell(0).orElseThrow(),
                "three delivered and one in stock make a table, three birch planks in stock do not");
        assertEquals(OAK, IngredientPicker.pick(table,
                Supply.of(Map.of(OAK, 3L), Map.of(OAK, 1L, BIRCH, 7L), Set.of())).cell(0).orElseThrow(),
                "one table from either: the recipe's order decides");
    }

    @Test
    void thenWhatAStationCanMake() {
        List<List<String>> torch = cells(List.of(COAL, CHARCOAL), null, null, List.of(STICK));
        assertEquals(CraftGrid.of(CHARCOAL, null, null, STICK),
                IngredientPicker.pick(torch, Supply.of(Map.of(), Map.of(), Set.of(CHARCOAL))),
                "nothing in stock: a station with a charcoal pattern decides");
        assertEquals(CraftGrid.of(COAL, null, null, STICK),
                IngredientPicker.pick(torch, Supply.of(Map.of(), Map.of(COAL, 1L), Set.of(CHARCOAL))),
                "one coal in stock beats a charcoal that would have to be made");
    }

    /** Vanilla never takes a damaged, enchanted or renamed item for a recipe ({@code findSlotMatchingUnusedItem}). */
    @Test
    void plainItemsComeBeforeWornOnes() {
        List<List<String>> renamedFirst = cells(List.of(RENAMED_OAK, OAK));
        Supply<String> renamedEverywhere = Supply.of(Map.of(RENAMED_OAK, 64L), Map.of(RENAMED_OAK, 1000L),
                Set.of(RENAMED_OAK), Set.of(RENAMED_OAK));
        assertEquals(OAK, IngredientPicker.pick(renamedFirst, renamedEverywhere).cell(0).orElseThrow(),
                "a plain plank nobody has beats a delivered, plentiful, makeable renamed one");
        assertEquals(RENAMED_BIRCH, IngredientPicker.pick(cells(List.of(RENAMED_OAK, RENAMED_BIRCH)),
                Supply.of(Map.of(RENAMED_BIRCH, 1L), Map.of(), Set.of(), Set.of(RENAMED_OAK, RENAMED_BIRCH)))
                .cell(0).orElseThrow(), "when every candidate is worn the rest of the ranking still decides");
        assertEquals(RENAMED_OAK, IngredientPicker.pick(cells(List.of(RENAMED_OAK, RENAMED_BIRCH)),
                Supply.of(Map.of(), Map.of(), Set.of(), Set.of(RENAMED_OAK, RENAMED_BIRCH))).cell(0).orElseThrow(),
                "and never fails: the first candidate");
    }

    @Test
    void aMissingIngredientNeverFails() {
        List<List<String>> piston = cells(PLANKS, PLANKS, PLANKS, List.of(COBBLE, DEEPSLATE, BLACKSTONE),
                List.of(IRON), List.of(COBBLE, DEEPSLATE, BLACKSTONE), List.of(COBBLE, DEEPSLATE, BLACKSTONE),
                List.of(REDSTONE), List.of(COBBLE, DEEPSLATE, BLACKSTONE));
        assertEquals(CraftGrid.of(OAK, OAK, OAK, DEEPSLATE, IRON, DEEPSLATE, DEEPSLATE, REDSTONE, DEEPSLATE),
                IngredientPicker.pick(piston, buffer(Map.of(DEEPSLATE, 4L))),
                "no iron, no redstone and no planks anywhere: the piston is still laid, from the delivered deepslate");
    }

    // --- one key per ingredient --------------------------------------------------------------------------------------

    @Test
    void cellsWithTheSameListGetTheSameKey() {
        List<List<String>> chest = repeat(PLANKS, CraftGrid.SIZE);
        chest.set(4, List.of());
        CraftGrid<String> mixed = IngredientPicker.pick(chest, buffer(Map.of(OAK, 4L, BIRCH, 4L, SPRUCE, 4L)));
        assertEquals(CraftGrid.of(OAK, OAK, OAK, OAK, null, OAK, OAK, OAK, OAK), mixed,
                "twelve delivered planks of three kinds still lay one kind, never a mix");
        assertEquals(CraftGrid.of(BIRCH, BIRCH, BIRCH, BIRCH, null, BIRCH, BIRCH, BIRCH, BIRCH),
                IngredientPicker.pick(chest, buffer(Map.of(OAK, 4L, BIRCH, 8L, SPRUCE, 4L))),
                "and the kind that makes the whole chest wins");
    }

    @Test
    void overlappingListsSettleOnOneKey() {
        List<List<String>> grid = cells(List.of(SPRUCE), PLANKS, PLANKS);
        assertEquals(CraftGrid.of(SPRUCE, SPRUCE, SPRUCE), IngredientPicker.pick(grid, NOTHING),
                "nothing else tells the planks apart: the key the spruce-only cell took");
        assertEquals(CraftGrid.of(SPRUCE, OAK, OAK), IngredientPicker.pick(grid, stock(Map.of(OAK, 64L))),
                "but stock still beats it");
        assertEquals(CraftGrid.of(OAK, OAK, OAK, OAK), IngredientPicker.pick(
                cells(List.of(OAK, BIRCH), List.of(OAK, BIRCH), List.of(BIRCH, OAK), List.of(BIRCH, OAK)), NOTHING),
                "the same members in another order are another list, and still end on one key");
    }

    @Test
    void anEarlierGroupKeepsWhatItNeeds() {
        Supply<String> oneEach = buffer(Map.of(OAK, 1L, BIRCH, 1L));
        assertEquals(CraftGrid.of(BIRCH, OAK), IngredientPicker.pick(cells(List.of(OAK, BIRCH), List.of(OAK)),
                oneEach), "the oak-only cell is picked first and keeps the delivered oak plank");
        assertEquals(CraftGrid.of(OAK, BIRCH), IngredientPicker.pick(cells(List.of(OAK), List.of(OAK, BIRCH)),
                oneEach), "whichever cell comes first in the grid");
        assertEquals(CraftGrid.of(OAK, BIRCH), IngredientPicker.pick(cells(List.of(OAK), List.of(OAK, BIRCH)),
                stock(Map.of(OAK, 1L, BIRCH, 1L))), "the same for what the warehouse can hand");
        assertEquals(CraftGrid.of(OAK, OAK), IngredientPicker.pick(cells(List.of(OAK), List.of(OAK, BIRCH)),
                Supply.of(Map.of(OAK, 1L), Map.of(OAK, 1L, BIRCH, 1L), Set.of())),
                "two oak planks are enough for both cells");
    }

    // --- caps, lookups, numbers --------------------------------------------------------------------------------------

    @Test
    void candidatesAreCutAfterTheCap() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < IngredientPicker.MAX_CANDIDATES + 6; i++)
            many.add("item_" + i);
        assertEquals(many.subList(0, IngredientPicker.MAX_CANDIDATES), IngredientPicker.considered(many));
        List<String> repeated = new ArrayList<>(Collections.nCopies(IngredientPicker.MAX_CANDIDATES * 2, OAK));
        repeated.add(BIRCH);
        repeated.add(OAK);
        assertEquals(List.of(OAK, BIRCH), IngredientPicker.considered(repeated),
                "duplicates count once, at their first place, and do not use up the cap");
        assertEquals(List.of(), IngredientPicker.considered(List.of()));

        String beyond = many.get(IngredientPicker.MAX_CANDIDATES);
        Counting supply = new Counting(Supply.of(Map.of(beyond, 64L), Map.of(beyond, 1000L), Set.of(beyond)));
        assertEquals(CraftGrid.of(many.get(0)), IngredientPicker.pick(cells(many), supply),
                "a candidate past the cap is never picked, however much of it there is");
        for (int i = IngredientPicker.MAX_CANDIDATES; i < many.size(); i++)
            assertFalse(supply.keysAsked().contains(many.get(i)), "and never looked up: " + many.get(i));
    }

    @Test
    void everyKeyIsLookedUpOnceAndOnlyWhenCompared() {
        Counting supply = new Counting(Supply.of(Map.of(OAK, 1L), Map.of(BIRCH, 9L), Set.of(SPRUCE)));
        List<List<String>> grid = cells(PLANKS, PLANKS, List.of(OAK), List.of(STICK), List.of(COAL, CHARCOAL),
                PLANKS, List.of(OAK, BIRCH), null, List.of(STICK));
        IngredientPicker.pick(grid, supply);
        assertEquals(new TreeSet<>(List.of(OAK, BIRCH, SPRUCE, COAL, CHARCOAL)), supply.keysAsked(),
                "the stick, a single candidate wherever it appears, is never asked about");
        for (Map.Entry<String, Integer> question : supply.asked.entrySet())
            assertEquals(1, question.getValue(), "asked once: " + question.getKey());
    }

    // --- keys a client chose: one hash code for all ------------------------------------------------------------------

    /**
     * A key whose hash code a hostile client chose: every instance has the same one, as item keys that differ only in
     * what their hash does not read have (a name's colour, a mod's own component, ADR-064). Equal by its id only;
     * every call of {@link #equals} is counted.
     */
    private static final class Colliding {
        private final int id;
        private final long[] equalsCalls;

        Colliding(int id, long[] equalsCalls) {
            this.id = id;
            this.equalsCalls = equalsCalls;
        }

        @Override
        public boolean equals(Object other) {
            equalsCalls[0]++;
            return other instanceof Colliding colliding && colliding.id == id;
        }

        @Override
        public int hashCode() {
            return 42;
        }

        @Override
        public String toString() {
            return "colliding#" + id;
        }
    }

    /**
     * The worst lay a client can send — nine cells of {@value IngredientPicker#MAX_CANDIDATES} distinct keys, all with
     * one hash code — costs a few comparisons per candidate, not the square of the candidates: only the first
     * {@value IngredientPicker#MAX_SAME_HASH} distinct keys of the lay are kept, so the first cell keeps four and every
     * other cell ends up empty. Without the bound the hash sets and maps of a pick compare such keys well over a
     * hundred thousand times; the bound allows a small multiple of the candidates.
     */
    @Test
    void keysSharingAHashCodeCostLinearWork() {
        long[] equalsCalls = new long[1];
        List<List<Colliding>> grid = new ArrayList<>();
        int next = 0;
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            List<Colliding> candidates = new ArrayList<>();
            for (int i = 0; i < IngredientPicker.MAX_CANDIDATES; i++)
                candidates.add(new Colliding(next++, equalsCalls));
            grid.add(candidates);
        }
        Map<Colliding, Long> stocked = new HashMap<>();
        stocked.put(grid.get(0).get(IngredientPicker.MAX_SAME_HASH - 1), 64L);
        Supply<Colliding> supply = Supply.of(Map.of(), stocked, Set.of());
        equalsCalls[0] = 0;
        CraftGrid<Colliding> picked = IngredientPicker.pick(grid, supply);

        int candidates = CraftGrid.SIZE * IngredientPicker.MAX_CANDIDATES;
        long bound = (long) candidates * IngredientPicker.MAX_SAME_HASH * 2;
        assertTrue(equalsCalls[0] <= bound, "a pick of " + candidates + " keys with one hash code compared keys "
                + equalsCalls[0] + " times, more than " + bound);
        assertEquals(Optional.of(grid.get(0).get(IngredientPicker.MAX_SAME_HASH - 1)), picked.cell(0),
                "the first cell keeps its first four keys and ranks them as usual: the stocked fourth wins");
        for (int cell = 1; cell < CraftGrid.SIZE; cell++)
            assertEquals(Optional.empty(), picked.cell(cell), "every key of cell " + cell + " was dropped");
    }

    /**
     * The bound counts distinct keys only: the same key in every cell, or many times in one, is kept wherever it is,
     * and keys with hash codes of their own are never dropped. A fifth distinct key with a shared hash code is dropped
     * even where it would rank first, and a fourth is not.
     */
    @Test
    void theHashBoundCountsDistinctKeysOnly() {
        long[] equalsCalls = new long[1];
        List<Colliding> five = new ArrayList<>();
        for (int i = 0; i < IngredientPicker.MAX_SAME_HASH + 1; i++)
            five.add(new Colliding(i, equalsCalls));
        Colliding fourth = five.get(IngredientPicker.MAX_SAME_HASH - 1);
        Colliding fifth = five.get(IngredientPicker.MAX_SAME_HASH);
        List<List<Colliding>> grid = new ArrayList<>(Collections.nCopies(CraftGrid.SIZE, five));
        assertEquals(grid.stream().map(cell -> cell.subList(0, IngredientPicker.MAX_SAME_HASH)).toList(),
                IngredientPicker.spreadHashes(grid), "the same four keys in every cell, the fifth dropped in each");

        assertEquals(Optional.of(five.getFirst()), IngredientPicker.pick(List.of(five),
                Supply.of(Map.of(fifth, 64L), Map.of(), Set.of())).cell(0),
                "a dropped fifth key is never picked, however much of it is delivered");
        assertEquals(Optional.of(fourth), IngredientPicker.pick(List.of(five),
                Supply.of(Map.of(fourth, 64L), Map.of(), Set.of())).cell(0), "the fourth is kept and ranked");

        List<String> planks = new ArrayList<>();
        for (int i = 0; i < IngredientPicker.MAX_CANDIDATES; i++)
            planks.add("planks_" + i);
        List<List<String>> chest = repeat(planks, CraftGrid.SIZE);
        chest.set(4, List.of());
        assertEquals(chest, IngredientPicker.spreadHashes(chest), "real items with their own hash codes all stay");
    }

    /**
     * A cell that lists far more than {@value IngredientPicker#MAX_SAME_HASH} candidates with hash codes of their own —
     * a tipped arrow's potion slot, which lists every potion, each of which hashes apart (ADR-064) — keeps every one of
     * them, so the one the buffer holds is picked wherever it stands in the list, the last one included.
     */
    @Test
    void aCellOfDistinctHashCodesKeepsEveryCandidate() {
        List<String> potions = new ArrayList<>();
        for (int i = 0; i < IngredientPicker.MAX_CANDIDATES; i++)
            potions.add("potion_" + i);
        List<String> held = List.of(potions.getFirst(), potions.get(IngredientPicker.MAX_SAME_HASH), potions.getLast());
        for (String potion : held) {
            List<List<String>> grid = repeat(List.of("arrow"), CraftGrid.SIZE);
            grid.set(4, potions);
            CraftGrid<String> picked = IngredientPicker.pick(grid, buffer(Map.of(potion, 1L)));
            assertEquals(Optional.of(potion), picked.cell(4), "the potion the buffer holds: " + potion);
            assertEquals(Optional.of("arrow"), picked.cell(0), "and the arrows around it");
        }
    }

    @Test
    void countsBelowZeroAreZeroAndHugeOnesDoNotOverflow() {
        assertEquals(OAK, IngredientPicker.pick(cells(List.of(OAK, BIRCH)),
                Supply.of(Map.of(OAK, Long.MAX_VALUE, BIRCH, Long.MAX_VALUE), Map.of(OAK, Long.MAX_VALUE), Set.of()))
                .cell(0).orElseThrow(), "buffer plus stock saturates: a tie with the full birch buffer, never a "
                        + "negative oak");
        assertEquals(OAK, IngredientPicker.pick(cells(List.of(OAK, BIRCH)),
                Supply.of(Map.of(BIRCH, -5L), Map.of(BIRCH, -5L), Set.of())).cell(0).orElseThrow(),
                "a negative count is none, and none against none is a tie");
        assertEquals(BIRCH, IngredientPicker.pick(cells(List.of(OAK, BIRCH)),
                Supply.of(Map.of(OAK, -5L), Map.of(OAK, -5L, BIRCH, 1L), Set.of())).cell(0).orElseThrow());
    }

    // --- determinism -------------------------------------------------------------------------------------------------

    /**
     * Random recipes and supplies: the answer is the same every time and whatever map implementation the counts come
     * in, every picked key is one of its cell's candidates, and cells with equal lists hold equal keys.
     */
    @Test
    void theAnswerIsDeterministic() {
        Random random = new Random(24L);
        List<String> pool = List.of(OAK, BIRCH, SPRUCE, STICK, COAL, CHARCOAL, COBBLE, DEEPSLATE, IRON, REDSTONE);
        for (int round = 0; round < 500; round++) {
            List<List<String>> lists = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                List<String> list = new ArrayList<>(pool);
                Collections.shuffle(list, random);
                lists.add(List.copyOf(list.subList(0, 1 + random.nextInt(4))));
            }
            List<List<String>> grid = new ArrayList<>();
            int cells = random.nextInt(CraftGrid.SIZE + 1);
            for (int i = 0; i < cells; i++)
                grid.add(random.nextInt(5) == 0 ? List.of() : lists.get(random.nextInt(lists.size())));

            Map<String, Long> inBuffer = new LinkedHashMap<>();
            Map<String, Long> available = new LinkedHashMap<>();
            Set<String> producible = new HashSet<>();
            Set<String> worn = new HashSet<>();
            for (String key : pool) {
                if (random.nextBoolean())
                    inBuffer.put(key, (long) random.nextInt(10));
                if (random.nextBoolean())
                    available.put(key, (long) random.nextInt(100));
                if (random.nextInt(4) == 0)
                    producible.add(key);
                if (random.nextInt(8) == 0)
                    worn.add(key);
            }
            CraftGrid<String> picked = IngredientPicker.pick(grid, Supply.of(inBuffer, available, producible, worn));
            assertEquals(picked, IngredientPicker.pick(grid, Supply.of(inBuffer, available, producible, worn)));
            Map<String, Long> reversedBuffer = new LinkedHashMap<>();
            List<String> bufferKeys = new ArrayList<>(inBuffer.keySet());
            Collections.reverse(bufferKeys);
            for (String key : bufferKeys)
                reversedBuffer.put(key, inBuffer.get(key));
            assertEquals(picked, IngredientPicker.pick(grid, Supply.of(reversedBuffer, new TreeMap<>(available),
                    new TreeSet<>(producible), new TreeSet<>(worn))), "round " + round);

            for (int i = 0; i < CraftGrid.SIZE; i++) {
                List<String> candidates = i < grid.size() ? grid.get(i) : List.of();
                Optional<String> cell = picked.cell(i);
                assertEquals(candidates.isEmpty(), cell.isEmpty(), "round " + round + ", cell " + i);
                cell.ifPresent(key -> assertTrue(candidates.contains(key), "a candidate of its own cell: " + key));
                for (int j = 0; j < i; j++) {
                    if (!candidates.isEmpty() && candidates.equals(grid.get(j)))
                        assertEquals(picked.cell(j), cell, "equal lists, equal keys: cells " + j + " and " + i);
                }
            }
        }
    }
}
