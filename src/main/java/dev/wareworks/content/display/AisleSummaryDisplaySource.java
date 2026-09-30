package dev.wareworks.content.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.NetworkGoggleInfo;
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

        // Which aisles the warehouse is made of (M21, issue #1, ADR-033), and only for one that really bends: a
        // display has few rows, and on a straight aisle this line would repeat the letter the line above already
        // carries. A discovery that stopped short of what a player laid is marked rather than explained — the reason
        // takes a sentence, and the controller's goggles and the log are where a sentence belongs.
        controller.networkInfo().filter(network -> network.aisleCount() > 1).ifPresent(network -> lines.add(
                WareworksLang.translateDirect(network.stopsShort() ? WareworksLang.DISPLAY_AISLE_LINE_AISLES_CUT
                        : WareworksLang.DISPLAY_AISLE_LINE_AISLES, aisleLetters(network))));

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
        // Only for an aisle that really has an accepting warehouse port (M17, issue #12), by the same rule the stock
        // rule lines below follow: a display has few rows, and "Ports: 0 accepting" would push a number a player asked
        // for off a four-tube board. It is the count the controller's goggles show, so both surfaces agree, and it is
        // the other explanation — next to "at max" — for a warehouse input that is backing up. The count is a cached
        // field on the controller, so a pull stays a handful of field reads (ADR-026).
        // Since M18 (issue #13) the same line carries the collecting ports as well, each half only while it is above 0:
        // an aisle with only collecting ports says "Ports: 2 collecting", one with both says both, and one with neither
        // says nothing at all — which is what keeps a zero from pushing a number a player asked for off the board.
        int accepting = controller.acceptingPortCount();
        int collecting = controller.collectingPortCount();
        if (accepting > 0 && collecting > 0)
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_PORTS_BOTH,
                    WareworksLang.number(accepting), WareworksLang.number(collecting)));
        else if (accepting > 0)
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_PORTS,
                    WareworksLang.number(accepting)));
        else if (collecting > 0)
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_PORTS_COLLECTING,
                    WareworksLang.number(collecting)));
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
        // it is the only state here that asks a player to go and look at a machine, and a board that shows it at all
        // has to show it where it cannot be read as one more statistic. Left out entirely while nothing is stopped,
        // which is every warehouse that is working.
        //
        // It deliberately sits outside the stock-rule block above and no longer speaks of rules (M20, issue #4): since
        // a lost batch of any order arms the stop, the item it holds is regularly an intermediate of a chain that no
        // rule governs, and this line can stand on an aisle with no stock keeper at all. The count is the controller's
        // own cached one, and the wording is the production station's ("Stopped products"), because that station is
        // where a player lifts it.
        if (controller.pausedStockRuleCount() > 0)
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_STOPPED,
                    WareworksLang.number(controller.pausedStockRuleCount())));
        // Only while the aisle really holds chunks (M19, issue #10), by the same rule the port and rule lines follow: a
        // display has few rows, and on a server with chunk loading switched off — the default — this line would always
        // read 0 and push a number a player asked for off a four-tube board. It reads two cached controller fields, so a
        // pull is still a handful of field reads (ADR-026).
        if (controller.chunkKeepReason().isHolding())
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_CHUNKS,
                    WareworksLang.number(controller.chunkKeepChunks())));
        return WarehouseDisplays.limit(lines, stats);
    }

    /**
     * The aisle letters of a warehouse, space-separated and in aisle order — "A B C". Letters only: a display row is
     * short, and the lengths belong on the controller's goggles, which have the room for them.
     */
    private static String aisleLetters(NetworkGoggleInfo network) {
        StringBuilder letters = new StringBuilder(2 * network.aisleCount());
        for (int aisle = 0; aisle < network.aisleCount(); aisle++) {
            if (aisle > 0)
                letters.append(' ');
            letters.append(network.letterOf(aisle).map(String::valueOf).orElse("?"));
        }
        return letters.toString();
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
