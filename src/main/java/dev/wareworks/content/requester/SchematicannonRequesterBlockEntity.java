package dev.wareworks.content.requester;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.remote.RemoteAccess;
import dev.wareworks.content.remote.RemoteLink;
import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.requester.ChecklistNeed;
import dev.wareworks.core.requester.OrderAmount;
import dev.wareworks.core.requester.OrderShare;
import dev.wareworks.core.requester.RequesterLimits;
import dev.wareworks.core.requester.RequesterOrder;
import dev.wareworks.core.requester.RequesterOrders;
import dev.wareworks.core.requester.RequesterStatus;
import dev.wareworks.core.requester.VariantPick;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the Schematicannon Requester (issue #45, ADR-080, amended by ADR-081, {@code docs/warehouse-system.md}):
 * orders what the Schematicannon in front of it is missing at the terminal its Warehouse Remote is linked to, from stock
 * first and the rest made through the aisle's production patterns, a stack at a time and never twice. It moves no item:
 * the crane delivers into the terminal's buffer, and the player's own funnel and belt take the items on to a chest
 * beside the cannon.
 * <p>
 * <b>Polling, not ticking.</b> The work runs once every {@code requester.pollIntervalTicks} (a second by default) and
 * costs, when nothing is missing and nothing is ordered, one look at the cannon through {@link NoTicket} and a few
 * field reads ({@link CannonView}). With orders open it also reaches the terminal ({@link RemoteAccess#checkFromBlock},
 * no chunk ticket either), finds its controller and walks that terminal's open requests and its buffer once. Only when
 * an order is due does it look at the four other sides of the cannon (one requester per cannon), the cannon's material
 * list ({@link ChecklistNeed}), its attached inventories (a simulated insert), the buffer's room and the stock. Nothing
 * is scanned per tick, and nothing the requester reads is ever loaded or kept loaded by it.
 * <p>
 * <b>The order rule</b> (§7, §8): while the cannon waits for an item, an order for it is placed only when no order of
 * this requester for it is open in any phase, no matching stack lies in the buffer and fewer than
 * {@value RequesterLimits#MAX_ORDERS} orders are open ({@link RequesterOrders#lock}). It asks for the smallest of a
 * stack, what the schematic still needs, the room in the cannon's inventories, the room in the buffer and what the
 * stock and the patterns can give: the stock first, then at most what the schematic still needs made
 * ({@link OrderAmount#of}), through {@link WarehouseTerminalBlockEntity#requestForRequester}, a computer's request with
 * the made part capped. A strict requirement is ordered with exactly the reported components, a plain one as the stocked
 * variant {@link VariantPick} chooses, or, with none in stock, as the variant a pattern makes ({@link VariantPick#toMake}).
 * After an attempt to have something made that was refused or came back short, the next one waits
 * {@value RequesterLimits#MAKE_RETRY_POLLS} polls.
 * <p>
 * <b>Every order is followed</b> by request id and its own share, first in, first served ({@link OrderShare}): running,
 * then in the buffer, then the block's grace ({@link GraceBehaviour}) while the items travel the belt. A share that is
 * partly made also follows the root order of the plan it started: it stays running while that order is open, its
 * dead-man clock stands meanwhile (the production order's own timeout ends a stuck chain), and a root that fails takes
 * exactly what it still owed off the share. A running order whose live age reaches the dead-man switch
 * ({@code requester.orderTimeoutTicks}) gives back only its own outstanding share ({@code reduceRequestShare}, never a
 * cancel: a merged id would take a player's items down with it) and may be placed again. The same give-back runs when
 * the remote is taken out, the block is broken, or the cannon has stopped printing for two polls in a row; the made part
 * of it comes off the promise of its own root only, and production already running finishes into stock. A terminal that
 * cannot be reached then simply lets its requests complete.
 * <p>
 * <b>Identity.</b> The player who put the remote in is its owner: the remote's rules are checked with them at
 * insertion, and the claim check ({@code Level#mayInteract}) again before every order while they are online. No fake
 * player is ever built. The order itself carries no player, like a computer's (ADR-074), and nothing is remembered on
 * the terminal.
 * <p>
 * <b>State.</b> The remote, its owner, the orders and the material-list counter are saved, each read only from a full
 * save, so a schematic print over a standing requester changes nothing but the grace; a schematic never carries them
 * ({@link #writeSafe}). The status, the cannon's last signature, the variant picked, the poll times and the wait before
 * the next attempt to have something made are rebuilt on the first poll. Clients get the remote, for the cradle, and
 * the goggle summary while someone looks.
 */
public class SchematicannonRequesterBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation, GoggleObservers.Observable, Clearable {
    /** NBT key of the goggle summary in client packets. */
    public static final String SUMMARY_TAG = "GoggleSummary";
    /** NBT key of the remote in the slot, written only while there is one (saves and client packets). */
    public static final String REMOTE_TAG = "Remote";
    /** NBT key of the open orders: written by every full save, empty or not, so it marks one ({@link #isFullSave}). */
    public static final String ORDERS_TAG = "Orders";
    private static final String OWNER_TAG = "Owner";
    private static final String OWNER_NAME_TAG = "OwnerName";
    private static final String RUN_TAG = "Run";
    private static final String TIMEOUTS_TAG = "Timeouts";
    private static final String KEY = "Key";
    private static final String STRICT = "Strict";
    private static final String REQUEST = "Request";
    private static final String SHARE = "Share";
    private static final String AHEAD = "Ahead";
    private static final String AHEAD_DELIVERED = "AheadDelivered";
    private static final String PRODUCING = "Producing";
    private static final String ROOT = "Root";
    private static final String UNSURE = "Unsure";
    private static final String PHASE = "Phase";
    private static final String LIVE_AGE = "LiveAge";
    private static final String GRACE_UNTIL = "GraceUntil";
    private static final String COUNTS = "Counts";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";
    private static final String FINGERPRINT = "Fingerprint";
    private static final String REQUIRED = "Required";
    private static final String GATHERED = "Gathered";
    private static final String BLOCKS = "Blocks";
    private static final String LAST_REFRESH = "LastRefresh";
    private static final int TICKS_PER_SECOND = 20;

    private final RemoteSlot slot = new RemoteSlot(this);
    private GraceBehaviour grace;

    // --- saved ---------------------------------------------------------------------------------------------------
    @Nullable
    private UUID owner;
    private String ownerName = "";
    private final RequesterOrders<ItemKey, Item> orders = new RequesterOrders<>(ItemKey::getItem);
    private final ChecklistNeed<Item> checklist = new ChecklistNeed<>();
    private int timeouts;

    // --- rebuilt on the first poll -------------------------------------------------------------------------------
    private long nextPollTick;
    /** The game time of the last poll that reached the terminal and its controller, {@code -1} for none yet. */
    private long lastReachedTick = -1L;
    private int idlePolls;
    private RequesterStatus status = RequesterStatus.NO_REMOTE;
    private CannonView.Phase cannonPhase = CannonView.Phase.NO_CANNON;
    private ItemStack missing = ItemStack.EMPTY;
    private String cannonWord = "";
    private Item subject = Items.AIR;
    private int refusedDistance;
    private int refusedRange;
    @Nullable
    private CannonView.Signature signature;
    private CannonView.Requirement requirement = CannonView.Requirement.UNKNOWN;
    @Nullable
    private ItemKey picked;
    /** No attempt to have something made before this game time: the last one was refused or came back short. */
    private long makeRetryAt;
    /** Why the last attempt to have something made was refused, shown while the retry waits; {@code null} for none. */
    @Nullable
    private MakeRefusal makeRefusal;
    /** Why the chain for the missing item cannot be planned, at this poll ({@link RequesterStatus#CANNOT_MAKE}). */
    @Nullable
    private PlanRefusal planRefusal;
    /** The item {@link #planRefusal} is about, or the item the safety stop halted, at this poll. */
    private Item about = Items.AIR;
    /** Each running order's own delivered amount as of the last poll that reached the terminal, for the goggles. */
    private final Map<UUID, Integer> delivered = new HashMap<>();
    /**
     * What the root of each running order with a made part has not produced yet, as of the last poll that reached the
     * terminal, for the goggles ({@link OrderShare#beingMade}).
     */
    private final Map<UUID, Long> unmade = new HashMap<>();
    @Nullable
    private RequesterSummary.GaveUp gaveUp;
    private long gaveUpUntil;
    private boolean dirty;

    /** Derived goggle state; synced to clients, never authoritative and never saved. */
    private RequesterSummary summary = RequesterSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    public SchematicannonRequesterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The grace value box: the only behaviour, and the only setting a schematic carries. */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        grace = new GraceBehaviour(this);
        behaviours.add(grace);
    }

    // --- what it is ----------------------------------------------------------------------------------------------

    /** The direction from this block towards the cannon it serves. */
    public Direction facing() {
        return getBlockState().getOptionalValue(SchematicannonRequesterBlock.FACING).orElse(Direction.NORTH);
    }

    /** Where the cannon it serves stands: right in front. */
    public BlockPos cannonPos() {
        return worldPosition.relative(facing());
    }

    /** The remote in the slot, or an empty stack (a copy). */
    public ItemStack remote() {
        return slot.remote().copy();
    }

    /** The remote in the slot itself, for the renderer, which draws it every frame: never change it. */
    public ItemStack cradleStack() {
        return slot.remote();
    }

    /** Whether a remote is in the slot. */
    public boolean hasRemote() {
        return slot.isFilled();
    }

    /** The terminal the remote in the slot is linked to, or {@code null} without one. */
    @Nullable
    public RemoteLink link() {
        return WarehouseRemoteItem.link(slot.remote());
    }

    /** The player who put the remote in, empty without a remote. */
    public Optional<UUID> owner() {
        return Optional.ofNullable(owner);
    }

    /** What the requester did or waited for at its last poll (server) or as last synced (client). */
    public RequesterStatus status() {
        return level != null && level.isClientSide ? summary.status() : status;
    }

    /** What the cannon in front did at the last poll (server). */
    public CannonView.Phase cannonPhase() {
        return cannonPhase;
    }

    /** The open orders, oldest first (server). */
    public List<RequesterOrder<ItemKey>> orders() {
        return orders.all();
    }

    /** Whether an order of this requester is open in any phase (server). */
    public boolean hasOpenOrders() {
        return !orders.isEmpty();
    }

    /** What this requester ordered of {@code item} since the cannon's material list was made (server). */
    public int orderedSinceList(Item item) {
        return checklist.ordered(item);
    }

    /** How many orders the dead-man switch gave up since the remote went in (server). */
    public int timeouts() {
        return timeouts;
    }

    /** The grace in seconds. */
    public int graceSeconds() {
        return grace == null ? RequesterLimits.DEFAULT_GRACE_SECONDS : grace.seconds();
    }

    /** Server: sets the grace in seconds (clamped), as the value box does. */
    public void setGraceSeconds(int seconds) {
        if (grace != null)
            grace.setSeconds(seconds);
    }

    /** The goggle data as of the last observation (server) or sync (client). */
    public RequesterSummary summary() {
        return summary;
    }

    /** Server: the cannon in front changed, so the next tick polls rather than waiting out the interval. */
    public void pollSoon() {
        nextPollTick = 0L;
    }

    // --- the poll ------------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || isVirtual() || isRemoved() || !(level instanceof ServerLevel server))
            return;
        long now = server.getGameTime();
        if (now < nextPollTick)
            return;
        int pollTicks = RequesterLimits.clampPoll(WareworksConfig.requesterPollIntervalTicks());
        nextPollTick = now + pollTicks;
        dirty = false;
        RequesterStatus next = poll(server, now, pollTicks);
        show(next == null ? RequesterStatus.NO_REMOTE : next);
        if (dirty)
            setChanged();
    }

    /** One poll (see the class comment); answers the status to show. */
    @Nullable
    private RequesterStatus poll(ServerLevel server, long now, int pollTicks) {
        CannonView cannon = CannonView.read(server, cannonPos());
        cannonPhase = cannon.phase();
        missing = cannon.missing();
        cannonWord = cannonPhase == CannonView.Phase.BLOCKED ? cannon.statusWord() : "";
        subject = Items.AIR;
        planRefusal = null;
        about = Items.AIR;
        refusedDistance = 0;
        refusedRange = 0;
        if (gaveUp != null && now >= gaveUpUntil)
            gaveUp = null;
        if (!slot.isFilled()) {
            idlePolls = 0;
            return RequesterStatus.NO_REMOTE;
        }
        idlePolls = cannonPhase.isIdle() ? idlePolls + 1 : 0;
        RequesterStatus cannonStatus = cannonPhase.status().orElse(null);
        boolean due = cannonPhase == CannonView.Phase.MISSING;
        if (orders.isEmpty() && !due)
            return cannonStatus;

        // The terminal, read without a chunk ticket, and its warehouse.
        RemoteAccess.RemoteCheck check = RemoteAccess.checkFromBlock(server, worldPosition, link(), null);
        WarehouseTerminalBlockEntity terminal = null;
        WarehouseControllerBlockEntity controller = null;
        RequesterStatus linkStatus;
        if (check instanceof RemoteAccess.RemoteCheck.Refused refused) {
            linkStatus = linkStatusOf(refused);
        } else {
            terminal = ((RemoteAccess.RemoteCheck.Reached) check).terminal();
            controller = terminal.controller().orElse(null);
            linkStatus = controller == null ? RequesterStatus.NO_CONTROLLER : null;
        }

        Collection<ItemKey> buffered = List.of();
        if (controller != null) {
            Function<UUID, Optional<RequesterOrders.Request>> requests = requestsOf(controller, terminal.getBlockPos(),
                    orders.hasRunning());
            buffered = terminal.bufferedItems().keys();
            advance(now, pollTicks, requests, buffered, controller);
            if (idlePolls >= RequesterLimits.IDLE_POLLS_BEFORE_GIVE_BACK && !orders.isEmpty())
                giveBack(controller, requests);
        } else if (idlePolls >= RequesterLimits.IDLE_POLLS_BEFORE_GIVE_BACK && !orders.isEmpty()) {
            // The terminal cannot be reached to give anything back: the requests simply complete.
            dropOrders();
        }

        if (!due) {
            // A cannon that needs nothing now, with orders still on their way: a red cannon line or a broken link
            // is what a player has to see; otherwise the lamp says the orders are under way.
            if (cannonStatus != null && cannonStatus.isRefused())
                return cannonStatus;
            if (linkStatus != null)
                return linkStatus;
            RequesterStatus working = workingStatus();
            return working != null ? working : cannonStatus;
        }
        if (secondRequester(server))
            return RequesterStatus.SECOND_REQUESTER;
        if (linkStatus != null)
            return linkStatus;
        ServerPlayer ownerPlayer = owner == null ? null : server.getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer != null && !terminal.getLevel().mayInteract(ownerPlayer, terminal.getBlockPos()))
            return RequesterStatus.LINK_NOT_ALLOWED;
        return decide(now, pollTicks, cannon, terminal, controller, buffered);
    }

    /**
     * The open requests of the terminal by id, read once per poll. With {@code making}, also which of them an open
     * production order makes items for (ADR-081): one walk over the controller's open production orders, in memory,
     * only while some order runs.
     */
    private static Function<UUID, Optional<RequesterOrders.Request>> requestsOf(
            WarehouseControllerBlockEntity controller, BlockPos terminalPos, boolean making) {
        Set<UUID> made = new HashSet<>();
        if (making) {
            for (ProductionOrder<ItemKey, RackPosition> order : controller.openProductionOrders())
                order.backingRequest().ifPresent(made::add);
        }
        Map<UUID, RequesterOrders.Request> open = new LinkedHashMap<>();
        for (RetrievalRequest<ItemKey, BlockPos> request : controller.requestsFor(terminalPos))
            open.put(request.id(), new RequesterOrders.Request(request.requested(), request.delivered(),
                    made.contains(request.id())));
        return id -> Optional.ofNullable(open.get(id));
    }

    /**
     * Where the root production order {@code id} stands (ADR-081): open, completed, failed with what it still owed its
     * request, or gone. Asked only for orders with a made part; an in-memory lookup.
     */
    private static RequesterOrders.RootState rootOf(WarehouseControllerBlockEntity controller, UUID id) {
        return controller.productionOrder(id)
                .map(order -> order.isOpen() ? RequesterOrders.RootState.OPEN
                        : order.state() == ProductionOrderState.COMPLETE ? RequesterOrders.RootState.MADE
                        : RequesterOrders.RootState.failed(order.unfulfilledPromise()))
                .orElse(RequesterOrders.RootState.GONE);
    }

    /**
     * Moves the orders on, on a poll that reached the terminal and its controller: their live age grows only then
     * ({@link RequesterLimits#liveStep}), and not while production makes items for their request; an order the
     * dead-man switch gives up returns its own outstanding share; a made part that fell short is taken off the counter.
     */
    private void advance(long now, int pollTicks, Function<UUID, Optional<RequesterOrders.Request>> requests,
            Collection<ItemKey> buffered, WarehouseControllerBlockEntity controller) {
        int step = RequesterLimits.liveStep(now, lastReachedTick, pollTicks);
        lastReachedTick = now;
        if (orders.isEmpty())
            return;
        RequesterOrders.Advance<ItemKey> advance = orders.advance(now, step, requests, id -> rootOf(controller, id),
                buffered, RequesterLimits.graceTicks(graceSeconds()), WareworksConfig.requesterOrderTimeoutTicks());
        for (RequesterOrders.Shortfall<ItemKey> shortfall : advance.shortfalls()) {
            RequesterOrder<ItemKey> order = shortfall.order();
            // The controller already took the same amount off the request (rule R3, read from the order's own root).
            checklist.subtract(order.key().getItem(), shortfall.cut());
            Wareworks.LOGGER.debug("Schematicannon requester at {}: production for {} of request {} fell short by {}",
                    worldPosition, order.key(), order.requestId(), shortfall.cut());
        }
        for (RequesterOrders.GivenBack<ItemKey> given : advance.gaveUp()) {
            RequesterOrder<ItemKey> order = given.order();
            // Only what is provably the order's own (OrderShare#returnable); 0 leaves the request as it is.
            if (given.outstanding() > 0)
                controller.reduceRequestShare(order.requestId(), given.outstanding(), given.producing(),
                        given.root());
            checklist.subtract(order.key().getItem(), given.outstanding());
            timeouts++;
            gaveUp = new RequesterSummary.GaveUp(order.key().getItem(), order.share(), order.liveAge());
            gaveUpUntil = now + RequesterLimits.GAVE_UP_SHOWN_TICKS;
            Wareworks.LOGGER.debug("Schematicannon requester at {} gave up {} x{} of request {} after {} live ticks",
                    worldPosition, order.key(), given.outstanding(), order.requestId(), order.liveAge());
        }
        if (advance.changed())
            dirty = true;
        delivered.clear();
        unmade.clear();
        for (RequesterOrder<ItemKey> order : orders.all()) {
            if (order.isRunning())
                requests.apply(order.requestId()).ifPresent(request -> delivered.put(order.requestId(),
                        OrderShare.ownDelivered(request.requested(), request.delivered(), order.ahead(),
                                order.share())));
            if (order.isMaking())
                order.root().ifPresent(root -> unmade.put(order.requestId(), controller.productionOrder(root)
                        .map(made -> made.isOpen() ? made.unfulfilledPromise() : 0L).orElse(0L)));
        }
    }

    /**
     * Gives every open order's outstanding share back and forgets the orders (§9.3). The made parts are settled against
     * their roots first ({@link RequesterOrders#settle}), as a poll would: a root that failed since the last poll has
     * already cost the merged request what it owed, and giving that part back as well would take it off a player's
     * click merged in the same request (rule R3). The made part of a share comes off the promise of its own root only,
     * never a merged player's (rule R1 of ADR-081: no production order is cancelled; it finishes into stock).
     */
    private void giveBack(WarehouseControllerBlockEntity controller,
            Function<UUID, Optional<RequesterOrders.Request>> requests) {
        for (RequesterOrders.Shortfall<ItemKey> shortfall : orders.settle(requests, id -> rootOf(controller, id)))
            checklist.subtract(shortfall.order().key().getItem(), shortfall.cut());
        for (RequesterOrders.GivenBack<ItemKey> given : orders.giveBack(requests)) {
            controller.reduceRequestShare(given.order().requestId(), given.outstanding(), given.producing(),
                    given.root());
            checklist.subtract(given.order().key().getItem(), given.outstanding());
        }
        delivered.clear();
        unmade.clear();
        dirty = true;
    }

    /** Forgets every order without giving anything back: their requests complete normally. */
    private void dropOrders() {
        orders.clear();
        delivered.clear();
        unmade.clear();
        dirty = true;
    }

    /** The phase of the most pressing open order as a status, or {@code null} without orders. */
    @Nullable
    private RequesterStatus workingStatus() {
        RequesterStatus found = null;
        for (RequesterOrder<ItemKey> order : orders.all())
            found = RequesterStatus.first(found, RequesterOrders.statusOf(order));
        return found;
    }

    /**
     * One requester per cannon (§2): another requester on one of the cannon's other sides, facing it and holding a
     * remote, serves it when it stands at a lower position <b>or still has orders open</b>, and this one stays idle.
     * Two requesters linked to different terminals would each pass their own locks and order the same item twice; the
     * second clause covers a requester at a lower position that gets its remote while the other one's order is still
     * on its way, which its own locks cannot see. Both then wait, the running order is still followed, and once it is
     * through the one at the lower position takes over. Deterministic, and no message passes between the blocks.
     */
    private boolean secondRequester(ServerLevel server) {
        BlockPos cannon = cannonPos();
        for (Direction side : Direction.values()) {
            BlockPos other = cannon.relative(side);
            if (other.equals(worldPosition))
                continue;
            BlockState state = NoTicket.blockState(server, other);
            if (state == null || !state.is(getBlockState().getBlock())
                    || !other.relative(state.getValue(SchematicannonRequesterBlock.FACING)).equals(cannon))
                continue;
            SchematicannonRequesterBlockEntity first = NoTicket.blockEntity(server, other,
                    SchematicannonRequesterBlockEntity.class);
            if (first != null && first.hasRemote()
                    && (other.asLong() < worldPosition.asLong() || first.hasOpenOrders()))
                return true;
        }
        return false;
    }

    /**
     * The order decision for a cannon that misses an item, with the terminal and its warehouse reached (§7, §8; the
     * made part since ADR-081).
     * <p>
     * <b>Stock first.</b> A stocked variant is ordered as before, and the controller makes the rest of it only when a
     * pattern makes exactly that key; otherwise it grants the stock. A variant a pattern makes is chosen only when
     * automation may take none of any variant, the plain one first ({@link VariantPick#toMake}). A strict requirement
     * always uses its exact key, which the terminal refuses when it is neither held nor made by a pattern. What may be
     * made is capped by what the schematic still needs ({@link ChecklistNeed.Need#makeCap()}). After an attempt that was
     * refused or came back short, the next one waits {@value RequesterLimits#MAKE_RETRY_POLLS} polls, and meanwhile the
     * refusal that attempt got is shown rather than a plain "out of stock"; it is forgotten once no production station
     * is left or a retry finds no pattern that makes the item.
     */
    private RequesterStatus decide(long now, int pollTicks, CannonView cannon, WarehouseTerminalBlockEntity terminal,
            WarehouseControllerBlockEntity controller, Collection<ItemKey> buffered) {
        CannonView.Signature seen = cannon.signature();
        if (!seen.equals(signature)) {
            signature = seen;
            requirement = cannon.requirement();
            picked = null;
            makeRetryAt = 0L;
            makeRefusal = null;
        }
        ItemKey missingKey = seen.key();
        Item item = missingKey.getItem();
        subject = item;
        boolean strict = requirement.strict();
        Optional<RequesterStatus> locked = orders.lock(missingKey, strict, buffered);
        if (locked.isPresent())
            return locked.get();

        ItemKey key = strict ? missingKey : variant(controller, item);
        ItemKey sized = key == null ? missingKey : key;
        int stack = Math.max(1, sized.getMaxStackSize());
        long available = key == null ? 0L : controller.availableTo(StockAccess.AUTOMATION, key);
        long availableToPlayer = key != null ? controller.availableTo(StockAccess.PLAYER, key)
                : VariantPick.pick(controller.stockIndex().keys(), candidate -> candidate.getItem() == item,
                        ItemKey::isPlain,
                        candidate -> controller.availableTo(StockAccess.PLAYER, candidate), ItemKey.ORDER)
                        .map(candidate -> controller.availableTo(StockAccess.PLAYER, candidate)).orElse(0L);
        long cannonRoom = cannon.roomFor(sized, stack);
        long bufferRoom = terminal.roomFor(sized);
        int maxRequest = Math.max(1, WareworksConfig.maxTerminalRequestAmount());
        // What to have made, if anything: the stocked key itself (the controller makes the rest of it only if a pattern
        // makes exactly that key), or with nothing stocked the variant a pattern makes. The patterns are walked only
        // with room on both ends and no retry wait, and never at all without a production station.
        boolean makeAllowed = now >= makeRetryAt;
        ItemKey orderKey = key;
        if (key == null && makeAllowed && cannonRoom > 0 && bufferRoom > 0) {
            Set<ItemKey> producible = controller.productionStations().isEmpty() ? Set.of()
                    : controller.producibleKeys();
            orderKey = VariantPick.toMake(producible, candidate -> candidate.getItem() == item,
                    ItemKey::isPlain, ItemKey.ORDER).orElse(null);
            if (orderKey == null) {
                makeRetryAt = now + RequesterLimits.makeRetryTicks(pollTicks);
                // No pattern makes it any more (removed, or its station broken): an earlier refusal no longer holds.
                makeRefusal = null;
            }
        }
        boolean makeable = makeAllowed && orderKey != null;
        // The material list is only consulted, and perhaps refreshed, once everything outside it would allow an order.
        int need = 1;
        int makeCap = 0;
        if (cannonRoom > 0 && bufferRoom > 0 && (available > 0 || makeable)) {
            ChecklistNeed.Need needed = checklist.needOf(item, stack, seen.count(), requirement.damage(),
                    cannon.checklist(), orders.liveShares(), now, WareworksConfig.requesterChecklistRefreshTicks(),
                    cannon::refreshedChecklist);
            need = needed.amount();
            makeCap = makeable ? needed.makeCap() : 0;
            dirty = true;
        }
        OrderAmount amount = OrderAmount.of(need, stack, cannonRoom, bufferRoom, available, availableToPlayer,
                maxRequest, makeCap);
        if (amount.refusal().isPresent()) {
            RequesterStatus refusal = amount.refusal().get();
            // While a refused attempt to have it made waits for its retry, its reason says more than "out of stock" —
            // but only while it can still hold: without a production station nothing is made, refused or not.
            if (makeRefusal != null && now < makeRetryAt && !controller.productionStations().isEmpty()
                    && (refusal == RequesterStatus.OUT_OF_STOCK || refusal == RequesterStatus.RESERVED)) {
                planRefusal = makeRefusal.plan();
                about = makeRefusal.about();
                return makeRefusal.status();
            }
            return refusal;
        }

        WarehouseTerminalBlockEntity.RequesterRequest placed = terminal.requestForRequester(orderKey, amount.amount(),
                amount.maxMade());
        RequestResult result = placed.result();
        if (amount.maxMade() > 0 && (!result.isAccepted() || result.producing() < amount.maxMade())) {
            makeRetryAt = now + RequesterLimits.makeRetryTicks(pollTicks);
            makeRefusal = null;
            if (!result.isAccepted()) {
                RequesterStatus refused = statusOf(result);
                makeRefusal = new MakeRefusal(refused, planRefusal, about);
            }
        }
        if (result.request().isEmpty())
            return statusOf(result);
        RetrievalRequest<ItemKey, BlockPos> request = result.request().get();
        int share = result.granted();
        Optional<UUID> root = placed.productionRoot();
        int producing = root.isPresent() ? Math.min(result.producing(), share) : 0;
        orders.place(orderKey, strict, request.id(), share, request.requested() - share, request.delivered(),
                producing, producing > 0 ? root : Optional.empty());
        checklist.add(item, share);
        dirty = true;
        return producing > 0 ? RequesterStatus.MAKING : RequesterStatus.ORDERED;
    }

    /**
     * Why the last attempt to have the missing item made was refused, with the plan refusal and its item for
     * {@link RequesterStatus#CANNOT_MAKE} and the stopped item for {@link RequesterStatus#MAKING_STOPPED}; transient,
     * shown while the retry waits.
     */
    private record MakeRefusal(RequesterStatus status, @Nullable PlanRefusal plan, Item about) {
    }

    /**
     * The variant of {@code item} to order for a plain requirement ({@link VariantPick}), kept for the current wait and
     * picked again once it has run dry; {@code null} when automation may take none of any variant.
     */
    @Nullable
    private ItemKey variant(WarehouseControllerBlockEntity controller, Item item) {
        if (picked != null && picked.getItem() == item && controller.availableTo(StockAccess.AUTOMATION, picked) > 0)
            return picked;
        picked = VariantPick.pick(controller.stockIndex().keys(), candidate -> candidate.getItem() == item,
                ItemKey::isPlain,
                candidate -> controller.availableTo(StockAccess.AUTOMATION, candidate), ItemKey.ORDER).orElse(null);
        return picked;
    }

    /**
     * The requester status of a terminal's refusal (ADR-081 §6.1): not in stock is {@link RequesterStatus#CANNOT_MAKE}
     * when a chain could not be planned for a reason a player can act on (this also remembers the plan refusal and its
     * item for the goggles), the safety stop is {@link RequesterStatus#MAKING_STOPPED} (remembering the stopped item,
     * which may be an ingredient further down the chain), full production order slots are
     * {@link RequesterStatus#PRODUCTION_BUSY}; the terminal's caps pass by themselves.
     */
    private RequesterStatus statusOf(RequestResult result) {
        return switch (result.rejection().orElse(RequestRejection.NOT_IN_STOCK)) {
            case NO_CONTROLLER -> RequesterStatus.NO_CONTROLLER;
            case NOT_IN_STOCK -> {
                PlanRefusal refusal = result.refusal().orElse(null);
                if (refusal == null || refusal == PlanRefusal.NO_PATTERN || refusal == PlanRefusal.PAUSED
                        || refusal == PlanRefusal.ORDERS_BUSY)
                    yield RequesterStatus.OUT_OF_STOCK;
                planRefusal = refusal;
                about = result.about().map(ItemKey::getItem).orElse(Items.AIR);
                yield RequesterStatus.CANNOT_MAKE;
            }
            case RESERVED -> RequesterStatus.RESERVED;
            case PRODUCTION_PAUSED -> {
                // The controller names the item the safety stop halted: the product, or an ingredient of its chain.
                about = result.about().map(ItemKey::getItem).orElse(Items.AIR);
                yield RequesterStatus.MAKING_STOPPED;
            }
            case PRODUCTION_BUSY -> RequesterStatus.PRODUCTION_BUSY;
            default -> RequesterStatus.TERMINAL_BUSY;
        };
    }

    /** The requester status of one of the remote's refusals, keeping the numbers the out-of-range line names. */
    private RequesterStatus linkStatusOf(RemoteAccess.RemoteCheck.Refused refused) {
        return switch (refused.langKey()) {
            case WareworksLang.REMOTE_NOT_LINKED -> RequesterStatus.NO_REMOTE;
            case WareworksLang.REMOTE_OTHER_DIMENSION -> RequesterStatus.LINK_OTHER_DIMENSION;
            case WareworksLang.REMOTE_OUT_OF_RANGE -> {
                Object[] args = refused.args();
                refusedDistance = args.length > 0 && args[0] instanceof Number distance ? distance.intValue() : 0;
                refusedRange = args.length > 1 && args[1] instanceof Number range ? range.intValue() : 0;
                yield RequesterStatus.LINK_OUT_OF_RANGE;
            }
            case WareworksLang.REMOTE_NOT_LOADED -> RequesterStatus.LINK_NOT_LOADED;
            case WareworksLang.REMOTE_NOT_ALLOWED -> RequesterStatus.LINK_NOT_ALLOWED;
            default -> RequesterStatus.LINK_GONE;
        };
    }

    /** Remembers {@code next} and writes the two lamps, only when one of them really changed. */
    private void show(RequesterStatus next) {
        status = next;
        BlockState state = getBlockState();
        BlockState wanted = state;
        if (state.hasProperty(SchematicannonRequesterBlock.LIT))
            wanted = wanted.setValue(SchematicannonRequesterBlock.LIT, next.isLit());
        if (state.hasProperty(SchematicannonRequesterBlock.REFUSED))
            wanted = wanted.setValue(SchematicannonRequesterBlock.REFUSED, next.isRefused());
        if (wanted != state && level != null)
            // UPDATE_CLIENTS and nothing else: the lamps are what a player sees, not something neighbours react to.
            level.setBlock(worldPosition, wanted, Block.UPDATE_CLIENTS);
    }

    // --- the remote slot -----------------------------------------------------------------------------------------

    /**
     * Server: {@code player} clicked with a Warehouse Remote in {@code hand} (§3). A real player only — the block has
     * already passed a fake one by. Refused with a line on the action bar, the remote staying in the hand: an unlinked
     * remote, a slot that is already filled (no swap), and every one of the remote's own rules measured from this block
     * with this player ({@link RemoteAccess#checkFromBlock}). Otherwise one remote goes in (a copy in creative), the
     * player becomes the owner, and a cannon that skips missing blocks is pointed out once in chat.
     *
     * @return whether the remote went in
     */
    public boolean putIn(ServerPlayer player, InteractionHand hand) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(hand, "hand");
        if (!(level instanceof ServerLevel server) || isRemoved())
            return false;
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof WarehouseRemoteItem))
            return false;
        RemoteLink link = WarehouseRemoteItem.link(held);
        if (link == null) {
            tell(player, WareworksLang.REMOTE_NOT_LINKED);
            return false;
        }
        if (slot.isFilled()) {
            tell(player, WareworksLang.REQUESTER_OCCUPIED);
            return false;
        }
        RemoteAccess.RemoteCheck check = RemoteAccess.checkFromBlock(server, worldPosition, link, player);
        if (check instanceof RemoteAccess.RemoteCheck.Refused refused) {
            player.displayClientMessage(refused.message(), true);
            return false;
        }
        ItemStack inserted = player.getAbilities().instabuild ? held.copyWithCount(1) : held.split(1);
        startAfresh();
        owner = player.getUUID();
        ownerName = player.getGameProfile().getName();
        slot.setStackInSlot(0, inserted);
        BlockPos terminal = link.terminal().pos();
        tell(player, WareworksLang.REQUESTER_LINKED, terminal.getX(), terminal.getY(), terminal.getZ());
        AllSoundEvents.CONTROLLER_PUT.playOnServer(server, worldPosition);
        // The action bar now holds the link line, so the one hint a hand-placed cannon needs goes into chat.
        if (CannonView.read(server, cannonPos()).phase() == CannonView.Phase.SKIP_MISSING)
            player.sendSystemMessage(WareworksLang.translateDirect(WareworksLang.REQUESTER_SKIP_MISSING_WARNING,
                    Component.translatable(RequesterLines.SKIP_MISSING_OPTION)));
        return true;
    }

    /**
     * Server: {@code player} sneaks and clicks with an empty main hand: the open orders are given back, the remote goes
     * into the hand (or the inventory), and the owner, the orders and the counts are forgotten (§3).
     *
     * @return whether a remote came out
     */
    public boolean takeOut(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved() || !slot.isFilled())
            return false;
        giveBackEverything();
        ItemStack remote = slot.remote().copy();
        slot.setStackInSlot(0, ItemStack.EMPTY);
        startAfresh();
        if (player.getMainHandItem().isEmpty())
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        else
            player.getInventory().placeItemBackInInventory(remote);
        tell(player, WareworksLang.REQUESTER_REMOVED);
        AllSoundEvents.CONTROLLER_TAKE.playOnServer(level, worldPosition);
        return true;
    }

    /** Server: an empty-hand click shows what the requester does now on the action bar. */
    public void showStatus(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide)
            return;
        LangBuilder line = RequesterLines.actionBar(buildSummary(level.getGameTime()));
        player.displayClientMessage(line.component(), true);
    }

    /**
     * Server: puts {@code remote} into an empty slot without an owner and without any check, for scenes that only show
     * the block — Ponder and the visual harness. A requester filled this way orders like any other, but without an
     * owner the claim check before an order is skipped, so nothing a player does reaches here.
     *
     * @return whether it went in
     */
    public boolean showRemote(ItemStack remote) {
        Objects.requireNonNull(remote, "remote");
        if (slot.isFilled() || !(remote.getItem() instanceof WarehouseRemoteItem))
            return false;
        startAfresh();
        slot.setStackInSlot(0, remote.copyWithCount(1));
        return true;
    }

    /** Forgets everything that belonged to the remote that was in the slot; the next tick polls. */
    private void startAfresh() {
        owner = null;
        ownerName = "";
        orders.clear();
        checklist.clear();
        timeouts = 0;
        gaveUp = null;
        delivered.clear();
        unmade.clear();
        signature = null;
        picked = null;
        makeRetryAt = 0L;
        makeRefusal = null;
        idlePolls = 0;
        lastReachedTick = -1L;
        nextPollTick = 0L;
        setChanged();
    }

    /**
     * Gives back every open order's outstanding share at the terminal, if it can be reached now; otherwise the orders
     * are only forgotten and their requests complete normally, which is bounded and conserves every item.
     */
    private void giveBackEverything() {
        if (orders.isEmpty())
            return;
        if (level instanceof ServerLevel server
                && RemoteAccess.checkFromBlock(server, worldPosition, link(), null)
                        instanceof RemoteAccess.RemoteCheck.Reached reached) {
            Optional<WarehouseControllerBlockEntity> controller = reached.terminal().controller();
            if (controller.isPresent()) {
                giveBack(controller.get(), requestsOf(controller.get(), reached.terminal().getBlockPos(), false));
                return;
            }
        }
        dropOrders();
    }

    private static void tell(ServerPlayer player, String relativeKey, Object... args) {
        player.displayClientMessage(WareworksLang.translateDirect(relativeKey, args), true);
    }

    // --- goggles -------------------------------------------------------------------------------------------------

    /** A player looks at the requester through goggles (server): rebuild the summary and sync a change (throttled). */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        RequesterSummary next = buildSummary(level.getGameTime());
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    /** The summary of what the requester knows now (server). */
    private RequesterSummary buildSummary(long now) {
        List<RequesterSummary.Row> rows = new ArrayList<>();
        for (RequesterOrder<ItemKey> order : orders.all()) {
            int own = order.isRunning() ? delivered.getOrDefault(order.requestId(), 0) : order.share();
            int graceLeft = order.phase() == RequesterOrder.Phase.GRACE
                    ? (int) Math.max(0L, (order.graceUntil() - now + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND) : 0;
            // What production still owes: never more than is undelivered, and down run by run (ADR-081).
            int made = order.isMaking() ? OrderShare.beingMade(order.share(), own, order.producing(),
                    unmade.getOrDefault(order.requestId(), Long.MAX_VALUE)) : 0;
            rows.add(new RequesterSummary.Row(order.key().getItem(), order.phase(), order.share(), own,
                    order.liveAge(), graceLeft, made, order.isMaking()));
        }
        boolean filled = slot.isFilled();
        return new RequesterSummary(filled ? status : RequesterStatus.NO_REMOTE, cannonPhase,
                missing.isEmpty() ? Items.AIR : missing.getItem(), missing.getCount(), cannonWord, subject,
                refusedDistance, refusedRange, filled ? ownerName : "", rows,
                gaveUp != null && now < gaveUpUntil ? gaveUp : null, timeouts,
                filled && status == RequesterStatus.CANNOT_MAKE ? planRefusal : null,
                filled && (status == RequesterStatus.CANNOT_MAKE || status == RequesterStatus.MAKING_STOPPED) ? about
                        : Items.AIR);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        RequesterSummary shown = summary;
        WareworksLang.translate(WareworksLang.GOGGLES_SCHEMATICANNON_REQUESTER).forGoggles(tooltip);
        for (LangBuilder line : RequesterLines.goggles(shown, link()))
            line.forGoggles(tooltip, 1);
        return true;
    }

    // --- lifecycle -----------------------------------------------------------------------------------------------

    /**
     * Real removal (ADR-013: drops only here): the open orders are given back first, then the remote is dropped where
     * the block stood. A wrench pickup goes the same way.
     */
    @Override
    public void destroy() {
        super.destroy();
        if (level == null || level.isClientSide)
            return;
        giveBackEverything();
        ItemStack remote = slot.remote();
        if (!remote.isEmpty()) {
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                    remote.copy());
            slot.load(ItemStack.EMPTY);
        }
    }

    /**
     * {@link Clearable}: commands ({@code /setblock}, {@code /fill}) and structure placement clear the block before
     * they replace it. The open orders are given back here, while the remote still says which terminal to give them
     * back to — {@link #destroy}, which runs after this, finds the slot empty and could only forget them — and then the
     * slot is emptied without a drop.
     */
    @Override
    public void clearContent() {
        giveBackEverything();
        slot.load(ItemStack.EMPTY);
    }

    // --- persistence and sync ------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (slot.isFilled())
            tag.put(REMOTE_TAG, slot.remote().save(registries));
        if (clientPacket) {
            CompoundTag summaryTag = new CompoundTag();
            summary.write(summaryTag);
            tag.put(SUMMARY_TAG, summaryTag);
            return;
        }
        if (owner != null) {
            tag.putUUID(OWNER_TAG, owner);
            tag.putString(OWNER_NAME_TAG, ownerName);
        }
        tag.put(ORDERS_TAG, writeOrders(registries));
        tag.put(RUN_TAG, writeRun());
        if (timeouts > 0)
            tag.putInt(TIMEOUTS_TAG, timeouts);
    }

    /**
     * What a <b>schematic</b> carries along ({@code PartialSafeNBT}): the grace, and only when it is not the default
     * ({@link GraceBehaviour#write}). Never the remote, its owner or the orders — a printed requester starts empty, and
     * a requester at its defaults hands a print nothing at all.
     */
    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            slot.load(readRemote(tag, registries));
            summary = RequesterSummary.read(tag.getCompound(SUMMARY_TAG));
            return;
        }
        // A tag that is no full save is a schematic print landing on a requester that is already standing: it says
        // nothing about the remote, the owner or the orders, which every save writes and a print never does.
        if (!isFullSave(tag))
            return;
        slot.load(readRemote(tag, registries));
        owner = tag.hasUUID(OWNER_TAG) ? tag.getUUID(OWNER_TAG) : null;
        ownerName = owner == null ? "" : tag.getString(OWNER_NAME_TAG);
        orders.restore(readOrders(tag.getList(ORDERS_TAG, Tag.TAG_COMPOUND), registries));
        readRun(tag.getCompound(RUN_TAG));
        timeouts = Math.max(0, tag.getInt(TIMEOUTS_TAG));
        delivered.clear();
        unmade.clear();
        signature = null;
        picked = null;
        makeRetryAt = 0L;
        makeRefusal = null;
        lastReachedTick = -1L;
        nextPollTick = 0L;
    }

    /** Whether {@code tag} is a full save rather than a schematic print's settings ({@link #ORDERS_TAG}). */
    private static boolean isFullSave(CompoundTag tag) {
        return tag.contains(ORDERS_TAG, Tag.TAG_LIST);
    }

    private static ItemStack readRemote(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(REMOTE_TAG, Tag.TAG_COMPOUND))
            return ItemStack.EMPTY;
        return ItemStack.parse(registries, tag.getCompound(REMOTE_TAG)).orElse(ItemStack.EMPTY);
    }

    private ListTag writeOrders(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (RequesterOrder<ItemKey> order : orders.all()) {
            CompoundTag entry = new CompoundTag();
            order.key().saveTo(entry, KEY, registries);
            entry.putBoolean(STRICT, order.strict());
            entry.putUUID(REQUEST, order.requestId());
            entry.putInt(SHARE, order.share());
            entry.putInt(AHEAD, order.ahead());
            if (order.aheadDelivered() > 0)
                entry.putInt(AHEAD_DELIVERED, order.aheadDelivered());
            // Only for a made part (ADR-081), so a share from stock saves exactly as in 0.20.0-beta.
            if (order.producing() > 0) {
                entry.putInt(PRODUCING, order.producing());
                entry.putUUID(ROOT, order.root().orElseThrow());
            }
            if (order.unsure() > 0)
                entry.putInt(UNSURE, order.unsure());
            entry.putString(PHASE, order.phase().serializedName());
            entry.putInt(LIVE_AGE, order.liveAge());
            if (order.graceUntil() != 0L)
                entry.putLong(GRACE_UNTIL, order.graceUntil());
            list.add(entry);
        }
        return list;
    }

    /**
     * The saved orders; an entry without a key, a request id or a valid phase and share is skipped. A made part is read
     * only with both its amount and its root, clamped to the share; otherwise the order reads as one from stock, which
     * is what every 0.20.0-beta save holds. The unsure made part is clamped to what the made part leaves of the share.
     * Never throws.
     */
    private static List<RequesterOrder<ItemKey>> readOrders(ListTag list, HolderLookup.Provider registries) {
        List<RequesterOrder<ItemKey>> read = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Optional<ItemKey> key = ItemKey.loadFrom(entry, KEY, registries);
            Optional<RequesterOrder.Phase> phase = RequesterOrder.Phase.byName(entry.getString(PHASE));
            int share = entry.getInt(SHARE);
            if (key.isEmpty() || phase.isEmpty() || !entry.hasUUID(REQUEST) || share < 1)
                continue;
            int ahead = Math.max(0, entry.getInt(AHEAD));
            boolean made = entry.getInt(PRODUCING) > 0 && entry.hasUUID(ROOT);
            int producing = made ? Math.min(share, entry.getInt(PRODUCING)) : 0;
            int unsure = Math.max(0, Math.min(share - producing, entry.getInt(UNSURE)));
            read.add(new RequesterOrder<>(key.get(), entry.getBoolean(STRICT), entry.getUUID(REQUEST), share, ahead,
                    Math.max(0, Math.min(ahead, entry.getInt(AHEAD_DELIVERED))), producing,
                    made ? Optional.of(entry.getUUID(ROOT)) : Optional.empty(), unsure, phase.get(),
                    Math.max(0, entry.getInt(LIVE_AGE)), entry.getLong(GRACE_UNTIL)));
        }
        return read;
    }

    /** The material-list counter: what was ordered per item, the list it was counted against, the last refresh. */
    private CompoundTag writeRun() {
        CompoundTag run = new CompoundTag();
        ListTag counts = new ListTag();
        for (Map.Entry<Item, Integer> entry : checklist.counts().entrySet()) {
            CompoundTag count = new CompoundTag();
            count.putString(ITEM, BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            count.putInt(COUNT, entry.getValue());
            counts.add(count);
        }
        run.put(COUNTS, counts);
        checklist.fingerprint().ifPresent(fingerprint -> {
            CompoundTag saved = new CompoundTag();
            saved.putLong(REQUIRED, fingerprint.required());
            saved.putLong(GATHERED, fingerprint.gathered());
            saved.putLong(BLOCKS, fingerprint.blocksToPlace());
            run.put(FINGERPRINT, saved);
        });
        if (checklist.lastRefresh() != ChecklistNeed.NEVER)
            run.putLong(LAST_REFRESH, checklist.lastRefresh());
        return run;
    }

    private void readRun(CompoundTag run) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        ListTag list = run.getList(COUNTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag count = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(count.getString(ITEM));
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            if (item != Items.AIR && count.getInt(COUNT) > 0)
                counts.put(item, count.getInt(COUNT));
        }
        Optional<ChecklistNeed.Fingerprint> fingerprint = Optional.empty();
        if (run.contains(FINGERPRINT, Tag.TAG_COMPOUND)) {
            CompoundTag saved = run.getCompound(FINGERPRINT);
            fingerprint = Optional.of(new ChecklistNeed.Fingerprint(saved.getLong(REQUIRED), saved.getLong(GATHERED),
                    saved.getLong(BLOCKS)));
        }
        checklist.restore(counts, fingerprint,
                run.contains(LAST_REFRESH, Tag.TAG_LONG) ? run.getLong(LAST_REFRESH) : ChecklistNeed.NEVER);
    }
}
