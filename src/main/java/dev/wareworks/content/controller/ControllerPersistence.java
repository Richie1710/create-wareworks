package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.warehouse.AisleMembership;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * NBT form of a warehouse controller's persistent state ({@code docs/warehouse-system.md} §3.3):
 * <pre>
 * Layout:     { Facing: "east", Length: int, Height: int }            (absent without dock)
 * Locations:  [ { X: int, Y: int, Side: "L"|"R", Kind: "STORAGE"|"INPUT"|"OUTPUT",
 *                 Stock: [ { Item: &lt;ItemKey&gt;, Count: long } ] } ]    (Stock only for STORAGE)
 * Misaligned: int[] (x, y, side ordinal) per position
 * Requests:   [ { Id: UUID, Item: &lt;ItemKey&gt;, Requested: int, Remaining: int,
 *                 Destination: int[3] (offset from the controller) } ]        (queue order)
 * </pre>
 * Items are stored as count-less {@link ItemKey}s next to a {@code long} count, so {@code ItemStack.save} is never
 * called with a count above 99 or with an empty stack. Request destinations are stored relative to the controller, so
 * they stay right when a structure is moved without rotation. Writing never throws (an entry that fails is skipped and
 * logged); reading never throws and skips invalid entries.
 */
final class ControllerPersistence {
    static final String LAYOUT_TAG = "Layout";
    static final String LOCATIONS_TAG = "Locations";
    static final String MISALIGNED_TAG = "Misaligned";
    static final String REQUESTS_TAG = "Requests";
    static final String PRODUCTION_ORDERS_TAG = "ProductionOrders";
    private static final String ID = "Id";
    private static final String STATION = "Station";
    private static final String RESULT = "Result";
    private static final String RESULT_AMOUNT = "ResultAmount";
    private static final String STATE = "State";
    private static final String PRODUCED = "Produced";
    private static final String STOCK_SEEN = "StockSeen";
    private static final String PROMISED = "Promised";
    private static final String LINES = "Lines";
    private static final String REQUIRED = "Required";
    private static final String DELIVERED = "Delivered";
    private static final String REQUEST = "Request";
    /** Bound on the production orders one save may contain, whatever {@code maxProductionOrders} allows. */
    private static final int MAX_SAVED_ORDERS = 256;
    private static final String REQUESTED = "Requested";
    private static final String REMAINING = "Remaining";
    private static final String DESTINATION = "Destination";
    /** Ints of a destination offset: dx, dy, dz. */
    private static final int OFFSET_INTS = 3;
    private static final String FACING = "Facing";
    private static final String LENGTH = "Length";
    private static final String HEIGHT = "Height";
    private static final String X = "X";
    private static final String Y = "Y";
    private static final String SIDE = "Side";
    private static final String KIND = "Kind";
    private static final String STOCK = "Stock";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";
    /** Ints per misaligned position in the flat array: x, y, side ordinal. */
    private static final int INTS_PER_POSITION = 3;
    private static final Side[] SIDES = Side.values();

    /** Saved aisle direction and size; the dock position is always in front of the controller. */
    record SavedLayout(Direction facing, AisleGeometry geometry) {
    }

    /** Saved membership and per-location stock counts (positive counts only). */
    record SavedLocations(List<LocationRecord> records, List<RackPosition> misaligned,
                          Map<RackPosition, Map<ItemKey, Long>> stock) {
    }

    private ControllerPersistence() {
    }

    static void writeLayout(CompoundTag tag, @Nullable AisleLayout layout) {
        if (layout == null)
            return;
        CompoundTag layoutTag = new CompoundTag();
        layoutTag.putString(FACING, layout.facing().getSerializedName());
        layoutTag.putInt(LENGTH, layout.geometry().length());
        layoutTag.putInt(HEIGHT, layout.geometry().height());
        tag.put(LAYOUT_TAG, layoutTag);
    }

    static Optional<SavedLayout> readLayout(CompoundTag tag) {
        if (!tag.contains(LAYOUT_TAG, Tag.TAG_COMPOUND))
            return Optional.empty();
        CompoundTag layoutTag = tag.getCompound(LAYOUT_TAG);
        Direction facing = Direction.byName(layoutTag.getString(FACING));
        if (facing == null || !facing.getAxis().isHorizontal())
            return Optional.empty();
        int length = Mth.clamp(layoutTag.getInt(LENGTH), 0, AisleGeometry.MAX_LENGTH);
        int height = Mth.clamp(layoutTag.getInt(HEIGHT), AisleGeometry.MIN_HEIGHT, AisleGeometry.MAX_HEIGHT);
        return Optional.of(new SavedLayout(facing, AisleGeometry.of(length, height)));
    }

