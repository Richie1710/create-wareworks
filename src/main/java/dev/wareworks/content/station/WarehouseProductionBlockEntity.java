package dev.wareworks.content.station;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ExtractOnlyItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.LocalSelectorLink;
import dev.wareworks.content.selector.RecipeSlip;
import dev.wareworks.content.selector.SelectorMachine;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.content.selector.SelectorTexts;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.production.CrafterGrid;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of the warehouse production station ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * It is a {@link WarehouseDeliveryStationBlockEntity} — a station the crane <b>delivers into</b> — but it reports
 * {@link LocationKind#PRODUCTION} rather than {@code OUTPUT}. That distinction is the whole point: a production station
 * is never the destination of a retrieval request, and retrieve leftovers are never dumped into one, both of which
 * reusing {@code OUTPUT} would have allowed (ADR-024). What it shares with the output is everything physical: the
 * extract-only buffer a funnel, chute, belt or Create mechanical arm ({@link DeliveryStationArmPoint}; M12) pulls from,
 * and the crane's {@code insert}.
 * <p>
 * <b>The patterns live here</b> ({@link ProductionPatterns}), which answers "which machine gets these ingredients"
 * without any extra configuration: they get them here, and the player's machinery is whatever they hooked up to this
 * block. The controller reads them to decide what its aisle can produce; nothing in Wareworks checks that a pattern
 * matches a real recipe — <b>except</b> while a Warehouse Crafter Feeder is attached to this station's back
 * ({@link #attachedFeeder()}, issue #34, ADR-067): the feeder lays the patterns into a vanilla Crafter, which crafts
 * only real crafting recipes, so then every pattern is checked against one ({@link #crafterCheck}) and only the ones
 * that pass reach the planner ({@link #activePatterns()}).
 * <p>
 * <b>No ticker, no redstone.</b> Patterns are edited in the screen ({@link #openScreen}), and the station only reacts
 * to insertions, extractions, goggle observation and lifecycle events like every other station.
 */
public class WarehouseProductionBlockEntity extends WarehouseDeliveryStationBlockEntity implements IInteractionChecker {
    /** NBT key of the goggle data that is specific to this station (client packets only). */
    public static final String PRODUCTION_SUMMARY_TAG = "ProductionSummary";
    /** NBT key of {@link #feederLaid}. */
    private static final String FEEDER_LAID_TAG = "FeederLaid";
    private static final String FEEDER_LAID_ORDER = "Order";
    private static final String FEEDER_LAID_RUNS = "Runs";
    /** Most orders whose laid runs are read back from a save, which is untrusted. */
    private static final int MAX_FEEDER_LAID = 256;
    /** Most runs one order can have laid: as many as a loaded feed may claim. */
    private static final int MAX_FEEDER_RUNS = WarehouseCrafterFeederBlockEntity.MAX_RUNS;
    /**
     * NBT key of the Recipe Selector state (ADR-085, design §3.1): written by every full save, read only from one, so a
     * schematic print never carries a selector link, a selection or a hold.
     */
    public static final String RECIPE_SELECTOR_TAG = "RecipeSelector";
    private static final String SELECTOR_MODE = "Mode";
    private static final String SELECTOR_LINK = "Selector";
    private static final String SELECTOR_KIND = "MachineKind";
    private static final String SELECTOR_RETURN = "ReturnInput";
    private static final String SELECTOR_SELECTION = "Selection";
    private static final String SELECTOR_SWITCH_TIME = "SwitchTime";
    private static final String SELECTOR_EPOCH = "Epoch";
    private static final String SELECTOR_HOLD = "Hold";
    private static final String SELECTOR_PROBE = "Probe";

    private final ProductionPatterns patterns;
    /** Derived goggle state, never saved; synced with the shared station summary. */
    private ProductionGoggleSummary productionSummary = ProductionGoggleSummary.NONE;
    /**
     * Bumped by every pattern change ({@link #setPatternEntry}, {@link #clearPattern}, a full load), so the crafter
     * recipe checks below know when their answers are stale. Never saved.
     */
    private int patternsRevision;
    /**
     * Cached Recipe Selector pattern checks per slot (ADR-085, design §10; null = not computed yet), valid for
     * {@link #selectorChecksRevision}, the machine kind {@link #selectorChecksKind}, the recipe manager
     * {@link #selectorChecksRecipes} and Create's recipe switches {@link #selectorChecksOptions} (review fix, Create API
     * 3: Create's server config can be changed while the game runs). Never saved.
     */
    private SelectorRecipeCheck.Verdict[] selectorChecks = new SelectorRecipeCheck.Verdict[0];
    private int selectorChecksRevision = -1;
    @Nullable
    private SelectorMachine.Kind selectorChecksKind;
    private WeakReference<Object> selectorChecksRecipes = new WeakReference<>(null);
    @Nullable
    private SelectorRecipeCheck.Options selectorChecksOptions;
    /** Cached crafter recipe checks per pattern slot (null = not computed yet), valid for {@link #checksRevision}. */
    private CrafterRecipeCheck.Result[] checks = new CrafterRecipeCheck.Result[0];
    private int checksRevision = -1;
    /**
     * The recipe manager the cached checks were computed against. A {@code /reload} replaces it, which is how the cache
     * notices new or removed recipes without listening for anything — the approach of vanilla's own {@code RecipeCache}.
     */
    private WeakReference<Object> checksRecipes = new WeakReference<>(null);
    /**
     * Whether the Crafter takes a key for a filter group of a slot ({@link #crafterTakes}, ADR-082), valid with
     * {@link #checks}: cleared whenever they are.
     */
    private final Map<SlotChoiceKey, Boolean> crafterTakes = new HashMap<>();
    /**
     * Runs of each open order the attached Crafter Feeder has laid into its Crafter (issue #34, ADR-067). Kept here and
     * saved with the station rather than with the feeder, so a feeder that is broken and placed again, or whose save
     * could not be read, knows how much of an order is already in the Crafter or crafted and never lays it twice.
     * Pruned by the feeder to orders that are still open.
     */
    private final Map<UUID, Integer> feederLaid = new LinkedHashMap<>();

    /**
     * The Recipe Selector state (ADR-085, design §3.1). Saved in a full save only; {@link #selectorArmed},
     * {@link #armFault} and {@link #slipsTrusted} are never saved, so a load verifies the line again before it fetches.
     */
    private SelectorMode selectorMode = SelectorMode.NONE;
    private Optional<BlockPos> linkedSelector = Optional.empty();
    private Optional<SelectorMachine.Kind> machineKind = Optional.empty();
    private Optional<BlockPos> returnInput = Optional.empty();
    private Optional<ItemKey> selection = Optional.empty();
    private long switchTime;
    private int selectorEpoch;
    private Optional<SelectorHold> selectorHold = Optional.empty();
    private Optional<UUID> probeOrder = Optional.empty();
    private boolean selectorArmed;
    private Optional<SelectorWait> armFault = Optional.empty();
    private boolean armFaultOnSwitch;
    private boolean slipsTrusted;
    /** GameTests only ({@link #installTestSelectorArm}); never saved. */
    @Nullable
    private SelectorArm testSelectorArm;

    public WarehouseProductionBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, WareworksConfig.productionBufferSlots());
        patterns = new ProductionPatterns(WareworksConfig.maxProductionPatterns());
    }

    /** Registers the extract-only view for every side (listed in {@code WareworksCapabilities}). */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.get(),
                (be, side) -> be.externalHandler());
    }

    /** No behaviours: the station is operated through its screen, and like every station it has no ticker. */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    /** A production station, not an output: it is no request destination and takes no retrieve leftovers (ADR-024). */
    @Override
    public LocationKind locationKind() {
        return LocationKind.PRODUCTION;
    }

    // --- patterns ----------------------------------------------------------------------------------------------------

    /** The station's pattern slots (server). */
    public ProductionPatterns patterns() {
        return patterns;
    }

    /**
     * The patterns the warehouse may plan with, in slot order: every complete pattern — or, while a Warehouse Crafter
     * Feeder is attached ({@link #attachedFeeder()}, issue #34, ADR-067 D7), only those whose crafting recipe check
     * passed. This one filter is what keeps the controller's planner, the restocking rules and the terminal from ever
     * fetching ingredients for a pattern the Crafter would not craft: a pattern that is not offered is never ordered.
     */
    public List<ProductionPattern<ItemKey>> activePatterns() {
        List<SlotPattern> slots = activeSlots();
        List<ProductionPattern<ItemKey>> usable = new ArrayList<>(slots.size());
        for (SlotPattern slot : slots)
            usable.add(slot.pattern());
        return List.copyOf(usable);
    }

    /**
     * One pattern the warehouse may plan with, and the slot it is in (ADR-082): the slot is what narrows a filter's
     * candidates to what the Crafter at this station takes ({@link #crafterTakes}).
     */
    public record SlotPattern(int slot, ProductionPattern<ItemKey> pattern) {
    }

    /**
     * {@link #activePatterns()} with each pattern's slot, in slot order. At a Recipe Selector station whose machine
     * kind is known, a pattern the machine would not make exactly is dropped as well ({@link #selectorCheck}, ADR-085
     * design §10, the feeder's precedent).
     */
    public List<SlotPattern> activeSlots() {
        boolean server = level != null && !level.isClientSide;
        boolean feeder = server && attachedFeeder().isPresent();
        boolean selector = server && selectorMode.selects() && machineKind.isPresent();
        List<SlotPattern> usable = new ArrayList<>(patterns.size());
        for (int slot = 0; slot < patterns.size(); slot++) {
            Optional<ProductionPattern<ItemKey>> pattern = patterns.patternAt(slot);
            if (pattern.isEmpty() || (feeder && checkOf(slot).check() != CrafterPatternCheck.VALID))
                continue;
            if (selector && selectorCheckOf(slot) != SelectorRecipeCheck.Verdict.VALID)
                continue;
            usable.add(new SlotPattern(slot, pattern.get()));
        }
        return List.copyOf(usable);
    }

    /**
     * Server: the Recipe Selector pattern check of slot {@code slot} (ADR-085, design §10), or empty while the station
     * is in no selector mode, its machine kind is unknown or the slot holds no complete pattern. Cached until the
     * patterns, the machine kind, the recipes or Create's recipe switches change.
     */
    public Optional<SelectorRecipeCheck.Verdict> selectorCheck(int slot) {
        if (level == null || level.isClientSide || !selectorMode.selects() || machineKind.isEmpty() || slot < 0
                || slot >= patterns.size() || patterns.patternAt(slot).isEmpty())
            return Optional.empty();
        return Optional.of(selectorCheckOf(slot));
    }

    /** The cached selector check of {@code slot}; the caller has checked mode, kind and pattern. */
    private SelectorRecipeCheck.Verdict selectorCheckOf(int slot) {
        Object recipes = SelectorRecipeCheck.recipeSet(level);
        SelectorMachine.Kind kind = machineKind.orElseThrow();
        SelectorRecipeCheck.Options options = SelectorRecipeCheck.Options.fromServer();
        if (selectorChecksRevision != patternsRevision || selectorChecks.length != patterns.size()
                || selectorChecksKind != kind || selectorChecksRecipes.get() != recipes
                || !options.equals(selectorChecksOptions)) {
            selectorChecks = new SelectorRecipeCheck.Verdict[patterns.size()];
            selectorChecksRevision = patternsRevision;
            selectorChecksKind = kind;
            selectorChecksRecipes = new WeakReference<>(recipes);
            selectorChecksOptions = options;
        }
        SelectorRecipeCheck.Verdict cached = selectorChecks[slot];
        if (cached == null) {
            Optional<ProductionPattern<ItemKey>> pattern = patterns.patternAt(slot);
            cached = pattern.isEmpty() ? SelectorRecipeCheck.Verdict.NO_RECIPE
                    : SelectorRecipeCheck.check(level, kind, pattern.get(), options);
            selectorChecks[slot] = cached;
        }
        return cached;
    }

    /**
     * Server: whether the Crafter at this station takes {@code key} for the filter cells of {@code choice} in slot
     * {@code slot} (ADR-082): always without a feeder; with one, the slot's checked reference grid with every cell of
     * that filter set to {@code key} must make exactly the pattern's result. Cached until the patterns or the recipes
     * change, so a planning pass asks the recipe book once per slot, filter and key.
     */
    public boolean crafterTakes(int slot, ItemKey choice, ItemKey key) {
        if (level == null || level.isClientSide || attachedFeeder().isEmpty())
            return true;
        CrafterRecipeCheck.Result check = checkOf(slot);
        if (check.check() != CrafterPatternCheck.VALID || check.reference().isEmpty())
            return false;
        SlotChoiceKey cacheKey = new SlotChoiceKey(slot, choice, key);
        Boolean cached = crafterTakes.get(cacheKey);
        if (cached != null)
            return cached;
        Optional<ItemKey> result = patterns.keyAt(slot, ProductionPatterns.RESULT_ENTRY);
        CrafterGrid<ItemKey> layout = CrafterRecipeCheck.layoutOf(patterns, slot);
        CrafterGrid<ItemKey> reference = check.reference().get();
        List<ItemKey> cells = new ArrayList<>(CrafterGrid.SLOTS);
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++)
            cells.add(layout.at(cell).filter(choice::equals).isPresent() ? key : reference.at(cell).orElse(null));
        boolean takes = result.isPresent() && CrafterRecipeCheck.makesExactly(level, CrafterGrid.ofNullable(cells),
                result.get(), patterns.countAt(slot, ProductionPatterns.RESULT_ENTRY));
        crafterTakes.put(cacheKey, takes);
        return takes;
    }

    /** The cache key of {@link #crafterTakes}. */
    private record SlotChoiceKey(int slot, ItemKey choice, ItemKey key) {
    }

    // --- the crafter feeder (issue #34, ADR-067) ---------------------------------------------------------------------

    /**
     * Server: the Warehouse Crafter Feeder attached to this station's back, if there is one: the block behind the station
     * is a feeder that does not face the station, and that feeder resolves its station to this one
     * ({@link CrafterFeederAttachment}) — a feeder between the backs of two stations serves neither. A handful of block
     * state reads and one block entity lookup, no search.
     */
    public Optional<WarehouseCrafterFeederBlockEntity> attachedFeeder() {
        if (level == null || level.isClientSide || isRemoved())
            return Optional.empty();
        BlockPos behind = worldPosition.relative(facing().getOpposite());
        if (!level.isLoaded(behind))
            return Optional.empty();
        BlockState state = level.getBlockState(behind);
        if (!(state.getBlock() instanceof WarehouseCrafterFeederBlock))
            return Optional.empty();
        CrafterFeederAttachment.Result attachment = CrafterFeederAttachment.resolve(level, behind,
                state.getValue(WarehouseCrafterFeederBlock.FACING));
        if (attachment.kind() != CrafterFeederAttachment.Kind.ONE
                || !attachment.station().map(worldPosition::equals).orElse(false))
            return Optional.empty();
        return level.getBlockEntity(behind) instanceof WarehouseCrafterFeederBlockEntity feeder && !feeder.isRemoved()
                ? Optional.of(feeder) : Optional.empty();
    }

    /**
     * Server: the crafting recipe check of pattern slot {@code slot} ({@link CrafterRecipeCheck}) -
     * {@link CrafterPatternCheck#NOT_CHECKED} while no feeder is attached, because then nothing is checked at all.
     * Cached until the patterns or the recipes change.
     */
    public CrafterRecipeCheck.Result crafterCheck(int slot) {
        if (level == null || level.isClientSide || attachedFeeder().isEmpty())
            return CrafterRecipeCheck.Result.NOT_CHECKED;
        return checkOf(slot);
    }

    /** Server: {@link #crafterCheck} for every pattern slot, in slot order (the station screen). */
    public List<CrafterRecipeCheck.Result> crafterChecks() {
        boolean attached = level != null && !level.isClientSide && attachedFeeder().isPresent();
        List<CrafterRecipeCheck.Result> all = new ArrayList<>(patterns.size());
        for (int slot = 0; slot < patterns.size(); slot++)
            all.add(attached ? checkOf(slot) : CrafterRecipeCheck.Result.NOT_CHECKED);
        return List.copyOf(all);
    }

    /** The cached check of {@code slot}, computed on first use after a pattern or recipe change. */
    private CrafterRecipeCheck.Result checkOf(int slot) {
        if (slot < 0 || slot >= patterns.size() || level == null)
            return CrafterRecipeCheck.Result.INCOMPLETE;
        Object recipes = CrafterRecipeCheck.recipeSet(level);
        if (checksRevision != patternsRevision || checks.length != patterns.size() || checksRecipes.get() != recipes) {
            checks = new CrafterRecipeCheck.Result[patterns.size()];
            checksRevision = patternsRevision;
            checksRecipes = new WeakReference<>(recipes);
            crafterTakes.clear();
        }
        CrafterRecipeCheck.Result cached = checks[slot];
        if (cached == null) {
            cached = CrafterRecipeCheck.check(level, patterns, slot);
            checks[slot] = cached;
        }
        return cached;
    }

    /** Items of {@code key} in the buffer: what the feeder may still take of it. */
    long bufferCount(ItemKey key) {
        return buffer.countOf(key);
    }

    /**
     * Feeder: takes up to one stack of {@code key} out of the buffer ({@link StationBuffer#extract}). The items go
     * straight into a Crafter slot in the same tick; nothing in between holds them (ADR-067 D3).
     */
    ItemStack extractForFeeder(ItemKey key, int amount, boolean simulate) {
        return buffer.extract(key, amount, simulate);
    }

    /** The aisle's controller, for the feeder attached to this station. */
    Optional<WarehouseControllerBlockEntity> controllerForFeeder() {
        return controller();
    }

    /** Server: runs of order {@code order} a feeder has laid into its Crafter so far; 0 for one it never touched. */
    public int feederLaidRuns(UUID order) {
        return feederLaid.getOrDefault(order, 0);
    }

    /** A feeder laid {@code runs} more runs of order {@code order}. */
    void addFeederLaidRuns(UUID order, int runs) {
        if (runs <= 0)
            return;
        feederLaid.merge(order, runs, (before, added) -> Math.min(MAX_FEEDER_RUNS, before + added));
        setChanged();
    }

    /** Forgets the laid runs of every order {@code open} does not accept any more. */
    void pruneFeederLaid(Predicate<UUID> open) {
        boolean changed = false;
        for (Iterator<UUID> it = feederLaid.keySet().iterator(); it.hasNext();) {
            if (!open.test(it.next())) {
                it.remove();
                changed = true;
            }
        }
        if (changed)
            setChanged();
    }

    private void patternsChanged() {
        patternsRevision++;
        Arrays.fill(checks, null);
        crafterTakes.clear();
    }

    /**
     * What a player's edit of one pattern entry came to (ADR-082): written, nothing to change, or refused with the
     * reason the hotbar line names.
     */
    public enum EditOutcome {
        /** The entry changed. */
        CHANGED,
        /** Nothing changed: the same entry, an out-of-range index, or a refused item. */
        UNCHANGED,
        /** The filter encodes into more than {@link PatternFilterCells#MAX_FILTER_BYTES} (D16). */
        FILTER_TOO_LARGE,
        /** A filter cell would let the slot's result through, in either direction of the edit (D12). */
        FILTER_LETS_RESULT;

        /** The hotbar line of a refusal, or {@code null} for an outcome that is none. */
        public String langKey() {
            return switch (this) {
                case FILTER_TOO_LARGE -> WareworksLang.PRODUCTION_FILTER_TOO_LARGE;
                case FILTER_LETS_RESULT -> WareworksLang.PRODUCTION_FILTER_MAKES_RESULT;
                default -> null;
            };
        }
    }

    /**
     * Server: sets or clears one pattern entry ({@link ProductionPatterns#setEntry}) and saves the change. Editing a
     * pattern never consumes an item — the entries are ghost items.
     *
     * @return whether the entry changed
     */
    public boolean setPatternEntry(int pattern, int entry, ItemKey key, int count) {
        if (level == null || level.isClientSide || isRemoved())
            return false;
        if (!patterns.setEntry(pattern, entry, key, count))
            return false;
        patternsChanged();
        setChanged();
        return true;
    }

    /**
     * Server: a player's edit of one pattern entry (ADR-076). It writes the entry like {@link #setPatternEntry}, and
     * while the result is empty or is what the crafting grid made before the edit, the result <b>follows the grid</b>
     * when a cell's item changes: it becomes what the grid makes now, or empty when that is no crafting recipe. A plank
     * alone makes a button, and a player on the way to a chest must not end up with a chest grid that says "button". A
     * result the grid does not make was chosen by the player, for another machine, and is left alone, and so is every
     * result while only a cell's amount changes. A cell holding more than one item is no crafting grid. A followed result
     * that a filter cell of the slot lets through is refused like a chosen one (D12, ADR-082): the slot stays as it was.
     */
    public EditOutcome editPatternEntry(int pattern, int entry, ItemKey key, int count) {
        if (level == null || level.isClientSide || isRemoved())
            return EditOutcome.UNCHANGED;
        EditOutcome refused = refusedFilterEdit(pattern, entry, key);
        if (refused != null)
            return refused;
        Optional<ItemKey> result = patterns.keyAt(pattern, ProductionPatterns.RESULT_ENTRY);
        boolean follows = pattern >= 0 && pattern < patterns.size() && entry >= 0
                && entry < ProductionPatterns.GRID_CELLS
                && !patterns.keyAt(pattern, entry).equals(Optional.ofNullable(key))
                && (result.isEmpty() || isWhatTheGridMakes(pattern, result.get()));
        if (!follows)
            return setPatternEntry(pattern, entry, key, count) ? EditOutcome.CHANGED : EditOutcome.UNCHANGED;
        Optional<ItemKey> oldCell = patterns.keyAt(pattern, entry);
        int oldCellCount = patterns.countAt(pattern, entry);
        boolean oldCellFilter = patterns.isFilterAt(pattern, entry);
        int resultCount = patterns.countAt(pattern, ProductionPatterns.RESULT_ENTRY);
        if (result.isPresent())
            patterns.setEntry(pattern, ProductionPatterns.RESULT_ENTRY, null, ProductionEntry.MIN_COUNT);
        patterns.setEntry(pattern, entry, key, count);
        Optional<ItemStack> made = recipeOfGrid(pattern);
        if (made.isPresent() && filterCellLets(pattern, ItemKey.of(made.get()))) {
            // The followed result would be let through by a filter cell of the slot (D12): the slot goes back as it was.
            patterns.setEntry(pattern, entry, oldCell.orElse(null), oldCellCount, oldCellFilter);
            result.ifPresent(old -> patterns.setEntry(pattern, ProductionPatterns.RESULT_ENTRY, old, resultCount));
            return EditOutcome.FILTER_LETS_RESULT;
        }
        made.ifPresent(stack -> patterns.setEntry(pattern, ProductionPatterns.RESULT_ENTRY, ItemKey.of(stack),
                stack.getCount()));
        patternsChanged();
        setChanged();
        return EditOutcome.CHANGED;
    }

    /**
     * The refusal of a filter edit (ADR-082), or {@code null}: a filter into a grid cell must fit (D16) and must not let
     * the slot's result through, and a result must not be let through by any filter cell of the slot (D12). The core
     * leaves the result out of a filter's candidates anyway; refusing here keeps a player from writing a pattern that
     * reads as making its own ingredient.
     */
    private EditOutcome refusedFilterEdit(int pattern, int entry, ItemKey key) {
        if (key == null || pattern < 0 || pattern >= patterns.size())
            return null;
        if (entry >= 0 && entry < ProductionPatterns.GRID_CELLS && PatternFilterCells.isFilter(key)) {
            if (!PatternFilterCells.fits(key, level.registryAccess()))
                return EditOutcome.FILTER_TOO_LARGE;
            Optional<ItemKey> result = patterns.keyAt(pattern, ProductionPatterns.RESULT_ENTRY);
            if (result.isPresent()
                    && PatternFilterCells.test(PatternFilterCells.resolve(key), level, result.get()))
                return EditOutcome.FILTER_LETS_RESULT;
            return null;
        }
        if (entry == ProductionPatterns.RESULT_ENTRY && filterCellLets(pattern, key))
            return EditOutcome.FILTER_LETS_RESULT;
        return null;
    }

    /** Whether a filter cell of pattern slot {@code pattern} lets {@code result} through (D12). */
    private boolean filterCellLets(int pattern, ItemKey result) {
        for (int cell = 0; cell < ProductionPatterns.GRID_CELLS; cell++) {
            if (patterns.isFilterAt(pattern, cell) && PatternFilterCells.test(
                    PatternFilterCells.resolve(patterns.keyAt(pattern, cell).orElseThrow()), level, result))
                return true;
        }
        return false;
    }

    private boolean isWhatTheGridMakes(int pattern, ItemKey result) {
        return recipeOfGrid(pattern).filter(result::matches)
                .filter(made -> made.getCount() == patterns.countAt(pattern, ProductionPatterns.RESULT_ENTRY))
                .isPresent();
    }

    /**
     * What pattern slot {@code pattern}'s grid makes in a Crafter; nothing if a cell holds more than one item. A filter
     * group is read as the first item its filter names (D13), and a group that names none makes nothing, so the result
     * does not follow.
     */
    private Optional<ItemStack> recipeOfGrid(int pattern) {
        Optional<CrafterGrid<ItemKey>> grid = CrafterRecipeCheck.referenceLayout(patterns, pattern, level);
        if (grid.isEmpty())
            return Optional.empty();
        int[] counts = new int[CrafterGrid.SLOTS];
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++)
            counts[cell] = patterns.countAt(pattern, cell);
        return grid.get().allCellsSingle(counts) ? CrafterRecipeCheck.makes(level, grid.get()) : Optional.empty();
    }

    /**
     * Server: overwrites one pattern slot with a recipe a player laid from a recipe viewer (ADR-076). The caller has
     * checked that {@code result} is none of {@code cells}. Every cell is exact.
     */
    public void writePattern(int pattern, CrafterGrid<ItemKey> cells, List<Integer> counts, ItemKey result,
            int resultCount) {
        writePattern(pattern, cells, new boolean[CrafterGrid.SLOTS], counts, result, resultCount);
    }

    /**
     * {@link #writePattern(int, CrafterGrid, List, ItemKey, int)} with the cells that are filter cells (ADR-082), e.g.
     * the "is tagged" filters a recipe viewer's tag ingredients became. A flagged cell whose key is no configured filter
     * is written exact.
     */
    public void writePattern(int pattern, CrafterGrid<ItemKey> cells, boolean[] filterCells, List<Integer> counts,
            ItemKey result, int resultCount) {
        if (level == null || level.isClientSide || isRemoved())
            return;
        patterns.clearPattern(pattern);
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            int index = cell;
            boolean filter = index < filterCells.length && filterCells[index];
            cells.at(cell).ifPresent(key -> patterns.setEntry(pattern, index, key, counts.get(index), filter));
        }
        patterns.setEntry(pattern, ProductionPatterns.RESULT_ENTRY, result, resultCount);
        patternsChanged();
        setChanged();
    }

    /** Server: clears one pattern slot completely. */
    public boolean clearPattern(int pattern) {
        if (level == null || level.isClientSide || isRemoved())
            return false;
        if (!patterns.clearPattern(pattern))
            return false;
        patternsChanged();
        setChanged();
        return true;
    }

    /**
     * Whether the buffer still holds any of {@code keys}. The controller asks this about an order's ingredients to see
     * whether the player's machine has taken them yet ({@code ProductionOrder#withIngredientsTaken}): while they are
     * still lying here, nothing has been produced.
     */
    public boolean holdsAnyOf(Collection<ItemKey> keys) {
        Objects.requireNonNull(keys, "keys");
        for (ItemKey key : keys) {
            if (buffer.countOf(key) > 0)
                return true;
        }
        return false;
    }

    // --- the safety stop ---------------------------------------------------------------------------------------------

    /**
     * Server: which of the products this station's own patterns make the <b>safety stop</b> is holding, with the cost of
     * each (M20, issue #4, ADR-032). Empty for a station whose machines work, which is every station of a warehouse
     * that has never lost a batch.
     * <p>
     * <b>This is the one answer four surfaces are built from</b> — the screen's stopped row, the goggle lines, the block
     * state and the chat line a resume writes — so a player cannot be told one thing by the block and another by its
     * screen. The pause itself stays in the controller, keyed by item: this only turns it around into the question a
     * player standing in front of a machine actually asks.
     * <p>
     * Cost: one map lookup per pattern of this station, no world search and no inventory read. Two patterns that make the
     * same item report it once, in pattern order.
     */
    public List<StoppedProduct> stoppedProducts() {
        if (level == null || level.isClientSide || isRemoved())
            return List.of();
        return stoppedProducts(controller().orElse(null));
    }

    /**
     * {@link #stoppedProducts()} against a controller the caller has already resolved, so the controller's own pass over
     * its production stations does not look each one up again.
     *
     * @param controller the aisle's controller, or {@code null} when this station belongs to no loaded warehouse — which
     *                   has no pauses to report
     */
    List<StoppedProduct> stoppedProducts(@Nullable WarehouseControllerBlockEntity controller) {
        if (controller == null)
            return List.of();
        List<StoppedProduct> stopped = new ArrayList<>(1);
        for (ProductionPattern<ItemKey> pattern : patterns.patterns()) {
            ItemKey result = pattern.result().key();
            if (stopped.stream().anyMatch(entry -> entry.key().equals(result)))
                continue; // two patterns may make the same item; it is stopped once
            controller.stockRulePause(result).ifPresent(pause -> stopped.add(StoppedProduct.of(result, pause)));
        }
        return List.copyOf(stopped);
    }

    /**
     * Server: a player has looked at the machine behind this station and says it is worth another batch — the way back
     * from the <b>safety stop</b> for the items this station's own patterns make (ADR-027, widened by ADR-032, M20).
     * <p>
     * <b>Why here.</b> An order that ended with ingredients already in a machine and nothing coming back stops the
     * warehouse from making that item until a player resumes it, and since M20 that covers every kind of order — a click,
     * a redstone pulse and a rule's own refill alike. The stock keeper's own resume only reaches an item one of its rules
     * <i>governs</i>, and the item a chain loses a batch of is normally an intermediate that no rule governs at all, so
     * without this the stop had no way back on an aisle without the right keeper row. The station is the right block for
     * it: it is the one standing in front of the machine that swallowed the batch.
     * <p>
     * It stays a <b>deliberate</b> action and never a timer: the warehouse cannot tell a fixed machine from a broken one,
     * so only a player may say that another batch is worth trying. It resumes only what <i>this station's</i> patterns
     * make, so a click here says nothing about anybody else's machine.
     * <p>
     * The <b>cost</b> of each stop is read before it is lifted and handed back with it, because a pause that is gone can
     * no longer say what it cost — and what it cost is the one thing a player has to be told
     * ({@link #tellResumed(Player, List)}).
     *
     * @return what the warehouse makes again and what each of those stops cost, in pattern order; empty when none of
     *         this station's products was stopped
     */
    public List<StoppedProduct> resumeStoppedProducts() {
        if (level == null || level.isClientSide || isRemoved())
            return List.of();
        WarehouseControllerBlockEntity controller = controller().orElse(null);
        if (controller == null)
            return List.of();
        List<StoppedProduct> resumed = new ArrayList<>(1);
        for (StoppedProduct stopped : stoppedProducts(controller)) {
            if (controller.resumeStockRule(stopped.key()))
                resumed.add(stopped);
        }
        return List.copyOf(resumed);
    }

    /**
     * Server: the same resume gesture also releases a Recipe Selector hold (ADR-085, design §8), and tells
     * {@code player} what came of it: released (the line goes on with its selection), or refused because the station's
     * buffer or its return input still holds something.
     *
     * @return whether the station had a hold, i.e. whether {@code player} was told something here
     */
    public boolean releaseSelectorHold(Player player) {
        Objects.requireNonNull(player, "player");
        if (selectorHold.isEmpty() || level == null || level.isClientSide || isRemoved())
            return false;
        Optional<WarehouseControllerBlockEntity> controller = controller();
        if (controller.isEmpty())
            return false;
        WarehouseControllerBlockEntity.SelectorRelease release = controller.get().releaseSelectorHold(this);
        Component item = release.item().map(key -> key.toStack().getHoverName())
                .or(() -> release.at().filter(level::isLoaded).map(at -> level.getBlockState(at).getBlock().getName()))
                .orElse(Component.empty());
        String message = switch (release.outcome()) {
            case RELEASED -> WareworksLang.SELECTOR_HOLD_RELEASED;
            case REFUSED_BUFFER -> WareworksLang.SELECTOR_HOLD_REFUSED_BUFFER;
            case REFUSED_RETURN -> WareworksLang.SELECTOR_HOLD_REFUSED_RETURN;
            case REFUSED_PACKAGER -> WareworksLang.SELECTOR_HOLD_REFUSED_PACKAGER;
            case REFUSED_PORT -> WareworksLang.SELECTOR_HOLD_REFUSED_PORT;
            case NO_HOLD -> null;
        };
        if (message == null)
            return false;
        player.displayClientMessage(WareworksLang.translateDirect(message, item), false);
        return true;
    }

    /**
     * Tells {@code player} what a resume did: one line per item, naming the item and the ingredient items that stayed in
     * the machine — or, for a station nothing of whose products was stopped, that there was nothing here to lift.
     * <p>
     * It lives here because <b>two</b> ways in lead to it, a sneak-click on the block and the stopped row of the screen
     * ({@code ProductionMenu#submitResume}), and a player who used one of them must be told exactly what the other one
     * would have told them. A click that does nothing silently is a click nobody finds.
     */
    public static void tellResumed(Player player, List<StoppedProduct> resumed) {
        Objects.requireNonNull(player, "player");
        if (resumed == null || resumed.isEmpty()) {
            player.displayClientMessage(WareworksLang.translateDirect(WareworksLang.PRODUCTION_NOTHING_STOPPED), false);
            return;
        }
        for (StoppedProduct stopped : resumed) {
            Component name = stopped.key().toStack().getHoverName();
            player.displayClientMessage(stopped.unrecovered() > 0L
                    ? WareworksLang.translateDirect(WareworksLang.PRODUCTION_RESUMED_LOST, name,
                            Component.literal(Long.toString(stopped.unrecovered())))
                    : WareworksLang.translateDirect(WareworksLang.PRODUCTION_RESUMED, name), false);
        }
    }

    /**
     * Server: lets this station's block show whether the safety stop is holding anything it makes
     * ({@link WarehouseProductionBlock#STOPPED}), so a player walking past an aisle sees <b>which</b> machine stopped
     * without opening anything.
     * <p>
     * Written by the controller's rule pass, which is the same beat and the same rule the stock keeper's lamp follows
     * (M15): only on a real change, and with {@code UPDATE_CLIENTS} alone, because this is something a player reads and
     * not something a neighbour reacts to. A station whose block was replaced or whose chunk is gone writes nothing.
     *
     * @return whether this station now shows the stop, so the controller knows whether any station still has to be
     *         cleared once the last pause is lifted
     */
    public boolean refreshStoppedState(WarehouseControllerBlockEntity controller) {
        if (level == null || level.isClientSide || isRemoved())
            return false;
        return writeStoppedState(!stoppedProducts(controller).isEmpty());
    }

    /**
     * Server: this station is not part of a warehouse any more — its controller was broken, its aisle was lost, or it
     * was turned away from the aisle — so it stops claiming that anything it makes is held: a lamp lives in a block
     * state and survives every save, and one that outlives the warehouse that lit it is a red light nothing can ever
     * put out (the counterpart M15 had to add for the stock keeper's comparator). Called by the controller only on a
     * <b>real</b> loss; a chunk unload leaves everything as it is (ADR-013).
     */
    public void clearStoppedState() {
        if (level == null || level.isClientSide || isRemoved())
            return;
        writeStoppedState(false);
    }

    /** Writes {@link WarehouseProductionBlock#STOPPED}, only on a real change; returns what it now says. */
    private boolean writeStoppedState(boolean stopped) {
        BlockState state = getBlockState();
        if (!state.hasProperty(WarehouseProductionBlock.STOPPED))
            return false;
        if (state.getValue(WarehouseProductionBlock.STOPPED) != stopped)
            // UPDATE_CLIENTS and nothing else: the lamp is what a player sees, not something neighbours react to.
            level.setBlock(worldPosition, state.setValue(WarehouseProductionBlock.STOPPED, stopped),
                    Block.UPDATE_CLIENTS);
        return stopped;
    }

    // --- Recipe Selector (ADR-085) ---------------------------------------------------------------------------------

    /**
     * What a station in local selector mode uses instead of a real Recipe Selector in a GameTest
     * ({@link #installTestSelectorArm}): sets the machine's filter, answering like {@link LocalSelectorLink#armFor}.
     */
    @FunctionalInterface
    public interface SelectorArm {
        Optional<SelectorWait> arm(ItemKey target, boolean switching);
    }

    /** What a request to change the station's selector mode came to (design §3.2, §3.3). */
    public enum SelectorModeChange {
        /** The mode was set. */
        ACCEPTED,
        /** The station was already in this mode; nothing changed. */
        UNCHANGED,
        /** A Crafter Feeder is attached: a selector station cannot feed a Crafter. */
        REFUSED_FEEDER,
        /** The station is in the other selector mode. */
        REFUSED_OTHER_MODE,
        /** The station has open orders. */
        REFUSED_OPEN_ORDERS,
        /** The station already has a loaded Recipe Selector that links back to it (design §3.3). */
        REFUSED_SELECTOR_PRESENT
    }

    /** What switching the station's selector off came to (design §3.3, {@code SWITCH_OFF}). */
    public enum SelectorSwitchOff {
        SWITCHED_OFF,
        /** The station has no selector mode to switch off. */
        NOT_ON,
        /** An order of the station has handed ingredients over: its line must drain first. */
        REFUSED_ENGAGED,
        /**
         * The station holds (design §8): the player has to check the line and resume it first, so lost ingredients or a
         * quarantined item never end up in the line or the racks of what would be a plain station then.
         */
        REFUSED_HOLD
    }

    /** Whether the station is in selector mode, and which (full save only, never in a schematic print). */
    public SelectorMode selectorMode() {
        return selectorMode;
    }

    /** Local mode: the position of the one registered Recipe Selector. */
    public Optional<BlockPos> linkedSelector() {
        return linkedSelector;
    }

    /** The kind of machine the station's selector serves, as far as the station knows it. */
    public Optional<SelectorMachine.Kind> selectorMachineKind() {
        return machineKind;
    }

    /** The world position of the station's own Warehouse Input for its products (design §3.5). */
    public Optional<BlockPos> selectorReturnInput() {
        return returnInput;
    }

    /** The result the station's line is set to. */
    public Optional<ItemKey> selection() {
        return selection;
    }

    /** Game time of the last switch of the line (the slip's sequence). */
    public long selectorSwitchTime() {
        return switchTime;
    }

    /** How often the line has switched (goggles only). */
    public int selectorEpoch() {
        return selectorEpoch;
    }

    /** The hold that stops the station until a player confirms, if one is armed (design §8). */
    public Optional<SelectorHold> selectorHold() {
        return selectorHold;
    }

    /** Slip mode: the order that may fetch one run only until it has brought a result back (design §7.5). */
    public Optional<UUID> selectorProbeOrder() {
        return probeOrder;
    }

    /** Local mode: the machine's filter read back as the selection at the last arm pass. Never saved. */
    public boolean selectorArmed() {
        return selectorArmed;
    }

    /** Local mode: why the last arm pass failed, if it did. Never saved. */
    public Optional<SelectorWait> selectorArmFault() {
        return armFault;
    }

    /** Slip mode: whether a probe has shown the far factory to work since the last load. Never saved. */
    public boolean selectorSlipsTrusted() {
        return slipsTrusted;
    }

    /** Whether the station's buffer is empty (a selector station switches its line only then). */
    public boolean bufferIsEmpty() {
        return buffer.isEmpty();
    }

    /** The first item in the station's buffer, if any (the hold release names it). */
    public Optional<ItemKey> firstBufferedItem() {
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack stack = buffer.getStackInSlot(slot);
            if (!stack.isEmpty())
                return Optional.of(ItemKey.of(stack));
        }
        return Optional.empty();
    }

    /**
     * Server: puts the station into selector mode {@code mode} (ADR-085). Refused while a Crafter Feeder is attached,
     * while the station is in the other mode, and while it has open orders (orders created before the switch would not
     * be bound to the line). An accepted change starts the station afresh: no selection, not armed, no hold.
     *
     * @param kind the machine kind the selector declares, if it knows it
     */
    public SelectorModeChange setSelectorMode(SelectorMode mode, Optional<SelectorMachine.Kind> kind) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(kind, "kind");
        if (!mode.selects())
            throw new IllegalArgumentException("switch a selector off with switchSelectorOff()");
        if (selectorMode == mode) {
            if (kind.isPresent() && !kind.equals(machineKind)) {
                machineKind = kind;
                selectorChanged();
            }
            return SelectorModeChange.UNCHANGED;
        }
        SelectorModeChange refused = selectorModeRefusal(false);
        if (refused != null)
            return refused;
        selectorMode = mode;
        machineKind = kind;
        linkedSelector = Optional.empty();
        resetSelectorLine();
        selectorChanged();
        return SelectorModeChange.ACCEPTED;
    }

    /**
     * Server: a local Recipe Selector at {@code selectorPos} registers itself (design §3.3): mode LOCAL with that one
     * selector. A station whose registered selector is gone (not loaded, or no longer linked back) accepts a new one
     * even with open orders: they are already bound to the line and simply wait for it.
     */
    public SelectorModeChange registerSelector(BlockPos selectorPos, Optional<SelectorMachine.Kind> kind) {
        Objects.requireNonNull(selectorPos, "selectorPos");
        Objects.requireNonNull(kind, "kind");
        Optional<SelectorModeChange> refused = selectorRegistrationRefusal(selectorPos);
        if (refused.isPresent())
            return refused.get();
        selectorMode = SelectorMode.LOCAL;
        linkedSelector = Optional.of(selectorPos.immutable());
        machineKind = kind;
        // Cleared, so the first arm pass switches with an idle machine (design §3.3) - unless an order's ingredients are
        // in the line already (review fix): then the selection is what the line holds, the next pass sets the new
        // machine to it (or stops the line while that machine is busy with something else), and an older head of
        // another result keeps waiting for that order instead of both waiting for each other.
        Optional<WarehouseControllerBlockEntity> controller = controller();
        if (controller.isEmpty() || !controller.get().selectorStationEngaged(worldPosition))
            selection = Optional.empty();
        selectorArmed = false;
        armFault = Optional.empty();
        selectorChanged();
        return SelectorModeChange.ACCEPTED;
    }

    /**
     * Server: why a local Recipe Selector at {@code selectorPos} could not register here right now, or empty if it
     * could (design §3.2, §3.3); changes nothing. The selector item asks it before it places the block.
     */
    public Optional<SelectorModeChange> selectorRegistrationRefusal(BlockPos selectorPos) {
        Objects.requireNonNull(selectorPos, "selectorPos");
        if (attachedFeeder().isPresent())
            return Optional.of(SelectorModeChange.REFUSED_FEEDER);
        if (selectorMode == SelectorMode.LOCAL) {
            // The same position registering again is a new block where the old one stood.
            if (linkedSelector.filter(selectorPos::equals).isEmpty() && localSelector().isPresent())
                return Optional.of(SelectorModeChange.REFUSED_SELECTOR_PRESENT);
            // A station whose selector is gone takes a new one even with open orders: they already wait for it.
            return Optional.empty();
        }
        return Optional.ofNullable(selectorModeRefusal(false));
    }

    /**
     * Server: binds the Warehouse Input at {@code input} as this station's return input through the aisle's controller
     * (design §3.5); empty when the station is in no warehouse.
     */
    public Optional<WarehouseControllerBlockEntity.ReturnInputBinding> bindSelectorReturnInputAt(BlockPos input) {
        Objects.requireNonNull(input, "input");
        return controller().map(found -> found.bindSelectorReturnInput(this, input));
    }

    /**
     * Server: the local Recipe Selector at {@code selectorPos} was broken. The station keeps mode LOCAL (fail closed):
     * its orders wait for a new selector or for the player to switch the selector off.
     */
    public void selectorRemoved(BlockPos selectorPos) {
        if (linkedSelector.filter(selectorPos::equals).isEmpty())
            return;
        linkedSelector = Optional.empty();
        selectorArmed = false;
        selectorChanged();
    }

    /**
     * Server: switches the station's selector off (design §3.3, {@code SWITCH_OFF}). Refused while an order of the
     * station is engaged, because its ingredients are in the line, and while the station holds and has a controller
     * that can release the hold (resume it first);
     * accepted, the station's open orders become ordinary
     * orders again, the selector forgets its selection and the machine's filter is left as it is (I6).
     */
    public SelectorSwitchOff switchSelectorOff() {
        if (!selectorMode.selects())
            return SelectorSwitchOff.NOT_ON;
        Optional<WarehouseControllerBlockEntity> controller = controller();
        if (controller.isPresent() && controller.get().selectorStationEngaged(worldPosition))
            return SelectorSwitchOff.REFUSED_ENGAGED;
        // Review fix: switching off would drop the hold and with it the quarantine, unchecked (design §8). Only while
        // a controller can release it (re-review fix): without one, switching off is the station's only way out.
        if (selectorHold.isPresent() && controller.isPresent())
            return SelectorSwitchOff.REFUSED_HOLD;
        localSelector().ifPresent(LocalSelectorLink::clearSelection);
        selectorMode = SelectorMode.NONE;
        linkedSelector = Optional.empty();
        machineKind = Optional.empty();
        resetSelectorLine();
        controller.ifPresent(found -> found.onSelectorSwitchedOff(worldPosition));
        selectorChanged();
        return SelectorSwitchOff.SWITCHED_OFF;
    }

    /** Server: binds (or, with empty, unbinds) the station's return input; the controller checks the rules first. */
    public void setSelectorReturnInput(Optional<BlockPos> input) {
        Objects.requireNonNull(input, "input");
        Optional<BlockPos> next = input.map(BlockPos::immutable);
        if (next.equals(returnInput))
            return;
        returnInput = next;
        selectorChanged();
    }

    /**
     * Server (controller): the line switched to {@code target} at {@code now}. The switch time only ever grows, so a
     * slip's sequence stays monotonic even within one tick.
     */
    public void selectorSwitched(ItemKey target, long now) {
        Objects.requireNonNull(target, "target");
        selection = Optional.of(target);
        switchTime = Math.max(now, switchTime + 1L);
        selectorEpoch++;
        setChanged();
    }

    /**
     * Server (controller): the result of an arm pass; returns whether anything changed.
     *
     * @param switching whether the pass tried to switch the line (a busy machine then only delays the switch)
     */
    public boolean setSelectorArmed(boolean armed, Optional<SelectorWait> fault, boolean switching) {
        Objects.requireNonNull(fault, "fault");
        if (armed == selectorArmed && fault.equals(armFault) && switching == armFaultOnSwitch)
            return false;
        selectorArmed = armed;
        armFault = fault;
        armFaultOnSwitch = switching;
        return true;
    }

    /**
     * Local mode: whether the last failed arm pass was a switch. A busy machine only delays a switch, but a busy machine
     * whose filter is out of step with the selection (after a crash restore, design §6.1) must stop the line: its
     * ingredients would go into a machine set to something else. Never saved.
     */
    public boolean selectorArmFaultOnSwitch() {
        return armFaultOnSwitch;
    }

    /** Server (controller): arms {@code hold} unless one is armed already; returns whether it was armed. */
    public boolean armSelectorHold(SelectorHold hold) {
        Objects.requireNonNull(hold, "hold");
        if (selectorHold.isPresent())
            return false;
        selectorHold = Optional.of(hold);
        selectorArmed = false;
        slipsTrusted = false;
        selectorChanged();
        return true;
    }

    /**
     * Server (controller, after its checks): releases the hold. The selection is kept, so whatever is still in the line
     * is surplus of the old product and never becomes the next one (design §8).
     */
    public void clearSelectorHold() {
        if (selectorHold.isEmpty())
            return;
        selectorHold = Optional.empty();
        selectorArmed = false;
        slipsTrusted = false;
        selectorChanged();
    }

    /**
     * Slip mode (design §7.3): the recipe slip a box packed from this station gets now: the selection, the station's
     * address and position, and the game time of its last switch. Empty in any other mode or without a selection.
     */
    public Optional<RecipeSlip> currentSlip() {
        if (selectorMode != SelectorMode.SLIPS || selection.isEmpty() || level == null)
            return Optional.empty();
        String from = controller().flatMap(found -> found.addressOf(worldPosition)).map(StorageAddress::format)
                .orElseGet(() -> worldPosition.getX() + ", " + worldPosition.getY() + ", " + worldPosition.getZ());
        return Optional.of(RecipeSlip.of(selection.get().getItem(), from, GlobalPos.of(level.dimension(),
                worldPosition), switchTime));
    }

    /**
     * Server (controller, slip mode): whether a probe has shown the far factory to work (design §7.5). Never saved: a
     * load, slips switched on and every hold make the line untrusted again.
     */
    public void setSelectorSlipsTrusted(boolean trusted) {
        slipsTrusted = trusted;
    }

    /**
     * GameTests only: puts {@code stack} into the station's buffer as a crane delivery would, and returns what did not
     * fit. The buffer's own view is extract-only.
     */
    public ItemStack insertIntoBufferForTest(ItemStack stack) {
        return buffer.insert(stack, false);
    }

    /** Server (controller): the probe order is {@code order} now, or none. */
    public void setSelectorProbeOrder(Optional<UUID> order) {
        Objects.requireNonNull(order, "order");
        if (order.equals(probeOrder))
            return;
        probeOrder = order;
        setChanged();
    }

    /**
     * Server (controller, local mode): asks the station's selector to set its machine's filter to {@code target}
     * (design §6.1). Fails closed: no registered selector, an unloaded one or one that no longer links back to this
     * station answers a fault, never a guess and never another machine.
     */
    public Optional<SelectorWait> armLocalSelector(ItemKey target, boolean switching) {
        Objects.requireNonNull(target, "target");
        if (testSelectorArm != null)
            return testSelectorArm.arm(target, switching);
        if (linkedSelector.isEmpty() || level == null)
            return Optional.of(SelectorWait.NO_SELECTOR);
        if (!level.isLoaded(linkedSelector.get()))
            return Optional.of(SelectorWait.SELECTOR_NOT_LOADED);
        Optional<LocalSelectorLink> selector = localSelector();
        if (selector.isEmpty())
            return Optional.of(SelectorWait.NO_SELECTOR);
        Optional<SelectorWait> fault = selector.get().armFor(target, switching);
        // The machine kind is read again on every pass (design §3.1): a Mixer set on the Basin later changes the
        // pattern check.
        Optional<SelectorMachine.Kind> kind = selector.get().machineKind();
        if (kind.isPresent() && !kind.equals(machineKind)) {
            machineKind = kind;
            setChanged();
        }
        return fault;
    }

    /**
     * GameTests only: a stand-in for the local Recipe Selector and its machine, so the controller's scheduling can be
     * tested without the selector block. Never saved; {@code null} removes it.
     */
    public void installTestSelectorArm(@Nullable SelectorArm arm) {
        testSelectorArm = arm;
    }

    /** The registered local selector, loaded and linked back to this station; never loads a chunk. */
    private Optional<LocalSelectorLink> localSelector() {
        if (linkedSelector.isEmpty() || level == null || !level.isLoaded(linkedSelector.get()))
            return Optional.empty();
        return level.getBlockEntity(linkedSelector.get()) instanceof LocalSelectorLink link
                && link.linkedStation().filter(worldPosition::equals).isPresent() ? Optional.of(link)
                : Optional.empty();
    }

    /** Why a mode change is refused, or {@code null}; {@code sameModeAllowed} lets a re-registration pass. */
    @Nullable
    private SelectorModeChange selectorModeRefusal(boolean sameModeAllowed) {
        if (attachedFeeder().isPresent())
            return SelectorModeChange.REFUSED_FEEDER;
        if (selectorMode.selects() && !sameModeAllowed)
            return SelectorModeChange.REFUSED_OTHER_MODE;
        Optional<WarehouseControllerBlockEntity> controller = controller();
        if (controller.isPresent() && controller.get().productionOrdersAt(worldPosition).stream()
                .anyMatch(ProductionOrder::isOpen))
            return SelectorModeChange.REFUSED_OPEN_ORDERS;
        return null;
    }

    /** Forgets the line's state: selection, hold, probe, arming and trust. */
    private void resetSelectorLine() {
        selection = Optional.empty();
        selectorHold = Optional.empty();
        probeOrder = Optional.empty();
        selectorArmed = false;
        armFault = Optional.empty();
        slipsTrusted = false;
    }

    /** Saves the change and tells the aisle's controller, which keeps its own list of selector stations. */
    private void selectorChanged() {
        setChanged();
        controller().ifPresent(found -> found.onSelectorStationChanged(worldPosition));
    }

    private CompoundTag saveSelector(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(SELECTOR_MODE, selectorMode.name());
        linkedSelector.ifPresent(pos -> tag.putLong(SELECTOR_LINK, pos.asLong()));
        machineKind.ifPresent(kind -> tag.putString(SELECTOR_KIND, kind.name()));
        returnInput.ifPresent(pos -> tag.putLong(SELECTOR_RETURN, pos.asLong()));
        selection.ifPresent(key -> tag.put(SELECTOR_SELECTION, key.save(registries)));
        tag.putLong(SELECTOR_SWITCH_TIME, switchTime);
        tag.putInt(SELECTOR_EPOCH, selectorEpoch);
        selectorHold.ifPresent(hold -> tag.put(SELECTOR_HOLD, hold.save(registries)));
        probeOrder.ifPresent(id -> tag.putUUID(SELECTOR_PROBE, id));
        return tag;
    }

    /** Reads the selector state of a full save; an older save without one reads as mode NONE. Never throws. */
    private void loadSelector(CompoundTag tag, HolderLookup.Provider registries) {
        selectorMode = SelectorMode.NONE;
        for (SelectorMode mode : SelectorMode.values()) {
            if (mode.name().equals(tag.getString(SELECTOR_MODE)))
                selectorMode = mode;
        }
        linkedSelector = tag.contains(SELECTOR_LINK, Tag.TAG_LONG)
                ? Optional.of(BlockPos.of(tag.getLong(SELECTOR_LINK))) : Optional.empty();
        machineKind = SelectorMachine.Kind.byName(tag.getString(SELECTOR_KIND));
        returnInput = tag.contains(SELECTOR_RETURN, Tag.TAG_LONG)
                ? Optional.of(BlockPos.of(tag.getLong(SELECTOR_RETURN))) : Optional.empty();
        selection = tag.contains(SELECTOR_SELECTION) ? ItemKey.load(registries, tag.get(SELECTOR_SELECTION))
                : Optional.empty();
        switchTime = Math.max(0L, tag.getLong(SELECTOR_SWITCH_TIME));
        selectorEpoch = Math.max(0, tag.getInt(SELECTOR_EPOCH));
        selectorHold = tag.contains(SELECTOR_HOLD, Tag.TAG_COMPOUND)
                ? SelectorHold.load(tag.getCompound(SELECTOR_HOLD), registries) : Optional.empty();
        probeOrder = tag.hasUUID(SELECTOR_PROBE) ? Optional.of(tag.getUUID(SELECTOR_PROBE)) : Optional.empty();
        if (!selectorMode.selects()) {
            linkedSelector = Optional.empty();
            machineKind = Optional.empty();
            resetSelectorLine();
        }
        // Never saved: the first arm pass after a load verifies the filter again (design §11).
        selectorArmed = false;
        armFault = Optional.empty();
        slipsTrusted = false;
    }

    // --- screen ------------------------------------------------------------------------------------------------------

    /**
     * Opens the pattern screen for {@code player} (server). The extra data carries the block position and the buffer
     * slot count, exactly as the warehouse terminal does, so both sides build the same slots even when the client's
     * block entity is missing.
     *
     * @return whether a screen was opened
     */
    public boolean openScreen(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved())
            return false;
        return player.openMenu(new SimpleMenuProvider(
                (id, inventory, owner) -> ProductionMenu.create(id, inventory, this),
                getBlockState().getBlock().getName()), extra -> {
                    extra.writeBlockPos(worldPosition);
                    extra.writeVarInt(bufferSlots());
                    extra.writeVarInt(patterns.size());
                }).isPresent();
    }

    /** The buffer behind the screen's slots: items can be taken out by hand, nothing can be put in. */
    public IItemHandler menuBuffer() {
        return buffer;
    }

    /**
     * Whether {@code player} may use this station, by the same rule vanilla containers use
     * ({@code Container#stillValidBlockEntity}). Create's {@code MenuBase} asks it every tick, so a broken, replaced or
     * unloaded station closes its screen by itself. Never throws.
     */
    @Override
    public boolean canPlayerUse(Player player) {
        Objects.requireNonNull(player, "player");
        return !isRemoved() && Container.stillValidBlockEntity(this, player);
    }

    // --- goggles -----------------------------------------------------------------------------------------------------

    /** The production goggle data as of the last observation (server) or sync (client). */
    public ProductionGoggleSummary productionSummary() {
        return productionSummary;
    }

    /**
     * A player looks at the station through goggles (server): the shared station summary is rebuilt by the base, and
     * the production data on top of it here. A change of either marks the packet pending, so both are synced together
     * under the base's throttle.
     */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        ProductionGoggleSummary next = createProductionSummary();
        if (!next.equals(productionSummary)) {
            productionSummary = next;
            markSummaryDirty();
        }
        super.onGoggleObserved();
    }

    private ProductionGoggleSummary createProductionSummary() {
        Optional<WarehouseControllerBlockEntity> found = controller();
        // The feeder flag and how many complete patterns it refuses (issue #34): numbers only, like the rest.
        boolean feeder = attachedFeeder().isPresent();
        int refused = 0;
        if (feeder) {
            for (int slot = 0; slot < patterns.size(); slot++)
                refused += checkOf(slot).check().refused() ? 1 : 0;
        }
        if (found.isEmpty())
            return new ProductionGoggleSummary(patterns.patternCount(), 0, Optional.empty(), 0L, 0L, 0, 0L, false,
                    feeder, refused);
        List<StoppedProduct> stopped = stoppedProducts(found.get());
        long unrecovered = 0L;
        for (StoppedProduct entry : stopped)
            unrecovered += entry.unrecovered();
        List<ProductionOrder<ItemKey, RackPosition>> orders = found.get().productionOrdersAt(worldPosition);
        int open = 0;
        long missing = 0L;
        long awaited = 0L;
        Optional<ProductionOrderState> oldest = Optional.empty();
        // Whether the order the line reports is a chain step that is fetching nothing because an earlier step of its
        // own plan is still running (M20, issue #4). The controller answers it, because it owns the plan, and the same
        // answer goes into the rows of both screens ({@code WarehouseControllerBlockEntity#waitsForStep}).
        boolean oldestWaitingForStep = false;
        // The Recipe Selector's part (ADR-085): the oldest order that waits at the gate and why, and how many queue.
        boolean selects = selectorMode.selects();
        SelectorWait headWait = SelectorWait.NONE;
        Optional<ResourceLocation> headItem = Optional.empty();
        int queued = 0;
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            if (oldest.isEmpty()) {
                oldest = Optional.of(order.state());
                oldestWaitingForStep = found.get().waitsForStep(order);
            }
            if (!order.isOpen())
                continue;
            open++;
            missing += order.outstandingIngredients();
            awaited += order.outstandingResult();
            if (!selects)
                continue;
            SelectorWait wait = found.get().waitsForSelector(order);
            if (wait.queues() && wait != SelectorWait.HOLD)
                queued++;
            if (headWait == SelectorWait.NONE && wait != SelectorWait.NONE) {
                headWait = wait;
                headItem = found.get().selectorWaitItem(order, wait)
                        .map(key -> BuiltInRegistries.ITEM.getKey(key.getItem()));
            }
        }
        ProductionGoggleSummary.SelectorSummary selector = selects
                ? new ProductionGoggleSummary.SelectorSummary(selectorMode,
                        selection.map(key -> BuiltInRegistries.ITEM.getKey(key.getItem())), selectorEpoch,
                        selectorHold.isPresent(), found.get().selectorFault(worldPosition), headWait, headItem, queued)
                : ProductionGoggleSummary.SelectorSummary.NONE;
        return new ProductionGoggleSummary(patterns.patternCount(), open, oldest, missing, awaited, stopped.size(),
                unrecovered, oldestWaitingForStep, feeder, refused, selector);
    }

    /**
     * Production lines of the goggle tooltip: the patterns, then what the orders at this station are waiting for. The
     * request lines of the shared delivery base are deliberately skipped — a production station serves no retrieval
     * request, so "No pending request" would be noise.
     */
    @Override
    protected void addStationGoggleLines(List<Component> tooltip, StationGoggleSummary shown) {
        ProductionGoggleSummary production = productionSummary;
        if (production.patterns() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_PATTERNS, production.patterns())
                    .forGoggles(tooltip, 1);
        else
            WareworksLang.translate(WareworksLang.GOGGLES_PRODUCTION_NO_PATTERNS).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        // A Crafter Feeder at the back (issue #34, ADR-067) changes what the patterns are: the ones that are no
        // crafting recipe are skipped, and that count is what tells a player why an item vanished from the terminal.
        if (production.feeder()) {
            WareworksLang.translate(WareworksLang.GOGGLES_PRODUCTION_FEEDER).style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, 1);
            if (production.refusedPatterns() > 0)
                WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_REFUSED, production.refusedPatterns())
                        .style(ChatFormatting.GOLD).forGoggles(tooltip, 2);
        }
        addSelectorGoggleLines(tooltip, production.selector());
        // The safety stop outranks everything else the station could say, exactly as it does on the keeper's lamp and in
        // its screen: it is the one state a player has to act on, and the pause has already cancelled the orders that
        // would otherwise be reported below (M20, ADR-032).
        if (production.anyStopped()) {
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_STOPPED, production.stoppedProducts())
                    .style(ChatFormatting.RED).forGoggles(tooltip, 1);
            if (production.unrecovered() > 0)
                WareworksLang.countLine(WareworksLang.KEEPER_PAUSED_LOST, production.unrecovered())
                        .forGoggles(tooltip, 2);
            WareworksLang.translate(WareworksLang.PRODUCTION_RESUME_HINT).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 2);
        }
        if (production.openOrders() == 0) {
            WareworksLang.translate(WareworksLang.GOGGLES_PRODUCTION_NO_ORDERS).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
            return;
        }
        WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_ORDERS, production.openOrders())
                .forGoggles(tooltip, 1);
        // The state of the oldest order — or, for a chain step the aisle is deliberately handing nothing, what it is
        // really doing: the same sentence the terminal's step panel and this station's own rows show (M20, issue #4).
        if (production.oldestWaitingForStep())
            WareworksLang.productionWaitingForStep().forGoggles(tooltip, 2);
        else
            production.oldestState().ifPresent(state -> WareworksLang.productionState(state).forGoggles(tooltip, 2));
        if (production.missingIngredients() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_MISSING, production.missingIngredients())
                    .forGoggles(tooltip, 2);
        if (production.awaitedResult() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_AWAITED, production.awaitedResult())
                    .forGoggles(tooltip, 2);
    }

    /**
     * The Recipe Selector's goggle lines (ADR-085, design §12): the mode, what the line makes now, a hold or a fault in
     * red, why the oldest waiting order waits and how many orders queue for their turn.
     */
    private static void addSelectorGoggleLines(List<Component> tooltip, ProductionGoggleSummary.SelectorSummary shown) {
        if (!shown.on())
            return;
        WareworksLang.translate(shown.mode() == SelectorMode.SLIPS ? WareworksLang.GOGGLES_PRODUCTION_SELECTOR_SLIPS
                : WareworksLang.GOGGLES_PRODUCTION_SELECTOR_LOCAL).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
        shown.selection().flatMap(BuiltInRegistries.ITEM::getOptional).ifPresent(item -> WareworksLang
                .translate(WareworksLang.GOGGLES_PRODUCTION_SELECTOR_MAKING, new ItemStack(item).getHoverName())
                .style(ChatFormatting.WHITE).forGoggles(tooltip, 2));
        if (shown.hold())
            WareworksLang.translate(WareworksLang.GOGGLES_PRODUCTION_SELECTOR_HOLD).style(ChatFormatting.RED)
                    .forGoggles(tooltip, 2);
        else
            shown.fault().ifPresent(fault -> WareworksLang.builder()
                    .add(SelectorTexts.waitText(fault, Optional.empty())).style(ChatFormatting.RED)
                    .forGoggles(tooltip, 2));
        if (shown.headWait() != SelectorWait.NONE && !shown.hold() && shown.fault().isEmpty())
            WareworksLang.builder().add(SelectorTexts.waitText(shown.headWait(), shown.headItem()
                    .flatMap(BuiltInRegistries.ITEM::getOptional).map(ItemKey::of)))
                    .style(ProductionScreenState.isSelectorFault(shown.headWait()) ? ChatFormatting.RED
                            : ChatFormatting.GRAY)
                    .forGoggles(tooltip, 2);
        if (shown.waiting() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_SELECTOR_WAITING, shown.waiting())
                    .style(ChatFormatting.GRAY).forGoggles(tooltip, 2);
    }

    @Override
    protected String goggleHeaderKey() {
        return WareworksLang.GOGGLES_WAREHOUSE_PRODUCTION;
    }

    // --- persistence and sync ----------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            // Only the bounded numbers: the pattern items never go into a chunk packet (see ProductionGoggleSummary).
            CompoundTag summary = new CompoundTag();
            productionSummary.write(summary);
            tag.put(PRODUCTION_SUMMARY_TAG, summary);
            return;
        }
        tag.put(ProductionPatterns.PATTERNS_TAG, patterns.save(registries));
        if (!feederLaid.isEmpty()) {
            ListTag laid = new ListTag();
            for (Map.Entry<UUID, Integer> entry : feederLaid.entrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putUUID(FEEDER_LAID_ORDER, entry.getKey());
                entryTag.putInt(FEEDER_LAID_RUNS, entry.getValue());
                laid.add(entryTag);
            }
            tag.put(FEEDER_LAID_TAG, laid);
        }
        tag.put(RECIPE_SELECTOR_TAG, saveSelector(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            productionSummary = ProductionGoggleSummary.read(tag.getCompound(PRODUCTION_SUMMARY_TAG));
            return;
        }
        // A tag that is no full save is a schematic print landing on a station that is already standing (isFullSave,
        // issue #25, ADR-061): patterns do not travel in a schematic, so such a tag says nothing about them and must
        // not wipe the ones a player wrote. Every save writes the list, even an empty one.
        if (isFullSave(tag)) {
            patterns.load(tag.getList(ProductionPatterns.PATTERNS_TAG, Tag.TAG_COMPOUND), registries);
            patternsChanged();
            feederLaid.clear();
            ListTag laid = tag.getList(FEEDER_LAID_TAG, Tag.TAG_COMPOUND);
            for (int i = 0; i < laid.size() && feederLaid.size() < MAX_FEEDER_LAID; i++) {
                CompoundTag entryTag = laid.getCompound(i);
                int runs = Math.min(MAX_FEEDER_RUNS, entryTag.getInt(FEEDER_LAID_RUNS));
                if (entryTag.hasUUID(FEEDER_LAID_ORDER) && runs > 0)
                    feederLaid.put(entryTag.getUUID(FEEDER_LAID_ORDER), runs);
            }
            // The selector state too (design §3.1): a printed station is mode NONE and a standing one keeps its own.
            loadSelector(tag.getCompound(RECIPE_SELECTOR_TAG), registries);
        }
    }

    /** A station in selector mode tells its controller when it loads, so the controller lists it again. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel && selectorMode.selects())
            controller().ifPresent(found -> found.onSelectorStationChanged(worldPosition));
    }

    /**
     * Real removal: an aisle's controller cancels the production orders of a station that is gone.
     * <p>
     * The controller is resolved <b>directly</b> rather than through {@code controller()}: Create's
     * {@code SmartBlockEntity#setRemoved} sets the removed flag <i>before</i> it calls {@link #remove()}, and
     * {@code controller()} refuses once {@code isRemoved()} is true — so that route could never reach the controller
     * and this cleanup silently did nothing (M11 review fix).
     */
    @Override
    public void remove() {
        // The selector lets go of its selection before the station's orders are cancelled (design §3.3); the
        // machine's filter stays as it is (I6).
        if (level instanceof ServerLevel)
            localSelector().ifPresent(LocalSelectorLink::clearSelection);
        super.remove();
        if (level instanceof ServerLevel)
            WarehouseRegistry.findController(level, worldPosition)
                    .ifPresent(controller -> controller.onProductionStationRemoved(worldPosition));
    }
}
