package dev.wareworks.content.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.chat.MutableComponent;

/**
 * Display Link source "Aisle Summary" ({@code docs/warehouse-system.md} §10): the aisle letter and status, the counted
 * inventories in use, the item types and the total stock of one aisle.
 * <p>
 * Bound to the warehouse controller and the warehouse terminal, so a display can stand at the controller itself or at
 * the terminal players walk to. Everything comes from state the controller already maintains — the membership counts and
 * the stock index — so a pull is a handful of field reads, never an inventory scan (hard rule, ADR-026).
 */
public class AisleSummaryDisplaySource extends DisplaySource {
    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        Optional<WarehouseControllerBlockEntity> found = WarehouseDisplays.controller(context);
        if (found.isEmpty())
            return WarehouseDisplays.limit(List.of(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_NO_AISLE)),
                    stats);

        WarehouseControllerBlockEntity controller = found.get();
        ControllerStatus status = controller.status();
        List<MutableComponent> lines = new ArrayList<>(4);
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_AISLE,
                String.valueOf(controller.aisleLetter()), WareworksLang.translateDirect(statusKey(status))));

        // Without a dock there is no aisle, so every count below would read 0 and say nothing: the goggle tooltip of a
        // controller stops at the same point.
        if (status == ControllerStatus.NO_DOCK || status == ControllerStatus.DOCK_MISALIGNED)
            return WarehouseDisplays.limit(lines, stats);

        StockView<ItemKey, RackPosition> stock = controller.stockIndex();
        // Both numbers must be drawn from the same population, or the line could never read full: an alias of a shared
        // inventory (two interfaces on one double chest) is indexed with empty counts and is therefore never occupied.
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_LOCATIONS,
                WareworksLang.number(stock.occupiedLocations()),
                WareworksLang.number(controller.countedStorageLocationCount())));
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_ITEM_TYPES,
                WareworksLang.number(stock.distinctKeys())));
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_ITEMS,
                WareworksLang.number(stock.totalItems())));
        // Only for an aisle that really has stock rules (M15, issue #3): a display has few rows, and a line reading
        // "Rules: 0 · below min 0 · at max 0" would push a number a player asked for off a four-tube board. All three
        // counts are the controller's own cached ones, so a pull stays a handful of field reads (ADR-026). The
        // at-maximum count is the one that explains a warehouse input backing up, so a board that watches the aisle
        // must be able to show it rather than only the goggles (M15 review fix).
        if (controller.governingStockRuleCount() > 0)
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_RULES,
                    WareworksLang.number(controller.governingStockRuleCount()),
                    WareworksLang.number(controller.stockRulesBelowMinimum()),
                    WareworksLang.number(controller.stockRulesAtMaximum())));
        // The safety stop gets a line of its own rather than a fourth number on the one above (M15 part 2, issue #3):
        // it is the only rule state that asks a player to go and look at a machine, and a board that shows it at all
        // has to show it where it cannot be read as one more statistic. Left out entirely while nothing is paused,
        // which is every warehouse that is working.
        if (controller.pausedStockRuleCount() > 0)
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_RULES_PAUSED,
                    WareworksLang.number(controller.pausedStockRuleCount())));
        return WarehouseDisplays.limit(lines, stats);
    }

    /** The short status text of a display, not the goggle sentence. */
    private static String statusKey(ControllerStatus status) {
        return switch (status) {
            case READY -> WareworksLang.DISPLAY_AISLE_STATUS_READY;
            case NO_DOCK -> WareworksLang.DISPLAY_AISLE_STATUS_NO_DOCK;
            case DOCK_MISALIGNED -> WareworksLang.DISPLAY_AISLE_STATUS_DOCK_MISALIGNED;
            case NO_RAILS -> WareworksLang.DISPLAY_AISLE_STATUS_NO_RAILS;
        };
    }
}