    static void writeLocations(CompoundTag tag, AisleMembership membership, StockView<ItemKey, RackPosition> stock,
                               HolderLookup.Provider registries) {
        ListTag locations = new ListTag();
        for (LocationRecord record : membership.records()) {
            try {
                locations.add(writeRecord(record, stock, registries));
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save warehouse location {}", record, e);
            }
        }
        tag.put(LOCATIONS_TAG, locations);

        List<RackPosition> misaligned = List.copyOf(membership.misalignedPositions());
        int[] packed = new int[misaligned.size() * INTS_PER_POSITION];
        for (int i = 0; i < misaligned.size(); i++) {
            RackPosition position = misaligned.get(i);
            packed[i * INTS_PER_POSITION] = position.x();
            packed[i * INTS_PER_POSITION + 1] = position.y();
            packed[i * INTS_PER_POSITION + 2] = position.side().ordinal();
        }
        tag.putIntArray(MISALIGNED_TAG, packed);
    }

    private static CompoundTag writeRecord(LocationRecord record, StockView<ItemKey, RackPosition> stock,
                                           HolderLookup.Provider registries) {
        CompoundTag entry = new CompoundTag();
        entry.putInt(X, record.x());
        entry.putInt(Y, record.y());
        entry.putString(SIDE, String.valueOf(record.side().letter()));
        entry.putString(KIND, record.kind().name());
        if (record.kind() != LocationKind.STORAGE)
            return entry;
        ListTag items = new ListTag();
        for (Map.Entry<ItemKey, Long> count : stock.countsAt(record.position()).entrySet()) {
            if (count.getValue() <= 0)
                continue;
            Tag keyTag = count.getKey().save(registries);
            if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                continue; // unencodable key (already logged by ItemKey); the next snapshot counts it again
            CompoundTag item = new CompoundTag();
            item.put(ITEM, keyTag);
            item.putLong(COUNT, count.getValue());
            items.add(item);
        }
        entry.put(STOCK, items);
        return entry;
    }

    static SavedLocations readLocations(CompoundTag tag, HolderLookup.Provider registries) {
        List<LocationRecord> records = new ArrayList<>();
        Map<RackPosition, Map<ItemKey, Long>> stock = new HashMap<>();
        ListTag list = tag.getList(LOCATIONS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                Optional<LocationRecord> record = readRecord(entry);
                if (record.isEmpty())
                    continue;
                records.add(record.get());
                if (record.get().kind() == LocationKind.STORAGE)
                    stock.put(record.get().position(), readStock(entry.getList(STOCK, Tag.TAG_COMPOUND), registries));
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping unreadable warehouse location {}", entry, e);
            }
        }

