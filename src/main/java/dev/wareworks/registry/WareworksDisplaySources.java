package dev.wareworks.registry;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.tterrag.registrate.util.nullness.NonNullUnaryOperator;

import dev.wareworks.Wareworks;
import dev.wareworks.content.display.AisleSummaryDisplaySource;
import dev.wareworks.content.display.CraneStatusDisplaySource;
import dev.wareworks.content.display.FilteredStockDisplaySource;
import dev.wareworks.content.display.StockListDisplaySource;
import net.minecraft.world.level.block.Block;

/**
 * Create display link sources ({@code docs/warehouse-system.md} §10, M14): what a player's Display Link may read off a
 * Wareworks block and write onto nixie tubes, a display board, a sign or a lectern.
 * <p>
 * <b>Registry.</b> The registry is Create's ({@code CreateRegistries.DISPLAY_SOURCE}). Unlike the arm interaction point
 * types of M12 these are <b>not</b> built with a NeoForge {@link net.neoforged.neoforge.registries.DeferredRegister}:
 * binding a source to a block needs a Registrate {@link RegistryEntry}, which a {@code DeferredHolder} is not, so the
 * entries go through {@link CreateRegistrate#displaySource} — the same path Create's own {@code AllDisplaySources}
 * uses. It already wires the entry into {@code DisplaySource.BY_BLOCK} and {@code BY_BLOCK_ENTITY}.
 * <p>
 * <b>Order.</b> The Display Link screen preselects the first source a block offers, so the order two sources are added
 * to one block in must be reproducible. Registrate defers every {@code onRegisterAfter} callback through a
 * {@code HashMultimap}, whose iteration order is not: two separate {@code .transform(...)} calls on one block could
 * therefore swap between launches. {@link #bind(RegistryEntry, RegistryEntry)} adds both inside <b>one</b> callback,
 * which fixes the order.
 * <p>
 * <b>Never a scan.</b> Every source answers from state the controller and the crane already maintain; a pull must stay
 * cheap, because a player may hang any number of links on one controller (ADR-026).
 * <p>
 * Initialised through {@link #register()} before {@link WareworksBlocks#register()}, whose builders reference the
 * entries.
 */
public final class WareworksDisplaySources {
    private static final CreateRegistrate REGISTRATE = Wareworks.registrate();

    /** Aisle letter and status, storage locations in use, item types and total stock. */
    public static final RegistryEntry<DisplaySource, AisleSummaryDisplaySource> AISLE_SUMMARY =
            REGISTRATE.displaySource("aisle_summary", AisleSummaryDisplaySource::new).register();

    /** The most stocked item types of the aisle with their amounts, one per line. */
    public static final RegistryEntry<DisplaySource, StockListDisplaySource> STOCK_LIST =
            REGISTRATE.displaySource("stock_list", StockListDisplaySource::new).register();

    /** How many of the item in the source block's filter slot the aisle holds. */
    public static final RegistryEntry<DisplaySource, FilteredStockDisplaySource> FILTERED_STOCK =
            REGISTRATE.displaySource("filtered_stock", FilteredStockDisplaySource::new).register();

    /** What the stacker crane is doing, what it carries and where it is going. */
    public static final RegistryEntry<DisplaySource, CraneStatusDisplaySource> CRANE_STATUS =
            REGISTRATE.displaySource("crane_status", CraneStatusDisplaySource::new).register();

    private WareworksDisplaySources() {
    }

    /**
     * Registrate transformer that offers <b>two</b> sources on one block, in this order. Use it instead of two
     * {@code .transform(DisplaySource.displaySource(...))} calls; see the class comment.
     */
    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> bind(
            RegistryEntry<DisplaySource, ? extends DisplaySource> first,
            RegistryEntry<DisplaySource, ? extends DisplaySource> second) {
        return builder -> builder.onRegisterAfter(CreateRegistries.DISPLAY_SOURCE, block -> {
            DisplaySource.BY_BLOCK.add(block, first.get());
            DisplaySource.BY_BLOCK.add(block, second.get());
        });
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} display sources", REGISTRATE.getModid());
    }
}
