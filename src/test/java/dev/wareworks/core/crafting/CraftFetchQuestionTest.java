package dev.wareworks.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.crafting.CraftFetchQuestion.Bound;
import dev.wareworks.core.crafting.CraftFetchQuestion.Chains;
import dev.wareworks.core.crafting.CraftFetchQuestion.Line;
import dev.wareworks.core.crafting.CraftFetchQuestion.Promises;
import dev.wareworks.core.crafting.CraftFetchQuestion.Verdict;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.production.ChainPreview;
import dev.wareworks.core.production.FetchPreview;
import dev.wareworks.core.production.PlanBudget;
import dev.wareworks.core.production.PlanLimits;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.ProductionPlan;
import dev.wareworks.core.production.ProductionPlanInput;
import dev.wareworks.core.production.ProductionPlanner;
import dev.wareworks.core.production.SequentialPlans;
import dev.wareworks.core.production.StationPattern;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.RequestAcknowledgement;

/**
 * Fetch on a crafting grid, measured before anything is requested (issue #24, slices 2 and 3): how many whole crafts
 * the racks can supply, how many fit into the terminal's buffer once every open request has put its items down, which
 * of the two binds and about which item, whether the player has to be asked, and that a Yes is measured again rather
 * than trusted — and, since slice 3, what production chains at the warehouse's own machines would make first, with the
 * numbers of the fetch a Yes starts and the refusal that names the item a chain cannot get past.
 * <p>
 * The buffer here is a terminal's: {@value #SLOTS} slots unless a test says otherwise, each with the slot limit both
 * real sides report ({@value #LIMIT}), so what a slot holds is bounded by the item's own stack size — 64 for most
 * items, 16 for eggs, 1 for a milk bucket.
 */
class CraftFetchQuestionTest {
    private static final int LIMIT = 99;
    private static final int SLOTS = 9;

    private static final String PLANK = "oak_planks";
    private static final String STICK = "stick";
    private static final String COBBLE = "cobblestone";
    private static final String IRON = "iron_ingot";
    private static final String REDSTONE = "redstone";
    private static final String MILK = "milk_bucket";
    private static final String SUGAR = "sugar";
    private static final String EGG = "egg";
    private static final String WHEAT = "wheat";
    private static final String SLIME = "slime_ball";
    private static final String STRING = "string";
    private static final String LOG = "oak_log";
    private static final String CHARCOAL = "charcoal";
    private static final String COAL = "coal";
    private static final String HOOK = "tripwire_hook";

    /** Eight planks around an empty centre. */
    private static final CraftGrid<String> CHEST = CraftGrid.of(PLANK, PLANK, PLANK, PLANK, null, PLANK, PLANK, PLANK,
            PLANK);
    /** Two planks on top of each other. */
    private static final CraftGrid<String> STICKS = CraftGrid.of(PLANK, null, null, PLANK);
    /** Three milk, two sugar, one egg, three wheat: the recipe whose milk buckets take a slot each. */
    private static final CraftGrid<String> CAKE = CraftGrid.of(MILK, MILK, MILK, SUGAR, EGG, SUGAR, WHEAT, WHEAT,
            WHEAT);
    /** Three planks, four cobblestone, one iron, one redstone. */
    private static final CraftGrid<String> PISTON = CraftGrid.of(PLANK, PLANK, PLANK, COBBLE, IRON, COBBLE, COBBLE,
            REDSTONE, COBBLE);
    /** A lead: four string, one slime ball. */
    private static final CraftGrid<String> LEAD = CraftGrid.of(STRING, STRING, null, STRING, SLIME, null, null, null,
            STRING);
    /** One plank and one charcoal: two ingredients the machines both make from logs. */
    private static final CraftGrid<String> KINDLING = CraftGrid.of(PLANK, CHARCOAL);
    /** A campfire: three sticks around a coal, three logs below — logs taken as logs and, as sticks, made from logs. */
    private static final CraftGrid<String> CAMPFIRE = CraftGrid.of(null, STICK, null, STICK, COAL, STICK, LOG, LOG,
            LOG);
    /** Oak fences: plank, stick, plank in two rows — sticks made of planks, planks made of logs. */
    private static final CraftGrid<String> FENCE = CraftGrid.of(PLANK, STICK, PLANK, PLANK, STICK, PLANK);
    /** An oak fence gate: stick, plank, stick in two rows — the sticks' cell before the planks'. */
    private static final CraftGrid<String> GATE = CraftGrid.of(STICK, PLANK, STICK, STICK, PLANK, STICK);
    /** A crossbow: three sticks, an iron ingot, two string and a tripwire hook; the string's cell before the hook's. */
    private static final CraftGrid<String> CROSSBOW = CraftGrid.of(STICK, IRON, STICK, STRING, HOOK, STRING, null,
            STICK, null);
    /** The crossbow's ingredients with the hook's cell before the string's. */
    private static final CraftGrid<String> HOOK_FIRST = CraftGrid.of(STICK, IRON, STICK, HOOK, STRING, STRING, null,
            STICK, null);

    /**
     * The warehouse's machines: a saw (a log to four planks), a kiln (a log to a charcoal), a crafter (sticks, and two
     * tripwire hooks of an iron ingot, a stick and a plank).
     */
    private static final List<StationPattern<String, String>> MACHINES = List.of(
            StationPattern.of("saw", ProductionPattern.of(LOG, 1, PLANK, 4)),
            StationPattern.of("kiln", ProductionPattern.of(LOG, 1, CHARCOAL, 1)),
            StationPattern.of("crafter", ProductionPattern.of(PLANK, 2, STICK, 4)),
            StationPattern.of("crafter", new ProductionPattern<>(List.of(new ProductionEntry<>(IRON, 1),
                    new ProductionEntry<>(STICK, 1), new ProductionEntry<>(PLANK, 1)), new ProductionEntry<>(HOOK, 2))));

    private static int maxStack(String key) {
        return switch (key) {
            case MILK -> 1;
            case EGG -> 16;
            default -> 64;
        };
    }

