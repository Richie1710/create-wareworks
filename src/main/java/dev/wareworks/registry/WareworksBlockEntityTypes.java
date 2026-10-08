package dev.wareworks.registry;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.client.render.FluidBayRenderer;
import dev.wareworks.client.render.RackBayRenderer;
import dev.wareworks.client.render.StackerCraneRenderer;
import dev.wareworks.client.render.WarehouseInterfaceRenderer;
import dev.wareworks.client.render.WarehouseOutputRenderer;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.station.WarehouseHomePointBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;

/**
 * Block entity type registrations ({@code REGISTRATE.blockEntity(name, Factory::new).validBlocks(...).register()}).
 * <p>
 * Renderers are attached with Registrate's {@code .renderer(() -> Renderer::new)} only (one registration path per type).
 * {@code .visual(...)} must precede {@code .validBlocks(...)}.
 * Initialised through {@link #register()} after {@link WareworksBlocks#register()}.
 */
public final class WareworksBlockEntityTypes {
    private static final CreateRegistrate REGISTRATE = Wareworks.registrate();

    /**
     * Warehouse interface: {@code client.render.WarehouseInterfaceRenderer} draws the item in the store filter slot
     * (M8; the only renderer registration for this type, and the lambda is only evaluated on the client). It is
     * Create's {@code SmartBlockEntityRenderer} with the view distance the filter item is actually drawn at, because
     * this is the one block a warehouse places by the hundred. No exposed capability.
     */
    public static final BlockEntityEntry<WarehouseInterfaceBlockEntity> WAREHOUSE_INTERFACE = REGISTRATE
            .blockEntity("warehouse_interface", WarehouseInterfaceBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_INTERFACE)
            .renderer(() -> WarehouseInterfaceRenderer::new)
            .register();

    /**
     * Rack bay ({@code docs/warehouse-system.md} §3.8, M28, issue #20): <b>one</b> type for all three materials, because
     * {@code .validBlocks(...)} is varargs and the tier is read off the block ({@code RackBayBlock#tier}) — one
     * registration, one capability registrar, one renderer slot. Its item capability is the bay's own one-slot,
     * one-type handler on every side, which is the whole of its contents.
     * <p>
     * {@code client.render.RackBayRenderer} draws the stored item at the mouth of the bay (M29 step 13, ADR-049; the
     * only renderer registration for this type, and the lambda is only evaluated on the client). The coarse fill level
     * stays block state geometry in the chunk mesh (ADR-047) — the renderer adds <b>which</b> item, nothing else — and
     * its view distance is cut to Create's {@code filterItemRenderDistance}, because a renderer on this type puts every
     * bay of a rack wall into its chunk section's per-frame list. That cap is the whole design and not a preference;
     * see the renderer.
     */
    public static final BlockEntityEntry<RackBayBlockEntity> RACK_BAY = REGISTRATE
            .blockEntity("rack_bay", RackBayBlockEntity::new)
            .validBlocks(WareworksBlocks.RACK_BAY_WOOD, WareworksBlocks.RACK_BAY_ANDESITE,
                    WareworksBlocks.RACK_BAY_BRASS)
            .renderer(() -> RackBayRenderer::new)
            .register();

    /**
     * Fluid bay ({@code docs/warehouse-system.md} §3.9, M30, issue #21): <b>one</b> type for both materials, for the
     * rack bay's reason — {@code .validBlocks(...)} is varargs and the tier is read off the block
     * ({@code FluidBayBlock#tier}).
     * <p>
     * Its capability is a {@code Capabilities.FluidHandler.BLOCK} on every face but the one towards the aisle, and it
     * exposes <b>no item capability at all</b> ({@link WareworksCapabilities}).
     * <p>
     * {@code client.render.FluidBayRenderer} draws the fluid standing in the bay (M30 step 5; the only renderer
     * registration for this type, and the lambda is only evaluated on the client). Unlike a rack bay, which keeps its
     * coarse fill level in the chunk mesh and uses its renderer only for <i>which</i> item it holds (ADR-047), a fluid
     * bay's level is drawn <b>entirely</b> here: a fluid's look is its own still sprite with its own tint, the set of
     * fluids is open, and no baked variant can name a sprite it has never heard of. For that same reason it is the one
     * renderer of this mod that keeps the <b>vanilla</b> view distance: the others draw a detail of a block that is
     * visible without them, and this one draws the whole readout, which a ten-block cap would hide from across the
     * room (ADR-053).
     */
    public static final BlockEntityEntry<FluidBayBlockEntity> FLUID_BAY = REGISTRATE
            .blockEntity("fluid_bay", FluidBayBlockEntity::new)
            .validBlocks(WareworksBlocks.FLUID_BAY_COPPER, WareworksBlocks.FLUID_BAY_BRASS)
            .renderer(() -> FluidBayRenderer::new)
            .register();

