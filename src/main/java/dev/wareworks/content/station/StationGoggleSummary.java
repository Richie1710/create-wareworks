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
 */
public record StationGoggleSummary(AisleAssignment assignment, InventorySummary<Item> buffer, int openRequests,
                                   long requestedItems, long deliveredItems, Optional<RequestRejection> lastRejection) {
    public static final StationGoggleSummary NONE = new StationGoggleSummary(AisleAssignment.NONE,
            InventorySummary.empty(), 0, 0L, 0L, Optional.empty());

    private static final String ASSIGNMENT = "Assignment";
    private static final String BUFFER = "Buffer";
    private static final String OPEN_REQUESTS = "OpenRequests";
    private static final String REQUESTED_ITEMS = "RequestedItems";
    private static final String DELIVERED_ITEMS = "DeliveredItems";
    private static final String LAST_REJECTION = "LastRejection";

    public StationGoggleSummary {
        if (assignment == null)
            assignment = AisleAssignment.NONE;
        if (buffer == null)
            buffer = InventorySummary.empty();
        if (lastRejection == null)
            lastRejection = Optional.empty();
        openRequests = Math.max(0, openRequests);
        requestedItems = Math.max(0L, requestedItems);
        deliveredItems = Math.max(0L, deliveredItems);
    }

    /** This summary with other request data. */
    public StationGoggleSummary withRequests(int open, long requested, long delivered,
            Optional<RequestRejection> rejection) {
        return new StationGoggleSummary(assignment, buffer, open, requested, delivered, rejection);
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
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static StationGoggleSummary read(CompoundTag tag) {
        return new StationGoggleSummary(AisleAssignment.read(tag.getCompound(ASSIGNMENT)),
                ItemTypeSummaries.read(tag.getCompound(BUFFER)), tag.getInt(OPEN_REQUESTS), tag.getLong(REQUESTED_ITEMS),
                tag.getLong(DELIVERED_ITEMS), tag.contains(LAST_REJECTION, Tag.TAG_STRING)
                        ? RequestRejection.byName(tag.getString(LAST_REJECTION)) : Optional.empty());
    }
}
