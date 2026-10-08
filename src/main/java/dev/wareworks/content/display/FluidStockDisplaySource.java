package dev.wareworks.content.display;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.mutable.MutableInt;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.ValueListDisplaySource;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayLayout;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import com.simibubi.create.foundation.utility.FluidFormatter;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.StockView;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.IntAttached;
import net.minecraft.network.chat.MutableComponent;

/**
 * Display Link source "Fluid Stock" ({@code docs/warehouse-system.md} §10, §3.9, M30, issue #21, D10): the fluids one
 * warehouse holds in its fluid bays, with the amount of each, one per line.
 * <p>
 * Bound to the warehouse controller and the warehouse terminal, beside "Warehouse Summary" and "Stock List".
 * <p>
 * <b>A source of its own and not two kinds of row inside "Stock List".</b> That list renders one {@code IntAttached}
 * column, so a shared source would print {@code 64} (iron ingots) next to {@code 64000} (millibuckets of lava) in the
 * same column and a reader would take them for one scale. Two sources are also the issue's own answer to "what does
 * the warehouse hold": {@code lava: 64 buckets} <b>and</b> {@code bucket: 17}, with nothing pretending one is the
 * other.
 * <p>
 * <b>The number is millibuckets and the unit travels with it</b>, which is Create's own {@code FluidListDisplaySource}
 * verbatim ({@code FluidFormatter.asComponents}). The mod counts fluid in millibuckets, because a bottle is 250 mB and
 * buckets cannot express it, and nobody reads 256 000 — so the Display Link's own "shortened / full number" switch is
 * what chooses between {@code "64.0 B"} and {@code "64000 mB"}, and {@code create.generic.unit.buckets} is {@code "B"}
 * and {@code create.generic.unit.millibuckets} is {@code "mB"} in every language Create ships. Truncating to whole
 * buckets here instead would have had to answer {@code 0} for a bay holding half a bucket, which reads as "no lava"
 * about a warehouse that has some.
 * <p>
 * Two fluids with the same amount are ordered by {@link FluidKey#ORDER}, the rule {@link StockListDisplaySource}
 * already follows for items: the index keeps its keys in a {@code HashSet}, whose iteration order may differ between
 * launches, so without a value-based tie-break the display would swap two equally stocked rows for no reason. That
 * order is deliberately not {@code FluidKey#hashCode()}, which mixes in a fluid's identity hash and changes with every
 * restart.
 * <p>
 * Everything comes from the fluid stock index the controller already maintains, so a pull is a handful of map reads
 * and never a world search or a tank scan (hard rule, ADR-026).
 */
public class FluidStockDisplaySource extends ValueListDisplaySource {
    /** Characters the unit section holds: {@code "mB"} is the longest Create ships, in every language. */
    private static final int UNIT_CHARS = 2;
    /** Characters the number section never drops below, so a one-digit amount still has a column to sit in. */
    private static final int MIN_NUMBER_CHARS = 3;

    @Override
    protected Stream<IntAttached<MutableComponent>> provideEntries(DisplayLinkContext context, int maxRows) {
        Optional<WarehouseControllerBlockEntity> found = WarehouseDisplays.controller(context);
        if (found.isEmpty())
            return Stream.empty();

        StockView<FluidKey, RackPosition> fluid = found.get().fluidStockIndex();
        Map<FluidKey, Long> totals = new HashMap<>();
        for (FluidKey key : fluid.keys())
            totals.put(key, fluid.count(key));

        return KeyCount.largestFirst(totals, maxRows, FluidKey.ORDER).stream()
                // The index counts in long and IntAttached carries an int; 2 147 483 buckets is 8 388 brass bays.
                .map(entry -> IntAttached.with((int) Math.min(Integer.MAX_VALUE, entry.count()),
                        entry.key().hoverName().copy()));
    }

    /**
     * Amount, unit, then the fluid's name — the row Create's own fluid list writes, built by the same formatter, so a
     * player who has read a Smart Observer on a tank reads this without learning anything new.
     * <p>
     * The separating space is <b>in front of the name</b> and not behind the unit, which is the one place this differs
     * from Create's fluid list. A text target (a sign, a lectern, a nixie row) is handed the three components
     * concatenated, so a space behind the unit is the only thing that keeps that reading from being
     * {@code "48.0BLava"}; and it cannot go behind the unit, because the unit is <b>one flap</b> whose face is matched
     * against Create's own {@code fluid_units} cycle, which has exactly {@code "mB"} and {@code "B "} in it. In front
     * of the name it costs a text target nothing and a board one blank flap, and the row reads {@code "48.0B Lava"} —
     * the same shape as the {@code "64K Iron Ingot"} of every other value list.
     */
    @Override
    protected List<MutableComponent> createComponentsFromEntry(DisplayLinkContext context,
            IntAttached<MutableComponent> entry) {
        Couple<MutableComponent> formatted = FluidFormatter.asComponents(entry.getFirst(), shortenNumbers(context));
        return List.of(formatted.getFirst(), formatted.getSecond(),
                WHITESPACE.copy().append(entry.getSecond()));
    }

    /**
     * Three sections — number, unit, name — because the inherited layout knows nothing of a unit and would give the
     * {@code "B"} no flaps of its own. Create's fluid list does exactly this; the only difference is that the number
     * section is sized from the <b>widest</b> row of this pull, which {@link ValueListDisplaySource} collects into
     * {@code flapDisplayContext} while it builds them.
     */
    @Override
    public void loadFlapDisplayLayout(DisplayLinkContext context, FlapDisplayBlockEntity flapDisplay,
            FlapDisplayLayout layout) {
        boolean shorten = shortenNumbers(context);
        int widest = ((MutableInt) context.flapDisplayContext).intValue();
        // Measured off the widest row of this pull rather than guessed from the number: Create's formatter decides
        // whether that row reads "256.0" or "256000", and asking it is exact in every language.
        int digits = FluidFormatter.asComponents(widest, shorten).getFirst().getString().length();
        String layoutKey = "WareworksFluidStock_" + shorten + "_" + digits;
        if (layout.isLayout(layoutKey))
            return;

        int maxCharCount = flapDisplay.getMaxCharCount(1);
        int numberChars = Math.min(maxCharCount, Math.max(MIN_NUMBER_CHARS, digits));
        int nameChars = Math.max(maxCharCount - numberChars - UNIT_CHARS, 0);
        FlapDisplaySection value =
                new FlapDisplaySection(FlapDisplaySection.MONOSPACE * numberChars, "number", false, false)
                        .rightAligned();
        FlapDisplaySection unit =
                new FlapDisplaySection(FlapDisplaySection.MONOSPACE * UNIT_CHARS, "fluid_units", true, true);
        FlapDisplaySection name =
                new FlapDisplaySection(FlapDisplaySection.MONOSPACE * nameChars, "alphabet", false, false);
        layout.configure(layoutKey, List.of(value, unit, name));
    }

    /** The amount comes first, like the stock list's rows and like Create's own fluid list. */
    @Override
    protected boolean valueFirst() {
        return true;
    }
}
