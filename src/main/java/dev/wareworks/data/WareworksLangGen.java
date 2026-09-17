package dev.wareworks.data;

import java.util.function.BiConsumer;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.production.ProductionOrderState;
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
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_WAREHOUSE_CONTROLLER), "Warehouse Controller:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_AISLE_LETTER), "Aisle %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_READY), "Ready");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_NO_DOCK), "No stacker crane in front");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_DOCK_MISALIGNED),
                "The stacker crane in front faces another way");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATUS_NO_RAILS), "No warehouse rails in front of the crane");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STORAGE_LOCATIONS), "Storage locations: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_FILTERED_LOCATIONS), "Filtered locations: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_STATIONS), "Inputs: %1$s, outputs: %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_MISALIGNED_COUNT), "Misaligned blocks: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ITEM_TYPES), "Item types: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_ITEMS_STORED), "Items stored: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.CONTROLLER_AISLE_LETTER), "Aisle");
        lang.accept(WareworksLang.key(WareworksLang.CONTROLLER_AISLE_LETTER_ROW), "Letter");
        lang.accept(WareworksLang.key(WareworksLang.INTERFACE_STORE_FILTER), "Stored Items");
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
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_ROUTE), "From %1$s to %2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_HOLDING), "Holding:");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_CRANE_HEAD_EMPTY), "Grabber empty");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_LAST_PLAN), "Last planning: %1$s");
        for (NoJobReason reason : NoJobReason.values()) {
            lang.accept(WareworksLang.key(WareworksLang.noJobReasonKey(reason)), switch (reason) {
                case WAREHOUSE_FULL -> "no storage location accepts the input items";
                case NO_MATCHING_FILTER -> "no storage location has a filter that accepts the input items";
                case OUTPUT_FULL -> "an output is full";
                case PRODUCTION_FULL -> "a production station cannot take more ingredients";
                case NOT_IN_STOCK -> "a requested item is not in stock";
                case LOCATION_UNAVAILABLE -> "an output is not reachable";
                case BUDGET_EXHAUSTED -> "still searching";
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
                "Click an item for this amount, Shift for a stack, Ctrl for everything");
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
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCIBLE), "Can be produced here");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCIBLE_AMOUNT), "Can be made now: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCTION), "Production");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_ORDER_LOST), "%1$s, not recovered");
        lang.accept(WareworksLang.key(WareworksLang.TERMINAL_PRODUCING), "Requested %1$s x%2$s, producing %3$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_DELIVERED_ITEMS), "Delivered so far: %1$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RESERVED_INCOMING), "Incoming: %1$s x%2$s");
        lang.accept(WareworksLang.key(WareworksLang.GOGGLES_RESERVED_OUTGOING), "Reserved for pickup: %1$s x%2$s");
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
                "When looked at with Goggles",
                "Shows its _address_, its _filter_, the _attached inventory_, its _used slots_, the most stored _items_ "
                        + "and the items _reserved_ for a running crane job.");

        tooltip(lang, "block.wareworks.warehouse_input",
                "A _station_ of a warehouse _aisle_ where items _enter_ the warehouse. It _buffers_ arriving items until "
                        + "a _stacker crane_ stores them.",
                "When placed",
                "The _opening_ faces you: stand in the _aisle_ and place it into a _rack_ beside the aisle.",
                "When items arrive",
                "Accepts items from _belts_, _funnels_, _chutes_ and _hoppers_. The _stacker crane_ carries them to a free "
                        + "_storage location_; automation can only _insert_, never take items out.",
                "When looked at with Goggles",
                "Shows its _address_ and the _buffered items_.");

        tooltip(lang, "block.wareworks.warehouse_output",
                "A _station_ of a warehouse _aisle_ where retrieved items _leave_ the warehouse. _Funnels_, _chutes_ and "
                        + "_hoppers_ can pull them out.",
                "When placed",
                "The _opening_ faces you: stand in the _aisle_ and place it into a _rack_ beside the aisle.",
                "When setting the filter",
                "Click the _filter slot_ with the item to request. It sits on the _top_, the _back_ and both _side_ "
                        + "faces, never in the _aisle opening_, where the crane reaches in. Hold _Right-Click_ on it to "
                        + "set the _amount_, at most one _stack_.",
                "When powered by Redstone",
                "Each _pulse_ requests the filter item from the _warehouse controller_: up to the set _amount_, at most "
                        + "what is _in stock_. The _stacker crane_ then brings the items here; pulse again for more.",
                "When looked at with Goggles",
                "Shows its _address_, the _buffered items_, the _pending request_ with the items _delivered_ so far, "
                        + "and why the last request was _refused_.");

        tooltip(lang, "block.wareworks.warehouse_terminal",
                "A _station_ of a warehouse _aisle_ with a _screen_: ask for items here and the _stacker crane_ fetches "
                        + "them. _Funnels_, _chutes_ and _hoppers_ can pull them out again.",
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
                "When looked at with Goggles",
                "Shows its _address_, the _buffered items_, the _pending request_ with the items _delivered_ so far, "
                        + "and why the last request was _refused_.");

        tooltip(lang, "block.wareworks.warehouse_production",
                "A _station_ of a warehouse _aisle_ that feeds your _machines_. Define a _pattern_ here, and the "
                        + "_stacker crane_ brings its _ingredients_ to this block; your own _funnel_, _chute_ or "
                        + "_belt_ carries them into the machine. Wareworks never crafts anything itself.",
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
                "When looked at with Goggles",
                "Shows its _address_, its _patterns_, the running _production orders_ with their _state_ and the "
                        + "_buffered items_.");

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
