package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.core.stock.RestockInput;
import dev.wareworks.core.stock.RestockLimits;
import dev.wareworks.core.stock.RestockPlan;
import dev.wareworks.core.stock.RestockPlanner;
import dev.wareworks.core.stock.StockLevels;
import dev.wareworks.core.stock.StockRule;

/**
 * What the planners cost with the most patterns a station may hold (issue #35, ADR-068): eight stations full of
 * nine-ingredient patterns, compared with the same aisle at the old cap of eight patterns per station and at 18.
 * <p>
 * The aisle is built to be expensive rather than typical: results form chains four levels deep, every result has four
 * patterns of which the first two are <b>dead ends</b> (an ingredient nothing stocks and nothing makes), so a chain walk
 * first burns its fallback attempts on them, and the top-of-chain order is larger than the default ingredient budget,
 * so the planner also binary-searches the largest plan that fits.
 * <p>
 * The assertions are deliberately loose (a median below 5 ms) so that a busy build machine cannot fail them; the
 * numbers themselves are printed and are the ones quoted in ADR-068 and in {@link ProduciblePlanner}'s class comment.
 * <p>
 * Only the core planners are measured. {@code terminalPass} is the core of the terminal's producible pass
 * ({@code ProduciblePlanner} over a map of stock); the controller's reservation-aware availability lookup, resolving
 * the stations of the aisle and a Crafter Feeder's recipe checks are not included.
 */
class ProductionScaleTest {
    private static final int STATIONS = 8;
    private static final int OLD_CAP = 8;
    /** A size between the two, so the growth is read from more than two points. */
    private static final int MIDDLE = 18;
    private static final int LEVELS = 4;
    private static final int ALTERNATIVES = 4;
    private static final int RAW_KEYS = 64;
    private static final int WARMUP = 200;
    private static final int RUNS = 1000;
    /** Loose ceiling per median, so a slow CI machine cannot fail the build over noise. */
    private static final double CEILING_MS = 5.0;
    /**
     * Loose ceiling for the filter-cell aisle (ADR-082), whose every one of its 288 patterns asks 128 candidates: a
     * shape far past what a real aisle holds, kept as the worst case rather than as the typical one.
     */
    private static final double FILTER_CEILING_MS = 25.0;

    /** One aisle of {@code STATIONS} stations with {@code perStation} patterns each, and what it is asked. */
    private record Aisle(List<StationPattern<String, Integer>> patterns, Map<String, Long> stock, List<String> results,
                         String top, List<String> needs) {
        ToLongFunction<String> available() {
            return key -> stock.getOrDefault(key, 0L);
        }

        ProductionPlanInput<String, Integer> input(PlanLimits limits) {
            return ProductionPlanInput.of(patterns, PlanBudget.ofMap(stock), limits, limits.maxSteps());
        }
    }

    private static String raw(int index) {
        return "raw" + Math.floorMod(index, RAW_KEYS);
    }

    /**
     * {@code STATIONS × perStation} patterns of nine ingredients each. Results are spread over {@value #LEVELS} chain
     * levels with {@value #ALTERNATIVES} patterns per result; a result on level L > 0 needs one result of level L - 1
     * plus eight raw items, level 0 needs nine raw items, and alternatives 0 and 1 also need an item nobody has.
     */
    private static Aisle aisle(int perStation) {
        int total = STATIONS * perStation;
        int resultCount = total / ALTERNATIVES;
        int perLevel = resultCount / LEVELS;
        List<String> results = new ArrayList<>(resultCount);
        for (int r = 0; r < resultCount; r++)
            results.add("made" + r);
        List<StationPattern<String, Integer>> patterns = new ArrayList<>(total);
        int index = 0;
        for (int r = 0; r < resultCount; r++) {
            int level = r / perLevel;
            for (int alt = 0; alt < ALTERNATIVES; alt++) {
                List<ProductionEntry<String>> cells = new ArrayList<>(ProductionPattern.GRID_SIZE);
                Set<String> used = new LinkedHashSet<>();
                if (level > 0)
                    used.add(results.get((level - 1) * perLevel + (r + alt) % perLevel));
                if (alt < 2)
                    used.add("missing" + r);
                int salt = r * 7 + alt * 3;
                while (used.size() < ProductionPattern.GRID_SIZE)
                    used.add(raw(salt++));
                for (String key : used)
                    cells.add(new ProductionEntry<>(key, 1));
                ProductionPattern<String> pattern = ProductionPattern.fromGrid(cells,
                        new ProductionEntry<>(results.get(r), 1));
                patterns.add(StationPattern.of(index % STATIONS, pattern));
                index++;
            }
        }
        Map<String, Long> stock = new HashMap<>();
        for (int i = 0; i < RAW_KEYS; i++)
            stock.put(raw(i), 1_000_000L);
        String top = results.get(resultCount - 1);
        List<String> needs = new ArrayList<>(ProductionPattern.GRID_SIZE);
        for (int i = 0; i < ProductionPattern.GRID_SIZE; i++)
            needs.add(results.get(Math.floorMod(resultCount - 1 - i * 3, resultCount)));
        return new Aisle(List.copyOf(patterns), stock, List.copyOf(results), top, List.copyOf(needs));
    }