        List<RackPosition> misaligned = new ArrayList<>();
        int[] packed = tag.getIntArray(MISALIGNED_TAG);
        for (int i = 0; i + INTS_PER_POSITION <= packed.length; i += INTS_PER_POSITION) {
            int sideOrdinal = packed[i + 2];
            if (sideOrdinal >= 0 && sideOrdinal < SIDES.length)
                rackPosition(packed[i], packed[i + 1], SIDES[sideOrdinal]).ifPresent(misaligned::add);
        }
        return new SavedLocations(records, misaligned, stock);
    }

    static void writeRequests(CompoundTag tag, List<RetrievalRequest<ItemKey, BlockPos>> requests, BlockPos controller,
                              HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (RetrievalRequest<ItemKey, BlockPos> request : requests) {
            try {
                Tag keyTag = request.key().save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                    continue; // unencodable key (already logged by ItemKey); the request is lost, no items are
                CompoundTag entry = new CompoundTag();
                entry.putUUID(ID, request.id());
                entry.put(ITEM, keyTag);
                entry.putInt(REQUESTED, request.requested());
                entry.putInt(REMAINING, request.remaining());
                BlockPos offset = request.destination().subtract(controller);
                entry.putIntArray(DESTINATION, new int[] {offset.getX(), offset.getY(), offset.getZ()});
                list.add(entry);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save retrieval request {}", request, e);
            }
        }
        tag.put(REQUESTS_TAG, list);
    }

    /** Requests in saved order; entries without id, item, destination or remaining amount are skipped. */
    static List<RetrievalRequest<ItemKey, BlockPos>> readRequests(CompoundTag tag, BlockPos controller,
                                                                  HolderLookup.Provider registries) {
        List<RetrievalRequest<ItemKey, BlockPos>> requests = new ArrayList<>();
        ListTag list = tag.getList(REQUESTS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                int requested = entry.getInt(REQUESTED);
                int remaining = Math.min(entry.getInt(REMAINING), requested);
                int[] offset = entry.getIntArray(DESTINATION);
                if (!entry.hasUUID(ID) || requested < 1 || remaining < 1 || offset.length != OFFSET_INTS)
                    continue;
                Optional<ItemKey> key = ItemKey.load(registries, entry.get(ITEM));
                if (key.isEmpty())
                    continue;
                requests.add(new RetrievalRequest<>(entry.getUUID(ID), key.get(), requested, remaining,
                        controller.offset(offset[0], offset[1], offset[2])));
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping unreadable retrieval request {}", entry, e);
            }
        }
        return requests;
    }

    /**
     * NBT form of the production orders ({@code docs/warehouse-system.md} §3.5):
     * <pre>
     * ProductionOrders: [ { Id: UUID, Station: {X, Y, Side}, Result: &lt;ItemKey&gt;, ResultAmount: int,
     *                       State: "WAITING_FOR_RESULT", Produced: long, StockSeen: long, Request?: UUID,
     *                       Promised: long,
     *                       Lines: [ { Id: UUID, Item: &lt;ItemKey&gt;, Required: int, Delivered: int } ] } ]
     * </pre>
     * <b>The deadline is deliberately not saved.</b> A world that was closed for an hour would otherwise time out
     * every order the moment it loads; a restored order starts its timeout over instead
     * ({@code ProductionOrders#restartDeadlines}). Writing never throws (an order that fails is skipped and logged).
     */
    static void writeProductionOrders(CompoundTag tag, List<ProductionOrder<ItemKey, RackPosition>> orders,
                                      HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            try {
                Tag resultTag = order.result().save(registries);
                if (resultTag instanceof CompoundTag compound && compound.isEmpty())
                    continue; // unencodable key (already logged by ItemKey); the order is lost, no items are
                ListTag lines = new ListTag();
                boolean complete = true;
                for (SupplyLine<ItemKey> line : order.lines()) {
                    Tag keyTag = line.key().save(registries);
                    if (keyTag instanceof CompoundTag compound && compound.isEmpty()) {
                        complete = false;
                        break;
                    }
                    CompoundTag lineTag = new CompoundTag();
                    lineTag.putUUID(ID, line.id());
                    lineTag.put(ITEM, keyTag);
                    lineTag.putInt(REQUIRED, line.required());
                    lineTag.putInt(DELIVERED, line.delivered());
                    lines.add(lineTag);
                }
                if (!complete || lines.isEmpty())
                    continue;
                CompoundTag entry = new CompoundTag();
                entry.putUUID(ID, order.id());
                entry.put(STATION, writeRack(order.station()));
                entry.put(RESULT, resultTag);
                entry.putInt(RESULT_AMOUNT, order.resultAmount());
                entry.putString(STATE, order.state().name());
                entry.putLong(PRODUCED, order.produced());
                entry.putLong(STOCK_SEEN, order.resultStockSeen());
                entry.putLong(PROMISED, order.promisedToRequest());
                order.backingRequest().ifPresent(id -> entry.putUUID(REQUEST, id));
                entry.put(LINES, lines);
                list.add(entry);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save production order {}", order.id(), e);
            }
        }
        tag.put(PRODUCTION_ORDERS_TAG, list);
    }

    /**
     * Production orders in saved order. Reading never throws and skips invalid entries; the deadline of every restored
     * order is 0 and is set by the controller on its next tick ({@link #writeProductionOrders}).
     */
    static List<ProductionOrder<ItemKey, RackPosition>> readProductionOrders(CompoundTag tag,
                                                                             HolderLookup.Provider registries) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = new ArrayList<>();
        ListTag list = tag.getList(PRODUCTION_ORDERS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && orders.size() < MAX_SAVED_ORDERS; i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                readProductionOrder(entry, registries).ifPresent(orders::add);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping unreadable production order {}", entry, e);
            }
        }
        return orders;
    }

    private static Optional<ProductionOrder<ItemKey, RackPosition>> readProductionOrder(CompoundTag entry,
                                                                                        HolderLookup.Provider registries) {
        int resultAmount = entry.getInt(RESULT_AMOUNT);
        Optional<RackPosition> station = readRack(entry.getCompound(STATION));
        Optional<ItemKey> result = ItemKey.load(registries, entry.get(RESULT));
        if (!entry.hasUUID(ID) || station.isEmpty() || result.isEmpty() || resultAmount < 1)
            return Optional.empty();
        List<SupplyLine<ItemKey>> lines = new ArrayList<>();
        ListTag lineTags = entry.getList(LINES, Tag.TAG_COMPOUND);
        for (int i = 0; i < lineTags.size() && lines.size() < ProductionPattern.MAX_INGREDIENTS; i++) {
            CompoundTag lineTag = lineTags.getCompound(i);
            int required = lineTag.getInt(REQUIRED);
            Optional<ItemKey> key = ItemKey.load(registries, lineTag.get(ITEM));
            if (!lineTag.hasUUID(ID) || key.isEmpty() || required < 1)
                continue;
            int delivered = Mth.clamp(lineTag.getInt(DELIVERED), 0, required);
            lines.add(new SupplyLine<>(lineTag.getUUID(ID), key.get(), required, delivered));
        }
        if (lines.isEmpty())
            return Optional.empty(); // an order without ingredients could never finish and promises nothing
        ProductionOrderState state = ProductionOrderState.byName(entry.getString(STATE))
                .orElse(ProductionOrderState.WAITING_FOR_INGREDIENTS);
        Optional<UUID> request = entry.hasUUID(REQUEST) ? Optional.of(entry.getUUID(REQUEST)) : Optional.empty();
        // A save written before the promise was recorded has no entry. Such an order gave its request the whole run
        // back, so reading the full result amount is what keeps an old save behaving exactly as it did; every order
        // written from now on carries the real promise (ProductionOrder#promisedToRequest).
        long promised = entry.contains(PROMISED, Tag.TAG_LONG) ? entry.getLong(PROMISED) : resultAmount;
        return Optional.of(new ProductionOrder<>(entry.getUUID(ID), station.get(), result.get(), resultAmount, lines,
                state, 0L, Math.max(0L, entry.getLong(PRODUCED)), Math.max(0L, entry.getLong(STOCK_SEEN)), request,
                promised));
    }

    /** NBT form of a rack position: {@code {X: int, Y: int, Side: "L"|"R"}}. */
    private static CompoundTag writeRack(RackPosition rack) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(X, rack.x());
        tag.putInt(Y, rack.y());
        tag.putString(SIDE, String.valueOf(rack.side().letter()));
        return tag;
    }

    /** Reads a rack position written by {@link #writeRack}; empty unless it lies within the address limits. */
    private static Optional<RackPosition> readRack(CompoundTag tag) {
        if (!tag.contains(X, Tag.TAG_INT) || !tag.contains(Y, Tag.TAG_INT))
            return Optional.empty();
        String sideText = tag.getString(SIDE);
        Optional<Side> side = sideText.length() == 1 ? Side.fromLetter(sideText.charAt(0)) : Optional.empty();
        return side.flatMap(value -> rackPosition(tag.getInt(X), tag.getInt(Y), value));
    }

    private static Optional<LocationRecord> readRecord(CompoundTag entry) {
        if (!entry.contains(X, Tag.TAG_INT) || !entry.contains(Y, Tag.TAG_INT))
            return Optional.empty();
        String sideText = entry.getString(SIDE);
        Optional<Side> side = sideText.length() == 1 ? Side.fromLetter(sideText.charAt(0)) : Optional.empty();
        Optional<LocationKind> kind = LocationKind.byName(entry.getString(KIND));
        if (side.isEmpty() || kind.isEmpty())
            return Optional.empty();
        return rackPosition(entry.getInt(X), entry.getInt(Y), side.get())
                .map(position -> new LocationRecord(position, kind.get()));
    }

    private static Optional<RackPosition> rackPosition(int x, int y, Side side) {
        if (x < 0 || x > AisleGeometry.MAX_LENGTH || y < 0 || y >= AisleGeometry.MAX_HEIGHT)
            return Optional.empty();
        return Optional.of(new RackPosition(x, y, side));
    }

    private static Map<ItemKey, Long> readStock(ListTag items, HolderLookup.Provider registries) {
        Map<ItemKey, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag item = items.getCompound(i);
            long count = item.getLong(COUNT);
            if (count <= 0)
                continue;
            ItemKey.load(registries, item.get(ITEM))
                    .ifPresent(key -> counts.merge(key, count, ControllerPersistence::saturatedAdd));
        }
        return counts;
    }

    /** Sum of two positive counts, capped at {@link Long#MAX_VALUE}. */
    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
