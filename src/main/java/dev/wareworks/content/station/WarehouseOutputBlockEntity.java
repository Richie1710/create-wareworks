package dev.wareworks.content.station;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ExtractOnlyItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Block entity of the warehouse output ({@code docs/warehouse-system.md} §3.2, §7.2): a buffer of
 * {@code outputBufferSlots} slots that the crane fills and automation empties, plus the request definition.
 * <p>
 * Buffer, extract-only capability, the crane's {@code insert} API, the last rejection and the request goggle lines come
 * from {@link WarehouseDeliveryStationBlockEntity}; this class adds only how a request is <b>entered</b>.
 * <p>
 * <b>Automation.</b> The item capability is an {@link ExtractOnlyItemHandler}: funnels, chutes and hoppers can pull,
 * nothing can be pushed in. There is no belt input. A Create mechanical arm can take items out through the same view
 * ({@link DeliveryStationArmPoint}, take only; M12).
 * <p>
 * <b>Request.</b> A {@link RequestFilterBehaviour} (Create's {@link FilteringBehaviour} with a visible count) defines what
 * to request (ADR-013): the exact filter stack as {@link ItemKey} (item and components) and up to the count, at most one
 * stack; unstackable items request 1. List, attribute and package filter items are refused, because a request needs one
 * concrete item; a clipboard carrying one is refused before Create takes anything from the player. The filter slot is on
 * the top, back and side faces, not on the bottom or the aisle side, where the crane reaches into the opening. The
 * filter item is drawn by Create's {@code SmartBlockEntityRenderer}. On a redstone rising edge
 * ({@link WarehouseOutputBlock}) the output asks the controller of its aisle ({@link #submitRequest()}); a refusal is
 * kept as {@code lastRejection()} (saved, shown in goggles) and cleared by the next accepted request.
 * <p>
 * <b>Repeated pulses merge</b> ({@code docs/warehouse-system.md} §7.2, ADR-020): a pulse for an item this output
 * already waits for grows that request instead of queueing a second one, so a pulse clock produces one trip rather than
 * one per pulse. Because a merge takes no queue slot, the amount is bounded by {@link #maxRequestAmount()} instead of by
 * the per-output slot cap.
 */
public class WarehouseOutputBlockEntity extends WarehouseDeliveryStationBlockEntity {
    /**
     * Request filter. Assigned in {@link #addBehaviours}, which {@code SmartBlockEntity} calls from its constructor, so this
     * field must not have an initializer.
     */
    protected RequestFilterBehaviour requestFilter;

    public WarehouseOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, WareworksConfig.outputBufferSlots());
    }

    /** Registers the extract-only view for every side (listed in {@code WareworksCapabilities}). */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.get(),
                (be, side) -> be.externalHandler());
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Top, back and side faces show the filter slot: not the bottom, and not the aisle side, where the crane reaches
        // into the opening. Faces covered by rack neighbours simply cannot be clicked; a clipboard pastes on any face.
        requestFilter = new RequestFilterBehaviour(this, new CenteredSideValueBoxTransform((state, side) ->
                side != Direction.DOWN && side != state.getOptionalValue(WarehouseStationBlock.FACING).orElse(null)),
                WarehouseOutputBlockEntity::isRequestable);
        requestFilter.showCount();
        requestFilter.setLabel(WareworksLang.translateDirect(WareworksLang.OUTPUT_REQUEST_FILTER));
        behaviours.add(requestFilter);
    }

    /** Whether {@code stack} can define a request: any concrete item, but no list, attribute or package filter. */
    public static boolean isRequestable(ItemStack stack) {
        return !(stack.getItem() instanceof FilterItem);
    }

    // --- request -------------------------------------------------------------------------------------------------

    /** A copy of the filter stack (empty without filter). */
    public ItemStack requestedItem() {
        return requestFilter.getFilter().copy();
    }

    /**
     * The amount a request asks for, up to which the controller grants what is in stock: the filter count, clamped to
     * 1..one stack of the filter item; 0 without filter.
     */
    public int requestAmount() {
        ItemStack filter = requestFilter.getFilter();
        if (filter.isEmpty())
            return 0;
        return Mth.clamp(requestFilter.getAmount(), 1, filter.getMaxStackSize());
    }

    /**
     * The largest amount one open request of this output may wait for ({@code docs/warehouse-system.md} §7.2): as much
     * as its {@code maxOpenRequestsPerOutput} request slots could hold before repeated pulses merged, i.e. that many
     * pulses of {@link #requestAmount()}.
     * <p>
     * Since repeated pulses for one item grow a single request instead of taking a slot each, the per-output slot cap
     * no longer bounds what a pulse clock promises; this is that bound, so a clock is refused with
     * {@link RequestRejection#REQUEST_FULL} exactly where {@link RequestRejection#OUTPUT_FULL} refused it before,
     * instead of promising the aisle's whole stock of that item. A single pulse is never refused by it (the factor is
     * at least 1), and deliveries free room again.
     */
    public int maxRequestAmount() {
        long perOutput = Math.max(RequestQueue.MIN_OPEN_REQUESTS, WareworksConfig.maxOpenRequestsPerOutput());
        return (int) Math.min(Integer.MAX_VALUE, perOutput * Math.max(1, requestAmount()));
    }

    /** Called by the block on a redstone rising edge (server). */
    public void onRedstoneRisingEdge() {
        submitRequest();
    }

    /**
     * Server: submits the request defined by the filter slot to the controller of this output's aisle and remembers a
     * refusal. See {@link WarehouseControllerBlockEntity#request} for the rules.
     */
    public RequestResult submitRequest() {
        if (level == null || level.isClientSide || isRemoved())
            return RequestResult.rejected(RequestRejection.NO_CONTROLLER);
        return rememberRejection(resolveRequest());
    }

    private RequestResult resolveRequest() {
        ItemStack filter = requestFilter.getFilter();
        if (filter.isEmpty())
            return RequestResult.rejected(RequestRejection.NO_FILTER);
        Optional<WarehouseControllerBlockEntity> controller = controller();
        if (controller.isEmpty())
            return RequestResult.rejected(RequestRejection.NO_CONTROLLER);
        // The cap is passed to the controller rather than applied here: repeated pulses for one item are merged into
        // the open request, so it must bound the merged remaining amount, not this pulse (§7.2, ADR-020).
        // A redstone pulse is the warehouse's own automation, so a stock rule's reserve holds items back from it: an
        // output must not be able to empty a buffer a player set aside overnight (M15, issue #3).
        return controller.get().request(worldPosition, ItemKey.of(filter), requestAmount(), maxRequestAmount(),
                StockAccess.AUTOMATION);
    }

    // --- station -------------------------------------------------------------------------------------------------

    @Override
    protected String goggleHeaderKey() {
        return WareworksLang.GOGGLES_WAREHOUSE_OUTPUT;
    }
}
