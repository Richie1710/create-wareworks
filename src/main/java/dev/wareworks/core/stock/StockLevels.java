package dev.wareworks.core.stock;

/**
 * What a warehouse knows about one item key at one moment, as a stock rule is judged against it (M15, issue #3).
 * <p>
 * The four numbers answer four different questions, and mixing them up is the whole difficulty of the feature:
 * <ul>
 *   <li>{@link #stocked()} is what is <b>lying in the racks</b>;</li>
 *   <li>{@link #inbound()} is what is <b>already on its way into</b> them;</li>
 *   <li>{@link #expected()} is what the warehouse has <b>already asked for</b> and does not have yet;</li>
 *   <li>{@link #available()} is what a <b>new request may still claim</b>, i.e. the stocked amount minus everything
 *       that is promised to a job or an open request.</li>
 * </ul>
 * {@link #pipeline()} — stocked plus inbound plus expected — is "what the warehouse has or has already sent for", the
 * number a minimum is compared with. The storage cap of a maximum uses {@code stocked + inbound} and allows for
 * {@code expected} separately ({@link StockRule#headroom}), because a production run regularly comes back with more
 * than was asked for and the warehouse must always be able to take back what it sent out for.
 * <p>
 * Pure Java with no Minecraft types. The content layer fills it from the stock index ({@code StockView#count}), the
 * reservation ledger (capacity reserved for {@code STORE} jobs), the open production orders
 * ({@code ProductionOrders#outstandingResult}) and the controller's {@code availableStock}. Negative inputs are read as
 * 0, so a caller can pass a raw subtraction without guarding it, and every sum saturates instead of overflowing.
 *
 * @param stocked   what the aisle's storage locations hold
 * @param inbound   items of the key a transport job is carrying into storage
 * @param expected  result items open production orders still wait for
 * @param available what a new request may still claim, before any reserve is taken off
 */
public record StockLevels(long stocked, long inbound, long expected, long available) {
    /** Nothing stored, nothing coming, nothing available: the levels of an item the warehouse has never seen. */
    public static final StockLevels NONE = new StockLevels(0L, 0L, 0L, 0L);

    public StockLevels {
        stocked = Math.max(0L, stocked);
        inbound = Math.max(0L, inbound);
        expected = Math.max(0L, expected);
        available = Math.max(0L, available);
    }

    /** Levels of an item that is simply lying in the racks, unpromised and with nothing on its way. */
    public static StockLevels stored(long stocked) {
        return new StockLevels(stocked, 0L, 0L, stocked);
    }

    /**
     * What the warehouse has or has already sent for: {@code stocked + inbound + expected}. This is the number a
     * minimum is compared with, so a rule that has just ordered counts the order and does not order again.
     */
    public long pipeline() {
        return sum(sum(stocked, inbound), expected);
    }

    /** {@code a + b}, saturating at {@link Long#MAX_VALUE} instead of overflowing. Both values must be ≥ 0. */
    static long sum(long a, long b) {
        long total = a + b;
        return total < 0L ? Long.MAX_VALUE : total;
    }

    /** {@code a - b}, saturating instead of overflowing. */
    static long difference(long a, long b) {
        long result = a - b;
        if (((a ^ b) & (a ^ result)) < 0L)
            return b < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        return result;
    }
}
