package dev.wareworks.registry;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.util.entry.BlockEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.crane.StackerCraneBlock;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.WarehouseHomePointBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.data.WareworksBlockStateGen;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

/**
 * Block (and block item) registrations.
 * <p>
 * Entries are built with {@link #REGISTRATE} in static fields. <b>Declaration order is the creative tab order</b>,
 * which follows how an aisle is built: stacker crane (dock), rail,
 * controller, interface, the three rack bays (wood, andesite, brass), the two fluid bays (copper, brass), input,
 * output, terminal, production station, stock keeper, home point (GameTest {@code creativetaborderandicon}). The bays
 * stand directly after the interface because they are the other half of the same answer: an interface turns somebody
 * else's inventory into a storage location, a bay <b>is</b> one — and the fluid bays stand directly after the item
 * ones, weakest first, because they are the same block for the other kind of goods. Recipes are hand-written JSON in
 * {@code data/wareworks/recipe/} (GameTest {@code recipesloaded}). This class must only be initialised through
 * {@link #register()}, which {@code Wareworks} calls after {@code registerEventListeners}; otherwise Registrate silently
 * drops client-side listeners.
 * <p>
 * Conventions for new blocks: warehouse state blocks use {@code .transform(WareworksTags.relocationProtected())};
 * kinetic consumers use {@code .transform(WareworksStress.configuredImpact())}. A block a Display Link may read gets
 * its sources from {@link WareworksDisplaySources} — one {@code DisplaySource.displaySource(...)} transform, or
 * {@link WareworksDisplaySources#bind} where a block offers two, so their order in the link's screen is fixed.
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
                    // Status first, so every link a player has already hung on a dock keeps its preselection (M25).
                    .transform(WareworksDisplaySources.bind(WareworksDisplaySources.CRANE_STATUS,
                            WareworksDisplaySources.CRANE_THROUGHPUT))
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "item"))
                    .register();

    /**
     * Warehouse rail ({@code docs/warehouse-system.md} §1, ADR-033). No block entity; a variant blockstate over the six
     * hand-made models in {@code models/block/warehouse_rail/} (authored along the Z axis, and on the north and east
     * sides for the junctions), picked by the rail's derived connection flags
     * ({@code WareworksBlockStateGen#railBlockProvider}). Drops itself.
     */
    public static final BlockEntry<WarehouseRailBlock> WAREHOUSE_RAIL =
            REGISTRATE.block("warehouse_rail", WarehouseRailBlock::new)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.mapColor(MapColor.METAL).sound(SoundType.METAL).noOcclusion())
                    .transform(TagGen.pickaxeOnly())
                    .blockstate(WareworksBlockStateGen.railBlockProvider())
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
                    .transform(WareworksDisplaySources.bind(WareworksDisplaySources.AISLE_SUMMARY,
                            WareworksDisplaySources.STOCK_LIST, WareworksDisplaySources.FLUID_STOCK))
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
                    .transform(DisplaySource.displaySource(WareworksDisplaySources.FILTERED_STOCK))
                    .blockstate(BlockStateGen.horizontalBlockProvider(true))
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Wooden rack bay ({@code docs/warehouse-system.md} §3.8, M28, issue #20): the cheap early bay, a better barrel,
     * buildable long before there is a warehouse. {@link #rackBay} explains what the three entries share and what is
     * still missing from them.
     */
    public static final BlockEntry<RackBayBlock> RACK_BAY_WOOD =
            rackBay("rack_bay_wood", BayTier.WOOD)
                    .initialProperties(SharedProperties::wooden)
                    .properties(p -> p.mapColor(MapColor.WOOD).sound(SoundType.WOOD))
                    .lang("Wooden Rack Bay")
                    .transform(TagGen.axeOnly())
                    .register();

    /** Andesite rack bay ({@link #rackBay}): four times a wooden one, and it may not stand on top of wood. */
    public static final BlockEntry<RackBayBlock> RACK_BAY_ANDESITE =
            rackBay("rack_bay_andesite", BayTier.ANDESITE)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.STONE).sound(SoundType.NETHERITE_BLOCK))
                    .lang("Andesite Rack Bay")
                    .transform(TagGen.pickaxeOnly())
                    .register();

    /** Brass rack bay ({@link #rackBay}): level with a fully upgraded drawer, and it carries nothing above it but brass. */
    public static final BlockEntry<RackBayBlock> RACK_BAY_BRASS =
            rackBay("rack_bay_brass", BayTier.BRASS)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW).sound(SoundType.NETHERITE_BLOCK))
                    .lang("Brass Rack Bay")
                    .transform(TagGen.pickaxeOnly())
                    .register();

    /**
     * Copper fluid bay ({@code docs/warehouse-system.md} §3.9, M30, issue #21): the rack bay of fluids, holding 64
     * buckets of one fluid — eight Create Fluid Tank blocks in one. Copper, because in Create fluids are copper.
     * {@link #fluidBay} explains what the two entries share.
     */
    public static final BlockEntry<FluidBayBlock> FLUID_BAY_COPPER =
            fluidBay("fluid_bay_copper", FluidBayTier.COPPER)
                    .initialProperties(SharedProperties::copperMetal)
                    .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).sound(SoundType.COPPER))
                    .lang("Copper Fluid Bay")
                    .register();

    /** Brass fluid bay ({@link #fluidBay}): 256 buckets, more than a 3 x 3 x 3 Create tank tower, which is 216. */
    public static final BlockEntry<FluidBayBlock> FLUID_BAY_BRASS =
            fluidBay("fluid_bay_brass", FluidBayTier.BRASS)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW).sound(SoundType.NETHERITE_BLOCK))
                    .lang("Brass Fluid Bay")
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
     * Warehouse output station, the warehouse <b>port</b> since M17 ({@code docs/warehouse-system.md} §3.2, issue #12).
     * Horizontal blockstate over the hand-made {@code models/block/warehouse_output/block.json} (authored with the aisle
     * opening facing north) plus its {@code block_accept} twin for the accepting direction; all {@code powered} variants
     * keep the same model, because a stored edge is nothing a player can see
     * ({@link WareworksBlockStateGen#warehousePortBlockProvider()}). The item model stays the requesting one, which is
     * the direction a placed port starts in. Does not conduct redstone, so neighbouring ports in a rack row are
     * triggered separately. Protected from contraptions; drops itself.
     */
    public static final BlockEntry<WarehouseOutputBlock> WAREHOUSE_OUTPUT =
            REGISTRATE.block("warehouse_output", WarehouseOutputBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .transform(DisplaySource.displaySource(WareworksDisplaySources.FILTERED_STOCK))
                    .blockstate(WareworksBlockStateGen.warehousePortBlockProvider())
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
                    .transform(WareworksDisplaySources.bind(WareworksDisplaySources.AISLE_SUMMARY,
                            WareworksDisplaySources.STOCK_LIST, WareworksDisplaySources.FLUID_STOCK))
                    .blockstate(WareworksBlockStateGen.terminalBlockProvider())
                    .item()
                    .transform(ModelGen.customItemModel("_", "item"))
                    .register();

    /**
     * Warehouse production station ({@code docs/warehouse-system.md} §3.5, ADR-024). An output-style station the crane
     * delivers the ingredients of a production order into; the player's own machinery empties it and sends the product
     * back through a warehouse input. Its own blockstate ({@link WareworksBlockStateGen#productionBlockProvider()}) over
     * the hand-made {@code models/block/warehouse_production/block.json} and its stopped twin (authored with the aisle
     * opening facing north), because since M20 the block itself says whether the safety stop is holding one of its
     * products ({@code WarehouseProductionBlock#STOPPED}); the item model uses the working block model. Does not
     * conduct redstone, like the other stations. Protected from contraptions; drops itself.
     */
    public static final BlockEntry<WarehouseProductionBlock> WAREHOUSE_PRODUCTION =
            REGISTRATE.block("warehouse_production", WarehouseProductionBlock::new)
                    .initialProperties(SharedProperties::stone)
                    .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(WareworksBlockStateGen.productionBlockProvider())
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Warehouse stock keeper ({@code docs/warehouse-system.md} §3.6, M15, issue #3). The aisle member that holds the
     * warehouse's stock rules — one item plus a minimum, a maximum and a reserve, per row. Its own blockstate
     * ({@link WareworksBlockStateGen#stockKeeperBlockProvider()}) over the hand-made
     * {@code models/block/warehouse_stock_keeper/block.json} and its lit twin (authored with the aisle side facing
     * north); the item model uses the unlit block model.
     * <p>
     * It holds no items, so a <b>schematic</b> may carry its rules along: the block is deliberately <i>not</i> in
     * {@code create:safe_nbt}, which makes Create take the {@code PartialSafeNBT} path instead and gives the keeper
     * control over what a printed copy starts with — the rules, never the comparator value it had in another warehouse
     * ({@code WarehouseStockKeeperBlockEntity#writeSafe}). A wrench pickup and a broken block drop through the loot
     * table and keep no rules, the same as a production station's patterns. Does not conduct redstone, like the other
     * members that sit in a rack row. Protected from contraptions; drops itself.
     */
    public static final BlockEntry<WarehouseStockKeeperBlock> WAREHOUSE_STOCK_KEEPER =
            REGISTRATE.block("warehouse_stock_keeper", WarehouseStockKeeperBlock::new)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(WareworksBlockStateGen.stockKeeperBlockProvider())
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * Warehouse home point ({@code docs/stacker-crane.md} §4.7, M21, issue #1, ADR-034). The rack position a stacker
     * crane with nothing to do waits at: a player places it beside the rails where the next job usually starts, and the
     * crane parks in front of it instead of standing wherever its last job left it. Without one the dock stays home.
     * <p>
     * Its own blockstate ({@link WareworksBlockStateGen#homePointBlockProvider()}) over the hand-made
     * {@code models/block/warehouse_home_point/block.json} and its two lamp twins (authored with the aisle side facing
     * north); the item model uses the dark block model. It holds no items, so there is nothing to drop but itself, and
     * it does not conduct redstone, like the other members that sit in a rack row.
     */
    public static final BlockEntry<WarehouseHomePointBlock> WAREHOUSE_HOME_POINT =
            REGISTRATE.block("warehouse_home_point", WarehouseHomePointBlock::new)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_GRAY).sound(SoundType.NETHERITE_BLOCK)
                            .isRedstoneConductor((state, level, pos) -> false))
                    .transform(TagGen.pickaxeOnly())
                    .transform(WareworksTags.relocationProtected())
                    .blockstate(WareworksBlockStateGen.homePointBlockProvider())
                    .item()
                    .transform(ModelGen.customItemModel("_", "block"))
                    .register();

    /**
     * The three rack bays share everything but their material: one {@link RackBayBlock} class carrying its
     * {@link BayTier}, protected from contraptions like every other block that holds warehouse state, and all three
     * served by the one {@code RACK_BAY} block entity type.
     * <p>
     * <b>Its look</b> (M28 step 9, M29 step 12) is a <b>multipart</b> blockstate
     * ({@link WareworksBlockStateGen#rackBayBlockProvider()}) over three hand-made models per tier: the
     * {@code block} shell — the load beam of its own level, the pallet on it and the rack's back, open towards the
     * aisle over the arm port's whole window — and the {@code upright} frame its blockstate stands at each end,
     * halved to {@code upright_half} where the next bay shares it ({@code TieredBay#LEFT}). On top of those come
     * the shared load models in {@code models/block/rack_bay/}, which draw four fill steps on the pallet. The item
     * model is {@code item.json}, the shell between <b>both</b> of its uprights — a bay standing on its own, which
     * is what a crafted one is.
     * <p>
     * <b>Its loot table is the plain block, and that is a rule rather than a default.</b> Registrate's self-drop is
     * exactly right and has to stay exactly that: no {@code copy_nbt}, no {@code setBlockEntityData}, nothing that
     * copies the block entity into the item. Breaking a bay <b>resets</b> it — an empty bay item and the whole load as
     * one {@code PalletEntity} on the floor (ADR-046) — so a loot table that carried the contents as well would hand
     * out both, which is the duplication the pallet exists to avoid, and would turn a brass bay into a pocketable
     * crate worth thirty-eight shulker boxes, which the owner rejected outright. The GameTest
     * {@code bayBreakResets} asserts that the dropped bay item is a plain, componentless one for that reason.
     */
    private static BlockBuilder<RackBayBlock, CreateRegistrate> rackBay(String name, BayTier tier) {
        return REGISTRATE.block(name, properties -> new RackBayBlock(properties, tier))
                .transform(WareworksTags.relocationProtected())
                // The aisle face is a window onto the load and the arm port is a recess, so the block is not a full
                // cube: without this it would cull its neighbours' faces and light its own interior as if it were one.
                .properties(p -> p.noOcclusion())
                .blockstate(WareworksBlockStateGen.rackBayBlockProvider())
                .item()
                .transform(ModelGen.customItemModel("_", "item"));
    }

    /**
     * The two fluid bays share everything but their material: one {@link FluidBayBlock} class carrying its
     * {@link FluidBayTier}, protected from contraptions like every other block that holds warehouse state, and both
     * served by the one {@code FLUID_BAY} block entity type. Pipes connect on every face but the one towards the
     * aisle; no <b>item</b> capability is exposed at all, which is what makes "a container reaches a bay only through
     * the crane's head or a player's hand" structural ({@link dev.wareworks.content.storage.FluidBayBlockEntity}).
     * <p>
     * <b>Its look</b> (M30 step 5) is the rack bay's own <b>multipart</b> blockstate
     * ({@link WareworksBlockStateGen#fluidBayBlockProvider()}) over three hand-made models per tier: the {@code block}
     * shell — the load beam of its own level, the rack's back, and on the beam a closed vessel open towards the aisle
     * over the width of the arm port — and the {@code upright} frame its blockstate stands at each end, halved to
     * {@code upright_half} where the next bay shares it. Those uprights are the rack bay's, pixel for pixel, because
     * joining is across families: a tank at the end of a rack wall takes over half of that wall's post. What stands
     * <b>in</b> the vessel is not a model at all — the level is the readout, so it is drawn over the fluid's own
     * still texture by {@code client.render.FluidBayRenderer} ({@link dev.wareworks.client.render.FluidBayLayout}).
     * The item model is {@code item.json}, the tank between <b>both</b> of its uprights.
     * <p>
     * <b>Its loot table is the plain block</b>, Registrate's self-drop, and that is a rule rather than a default: a
     * fluid has no drop form at all, so breaking a full fluid bay <b>loses</b> what is in it and says so in three
     * places before a player can hit it (D7) — exactly as Create's own Fluid Tank, which drops nothing either. A
     * {@code copy_nbt} on this table would instead make a pocketable 256-bucket lava supply, which is the removal
     * crate the owner refused for items in ADR-046, in its worse fluid form.
     */
    private static BlockBuilder<FluidBayBlock, CreateRegistrate> fluidBay(String name, FluidBayTier tier) {
        return REGISTRATE.block(name, properties -> new FluidBayBlock(properties, tier))
                .transform(WareworksTags.relocationProtected())
                .transform(TagGen.pickaxeOnly())
                // The aisle face is a window onto the fluid and the arm port is a recess, so the block is not a full
                // cube: without this it would cull its neighbours' faces and light its own interior as if it were one.
                .properties(p -> p.noOcclusion())
                .blockstate(WareworksBlockStateGen.fluidBayBlockProvider())
                .item()
                .transform(ModelGen.customItemModel("_", "item"));
    }

    private WareworksBlocks() {
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} blocks", REGISTRATE.getModid());
    }
}
