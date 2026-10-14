package dev.wareworks.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.crafting.SlotPlan.Outcome;
import dev.wareworks.core.crafting.SlotPlan.Refusal;
import dev.wareworks.core.crafting.SlotPlan.Side;
import dev.wareworks.core.crafting.SlotPlan.Stack;
import dev.wareworks.core.crafting.SlotPlan.Write;
import dev.wareworks.core.inventory.SlotView;

/**
 * One craft at a terminal, planned on slot views before anything moves (issue #24, M32): what is taken from the
 * buffer, where remainders and the result go, and that a craft which cannot be carried out in full is refused with the
 * item to blame and without a single write.
 * <p>
 * The slots here have the limit both real sides report ({@value #LIMIT}, {@code Container#getMaxStackSize}), so what
 * a slot holds is bounded by each item's own stack size, as in the game: 64 for most items, 16 for buckets, eggs and
 * honey bottles, 1 for a milk bucket or a cake. Every planned craft is checked against vanilla's definition of a
 * craft: the two sides together afterwards hold what they held before, less one item per occupied cell, plus the
 * remainders and the result.
 */
class SlotPlanTest {
    /** The slot limit of the terminal's buffer and of the player inventory. */
    private static final int LIMIT = 99;
    /** Slots of a player's main inventory. */
    private static final int INVENTORY_SLOTS = 36;

    private static final String PLANK = "oak_planks";
    private static final String NAMED_PLANK = "oak_planks{custom_name:'Heirloom'}";
    private static final String LOG = "oak_log";
    private static final String CHEST = "chest";
    private static final String COBBLE = "cobblestone";
    private static final String MILK = "milk_bucket";
    private static final String BUCKET = "bucket";
    private static final String SUGAR = "sugar";
    private static final String EGG = "egg";
    private static final String WHEAT = "wheat";
    private static final String CAKE = "cake";
    private static final String HONEY = "honey_bottle";
    private static final String BOTTLE = "glass_bottle";
    private static final String HONEY_BLOCK = "honey_block";
    private static final String IRON = "iron_ingot";
    private static final String REDSTONE = "redstone";
    private static final String BOOK = "written_book{generation:0}";
    private static final String COPY = "written_book{generation:1}";
    private static final String QUILL = "writable_book";

    private static SlotView<String> slot(String key, int count, int maxStackSize) {
        return SlotView.of(key, count, LIMIT, maxStackSize);
    }

    /** A full stack of an ordinary item. */
    private static SlotView<String> full(String key) {
        return slot(key, 64, 64);
    }

    private static SlotView<String> empty() {
        return SlotView.empty(LIMIT);
    }

    @SafeVarargs
    private static List<SlotView<String>> slots(SlotView<String>... views) {
        return List.of(views);
    }

    /** {@code size} slots, each a full stack of cobblestone: a side with no room for anything else. */
    private static List<SlotView<String>> fullOfCobble(int size) {
        return Collections.nCopies(size, full(COBBLE));
    }

    /** {@code size} empty slots. */
    private static List<SlotView<String>> emptySlots(int size) {
        return Collections.nCopies(size, empty());
    }

    private static Stack<String> stack(String key, int count, int maxStackSize) {
        return new Stack<>(key, count, maxStackSize);
    }

    private static CraftGrid<String> chest() {
        return CraftGrid.of(PLANK, PLANK, PLANK, PLANK, null, PLANK, PLANK, PLANK, PLANK);
    }

    private static CraftGrid<String> cake() {
        return CraftGrid.of(MILK, MILK, MILK, SUGAR, EGG, SUGAR, WHEAT, WHEAT, WHEAT);
    }

    /** Three empty buckets, one per milk cell, as {@code getRemainingItems} hands them back. */
    private static List<Stack<String>> cakeBuckets() {
        return List.of(stack(BUCKET, 1, 16), stack(BUCKET, 1, 16), stack(BUCKET, 1, 16));
    }

