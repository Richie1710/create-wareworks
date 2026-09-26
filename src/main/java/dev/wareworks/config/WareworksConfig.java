package dev.wareworks.config;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
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

    public static int maxProductionOrders() {
        return get(SERVER.maxProductionOrders);
    }

    public static int productionOrderTimeoutTicks() {
        return get(SERVER.productionOrderTimeoutTicks);
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

    /** The config values. Read them through the static getters of {@link WareworksConfig}. */
    public static final class Server {
        public final ModConfigSpec.IntValue maxAisleLength;
        public final ModConfigSpec.IntValue maxMastHeight;
        public final ModConfigSpec.IntValue geometryRefreshTicks;

        public final ModConfigSpec.DoubleValue stressImpact;
        public final ModConfigSpec.DoubleValue travelBlocksPerTickPerRpm;
        public final ModConfigSpec.DoubleValue liftBlocksPerTickPerRpm;
        public final ModConfigSpec.DoubleValue armExtendPerTickPerRpm;
        public final ModConfigSpec.DoubleValue maxBlocksPerTick;
        public final ModConfigSpec.IntValue transferTicks;
        public final ModConfigSpec.IntValue grabberStacks;
        public final ModConfigSpec.IntValue grabberMaxItems;

        public final ModConfigSpec.IntValue inputBufferSlots;
        public final ModConfigSpec.IntValue outputBufferSlots;
        public final ModConfigSpec.IntValue terminalBufferSlots;
        public final ModConfigSpec.IntValue maxTerminalRequestAmount;
        public final ModConfigSpec.IntValue maxTerminalStockEntries;
        public final ModConfigSpec.IntValue productionBufferSlots;
        public final ModConfigSpec.IntValue maxProductionPatterns;
        public final ModConfigSpec.IntValue stockKeeperRows;

        public final ModConfigSpec.IntValue snapshotIntervalTicks;
        public final ModConfigSpec.IntValue dispatchIntervalTicks;
        public final ModConfigSpec.IntValue retryTicks;
        public final ModConfigSpec.IntValue holdRetryTicks;
        public final ModConfigSpec.IntValue fullBackoffTicks;
        public final ModConfigSpec.IntValue maxOpenRequests;
        public final ModConfigSpec.IntValue maxOpenRequestsPerOutput;
        public final ModConfigSpec.IntValue maxSnapshotsPerTick;
        public final ModConfigSpec.IntValue maxProductionOrders;
        public final ModConfigSpec.IntValue productionOrderTimeoutTicks;
        public final ModConfigSpec.IntValue maxStockRules;
        public final ModConfigSpec.IntValue stockRuleIntervalTicks;
        public final ModConfigSpec.IntValue maxRestockOrders;
        public final ModConfigSpec.IntValue maxRestockOrdersPerRule;
        public final ModConfigSpec.IntValue maxRestockOrderAmount;
        public final ModConfigSpec.IntValue maxRestockIngredientItems;

        Server(ModConfigSpec.Builder builder) {
            builder.comment("Aisle geometry").push("aisle");
            maxAisleLength = builder
                    .comment("Maximum number of consecutive warehouse rails counted as one aisle.",
                            "Clients draw the moving crane only while the dock's chunk is within their render distance,",
                            "so a player at the far end of a long aisle needs a render distance that reaches the dock.")
                    .defineInRange("maxAisleLength", 32, 1, 128);
            maxMastHeight = builder
                    .comment("Maximum mast height of a stacker crane, in blocks (reachable levels).")
                    .defineInRange("maxMastHeight", 16, 1, 64);
            geometryRefreshTicks = builder
                    .comment("[in Ticks] How often a stacker crane re-counts the rails of its aisle.")
                    .defineInRange("geometryRefreshTicks", 40, 1, 1200);
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
                    .comment("[in Ticks] Round-robin reconciliation: one storage location is re-read per interval.")
                    .defineInRange("snapshotIntervalTicks", 10, 1, 1200);
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
            maxProductionOrders = builder
                    .comment("Maximum number of production orders one controller runs at the same time.",
                            "Each order promises its ingredients, so they are no longer available to other requests "
                                    + "until it finishes, times out or is cancelled.")
                    .defineInRange("maxProductionOrders", 8, 1, 64);
            productionOrderTimeoutTicks = builder
                    .comment("[in Ticks] How long a production order may make no progress before it gives up.",
                            "Default 6000 ticks = 5 minutes. Every delivery, every state change and every result item "
                                    + "that arrives pushes the deadline out again, so only a genuinely stuck order "
                                    + "times out. A timed-out order releases what it still promised; ingredients your "
                                    + "machine has already taken are not recovered.")
                    .defineInRange("productionOrderTimeoutTicks", 6000, 200, 72000);
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
        }
    }
}
