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
 */
public class WarehouseInputBlockEntity extends WarehouseStationBlockEntity {
    private final IItemHandler externalHandler;

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

    @Override
    protected String goggleHeaderKey() {
        return WareworksLang.GOGGLES_WAREHOUSE_INPUT;
    }
}
