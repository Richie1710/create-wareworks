package dev.wareworks.core.requester;

import java.util.Objects;
import java.util.Optional;

/**
 * How much one order of a Schematicannon Requester asks for (issue #45, ADR-080, amended by ADR-081,
 * {@code docs/warehouse-system.md}): a full stack at most, or what the schematic still needs by the cannon's material
 * list if that is less (a full stack for an item the list does not show), and never more than the cannon's inventories
 * or the terminal's buffer can take, nor more than the warehouse's stock and, for the rest, its production patterns can
 * give. Pure arithmetic.
 * <p>
 * <b>The need</b> ({@link #need}) comes from the cannon's material list:
 * <pre>
 * remaining = required - gathered - ordered
 * need      = damage ? 1 : required &lt;= 0 ? stack : remaining &gt; 0 ? max(remaining, missing) : 0 (stale)
 * </pre>
 * {@code missing} is what the cannon reports for the block it waits on (a double slab misses two), so the need never
 * drops below it. A {@code 0} means the counter is stale: the list says nothing is left while the cannon still misses
 * the item; {@link ChecklistNeed} decides what to order then.
 * <p>
 * <b>The amount</b> ({@link #of}) is the stock part first, then the made part (ADR-081):
 * <pre>
 * cap       = min(stack, need, cannonRoom, bufferRoom, maxRequest)
 * fromStock = min(cap, available)
 * made      = min(cap - fromStock, makeCap)
 * amount    = fromStock + made
 * </pre>
 * The stock part is never larger than what automation may take; the made part never larger than what the schematic
 * still needs ({@code makeCap}, {@link ChecklistNeed#needOf}), because making costs ingredients and stock does not. The
 * first of the outside factors that is 0 decides the refusal, in this order: the cannon's inventories
 * ({@link RequesterStatus#CANNON_FULL}, owner rule: nothing is ordered while they are full), the buffer
 * ({@link RequesterStatus#BUFFER_FULL}: the planner would skip such a request until the dead-man switch fires), the
 * stock and the made part together ({@link RequesterStatus#RESERVED} when a reserve holds stock back, otherwise
 * {@link RequesterStatus#OUT_OF_STOCK}).
 *
 * @param amount  what to order, at least 1; 0 exactly when {@code refusal} is present
 * @param maxMade what of {@code amount} production may make at most, {@code 0..amount}; 0 for a refusal
 * @param refusal why nothing is ordered
 */
public record OrderAmount(int amount, int maxMade, Optional<RequesterStatus> refusal) {
    public OrderAmount {
        Objects.requireNonNull(refusal, "refusal");
        if (refusal.isPresent() ? amount != 0 : amount < 1)
            throw new IllegalArgumentException("an order asks for at least 1 item, a refusal for none: " + amount);
        if (maxMade < 0 || maxMade > amount)
            throw new IllegalArgumentException("maxMade must be within 0.." + amount + ": " + maxMade);
    }

    /** An order for {@code amount} items from stock, none of them made. */
    public static OrderAmount order(int amount) {
        return order(amount, 0);
    }

    /** An order for {@code amount} items of which production may make {@code maxMade}. */
    public static OrderAmount order(int amount, int maxMade) {
        return new OrderAmount(amount, maxMade, Optional.empty());
    }

    /** No order, because of {@code reason}. */
    public static OrderAmount refused(RequesterStatus reason) {
        return new OrderAmount(0, 0, Optional.of(Objects.requireNonNull(reason, "reason")));
    }

    /** Whether something is ordered. */
    public boolean isOrder() {
        return refusal.isEmpty();
    }

    /**
     * What the schematic still needs of the missing item (see the class comment), or {@code 0} when the counter is
     * stale.
     *
     * @param stack    the item's stack size (at least 1 is assumed)
     * @param missing  how many the cannon reports missing for the block it waits on (at least 1 is assumed)
     * @param required what the material list requires of the item in all; {@code 0} when it is not on the list
     * @param gathered what the list found in the cannon's inventories
     * @param ordered  what this requester ordered since the list was made
     * @param damage   whether the requirement only damages the item (super glue): one is enough then
     */
    public static int need(int stack, int missing, long required, long gathered, long ordered, boolean damage) {
        int fullStack = Math.max(1, stack);
        if (damage)
            return 1;
        if (required <= 0)
            return fullStack;
        long remaining = required - Math.max(0, gathered) - Math.max(0, ordered);
        if (remaining <= 0)
            return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(remaining, Math.max(1, missing)));
    }

    /**
     * The order for a {@code need} from stock alone: {@link #of(int, int, long, long, long, long, int, int)} with
     * nothing to make.
     */
    public static OrderAmount of(int need, int stack, long cannonRoom, long bufferRoom, long available,
            long availableToPlayer, int maxRequest) {
        return of(need, stack, cannonRoom, bufferRoom, available, availableToPlayer, maxRequest, 0);
    }

    /**
     * The order for a {@code need} (see the class comment).
     *
     * @param need              what the schematic still needs, at least 1
     * @param stack             the item's stack size (at least 1 is assumed)
     * @param cannonRoom        how many the cannon's attached inventories could take
     * @param bufferRoom        how many the terminal's buffer could take
     * @param available         how many the warehouse may give automation
     * @param availableToPlayer how many it would give a player; more than {@code available} means a reserve
     * @param maxRequest        the terminal's request cap (at least 1 is assumed)
     * @param makeCap           how many of the order production may make at most (rule R2 of ADR-081); 0 when no
     *                          pattern makes the item or making waits for a retry
     * @throws IllegalArgumentException if {@code need < 1}
     */
    public static OrderAmount of(int need, int stack, long cannonRoom, long bufferRoom, long available,
            long availableToPlayer, int maxRequest, int makeCap) {
        if (need < 1)
            throw new IllegalArgumentException("need must be at least 1: " + need);
        if (cannonRoom <= 0)
            return refused(RequesterStatus.CANNON_FULL);
        if (bufferRoom <= 0)
            return refused(RequesterStatus.BUFFER_FULL);
        long cap = Math.min(Math.max(1, stack), need);
        cap = Math.min(cap, cannonRoom);
        cap = Math.min(cap, bufferRoom);
        cap = Math.min(cap, Math.max(1, maxRequest));
        long fromStock = Math.min(cap, Math.max(0L, available));
        long made = Math.min(cap - fromStock, Math.max(0, makeCap));
        if (fromStock + made <= 0)
            return refused(availableToPlayer > 0 ? RequesterStatus.RESERVED : RequesterStatus.OUT_OF_STOCK);
        return order((int) (fromStock + made), (int) made);
    }
}
