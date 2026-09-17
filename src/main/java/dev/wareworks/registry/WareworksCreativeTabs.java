package dev.wareworks.registry;

import com.simibubi.create.AllCreativeModeTabs;

import dev.wareworks.Wareworks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Wareworks creative tab.
 * <p>
 * The tab has no {@code displayItems}: Registrate fills it, because {@code Wareworks} sets it as Registrate's default
 * tab before any item is built. Every Wareworks item therefore appears exactly once, in registration order,
 * which {@code WareworksBlocks} declares in building
 * order: stacker crane, rail, controller, interface, input, output, terminal. The icon is the stacker crane item.
 */
public final class WareworksCreativeTabs {
    public static final ResourceKey<CreativeModeTab> BASE_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Wareworks.asResource("base"));
    /** Lang key of the tab title; the English value is generated in {@code WareworksLangGen}. */
    public static final String BASE_TITLE_KEY = "itemGroup.wareworks.base";

    private static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Wareworks.ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BASE = REGISTER.register(
            BASE_KEY.location().getPath(),
            () -> CreativeModeTab.builder()
                    .title(Component.translatable(BASE_TITLE_KEY))
                    .withTabsBefore(AllCreativeModeTabs.PALETTES_CREATIVE_TAB.getKey())
                    .icon(WareworksCreativeTabs::icon)
                    .build());

    private WareworksCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }

    /**
     * Tab icon: the stacker crane item (the machine is the feature). Evaluated lazily when the tab is drawn or its icon is
     * queried, which is after registration, so the block entry is resolved by then.
     */
    private static ItemStack icon() {
        return WareworksBlocks.STACKER_CRANE.asStack();
    }
}
