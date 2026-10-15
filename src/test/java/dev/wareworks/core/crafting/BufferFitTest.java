package dev.wareworks.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.crafting.SlotPlan.Stack;
import dev.wareworks.core.inventory.SlotView;

/**
 * Room in a terminal's buffer, measured before a fetch requests anything (issue #24, slice 2): what is already on its
 * way is put down first, the fetch has to fit into what is left, and the largest fitting fetch is found with a handful
 * of plans rather than one per craft.
 */
class BufferFitTest {
    private static final int LIMIT = 99;
    private static final String PLANK = "oak_planks";
    private static final String COBBLE = "cobblestone";
    private static final String MILK = "milk_bucket";

    private static List<SlotView<String>> empty(int slots) {
        List<SlotView<String>> buffer = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++)
            buffer.add(SlotView.empty(LIMIT));
        return buffer;
    }

    private static Stack<String> stack(String key, int count) {
        return new Stack<>(key, count, MILK.equals(key) ? 1 : 64);
    }

    @Test
    void whatIsOnItsWayIsPutDownBeforeTheFetch() {
        List<SlotView<String>> buffer = empty(3);
        assertTrue(BufferFit.fits(buffer, List.of(), List.of(stack(PLANK, 3 * 64))));
        assertFalse(BufferFit.fits(buffer, List.of(stack(COBBLE, 1)), List.of(stack(PLANK, 3 * 64))),
                "one cobblestone on its way takes a whole slot");
        assertTrue(BufferFit.fits(buffer, List.of(stack(COBBLE, 1)), List.of(stack(PLANK, 2 * 64))));
    }

    @Test
    void whatIsOnItsWayAndDoesNotFitIsNotHeldAgainstTheFetch() {
        List<SlotView<String>> buffer = new ArrayList<>(empty(3));
        buffer.set(0, SlotView.of(PLANK, 10, LIMIT, 64));
        List<Stack<String>> flood = List.of(stack(COBBLE, 50 * 64));
        assertTrue(BufferFit.fits(buffer, flood, List.of(stack(PLANK, 54))), "the planks stack onto their own slot");
        assertFalse(BufferFit.fits(buffer, flood, List.of(stack(PLANK, 55))), "and only up to one stack");
    }

    @Test
    void anItemThatDoesNotStackTakesASlotEach() {
        List<SlotView<String>> buffer = empty(4);
        assertTrue(BufferFit.fits(buffer, List.of(), List.of(stack(MILK, 4))));
        assertFalse(BufferFit.fits(buffer, List.of(), List.of(stack(MILK, 5))));
        assertFalse(BufferFit.fits(buffer, List.of(stack(MILK, 1)), List.of(stack(MILK, 4))));
    }

    @Test
    void theViewsAreNeverWritten() {
        List<SlotView<String>> buffer = empty(2);
        List<SlotView<String>> before = List.copyOf(buffer);
        BufferFit.fits(buffer, List.of(stack(COBBLE, 64)), List.of(stack(PLANK, 64)));
        assertEquals(before, buffer);
    }

    @Test
    void aFetchThatFitsCostsOnePlan() {
        AtomicInteger plans = new AtomicInteger();
        IntFunction<List<Stack<String>>> demand = runs -> {
            plans.incrementAndGet();
            return List.of(stack(PLANK, 8 * runs));
        };
        assertEquals(16, BufferFit.largest(empty(9), List.of(), 16, demand));
        assertEquals(1, plans.get());
    }

    @Test
    void theLargestFittingFetchIsFoundWithALogarithmicNumberOfPlans() {
        // 65536 runs of one plank each into nine slots: 576 fit.
        AtomicInteger plans = new AtomicInteger();
        IntFunction<List<Stack<String>>> demand = runs -> {
            plans.incrementAndGet();
            return runs == 0 ? List.of() : List.of(stack(PLANK, runs));
        };
        assertEquals(576, BufferFit.largest(empty(9), List.of(), 65_536, demand));
        assertTrue(plans.get() <= 1 + 17, "one check of the maximum, then a binary search: " + plans.get());
    }

    @Test
    void theBinarySearchAgreesWithTryingEveryAmount() {
        Random random = new Random(24L);
        for (int round = 0; round < 200; round++) {
            int slots = 1 + random.nextInt(12);
            List<SlotView<String>> buffer = empty(slots);
            for (int i = 0; i < slots; i++) {
                int pick = random.nextInt(4);
                if (pick == 1)
                    buffer.set(i, SlotView.of(PLANK, 1 + random.nextInt(64), LIMIT, 64));
                else if (pick == 2)
                    buffer.set(i, SlotView.of(COBBLE, 1 + random.nextInt(64), LIMIT, 64));
            }
            List<Stack<String>> ahead = random.nextBoolean() ? List.of()
                    : List.of(stack(COBBLE, 1 + random.nextInt(6 * 64)));
            int planksPerRun = 1 + random.nextInt(9);
            int milkPerRun = random.nextInt(2);
            IntFunction<List<Stack<String>>> demand = runs -> {
                List<Stack<String>> stacks = new ArrayList<>();
                if (runs > 0)
                    stacks.add(stack(PLANK, planksPerRun * runs));
                if (runs > 0 && milkPerRun > 0)
                    stacks.add(stack(MILK, milkPerRun * runs));
                return stacks;
            };
            int max = 1 + random.nextInt(100);
            int expected = 0;
            for (int runs = 1; runs <= max && BufferFit.fits(buffer, ahead, demand.apply(runs)); runs++)
                expected = runs;
            assertEquals(expected, BufferFit.largest(buffer, ahead, max, demand), "round " + round);
        }
    }

    @Test
    void noRunsAskedForIsZero() {
        assertEquals(0, BufferFit.largest(empty(1), List.of(), 0, runs -> List.of(stack(PLANK, 1))));
        assertEquals(0, BufferFit.largest(empty(0), List.of(), 5, runs -> List.of(stack(PLANK, runs))),
                "a buffer without slots takes nothing");
    }
}