    /**
     * The core of the terminal's stock push: every pattern's producible amount, best per result, plus the producible
     * keys, over a plain map of stock (no reservation lookup, no station resolution, no feeder checks).
     */
    private static long terminalPass(Aisle aisle) {
        ToLongFunction<String> available = aisle.available();
        Map<String, Long> amounts = new LinkedHashMap<>();
        List<ProductionPattern<String>> plain = new ArrayList<>(aisle.patterns().size());
        for (StationPattern<String, Integer> candidate : aisle.patterns()) {
            plain.add(candidate.pattern());
            amounts.merge(candidate.result(), ProduciblePlanner.producibleAmount(candidate.pattern(), available),
                    Math::max);
        }
        return amounts.size() + ProduciblePlanner.producibleKeys(plain).size();
    }

    private static long restockPass(Aisle aisle) {
        Map<String, List<ProductionPattern<String>>> byResult = new LinkedHashMap<>();
        for (StationPattern<String, Integer> candidate : aisle.patterns())
            byResult.computeIfAbsent(candidate.result(), key -> new ArrayList<>()).add(candidate.pattern());
        List<RestockInput<String>> inputs = new ArrayList<>(aisle.results().size());
        for (String key : aisle.results())
            inputs.add(RestockInput.of(new StockRule<>(key, 64L, StockRule.UNSET, StockRule.UNSET), StockLevels.NONE,
                    byResult.getOrDefault(key, List.of())));
        RestockPlan<String> plan = RestockPlanner.plan(inputs, RestockLimits.DEFAULT, 0, false, aisle.available());
        return plan.order().isPresent() ? 1L : 0L;
    }

    private static long previewPass(Aisle aisle) {
        SequencedMap<String, Long> needs = new LinkedHashMap<>();
        for (String key : aisle.needs())
            needs.put(key, 4L);
        return SequentialPlans.preview(aisle.input(PlanLimits.DEFAULT), needs).chains().size();
    }

    @FunctionalInterface
    private interface Work {
        long run();
    }

    /** Median in milliseconds of {@value #RUNS} runs after {@value #WARMUP} warm-up runs. */
    private static double median(Work work) {
        long sink = 0L;
        for (int i = 0; i < WARMUP; i++)
            sink += work.run();
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            sink += work.run();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        assertTrue(sink >= 0L);
        return times[RUNS / 2] / 1_000_000.0;
    }

    private static Map<String, Double> measure(Aisle aisle) {
        Map<String, Double> medians = new LinkedHashMap<>();
        medians.put("producible core (terminal)", median(() -> terminalPass(aisle)));
        medians.put("plan, default limits", median(() -> ProductionPlanner
                .plan(aisle.input(PlanLimits.DEFAULT), aisle.top(), 16L).accepted() ? 1L : 0L));
        medians.put("plan, unbounded limits", median(() -> ProductionPlanner
                .plan(aisle.input(PlanLimits.UNBOUNDED), aisle.top(), 16L).accepted() ? 1L : 0L));
        medians.put("craft panel preview (9 needs)", median(() -> previewPass(aisle)));
        medians.put("restock pass (1 rule per result)", median(() -> restockPass(aisle)));
        return medians;
    }

    @Test
    void theAisleIsTheShapeTheBenchmarkClaims() {
        Aisle full = aisle(ProductionPatterns.MAX_SLOTS);
        assertEquals(STATIONS * ProductionPatterns.MAX_SLOTS, full.patterns().size());
        for (StationPattern<String, Integer> pattern : full.patterns())
            assertEquals(ProductionPattern.GRID_SIZE, pattern.pattern().ingredients().size());
        // The chain is really four levels deep and really reachable past its dead ends.
        ProductionPlanResult<String, Integer> plan = ProductionPlanner.plan(full.input(PlanLimits.UNBOUNDED),
                full.top(), 16L);
        assertTrue(plan.accepted(), () -> "refused: " + plan.refusal() + " about " + plan.about());
        assertEquals(LEVELS, plan.orElseThrow().steps());
        // With the default ingredient budget the order is clamped, not refused.
        ProductionPlanResult<String, Integer> clamped = ProductionPlanner.plan(full.input(PlanLimits.DEFAULT),
                full.top(), 16L);
        assertTrue(clamped.accepted());
        assertTrue(clamped.orElseThrow().rootPromise() < 16L);
        assertFalse(SequentialPlans.preview(full.input(PlanLimits.DEFAULT), new LinkedHashMap<>(Map.of(full.top(), 4L)))
                .chains().isEmpty());
    }

