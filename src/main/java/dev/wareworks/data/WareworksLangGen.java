package dev.wareworks.data;

import java.util.function.BiConsumer;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.ChunkKeepReason;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.StockRuleAdjustment;
import dev.wareworks.core.stock.StockRulePause;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.registry.WareworksCreativeTabs;
import dev.wareworks.util.WareworksLang;

/**
 * English lang entries that do not come from a Registrate builder (creative tab, goggle and UI lines, Create-style
 * item descriptions). Written to {@code src/generated/resources/assets/wareworks/lang/en_us.json} by {@code runData}.
 * <p>
 * Block and item names come from their builders ({@code defaultLang} / {@code .lang(...)}); never add them here, or
 * datagen fails with a duplicate key. Create item descriptions use the keys
 * {@code block.wareworks.<id>.tooltip.summary}, {@code .condition1}/{@code .behaviour1}, {@code .control1}/{@code .action1}.
 * Every key generated here must also be translated in the hand-written {@code de_de.json}
 * (checked by {@code LangConsistencyTest}).
 */
public final class WareworksLangGen {
    private WareworksLangGen() {
    }

    public static void generate(BiConsumer<String, String> lang) {
        lang.accept(WareworksCreativeTabs.BASE_TITLE_KEY, Wareworks.NAME);

        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ITEM_COUNT), "%1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_EMPTY), "Empty");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MORE_ENTRIES), "...and %1$s more");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_SLOTS_USED), "Slots: %1$s / %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_LOCATION), "Storage Location:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NO_AISLE), "Not part of an aisle");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ATTACHED_INVENTORY), "Inventory: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NO_INVENTORY), "No inventory attached");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STACKER_CRANE), "Stacker Crane:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_AISLE_SIZE), "Aisle: %1$s long, mast %2$s high");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NO_CONTROLLER), "No controller");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CONTROLLER_LINKED), "Controller linked");
        lang.accept(WareworksLang.key(WareworksLang.CRANE_MAST_HEIGHT), "Mast Height");
        lang.accept(WareworksLang.key(WareworksLang.CRANE_ROTATION_LOCKED),
                "The stacker crane is busy: it can only be turned without a job and with an empty grabber");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ADDRESS), "Address: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MISALIGNED), "Misaligned");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MISALIGNED_HINT), "Turn the brass port away from the aisle");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER), "Filter: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER_NONE), "Accepts everything");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER_EMPTY),
                "Empty filter: this location accepts nothing");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER_SHADOWED),
                "Without effect: another interface counts this inventory");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_PRIORITY), "Priority: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_CONTROLLER), "Warehouse Controller:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_AISLE_LETTER), "Aisle %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_READY), "Ready");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_NO_DOCK), "No stacker crane in front");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_DOCK_MISALIGNED),
                "The stacker crane in front faces another way");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_NO_RAILS), "No warehouse rails in front of the crane");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_LOCATIONS), "Storage locations: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FILTERED_LOCATIONS), "Filtered locations: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRIORITISED_LOCATIONS), "Prioritised locations: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ACCEPTING_PORTS), "Accepting ports: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_COLLECTING_PORTS), "Collecting ports: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CHUNK_LOADING), "Chunk loading: %1$s chunks (%2$s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CHUNK_LOADING_NONE), "Chunk loading: none (%1$s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CHUNK_LOADING_AT_LIMIT),
                "Chunk loading: none (server limit: %1$s aisles)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CHUNK_LOADING_AT_COLLECT_LIMIT),
                "Chunk loading: none (collecting limit: %1$s aisles)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CHUNK_LOADING_TOO_MANY),
                "Chunk loading: none (this aisle needs %1$s of %2$s chunks)");
        for (ChunkKeepReason reason : ChunkKeepReason.values()) {
            if (reason == ChunkKeepReason.NONE)
                continue;
            lang.accept(WareworksLang.key(reason.langKey()), switch (reason) {
                case CRANE_JOB -> "crane job";
                case OPEN_REQUESTS -> "open requests";
                case PRODUCTION_ORDERS -> "production orders";
                case COLLECTING -> "collecting from a machine";
                case RELEASING -> "idle, letting go";
                case AT_LEVEL_LIMIT -> "as many aisles hold chunks as the server allows";
                // Its own reason rather than AT_LEVEL_LIMIT: the collect opt-in is a second setting, and an aisle
                // queued behind it would otherwise look byte-for-byte like an idle one (M19 review).
                case AT_COLLECT_LIMIT -> "as many aisles collect with held chunks as the server allows";
                case TOO_MANY_CHUNKS -> "this aisle needs more chunks than the server allows";
                // Two things reach this state: the longest allowed hold running out, and an operator's
                // "/wareworks chunks release". The line must be true of both, so it says what the aisle IS doing
                // rather than guessing why; the cause is in the server log and in the command's own answer.
                case GAVE_UP -> "let go; holds again when its work changes";
                case NONE -> throw new IllegalStateException("skipped above");
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_HEADER), "Chunks held in %1$s:");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_ROW),
                "  %1$s: aisle %2$s, %3$s chunk(s), %4$s, held for %5$s s");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_ROW_UNCLAIMED),
                "  %1$s: %2$s chunk(s) reinstated from the save, no warehouse controller has claimed them yet");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_NONE), "No Wareworks aisle is holding any chunks");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_TOTAL),
                "Wareworks holds %1$s chunk(s) in %2$s aisle(s) over %3$s dimension(s)");
        // Two numbers, because the first one is NOT a total: Wareworks takes block tickets, so that is the number the
        // rows above are comparable with, while vanilla /forceload and entity tickets live in two other stores
        // entirely (M19 review).
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_RAW),
                "  %1$s has %2$s chunk(s) force-loaded by block tickets (all mods), %3$s in total "
                        + "(entity tickets and /forceload included)");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_RELEASED), "Released the chunks of the aisle at %1$s");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_NOT_HELD),
                "No aisle at %1$s is holding chunks in %2$s");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_RELEASED_ALL),
                "Released the chunks of %1$s aisle(s) in %2$s dimension(s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATIONS), "Inputs: %1$s, outputs: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MISALIGNED_COUNT), "Misaligned blocks: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ITEM_TYPES), "Item types: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ITEMS_STORED), "Items stored: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.CONTROLLER_AISLE_LETTER), "Aisle");
        lang.accept(WareworksLang.key(WareworksLang.CONTROLLER_AISLE_LETTER_ROW), "Letter");
        lang.accept(WareworksLang.key(WareworksLang.INTERFACE_STORE_FILTER), "Stored Items");
        lang.accept(WareworksLang.key(WareworksLang.INTERFACE_STORE_PRIORITY), "Storage Priority");
        lang.accept(WareworksLang.key(WareworksLang.INTERFACE_STORE_PRIORITY_ROW), "Priority");
        lang.accept(WareworksLang.key(WareworksLang.INTERFACE_STORE_PRIORITY_TIP), "Hold to set the priority");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_OPEN_REQUESTS), "Open requests: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_INPUT), "Warehouse Input:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_OUTPUT), "Warehouse Output:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_TERMINAL), "Warehouse Terminal:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_PRODUCTION), "Warehouse Production:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_PATTERNS), "Patterns: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_NO_PATTERNS), "No patterns set");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_ORDERS), "Production orders: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_NO_ORDERS), "No production order");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_STATE), "Order: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_MISSING), "Ingredients still to fetch: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_AWAITED), "Waiting for results: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_STATIONS), "Production stations: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_RESUMED), "The warehouse makes %1$s again");
        // "Delivered and never came back" rather than "stayed in the machine": the number is what the crane dropped into
        // the *station*, and part of it may still be in the station's own buffer, which a player can empty by hand.
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_RESUMED_LOST),
                "The warehouse makes %1$s again; %2$s ingredient items were delivered and never came back");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_NOTHING_STOPPED),
                "Nothing this station makes is stopped");
        // The safety stop where the machine is (M20, issue #4, ADR-032): the goggle line, the screen's own row and the
        // sentence that says how to lift it. "Stopped" rather than "paused", because a player stopped nothing here.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PRODUCTION_STOPPED), "Stopped products: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_RESUME_HINT),
                "Sneak-click the station to make them again");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_STOPPED_LINE),
                "Stopped: %1$s. Click to make it again");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_STOPPED_LINE_MANY),
                "Stopped: %1$s products. Click to make them again");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_STOPPED_ITEM), "%1$s: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STOCK_RULES), "Stock rules: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RULES_BELOW_MINIMUM), "Below minimum: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RULES_AT_MAXIMUM), "At maximum: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_STOCK_KEEPER), "Warehouse Stock Keeper:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_RULES), "Rules: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_NO_RULES), "No rules set");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_NO_WAREHOUSE), "Not part of a warehouse");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_BELOW_MINIMUM), "Below minimum: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_AT_MAXIMUM), "At maximum: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_AT_RESERVE), "Down to the reserve: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_ORDERING), "Being made now: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_WAITING),
                "Waiting for ingredients: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_PAUSED),
                "Paused after a lost batch: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_PAUSED_HINT),
                "Check the machine, then open the rule and click its mark to resume");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_WITHOUT_EFFECT), "Without effect: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_KEEPER_SATISFIED), "Everything within its limits");
        for (ProductionOrderState state : ProductionOrderState.values()) {
            lang.accept(WareworksLang.key(state.langKey()), switch (state) {
                case WAITING_FOR_INGREDIENTS -> "waiting for ingredients";
                // Short on purpose: an order line names the item and this state side by side, in a 216 pixel row
                // (§3.4.2). "ingredients delivered to the machine" left a long item name no room at all.
                case DELIVERED -> "at the machine";
                case WAITING_FOR_RESULT -> "waiting for the result";
                case COMPLETE -> "complete";
                case TIMED_OUT -> "timed out";
                case CANCELLED -> "cancelled";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATION_MISALIGNED_HINT), "Turn the opening towards the aisle");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_TERMINAL_MISALIGNED_HINT),
                "Turn the screen away from the aisle with the wrench");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BUFFER), "Buffer:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PENDING_REQUESTS), "Items requested: %1$s (requests: %2$s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NO_PENDING_REQUEST), "No pending request");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_LAST_REJECTION), "Last request refused: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_REQUEST_FILTER), "Requested Item");
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_REQUEST_AMOUNT), "Requested Amount");
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_ACCEPT_FILTER), "Accepted Item");
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_COLLECT_FILTER), "Collected Item");
        // The same board in the accepting direction, where the amount column means nothing and only the rows do: the
        // title therefore names what it really sets there, in the words the goggles use ("Redstone: While powered").
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_PORT_BOARD), "Redstone Behaviour");
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_PORT_RANK), "Port Direction");
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_PORT_REDSTONE_TIP), "Hold to set when the port acts");
        // The same tip on a requesting port, where the column is the amount too: naming only one of the two settings is
        // what Create's own "Hold to set amount" did, and it is the amount every pre-M17 world was built on.
        lang.accept(WareworksLang.key(WareworksLang.OUTPUT_REQUEST_AMOUNT_TIP),
                "Hold to set the amount and when the port acts");
        for (PortRedstone mode : PortRedstone.values()) {
            lang.accept(WareworksLang.key(mode.langKey()), switch (mode) {
                case PULSE -> "On a pulse";
                case WHILE_POWERED -> "While powered";
                case UNLESS_POWERED -> "Unless powered";
            });
        }
        lang.accept(WareworksLang.key(PortSettings.rowLangKey(PortSettings.REQUEST_ROW)), "Request");
        lang.accept(WareworksLang.key(PortSettings.rowLangKey(PortSettings.OVERFLOW_ROW)), "Overflow — after storage");
        lang.accept(WareworksLang.key(PortSettings.rowLangKey(PortSettings.DIVERSION_ROW)),
                "Diversion — before storage");
        // The fourth row, whose column means nothing at all (M18, issue #13): the only row that turns the port around.
        lang.accept(WareworksLang.key(PortSettings.rowLangKey(PortSettings.COLLECT_ROW)),
                "Collect — out of a machine");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_REDSTONE), "Redstone: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_ACTIVE), "Active");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_WAITING), "Waiting for a signal");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_OVERFLOW), "Port: overflow (%1$s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_DIVERSION), "Port: diversion (%1$s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_ACCEPTS), "Accepts: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_ACCEPTS_ANY), "Any item");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_EXPORTED), "Handed over: %1$s");
        // The collect direction (M18, issue #13). No rank line: a collecting port has no magnitude at all.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_COLLECTING), "Port: collects into the warehouse");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_COLLECTS), "Collects: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_COLLECT_READY), "Ready: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_COLLECTED), "Collected: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_NO_INVENTORY), "No inventory behind the port");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_COLLECT_OWN_STORAGE),
                "This inventory is already a storage location");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_COLLECT_REFUSED), "Not fetched: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_STATUS), "Status: %1$s");
        for (CranePhase phase : CranePhase.values()) {
            lang.accept(WareworksLang.key(WareworksLang.cranePhaseKey(phase)), switch (phase) {
                case IDLE -> "Waiting for a job";
                case TRAVEL_TO_SOURCE -> "Travelling to the source";
                case EXTEND_SOURCE -> "Reaching into the source";
                case PICK -> "Picking up items";
                case RETRACT_SOURCE -> "Retracting from the source";
                case TRAVEL_TO_TARGET -> "Travelling to the target";
                case EXTEND_TARGET -> "Reaching into the target";
                case DROP -> "Dropping off items";
                case RETRACT_TARGET -> "Retracting from the target";
                case COMPLETE -> "Job complete";
                case REROUTE -> "Looking for another target";
                case HOLDING -> "Holding items, no target found";
                case WAITING_FOR_TARGET -> "Waiting for space at the output";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_PAUSED), "Paused: %1$s");
        for (CranePauseReason reason : CranePauseReason.values()) {
            if (reason == CranePauseReason.NONE)
                continue;
            lang.accept(WareworksLang.key(reason.langKey()), switch (reason) {
                case NO_ROTATION -> "no rotation";
                case OVERSTRESSED -> "overstressed";
                case CHUNK_NOT_LOADED -> "area not loaded";
                case SPEED_FACTOR_ZERO -> "a crane speed factor is 0 in the server config";
                case NONE -> throw new IllegalStateException("skipped above");
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_JOB_STORE), "Storing %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_JOB_RETRIEVE), "Retrieving %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_JOB_HAND_OVER), "Handing over %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_JOB_COLLECT), "Collecting %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_ROUTE), "From %1$s to %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_HOLDING), "Holding:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_HEAD_EMPTY), "Grabber empty");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_LAST_PLAN), "Last planning: %1$s");
        for (NoJobReason reason : NoJobReason.values()) {
            lang.accept(WareworksLang.key(WareworksLang.noJobReasonKey(reason)), switch (reason) {
                // No origin is named in these four (M18 review): a collecting port reports the same three of them as a
                // warehouse input, and a collect-only aisle need not contain an input at all.
                case WAREHOUSE_FULL -> "no storage location accepts these items";
                case NO_MATCHING_FILTER -> "no storage location has a filter that accepts these items";
                case PORT_FULL -> "an accepting port was the only place left for these items and it is full";
                case AT_MAXIMUM -> "a stock rule for these items is at its maximum";
                case OUTPUT_FULL -> "an output is full";
                case PRODUCTION_FULL -> "a production station cannot take more ingredients";
                case NOT_IN_STOCK -> "a requested item is not in stock";
                case LOCATION_UNAVAILABLE -> "an output is not reachable";
                case BUDGET_EXHAUSTED -> "still searching";
                case COLLECT_SOURCE_EMPTY -> "a machine hands out nothing its port may fetch";
                case NO_WORK -> "nothing to do";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SEARCH), "Search items");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SORT), "Sorting: %1$s");
        for (TerminalSort sort : TerminalSort.values()) {
            lang.accept(WareworksLang.key(sort.langKey()), switch (sort) {
                case AMOUNT -> "most available first";
                case NAME -> "by name";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_ONLY_IN_STOCK), "Showing only what is available");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SHOW_ALL), "Showing everything the aisle holds");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_AMOUNT_HINT),
                "Click an item for this amount, Shift for a stack, Ctrl for everything, Alt to skip the question");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_EMPTY), "The aisle holds nothing");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_NO_MATCH), "No item matches the search");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LOADING), "Reading the stock...");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_NO_AISLE), "Not part of an aisle");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRANE), "Crane: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_NO_CRANE), "No stacker crane");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_WAITING), "Waiting for %1$s, delivered %2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_REQUESTED), "Requested %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_REQUESTED_MERGED),
                "Requested %1$s x%2$s, waiting for %3$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_REFUSED), "Request refused: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_STORED), "In stock: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_AVAILABLE), "Available: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_RESERVED), "Promised to other requests: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_RULE), "Stock rule: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_RULE_RESERVED), "Kept back from automation: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_RULE_MAXIMUM), "Stored at most: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_BELOW_RESERVE), "Your request goes below the reserve");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_TITLE), "Are you sure?");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_RESERVE),
                "This takes %1$s of the %2$s items held in reserve.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_MAXIMUM),
                "%1$s of the %2$s items this makes cannot be stored: the maximum is %3$s.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_INGREDIENT),
                "Making it takes %1$s of the %2$s reserved %3$s.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_CHANGED),
                "The warehouse has changed since you were asked:");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_YES), "Confirm");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_NO), "Cancel");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_SKIP),
                "Hold Alt while clicking to skip this question");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_BUFFER), "Delivered here");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_NOT_SHOWN), "+%1$s not shown");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_PATTERNS), "Patterns");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_BUFFER), "Delivered here");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_HINT),
                "Click with an item to set it, with an empty hand to clear it; scroll to set the amount");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_ORDER), "%1$s x%2$s - %3$s");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_ORDER_LOST),
                "%1$s x%2$s - %3$s, ingredients not recovered");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_INGREDIENT), "Ingredient of one run");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_RESULT), "Result of one run");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_TAB), "Pattern %1$s");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_TAB_HINT), "Click to edit, Right-click to clear");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_CANCEL_HINT), "Click to give up on this order");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_INGREDIENTS_LOST),
                "Ingredients your machine already took are not recovered");
        // Not a ProductionOrderState (M20, issue #4): such an order is nominally waiting for ingredients, but it
        // fetches nothing at all until the step below it is done. One sentence for the terminal's step panel, a
        // station's own order rows and that station's goggle line, so the three can never disagree.
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_WAITING_FOR_STEP), "waiting for an earlier step");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_RULES), "Rules");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_EMPTY_ROW), "Empty row");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_MINIMUM), "Minimum");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_MAXIMUM), "Maximum");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_RESERVE), "Reserve");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_MINIMUM_SHORT), "Min");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_MAXIMUM_SHORT), "Max");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_RESERVE_SHORT), "Res");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_MINIMUM_HINT),
                "How many the warehouse tries to keep: below it the comparator calls for the item");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_MAXIMUM_HINT),
                "The most the warehouse stores: above it the crane stops accepting the item");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_RESERVE_HINT),
                "The last items, kept from redstone requests; you can still take them at a terminal");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_NUMBER_HINT),
                "Scroll to change, Shift for whole stacks, Right-click to switch it off");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_ITEM_HINT),
                "Click with an item to set it, with an empty hand to clear the row");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_HINT), "Click an item in, scroll a number");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_NO_WAREHOUSE), "Not part of a warehouse");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_LIMITS),
                "Minimum %1$s \u00b7 Maximum %2$s \u00b7 Reserve %3$s");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_IN_STOCK), "In stock: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_AVAILABLE), "Available: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_HELD_BACK), "Held back from automation: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_SHORTFALL), "Short of the minimum: %1$s");
        for (StockRuleStatus status : StockRuleStatus.values()) {
            lang.accept(WareworksLang.key(WareworksLang.keeperStatusKey(status)), switch (status) {
                case NO_ITEM -> "No item set";
                case NO_WAREHOUSE -> "Not part of a warehouse";
                case INERT -> "Without effect: this aisle already applies its limit of rules";
                case SHADOWED -> "Without effect: an earlier rule already governs this item";
                case NO_LIMITS -> "No limit set yet";
                case BELOW_MINIMUM -> "Below the minimum";
                case AT_MAXIMUM -> "At the maximum: no more is stored";
                case AT_RESERVE -> "Down to the reserve: automation gets no more";
                case SATISFIED -> "Within its limits";
                case ORDERING -> "Below the minimum: the warehouse is making more";
                case WAITING_FOR_INGREDIENTS -> "Below the minimum: waiting for ingredients";
                case PAUSED -> "Paused: an order lost its ingredients";
            });
        }
        for (RestockOutcome outcome : RestockOutcome.values()) {
            lang.accept(WareworksLang.key(WareworksLang.keeperRestockKey(outcome)), switch (outcome) {
                case NOT_GOVERNING -> "This rule applies nothing";
                case PAUSED -> "Paused: the warehouse stopped ordering this";
                case SATISFIED -> "Enough in stock";
                case DISABLED -> "Automatic restocking is switched off";
                case ORDER_OPEN -> "An order for this is already running";
                case ORDERS_BUSY -> "Too many orders are running; this one waits";
                case NO_PATTERN -> "No production station here makes this";
                case NO_ROOM -> "A whole run would go past the maximum";
                case WAITING_FOR_INGREDIENTS -> "The ingredients are not available";
                case DEFERRED -> "Will be ordered next";
                case ORDERED -> "Ordered from a production station";
            });
        }
        for (StockRulePause.Cause cause : StockRulePause.Cause.values()) {
            lang.accept(WareworksLang.key(WareworksLang.keeperPausedKey(cause)), switch (cause) {
                case TIMED_OUT -> "the last order timed out";
                case CANCELLED -> "the last order was given up";
                case ORDER_TIMED_OUT -> "an order at a machine timed out";
                case ORDER_CANCELLED -> "an order at a machine was given up";
            });
        }
        // Label-and-number rather than "%1$s ingredient items", which reads "1 ingredient items" at the count that
        // fires the safety stop most often (M15 DoD). The same shape as the goggle lines "Below minimum: N".
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_PAUSED_LOST),
                "Ingredient items delivered and never returned: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_RESUME_HINT),
                "Click the mark to order again, once the machine works");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_PAUSED_LINE),
                "Paused: %1$s. Click the mark to resume");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_MISSING_INGREDIENT), "Missing: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.KEEPER_RESUMED), "The warehouse orders this item again");
        for (StockRuleAdjustment adjustment : StockRuleAdjustment.values()) {
            lang.accept(WareworksLang.key(WareworksLang.keeperAdjustmentKey(adjustment)), switch (adjustment) {
                case NONE -> "Stored";
                case VALUE_CLAMPED -> "Number out of range, corrected";
                case MAXIMUM_RAISED_TO_MINIMUM -> "Maximum raised to the minimum";
                case RESERVE_CLAMPED_TO_MAXIMUM -> "Reserve lowered to the maximum";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCIBLE), "Can be produced here");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCIBLE_AMOUNT), "Can be made now: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCTION), "Production");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_ORDER_LOST), "%1$s, not recovered");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCING), "Requested %1$s x%2$s, producing %3$s");

        // A production plan in the terminal (M20, issue #4, ADR-032). The section has two lines and a chain has up to
        // maxProductionPlanSteps orders, so a chain takes one line: what it makes on the left, where the work really is
        // on the right, and the steps themselves in a panel that names the machine to walk to.
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_FRONTIER), "now: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_STEPS), "%1$s steps");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_TITLE), "Chain for %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_STEP_AT), "%1$s: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_NO_ADDRESS), "no address");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_CANCEL_COST), "Steps that would end: %1$s");
        // "Already delivered to your stations", not "at your machines": the number is what the crane handed over, and some
        // of it may still be in a station's own buffer. The third line is the consequence a player cannot see coming.
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_CANCEL_LOST),
                "Ingredients already delivered: %1$s - the warehouse does not fetch them back");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_CANCEL_STOP),
                "The warehouse then stops making %1$s until you resume it at the machine");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_CANCEL), "Give up on the chain");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_CLOSE), "Close");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_HINT), "Click to see every step of the chain");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PLAN_CANCEL_HINT),
                "Click the x to give up on the whole chain");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_DELIVERED_ITEMS), "Delivered so far: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RESERVED_INCOMING), "Incoming: %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RESERVED_OUTGOING), "Reserved for pickup: %1$s x%2$s");

        // Display Link sources (M14). The four names must stay in step with the registry paths in
        // WareworksDisplaySources: Create builds a source name as "wareworks.display_source.<path>". The line texts are
        // deliberately short, because a row of four nixie tubes shows eight characters.
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_AISLE_SUMMARY), "Aisle Summary");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_STOCK_LIST), "Stock List");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_FILTERED_STOCK), "Stock of the Filtered Item");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_CRANE_STATUS), "Crane Status");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_AISLE), "Aisle %1$s: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_LOCATIONS), "Locations: %1$s / %2$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_ITEM_TYPES), "Item types: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_ITEMS), "Items: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_RULES),
                "Rules: %1$s · below min %2$s · at max %3$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_STOPPED), "Stopped products: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_PORTS), "Ports: %1$s accepting");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_PORTS_BOTH),
                "Ports: %1$s accepting, %2$s collecting");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_PORTS_COLLECTING), "Ports: %1$s collecting");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_CHUNKS), "Chunks: %1$s held");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_NO_AISLE), "No aisle");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_STATUS_READY), "Ready");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_STATUS_NO_DOCK), "No crane");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_STATUS_DOCK_MISALIGNED), "Crane turned");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_STATUS_NO_RAILS), "No rails");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_IDLE), "Idle");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_STORING), "Storing");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_RETRIEVING), "Retrieving");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_SUPPLYING), "Supplying");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_HANDING_OVER), "Handing over");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_COLLECTING), "Collecting");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_PAUSED), "Paused");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_LINE_JOB), "%1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_LINE_TARGET), "To %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_CRANE_LINE_HOLDING), "Holding %1$s");

        for (RequestRejection rejection : RequestRejection.values()) {
            lang.accept(WareworksLang.key(rejection.langKey()), switch (rejection) {
                case NO_CONTROLLER -> "not part of an aisle with a controller";
                case NO_FILTER -> "no item in the filter slot";
                case NOT_IN_STOCK -> "not in stock";
                case QUEUE_FULL -> "too many open requests";
                case OUTPUT_FULL -> "too many open requests for this output";
                case REQUEST_FULL -> "the open request here already asks for the largest allowed amount";
                case PRODUCTION_BUSY -> "too many production orders are running; wait for one or give one up";
                case OUT_OF_REACH -> "you are too far away from the terminal";
                case INVALID_AMOUNT -> "the requested amount must be at least 1";
                case RESERVED -> "a stock rule keeps the rest of it in reserve";
                case PRODUCTION_PAUSED -> "the warehouse has stopped making it; check the machine and resume it";
            });
        }

        // Why a production chain could not be planned (M20, issue #4, ADR-032). Every one of them names the item it is
        // about, which is what makes a refusal something a player can walk to: a click on a chest is refused because
        // oak logs are missing, not because "the chest" is.
        //
        // These are written for the terminal's status row and are therefore short. That row is 216 px wide and holds
        // one line ({@code docs/warehouse-system.md} §3.4.2), and a refusal is the one answer a player really has to
        // read, so the sentence must fit with the item's name still in it. The wordier cure ("wait for an order",
        // "raise the limit") lives in the item descriptions and the goggle lines, which have room for it.
        for (PlanRefusal refusal : PlanRefusal.values()) {
            lang.accept(WareworksLang.key(refusal.langKey()), switch (refusal) {
                case NO_PATTERN -> "No pattern makes %1$s";
                case MISSING_INGREDIENT -> "%1$s is missing";
                case PAUSED -> "Making %1$s is stopped";
                case NO_ROOM -> "No room for %1$s";
                case LOOP -> "The chain loops at %1$s";
                case TOO_MANY_STEPS -> "Too many steps to make %1$s";
                case TOO_MANY_INGREDIENT_ITEMS -> "Too many items to make %1$s";
                case ORDERS_BUSY -> "No free order for %1$s";
            });
        }

        tooltip(lang, "block.wareworks.warehouse_interface",
                "Turns the _inventory_ it faces into an _addressable storage location_ of a warehouse aisle. A _stacker "
                        + "crane_ stores items there and takes them out again.",
                "When placed",
                "The _brass port_ faces the clicked _inventory_, such as a _chest_ or _barrel_. Clicked on the side "
                        + "of another _warehouse interface_, it copies that direction; otherwise it faces away from you. "
                        + "Keep the _framed plate_ towards the _aisle_.",
                "When setting the filter",
                "Click the _filter slot_ below the arm port with an item to store _only_ that item here. _List_, "
                        + "_attribute_ and _package filters_ all work, and an _empty_ slot accepts _everything_. A "
                        + "filter only restricts _storing_: whatever is already inside can always be _retrieved_, and "
                        + "changing a filter never moves items that are already stored.",
                "When setting the priority",
                "_Hold_ the click on the _filter slot_ to set a _priority_ from _0_ to _9_: among the locations that "
                        + "are _equally suitable_, the crane fills the _highest_ one first, so a rack by the _door_ can "
                        + "fill before the far end of the aisle. It only decides where _new_ items go — it never moves "
                        + "what is already stored, and _retrieval_ always takes the _nearest_ source.",
                "When looked at with Goggles",
                "Shows its _address_, its _filter_, its _priority_, the _attached inventory_, its _used slots_, the "
                        + "most stored _items_ and the items _reserved_ for a running crane job.");

        tooltip(lang, "block.wareworks.warehouse_input",
                "A _station_ of a warehouse _aisle_ where items _enter_ the warehouse. It _buffers_ arriving items until "
                        + "a _stacker crane_ stores them.",
                "When placed",
                "The _opening_ faces you: stand in the _aisle_ and place it into a _rack_ beside the aisle.",
                "When items arrive",
                "Accepts items from _belts_, _funnels_, _chutes_, _hoppers_ and _Mechanical Arms_. The _stacker crane_ "
                        + "carries them to a free _storage location_; automation can only _insert_, never take items out.",
                "When looked at with Goggles",
                "Shows its _address_ and the _buffered items_.");

        tooltip(lang, "block.wareworks.warehouse_output",
                "The _port_ of a warehouse _aisle_: it _requests_ items, or it _accepts_ what the warehouse cannot keep. "
                        + "_Funnels_, _chutes_, _hoppers_ and _Mechanical Arms_ can pull them out.",
                "When placed",
                "The _opening_ faces you: stand in the _aisle_ and place it into a _rack_ beside the aisle. A new port "
                        + "_requests_, exactly as before.",
                "When setting the filter",
                "Click the _filter slot_ with the item to request. It sits on the _top_, the _back_ and both _side_ "
                        + "faces, never in the _aisle opening_, where the crane reaches in. Hold _Right-Click_ on it to "
                        + "set the _amount_ and _when_ the port acts: on a _pulse_, _while powered_, or _unless "
                        + "powered_.",
                "When setting the direction",
                "Hold _Right-Click_ with the _wrench_ on the port's own box — a small box on the same _top_, _back_ and "
                        + "_side_ faces, just above the filter slot — to set the _direction_: _Request_, or _Accept_ "
                        + "with a rank. An _overflow_ ranks after every storage location, a _diversion_ before them, and "
                        + "the _filter_ then says which items the port takes at all. An accepting port turns _andesite_ "
                        + "around the aisle opening and on the spout, and shows its rank on the _back_.",
                "When powered by Redstone",
                "A _requesting_ port asks the _warehouse controller_ for the filter item: up to the set _amount_, at "
                        + "most what is _in stock_. An _accepting_ one takes items that arrived at an _input_ and would "
                        + "otherwise be stored. _On a pulse_ it acts once per signal; _while powered_ it keeps going, "
                        + "with _one_ open request at a time, so a machine is fed without a _clock_; _unless powered_ "
                        + "does the same until a _lever_ switches it off. Nothing is ever _destroyed_: a full port lets "
                        + "the _input_ back up.",
                "When looked at with Goggles",
                "Shows its _address_, the _buffered items_ and its _redstone_ setting. A _requesting_ port adds its "
                        + "_pending request_ with the items _delivered_ so far and why the last one was _refused_; an "
                        + "_accepting_ one its _rank_, which items it _accepts_ and how many it has _handed over_.");

        tooltip(lang, "block.wareworks.warehouse_terminal",
                "A _station_ of a warehouse _aisle_ with a _screen_: ask for items here and the _stacker crane_ fetches "
                        + "them. _Funnels_, _chutes_, _hoppers_ and _Mechanical Arms_ can pull them out again.",
                "When placed",
                "The _screen_ faces you and the _arm port_ the opposite way: stand where you want to read the "
                        + "terminal, with the _aisle_ on the far side of the _rack_. The port then moves to whichever "
                        + "side of the terminal the aisle really is on.",
                "When turned with a Wrench",
                "The _wrench_ turns the _screen_ to the next face, never onto the _arm port_: the crane keeps loading "
                        + "the terminal from the _aisle_ while you put the screen where you stand.",
                "When requesting items",
                "The screen lists everything the _aisle_ holds with the amount that is still _available_. Every request "
                        + "is a normal _retrieval job_: the crane brings the items here, just like a request from a "
                        + "_warehouse output_.",
                "When ordering something made of something else",
                "An item your _production stations_ can make is offered even when none is in stock, and one whose "
                        + "_ingredients_ are missing is ordered as a whole _chain_. The terminal shows it as _one line_ "
                        + "with the step that is working; a click on that line lists every _step_ with the _address_ of "
                        + "the machine it runs at, and gives the whole chain up at once.",
                "When looked at with Goggles",
                "Shows its _address_, the _buffered items_, the _pending request_ with the items _delivered_ so far, "
                        + "and why the last request was _refused_.");

        tooltip(lang, "block.wareworks.warehouse_production",
                "A _station_ of a warehouse _aisle_ that feeds your _machines_. Define a _pattern_ here, and the "
                        + "_stacker crane_ brings its _ingredients_ to this block; your own _funnel_, _chute_, _belt_ or "
                        + "_Mechanical Arm_ carries them into the machine. Wareworks never crafts anything itself.",
                "When placed",
                "The _opening_ faces you: stand in the _aisle_ and place it into a _rack_ beside the aisle.",
                "When setting a pattern",
                "_Right-Click_ with an _empty hand_ to open it. Click a _slot_ with an item to set an _ingredient_ or "
                        + "the _result_, and _scroll_ on a slot to change its _amount_. Nothing is used up: the "
                        + "pattern only says what one run of your machine needs and makes.",
                "When ordering the result",
                "The _terminal_ marks a pattern's result as _producible_ even when none is in stock. Ordering it "
                        + "reserves the _ingredients_, the crane delivers them here, and the _product_ returns to the "
                        + "warehouse through a _warehouse input_ like any other item.",
                "When an ingredient is missing",
                "If another _pattern_ of the same aisle makes that ingredient, the whole _chain_ is planned the moment "
                        + "you click: _one order per step_, each at the station holding its pattern, and every "
                        + "_intermediate_ travels through a real _storage location_. A step that is waiting for an "
                        + "earlier one is handed _nothing_ at all. If something is really missing, the click is "
                        + "_refused_ and names the item.",
                "When a batch is lost",
                "If your machine takes the _ingredients_ and nothing comes back, the warehouse _stops making that "
                        + "item_ and the _ring_ of this block turns into a red _lamp_: nothing takes items back out of "
                        + "a machine, so it never quietly tries again. Look at the machine, then _Sneak-Right-Click_ "
                        + "this block or click the red row in its _screen_ to make the item again — you are told how "
                        + "many _ingredients_ were delivered and never came back.",
                "When looked at with Goggles",
                "Shows its _address_, its _patterns_, the running _production orders_ with their _state_ and the "
                        + "_buffered items_. While the warehouse has _stopped_ making something this station makes, it "
                        + "says how many products are held and what they cost.");

        tooltip(lang, "block.wareworks.warehouse_stock_keeper",
                "Holds the _stock rules_ of a warehouse _aisle_: one _item_ per row plus a _minimum_, a _maximum_ and "
                        + "a _reserve_. The three numbers govern three different directions, and an aisle may hold "
                        + "several keepers.",
                "When placed",
                "The _panel_ faces you: stand in the _aisle_ and place it into a _rack_ beside the aisle, like every "
                        + "other station.",
                "When setting a rule",
                "_Right-Click_ with an _empty hand_ to open it. Click a _row_ with an item to set what it governs, and "
                        + "_scroll_ on one of its three numbers to change it (_Shift_ for whole stacks, _Right-Click_ "
                        + "to switch it off). Nothing is used up: a row's item is only a name.",
                "Minimum: what comes in",
                "The warehouse _tries to keep_ this many. While it holds fewer, the comparator on this block calls for "
                        + "the item, so a _farm_ or a hand-built line runs exactly as long as it is needed.",
                "Minimum: the warehouse restocks",
                "If a _warehouse production_ of the same aisle has a _pattern_ for the item, the warehouse _orders it "
                        + "by itself_ while it is below the minimum — never spending what a _reserve_ protects. If a "
                        + "machine _swallows_ a batch and nothing comes back, that rule _stops ordering_ and waits for "
                        + "you: open it and click the rule's _mark_, or _Sneak-Right-Click_ the _warehouse production_ "
                        + "that makes the item, to let it try again.",
                "Maximum: what may be stored",
                "The warehouse stores _no more_ than this. Above it the _stacker crane_ stops accepting the item and "
                        + "a _warehouse input_ holding it _backs up on purpose_ — that is the rule working, not a jam.",
                "Reserve: what may go out",
                "The last items are kept from the warehouse's own _automation_: a _redstone request_ at a _warehouse "
                        + "output_ stops at them. _You_ are not stopped — a request at a _terminal_ is served down to "
                        + "the last item.",
                "When looked at with Goggles",
                "Shows its _address_, how many _rules_ it holds and how many of them are _below their minimum_, _at "
                        + "their maximum_, _down to their reserve_ or _paused_ after a lost batch.");

        tooltip(lang, "block.wareworks.warehouse_controller",
                "Manages one warehouse _aisle_: it gives the aisle its _letter_, finds its _storage locations_ and "
                        + "_stations_, keeps _count_ of the stored items and _plans_ the _stacker crane's_ jobs. It never "
                        + "moves items itself.",
                "When placed",
                "Place it directly _behind_ a _stacker crane_ while looking at the crane; the _display_ faces you.",
                "When using the value panel",
                "Hold _Right-Click_ on the _value panel_ to set the _aisle letter_ used in addresses such as _A-03-07R_.",
                "When looked at with Goggles",
                "Shows the _status_, the _aisle size_, the number of _storage locations_, _stations_ and "
                        + "_misaligned_ blocks, the _stored items_, the _crane's job_ and the last _planning result_.");

        tooltip(lang, "block.wareworks.stacker_crane",
                "The _dock_ of a _stacker crane_. The crane travels along the _aisle_ of _warehouse rails_ in front of "
                        + "it and _stores_ and _retrieves_ items for the _warehouse controller_ behind it.",
                "When placed",
                "The _aisle_ runs in the direction you look. Connect a _shaft_ to the _bottom_; shafts and cogs at "
                        + "the sides do not connect. A _wrench_ turns it only while the crane is _idle_ and _empty_.",
                "When powered by rotation",
                "Carries out the controller's _jobs_. Faster _rotation_ moves the crane faster; without rotation or when "
                        + "_overstressed_ it _pauses_ where it is and continues later.",
                "When using the value panel",
                "Hold _Right-Click_ on the _value panel_ to set the _mast height_: how many _levels_ the crane reaches.",
                "When looked at with Goggles",
                "Shows the _aisle length_, the _mast height_, whether a _controller_ is linked, the current _job_ and "
                        + "the _held items_.");

        tooltip(lang, "block.wareworks.warehouse_rail",
                "Lays out the _aisle_ of a _stacker crane_. The aisle is as long as the _straight line_ of rails in "
                        + "front of the crane's _dock_.",
                "When placed",
                "Runs in the direction you look. A _gap_, another block or a rail _across_ the aisle ends the aisle.");
    }

    /** Create item description: summary plus condition/behaviour pairs ({@code .tooltip.conditionN/behaviourN}). */
    private static void tooltip(BiConsumer<String, String> lang, String descriptionId, String summary,
                                String... conditionBehaviourPairs) {
        if (conditionBehaviourPairs.length % 2 != 0)
            throw new IllegalArgumentException("conditions and behaviours must come in pairs: " + descriptionId);
        String prefix = descriptionId + ".tooltip.";
        lang.accept(prefix + "summary", summary);
        for (int i = 0; i < conditionBehaviourPairs.length / 2; i++) {
            lang.accept(prefix + "condition" + (i + 1), conditionBehaviourPairs[2 * i]);
            lang.accept(prefix + "behaviour" + (i + 1), conditionBehaviourPairs[2 * i + 1]);
        }
    }
}