    /** What the caller does with a planned outcome: write exactly the listed slots of one side. */
    private static List<SlotView<String>> committed(List<SlotView<String>> views, Side side, Outcome<String> outcome) {
        List<SlotView<String>> after = new ArrayList<>(views);
        for (Write<String> write : outcome.writes()) {
            if (write.side() == side)
                after.set(write.slot(), write.after());
        }
        return after;
    }

    private static Map<String, Long> census(List<SlotView<String>> buffer, List<SlotView<String>> inventory) {
        Map<String, Long> totals = new HashMap<>();
        for (List<SlotView<String>> side : List.of(buffer, inventory)) {
            for (SlotView<String> view : side) {
                if (!view.isEmpty())
                    totals.merge(view.key(), (long) view.count(), Long::sum);
            }
        }
        totals.values().removeIf(count -> count == 0L);
        return totals;
    }

    /**
     * Plans the craft, asserts it is planned and conserves items the way a vanilla craft does, and returns the outcome
     * for the placement checks.
     */
    private static Outcome<String> plannedCraft(CraftGrid<String> grid, List<SlotView<String>> buffer,
            List<SlotView<String>> inventory, List<Stack<String>> remainders, Stack<String> result) {
        Outcome<String> outcome = SlotPlan.craft(grid, buffer, inventory, remainders, result);
        assertTrue(outcome.isPlanned(), () -> "refused: " + outcome);
        Map<String, Long> expected = new HashMap<>(census(buffer, inventory));
        grid.perCraft().forEach((key, count) -> expected.merge(key, (long) -count, Long::sum));
        for (Stack<String> remainder : remainders)
            expected.merge(remainder.key(), (long) remainder.count(), Long::sum);
        expected.merge(result.key(), (long) result.count(), Long::sum);
        expected.values().removeIf(count -> count == 0L);
        assertEquals(expected, census(committed(buffer, Side.BUFFER, outcome),
                committed(inventory, Side.INVENTORY, outcome)), "a craft moves exactly its recipe delta");
        return outcome;
    }

    private static Write<String> write(Side side, int slot, SlotView<String> after) {
        return new Write<>(side, slot, after);
    }

    // --- the plain craft ---------------------------------------------------------------------------------------------

    /** Eight planks leave the buffer, and the chest goes into the player's inventory. */
    @Test
    void aCraftTakesFromTheBufferAndPutsTheResultIntoTheInventory() {
        Outcome<String> outcome = plannedCraft(chest(), slots(slot(PLANK, 10, 64), empty()), emptySlots(3), List.of(),
                stack(CHEST, 1, 64));
        assertEquals(List.of(write(Side.BUFFER, 0, slot(PLANK, 2, 64)), write(Side.INVENTORY, 0, slot(CHEST, 1, 64))),
                outcome.writes());
        assertEquals(Optional.empty(), outcome.refusal());
        assertEquals(Optional.empty(), outcome.about());
    }

    /** The result joins a stack it can join before it takes an empty slot. */
    @Test
    void theResultStacksBeforeItTakesAnEmptySlot() {
        Outcome<String> outcome = plannedCraft(chest(), slots(full(PLANK)),
                slots(empty(), slot(COBBLE, 5, 64), slot(CHEST, 3, 64)), List.of(), stack(CHEST, 1, 64));
        assertEquals(List.of(write(Side.BUFFER, 0, slot(PLANK, 56, 64)), write(Side.INVENTORY, 2, slot(CHEST, 4, 64))),
                outcome.writes());
    }

    /**
     * The inventory comes first for the result, even when only the buffer has a stack to join: the player crafted it
     * for themselves, and the buffer is the fall-back.
     */
    @Test
    void theInventoryComesBeforeAStackInTheBuffer() {
        Outcome<String> outcome = plannedCraft(chest(), slots(full(PLANK), slot(CHEST, 3, 64)),
                slots(slot(COBBLE, 5, 64), empty()), List.of(), stack(CHEST, 1, 64));
        assertEquals(write(Side.INVENTORY, 1, slot(CHEST, 1, 64)), outcome.writes().getLast());
    }