    @Test
    void thirtySixPatternsPerStationStayCheap() {
        Aisle small = aisle(OLD_CAP);
        Aisle middle = aisle(MIDDLE);
        Aisle large = aisle(ProductionPatterns.MAX_SLOTS);
        // A first round of each only warms the JIT, so no shape is measured against a colder VM than another.
        measure(small);
        measure(middle);
        measure(large);
        Map<String, Double> old = measure(small);
        Map<String, Double> mid = measure(middle);
        Map<String, Double> full = measure(large);
        StringBuilder report = new StringBuilder("ProductionScaleTest: median ms, " + STATIONS + " stations x "
                + OLD_CAP + " / x " + MIDDLE + " / x " + ProductionPatterns.MAX_SLOTS + " patterns of 9 ingredients\n");
        full.forEach((name, ms) -> report.append(String.format(java.util.Locale.ROOT,
                "  %-34s %8.4f -> %8.4f -> %8.4f (x%.1f)%n", name, old.get(name), mid.get(name), ms,
                ms / Math.max(1e-6, old.get(name)))));
        System.out.print(report);
        full.forEach((name, ms) -> assertTrue(ms < CEILING_MS, name + " took " + ms + " ms"));
    }

    /**
     * Filter cells at the cap (ADR-082): {@value #STATIONS} stations × 36 patterns, each with two choice ingredients of
     * 64 candidates over 2 000 stock keys, through the terminal's producible pass and a plan — once with the two
     * candidate lists of a pattern disjoint from each other and from its exact cells (the closed form), once
     * overlapping (a real payment probe per pattern). The numbers printed are the ones quoted in
     * {@link ProduciblePlanner}'s class comment.
     */
    @Test
    void filterCellsAtTheCapStayCheap() {
        double[] disjoint = filterCells(false);
        double[] overlapping = filterCells(true);
        System.out.printf(java.util.Locale.ROOT, "ProductionScaleTest (filter cells): %d patterns x 2 choices of 64 "
                + "candidates over 2000 keys: producible pass %.4f ms disjoint, %.4f ms overlapping; plan %.4f ms / "
                + "%.4f ms%n", STATIONS * ProductionPatterns.MAX_SLOTS, disjoint[0], overlapping[0], disjoint[1],
                overlapping[1]);
        for (double ms : new double[] {disjoint[0], disjoint[1], overlapping[0], overlapping[1]})
            assertTrue(ms < FILTER_CEILING_MS, "took " + ms + " ms");
    }

    /** Median ms of the producible pass and of one plan over the filter-cell aisle. */
    private static double[] filterCells(boolean overlap) {
        int keys = 2000;
        int candidatesPerChoice = 64;
        Map<String, Long> stock = new HashMap<>();
        for (int i = 0; i < keys; i++)
            stock.put("stock" + i, 16L + i % 97);
        for (int i = 0; i < 64; i++)
            stock.put("exact" + i, 1_000L);
        Map<String, List<String>> byChoice = new HashMap<>();
        List<StationPattern<String, Integer>> patterns = new ArrayList<>();
        int total = STATIONS * ProductionPatterns.MAX_SLOTS;
        for (int p = 0; p < total; p++) {
            String first = "filter" + (2 * p);
            String second = "filter" + (2 * p + 1);
            int base = p * 13;
            byChoice.put(first, window(base, candidatesPerChoice, keys));
            byChoice.put(second, window(base + (overlap ? 32 : candidatesPerChoice), candidatesPerChoice, keys));
            List<ProductionEntry<String>> cells = new ArrayList<>();
            cells.add(new ProductionEntry<>(first, 4));
            cells.add(new ProductionEntry<>(second, 2));
            for (int i = 0; i < 7; i++)
                cells.add(new ProductionEntry<>(overlap ? "stock" + Math.floorMod(base + i, keys)
                        : "exact" + Math.floorMod(p * 7 + i, 64), 1));
            patterns.add(StationPattern.of(p % STATIONS, ProductionPattern.fromGrid(cells,
                    new ProductionEntry<>("filtered" + p, 1), Set.of(first, second))));
        }
        IngredientChoices<String> choices = IngredientChoices.of(byChoice);
        ToLongFunction<String> available = key -> stock.getOrDefault(key, 0L);
        Work pass = () -> {
            long sum = 0L;
            for (StationPattern<String, Integer> candidate : patterns)
                sum += ProduciblePlanner.producibleAmount(candidate.pattern(), available, choices);
            return sum;
        };
        assertTrue(pass.run() > 0L, "the filters really pay");
        ProductionPlanInput<String, Integer> input = ProductionPlanInput.of(patterns, PlanBudget.ofMap(stock),
                PlanLimits.DEFAULT, PlanLimits.DEFAULT.maxSteps()).withChoices(choices);
        Work plan = () -> ProductionPlanner.plan(input, "filtered" + (total - 1), 8L).accepted() ? 1L : 0L;
        assertEquals(1L, plan.run());
        median(pass);
        median(plan);
        return new double[] {median(pass), median(plan)};
    }

    private static List<String> window(int start, int size, int keys) {
        List<String> keysInWindow = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            keysInWindow.add("stock" + Math.floorMod(start + i, keys));
        return keysInWindow;
    }
}
