package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.Reservation;
import dev.wareworks.util.WareworksLang;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What the goggles of a storage location show about its reservations ({@code docs/warehouse-system.md} §3.1): items a
 * crane job is bringing ({@code CAPACITY}, and {@code TRANSIT} at stations) and items reserved for a job to take out
 * ({@code STOCK}), each by item type with the {@value #MAX_ENTRIES} largest amounts.
 * <p>
 * Built on the server from the controller's reservation ledger while a player observes the interface, and synced in its
 * client packet. The synced form holds at most {@value #MAX_ENTRIES} item ids and counts per direction, whatever the
 * reservations are, so it is bounded; item data components never leave the server. Records compare by value, so the
 * server only syncs a change. Reading never throws: invalid entries (unknown items, negative counts) are dropped.
 *
 * @param incoming items on their way into the location, largest first (at most {@value #MAX_ENTRIES})
 * @param outgoing items reserved inside the location for a pick, largest first (at most {@value #MAX_ENTRIES})
 */
public record LocationReservationSummary(List<KeyCount<Item>> incoming, List<KeyCount<Item>> outgoing) {
    /** Item types listed per direction. */
    public static final int MAX_ENTRIES = 2;
    /** No reservation. */
    public static final LocationReservationSummary NONE = new LocationReservationSummary(List.of(), List.of());

    private static final String INCOMING = "Incoming";
    private static final String OUTGOING = "Outgoing";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";

    public LocationReservationSummary {
        incoming = bounded(incoming);
        outgoing = bounded(outgoing);
    }

    /** Summary of the reservations at one location (e.g. {@code ReservationView#reservationsAt}). */
    public static LocationReservationSummary of(Collection<? extends Reservation<ItemKey, ?>> reservations) {
        Map<Item, Long> in = new LinkedHashMap<>();
        Map<Item, Long> out = new LinkedHashMap<>();
        for (Reservation<ItemKey, ?> reservation : reservations) {
            Map<Item, Long> totals = reservation.kind() == Reservation.Kind.STOCK ? out : in;
            totals.merge(reservation.key().getItem(), (long) reservation.amount(), LocationReservationSummary::saturatedSum);
        }
        return new LocationReservationSummary(largestFirst(in), largestFirst(out));
    }

    /** Whether nothing is reserved. */
    public boolean isEmpty() {
        return incoming.isEmpty() && outgoing.isEmpty();
    }

    /**
     * Adds "Incoming: item xN" and "Reserved for pickup: item xN" lines at {@code indents}, nothing without reservations.
     * Client only (goggle lines measure the client font).
     */
    public void addGoggleLines(List<Component> tooltip, int indents) {
        for (KeyCount<Item> entry : incoming)
            WareworksLang.reservedItems(WareworksLang.GOGGLES_RESERVED_INCOMING, entry.key(), entry.count())
                    .forGoggles(tooltip, indents);
        for (KeyCount<Item> entry : outgoing)
            WareworksLang.reservedItems(WareworksLang.GOGGLES_RESERVED_OUTGOING, entry.key(), entry.count())
                    .forGoggles(tooltip, indents);
    }

    /** Writes this summary into {@code tag} as item ids and counts; an empty direction writes nothing. Never throws. */
    public void write(CompoundTag tag) {
        if (!incoming.isEmpty())
            tag.put(INCOMING, writeEntries(incoming));
        if (!outgoing.isEmpty())
            tag.put(OUTGOING, writeEntries(outgoing));
    }

    /** Reads a summary written by {@link #write}. Never throws; reads at most {@value #MAX_ENTRIES} entries per direction. */
    public static LocationReservationSummary read(CompoundTag tag) {
        return new LocationReservationSummary(readEntries(tag.getList(INCOMING, Tag.TAG_COMPOUND)),
                readEntries(tag.getList(OUTGOING, Tag.TAG_COMPOUND)));
    }

    private static ListTag writeEntries(List<KeyCount<Item>> entries) {
        ListTag list = new ListTag();
        for (KeyCount<Item> entry : entries) {
            if (entry.key() == Items.AIR)
                continue; // an unregistered item maps to air, which would read back as a wrong item
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString(ITEM, ItemTypeSummaries.itemId(entry.key()));
            entryTag.putLong(COUNT, entry.count());
            list.add(entryTag);
        }
        return list;
    }

    private static List<KeyCount<Item>> readEntries(ListTag list) {
        List<KeyCount<Item>> entries = new ArrayList<>(MAX_ENTRIES);
        for (int i = 0; i < list.size() && entries.size() < MAX_ENTRIES; i++) {
            CompoundTag entryTag = list.getCompound(i);
            long count = entryTag.getLong(COUNT);
            if (count <= 0)
                continue;
            ItemTypeSummaries.itemById(entryTag.getString(ITEM)).ifPresent(item -> entries.add(new KeyCount<>(item, count)));
        }
        return entries;
    }

    private static List<KeyCount<Item>> largestFirst(Map<Item, Long> totals) {
        List<KeyCount<Item>> entries = new ArrayList<>(totals.size());
        totals.forEach((item, count) -> entries.add(new KeyCount<>(item, count)));
        // List.sort is stable: equal amounts keep the reservation order.
        entries.sort(Comparator.comparingLong((KeyCount<Item> entry) -> entry.count()).reversed());
        return entries;
    }

    private static List<KeyCount<Item>> bounded(List<KeyCount<Item>> entries) {
        if (entries == null || entries.isEmpty())
            return List.of();
        return List.copyOf(entries.subList(0, Math.min(MAX_ENTRIES, entries.size())));
    }

    private static long saturatedSum(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