    /** A result bigger than the room of one stack fills that stack and spills into the next free slot. */
    @Test
    void aResultSpreadsOverSlots() {
        Outcome<String> outcome = plannedCraft(CraftGrid.of(LOG), slots(slot(LOG, 3, 64)),
                slots(empty(), full(COBBLE), slot(PLANK, 62, 64)), List.of(), stack(PLANK, 4, 64));
        assertEquals(List.of(write(Side.BUFFER, 0, slot(LOG, 2, 64)), write(Side.INVENTORY, 0, slot(PLANK, 2, 64)),
                write(Side.INVENTORY, 2, full(PLANK))), outcome.writes());
    }

    /** With the inventory full, the result goes into the buffer, into the slot the ingredients just freed. */
    @Test
    void theResultGoesIntoTheBufferWhenTheInventoryIsFull() {
        Outcome<String> outcome = plannedCraft(chest(), slots(slot(PLANK, 8, 64), full(COBBLE)),
                fullOfCobble(INVENTORY_SLOTS), List.of(), stack(CHEST, 1, 64));
        assertEquals(List.of(write(Side.BUFFER, 0, slot(CHEST, 1, 64))), outcome.writes(),
                "the plank slot emptied and became the chest's slot: one write");
    }

    // --- remainders --------------------------------------------------------------------------------------------------

    /** A honey bottle makes sugar and leaves its glass bottle with the other delivered items. */
    @Test
    void remaindersGoIntoTheBufferFirst() {
        Outcome<String> outcome = plannedCraft(CraftGrid.of(HONEY), slots(slot(HONEY, 4, 16), empty()),
                emptySlots(INVENTORY_SLOTS), List.of(stack(BOTTLE, 1, 16)), stack(SUGAR, 3, 64));
        assertEquals(List.of(write(Side.BUFFER, 0, slot(HONEY, 3, 16)), write(Side.BUFFER, 1, slot(BOTTLE, 1, 16)),
                write(Side.INVENTORY, 0, slot(SUGAR, 3, 64))), outcome.writes());
    }

    /** A remainder joins a stack of its kind in the buffer before it takes an empty buffer slot. */
    @Test
    void remaindersStackInTheBuffer() {
        Outcome<String> outcome = plannedCraft(CraftGrid.of(HONEY), slots(slot(HONEY, 4, 16), empty(),
                slot(BOTTLE, 15, 16)), emptySlots(INVENTORY_SLOTS), List.of(stack(BOTTLE, 1, 16)),
                stack(SUGAR, 3, 64));
        assertEquals(write(Side.BUFFER, 2, slot(BOTTLE, 16, 16)), outcome.writes().get(1));
    }

    /** With no room left in the buffer the remainder goes into the inventory, and before the result does. */
    @Test
    void remaindersSpillIntoTheInventoryWhenTheBufferIsFull() {
        Outcome<String> outcome = plannedCraft(CraftGrid.of(HONEY), slots(slot(HONEY, 4, 16), full(COBBLE)),
                emptySlots(3), List.of(stack(BOTTLE, 1, 16)), stack(SUGAR, 3, 64));
        assertEquals(List.of(write(Side.BUFFER, 0, slot(HONEY, 3, 16)), write(Side.INVENTORY, 0, slot(BOTTLE, 1, 16)),
                write(Side.INVENTORY, 1, slot(SUGAR, 3, 64))), outcome.writes());
    }

    /**
     * A milk bucket is a stack of one, so three of them free three slots, and the three empty buckets they leave share
     * one: a cake can be made in a buffer that is completely full, with a full inventory, and lands in the buffer.
     */
    @Test
    void threeMilkBucketsLeaveRoomForTheirBucketsAndTheCake() {
        List<SlotView<String>> buffer = slots(slot(MILK, 1, 1), slot(MILK, 1, 1), slot(MILK, 1, 1),
                slot(SUGAR, 10, 64), slot(EGG, 5, 16), slot(WHEAT, 20, 64), full(COBBLE), full(COBBLE), full(COBBLE));
        Outcome<String> outcome = plannedCraft(cake(), buffer, fullOfCobble(INVENTORY_SLOTS), cakeBuckets(),
                stack(CAKE, 1, 1));
        assertEquals(List.of(write(Side.BUFFER, 0, slot(BUCKET, 3, 16)), write(Side.BUFFER, 1, slot(CAKE, 1, 1)),
                write(Side.BUFFER, 2, empty()), write(Side.BUFFER, 3, slot(SUGAR, 8, 64)),
                write(Side.BUFFER, 4, slot(EGG, 4, 16)), write(Side.BUFFER, 5, slot(WHEAT, 17, 64))),
                outcome.writes());
    }

