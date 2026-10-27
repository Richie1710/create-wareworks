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
}
