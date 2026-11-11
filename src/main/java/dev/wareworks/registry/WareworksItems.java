package dev.wareworks.registry;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.ItemEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.storage.BayUpgradeKitItem;
import dev.wareworks.content.handtruck.HandTruckItem;
import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.core.storage.BayUpgrade;

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
     * The rack upgrade kit for the step wood to andesite (ADR-083): right-clicked on a Wooden Rack Bay, that bay becomes
     * an Andesite Rack Bay where it stands, with its goods, filter and priority. One kit per step, and the three kits
     * stand together before the hand truck. Their look is one hand-made parent,
     * {@code models/item/upgrade_kit/item.json}, with the target bay's frame texture.
     */
    public static final ItemEntry<BayUpgradeKitItem> RACK_UPGRADE_KIT_ANDESITE = upgradeKit(
            "rack_upgrade_kit_andesite", BayUpgrade.ANDESITE_RACK, "Andesite Rack Upgrade Kit",
            "andesite_block", "andesite_casing");

    /** The rack upgrade kit for the step andesite to brass (ADR-083), see {@link #RACK_UPGRADE_KIT_ANDESITE}. */
    public static final ItemEntry<BayUpgradeKitItem> RACK_UPGRADE_KIT_BRASS = upgradeKit(
            "rack_upgrade_kit_brass", BayUpgrade.BRASS_RACK, "Brass Rack Upgrade Kit", "brass_block",
            "brass_casing");

    /**
     * The fluid bay upgrade kit for the step copper to brass (ADR-083): the one that matters most, because breaking a
     * fluid bay to replace it loses the fluid. See {@link #RACK_UPGRADE_KIT_ANDESITE}.
     */
    public static final ItemEntry<BayUpgradeKitItem> FLUID_BAY_UPGRADE_KIT_BRASS = upgradeKit(
            "fluid_bay_upgrade_kit_brass", BayUpgrade.BRASS_FLUID, "Brass Fluid Bay Upgrade Kit", "brass_block",
            "fluid_tank");

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

    /**
     * The Warehouse Remote (issue #44, ADR-077): sneak and right-click a warehouse terminal to link it, then right-click
     * anywhere within the server's range to open that terminal's screen. Everything requested from afar is still
     * delivered by the crane into the terminal's buffer or to a package door. It never stacks; the link is the item
     * component {@code wareworks:remote_link}. Its look is the hand-made {@code models/item/warehouse_remote/item.json}.
     */
    public static final ItemEntry<WarehouseRemoteItem> WAREHOUSE_REMOTE = REGISTRATE
            .item("warehouse_remote", WarehouseRemoteItem::new)
            .properties(p -> p.stacksTo(1))
            .model((context, provider) -> provider.withExistingParent(context.getName(),
                    provider.modLoc("item/warehouse_remote/item")))
            .register();

    private WareworksItems() {
    }

    /**
     * One upgrade kit: the shared parent model with the target bay's frame texture, the step's own beam texture and a
     * belt strap; stacks to 64.
     */
    private static ItemEntry<BayUpgradeKitItem> upgradeKit(String name, BayUpgrade step, String lang, String frame,
            String beam) {
        return REGISTRATE.item(name, properties -> new BayUpgradeKitItem(properties, step))
                .properties(p -> p.stacksTo(64))
                .lang(lang)
                .model((context, provider) -> provider.withExistingParent(context.getName(),
                                provider.modLoc("item/upgrade_kit/item"))
                        .texture("frame", "create:block/" + frame)
                        .texture("beam", "create:block/" + beam)
                        .texture("strap", "create:block/belt")
                        .texture("particle", "create:block/" + frame))
                .register();
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} items", REGISTRATE.getModid());
    }
}
