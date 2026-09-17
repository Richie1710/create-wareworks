package dev.wareworks.registry;

import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.util.entry.BlockEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.crane.StackerCraneBlock;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.data.WareworksBlockStateGen;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

/**
 * Block (and block item) registrations.
 * <p>
 * Entries are built with {@link #REGISTRATE} in static fields. <b>Declaration order is the creative tab order</b>,
 * which follows how an aisle is built: stacker crane (dock), rail,
 * controller, interface, input, output, terminal, production station (GameTest {@code creativetaborderandicon}). Recipes are hand-written JSON in
 * {@code data/wareworks/recipe/} (GameTest {@code recipesloaded}). This class must only be initialised through
 * {@link #register()}, which {@code Wareworks} calls after {@code registerEventListeners}; otherwise Registrate silently
 * drops client-side listeners.
 * <p>
 * Conventions for new blocks: warehouse state blocks use {@code .transform(WareworksTags.relocationProtected())};
 * kinetic consumers use {@code .transform(WareworksStress.configuredImpact())}.
 */
public final class WareworksBlocks {
    private static final CreateRegistrate REGISTRATE = Wareworks.registrate();

    /**
     * Stacker crane dock ({@code docs/stacker-crane.md} §2). Kinetic consumer with the configured stress impact,
     * horizontal blockstate over the hand-made {@code models/block/stacker_crane/block.json} (the low rail bed the parked
     * crane stands on, authored with the aisle side facing north; the moving crane is drawn by its renderer, §7). The item
     * model is the hand-made {@code models/block/stacker_crane/item.json} (bed with a miniature crane); it is also the
     * creative tab icon. Protected from contraptions; drops itself.
     */
    public static final BlockEntry<StackerCraneBlock> STACKER_CRANE =
            REGISTRATE.block("stacker_crane", StackerCraneBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.PODZOL).sound(SoundType.NETHERITE_BLOCK).noOcclusion())
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .transform(WareworksStress.configuredImpact())
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "item"))
                    .register();

    /**
     * Warehouse rail ({@code docs/warehouse-system.md} §1). No block entity; horizontal axis blockstate over the
     * hand-made {@code models/block/warehouse_rail/block.json} (authored along the Z axis). Drops itself.
     */
    public static final BlockEntry<WarehouseRailBlock> WAREHOUSE_RAIL =
            REGISTRATE.block("warehouse_rail", WarehouseRailBlock::new)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.mapColor(MapColor.METAL).sound(SoundType.METAL).noOcclusion())
                    .transform(TagGen.pickaxeOnly())
                    .blockstate(BlockStateGen.horizontalAxisBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Warehouse controller ({@code docs/warehouse-system.md} §3.3). Stands behind the dock and faces it; horizontal
     * blockstate over the hand-made {@code models/block/warehouse_controller/block.json} (authored with the dock side
     * facing north, display on the south side). Protected from contraptions; drops itself.
     */
    public static final BlockEntry<WarehouseControllerBlock> WAREHOUSE_CONTROLLER =
            REGISTRATE.block("warehouse_controller", WarehouseControllerBlock::new)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW).sound(SoundType.NETHERITE_BLOCK))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Warehouse interface ({@code docs/warehouse-system.md} §3.1). Horizontal blockstate over the hand-made
     * {@code models/block/warehouse_interface/block.json} (authored with its port facing north); the item model uses
     * the same block model. Drops itself (Registrate default loot).
     */
    public static final BlockEntry<WarehouseInterfaceBlock> WAREHOUSE_INTERFACE =
            REGISTRATE.block("warehouse_interface", WarehouseInterfaceBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.PODZOL).sound(SoundType.NETHERITE_BLOCK))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Warehouse input station ({@code docs/warehouse-system.md} §3.2). Horizontal blockstate over the hand-made
     * {@code models/block/warehouse_input/block.json} (authored with the aisle opening facing north). Does not conduct
     * redstone, so a signal meant for a neighbouring output never passes through it. Protected from contraptions; drops
     * itself.
     */
    public static final BlockEntry<WarehouseInputBlock> WAREHOUSE_INPUT =
            REGISTRATE.block("warehouse_input", WarehouseInputBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.STONE).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Warehouse output station ({@code docs/warehouse-system.md} §3.2). Horizontal blockstate (all {@code powered}
     * variants use the same model) over the hand-made {@code models/block/warehouse_output/block.json} (authored with
     * the aisle opening facing north). Does not conduct redstone, so neighbouring outputs in a rack row are triggered
     * separately. Protected from contraptions; drops itself.
     */
    public static final BlockEntry<WarehouseOutputBlock> WAREHOUSE_OUTPUT =
            REGISTRATE.block("warehouse_output", WarehouseOutputBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Warehouse terminal ({@code docs/warehouse-system.md} §3.4, ADR-018; redesigned in M10 by ADR-022). An
     * output-style station with a screen: the player requests items here and the crane delivers them into its buffer.
     * <p>
     * It is the one block with <b>two</b> independent horizontal directions (the crane's intake port and the screen),
     * which no rotated single model can express, so it gets a <b>multipart</b> blockstate over the hand-made shells in
     * {@code models/block/warehouse_terminal/} (all authored on the north face) and its own hand-made
     * {@code item.json}. Does not conduct redstone, like the other stations. Protected from contraptions; drops itself.
     */
    public static final BlockEntry<WarehouseTerminalBlock> WAREHOUSE_TERMINAL =
            REGISTRATE.block("warehouse_terminal", WarehouseTerminalBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(WareworksBlockStateGen.terminalBlockProvider())
                    .item()
                    .transform(ModelGen.customItemModel("_", "item"))
                    .register();

    /**
     * Warehouse production station ({@code docs/warehouse-system.md} §3.5, ADR-024). An output-style station the crane
     * delivers the ingredients of a production order into; the player's own machinery empties it and sends the product
     * back through a warehouse input. Horizontal blockstate over the hand-made
     * {@code models/block/warehouse_production/block.json} (authored with the aisle opening facing north). Does not
     * conduct redstone, like the other stations. Protected from contraptions; drops itself.
     */
    public static final BlockEntry<WarehouseProductionBlock> WAREHOUSE_PRODUCTION =
            REGISTRATE.block("warehouse_production", WarehouseProductionBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    private WareworksBlocks() {
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} blocks", REGISTRATE.getModid());
    }
}
