package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.stock.StockRules;
import dev.wareworks.core.warehouse.AisleMembership;
import dev.wareworks.core.warehouse.AisleNames;
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
 * Layout:     { Facing: "east", Length: int, Height: int }            (absent without dock; the branch at the dock)
 * Network:    { Branches: [ { D: int[2] origin offset from the dock, H: "south", L: int, C: "B" } ],
 *               Lines:    [ { A: 0|1, F: int, D: int[2], C: "B" } ] }  (absent while the warehouse is one aisle)
 * Names:      [ { C: "A", N: "Ores" } ]                                (absent while no aisle has a name, M25)
 * Locations:  [ { X: int, Y: int, Side: "L"|"R", Kind: "STORAGE"|"INPUT"|"OUTPUT",
 *                 Stock: [ { Item: &lt;ItemKey&gt;, Count: long } ] } ]    (Stock only for STORAGE)
 * Misaligned: int[] (x, y, side ordinal) per position
 * Requests:   [ { Id: UUID, Item: &lt;ItemKey&gt;, Requested: int, Remaining: int,
 *                 Destination: int[3] (offset from the controller) } ]        (queue order)
 * StockRules: [ { X: int, Y: int, Side: "L"|"R",
 *                 Rules: [ { Item: &lt;ItemKey&gt;, Min: long, Max: long, Reserve: long } ] } ]  (keeper order)
 * ChunkKeep:  { GaveUp: true, Work: long }                              (absent unless the aisle gave up, M19)
 * </pre>
 * The stock rules are the controller's own copy of what the aisle's warehouse stock keepers hold. They are saved
 * <b>here</b> rather than only in the keepers, because a rule gates item movement in both directions and a keeper's
 * chunk can be unloaded while the controller plans: after a load the copy is there before the first tick
 * ({@link AisleStockRules}).
 * Items are stored as count-less {@link ItemKey}s next to a {@code long} count, so {@code ItemStack.save} is never
 * called with a count above 99 or with an empty stack. Request destinations are stored relative to the controller, so
 * they stay right when a structure is moved without rotation. Writing never throws (an entry that fails is skipped and
 * logged); reading never throws and skips invalid entries.
 */
