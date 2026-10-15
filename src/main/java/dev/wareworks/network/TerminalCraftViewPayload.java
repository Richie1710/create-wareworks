package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Server → client: what the grid laid in the terminal's crafting panel makes, and what the aisle says about each of its
 * items (M32, issue #24, {@code docs/warehouse-system.md} §3.4.5).
 * <p>
 * <b>The recipe is the server's.</b> Vanilla matches crafting grids only on the server, so the client sends its grid
 * ({@link TerminalCraftPayload}, {@code PREVIEW}) and this is the answer: the recipe it matches, what one craft makes,
 * and whether limited crafting keeps the player from it. The client never runs recipe code.
 * <p>
 * <b>It names the grid it answers.</b> The view carries the very cells it was resolved for, so the panel can tell a
 * view of the grid on screen from one of an earlier grid — also one of the same items in another arrangement or in
 * another number, which can make something else entirely (two planks side by side are a pressure plate, one above the
 * other sticks) — and shows a result, and lets a click craft, only for a view of exactly its grid
 * ({@link #describes}).
 * <p>
 * <b>The stock is the server's too.</b> A cell's colour comes from these numbers rather than from the screen's stock
 * list, which is capped and may not hold a grid item at all: per distinct grid item, what a click could be granted of
 * it, whether a production station of the aisle makes it, and whether the safety stop holds it. What is already
 * <i>here</i> in the buffer the screen counts itself from the synced buffer slots, so that part reacts at once.
 * <p>
 * <b>It answers a laid recipe</b> (issue #24, slice 4). When a recipe viewer lays a recipe into the panel
 * ({@link TerminalCraftLayPayload}), the server picks one item per cell and checks the grid against its recipe manager;
 * the grid it accepted comes back here, in {@link #lay()}, for the screen to adopt. It is a field of its own rather
 * than {@link #cells()}, because the screen's next preview — sent when the viewer hands the screen back — may name the
 * grid the panel showed before, and the view then answers that grid while the lay still has to be adopted. A lay
 * carries a number that grows with every lay this menu accepted, so a screen adopts each one once, and a lay equal to
 * an earlier one is still a new view and pushed.
 * <p>
 * Pushed with the terminal's other screen updates and compared <b>by value</b>, so a panel whose grid and aisle did not
 * change costs no packet, and an empty grid costs no lookup at all. Reading is bounded at {@value CraftGrid#SIZE} cells
 * and {@value CraftGrid#SIZE} key entries whatever the length prefixes claim, which is also the most a grid can name.
 *
 * @param containerId the menu the view belongs to; the screen ignores one for another menu
 * @param cells       the grid this view was resolved for, exactly as the server received it; the empty grid for the
 *                    empty view
 * @param recipe      the crafting recipe the grid matches; empty when it matches none
 * @param result      what one craft makes; an empty stack exactly when there is no recipe
 * @param locked      whether limited crafting keeps this player from the recipe
 * @param noAisle     the terminal is misaligned, outside a warehouse, or its controller is not loaded: the stock
 *                    numbers are all zero, and crafting from the buffer still works
 * @param keys        one entry per distinct item of the grid, in the order the grid first names them; empty for an
 *                    empty grid
 * @param lay         the last recipe a recipe viewer laid into this menu's grid and the server accepted;
 *                    {@link Lay#NONE} until one was
 */
public record TerminalCraftViewPayload(int containerId, CraftGrid<ItemKey> cells, Optional<ResourceLocation> recipe,
                                       ItemStack result, boolean locked, boolean noAisle, List<KeyView> keys, Lay lay)
        implements CustomPacketPayload {
    /**
     * What the aisle says about one item of the grid.
     *
     * @param key         the exact item
     * @param available   what a click at this terminal could be granted of it now (a player's view of the stock:
     *                    what is promised elsewhere is taken off, a stock keeper's reserve is not)
     * @param producible  whether a production station of the aisle has a pattern that makes it
     * @param paused      whether the safety stop holds the making of it
     */
    public record KeyView(ItemKey key, long available, boolean producible, boolean paused) {
        public KeyView {
            Objects.requireNonNull(key, "key");
            available = Math.max(0L, available);
        }
    }

    /**
     * A recipe a recipe viewer laid into the panel, as the server accepted it (issue #24, slice 4): the grid it picked
     * from the viewer's candidates, which matches a crafting recipe of the server's.
     * <p>
     * The screen adopts {@link #cells()} as its grid when {@link #number()} is one it has not adopted yet, folds the
     * panel out when {@link #foldOut()} asks for it, and previews the grid with {@link #recipe()} as the hint — which
     * the server then answers without looking the recipe up again, because the lay already did.
     *
     * @param number  how many lays this menu has accepted, this one included; 0 for {@link #NONE}. It only grows, so a
     *                screen tells a new lay from one it already adopted
     * @param cells   the grid the server picked, row-major
     * @param recipe  the crafting recipe that grid matched on the server
     * @param foldOut whether the player asked for the panel to fold out ({@link TerminalCraftLayPayload#foldOut()})
     */
    public record Lay(int number, CraftGrid<ItemKey> cells, Optional<ResourceLocation> recipe, boolean foldOut) {
        /** No recipe was laid into this menu's grid. */
        public static final Lay NONE = new Lay(0, CraftGrid.empty(), Optional.empty(), false);

        public Lay {
            Objects.requireNonNull(cells, "cells");
            Objects.requireNonNull(recipe, "recipe");
            number = Math.max(0, number);
        }

        /** Whether this is a lay at all rather than {@link #NONE}. */
        public boolean isPresent() {
            return number > 0;
        }

        /** Writes the number, and the rest only for a lay that is present: no lay costs one byte. */
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(number);
            if (!isPresent())
                return;
            TerminalCraftPayload.writeCells(buffer, cells);
            TerminalCraftPayload.writeRecipeId(buffer, recipe);
            buffer.writeBoolean(foldOut);
        }

        /** Reads what {@link #write} wrote; a grid of another size reads as the empty grid, which nothing adopts. */
        private static Lay read(RegistryFriendlyByteBuf buffer) {
            int number = buffer.readVarInt();
            if (number <= 0)
                return NONE;
            CraftGrid<ItemKey> cells = TerminalCraftPayload.readCells(buffer).orElseGet(CraftGrid::empty);
            Optional<ResourceLocation> recipe = TerminalCraftPayload.readRecipeId(buffer);
            return new Lay(number, cells, recipe, buffer.readBoolean());
        }
    }

    /** Most key entries one view carries: a grid names at most this many items. */
    public static final int MAX_KEYS = CraftGrid.SIZE;

    public static final CustomPacketPayload.Type<TerminalCraftViewPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_craft_view"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftViewPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCraftViewPayload::write, TerminalCraftViewPayload::new);

    public TerminalCraftViewPayload {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(recipe, "recipe");
        // A copy, so the view cannot change after it was compared or sent, whoever holds the stack it was built from.
        result = Objects.requireNonNull(result, "result").copy();
        Objects.requireNonNull(keys, "keys");
        keys = List.copyOf(keys.size() <= MAX_KEYS ? keys : keys.subList(0, MAX_KEYS));
        Objects.requireNonNull(lay, "lay");
    }

    /** A view that answers no laid recipe: the shape of slices 1 to 3. */
    public TerminalCraftViewPayload(int containerId, CraftGrid<ItemKey> cells, Optional<ResourceLocation> recipe,
            ItemStack result, boolean locked, boolean noAisle, List<KeyView> keys) {
        this(containerId, cells, recipe, result, locked, noAisle, keys, Lay.NONE);
    }

    /** The view of an empty grid: no recipe, nothing to say about any item. */
    public static TerminalCraftViewPayload none(int containerId) {
        return none(containerId, Lay.NONE);
    }

    /** The view of an empty grid that still answers {@code lay}, the last recipe laid into this menu. */
    public static TerminalCraftViewPayload none(int containerId, Lay lay) {
        return new TerminalCraftViewPayload(containerId, CraftGrid.empty(), Optional.empty(), ItemStack.EMPTY, false,
                false, List.of(), lay);
    }

    /**
     * Reads what {@link #write} wrote. A cell count other than {@value CraftGrid#SIZE} — a build of another shape —
     * reads as the empty grid, which no grid on screen that names an item is.
     */
    private TerminalCraftViewPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), TerminalCraftPayload.readCells(buffer).orElseGet(CraftGrid::empty),
                TerminalCraftPayload.readRecipeId(buffer), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                buffer.readBoolean(), buffer.readBoolean(), readKeys(buffer), Lay.read(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        TerminalCraftPayload.writeCells(buffer, cells);
        TerminalCraftPayload.writeRecipeId(buffer, recipe);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, result);
        buffer.writeBoolean(locked);
        buffer.writeBoolean(noAisle);
        buffer.writeVarInt(keys.size());
        for (KeyView key : keys) {
            ItemKey.STREAM_CODEC.encode(buffer, key.key());
            buffer.writeVarLong(key.available());
            buffer.writeBoolean(key.producible());
            buffer.writeBoolean(key.paused());
        }
        // Appended in slice 4, after everything slices 1 to 3 wrote.
        lay.write(buffer);
    }

    private static List<KeyView> readKeys(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(Math.max(0, buffer.readVarInt()), MAX_KEYS);
        List<KeyView> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            keys.add(new KeyView(ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarLong(), buffer.readBoolean(),
                    buffer.readBoolean()));
        return keys;
    }

    /**
     * Whether this view was resolved for exactly {@code grid}: every cell the same item, or empty, in the same place.
     * Only then are its recipe, its result and its lock the answer for that grid; a view of any other grid — the edit
     * that is still waiting for its preview, or whose answer is on the way — says nothing about it.
     */
    public boolean describes(CraftGrid<ItemKey> grid) {
        return cells.equals(grid);
    }

    /** What the aisle says about {@code key}, or empty when the grid does not name it. */
    public Optional<KeyView> keyView(ItemKey key) {
        for (KeyView view : keys) {
            if (view.key().equals(key))
                return Optional.of(view);
        }
        return Optional.empty();
    }

    /**
     * By value, including the cells, the result and the lay: {@link ItemStack} compares by identity, and a view
     * rebuilt from an unchanged grid must equal the one already sent, or the menu would push it again on every refresh.
     * A grid that changed always gives a view that differs, and so does every accepted lay, so the panel is told about
     * every grid the server read and every recipe it laid.
     */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TerminalCraftViewPayload view && containerId == view.containerId
                && locked == view.locked && noAisle == view.noAisle && cells.equals(view.cells)
                && recipe.equals(view.recipe) && ItemStack.matches(result, view.result) && keys.equals(view.keys)
                && lay.equals(view.lay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerId, cells, recipe, ItemStack.hashItemAndComponents(result), result.getCount(),
                locked, noAisle, keys, lay);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
