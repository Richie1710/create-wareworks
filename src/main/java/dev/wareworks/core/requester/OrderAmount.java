package dev.wareworks.core.requester;

import java.util.Objects;
import java.util.Optional;

/**
 * How much one order of a Schematicannon Requester asks for (issue #45, ADR-080, {@code docs/warehouse-system.md}): a
 * full stack at most, or exactly what the schematic still needs if that is less, and never more than the cannon's
 * inventories, the terminal's buffer or the warehouse's stock can take or give. Pure arithmetic.
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
 * <b>The amount</b> ({@link #of}) is {@code min(stack, need, cannonRoom, bufferRoom, available, maxRequest)}. The first
 * of the three outside factors that is 0 decides the refusal, in this order: the cannon's inventories
 * ({@link RequesterStatus#CANNON_FULL}, owner rule: nothing is ordered while they are full), the buffer
 * ({@link RequesterStatus#BUFFER_FULL}: the planner would skip such a request until the dead-man switch fires), the
 * stock ({@link RequesterStatus#RESERVED} when only a reserve holds it back, otherwise
 * {@link RequesterStatus#OUT_OF_STOCK}).
 *
 * @param amount  what to order, at least 1; 0 exactly when {@code refusal} is present
 * @param refusal why nothing is ordered
 */
public record OrderAmount(int amount, Optional<RequesterStatus> refusal) {
    public OrderAmount {
        Objects.requireNonNull(refusal, "refusal");
        if (refusal.isPresent() ? amount != 0 : amount < 1)
            throw new IllegalArgumentException("an order asks for at least 1 item, a refusal for none: " + amount);
    }

    /** An order for {@code amount} items. */
    public static OrderAmount order(int amount) {
        return new OrderAmount(amount, Optional.empty());
    }

    /** No order, because of {@code reason}. */
    public static OrderAmount refused(RequesterStatus reason) {
        return new OrderAmount(0, Optional.of(Objects.requireNonNull(reason, "reason")));
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
     * The order for a {@code need} (see the class comment).
     *
     * @param need              what the schematic still needs, at least 1
     * @param stack             the item's stack size (at least 1 is assumed)
     * @param cannonRoom        how many the cannon's attached inventories could take
     * @param bufferRoom        how many the terminal's buffer could take
     * @param available         how many the warehouse may give automation
     * @param availableToPlayer how many it would give a player; more than {@code available} means a reserve
     * @param maxRequest        the terminal's request cap (at least 1 is assumed)
     * @throws IllegalArgumentException if {@code need < 1}
     */
    public static OrderAmount of(int need, int stack, long cannonRoom, long bufferRoom, long available,
            long availableToPlayer, int maxRequest) {
        if (need < 1)
            throw new IllegalArgumentException("need must be at least 1: " + need);
        if (cannonRoom <= 0)
            return refused(RequesterStatus.CANNON_FULL);
        if (bufferRoom <= 0)
            return refused(RequesterStatus.BUFFER_FULL);
        if (available <= 0)
            return refused(availableToPlayer > 0 ? RequesterStatus.RESERVED : RequesterStatus.OUT_OF_STOCK);
        long amount = Math.min(Math.max(1, stack), need);
        amount = Math.min(amount, cannonRoom);
        amount = Math.min(amount, bufferRoom);
        amount = Math.min(amount, available);
        amount = Math.min(amount, Math.max(1, maxRequest));
        return order((int) amount);
    }
}