    /** A stack of one is a stack of one: two cakes never share a slot. */
    @Test
    void aStackOfOneNeverStacks() {
        List<SlotView<String>> inventory = new ArrayList<>(fullOfCobble(INVENTORY_SLOTS));
        inventory.set(0, slot(CAKE, 1, 1));
        inventory.set(7, empty());
        Outcome<String> outcome = plannedCraft(cake(), slots(slot(MILK, 1, 1), slot(MILK, 1, 1), slot(MILK, 1, 1),
                slot(SUGAR, 2, 64), slot(EGG, 1, 16), slot(WHEAT, 3, 64)), inventory, cakeBuckets(),
                stack(CAKE, 1, 1));
        assertEquals(write(Side.INVENTORY, 7, slot(CAKE, 1, 1)), outcome.writes().getLast());
    }

    /**
     * Four honey bottles from a stack of sixteen free no slot. With the buffer and the inventory full, the four glass
     * bottles fit nowhere, so the craft is refused, naming the bottle, before anything moves — and before the result
     * is even looked at, although a honey block would have fitted.
     */
    @Test
    void honeyBottlesFromAStackFreeNoSlot() {
        List<SlotView<String>> buffer = new ArrayList<>(fullOfCobble(9));
        buffer.set(0, slot(HONEY, 16, 16));
        List<SlotView<String>> inventory = new ArrayList<>(fullOfCobble(INVENTORY_SLOTS));
        inventory.set(5, slot(HONEY_BLOCK, 1, 64));
        Outcome<String> outcome = SlotPlan.craft(CraftGrid.of(HONEY, HONEY, null, HONEY, HONEY), buffer, inventory,
                List.of(stack(BOTTLE, 1, 16), stack(BOTTLE, 1, 16), stack(BOTTLE, 1, 16), stack(BOTTLE, 1, 16)),
                stack(HONEY_BLOCK, 1, 64));
        assertFalse(outcome.isPlanned());
        assertEquals(Optional.of(Refusal.NO_ROOM), outcome.refusal());
        assertEquals(Optional.of(BOTTLE), outcome.about());
        assertEquals(List.of(), outcome.writes(), "a refused craft writes nothing");
    }

    /** A remainder that is the very item taken comes back into the slot it left: that slot needs no write. */
    @Test
    void aSlotTakenFromAndRefilledIsNoWrite() {
        Outcome<String> outcome = plannedCraft(CraftGrid.of(BOOK, QUILL), slots(slot(BOOK, 1, 16), slot(QUILL, 1, 1)),
                emptySlots(INVENTORY_SLOTS), List.of(stack(BOOK, 1, 16)), stack(COPY, 1, 16));
        assertEquals(List.of(write(Side.BUFFER, 1, empty()), write(Side.INVENTORY, 0, slot(COPY, 1, 16))),
                outcome.writes());
    }

    // --- refusals ----------------------------------------------------------------------------------------------------

    /** A result that fits nowhere is refused, naming the result. */
    @Test
    void aResultWithNoRoomIsRefused() {
        Outcome<String> outcome = SlotPlan.craft(chest(), slots(slot(PLANK, 9, 64), full(COBBLE)),
                fullOfCobble(INVENTORY_SLOTS), List.of(), stack(CHEST, 1, 64));
        assertEquals(Optional.of(Refusal.NO_ROOM), outcome.refusal());
        assertEquals(Optional.of(CHEST), outcome.about());
        assertEquals(List.of(), outcome.writes());
    }

