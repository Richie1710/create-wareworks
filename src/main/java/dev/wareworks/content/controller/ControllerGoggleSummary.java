package dev.wareworks.content.controller;

import java.util.Optional;

import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.core.job.NoJobReason;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * What the goggle tooltip of a warehouse controller shows, as synced to clients: link status, aisle size, member counts,
 * stock totals, open retrieval requests, the linked crane's status and job, and the last planning result
 * ({@code docs/warehouse-system.md} §3.3).
 * <p>
 * Numbers, a status name, a bounded {@link CraneGoggleInfo} (enum names, item ids, rack positions) and a reason name, so
 * the synced tag has a bounded size whatever the warehouse holds (the update tag is part of every chunk packet;
 * {@code docs/architecture.md}, goggle data notes). Records compare by value, so the server only syncs when something
 * visible changed. Negative values are clamped to 0 and reading never throws.
 *
 * @param status            link status
 * @param aisleLength       number of rails (0 without dock)
 * @param mastHeight        reachable levels (0 without dock)
 * @param storageLocations  aligned warehouse interfaces
 * @param filteredLocations storage locations that carry a store filter (M8, ADR-021); a count only, so the tag stays
 *                          bounded, and the filters themselves are read from the interfaces
 * @param prioritisedLocations storage locations that carry a storage priority (M16, ADR-028); a count only, for the same
 *                          reason — a priority is per location, so only the interfaces can name them
 * @param inputs            aligned warehouse inputs
 * @param outputs           aligned warehouse outputs
 * @param acceptingPorts    warehouse ports of this aisle that <b>accept</b> items instead of requesting them
 *                          (M17, issue #12, ADR-029); a count only, like the store filters, because the rank and
 *                          the redstone behaviour live on the ports themselves. Left out of the synced tag while
 *                          it is 0, which is every aisle before M17
 * @param collectingPorts   warehouse ports of this aisle that <b>collect</b> items out of a machine (M18, issue #13,
 *                          ADR-030); a count only, for the same reason, and left out of the synced tag while it is 0
 * @param productionStations aligned warehouse production stations (M11, ADR-024)
 * @param misaligned        members at rack positions with the wrong facing
 * @param itemTypes         distinct item keys in stock
 * @param totalItems        stored items over all locations
 * @param openRequests      open retrieval requests
 * @param productionOrders  open production orders (M11, ADR-024)
 * @param stockRules        rules of the aisle's warehouse stock keepers that really govern an item (M15, issue #3); a
 *                          count only, like the store filters, so the tag stays bounded and the rules themselves are
 *                          read where they are configured
 * @param rulesBelowMinimum governing rules whose item the warehouse is short of
 * @param rulesAtMaximum    governing rules that stop their item from being stored, so an input holding it backs up on
 *                          purpose — the number that turns "why is my belt jammed" into a line a player can read
 * @param rulesPaused       rules the safety stop is holding (M15 part 2, issue #3): an automatic restock order of
 *                          theirs ended with ingredients already in a machine and nothing coming back, so the
 *                          warehouse stopped ordering for them and waits for the player
 * @param chunkKeepReason   whether this aisle is holding its chunks loaded right now and why, or why it is not
 *                          (M19, issue #10, ADR-031). {@link ChunkKeepReason#NONE} means "nothing to report", which is
 *                          every aisle while the feature is off, and is left out of the synced tag entirely
 * @param chunkKeepChunks   the chunk count the line above is about: how many chunks are held while
 *                          {@link ChunkKeepReason#isHolding()}, and how many the aisle's footprint would need while it
 *                          is refused. A single number rather than two, so the tag stays as bounded as the record's
 *                          contract requires
 * @param network           the rail network the warehouse is made of, when it has anything beyond a clean straight
 *                          aisle to say about it (M21, issue #1, ADR-033): more than one aisle, or a discovery that
 *                          stopped short. Empty — and absent from the synced tag — for every warehouse that never
 *                          bends, which is every warehouse built before M21 ({@link NetworkGoggleInfo})
 * @param crane             the linked crane's goggle data (empty without a loaded, linked dock)
 * @param lastPlanReason    why the last planning run created no job (empty if it created one or never ran)
 */
public record ControllerGoggleSummary(ControllerStatus status, int aisleLength, int mastHeight, int storageLocations,
                                      int filteredLocations, int prioritisedLocations, int inputs, int outputs,
                                      int acceptingPorts, int collectingPorts,
                                      int productionStations,
                                      int misaligned, int itemTypes, long totalItems, int openRequests,
                                      int productionOrders, int stockRules, int rulesBelowMinimum, int rulesAtMaximum,
                                      int rulesPaused, ChunkKeepReason chunkKeepReason, int chunkKeepChunks,
                                      Optional<NetworkGoggleInfo> network,
                                      Optional<CraneGoggleInfo> crane,
                                      Optional<NoJobReason> lastPlanReason) {
    public static final ControllerGoggleSummary NONE =
            counts(ControllerStatus.NO_DOCK, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0, 0, 0, 0, 0, 0);

    private static final String STATUS = "Status";
    private static final String LENGTH = "Length";
    private static final String HEIGHT = "Height";
    private static final String STORAGE = "Storage";
    private static final String FILTERED = "Filtered";
    private static final String PRIORITISED = "Prioritised";
    private static final String INPUTS = "Inputs";
    private static final String OUTPUTS = "Outputs";
    private static final String ACCEPTING_PORTS = "AcceptingPorts";
    private static final String COLLECTING_PORTS = "CollectingPorts";
    private static final String PRODUCTION_STATIONS = "Production";
    private static final String PRODUCTION_ORDERS = "Orders";
    private static final String STOCK_RULES = "StockRules";
    private static final String RULES_BELOW_MINIMUM = "RulesBelowMinimum";
    private static final String RULES_AT_MAXIMUM = "RulesAtMaximum";
    private static final String RULES_PAUSED = "RulesPaused";
    private static final String CHUNK_KEEP = "ChunkKeep";
    private static final String CHUNK_KEEP_CHUNKS = "ChunkKeepChunks";
    private static final String MISALIGNED = "Misaligned";
    private static final String ITEM_TYPES = "ItemTypes";
    private static final String TOTAL_ITEMS = "TotalItems";
    private static final String OPEN_REQUESTS = "OpenRequests";
    private static final String NETWORK = "Network";
    private static final String CRANE = "Crane";
    private static final String LAST_PLAN = "LastPlan";

    public ControllerGoggleSummary {
        if (status == null)
            status = ControllerStatus.NO_DOCK;
        aisleLength = Math.max(0, aisleLength);
        mastHeight = Math.max(0, mastHeight);
        storageLocations = Math.max(0, storageLocations);
        filteredLocations = Math.max(0, filteredLocations);
        prioritisedLocations = Math.max(0, prioritisedLocations);
        inputs = Math.max(0, inputs);
        outputs = Math.max(0, outputs);
        acceptingPorts = Math.max(0, acceptingPorts);
        collectingPorts = Math.max(0, collectingPorts);
        productionStations = Math.max(0, productionStations);
        misaligned = Math.max(0, misaligned);
        itemTypes = Math.max(0, itemTypes);
        totalItems = Math.max(0L, totalItems);
        openRequests = Math.max(0, openRequests);
        productionOrders = Math.max(0, productionOrders);
        stockRules = Math.max(0, stockRules);
        rulesBelowMinimum = Math.max(0, rulesBelowMinimum);
        rulesAtMaximum = Math.max(0, rulesAtMaximum);
        rulesPaused = Math.max(0, rulesPaused);
        if (chunkKeepReason == null)
            chunkKeepReason = ChunkKeepReason.NONE;
        chunkKeepChunks = Math.max(0, chunkKeepChunks);
        if (network == null)
            network = Optional.empty();
        if (crane == null)
            crane = Optional.empty();
        if (lastPlanReason == null)
            lastPlanReason = Optional.empty();
    }

    /**
     * A summary of the counts only (no crane data, no planning result).
     * <p>
     * A named factory rather than a shorter constructor overload (M8 review fix): the record carries eight consecutive
     * {@code int} counts, so two constructors differing only in arity let a caller that adds one argument silently
     * shift every count after it — the kind of mistake that compiles and shows only as a wrong goggle line. There is
     * exactly one way to build a summary of counts, and it names every one of them.
     */
    public static ControllerGoggleSummary counts(ControllerStatus status, int aisleLength, int mastHeight,
                                                 int storageLocations, int filteredLocations, int prioritisedLocations,
                                                 int inputs, int outputs, int acceptingPorts, int collectingPorts,
                                                 int productionStations, int misaligned,
                                                 int itemTypes, long totalItems, int openRequests, int productionOrders,
                                                 int stockRules, int rulesBelowMinimum, int rulesAtMaximum,
                                                 int rulesPaused) {
        return new ControllerGoggleSummary(status, aisleLength, mastHeight, storageLocations, filteredLocations,
                prioritisedLocations, inputs, outputs, acceptingPorts, collectingPorts, productionStations, misaligned,
                itemTypes,
                totalItems, openRequests, productionOrders, stockRules, rulesBelowMinimum, rulesAtMaximum, rulesPaused,
                ChunkKeepReason.NONE, 0, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * This summary without crane data and planning result (the counts only, filtered locations included). The chunk
     * loading state is kept: it is a property of the aisle, not of the crane, and it reads {@link ChunkKeepReason#NONE}
     * for every aisle while the feature is off. The network is kept for the same reason — it is the shape of the rails,
     * not of the machine — and is empty for every warehouse that does not bend.
     */
    public ControllerGoggleSummary withoutCrane() {
        return new ControllerGoggleSummary(status, aisleLength, mastHeight, storageLocations, filteredLocations,
                prioritisedLocations, inputs, outputs, acceptingPorts, collectingPorts, productionStations, misaligned,
                itemTypes, totalItems, openRequests, productionOrders, stockRules, rulesBelowMinimum, rulesAtMaximum,
                rulesPaused, chunkKeepReason, chunkKeepChunks, network, Optional.empty(), Optional.empty());
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putString(STATUS, status.name());
        tag.putInt(LENGTH, aisleLength);
        tag.putInt(HEIGHT, mastHeight);
        tag.putInt(STORAGE, storageLocations);
        tag.putInt(FILTERED, filteredLocations);
        // Left out while nothing is prioritised, which is every aisle before M16, and a missing key reads back as 0 —
        // the same rule the production and stock-rule numbers follow.
        if (prioritisedLocations > 0)
            tag.putInt(PRIORITISED, prioritisedLocations);
        tag.putInt(INPUTS, inputs);
        tag.putInt(OUTPUTS, outputs);
        // Left out while no port of this aisle accepts anything, which is every aisle before M17, and a missing
        // key reads back as 0 — the same rule the storage priorities and the production numbers follow.
        if (acceptingPorts > 0)
            tag.putInt(ACCEPTING_PORTS, acceptingPorts);
        // And the same for the collecting ports (M18, issue #13): every aisle before M18 adds nothing at all.
        if (collectingPorts > 0)
            tag.putInt(COLLECTING_PORTS, collectingPorts);
        tag.putInt(MISALIGNED, misaligned);
        tag.putInt(ITEM_TYPES, itemTypes);
        tag.putLong(TOTAL_ITEMS, totalItems);
        tag.putInt(OPEN_REQUESTS, openRequests);
        // The two production numbers are left out while an aisle has no production station at all, which is almost
        // every aisle. This tag is part of every chunk packet (§3.1.1), so a number that is always 0 should not travel
        // with it — the same rule the store filter follows, and {@link #read} reads a missing key back as 0.
        if (productionStations > 0)
            tag.putInt(PRODUCTION_STATIONS, productionStations);
        if (productionOrders > 0)
            tag.putInt(PRODUCTION_ORDERS, productionOrders);
        // The same rule for the stock keepers' numbers: an aisle without rules — which is every aisle before M15 —
        // adds nothing at all to the chunk packet, and a missing key reads back as 0.
        if (stockRules > 0)
            tag.putInt(STOCK_RULES, stockRules);
        if (rulesBelowMinimum > 0)
            tag.putInt(RULES_BELOW_MINIMUM, rulesBelowMinimum);
        if (rulesAtMaximum > 0)
            tag.putInt(RULES_AT_MAXIMUM, rulesAtMaximum);
        if (rulesPaused > 0)
            tag.putInt(RULES_PAUSED, rulesPaused);
        // Left out entirely while there is nothing to report, which is every aisle on a server that has chunk loading
        // switched off - the default (M19, issue #10). A missing key reads back as NONE / 0.
        if (chunkKeepReason != ChunkKeepReason.NONE) {
            tag.putString(CHUNK_KEEP, chunkKeepReason.name());
            tag.putInt(CHUNK_KEEP_CHUNKS, chunkKeepChunks);
        }
        // Left out entirely for a warehouse of one aisle whose rails simply end (M21, issue #1): that is every
        // warehouse built before M21, and its packet must not grow by a single byte for a feature it does not use.
        network.ifPresent(info -> {
            CompoundTag networkTag = new CompoundTag();
            info.write(networkTag);
            tag.put(NETWORK, networkTag);
        });
        crane.ifPresent(info -> {
            CompoundTag craneTag = new CompoundTag();
            info.write(craneTag);
            tag.put(CRANE, craneTag);
        });
        lastPlanReason.ifPresent(reason -> tag.putString(LAST_PLAN, reason.name()));
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid values read as 0 / NO_DOCK / empty. */
    public static ControllerGoggleSummary read(CompoundTag tag) {
        Optional<CraneGoggleInfo> crane = tag.contains(CRANE, Tag.TAG_COMPOUND)
                ? Optional.of(CraneGoggleInfo.read(tag.getCompound(CRANE))) : Optional.empty();
        Optional<NetworkGoggleInfo> network = NetworkGoggleInfo.isPresent(tag, NETWORK)
                ? Optional.of(NetworkGoggleInfo.read(tag.getCompound(NETWORK))) : Optional.empty();
        return new ControllerGoggleSummary(ControllerStatus.byName(tag.getString(STATUS)).orElse(ControllerStatus.NO_DOCK),
                tag.getInt(LENGTH), tag.getInt(HEIGHT), tag.getInt(STORAGE), tag.getInt(FILTERED),
                tag.getInt(PRIORITISED), tag.getInt(INPUTS), tag.getInt(OUTPUTS), tag.getInt(ACCEPTING_PORTS),
                tag.getInt(COLLECTING_PORTS),
                tag.getInt(PRODUCTION_STATIONS),
                tag.getInt(MISALIGNED), tag.getInt(ITEM_TYPES),
                tag.getLong(TOTAL_ITEMS), tag.getInt(OPEN_REQUESTS), tag.getInt(PRODUCTION_ORDERS),
                tag.getInt(STOCK_RULES), tag.getInt(RULES_BELOW_MINIMUM), tag.getInt(RULES_AT_MAXIMUM),
                tag.getInt(RULES_PAUSED), ChunkKeepReason.byName(tag.getString(CHUNK_KEEP)),
                tag.getInt(CHUNK_KEEP_CHUNKS), network, crane, reasonByName(tag.getString(LAST_PLAN)));
    }

    private static Optional<NoJobReason> reasonByName(String name) {
        for (NoJobReason reason : NoJobReason.values()) {
            if (reason.name().equals(name))
                return Optional.of(reason);
        }
        return Optional.empty();
    }
}