final class ControllerPersistence {
    static final String LAYOUT_TAG = "Layout";
    /**
     * The rail network beyond the branch at the dock (M21, ADR-033), <b>written only while the warehouse really has
     * more than one aisle</b>. Everything a warehouse of one aisle is stays in {@link #LAYOUT_TAG} exactly as it was,
     * so a world saved on 0.5.0 loads, and saves again, byte for byte.
     * <p>
     * Origins are offsets from the <b>dock</b>, which is always the block in front of the controller, so they are
     * relative like the request destinations are and a structure moved without rotation keeps its addresses. The
     * links where two aisles meet are <b>derived</b> from the branch list and never saved, so a save can never
     * contradict the rails that are there.
     */
    static final String NETWORK_TAG = "Network";
    /**
     * The names a player has given this warehouse's aisles (M25, issue #15, ADR-038), keyed by aisle letter and
     * written only while at least one aisle has one:
     * <pre>
     * Names: [ { C: "A", N: "Ores" }, { C: "B", N: "Metals" } ]
     * </pre>
     * <b>Top level, deliberately not inside {@link #NETWORK_TAG}.</b> {@link #writeNetwork} returns having written
     * nothing at all while the warehouse is one straight aisle with no pinned line, which is every warehouse built
     * before M21 — and those are exactly the warehouses a name is most useful on. Keeping the names in their own tag
     * lets a one-aisle warehouse carry one, and it leaves the "a straight warehouse has no {@code Network} tag"
     * property of that method untouched.
     */
    static final String NAMES_TAG = "Names";
    static final String LOCATIONS_TAG = "Locations";
    static final String MISALIGNED_TAG = "Misaligned";
    static final String REQUESTS_TAG = "Requests";
    static final String PRODUCTION_ORDERS_TAG = "ProductionOrders";
    static final String STOCK_RULES_TAG = "StockRules";
    static final String STOCK_PAUSES_TAG = "StockPauses";
    static final String CHUNK_KEEP_TAG = "ChunkKeep";
    private static final String GAVE_UP = "GaveUp";
    private static final String WORK = "Work";
    private static final String CAUSE = "Cause";
    private static final String UNRECOVERED = "Unrecovered";
    private static final String RULES = "Rules";
    private static final String MINIMUM = "Min";
    private static final String MAXIMUM = "Max";
    private static final String RESERVE = "Reserve";
    /** Bound on keepers one save may list, whatever the aisle geometry allows. */
    private static final int MAX_SAVED_KEEPERS = 256;
    private static final String ID = "Id";
    private static final String STATION = "Station";
    private static final String RESULT = "Result";
    private static final String RESULT_AMOUNT = "ResultAmount";
    private static final String STATE = "State";
    private static final String PRODUCED = "Produced";
    private static final String STOCK_SEEN = "StockSeen";
    private static final String PROMISED = "Promised";
    private static final String LINE_LIST = "Lines";
    private static final String REQUIRED = "Required";
    private static final String DELIVERED = "Delivered";
    private static final String REQUEST = "Request";
    /** Marks an order the warehouse started by itself to refill a stock rule (M15 part 2). */
    private static final String RESTOCK = "Restock";
    /**
     * The ingredient line of another order this one is a step for (M20): the only thing a production plan is stored as
     * ({@code ProductionOrder#parentLine}).
     */
    private static final String PARENT = "Parent";
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
    /**
     * Branch of a rack position, <b>written only when it is not</b> {@link RackPosition#FIRST_BRANCH} (M21, ADR-033),
     * so a warehouse with one aisle — every warehouse up to 0.5.0 — saves exactly the bytes it did before the field
     * existed, and an absent {@code B} reads as branch 0.
     */
    private static final String BRANCH = "B";
    private static final String KIND = "Kind";
    private static final String STOCK = "Stock";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";
    /** Ints per misaligned position in the flat array: x, y, side ordinal. */
    private static final int INTS_PER_POSITION = 3;
    /**
     * Branches of the misaligned positions, one int per entry of {@link #MISALIGNED_TAG} and in the same order. A
     * <b>parallel</b> array rather than a fourth int per entry, and written only when at least one branch is not
     * {@link RackPosition#FIRST_BRANCH}: that keeps {@link #MISALIGNED_TAG} itself byte-identical on a one-aisle
     * warehouse, and an array of the wrong length is ignored rather than shifting every position by one.
     */
    private static final String MISALIGNED_BRANCHES_TAG = "MisalignedBranches";
    private static final Side[] SIDES = Side.values();

    private static final String BRANCHES = "Branches";
    private static final String LINES = "Lines";
    private static final String ORIGIN = "D";
    private static final String HEADING = "H";
    private static final String BRANCH_LENGTH = "L";
    private static final String LETTER = "C";
    /** The name of one aisle inside {@link #NAMES_TAG}; the letter it belongs to is written as {@link #LETTER}. */
    private static final String NAME = "N";
    /**
     * Entries of {@link #NAMES_TAG} one read walks. A warehouse has at most {@value StorageAddress#AISLE_COUNT} aisle
     * letters, so a longer list is not one this mod wrote, and the rest of it is ignored rather than walked.
     */
    private static final int MAX_SAVED_NAMES = StorageAddress.AISLE_COUNT;
    private static final String LINE_AXIS = "A";
    private static final String LINE_FIXED = "F";
    /** Ints of a branch origin offset: dx, dz (every branch lies at the dock's own level). */
    private static final int ORIGIN_INTS = 2;

    /** Saved aisle direction and size; the dock position is always in front of the controller. */
    record SavedLayout(Direction facing, AisleGeometry geometry) {
    }

