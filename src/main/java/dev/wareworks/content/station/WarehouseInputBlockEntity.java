package dev.wareworks.content.station;

import java.util.List;

import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.InsertOnlyItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
 */
public class WarehouseInputBlockEntity extends WarehouseStationBlockEntity {
    private final IItemHandler externalHandler;

    /**
     * Packages taken apart here since this block entity was loaded, and the last refusal
     * ({@link PackageUnpackSummary}). Server-side diagnosis: deliberately not saved, so it starts empty after a load
     * and no NBT key is added to any save (M26).
     */
    private long packagesOpened;
    private int refusedPackageStacks;
    private int refusedFreeSlots;

    public WarehouseInputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, WareworksConfig.inputBufferSlots());
        externalHandler = new InsertOnlyItemHandler(buffer);
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
        return buffer.insert(transported.stack, simulate);
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
        return super.createSummary()
                .withPackages(new PackageUnpackSummary(packagesOpened, refusedPackageStacks, refusedFreeSlots));
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
        PackageUnpackSummary packages = shown.packages();
        boolean unpacksHere = packagerUnpacksHere();
        if (!unpacksHere && packages.isEmpty())
            return;
        if (unpacksHere)
            WareworksLang.translate(WareworksLang.GOGGLES_INPUT_PACKAGE_UNPACKING).forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_INPUT_PACKAGES_OPENED, packages.opened()).forGoggles(tooltip, 2);
        if (packages.hasRefusal())
            WareworksLang.packageRefused(packages.refusedPackageStacks(), packages.freeSlotsAtRefusal())
                    .forGoggles(tooltip, 2);
    }

    @Override
    protected String goggleHeaderKey() {
        return WareworksLang.GOGGLES_WAREHOUSE_INPUT;
    }
}
