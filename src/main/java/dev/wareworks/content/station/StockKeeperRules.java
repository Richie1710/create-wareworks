package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleAdjustment;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The editable rule rows of one warehouse stock keeper ({@code docs/warehouse-system.md} §3.6, M15, issue #3).
 * <p>
 * <b>A row is a rule or nothing.</b> Each row holds either a complete {@link StockRule} — one item plus the three
 * numbers — or nothing at all. The item comes first: a row without an item has no numbers to set, and clearing the
 * item clears the row. That keeps every stored row a rule the core layer can judge without a half-written state, the
 * way {@code ProductionPatterns} keeps a draft grid but never hands out an incomplete {@code ProductionPattern}.
 * <p>
 * <b>Nothing here moves an item.</b> The item of a row is a ghost: only its {@link ItemKey} is stored, taken from what
 * the player carries or clicks. Editing a rule never takes anything out of an inventory, which is why the whole
 * configuration surface can be driven by one validated payload.
 * <p>
 * <b>Numbers are clamped on the way in, never on the way out</b> ({@link StockRule}'s canonical constructor): a value
 * outside {@code -1 … } {@link StockRule#MAX_AMOUNT} is pulled into range, a minimum above the maximum raises the
 * maximum, a reserve above the maximum lowers the reserve. Every edit reports the one correction it had to make
 * ({@link StockRuleAdjustment}), so a player is told when the value they scrolled to is not the value that was stored.
 * <p>
 * <b>Bounded loading.</b> Save data is untrusted (block entity data on items, {@code /data merge}, schematics), so a
 * load reads at most {@value #MAX_ROWS} rows, clamps every number and drops an entry whose item cannot be decoded.
 * Reading never throws, and a lowered {@code stockKeeperRows} never loses a rule: a keeper that was saved with more rows
 * <b>keeps them all</b> — it stays as tall as it was written, and the new value applies to keepers placed from now on
 * ({@link #load}). {@code maxStockRules} is the knob that bounds what an existing build applies.
 * <p>
 * Server thread only, except for the read-only accessors a menu uses to build its screen state.
 */
public final class StockKeeperRules {
    /** Hard cap on rule rows of one keeper, whatever {@code stockKeeperRows} says. */
    public static final int MAX_ROWS = 16;

    /** The NBT key the block entity stores {@link #save} under. */
    public static final String RULES_TAG = "Rules";

    /** {@code field} of an edit that sets or clears the row's item. */
    public static final int FIELD_ITEM = 0;
    /** {@code field} of an edit that sets the row's minimum. */
    public static final int FIELD_MINIMUM = 1;
    /** {@code field} of an edit that sets the row's maximum. */
    public static final int FIELD_MAXIMUM = 2;
    /** {@code field} of an edit that sets the row's reserve. */
    public static final int FIELD_RESERVE = 3;
    /** {@code field} of an edit that clears a whole row, item and numbers together. */
    public static final int FIELD_CLEAR_ROW = -1;
    /**
     * {@code field} of an edit that lets a paused rule order again (M15 part 2, issue #3). It changes no number, so
     * it is handled by the block entity and never reaches {@link #apply}: the pause lives in the controller, which is
     * the only thing that is always loaded when an order could be started.
     */
    public static final int FIELD_RESUME = 4;

    private static final String ROW = "Row";
    private static final String ITEM = "Item";
    private static final String MINIMUM = "Min";
    private static final String MAXIMUM = "Max";
    private static final String RESERVE = "Reserve";

    /**
     * What one edit did.
     *
     * @param changed    whether the stored rules really changed (a no-op edit saves and syncs nothing)
     * @param adjustment the one correction the stored value needed, {@link StockRuleAdjustment#NONE} when none
     * @param resumed    whether this edit also lifted the <b>safety stop</b> of the rule it touched (M15 part 2,
     *                   issue #3). Re-writing a rule is one of the ways back from it, and re-arming an automatic order
     *                   is not something that should happen silently: the screen says so
     */
    public record Edit(boolean changed, StockRuleAdjustment adjustment, boolean resumed) {
        /** Nothing happened: an out-of-range row, an unknown field, or a value that was already stored. */
        public static final Edit NONE = new Edit(false, StockRuleAdjustment.NONE, false);

        public Edit {
            Objects.requireNonNull(adjustment, "adjustment");
        }

        /** An edit that lifted no safety stop, which is every edit of a rule that was not paused. */
        public Edit(boolean changed, StockRuleAdjustment adjustment) {
            this(changed, adjustment, false);
        }

        /** This edit, with the safety stop of its rule reported as lifted. */
        public Edit asResumed() {
            return resumed ? this : new Edit(changed, adjustment, true);
        }
    }

    /** One entry per row; {@code null} for an empty row. */
    private final List<StockRule<ItemKey>> rows;

    /** @param rowCount rule rows this keeper offers ({@code stockKeeperRows}), clamped to 1..{@value #MAX_ROWS} */
    public StockKeeperRules(int rowCount) {
        int count = Math.min(Math.max(1, rowCount), MAX_ROWS);
        List<StockRule<ItemKey>> list = new ArrayList<>(count);
        for (int row = 0; row < count; row++)
            list.add(null);
        this.rows = list;
    }

    // --- reading -----------------------------------------------------------------------------------------------

    /** Number of rule rows this keeper offers. */
    public int size() {
        return rows.size();
    }

    /** Whether no row holds a rule at all. */
    public boolean isEmpty() {
        for (StockRule<ItemKey> rule : rows) {
            if (rule != null)
                return false;
        }
        return true;
    }

    /** The rule of one row, or empty when that row is not configured. */
    public Optional<StockRule<ItemKey>> ruleAt(int row) {
        return inRange(row) ? Optional.ofNullable(rows.get(row)) : Optional.empty();
    }

    /** How many rows hold a rule. */
    public int ruleCount() {
        int count = 0;
        for (StockRule<ItemKey> rule : rows) {
            if (rule != null)
                count++;
        }
        return count;
    }

    /**
     * The rules of this keeper in row order, empty rows left out. This is what a controller copies into its own rule
     * set ({@code AisleStockRules}); the order of the rows is part of the meaning, because the first rule for an item
     * governs it and every later one is shadowed.
     */
    public List<StockRule<ItemKey>> rules() {
        List<StockRule<ItemKey>> configured = new ArrayList<>(rows.size());
        for (StockRule<ItemKey> rule : rows) {
            if (rule != null)
                configured.add(rule);
        }
        return List.copyOf(configured);
    }

    // --- editing -----------------------------------------------------------------------------------------------

    /**
     * Applies one edit of a validated configuration payload.
     *
     * @param row   the rule row
     * @param field {@link #FIELD_ITEM}, {@link #FIELD_MINIMUM}, {@link #FIELD_MAXIMUM}, {@link #FIELD_RESERVE} or
     *              {@link #FIELD_CLEAR_ROW}
     * @param key   the item, for {@link #FIELD_ITEM}; {@code null} clears the row
     * @param value the number, for the three number fields ({@link StockRule#UNSET} switches it off)
     */
    public Edit apply(int row, int field, @Nullable ItemKey key, long value) {
        if (!inRange(row))
            return Edit.NONE;
        return switch (field) {
            case FIELD_ITEM -> setItem(row, key);
            case FIELD_CLEAR_ROW -> setItem(row, null);
            case FIELD_MINIMUM, FIELD_MAXIMUM, FIELD_RESERVE -> setNumber(row, field, value);
            default -> Edit.NONE;
        };
    }

    /**
     * Sets or clears the item of a row. A row that had no rule starts one with all three numbers off; a row that had
     * one keeps its numbers on the new item. {@code null} clears the whole row, because numbers without an item govern
     * nothing and a row a player emptied should not keep invisible settings.
     */
    public Edit setItem(int row, @Nullable ItemKey key) {
        if (!inRange(row))
            return Edit.NONE;
        StockRule<ItemKey> current = rows.get(row);
        if (key == null) {
            if (current == null)
                return Edit.NONE;
            rows.set(row, null);
            return new Edit(true, StockRuleAdjustment.NONE);
        }
        StockRule<ItemKey> next = current == null ? StockRule.of(key) : current.withKey(key);
        if (next.equals(current))
            return Edit.NONE;
        rows.set(row, next);
        return new Edit(true, StockRuleAdjustment.NONE);
    }

    /**
     * Sets one of the three numbers of a row. A row without an item has nothing to govern, so the edit does nothing:
     * the item comes first, which is also what the screen shows.
     */
    public Edit setNumber(int row, int field, long value) {
        if (!inRange(row) || (field != FIELD_MINIMUM && field != FIELD_MAXIMUM && field != FIELD_RESERVE))
            return Edit.NONE;
        StockRule<ItemKey> current = rows.get(row);
        if (current == null)
            return Edit.NONE;
        long minimum = field == FIELD_MINIMUM ? value : current.minimum();
        long maximum = field == FIELD_MAXIMUM ? value : current.maximum();
        long reserve = field == FIELD_RESERVE ? value : current.reserve();
        StockRule.Adjusted<ItemKey> adjusted = StockRule.checked(current.key(), minimum, maximum, reserve);
        if (adjusted.rule().equals(current))
            return new Edit(false, adjusted.adjustment());
        rows.set(row, adjusted.rule());
        return new Edit(true, adjusted.adjustment());
    }

    /** Clears every row. */
    public void clear() {
        for (int row = 0; row < rows.size(); row++)
            rows.set(row, null);
    }

    // --- persistence -------------------------------------------------------------------------------------------

    /**
     * NBT form: {@code Rules: [{Row: int, Item: <ItemKey>, Min: long, Max: long, Reserve: long}]}. Empty rows are left
     * out, so an unconfigured keeper saves almost nothing. Never throws.
     */
    public ListTag save(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int row = 0; row < rows.size(); row++) {
            StockRule<ItemKey> rule = rows.get(row);
            if (rule == null)
                continue;
            try {
                Tag keyTag = rule.key().save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                    continue; // unencodable key, already logged by ItemKey
                CompoundTag rowTag = new CompoundTag();
                rowTag.putInt(ROW, row);
                rowTag.put(ITEM, keyTag);
                rowTag.putLong(MINIMUM, rule.minimum());
                rowTag.putLong(MAXIMUM, rule.maximum());
                rowTag.putLong(RESERVE, rule.reserve());
                list.add(rowTag);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save stock rule row {}", row, e);
            }
        }
        return list;
    }

    /**
     * Replaces the rules with a save written by {@link #save}. Never throws; unreadable entries are skipped and every
     * number is clamped by {@link StockRule} itself.
     * <p>
     * <b>A lowered {@code stockKeeperRows} never loses a rule.</b> The row list first grows to hold every saved row
     * (bounded by {@value #MAX_ROWS}), the way {@code ProductionPatterns} grows for pattern slots: without that, a
     * keeper loaded under a lowered config dropped the rules in the higher rows on read and the next save wrote the
     * truncated set back. The consequence is deliberate and is what the config comment says: this keeper stays as tall
     * as it was saved — every one of its rows is shown and applied — and the lowered value governs the keepers a player
     * places from now on.
     */
    public void load(ListTag list, HolderLookup.Provider registries) {
        clear();
        if (list == null)
            return;
        int highestSaved = -1;
        for (int i = 0; i < list.size() && i < MAX_ROWS; i++)
            highestSaved = Math.max(highestSaved, list.getCompound(i).getInt(ROW));
        growTo(highestSaved + 1);
        for (int i = 0; i < list.size() && i < MAX_ROWS; i++) {
            CompoundTag rowTag = list.getCompound(i);
            try {
                int row = rowTag.getInt(ROW);
                if (!inRange(row))
                    continue;
                Optional<ItemKey> key = ItemKey.load(registries, rowTag.get(ITEM));
                if (key.isEmpty())
                    continue; // a row without a readable item governs nothing
                rows.set(row, new StockRule<>(key.get(), rowTag.getLong(MINIMUM), rowTag.getLong(MAXIMUM),
                        rowTag.getLong(RESERVE)));
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping unreadable stock rule row {}", rowTag, e);
            }
        }
    }

    /** Grows the row list to {@code count} rows, bounded by {@value #MAX_ROWS}; never shrinks it ({@link #load}). */
    private void growTo(int count) {
        for (int row = rows.size(); row < Math.min(count, MAX_ROWS); row++)
            rows.add(null);
    }

    private boolean inRange(int row) {
        return row >= 0 && row < rows.size();
    }

    @Override
    public String toString() {
        return "StockKeeperRules[rows=" + rows.size() + ", configured=" + ruleCount() + "]";
    }
}
