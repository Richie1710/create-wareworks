package dev.wareworks.client.ponder;

import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import com.tterrag.registrate.util.entry.RegistryEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Ponder tags: the categories our blocks appear under in Ponder's index.
 * <p>
 * Tags passed to {@code addStoryBoard} only make a tag flash in the UI; membership comes from
 * {@code addToTag(tag).add(component)} here.
 * <p>
 * {@link #WAREHOUSE} is our own tag and is the one that gets a title, a description and an icon (all in the
 * {@code wareworks} namespace, so {@code provideLang} emits them). Its icon is an item, not a
 * {@code textures/ponder/tag/*.png}, so no texture file is needed. Our blocks are additionally added to two
 * existing Create tags, which emits no lang of its own: Create already ships those strings, and Create's plugin
 * is always registered first.
 */
public final class WareworksPonderTags {
    /** Our own Ponder tag; lang keys {@code wareworks.ponder.tag.warehouse} and {@code ....description}. */
    public static final ResourceLocation WAREHOUSE = Wareworks.asResource("warehouse");

    private WareworksPonderTags() {
    }

    public static void register(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<RegistryEntry<?, ?>> entries = helper.withKeyFunction(RegistryEntry::getId);

        helper.registerTag(WAREHOUSE)
                .addToIndex()
                // (icon, not main item): the crane stays in the tag's own item list as well.
                .item(WareworksBlocks.STACKER_CRANE.get(), true, false)
                .title("Automated Warehouses")
                .description("Components which store and retrieve items with Stacker Cranes")
                .register();

        entries.addToTag(WAREHOUSE)
                .add(WareworksBlocks.STACKER_CRANE)
                .add(WareworksBlocks.WAREHOUSE_RAIL)
                .add(WareworksBlocks.WAREHOUSE_CONTROLLER)
                .add(WareworksBlocks.WAREHOUSE_INTERFACE)
                .add(WareworksBlocks.WAREHOUSE_INPUT)
                .add(WareworksBlocks.WAREHOUSE_OUTPUT);

        // The dock is a kinetic appliance: it consumes rotational force and its speed depends on the RPM.
        entries.addToTag(AllCreatePonderTags.KINETIC_APPLIANCES)
                .add(WareworksBlocks.STACKER_CRANE);
        // Interface and stations move items around, so they belong next to Create's logistics blocks.
        entries.addToTag(AllCreatePonderTags.LOGISTICS)
                .add(WareworksBlocks.WAREHOUSE_INTERFACE)
                .add(WareworksBlocks.WAREHOUSE_INPUT)
                .add(WareworksBlocks.WAREHOUSE_OUTPUT);
    }
}
