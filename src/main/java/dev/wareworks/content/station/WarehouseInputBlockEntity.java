package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.InsertOnlyItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.packagemode.PackageModeGate;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.production.PackageModeDrain;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of the warehouse input ({@code docs/warehouse-system.md} §3.2): a buffer of {@code inputBufferSlots} slots
 * that automation fills and the crane empties.
 * <p>
 * <b>Automation.</b> The item capability is an {@link InsertOnlyItemHandler}: funnels, chutes and hoppers can insert,
 * nothing can be extracted. Belts, belt tunnels, ejectors and other "direct" inserters use
 * {@link DirectBeltInputBehaviour}, whose handler inserts into the buffer and returns the remainder (the direction
 * passed by Create is not consistent across callers and is ignored). Belt funnels on top are not supported.
 * <p>
 * <b>Mechanical arms (M12).</b> A Create arm resolves its targets only through a registered
 * {@code ArmInteractionPointType}, with no fallback to the item capability, so the input has one of its own
 * ({@code WareworksArmInteractionPoints#WAREHOUSE_INPUT}). Its {@link WarehouseInputArmPoint} is <b>deposit only</b>: the
 * arm puts items in through the same insert-only view funnels use and can never take anything out, whatever its
 * saved mode says. GameTests {@code stationarmpointtypes}, {@code stationarmpointsemantics} and
 * {@code mechanicalarmsfeedandemptystations} pin this.
 * <p>
 * <b>Crane API (M3).</b> {@link #bufferedItems()} shows what waits to be stored; {@link #extract} takes one stack of an
 * item at a time, with a simulate mode that matches the real result in the same tick; {@link #countOf} counts one item
 * without a snapshot; {@link #insert} takes back rerouted store leftovers.
 * <p>
 * <b>Packages (M26, issue #18).</b> A Create Packager whose back touches this block unpacks into it, with no Wareworks
 * code in the item path at all ({@code docs/warehouse-system.md} §3.2.5). Everything this class adds is <b>reading</b>:
 * the goggle tooltip says that packages are taken apart here, how many have been, and — the one failure nothing else in
 * the game diagnoses — why the last one was refused whole. The capability is counted by
 * {@link WarehouseInputUnpackingHandler}, which delegates verbatim to Create's default handler; whether a Packager is
 * attached is read off the neighbouring block states on the client, so none of it costs a byte of sync beyond the three
 * numbers of {@link PackageUnpackSummary}.
 * <p>
 * <b>Package mode (issue #40).</b> With the world's {@code [packageMode] enabled} switch on
 * ({@link PackageModeGate}), this input is an entrance for sealed Create packages only: its capability view
 * ({@link ExternalView}) and its belt side refuse a loose stack, which stays with whatever brought it (belt, funnel,
 * chute, arm). The one exception is the drain rule ({@link PackageModeDrain}): the loose results of a production order
 * that was open when the world loaded are let in, up to what that order still waits for. The crane's own
 * {@link #insert} (rerouted leftovers) is never gated, so a crane always has somewhere to put what it carries; loose
 * items that end up here that way, or were here when the world loaded, are held by the controller's backstop
 * ({@code WarehouseControllerBlockEntity#packageModeHolds}) and taken out with an empty hand
 * ({@link #takePackageModeHeld}). A Packager behind the input would open the boxes, so the unpacking handler refuses a
 * box whole in package mode unless every stack in it is such a drained result. With the switch off, every path below
 * is the one it was before package mode existed.
 */
public class WarehouseInputBlockEntity extends WarehouseStationBlockEntity {
    /** How long a read of the controller's drain allowance is reused (package mode only): a refused funnel asks often. */
    private static final int DRAIN_REREAD_TICKS = 20;

    private final IItemHandler externalHandler;

    /**
     * Packages taken apart here since this block entity was loaded, and the last refusal
     * ({@link PackageUnpackSummary}). Server-side diagnosis: deliberately not saved, so it starts empty after a load
     * and no NBT key is added to any save (M26).
     */
    private long packagesOpened;
    private int refusedPackageStacks;
    private int refusedFreeSlots;

    /**
     * Package mode (issue #40), server diagnosis and caches, never saved: the last loose item an entrance refused,
     * whether the Packager behind this input was refused a box because of package mode, and the controller's drain
     * allowance as last read (at {@link #drainReadAt}). {@link #unpackBypass} is set only while
     * {@link WarehouseInputUnpackingHandler} hands Create's default handler a box it has already admitted whole.
     */
    private Item lastLooseRefused;
    private boolean packagerRefusedForPackageMode;
    private Map<ItemKey, Long> drainAllowance = Map.of();
    private long drainReadAt = Long.MIN_VALUE;
    private boolean unpackBypass;
    /**
     * Package mode's stock rules (issue #40, S5): the controller this input asks about a box's maximum, re-resolved at
     * most once per {@value #DRAIN_REREAD_TICKS} ticks (the lookup scans the level's controllers, and a refused funnel
     * asks every tick); the answer itself is always read live. Not saved.
     */
    private @Nullable WarehouseControllerBlockEntity ruleController;
    private long ruleControllerReadAt = Long.MIN_VALUE;
    /**
     * The address of the last box an entrance refused because its address is at a package rule's maximum, {@code null}
     * when the last box offered was let in. Not saved.
     */
    private @Nullable String lastBoxAtMaximum;

    /**
     * Package mode (issue #40, review fix 2026-11-22): moves whenever any input's buffer changes, loads or goes away, so
     * a controller can keep its count of the boxes waiting in its inputs until one of them really changed instead of
     * reading every input's buffer each time a refused funnel asks again ({@code WarehouseControllerBlockEntity}). Not
     * saved; only ever compared for equality.
     */
    private static final java.util.concurrent.atomic.AtomicLong BUFFER_REVISION =
            new java.util.concurrent.atomic.AtomicLong();

    /** The current {@link #BUFFER_REVISION}: equal to an earlier answer only if no input changed in between. */
    public static long bufferRevision() {
        return BUFFER_REVISION.get();
    }

    @Override
    protected void onBufferContentsChanged() {
        BUFFER_REVISION.incrementAndGet();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        BUFFER_REVISION.incrementAndGet();
    }

    /** Block destroyed or chunk unloaded ({@code SmartBlockEntity#setRemoved} calls this in both cases). */
    @Override
    public void invalidate() {
        super.invalidate();
        BUFFER_REVISION.incrementAndGet();
    }

    public WarehouseInputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, WareworksConfig.inputBufferSlots());
        externalHandler = new ExternalView();
    }

    /** Registers the insert-only view for every side (listed in {@code WareworksCapabilities}). */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WareworksBlockEntityTypes.WAREHOUSE_INPUT.get(),
                (be, side) -> be.externalHandler);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Runs inside the super constructor: the buffer does not exist yet, but the handler only uses it when called.
        behaviours.add(new DirectBeltInputBehaviour(this).setInsertionHandler(this::insertFromBelt));
    }

    private ItemStack insertFromBelt(TransportedItemStack transported, Direction side, boolean simulate) {
        ItemStack stack = transported.stack;
        int admitted = admittedCount(stack);
        if (admitted >= stack.getCount())
            return buffer.insert(stack, simulate);
        if (admitted <= 0)
            return stack;
        ItemStack rest = buffer.insert(stack.copyWithCount(admitted), simulate);
        return stack.copyWithCount(stack.getCount() - admitted + rest.getCount());
    }

    // --- package mode (issue #40) ---------------------------------------------------------------------------------

    /**
     * How many of {@code stack} an entrance of this input lets in before the buffer's own room is asked: all of it with
     * package mode off, for a package, for an empty stack and while the unpacking handler hands over a box it admitted
     * whole; otherwise only what the drain rule still allows ({@link #drainRoom}), and nothing at all for any other
     * loose stack. A refusal is recorded for the goggles (one field, no allocation, no sync).
     */
    private int admittedCount(ItemStack stack) {
        if (unpackBypass || PackageModeGate.admits(stack))
            return isRuledPackage(stack) ? packageAdmitted(stack, true) : stack.getCount();
        long room = drainRoom(ItemKey.of(stack));
        if (room <= 0L) {
            lastLooseRefused = stack.getItem();
            return 0;
        }
        return (int) Math.min(stack.getCount(), room);
    }

    /** Whether package mode's stock rules are asked about {@code stack} at all: only a box, only with the switch on. */
    private boolean isRuledPackage(ItemStack stack) {
        return !unpackBypass && PackageModeGate.enabled() && PackageModeGate.isPackage(stack);
    }

    /**
     * How many of the box stack {@code stack} this input lets in under the package rules' maxima (issue #40, S5, design
     * critique M5): what the controller says the box's address group still has room for, counted with what every input
     * of the warehouse already holds of it. A box over the cap is refused here, so it waits with its sender, where a
     * Package Filter can send it elsewhere, rather than taking one of this input's slots. Without a loaded controller
     * nothing is capped. {@code record} notes a refusal for the goggles.
     */
    private int packageAdmitted(ItemStack stack, boolean record) {
        WarehouseControllerBlockEntity controller = ruleController();
        long room = controller == null ? Long.MAX_VALUE : controller.packageAdmitRoom(ItemKey.of(stack));
        if (room <= 0L) {
            if (record)
                lastBoxAtMaximum = PackageItem.getAddress(stack);
            return 0;
        }
        if (record)
            lastBoxAtMaximum = null;
        return (int) Math.min(stack.getCount(), room);
    }

    /** {@link #ruleController}, re-resolved when stale or gone; {@code null} on a client or without a controller. */
    private @Nullable WarehouseControllerBlockEntity ruleController() {
        if (level == null || level.isClientSide)
            return null;
        long now = level.getGameTime();
        if (ruleController == null || ruleController.isRemoved() || ruleControllerReadAt == Long.MIN_VALUE
                || now < ruleControllerReadAt || now - ruleControllerReadAt >= DRAIN_REREAD_TICKS) {
            ruleController = WarehouseRegistry.findController(level, worldPosition).orElse(null);
            ruleControllerReadAt = now;
        }
        return ruleController;
    }

    /**
     * Package mode's drain rule at this input ({@link PackageModeDrain#room}): loose items of {@code key} this buffer
     * may still take in for a production order that was open when the world loaded. The controller's allowance is read
     * at most once per {@value #DRAIN_REREAD_TICKS} ticks; what this buffer already holds of the key is counted live,
     * so the simulate and the real pass of one insert agree. 0 on a client and without a loaded controller.
     */
    long drainRoom(ItemKey key) {
        if (level == null || level.isClientSide)
            return 0L;
        long now = level.getGameTime();
        if (drainReadAt == Long.MIN_VALUE || now < drainReadAt || now - drainReadAt >= DRAIN_REREAD_TICKS) {
            drainAllowance = WarehouseRegistry.findController(level, worldPosition)
                    .map(WarehouseControllerBlockEntity::packageModeDrainAllowance).orElse(Map.of());
            drainReadAt = now;
        }
        if (drainAllowance.isEmpty())
            return 0L;
        return PackageModeDrain.room(drainAllowance, key, buffer.countOf(key));
    }

    /**
     * {@link WarehouseInputUnpackingHandler}, package mode: whether a box holding {@code contents} may be opened into
     * this input. Only when every stack in it is a drained result ({@link #drainRoom}), counted together per item, so
     * the box goes in whole or not at all.
     */
    boolean mayOpenInPackageMode(List<ItemStack> contents) {
        Map<ItemKey, Long> needed = new HashMap<>();
        for (ItemStack stack : contents) {
            if (!stack.isEmpty())
                needed.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
        }
        for (Map.Entry<ItemKey, Long> entry : needed.entrySet()) {
            if (drainRoom(entry.getKey()) < entry.getValue())
                return false;
        }
        return true;
    }

    /** {@link WarehouseInputUnpackingHandler}: runs {@code action} with the entrance gate open (a box admitted whole). */
    boolean withUnpackBypass(BooleanSupplier action) {
        boolean before = unpackBypass;
        unpackBypass = true;
        try {
            return action.getAsBoolean();
        } finally {
            unpackBypass = before;
        }
    }

    /** {@link WarehouseInputUnpackingHandler}: a box was refused whole because of package mode. */
    void onPackageRefusedForPackageMode() {
        packagerRefusedForPackageMode = true;
    }

    /**
     * Server, a player's empty-hand click in package mode (issue #40, owner decision E4): takes out every loose item the
     * controller's backstop keeps in this input ({@code WarehouseControllerBlockEntity#packageModeHolds}) and every box
     * the warehouse may not store because a package rule's maximum leaves no room for it
     * ({@code WarehouseControllerBlockEntity#storeHeadroom} 0: a lowered maximum, or a crane's reroute), and only those
     * (review fix: nine such boxes would block the input for every address). Other packages and drained production
     * results stay for the crane. Without a loaded controller every loose item is held (nothing could store it), so
     * every loose item comes out, and every box stays.
     *
     * @return the stacks taken out, empty with package mode off or when nothing is held here
     */
    public List<ItemStack> takePackageModeHeld() {
        if (!PackageModeGate.enabled() || level == null || level.isClientSide || isRemoved())
            return List.of();
        WarehouseControllerBlockEntity controller = WarehouseRegistry.findController(level, worldPosition).orElse(null);
        List<ItemStack> taken = new ArrayList<>();
        for (ItemKey key : List.copyOf(bufferedItems().keys())) {
            if (!heldForAHand(controller, key))
                continue;
            for (ItemStack stack = buffer.extract(key, key.toStack().getMaxStackSize(), false); !stack.isEmpty();
                    stack = buffer.extract(key, key.toStack().getMaxStackSize(), false))
                taken.add(stack);
        }
        if (!taken.isEmpty())
            setChanged();
        return taken;
    }

    /**
     * Whether the empty-hand click takes {@code key} out of this input in package mode: a loose key the backstop holds,
     * or a box the warehouse may not store under a package rule's maximum.
     */
    private static boolean heldForAHand(@Nullable WarehouseControllerBlockEntity controller, ItemKey key) {
        if (PackageModeGate.isPackage(key))
            return controller != null && controller.storeHeadroom(key) <= 0L;
        return controller == null || controller.packageModeHolds(key);
    }

    /**
     * What waits in this buffer for a hand right now (package mode, for the goggles): {@code [0]} loose items the
     * backstop keeps, {@code [1]} boxes over a package rule's maximum.
     */
    private long[] heldForAHand() {
        long[] held = new long[2];
        if (!PackageModeGate.enabled() || level == null || level.isClientSide)
            return held;
        WarehouseControllerBlockEntity controller = WarehouseRegistry.findController(level, worldPosition).orElse(null);
        for (Map.Entry<ItemKey, Long> entry : bufferedItems().totals().entrySet()) {
            ItemKey key = entry.getKey();
            if (heldForAHand(controller, key))
                held[PackageModeGate.isPackage(key) ? 1 : 0] += entry.getValue();
        }
        return held;
    }

    /**
     * The input's capability view: insert-only, as {@link InsertOnlyItemHandler} always was, plus the package-mode
     * gate ({@link #admittedCount}). With package mode off every call is exactly the backing buffer's.
     */
    private final class ExternalView implements IItemHandler {
        @Override
        public int getSlots() {
            return buffer.getSlots();
        }

        /** The backing stack; callers must not modify it ({@link IItemHandler#getStackInSlot} contract). */
        @Override
        public ItemStack getStackInSlot(int slot) {
            return buffer.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            int admitted = admittedCount(stack);
            if (admitted >= stack.getCount())
                return buffer.insertItem(slot, stack, simulate);
            if (admitted <= 0)
                return stack;
            ItemStack rest = buffer.insertItem(slot, stack.copyWithCount(admitted), simulate);
            return stack.copyWithCount(stack.getCount() - admitted + rest.getCount());
        }

        /** Always empty: nothing can be taken out through this view. */
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return buffer.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (unpackBypass || PackageModeGate.admits(stack))
                return buffer.isItemValid(slot, stack) && (!isRuledPackage(stack) || packageAdmitted(stack, false) > 0);
            return drainRoom(ItemKey.of(stack)) > 0L && buffer.isItemValid(slot, stack);
        }
    }

    /**
     * Crane: takes up to {@code amount} items of {@code key} from the buffer, at most one stack per call.
     *
     * @return a new stack with the taken items, or empty
     */
    public ItemStack extract(ItemKey key, int amount, boolean simulate) {
        return buffer.extract(key, amount, simulate);
    }

    /** Crane: number of items of {@code key} waiting in the buffer (live, no snapshot). */
    public long countOf(ItemKey key) {
        return buffer.countOf(key);
    }

    /**
     * Crane: puts {@code stack} back into the buffer (store leftovers rerouted to an input, {@code docs/warehouse-system.md}
     * §8), matching stacks first. The caller's stack is not modified.
     *
     * @return what did not fit (a new stack, or empty)
     */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        return buffer.insert(stack, simulate);
    }

    /**
     * Server, a player's empty-hand click (ADR-085 I7, design §8): takes out every item the Recipe Selector's quarantine
     * keeps in this input because the station it returns for does not expect it, and only those. Nothing else in the
     * buffer moves; the crane stores the rest as before.
     *
     * @return the stacks taken out, empty when this is no return input or holds nothing quarantined
     */
    public List<ItemStack> takeQuarantined() {
        if (level == null || level.isClientSide || isRemoved())
            return List.of();
        WarehouseControllerBlockEntity controller = WarehouseRegistry.findController(level, worldPosition).orElse(null);
        if (controller == null)
            return List.of();
        List<ItemStack> taken = new ArrayList<>();
        for (ItemKey key : List.copyOf(bufferedItems().keys())) {
            if (!controller.selectorQuarantines(worldPosition, key))
                continue;
            for (ItemStack stack = buffer.extract(key, key.toStack().getMaxStackSize(), false); !stack.isEmpty();
                    stack = buffer.extract(key, key.toStack().getMaxStackSize(), false))
                taken.add(stack);
        }
        if (!taken.isEmpty())
            setChanged();
        return taken;
    }

    @Override
    public LocationKind locationKind() {
        return LocationKind.INPUT;
    }

    @Override
    public IItemHandler externalHandler() {
        return externalHandler;
    }

    // --- packages (M26, issue #18) --------------------------------------------------------------------------------

    /**
     * {@link WarehouseInputUnpackingHandler}: a package was taken apart here for real. Clears the refusal — the cliff
     * the last one hit is over, and leaving it on record would read as a door that is still stuck.
     * <p>
     * Marks nothing changed and sends nothing: the next goggle observation picks the numbers up through
     * {@link #createSummary()} and syncs them at most once per throttle interval. A refused funnel calls the handler
     * every tick, so nothing here may save, sync or allocate.
     */
    public void onPackageOpened() {
        packagesOpened++;
        refusedPackageStacks = 0;
        refusedFreeSlots = 0;
    }

    /**
     * {@link WarehouseInputUnpackingHandler}: Create's simulate pass refused a package whole. Records how many stacks
     * that package <b>held</b> beside the buffer slots that are free right now, which is the moment of the refusal:
     * the simulate pass changes nothing, so the free count is neither stale nor spent.
     * <p>
     * The package's own stacks rather than the ones the simulate pass could not place, because only the first number
     * is commensurable with the second: Create places greedily, so "4 of them found no room" beside "5 slots free"
     * reads as a contradiction although both numbers are right ({@link PackageUnpackSummary}).
     */
    public void onPackageRefused(int packageStacks) {
        if (packageStacks <= 0)
            return;
        refusedPackageStacks = packageStacks;
        refusedFreeSlots = freeBufferSlots();
    }

    /** Empty buffer slots, counted without a snapshot: a refused funnel retries every tick. */
    private int freeBufferSlots() {
        int free = 0;
        for (int slot = 0; slot < buffer.getSlots(); slot++)
            if (buffer.getStackInSlot(slot).isEmpty())
                free++;
        return free;
    }

    /**
     * Whether a Create Packager's <b>back</b> touches this input, i.e. whether packages fed to it land here
     * ({@link PackageHandover#packagerFor}, which is also what the port's own handover lines read). Nothing but
     * neighbouring block states and block entities, which a client already has, so this line costs no sync at all.
     * <p>
     * A <b>Repackager</b> does not count, and that is not a nicety: it overrides {@code unwrapBox} to insert the whole
     * box into its target and never consults an unpacking handler at all, so it does not take packages apart — it has
     * the input store them as items.
     */
    private boolean packagerUnpacksHere() {
        return level != null && PackageHandover.packagerFor(level, worldPosition).isPresent();
    }

    @Override
    protected StationGoggleSummary createSummary() {
        boolean packageMode = PackageModeGate.enabled();
        String refusedLoose = lastLooseRefused == null || !packageMode ? ""
                : BuiltInRegistries.ITEM.getKey(lastLooseRefused).toString();
        long[] held = heldForAHand();
        // Why the items here wait (issue #53): the controller's answer about this input, only while it holds any.
        Optional<NoJobReason> notStored = level == null || level.isClientSide || buffer.snapshot().isEmpty()
                ? Optional.empty()
                : WarehouseRegistry.findController(level, worldPosition).flatMap(c -> c.storeRefusalAt(worldPosition));
        return super.createSummary()
                .withNotStored(notStored)
                .withPackages(new PackageUnpackSummary(packagesOpened, refusedPackageStacks, refusedFreeSlots,
                        held[0], refusedLoose, packagerRefusedForPackageMode && packageMode,
                        packageMode ? lastBoxAtMaximum : null, held[1]));
    }

    /**
     * Client: what this input does with Create packages (M26, issue #18). Nothing at all unless a Packager stands at
     * it or one has already handed it something, so an ordinary input's tooltip is unchanged.
     * <p>
     * The refusal line is the only place in the game that names the all-or-nothing cliff: Create consumes a package
     * whole or not at all, so a package with more stacks than the buffer has room for waits in the funnel for ever
     * without a single block saying why.
     */
    @Override
    protected void addStationGoggleLines(List<Component> tooltip, StationGoggleSummary shown) {
        super.addStationGoggleLines(tooltip, shown);
        // Why the items here wait (issue #53), in the words of the controller's planning line, and for the one type
        // rule the cure as well: a player standing at a full input should not have to find the controller first.
        shown.notStored().ifPresent(reason -> {
            WareworksLang.translate(WareworksLang.GOGGLES_INPUT_NOT_STORED).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            WareworksLang.translate(WareworksLang.noJobReasonKey(reason)).style(ChatFormatting.WHITE)
                    .forGoggles(tooltip, 2);
            if (reason == NoJobReason.NO_LOCATION_FOR_TYPE)
                WareworksLang.translate(WareworksLang.GOGGLES_INPUT_ONE_TYPE_HINT).style(ChatFormatting.GRAY)
                        .forGoggles(tooltip, 2);
        });
        PackageUnpackSummary packages = shown.packages();
        boolean unpacksHere = packagerUnpacksHere();
        // Package mode (issue #40): the client reads the switch the server told it at login.
        boolean packageMode = PackageModeGate.enabledOnClient();
        if (packageMode)
            addPackageModeLines(tooltip, packages, unpacksHere);
        if (!unpacksHere && packages.opened() == 0L && !packages.hasRefusal())
            return;
        if (unpacksHere && !packageMode)
            WareworksLang.translate(WareworksLang.GOGGLES_INPUT_PACKAGE_UNPACKING).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_INPUT_PACKAGES_OPENED, packages.opened()).forGoggles(tooltip, 2);
        if (packages.hasRefusal())
            WareworksLang.packageRefused(packages.refusedPackageStacks(), packages.freeSlotsAtRefusal())
                    .forGoggles(tooltip, 2);
    }

    /** The package-mode lines of an input's goggles: what it takes, what it refused, what waits for a hand. */
    private static void addPackageModeLines(List<Component> tooltip, PackageUnpackSummary packages,
            boolean unpacksHere) {
        WareworksLang.translate(WareworksLang.GOGGLES_INPUT_PACKAGES_ONLY).style(ChatFormatting.GOLD)
                .forGoggles(tooltip, 1);
        if (unpacksHere || packages.packagerRefused())
            WareworksLang.translate(WareworksLang.GOGGLES_INPUT_PACKAGER_IN_PACKAGE_MODE).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
        if (!packages.lastLooseRefused().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(packages.lastLooseRefused());
            Component name = id == null ? Component.literal(packages.lastLooseRefused())
                    : BuiltInRegistries.ITEM.get(id).getDescription();
            WareworksLang.translate(WareworksLang.GOGGLES_INPUT_LOOSE_REFUSED,
                    WareworksLang.builder().add(name).style(ChatFormatting.WHITE))
                    .style(ChatFormatting.GRAY).forGoggles(tooltip, 2);
        }
        if (packages.heldLoose() > 0L)
            WareworksLang.countLine(WareworksLang.GOGGLES_INPUT_LOOSE_HELD, packages.heldLoose())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 2);
        if (packages.heldBoxes() > 0L)
            WareworksLang.countLine(WareworksLang.GOGGLES_INPUT_BOXES_HELD, packages.heldBoxes())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 2);
        if (packages.boxAtMaximum() != null)
            WareworksLang.translate(WareworksLang.GOGGLES_INPUT_PACKAGE_AT_MAXIMUM,
                    WareworksLang.builder().text(packages.boxAtMaximum().isBlank() ? "-" : packages.boxAtMaximum())
                            .style(ChatFormatting.WHITE))
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 2);
    }

    @Override
    protected String goggleHeaderKey() {
        return WareworksLang.GOGGLES_WAREHOUSE_INPUT;
    }
}
