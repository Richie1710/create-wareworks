package dev.wareworks.config;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import dev.wareworks.core.crane.HomeReturn;
import dev.wareworks.core.job.TravelTimeModel;
import dev.wareworks.core.production.PlanLimits;
import dev.wareworks.core.stock.RestockLimits;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server configuration of Create: Wareworks: {@code <instance>/config/wareworks-server.toml}, optionally overridden per
 * world by {@code <world>/serverconfig/wareworks-server.toml} (NeoForge {@code ServerLifecycleHooks.handleServerAboutToStart}).
 * <p>
 * Keys and defaults follow {@code docs/warehouse-system.md} §9; the TOML file groups them into the sections
 * {@code aisle}, {@code crane}, {@code stations} and {@code controller}.
 * <p>
 * SERVER config values exist only while a server runs (and on connected clients). Always read them through the typed
 * getters below: they fall back to the default when the config is not loaded (registry events, datagen, main menu,
 * foreign contexts) and therefore never throw. {@code core.*} never sees this class; content code copies the values it
 * needs into plain parameters.
 */
public final class WareworksConfig {
    /**
     * Default of {@code crane.returnHomeIdleTicks}: ten seconds without work before a crane of a warehouse with more
     * than one aisle drives home (M21, ADR-034). Long enough that a warehouse working through a queue never sends its
     * crane away between two jobs, short enough that a player who walks up to their terminal finds the machine there.
     */
    public static final int DEFAULT_RETURN_HOME_IDLE_TICKS = 200;

    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        Pair<Server, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = pair.getLeft();
        SERVER_SPEC = pair.getRight();
    }

    private WareworksConfig() {
    }

    /** Registers the server config. Call once from the {@code Wareworks} constructor. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }

    public static boolean isServerConfigLoaded() {
        return SERVER_SPEC.isLoaded();
    }

    /** The configured value, or its default while the config is not loaded. Never throws. */
    public static int get(ModConfigSpec.IntValue value) {
        if (SERVER_SPEC.isLoaded()) {
            try {
                return value.get();
            } catch (IllegalStateException e) {
                // unloaded between the check and the read (server stopping): fall through to the default
            }
        }
        return value.getDefault();
    }

    /** The configured value, or its default while the config is not loaded. Never throws. */
    public static double get(ModConfigSpec.DoubleValue value) {
        if (SERVER_SPEC.isLoaded()) {
            try {
                return value.get();
            } catch (IllegalStateException e) {
                // unloaded between the check and the read (server stopping): fall through to the default
            }
        }
        return value.getDefault();
    }

    // --- aisle ---------------------------------------------------------------------------------------------------

    public static int maxAisleLength() {
        return get(SERVER.maxAisleLength);
    }

    public static int maxMastHeight() {
        return get(SERVER.maxMastHeight);
    }

    public static int geometryRefreshTicks() {
        return get(SERVER.geometryRefreshTicks);
    }

    public static int maxNetworkRails() {
        return get(SERVER.maxNetworkRails);
    }

    public static int maxBranches() {
        return get(SERVER.maxBranches);
    }

    /**
     * Aisle blocks two aisles of one warehouse may share (M22, issue #2, ADR-035). A junction is what a route search
     * is priced in — every junction costs at most two nodes and one single-source pass — so this is the key that
     * bounds the cost of planning on a warehouse that splits, and an aisle that would push the count over it is left
     * out and reported.
     */
    public static int maxJunctions() {
        return get(SERVER.maxJunctions);
    }

    // --- crane ---------------------------------------------------------------------------------------------------

    public static double stressImpact() {
        return get(SERVER.stressImpact);
    }

    public static double travelBlocksPerTickPerRpm() {
        return get(SERVER.travelBlocksPerTickPerRpm);
    }

    public static double liftBlocksPerTickPerRpm() {
        return get(SERVER.liftBlocksPerTickPerRpm);
    }

    public static double armExtendPerTickPerRpm() {
        return get(SERVER.armExtendPerTickPerRpm);
    }

    public static double maxBlocksPerTick() {
        return get(SERVER.maxBlocksPerTick);
    }

    /**
     * Blocks of travel one quarter turn at a corner costs the crane (M21, ADR-033). The turn therefore runs off the
     * same rotational source and scales with RPM exactly as driving does; 0 turns in a single tick.
     */
    public static double turnPenaltyBlocks() {
        return get(SERVER.turnPenaltyBlocks);
    }

    /**
     * Ticks a crane waits for work before it drives back to its home point, or to its dock when the warehouse has none
     * (M21, ADR-034, {@code docs/stacker-crane.md} §4.7). {@link HomeReturn#OFF} switches it off; a warehouse of one
     * straight aisle never returns whatever this says.
     */
    public static int returnHomeIdleTicks() {
        return get(SERVER.returnHomeIdleTicks);
    }

    public static int transferTicks() {
        return get(SERVER.transferTicks);
    }

    public static int grabberStacks() {
        return get(SERVER.grabberStacks);
    }

    public static int grabberMaxItems() {
        return get(SERVER.grabberMaxItems);
    }

    // --- stations ------------------------------------------------------------------------------------------------

    public static int inputBufferSlots() {
        return get(SERVER.inputBufferSlots);
    }

    public static int outputBufferSlots() {
        return get(SERVER.outputBufferSlots);
    }

    public static int terminalBufferSlots() {
        return get(SERVER.terminalBufferSlots);
    }

    public static int maxTerminalRequestAmount() {
        return get(SERVER.maxTerminalRequestAmount);
    }

    public static int maxTerminalStockEntries() {
        return get(SERVER.maxTerminalStockEntries);
    }

    /**
     * Clipboard entries one list order takes ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19). Entries beyond
     * it stay on the clipboard untouched and unticked, and the screen says how many were taken.
     */
    public static int maxTerminalListEntries() {
        return get(SERVER.maxTerminalListEntries);
    }

    /** Retrieval requests one list order keeps open at a time (M23). */
    public static int terminalListOpenRequests() {
        return get(SERVER.terminalListOpenRequests);
    }

    /** Ticks between two top-up passes of a list order (M23); a delivery makes the next one due at once. */
    public static int terminalListIntervalTicks() {
        return get(SERVER.terminalListIntervalTicks);
    }

    /** Ticks a list order may make no progress at all before it parks until a player resumes it (M23); 0 never parks. */
    public static int terminalListStallTicks() {
        return get(SERVER.terminalListStallTicks);
    }

    public static int productionBufferSlots() {
        return get(SERVER.productionBufferSlots);
    }

    public static int maxProductionPatterns() {
        return get(SERVER.maxProductionPatterns);
    }

    /** Rule rows one warehouse stock keeper offers ({@code docs/warehouse-system.md} §3.6, M15). */
    public static int stockKeeperRows() {
        return get(SERVER.stockKeeperRows);
    }

    // --- controller ----------------------------------------------------------------------------------------------

    public static int snapshotIntervalTicks() {
        return get(SERVER.snapshotIntervalTicks);
    }

    /**
     * Ticks the round robin may take to come round to every storage location once (M22). The locations read per
     * interval are scaled to meet it ({@link dev.wareworks.core.warehouse.SnapshotCadence}), so a warehouse of many
     * aisles notices a chest a player
     * emptied by hand in bounded time instead of in time proportional to its size; 0 switches the scaling off.
     */
    public static int snapshotCycleTicks() {
        return get(SERVER.snapshotCycleTicks);
    }

    public static int dispatchIntervalTicks() {
        return get(SERVER.dispatchIntervalTicks);
    }

    public static int retryTicks() {
        return get(SERVER.retryTicks);
    }

    public static int holdRetryTicks() {
        return get(SERVER.holdRetryTicks);
    }

    public static int fullBackoffTicks() {
        return get(SERVER.fullBackoffTicks);
    }

    public static int maxOpenRequests() {
        return get(SERVER.maxOpenRequests);
    }

    public static int maxOpenRequestsPerOutput() {
        return get(SERVER.maxOpenRequestsPerOutput);
    }

    public static int maxSnapshotsPerTick() {
        return get(SERVER.maxSnapshotsPerTick);
    }

    /**
     * How often a controller re-reads the inventory behind a gated-open <b>collecting</b> warehouse port without being
     * told to ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13).
     * <p>
     * It covers the machines that change their inventory without firing a neighbour-change hint (a furnace's result slot,
     * several Create blocks); a hint always overtakes it, and the reads share the {@link #maxSnapshotsPerTick()} budget.
     */
    public static int collectPollIntervalTicks() {
        return get(SERVER.collectPollIntervalTicks);
    }

    public static int maxProductionOrders() {
        return get(SERVER.maxProductionOrders);
    }

    public static int productionOrderTimeoutTicks() {
        return get(SERVER.productionOrderTimeoutTicks);
    }

    /**
     * Production orders one click may create at once, the ordered item's own included (M20, issue #4): the bound on how
     * long a chain may be. <b>1 switches recursion off completely</b> and the warehouse orders exactly as it did before
     * M20.
     */
    public static int maxProductionPlanSteps() {
        return get(SERVER.maxProductionPlanSteps);
    }

    /**
     * The largest number of ingredient items one production plan may hand to machines, over every one of its steps
     * (M20, issue #4) — the number that really bounds what one click can lose, the way
     * {@link #maxRestockIngredientItems()} bounds an automatic order.
     */
    public static int maxPlanIngredientItems() {
        return get(SERVER.maxPlanIngredientItems);
    }

    /** The bounds one production plan obeys, as one value for the planner (M20, issue #4). */
    public static PlanLimits planLimits() {
        return new PlanLimits(maxProductionPlanSteps(), maxPlanIngredientItems());
    }

    /** How many stock rules one aisle applies at most; the rules beyond it are inert (M15). */
    public static int maxStockRules() {
        return get(SERVER.maxStockRules);
    }

    /** How often a controller judges its stock rules against the current stock (M15). */
    public static int stockRuleIntervalTicks() {
        return get(SERVER.stockRuleIntervalTicks);
    }

    /** Automatic restock orders one aisle may run at once; 0 switches automatic restocking off (M15 part 2). */
    public static int maxRestockOrders() {
        return get(SERVER.maxRestockOrders);
    }

    /** Automatic restock orders one stock rule may have open at once (M15 part 2). */
    public static int maxRestockOrdersPerRule() {
        return get(SERVER.maxRestockOrdersPerRule);
    }

    /** The largest amount of the product one automatic restock order may ask for (M15 part 2). */
    public static int maxRestockOrderAmount() {
        return get(SERVER.maxRestockOrderAmount);
    }

    /**
     * The largest number of ingredient items one automatic restock order may spend (M15 part 2) — the bound that
     * really limits what a broken machine can swallow before the safety stop fires.
     */
    public static int maxRestockIngredientItems() {
        return get(SERVER.maxRestockIngredientItems);
    }

    /** The bounds automatic restocking obeys, as one value for the planner (M15 part 2). */
    public static RestockLimits restockLimits() {
        return new RestockLimits(maxRestockOrdersPerRule(), maxRestockOrders(), maxRestockOrderAmount(),
                maxRestockIngredientItems());
    }

    // --- chunkLoading --------------------------------------------------------------------------------------------

    /**
     * How many aisles of one dimension may hold their own chunks loaded while they have work (M19, issue #10, ADR-031).
     * <p>
     * <b>0 switches the whole feature off, and that is the default</b> — the same "0 means off" shape
     * {@link #maxRestockOrders()} uses, so one integer is both the master switch and the bound.
     */
    public static int maxTicketedAislesPerLevel() {
        return get(SERVER.maxTicketedAislesPerLevel);
    }

    /** Whether chunk loading is switched on at all ({@link #maxTicketedAislesPerLevel()} above 0). */
    public static boolean chunkLoadingEnabled() {
        return maxTicketedAislesPerLevel() > 0;
    }

    /**
     * How many chunks one <b>warehouse</b> may hold over all its aisles together; a warehouse that needs more holds
     * <b>nothing</b> (never a partial hold) and names the number it would need (M19, ADR-031; ADR-033 for the network).
     * <p>
     * The key keeps its M19 name on purpose: renaming a config key silently resets every server that had set it.
     */
    public static int maxChunksPerAisle() {
        return get(SERVER.maxChunksPerAisle);
    }

    /** How long a holding aisle lingers after its last work before it lets go (the anti-thrash linger). */
    public static int chunkReleaseDelayTicks() {
        return get(SERVER.releaseDelayTicks);
    }

    /** The longest single uninterrupted hold; 0 is unlimited (and then a stuck aisle is a permanent chunk loader). */
    public static int maxChunkHoldTicks() {
        return get(SERVER.maxHoldTicks);
    }

    /**
     * How many aisles of one dimension may hold their chunks <b>only</b> because a collecting port has something pending
     * (M19, issue #10). 0 switches that opt-in off, which is the default; these aisles also count against
     * {@link #maxTicketedAislesPerLevel()}, so the smaller of the two wins.
     */
    public static int maxCollectHoldAislesPerLevel() {
        return get(SERVER.maxCollectHoldAislesPerLevel);
    }

    /** The config values. Read them through the static getters of {@link WareworksConfig}. */
    public static final class Server {
        public final ModConfigSpec.IntValue maxAisleLength;
        public final ModConfigSpec.IntValue maxMastHeight;
        public final ModConfigSpec.IntValue geometryRefreshTicks;
        public final ModConfigSpec.IntValue maxNetworkRails;
        public final ModConfigSpec.IntValue maxBranches;
        public final ModConfigSpec.IntValue maxJunctions;

        public final ModConfigSpec.DoubleValue stressImpact;
        public final ModConfigSpec.DoubleValue travelBlocksPerTickPerRpm;
        public final ModConfigSpec.DoubleValue liftBlocksPerTickPerRpm;
        public final ModConfigSpec.DoubleValue armExtendPerTickPerRpm;
        public final ModConfigSpec.DoubleValue maxBlocksPerTick;
        public final ModConfigSpec.DoubleValue turnPenaltyBlocks;
        public final ModConfigSpec.IntValue returnHomeIdleTicks;
        public final ModConfigSpec.IntValue transferTicks;
        public final ModConfigSpec.IntValue grabberStacks;
        public final ModConfigSpec.IntValue grabberMaxItems;

        public final ModConfigSpec.IntValue inputBufferSlots;
        public final ModConfigSpec.IntValue outputBufferSlots;
        public final ModConfigSpec.IntValue terminalBufferSlots;
        public final ModConfigSpec.IntValue maxTerminalRequestAmount;
        public final ModConfigSpec.IntValue maxTerminalStockEntries;
        public final ModConfigSpec.IntValue maxTerminalListEntries;
        public final ModConfigSpec.IntValue terminalListOpenRequests;
        public final ModConfigSpec.IntValue terminalListIntervalTicks;
        public final ModConfigSpec.IntValue terminalListStallTicks;
        public final ModConfigSpec.IntValue productionBufferSlots;
        public final ModConfigSpec.IntValue maxProductionPatterns;
        public final ModConfigSpec.IntValue stockKeeperRows;

        public final ModConfigSpec.IntValue snapshotIntervalTicks;
        public final ModConfigSpec.IntValue snapshotCycleTicks;
        public final ModConfigSpec.IntValue dispatchIntervalTicks;
        public final ModConfigSpec.IntValue retryTicks;
        public final ModConfigSpec.IntValue holdRetryTicks;
        public final ModConfigSpec.IntValue fullBackoffTicks;
        public final ModConfigSpec.IntValue maxOpenRequests;
        public final ModConfigSpec.IntValue maxOpenRequestsPerOutput;
        public final ModConfigSpec.IntValue maxSnapshotsPerTick;
        public final ModConfigSpec.IntValue collectPollIntervalTicks;
        public final ModConfigSpec.IntValue maxProductionOrders;
        public final ModConfigSpec.IntValue productionOrderTimeoutTicks;
        public final ModConfigSpec.IntValue maxProductionPlanSteps;
        public final ModConfigSpec.IntValue maxPlanIngredientItems;
        public final ModConfigSpec.IntValue maxStockRules;
        public final ModConfigSpec.IntValue stockRuleIntervalTicks;
        public final ModConfigSpec.IntValue maxRestockOrders;
        public final ModConfigSpec.IntValue maxRestockOrdersPerRule;
        public final ModConfigSpec.IntValue maxRestockOrderAmount;
        public final ModConfigSpec.IntValue maxRestockIngredientItems;

        public final ModConfigSpec.IntValue maxTicketedAislesPerLevel;
        public final ModConfigSpec.IntValue maxChunksPerAisle;
        public final ModConfigSpec.IntValue releaseDelayTicks;
        public final ModConfigSpec.IntValue maxHoldTicks;
        public final ModConfigSpec.IntValue maxCollectHoldAislesPerLevel;

        Server(ModConfigSpec.Builder builder) {
            builder.comment("Aisle geometry").push("aisle");
            maxAisleLength = builder
                    .comment("Maximum number of warehouse rails in one straight aisle of a warehouse.",
                            "Clients draw the moving crane only while the dock's chunk is within their render distance,",
                            "so a player at the far end of a long aisle needs a render distance that reaches the dock.")
                    .defineInRange("maxAisleLength", 32, 1, 128);
            maxMastHeight = builder
                    .comment("Maximum mast height of a stacker crane, in blocks (reachable levels).")
                    .defineInRange("maxMastHeight", 16, 1, 64);
            geometryRefreshTicks = builder
                    .comment("[in Ticks] How often a stacker crane re-counts the rails of its aisle.")
                    .defineInRange("geometryRefreshTicks", 40, 1, 1200);
            maxNetworkRails = builder
                    .comment("Maximum number of warehouse rails in one connected warehouse, over all of its aisles.",
                            "Rails that touch connect, so this is what bounds the cost of one discovery run.",
                            "Rails beyond it are not part of the warehouse; the dock reports where it stopped.")
                    .defineInRange("maxNetworkRails", 256, 16, 1024);
            maxBranches = builder
                    .comment("Maximum number of straight aisles in one connected warehouse.",
                            "The ceiling is 26 because every aisle needs an address letter A-Z.",
                            "Set it to 1 to keep every warehouse the single straight aisle it was up to 0.5.0-alpha:",
                            "discovery then follows the dock's facing and reads nothing beside it, so no rail next to",
                            "an aisle can join it or shorten it. A rail laid across the aisle line still connects",
                            "whichever way it is turned - closing it with a wrench is what keeps a rail out.")
                    .defineInRange("maxBranches", 16, 1, 26);
            maxJunctions = builder
                    .comment("Maximum number of junctions in one connected warehouse: aisle blocks that two aisles "
                            + "share, i.e. every corner, tee and crossing.",
                            "Junctions are the only places a crane can change aisle, so this is what the route search "
                                    + "costs: at the default 32 a fully explored warehouse is about 260 000 integer "
                                    + "operations, and at the ceiling of 128 about 17 million - paid once per change "
                                    + "to the rails and spread over every planning pass until the next one.",
                            "An aisle that would push the count over it is not part of the warehouse; the dock reports "
                                    + "where it stopped. Set it to 0 to keep every warehouse the single aisle at its "
                                    + "dock without switching the rest of this version off.")
                    .defineInRange("maxJunctions", 32, 0, 128);
            builder.pop();

            builder.comment("Stacker crane").push("crane");
            stressImpact = builder
                    .comment("[in Stress Units] Stress impact of the stacker crane per RPM.")
                    .defineInRange("stressImpact", 4.0, 0.0, 1024.0);
            travelBlocksPerTickPerRpm = builder
                    .comment("[in Blocks per Tick per RPM] Travel speed along the aisle (X axis). Default 1/384.")
                    .defineInRange("travelBlocksPerTickPerRpm", 1.0 / 384.0, 0.0, 1.0);
            liftBlocksPerTickPerRpm = builder
                    .comment("[in Blocks per Tick per RPM] Lift speed of the carriage (Y axis). Default 1/512.")
                    .defineInRange("liftBlocksPerTickPerRpm", 1.0 / 512.0, 0.0, 1.0);
            armExtendPerTickPerRpm = builder
                    .comment("[in Extensions per Tick per RPM] Arm speed as a fraction of the full extension. Default 1/192.")
                    .defineInRange("armExtendPerTickPerRpm", 1.0 / 192.0, 0.0, 1.0);
            maxBlocksPerTick = builder
                    .comment("[in Blocks per Tick] Hard speed cap for each crane axis.")
                    .defineInRange("maxBlocksPerTick", 1.0, 0.01, 4.0);
            turnPenaltyBlocks = builder
                    .comment("[in Blocks] What one quarter turn at a corner costs the crane, measured in blocks of "
                            + "travel.",
                            "The turn runs off the same shaft as driving, so it also scales with RPM: at the default "
                                    + "travel speed a turn takes 3 ticks at 128 RPM and 12 at 32 RPM.",
                            "The job planner counts turns with this number too, so a rack round a corner ranks behind "
                                    + "an equally distant one on the aisle the crane is already on.",
                            "0 is a turn in a single tick.")
                    .defineInRange("turnPenaltyBlocks", TravelTimeModel.DEFAULT_TURN_PENALTY_BLOCKS,
                            TravelTimeModel.MIN_TURN_PENALTY_BLOCKS, TravelTimeModel.MAX_TURN_PENALTY_BLOCKS);
            returnHomeIdleTicks = builder
                    .comment("[in Ticks] How long a stacker crane waits for work before it drives back to its home "
                            + "point - or, without one, to its dock.",
                            "Only a warehouse with MORE THAN ONE aisle sends its crane home: on a single straight "
                                    + "aisle the crane stays exactly where its last job left it, which is what it "
                                    + "always did.",
                            "The trip home is no job: it is interrupted by the next real job in the tick that job "
                                    + "arrives, and it never holds a chunk loaded.",
                            "0 switches returning home off everywhere.")
                    .defineInRange("returnHomeIdleTicks", DEFAULT_RETURN_HOME_IDLE_TICKS, HomeReturn.OFF, 72000);
            transferTicks = builder
                    .comment("[in Ticks] Duration of one pick or drop.")
                    .defineInRange("transferTicks", 10, 1, 200);
            grabberStacks = builder
                    .comment("Number of stacks the grabber carries per trip.")
                    .defineInRange("grabberStacks", 1, 1, 27);
            grabberMaxItems = builder
                    .comment("Absolute item cap of the grabber per trip.")
                    .defineInRange("grabberMaxItems", 64, 1, 1728);
            builder.pop();

            builder.comment("Warehouse input and output stations").push("stations");
            inputBufferSlots = builder
                    .comment("Buffer slots of a warehouse input. Applies to newly placed or reloaded stations.")
                    .worldRestart()
                    .defineInRange("inputBufferSlots", 9, 1, 27);
            outputBufferSlots = builder
                    .comment("Buffer slots of a warehouse output. Applies to newly placed or reloaded stations.")
                    .worldRestart()
                    .defineInRange("outputBufferSlots", 9, 1, 27);
            terminalBufferSlots = builder
                    .comment("Buffer slots of a warehouse terminal. Applies to newly placed or reloaded stations.",
                            "More than 12 slots make the terminal screen taller; it takes those rows from its item "
                                    + "grid (which scrolls), so the window still fits every GUI scale.")
                    .worldRestart()
                    .defineInRange("terminalBufferSlots", 9, 1, 27);
            maxTerminalRequestAmount = builder
                    .comment("Maximum number of items one warehouse terminal request may be waiting for at once.",
                            "Repeated clicks for the same item grow that one request up to this bound, and delivered "
                                    + "items free room again, so one request can deliver more than this over its life.",
                            "The controller clamps it further to the stock that is not promised to another request.")
                    .defineInRange("maxTerminalRequestAmount", 1024, 1, 65536);
            maxTerminalStockEntries = builder
                    .comment("Maximum number of item types a warehouse terminal reports in one stock snapshot,",
                            "so that a huge warehouse cannot produce an unbounded list for the screen.",
                            "This bounds the list, not the work: the terminal still reads its aisle's item types once "
                                    + "per refresh. The types with the most items are reported, and the screen says "
                                    + "how many it was not told about.")
                    .defineInRange("maxTerminalStockEntries", 512, 16, 4096);
            maxTerminalListEntries = builder
                    .comment("Entries one clipboard order in a warehouse terminal takes from the clipboard.",
                            "A Schematicannon's material checklist is usually far shorter than this. Entries beyond "
                                    + "the bound stay on the clipboard untouched and unticked, and the terminal says "
                                    + "how many it took.")
                    .defineInRange("maxTerminalListEntries", 128, 1, 1024);
            terminalListOpenRequests = builder
                    .comment("Retrieval requests one clipboard order keeps open at a time.",
                            "A list is worked off in portions: this is how many of its entries the crane may be "
                                    + "fetching at once. They are ordinary requests, so maxOpenRequests and "
                                    + "maxOpenRequestsPerOutput still bound them.")
                    .defineInRange("terminalListOpenRequests", 2, 1, 16);
            terminalListIntervalTicks = builder
                    .comment("Ticks between two top-up passes of a clipboard order.",
                            "A delivery makes the next pass due at once, so freed buffer space continues the list "
                                    + "without waiting for this interval; it only bounds how often an order that "
                                    + "found nothing measures again.")
                    .defineInRange("terminalListIntervalTicks", 20, 1, 1200);
            terminalListStallTicks = builder
                    .comment("Ticks a clipboard order may make no progress at all before it stops measuring until a "
                            + "player resumes it at the terminal.",
                            "An order that is waiting for items already on their way, or for a full buffer to be "
                                    + "emptied, is making progress and never parks. Set to 0 to let an order keep "
                                    + "trying for ever.")
                    .defineInRange("terminalListStallTicks", 1200, 0, 432000);
            productionBufferSlots = builder
                    .comment("Buffer slots of a warehouse production station. Applies to newly placed or reloaded "
                            + "stations.",
                            "The crane delivers a production order's ingredients here and your own funnel, chute or "
                                    + "belt takes them into the machine.")
                    .worldRestart()
                    .defineInRange("productionBufferSlots", 9, 1, 27);
            maxProductionPatterns = builder
                    .comment("Pattern slots of a warehouse production station: how many different things one station "
                            + "can be told to make.",
                            "A pattern is a 3x3 grid of ingredient slots plus one result, like a crafting recipe. "
                                    + "Wareworks never crafts it; it only delivers the ingredients here and waits for "
                                    + "the result to come back through a warehouse input.",
                            "Applies to stations placed from now on. A station that already holds more patterns keeps "
                                    + "them all, so lowering this never loses a pattern.")
                    .worldRestart()
                    .defineInRange("maxProductionPatterns", 4, 1, 8);
            stockKeeperRows = builder
                    .comment("Rule rows of a warehouse stock keeper: how many different items one keeper governs.",
                            "A row is one item plus a minimum, a maximum and a reserve; an aisle may hold several "
                                    + "keepers, and their rows are applied in the order the blocks stand in the aisle.",
                            "Applies to keepers placed from now on. A keeper that already holds more rows keeps them "
                                    + "all, so lowering this never loses a rule and never switches one off; use "
                                    + "maxStockRules to limit how many rules an aisle applies.",
                            "The screen shows six rows without scrolling, so the default costs no scrollbar.")
                    .worldRestart()
                    .defineInRange("stockKeeperRows", 6, 1, 16);
            builder.pop();

            builder.comment("Warehouse controller").push("controller");
            snapshotIntervalTicks = builder
                    .comment("[in Ticks] Round-robin reconciliation: how often the controller re-reads storage "
                            + "locations it has heard nothing about.")
                    .defineInRange("snapshotIntervalTicks", 10, 1, 1200);
            snapshotCycleTicks = builder
                    .comment("[in Ticks] How long the round robin above may take to come round to every storage "
                            + "location once.",
                            "The number of locations read per interval is scaled to meet it, so the CYCLE time is "
                                    + "bounded by the warehouse size instead of the other way round: a warehouse of "
                                    + "many aisles no longer takes proportionally longer to notice a chest a player "
                                    + "emptied by hand.",
                            "At the default it reads one location per interval up to about 1200 locations - which is "
                                    + "every warehouse at the default aisle limits, and exactly what every version up "
                                    + "to 0.5.0-alpha did. A bigger warehouse, which needs a raised maxAisleLength or "
                                    + "maxMastHeight, reads more: each turn reads at most maxSnapshotsPerTick "
                                    + "locations, the same ceiling the change queue has, so a controller never reads "
                                    + "more than twice that many inventories in the one tick a turn falls on.",
                            "0 switches the scaling off and reads one location per interval, whatever the size.")
                    .defineInRange("snapshotCycleTicks", 12000, 0, 432000);
            dispatchIntervalTicks = builder
                    .comment("[in Ticks] How often the controller plans jobs.")
                    .defineInRange("dispatchIntervalTicks", 5, 1, 200);
            retryTicks = builder
                    .comment("[in Ticks] Retry interval while a crane waits for a full output station.")
                    .defineInRange("retryTicks", 20, 1, 1200);
            holdRetryTicks = builder
                    .comment("[in Ticks] Retry interval for rerouting items a crane is holding.")
                    .defineInRange("holdRetryTicks", 40, 1, 1200);
            fullBackoffTicks = builder
                    .comment("[in Ticks] Back-off after no storage location accepted an item (warehouse full).")
                    .defineInRange("fullBackoffTicks", 40, 1, 1200);
            maxOpenRequests = builder
                    .comment("Maximum number of open retrieval requests per controller.")
                    .defineInRange("maxOpenRequests", 16, 1, 256);
            maxOpenRequestsPerOutput = builder
                    .comment("Maximum number of open retrieval requests per warehouse output, so that one output cannot "
                            + "take every slot of the controller's queue. One slot per item type: repeated requests for "
                            + "the same item are merged into the open one instead of taking another slot.",
                            "It therefore also bounds that merge: one output request may wait for this many times its "
                                    + "filter amount, so a redstone pulse clock cannot promise a whole item type.")
                    .defineInRange("maxOpenRequestsPerOutput", 4, 1, 256);
            maxSnapshotsPerTick = builder
                    .comment("Maximum number of storage locations a controller re-reads per tick after content changes, "
                            + "when locations join or after loading (the round robin comes on top).")
                    .defineInRange("maxSnapshotsPerTick", 4, 1, 64);
            collectPollIntervalTicks = builder
                    .comment("[in Ticks] How often a controller re-reads the inventory behind a collecting warehouse "
                                    + "port without being told to. It covers machines that change their inventory "
                                    + "without notifying their neighbours (a furnace result slot, several Create "
                                    + "blocks).",
                            "A change hint always overtakes it, and these reads share maxSnapshotsPerTick with the "
                                    + "stock index, so a lower value costs at most one bounded read per port per "
                                    + "interval.")
                    .defineInRange("collectPollIntervalTicks", 20, 1, 1200);
            maxProductionOrders = builder
                    .comment("Maximum number of production orders one controller runs at the same time.",
                            "Each order promises its ingredients, so they are no longer available to other requests "
                                    + "until it finishes, times out or is cancelled.",
                            "A recursive order holds ONE order per step, so raise this together with "
                                    + "maxProductionPlanSteps: a chain that does not fit into the free slots is "
                                    + "refused instead of started.")
                    .defineInRange("maxProductionOrders", 12, 1, 64);
            productionOrderTimeoutTicks = builder
                    .comment("[in Ticks] How long a production order may make no progress before it gives up.",
                            "Default 6000 ticks = 5 minutes. Every delivery, every state change and every result item "
                                    + "that arrives pushes the deadline out again, so only a genuinely stuck order "
                                    + "times out. A timed-out order releases what it still promised; ingredients your "
                                    + "machine has already taken are not recovered.")
                    .defineInRange("productionOrderTimeoutTicks", 6000, 200, 72000);
            maxProductionPlanSteps = builder
                    .comment("Maximum number of production orders ONE order may create, the ordered item's own "
                            + "included.",
                            "This is what lets you order an item whose ingredients have to be made first: the "
                                    + "warehouse works out the whole chain at the moment you click and either creates "
                                    + "every step at once or refuses the order and names the item that is really "
                                    + "missing.",
                            "Set it to 1 to switch recursion off completely: the warehouse then orders exactly as it "
                                    + "did before, one level deep. There is no separate depth limit - a chain may be "
                                    + "as deep as it likes as long as it fits into this many steps, into "
                                    + "maxPlanIngredientItems and into the free slots of maxProductionOrders.")
                    .defineInRange("maxProductionPlanSteps", 32, PlanLimits.MIN_STEPS, PlanLimits.MAX_STEPS);
            maxPlanIngredientItems = builder
                    .comment("The largest number of INGREDIENT items one order may put into your machines, counted "
                            + "over every step of its chain.",
                            "This is what really bounds what one click can lose: the step count bounds how many "
                                    + "machines are involved, not how much goes into them. A larger order is made "
                                    + "smaller until it fits, and one run of the ordered item is always allowed.")
                    .defineInRange("maxPlanIngredientItems", 256, (int) PlanLimits.MIN_INGREDIENT_ITEMS,
                            (int) PlanLimits.MAX_INGREDIENT_ITEMS);
            maxStockRules = builder
                    .comment("Maximum number of stock rules one aisle applies, over all its warehouse stock keepers.",
                            "Rules beyond it are inert and say so; lowering the value is reversible, because nothing "
                                    + "is ever written back into a keeper.")
                    .defineInRange("maxStockRules", 32, 1, 256);
            stockRuleIntervalTicks = builder
                    .comment("[in Ticks] How often a controller judges its stock rules against the current stock.",
                            "This drives the keepers' lamps and their comparator output; the maximum and the "
                                    + "reserve are applied when a job is planned or a request is made, not on this "
                                    + "interval.",
                            "It is also how often the warehouse may start an automatic restock order - at most one per "
                                    + "pass - so a large value makes restocking slow even when the ingredients are "
                                    + "there.")
                    .defineInRange("stockRuleIntervalTicks", 20, 5, 1200);
            maxRestockOrders = builder
                    .comment("Maximum number of production orders a warehouse may start BY ITSELF to refill the "
                            + "minimums of its stock rules.",
                            "Set it to 0 to switch automatic restocking off completely: your rules then still cap "
                                    + "storing and still hold their reserve back, the warehouse just never orders "
                                    + "anything on its own.",
                            "These orders also count against maxProductionOrders, so the smaller of the two wins.")
                    .defineInRange("maxRestockOrders", 4, 0, 64);
            maxRestockOrdersPerRule = builder
                    .comment("Maximum number of automatic restock orders ONE stock rule may have open at a time.",
                            "At the default of 1 a rule waits for its own order before it asks for more, which is "
                                    + "what keeps a slow machine from collecting a queue of identical runs. 0 "
                                    + "switches automatic restocking off, like maxRestockOrders.")
                    .defineInRange("maxRestockOrdersPerRule", 1, 0, 16);
            maxRestockOrderAmount = builder
                    .comment("The largest amount of the PRODUCT one automatic restock order may ask for.",
                            "A rule that is short by more than this is refilled in several orders instead of one, so "
                                    + "a minimum of 100000 cannot turn into a single order with hundreds of crane "
                                    + "trips. An order never asks for more than its rule's own maximum leaves room "
                                    + "for, and it makes whole runs only, so a shortfall smaller than one run is "
                                    + "reported instead of overshooting the maximum.")
                    .defineInRange("maxRestockOrderAmount", 512, 1, 65536);
            maxRestockIngredientItems = builder
                    .comment("The largest number of INGREDIENT items one automatic restock order may spend.",
                            "This is what really bounds what a broken machine can swallow before the safety stop "
                                    + "fires: the amount above counts the product, and a pattern of nine ingots to "
                                    + "one block would turn 512 blocks into 4608 ingots.",
                            "One run is always allowed, even when that single run costs more than this: a pattern "
                                    + "cannot be cut in half. The bound is about repeats, and "
                                    + "maxRestockOrdersPerRule then sequences a large shortfall one order at a time.")
                    .defineInRange("maxRestockIngredientItems", 64, 1, 65536);
            builder.pop();

            builder.comment("Chunk loading for aisles that have work - THIS IS A CHUNK LOADER").push("chunkLoading");
            maxTicketedAislesPerLevel = builder
                    .comment("How many aisles of ONE dimension may hold their own chunks loaded while they have work: "
                            + "a crane job, an open request or an open production order (an automatic restock order is "
                            + "one of those).",
                            "0 switches the whole feature off, and that is the default: a server that does not want "
                                    + "this pays nothing, and every aisle behaves exactly as before - the crane pauses "
                                    + "while its chunks are away and continues when they come back.",
                            "Above 0 this IS a chunk loader. A holding aisle keeps its chunks loaded with no player "
                                    + "nearby and keeps its whole dimension ticking, and it loads a slightly LARGER "
                                    + "area than the number of chunks below, because a force-loaded chunk also lets "
                                    + "its 8 neighbours tick their blocks (the same footprint vanilla /forceload has).",
                            "Random ticks and mob spawning are NOT enabled in held chunks: crops do not grow and mobs "
                                    + "do not spawn there. This loads a warehouse, not a farm.",
                            "An aisle lets go as soon as it is idle, so this is never a permanent loader. A collecting "
                                    + "port can only notice its machine while its own chunk ticks, so this keeps a "
                                    + "RUNNING collection going; it cannot start one.")
                    .defineInRange("maxTicketedAislesPerLevel", 0, 0, 64);
            maxChunksPerAisle = builder
                    .comment("How many chunks ONE warehouse may hold, over all of its aisles together. A warehouse "
                            + "whose footprint needs more holds NOTHING (never a partial hold) and says so when "
                            + "looked at through goggles and in /wareworks chunks, both naming the number it would "
                            + "need. Lowering this under a warehouse that is ALREADY holding makes it let go, and "
                            + "building a warehouse past this while it holds does the same: the number is a bound on "
                            + "the hold, not only on taking it.",
                            "The footprint is each aisle's box plus one block on every horizontal side, counted once "
                                    + "where aisles share a chunk. It covers the controller, the dock, the rails, "
                                    + "every rack position, the inventories behind them and the machine behind a "
                                    + "collecting port.",
                            "Worst case by shape, all at the default aisle.maxAisleLength = 32: ONE straight aisle "
                                    + "needs 8 chunks (12 at a length cap of 64, 20 at 128). A warehouse that BENDS "
                                    + "needs more than that, because a corner turns one long rectangle into two "
                                    + "shorter ones at right angles: an L of 32 + 16 rails needs 10, an L of two full "
                                    + "32-rail aisles needs 12, a U of three needs 16, and the widest warehouse "
                                    + "aisle.maxNetworkRails = 256 allows needs 36 - the same 36 a single 256-rail "
                                    + "aisle would need if the length cap let you build one.",
                            "So this number no longer follows from aisle.maxAisleLength alone, and the default 10 is "
                                    + "deliberately NOT the worst case any more, the way 8 was while a warehouse was "
                                    + "always one straight aisle: it covers every straight aisle of the default "
                                    + "length cap and a first corner, and a bigger warehouse has to raise it. That is "
                                    + "a chunk-loading budget, not a build limit - a warehouse over this number works "
                                    + "exactly as it always did, it only does not hold its own chunks.")
                    .defineInRange("maxChunksPerAisle", 10, 1, 64);
            releaseDelayTicks = builder
                    .comment("[in Ticks] How long a holding aisle waits after its last work before it lets its chunks "
                            + "go.",
                            "This is what keeps a burst of jobs from making the tickets thrash: work that arrives "
                                    + "again inside this window never releases in between. Default 100 ticks = 5 "
                                    + "seconds.")
                    .defineInRange("releaseDelayTicks", 100, 0, 1200);
            maxHoldTicks = builder
                    .comment("[in Ticks] The longest a single aisle may hold its chunks without a break. Default 72000 "
                            + "ticks = 1 hour.",
                            "When it runs out the aisle lets go, says so through goggles and may only hold again once "
                                    + "its work really changed - another job, another set of requests or orders - or "
                                    + "that work is finished. That is what bounds a request that can never be served "
                                    + "or a crane stuck holding items, and it is remembered across a restart, so "
                                    + "coming back does not start the hour again for the same stuck work.",
                            "0 means unlimited, and then one stuck aisle IS a permanent chunk loader.")
                    .defineInRange("maxHoldTicks", 72000, 0, 1728000);
            maxCollectHoldAislesPerLevel = builder
                    .comment("Separate opt-in: how many aisles of one dimension may hold their chunks ONLY because a "
                            + "collecting warehouse port has items waiting, with no job, request or order of their "
                            + "own. 0 switches it off, which is the default.",
                            "It keeps a production loop alive across the gap between one machine output and the next. "
                                    + "It does not make an idle aisle notice a machine that starts producing later: "
                                    + "an aisle with nothing pending releases its chunks and unloads.",
                            "These aisles also count against maxTicketedAislesPerLevel, so the smaller of the two "
                                    + "wins. An aisle that is only waiting for a slot under THIS cap says so through "
                                    + "goggles, so it can be told apart from an idle one.")
                    .defineInRange("maxCollectHoldAislesPerLevel", 0, 0, 64);
            builder.pop();
        }
    }
}
