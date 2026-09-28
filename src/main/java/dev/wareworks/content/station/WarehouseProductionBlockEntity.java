package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

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
        }
        return new ProductionGoggleSummary(patterns.patternCount(), open, oldest, missing, awaited, stopped.size(),
                unrecovered, oldestWaitingForStep);
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
