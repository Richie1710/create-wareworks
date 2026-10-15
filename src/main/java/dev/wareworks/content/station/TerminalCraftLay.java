package dev.wareworks.content.station;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.IngredientPicker;
import dev.wareworks.core.stock.StockAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * What a warehouse terminal tells the {@link IngredientPicker} when a recipe viewer lays a recipe into its crafting
 * panel (issue #24, slice 4, {@code docs/warehouse-system.md} §3.4.5): per candidate item, how many the terminal's
 * buffer holds, what the aisle could hand a player here, whether one of its production stations makes it, and whether
 * it is a plain item in the sense of vanilla's unused-item rule.
 * <p>
 * <b>Reads, never writes.</b> The buffer is read once per lay, slot by slot, into a count per item; the aisle is asked
 * only about the candidates the picker has to compare, each at most once, and the aisle's producible items are resolved
 * once, on the first question that needs them. Nothing is reserved, requested or moved: laying a recipe only gives the
 * panel a grid, which the player then fetches for or crafts from like a grid laid by hand.
 * <p>
 * The numbers are the ones the panel's view shows for a grid's items ({@code WarehouseTerminalMenu#craftView}): the
 * buffer slots the player's menu has, and {@code availableTo(StockAccess.PLAYER, ...)}, the stock a click at this
 * terminal could be granted. A terminal without an aisle has no stock and makes nothing, so only its buffer counts.
 * <p>
 * Server thread only.
 */
public final class TerminalCraftLay {
    private TerminalCraftLay() {
    }

    /**
     * Whether {@code key} is a plain item: not damaged, not enchanted, not renamed. That is vanilla's unused-item rule
     * ({@code Inventory#findSlotMatchingUnusedItem}), which vanilla's own recipe placement follows: a worn tool or a
     * named item is never taken for a recipe while a plain one would do.
     */
    public static boolean plain(ItemKey key) {
        ItemStack stack = Objects.requireNonNull(key, "key").toStack();
        return !stack.isDamaged() && !stack.isEnchanted() && !stack.has(DataComponents.CUSTOM_NAME);
    }

    /**
     * The supply for one lay at a terminal.
     *
     * @param buffer     the terminal's buffer as the player's menu shows it
     * @param slots      how many of its slots the menu shows; slots past it or past the handler are not read
     * @param controller the terminal's aisle, empty while it has none
     */
    public static IngredientPicker.Supply<ItemKey> supply(IItemHandler buffer, int slots,
            Optional<WarehouseControllerBlockEntity> controller) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(controller, "controller");
        Map<ItemKey, Long> inBuffer = new HashMap<>();
        int read = Math.max(0, Math.min(slots, buffer.getSlots()));
        for (int slot = 0; slot < read; slot++) {
            ItemStack stack = buffer.getStackInSlot(slot);
            if (!stack.isEmpty())
                inBuffer.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
        }
        return new TerminalSupply(inBuffer, controller.orElse(null));
    }

    /** The buffer counted once, the aisle asked on demand. */
    private static final class TerminalSupply implements IngredientPicker.Supply<ItemKey> {
        private final Map<ItemKey, Long> inBuffer;
        @Nullable
        private final WarehouseControllerBlockEntity controller;
        /** The aisle's producible items, resolved on the first question about one: it walks every station. */
        @Nullable
        private Set<ItemKey> producible;

        private TerminalSupply(Map<ItemKey, Long> inBuffer, @Nullable WarehouseControllerBlockEntity controller) {
            this.inBuffer = inBuffer;
            this.controller = controller;
        }

        @Override
        public long inBuffer(ItemKey key) {
            return inBuffer.getOrDefault(key, 0L);
        }

        @Override
        public long available(ItemKey key) {
            return controller == null ? 0L : controller.availableTo(StockAccess.PLAYER, key);
        }

        @Override
        public boolean producible(ItemKey key) {
            if (controller == null)
                return false;
            if (producible == null)
                producible = controller.producibleKeys();
            return producible.contains(key);
        }

        @Override
        public boolean plain(ItemKey key) {
            return TerminalCraftLay.plain(key);
        }
    }
}