    /** A craft that is one plank short is refused, naming the plank. */
    @Test
    void aShortIngredientIsNamed() {
        Outcome<String> outcome = SlotPlan.craft(chest(), slots(slot(PLANK, 4, 64), slot(PLANK, 3, 64)),
                emptySlots(INVENTORY_SLOTS), List.of(), stack(CHEST, 1, 64));
        assertEquals(Optional.of(Refusal.NOT_IN_BUFFER), outcome.refusal());
        assertEquals(Optional.of(PLANK), outcome.about());
        assertEquals(List.of(), outcome.writes());
    }

    /** With several keys short, the refusal names the first in grid order. */
    @Test
    void theFirstShortKeyInGridOrderIsNamed() {
        CraftGrid<String> piston = CraftGrid.of(PLANK, PLANK, PLANK, COBBLE, IRON, COBBLE, COBBLE, REDSTONE, COBBLE);
        Outcome<String> outcome = SlotPlan.craft(piston, slots(full(PLANK), full(COBBLE)),
                emptySlots(INVENTORY_SLOTS), List.of(), stack("piston", 1, 64));
        assertEquals(Optional.of(IRON), outcome.about(), "iron comes before redstone in the grid");
    }

    /**
     * Only the exact key is an ingredient, and only from the buffer: renamed planks in the buffer and plain planks in
     * the player's own inventory stay where they are, and the craft is refused naming the plank.
     */
    @Test
    void onlyTheExactKeyInTheBufferIsTaken() {
        Outcome<String> refused = SlotPlan.craft(chest(), slots(full(NAMED_PLANK)), slots(full(PLANK)), List.of(),
                stack(CHEST, 1, 64));
        assertEquals(Optional.of(Refusal.NOT_IN_BUFFER), refused.refusal());
        assertEquals(Optional.of(PLANK), refused.about());

        Outcome<String> planned = plannedCraft(chest(), slots(slot(NAMED_PLANK, 8, 64), slot(PLANK, 8, 64)),
                slots(full(PLANK)), List.of(), stack(CHEST, 1, 64));
        assertEquals(List.of(write(Side.BUFFER, 1, slot(CHEST, 1, 64))), planned.writes(),
                "only the plain planks' slot changes; the renamed planks and the player's own planks stay");
    }

    @Test
    void anEmptyGridIsNoCraft() {
        assertThrows(IllegalArgumentException.class, () -> SlotPlan.craft(CraftGrid.<String>empty(),
                slots(full(PLANK)), emptySlots(1), List.of(), stack(CHEST, 1, 64)));
    }

    // --- the overlay -------------------------------------------------------------------------------------------------

    /**
     * The plan never touches the views it was given: the lists are unmodifiable here, so a single write would throw,
     * and they compare equal before and after, refused or planned. Only the plan's own view shows the planned contents.
     */
    @Test
    void theRealViewsAreNeverTouchedBeforeTheCommit() {
        List<SlotView<String>> buffer = slots(slot(PLANK, 10, 64), empty());
        List<SlotView<String>> inventory = slots(full(COBBLE));
        List<SlotView<String>> bufferBefore = List.copyOf(buffer);
        List<SlotView<String>> inventoryBefore = List.copyOf(inventory);

        SlotPlan<String> plan = SlotPlan.over(buffer, inventory);
        assertTrue(plan.take(PLANK, 8));
        assertEquals(0, plan.put(stack(CHEST, 1, 64), Side.INVENTORY));
        assertEquals(slot(PLANK, 2, 64), plan.view(Side.BUFFER, 0), "the plan sees what it planned");
        assertEquals(slot(CHEST, 1, 64), plan.view(Side.BUFFER, 1));
        assertEquals(2L, plan.count(Side.BUFFER, PLANK));
        assertEquals(bufferBefore, buffer, "the real buffer views are what they were");
        assertEquals(inventoryBefore, inventory);

        SlotPlan.craft(chest(), buffer, inventory, List.of(), stack(CHEST, 1, 64));
        SlotPlan.craft(chest(), slots(slot(PLANK, 7, 64)), inventory, List.of(), stack(CHEST, 1, 64));
        assertEquals(bufferBefore, buffer);
        assertEquals(inventoryBefore, inventory);
    }

