package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.terminal.ListOrder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * A warehouse terminal's <b>craft order</b> (issue #24, slice 2, {@code docs/warehouse-system.md} §3.4.5, ADR-062):
 * what pressing Fetch on the crafting panel started — the ingredients of a recipe laid in the grid, for a number of
 * crafts, brought into the terminal's buffer by ordinary crane trips.
 * <p>
 * <b>A second list order, not a new mechanism.</b> {@link #order()} is a {@link ListOrder} like the clipboard's, one
 * line per ingredient with what the buffer was missing when the fetch started, and every portion of it is an ordinary
 * request with {@code RequestScope.CRAFT}. The terminal runs it beside its clipboard order through the same three
 * controller hooks, so the two never block each other.
 * <p>
 * Beside the order it keeps what the screen needs to show it: the grid the fetch was started for (pushed to every
 * screen open at the terminal, which shows that recipe and locks editing while the order is open), the number of
 * crafts and the recipe the grid matched — a hint only, never trusted. It holds no item and never crafts: the order
 * ends with the ingredients in the buffer, and only a player's own click turns them into anything (ADR-059).
 * <p>
 * The last refused portion ({@link #lastRefusal()}) is transient, like the order's own question and per-item consent:
 * it is measured against the warehouse as it is and is never saved.
 * <p>
 * Server thread only. Persistence ({@link #write}, {@link #read}):
 * <pre>
 * CraftOrder: { Order: &lt;TerminalListPersistence&gt;, Cells: [ { I: 0..8, Item: &lt;ItemKey&gt; } ],
 *               Crafts: int, Recipe?: "namespace:path" }
 * </pre>
 * Reading never throws: a cell with a bad index, a second entry for one index or a key that cannot be decoded is
 * skipped, the crafts are clamped, an unreadable recipe id is no hint, and a compound without a readable order is no
 * craft order at all.
 */
final class TerminalCraftOrder {
    /** NBT key of the craft order inside the terminal's station data. */
    static final String CRAFT_ORDER_TAG = "CraftOrder";

    private static final String ORDER = "Order";
    private static final String CELLS = "Cells";
    private static final String INDEX = "I";
    private static final String ITEM = "Item";
    private static final String CRAFTS = "Crafts";
    private static final String RECIPE = "Recipe";
    /** Longest recipe id read back; the bound keeps hand-edited save data from claiming a megabyte. */
    private static final int MAX_RECIPE_ID = 512;

    private final ListOrder<ItemKey> order;
    private final CraftGrid<ItemKey> cells;
    private final int crafts;
    private final Optional<ResourceLocation> recipe;
    private Optional<TerminalPortionRefusal> lastRefusal = Optional.empty();

    /**
     * @param order  the order the fetch resolved into
     * @param cells  the grid it was started for
     * @param crafts how many crafts it fetches for, clamped to at least 1
     * @param recipe the recipe the grid matched when it started; a hint only
     */
    TerminalCraftOrder(ListOrder<ItemKey> order, CraftGrid<ItemKey> cells, int crafts,
            Optional<ResourceLocation> recipe) {
        this.order = Objects.requireNonNull(order, "order");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.crafts = Math.max(1, crafts);
        this.recipe = Objects.requireNonNull(recipe, "recipe");
    }

    /** The list order behind the fetch: one line per ingredient. */
    ListOrder<ItemKey> order() {
        return order;
    }

    /** The grid the fetch was started for. */
    CraftGrid<ItemKey> cells() {
        return cells;
    }

    /** How many crafts the fetch brings the ingredients for. */
    int crafts() {
        return crafts;
    }

    /** The recipe the grid matched when the fetch started; a hint only. */
    Optional<ResourceLocation> recipe() {
        return recipe;
    }

    /** Whether the order still has work to do. */
    boolean isOpen() {
        return order.state().isOpen();
    }

    /** Why the last portion was refused, while that still holds; never saved. */
    Optional<TerminalPortionRefusal> lastRefusal() {
        return lastRefusal;
    }

    void lastRefusal(Optional<TerminalPortionRefusal> refusal) {
        this.lastRefusal = refusal == null ? Optional.empty() : refusal;
    }

    /** How far the order has got, in the bounded form the screens and the goggles are given. */
    TerminalCraftOrderState state() {
        return new TerminalCraftOrderState(true, order.state(), cells, crafts, order.wanted(), order.delivered(),
                order.inFlight(), order.current().map(line -> line.key()), isOpen() ? lastRefusal : Optional.empty());
    }

    // --- persistence -------------------------------------------------------------------------------------------------

    /** Writes {@code craftOrder} into {@code tag}, and nothing at all for a terminal without one. Never throws. */
    static void write(CompoundTag tag, TerminalCraftOrder craftOrder, HolderLookup.Provider registries) {
        if (craftOrder == null)
            return;
        CompoundTag craftTag = new CompoundTag();
        TerminalListPersistence.write(craftTag, craftOrder.order, registries, ORDER);
        if (!craftTag.contains(ORDER, Tag.TAG_COMPOUND))
            return;
        ListTag cellTags = new ListTag();
        for (int index = 0; index < CraftGrid.SIZE; index++) {
            Optional<ItemKey> cell = craftOrder.cells.cell(index);
            if (cell.isEmpty())
                continue;
            try {
                Tag keyTag = cell.get().save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                    continue; // unencodable key (already logged by ItemKey): the cell reads back empty
                CompoundTag entry = new CompoundTag();
                entry.putInt(INDEX, index);
                entry.put(ITEM, keyTag);
                cellTags.add(entry);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save a cell of a craft order's grid {}", cell.get(), e);
            }
        }
        craftTag.put(CELLS, cellTags);
        craftTag.putInt(CRAFTS, craftOrder.crafts);
        craftOrder.recipe.ifPresent(id -> craftTag.putString(RECIPE, id.toString()));
        tag.put(CRAFT_ORDER_TAG, craftTag);
    }

    /**
     * Reads the craft order {@link #write} wrote, or empty when the tag holds none or none that can be read.
     * {@code now} becomes the restored order's interval and stall origin ({@link TerminalListPersistence}); an order
     * that was asking comes back running ({@link ListOrder#restore}). Never throws.
     */
    static Optional<TerminalCraftOrder> read(CompoundTag tag, HolderLookup.Provider registries, long now) {
        if (tag == null || !tag.contains(CRAFT_ORDER_TAG, Tag.TAG_COMPOUND))
            return Optional.empty();
        try {
            CompoundTag craftTag = tag.getCompound(CRAFT_ORDER_TAG);
            Optional<ListOrder<ItemKey>> order = TerminalListPersistence.read(craftTag, registries, now, ORDER);
            if (order.isEmpty())
                return Optional.empty(); // nothing to fetch and nothing to show
            return Optional.of(new TerminalCraftOrder(order.get(), readCells(craftTag, registries),
                    Math.max(1, craftTag.getInt(CRAFTS)), readRecipe(craftTag)));
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Skipping an unreadable craft order", e);
            return Optional.empty();
        }
    }

    private static CraftGrid<ItemKey> readCells(CompoundTag craftTag, HolderLookup.Provider registries) {
        List<Optional<ItemKey>> cells = new ArrayList<>(CraftGrid.SIZE);
        for (int i = 0; i < CraftGrid.SIZE; i++)
            cells.add(Optional.empty());
        boolean[] seen = new boolean[CraftGrid.SIZE];
        ListTag cellTags = craftTag.getList(CELLS, Tag.TAG_COMPOUND);
        for (int i = 0; i < cellTags.size(); i++) {
            CompoundTag entry = cellTags.getCompound(i);
            if (!entry.contains(INDEX, Tag.TAG_INT))
                continue;
            int index = entry.getInt(INDEX);
            if (index < 0 || index >= CraftGrid.SIZE || seen[index])
                continue; // a bad index or a second entry for one cell: the first readable one stands
            try {
                Optional<ItemKey> key = ItemKey.load(registries, entry.get(ITEM));
                if (key.isEmpty())
                    continue;
                cells.set(index, key);
                seen[index] = true;
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping an unreadable cell of a craft order's grid {}", entry, e);
            }
        }
        return new CraftGrid<>(cells);
    }

    private static Optional<ResourceLocation> readRecipe(CompoundTag craftTag) {
        if (!craftTag.contains(RECIPE, Tag.TAG_STRING))
            return Optional.empty();
        String id = craftTag.getString(RECIPE);
        if (id.isEmpty() || id.length() > MAX_RECIPE_ID)
            return Optional.empty();
        return Optional.ofNullable(ResourceLocation.tryParse(id));
    }
}
