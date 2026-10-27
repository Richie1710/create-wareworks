package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the list question's <b>Missing</b> section ({@code docs/warehouse-system.md} §3.4.4, issue #36,
 * ADR-069): a material the list is short of, summed over every entry that needs it, and which entries those are.
 * <p>
 * Derived from the lines' own {@link ListOrderConfirmation.Line#materials()} on both sides of the wire, so it is never
 * sent and can never disagree with the rows above it. Pure Java with no Minecraft types.
 *
 * @param key     the material
 * @param amount  how many of it the list is short of, summed over the entries (saturating)
 * @param entries the {@link ListOrderConfirmation.Line#order()}s of the entries that need it, distinct, in list order
 * @param <K>     item key type
 */
public record ListShortage<K>(K key, long amount, List<Integer> entries) {
    public ListShortage {
        Objects.requireNonNull(key, "key");
        amount = Math.max(1L, amount);
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * Every material {@code lines} are short of, with its total: the largest total first, ties by the material's first
     * appearance in list order. Empty when nothing is short. Bounded by the lines' own materials, so by the list cap
     * times {@link ListOrderConfirmation#MAX_LINE_MATERIALS}.
     *
     * @param lines the question's lines, in list order
     */
    public static <K> List<ListShortage<K>> of(List<ListOrderConfirmation.Line<K>> lines) {
        Objects.requireNonNull(lines, "lines");
        Map<K, Long> totals = new LinkedHashMap<>();
        Map<K, Set<Integer>> needers = new LinkedHashMap<>();
        List<ListOrderConfirmation.Line<K>> inOrder = new ArrayList<>(lines);
        inOrder.sort(Comparator.comparingInt(ListOrderConfirmation.Line::order));
        for (ListOrderConfirmation.Line<K> line : inOrder) {
            for (ListOrderConfirmation.Material<K> material : line.materials()) {
                totals.merge(material.key(), material.amount(), ListShortage::saturatedAdd);
                needers.computeIfAbsent(material.key(), key -> new LinkedHashSet<>()).add(line.order());
            }
        }
        List<ListShortage<K>> found = new ArrayList<>(totals.size());
        totals.forEach((key, amount) -> found.add(new ListShortage<>(key, amount, List.copyOf(needers.get(key)))));
        // A stable sort, so equal totals keep the order their materials first appeared in.
        found.sort(Comparator.comparingLong(ListShortage<K>::amount).reversed());
        return List.copyOf(found);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0L ? Long.MAX_VALUE : sum;
    }
}