    /**
     * Stacker crane dock: kinetic block entity. The moving crane is drawn by {@code client.render.StackerCraneRenderer}, a
     * {@code SafeBlockEntityRenderer} (the only renderer registration for this type; the lambda is only evaluated on the
     * client). No Flywheel visual (ADR-013), so the renderer also runs with Flywheel active and in Ponder.
     */
    public static final BlockEntityEntry<StackerCraneBlockEntity> STACKER_CRANE = REGISTRATE
            .blockEntity("stacker_crane", StackerCraneBlockEntity::new)
            .validBlocks(WareworksBlocks.STACKER_CRANE)
            .renderer(() -> StackerCraneRenderer::new)
            .register();

    /**
     * Warehouse controller: no renderer (static block model; the "Aisle" value box is drawn by Create's value box
     * overlay) and no exposed capability.
     */
    public static final BlockEntityEntry<WarehouseControllerBlockEntity> WAREHOUSE_CONTROLLER = REGISTRATE
            .blockEntity("warehouse_controller", WarehouseControllerBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_CONTROLLER)
            .register();

    /** Warehouse input: no renderer (static block model); item capability: insert-only view. */
    public static final BlockEntityEntry<WarehouseInputBlockEntity> WAREHOUSE_INPUT = REGISTRATE
            .blockEntity("warehouse_input", WarehouseInputBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_INPUT)
            .register();

    /**
     * Warehouse port (the output station): {@code client.render.WarehouseOutputRenderer} draws the item in the request
     * filter slot and, for an accepting port, its signed rank on the plate on the back (M17; the only renderer
     * registration for this type, and the lambda is only evaluated on the client). Item capability: extract-only view.
     */
    public static final BlockEntityEntry<WarehouseOutputBlockEntity> WAREHOUSE_OUTPUT = REGISTRATE
            .blockEntity("warehouse_output", WarehouseOutputBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_OUTPUT)
            .renderer(() -> WarehouseOutputRenderer::new)
            .register();

    /**
     * Warehouse terminal: no renderer (static block model; the screen comes as a GUI, not as a block entity renderer)
     * and, like the output, an extract-only item capability.
     */
    public static final BlockEntityEntry<WarehouseTerminalBlockEntity> WAREHOUSE_TERMINAL = REGISTRATE
            .blockEntity("warehouse_terminal", WarehouseTerminalBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_TERMINAL)
            .register();

    /**
     * Warehouse production station: no renderer (static block model; the pattern editor is a GUI, not a block entity
     * renderer) and, like the output and the terminal, an extract-only item capability.
     */
    public static final BlockEntityEntry<WarehouseProductionBlockEntity> WAREHOUSE_PRODUCTION = REGISTRATE
            .blockEntity("warehouse_production", WarehouseProductionBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_PRODUCTION)
            .register();

    /**
     * Warehouse stock keeper: no renderer (a static block model with a lit variant; the rules are edited in a GUI, not
     * drawn on the block) and <b>no capability at all</b> — it holds no items.
     */
    public static final BlockEntityEntry<WarehouseStockKeeperBlockEntity> WAREHOUSE_STOCK_KEEPER = REGISTRATE
            .blockEntity("warehouse_stock_keeper", WarehouseStockKeeperBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_STOCK_KEEPER)
            .register();

    /**
     * Warehouse home point: no renderer (a static block model with two lamp variants) and <b>no capability at all</b> —
     * it holds no items. It saves nothing either: its content is where it stands (M21, ADR-034).
     */
    public static final BlockEntityEntry<WarehouseHomePointBlockEntity> WAREHOUSE_HOME_POINT = REGISTRATE
            .blockEntity("warehouse_home_point", WarehouseHomePointBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_HOME_POINT)
            .register();

    private WareworksBlockEntityTypes() {
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} block entity types", REGISTRATE.getModid());
    }
}
