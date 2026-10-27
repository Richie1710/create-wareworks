package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.kinetics.StaleOverstressCheck;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.production.CrafterFeedPlan;
import dev.wareworks.core.production.CrafterGrid;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.FeederCycle;
import dev.wareworks.core.production.FeederStatus;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of the Warehouse Crafter Feeder (issue #34, ADR-067): lays the production orders of the station whose
 * back it sits on into the vanilla Crafter in front of it, one order at a time, and pulses the Crafter once per run.
 * <p>
 * <b>What it does, per server tick</b> ({@link #serverTick}):
 * <ol>
 * <li>finds its station ({@link CrafterFeederAttachment}) and its Crafter ({@code FACING});</li>
 * <li>while idle, every {@value #IDLE_POLL_TICKS} ticks, loads the oldest order of the station whose ingredients have
 * all been delivered ({@link ProductionOrderState#DELIVERED}) and that belongs to a pattern slot whose crafting recipe
 * check passed ({@link CrafterFeedPlan#match}) — but only into an <b>empty</b> grid, so two orders never mix. An order a
 * feeder had already begun and lost (the feeder was broken and placed again) is loaded again for the runs it has left
 * ({@link CrafterFeedPlan#remainingRuns}); the station keeps how many runs of each order were laid. Loading disables
 * the Crafter slots the pattern leaves empty and enables the ones it uses;</li>
 * <li>lays a batch into the empty grid, after checking that the layout is still the crafting recipe it was loaded as:
 * up to a stack per used slot, several runs at once ({@link CrafterFeedPlan#batch}), straight from the station's buffer
 * into the Crafter's slots — the feeder itself
 * holds nothing, so an item is always in the buffer, in the Crafter, in what stands in front of the Crafter's output,
 * or dropped;</li>
 * <li>pulses once per run ({@link FeederCycle}), after checking that the Crafter would make the order's result and that
 * its output can take the result and every remainder — the vanilla Crafter would otherwise drop them on the floor;</li>
 * <li>once the last run of the order is crafted and the grid is empty again, restores the Crafter's slots and becomes
 * idle.</li>
 * </ol>
 * <b>What it tells the warehouse</b>: progress per batch and per run (the order's timeout starts over), and, with the
 * first batch, that the machine has taken the order on
 * ({@link WarehouseControllerBlockEntity#onCrafterFeederTookIngredients}), so the order waits for its result from then
 * on and results that arrive meanwhile are credited to it first. The result is
 * counted exactly like any machine's: stored out of a warehouse input. The order's timeouts and the safety stop are the
 * warehouse's own and untouched; a blocked feeder simply stops pulsing and says why ({@link FeederStatus}).
 * <p>
 * <b>The grid is the truth.</b> The feeder saves which order it serves, the layout it lays and how many runs it has not
 * laid yet; how many lie in the grid it reads from the Crafter every tick ({@link CrafterGrid#runsIn}), so no counter of
 * its own can drift from the items. A grid that holds anything but the loaded layout is never pulsed.
 */
public class WarehouseCrafterFeederBlockEntity extends KineticBlockEntity implements GoggleObservers.Observable {
    /** Ticks between two looks for an order while idle, and between two attempts to lay a batch that failed. */
    public static final int IDLE_POLL_TICKS = 10;
    /** NBT key of the feeder's full save; present in every save, so a schematic print cannot be mistaken for one. */
    public static final String STATE_TAG = "CrafterFeeder";
    /** NBT key of the goggle data (client packets only). */
    public static final String SUMMARY_TAG = "FeederSummary";
    /** Most runs a loaded feed may claim: every cell a full pattern entry of a stack. */
    public static final int MAX_RUNS = ProductionEntry.MAX_COUNT * CrafterFeedPlan.MAX_BATCH;

    private static final String FEED = "Feed";
    private static final String ORDER = "Order";
    private static final String SLOT = "Slot";
    private static final String CELLS = "Cells";
    private static final String RESULT = "Result";
    private static final String PER_RUN = "PerRun";
    private static final String TO_LAY = "ToLay";
    private static final String CRAFTER_SIDE = "CrafterSide";
    private static final String LOCKED = "Locked";
    private static final String UNLOCKED = "Unlocked";
    private static final String RELEASE = "Release";

    /**
     * The order the feeder serves.
     * <p>
     * {@code crafterSide} is the side of the feeder the Crafter stood on when its slots were locked for this order, or
     * {@code null} while no Crafter is locked (none in front, or one that was swapped and not adopted yet). A side and not
     * a position, so a save moved by a structure or a test copy still names the block next to the feeder.
     */
    private static final class Feed {
        private final UUID order;
        private final int slot;
        private final CrafterGrid<ItemKey> layout;
        @Nullable
        private final ResourceLocation result;
        /** Result items one run makes, as the pattern said when the order was loaded; 0 if unknown. */
        private final int perRun;
        private int toLay;
        @Nullable
        private Direction crafterSide;
        /** Slots the feeder disabled because the layout leaves them empty. */
        private int locked;
        /** Slots a player had disabled that the layout uses, which the feeder enabled. */
        private int unlocked;

        private Feed(UUID order, int slot, CrafterGrid<ItemKey> layout, @Nullable ResourceLocation result, int perRun,
                int toLay) {
            this.order = order;
            this.slot = slot;
            this.layout = layout;
            this.result = result;
            this.perRun = perRun;
            this.toLay = toLay;
        }
    }

    /**
     * Locks the feeder set on a Crafter and could not take back yet, because the feed they belonged to could not be read
     * back. Released on the first tick that finds that Crafter.
     */
    private record LockRelease(Direction side, int locked, int unlocked) {
    }

    /** One look at a Crafter's grid. */
    private record GridRead(List<Optional<ItemKey>> keys, int[] counts) {
        boolean isEmpty() {
            for (int count : counts) {
                if (count > 0)
                    return false;
            }
            return true;
        }
    }

    @Nullable
    private Feed feed;
    @Nullable
    private LockRelease orphanLocks;
    private final FeederCycle cycle = new FeederCycle();
    /** Clears an overstress Create keeps after the network has the capacity again (issue #38, ADR-071). */
    private final StaleOverstressCheck staleOverstress = new StaleOverstressCheck();
    /** Runs in the grid at the last rising edge, so the read after the craft can tell whether one was crafted. */
    private int runsAtEdge = -1;
    /** Runs lying in the grid at the last read, for the goggles. */
    private int runsInGrid;
    /** Whether the last pulse crafted nothing; shown until the next run starts. */
    private boolean craftFailed;
    private boolean loadPending = true;
    private int pollTimer;
    private FeederStatus status = FeederStatus.IDLE;

    /** What the goggles show, as of the last sync (server: as of the last observation). */
    private CrafterFeederGoggleSummary summary = CrafterFeederGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    public WarehouseCrafterFeederBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // --- state for callers and tests ---------------------------------------------------------------------------------

    /** Where the feeder faces: towards its Crafter. */
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(WarehouseCrafterFeederBlock.FACING)
                ? state.getValue(WarehouseCrafterFeederBlock.FACING) : Direction.NORTH;
    }

    /** What the feeder did in its last tick, or why it did nothing (server). */
    public FeederStatus status() {
        return status;
    }

    /** The order the feeder serves, empty while idle (server). */
    public Optional<UUID> feedOrder() {
        return feed == null ? Optional.empty() : Optional.of(feed.order);
    }

    /** The pattern slot of the order it serves, empty while idle (server). */
    public OptionalInt feedSlot() {
        return feed == null ? OptionalInt.empty() : OptionalInt.of(feed.slot);
    }

    /** Runs of the served order not laid into the Crafter yet; 0 while idle (server). */
    public int runsToLay() {
        return feed == null ? 0 : feed.toLay;
    }

    /** The goggle data as of the last sync (client) or observation (server). */
    public CrafterFeederGoggleSummary summary() {
        return summary;
    }

    /** Whether the feeder may work: it has rotation, or the server config says it needs none. */
    public boolean running() {
        return !WareworksConfig.crafterFeederRequiresRotation() || getSpeed() != 0;
    }

    private int ticksPerRun() {
        return FeederCycle.ticksPerRun(getSpeed(), WareworksConfig.crafterFeederRequiresRotation());
    }

    // --- ticking -----------------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        staleOverstress.serverTick(this);
        serverTick();
    }

    private void serverTick() {
        boolean powering = getBlockState().getValue(WarehouseCrafterFeederBlock.POWERING);
        if (loadPending) {
            // A signal saved high (a save in the middle of a pulse) comes down at once; a vanilla craft that was already
            // scheduled is in the chunk's tick list and still happens, and the grid read below sees it.
            loadPending = false;
            if (powering)
                setPowering(false);
            cycle.cooldown(ticksPerRun());
        } else if (powering && !cycle.high()) {
            // The signal is high only during a pulse of this feeder: never after a wrench turn, a /setblock or a print.
            setPowering(false);
        }
        if (cycle.advance() == FeederCycle.Step.LOWER)
            setPowering(false);
        // VERIFY is not taken from advance(): the grid is read on the first tick after the pulse that reaches the grid
        // (work(), whatever phase the cycle is in by then), so a tick that returns early (no station, no Crafter) cannot
        // lose the read.
        status = work();
    }

    /** One tick of work; returns the status it ends in. */
    private FeederStatus work() {
        CrafterFeederAttachment.Result attachment = CrafterFeederAttachment.resolve(level, worldPosition, facing());
        if (attachment.kind() == CrafterFeederAttachment.Kind.UNLOADED) {
            stopPulse();
            return status;
        }
        if (attachment.kind() == CrafterFeederAttachment.Kind.TWO) {
            stopPulse();
            return FeederStatus.TWO_STATIONS;
        }
        WarehouseProductionBlockEntity station = attachment.station()
                .map(pos -> level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity found
                        && !found.isRemoved() ? found : null)
                .orElse(null);
        if (station == null) {
            stopPulse();
            releaseWithoutStation();
            return FeederStatus.NO_STATION;
        }

        BlockPos crafterPos = worldPosition.relative(facing());
        if (!level.isLoaded(crafterPos)) {
            stopPulse();
            return status;
        }
        CrafterBlockEntity crafter = crafterAt(crafterPos);
        releaseOrphanLocks();
        if (feed != null && feed.crafterSide != null && feed.crafterSide != facing()) {
            // The feeder was turned, or the Crafter it locked is gone: whatever lies in that grid has gone to the
            // order exactly like an ingredient a machine swallowed. The order and its runs not laid yet stay.
            releaseLocks(feed);
            feed.crafterSide = null;
            setChanged();
        }
        if (crafter == null) {
            stopPulse();
            if (feed != null && feed.crafterSide != null) {
                feed.crafterSide = null;
                feed.locked = 0;
                feed.unlocked = 0;
                setChanged();
            }
            return FeederStatus.NO_CRAFTER;
        }

        GridRead grid = read(crafter);
        // Whenever a pulse is pending and over, whatever the phase: a few ticks without a station or a Crafter right
        // after an edge must not turn a late read into a lost one (a lost failed read would skip the backoff).
        if (!cycle.inPulse() && runsAtEdge >= 0 && feed != null)
            verify(station, grid);

        if (!running()) {
            stopPulse();
            return FeederStatus.NO_ROTATION;
        }
        if (cycle.inPulse())
            return FeederStatus.FEEDING;
        if (feed == null)
            return idle(station, crafter, crafterPos, grid);

        if (feed.crafterSide == null) {
            // A new Crafter in front (or the first one after a swap): adopt it, but only with an empty grid.
            if (!grid.isEmpty())
                return FeederStatus.GRID_NOT_EMPTY;
            applyLocks(feed, crafter);
        }
        OptionalInt runs = feed.layout.runsIn(grid.keys(), grid.counts());
        if (runs.isEmpty())
            return FeederStatus.GRID_DISTURBED;
        runsInGrid = runs.getAsInt();
        if (runsInGrid == 0)
            return layOrRelease(station, crafter, crafterPos);
        return run(crafter, crafterPos);
    }

    /** The grid was read after a pulse: one run fewer means the Crafter crafted; none fewer means it did not. */
    private void verify(WarehouseProductionBlockEntity station, GridRead grid) {
        OptionalInt runs = feed.layout.runsIn(grid.keys(), grid.counts());
        int before = runsAtEdge;
        runsAtEdge = -1;
        if (runs.isEmpty())
            return; // disturbed: the run is reported by no one, and the grid status says why
        if (runs.getAsInt() < before) {
            craftFailed = false;
            station.controllerForFeeder().ifPresent(controller -> controller.onCrafterFeederProgress(feed.order));
        } else {
            craftFailed = true;
            cycle.backOff();
        }
    }

    /**
     * No station behind the feeder any more: breaking a station cancels its orders, so the loaded order can no longer be
     * served. Once the grid is empty (crafted, or emptied by the player) the Crafter gets its slots back; whatever still
     * lies in it is left alone and never pulsed while no station is there.
     */
    private void releaseWithoutStation() {
        if (feed == null)
            return;
        BlockPos crafterPos = worldPosition.relative(facing());
        if (!level.isLoaded(crafterPos))
            return;
        CrafterBlockEntity crafter = crafterAt(crafterPos);
        if (crafter == null) {
            // Nothing left to give back here: the locks went with the Crafter.
            feed.crafterSide = null;
            feed.locked = 0;
            feed.unlocked = 0;
            release(null);
            return;
        }
        if (read(crafter).isEmpty())
            release(crafter);
    }

    /** No order loaded: look for one now and then, and load it into an empty grid. */
    private FeederStatus idle(WarehouseProductionBlockEntity station, CrafterBlockEntity crafter, BlockPos crafterPos,
            GridRead grid) {
        runsInGrid = 0;
        if (pollTimer > 0) {
            pollTimer--;
            return status == FeederStatus.GRID_NOT_EMPTY || status == FeederStatus.NO_WAREHOUSE ? status
                    : FeederStatus.IDLE;
        }
        pollTimer = IDLE_POLL_TICKS;
        Optional<WarehouseControllerBlockEntity> controller = station.controllerForFeeder();
        if (controller.isEmpty())
            return FeederStatus.NO_WAREHOUSE;
        Optional<Candidate> candidate = nextOrder(station, controller.get());
        if (candidate.isEmpty())
            return FeederStatus.IDLE;
        if (!grid.isEmpty())
            return FeederStatus.GRID_NOT_EMPTY;
        Candidate found = candidate.get();
        ResourceLocation result = station.patterns().keyAt(found.slot(), ProductionPatterns.RESULT_ENTRY)
                .map(key -> BuiltInRegistries.ITEM.getKey(key.getItem())).orElse(null);
        feed = new Feed(found.order(), found.slot(), CrafterRecipeCheck.layoutOf(station.patterns(), found.slot()),
                result, found.perRun(), found.runs());
        applyLocks(feed, crafter);
        craftFailed = false;
        pollTimer = 0;
        setChanged();
        return layOrRelease(station, crafter, crafterPos);
    }

    /**
     * An order to load.
     *
     * @param runs   runs still to lay ({@link CrafterFeedPlan#remainingRuns}), at least 1
     * @param perRun result items one run makes
     */
    private record Candidate(UUID order, int slot, int runs, int perRun) {
    }

    /**
     * The oldest order at {@code station} that waits for this feeder and belongs to a pattern slot whose crafting recipe
     * check passed: a delivered one, or one a feeder already began and lost (it waits for its result, and the station
     * remembers some but not all of its runs as laid). The check is the station's own cached one, taken now, right
     * before loading: an order planned before a slot was edited into something else finds no slot and is skipped (it
     * times out as any order whose machine does nothing). How many runs are loaded is what is really left
     * ({@link CrafterFeedPlan#remainingRuns}); an order with nothing left is reported as taken and skipped.
     */
    private Optional<Candidate> nextOrder(WarehouseProductionBlockEntity station,
            WarehouseControllerBlockEntity controller) {
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrdersAt(station.getBlockPos());
        station.pruneFeederLaid(id -> controller.productionOrder(id).map(ProductionOrder::isOpen).orElse(false));
        if (orders.isEmpty())
            return Optional.empty();
        ProductionPatterns patterns = station.patterns();
        List<Optional<ProductionPattern<ItemKey>>> slots = new ArrayList<>(patterns.size());
        for (int slot = 0; slot < patterns.size(); slot++)
            slots.add(patterns.patternAt(slot));
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            boolean delivered = order.state() == ProductionOrderState.DELIVERED;
            int laid = station.feederLaidRuns(order.id());
            // An order that waits for its result and that no feeder ever laid was taken by some other machine.
            if (!delivered && !(order.state() == ProductionOrderState.WAITING_FOR_RESULT && laid > 0))
                continue;
            Optional<CrafterFeedPlan.Match> match = CrafterFeedPlan.match(order, slots,
                    slot -> station.crafterCheck(slot).check() == CrafterPatternCheck.VALID);
            if (match.isEmpty())
                continue;
            int slot = match.get().slot();
            int perRun = Math.max(1, patterns.countAt(slot, ProductionPatterns.RESULT_ENTRY));
            int runs = CrafterFeedPlan.remainingRuns(match.get().runs(), laid, order.outstandingResult(), perRun);
            if (runs <= 0) {
                // Everything is laid, or the result already arrived: nothing for the Crafter, the order only waits.
                if (delivered)
                    controller.onCrafterFeederTookIngredients(order.id());
                continue;
            }
            return Optional.of(new Candidate(order.id(), slot, runs, perRun));
        }
        return Optional.empty();
    }

    /**
     * The grid is empty: lay the next batch of the loaded order, or — when everything has been laid, or the order has
     * ended — restore the Crafter's slots and become idle. Items not laid yet stay in the station's buffer.
     */
    private FeederStatus layOrRelease(WarehouseProductionBlockEntity station, CrafterBlockEntity crafter,
            BlockPos crafterPos) {
        if (feed.toLay <= 0) {
            release(crafter);
            return FeederStatus.IDLE;
        }
        if (pollTimer > 0) {
            pollTimer--;
            return status == FeederStatus.MISSING_INGREDIENTS || status == FeederStatus.RECIPE_CHANGED ? status
                    : FeederStatus.FEEDING;
        }
        Optional<WarehouseControllerBlockEntity> controller = station.controllerForFeeder();
        if (controller.isEmpty()) {
            pollTimer = IDLE_POLL_TICKS;
            return FeederStatus.NO_WAREHOUSE;
        }
        Optional<ProductionOrder<ItemKey, RackPosition>> order = controller.get().productionOrder(feed.order);
        if (order.isEmpty() || !order.get().isOpen()) {
            // Cancelled, timed out, pruned or completed by another source: lay nothing more of it.
            release(crafter);
            return FeederStatus.IDLE;
        }
        if (!recipeHolds(feed)) {
            // A /reload or a datapack changed the recipe since the order was loaded: lay nothing more of it, and let the
            // order time out like any order whose machine does nothing. The ingredients stay in the buffer.
            pollTimer = IDLE_POLL_TICKS;
            return FeederStatus.RECIPE_CHANGED;
        }
        FeederStatus laid = lay(station, controller.get(), crafterPos);
        if (laid != FeederStatus.FEEDING)
            pollTimer = IDLE_POLL_TICKS;
        return laid;
    }

    /**
     * Lays one batch: simulated first for every key and every slot, then for real, item by item from the buffer into
     * the slot, so a refusal never leaves half a batch anywhere.
     */
    private FeederStatus lay(WarehouseProductionBlockEntity station, WarehouseControllerBlockEntity controller,
            BlockPos crafterPos) {
        IItemHandler grid = level.getCapability(Capabilities.ItemHandler.BLOCK, crafterPos, facing().getOpposite());
        if (grid == null)
            return FeederStatus.NO_CRAFTER;
        int smallestStack = CrafterFeedPlan.MAX_BATCH;
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            Optional<ItemKey> key = feed.layout.at(cell);
            if (key.isPresent())
                smallestStack = Math.min(smallestStack, key.get().getMaxStackSize());
        }
        int batch = CrafterFeedPlan.batch(feed.toLay, smallestStack);
        if (batch <= 0)
            return FeederStatus.IDLE;
        for (Map.Entry<ItemKey, Integer> need : feed.layout.cellsPerKey().entrySet()) {
            if (station.bufferCount(need.getKey()) < (long) batch * need.getValue())
                return FeederStatus.MISSING_INGREDIENTS;
        }
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            Optional<ItemKey> key = feed.layout.at(cell);
            if (key.isPresent() && !grid.insertItem(CrafterGrid.slotOf(cell), key.get().toStack(batch), true).isEmpty())
                return FeederStatus.GRID_DISTURBED;
        }
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            Optional<ItemKey> key = feed.layout.at(cell);
            if (key.isEmpty())
                continue;
            ItemStack taken = station.extractForFeeder(key.get(), batch, false);
            if (taken.isEmpty())
                continue;
            ItemStack rest = grid.insertItem(CrafterGrid.slotOf(cell), taken, false);
            if (!rest.isEmpty())
                giveBack(station, rest);
        }
        feed.toLay -= batch;
        runsInGrid = batch;
        setChanged();
        station.addFeederLaidRuns(feed.order, batch);
        controller.onCrafterFeederProgress(feed.order);
        // Said with every batch, so already with the first one: from now on the order waits for its result, and results
        // that arrive are credited to it before an older order of the same item that still waits for an ingredient.
        // Only the first call changes anything.
        controller.onCrafterFeederTookIngredients(feed.order);
        return FeederStatus.FEEDING;
    }

    /**
     * What a Crafter slot refused after the simulation said it would take it: back into the station's buffer, the way
     * the crane delivers; only what even that refuses is dropped at the feeder (the rule of {@code TerminalCrafting}).
     */
    private void giveBack(WarehouseProductionBlockEntity station, ItemStack rest) {
        ItemStack left = station.insert(rest, false);
        if (left.isEmpty())
            return;
        Wareworks.LOGGER.warn("Crafter feeder at {} could not put {} back into its station; dropping it", worldPosition,
                left);
        Block.popResource(level, worldPosition, left);
    }

    /** The grid holds runs: pulse the next one, if the Crafter may craft it now. */
    private FeederStatus run(CrafterBlockEntity crafter, BlockPos crafterPos) {
        if (!cycle.ready())
            return craftFailed ? FeederStatus.CRAFT_FAILED : FeederStatus.FEEDING;
        BlockState crafterState = crafter.getBlockState();
        if (crafterState.hasProperty(CrafterBlock.TRIGGERED) && crafterState.getValue(CrafterBlock.TRIGGERED))
            return FeederStatus.EXTERNAL_SIGNAL;
        FeederStatus output = outputCheck(crafter, crafterPos);
        if (output != null)
            return output;
        cycle.raise(ticksPerRun());
        runsAtEdge = runsInGrid;
        craftFailed = false;
        setPowering(true);
        return FeederStatus.FEEDING;
    }

    /**
     * Whether the Crafter can put the result of its next craft and every remainder into what stands in front of its
     * output; {@code null} when it can. The Crafter drops whatever does not fit, so the feeder never pulses while that
     * could happen.
     */
    @Nullable
    private FeederStatus outputCheck(CrafterBlockEntity crafter, BlockPos crafterPos) {
        BlockState crafterState = crafter.getBlockState();
        if (!crafterState.hasProperty(BlockStateProperties.ORIENTATION))
            return FeederStatus.NO_CRAFTER;
        Direction front = crafterState.getValue(BlockStateProperties.ORIENTATION).front();
        BlockPos outputPos = crafterPos.relative(front);
        if (!level.isLoaded(outputPos))
            return FeederStatus.NO_OUTPUT;
        Optional<List<ItemStack>> leaving = CrafterRecipeCheck.output(level, crafter.asCraftInput());
        if (leaving.isEmpty())
            return FeederStatus.CRAFT_FAILED;
        if (!makesResult(leaving.get().getFirst(), feed))
            return FeederStatus.RECIPE_CHANGED; // the grid would now craft something the order never planned
        IItemHandler target = level.getCapability(Capabilities.ItemHandler.BLOCK, outputPos, front.getOpposite());
        if (target == null)
            return FeederStatus.NO_OUTPUT;
        return CrafterOutputFit.fits(target, leaving.get()) ? null : FeederStatus.OUTPUT_FULL;
    }

    /**
     * Whether one run of {@code target}'s layout is still the crafting recipe it was loaded as: it makes the order's
     * result, as many as one run of the pattern said. Asked before every batch, because a {@code /reload} can change
     * recipes while an order runs.
     */
    private boolean recipeHolds(Feed target) {
        Optional<List<ItemStack>> leaving = CrafterRecipeCheck.output(level, CrafterRecipeCheck.inputOf(target.layout));
        return leaving.isPresent() && makesResult(leaving.get().getFirst(), target);
    }

    /** Whether {@code made} is the loaded order's result, in the amount one run makes. */
    private static boolean makesResult(ItemStack made, @Nullable Feed target) {
        if (target == null || target.result == null || made.isEmpty())
            return false;
        return target.result.equals(BuiltInRegistries.ITEM.getKey(made.getItem()))
                && (target.perRun <= 0 || made.getCount() == target.perRun);
    }

    /** Lowers the signal if this feeder was pulsing; the craft its edge scheduled still happens. */
    private void stopPulse() {
        cycle.lowerEarly();
        setPowering(false);
    }

    private void setPowering(boolean powering) {
        WarehouseCrafterFeederBlock.setPowering(level, worldPosition, powering);
    }

    // --- the Crafter -------------------------------------------------------------------------------------------------

    @Nullable
    private CrafterBlockEntity crafterAt(BlockPos pos) {
        if (!level.isLoaded(pos))
            return null;
        return level.getBlockState(pos).getBlock() instanceof CrafterBlock
                && level.getBlockEntity(pos) instanceof CrafterBlockEntity crafter && !crafter.isRemoved() ? crafter
                        : null;
    }

    private static GridRead read(CrafterBlockEntity crafter) {
        List<Optional<ItemKey>> keys = new ArrayList<>(CrafterGrid.SLOTS);
        int[] counts = new int[CrafterGrid.SLOTS];
        for (int slot = 0; slot < CrafterGrid.SLOTS; slot++) {
            ItemStack stack = crafter.getItem(slot);
            keys.add(ItemKey.fromStack(stack));
            counts[slot] = stack.isEmpty() ? 0 : stack.getCount();
        }
        return new GridRead(keys, counts);
    }

    /**
     * Disables the slots the layout leaves empty and enables the used ones a player had disabled (ADR-067 D6), so
     * nothing but the layout can go into the grid while the order runs. Both are always possible here: the grid is
     * empty, and a Crafter changes a slot's state only while it is empty.
     */
    private void applyLocks(Feed target, CrafterBlockEntity crafter) {
        int locked = 0;
        int unlocked = 0;
        for (int slot = 0; slot < CrafterGrid.SLOTS; slot++) {
            boolean used = target.layout.at(slot).isPresent();
            boolean disabled = crafter.isSlotDisabled(slot);
            if (!used && !disabled && crafter.getItem(slot).isEmpty()) {
                crafter.setSlotState(slot, false);
                if (crafter.isSlotDisabled(slot))
                    locked |= 1 << slot;
            } else if (used && disabled) {
                crafter.setSlotState(slot, true);
                if (!crafter.isSlotDisabled(slot))
                    unlocked |= 1 << slot;
            }
        }
        target.locked = locked;
        target.unlocked = unlocked;
        target.crafterSide = facing();
        setChanged();
    }

    /** Takes back what {@link #applyLocks} did, on the Crafter on the side it was done on, if it is still there. */
    private void releaseLocks(Feed target) {
        if (target.crafterSide != null)
            releaseLocks(target.crafterSide, target.locked, target.unlocked);
        target.locked = 0;
        target.unlocked = 0;
    }

    private void releaseLocks(Direction side, int locked, int unlocked) {
        if (level == null || (locked == 0 && unlocked == 0))
            return;
        CrafterBlockEntity crafter = crafterAt(worldPosition.relative(side));
        if (crafter == null)
            return;
        for (int slot = 0; slot < CrafterGrid.SLOTS; slot++) {
            int bit = 1 << slot;
            // A slot the feeder disabled is empty (a disabled slot takes nothing), so enabling it always works.
            if ((locked & bit) != 0 && crafter.isSlotDisabled(slot))
                crafter.setSlotState(slot, true);
            // A slot the player had disabled goes back to that once it is empty again.
            if ((unlocked & bit) != 0 && !crafter.isSlotDisabled(slot) && crafter.getItem(slot).isEmpty())
                crafter.setSlotState(slot, false);
        }
    }

    private void releaseOrphanLocks() {
        if (orphanLocks == null)
            return;
        releaseLocks(orphanLocks.side(), orphanLocks.locked(), orphanLocks.unlocked());
        orphanLocks = null;
        setChanged();
    }

    /** The order is done with: restore the Crafter's slots and become idle. */
    private void release(@Nullable CrafterBlockEntity crafter) {
        if (feed != null)
            releaseLocks(feed);
        feed = null;
        runsAtEdge = -1;
        runsInGrid = 0;
        craftFailed = false;
        pollTimer = 0;
        setChanged();
    }

    // --- lifecycle ---------------------------------------------------------------------------------------------------

    /**
     * Real removal (not a chunk unload): the Crafter gets its slots back. Nothing is dropped, because the feeder holds
     * nothing; whatever lies in the grid stays there for the player.
     */
    @Override
    public void remove() {
        super.remove();
        if (level == null || level.isClientSide)
            return;
        if (feed != null)
            releaseLocks(feed);
        if (orphanLocks != null)
            releaseLocks(orphanLocks.side(), orphanLocks.locked(), orphanLocks.unlocked());
    }

    // --- goggles -----------------------------------------------------------------------------------------------------

    /**
     * A player looks at the feeder through goggles (server): rebuild the summary from the last tick's status and sync a
     * change, at most once a second ({@link SyncThrottle}).
     */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        CrafterFeederGoggleSummary next = new CrafterFeederGoggleSummary(status,
                feed == null ? Optional.empty() : Optional.ofNullable(feed.result),
                feed == null ? 0 : feed.toLay + runsInGrid);
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData();
    }

    /**
     * The feeder's goggle lines (client): the header, one status line and Create's kinetic stats below a blank line, as
     * the stacker crane's dock shows them. While an order runs the line names the item and the runs still to craft; a
     * blocked status is gold, because it is the one line a player has to act on.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CrafterFeederGoggleSummary shown = summary;
        WareworksLang.translate(WareworksLang.GOGGLES_CRAFTER_FEEDER).forGoggles(tooltip);
        Optional<Item> result = shown.result().flatMap(BuiltInRegistries.ITEM::getOptional);
        if (shown.status() == FeederStatus.FEEDING && result.isPresent())
            WareworksLang.translate(WareworksLang.CRAFTER_FEEDER_FEEDING,
                            new ItemStack(result.get()).getHoverName(), LangNumberFormat.format(shown.runsLeft()))
                    .style(ChatFormatting.WHITE).forGoggles(tooltip, 1);
        else
            WareworksLang.translate(shown.status().langKey())
                    .style(shown.status().blocked() ? ChatFormatting.GOLD : ChatFormatting.GRAY)
                    .forGoggles(tooltip, 1);
        List<Component> kineticStats = new ArrayList<>();
        if (super.addToGoggleTooltip(kineticStats, isPlayerSneaking)) {
            tooltip.add(CommonComponents.EMPTY);
            tooltip.addAll(kineticStats);
        }
        return true;
    }

    // --- persistence and sync ----------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            CompoundTag goggles = new CompoundTag();
            summary.write(goggles);
            tag.put(SUMMARY_TAG, goggles);
            return;
        }
        CompoundTag state = new CompoundTag();
        if (feed != null)
            state.put(FEED, writeFeed(feed, registries));
        if (orphanLocks != null)
            state.put(RELEASE, writeLocks(orphanLocks.side(), orphanLocks.locked(), orphanLocks.unlocked()));
        tag.put(STATE_TAG, state);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            summary = CrafterFeederGoggleSummary.read(tag.getCompound(SUMMARY_TAG));
            return;
        }
        // A tag without the feeder's own state is no save of a feeder: it is a schematic print landing on one that is
        // already standing (issue #25, ADR-061), which says nothing about the order it serves.
        if (!tag.contains(STATE_TAG, Tag.TAG_COMPOUND))
            return;
        CompoundTag state = tag.getCompound(STATE_TAG);
        feed = null;
        orphanLocks = state.contains(RELEASE, Tag.TAG_COMPOUND) ? readLocks(state.getCompound(RELEASE)) : null;
        if (state.contains(FEED, Tag.TAG_COMPOUND))
            readFeed(state.getCompound(FEED), registries);
        runsAtEdge = -1;
        loadPending = true;
    }

    private static CompoundTag writeFeed(Feed source, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ORDER, source.order);
        tag.putInt(SLOT, source.slot);
        ListTag cells = new ListTag();
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            Optional<ItemKey> key = source.layout.at(cell);
            cells.add(key.isPresent() ? key.get().save(registries) : new CompoundTag());
        }
        tag.put(CELLS, cells);
        if (source.result != null)
            tag.putString(RESULT, source.result.toString());
        tag.putInt(PER_RUN, source.perRun);
        tag.putInt(TO_LAY, source.toLay);
        if (source.crafterSide != null)
            tag.put(CRAFTER_SIDE, writeLocks(source.crafterSide, source.locked, source.unlocked));
        return tag;
    }

    /**
     * Reads a feed written by {@link #writeFeed}. Save data is untrusted, so everything is bounded; a feed whose layout
     * cannot be read back is dropped, and the locks it had set are kept aside to be released on the first tick.
     */
    private void readFeed(CompoundTag tag, HolderLookup.Provider registries) {
        LockRelease locks = tag.contains(CRAFTER_SIDE, Tag.TAG_COMPOUND) ? readLocks(tag.getCompound(CRAFTER_SIDE))
                : null;
        if (!tag.hasUUID(ORDER)) {
            keepAside(locks);
            return;
        }
        ListTag cells = tag.getList(CELLS, Tag.TAG_COMPOUND);
        if (cells.size() != CrafterGrid.SLOTS) {
            keepAside(locks);
            return;
        }
        List<ItemKey> keys = new ArrayList<>(CrafterGrid.SLOTS);
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            CompoundTag cellTag = cells.getCompound(cell);
            if (cellTag.isEmpty()) {
                keys.add(null);
                continue;
            }
            Optional<ItemKey> key = ItemKey.load(registries, cellTag);
            if (key.isEmpty()) {
                keepAside(locks);
                return;
            }
            keys.add(key.get());
        }
        CrafterGrid<ItemKey> layout = CrafterGrid.ofNullable(keys);
        if (layout.isEmpty()) {
            keepAside(locks);
            return;
        }
        ResourceLocation result = tag.contains(RESULT, Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString(RESULT))
                : null;
        Feed loaded = new Feed(tag.getUUID(ORDER), Mth.clamp(tag.getInt(SLOT), 0, ProductionPatterns.MAX_SLOTS - 1),
                layout, result, Mth.clamp(tag.getInt(PER_RUN), 0, ProductionEntry.MAX_COUNT),
                Mth.clamp(tag.getInt(TO_LAY), 0, MAX_RUNS));
        if (locks != null) {
            loaded.crafterSide = locks.side();
            loaded.locked = locks.locked();
            loaded.unlocked = locks.unlocked();
        }
        feed = loaded;
    }

    private void keepAside(@Nullable LockRelease locks) {
        if (locks != null && (locks.locked() != 0 || locks.unlocked() != 0))
            orphanLocks = locks;
    }

    private static CompoundTag writeLocks(Direction side, int locked, int unlocked) {
        CompoundTag tag = new CompoundTag();
        tag.putString(CRAFTER_SIDE, side.getSerializedName());
        tag.putInt(LOCKED, locked & CrafterGrid.ALL_SLOTS_MASK);
        tag.putInt(UNLOCKED, unlocked & CrafterGrid.ALL_SLOTS_MASK);
        return tag;
    }

    @Nullable
    private static LockRelease readLocks(CompoundTag tag) {
        Direction side = Direction.byName(tag.getString(CRAFTER_SIDE));
        if (side == null)
            return null;
        return new LockRelease(side, tag.getInt(LOCKED) & CrafterGrid.ALL_SLOTS_MASK,
                tag.getInt(UNLOCKED) & CrafterGrid.ALL_SLOTS_MASK);
    }

    @Override
    public String toString() {
        return "WarehouseCrafterFeederBlockEntity[" + worldPosition + ", " + status + ", "
                + (feed == null ? "idle" : feed.order + " slot " + feed.slot + " toLay " + feed.toLay) + "]";
    }
}
