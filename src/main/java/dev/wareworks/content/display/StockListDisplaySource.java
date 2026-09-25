package dev.wareworks.content.display;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.ValueListDisplaySource;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.StockView;
import net.createmod.catnip.data.IntAttached;
import net.minecraft.network.chat.MutableComponent;

/**
 * Display Link source "Stock List" ({@code docs/warehouse-system.md} §10): the most stocked item types of one aisle with
 * their amounts, one per line.
 * <p>
 * Bound to the warehouse controller and the warehouse terminal. Create's {@link ValueListDisplaySource} owns everything
 * below the entries — the row limit of the target, the page condensation of a lectern, the shortened/full number option
 * and the two-column flap display layout — so this class only has to name the entries.
 * <p>
 * It lists what is <b>stored</b>, not what the aisle could produce: an item a production station has a pattern for but
 * no stock of appears in a terminal's offer list, never on a stock display.
 * <p>
 * Two item types with the same amount are ordered by {@link ItemKey#ORDER}, the same rule
 * {@code TerminalStockEntry.ORDER} uses: the stock index keeps its keys in a {@code HashSet}, whose iteration order may
 * differ between launches, so without a value-based tie-break the display would swap two equally stocked lines for no
 * reason.
 */
public class StockListDisplaySource extends ValueListDisplaySource {
    @Override
    protected Stream<IntAttached<MutableComponent>> provideEntries(DisplayLinkContext context, int maxRows) {
        Optional<WarehouseControllerBlockEntity> found = WarehouseDisplays.controller(context);
        if (found.isEmpty())
            return Stream.empty();

        StockView<ItemKey, RackPosition> stock = found.get().stockIndex();
        Map<ItemKey, Long> totals = new HashMap<>();
        for (ItemKey key : stock.keys())
            totals.put(key, stock.count(key));

        return KeyCount.largestFirst(totals, maxRows, ItemKey.ORDER).stream()
                // The aisle counts in long, IntAttached carries an int; no display shows more than that anyway.
                .map(entry -> IntAttached.with((int) Math.min(Integer.MAX_VALUE, entry.count()),
                        entry.key().getItem().getDescription().copy()));
    }

    /** Amount first, then the item name, like Create's own item list source. */
    @Override
    protected boolean valueFirst() {
        return true;
    }
}