    private static List<SlotView<String>> emptyBuffer(int slots) {
        List<SlotView<String>> buffer = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++)
            buffer.add(SlotView.empty(LIMIT));
        return buffer;
    }

    /** {@code SLOTS} slots, the first ones holding {@code stacks}, the rest empty. */
    @SafeVarargs
    private static List<SlotView<String>> buffer(SlotView<String>... stacks) {
        List<SlotView<String>> buffer = emptyBuffer(SLOTS);
        for (int i = 0; i < stacks.length; i++)
            buffer.set(i, stacks[i]);
        return buffer;
    }

    private static SlotView<String> stack(String key, int count) {
        return SlotView.of(key, count, LIMIT, maxStack(key));
    }

    private static ToLongFunction<String> stock(Map<String, Long> stock) {
        return key -> stock.getOrDefault(key, 0L);
    }

    private static ToLongFunction<String> plenty() {
        return key -> 100_000L;
    }

    private static CraftFetchQuestion<String> measure(CraftGrid<String> grid, int crafts, List<SlotView<String>> buffer,
            ToLongFunction<String> available, List<KeyCount<String>> open) {
        return CraftFetchQuestion.measure(grid, crafts, buffer, available, open, CraftFetchQuestionTest::maxStack);
    }

    private static CraftFetchQuestion<String> measure(CraftGrid<String> grid, int crafts, List<SlotView<String>> buffer,
            ToLongFunction<String> available) {
        return measure(grid, crafts, buffer, available, List.of());
    }

    /** A Fetch measured with what production chains would make. */
    private static CraftFetchQuestion<String> offer(CraftGrid<String> grid, int crafts, List<SlotView<String>> buffer,
            ToLongFunction<String> available, Chains<String> chains) {
        return CraftFetchQuestion.measure(grid, crafts, buffer, available, List.of(), CraftFetchQuestionTest::maxStack,
                chains);
    }

    /** A preview of production chains that remembers every question it was asked. */
    private abstract static class Counting implements Chains<String> {
        final List<Map<String, Long>> needs = new ArrayList<>();
        final List<FetchPreview<String>> answers = new ArrayList<>();

        @Override
        public final FetchPreview<String> preview(SequencedMap<String, Long> lines) {
            assertFalse(lines.isEmpty(), "the chains are never asked about nothing");
            lines.values().forEach(amount -> assertTrue(amount >= 1L));
            needs.add(new LinkedHashMap<>(lines));
            FetchPreview<String> answer = answer(lines);
            answers.add(answer);
            return answer;
        }

        abstract FetchPreview<String> answer(SequencedMap<String, Long> lines);

        int previews() {
            return needs.size();
        }
    }

    /**
     * The warehouse's {@code previewProduction} in miniature: the lines walked in line order against what
     * {@code racks} holds when it is asked, each taking from the racks and having the rest made by {@link #MACHINES}
     * ({@link SequentialPlans}), with the items in {@code stopped} stopped by the safety stop.
     */
    private static Counting machines(Map<String, Long> racks, String... stopped) {
        return new Counting() {
            @Override
            FetchPreview<String> answer(SequencedMap<String, Long> lines) {
                ProductionPlanInput<String, String> input = ProductionPlanInput.<String, String>of(MACHINES,
                        PlanBudget.ofMap(racks), PlanLimits.UNBOUNDED, 16).withPaused(Set.of(stopped));
                return SequentialPlans.preview(input, lines);
            }
        };
    }

    /**
     * Chains that answer every line the racks cannot serve with {@code answer} for what they lack, each on its own:
     * every line takes what {@code racks} holds of its item, and nothing a chain does takes anything from another.
     */
    private static Counting answering(Map<String, Long> racks, BiFunction<String, Long, ChainPreview<String>> answer) {
        return new Counting() {
            @Override
            FetchPreview<String> answer(SequencedMap<String, Long> lines) {
                Map<String, Long> taken = new LinkedHashMap<>();
                Map<String, ChainPreview<String>> chains = new LinkedHashMap<>();
                lines.forEach((key, need) -> {
                    long took = Math.min(need, racks.getOrDefault(key, 0L));
                    taken.put(key, took);
                    if (need > took)
                        chains.put(key, answer.apply(key, need - took));
                });
                return new FetchPreview<>(taken, chains);
            }
        };
    }

    /**
     * What the craft order's portions would have made, requested one after another in line order with nothing in
     * between — the controller's request path in miniature: each claims what is left of its item in {@code racks},
     * the planner plans the rest against what is left, and that chain's ingredients are spoken for before the next
     * portion. The sum of every chain's whole-run output, which the order's budget has to cover.
     */
    private static long portionsMake(CraftGrid<String> grid, int crafts, Map<String, Long> racks) {
        Map<String, Long> left = new HashMap<>(racks);
        long made = 0L;
        for (Map.Entry<String, Integer> line : grid.toFetch(crafts, Map.of()).entrySet()) {
            String key = line.getKey();
            long claimed = Math.min(line.getValue(), left.getOrDefault(key, 0L));
            left.merge(key, -claimed, Long::sum);
            long rest = line.getValue() - claimed;
            if (rest < 1L)
                continue;
            ProductionPlan<String, String> plan = ProductionPlanner.plan(ProductionPlanInput.of(MACHINES,
                    PlanBudget.ofMap(new HashMap<>(left)), PlanLimits.UNBOUNDED, 16), key, rest).orElseThrow();
            plan.leafDemand().forEach((leaf, items) -> left.merge(leaf, -items, Long::sum));
            made += plan.root().output();
        }
        return made;
    }

    // --- everything is there ----------------------------------------------------------------------------------------

    @Test
    void aFetchTheWarehouseServesInFullStartsWithoutAQuestion() {
        CraftFetchQuestion<String> question = measure(CHEST, 3, emptyBuffer(SLOTS), plenty());
        assertEquals(3, question.asked());
        assertEquals(3, question.stockRuns());
        assertEquals(3, question.madeRuns());
        assertEquals(3, question.roomRuns());
        assertEquals(3, question.possible());
        assertEquals(Bound.NONE, question.bound());
        assertTrue(question.about().isEmpty(), "nothing binds, so nothing is named");
        assertTrue(question.named().isEmpty());
        assertFalse(question.required());
        assertFalse(question.refused());
        assertEquals(Verdict.START, question.verdict(false, 0L));
        assertEquals(RequestAcknowledgement.NONE, question.budget(), "nothing made, nothing agreed to");
    }

    @Test
    void theStartedOrderAsksForExactlyWhatTheBufferIsMissing() {
        List<SlotView<String>> buffer = buffer(stack(PLANK, 10), stack(STICK, 3));
        assertEquals(List.of(ListEntry.of(0, 0, PLANK, 14)), CraftFetchQuestion.entries(CHEST, 3, buffer),
                "x3 is 24 planks, and the 10 already here are topped up, never fetched again");
        assertEquals(List.of(ListEntry.of(0, 0, PLANK, 2)), CraftFetchQuestion.entries(STICKS, 1, emptyBuffer(SLOTS)),
                "sticks x1 brings exactly two planks");
        assertEquals(List.of(ListEntry.of(0, 0, PLANK, 6), ListEntry.of(0, 3, COBBLE, 8),
                ListEntry.of(0, 4, IRON, 2), ListEntry.of(0, 7, REDSTONE, 2)),
                CraftFetchQuestion.entries(PISTON, 2, emptyBuffer(SLOTS)),
                "one line per key, in first-cell order, positioned at that cell");
        assertTrue(CraftFetchQuestion.entries(CHEST, 1, buffer(stack(PLANK, 8))).isEmpty(),
                "a buffer that holds every craft starts no line at all");
    }

    // --- the stock bound ---------------------------------------------------------------------------------------------

    @Test
    void stockBoundsTheCraftsAndTheBufferCountsTowardsThem() {
        // 10 planks here and 14 in the racks: 24 planks, three chests' worth.
        CraftFetchQuestion<String> question = measure(CHEST, 4, buffer(stack(PLANK, 10)),
                stock(Map.of(PLANK, 14L)));
        assertEquals(3, question.stockRuns());
        assertEquals(3, question.possible());
        assertEquals(1, question.ready());
        assertEquals(Bound.STOCK, question.bound());
        assertEquals(Optional.of(PLANK), question.about());
        assertTrue(question.required());
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
        assertEquals(List.of(new Line<>(PLANK, 22L, 14L, 0L)), question.named(),
                "x4 needs 22 more planks and the racks hold 14");
        assertTrue(question.named().get(0).isShort());
    }

    @Test
    void theBottleneckIsTheKeyWithTheFewestCraftsAndTheFirstOnATie() {
        // Planks for 5 pistons, cobblestone for 2, iron for 2, redstone for 3.
        Map<String, Long> racks = Map.of(PLANK, 15L, COBBLE, 9L, IRON, 2L, REDSTONE, 3L);
        CraftFetchQuestion<String> question = measure(PISTON, 4, emptyBuffer(SLOTS), stock(racks));
        assertEquals(2, question.possible());
        assertEquals(Optional.of(COBBLE), question.about(),
                "cobblestone and iron both allow two; cobblestone is first");
        assertEquals(List.of(COBBLE, IRON, REDSTONE), keys(question.named()),
                "every key short of x4 is named, in grid order");
        assertEquals(new Line<>(IRON, 4L, 2L, 0L), question.named().get(1));
    }

    @Test
    void notOneCraftIsRefusedAndNamesTheMissingItem() {
        CraftFetchQuestion<String> question = measure(LEAD, 2, emptyBuffer(SLOTS),
                stock(Map.of(STRING, 640L)));
        assertEquals(0, question.possible());
        assertTrue(question.refused());
        assertFalse(question.required(), "a refusal is not a question");
        assertEquals(Verdict.NOT_ENOUGH, question.verdict(false, 0L));
        assertEquals(Optional.of(SLIME), question.about());
        assertEquals(List.of(new Line<>(SLIME, 2L, 0L, 0L)), question.named());
    }

    @Test
    void whatTheBufferAlreadyHoldsIsNotAFetch() {
        // One chest's worth is here; the racks have nothing. x3 cannot bring a single plank, so it is refused rather
        // than asked about: "fetch x1?" would fetch nothing.
        CraftFetchQuestion<String> question = measure(CHEST, 3, buffer(stack(PLANK, 8)), stock(Map.of()));
        assertEquals(1, question.ready());
        assertEquals(1, question.possible());
        assertTrue(question.refused());
        assertEquals(Verdict.NOT_ENOUGH, question.verdict(false, 0L));
        assertEquals(Optional.of(PLANK), question.about());
    }

    @Test
    void aBufferThatHoldsEveryCraftHasNothingToFetch() {
        CraftFetchQuestion<String> question = measure(CHEST, 2, buffer(stack(PLANK, 16), stack(PLANK, 3)),
                stock(Map.of()));
        assertTrue(question.nothingToFetch());
        assertEquals(2, question.ready());
        assertEquals(2, question.possible());
        assertEquals(Verdict.NOTHING_TO_FETCH, question.verdict(false, 0L));
        assertEquals(Verdict.NOTHING_TO_FETCH, question.verdict(true, 0L), "a Yes changes nothing about that");
        assertFalse(question.required());
        assertFalse(question.refused());
    }

    @Test
    void theTopUpIsAgainstTheBufferOnlyNotAgainstWhatIsOnItsWay() {
        // An open request already brings 64 planks to this terminal. They are somebody's and are not subtracted: the
        // fetch still needs its own 16, and the request only takes room.
        List<KeyCount<String>> open = List.of(new KeyCount<>(PLANK, 64L));
        CraftFetchQuestion<String> question = measure(CHEST, 2, emptyBuffer(SLOTS), stock(Map.of(PLANK, 16L)), open);
        assertEquals(2, question.possible());
        assertEquals(Verdict.START, question.verdict(false, 0L),
                "counting the 64 on their way as delivered would answer that there is nothing to fetch");
        assertEquals(0, question.ready(), "the 64 on their way are not counted as already in the buffer");
        assertFalse(question.nothingToFetch());
        assertEquals(2, question.roomRuns(), "the request only takes room: one slot of nine");
    }

    @Test
    void aNegativeStockAnswerIsNothing() {
        CraftFetchQuestion<String> question = measure(STICKS, 1, emptyBuffer(SLOTS), key -> -5L);
        assertEquals(0, question.stockRuns());
        assertEquals(Verdict.NOT_ENOUGH, question.verdict(false, 0L));
    }

    // --- the room bound ----------------------------------------------------------------------------------------------

    @Test
    void milkBucketsTakeASlotEachSoACakeFetchIsClampedToTheBufferRoom() {
        // x4: twelve milk buckets alone need twelve of nine slots. x2: six milk, and sugar, egg and wheat a slot each.
        CraftFetchQuestion<String> question = measure(CAKE, 4, emptyBuffer(SLOTS), plenty());
        assertEquals(4, question.stockRuns());
        assertEquals(2, question.roomRuns());
        assertEquals(2, question.possible());
        assertEquals(Bound.ROOM, question.bound());
        assertTrue(question.about().isEmpty(), "the buffer is to blame, not an item");
        assertTrue(question.named().isEmpty(), "and the racks fall short of nothing");
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
    }

    @Test
    void theRoomLeftAfterEveryOpenRequestIsWhatCounts() {
        // Eight stacks of cobblestone are on their way to a nine-slot buffer: one slot is left, and it holds 64
        // planks, i.e. eight chests.
        List<KeyCount<String>> open = List.of(new KeyCount<>(COBBLE, 8 * 64L));
        CraftFetchQuestion<String> question = measure(CHEST, 16, emptyBuffer(SLOTS), plenty(), open);
        assertEquals(8, question.roomRuns());
        assertEquals(Bound.ROOM, question.bound());
        assertEquals(16, measure(CHEST, 16, emptyBuffer(SLOTS), plenty()).roomRuns(),
                "without the open request the same fetch fits in two slots");
    }

    @Test
    void severalOpenRequestsAreAllPutDownFirst() {
        List<KeyCount<String>> open = List.of(new KeyCount<>(COBBLE, 4 * 64L), new KeyCount<>(IRON, 3 * 64L),
                new KeyCount<>(MILK, 1L), new KeyCount<>(REDSTONE, 0L));
        // 4 + 3 + 1 slots are taken; one is left for the fetch.
        CraftFetchQuestion<String> question = measure(CHEST, 9, emptyBuffer(SLOTS), plenty(), open);
        assertEquals(8, question.roomRuns(), "a request bringing nothing takes nothing");
    }

    @Test
    void theFetchStacksOntoWhatTheBufferHolds() {
        // Eight full slots of something else, and 40 planks in the ninth: 24 more planks fit onto that stack, which
        // makes eight chests' worth in all, three on top of the five already here.
        List<SlotView<String>> buffer = buffer(stack(COBBLE, 64), stack(COBBLE, 64), stack(COBBLE, 64),
                stack(COBBLE, 64), stack(COBBLE, 64), stack(COBBLE, 64), stack(COBBLE, 64), stack(COBBLE, 64),
                stack(PLANK, 40));
        CraftFetchQuestion<String> question = measure(CHEST, 20, buffer, plenty());
        assertEquals(5, question.ready());
        assertEquals(8, question.roomRuns(), "one slot holds 64 planks, however high its slot limit");
        assertEquals(Bound.ROOM, question.bound());
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
    }

    @Test
    void itemsAnOpenRequestCannotPutDownAreNotHeldAgainstTheFetch() {
        // The open request would fill four buffers; it fills this one and the rest waits, as it would anyway. The
        // planks already here still take more planks on top.
        List<SlotView<String>> buffer = buffer(stack(PLANK, 8));
        List<KeyCount<String>> open = List.of(new KeyCount<>(COBBLE, 36 * 64L));
        CraftFetchQuestion<String> question = measure(CHEST, 4, buffer, plenty(), open);
        assertEquals(4, question.roomRuns(), "24 more planks still stack onto the 8 here");
        assertEquals(Verdict.START, question.verdict(false, 0L));
        assertEquals(Verdict.NO_ROOM_FOR_ONE,
                measure(CraftGrid.of(IRON, IRON, IRON), 1, buffer, plenty(), open).verdict(false, 0L),
                "while iron finds no slot at all");
    }

    @Test
    void aFullBufferRefusesWithoutNamingAnItem() {
        List<SlotView<String>> buffer = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++)
            buffer.add(stack(COBBLE, 64));
        CraftFetchQuestion<String> question = measure(CHEST, 1, buffer, plenty());
        assertEquals(0, question.roomRuns());
        assertEquals(Bound.ROOM, question.bound());
        assertTrue(question.about().isEmpty());
        assertEquals(Verdict.NO_ROOM_FOR_ONE, question.verdict(false, 0L));
    }

    @Test
    void stockWinsATieWithRoomBecauseEmptyingTheBufferWouldNotHelp() {
        // Room for two cakes, milk for two: both bind at x2.
        CraftFetchQuestion<String> question = measure(CAKE, 4, emptyBuffer(SLOTS),
                stock(Map.of(MILK, 6L, SUGAR, 64L, EGG, 16L, WHEAT, 64L)));
        assertEquals(2, question.stockRuns());
        assertEquals(2, question.roomRuns());
        assertEquals(Bound.STOCK, question.bound());
        assertEquals(Optional.of(MILK), question.about());
        // Neither: no milk and no room. The missing item is still the one named.
        List<SlotView<String>> full = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++)
            full.add(stack(COBBLE, 64));
        CraftFetchQuestion<String> neither = measure(CAKE, 1, full, stock(Map.of(SUGAR, 64L)));
        assertEquals(Verdict.NOT_ENOUGH, neither.verdict(false, 0L));
        assertEquals(Optional.of(MILK), neither.about());
    }

    // --- the Yes, and what it covers ---------------------------------------------------------------------------------

    @Test
    void aYesIsMeasuredAgainAndStartsOnlyIfItStillHolds() {
        Map<String, Long> racks = new HashMap<>(Map.of(PLANK, 14L));
        List<SlotView<String>> buffer = buffer(stack(PLANK, 10));
        CraftFetchQuestion<String> asked = measure(CHEST, 4, buffer, stock(racks));
        assertEquals(Verdict.ASK, asked.verdict(false, 0L));
        int yes = asked.possible();
        assertEquals(3, yes);

        // The Yes comes back as a Fetch for exactly x3, and the warehouse has not moved: it starts.
        assertEquals(Verdict.START, measure(CHEST, yes, buffer, stock(racks)).verdict(true, asked.producing()));

        // Someone took planks in between: the Yes was for three and only two are possible now. Asked again.
        racks.put(PLANK, 6L);
        CraftFetchQuestion<String> stale = measure(CHEST, yes, buffer, stock(racks));
        assertEquals(2, stale.possible());
        assertEquals(Verdict.CHANGED, stale.verdict(true, asked.producing()));
        assertEquals(Verdict.ASK, stale.verdict(false, 0L), "the same numbers without a Yes are a first question");

        // Everything is gone: a refusal wins over asking again.
        racks.put(PLANK, 0L);
        assertEquals(Verdict.NOT_ENOUGH, measure(CHEST, yes, buffer, stock(racks)).verdict(true, 0L));
    }

    @Test
    void aYesForMoreThanIsPossibleNeverStartsAPartOfIt() {
        CraftFetchQuestion<String> question = measure(CHEST, 4, emptyBuffer(SLOTS), stock(Map.of(PLANK, 16L)));
        assertFalse(question.covers(0L));
        assertFalse(question.covers(Long.MAX_VALUE), "no amount of consent makes two crafts four");
        assertEquals(Verdict.CHANGED, question.verdict(true, Long.MAX_VALUE));
    }

    // --- production: the seam for the chain offer --------------------------------------------------------------------

    @Test
    void withoutPromisesTheMadeBoundIsTheStockBound() {
        CraftFetchQuestion<String> question = measure(PISTON, 4, emptyBuffer(SLOTS),
                stock(Map.of(PLANK, 15L, COBBLE, 9L, IRON, 2L, REDSTONE, 3L)));
        assertEquals(question.stockRuns(), question.madeRuns());
        assertEquals(0L, question.producing());
        assertEquals(question.possible(), question.withoutMaking());
    }

    @Test
    void whatAChainWouldMakeRaisesTheBoundAndIsAlwaysAskedAbout() {
        // Two planks per stick craft, none in the racks; a chain would make 8 of which 4 are promised to this fetch.
        Promises<String> chain = new Promises<>(Map.of(PLANK, ChainPreview.accepted(4L, 8L)));
        CraftFetchQuestion<String> question = CraftFetchQuestion.measure(STICKS, 2, emptyBuffer(SLOTS),
                stock(Map.of()), List.of(), CraftFetchQuestionTest::maxStack, chain);
        assertEquals(0, question.stockRuns());
        assertEquals(2, question.madeRuns());
        assertEquals(2, question.possible());
        assertEquals(0, question.withoutMaking());
        assertEquals(8L, question.producing());
        assertEquals(List.of(new Line<>(PLANK, 4L, 0L, 8L)), question.named(), "what would be made is named");
        assertFalse(question.named().get(0).isShort());
        assertTrue(question.required(), "every craft is possible, but only with production");
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
        assertEquals(Verdict.START, question.verdict(true, 8L), "a Yes to those eight covers it");
        assertEquals(Verdict.CHANGED, question.verdict(true, 7L), "a Yes to fewer does not");
        assertEquals(new RequestAcknowledgement(false, 0L, 0L, 0L, 8L), question.budget(),
                "and the order it starts may have those eight made, and nothing else");
    }

    @Test
    void aChainThatDoesNotHoldNamesTheItemInTheWay() {
        Promises<String> noLogs = new Promises<>(Map.of(PLANK,
                ChainPreview.refused(PlanRefusal.MISSING_INGREDIENT, LOG)));
        CraftFetchQuestion<String> question = CraftFetchQuestion.measure(STICKS, 2, emptyBuffer(SLOTS),
                stock(Map.of()), List.of(), CraftFetchQuestionTest::maxStack, noLogs);
        assertEquals(0, question.possible());
        assertEquals(Verdict.NOT_ENOUGH, question.verdict(false, 0L));
        assertEquals(Optional.of(PLANK), question.about(), "the bottleneck is still the ingredient");
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), question.chainRefusal());
        assertEquals(Optional.of(LOG), question.chainAbout());
        assertEquals(Optional.of(LOG), question.reasonItem(), "but the sentence names what is really missing");
        assertEquals(0L, question.producing());
        assertEquals(List.of(new Line<>(PLANK, 4L, 0L, 0L)), question.named());
    }

    @Test
    void anIngredientNothingMakesIsSimplyMissing() {
        Promises<String> nothing = new Promises<>(Map.of(SLIME, ChainPreview.refused(PlanRefusal.NO_PATTERN, SLIME)));
        CraftFetchQuestion<String> question = CraftFetchQuestion.measure(LEAD, 2, emptyBuffer(SLOTS),
                stock(Map.of(STRING, 640L)), List.of(), CraftFetchQuestionTest::maxStack, nothing);
        assertEquals(Verdict.NOT_ENOUGH, question.verdict(false, 0L));
        assertEquals(Optional.of(SLIME), question.about());
        assertTrue(question.chainRefusal().isEmpty(), "no chain was tried, so none is blamed");
        assertTrue(question.chainAbout().isEmpty());
        assertEquals(Optional.of(SLIME), question.reasonItem());
    }

    @Test
    void aStoppedIngredientSaysSo() {
        Promises<String> stopped = new Promises<>(Map.of(PLANK, ChainPreview.refused(PlanRefusal.PAUSED, PLANK)));
        CraftFetchQuestion<String> question = CraftFetchQuestion.measure(STICKS, 2, emptyBuffer(SLOTS),
                stock(Map.of()), List.of(), CraftFetchQuestionTest::maxStack, stopped);
        assertEquals(Optional.of(PlanRefusal.PAUSED), question.chainRefusal());
        assertEquals(Optional.of(PLANK), question.reasonItem(), "making Oak Planks is stopped");
    }

    @Test
    void onlyTheBottlenecksRefusalIsKept() {
        // Cobblestone and iron both allow two pistons; cobblestone comes first and is the bottleneck.
        Map<String, Long> racks = Map.of(PLANK, 15L, COBBLE, 9L, IRON, 2L, REDSTONE, 3L);
        Promises<String> ironChain = new Promises<>(Map.of(IRON,
                ChainPreview.refused(PlanRefusal.MISSING_INGREDIENT, "raw_iron")));
        CraftFetchQuestion<String> question = CraftFetchQuestion.measure(PISTON, 4, emptyBuffer(SLOTS), stock(racks),
                List.of(), CraftFetchQuestionTest::maxStack, ironChain);
        assertEquals(Optional.of(COBBLE), question.about());
        assertTrue(question.chainRefusal().isEmpty(), "iron's chain does not explain the cobblestone");

        Promises<String> cobbleChain = new Promises<>(Map.of(COBBLE,
                ChainPreview.refused(PlanRefusal.ORDERS_BUSY, COBBLE)));
        CraftFetchQuestion<String> busy = CraftFetchQuestion.measure(PISTON, 4, emptyBuffer(SLOTS), stock(racks),
                List.of(), CraftFetchQuestionTest::maxStack, cobbleChain);
        assertEquals(Optional.of(PlanRefusal.ORDERS_BUSY), busy.chainRefusal());
        assertEquals(Optional.of(COBBLE), busy.chainAbout());
    }

    // --- production: the chain offer, measured against the machines --------------------------------------------------

    @Test
    void noChainIsPreviewedWhenTheRacksServeEverythingThatFits() {
        Counting machines = machines(new HashMap<>());
        // The racks serve all three chests.
        assertEquals(measure(CHEST, 3, emptyBuffer(SLOTS), plenty()),
                offer(CHEST, 3, emptyBuffer(SLOTS), plenty(), machines));
        // The room stops a cake fetch at x2, and the racks hold more than that.
        CraftFetchQuestion<String> cake = offer(CAKE, 4, emptyBuffer(SLOTS), plenty(), machines);
        assertEquals(Bound.ROOM, cake.bound());
        assertEquals(2, cake.possible());
        // The buffer already holds both chests.
        assertEquals(Verdict.NOTHING_TO_FETCH,
                offer(CHEST, 2, buffer(stack(PLANK, 16)), stock(Map.of()), machines).verdict(false, 0L));
        // Not one craft fits.
        List<SlotView<String>> full = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++)
            full.add(stack(COBBLE, 64));
        assertEquals(Verdict.NO_ROOM_FOR_ONE, offer(CHEST, 1, full, plenty(), machines).verdict(false, 0L));
        assertEquals(0, machines.previews(), "no chain is planned for a fetch the racks serve");
    }

    /**
     * A tie with the room is the one preview a fetch the racks serve can cost: they supply exactly the crafts that
     * fit, and fewer fit than were asked for. One craft past what fits decides which binds, and when it is the stock,
     * the question names what that craft falls short of, with its chain's refusal — not the bare ingredient, and not
     * the buffer.
     */
    @Test
    void aTieWithTheRoomCostsOnePreviewAndNamesWhatOneCraftMoreLacks() {
        List<SlotView<String>> full = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++)
            full.add(stack(COBBLE, 64));
        // A full buffer and no logs: not one craft fits, and not one could be made either. The stock wins the tie,
        // and the sentence is the one a player can act on.
        Map<String, Long> noLogs = new HashMap<>();
        Counting idle = machines(noLogs);
        CraftFetchQuestion<String> neither = offer(STICKS, 2, full, stock(noLogs), idle);
        assertEquals(Verdict.NOT_ENOUGH, neither.verdict(false, 0L));
        assertEquals(Optional.of(PLANK), neither.about());
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), neither.chainRefusal());
        assertEquals(Optional.of(LOG), neither.reasonItem(), "Oak Log is missing");
        assertEquals(1, idle.previews());
        // With a log the saw could make the planks for one craft: it is the buffer the player has to empty.
        Map<String, Long> oneLog = Map.of(LOG, 1L);
        Counting saw = machines(oneLog);
        CraftFetchQuestion<String> room = offer(STICKS, 2, full, stock(oneLog), saw);
        assertEquals(Verdict.NO_ROOM_FOR_ONE, room.verdict(false, 0L));
        assertEquals(Bound.ROOM, room.bound());
        assertTrue(room.reasonItem().isEmpty());
        assertEquals(1, saw.previews());

        // Eight chests fit (eight stacks are on their way) and the racks hold exactly their 64 planks.
        List<KeyCount<String>> open = List.of(new KeyCount<>(COBBLE, 8 * 64L));
        Map<String, Long> planks = Map.of(PLANK, 64L);
        Counting short64 = machines(planks);
        CraftFetchQuestion<String> tie = CraftFetchQuestion.measure(CHEST, 16, emptyBuffer(SLOTS), stock(planks), open,
                CraftFetchQuestionTest::maxStack, short64);
        assertEquals(8, tie.possible());
        assertEquals(Bound.STOCK, tie.bound(), "no log for a ninth chest");
        assertEquals(Optional.of(PLANK), tie.about());
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), tie.chainRefusal(),
                "the question says why one craft more is not possible");
        assertEquals(Optional.of(LOG), tie.chainAbout());
        assertEquals(Verdict.ASK, tie.verdict(false, 0L));
        assertEquals(0L, tie.producing(), "and the Yes has nothing made");
        assertEquals(1, short64.previews(), "one preview, one craft past what fits");
        assertEquals(Map.of(PLANK, 72L), short64.needs.get(0));

        Map<String, Long> planksAndLogs = Map.of(PLANK, 64L, LOG, 2L);
        Counting withLogs = machines(planksAndLogs);
        CraftFetchQuestion<String> roomBinds = CraftFetchQuestion.measure(CHEST, 16, emptyBuffer(SLOTS),
                stock(planksAndLogs), open, CraftFetchQuestionTest::maxStack, withLogs);
        assertEquals(Bound.ROOM, roomBinds.bound(), "the saw could make a ninth chest's planks");
        assertEquals(9, roomBinds.madeRuns());
        assertEquals(8, roomBinds.possible());
        assertEquals(0L, roomBinds.producing(), "the eight that fit need nothing made");
        assertEquals(1, withLogs.previews());
    }

    @Test
    void withoutMachinesTheOfferIsTheStockMeasurement() {
        Map<String, Long> racks = Map.of(PLANK, 15L, COBBLE, 9L, IRON, 2L, REDSTONE, 3L);
        assertEquals(measure(PISTON, 4, emptyBuffer(SLOTS), stock(racks)),
                offer(PISTON, 4, emptyBuffer(SLOTS), stock(racks), Chains.none()));
    }

    /**
     * Sixteen chests asked for, eight fit (eight stacks are on their way), sixteen planks in the racks: the chains are
     * asked about the 64 planks of the eight chests that fit — not the 128 of all sixteen — and the planks' line takes
     * the sixteen in the racks first, so its chain is asked for the 48 the other six chests need.
     */
    @Test
    void theChainsArePreviewedForWhatFitsWithEachLineTakingFromTheRacksFirst() {
        List<KeyCount<String>> open = List.of(new KeyCount<>(COBBLE, 8 * 64L));
        Map<String, Long> racks = Map.of(PLANK, 16L);
        Counting saw = answering(racks, (key, amount) -> ChainPreview.accepted(amount, amount));
        CraftFetchQuestion<String> question = CraftFetchQuestion.measure(CHEST, 16, emptyBuffer(SLOTS),
                stock(racks), open, CraftFetchQuestionTest::maxStack, saw);
        assertEquals(Map.of(PLANK, 64L), saw.needs.get(0), "the line of the eight chests that fit");
        assertEquals(new FetchPreview<>(Map.of(PLANK, 16L), Map.of(PLANK, ChainPreview.accepted(48L, 48L))),
                saw.answers.get(0), "the line's own take out of the racks, and a chain for the rest");
        assertEquals(8, question.possible());
        assertEquals(48L, question.producing(), "what the fetch for eight makes, the one a Yes starts");
        assertEquals(List.of(new Line<>(PLANK, 128L, 16L, 48L)), question.named());
        assertEquals(2, question.withoutMaking(), "the sixteen planks in the racks are two chests");
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
        // The chains could have made more, so it is the buffer the player has to empty: one preview past what fits
        // settles that.
        assertEquals(2, saw.previews());
        assertEquals(Map.of(PLANK, 72L), saw.needs.get(1));
        assertEquals(Bound.ROOM, question.bound());
        assertEquals(9, question.madeRuns());
        assertTrue(question.about().isEmpty());

        // Chains that could not have made more: the supply and the room tie, and the stock wins the tie as always.
        Counting short48 = answering(racks, (key, amount) -> ChainPreview.accepted(Math.min(amount, 48L),
                Math.min(amount, 48L)));
        CraftFetchQuestion<String> tie = CraftFetchQuestion.measure(CHEST, 16, emptyBuffer(SLOTS),
                stock(racks), open, CraftFetchQuestionTest::maxStack, short48);
        assertEquals(8, tie.possible());
        assertEquals(Bound.STOCK, tie.bound());
        assertEquals(Optional.of(PLANK), tie.about());
    }

    /**
     * One plank and one charcoal per craft, both made from logs, six logs in the racks and nothing else. Planned for
     * all eight crafts, the planks would take two logs and leave the charcoal four: four crafts. The question then
     * shows what a fetch for <b>four</b> makes — one log's planks, not two — because that is what its Yes starts.
     */
    @Test
    void theNumbersShownAreThoseOfTheFetchAYesStarts() {
        Map<String, Long> racks = new HashMap<>(Map.of(LOG, 6L));
        Counting machines = machines(racks);
        CraftFetchQuestion<String> question = offer(KINDLING, 8, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(Map.of(PLANK, 8L, CHARCOAL, 8L), machines.needs.get(0), "first for what fits");
        assertEquals(4, question.possible());
        assertEquals(0, question.withoutMaking());
        assertEquals(Bound.STOCK, question.bound());
        assertEquals(List.of(new Line<>(PLANK, 8L, 0L, 4L), new Line<>(CHARCOAL, 8L, 0L, 4L)), question.named(),
                "four planks and four charcoal will be made first");
        assertEquals(8L, question.producing(), "not the twelve a fetch for eight would have made");
        assertEquals(new RequestAcknowledgement(false, 0L, 0L, 0L, 8L), question.budget());
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
        assertEquals(3, machines.previews(), "what fits, the chains' own offer, and one craft past it");

        // The Yes comes back as a Fetch for x4 with the eight it agreed to: measured again, it starts.
        CraftFetchQuestion<String> yes = offer(KINDLING, 4, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(Verdict.START, yes.verdict(true, question.producing()));
        assertEquals(Verdict.CHANGED, yes.verdict(true, 7L), "a Yes to fewer made items does not cover eight");
        // Two logs were taken in between: three crafts now, so the Yes is asked about again.
        racks.put(LOG, 4L);
        CraftFetchQuestion<String> stale = offer(KINDLING, 4, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(3, stale.possible());
        assertEquals(Verdict.CHANGED, stale.verdict(true, question.producing()));
    }

    /**
     * Fences x2 with two logs (GameTest 32): for two crafts the planks' chain takes both logs and the sticks' chain
     * finds none; for one, each gets one. Measured at x1 the two ingredients tie, and the planks come first in the
     * grid — but it is the sticks that one craft more falls short of, so the question names them, with the refusal of
     * their chain: "Oak Log is missing". Kindling x8 with six logs names the charcoal the same way: at x5 its kiln gets
     * the four logs the planks leave, which is fewer than five, while the planks' chain would hold; that chain is
     * clamped rather than refused, so there is no refusal to name with it.
     */
    @Test
    void theSearchNamesWhatOneCraftMoreFallsShortOf() {
        Map<String, Long> twoLogs = Map.of(LOG, 2L);
        Counting machines = machines(twoLogs);
        CraftFetchQuestion<String> fences = offer(FENCE, 2, emptyBuffer(SLOTS), stock(twoLogs), machines);
        assertEquals(1, fences.possible());
        assertEquals(Bound.STOCK, fences.bound());
        assertEquals(8L, fences.producing(), "four planks and four sticks for the one craft");
        assertEquals(List.of(new Line<>(PLANK, 8L, 0L, 4L), new Line<>(STICK, 4L, 0L, 4L)), fences.named());
        assertEquals(Optional.of(STICK), fences.about(), "not the planks, which only tie with the sticks at x1");
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), fences.chainRefusal());
        assertEquals(Optional.of(LOG), fences.chainAbout());
        assertEquals(Optional.of(LOG), fences.reasonItem());
        assertEquals(Verdict.ASK, fences.verdict(false, 0L));
        assertEquals(2, machines.previews(), "what fits, then one craft: naming costs no preview");

        Map<String, Long> sixLogs = Map.of(LOG, 6L);
        CraftFetchQuestion<String> kindling = offer(KINDLING, 8, emptyBuffer(SLOTS), stock(sixLogs),
                machines(sixLogs));
        assertEquals(4, kindling.possible());
        assertEquals(Optional.of(CHARCOAL), kindling.about(), "the kiln gets four logs, one craft more needs five");
        assertTrue(kindling.chainRefusal().isEmpty(), "a clamped chain is not a refused one");
        assertEquals(Optional.of(CHARCOAL), kindling.reasonItem());

        // With logs enough for both chains there is nothing to search and nothing to name.
        Map<String, Long> plenty = Map.of(LOG, 64L);
        CraftFetchQuestion<String> enough = offer(FENCE, 2, emptyBuffer(SLOTS), stock(plenty), machines(plenty));
        assertEquals(2, enough.possible());
        assertEquals(Bound.NONE, enough.bound());
        assertTrue(enough.about().isEmpty());
    }

    /**
     * A campfire: three sticks, one coal and three logs, with four logs in the racks and sticks made from planks made
     * from logs. Two campfires need seven logs — six as logs and one for the planks the sticks are made of — and one
     * needs four: the sticks' cell comes first, so their chain takes one log, and the logs' line the other three. Fetch
     * x2 therefore offers x1 and has the sticks made — it does not refuse.
     */
    @Test
    void aFetchTheLogsDoNotHoldForIsOfferedForFewerCrafts() {
        Map<String, Long> racks = Map.of(LOG, 4L, COAL, 64L);
        Counting machines = machines(racks);
        CraftFetchQuestion<String> question = offer(CAMPFIRE, 2, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(Map.of(STICK, 6L, COAL, 2L, LOG, 6L), machines.needs.get(0));
        assertEquals(List.of(STICK, COAL, LOG), List.copyOf(machines.needs.get(0).keySet()), "in line order");
        assertEquals(Map.of(STICK, 0L, COAL, 2L, LOG, 3L), machines.answers.get(0).taken(),
                "the sticks' chain took one of the four logs before the logs' line");
        assertEquals(1, question.possible());
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
        assertEquals(4L, question.producing(), "one run of four sticks");
        assertEquals(List.of(new Line<>(STICK, 6L, 0L, 4L), new Line<>(LOG, 6L, 4L, 0L)), question.named());
        assertEquals(2, machines.previews());

        assertEquals(Verdict.START, offer(CAMPFIRE, 1, emptyBuffer(SLOTS), stock(racks), machines)
                .verdict(true, question.producing()), "and the Yes for x1 starts");
    }

    /** No logs at all: Fetch is refused, and the sentence names the log rather than the planks (GameTest 29). */
    @Test
    void withoutTheRawMaterialTheFetchIsRefusedNamingIt() {
        Map<String, Long> racks = new HashMap<>();
        Counting machines = machines(racks);
        CraftFetchQuestion<String> question = offer(STICKS, 2, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(Verdict.NOT_ENOUGH, question.verdict(false, 0L));
        assertEquals(0, question.possible());
        assertEquals(Optional.of(PLANK), question.about());
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), question.chainRefusal());
        assertEquals(Optional.of(LOG), question.reasonItem());
        assertEquals(2, machines.previews(), "the crafts that fit, then whether one craft is possible at all");

        // One log in the racks: the planks are made first, and the player is asked about that.
        racks.put(LOG, 1L);
        CraftFetchQuestion<String> offered = offer(STICKS, 2, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(2, offered.possible());
        assertEquals(List.of(new Line<>(PLANK, 4L, 0L, 4L)), offered.named());
        assertEquals(Verdict.ASK, offered.verdict(false, 0L), "made items are always asked about first");
        assertEquals(Verdict.START, offered.verdict(true, 4L));
    }

    @Test
    void aStoppedIngredientIsNamedAndNotSearched() {
        Map<String, Long> racks = new HashMap<>(Map.of(PLANK, 2L, LOG, 10L));
        Counting machines = machines(racks, PLANK);
        CraftFetchQuestion<String> question = offer(STICKS, 4, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(1, question.possible(), "the two planks in the racks are one craft");
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
        assertEquals(Optional.of(PlanRefusal.PAUSED), question.chainRefusal());
        assertEquals(Optional.of(PLANK), question.chainAbout());
        assertEquals(1, machines.previews(), "a stopped item cannot be made for fewer crafts either");

        racks.remove(PLANK);
        CraftFetchQuestion<String> none = offer(STICKS, 4, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(Verdict.NOT_ENOUGH, none.verdict(false, 0L));
        assertEquals(Optional.of(PLANK), none.reasonItem());
        assertEquals(2, machines.previews());
    }

    @Test
    void anItemNothingMakesCostsOnePreview() {
        Map<String, Long> racks = Map.of(STRING, 640L);
        Counting machines = machines(racks);
        CraftFetchQuestion<String> question = offer(LEAD, 2, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(Map.of(STRING, 8L, SLIME, 2L), machines.needs.get(0));
        assertEquals(Map.of(STRING, 8L, SLIME, 0L), machines.answers.get(0).taken());
        assertEquals(Verdict.NOT_ENOUGH, question.verdict(false, 0L));
        assertEquals(Optional.of(SLIME), question.reasonItem());
        assertTrue(question.chainRefusal().isEmpty());
        assertEquals(1, machines.previews());
    }

    /**
     * A fence gate's sticks' cell comes before its planks' (issue #24 review): with two planks and a log in the racks
     * the sticks' portion has its sticks made of those two planks, and the planks' portion then finds none and has its
     * own made of the log. The question adds up exactly those two chains, so the Yes covers both portions and neither
     * asks again — taking every line's stock first would have offered the sticks alone, and the planks' portion would
     * then have asked for production its Yes did not cover.
     */
    @Test
    void theOfferAddsUpWhatThePortionsWillMakeInLineOrder() {
        Map<String, Long> racks = Map.of(PLANK, 2L, LOG, 1L);
        Counting machines = machines(racks);
        CraftFetchQuestion<String> gate = offer(GATE, 1, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(1, gate.possible());
        assertEquals(Bound.NONE, gate.bound());
        assertEquals(List.of(new Line<>(STICK, 4L, 0L, 4L), new Line<>(PLANK, 2L, 2L, 4L)), gate.named(),
                "the sticks made of the two planks, and the planks made of the log");
        assertEquals(8L, gate.producing());
        assertEquals(portionsMake(GATE, 1, racks), gate.producing(),
                "what a Yes agrees to is what the portions make, in the order they are requested");
        assertEquals(new RequestAcknowledgement(false, 0L, 0L, 0L, 8L), gate.budget());
        assertEquals(Verdict.ASK, gate.verdict(false, 0L));
        assertEquals(Verdict.START, offer(GATE, 1, emptyBuffer(SLOTS), stock(racks), machines).verdict(true, 8L));
        assertEquals(Verdict.CHANGED, offer(GATE, 1, emptyBuffer(SLOTS), stock(racks), machines).verdict(true, 4L),
                "a Yes to the sticks alone does not cover the planks");

        // An oak fence has its planks' cell first: the planks' line takes the two, and the sticks' chain makes its own
        // planks of the log. One chain, and again what the portions make.
        CraftGrid<String> fenceRow = CraftGrid.of(PLANK, STICK, PLANK);
        CraftFetchQuestion<String> fence = offer(fenceRow, 1, emptyBuffer(SLOTS), stock(racks), machines(racks));
        assertEquals(List.of(new Line<>(STICK, 1L, 0L, 4L)), fence.named());
        assertEquals(portionsMake(fenceRow, 1, racks), fence.producing());

        // The two planks in the racks are counted once: with no log the gate is not possible at all, and the
        // sentence names the log the planks' chain could not get past.
        Map<String, Long> noLog = Map.of(PLANK, 2L);
        CraftFetchQuestion<String> none = offer(GATE, 1, emptyBuffer(SLOTS), stock(noLog), machines(noLog));
        assertEquals(Verdict.NOT_ENOUGH, none.verdict(false, 0L));
        assertEquals(Optional.of(PLANK), none.about());
        assertEquals(Optional.of(LOG), none.reasonItem());
    }

    /**
     * An ingredient nothing makes caps the search at what the racks hold of it, and when no smaller guess fails
     * first, the craft one past that cap is never previewed: the chains of the larger fetch say nothing about it. A
     * crossbow x4 takes all four iron ingots as iron, so the hooks' chain finds none at x4 — but at x2 the iron is
     * enough for the ingots and a hook run. The string is what one craft more lacks, and nothing makes string.
     */
    @Test
    void aFetchTheCapStopsNamesTheIngredientThatCapsIt() {
        Map<String, Long> racks = Map.of(IRON, 4L, STRING, 2L, STICK, 64L, PLANK, 64L);
        Counting machines = machines(racks);
        CraftFetchQuestion<String> crossbow = offer(CROSSBOW, 4, emptyBuffer(SLOTS), stock(racks), machines);
        assertEquals(1, crossbow.possible());
        assertEquals(Bound.STOCK, crossbow.bound());
        assertEquals(Optional.of(STRING), crossbow.about(), "not the hooks, whose chain only x4 starves");
        assertTrue(crossbow.chainRefusal().isEmpty(), "string is simply missing: no 'Iron Ingot is missing'");
        assertEquals(Optional.of(STRING), crossbow.reasonItem());
        assertEquals(2L, crossbow.producing(), "one run of two hooks for the one crossbow");
        assertEquals(Verdict.ASK, crossbow.verdict(false, 0L));
        assertEquals(2, machines.previews(), "what fits and the one craft the string allows: naming costs nothing");

        // The hook's cell before the string's, and string for no crossbow at all: the refusal names the string, not
        // the hooks the larger fetch's iron starves.
        Map<String, Long> oneString = Map.of(IRON, 4L, STRING, 1L, STICK, 64L, PLANK, 64L);
        Counting again = machines(oneString);
        CraftFetchQuestion<String> none = offer(HOOK_FIRST, 4, emptyBuffer(SLOTS), stock(oneString), again);
        assertEquals(Verdict.NOT_ENOUGH, none.verdict(false, 0L));
        assertEquals(Optional.of(STRING), none.about());
        assertTrue(none.chainRefusal().isEmpty());
        assertEquals(Optional.of(STRING), none.reasonItem());
        assertEquals(1, again.previews());
    }

    /**
     * The same for a campfire short of coal, which nothing here makes: four logs are enough for one campfire (three
     * as logs, one for the sticks' planks), so "Oak Log is missing" would send the player for logs they have. And a
     * slime ball nothing makes beats a stick whose chain the planks' chain starved at the larger fetch.
     */
    @Test
    void anIngredientNothingMakesIsNamedOverAChainOnlyTheLargerFetchStarves() {
        Map<String, Long> logs = Map.of(LOG, 4L);
        Counting noCoal = machines(logs);
        CraftFetchQuestion<String> refused = offer(CAMPFIRE, 2, emptyBuffer(SLOTS), stock(logs), noCoal);
        assertEquals(Verdict.NOT_ENOUGH, refused.verdict(false, 0L));
        assertEquals(Optional.of(COAL), refused.about());
        assertEquals(Optional.of(COAL), refused.reasonItem());
        assertTrue(refused.chainRefusal().isEmpty());
        assertEquals(1, noCoal.previews());

        Map<String, Long> oneCoal = Map.of(LOG, 7L, COAL, 1L);
        Counting machines = machines(oneCoal);
        CraftFetchQuestion<String> one = offer(CAMPFIRE, 3, emptyBuffer(SLOTS), stock(oneCoal), machines);
        assertEquals(Verdict.ASK, one.verdict(false, 0L));
        assertEquals(1, one.possible());
        assertEquals(4L, one.producing(), "one crafter run of sticks");
        assertEquals(Optional.of(COAL), one.about(), "seven logs are enough for two; the one coal is not");
        assertEquals(Optional.of(COAL), one.reasonItem());
        assertTrue(one.chainRefusal().isEmpty());
        assertEquals(2, machines.previews());

        Map<String, Long> twoLogs = Map.of(LOG, 2L);
        CraftFetchQuestion<String> slime = offer(CraftGrid.of(PLANK, STICK, SLIME), 8, emptyBuffer(SLOTS),
                stock(twoLogs), machines(twoLogs));
        assertEquals(Verdict.NOT_ENOUGH, slime.verdict(false, 0L));
        assertEquals(Optional.of(SLIME), slime.reasonItem());
        assertTrue(slime.chainRefusal().isEmpty());
    }

    /**
     * "Set x1 to fetch only that." is offered only when x1 really fetches something: with a chest's planks already in
     * the buffer and none in the racks, the racks alone serve exactly the craft that is here, and setting it would
     * answer that there is nothing to fetch.
     */
    @Test
    void theSmallerFetchIsOfferedOnlyWhenItFetchesSomething() {
        List<SlotView<String>> oneChest = buffer(stack(PLANK, 8));
        Map<String, Long> logs = Map.of(LOG, 64L);
        CraftFetchQuestion<String> nothingMore = offer(CHEST, 4, oneChest, stock(logs), machines(logs));
        assertEquals(1, nothingMore.ready());
        assertEquals(4, nothingMore.possible());
        assertEquals(1, nothingMore.withoutMaking(), "the craft that is already here");
        assertTrue(nothingMore.makesSomething());
        assertFalse(nothingMore.offersWithoutMaking(), "x1 would fetch nothing");

        Map<String, Long> planksAndLogs = Map.of(PLANK, 8L, LOG, 64L);
        CraftFetchQuestion<String> oneMore = offer(CHEST, 4, oneChest, stock(planksAndLogs), machines(planksAndLogs));
        assertEquals(2, oneMore.withoutMaking());
        assertTrue(oneMore.offersWithoutMaking(), "x2 fetches one chest's planks without making anything");
        assertEquals(16L, oneMore.producing());

        CraftFetchQuestion<String> fromRacks = measure(CHEST, 4, emptyBuffer(SLOTS), stock(Map.of(PLANK, 16L)));
        assertFalse(fromRacks.offersWithoutMaking(), "nothing made, nothing to say no to");
    }

    @Test
    void aChainShortOfRawMaterialIsSettledInThreePreviews() {
        CraftGrid<String> onePlank = CraftGrid.of(PLANK);
        Counting most300 = answering(Map.of(), (key, amount) -> ChainPreview.accepted(Math.min(amount, 300L),
                Math.min(amount, 300L)));
        CraftFetchQuestion<String> question = offer(onePlank, 576, emptyBuffer(SLOTS), stock(Map.of()), most300);
        assertEquals(300, question.possible());
        assertEquals(300L, question.producing());
        assertEquals(3, most300.previews(), "what fits, the chains' offer, and one craft past it");
    }

    @Test
    void theSearchIsBounded() {
        // A chain that holds up to 100 planks and is refused beyond: the offer for what fits says nothing, so the
        // range is halved.
        CraftGrid<String> onePlank = CraftGrid.of(PLANK);
        Counting upTo100 = answering(Map.of(), (key, amount) -> amount <= 100L ? ChainPreview.accepted(amount, amount)
                : ChainPreview.refused(PlanRefusal.MISSING_INGREDIENT, LOG));
        CraftFetchQuestion<String> question = offer(onePlank, 576, emptyBuffer(SLOTS), stock(Map.of()), upTo100);
        assertEquals(576, question.roomRuns());
        assertEquals(100, question.possible());
        assertEquals(100L, question.producing());
        int bound = 3 + (32 - Integer.numberOfLeadingZeros(576 - 1));
        assertTrue(upTo100.previews() <= bound, () -> upTo100.previews() + " previews, at most " + bound);
    }

    // --- bounds, sizes and the record --------------------------------------------------------------------------------

    @Test
    void theQuestionNamesAtMostFiveKeys() {
        // Nine different keys, none in stock.
        List<String> keys = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i");
        CraftGrid<String> nine = CraftGrid.of(keys.toArray(String[]::new));
        CraftFetchQuestion<String> question = measure(nine, 1, emptyBuffer(SLOTS), stock(Map.of()));
        assertEquals(CraftFetchQuestion.MAX_NAMED, question.named().size());
        assertEquals(keys.subList(0, CraftFetchQuestion.MAX_NAMED), keys(question.named()));
        assertEquals(Optional.of("a"), question.about());
    }

    @Test
    void hugeAmountsNeitherOverflowNorGoNegative() {
        CraftGrid<String> nineCobble = CraftGrid.of(COBBLE, COBBLE, COBBLE, COBBLE, COBBLE, COBBLE, COBBLE, COBBLE,
                COBBLE);
        // 65536 crafts of nine cobblestone are 589824 items: far more than nine slots hold, and fine as an int.
        CraftFetchQuestion<String> question = measure(nineCobble, 65_536, emptyBuffer(SLOTS), key -> Long.MAX_VALUE);
        assertEquals(65_536, question.stockRuns());
        assertEquals(64, question.roomRuns(), "nine stacks of 64 are 64 crafts");
        assertEquals(Verdict.ASK, question.verdict(false, 0L));
        assertEquals(List.of(ListEntry.of(0, 0, COBBLE, 589_824)),
                CraftFetchQuestion.entries(nineCobble, 65_536, emptyBuffer(SLOTS)));
    }

    @Test
    void measuringNeedsARecipeAndAtLeastOneCraft() {
        assertThrows(IllegalArgumentException.class,
                () -> measure(CraftGrid.empty(), 1, emptyBuffer(SLOTS), plenty()));
        assertThrows(IllegalArgumentException.class, () -> measure(CHEST, 0, emptyBuffer(SLOTS), plenty()));
    }

    @Test
    void theRecordClampsWhatItIsGivenAndDerivesTheBound() {
        // What a screen decodes is clamped, never trusted: runs above what was asked, a made bound below the stock
        // bound, and a key named for a bound that is not about an item.
        CraftFetchQuestion<String> clamped = new CraftFetchQuestion<>(0, -3, 9, -1, 7, Optional.of(PLANK), -5L, null);
        assertEquals(1, clamped.asked());
        assertEquals(0, clamped.ready());
        assertEquals(1, clamped.stockRuns());
        assertEquals(1, clamped.madeRuns(), "never below the stock bound");
        assertEquals(1, clamped.roomRuns());
        assertEquals(0L, clamped.producing());
        assertTrue(clamped.named().isEmpty());
        assertEquals(Bound.NONE, clamped.bound());
        assertTrue(clamped.about().isEmpty(), "a key is only named for the stock bound");

        CraftFetchQuestion<String> room = new CraftFetchQuestion<>(4, 0, 4, 4, 2, Optional.of(PLANK), 0L, List.of());
        assertEquals(Bound.ROOM, room.bound());
        assertTrue(room.about().isEmpty());

        List<Line<String>> many = new ArrayList<>();
        for (int i = 0; i < 9; i++)
            many.add(new Line<>("k" + i, 1L, 0L, 0L));
        CraftFetchQuestion<String> stockBound = new CraftFetchQuestion<>(4, 0, 1, 1, 4, Optional.of("k0"), 0L, many);
        assertEquals(Bound.STOCK, stockBound.bound());
        assertEquals(Optional.of("k0"), stockBound.about());
        assertEquals(CraftFetchQuestion.MAX_NAMED, stockBound.named().size());
        assertEquals(new Line<>(PLANK, 0L, 0L, 0L), new Line<>(PLANK, -1L, -2L, -3L));
    }

    @Test
    void theRecordKeepsAChainRefusalOnlyWithTheBottleneck() {
        Optional<PlanRefusal> missing = Optional.of(PlanRefusal.MISSING_INGREDIENT);
        CraftFetchQuestion<String> room = new CraftFetchQuestion<>(4, 0, 4, 4, 2, Optional.of(PLANK), 0L, List.of(),
                missing, Optional.of(LOG));
        assertEquals(Bound.ROOM, room.bound());
        assertTrue(room.chainRefusal().isEmpty(), "the room binds: no item, so no chain, is to blame");
        assertTrue(room.chainAbout().isEmpty());
        assertTrue(room.reasonItem().isEmpty());

        CraftFetchQuestion<String> stray = new CraftFetchQuestion<>(4, 0, 1, 1, 4, Optional.of(PLANK), 0L, List.of(),
                Optional.empty(), Optional.of(LOG));
        assertTrue(stray.chainAbout().isEmpty(), "an item is only named for a refusal");
        assertEquals(Optional.of(PLANK), stray.reasonItem());

        CraftFetchQuestion<String> nobody = new CraftFetchQuestion<>(4, 0, 1, 1, 4, Optional.empty(), 0L, List.of(),
                missing, Optional.of(LOG));
        assertTrue(nobody.chainRefusal().isEmpty(), "a refusal explains the bottleneck, and there is none");

        CraftFetchQuestion<String> kept = new CraftFetchQuestion<>(4, 0, 1, 1, 4, Optional.of(PLANK), 0L, List.of(),
                missing, Optional.of(LOG));
        assertEquals(missing, kept.chainRefusal());
        assertEquals(Optional.of(LOG), kept.reasonItem());

        CraftFetchQuestion<String> slice2 = new CraftFetchQuestion<>(4, 0, 1, 1, 4, Optional.of(PLANK), 0L, List.of());
        assertTrue(slice2.chainRefusal().isEmpty(), "the shape without production carries no refusal");
        assertFalse(slice2.makesSomething());
    }

    private static List<String> keys(List<Line<String>> lines) {
        List<String> keys = new ArrayList<>();
        for (Line<String> line : lines)
            keys.add(line.key());
        return keys;
    }
}