    /** A take is all or nothing: asking for more than the buffer holds plans nothing at all. */
    @Test
    void aShortTakePlansNothing() {
        SlotPlan<String> plan = SlotPlan.over(slots(slot(PLANK, 3, 64), slot(PLANK, 4, 64)), slots(full(PLANK)));
        assertFalse(plan.take(PLANK, 8));
        assertEquals(List.of(), plan.writes());
        assertEquals(7L, plan.count(Side.BUFFER, PLANK));
        assertTrue(plan.take(PLANK, 0), "taking nothing always works");
        assertFalse(plan.take(PLANK, 8), "the inventory's planks are never a source");
    }

    /**
     * The smallest stacks are emptied first, so a take frees as many slots as it can — here the one slot the glass
     * bottle needs, which a take in slot order would not have freed.
     */
    @Test
    void theSmallestStacksAreTakenFirst() {
        SlotPlan<String> plan = SlotPlan.over(slots(slot(PLANK, 10, 64), slot(PLANK, 2, 64), slot(PLANK, 5, 64)),
                List.of());
        assertTrue(plan.take(PLANK, 4));
        assertEquals(List.of(write(Side.BUFFER, 1, empty()), write(Side.BUFFER, 2, slot(PLANK, 3, 64))),
                plan.writes());

        List<SlotView<String>> buffer = slots(slot(HONEY, 10, 16), slot(HONEY, 1, 16), full(COBBLE),
                slot(SUGAR, 10, 64));
        Outcome<String> outcome = plannedCraft(CraftGrid.of(HONEY), buffer, fullOfCobble(INVENTORY_SLOTS),
                List.of(stack(BOTTLE, 1, 16)), stack(SUGAR, 3, 64));
        assertEquals(List.of(write(Side.BUFFER, 1, slot(BOTTLE, 1, 16)), write(Side.BUFFER, 3, slot(SUGAR, 13, 64))),
                outcome.writes(), "the single bottle went, and its slot took the glass bottle");
    }

    /** A placement that does not fit in full keeps what fits and says how much is left. */
    @Test
    void putReportsWhatFitsNowhere() {
        SlotPlan<String> plan = SlotPlan.over(slots(empty()), slots(slot(PLANK, 60, 64)));
        assertEquals(6, plan.put(stack(PLANK, 74, 64), Side.INVENTORY));
        assertEquals(full(PLANK), plan.view(Side.INVENTORY, 0));
        assertEquals(full(PLANK), plan.view(Side.BUFFER, 0));
    }

    /** A slot never holds more than its own limit, even when the item's stack would be larger. */
    @Test
    void aSlotLimitBelowTheStackSizeIsKept() {
        SlotPlan<String> plan = SlotPlan.over(slots(SlotView.empty(1), SlotView.empty(1)), List.of());
        assertEquals(1, plan.put(stack(PLANK, 3, 64), Side.BUFFER));
        assertEquals(SlotView.of(PLANK, 1, 1, 64), plan.view(Side.BUFFER, 0));
        assertEquals(SlotView.of(PLANK, 1, 1, 64), plan.view(Side.BUFFER, 1));
    }

    /** An outcome is either a plan or a refusal with its item, never both. */
    @Test
    void anOutcomeIsAPlanOrANamedRefusal() {
        assertThrows(IllegalArgumentException.class,
                () -> new Outcome<>(Optional.of(Refusal.NO_ROOM), Optional.<String>empty(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Outcome<>(Optional.empty(), Optional.of(PLANK), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Outcome<>(Optional.of(Refusal.NO_ROOM),
                Optional.of(PLANK), List.of(write(Side.BUFFER, 0, empty()))));
        assertThrows(IllegalArgumentException.class, () -> stack(PLANK, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> stack(PLANK, 1, 0));
    }
}
