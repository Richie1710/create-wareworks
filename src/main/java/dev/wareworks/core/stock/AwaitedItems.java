package dev.wareworks.core.stock;

/**
 * How many more of an item something is still <b>waiting for as an item</b> (ADR-089 amendment). It matters for one kind
 * of item only: a filled container, which a fluid bay would drain. While this is above 0 for it, the crane stores it on
 * a shelf and never pours it.
 * <p>
 * Three kinds of waiter, each measured on its own, and the largest of them counts. They are not added up, because they
 * regularly wait for the <b>same</b> items: a request is backed by the order that makes them, a chain's parent line by
 * the step below it, and a stock keeper's automatic order by the minimum that started it.
 * <ul>
 * <li><b>Production:</b> what the open orders with this result still wait for, less what a store or collect trip is
 * already bringing to a shelf. What is already in stock does not count against it: an order waits for new items.</li>
 * <li><b>Takers:</b> what open requests and open orders' item ingredients are still owed from the racks and the racks
 * cannot cover, less everything on its way onto a shelf.</li>
 * <li><b>A stock keeper minimum</b> of the item: what is missing below it, less everything on its way onto a shelf.</li>
 * </ul>
 */
public final class AwaitedItems {
    private AwaitedItems() {
    }

    /**
     * Items still awaited, never negative; 0 means nothing waits and the item is stored like any other.
     *
     * @param productionResults what the open production orders with this result still wait for
     * @param promised          what open requests and open orders' item ingredients are still owed from the racks,
     *                          without what a crane already carries to them
     * @param free              what the racks hold for those takers: the stock (relocated items counted as if they never
     *                          left) less what jobs that serve no request have reserved. May be negative
     * @param minimum           the minimum of the stock rule governing the item, 0 without one
     * @param held              what the racks hold, relocated items counted as if they never left
     * @param arriving          items a store or collect trip is bringing to a shelf
     * @param returning         items another trip is putting back onto a shelf (a retrieve, supply or fill leftover)
     */
    public static long owed(long productionResults, long promised, long free, long minimum, long held, long arriving,
            long returning) {
        long coming = Math.max(0L, arriving) + Math.max(0L, returning);
        long production = productionResults - Math.max(0L, arriving);
        long takers = Math.max(0L, promised - free) - coming;
        long keeper = Math.max(0L, minimum) - Math.max(0L, held) - coming;
        return Math.max(0L, Math.max(production, Math.max(takers, keeper)));
    }
}