    /**
     * The saved shape of a warehouse beyond its first aisle: the further branches in index order (the first one is
     * {@link SavedLayout}) and the lines the aisle letters are pinned to ({@link BranchTable}).
     */
    record SavedNetwork(List<BranchGeometry> branches, List<Optional<Character>> letters,
                        List<Map.Entry<Long, BranchTable.Entry>> lines) {
        static final SavedNetwork NONE = new SavedNetwork(List.of(), List.of(), List.of());

        SavedNetwork {
            branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
            letters = List.copyOf(Objects.requireNonNull(letters, "letters"));
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }

        boolean isEmpty() {
            return branches.isEmpty() && lines.isEmpty();
        }
    }

    /** Saved membership and per-location stock counts (positive counts only). */
    record SavedLocations(List<LocationRecord> records, List<RackPosition> misaligned,
                          Map<RackPosition, Map<ItemKey, Long>> stock) {
    }

    /**
     * The chunk-hold give-up flag and the fingerprint of the work it refuses (M19, §11.4). The <b>one</b> thing the
     * chunk-loading feature saves: the hold itself is derived state and the tickets are NeoForge's own saved data, but
     * the bound "this aisle may not hold again until its work really changes" has to outlive the block entity instance —
     * the work it refuses is saved too, so a flag that did not would let every reload take the whole footprint again for
     * work that had already proved unservable (M19 review).
     */
    record SavedChunkKeep(boolean gaveUp, long workFingerprint) {
        static final SavedChunkKeep NONE = new SavedChunkKeep(false, 0L);
    }

    private ControllerPersistence() {
    }

    /**
     * Writes the branch at the dock, in exactly the three fields a warehouse has always written: its direction, the
     * number of rails of its first aisle and the mast height. A warehouse that bends writes the rest into
     * {@link #NETWORK_TAG}, so this tag means the same thing it always did and an older version reading a newer save
     * still finds the straight aisle.
     */
    static void writeLayout(CompoundTag tag, @Nullable WarehouseLayout layout) {
        if (layout == null)
            return;
        CompoundTag layoutTag = new CompoundTag();
        layoutTag.putString(FACING, layout.facing().getSerializedName());
        layoutTag.putInt(LENGTH, layout.geometry().length());
        layoutTag.putInt(HEIGHT, layout.geometry().height());
        tag.put(LAYOUT_TAG, layoutTag);
    }

