package dev.wareworks.content.display;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Display Link source "Stock of the Filtered Item" ({@code docs/warehouse-system.md} §10): how many of the item in the
 * source block's filter slot the whole aisle holds.
 * <p>
 * Bound to the warehouse output (its request filter) and the warehouse interface (its store filter). The filter item
 * <b>is</b> the configuration, so the source has no setting of its own beyond Create's generic label text box.
 * <p>
 * It reports the <b>stored</b> total, not what a new request could still claim: the available amount moves with every
 * reservation, so a display of it would count down and back up while the crane works.
 * <p>
 * It reads {@code 0} for an empty filter slot, for a Create list, attribute or package filter (an interface accepts
 * those, they select no single item type to count) and for a block that belongs to no loaded aisle. A display therefore
 * cannot tell "none in stock" from "not configured" — keeping the line numeric is what lets a display board use its
 * number layout.
 */
public class FilteredStockDisplaySource extends NumericSingleLineDisplaySource {
    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        ItemStack filter = filterOf(context.getSourceBlockEntity());
        if (filter.isEmpty() || filter.getItem() instanceof FilterItem)
            return ZERO.copy();

        Optional<WarehouseControllerBlockEntity> controller = WarehouseDisplays.controller(context);
        if (controller.isEmpty())
            return ZERO.copy();
        return WareworksLang.number(controller.get().countOf(ItemKey.of(filter))).component();
    }

    /** The filter slot of the source block; empty for anything else. */
    private static ItemStack filterOf(@Nullable BlockEntity source) {
        if (source instanceof WarehouseOutputBlockEntity output)
            return output.requestedItem();
        if (source instanceof WarehouseInterfaceBlockEntity storage)
            return storage.storeFilter();
        return ItemStack.EMPTY;
    }

    /** Create's own "Label" text box, like its item count source: a player names what the number means. */
    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }
}
