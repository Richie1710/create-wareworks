package dev.wareworks.content.station;

import java.util.Optional;

import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.inventory.InventorySummary;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;

/**
 * What the goggle tooltip of a warehouse station shows, as synced to clients ({@code docs/warehouse-system.md} §3.2):
 * its aisle assignment, a summary of the buffer by item type and, for outputs, the pending requests (remaining and
 * delivered so far) and the last rejection.
 * <p>
 * The synced size is bounded: an address string, a few numbers, a reason name and at most
 * {@link WarehouseStationBlockEntity#GOGGLE_TOP_ENTRIES} item ids ({@link ItemTypeSummaries}). Records compare by
 * value, so the server only syncs visible changes. Negative numbers are clamped to 0; reading never throws.
 *
 * @param assignment     aisle address, misaligned or not part of an aisle
 * @param buffer         buffer contents by item type
 * @param openRequests   open retrieval requests for this output (0 for inputs)
 * @param requestedItems items these requests still wait for
 * @param deliveredItems items of these requests that were already dropped into the buffer
 * @param lastRejection  why the last request of this output was refused, if it was
 * @param exportedItems  items an <b>accepting</b> warehouse port has handed over since it was built (M17, issue #12): a
 *                       single number, and the one line that tells a player whether their overflow ever did anything —
 *                       and, in the loop of risk 2, how much it is churning
 * @param portArmed      whether an <b>accepting</b> warehouse port in {@code PULSE} mode holds a rising edge nobody has
 *                       spent yet (M17). It has to travel here because the token is a server field and a goggle tooltip
 *                       is built on the client: without it the "may act now" line of an accepting pulse port would read
 *                       "waiting for a signal" for ever, which is the one combination that line exists for — the rank,
 *                       the mode and the signal all reach the client by themselves
 * @param collect        what a <b>collecting</b> warehouse port fetches from and has fetched (M18, issue #13):
 *                       {@link PortCollectSummary}, all of it server-only knowledge, and {@link PortCollectSummary#NONE}
 *                       — written as nothing at all — for every other station and every other port direction
 */
public record StationGoggleSummary(AisleAssignment assignment, InventorySummary<Item> buffer, int openRequests,
                                   long requestedItems, long deliveredItems, Optional<RequestRejection> lastRejection,
                                   long exportedItems, boolean portArmed, PortCollectSummary collect) {
    public static final StationGoggleSummary NONE = new StationGoggleSummary(AisleAssignment.NONE,
            InventorySummary.empty(), 0, 0L, 0L, Optional.empty(), 0L, false, PortCollectSummary.NONE);

    private static final String ASSIGNMENT = "Assignment";
    private static final String BUFFER = "Buffer";
    private static final String OPEN_REQUESTS = "OpenRequests";
    private static final String REQUESTED_ITEMS = "RequestedItems";
    private static final String DELIVERED_ITEMS = "DeliveredItems";
    private static final String LAST_REJECTION = "LastRejection";
    private static final String EXPORTED_ITEMS = "ExportedItems";
    private static final String PORT_ARMED = "PortArmed";
    private static final String COLLECT = "Collect";

    public StationGoggleSummary {
        if (assignment == null)
            assignment = AisleAssignment.NONE;
        if (buffer == null)
            buffer = InventorySummary.empty();
        if (lastRejection == null)
            lastRejection = Optional.empty();
        if (collect == null)
            collect = PortCollectSummary.NONE;
        openRequests = Math.max(0, openRequests);
        requestedItems = Math.max(0L, requestedItems);
        deliveredItems = Math.max(0L, deliveredItems);
        exportedItems = Math.max(0L, exportedItems);
    }

    /** This summary with other request data. */
    public StationGoggleSummary withRequests(int open, long requested, long delivered,
            Optional<RequestRejection> rejection) {
        return new StationGoggleSummary(assignment, buffer, open, requested, delivered, rejection, exportedItems,
                portArmed, collect);
    }

    /** This summary with a warehouse port's export counter and its unspent rising edge (M17). */
    public StationGoggleSummary withPort(long exported, boolean armed) {
        return new StationGoggleSummary(assignment, buffer, openRequests, requestedItems, deliveredItems, lastRejection,
                exported, armed, collect);
    }

    /** This summary with what a collecting warehouse port fetches from and has fetched (M18, issue #13). */
    public StationGoggleSummary withCollect(PortCollectSummary collect) {
        return new StationGoggleSummary(assignment, buffer, openRequests, requestedItems, deliveredItems, lastRejection,
                exportedItems, portArmed, collect);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        CompoundTag assignmentTag = new CompoundTag();
        assignment.write(assignmentTag);
        tag.put(ASSIGNMENT, assignmentTag);
        CompoundTag bufferTag = new CompoundTag();
        ItemTypeSummaries.write(bufferTag, buffer);
        tag.put(BUFFER, bufferTag);
        tag.putInt(OPEN_REQUESTS, openRequests);
        tag.putLong(REQUESTED_ITEMS, requestedItems);
        tag.putLong(DELIVERED_ITEMS, deliveredItems);
        lastRejection.ifPresent(rejection -> tag.putString(LAST_REJECTION, rejection.name()));
        // Only a port that really exported something, or that holds a rising edge, writes these, so no other station's
        // packet grows by a byte (M17).
        if (exportedItems > 0)
            tag.putLong(EXPORTED_ITEMS, exportedItems);
        if (portArmed)
            tag.putBoolean(PORT_ARMED, true);
        // Only a collecting port writes this at all, so no other station's packet grows by a byte (M18).
        if (!collect.isEmpty()) {
            CompoundTag collectTag = new CompoundTag();
            collect.write(collectTag);
            tag.put(COLLECT, collectTag);
        }
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static StationGoggleSummary read(CompoundTag tag) {
        return new StationGoggleSummary(AisleAssignment.read(tag.getCompound(ASSIGNMENT)),
                ItemTypeSummaries.read(tag.getCompound(BUFFER)), tag.getInt(OPEN_REQUESTS), tag.getLong(REQUESTED_ITEMS),
                tag.getLong(DELIVERED_ITEMS), tag.contains(LAST_REJECTION, Tag.TAG_STRING)
                        ? RequestRejection.byName(tag.getString(LAST_REJECTION)) : Optional.empty(),
                tag.getLong(EXPORTED_ITEMS), tag.getBoolean(PORT_ARMED),
                PortCollectSummary.read(tag.getCompound(COLLECT)));
    }
}
