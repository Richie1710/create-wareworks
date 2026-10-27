package dev.wareworks.registry;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.ItemEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.handtruck.HandTruckItem;

/**
 * Item registrations that are not block items.
 * <p>
 * Loaded by {@code Wareworks} <b>after</b> {@link WareworksBlocks}, so these items stand after every block in the
 * creative tab (registration order is the tab order, GameTest {@code creativetaborderandicon}). Recipes are
 * hand-written JSON in {@code data/wareworks/recipe/}, like the blocks' ({@code recipesloaded}).
 */
public final class WareworksItems {
    private static final CreateRegistrate REGISTRATE = Wareworks.registrate();

    /**
     * The hand truck (issue #37, ADR-070, {@code docs/hand-truck.md}): right-click a chest or barrel with the empty
     * truck and the container goes onto it with everything inside; right-click a surface with the loaded truck and it
     * is set down again. It never stacks, loaded or empty. Its look is the hand-made
     * {@code models/item/hand_truck/item.json}.
     */
    public static final ItemEntry<HandTruckItem> HAND_TRUCK = REGISTRATE.item("hand_truck", HandTruckItem::new)
            .properties(p -> p.stacksTo(1))
            .model((context, provider) -> provider.withExistingParent(context.getName(),
                    provider.modLoc("item/hand_truck/item")))
            .register();

    private WareworksItems() {
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} items", REGISTRATE.getModid());
    }
}
