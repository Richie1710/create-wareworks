package dev.wareworks.data;

import java.util.function.BiConsumer;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.ChunkKeepReason;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.station.HomePointStatus;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalListResult;
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
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.core.warehouse.NetworkStop;
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
        // M21 (issue #1, ADR-033): the lines a warehouse that bends shows instead. One aisle keeps the line above.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NETWORK_SIZE),
                "Warehouse: %1$s rails, %2$s aisles, mast %3$s high");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NETWORK_AISLES), "Aisles: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NETWORK_AISLES_MORE), "Aisles: %1$s, and %2$s more");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NETWORK_STOP), "Warehouse stops at %1$s: %2$s");
        // One reason each, never merged into one message: a warehouse can be ready and still stop at a branching rail,
        // and a player looking at the thing a message denies must never read a sentence that is false (the M5
        // NO_DOCK / DOCK_MISALIGNED lesson). END is generated too, so no reason can ever resolve to a raw key.
        for (NetworkStop stop : NetworkStop.values()) {
            lang.accept(WareworksLang.key(WareworksLang.networkStopKey(stop)), switch (stop) {
                case END -> "the rails end here";
                case UNLOADED -> "the next rail is in a chunk that is not loaded";
                case CLOSED -> "the next rail is closed with a wrench";
                case SECOND_DOCK -> "another stacker crane dock stands here, and a dock is a wall";
                case MAX_RAILS -> "the warehouse has as many rails as this server allows";
                case MAX_BRANCHES -> "the warehouse has as many aisles as this server allows";
                case MAX_JUNCTIONS -> "the warehouse has as many junctions as this server allows";
                case MAX_LENGTH -> "this aisle is as long as this server allows";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_ON_AISLE), "On aisle %1$s at position %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_NO_CONTROLLER), "No controller");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CONTROLLER_LINKED), "Controller linked");
        lang.accept(WareworksLang.key(WareworksLang.CRANE_MAST_HEIGHT), "Mast Height");
        lang.accept(WareworksLang.key(WareworksLang.CRANE_ROTATION_LOCKED),
                "The stacker crane is busy: it can only be turned without a job and with an empty grabber");
        lang.accept(WareworksLang.key(WareworksLang.RAIL_CLOSED),
                "Rail closed: no warehouse runs through it any more");
        lang.accept(WareworksLang.key(WareworksLang.RAIL_OPENED), "Rail opened: it joins the rails it touches again");
        // M25 (issue #15, ADR-038): the four sentences a naming click answers with. The quotes are part of the
        // sentence, so a name that is a single word still reads as a name and a name with a space still reads as one.
        //
        // Each is one WHOLE answer, because the client's HUD holds exactly one action-bar message at a time: a cut
        // name is told in a single sentence that still names its aisle, never in a second message that would replace
        // the first before anything was drawn (M25 review fix).
        lang.accept(WareworksLang.key(WareworksLang.AISLE_NAMED), "Aisle %1$s is now \"%2$s\"");
        lang.accept(WareworksLang.key(WareworksLang.AISLE_NAME_CLEARED), "Aisle %1$s has no name any more");
        lang.accept(WareworksLang.key(WareworksLang.AISLE_NAMED_CUT), "Aisle %1$s is now \"%2$s\" (shortened)");
        lang.accept(WareworksLang.key(WareworksLang.AISLE_NAME_NO_AISLE),
                "This is not part of a warehouse aisle yet");
        // M28 (issue #20, ADR-044): one sentence for a rule that is refused in both directions, because it is one
        // rule — the strength of a column never rises going upwards.
        lang.accept(WareworksLang.key(WareworksLang.BAY_COLUMN_REFUSED),
                "A rack bay may carry nothing stronger above it");
        // M30 (issue #21): the same rule on the fluid ladder, in its own sentence - the two ladders are separate, so a
        // player holding a tank is told about tanks.
        lang.accept(WareworksLang.key(WareworksLang.FLUID_BAY_COLUMN_REFUSED),
                "A fluid bay may carry nothing stronger above it");
        // M28 step 6: the gesture a bay does NOT carry. A right-click with an item puts that item in, so the aisle
        // naming a player learned on an interface would silently swallow the renamed item they held out at the bay.
        lang.accept(WareworksLang.key(WareworksLang.BAY_NO_NAMING),
                "Name an aisle at a warehouse controller or interface");
        // M30 step 6 (issue #21, D7): the third of the three places that say the break loses the fluid, at the one
        // moment a player is about to find out. It warns and never refuses, and it names the fluid and not the amount.
        lang.accept(WareworksLang.key(WareworksLang.FLUID_BAY_BREAK_LOSES),
                "Breaking this bay loses what is in it: %1$s");
        // M28 step 6: the rack bay's goggle lines. The header's argument is the block's own name, so "Brass" is said
        // once, by the block, instead of in three more lang keys that could drift from it.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RACK_BAY), "%1$s:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BAY_CONTENTS), "%1$s %2$s / %3$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BAY_CAPACITY), "Capacity: %1$s stacks");
        // The two lines that stand instead of "Accepts everything" on an unfiltered bay, because a bay accepts
        // everything exactly once: before the first item, and then only that one until it has drained.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BAY_LEARNED), "Holds %1$s until it is empty");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BAY_ACCEPTS_FIRST), "Takes the first item that arrives");
        // Worded for the closure and not for the neighbour (ADR-044): the flag can be true for a bay with nothing
        // stronger directly above it, in a column only a command could have built.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BAY_OVERLOADED),
                "The rack above this bay is overloaded");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BAY_MISALIGNED_HINT),
                "Turn the open front towards the aisle");
        // "Not part of an aisle" is a defect for every other member of a warehouse and a plain fact for a bay, which
        // is an early-game barrel long before there is a crane. One line says so, so nobody goes looking for a fault.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_BAY_NO_WAREHOUSE),
                "A rack bay works by hand with no warehouse");
        // M30 step 6 (issue #21): the fluid bay's own goggle rows. The header's argument is the block's own name, as
        // the rack bay's is; the misaligned hint is shared with the rack bay, because "turn the open front towards the
        // aisle" is the same sentence for a tank.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY), "%1$s:");
        // Counted in millibuckets, shown in buckets - with the one exception that keeps a bay holding 7 mB from
        // reading as empty, which a two-fraction-digit number format would otherwise produce (§3.9, D9).
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_CONTENTS), "%1$s %2$s / %3$s buckets");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_CONTENTS_SMALL),
                "%1$s %2$s mB of %3$s buckets");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_CAPACITY), "Capacity: %1$s buckets");
        // "Holds" and not "Filter", because the slot holds a CONTAINER and what is read from it is the FLUID inside:
        // a line saying "Filter: Lava Bucket" would describe the one reading this block never makes (D6).
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_FILTER), "Holds: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_LEARNED), "Holds %1$s until it has drained");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_ACCEPTS_FIRST),
                "Takes the first fluid that arrives");
        // A Create list or attribute filter, or an empty container: the slot names no fluid, so the bay is unfiltered
        // and the row below still says what it really does.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_FILTER_NO_FLUID),
                "That filter names no fluid");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_OVERLOADED),
                "The tanks above this bay are overloaded");
        // The second of the three places that say the break loses the fluid, shown only while there is something to
        // lose and standing directly under the contents row it is about (D7).
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_BREAK_LOSES),
                "Breaking it loses what is in it");
        // The one refusal of this block nothing else explains: 63 001 of 64 000 mB draws "63.00 / 64 buckets", which
        // looks like a bucket of room, and then a bucket does not fit because a container is emptied whole or refused
        // (D5). Shown only while that is really the case.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_NO_BUCKET_ROOM),
                "Less than a bucket of room: a whole bucket does not fit");
        // One of these two is shown ALWAYS, whichever way storage.fluidBayPipeExtraction stands: a rule about where
        // fluid may leave a warehouse must never be invisible (D4).
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW), "Pipes may draw from the back");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_PIPES_FILL),
                "Pipes may only fill from the back");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE),
                "A fluid bay works by hand with no warehouse");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ADDRESS), "Address: %1$s");
        // The name is in brackets behind the address, never instead of it: the address is what the terminal, the
        // crane's own lines and every report speak, and the name is the label a player put beside it.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ADDRESS_NAMED), "Address: %1$s (%2$s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MISALIGNED), "Misaligned");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_UNREACHABLE_AISLE),
                "The crane cannot reach this aisle");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MISALIGNED_HINT), "Turn the brass port away from the aisle");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER), "Filter: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER_NONE), "Accepts everything");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER_EMPTY),
                "Empty filter: this location accepts nothing");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_FILTER_SHADOWED),
                "Without effect: another storage location counts this inventory");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_PRIORITY), "Priority: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_CONTROLLER), "Warehouse Controller:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_LETTER), "Warehouse %1$s");
        // The letter keeps its place and the name follows it: the letter is what every other surface, and
        // /wareworks chunks, names this warehouse by (M25, issue #15, ADR-038).
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_LETTER_NAMED), "Warehouse %1$s — %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_AISLE_NAME_HINT),
                "Right-click with a renamed item to name an aisle");
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
                "Chunk loading: none (server limit: %1$s warehouses)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CHUNK_LOADING_AT_COLLECT_LIMIT),
                "Chunk loading: none (collecting limit: %1$s warehouses)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CHUNK_LOADING_TOO_MANY),
                "Chunk loading: none (this warehouse needs %1$s of %2$s chunks)");
        for (ChunkKeepReason reason : ChunkKeepReason.values()) {
            if (reason == ChunkKeepReason.NONE)
                continue;
            lang.accept(WareworksLang.key(reason.langKey()), switch (reason) {
                case CRANE_JOB -> "crane job";
                case OPEN_REQUESTS -> "open requests";
                case PRODUCTION_ORDERS -> "production orders";
                case COLLECTING -> "collecting from a machine";
                case RELEASING -> "idle, letting go";
                case AT_LEVEL_LIMIT -> "as many warehouses hold chunks as the server allows";
                // Its own reason rather than AT_LEVEL_LIMIT: the collect opt-in is a second setting, and an aisle
                // queued behind it would otherwise look byte-for-byte like an idle one (M19 review).
                case AT_COLLECT_LIMIT -> "as many warehouses collect with held chunks as the server allows";
                case TOO_MANY_CHUNKS -> "this warehouse needs more chunks than the server allows";
                // Two things reach this state: the longest allowed hold running out, and an operator's
                // "/wareworks chunks release". The line must be true of both, so it says what the aisle IS doing
                // rather than guessing why; the cause is in the server log and in the command's own answer.
                case GAVE_UP -> "let go; holds again when its work changes";
                case NONE -> throw new IllegalStateException("skipped above");
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_HEADER), "Chunks held in %1$s:");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_ROW),
                "  %1$s: warehouse %2$s, %3$s chunk(s), %4$s, held for %5$s s");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_ROW_UNCLAIMED),
                "  %1$s: %2$s chunk(s) reinstated from the save, no warehouse controller has claimed them yet");
        // The other half of the answer: a warehouse that holds NOTHING because its footprint is over the per-warehouse
        // cap, with the number it would need. "Needs 14" next to "the limit is 10" is the only form an operator can act
        // on, and a warehouse that bends meets this cap without anybody having changed a setting (M21, ADR-033).
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_ROW_OVER_CAP),
                "  %1$s: warehouse %2$s, holds nothing - this warehouse needs %3$s chunk(s) and the limit is %4$s");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_NONE), "No Wareworks warehouse is holding any chunks");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_TOTAL),
                "Wareworks holds %1$s chunk(s) in %2$s warehouse(s) over %3$s dimension(s)");
        // Two numbers, because the first one is NOT a total: Wareworks takes block tickets, so that is the number the
        // rows above are comparable with, while vanilla /forceload and entity tickets live in two other stores
        // entirely (M19 review).
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_RAW),
                "  %1$s has %2$s chunk(s) force-loaded by block tickets (all mods), %3$s in total "
                        + "(entity tickets and /forceload included)");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_RELEASED), "Released the chunks of the warehouse at %1$s");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_NOT_HELD),
                "No warehouse at %1$s is holding chunks in %2$s");
        lang.accept(WareworksLang.key(WareworksLang.COMMAND_CHUNKS_RELEASED_ALL),
                "Released the chunks of %1$s warehouse(s) in %2$s dimension(s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATIONS), "Inputs: %1$s, outputs: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MISALIGNED_COUNT), "Misaligned blocks: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ITEM_TYPES), "Item types: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ITEMS_STORED), "Items stored: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_TYPES), "Fluid types: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_STORED), "Fluid stored: %1$s buckets");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FLUID_STORED_SMALL), "Fluid stored: %1$s mB");
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
        // The row is one of the window's fixed 216 px rows and it leads with the item's name, so everything after the
        // name is what a long name pushes off the end. "Stopped: %1$s products. Click to make them again" measured 233
        // px with no name in it at all, and the German sentence 313; the short form leaves the name its room in both.
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_STOPPED_LINE),
                "Stopped: %1$s - click to resume");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_STOPPED_LINE_MANY),
                "Stopped: %1$s products - click to resume");
        lang.accept(WareworksLang.key(WareworksLang.PRODUCTION_STOPPED_ITEM), "%1$s: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STOCK_RULES), "Stock rules: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RULES_BELOW_MINIMUM), "Below minimum: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RULES_AT_MAXIMUM), "At maximum: %1$s");
        // M21 (issue #1, ADR-034): the home point says what it is doing in one sentence, and every way it can fail to
        // be used has its own — a player who placed a block and sees nothing happen must be able to read why.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_HOME_POINT), "Warehouse Home Point:");
        for (HomePointStatus status : HomePointStatus.values()) {
            lang.accept(WareworksLang.key(status.langKey()), switch (status) {
                case NO_WAREHOUSE -> "Not part of a warehouse";
                case SERVING -> "The stacker crane waits here";
                case SECOND -> "Without effect: this warehouse already has a home point";
                case UNREACHABLE -> "Without effect: the crane cannot drive here";
                case SINGLE_AISLE -> "Without effect: on one aisle the crane waits where it is";
                case SWITCHED_OFF -> "Without effect: this server switched returning home off";
            });
        }
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
        // Packages at the out door (M26, issue #18). "Packager" and "logistics network" are Create's own names for
        // the blocks a player holds, so these lines speak of them exactly as the game does.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_PACKAGE_HANDOVER), "Hands over as a package");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_PACKAGE_ADDRESS), "Addressed to: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_PACKAGE_NO_ADDRESS),
                "No address — hang a sign on the Packager");
        // The one failure nothing else in the game diagnoses, so the longest line of the feature earns its words.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PORT_PACKAGER_LINKED),
                "The Packager is linked to a logistics network and ignores redstone");
        // Packages at the in door (M26, issue #18). The refusal names both numbers, because the cliff is the pair:
        // Create consumes a package whole, so a package of three stacks needs room for all three at once. Both counts
        // come last in their phrase, because 1 is an ordinary value for either and no plural may disagree with it.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_INPUT_PACKAGE_UNPACKING), "Takes packages apart");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_INPUT_PACKAGES_OPENED), "Packages opened: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_INPUT_PACKAGE_REFUSED),
                "Last package refused: stacks in it %1$s, free slots %2$s");
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
        // The crane's throughput (M25, issue #16, ADR-039). GOGGLES_PERCENT is the only value in the whole mod with a
        // percent sign in it, which is why every share goes through it instead of writing the sign five times.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_PERCENT), "%1$s%%");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_BUSY), "Busy: %1$s of the last minute");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_BUSY_PARTIAL), "Busy: %1$s of the last %2$s s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_BLOCKED), "Blocked: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_BREAKDOWN), "Travel %1$s · at the rack %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_BREAKDOWN_TURNING),
                "Travel %1$s · turning %2$s (%3$s corners) · at the rack %4$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_TRIPS), "Trips: %1$s · items: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_LAST_PLAN), "Last planning: %1$s");
        for (NoJobReason reason : NoJobReason.values()) {
            lang.accept(WareworksLang.key(WareworksLang.noJobReasonKey(reason)), switch (reason) {
                // No origin is named in these four (M18 review): a collecting port reports the same three of them as a
                // warehouse input, and a collect-only aisle need not contain an input at all.
                case WAREHOUSE_FULL -> "no storage location accepts these items";
                case NO_MATCHING_FILTER -> "no storage location takes these items, whatever room it has";
                case PORT_FULL -> "an accepting port was the only place left for these items and it is full";
                case AT_MAXIMUM -> "a stock rule for these items is at its maximum";
                case OUTPUT_FULL -> "an output is full";
                case PRODUCTION_FULL -> "a production station cannot take more ingredients";
                case NOT_IN_STOCK -> "a requested item is not in stock";
                case LOCATION_UNAVAILABLE -> "an output station is not loaded or no longer there";
                case UNREACHABLE -> "the crane cannot drive to the aisle these items belong on";
                case BUDGET_EXHAUSTED -> "still searching";
                case COLLECT_SOURCE_EMPTY -> "a machine hands out nothing its port may fetch";
                case NO_WORK -> "nothing to do";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SEARCH), "Search items");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SORT), "Sorting: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SORT_NEXT), "Click: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SORT_NO_HISTORY),
                "nothing requested yet, so amounts decide");
        // One short label per order and one sentence that says what it does: the label sits beside the icon and has to
        // fit the terminal's row, the sentence is where a cycling button explains itself (M24, issue #17).
        for (TerminalSort sort : TerminalSort.values()) {
            lang.accept(WareworksLang.key(sort.langKey()), switch (sort) {
                case AMOUNT -> "most available first";
                case USED -> "most used first";
                case NAME -> "by name";
            });
            lang.accept(WareworksLang.key(sort.detailKey()), switch (sort) {
                // "warehouse", not "aisle": the list is the controller's stock index, which spans every aisle of the
                // warehouse since M22, and every other string of this screen says so (M24 review fix). The wording is
                // the short one because "what the warehouse holds most of comes first" measures 233 px against the
                // window's own 216 (WarehouseTerminalScreen#sortTooltipFits).
                case AMOUNT -> "what the warehouse has most of first";
                case USED -> "what you request most often comes first";
                case NAME -> "alphabetical, to find an item you know";
            });
        }
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_ONLY_IN_STOCK), "Showing only what is available");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_SHOW_ALL), "Showing everything the warehouse holds");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_AMOUNT_HINT),
                "Click an item for this amount, Shift for a stack, Ctrl for everything, Alt to skip the question");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_EMPTY), "The warehouse holds no items");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_NO_MATCH), "No item matches the search");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LOADING), "Reading the stock...");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_NO_AISLE), "Not part of a warehouse");
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
        // A portion of a clipboard order raised this one, not a click: it has to say which item it is about, and that
        // machines would be started for it (M23 review fix).
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_LIST_ITEM), "From the clipboard list: %1$s %2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CONFIRM_PRODUCE),
                "Making it starts a production order; %1$s would be made in total.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_BUFFER), "Delivered here");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_NOT_SHOWN), "+%1$s not shown");
        // The clipboard order (M23, issue #19): a whole list ordered at once, worked off in portions and ticked off on
        // the clipboard as it is delivered.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_TERMINAL_LIST),
                "Clipboard order: %1$s of %2$s entries (%3$s)");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_TERMINAL_LIST_LEFT), "Still to fetch: %1$s");
        // The craft order (issue #24, slice 2): the crafting panel's Fetch, one line of numbers while it is open.
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_TERMINAL_CRAFT), "Crafting fetch: %1$s of %2$s (%3$s)");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_LABEL), "Clipboard");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_SLOT),
                "A clipboard with a list of items - the warehouse fetches it and ticks off what it delivered");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_EMPTY),
                "Put a clipboard in to order a whole list");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_FETCH), "Fetch the whole list");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CANCEL), "Stop working the list off");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_RESUME), "Try the rest of the list again");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_ANSWER), "Answer the question about this list");
        // Short on purpose: the status line owns one 202 pixel row and has to hold four numbers plus the state
        // ({@code docs/warehouse-system.md} §3.4.2), so it names them instead of describing them.
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_STATUS), "List %1$s/%2$s, %3$s left (%4$s)");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_STATUS_DONE), "List done: %1$s/%2$s");
        // Count last, and no bare plural noun after a number that is commonly 1: these four sentences read "1 entries"
        // and "1 items" before the M23 review fix, and Minecraft's lang format has no plural selection.
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_TRUNCATED),
                "Entries not taken from the clipboard: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CONFIRM_TITLE), "Fetch this list?");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CONFIRM_SHORT),
                "%1$s of the %2$s items on the list are not in stock.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CONFIRM_PRODUCE),
                "In total %1$s would be produced for the list.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CONFIRM_IMPOSSIBLE),
                "Entries that cannot be had at all and stay unticked: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CONFIRM_ENTRY), "%1$s: %2$s of %3$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CONFIRM_MORE), "More entries: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_LIST_CONFIRM_ASK), "Fetch what there is?");
        // One word each, because this is a **column**, not a sentence: it is drawn inside the parentheses of
        // "List 4/9, 320 left (…)", and that frame already spends 152 of the status row's 216 px on three numbers
        // whose ceilings are config values (maxTerminalListEntries, maxTerminalRequestAmount). The phrases these used
        // to be ("waiting for your answer", "given up for now", 121 and 85 px against a 64 px column) were cut in
        // English and far worse in German. What a player is to *do* about the state is one hover away and in full on
        // the list button beside the row, whose icon and tooltip follow the very same state
        // (TERMINAL_LIST_ANSWER/RESUME), and in the clipboard's own slot tooltip.
        for (ListOrderState state : ListOrderState.values()) {
            lang.accept(WareworksLang.key(state.langKey()), switch (state) {
                case RUNNING -> "fetching";
                case ASKING -> "asking";
                case PARKED -> "paused";
                case DONE -> "done";
            });
        }
        for (TerminalListResult result : TerminalListResult.values()) {
            lang.accept(WareworksLang.key(result.langKey()), switch (result) {
                case STARTED -> "Working the list off";
                case ASKING -> "Please answer the question about this list";
                case ANSWERED -> "Carrying on with the list";
                case QUESTION -> "Here is the question about this list again";
                case DECLINED -> "The rest of the list is left for now";
                case RESUMED -> "Trying the rest of the list again";
                case CANCELLED -> "The list was given up";
                case NO_CLIPBOARD -> "Put a clipboard into the slot first";
                case EMPTY_LIST -> "This clipboard asks for nothing";
                case ALREADY_RUNNING -> "A list is already being worked off here";
                case NOT_RUNNING -> "No list is being worked off here";
                case NO_AISLE -> "This terminal is not part of a warehouse";
                case OUT_OF_REACH -> "You are too far away";
            });
        }
        // Crafting at the terminal (M32, issue #24, ADR-059). The panel is 54 px wide, so it holds numbers and the one
        // title word; every sentence is a tooltip line or the status row and fits its 216 px in English and German
        // (TerminalCraftTextWidthTest). "Available" and not "in stock": the number is what a click could be granted,
        // the stock tooltip's own "Available" (TerminalCraftViewPayload.KeyView#available).
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_TITLE), "Crafting");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_TOGGLE), "Crafting grid");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_TOGGLE_HINT),
                "Craft by hand from what was delivered");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_CLEAR), "Clear the grid");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_PER_CRAFT), "%1$s per craft, %2$s here");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_AVAILABLE), "%1$s available");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_SHORT), "Only %1$s available, %2$s needed");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_MISSING),
                "None available, and nothing here makes it");
        // Issue #24, slice 3: an ingredient a production station of the aisle has a pattern for. The Fetch measures
        // the chain and asks before anything is made; the grid's own recipe is never made by the warehouse (ADR-059).
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_MAKEABLE), "Can be made here: Fetch asks first");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_CELL_EMPTY),
                "Right-click a listed item, then click here");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_CELL_EMPTY_SHIFT),
                "Shift + right-click: into the next free cell");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_CELL_CLEAR), "Right-click to clear");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_STOCK_HINT), "Right-click: into the crafting grid");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_READY), "Ready to craft: x%1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_ONCE), "Click: craft once");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_ALL), "Shift-click: craft all");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_PARTIAL), "Crafted %1$s x%2$s. %3$s");
        // The craft order (issue #24, slice 2): Fetch brings the grid's ingredients by crane, topped up against what
        // the buffer already holds. Where a line names an item, the name gives way first; the question's lines wrap.
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_FETCH), "Fetch the ingredients for %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_TO_FETCH), "%1$s: %2$s to fetch");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_FETCH_HINT),
                "The amount field sets how many crafts");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_ANSWER), "Answer the question about this fetch");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_RESUME), "Try the rest of the fetch again");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_CANCEL), "Stop fetching the ingredients");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_CANCEL_MARK),
                "Click to give up; what arrived stays here");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_PROGRESS), "Delivered: %1$s of %2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_IN_FLIGHT), "On the way: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_GRID_LOCKED),
                "Cancel the fetch to change the recipe");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_DONE), "Delivered: click the result to craft");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_STATUS_RUNNING), "Fetching for %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_STATUS_ASKING),
                "Fetch for %1$s x%2$s: please answer");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_STATUS_PARKED), "Fetch for %1$s x%2$s is paused");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_CONFIRM_ITEM), "For the crafting grid: %1$s %2$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_QUESTION_TITLE),
                "Fetch the ingredients for %1$s x%2$s?");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_QUESTION_ROOM), "Only x%1$s of x%2$s fit here.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_QUESTION_STOCK),
                "Only x%1$s of x%2$s are available:");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_QUESTION_LINE),
                "%1$s: %2$s available, %3$s needed");
        // The chain offer (slice 3): what production makes first, and the fetch the racks alone could serve. The hint
        // is a line of its own, so neither line has to wrap with its number at the widest.
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_QUESTION_MADE), "%1$s x%2$s will be made first.");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_QUESTION_WITHOUT), "Without making: x%1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_CRAFT_QUESTION_WITHOUT_HINT),
                "Set x%1$s to fetch only that.");
        // One sentence per way a craft click can end. The ones that name an item take it as %1$s; "crafted" takes the
        // result and the number of items made. A refusal that stops a Shift-click after some crafts stands after
        // TERMINAL_CRAFT_PARTIAL, so each is a whole sentence of its own.
        // The Fetch button's answers (issue #24, slice 2) follow the same rule: an item as %1$s, a number of crafts as
        // %2$s. Every one of them fits the status row with the widest number (TerminalCraftTextWidthTest).
        // A recipe laid from a recipe viewer (slice 4) names its result as %1$s, like a craft does.
        for (TerminalCraftResult result : TerminalCraftResult.values()) {
            lang.accept(WareworksLang.key(result.langKey()), switch (result) {
                case CRAFTED -> "Crafted %1$s x%2$s";
                case NO_RECIPE -> "The grid makes nothing";
                case NOT_IN_BUFFER -> "Not enough %1$s here";
                case NO_ROOM -> "No room for %1$s";
                case LOCKED -> "You have not unlocked this recipe";
                case OUT_OF_REACH -> "You are too far away";
                case GRID_EMPTY -> "Lay a recipe first";
                case STARTED -> "Fetching for %1$s x%2$s";
                case ASKING -> "Answer the question about this fetch";
                case CHANGED -> "The warehouse changed in between";
                case NOTHING_TO_FETCH -> "Already here: click the result to craft";
                case NOT_ENOUGH -> "Can't fetch: not enough %1$s";
                case NO_ROOM_FOR_ONE -> "No room here for one more craft";
                case ALREADY_RUNNING -> "Already fetching ingredients here";
                case NO_AISLE -> "This terminal is not part of a warehouse";
                case ANSWERED -> "Carrying on with the fetch";
                case DECLINED -> "The rest of the fetch is left for now";
                case RESUMED -> "Trying the rest of the fetch again";
                case CANCELLED -> "The fetch was given up";
                case NOT_RUNNING -> "No ingredients are being fetched here";
                case LAID -> "Laid the recipe for %1$s";
                case NOT_LAID -> "This recipe cannot be laid here";
            });
        }
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
                case INERT -> "Without effect: this warehouse already applies its limit of rules";
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

        // Display Link sources (M14, M25, M30). The names must stay in step with the registry paths in
        // WareworksDisplaySources: Create builds a source name as "wareworks.display_source.<path>". The line texts are
        // deliberately short, because a row of four nixie tubes shows eight characters.
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_AISLE_SUMMARY), "Warehouse Summary");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_STOCK_LIST), "Stock List");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_FILTERED_STOCK), "Stock of the Filtered Item");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_CRANE_STATUS), "Crane Status");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_CRANE_THROUGHPUT), "Crane Throughput");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_SOURCE_FLUID_STOCK), "Fluid Stock");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_AISLE), "Warehouse %1$s: %2$s");
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
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_FLUIDS), "Fluids: %1$s · %2$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_AISLES), "Aisles: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_AISLES_CUT), "Aisles: %1$s (cut short)");
        // M25 (issue #15, ADR-038): the aisles a player named, letters and all, last of the optional lines and only
        // while there is a name. The second form carries how many names the row had no characters left for: a name is
        // up to sixteen characters of a player's own choosing, so the row is bounded by the target's width and not
        // only by an entry count, and a list it had to shorten says so (M25 review fix).
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_NAMES), "Names: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_LINE_NAMES_MORE), "Names: %1$s (+%2$s)");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_AISLE_NO_AISLE), "No warehouse");
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
        // The crane's throughput on a board (M25, issue #16, ADR-039): four fixed rows, so a four-row board drops
        // nothing, and one line instead of all four while the rolling minute is not full yet. The shares go through
        // GOGGLES_PERCENT like every other share in the mod, so no row carries a percent sign of its own.
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_THROUGHPUT_LINE_TRIPS), "Trips: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_THROUGHPUT_LINE_ITEMS), "Items: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_THROUGHPUT_LINE_BUSY), "Busy: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_THROUGHPUT_LINE_TURNING), "Turning: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_THROUGHPUT_MEASURING), "Measuring");
        lang.accept(WareworksLang.key(WareworksLang.DISPLAY_THROUGHPUT_NO_CRANE), "No crane");

        for (RequestRejection rejection : RequestRejection.values()) {
            lang.accept(WareworksLang.key(rejection.langKey()), switch (rejection) {
                case NO_CONTROLLER -> "not part of a warehouse with a controller";
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
                "Turns a _chest_ or any other _inventory_ into a storage location. _Filter_ and _priority_ at the "
                        + "filter slot.");

        // The three rack bays (M28, issue #20) and the two fluid bays (M30, issue #21). Each kind is written once
        // with its two tier differences passed in, so a player reading two tiers in JEI sees at a glance that only
        // the capacity and the column rule changed.
        rackBayTooltip(lang, "rack_bay_wood", "64 stacks", "wood carries only wood");
        rackBayTooltip(lang, "rack_bay_andesite", "256 stacks", "andesite never carries brass");
        rackBayTooltip(lang, "rack_bay_brass", "1024 stacks", "brass carries any bay");
        fluidBayTooltip(lang, "fluid_bay_copper", "64 buckets", "copper carries only copper");
        fluidBayTooltip(lang, "fluid_bay_brass", "256 buckets", "brass carries copper and brass");

        tooltip(lang, "block.wareworks.warehouse_input",
                "Where _items_ enter the warehouse. Can be filled by _belts_, _funnels_ and _Mechanical Arms_.");

        tooltip(lang, "block.wareworks.warehouse_output",
                "Hands out _items_ on a _redstone_ signal or takes the warehouse's _overflow_. Works with _funnels_ "
                        + "and _Mechanical Arms_.");

        tooltip(lang, "block.wareworks.warehouse_terminal",
                "Orders _items_ from the connected _warehouse_. Works with _funnels_ and _Mechanical Arms_.",
                "With a clipboard",
                "A filled-in _clipboard_, such as a _Schematicannon's_ material list, orders the whole list at "
                        + "once.");

        tooltip(lang, "block.wareworks.warehouse_production",
                "Supplies your _machines_ with ingredients from a _pattern_. Works with _funnels_ and a _Mechanical "
                        + "Arm_.",
                "When a batch is lost",
                "The ring turns _red_; _Sneak-Right-Click_ restarts it.");

        tooltip(lang, "block.wareworks.warehouse_stock_keeper",
                "Sets a _minimum_, a _maximum_ and a _reserve_ of the warehouse for each item.");

        tooltip(lang, "block.wareworks.warehouse_home_point",
                "Where the crane waits when it has nothing to do.",
                "When there is more than one",
                "A warehouse has only _one_; any further one lights up _red_.");

        tooltip(lang, "block.wareworks.warehouse_controller",
                "Runs a _warehouse_. Goes directly _behind_ the _stacker crane_.");

        tooltip(lang, "block.wareworks.stacker_crane",
                "The _dock_ of the stacker crane. Needs _rotation_ through a _shaft_ from _below_; nothing connects "
                        + "at the sides.",
                "When using the value panel",
                "Hold _Right-Click_ to set the _mast height_.");

        tooltip(lang, "block.wareworks.warehouse_rail",
                "Lays out the _aisles_ the crane drives on.",
                "When using a Wrench",
                "_Closes_ the rail, keeping two warehouses that touch apart.");
    }

    /**
     * Create item description of one rack bay ({@code docs/warehouse-system.md} §3.8). The three tiers share every
     * word but {@code stacks} and {@code carries}: the material decides how much a bay holds and what it may carry
     * above it, and nothing else about the block changes with it. Like every description it says what the block is
     * and the one thing the Ponder scenes do not show (issue #22); the goggles and the scenes carry the rest.
     *
     * @param stacks   how much one bay of this tier holds, as it is written in the summary
     * @param carries  the column rule for this tier, as it is written after "Nothing stronger on top:"
     */
    private static void rackBayTooltip(BiConsumer<String, String> lang, String block, String stacks, String carries) {
        tooltip(lang, "block.wareworks." + block,
                "A storage location for _one item type_, " + stacks + ". Works _by hand_ too, with no crane at all.",
                "When placed",
                "Nothing _stronger_ on top: " + carries + ".");
    }

    /**
     * Create item description of one fluid bay ({@code docs/warehouse-system.md} §3.9). The two tiers share every word
     * but {@code buckets} and {@code carries}, as the three rack bays do.
     * <p>
     * It is the <b>first of the three places that say breaking a bay loses the fluid</b> (D7) — the goggle line and
     * the warning on the first punch are the other two — and it is the only one of the three a player reads <i>before
     * the block is ever placed</i>, so the "When broken" pair stays however short the rest gets.
     *
     * @param buckets how much one bay of this tier holds, as it is written in the summary
     * @param carries the column rule for this tier, as it is written after "Nothing stronger on top:"
     */
    private static void fluidBayTooltip(BiConsumer<String, String> lang, String block, String buckets, String carries) {
        tooltip(lang, "block.wareworks." + block,
                "A storage location for _one fluid_, " + buckets + ". _Pipes_ connect at the back.",
                "When placed",
                "Nothing _stronger_ on top: " + carries + ".",
                "When broken",
                "_The fluid is lost._");
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
