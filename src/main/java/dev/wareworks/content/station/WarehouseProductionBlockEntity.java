package dev.wareworks.content.station;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ExtractOnlyItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
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
 * matches a real recipe.
 * <p>
 * <b>No ticker, no redstone.</b> Patterns are edited in the screen ({@link #openScreen}), and the station only reacts
 * to insertions, extractions, goggle observation and lifecycle events like every other station.
 */
public class WarehouseProductionBlockEntity extends WarehouseDeliveryStationBlockEntity implements IInteractionChecker {
    /** NBT key of the goggle data that is specific to this station (client packets only). */
    public static final String PRODUCTION_SUMMARY_TAG = "ProductionSummary";

    private final ProductionPatterns patterns;
    /** Derived goggle state, never saved; synced with the shared station summary. */
    private ProductionGoggleSummary productionSummary = ProductionGoggleSummary.NONE;

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

    /** The complete patterns of this station, in slot order. */
    public List<ProductionPattern<ItemKey>> activePatterns() {
        return patterns.patterns();
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
        setChanged();
        return true;
    }

    /** Server: clears one pattern slot completely. */
    public boolean clearPattern(int pattern) {
        if (level == null || level.isClientSide || isRemoved())
            return false;
        if (!patterns.clearPattern(pattern))
            return false;
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
        if (found.isEmpty())
            return new ProductionGoggleSummary(patterns.patternCount(), 0, Optional.empty(), 0L, 0L);
        List<ProductionOrder<ItemKey, RackPosition>> orders = found.get().productionOrdersAt(worldPosition);
        int open = 0;
        long missing = 0L;
        long awaited = 0L;
        Optional<ProductionOrderState> oldest = Optional.empty();
        for (ProductionOrder<ItemKey, RackPosition> order : orders) {
            if (oldest.isEmpty())
                oldest = Optional.of(order.state());
            if (!order.isOpen())
                continue;
            open++;
            missing += order.outstandingIngredients();
            awaited += order.outstandingResult();
        }
        return new ProductionGoggleSummary(patterns.patternCount(), open, oldest, missing, awaited);
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
        if (production.openOrders() == 0) {
            WareworksLang.translate(WareworksLang.GOGGLES_PRODUCTION_NO_ORDERS).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
            return;
        }
        WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_ORDERS, production.openOrders())
                .forGoggles(tooltip, 1);
        production.oldestState().ifPresent(state -> WareworksLang.productionState(state).forGoggles(tooltip, 2));
        if (production.missingIngredients() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_MISSING, production.missingIngredients())
                    .forGoggles(tooltip, 2);
        if (production.awaitedResult() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_PRODUCTION_AWAITED, production.awaitedResult())
                    .forGoggles(tooltip, 2);
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
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            productionSummary = ProductionGoggleSummary.read(tag.getCompound(PRODUCTION_SUMMARY_TAG));
            return;
        }
        patterns.load(tag.getList(ProductionPatterns.PATTERNS_TAG, Tag.TAG_COMPOUND), registries);
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
        super.remove();
        if (level instanceof ServerLevel)
            WarehouseRegistry.findController(level, worldPosition)
                    .ifPresent(controller -> controller.onProductionStationRemoved(worldPosition));
    }
}
