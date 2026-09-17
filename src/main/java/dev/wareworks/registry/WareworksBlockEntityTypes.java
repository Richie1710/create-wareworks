package dev.wareworks.registry;

import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.client.render.StackerCraneRenderer;
import dev.wareworks.client.render.WarehouseInterfaceRenderer;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
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
     * Warehouse output: Create's {@code SmartBlockEntityRenderer} draws the item in the request filter slot (the only
     * renderer registration for this type; the lambda is only evaluated on the client). Item capability: extract-only
     * view.
     */
    public static final BlockEntityEntry<WarehouseOutputBlockEntity> WAREHOUSE_OUTPUT = REGISTRATE
            .blockEntity("warehouse_output", WarehouseOutputBlockEntity::new)
            .validBlocks(WareworksBlocks.WAREHOUSE_OUTPUT)
            .renderer(() -> SmartBlockEntityRenderer::new)
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

    private WareworksBlockEntityTypes() {
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} block entity types", REGISTRATE.getModid());
    }
}
