package dev.wareworks.content.crane.head;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * The inventory a handling head reaches at one location of the aisle, for the current tick only
 * ({@code docs/stacker-crane.md} §6). Five kinds exist ({@link TransferContexts#resolve}): a storage location (the item
 * handler of the inventory behind a warehouse interface), an input station (its internal extract/insert API), an
 * output station (its internal insert API; nothing can be extracted), a <b>collecting</b> warehouse port (the item
 * handler of the inventory behind it, extract only; M18, issue #13) and a <b>fluid bay</b>, which holds no items at all
 * and whose one real operation is {@link #exchange} (M30, issue #21).
 * <p>
 * The same context serves the crane's real transfers and the controller's live simulations
 * ({@code docs/warehouse-system.md} §5): a simulated call equals the real one in the same tick for well-behaved
 * inventories. Callers never keep a context across ticks. Stacks passed in are never modified or stored.
 */
public interface TransferContext {
    /** What kind of location this is. */
    LocationKind kind();

    /** World position of the location (where the arm reaches in; stray items are spilled here). */
    BlockPos position();

    /**
     * Removes up to {@code maxAmount} items of exactly {@code key}, at most one stack of the key. Something else a
     * misbehaving inventory hands out is given back or spilled, never returned or lost.
     *
     * @return a new stack of {@code key} with the removed amount, or empty
     */
    ItemStack extract(ItemKey key, int maxAmount, boolean simulate);

    /**
     * Inserts {@code stack} (matching stacks first where applicable).
     *
     * @return what did not fit (a new stack, or empty)
     */
    ItemStack insert(ItemStack stack, boolean simulate);

    /** How many of up to {@code maxAmount} items of {@code key} could be extracted now (no change). */
    int simulateExtract(ItemKey key, int maxAmount);

    /** How many of {@code amount} items of {@code key} would be accepted now (no change). */
    int simulateInsert(ItemKey key, int amount);

    /** Drops {@code stack} into the world at {@link #position()} (never voids items). */
    void spill(ItemStack stack);

    /**
     * Gives up {@code amount} containers of {@code held} and takes back that many containers of <b>another</b> item at
     * the same stop — the <b>container exchange</b> (M30, issue #21, D1). A location that is not an exchange location
     * answers empty, which is why this is a default and no existing implementation changed.
     *
     * <h2>Why this is not an insert</h2>
     * An item handler cannot express it. {@link InventoryGrabber#drop} computes what a target accepted as
     * {@code chunk - remainder.getCount()}, so a fluid bay that took a lava bucket and handed an <i>empty</i> bucket
     * back as the insert remainder would be read as having accepted <b>nothing</b>: the filled bucket would stay
     * counted as held, the empty one would be dropped on the floor of that method, and no item census could see the
     * loss because the empty bucket never existed in any inventory. The exchange is therefore a primitive of its own,
     * and a fluid bay deliberately exposes no item capability at all so that a funnel, a chute, a belt or a hopper can
     * never reach the same trap.
     *
     * <h2>The contract, which the whole conservation argument rests on</h2>
     * <ol>
     * <li><b>All or nothing.</b> Either exactly {@code amount} containers are exchanged, or nothing happens and the
     * answer is empty. A partial exchange would leave <i>two</i> keys in the handling head — some filled, some empty —
     * and a head holding a key that is not its job's has that key spilled at the dock by
     * {@code CraneExecution.reconcileHeadWithJob}. Callers that want a bound ask {@link #simulateInsert} instead,
     * which <b>is</b> monotone.</li>
     * <li><b>A real call either performs exactly what it answers, or changes nothing and answers empty.</b> There is
     * no third outcome: a location that cannot keep this promise has to undo its own half before it answers.</li>
     * <li><b>Within one tick a real call answers what the simulated call answered.</b> The caller simulates first and
     * only performs when the plan is the exchange it asked for, so this is what makes the swap safe to begin at
     * all.</li>
     * <li><b>Nothing of {@code held} is passed in and nothing of the result is handed out.</b> The containers live in
     * the head as a key and a count; this method only says what they become. The head does the moving, in the same
     * synchronous call, with no tick boundary in between.</li>
     * </ol>
     *
     * @param held     the item the head is giving up, of which it really holds at least {@code amount}
     * @param amount   how many containers to exchange, at least 1
     * @param simulate whether to leave the location untouched and only answer what a real call would do
     * @return what the exchange yields, or empty for a location that refuses it (and then nothing changed)
     */
    default Optional<ContainerExchange> exchange(ItemKey held, int amount, boolean simulate) {
        return Optional.empty();
    }

    /**
     * Whether this location takes items <b>only</b> through {@link #exchange} and never through {@link #insert} — a
     * fluid bay, and nothing else (M30, issue #21, D3).
     * <p>
     * It exists so that a caller whose exchange was refused knows it already has its answer. Falling back to an
     * {@link #insert} there would be refused too, and a fluid bay deliberately <b>logs</b> that call, because by design
     * it has no legitimate caller: a container reaches a bay through an exchange or a player's hand and in no other way.
     * Without this question a bay that simply filled up between the plan and the drop would be reported in the log as a
     * caller using the wrong operation, which is the wrong diagnosis for the one situation the ladder handles routinely.
     * A refused exchange at such a location means "the target took nothing", which every caller already knows how to
     * answer.
     */
    default boolean exchangesOnly() {
        return false;
    }

    /**
     * What one {@link #exchange} does: the item the given-up containers turn into, how many of them, and how much
     * fluid moved in the opposite direction.
     * <p>
     * A record with no "nothing happened" state on purpose — a refused exchange is an empty {@link Optional}, never a
     * {@code ContainerExchange} of zero, so a caller cannot mistake one for the other.
     *
     * @param result       the item the head receives, one per container; never the item it gave up
     * @param containers   how many containers were exchanged, at least 1
     * @param millibuckets how much fluid moved into the location <b>in total</b>, for all {@code containers} together;
     *                     at least 1, and it is the number the fluid census sees appear in the location's tank while
     *                     the same amount disappears from the containers
     */
    record ContainerExchange(ItemKey result, int containers, int millibuckets) {
        public ContainerExchange {
            Objects.requireNonNull(result, "result");
            if (containers < 1)
                throw new IllegalArgumentException("an exchange moves at least one container: " + containers);
            if (millibuckets < 1)
                throw new IllegalArgumentException("an exchange moves at least 1 mB: " + millibuckets);
        }
    }
}