    /**
     * Writes the branches beyond the first one and the pinned lines — and <b>nothing at all</b> while the warehouse is
     * a single straight aisle with no line pinned, which is every warehouse built up to 0.5.0.
     */
    static void writeNetwork(CompoundTag tag, @Nullable WarehouseLayout layout, BranchTable table) {
        if (layout == null)
            return;
        List<Map.Entry<Long, BranchTable.Entry>> lines = table.entries();
        if (layout.branchCount() <= 1 && lines.isEmpty())
            return;
        ListTag branches = new ListTag();
        for (int i = RackPosition.FIRST_BRANCH + 1; i < layout.branchCount(); i++) {
            BranchGeometry branch = layout.network().branch(i);
            CompoundTag entry = new CompoundTag();
            entry.putIntArray(ORIGIN, new int[] { branch.originDx(), branch.originDz() });
            entry.putString(HEADING, branch.heading().name());
            entry.putInt(BRANCH_LENGTH, branch.length());
            layout.branch(i).letter().ifPresent(letter -> entry.putString(LETTER, String.valueOf(letter)));
            branches.add(entry);
        }
        ListTag lineList = new ListTag();
        for (Map.Entry<Long, BranchTable.Entry> line : lines) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(LINE_AXIS, (int) (line.getKey() >> 32));
            entry.putInt(LINE_FIXED, (int) (long) line.getKey());
            entry.putIntArray(ORIGIN, new int[] { line.getValue().originDx(), line.getValue().originDz() });
            entry.putString(LETTER, String.valueOf(line.getValue().letter()));
            lineList.add(entry);
        }
        CompoundTag network = new CompoundTag();
        network.put(BRANCHES, branches);
        network.put(LINE_LIST, lineList);
        tag.put(NETWORK_TAG, network);
    }

    /**
     * Reads the further branches and the pinned lines; an absent, malformed or over-long entry is skipped, so a
     * warehouse always ends up with a valid network — at worst the single aisle {@link #readLayout} describes.
     */
    static SavedNetwork readNetwork(CompoundTag tag) {
        if (!tag.contains(NETWORK_TAG, Tag.TAG_COMPOUND))
            return SavedNetwork.NONE;
        CompoundTag network = tag.getCompound(NETWORK_TAG);
        List<BranchGeometry> branches = new ArrayList<>();
        List<Optional<Character>> letters = new ArrayList<>();
        letters.add(Optional.empty()); // the branch at the dock takes the controller's own value box
        ListTag list = network.getList(BRANCHES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            if (branches.size() + 1 >= StorageAddress.AISLE_COUNT)
                break;
            CompoundTag entry = list.getCompound(i);
            int[] origin = entry.getIntArray(ORIGIN);
            Heading heading = headingOf(entry.getString(HEADING));
            int length = entry.getInt(BRANCH_LENGTH);
            if (origin.length != ORIGIN_INTS || heading == null || length < 0 || length > AisleGeometry.MAX_LENGTH) {
                Wareworks.LOGGER.warn("Skipping an unreadable warehouse aisle in a saved network");
                break; // the branches after it would carry the wrong indices, so the network stops here
            }
            branches.add(new BranchGeometry(branches.size() + 1, origin[0], origin[1], heading, length));
            letters.add(letterOf(entry.getString(LETTER)));
        }
        List<Map.Entry<Long, BranchTable.Entry>> lines = new ArrayList<>();
        ListTag lineList = network.getList(LINE_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < lineList.size() && lines.size() < BranchTable.MAX_ENTRIES; i++) {
            CompoundTag entry = lineList.getCompound(i);
            int[] origin = entry.getIntArray(ORIGIN);
            Optional<Character> letter = letterOf(entry.getString(LETTER));
            int axis = entry.getInt(LINE_AXIS);
            if (origin.length != ORIGIN_INTS || letter.isEmpty() || axis < 0 || axis > 1)
                continue;
            long key = ((long) axis << 32) | (entry.getInt(LINE_FIXED) & 0xFFFFFFFFL);
            lines.add(Map.entry(key, new BranchTable.Entry(letter.get(), origin[0], origin[1])));
        }
        return new SavedNetwork(branches, letters.subList(0, branches.size() + 1), lines);
    }

    @Nullable
    private static Heading headingOf(String name) {
        for (Heading heading : Heading.values()) {
            if (heading.name().equals(name))
                return heading;
        }
        return null;
    }

    private static Optional<Character> letterOf(String text) {
        if (text.length() != 1 || !StorageAddress.isValidAisle(text.charAt(0)))
            return Optional.empty();
        return Optional.of(text.charAt(0));
    }

    /**
     * Writes the aisle names, and <b>nothing at all</b> while no aisle of this warehouse has one — which is every
     * warehouse up to 0.7.0, so such a save stays byte for byte what it was (see {@link #NAMES_TAG}).
     * <p>
     * The names come out of {@link AisleNames#entries()}, which is already bounded to the aisle letters, in letter
     * order and re-sanitised, so there is nothing left to check here and the bytes of two equal name tables are equal.
     */
    static void writeNames(CompoundTag tag, AisleNames names) {
        if (names.isEmpty())
            return;
        ListTag list = new ListTag();
        names.entries().forEach((letter, name) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString(LETTER, String.valueOf(letter));
            entry.putString(NAME, name);
            list.add(entry);
        });
        tag.put(NAMES_TAG, list);
    }

    /**
     * Reads the aisle names; an absent, malformed or over-long entry is <b>skipped</b>, exactly as in
     * {@link #readNetwork}, because the input is a save, a command or a hand-edited world and one unusable entry must
     * not cost the readable ones.
     * <p>
     * The tag is only walked here: {@link AisleNames#AisleNames(Map)} does the per-entry filtering — a letter that is
     * not an aisle letter and a name that sanitises to blank are dropped there, each at the cost of itself and nothing
     * else — and the table is bounded by the {@value StorageAddress#AISLE_COUNT} aisle letters whatever the list
     * claims, so a crafted tag cannot widen it.
     * <p>
     * The walk itself stops after {@value #MAX_SAVED_NAMES} entries, because a warehouse has no more aisle letters
     * than that and a longer list is therefore not one this mod wrote. A letter listed twice inside that window keeps
     * the last of its entries; nothing this mod writes ever lists one twice.
     */
    static AisleNames readNames(CompoundTag tag) {
        if (!tag.contains(NAMES_TAG, Tag.TAG_LIST))
            return new AisleNames();
        ListTag list = tag.getList(NAMES_TAG, Tag.TAG_COMPOUND);
        Map<Character, String> names = new LinkedHashMap<>();
        int entries = Math.min(list.size(), MAX_SAVED_NAMES);
        for (int i = 0; i < entries; i++) {
            CompoundTag entry = list.getCompound(i);
            letterOf(entry.getString(LETTER)).ifPresent(letter -> names.put(letter, entry.getString(NAME)));
        }
        return new AisleNames(names);
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
        int[] branches = new int[misaligned.size()];
        boolean anyBranch = false;
        for (int i = 0; i < misaligned.size(); i++) {
            RackPosition position = misaligned.get(i);
            packed[i * INTS_PER_POSITION] = position.x();
            packed[i * INTS_PER_POSITION + 1] = position.y();
            packed[i * INTS_PER_POSITION + 2] = position.side().ordinal();
            branches[i] = position.branch();
            anyBranch |= position.branch() != RackPosition.FIRST_BRANCH;
        }
        tag.putIntArray(MISALIGNED_TAG, packed);
        if (anyBranch)
            tag.putIntArray(MISALIGNED_BRANCHES_TAG, branches);
    }

    private static CompoundTag writeRecord(LocationRecord record, StockView<ItemKey, RackPosition> stock,
                                           HolderLookup.Provider registries) {
        CompoundTag entry = new CompoundTag();
        entry.putInt(X, record.x());
        entry.putInt(Y, record.y());
        entry.putString(SIDE, String.valueOf(record.side().letter()));
        if (record.branch() != RackPosition.FIRST_BRANCH)
            entry.putInt(BRANCH, record.branch());
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
        int[] branches = tag.getIntArray(MISALIGNED_BRANCHES_TAG);
        // A branch array that does not line up with the positions is ignored, so a truncated save loses the branches
        // rather than attaching them to the wrong positions; the next probe restores them.
        boolean useBranches = branches.length == packed.length / INTS_PER_POSITION;
        for (int i = 0; i + INTS_PER_POSITION <= packed.length; i += INTS_PER_POSITION) {
            int sideOrdinal = packed[i + 2];
            int branch = useBranches ? branches[i / INTS_PER_POSITION] : RackPosition.FIRST_BRANCH;
            if (sideOrdinal >= 0 && sideOrdinal < SIDES.length)
                rackPosition(branch, packed[i], packed[i + 1], SIDES[sideOrdinal]).ifPresent(misaligned::add);
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
     * ProductionOrders: [ { Id: UUID, Station: {X, Y, Side, B?}, Result: &lt;ItemKey&gt;, ResultAmount: int,
     *                       State: "WAITING_FOR_RESULT", Produced: long, StockSeen: long, Request?: UUID,
     *                       Promised: long, Restock?: boolean, Parent?: UUID,
     *                       Lines: [ { Id: UUID, Item: &lt;ItemKey&gt;, Required: int, Delivered: int } ] } ]
     * </pre>
     * <b>The deadline is deliberately not saved.</b> A world that was closed for an hour would otherwise time out
     * every order the moment it loads; a restored order starts its timeout over instead
     * ({@code ProductionOrders#restartDeadlines}). Writing never throws (an order that fails is skipped and logged).
     * <p>
     * <b>A production plan is saved as one optional field per order</b> (M20, ADR-032): {@code Parent} is the
     * ingredient line of the order this one is a step for, and it is written only when there is one — so an order saved
     * before M20 reads back as exactly what it was, a plain single-level order, with no migration and nothing that can
     * throw. Everything else about a plan (its root, its depth, which step is working) is derived from those links
     * when it is asked for, so no two saved numbers can disagree. A save whose links are broken — truncated at
     * {@value #MAX_SAVED_ORDERS} entries, or edited — is repaired once after the restore by
     * {@code ProductionOrders#validatePlans}, which is what guarantees that no step is ever left waiting for a parent
     * that does not exist.
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
                // Only for the automatic orders, so a save from before M15 part 2 — and every ordinary order —
                // reads back as "a request asked for this", which is what it was.
                if (order.isRestock())
                    entry.putBoolean(RESTOCK, true);
                // Only for a step of a plan, for the same reason: every order that is not one saves exactly what it
                // always saved (M20).
                order.parentLine().ifPresent(parent -> entry.putUUID(PARENT, parent));
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
        // No Parent means "not a step", which is what every save before M20 says and what every plain order says now.
        // A Parent that names no line of any restored order is not repaired here: reading stays a pure decode, and
        // ProductionOrders#validatePlans ends such a step once, after the whole list is back.
        Optional<UUID> parent = entry.hasUUID(PARENT) ? Optional.of(entry.getUUID(PARENT)) : Optional.empty();
        return Optional.of(new ProductionOrder<>(entry.getUUID(ID), station.get(), result.get(), resultAmount, lines,
                state, 0L, Math.max(0L, entry.getLong(PRODUCED)), Math.max(0L, entry.getLong(STOCK_SEEN)), request,
                promised, entry.getBoolean(RESTOCK), parent));
    }

    /**
     * Writes the controller's copy of the stock rules of its aisle's keepers, keeper by keeper in aisle order. A
     * keeper with no rule at all is left out, so an aisle without rules saves one empty list. Never throws.
     */
    static void writeStockRules(CompoundTag tag, Map<RackPosition, List<StockRule<ItemKey>>> perKeeper,
                                HolderLookup.Provider registries) {
        ListTag keepers = new ListTag();
        for (Map.Entry<RackPosition, List<StockRule<ItemKey>>> entry : perKeeper.entrySet()) {
            if (entry.getValue().isEmpty())
                continue;
            try {
                ListTag rules = new ListTag();
                for (StockRule<ItemKey> rule : entry.getValue()) {
                    Tag keyTag = rule.key().save(registries);
                    if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                        continue; // unencodable key, already logged by ItemKey
                    CompoundTag ruleTag = new CompoundTag();
                    ruleTag.put(ITEM, keyTag);
                    ruleTag.putLong(MINIMUM, rule.minimum());
                    ruleTag.putLong(MAXIMUM, rule.maximum());
                    ruleTag.putLong(RESERVE, rule.reserve());
                    rules.add(ruleTag);
                }
                if (rules.isEmpty())
                    continue;
                CompoundTag keeperTag = writeRack(entry.getKey());
                keeperTag.put(RULES, rules);
                keepers.add(keeperTag);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save the stock rules of the keeper at {}", entry.getKey(), e);
            }
        }
        tag.put(STOCK_RULES_TAG, keepers);
    }

    /**
     * Reads the stock rules written by {@link #writeStockRules}. Never throws; an entry whose rack position or whose
     * item cannot be read is skipped, and every number is clamped by {@link StockRule} itself. Bounded by
     * {@value #MAX_SAVED_KEEPERS} keepers and {@code StockRules.MAX_RULES} rules per keeper, so crafted save data
     * cannot make the copy unbounded.
     */
    static Map<RackPosition, List<StockRule<ItemKey>>> readStockRules(CompoundTag tag,
                                                                     HolderLookup.Provider registries) {
        Map<RackPosition, List<StockRule<ItemKey>>> perKeeper = new LinkedHashMap<>();
        ListTag keepers = tag.getList(STOCK_RULES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < keepers.size() && i < MAX_SAVED_KEEPERS; i++) {
            CompoundTag keeperTag = keepers.getCompound(i);
            try {
                Optional<RackPosition> rack = readRack(keeperTag);
                if (rack.isEmpty())
                    continue;
                ListTag rules = keeperTag.getList(RULES, Tag.TAG_COMPOUND);
                List<StockRule<ItemKey>> read = new ArrayList<>(Math.min(rules.size(), StockRules.MAX_RULES));
                for (int r = 0; r < rules.size() && r < StockRules.MAX_RULES; r++) {
                    CompoundTag ruleTag = rules.getCompound(r);
                    ItemKey.load(registries, ruleTag.get(ITEM)).ifPresent(key -> read.add(new StockRule<>(key,
                            ruleTag.getLong(MINIMUM), ruleTag.getLong(MAXIMUM), ruleTag.getLong(RESERVE))));
                }
                if (!read.isEmpty())
                    perKeeper.put(rack.get(), List.copyOf(read));
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping unreadable stock rules {}", keeperTag, e);
            }
        }
        return perKeeper;
    }

    /**
     * Writes the rules the <b>safety stop</b> is holding (M15 part 2, issue #3):
     * {@code StockPauses: [ { Item: <ItemKey>, Cause: "TIMED_OUT", Unrecovered: long } ]}.
     * <p>
     * A pause is saved with the controller for the same reason the rule copy is: it is the one thing that must be
     * known <b>before</b> the first evaluation after a world load, or a restart would quietly resume ordering into a
     * machine that already swallowed a batch. Never throws; an unencodable item is skipped, and losing a pause that
     * way is the safe direction only because the very next lost batch pauses the rule again.
     */
    static void writeStockPauses(CompoundTag tag, Map<ItemKey, StockRulePause> pauses,
                                 HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<ItemKey, StockRulePause> entry : pauses.entrySet()) {
            try {
                Tag keyTag = entry.getKey().save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                    continue; // unencodable key, already logged by ItemKey
                CompoundTag pauseTag = new CompoundTag();
                pauseTag.put(ITEM, keyTag);
                pauseTag.putString(CAUSE, entry.getValue().cause().name());
                pauseTag.putLong(UNRECOVERED, entry.getValue().unrecovered());
                list.add(pauseTag);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save the stock rule pause of {}", entry.getKey(), e);
            }
        }
        tag.put(STOCK_PAUSES_TAG, list);
    }

    /**
     * Reads the pauses written by {@link #writeStockPauses}, in saved order. Never throws; an entry whose item cannot
     * be read is skipped, an unknown cause reads as {@link StockRulePause.Cause#TIMED_OUT} (a pause whose reason is
     * unreadable is still a pause), and the list is bounded by {@code StockRules.MAX_RULES}, because at most one rule
     * governs an item.
     */
    static Map<ItemKey, StockRulePause> readStockPauses(CompoundTag tag, HolderLookup.Provider registries) {
        Map<ItemKey, StockRulePause> pauses = new LinkedHashMap<>();
        ListTag list = tag.getList(STOCK_PAUSES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && pauses.size() < StockRules.MAX_RULES; i++) {
            CompoundTag pauseTag = list.getCompound(i);
            try {
                ItemKey.load(registries, pauseTag.get(ITEM)).ifPresent(key -> pauses.put(key,
                        new StockRulePause(StockRulePause.Cause.byName(pauseTag.getString(CAUSE))
                                .orElse(StockRulePause.Cause.TIMED_OUT), pauseTag.getLong(UNRECOVERED))));
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping unreadable stock rule pause {}", pauseTag, e);
            }
        }
        return pauses;
    }

    /**
     * Writes the chunk-hold give-up state, and <b>only</b> while it is set: an aisle that has not given up adds nothing
     * to the save, so the default server's controllers keep exactly the tag they had before M19.
     */
    static void writeChunkKeep(CompoundTag tag, boolean gaveUp, long workFingerprint) {
        if (!gaveUp)
            return;
        CompoundTag chunkKeep = new CompoundTag();
        chunkKeep.putBoolean(GAVE_UP, true);
        chunkKeep.putLong(WORK, workFingerprint);
        tag.put(CHUNK_KEEP_TAG, chunkKeep);
    }

    /** Reads what {@link #writeChunkKeep} wrote; {@link SavedChunkKeep#NONE} when the tag is absent. */
    static SavedChunkKeep readChunkKeep(CompoundTag tag) {
        if (!tag.contains(CHUNK_KEEP_TAG, Tag.TAG_COMPOUND))
            return SavedChunkKeep.NONE;
        CompoundTag chunkKeep = tag.getCompound(CHUNK_KEEP_TAG);
        if (!chunkKeep.getBoolean(GAVE_UP))
            return SavedChunkKeep.NONE;
        return new SavedChunkKeep(true, chunkKeep.getLong(WORK));
    }

    /**
     * NBT form of a rack position: {@code {X: int, Y: int, Side: "L"|"R", B?: int}}. {@code B} is the branch and is
     * <b>omitted when it is</b> {@link RackPosition#FIRST_BRANCH}.
     */
    private static CompoundTag writeRack(RackPosition rack) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(X, rack.x());
        tag.putInt(Y, rack.y());
        tag.putString(SIDE, String.valueOf(rack.side().letter()));
        if (rack.branch() != RackPosition.FIRST_BRANCH)
            tag.putInt(BRANCH, rack.branch());
        return tag;
    }

    /** Reads a rack position written by {@link #writeRack}; empty unless it lies within the address limits. */
    private static Optional<RackPosition> readRack(CompoundTag tag) {
        if (!tag.contains(X, Tag.TAG_INT) || !tag.contains(Y, Tag.TAG_INT))
            return Optional.empty();
        String sideText = tag.getString(SIDE);
        Optional<Side> side = sideText.length() == 1 ? Side.fromLetter(sideText.charAt(0)) : Optional.empty();
        return side.flatMap(value -> rackPosition(branchOf(tag), tag.getInt(X), tag.getInt(Y), value));
    }

    private static Optional<LocationRecord> readRecord(CompoundTag entry) {
        if (!entry.contains(X, Tag.TAG_INT) || !entry.contains(Y, Tag.TAG_INT))
            return Optional.empty();
        String sideText = entry.getString(SIDE);
        Optional<Side> side = sideText.length() == 1 ? Side.fromLetter(sideText.charAt(0)) : Optional.empty();
        Optional<LocationKind> kind = LocationKind.byName(entry.getString(KIND));
        if (side.isEmpty() || kind.isEmpty())
            return Optional.empty();
        return rackPosition(branchOf(entry), entry.getInt(X), entry.getInt(Y), side.get())
                .map(position -> new LocationRecord(position, kind.get()));
    }

    /** The saved branch of a rack position; an absent {@code B} is {@link RackPosition#FIRST_BRANCH}. */
    private static int branchOf(CompoundTag tag) {
        return tag.contains(BRANCH, Tag.TAG_INT) ? tag.getInt(BRANCH) : RackPosition.FIRST_BRANCH;
    }

    private static Optional<RackPosition> rackPosition(int branch, int x, int y, Side side) {
        if (x < 0 || x > AisleGeometry.MAX_LENGTH || y < 0 || y >= AisleGeometry.MAX_HEIGHT
                || branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH)
            return Optional.empty();
        return Optional.of(new RackPosition(branch, x, y, side));
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
