package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.wareworks.core.inventory.KeyCount;

/**
 * Folds the stocked package keys of a warehouse into one terminal row per <b>address</b> (issue #40, owner decision
 * E5, design §6.1): the boxes of an address, what is available of them, and a {@link PackageGroupSummary} of what is
 * inside.
 * <p>
 * Every box with its own contents is its own item key ({@code InventoryGrabber} keys boxes by all their components), so
 * a warehouse of boxes would otherwise list one row per box and the screen's 512-row window would hold 512 boxes and
 * nothing else. Folding happens on the server, <b>before</b> that cut, and the rows it makes are ordinary rows under a
 * synthetic key per address that the caller supplies: search, sort and "most used" then work per address with no
 * client logic.
 * <p>
 * Pure Java: the caller classifies its keys (address, the contents of one box, the order flag) and this class only
 * adds them up. Server thread only; one instance per snapshot.
 *
 * @param <K> item key type
 */
public final class PackageGroupFold<K> {
    private final Comparator<? super K> order;
    /** The groups in the order their first box was added; the caller sorts the rows for its cut. */
    private final Map<String, Group<K>> groups = new LinkedHashMap<>();

    /**
     * @param order a total order of keys that depends on their values only, which decides equal content counts so a
     *              summary never depends on a hash map's iteration order
     */
    public PackageGroupFold(Comparator<? super K> order) {
        this.order = Objects.requireNonNull(order, "order");
    }

    /**
     * Adds the stocked boxes of one package key.
     *
     * @param address      the boxes' address, {@code ""} for boxes without one
     * @param box          the package key
     * @param total        how many such boxes are stored (or carried by a relocation)
     * @param available    how many of them a player may still be promised
     * @param perBox       what <b>one</b> of these boxes holds
     * @param networkOrder whether the boxes carry Create's order data
     */
    public void add(String address, K box, long total, long available, List<KeyCount<K>> perBox,
            boolean networkOrder) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(perBox, "perBox");
        if (total <= 0L)
            return;
        Group<K> group = groups.computeIfAbsent(address == null ? "" : address, Group::new);
        group.boxes += total;
        group.available += Math.max(0L, Math.min(available, total));
        group.networkOrder |= networkOrder;
        for (KeyCount<K> content : perBox) {
            if (content.count() > 0L)
                group.contents.merge(content.key(), saturatingTimes(content.count(), total), PackageGroupFold::sum);
        }
    }

    /** Whether no box was added. */
    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** The groups, one per address, in the order their first box was added. */
    public List<Row<K>> rows() {
        List<Row<K>> rows = new ArrayList<>(groups.size());
        for (Group<K> group : groups.values()) {
            List<KeyCount<K>> top = KeyCount.largestFirst(group.contents, PackageGroupSummary.MAX_CONTENTS, order);
            rows.add(new Row<>(group.address, group.boxes, group.available,
                    new PackageGroupSummary<>(group.boxes, top, group.contents.size(), group.networkOrder)));
        }
        return List.copyOf(rows);
    }

    private static long saturatingTimes(long a, long b) {
        long product = a * b;
        return (a != 0 && (product / a != b || product < 0)) ? Long.MAX_VALUE : product;
    }

    private static long sum(long a, long b) {
        long total = a + b;
        return total < 0L ? Long.MAX_VALUE : total;
    }

    /**
     * One address's row.
     *
     * @param address   the address, {@code ""} for boxes without one
     * @param boxes     every box of the address that is stored
     * @param available what a player may still be promised of them
     * @param summary   what the boxes hold
     */
    public record Row<K>(String address, long boxes, long available, PackageGroupSummary<K> summary) {
        public Row {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(summary, "summary");
            boxes = Math.max(0L, boxes);
            available = Math.max(0L, Math.min(available, boxes));
        }
    }

    private static final class Group<K> {
        private final String address;
        private final Map<K, Long> contents = new HashMap<>();
        private long boxes;
        private long available;
        private boolean networkOrder;

        private Group(String address) {
            this.address = address;
        }
    }
}
