package dev.wareworks.core.crafting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import dev.wareworks.core.inventory.SlotView;

/**
 * Where the items of one craft at a warehouse terminal come from and go to (issue #24, M32), planned before a single
 * item moves.
 * <p>
 * <b>Two sides.</b> A craft at a terminal touches exactly two inventories: the terminal's <b>buffer</b>, the only place
 * its ingredients are ever taken from, and the crafting player's 36 main <b>inventory</b> slots. The plan reads both as
 * {@link SlotView}s and never writes them. What it changes is kept in an <b>overlay</b> of the slots it touched, so a
 * plan costs one scan of the slots it needs and no copy of either side, and a plan that is refused leaves nothing
 * behind to undo.
 * <p>
 * <b>One craft, in this order</b> ({@link #craft}):
 * <ol>
 * <li><b>take</b> one item of the exact key per occupied grid cell from the buffer — a shortfall is
 * {@link Refusal#NOT_IN_BUFFER}, naming the key;</li>
 * <li><b>put the remainders</b> (an empty bucket, a glass bottle, a cloned book's original) into the buffer and what is
 * left into the inventory: they stay with the ingredients they came from;</li>
 * <li><b>put the result</b> into the inventory and what is left into the buffer: the player crafted it for
 * themselves;</li>
 * <li>anything that fits nowhere is {@link Refusal#NO_ROOM}, naming the item. Nothing has been written, so nothing has
 * to be dropped.</li>
 * </ol>
 * Room for the remainders and the result is planned <b>after</b> the ingredients are gone: three milk buckets (a stack
 * of one each) free three slots, and the three empty buckets they leave need only one of them. Four honey bottles
 * taken from a stack of sixteen free no slot at all, so with every slot full that craft is refused instead of dropping
 * its glass bottles.
 * <p>
 * <b>Placing</b> ({@link #put}) fills slots that already hold the key before empty ones, on each side in slot order,
 * and never puts more into a slot than {@code min(slot limit, max stack size)}: the caller commits with plain slot
 * writes, which check nothing, so the plan itself must never build a stack the slot could not hold. <b>Taking</b>
 * ({@link #take}) empties the smallest stacks first, which frees as many slots as the take can for whatever has to be
 * put down afterwards.
 * <p>
 * <b>The commit is the caller's.</b> {@link #writes()} lists every slot whose planned contents differ from what it
 * holds, and the caller writes exactly those slots; with no other code running between the plan and the commit, the
 * plan is what happens. Pure Java with no Minecraft types, not thread-safe, used once per craft.
 *
 * @param <K> item key type
 */
public final class SlotPlan<K> {
    /** The two inventories a craft at a terminal touches. */
    public enum Side {
        /** The terminal's buffer ("Delivered here"): where the ingredients are, and the first place for remainders. */
        BUFFER,
        /** The crafting player's 36 main inventory slots: the first place for the result. */
        INVENTORY;

        /** The side a placement turns to when this one is full. */
        public Side other() {
            return this == BUFFER ? INVENTORY : BUFFER;
        }
    }

    /** Why a craft cannot be planned. Either way nothing has been touched. */
    public enum Refusal {
        /** The buffer holds fewer of a key than one craft takes; the outcome names that key. */
        NOT_IN_BUFFER,
        /** A remainder or the result fits neither side; the outcome names that item. */
        NO_ROOM
    }

    /**
     * Items to put down, as one stack of them.
     *
     * @param key          the exact item key
     * @param count        how many, at least 1
     * @param maxStackSize the key's max stack size, at least 1; what an empty slot can take of it
     * @param <K>          item key type
     */
    public record Stack<K>(K key, int count, int maxStackSize) {
        public Stack {
            Objects.requireNonNull(key, "key");
            if (count < 1)
                throw new IllegalArgumentException("a stack holds at least one item: " + count);
            if (maxStackSize < 1)
                throw new IllegalArgumentException("max stack size must be at least 1: " + maxStackSize);
        }
    }

    /**
     * One slot the caller has to write to carry out the plan.
     *
     * @param side  which inventory the slot belongs to
     * @param slot  the slot's index on that side
     * @param after what the slot holds afterwards; an empty view for a slot the plan emptied
     * @param <K>   item key type
     */
    public record Write<K>(Side side, int slot, SlotView<K> after) {
        public Write {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(after, "after");
        }
    }

