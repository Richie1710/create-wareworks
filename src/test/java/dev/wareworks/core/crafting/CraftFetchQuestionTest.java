package dev.wareworks.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.crafting.CraftFetchQuestion.Bound;
import dev.wareworks.core.crafting.CraftFetchQuestion.Line;
import dev.wareworks.core.crafting.CraftFetchQuestion.Promises;
import dev.wareworks.core.crafting.CraftFetchQuestion.Verdict;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.RequestAcknowledgement;

/**
 * Fetch on a crafting grid, measured before anything is requested (issue #24, slice 2): how many whole crafts the racks
 * can supply, how many fit into the terminal's buffer once every open request has put its items down, which of the two
 * binds and about which item, whether the player has to be asked, and that a Yes is measured again rather than trusted.
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
        Promises<String> chain = new Promises<>(Map.of(PLANK, 4L), Map.of(PLANK, 8L));
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

    private static List<String> keys(List<Line<String>> lines) {
        List<String> keys = new ArrayList<>();
        for (Line<String> line : lines)
            keys.add(line.key());
        return keys;
    }
}
