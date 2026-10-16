package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlock;

import dev.wareworks.client.gui.WarehouseProductionScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.TerminalPreferences;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.terminal.TerminalAmounts;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.core.terminal.TerminalUsage;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "terminal": the warehouse terminal's screen, driven like a player ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * It builds one aisle with a powered crane, a warehouse terminal beside the dock and a rack of chests holding
 * {@value #ITEM_TYPES} item types (from single items to several thousand, so the compact counts and the scrollbar are
 * on screen), then opens the real screen through {@code WarehouseTerminalBlockEntity#openScreen} and shoots it at five
 * moments: the full list, the list with a search typed character by character, right after a request was accepted,
 * after the same item was clicked {@value #MERGE_CLICKS} more times (the M7 merging case,
 * {@code docs/warehouse-system.md} §7.2: the status line names the pending total and "Open requests" stays 1), and
 * after the crane delivered the items into the terminal's buffer slots.
 * <p>
 * It then asks for {@link #USAGE_REQUESTS} item types several times each ({@link #usageSteps}), so that "most used" is
 * a <b>ranking</b> and not one favourite, presses the <b>sort button</b> through the mouse handler and shoots each of
 * the three orders, in English and in German (M24, issue #17, {@link #sortSteps}): the icon, the tooltip and the list
 * the order produces are asserted before every shot, the <b>server</b> checks the first rows of every order against its
 * own request counts, and so is what must not happen when the server pushes new counts at a scrolled grid. Finally the
 * world is saved, left and opened again ({@link #reloadSteps}), and the terminal has to come back in the order this
 * player chose, with the counts behind it.
 * <p>
 * Last of all it chooses a <b>package door</b> with the screen's door button (M27, issue #18, {@link #doorSteps}): a
 * requesting port across the aisle with a Create Packager at its back and a sign naming it. Real clicks, a right-click
 * and the wheel on the button; the gold cues on the title row and the stock cells; the choice kept through a fold of the
 * crafting panel and a re-layout; a door click that raises a question, which names the door and which the wheel cannot
 * change; a door click the server answers, delivered into the door's port and not into the terminal; the standing line
 * about the door while its request waits; "here" again on the next screen; and the choice falling back to here when the
 * Packager is broken.
 * <p>
 * The camera stands inside the aisle, about two blocks from the terminal: a screen closes itself as soon as the player
 * leaves the vanilla container range, so the shots also prove that the menu stays open while the crane works. The second
 * render pass only opens the screen once and takes a single shot, because a GUI does not depend on Flywheel.
 */
public final class TerminalVisualScenario implements VisualScenario {
    public static final String NAME = "terminal";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    /** The terminal sits beside the dock, on the right rack side, so the camera can see it and the aisle behind it. */
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    /** The production station beside it: what makes the terminal offer an item the aisle does not hold (M11). */
    private static final RackPosition PRODUCTION = RackPosition.of(2, 0, Side.RIGHT);
    /** The stock keeper above the terminal: what puts rule badges into the terminal's grid (M15, issue #3). */
    private static final RackPosition KEEPER = RackPosition.of(1, 1, Side.RIGHT);
    /**
     * The package door (M27, issue #18): a requesting port across the aisle from the production station, with a Create
     * Packager at its back and a sign beside the Packager. Nothing triggers the Packager, so whatever the crane brings
     * stays in the port, where the run can count it.
     */
    private static final RackPosition DOOR = RackPosition.of(2, 0, Side.LEFT);
    /** What the door's sign says, and so what the screen calls the door. */
    private static final String DOOR_ADDRESS = "Base North";
    /** The terminal and the door: the output stations the controller records. */
    private static final int OUTPUT_STATIONS = 2;
    /** What the door's last click asks for, while the crane stands still, so the request waits on screen. */
    private static final ItemKey DOOR_ITEM = ItemKey.of(Items.COBBLESTONE);
    private static final String DOOR_ITEM_SEARCH = "cobble";
    /** A search that keeps the item whose last items are all reserved, so a click on it raises a question. */
    private static final String RESERVED_SEARCH = "emerald";
    /** How long a door's answer stays in the status row, and a little more: then the standing line has the row. */
    private static final int FEEDBACK_GONE_TICKS = 120;
    private static final int STORAGE_FIRST_POSITION = 3;
    private static final int STORAGE_LEVELS = 2;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    /**
     * One item in the racks with a <b>long</b> name, so the three orders (M24, issue #17) can be checked against the
     * window's fixed row width with a name that really is too long for one: with nothing but "Lapis Lazuli" in the
     * aisle the check would pass without ever exercising anything.
     */
    private static final Item LONG_NAME = Items.WAXED_OXIDIZED_CUT_COPPER_STAIRS;

    /**
     * Item types placed into the racks: as many as the grid shows with the default buffer (12 × 2 cells since M11), so
     * this fills it, and the planks the production station offers come on top of them, which leaves room to scroll.
     */
    private static final List<ItemStack> STOCK = List.of(new ItemStack(Items.IRON_INGOT, 64),
            new ItemStack(Items.COPPER_INGOT, 64), new ItemStack(Items.GOLD_INGOT, 48),
            new ItemStack(Items.REDSTONE, 64), new ItemStack(Items.LAPIS_LAZULI, 32), new ItemStack(Items.DIAMOND, 24),
            new ItemStack(Items.EMERALD, 12), new ItemStack(Items.COAL, 64), new ItemStack(Items.QUARTZ, 40),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.GLASS, 48),
            new ItemStack(Items.WHEAT, 56), new ItemStack(Items.STRING, 16), new ItemStack(Items.BONE, 20),
            new ItemStack(Items.LEATHER, 8), new ItemStack(Items.PAPER, 64), new ItemStack(LONG_NAME, 36),
            new ItemStack(Items.FLINT, 5), new ItemStack(Items.SLIME_BALL, 3), new ItemStack(Items.ANDESITE, 64),
            new ItemStack(Items.SAND, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.KELP, 1));
    /** How many item types the racks hold; the screen must list at least this many. */
    private static final int ITEM_TYPES = 24;
    /** Stacks of the same item put into one chest, so a few entries show four-digit, compacted amounts. */
    private static final int STACKS_PER_LOCATION = 20;

    /**
     * The production pattern the station carries: one log makes {@value #PLANKS_PER_RUN} planks. Planks are
     * deliberately <b>not</b> in {@link #STOCK}, so the terminal offers an item the aisle holds none of (M11,
     * ADR-024), and logs are, so the offer has ingredients behind it.
     */
    private static final ItemKey INGREDIENT = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PRODUCT = ItemKey.of(Items.OAK_PLANKS);
    private static final int LOG_PER_RUN = 1;
    private static final int PLANKS_PER_RUN = 4;
    /** Narrows the grid to the producible item, so the marking is unmistakable in the shot. */
    private static final String PRODUCT_SEARCH = "plank";

    /**
     * The four rules the keeper holds, one per badge colour the terminal can draw: an item above its maximum, one
     * whose last items are all reserved, one the warehouse is short of and holds <b>none</b> of (its row exists only
     * because of the rule), and one that is simply within its limits.
     */
    private static final ItemKey AT_MAXIMUM_ITEM = ItemKey.of(Items.DIAMOND);
    private static final ItemKey AT_RESERVE_ITEM = ItemKey.of(Items.EMERALD);
    private static final ItemKey BELOW_MINIMUM_ITEM = ItemKey.of(Items.GOLD_BLOCK);
    private static final ItemKey SATISFIED_ITEM = ItemKey.of(Items.COAL);
    /** The control: an item no rule governs, whose row must say nothing about rules at all. */
    private static final ItemKey UNRULED_ITEM = ItemKey.of(Items.IRON_INGOT);
    private static final int RULE_MAXIMUM = 8;
    private static final int RULE_RESERVE = 12;
    private static final int RULE_MINIMUM = 64;
    private static final int SATISFIED_MINIMUM = 1;
    private static final int GOVERNING_RULES = 4;

    private static final String SEARCH_TEXT = "iron";
    /** The reported M7 case: the same item clicked ten more times while the first request is still open. */
    private static final int MERGE_CLICKS = 10;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int REQUEST_TIMEOUT_TICKS = 400;
    private static final int DELIVERY_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 4;

    // --- the sort button (M24, issue #17) --------------------------------------------------------------------------
    /** The orders the button gives, in the order the presses give them; the last press closes the cycle. */
    private static final List<TerminalSort> CYCLE = List.of(TerminalSort.USED, TerminalSort.NAME, TerminalSort.AMOUNT);
    /** The language the tooltips are measured in besides English; the only hand-written one the mod ships. */
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    /** Comfortably more than the menu's own push interval, so at least two pushes have been through. */
    private static final int PUSH_TICKS = 25;
    /** How many of a list's first names go into the log line of a shot. */
    private static final int NAMES_LOGGED = 4;
    /** Where the mouse is parked for a shot without a tooltip: the window's title row, on nothing at all. */
    private static final int TITLE_CORNER = 5;
    /** Lines the sort tooltip has at least: the order, what it does, and what the next press would give. */
    private static final int MIN_SORT_TOOLTIP_LINES = 3;
    /**
     * The first grid row the "click one cell twice" check may use: far enough down that a new count would pull the row
     * forward past everything this player has already asked for (M24 review fix).
     */
    private static final int STABLE_ROW_FROM = 5;
    /**
     * How often that one cell is clicked. It is also the count that item type ends up with, so it has to be one no
     * other item type of this scenario reaches, or {@link #requestedRanking()} would have no defined sequence.
     */
    private static final int STABLE_CLICKS = 4;
    /** How far the grid is scrolled down before it is checked that nothing the server pushes moves it. */
    private static final int SCROLL_NOTCHES = 2;
    // --- the three orders told apart (M24, issue #17) ---------------------------------------------------------------
    /**
     * The item types this player asks for before the sort shots, and how many clicks each of them gets: a
     * <b>ranking</b>, not one favourite. A "most used" order whose only evidence is a single item sitting in front of
     * the amount order cannot be told from a coincidence; four items in a known sequence can.
     * <p>
     * They are deliberately three of the <b>smallest</b> stacks of the aisle. The amount order puts those at the very
     * end of its list, so "most used" turns its tail into its head: the two screenshots cannot be mistaken for each
     * other, and neither can the two assertions. No stock rule governs any of them, so every click is simply accepted
     * instead of raising the confirmation panel.
     */
    private static final List<UsageRequest> USAGE_REQUESTS = List.of(new UsageRequest(Items.LEATHER, 5),
            new UsageRequest(Items.STRING, 3), new UsageRequest(Items.BONE, 2));

    /** How many of the first rows the server checks against its own request counts before each shot. */
    private static final int ASSERTED_ROWS = 6;
    /** The order whose survival of a save, quit and rejoin is shown; the one this player's counts are visible in. */
    private static final TerminalSort KEPT_SORT = TerminalSort.USED;
    /**
     * The width every text of a hard-clipped row has to fit, in every language; for the failure message. The
     * terminal's window and the production station's are the same 12 cells wide, so both their rows are 216 px.
     */
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    /**
     * The widest amount one of these rows can be asked to show: the ceiling of {@code maxTerminalRequestAmount}. A
     * line measured with it fits in every configuration, which a line measured with the warehouse this run happens to
     * build does not — "it fits here" is what every one of these defects looked like before it was found.
     * <p>
     * The clipboard line's third number is a <b>sum</b> over the whole list and so has a higher ceiling still; each
     * further digit costs about 6 px, and the log line prints every row's slack so that a reader can see how much
     * room is left for them.
     */
    private static final int WIDEST_AMOUNT = 65536;
    /** The widest entry count a clipboard order can show: the ceiling of {@code maxTerminalListEntries}. */
    private static final int WIDEST_ENTRIES = 1024;
    /** The widest number of stopped products the station's stopped row can name (a station has at most 8 patterns). */
    private static final int WIDEST_STOPPED = 99;
    /** Steps a chain's badge counts, when this run cannot read the server config; the shipped default. */
    private static final int DEFAULT_PLAN_STEPS = 32;
    /**
     * The status texts that are allowed not to fit the terminal's row, <b>per language</b>, as relative key names.
     * {@link #checkRowVocabularyFits} compares the offenders with the set of the language that is loaded
     * <b>exactly</b>: a new text that does not fit fails, and so does one of these being shortened and the entry left
     * behind.
     * <p>
     * {@code pause.speed_factor_zero} does not name a state of the machine, it names a <b>mistake in the server
     * config</b> ("a crane speed factor is 0 in the server config", and the German compound for it is half again as
     * long). Making that sentence fit 216 px would mean dropping the part that tells an operator where to look, which
     * is a wording decision for the project owner — and a player can read it in full through the crane's goggle
     * overlay, which has no fixed row width.
     * <p>
     * The {@code refused.*} entries are the same case and the reason the two sets differ at all. Those sentences are
     * the <b>goggles' own</b> ({@code gui.goggles.request_rejection.*}): whole explanatory sentences that name both
     * what went wrong and what to do about it, written for the goggle overlay and for the port and terminal block
     * tooltips, all of which have room. The status row shows them with its own frame already dropped
     * ({@code WarehouseTerminalScreen#refusedLine}, which is what makes seven of the eleven whole in English and four
     * in German, against three and one before it), and what is left is the diagnosis in full and the remedy cut.
     * Shortening them would take the remedy out of the place it was written for, and giving the terminal a short
     * vocabulary of its own is eleven new sentences in two languages — a wording decision for the project owner, not
     * something to slip into a release about clipboards and sorting. They are listed here so that the next run says
     * so out loud instead of a screenshot of one warehouse state not showing it.
     */
    private static final Map<String, Set<String>> STATUS_ROW_TOO_LONG = Map.of(
            ENGLISH, Set.of("pause.speed_factor_zero", "refused.production_busy", "refused.production_paused",
                    "refused.request_full", "refused.reserved"),
            GERMAN, Set.of("pause.speed_factor_zero", "refused.production_busy", "refused.production_paused",
                    "refused.request_full", "refused.reserved", "refused.output_full", "refused.invalid_amount",
                    "refused.no_controller"));
    /**
     * The state texts that are allowed not to fit the state column of a <b>chain's</b> order line, in either language.
     * <p>
     * A chain's line carries its step badge between the item and the state ({@code "3 steps"}), and the badge is paid
     * for out of the state's column — which is the design's own trade and not a defect
     * ({@code WarehouseTerminalScreen#renderOrderLine}: the state wins the row, but never past the item's floor,
     * because a name cut to three letters names nothing). What gives way is one of the two longest open states, and
     * both of them stand in full in the line's tooltip and in the chain's step panel. This set is what keeps that
     * trade honest: a third state falling off the row, or a wider badge, fails the run.
     */
    private static final Set<String> CHAIN_COLUMN_TOO_LONG =
            Set.of("state.waiting_for_ingredients", "state.waiting_for_result");
    /** A reload is a phase of its own: it costs world loading time that the run's own budget never allowed for. */
    private static final long RELOAD_WATCHDOG_MILLIS = VisualTestHarness.RUN_TIMEOUT_MILLIS;

    /** One item type the scenario asks for, with how many clicks it spends on it. */
    private record UsageRequest(Item item, int clicks) {
        ItemKey key() {
            return ItemKey.of(item);
        }

        String name() {
            return ItemKey.of(item).toStack().getHoverName().getString();
        }
    }

    /** In the aisle, two blocks from the terminal, looking at its screen; inside the vanilla container range. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.1, 1.6, 0.5, 1.4, 0.9, 1.2);

    /** The item of the first request; the repeated clicks look it up by key, not by grid position. */
    @Nullable
    private ItemKey requestedKey;
    /** The status line before the repeated clicks, so the step after them waits for the new answer. */
    private String feedbackBeforeMerge = "";
    /** The icon the button draws per order: three orders a player cannot tell apart would be one order. */
    private final Map<TerminalSort, Object> sortIcons = new EnumMap<>(TerminalSort.class);
    /** The list each order produces, so the three can be compared: same items, different sequence. */
    private final Map<TerminalSort, List<ItemKey>> sortLists = new EnumMap<>(TerminalSort.class);
    /** The longest item name as the grid's own tooltip shows it per order; it has to be the same text every time. */
    private final Map<TerminalSort, String> longestNames = new EnumMap<>(TerminalSort.class);
    /** What the search and the filter keep, taken in the first order: no order may change the set itself. */
    private Set<ItemKey> baselineKeys = Set.of();
    /** The same list as a sequence, so "most used without a history is the amount order" can be asserted outright. */
    private List<ItemKey> baselineOrder = List.of();
    /** Where the grid stood before the server pushed new counts at it. */
    private int scrollBeforePush;
    /**
     * The rows the screen last showed, as the client read them, handed to the <b>server</b> step that checks them
     * against its own stock snapshot and its own request counts. Volatile because the two steps run on two threads.
     */
    private volatile List<ItemKey> shownRows = List.of();
    /** Every key the screen last showed, so the server can check it is talking about the same list at all. */
    private volatile Set<ItemKey> shownKeys = Set.of();
    /** What the server held before the save, in its own words, for the log line after the rejoin. */
    private volatile String preferencesBeforeReload = "";
    /** The item the scrolled-grid request asked for; an accepted request, so the store has to show it too. */
    @Nullable
    private volatile ItemKey pushRequestKey;
    /** The item type the "click one cell twice" check clicks, and how often it has really been clicked so far. */
    @Nullable
    private volatile ItemKey stableKey;
    private volatile int stableCell = -1;
    private volatile int stableRowBefore = -1;
    private volatile int stableClicksDone;
    /** The language switches of the German half, awaited like the language screen does. */
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("terminal: clear the area and place the creative motor", TerminalVisualScenario::placeMotor)
                .server("terminal: build the aisle, the terminal and the stocked racks",
                        TerminalVisualScenario::buildAisle)
                .serverUntil("terminal: wait until the controller has indexed every rack",
                        TerminalVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("terminal: open the terminal screen", TerminalVisualScenario::openScreen)
                .until("terminal: wait for the screen with its stock", TerminalVisualScenario::screenReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("list");
        if (pass == VisualPass.FLYWHEEL) {
            freshUsageSteps(script);
            script.client("terminal: type '" + SEARCH_TEXT + "' into the search box",
                            context -> typeSearch(context, SEARCH_TEXT))
                    .waitTicks(SETTLE_TICKS)
                    .shot("search")
                    .client("terminal: clear the search", context -> screen(context).setSearch(""))
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: request a stack of the first item", this::requestFirstItem)
                    .until("terminal: wait until the server accepted the request",
                            context -> screen(context).status().requestsHere() > 0, REQUEST_TIMEOUT_TICKS)
                    .client("terminal: check that the status texts fit their rows",
                            TerminalVisualScenario::checkStatusFits)
                    .shot("request");
            // One click per tick, the way a player clicks: a menu answers at most
            // WarehouseTerminalMenu.MAX_REQUESTS_PER_TICK requests per tick, so ten clicks fired inside a single tick
            // would be answered eight times and the shot would show a total that no player can produce.
            for (int click = 1; click <= MERGE_CLICKS; click++)
                script.client("terminal: click the same item again (" + click + " of " + MERGE_CLICKS + ")",
                        this::requestSameItemOnce);
            script.until("terminal: wait until the merged answer is on screen", this::mergedAnswerShown,
                            REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check that the merged status texts fit their rows",
                            TerminalVisualScenario::checkStatusFits)
                    .shot("merged")
                    .serverUntil("terminal: wait until the crane delivered into the terminal",
                            TerminalVisualScenario::delivered, DELIVERY_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check that the status texts still fit their rows",
                            TerminalVisualScenario::checkStatusFits)
                    .shot("delivered")
                    // Production (M11, ADR-024): the terminal offers an item the aisle can only make, ordering it
                    // starts an order, and the section under the status line is where its states are read.
                    .client("terminal: search for the producible item",
                            context -> typeSearch(context, PRODUCT_SEARCH))
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check that the producible item is offered at zero stock",
                            TerminalVisualScenario::checkProducibleOffered)
                    .shot("producible")
                    .client("terminal: order everything that can be made",
                            TerminalVisualScenario::orderProducible)
                    .until("terminal: wait until the production order is on screen",
                            context -> orderState(context) != null, REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("ordered")
                    .serverUntil("terminal: wait until the crane delivered the ingredients",
                            TerminalVisualScenario::ingredientsDelivered, DELIVERY_TIMEOUT_TICKS)
                    .until("terminal: wait until the screen shows the delivered state",
                            context -> orderState(context) == ProductionOrderState.DELIVERED, REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("orderdelivered")
                    .client("terminal: give up on the order", TerminalVisualScenario::cancelOrder)
                    .until("terminal: wait until the screen shows it cancelled",
                            context -> orderState(context) == ProductionOrderState.CANCELLED, REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("ordercancelled")
                    .client("terminal: clear the search after the production shots",
                            context -> screen(context).setSearch(""))
                    // Stock rules (M15, issue #3): the badge in a cell's corner and the reserve as a part of what
                    // this player may still claim. The states are asserted on the server and then on the screen,
                    // because a screenshot cannot tell a right badge from a wrong one.
                    .server("terminal: write four stock rules on the keeper", TerminalVisualScenario::writeRules)
                    .serverUntil("terminal: wait until the aisle enforces them",
                            TerminalVisualScenario::rulesEnforced, SCREEN_TIMEOUT_TICKS)
                    .until("terminal: wait until the screen shows the badges",
                            TerminalVisualScenario::ruleBadgesShown, SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check every badge the shot claims", TerminalVisualScenario::checkRuleBadges)
                    .shot("rules");
            usageSteps(script);
            sortSteps(script);
            reloadSteps(script);
            doorSteps(script);
        }
        script.client("terminal: close the screen", TerminalVisualScenario::closeScreen)
                .until("terminal: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (!(open instanceof WarehouseTerminalScreen terminal))
            return "screen=" + (open == null ? "none" : open.getClass().getSimpleName());
        return String.format(Locale.ROOT,
                "screen=terminal entries=%d shown=%d ruled=%d sort=%s sortFits=%s first='%s' requestsHere=%d "
                        + "requestedHere=%d openRequests=%d orders=%d orderState=%s feedback='%s' status='%s' "
                        + "statusFits=%s",
                terminal.matchingEntries().size(), terminal.visibleEntries().size(),
                terminal.matchingEntries().stream().filter(StockLine::ruled).count(), terminal.sort(),
                terminal.sortTooltipFits(), firstNames(terminal.matchingEntries()), terminal.status().requestsHere(),
                terminal.status().requestedHere(), terminal.status().openRequests(),
                terminal.productionOrders().size(), newestOrderState(terminal), feedbackText(terminal),
                terminal.shownStatusLine().getString(), terminal.statusTextsFit());
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(PRODUCTION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, layout.sideDirection(PRODUCTION.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(KEEPER), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, layout.sideDirection(KEEPER.side()).getOpposite()));
        WarehouseProductionBlockEntity station = production(level, dock);
        if (!station.setPatternEntry(0, 0, INGREDIENT, LOG_PER_RUN)
                || !station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PRODUCT, PLANKS_PER_RUN))
            throw new VisualTestException("the production pattern could not be written");

        int next = 0;
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                for (int y = 0; y < STORAGE_LEVELS; y++) {
                    BlockPos rack = layout.rackPos(RackPosition.of(x, y, side));
                    BlockPos chest = rack.relative(outward);
                    level.setBlockAndUpdate(chest,
                            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                    level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                            .setValue(WarehouseInterfaceBlock.FACING, outward));
                    if (next < STOCK.size())
                        fillChest(level, chest, STOCK.get(next++));
                }
            }
        }
        if (next < STOCK.size())
            throw new VisualTestException("only " + next + " of " + STOCK.size() + " item types fit into the racks");
        buildDoor(level, dock);
    }

    /**
     * The package door (M27, issue #18): a requesting port, a Create Packager whose back touches it — its target
     * inventory is the block at {@code packagerPos - FACING} ({@code PackageHandover}), so FACING points away from the
     * port — and a vanilla sign beside the Packager on the ground. Nothing powers the Packager.
     */
    private static void buildDoor(ServerLevel level, BlockPos dock) {
        BranchLayout layout = layout(dock);
        Direction outward = layout.sideDirection(DOOR.side());
        BlockPos port = layout.rackPos(DOOR);
        level.setBlockAndUpdate(port, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
        level.setBlockAndUpdate(packagerPos(dock),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, outward));
        BlockPos sign = packagerPos(dock).relative(AISLE.getOpposite());
        level.setBlockAndUpdate(sign, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0));
        if (!(level.getBlockEntity(sign) instanceof SignBlockEntity entity)
                || !entity.setText(new SignText().setMessage(0, Component.literal(DOOR_ADDRESS)), true))
            throw new VisualTestException("the door's sign at " + sign + " did not take its text");
    }

    private static BlockPos doorPos(BlockPos dock) {
        return layout(dock).rackPos(DOOR);
    }

    private static BlockPos packagerPos(BlockPos dock) {
        return doorPos(dock).relative(layout(dock).sideDirection(DOOR.side()));
    }

    private static WarehouseOutputBlockEntity doorPort(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level, doorPos(dock));
        if (port == null)
            throw new VisualTestException("the package door's port is missing");
        return port;
    }

    /** One item type per chest: several stacks of it, so the grid shows amounts well above one stack. */
    private static void fillChest(ServerLevel level, BlockPos chest, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("the chest at " + chest + " has no item handler");
        int stacks = stack.getCount() >= stack.getMaxStackSize() ? STACKS_PER_LOCATION : 1;
        for (int i = 0; i < stacks; i++) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
            if (!rest.isEmpty())
                break; // the chest is full; the remaining stacks are not needed for the picture
        }
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = readyController(server, context);
        return controller != null && controller.stockIndex().distinctKeys() >= ITEM_TYPES
                // The station is recorded and its pattern is readable, so the terminal really offers the product.
                && controller.productionStations().size() == 1 && controller.producibleKeys().contains(PRODUCT);
    }

    /**
     * The aisle after the save, quit and rejoin: indexed and linked again, but <b>without</b> the stock the scenario
     * started from.
     * <p>
     * {@link #sceneReady} may not be reused here. By the time the world is reloaded the scenario has spent the aisle's
     * whole stack of oak logs on the production order, so the index holds one item type less than the racks were
     * filled with — and a readiness condition that waits for the first number again would simply never come true.
     * <p>
     * The keeper's rules are waited for instead, because they decide which rows the list has at all: a rule keeps the
     * row of an item the warehouse holds none of (M15), so a screen opened before the controller has its copy of them
     * back would show one row less than the list every earlier assertion was taken from.
     */
    private static boolean sceneReloaded(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = readyController(server, context);
        return controller != null && controller.stockRules().governingCount() == GOVERNING_RULES;
    }

    /** The aisle's controller once it is ready, linked and has nothing left to index; {@code null} while it is not. */
    @Nullable
    private static WarehouseControllerBlockEntity readyController(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return null;
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        boolean ready = controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == OUTPUT_STATIONS && crane.isControllerLinked();
        return ready ? controller : null;
    }

    private static void openScreen(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context.origin());
        ServerPlayer player = context.serverPlayer(server);
        if (!terminal.openScreen(player))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
        LOGGER.info(PREFIX + "terminal: opened the screen with {} item types in stock",
                terminal.stockSnapshot().size());
    }

    private static boolean delivered(MinecraftServer server, VisualContext context) {
        return terminal(server.overworld(), context.origin()).bufferedItems().totalItems() > 0;
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseProductionBlockEntity production(ServerLevel level, BlockPos dock) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                layout(dock).rackPos(PRODUCTION));
        if (station == null)
            throw new VisualTestException("the production station of the aisle is missing");
        return station;
    }

    /** Whether the crane has brought every ingredient of a production order to the station (server side). */
    private static boolean ingredientsDelivered(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), context.origin().relative(AISLE.getOpposite()));
        return controller != null && controller.productionOrders().stream()
                .anyMatch(order -> order.state() == ProductionOrderState.DELIVERED);
    }

    // --- screen (client thread) ------------------------------------------------------------------------------------

    private static boolean screenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && !terminal.matchingEntries().isEmpty();
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + open + ")");
    }

    /** Types the text character by character, exactly as a player would, and logs what the search kept. */
    private static void typeSearch(VisualContext context, String text) {
        WarehouseTerminalScreen terminal = screen(context);
        terminal.focusSearch();
        for (char typed : text.toCharArray())
            terminal.charTyped(typed, 0);
        LOGGER.info(PREFIX + "terminal: search '{}' keeps {} of the entries", text, terminal.matchingEntries().size());
    }

    /** One stack of the first item in the grid; its key is remembered, so the repeated clicks hit the same item. */
    private void requestFirstItem(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        StockLine<ItemKey> first = terminal.visibleEntries().stream().findFirst()
                .orElseThrow(() -> new VisualTestException("the terminal screen shows no item to request"));
        requestedKey = first.key();
        if (!terminal.requestVisible(0, TerminalAmounts.Click.STACK))
            throw new VisualTestException("the terminal screen shows no item to request");
        LOGGER.info(PREFIX + "terminal: requested one stack of {}", first.name());
    }

    /**
     * One more click on the item of the first request, the reported M7 case clicked exactly as a player does. The item
     * is looked up by its remembered key instead of by grid position, because the grid is sorted by amount and
     * re-orders as soon as the crane takes items out of a chest.
     */
    private void requestSameItemOnce(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        feedbackBeforeMerge = feedbackText(terminal);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        int cell = -1;
        for (int index = 0; index < visible.size() && cell < 0; index++) {
            if (visible.get(index).key().equals(requestedKey))
                cell = index;
        }
        if (cell < 0)
            throw new VisualTestException("the item of the first request left the visible grid");
        if (!terminal.requestVisible(cell, TerminalAmounts.Click.SELECTED))
            throw new VisualTestException("the repeated click could not be sent");
    }

    /**
     * Fails the run when a text of the two rows below the buffer does not fit its row. The M7 merged answer used to be
     * drawn straight through the right-aligned "Open requests" of the same row, which no screenshot can be trusted to
     * show — so the run asserts the widths instead of only photographing them ({@code docs/warehouse-system.md}
     * §3.4.2).
     */
    private static void checkStatusFits(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (!terminal.statusTextsFit())
            throw new VisualTestException(
                    "the terminal's status texts do not fit their rows: '" + terminal.shownStatusLine().getString()
                            + "' (window " + TerminalMenuLayout.WIDTH + " px wide)");
    }

    /** Whether the answer to the repeated clicks has replaced the first one in the status line. */
    private boolean mergedAnswerShown(VisualContext context) {
        String shown = feedbackText(screen(context));
        return !shown.isEmpty() && !shown.equals(feedbackBeforeMerge);
    }

    private static String feedbackText(WarehouseTerminalScreen terminal) {
        return terminal.feedbackLine().map(Component::getString).orElse("");
    }

    // --- production (M11, ADR-024) -----------------------------------------------------------------------------------

    /**
     * Fails the run unless the terminal really offers the producible item <b>at zero stock</b>, marked as producible
     * and with an amount behind it. A screenshot alone could not tell the marking from a normal empty cell, so the
     * claim the shot illustrates is asserted first.
     */
    private static void checkProducibleOffered(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        StockLine<ItemKey> line = terminal.entry(PRODUCT)
                .orElseThrow(() -> new VisualTestException("the terminal does not offer the producible item"));
        if (line.total() > 0L || !line.producible() || line.producibleAmount() <= 0L)
            throw new VisualTestException("the producible item is not offered as one: " + line);
        if (terminal.visibleEntries().stream().noneMatch(entry -> entry.key().equals(PRODUCT)))
            throw new VisualTestException("the producible item is not in the visible grid");
        LOGGER.info(PREFIX + "terminal: {} is offered at zero stock, {} of it can be made now", line.name(),
                line.producibleAmount());
    }

    /** Control-clicks the producible item, i.e. asks for everything the ingredients in stock allow. */
    private static void orderProducible(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (!visible.get(cell).key().equals(PRODUCT))
                continue;
            if (!terminal.requestVisible(cell, TerminalAmounts.Click.ALL))
                throw new VisualTestException("the producible item could not be ordered");
            LOGGER.info(PREFIX + "terminal: ordered everything that can be made of {}", visible.get(cell).name());
            return;
        }
        throw new VisualTestException("the producible item left the visible grid before it could be ordered");
    }

    // --- stock rules (M15, issue #3) -------------------------------------------------------------------------------

    /** Writes one rule per badge colour onto the keeper of the aisle, through the same entry point an edit uses. */
    private static void writeRules(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        rule(keeper, 0, AT_MAXIMUM_ITEM, StockKeeperRules.FIELD_MAXIMUM, RULE_MAXIMUM);
        rule(keeper, 1, AT_RESERVE_ITEM, StockKeeperRules.FIELD_RESERVE, RULE_RESERVE);
        rule(keeper, 2, BELOW_MINIMUM_ITEM, StockKeeperRules.FIELD_MINIMUM, RULE_MINIMUM);
        rule(keeper, 3, SATISFIED_ITEM, StockKeeperRules.FIELD_MINIMUM, SATISFIED_MINIMUM);
        LOGGER.info(PREFIX + "terminal: the keeper holds {} rules", keeper.rules().ruleCount());
    }

    private static void rule(WarehouseStockKeeperBlockEntity keeper, int row, ItemKey key, int field, long value) {
        if (!keeper.editRule(row, StockKeeperRules.FIELD_ITEM, key, 0L).changed()
                || !keeper.editRule(row, field, null, value).changed())
            throw new VisualTestException("the rule in row " + row + " could not be written");
    }

    /** Whether the controller's own copy governs every rule, i.e. whether the warehouse really enforces them. */
    private static boolean rulesEnforced(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), context.origin().relative(AISLE.getOpposite()));
        return controller != null && controller.stockRules().governingCount() == GOVERNING_RULES;
    }

    /** Whether the screen has the four ruled rows, including the one of an item the aisle holds none of. */
    private static boolean ruleBadgesShown(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        for (ItemKey key : List.of(AT_MAXIMUM_ITEM, AT_RESERVE_ITEM, BELOW_MINIMUM_ITEM, SATISFIED_ITEM)) {
            if (terminal.entry(key).filter(StockLine::ruled).isEmpty())
                return false;
        }
        return true;
    }

    /**
     * Fails the run unless every badge of the shot is the one it claims to be, and unless the reserve is reported as
     * a <b>part of</b> what this player may still claim — the user's decision for M15, and the one thing a screenshot
     * of coloured squares could never prove.
     */
    private static void checkRuleBadges(VisualContext context) {
        assertBadge(context, AT_MAXIMUM_ITEM, StockRuleStatus.AT_MAXIMUM);
        assertBadge(context, AT_RESERVE_ITEM, StockRuleStatus.AT_RESERVE);
        assertBadge(context, BELOW_MINIMUM_ITEM, StockRuleStatus.BELOW_MINIMUM);
        assertBadge(context, SATISFIED_ITEM, StockRuleStatus.SATISFIED);
        StockLine<ItemKey> reserved = line(context, AT_RESERVE_ITEM);
        if (reserved.ruleReserved() <= 0L || reserved.ruleReserved() > reserved.available())
            throw new VisualTestException("the reserve is not a part of what the player may claim: " + reserved);
        if (reserved.availableToAutomation() != 0L)
            throw new VisualTestException("automation should get nothing more of " + reserved.name());
        StockLine<ItemKey> shortOfMinimum = line(context, BELOW_MINIMUM_ITEM);
        if (shortOfMinimum.total() > 0L)
            throw new VisualTestException("the pinned row should have no stock at all: " + shortOfMinimum);
        // The row's own words. A tooltip is drawn only while a mouse hovers, so the run reads the lines instead of
        // photographing them: the hint that a click goes below the reserve is the whole point of the user's decision
        // that a player may take it.
        List<String> hint = screen(context).itemTooltip(reserved).stream().map(Component::getString).toList();
        if (hint.stream().noneMatch(text -> text.contains(reserveHintText())))
            throw new VisualTestException("the row does not say that a request goes below the reserve: " + hint);
        if (hint.stream().noneMatch(text -> text.contains(ruleStatusText(StockRuleStatus.AT_RESERVE))))
            throw new VisualTestException("the row does not name what the rule is doing: " + hint);
        List<String> unruled = screen(context).itemTooltip(line(context, UNRULED_ITEM)).stream()
                .map(Component::getString).toList();
        if (unruled.stream().anyMatch(text -> text.contains(reserveHintText())))
            throw new VisualTestException("an item no rule governs must say nothing about a reserve: " + unruled);
        LOGGER.info(PREFIX + "terminal: badges at maximum/reserve/minimum/satisfied are drawn, {} of {} available "
                + "are held back from automation, and the row says: {}", reserved.ruleReserved(),
                reserved.available(), hint);
    }

    private static void assertBadge(VisualContext context, ItemKey key, StockRuleStatus expected) {
        StockLine<ItemKey> line = line(context, key);
        if (line.rule().filter(expected::equals).isEmpty())
            throw new VisualTestException("the badge of " + line.name() + " is " + line.rule() + ", not " + expected);
    }

    private static String reserveHintText() {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_BELOW_RESERVE).getString();
    }

    private static String ruleStatusText(StockRuleStatus status) {
        return WareworksLang.translateDirect(WareworksLang.keeperStatusKey(status)).getString();
    }

    private static StockLine<ItemKey> line(VisualContext context, ItemKey key) {
        return screen(context).entry(key)
                .orElseThrow(() -> new VisualTestException("the terminal does not show " + key));
    }

    private static WarehouseStockKeeperBlockEntity keeper(ServerLevel level, BlockPos dock) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER.getNullable(level,
                layout(dock).rackPos(KEEPER));
        if (keeper == null)
            throw new VisualTestException("the warehouse stock keeper of the aisle is missing");
        return keeper;
    }

    /** Gives up on the newest order through the screen's own cancel control. */
    private static void cancelOrder(VisualContext context) {
        if (!screen(context).cancelVisibleOrder(0))
            throw new VisualTestException("the production order could not be cancelled from the screen");
    }

    /** The state of the newest order the screen knows about, or {@code null} while it knows none. */
    @Nullable
    private static ProductionOrderState orderState(VisualContext context) {
        List<ProductionScreenState.OrderView> orders = screen(context).productionOrders();
        return orders.isEmpty() ? null : orders.getLast().state();
    }

    private static String newestOrderState(WarehouseTerminalScreen terminal) {
        List<ProductionScreenState.OrderView> orders = terminal.productionOrders();
        return orders.isEmpty() ? "none" : orders.getLast().state().name();
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    // --- the sort button (M24, issue #17) ---------------------------------------------------------------------------

    /**
     * The three orders, each reached by a <b>real click</b> on the button through the mouse handler
     * ({@link ScreenInput}), shot and checked.
     * <p>
     * A screenshot can show that three orders look different; it cannot show that they are the right three, that the
     * tooltip is not a raw lang key, or that a translation has not grown wider than the window. So every shot is
     * preceded by the assertions behind it: the order the button claims, the tooltip's three parts and their width in
     * the window's own row, the icon (three orders a player cannot tell apart would be one order), the fact that no
     * order changes <i>which</i> items the search and the filter keep, and the one name in the aisle that is long
     * enough to be cut — which has to read the same in all three.
     * <p>
     * And before every shot the <b>server</b> says which item types the first rows have to be
     * ({@link #assertRowsOnServer}), built from its own stock snapshot and its own request counts. The client's checks
     * are read off the very list the client would draw, so a client sorting by the wrong numbers would agree with
     * itself; the server is where the numbers live.
     * <p>
     * Afterwards the grid is scrolled down and the server is made to push new counts at it, because the other half of
     * "the button a player presses" is everything that must <b>not</b> happen: the list may re-sort under a scroll
     * position the player set, it may not jump back to the top, and it may not fall back into the previous order for a
     * few ticks.
     */
    private void sortSteps(VisualScript script) {
        script.client("terminal: remember what the first order shows", this::rememberBaseline)
                .client("terminal: check the amount order", context -> checkSort(context, TerminalSort.AMOUNT));
        assertRowsOnServer(script, TerminalSort.AMOUNT);
        sortShots(script, TerminalSort.AMOUNT, "");
        for (TerminalSort next : CYCLE) {
            String label = next.name().toLowerCase(Locale.ROOT);
            script.client("terminal: press the sort button (-> " + label + ")", TerminalVisualScenario::pressSort)
                    .until("terminal: wait until the grid is in the " + label + " order",
                            context -> screen(context).sort() == next, SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check the " + label + " order", context -> checkSort(context, next));
            assertRowsOnServer(script, next);
            // The last press closes the cycle back onto the first order, which already has its two shots.
            if (next != TerminalSort.AMOUNT)
                sortShots(script, next, "");
        }
        script.client("terminal: check that the three orders really are three", this::checkOrdersDiffer);
        stableClickSteps(script);
        script.client("terminal: scroll the grid down", TerminalVisualScenario::scrollDown)
                .client("terminal: request one item, so the server pushes new counts", this::requestForPush)
                .until("terminal: wait until the answer is on screen", context -> screen(context).hasFeedback(),
                        REQUEST_TIMEOUT_TICKS)
                .waitTicks(PUSH_TICKS)
                .client("terminal: check that the push moved neither the grid nor the order", this::checkScrollKept)
                .client("terminal: scroll the grid back up", TerminalVisualScenario::scrollUp);
        germanSortSteps(script);
    }

    /**
     * <b>Clicking one cell of the grid repeatedly, in the "most used" order, keeps asking for the same item</b>
     * (M24 review fix).
     * <p>
     * It is the order's own hazard: the request count is the <b>first</b> sort key, so the first click on any row
     * gives it a count the row did not have and pulls it in front of every row with none - and the push that carries
     * that count arrives about a tick after the click. A player clicking a cell twice, which is how one request is
     * grown ({@code docs/warehouse-system.md} §7.2), would have asked for whatever item slid into that cell meanwhile,
     * and the grid's hit test is positional. So new counts now wait for the next list the player asks for themselves
     * ({@code WarehouseTerminalScreen#latestUsage}), and this is where that is shown.
     * <p>
     * The assertions are written so that only <b>that</b> movement can fail them: the cell has to still hold the item
     * before every one of the {@value #STABLE_CLICKS} clicks, and the row may not move <b>forward</b> in the list.
     * Losing items to its own request can only move a row back (the amount keys sort the largest first), so a row
     * that moved forward moved because of a count. The clicks are one per step and therefore one per tick, like every
     * other repeated click here, and the item is chosen with amounts far enough from its neighbours' that four items
     * leaving the racks cannot reorder it at all.
     * <p>
     * The counts are not lost, only deferred: the two presses that follow take them, and after the reload the screen
     * comes up in this order with this item type among the favourites, which {@link #checkOrderItself} asserts
     * against the clicks the scenario itself made.
     */
    private void stableClickSteps(VisualScript script) {
        script.client("terminal: press the sort button into the used order", TerminalVisualScenario::pressSort)
                .until("terminal: wait until the grid is in the used order",
                        context -> screen(context).sort() == TerminalSort.USED, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: pick a row far enough down the used order", this::rememberStableCell);
        for (int click = 1; click <= STABLE_CLICKS; click++)
            script.client("terminal: click that one cell (" + click + " of " + STABLE_CLICKS + ")",
                            this::clickStableCell)
                    .waitTicks(PUSH_TICKS)
                    .client("terminal: check that the new count did not move the row under the cursor",
                            this::checkStableCell);
        sortShots(script, TerminalSort.USED, "-clicked");
        for (TerminalSort next : List.of(TerminalSort.NAME, TerminalSort.AMOUNT)) {
            script.client("terminal: press the sort button back towards the amount order",
                            TerminalVisualScenario::pressSort)
                    .until("terminal: wait until the grid is in the " + next.name().toLowerCase(Locale.ROOT)
                            + " order", context -> screen(context).sort() == next, SCREEN_TIMEOUT_TICKS);
        }
        script.waitTicks(SETTLE_TICKS)
                .client("terminal: park the mouse beside the sort button", TerminalVisualScenario::hoverOffButton);
    }

    /**
     * Picks the cell the repeated clicks use: a stocked, unruled row this player has never asked for, at least
     * {@value #STABLE_ROW_FROM} rows down, and with amounts far enough from its neighbours' that the items this check
     * itself fetches cannot reorder it.
     */
    private void rememberStableCell(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        Set<ItemKey> counted = expectedCounts().keySet();
        for (int cell = STABLE_ROW_FROM; cell < visible.size(); cell++) {
            StockLine<ItemKey> line = visible.get(cell);
            if (line.rule().isPresent() || counted.contains(line.key()) || line.available() <= STABLE_CLICKS)
                continue;
            if (!separatedFromNeighbours(visible, cell))
                continue;
            stableKey = line.key();
            stableCell = cell;
            stableClicksDone = 0;
            stableRowBefore = cell;
            LOGGER.info(PREFIX + "terminal: clicking cell {} ({}, {} available) {} times in the used order", cell,
                    line.name(), line.available(), STABLE_CLICKS);
            return;
        }
        throw new VisualTestException("no row from " + STABLE_ROW_FROM
                + " on can be clicked repeatedly without being reordered by its own request: "
                + firstNames(visible));
    }

    /**
     * Whether the row at {@code cell} is far enough from its neighbours in amount that {@value #STABLE_CLICKS} items
     * leaving the racks cannot move it past either of them.
     */
    private static boolean separatedFromNeighbours(List<StockLine<ItemKey>> visible, int cell) {
        long available = visible.get(cell).available();
        for (int other : new int[] { cell - 1, cell + 1 }) {
            if (other < 0 || other >= visible.size())
                continue;
            if (Math.abs(visible.get(other).available() - available) <= STABLE_CLICKS)
                return false;
        }
        return true;
    }

    /** One click on the remembered cell, after checking that the cell still holds the item that was clicked. */
    private void clickStableCell(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        if (stableCell < 0 || stableCell >= visible.size())
            throw new VisualTestException("the grid no longer has the cell " + stableCell + " that was clicked");
        ItemKey inCell = visible.get(stableCell).key();
        if (!inCell.equals(stableKey))
            throw new VisualTestException("cell " + stableCell + " holds " + displayName(inCell) + " instead of "
                    + displayName(stableKey) + ": the list moved under the cursor, so this click would ask for "
                    + "something the player did not click on");
        stableRowBefore = rowOf(terminal, stableKey);
        if (!terminal.requestVisible(stableCell, TerminalAmounts.Click.SELECTED))
            throw new VisualTestException("the grid refused the click on " + displayName(stableKey));
        stableClicksDone++;
    }

    /** What the push may not do: pull the clicked row forward, or take it out of the cell it was clicked in. */
    private void checkStableCell(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        int row = rowOf(terminal, stableKey);
        if (row < 0)
            throw new VisualTestException(displayName(stableKey) + " has left the list after being requested");
        if (row < stableRowBefore)
            throw new VisualTestException("the count of click " + stableClicksDone + " moved "
                    + displayName(stableKey) + " forward from row " + stableRowBefore + " to row " + row
                    + ", i.e. under the cursor that clicked it");
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        if (row == stableRowBefore && (stableCell >= visible.size()
                || !visible.get(stableCell).key().equals(stableKey)))
            throw new VisualTestException("the row stayed at " + row + " but cell " + stableCell
                    + " no longer shows " + displayName(stableKey));
        LOGGER.info(PREFIX + "terminal: after click {} of {}, {} is still at row {} of the used order",
                stableClicksDone, STABLE_CLICKS, displayName(stableKey), row);
    }

    /** Where {@code key} is in the list the screen would draw, or -1. */
    private static int rowOf(WarehouseTerminalScreen terminal, ItemKey key) {
        List<StockLine<ItemKey>> matching = terminal.matchingEntries();
        for (int row = 0; row < matching.size(); row++) {
            if (matching.get(row).key().equals(key))
                return row;
        }
        return -1;
    }

    /**
     * "Most used" for a player who has never requested anything, which is the state every player starts in.
     * <p>
     * This runs <b>before</b> the first request of the scenario, because afterwards it can never be reached again in
     * that world: it is the one moment the store is empty. The order must then be exactly the amount order — asserted
     * list against list, not described — and the tooltip has to say so, or a terminal that looks like it ignored the
     * button is the obvious reading. The three presses leave the button where they found it, so the rest of the
     * scenario sees what it always saw.
     */
    private void freshUsageSteps(VisualScript script) {
        script.client("terminal: remember the list before anything was requested", this::rememberBaseline)
                .client("terminal: press the sort button with an empty history", TerminalVisualScenario::pressSort)
                .until("terminal: wait until the grid is in the used order",
                        context -> screen(context).sort() == TerminalSort.USED, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: check 'most used' without a history", this::checkUsedWithoutHistory);
        sortShots(script, TerminalSort.USED, "-empty");
        for (TerminalSort next : List.of(TerminalSort.NAME, TerminalSort.AMOUNT)) {
            script.client("terminal: press the sort button back towards the amount order",
                            TerminalVisualScenario::pressSort)
                    .until("terminal: wait until the grid is in the " + next.name().toLowerCase(Locale.ROOT)
                            + " order", context -> screen(context).sort() == next, SCREEN_TIMEOUT_TICKS);
        }
        script.waitTicks(SETTLE_TICKS)
                .client("terminal: park the mouse beside the sort button", TerminalVisualScenario::hoverOffButton);
    }

    /** The fall-back: the same list the amount order shows, and a tooltip that says why. */
    private void checkUsedWithoutHistory(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        checkSortTooltip(context, TerminalSort.USED);
        String reason = text(WareworksLang.TERMINAL_SORT_NO_HISTORY);
        if (terminal.sortTooltip().stream().map(Component::getString).noneMatch(line -> line.contains(reason)))
            throw new VisualTestException("the tooltip does not say that nothing has been requested yet: "
                    + terminal.sortTooltip().stream().map(Component::getString).toList());
        List<ItemKey> keys = new ArrayList<>();
        for (StockLine<ItemKey> line : terminal.matchingEntries())
            keys.add(line.key());
        if (!keys.equals(baselineOrder))
            throw new VisualTestException("'most used' without a history is not the amount order: " + keys.size()
                    + " entries beginning with " + firstNames(terminal.matchingEntries()));
        LOGGER.info(PREFIX + "terminal: with nothing requested yet, 'most used' is the amount order and says so: {}",
                terminal.sortTooltip().stream().map(Component::getString).toList());
    }

    /**
     * The same three tooltips in German, measured against the same row.
     * <p>
     * German is the project's one hand-written language, and it is where a tooltip grows: "Sorting: by name" is three
     * words, "Sortierung: am meisten Verfügbares zuerst" is five long ones. {@code LangConsistencyTest} can only check
     * that the keys and placeholders match — the <b>width</b> of a translation needs a font and a loaded language, so
     * it is checked here, in the one place that has both.
     * <p>
     * It measures the "nothing requested yet" line as well, although this player has long since requested something:
     * that line is only <i>shown</i> while the store is empty, which is a state a world passes through once and
     * before this pass, so the German translation of it had never been measured at all and really was 7 px too wide
     * (M24 review fix). {@code WarehouseTerminalScreen#sortTooltipLines} therefore hands over every line the button
     * can show, not only the ones it shows now.
     */
    private void germanSortSteps(VisualScript script) {
        script.client("terminal: check every text of every clipped row in English",
                TerminalVisualScenario::checkRowVocabularyFits)
                .client("terminal: check the door button's tooltip in English",
                        TerminalVisualScenario::checkDoorTooltipFits);
        switchLanguage(script, GERMAN);
        script.client("terminal: check every text of every clipped row in German",
                        TerminalVisualScenario::checkRowVocabularyFits)
                .client("terminal: check the door button's tooltip in German",
                        TerminalVisualScenario::checkDoorTooltipFits)
                .client("terminal: check the amount order in German",
                        context -> checkSortTooltip(context, TerminalSort.AMOUNT));
        tooltipShot(script, TerminalSort.AMOUNT, "-de");
        for (TerminalSort next : CYCLE) {
            String label = next.name().toLowerCase(Locale.ROOT);
            script.client("terminal: press the sort button in German (-> " + label + ")",
                            TerminalVisualScenario::pressSort)
                    .until("terminal: wait until the grid is in the " + label + " order",
                            context -> screen(context).sort() == next, SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check the " + label + " order in German",
                            context -> checkSortTooltip(context, next))
                    // The status row as well, in German: it is the one row of this screen whose text comes from the
                    // warehouse rather than from the player, and German is where a translation grows past it.
                    .client("terminal: check that the German status texts fit their rows",
                            TerminalVisualScenario::checkStatusFits);
            if (next != TerminalSort.AMOUNT)
                tooltipShot(script, next, "-de");
        }
        switchLanguage(script, ENGLISH);
        script.client("terminal: check that the tooltip is English again",
                context -> checkSortTooltip(context, TerminalSort.AMOUNT));
    }

    /**
     * Two shots of one order: the grid with the mouse parked away from the button, and the button being hovered.
     * <p>
     * The two are both needed and neither replaces the other. A player hovering the button reads the tooltip but the
     * tooltip then covers the first row of the very grid it is talking about, and a shot without it shows the icon and
     * the new order but says nothing about the words. So the run takes both, and a reader can put them side by side.
     */
    private static void sortShots(VisualScript script, TerminalSort sort, String suffix) {
        String label = "sort-" + sort.name().toLowerCase(Locale.ROOT) + suffix;
        script.client("terminal: park the mouse beside the sort button", TerminalVisualScenario::hoverOffButton)
                .waitTicks(SETTLE_TICKS)
                .shot(label);
        tooltipShot(script, sort, suffix);
    }

    /** One shot of the sort button being hovered, i.e. of its tooltip as a player reads it. */
    private static void tooltipShot(VisualScript script, TerminalSort sort, String suffix) {
        script.client("terminal: hover the sort button", TerminalVisualScenario::hoverSortButton)
                .waitTicks(SETTLE_TICKS)
                .shot("sort-" + sort.name().toLowerCase(Locale.ROOT) + suffix + "-tip");
    }

    /** Puts the cursor on the sort button, so the shot after it holds the tooltip a player reads. */
    private static void hoverSortButton(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalSortButton(terminal));
    }

    /** Puts the cursor into the window's title row: inside the screen, on no widget, slot, cell or order line. */
    private static void hoverOffButton(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.hover(context.minecraft(), new ScreenInput.Point(terminal.getGuiLeft() + TITLE_CORNER,
                terminal.getGuiTop() + TITLE_CORNER));
    }

    /** What the language screen does when a player picks a language; see {@link VisualLanguage}. */
    private void switchLanguage(VisualScript script, String code) {
        language.switchTo(script, "terminal: ", code);
    }

    /** Presses the sort button the way a player does: a real click, through the mouse handler and the screen's own hit test. */
    private static void pressSort(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        TerminalSort before = terminal.sort();
        ScreenInput.click(context.minecraft(), ScreenInput.terminalSortButton(terminal));
        LOGGER.info(PREFIX + "terminal: pressed the sort button in the {} order, now {}", before, terminal.sort());
    }

    /** The list the amount order shows right now: which item types it keeps, and in which order. */
    private void rememberBaseline(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.sort() != TerminalSort.AMOUNT)
            throw new VisualTestException("the baseline is taken in the " + terminal.sort() + " order");
        List<ItemKey> keys = new ArrayList<>();
        for (StockLine<ItemKey> line : terminal.matchingEntries())
            keys.add(line.key());
        if (new LinkedHashSet<>(keys).size() < ITEM_TYPES)
            throw new VisualTestException("the terminal shows only " + keys.size() + " item types, not " + ITEM_TYPES);
        baselineOrder = List.copyOf(keys);
        baselineKeys = Set.copyOf(keys);
    }

    /** Everything one order has to get right, asserted before the shot that illustrates it. */
    private void checkSort(VisualContext context, TerminalSort expected) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.sort() != expected)
            throw new VisualTestException("the grid is in the " + terminal.sort() + " order, not in " + expected);
        checkSortTooltip(context, expected);
        sortIcons.put(expected, ScreenInput.terminalSortIcon(terminal));

        List<StockLine<ItemKey>> matching = terminal.matchingEntries();
        List<ItemKey> keys = new ArrayList<>(matching.size());
        for (StockLine<ItemKey> line : matching)
            keys.add(line.key());
        if (!Set.copyOf(keys).equals(baselineKeys))
            throw new VisualTestException(
                    "the " + expected + " order changed which items the search and the filter keep: " + keys.size()
                            + " instead of " + baselineKeys.size());
        sortLists.put(expected, List.copyOf(keys));
        checkOffersLast(expected, matching);
        checkOrderItself(expected, matching);
        if (!terminal.statusTextsFit())
            throw new VisualTestException("the status texts do not fit their rows in the " + expected + " order: '"
                    + terminal.shownStatusLine().getString() + "'");
        longestNames.put(expected, checkLongestName(terminal, expected, matching));
        LOGGER.info(PREFIX + "terminal: the {} order begins with {}", expected, firstNames(matching));
    }

    /** An item the aisle can only make stays behind everything it really holds — in every order (M11, ADR-024). */
    private static void checkOffersLast(TerminalSort expected, List<StockLine<ItemKey>> matching) {
        boolean offerSeen = false;
        for (StockLine<ItemKey> line : matching) {
            if (line.isProducibleOnly())
                offerSeen = true;
            else if (offerSeen)
                throw new VisualTestException("the " + expected + " order puts an offer at zero stock in front of "
                        + line.name());
        }
    }

    /** That each order really is the order it claims, read off the list the screen would draw. */
    private void checkOrderItself(TerminalSort expected, List<StockLine<ItemKey>> matching) {
        List<StockLine<ItemKey>> stocked = matching.stream().filter(line -> !line.isProducibleOnly()).toList();
        switch (expected) {
            case AMOUNT -> {
                for (int i = 1; i < stocked.size(); i++) {
                    if (stocked.get(i - 1).available() < stocked.get(i).available())
                        throw new VisualTestException("the amount order has " + stocked.get(i).name() + " behind "
                                + stocked.get(i - 1).name());
                }
            }
            case NAME -> {
                for (int i = 1; i < stocked.size(); i++) {
                    if (String.CASE_INSENSITIVE_ORDER.compare(stocked.get(i - 1).name(), stocked.get(i).name()) > 0)
                        throw new VisualTestException("the name order has " + stocked.get(i).name() + " behind "
                                + stocked.get(i - 1).name());
                }
            }
            // The whole point of the third order: the items this player really asked for, in the order of how often,
            // and not one of them is where the amount order would have put it - the three favourites after the first
            // are the aisle's smallest stacks and sit at the very end of that one.
            case USED -> {
                List<ItemKey> wanted = requestedRanking();
                List<ItemKey> head = keysOf(stocked).subList(0, Math.min(wanted.size(), stocked.size()));
                if (!wanted.equals(head))
                    throw new VisualTestException("the most used order should begin with " + names(wanted)
                            + " (the items this player asked for, most often first), but begins with " + names(head));
            }
        }
    }

    /**
     * The longest name in the aisle, as the grid's own tooltip shows it: it must be there in full, and it must be the
     * same text in every order. The grid draws items and amounts, never names, so this tooltip is the one place a name
     * reaches a player — and the one place an order could cut it differently.
     */
    private static String checkLongestName(WarehouseTerminalScreen terminal, TerminalSort expected,
            List<StockLine<ItemKey>> matching) {
        StockLine<ItemKey> longest = null;
        for (StockLine<ItemKey> line : matching) {
            if (longest == null || line.name().length() > longest.name().length())
                longest = line;
        }
        if (longest == null)
            throw new VisualTestException("the terminal shows no item at all in the " + expected + " order");
        List<Component> tooltip = terminal.itemTooltip(longest);
        String shown = tooltip.isEmpty() ? "" : tooltip.getFirst().getString();
        if (!shown.contains(longest.name()) || shown.contains(CommonComponents.ELLIPSIS.getString()))
            throw new VisualTestException("the longest name is not shown in full in the " + expected + " order: '"
                    + shown + "' instead of '" + longest.name() + "'");
        return shown;
    }

    /** The sort button's tooltip in whichever language is loaded: three parts, no raw key, and inside the window's row. */
    private static void checkSortTooltip(VisualContext context, TerminalSort expected) {
        WarehouseTerminalScreen terminal = screen(context);
        String language = context.minecraft().getLanguageManager().getSelected();
        List<Component> tooltip = terminal.sortTooltip();
        if (tooltip.size() < MIN_SORT_TOOLTIP_LINES)
            throw new VisualTestException("the sort tooltip has only " + tooltip.size() + " lines in " + language);
        List<String> lines = tooltip.stream().map(Component::getString).toList();
        for (String line : lines) {
            if (line.contains(WareworksLang.key("gui.")))
                throw new VisualTestException("the sort tooltip shows a raw lang key in " + language + ": " + line);
        }
        String label = text(expected.langKey());
        String detail = text(expected.detailKey());
        String next = text(expected.next().langKey());
        if (!lines.getFirst().contains(label))
            throw new VisualTestException("the sort tooltip does not name the order in " + language + ": " + lines);
        if (lines.stream().noneMatch(line -> line.contains(detail)))
            throw new VisualTestException("the sort tooltip does not say what the order does in " + language + ": "
                    + lines);
        if (!lines.getLast().contains(next))
            throw new VisualTestException("the sort tooltip does not name what the next press gives in " + language
                    + ": " + lines);
        if (!terminal.sortTooltipFits())
            throw new VisualTestException("a line of the sort tooltip is wider than the terminal's row ("
                    + (TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN) + " px) in " + language + ": "
                    + widths(context, terminal.sortTooltipLines()));
        LOGGER.info(PREFIX + "terminal: the {} order reads in {}: {}", expected, language, lines);
    }

    /** Three orders a player can tell apart, and three lists that really differ. */
    private void checkOrdersDiffer(VisualContext context) {
        for (TerminalSort sort : TerminalSort.values()) {
            if (!sortIcons.containsKey(sort) || !sortLists.containsKey(sort))
                throw new VisualTestException("the button never reached the " + sort + " order");
        }
        for (TerminalSort one : TerminalSort.values()) {
            for (TerminalSort other : TerminalSort.values()) {
                if (one.ordinal() >= other.ordinal())
                    continue;
                if (sortIcons.get(one) == sortIcons.get(other))
                    throw new VisualTestException("the " + one + " and " + other + " orders draw the same icon");
                if (sortLists.get(one).equals(sortLists.get(other)))
                    throw new VisualTestException("the " + one + " and " + other + " orders produce the same list");
            }
        }
        if (Set.copyOf(longestNames.values()).size() != 1)
            throw new VisualTestException("the longest name is cut differently per order: " + longestNames);
        LOGGER.info(PREFIX + "terminal: three orders, three icons, three lists; '{}' reads the same in all of them",
                longestNames.get(TerminalSort.AMOUNT));
    }

    /** Scrolls the grid down by one row, the way a player's wheel does. */
    private static void scrollDown(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        int before = terminal.scrollRow();
        for (int notch = 0; notch < SCROLL_NOTCHES && terminal.scrollRow() == before; notch++)
            ScreenInput.scroll(context.minecraft(), ScreenInput.terminalCell(terminal, 0), -1.0);
        if (terminal.scrollRow() <= before)
            throw new VisualTestException("the grid did not scroll down (row " + terminal.scrollRow() + ")");
    }

    /** Scrolls back to the top, so the shots after this one show the list from its first row again. */
    private static void scrollUp(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        for (int notch = 0; notch < SCROLL_NOTCHES + 1 && terminal.scrollRow() > 0; notch++)
            ScreenInput.scroll(context.minecraft(), ScreenInput.terminalCell(terminal, 0), 1.0);
        if (terminal.scrollRow() != 0)
            throw new VisualTestException("the grid did not scroll back to the top (row " + terminal.scrollRow() + ")");
    }

    /**
     * Requests one item of the first row the scrolled grid shows that no stock rule governs, so the request is accepted
     * and the server really pushes new counts. A rule would raise the confirmation panel instead, and a refusal counts
     * nothing — either way there would be no push to check against.
     */
    private void requestForPush(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        scrollBeforePush = terminal.scrollRow();
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            StockLine<ItemKey> line = visible.get(cell);
            // The repeated-click check above already owns one item type, and two checks counting the same one would
            // leave two expected counts equal, which requestedRanking() has nothing to compare against.
            if (line.rule().isPresent() || line.available() <= 0L || line.key().equals(stableKey))
                continue;
            if (!terminal.requestVisible(cell, TerminalAmounts.Click.SELECTED))
                throw new VisualTestException("the scrolled grid refused the click on " + line.name());
            // Remembered because it is a real, accepted request and therefore counts: every later check of this
            // player's store has to expect it, or it would read as something that was counted without being asked for.
            pushRequestKey = line.key();
            LOGGER.info(PREFIX + "terminal: requested one {} with the grid scrolled to row {}", line.name(),
                    scrollBeforePush);
            return;
        }
        throw new VisualTestException("the scrolled grid shows no item that could be requested without a question");
    }

    /** What the push may not do: move the grid, or put the list back into the order the player left. */
    private void checkScrollKept(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.scrollRow() != scrollBeforePush)
            throw new VisualTestException("the server's push moved the grid from row " + scrollBeforePush + " to row "
                    + terminal.scrollRow());
        if (terminal.sort() != TerminalSort.AMOUNT)
            throw new VisualTestException("the server's push changed the order to " + terminal.sort());
        LOGGER.info(PREFIX + "terminal: the counts arrived, the grid stayed at row {} in the {} order",
                terminal.scrollRow(), terminal.sort());
    }

    // --- a "most used" order with a real ranking (M24, issue #17) ---------------------------------------------------

    /**
     * Asks the terminal for {@link #USAGE_REQUESTS} item types, several clicks each, so that "most used" has something
     * to be most used <i>about</i>.
     * <p>
     * Before this the scenario had asked for exactly one item type {@value #MERGE_CLICKS}+1 times, and the third order
     * could therefore only be checked by "the favourite is in front" — which the amount order would also satisfy for
     * an item that happens to be the largest stack. With four item types at four different counts the order is a
     * sequence that no other order produces, and the three smallest stacks of the aisle being the favourites means the
     * amount order's last rows are the used order's first ones.
     * <p>
     * The clicks go through the screen's own request path, one per step and therefore one per tick, like the merge
     * clicks above: a menu answers at most {@code WarehouseTerminalMenu.MAX_REQUESTS_PER_TICK} requests in a tick, and
     * the counting is part of accepting a request, so clicks that were never answered would never be counted either.
     * Afterwards the run waits twice, with the push interval in between, until this terminal has no open request left
     * and the crane is idle: every later assertion compares a list the client holds with one the server builds, and a
     * crane still carrying items would move the amounts between the two reads.
     */
    private void usageSteps(VisualScript script) {
        for (UsageRequest request : USAGE_REQUESTS) {
            for (int click = 1; click <= request.clicks(); click++)
                script.client("terminal: ask for " + request.name() + " (" + click + " of " + request.clicks() + ")",
                        context -> requestOne(context, request));
        }
        script.until("terminal: wait until everything that was asked for has arrived",
                        TerminalVisualScenario::warehouseSettled, DELIVERY_TIMEOUT_TICKS)
                .waitTicks(PUSH_TICKS)
                .until("terminal: check that the warehouse really came to rest",
                        TerminalVisualScenario::warehouseSettled, SCREEN_TIMEOUT_TICKS)
                .server("terminal: check the request counts the server has written", this::checkCountedRequests)
                .client("terminal: check that the status texts still fit their rows",
                        TerminalVisualScenario::checkStatusFits);
    }

    /** One click on {@code request}'s item, wherever the current order has put it in the grid. */
    private void requestOne(VisualContext context, UsageRequest request) {
        WarehouseTerminalScreen terminal = screen(context);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (!visible.get(cell).key().equals(request.key()))
                continue;
            if (!terminal.requestVisible(cell, TerminalAmounts.Click.SELECTED))
                throw new VisualTestException("the grid refused the click on " + request.name());
            return;
        }
        throw new VisualTestException(request.name() + " is not in the visible grid, so it cannot be clicked");
    }

    /** No open request of this terminal and an idle crane: the one state in which two reads cannot disagree. */
    private static boolean warehouseSettled(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        return terminal.status().requestsHere() == 0 && terminal.status().cranePhase() == CranePhase.IDLE;
    }

    /**
     * What this player's clicks taught the server, read from the server's own store before anything is shot.
     * <p>
     * It is the assertion the screenshots of the three orders rest on: a "most used" list is only right if the counts
     * behind it are the ones the player's clicks made, and no screenshot can show a count. The <b>size</b> is checked
     * as well as the counts, because an extra entry would mean something was counted that no player asked for — the
     * redstone path, a refusal, or a line of the clipboard order — and that is exactly what must not happen.
     */
    private void checkCountedRequests(MinecraftServer server, VisualContext context) {
        TerminalPreferences preferences = TerminalPreferences.of(context.serverPlayer(server));
        Map<ItemKey, Long> expected = expectedCounts();
        for (Map.Entry<ItemKey, Long> wanted : expected.entrySet()) {
            long counted = preferences.usage().countFor(wanted.getKey());
            if (counted != wanted.getValue())
                throw new VisualTestException("the server counted " + counted + " requests of "
                        + displayName(wanted.getKey()) + " for this player, not " + wanted.getValue() + " ("
                        + describeCounts(preferences) + ")");
        }
        if (preferences.usage().size() != expected.size())
            throw new VisualTestException("the server remembers " + preferences.usage().size()
                    + " item types for this player, not the " + expected.size() + " that were really asked for ("
                    + describeCounts(preferences) + ")");
        LOGGER.info(PREFIX + "terminal: the server counted {}", describeCounts(preferences));
    }

    /**
     * How often each item type was really asked for in this scenario, counted from the clicks it makes: the first
     * requested item with its {@value #MERGE_CLICKS} repeats, the producible item that was ordered once, and
     * {@link #USAGE_REQUESTS}.
     */
    private Map<ItemKey, Long> expectedCounts() {
        Map<ItemKey, Long> expected = new LinkedHashMap<>();
        if (requestedKey != null)
            expected.merge(requestedKey, MERGE_CLICKS + 1L, Long::sum);
        // Ordering something only a production station can make is an accepted request like any other, so it counts
        // once as well - and it stays at the end of every order anyway, because it is an offer and not stock.
        expected.merge(PRODUCT, 1L, Long::sum);
        for (UsageRequest request : USAGE_REQUESTS)
            expected.merge(request.key(), (long) request.clicks(), Long::sum);
        ItemKey pushed = pushRequestKey;
        if (pushed != null)
            expected.merge(pushed, 1L, Long::sum);
        // The repeated clicks on one cell (M24 review fix) are accepted requests like any other, counted as often as
        // they have really been made so far, so a check between two of them expects the right number too.
        ItemKey stable = stableKey;
        if (stable != null && stableClicksDone > 0)
            expected.merge(stable, (long) stableClicksDone, Long::sum);
        return expected;
    }

    /**
     * The item types this player asked for, most often first and without the producible offer: the sequence the "most
     * used" order has to <b>begin</b> with.
     * <p>
     * It is computed from the clicks the scenario itself makes, never read back from the store under test, so the
     * check cannot agree with a wrong implementation. The counts have to differ from each other — a ranking with two
     * equal counts would have no defined sequence, and a check that tolerated either would not be one.
     */
    private List<ItemKey> requestedRanking() {
        List<Map.Entry<ItemKey, Long>> ranked = new ArrayList<>(expectedCounts().entrySet());
        ranked.removeIf(entry -> entry.getKey().equals(PRODUCT));
        ranked.sort(Comparator.comparingLong((Map.Entry<ItemKey, Long> entry) -> -entry.getValue()));
        List<ItemKey> keys = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            if (index > 0 && ranked.get(index - 1).getValue().equals(ranked.get(index).getValue()))
                throw new VisualTestException("the scenario asked for " + displayName(ranked.get(index).getKey())
                        + " and " + displayName(ranked.get(index - 1).getKey()) + " equally often, so 'most used' has "
                        + "no defined sequence to be checked against");
            keys.add(ranked.get(index).getKey());
        }
        return List.copyOf(keys);
    }

    /** This player's whole store, strongest first, for a log line and for the message of a failed check. */
    private static String describeCounts(TerminalPreferences preferences) {
        List<String> counts = new ArrayList<>();
        for (TerminalUsage.Entry<ItemKey> entry : preferences.counts())
            counts.add(displayName(entry.key()) + " x" + entry.count());
        return "sort=" + preferences.sort() + ", used=" + counts;
    }

    // --- the server checks the first rows of every order ------------------------------------------------------------

    /**
     * Reads the rows the screen shows and then lets the <b>server</b> check them against its own data, as the two steps
     * before every sort shot.
     * <p>
     * The client already asserts that each order <i>is</i> its order (see {@link #checkOrderItself}), but it does so
     * from the very list it would draw: a client that sorted by the wrong counts would agree with itself. The server
     * owns the counts and the stock, so it can say which item types the first rows have to be — and that is what makes
     * a screenshot of "most used" evidence rather than an illustration.
     */
    private void assertRowsOnServer(VisualScript script, TerminalSort sort) {
        String label = sort.name().toLowerCase(Locale.ROOT);
        script.client("terminal: read the rows the screen shows in the " + label + " order", this::readShownRows)
                .server("terminal: the server checks the first rows of the " + label + " order",
                        (server, context) -> checkRowsOnServer(server, context, sort));
    }

    /** Hands the screen's current list to the server step after it. */
    private void readShownRows(VisualContext context) {
        List<StockLine<ItemKey>> matching = screen(context).matchingEntries();
        List<ItemKey> keys = new ArrayList<>(matching.size());
        for (StockLine<ItemKey> line : matching)
            keys.add(line.key());
        shownRows = List.copyOf(keys);
        shownKeys = Set.copyOf(keys);
    }

    /**
     * Builds the list {@code sort} has to produce from the server's own stock snapshot and the server's own request
     * counts, and fails the run unless the screen's first {@value #ASSERTED_ROWS} rows are exactly its first
     * {@value #ASSERTED_ROWS}.
     * <p>
     * The snapshot is taken through {@code WarehouseTerminalMenu#stockCounts}, i.e. through the very method whose
     * result is sent to the screen, and the counts through {@link TerminalPreferences}, i.e. the player's own save
     * data. The whole <b>key set</b> is compared as well: first rows that matched while the two sides were talking
     * about different lists would prove nothing.
     * <p>
     * <b>Why this needs no tie-break of its own, and when it would.</b> Every comparator of {@link TerminalSort} is a
     * total order over the <i>visible texts and amounts</i> — not over the keys, as its own class comment says: two
     * distinct {@code ItemKey}s that agree on all of them are a tie, and a stable sort then leaves such a pair in
     * whatever order the list it sorted already had. The two sides build that list differently (the client appends a
     * delta's new keys to its {@code LinkedHashMap}, the server rebuilds in {@code TerminalStockEntry.ORDER}), so a
     * tie group straddling the {@value #ASSERTED_ROWS}-row boundary could make this check fail on a difference a
     * player cannot see. It cannot happen here because this scenario stocks only item types with distinct names, and
     * that is a property of the scenario, not of the comparators. A scenario that stocked two rows a player cannot
     * tell apart (two enchanted books, two filled shulker boxes) would have to sort both sides by
     * {@code ItemKey.ORDER} first — the tie-break the content layer already uses for exactly this reason.
     */
    private void checkRowsOnServer(MinecraftServer server, VisualContext context, TerminalSort sort) {
        ServerPlayer player = context.serverPlayer(server);
        TerminalPreferences preferences = TerminalPreferences.of(player);
        List<StockLine<ItemKey>> lines = serverLines(player);
        if (!Set.copyOf(keysOf(lines)).equals(shownKeys))
            throw new VisualTestException("the server has " + lines.size() + " rows for the " + sort
                    + " order, the screen shows " + shownKeys.size() + "; they are not the same list");
        lines.sort(sort.comparator(preferences.usage()));
        List<ItemKey> expected = firstRows(keysOf(lines));
        List<ItemKey> shown = firstRows(shownRows);
        if (!expected.equals(shown))
            throw new VisualTestException("the " + sort + " order should begin with " + names(expected)
                    + ", but the screen shows " + names(shown) + " (" + describeCounts(preferences) + ")");
        LOGGER.info(PREFIX + "terminal: the server confirms the {} order begins with {}", sort, names(expected));
    }

    /**
     * The terminal's stock as the <b>server</b> has it, as the lines a screen would hold: the same conversion the push
     * uses ({@code WarehouseTerminalMenu#stockCounts}) plus the two texts a client resolves for itself, because the
     * display name is language dependent and is therefore never sent.
     */
    private static List<StockLine<ItemKey>> serverLines(ServerPlayer player) {
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu))
            throw new VisualTestException("the player has no terminal menu open on the server");
        List<StockLine<ItemKey>> lines = new ArrayList<>();
        for (StockCount<ItemKey> counts : menu.stockCounts()) {
            StockLine<ItemKey> line = StockLine.of(counts, displayName(counts.key()), modId(counts.key()));
            if (line.isShown())
                lines.add(line);
        }
        return lines;
    }

    private static List<ItemKey> keysOf(List<StockLine<ItemKey>> lines) {
        List<ItemKey> keys = new ArrayList<>(lines.size());
        for (StockLine<ItemKey> line : lines)
            keys.add(line.key());
        return keys;
    }

    private static List<ItemKey> firstRows(List<ItemKey> keys) {
        return List.copyOf(keys.subList(0, Math.min(ASSERTED_ROWS, keys.size())));
    }

    private static List<String> names(List<ItemKey> keys) {
        List<String> names = new ArrayList<>(keys.size());
        for (ItemKey key : keys)
            names.add(displayName(key));
        return names;
    }

    /** The name the grid's tooltip shows; resolved the same way on both sides ({@code WarehouseTerminalScreen}). */
    private static String displayName(ItemKey key) {
        return key.toStack().getHoverName().getString();
    }

    private static String modId(ItemKey key) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(key.getItem());
        return id == null ? "" : id.getNamespace();
    }

    // --- the chosen order survives a save, quit and rejoin ----------------------------------------------------------

    /**
     * The order a player chose and the counts behind it, through a <b>real</b> save, quit to the title screen and
     * rejoin of the same world (M24, issue #17, ADR-037).
     * <p>
     * This is the one claim of the milestone that nothing else can show. A GameTest server cannot quit to a title
     * screen, so {@code TerminalUsageGameTests} can only exercise the serializer; the robustness scenario proves the
     * <i>data</i> comes back but has no screen open. Here the screen itself is opened again after the rejoin and has to
     * come up in {@link #KEPT_SORT} with nobody pressing anything — which is what a player would call "it remembered".
     * <p>
     * The world is saved explicitly first, exactly as {@code VisualWorld#saveAndQuit} does, instead of relying on the
     * save that leaving does: the point of the step is the save, so it is not left to a side effect. The watchdog is
     * re-armed afterwards because loading a world again costs time that the run's own budget never allowed for.
     */
    private void reloadSteps(VisualScript script) {
        script.until("terminal: wait until the scrolled-grid request has arrived too",
                        TerminalVisualScenario::warehouseSettled, DELIVERY_TIMEOUT_TICKS)
                .client("terminal: press the sort button to the order that is to survive",
                        TerminalVisualScenario::pressSort)
                .until("terminal: wait until the grid is in the kept order",
                        context -> screen(context).sort() == KEPT_SORT, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: check the kept order before the save", context -> checkSort(context, KEPT_SORT));
        assertRowsOnServer(script, KEPT_SORT);
        script.client("terminal: park the mouse beside the sort button", TerminalVisualScenario::hoverOffButton)
                .waitTicks(SETTLE_TICKS)
                .shot("reload-chosen")
                .server("terminal: remember what the server holds for this player", this::rememberPreferences)
                .client("terminal: close the screen before the save", TerminalVisualScenario::closeScreen)
                .until("terminal: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS)
                .server("terminal: save the world", (server, context) -> server.saveEverything(true, true, true));
        // The title screen between the two terminal shots is what makes them a sequence: without it they are two
        // pictures of the same screen, and nothing in either says that the game was closed in between.
        VisualWorld.reload(script, worldProfile(), atTitle -> atTitle.waitTicks(SETTLE_TICKS).shot("reload-title"));
        script.client("terminal: give the reload its own watchdog phase",
                        context -> context.watchdog().rearm(RELOAD_WATCHDOG_MILLIS, "terminal after the reload"))
                .camera(AT_TERMINAL)
                .serverUntil("terminal: wait until the aisle is indexed again after the rejoin",
                        TerminalVisualScenario::sceneReloaded, SCENE_READY_TIMEOUT_TICKS)
                .server("terminal: the order and the counts came back from the player's save data",
                        this::checkPreferencesSurvived)
                .server("terminal: open the terminal screen again", TerminalVisualScenario::openScreen)
                .until("terminal: wait for the screen with its stock", TerminalVisualScenario::screenReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(PUSH_TICKS)
                .client("terminal: the screen came up in the kept order without anyone pressing anything",
                        this::checkSortCameBack);
        assertRowsOnServer(script, KEPT_SORT);
        script.client("terminal: park the mouse beside the sort button", TerminalVisualScenario::hoverOffButton)
                .waitTicks(SETTLE_TICKS)
                .shot("reload-kept");
        tooltipShot(script, KEPT_SORT, "-reloaded");
    }

    /** What the server holds before the save; the rejoin is compared against this text and against the counts. */
    private void rememberPreferences(MinecraftServer server, VisualContext context) {
        TerminalPreferences preferences = TerminalPreferences.of(context.serverPlayer(server));
        if (preferences.sort() != KEPT_SORT)
            throw new VisualTestException("the server stored " + preferences.sort() + " instead of the pressed "
                    + KEPT_SORT + "; the press never reached it");
        preferencesBeforeReload = describeCounts(preferences);
        LOGGER.info(PREFIX + "terminal: before the save the server holds {}", preferencesBeforeReload);
    }

    /**
     * After the rejoin: the order and every count are back, read from the <b>new</b> player object the world load
     * built, i.e. really from {@code playerdata/<uuid>.dat} and not from anything still in memory.
     */
    private void checkPreferencesSurvived(MinecraftServer server, VisualContext context) {
        TerminalPreferences preferences = TerminalPreferences.of(context.serverPlayer(server));
        if (preferences.sort() != KEPT_SORT)
            throw new VisualTestException("the chosen order did not survive the rejoin: " + preferences.sort()
                    + " instead of " + KEPT_SORT);
        String after = describeCounts(preferences);
        if (!after.equals(preferencesBeforeReload))
            throw new VisualTestException("the request counts did not survive the rejoin: " + after + " instead of "
                    + preferencesBeforeReload);
        checkCountedRequests(server, context);
        LOGGER.info(PREFIX + "terminal: PASS the order and the counts came back after the save, quit and rejoin ({})",
                after);
    }

    /** The screen a rejoined player opens: the stored order, and the list that order produces, with no press at all. */
    private void checkSortCameBack(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.sort() != KEPT_SORT)
            throw new VisualTestException("the reopened screen is in the " + terminal.sort() + " order, not in the "
                    + KEPT_SORT + " one the player chose before the save");
        checkSort(context, KEPT_SORT);
        LOGGER.info(PREFIX + "terminal: after the rejoin the screen opened in the {} order by itself, beginning with "
                + "{}", terminal.sort(), firstNames(terminal.matchingEntries()));
    }

    // --- every hard-clipped row, in every language ------------------------------------------------------------------

    /** One hard-clipped row, with the budget its texts have to fit and the ones that are allowed not to. */
    private record MeasuredRow(String label, int budget, Map<String, Component> texts, Set<String> allowedTooWide) {
        static MeasuredRow of(String label, int budget, Map<String, Component> texts) {
            return new MeasuredRow(label, budget, texts, Set.of());
        }
    }

    /**
     * Every text a hard-clipped row of the terminal or of the production station can hold, measured against that
     * row's own budget in the language that is loaded.
     * <p>
     * {@code statusTextsFit()} only ever sees the state the warehouse happens to be in while the run looks, and the
     * earlier runs recorded exactly that gap: with the client switched to German the idle line read
     * "Regalbedienger&auml;t: Wartet auf einen Auftrag" at 219 px in a 216 px row, was silently cut by the screen's
     * own {@code fitToRow}, and the index line said {@code statusFits=false} without anything failing. So this walks
     * the whole <b>vocabulary</b> of every such row instead and reports <b>all</b> offenders of all rows at once,
     * because a translation that is too long is never too long alone.
     * <p>
     * The rows are:
     * <ul>
     * <li>the terminal's <b>status row</b> ({@link #statusRow}): every {@link CranePhase}, every
     *     {@link CranePauseReason}, the two "no aisle" and "no crane" lines, the waiting line and every refusal with
     *     the widest amounts they can show, the whole clipboard vocabulary - the progress line in each of its
     *     states, the receipt and every answer the list button can produce (M23) - and every answer a craft click at
     *     the crafting panel can end in (M32), including a Fetch refused in a production chain's own sentence;</li>
     * <li>the <b>state column</b> of the terminal's order lines, for an open order, for a finished one and behind a
     *     chain's step badge ({@link #orderStateColumns});</li>
     * <li>the production station's <b>order line</b> and its <b>stopped row</b> ({@link #stationRows}).</li>
     * </ul>
     * Every budget is asked of the screen that draws the row ({@code WarehouseTerminalScreen#orderStateBudget},
     * {@code WarehouseProductionScreen#orderStateBudget}) rather than copied into this file, where the two could
     * drift apart without anything saying so.
     */
    private static void checkRowVocabularyFits(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        Font font = context.minecraft().font;
        String language = context.minecraft().getLanguageManager().getSelected();
        List<MeasuredRow> rows = new ArrayList<>();
        rows.add(statusRow(terminal, language));
        rows.addAll(orderStateColumns(terminal));
        rows.addAll(stationRows());

        List<String> failures = new ArrayList<>();
        for (MeasuredRow row : rows) {
            Map<String, String> tooWide = new LinkedHashMap<>();
            int widest = 0;
            for (Map.Entry<String, Component> line : row.texts().entrySet()) {
                int width = font.width(line.getValue());
                widest = Math.max(widest, width);
                if (width > row.budget())
                    tooWide.put(line.getKey(), "'" + line.getValue().getString() + "' " + width + " px (+"
                            + (width - row.budget()) + ")");
            }
            // An exact set, not an upper bound: a new text that does not fit fails here, and so does one of the known
            // ones being shortened and its entry left behind, which is the only way an entry can ever be removed
            // without someone noticing.
            if (!tooWide.keySet().equals(row.allowedTooWide()))
                failures.add("the " + row.label() + " (" + row.budget() + " px) does not hold " + tooWide
                        + ", and the only ones that may not fit it are " + row.allowedTooWide());
            LOGGER.info(PREFIX + "terminal: {} of {} texts fit the {} ({} px) in {}; widest {} px, slack {} px; "
                    + "known exceptions {}", row.texts().size() - tooWide.size(), row.texts().size(), row.label(),
                    row.budget(), language, widest, row.budget() - widest, tooWide.keySet());
        }
        if (!failures.isEmpty())
            throw new VisualTestException("texts of " + failures.size() + " row(s) do not fit them in " + language
                    + ": " + String.join("; ", failures));
    }

    /** The terminal's status row: the crane's words, the terminal's own, the request answers and the clipboard's. */
    private static MeasuredRow statusRow(WarehouseTerminalScreen terminal, String language) {
        Map<String, Component> row = new LinkedHashMap<>();
        for (CranePhase phase : CranePhase.values())
            row.put("phase." + phase.name().toLowerCase(Locale.ROOT),
                    terminal.craneStatusText(WareworksLang.translateDirect(WareworksLang.cranePhaseKey(phase))));
        for (CranePauseReason reason : CranePauseReason.values()) {
            // NONE is "not paused" and has no text at all: the status row asks for a pause reason only while
            // TerminalScreenStatus#isPaused() is true, which is exactly "the reason is not NONE". Measuring it would
            // measure the raw lang key - and the run did, at 246 px, which is how this comment came to be written.
            if (reason == CranePauseReason.NONE)
                continue;
            row.put("pause." + reason.name().toLowerCase(Locale.ROOT),
                    terminal.craneStatusText(WareworksLang.translateDirect(reason.langKey())));
        }
        row.put("no_aisle", WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_AISLE));
        row.put("no_crane", WareworksLang.translateDirect(WareworksLang.TERMINAL_NO_CRANE));
        row.put("loading", WareworksLang.translateDirect(WareworksLang.TERMINAL_LOADING));
        // The waiting line with the two widest amounts a request can carry, which is what the javadoc of this check
        // has claimed since M24 while the map never held it.
        row.put("waiting", WareworksLang.translateDirect(WareworksLang.TERMINAL_WAITING, amount(WIDEST_AMOUNT),
                amount(WIDEST_AMOUNT)));
        // The clipboard order (M23, issue #19). Its progress line wins this row while the order has work to do, so
        // every state it can be in while it does is measured; DONE gives the row back and shows the receipt instead.
        for (ListOrderState state : ListOrderState.values()) {
            if (state != ListOrderState.DONE)
                row.put("list.status." + state.name().toLowerCase(Locale.ROOT), WarehouseTerminalScreen
                        .listStatusText(state, WIDEST_ENTRIES, WIDEST_ENTRIES, WIDEST_AMOUNT));
        }
        row.put("list.status_done", WarehouseTerminalScreen.listDoneText(WIDEST_ENTRIES, WIDEST_ENTRIES));
        for (TerminalListResult result : TerminalListResult.values())
            row.put("list.result." + result.name().toLowerCase(Locale.ROOT),
                    WareworksLang.translateDirect(result.langKey()));
        // Every refusal as the row shows it, i.e. through the frame-dropping decision itself.
        for (RequestRejection rejection : RequestRejection.values())
            row.put("refused." + rejection.name().toLowerCase(Locale.ROOT),
                    terminal.refusedLine(WareworksLang.translateDirect(rejection.langKey())));
        // Crafting at the terminal (M32, issue #24): every answer a craft click can end in, built by the screen's own
        // method with the widest count and without the names, which give way in this row first
        // (WarehouseTerminalScreen#craftAnswerLine). A Shift-click that crafted some and then stopped puts the refusal
        // after the count; only the refusals that can arise inside the run are measured that way, because the others
        // (locked, out of reach, empty grid) are decided before the first craft, and the award unlocks a recipe.
        for (TerminalCraftResult result : TerminalCraftResult.values())
            row.put("craft.result." + result.name().toLowerCase(Locale.ROOT), WarehouseTerminalScreen.craftAnswerText(
                    result, result.isSuccess() ? WIDEST_AMOUNT : 0, Component.empty(), Component.empty()));
        for (TerminalCraftResult result : List.of(TerminalCraftResult.NO_ROOM, TerminalCraftResult.NOT_IN_BUFFER,
                TerminalCraftResult.NO_RECIPE))
            row.put("craft.partial." + result.name().toLowerCase(Locale.ROOT), WarehouseTerminalScreen
                    .craftAnswerText(result, WIDEST_AMOUNT, Component.empty(), Component.empty()));
        // The craft order (issue #24, slice 2) wins this row like the clipboard's while it has work to do, in every
        // state it can be in then, with the result's name left out because it gives way first; then the line it
        // leaves when it finishes, and the answer to an edit of the grid it locks.
        for (ListOrderState state : ListOrderState.values()) {
            if (state.isOpen())
                row.put("craft.status." + state.name().toLowerCase(Locale.ROOT),
                        WarehouseTerminalScreen.craftStatusText(state, WIDEST_AMOUNT, Component.empty()));
        }
        row.put("craft.done", WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_DONE));
        row.put("craft.grid_locked", WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_GRID_LOCKED));
        // A Fetch no production chain can help (issue #24, slice 3) is refused in the planner's own sentence, with the
        // item the chain ran into left out, because it gives way first (WarehouseTerminalScreen#craftFetchAnswerLine).
        for (PlanRefusal refusal : PlanRefusal.values())
            row.put("craft.chain_refusal." + refusal.name().toLowerCase(Locale.ROOT),
                    WarehouseTerminalScreen.craftChainRefusalText(refusal, Component.empty()));
        // Package doors (M27, issue #18): the answers to a door click and the standing line about the chosen door, built
        // by the screen's own methods with the widest amounts and without the names, which give way in this row first
        // (WarehouseTerminalScreen#fitDoorLine); the fallback when the door goes; and a door's OUTPUT_FULL through the
        // frame-dropping decision. The two door rejections are among the refusals above.
        row.put("door.requested", WarehouseTerminalScreen.doorRequestedText(Component.empty(), WIDEST_AMOUNT,
                Component.empty()));
        row.put("door.producing", WarehouseTerminalScreen.doorProducingText(Component.empty(), WIDEST_AMOUNT,
                Component.empty(), WIDEST_AMOUNT));
        row.put("door.standing", WarehouseTerminalScreen.doorStandingText(Component.empty(), WIDEST_AMOUNT,
                WIDEST_AMOUNT));
        row.put("door.standing_short", WarehouseTerminalScreen.doorStandingShortText(Component.empty(),
                WIDEST_AMOUNT));
        row.put("door.unavailable", WarehouseTerminalScreen.doorUnavailableText());
        row.put("door.refused_full", terminal.refusedLine(WarehouseTerminalScreen.doorRefusedFullText()));
        // An accepted door click whose chain was refused: every chain sentence behind the door it was for, both names
        // left out because they give way first.
        for (PlanRefusal refusal : PlanRefusal.values())
            row.put("door.shortfall." + refusal.name().toLowerCase(Locale.ROOT), WarehouseTerminalScreen
                    .doorShortfallText(Component.empty(), WareworksLang.translateDirect(refusal.langKey(),
                            Component.empty())));
        return new MeasuredRow("terminal status row", ROW_WIDTH, row,
                STATUS_ROW_TOO_LONG.getOrDefault(language, Set.of()));
    }

    /**
     * The state column of the terminal's order lines, in its three widths: an open order keeps the cancel mark's
     * column, a finished one gets it back, and a chain's line pays for its step badge out of this very column.
     */
    private static List<MeasuredRow> orderStateColumns(WarehouseTerminalScreen terminal) {
        Map<String, Component> open = new LinkedHashMap<>();
        Map<String, Component> finished = new LinkedHashMap<>();
        for (ProductionOrderState state : ProductionOrderState.values()) {
            String key = "state." + state.name().toLowerCase(Locale.ROOT);
            (state.isFinished() ? finished : open).put(key, WarehouseTerminalScreen.orderStateText(state, false));
            // "lostIngredients" is "finished, not complete and something was already delivered", so the marker can
            // only ever sit on one of these two states - measuring it on the others would measure a line no
            // warehouse can produce.
            if (state.isFinished() && state != ProductionOrderState.COMPLETE)
                finished.put("order_lost." + state.name().toLowerCase(Locale.ROOT),
                        WarehouseTerminalScreen.orderStateText(state, true));
        }
        Component badge = WarehouseTerminalScreen.stepBadgeText(planSteps());
        Map<String, Component> chain = new LinkedHashMap<>(open);
        // For a chain the column normally holds the frontier instead of a state: the item's name inside it gives way
        // by design, so what has to fit is the sentence around it.
        chain.put("plan.frontier", WarehouseTerminalScreen.frontierText(Component.empty()));
        return List.of(
                MeasuredRow.of("state column of an open order line", terminal.orderStateBudget(false, null), open),
                MeasuredRow.of("state column of a finished order line", terminal.orderStateBudget(true, null),
                        finished),
                new MeasuredRow("state column behind a chain's '" + badge.getString() + "' badge",
                        terminal.orderStateBudget(false, badge), chain, CHAIN_COLUMN_TOO_LONG));
    }

    /**
     * The production station's clipped rows: the state column of an order line, and the stopped row in both its forms.
     * <p>
     * They are measured here and not in a station scenario of their own because what is being measured is a
     * <b>translation</b> against a layout number, and both only need the font and the station's own arithmetic - while
     * this scenario is the one that switches the client's language, which is where the defects live. The German
     * {@code gui.production.waiting_for_step} was 172 px against a 158 px column and the German stopped row spent 250
     * px before the item's name even started, and nothing measured either of them.
     */
    private static List<MeasuredRow> stationRows() {
        Map<String, Component> column = new LinkedHashMap<>();
        for (ProductionOrderState state : ProductionOrderState.values()) {
            column.put("state." + state.name().toLowerCase(Locale.ROOT),
                    WarehouseProductionScreen.orderStateText(state, false, false));
            if (state.isFinished() && state != ProductionOrderState.COMPLETE)
                column.put("order_lost." + state.name().toLowerCase(Locale.ROOT),
                        WarehouseProductionScreen.orderStateText(state, false, true));
        }
        // An order waiting for an earlier step of its own chain is open, so it can carry no "lost" marker.
        column.put("waiting_for_step",
                WarehouseProductionScreen.orderStateText(ProductionOrderState.WAITING_FOR_INGREDIENTS, true, false));
        // The stopped row leads with the item's name, so what a long name pushes off the end is everything after it:
        // the singular form is measured with no name at all against a row that still owes the name its floor, and the
        // plural form, which has no name in it, against the whole row.
        Map<String, Component> stopped = new LinkedHashMap<>();
        stopped.put("stopped_line", WarehouseProductionScreen.stoppedRowText(1, Component.empty()));
        Map<String, Component> stoppedMany = new LinkedHashMap<>();
        stoppedMany.put("stopped_line_many",
                WarehouseProductionScreen.stoppedRowText(WIDEST_STOPPED, Component.empty()));
        return List.of(
                MeasuredRow.of("state column of a station's order line",
                        WarehouseProductionScreen.orderStateBudget(), column),
                MeasuredRow.of("station's stopped row without the item's name",
                        ROW_WIDTH - WarehouseProductionScreen.MIN_ITEM_WIDTH, stopped),
                MeasuredRow.of("station's stopped row for several products", ROW_WIDTH, stoppedMany));
    }

    // --- the package door (M27, issue #18) --------------------------------------------------------------------------

    /**
     * The door button, driven like a player: real clicks, a right-click and the wheel through the mouse handler
     * ({@link ScreenInput}), on the button the screen's own hit test finds. The choice is the screen's alone, so every
     * step asserts it on the client, and the server says where each request really goes.
     * <p>
     * It runs last, after the rejoin, so nothing a door click counts can reach the orders and rankings the earlier steps
     * assert; and the screen it starts on is the one the rejoin opened, which has to come up at here.
     */
    private void doorSteps(VisualScript script) {
        script.until("terminal: wait until the screen lists the package door", TerminalVisualScenario::doorListed,
                        SCREEN_TIMEOUT_TICKS)
                .client("terminal: the screen delivers here, and the door button is shown",
                        TerminalVisualScenario::checkDoorAtHere)
                .client("terminal: hover the door button", TerminalVisualScenario::hoverDoorButton)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: check the door button's tooltip at here",
                        context -> checkDoorTooltip(context, false))
                .shot("door-tooltip")
                .client("terminal: click the door button", TerminalVisualScenario::clickDoorButton)
                .client("terminal: check that the click chose the door", TerminalVisualScenario::checkDoorChosen)
                .client("terminal: the list button says a list still comes here",
                        TerminalVisualScenario::checkListSaysHere)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: check the door button's tooltip with the door chosen",
                        context -> checkDoorTooltip(context, true))
                .shot("door-chosen-tip")
                .client("terminal: hover the first stock cell", TerminalVisualScenario::hoverFirstCell)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: check the gold cues on the cell", TerminalVisualScenario::checkCellCue)
                .shot("door-chosen")
                // The choice belongs to the screen object, so laying the screen out again keeps it.
                .client("terminal: fold the crafting panel out", TerminalVisualScenario::clickCraftToggle)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: the fold kept the door", TerminalVisualScenario::checkDoorChosen)
                .client("terminal: fold the crafting panel back in", TerminalVisualScenario::clickCraftToggle)
                .waitTicks(SETTLE_TICKS)
                .client("terminal: the second fold kept the door", TerminalVisualScenario::checkDoorChosen)
                .client("terminal: lay the screen out again, as a window resize does",
                        context -> screen(context).resize(context.minecraft(), screen(context).width,
                                screen(context).height))
                .waitTicks(SETTLE_TICKS)
                .client("terminal: the re-layout kept the door", TerminalVisualScenario::checkDoorChosen)
                .client("terminal: right-click the door button", context -> ScreenInput.rightClick(
                        context.minecraft(), ScreenInput.terminalDoorButton(screen(context))))
                .client("terminal: the right-click went back to here", TerminalVisualScenario::checkDoorAtHere)
                .client("terminal: scroll down over the door button", context -> ScreenInput.scroll(
                        context.minecraft(), ScreenInput.terminalDoorButton(screen(context)), -1))
                .client("terminal: the wheel down chose the door", TerminalVisualScenario::checkDoorChosen)
                .client("terminal: scroll up over the door button", context -> ScreenInput.scroll(
                        context.minecraft(), ScreenInput.terminalDoorButton(screen(context)), 1))
                .client("terminal: the wheel up went back to here", TerminalVisualScenario::checkDoorAtHere)
                .client("terminal: click the door button again", TerminalVisualScenario::clickDoorButton)
                .client("terminal: the door is chosen again", TerminalVisualScenario::checkDoorChosen);
        doorQuestionSteps(script);
        doorStandingSteps(script);
        script.client("terminal: close the screen with the door chosen", TerminalVisualScenario::closeScreen)
                .until("terminal: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS)
                .server("terminal: open the terminal screen once more", TerminalVisualScenario::openScreen)
                .until("terminal: wait for the screen with its stock and the door",
                        context -> screenReady(context) && doorListed(context), SCREEN_TIMEOUT_TICKS)
                .client("terminal: a new screen delivers here again", TerminalVisualScenario::checkDoorAtHere)
                .client("terminal: choose the door on the new screen", TerminalVisualScenario::clickDoorButton)
                .client("terminal: the door is chosen on the new screen", TerminalVisualScenario::checkDoorChosen)
                .server("terminal: break the door's Packager", (server, context) -> server.overworld()
                        .destroyBlock(packagerPos(context.origin()), false))
                .until("terminal: wait until the choice fell back to here",
                        context -> !screen(context).isDoorChosen() && !screen(context).isDoorButtonShown(),
                        SCREEN_TIMEOUT_TICKS)
                .client("terminal: the screen says why the door went", TerminalVisualScenario::checkDoorGone)
                .client("terminal: park the mouse beside the sort button", TerminalVisualScenario::hoverOffButton)
                .waitTicks(SETTLE_TICKS)
                .shot("door-gone");
    }

    /**
     * A door click that crosses a stock rule raises the terminal's question, and the question names the door it was
     * measured for; the wheel over the door button changes nothing while it is up, and the Yes delivers to that door.
     */
    private void doorQuestionSteps(VisualScript script) {
        script.client("terminal: search for the item a rule holds in reserve",
                        context -> typeSearch(context, RESERVED_SEARCH))
                .waitTicks(SETTLE_TICKS)
                .client("terminal: click it for the door", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalCell(screen(context), 0)))
                .until("terminal: wait until the terminal asks", context -> screen(context).confirmation() != null,
                        REQUEST_TIMEOUT_TICKS)
                .client("terminal: the question names the door", TerminalVisualScenario::checkQuestionNamesDoor)
                .client("terminal: scroll over the door button while the question is up",
                        context -> ScreenInput.scroll(context.minecraft(), doorButtonCentre(context), -1))
                .client("terminal: the wheel changed nothing behind the question",
                        TerminalVisualScenario::checkDoorChosen)
                .waitTicks(SETTLE_TICKS)
                .shot("door-question")
                .client("terminal: answer yes", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalConfirmButton(screen(context), true)))
                .until("terminal: wait until the answer names the door",
                        context -> feedbackText(screen(context)).contains(DOOR_ADDRESS), REQUEST_TIMEOUT_TICKS)
                .serverUntil("terminal: wait until the crane put it into the door's port",
                        (server, context) -> doorPort(server.overworld(), context.origin()).bufferedItems()
                                .count(AT_RESERVE_ITEM) > 0, DELIVERY_TIMEOUT_TICKS)
                .server("terminal: none of it came to the terminal", TerminalVisualScenario::checkNothingHere)
                .client("terminal: clear the search after the question", context -> screen(context).setSearch(""));
    }

    /**
     * While the crane stands still, a door click's request waits, and once its answer has gone from the status row the
     * row says what the chosen door is still owed; the crane then delivers it into the door's port.
     */
    private void doorStandingSteps(VisualScript script) {
        script.serverUntil("terminal: wait until the crane is idle again", TerminalVisualScenario::warehouseIdle,
                        DELIVERY_TIMEOUT_TICKS)
                .server("terminal: stop the crane's motor, so the next request waits",
                        (server, context) -> motor(server.overworld(), context.origin()).generatedSpeed.setValue(0))
                .client("terminal: search for the door's last item", context -> typeSearch(context, DOOR_ITEM_SEARCH))
                .waitTicks(SETTLE_TICKS)
                .client("terminal: click it for the door", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalCell(screen(context), 0)))
                .until("terminal: wait until the answer names the door",
                        context -> feedbackText(screen(context)).contains(DOOR_ADDRESS)
                                && feedbackText(screen(context)).contains(DOOR_ITEM.toStack().getHoverName()
                                        .getString()), REQUEST_TIMEOUT_TICKS)
                .client("terminal: check that the door's answer fits its row", TerminalVisualScenario::checkStatusFits)
                .client("terminal: park the mouse beside the sort button", TerminalVisualScenario::hoverOffButton)
                .waitTicks(SETTLE_TICKS)
                .shot("door-sent")
                .server("terminal: the request goes to the door", TerminalVisualScenario::checkRequestForDoor)
                .waitTicks(FEEDBACK_GONE_TICKS)
                .until("terminal: wait until the door's row counts the request",
                        context -> chosenRow(context).waiting() > 0L, SCREEN_TIMEOUT_TICKS)
                .client("terminal: the status row says what the door is still owed",
                        TerminalVisualScenario::checkStandingLine)
                .shot("door-standing")
                .server("terminal: start the crane's motor again",
                        (server, context) -> motor(server.overworld(), context.origin()).generatedSpeed
                                .setValue(MOTOR_RPM))
                .serverUntil("terminal: wait until the crane put it into the door's port",
                        (server, context) -> doorPort(server.overworld(), context.origin()).bufferedItems()
                                .count(DOOR_ITEM) > 0, DELIVERY_TIMEOUT_TICKS)
                .server("terminal: none of it came to the terminal", TerminalVisualScenario::checkNothingHere)
                .client("terminal: clear the search after the door's last click",
                        context -> screen(context).setSearch(""));
    }

    /** Whether the screen lists the door, by its sign, with the door button shown. */
    private static boolean doorListed(VisualContext context) {
        if (!(context.minecraft().screen instanceof WarehouseTerminalScreen terminal))
            return false;
        return terminal.isDoorButtonShown()
                && terminal.doors().rows().stream().anyMatch(row -> row.label().equals(DOOR_ADDRESS));
    }

    /** The door's row as the screen last got it. */
    private static TerminalDoorRow doorRow(VisualContext context) {
        return screen(context).doors().rows().stream().filter(row -> row.label().equals(DOOR_ADDRESS)).findFirst()
                .orElseThrow(() -> new VisualTestException("the screen does not list the door '" + DOOR_ADDRESS
                        + "': " + screen(context).doors()));
    }

    /** The chosen door's row; fails while the choice is here. */
    private static TerminalDoorRow chosenRow(VisualContext context) {
        TerminalDoorRow row = doorRow(context);
        if (!screen(context).chosenDoor().equals(Optional.of(row.pos())))
            throw new VisualTestException("the door is not chosen: " + screen(context).chosenDoor());
        return row;
    }

    /** Here: no door chosen, the button shown but not green, the title row naming the warehouse. */
    private static void checkDoorAtHere(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.isDoorChosen() || terminal.isDoorButtonGreen() || !terminal.isDoorButtonShown())
            throw new VisualTestException("the screen should deliver here with the door button shown, not green: "
                    + "chosen " + terminal.chosenDoor() + ", green " + terminal.isDoorButtonGreen() + ", shown "
                    + terminal.isDoorButtonShown());
        String label = terminal.titleRowLabel().getString();
        String indicator = WarehouseTerminalScreen.doorIndicatorText(Component.empty()).getString().trim();
        if (label.startsWith(indicator))
            throw new VisualTestException("the title row names a door while the screen delivers here: " + label);
        TerminalDoorRow row = doorRow(context);
        if (row.state() != DoorState.READY)
            throw new VisualTestException("the door should be ready: " + row);
        LOGGER.info(PREFIX + "terminal: the screen delivers here; the door '{}' at {} is listed {}", row.label(),
                row.rackAddress(), row.state());
    }

    /** The door chosen: the button green, the title row naming the door in gold behind its arrow. */
    private static void checkDoorChosen(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        TerminalDoorRow row = chosenRow(context);
        if (!terminal.isDoorButtonGreen())
            throw new VisualTestException("the door button is not green while the door is chosen");
        String label = terminal.titleRowLabel().getString();
        String expected = WarehouseTerminalScreen.doorIndicatorText(Component.literal(DOOR_ADDRESS)).getString();
        if (!label.equals(expected))
            throw new VisualTestException("the title row reads '" + label + "', not '" + expected + "'");
        LOGGER.info(PREFIX + "terminal: the door {} is chosen; the title row reads '{}'", row.rackAddress(), label);
    }

    /** While a door is chosen the list button's tooltip says that a clipboard list still delivers here. */
    private static void checkListSaysHere(VisualContext context) {
        List<String> tooltip = screen(context).listButtonTooltip().stream().map(Component::getString).toList();
        String line = text(WareworksLang.TERMINAL_DOOR_LIST_HERE);
        if (!tooltip.contains(line))
            throw new VisualTestException("the list button's tooltip does not say '" + line + "': " + tooltip);
    }

    /**
     * The door button's tooltip: at most {@value WarehouseTerminalScreen#MAX_DOOR_TOOLTIP_LINES} lines inside the row,
     * where a click delivers in its first line, the current entry marked, the hint and Create's own scroll line.
     */
    private static void checkDoorTooltip(VisualContext context, boolean chosen) {
        WarehouseTerminalScreen terminal = screen(context);
        List<Component> tooltip = terminal.doorTooltip();
        List<String> lines = tooltip.stream().map(Component::getString).toList();
        checkDoorTooltipFits(context);
        String here = text(WareworksLang.TERMINAL_DOOR_HERE);
        TerminalDoorRow row = doorRow(context);
        String title = WarehouseTerminalScreen.doorTitleText(Component.literal(chosen ? DOOR_ADDRESS : here))
                .getString() + (chosen ? " " + row.rackAddress() : "");
        String current = "-> " + (chosen ? DOOR_ADDRESS + "  " + row.rackAddress() : here);
        String hint = text(WareworksLang.TERMINAL_DOOR_HINT);
        String scroll = Component.translatable(WareworksLang.CREATE_SCROLL_TO_SELECT_KEY).getString();
        if (lines.isEmpty() || !lines.getFirst().equals(title) || !lines.contains(current) || !lines.contains(hint)
                || !lines.getLast().equals(scroll))
            throw new VisualTestException("the door button's tooltip should start with '" + title + "', mark '"
                    + current + "' and end with '" + hint + "' and '" + scroll + "': " + lines);
        LOGGER.info(PREFIX + "terminal: the door button's tooltip ({} lines): {}", lines.size(), lines);
    }

    /**
     * Every line of the door button's tooltip inside the row, and at most
     * {@link WarehouseTerminalScreen#MAX_DOOR_TOOLTIP_LINES} lines.
     */
    private static void checkDoorTooltipFits(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<Component> tooltip = terminal.doorTooltip();
        if (tooltip.isEmpty() || tooltip.size() > WarehouseTerminalScreen.MAX_DOOR_TOOLTIP_LINES)
            throw new VisualTestException("the door button's tooltip has " + tooltip.size() + " lines: "
                    + widths(context, tooltip));
        for (Component line : tooltip) {
            if (context.minecraft().font.width(line) > ROW_WIDTH)
                throw new VisualTestException("a line of the door button's tooltip is wider than the window's row ("
                        + ROW_WIDTH + " px): " + widths(context, tooltip));
        }
        LOGGER.info(PREFIX + "terminal: CHECK door tooltip PASS: {} lines fit {} px in {}: {}", tooltip.size(),
                ROW_WIDTH, context.minecraft().getLanguageManager().getSelected(), widths(context, tooltip));
    }

    private static void hoverDoorButton(VisualContext context) {
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalDoorButton(screen(context)));
    }

    private static void clickDoorButton(VisualContext context) {
        ScreenInput.click(context.minecraft(), ScreenInput.terminalDoorButton(screen(context)));
    }

    private static void clickCraftToggle(VisualContext context) {
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftToggle(screen(context)));
    }

    /**
     * The centre of the door button while a question covers it: the button's own hit test is still the screen's, and
     * the point is the one a player's wheel would be over.
     */
    private static ScreenInput.Point doorButtonCentre(VisualContext context) {
        return ScreenInput.terminalDoorButton(screen(context));
    }

    private static void hoverFirstCell(VisualContext context) {
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalCell(screen(context), 0));
    }

    /** The cell under the mouse carries the door's cues: the gold tint is on, and its tooltip names the door. */
    private static void checkCellCue(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (!terminal.doorCueShown())
            throw new VisualTestException("the stock cells show no door cue while the door is chosen");
        ScreenInput.Point cell = ScreenInput.terminalCell(terminal, 0);
        List<String> tooltip = terminal.tooltipAt((int) cell.x(), (int) cell.y()).stream().map(Component::getString)
                .toList();
        String line = WareworksLang.translateDirect(WareworksLang.TERMINAL_DOOR_CELL, DOOR_ADDRESS).getString();
        if (!tooltip.contains(line))
            throw new VisualTestException("the cell's tooltip does not say '" + line + "': " + tooltip);
        LOGGER.info(PREFIX + "terminal: the hovered cell is tinted gold and its tooltip says '{}'", line);
    }

    /** The question the door click raised names the door and its rack address, in its own gold line. */
    private static void checkQuestionNamesDoor(VisualContext context) {
        TerminalDoorRow row = doorRow(context);
        String line = WareworksLang.translateDirect(WareworksLang.TERMINAL_DOOR_CONFIRM,
                DOOR_ADDRESS + " (" + row.rackAddress() + ")").getString();
        List<String> lines = ScreenInput.terminalConfirmationLines(screen(context)).stream()
                .map(Component::getString).toList();
        if (!lines.contains(line))
            throw new VisualTestException("the question does not say '" + line + "': " + lines);
        LOGGER.info(PREFIX + "terminal: the question says '{}': {}", line, lines);
    }

    /** Server: the open request for the door's last item is the door's, and none is the terminal's. */
    private static void checkRequestForDoor(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        BlockPos door = doorPos(context.origin());
        BlockPos here = layout(context.origin()).rackPos(TERMINAL);
        long forDoor = controller.requestsFor(door).stream().filter(request -> request.key().equals(DOOR_ITEM))
                .count();
        long forHere = controller.requestsFor(here).stream().filter(request -> request.key().equals(DOOR_ITEM))
                .count();
        if (forDoor != 1L || forHere != 0L)
            throw new VisualTestException("the door click should be one request to the door and none to the "
                    + "terminal: " + forDoor + " for the door, " + forHere + " for the terminal");
        // The door's own goggles list the click's request like any of its own: what a player sees looking at the port.
        WarehouseOutputBlockEntity port = doorPort(server.overworld(), context.origin());
        port.onGoggleObserved();
        if (port.summary().openRequests() != 1 || port.summary().requestedItems() != controller.requestedFor(door))
            throw new VisualTestException("the door's goggles should list the one request for "
                    + controller.requestedFor(door) + " items: " + port.summary());
        LOGGER.info(PREFIX + "terminal: PASS the door click is the door's request ({} waiting), and the door's "
                + "goggles list it", controller.requestedFor(door));
    }

    /** Server: the terminal's buffer holds none of what the door clicks asked for. */
    private static void checkNothingHere(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        long reserved = terminal.bufferedItems().count(AT_RESERVE_ITEM);
        long last = terminal.bufferedItems().count(DOOR_ITEM);
        if (reserved != 0L || last != 0L)
            throw new VisualTestException("a door click delivered into the terminal: " + reserved + " "
                    + AT_RESERVE_ITEM + ", " + last + " " + DOOR_ITEM);
        LOGGER.info(PREFIX + "terminal: PASS the door's items are in its port {} and none in the terminal",
                doorPort(server.overworld(), context.origin()).bufferedItems());
    }

    /** Server: the crane has no job and nothing is open, so the next request is the only thing it could serve. */
    private static boolean warehouseIdle(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(server.overworld(),
                context.origin());
        return crane != null && controller(server, context).openRequests().isEmpty() && crane.canAcceptJob();
    }

    /** The aisle's controller, whatever it is doing. */
    private static WarehouseControllerBlockEntity controller(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), context.origin().relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the aisle's controller is missing");
        return controller;
    }

    /** The status row's standing line about the chosen door, built from the door's own row on the screen. */
    private static void checkStandingLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        TerminalDoorRow row = chosenRow(context);
        if (terminal.hasFeedback())
            throw new VisualTestException("an answer still holds the status row: " + feedbackText(terminal));
        String expected = WarehouseTerminalScreen.doorStandingText(Component.literal(DOOR_ADDRESS), row.waiting(),
                row.delivered()).getString();
        String shown = terminal.shownStatusLine().getString();
        if (!shown.equals(expected))
            throw new VisualTestException("the status row reads '" + shown + "', not '" + expected + "'");
        List<String> tooltip = terminal.doorTooltip().stream().map(Component::getString).toList();
        String owed = WareworksLang.translateDirect(WareworksLang.TERMINAL_WAITING, amount(row.waiting()),
                amount(row.delivered())).getString();
        if (!tooltip.contains(owed))
            throw new VisualTestException("the door button's tooltip does not say '" + owed + "': " + tooltip);
        checkStatusFits(context);
        LOGGER.info(PREFIX + "terminal: PASS the status row reads '{}', and the button's tooltip '{}'", shown, owed);
    }

    /** The Packager is gone: the screen delivers here again, says so in the row, and shows no door button. */
    private static void checkDoorGone(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        String expected = WarehouseTerminalScreen.doorUnavailableText().getString();
        if (!feedbackText(terminal).equals(expected))
            throw new VisualTestException("the status row reads '" + feedbackText(terminal) + "', not '" + expected
                    + "'");
        if (!terminal.doors().isEmpty())
            throw new VisualTestException("a port without a Packager is still listed: " + terminal.doors());
        LOGGER.info(PREFIX + "terminal: PASS the door went, the choice is here again, and the row says '{}'",
                expected);
    }

    /** Steps a chain's badge counts at its widest: what this server allows, or the shipped default. */
    private static int planSteps() {
        return WareworksConfig.isServerConfigLoaded() ? WareworksConfig.maxProductionPlanSteps() : DEFAULT_PLAN_STEPS;
    }

    /** An amount as every one of these rows formats it, in the language that is loaded. */
    private static String amount(long value) {
        return LangNumberFormat.format(value);
    }

    /** The names of the first few rows, for the log line of a shot. */
    private static List<String> firstNames(List<StockLine<ItemKey>> matching) {
        List<String> names = new ArrayList<>(NAMES_LOGGED);
        for (StockLine<ItemKey> line : matching.subList(0, Math.min(NAMES_LOGGED, matching.size())))
            names.add(line.name());
        return names;
    }

    /** Every tooltip line with the width it is drawn at, for the message of a failed width check. */
    private static List<String> widths(VisualContext context, List<Component> tooltip) {
        List<String> measured = new ArrayList<>(tooltip.size());
        for (Component line : tooltip)
            measured.add("'" + line.getString() + "' " + context.minecraft().font.width(line) + " px");
        return measured;
    }

    private static String text(String relativeKey) {
        return WareworksLang.translateDirect(relativeKey).getString();
    }
}
