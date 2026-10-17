package dev.wareworks.content.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayLayout;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import com.simibubi.create.foundation.utility.FluidFormatter;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.warehouse.WarehouseNumber;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.data.Couple;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Display Link source "Warehouse Number" ({@code docs/warehouse-system.md} §10, ADR-026, issue #32): <b>one</b> number
 * about the whole warehouse, which a player picks in the Display Link screen — the stored items, the open requests,
 * the share of locations in use and so on ({@link WarehouseNumber}).
 * <p>
 * It exists because every other warehouse source writes several lines or a list, and a row of nixie tubes shows one:
 * "Warehouse Summary" there only ever read "Warehouse A: Ready", and "Items: 176" could not be shown at all. Some of
 * these numbers were on the controller's goggles alone, too (open requests, open production orders).
 * <p>
 * Bound to the warehouse controller and the warehouse terminal, <b>after</b> their three older sources, because the
 * Display Link screen preselects the first source a block offers: a link a player hangs there still starts on the
 * Warehouse Summary (ADR-026).
 * <p>
 * <b>A numeric single-line source</b>, like Create's own item count: Create's "Label" text box puts a player's word in
 * front of the number ({@code "Items 176"}), and a display board gets the label and the number in sections of their
 * own, with Create's {@code "Number"} layout. The number is formatted the way the goggles and the other sources format
 * it ({@link WareworksLang#number}), the share goes through the mod's one percent key ({@code "45%"}, German
 * {@code "45 %"}) and the fluid through Create's own formatter in its short form ({@code "48.0 B"}), as the Warehouse
 * Summary's fluid line does. Each of those is a component the client translates, so a nixie row, a board and a lectern
 * read the player's own language.
 * <p>
 * <b>The selector.</b> The second line of the Display Link screen picks the number, and the link saves it as an index
 * under {@link #MODE}, which is why the order of {@link WarehouseNumber} is a save format and may only grow at its end.
 * An index this build has no value for (a link saved by a later one) writes nothing at all rather than another number:
 * no number and no label, on a display board too, where Create's labelled layout would otherwise keep the label and the
 * last number ({@link #provideFlapDisplayText}, {@link #loadFlapDisplayLayout}). The selector is built in
 * {@link #initConfigurationWidgets}, which is {@code @OnlyIn(Dist.CLIENT)} like the method it overrides: on a dedicated
 * server FML removes it and its lambda from this class, so the server never sees a GUI type, exactly as with Create's
 * own {@code KineticStressDisplaySource}.
 * <p>
 * <b>Never a scan.</b> Every number is a field of the controller or a count its indexes maintain, so a pull is a
 * handful of field reads however many links hang on one warehouse (ADR-026), and it keeps Create's default refresh of
 * 100 ticks. A block that belongs to no loaded warehouse, and a controller without an aisle (no dock, or a turned
 * one), write the Warehouse Summary's own {@code "No warehouse"} rather than a {@code 0}, so a board never shows a
 * stale or invented count: such a controller still keeps its saved safety stops, which are numbers about a warehouse
 * that is not there.
 */
public class WarehouseNumberDisplaySource extends NumericSingleLineDisplaySource {
    /** The key Create's selection input saves the picked {@link WarehouseNumber#index()} under. */
    public static final String MODE = "Mode";
    /**
     * Width of the selector in the Display Link screen, the width of Create's "Label" text box above it, so the two
     * boxes line up. The names of the values have to fit it in every language ({@code WarehouseNumberTextTest}).
     */
    public static final int SELECTOR_WIDTH = 137;

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        Optional<WarehouseNumber> number = numberOf(context);
        if (number.isEmpty())
            return EMPTY_LINE;
        Optional<WarehouseControllerBlockEntity> found = WarehouseDisplays.controller(context);
        // A controller without an aisle (no dock, or a turned one) answers the same as a block of no warehouse, at the
        // point where the Warehouse Summary and the goggles stop before every count: it is not registered, so a
        // terminal of the same warehouse already says "No warehouse", and what it still keeps (its saved safety stops)
        // would be numbers about a warehouse that is not there (issue #32). The summary's status words would say more,
        // but the German ones do not fit a three-block board ("Kein Regalbediengerät"); this line does.
        if (found.isEmpty() || hasNoAisle(found.get().status()))
            return WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_NO_AISLE);
        return valueOf(found.get(), number.get());
    }

    /**
     * The board path. Create's {@code SingleLineDisplaySource#provideFlapDisplayText} puts a non-empty label in a
     * section of its own without looking at {@link #EMPTY_LINE}, and its {@code loadFlapDisplayLayout} would keep the
     * labelled layout with its number section, and with it the last number that stood there. So an index no number has
     * gets Create's own unlabelled layout and one blank section: no label and no number, as on every other target
     * (issue #32).
     */
    @Override
    public List<List<MutableComponent>> provideFlapDisplayText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (numberOf(context).isEmpty())
            return List.of(List.of(EMPTY_LINE));
        return super.provideFlapDisplayText(context, stats);
    }

    /** Create's layouts, except for an index no number has: the unlabelled one ({@link #provideFlapDisplayText}). */
    @Override
    public void loadFlapDisplayLayout(DisplayLinkContext context, FlapDisplayBlockEntity flapDisplay,
            FlapDisplayLayout layout) {
        if (numberOf(context).isPresent()) {
            super.loadFlapDisplayLayout(context, flapDisplay, layout);
            return;
        }
        String layoutKey = getFlapDisplayLayoutName(context);
        if (!layout.isLayout(layoutKey))
            layout.configure(layoutKey, List.of(createSectionForValue(context, flapDisplay.getMaxCharCount())));
    }

    /** The number the link's settings pick; empty for an index no number has (a link saved by a later build). */
    private static Optional<WarehouseNumber> numberOf(DisplayLinkContext context) {
        return WarehouseNumber.byIndex(context.sourceConfig().getInt(MODE));
    }

    /** The controller states in which it has no aisle and so no warehouse to count, as the summary decides them. */
    private static boolean hasNoAisle(ControllerStatus status) {
        return status == ControllerStatus.NO_DOCK || status == ControllerStatus.DOCK_MISALIGNED;
    }

    /**
     * The number {@code number} of {@code controller} as a display shows it. Only cached controller state: the counts
     * of its indexes, its request queue, its order book and the counts its own ticks keep.
     */
    static MutableComponent valueOf(WarehouseControllerBlockEntity controller, WarehouseNumber number) {
        StockView<ItemKey, RackPosition> stock = controller.stockIndex();
        return switch (number) {
            case ITEMS -> count(stock.totalItems());
            case ITEM_TYPES -> count(stock.distinctKeys());
            case FLUID -> fluid(controller.fluidStockIndex().totalItems());
            case LOCATIONS_USED -> count(controller.occupiedStorageLocationCount());
            case LOCATIONS_TOTAL -> count(controller.countedStorageLocationCount());
            // Both drawn from the counted locations, like the summary's "Locations: 3 / 30", so a full warehouse can
            // really read 100 %: an alias of a shared inventory is never occupied and is not in the total either, and a
            // fluid bay is in the total and counts as in use while it holds fluid.
            case LOCATIONS_USED_SHARE -> WareworksLang.percent(WarehouseNumber.share(
                    controller.occupiedStorageLocationCount(), controller.countedStorageLocationCount())).component();
            case OPEN_REQUESTS -> count(controller.openRequestCount());
            case PRODUCTION_ORDERS -> count(controller.openProductionOrderCount());
            case RULES_BELOW_MINIMUM -> count(controller.stockRulesBelowMinimum());
            case RULES_AT_MAXIMUM -> count(controller.stockRulesAtMaximum());
            case STOPPED_PRODUCTS -> count(controller.pausedStockRuleCount());
            case ACCEPTING_PORTS -> count(controller.acceptingPortCount());
            case COLLECTING_PORTS -> count(controller.collectingPortCount());
            case MISALIGNED -> count(controller.misalignedCount());
        };
    }

    private static MutableComponent count(long value) {
        return WareworksLang.number(value).component();
    }

    /**
     * Create's own short form, {@code "48.0 B"} from 1 000 mB on and plain millibuckets below, with the space the
     * Warehouse Summary's fluid line puts between number and unit. The unit is Create's {@code "B"} / {@code "mB"} in
     * every language it ships, so no German word is invented.
     */
    private static MutableComponent fluid(long millibuckets) {
        Couple<MutableComponent> amount = FluidFormatter.asComponents(millibuckets, true);
        return amount.getFirst().append(WHITESPACE).append(amount.getSecond());
    }

    /** The names of every value, in index order: the options of the selector. */
    public static List<Component> numberNames() {
        List<Component> names = new ArrayList<>(WarehouseNumber.values().length);
        for (WarehouseNumber number : WarehouseNumber.values())
            names.add(WareworksLang.translateDirect(WareworksLang.displayNumberKey(number)));
        return names;
    }

    /** Create's own "Label" text box, like its item count source: a player names what the number means. */
    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }

    /** The label box on the first line (Create's), the selector on the second, as Create's stress source does it. */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void initConfigurationWidgets(DisplayLinkContext context, ModularGuiLineBuilder builder,
            boolean isFirstLine) {
        super.initConfigurationWidgets(context, builder, isFirstLine);
        if (isFirstLine)
            return;
        builder.addSelectionScrollInput(0, SELECTOR_WIDTH, (input, label) -> input.forOptions(numberNames())
                .titled(WareworksLang.translateDirect(WareworksLang.DISPLAY_NUMBER_SHOWN)), MODE);
    }
}