    /**
     * The answer to one planned craft: either the writes that carry it out, or why it cannot happen and which item is
     * to blame. A refused outcome has no writes.
     *
     * @param refusal why the craft cannot happen; empty for a planned craft
     * @param about   the key the refusal names; present exactly when {@code refusal} is
     * @param writes  what to write for a planned craft, buffer slots before inventory slots, each side in slot order
     * @param <K>     item key type
     */
    public record Outcome<K>(Optional<Refusal> refusal, Optional<K> about, List<Write<K>> writes) {
        public Outcome {
            Objects.requireNonNull(refusal, "refusal");
            Objects.requireNonNull(about, "about");
            writes = List.copyOf(writes);
            if (refusal.isPresent() != about.isPresent())
                throw new IllegalArgumentException("a refusal names its item, and only a refusal does");
            if (refusal.isPresent() && !writes.isEmpty())
                throw new IllegalArgumentException("a refused craft writes nothing");
        }

        static <K> Outcome<K> planned(List<Write<K>> writes) {
            return new Outcome<>(Optional.empty(), Optional.empty(), writes);
        }

        static <K> Outcome<K> refused(Refusal refusal, K about) {
            return new Outcome<>(Optional.of(refusal), Optional.of(about), List.of());
        }

        /** Whether the craft can happen, i.e. whether {@link #writes()} is the plan. */
        public boolean isPlanned() {
            return refusal.isEmpty();
        }
    }

    private final List<SlotView<K>> buffer;
    private final List<SlotView<K>> inventory;
    /** The planned contents of the buffer slots this plan touched, by slot index. */
    private final Map<Integer, SlotView<K>> bufferOverlay = new TreeMap<>();
    /** The planned contents of the inventory slots this plan touched, by slot index. */
    private final Map<Integer, SlotView<K>> inventoryOverlay = new TreeMap<>();

    private SlotPlan(List<SlotView<K>> buffer, List<SlotView<K>> inventory) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    /**
     * An empty plan over the two sides as they are now. The lists are read, never written and not copied, so they must
     * not change while the plan is in use.
     *
     * @param buffer    the terminal's buffer slots
     * @param inventory the player's main inventory slots
     */
    public static <K> SlotPlan<K> over(List<SlotView<K>> buffer, List<SlotView<K>> inventory) {
        return new SlotPlan<>(buffer, inventory);
    }

