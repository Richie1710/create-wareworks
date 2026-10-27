package dev.wareworks.client.render;

import com.simibubi.create.foundation.item.render.CustomRenderedItems;
import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;

import net.minecraft.world.item.Item;

/**
 * The hand truck's client extensions (issue #37, ADR-070): its item renderer ({@link HandTruckItemRenderer}).
 * <p>
 * The arm pose stays vanilla's one-handed item pose on purpose. A two-handed pose (the crossbow hold) raises both arms
 * to the shoulders and would lift the truck off the ground; with the arm hanging, the model's third person transform
 * stands the truck upright in front of the player with its wheels on the ground and the handle in the hand, leaning
 * back as a pushed hand truck does.
 * <p>
 * Registered from {@code WareworksClient} on {@code RegisterClientExtensionsEvent}. {@link #create} also tells Create's
 * {@code ModelSwapper} to wrap the truck's baked model in a {@code CustomRenderedItemModel} (that is what
 * {@link CustomRenderedItems#register} is for), without which the renderer would never be asked; the common item class
 * therefore needs no client code of its own.
 */
public final class HandTruckClientExtensions extends SimpleCustomRenderer {
    private HandTruckClientExtensions() {
        super(new HandTruckItemRenderer());
    }

    /** The extensions for {@code item}, with the item recorded for Create's model swap. */
    public static HandTruckClientExtensions create(Item item) {
        CustomRenderedItems.register(item);
        return new HandTruckClientExtensions();
    }
}
