package dev.wareworks.content.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.utility.FluidFormatter;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.NetworkGoggleInfo;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.data.Couple;
import net.minecraft.network.chat.MutableComponent;

/**
 * Display Link source "Warehouse Summary" ({@code docs/warehouse-system.md} §10): the warehouse's letter and status,
 * the counted inventories in use, the item types and its total stock — <b>four lines on every warehouse</b> — and below
 * them, only when there is something to say, the fluid it holds, the aisles it is made of, its ports, its stock rules,
 * its stopped products, its held chunks and last of all the names a player gave its aisles. Every one of those is
 * below the four, because a four-tube board shows four rows and {@link WarehouseDisplays#limit} drops the tail: an optional line above
 * them would cost a player a number they asked for.
 * <p>
 * The order of the optional lines is the order in which a short board may lose them, youngest loss last: the names are
 * a label a player chose, while every line above them is a count they asked for or a state they have to act on.
 * <p>
 * It speaks for the <b>whole warehouse</b>, not for one of its aisles — one controller owns every aisle of its rail
 * network and keeps one stock index for all of them (ADR-033) — which is why its first line and its name say
 * "Warehouse A" where they said "Aisle A" before M22. The registry path stays {@code aisle_summary}, because renaming
 * it would silently unbind every Display Link a player has already pointed at a controller.
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
        // What the warehouse holds as FLUID (M30, issue #21, D10), only while it really holds some — which no
        // warehouse without a fluid bay does, so a board that stood on a warehouse built before M30 shows what it
        // showed.
        //
        // The FIRST of the optional lines, above the aisles line, and that is a decision about which row a short board
        // keeps rather than a place. Everything above it is a stock number a player asked for, and this is the fifth of
        // them; a board is hung on a warehouse that holds fluid BECAUSE of this row. The rows below it — the shape of
        // the rails, the ports, the rules, a diagnosis, a label — each sit below the one before them for that same
        // reason, youngest loss last.
        //
        // The amount is pre-formatted by Create's own FluidFormatter, the way its fluid list source formats a tank, so
        // the unit is Create's "B" / "mB" in every language it ships and no German word is invented. It is asked for
        // the SHORTENED form always: this source extends DisplaySource and therefore offers no "shortened / full
        // number" switch at all - that widget and the shortenNumbers() it feeds live on Create's
        // ValueListDisplaySource - and a board row is short. Shortening is only a question above 1 000 mB anyway:
        // asComponents falls back to plain millibuckets below that. The breakdown per fluid is the "Fluid Stock"
        // source; this is the one-row answer to "does this warehouse hold fluid at all, and how much".
        //
        // Both numbers come from the controller's parallel fluid index, so a pull stays a handful of map reads
        // (ADR-026).
        StockView<FluidKey, RackPosition> fluid = controller.fluidStockIndex();
        if (fluid.distinctKeys() > 0) {
            Couple<MutableComponent> amount = FluidFormatter.asComponents(fluid.totalItems(), true);
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_FLUIDS,
                    WareworksLang.number(fluid.distinctKeys()),
                    amount.getFirst().append(DisplaySource.WHITESPACE).append(amount.getSecond())));
        }
        // Which aisles the warehouse is made of (M21, issue #1, ADR-033), for one that really bends or splits: on a
        // straight aisle this line would only repeat the letter the first line already carries. A discovery that
        // stopped short of what a player laid is marked rather than explained — the reason takes a sentence, and the
        // controller's goggles and the log are where a sentence belongs; that mark is why a *one*-aisle warehouse gets
        // the line too as soon as it stops short (M22, issue #2), because every reason that is left is a maximum, a
        // chunk that is not loaded or a second dock, and all of them cut a straight aisle as easily as a comb.
        //
        // It sits here, below the three core numbers and above the other optional lines, for the reason every optional
        // line in this method sits below them: the four core lines are what a four-tube board shows, WarehouseDisplays
        // drops the tail, and a line inserted above them pushes "Items: N" — a number a player asked for — off the
        // board. It used to be inserted second, which cost a bending warehouse its item count all along and, once the
        // mark widened the gate, a perfectly straight one that had merely run into a maximum (M22 review fix).
        controller.networkInfo().filter(network -> network.aisleCount() > 1 || network.stopsShort())
                .ifPresent(network -> lines.add(
                        WareworksLang.translateDirect(network.stopsShort() ? WareworksLang.DISPLAY_AISLE_LINE_AISLES_CUT
                                : WareworksLang.DISPLAY_AISLE_LINE_AISLES, aisleLetters(network))));
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
        // The aisles a player named (M25, issue #15, ADR-038), as "A Ores · B Metals".
        //
        // LAST of all, and that is a decision and not a place (M25 review fix). WarehouseDisplays.limit drops the
        // tail, so whichever line is last is the one a short board loses — and everything above it is either a number
        // a player asked for or a state they have to go and look at a machine for ("Stopped products" above is the
        // sharpest of those). A label a player chose is the one row this source can afford to lose, so it is the row
        // that goes. It used to sit under the aisles line, where naming an aisle could push a diagnosis off a board
        // that was already full.
        //
        // It is left out entirely while no aisle has a name, which is every warehouse before M25. The names come
        // straight from the controller rather than from the synced record, because this runs on the server, and from
        // namedAisles() rather than from the whole saved table, because a label outlives the aisle it was given to
        // and a board names rows of THIS warehouse.
        //
        // The row is bounded by the target's own characters as well as by NetworkGoggleInfo.NAMES_LISTED entries, and
        // says how many names it had no room for: six 16-character names are 113 characters, where this board row
        // holds 26 (see WareworksLang.aisleNamesLine).
        //
        // The LETTER stays in front of every name. A name on its own could not be matched to the address the terminal,
        // the crane's lines and every report speak, and a board that says "Ores" without saying which aisle that is
        // would be the one surface of this feature a player cannot act on.
        SortedMap<Character, String> names = controller.namedAisles();
        if (!names.isEmpty())
            lines.add(WareworksLang.aisleNamesLine(names, NetworkGoggleInfo.NAMES_LISTED, stats.maxColumns()));
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
