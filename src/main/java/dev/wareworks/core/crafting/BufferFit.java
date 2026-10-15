package dev.wareworks.core.crafting;

import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import dev.wareworks.core.crafting.SlotPlan.Side;
import dev.wareworks.core.crafting.SlotPlan.Stack;
import dev.wareworks.core.inventory.SlotView;

/**
 * Whether what a craft fetch would bring still fits into a warehouse terminal's buffer, measured on slot views before
 * anything is requested (issue #24, slice 2, {@code docs/warehouse-system.md} §3.4.5).
 * <p>
 * <b>What is already on its way goes first.</b> Every open request to the terminal — a player's own click, another
 * player's click, a clipboard order's portion — will put its remaining items into the same buffer, and nothing tells
 * which crane trip arrives first. The fetch is therefore measured against the room those requests leave: they are put
 * down first, as far as they fit, and only then the fetch's own demand. A fetch that passes this check never makes the
 * crane wait at the terminal for room, however the trips interleave; room taken <b>later</b> (a new click, the result
 * of a craft put down in the buffer) falls back to the ordinary behaviour of a full destination, which waits and never
 * refuses.
 * <p>
 * Items of an open request that do not fit even on their own are not held against the fetch: they wait for room either
 * way, and the fetch is measured against the slots the requests really take.
 * <p>
 * The placement is {@link SlotPlan#put}'s own — stacking onto slots that hold the key before empty ones, each slot
 * bounded by {@code min(slot limit, max stack size)} — so a milk bucket takes a slot of its own here exactly as it does
 * when the crane drops it. Pure Java with no Minecraft types; the slot views are read and never written.
 */
public final class BufferFit {
    private BufferFit() {
    }

    /**
     * Whether all of {@code demand} fits into {@code buffer} once {@code ahead} has been put down first.
     *
     * @param buffer the terminal's buffer slots as they are now
     * @param ahead  what open requests to the terminal still bring, in the order they are listed; what of it does not
     *               fit is ignored
     * @param demand what the fetch would bring
     * @param <K>    item key type
     */
    public static <K> boolean fits(List<SlotView<K>> buffer, List<Stack<K>> ahead, List<Stack<K>> demand) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(ahead, "ahead");
        Objects.requireNonNull(demand, "demand");
        // The buffer is the only side: a plan over no inventory slots puts nothing anywhere else.
        SlotPlan<K> plan = SlotPlan.over(buffer, List.of());
        for (Stack<K> stack : ahead)
            plan.put(stack, Side.BUFFER);
        for (Stack<K> stack : demand) {
            if (plan.put(stack, Side.BUFFER) > 0)
                return false;
        }
        return true;
    }

    /**
     * The largest {@code r} in {@code 0..max} whose demand fits ({@link #fits}) after {@code ahead}.
     * <p>
     * {@code demand} must grow with {@code r} (what {@link CraftGrid#toFetch} does), which is what makes the answer a
     * boundary: a binary search, after one check of {@code max} itself, the common case in which everything fits. That
     * is one plan when the fetch fits and at most {@code 1 + log2(max + 1)} plans when it does not. An {@code r} of 0
     * is answered without a plan even when its demand would not fit, because no fetch can be smaller.
     *
     * @param max    the most runs the player asked for; 0 or less answers 0
     * @param demand what {@code r} runs would bring, for {@code r} in {@code 1..max}
     * @param <K>    item key type
     */
    public static <K> int largest(List<SlotView<K>> buffer, List<Stack<K>> ahead, int max,
            IntFunction<List<Stack<K>>> demand) {
        Objects.requireNonNull(demand, "demand");
        if (max <= 0)
            return 0;
        if (fits(buffer, ahead, demand.apply(max)))
            return max;
        int fitting = 0;
        int failing = max;
        // Invariant: runs <= fitting fit (0 trivially), failing does not.
        while (failing - fitting > 1) {
            int mid = fitting + (failing - fitting) / 2;
            if (fits(buffer, ahead, demand.apply(mid)))
                fitting = mid;
            else
                failing = mid;
        }
        return fitting;
    }
}