    /**
     * Plans one craft of {@code grid} (see the class comment): take one item per occupied cell from the buffer, put the
     * remainders buffer first, put the result inventory first. The first thing that fails decides the refusal, in
     * that order; the two lists are not touched either way.
     *
     * @param grid       the recipe as laid; must not be empty
     * @param buffer     the terminal's buffer slots
     * @param inventory  the player's main inventory slots
     * @param remainders what the recipe leaves behind, in grid order; empty for most recipes
     * @param result     what the craft makes
     * @throws IllegalArgumentException if the grid is empty: a craft that takes nothing would make something from
     *                                  nothing
     */
    public static <K> Outcome<K> craft(CraftGrid<K> grid, List<SlotView<K>> buffer, List<SlotView<K>> inventory,
            List<Stack<K>> remainders, Stack<K> result) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(remainders, "remainders");
        Objects.requireNonNull(result, "result");
        if (grid.isEmpty())
            throw new IllegalArgumentException("a craft takes at least one item");
        SlotPlan<K> plan = over(buffer, inventory);
        for (Map.Entry<K, Integer> need : grid.perCraft().entrySet()) {
            if (!plan.take(need.getKey(), need.getValue()))
                return Outcome.refused(Refusal.NOT_IN_BUFFER, need.getKey());
        }
        for (Stack<K> remainder : remainders) {
            if (plan.put(remainder, Side.BUFFER) > 0)
                return Outcome.refused(Refusal.NO_ROOM, remainder.key());
        }
        if (plan.put(result, Side.INVENTORY) > 0)
            return Outcome.refused(Refusal.NO_ROOM, result.key());
        return Outcome.planned(plan.writes());
    }

    /** Slots on {@code side}. */
    public int size(Side side) {
        return slots(side).size();
    }

    /** Slot {@code slot} of {@code side} as planned: its planned contents once the plan touched it, else its view. */
    public SlotView<K> view(Side side, int slot) {
        SlotView<K> planned = overlay(side).get(slot);
        return planned != null ? planned : slots(side).get(slot);
    }

    /** How many of {@code key} {@code side} holds as planned. */
    public long count(Side side, K key) {
        Objects.requireNonNull(key, "key");
        long total = 0L;
        for (int slot = 0; slot < size(side); slot++) {
            SlotView<K> view = view(side, slot);
            if (view.holds(key))
                total += view.count();
        }
        return total;
    }

    /**
     * Plans taking {@code count} of the exact {@code key} from the <b>buffer</b>, the only place a craft takes from.
     * All or nothing: when the buffer holds fewer, nothing is planned and the answer is {@code false}. The smallest
     * stacks are emptied first (ties in slot order), so the take frees as many slots as it can.
     *
     * @param count how many to take; 0 or less takes nothing and succeeds
     */
    public boolean take(K key, int count) {
        Objects.requireNonNull(key, "key");
        if (count <= 0)
            return true;
        List<Integer> holding = new ArrayList<>();
        long held = 0L;
        for (int slot = 0; slot < buffer.size(); slot++) {
            SlotView<K> view = view(Side.BUFFER, slot);
            if (view.holds(key)) {
                holding.add(slot);
                held += view.count();
            }
        }
        if (held < count)
            return false;
        holding.sort(Comparator.comparingInt((Integer slot) -> view(Side.BUFFER, slot).count())
                .thenComparingInt(slot -> slot));
        int left = count;
        for (int slot : holding) {
            if (left == 0)
                break;
            SlotView<K> view = view(Side.BUFFER, slot);
            int taken = Math.min(left, view.count());
            int remaining = view.count() - taken;
            bufferOverlay.put(slot, remaining == 0 ? SlotView.empty(view.slotLimit())
                    : SlotView.of(key, remaining, view.slotLimit(), view.maxStackSize()));
            left -= taken;
        }
        return true;
    }

    /**
     * Plans putting {@code stack} down: on side {@code first}, then on the other side, each time into slots that
     * already hold the key before empty ones and in slot order. What fits stays planned even when not all of it does.
     *
     * @return how many items fit nowhere; 0 when the whole stack is placed
     */
    public int put(Stack<K> stack, Side first) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(first, "first");
        int left = stack.count();
        for (Side side : List.of(first, first.other())) {
            left = fill(side, stack, left, true);
            left = fill(side, stack, left, false);
            if (left == 0)
                break;
        }
        return left;
    }

    /**
     * Every slot whose planned contents differ from its view: buffer slots before inventory slots, each side in slot
     * order. A slot the plan touched and left as it was (taken from, then filled back to the same stack) is not listed.
     */
    public List<Write<K>> writes() {
        List<Write<K>> writes = new ArrayList<>();
        for (Side side : Side.values()) {
            overlay(side).forEach((slot, after) -> {
                if (!after.equals(slots(side).get(slot)))
                    writes.add(new Write<>(side, slot, after));
            });
        }
        return List.copyOf(writes);
    }

    /**
     * One placement pass over {@code side}: into the slots holding the key ({@code stacking}) or into the empty ones.
     *
     * @return how many of {@code left} are still to be placed
     */
    private int fill(Side side, Stack<K> stack, int left, boolean stacking) {
        for (int slot = 0; slot < size(side) && left > 0; slot++) {
            SlotView<K> view = view(side, slot);
            if (stacking ? !view.holds(stack.key()) : !view.isEmpty())
                continue;
            // An occupied slot keeps its own stack size; the smaller of the two only bounds the room, in case a caller
            // ever hands in two different sizes for one key.
            int ownStack = view.isEmpty() ? stack.maxStackSize() : view.maxStackSize();
            int room = Math.max(0, Math.min(view.slotLimit(), Math.min(ownStack, stack.maxStackSize())) - view.count());
            if (room == 0)
                continue;
            int placed = Math.min(room, left);
            overlay(side).put(slot, SlotView.of(stack.key(), view.count() + placed, view.slotLimit(), ownStack));
            left -= placed;
        }
        return left;
    }

    private List<SlotView<K>> slots(Side side) {
        return side == Side.BUFFER ? buffer : inventory;
    }

    private Map<Integer, SlotView<K>> overlay(Side side) {
        return side == Side.BUFFER ? bufferOverlay : inventoryOverlay;
    }
}
