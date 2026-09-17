package dev.wareworks;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.crane.CraneServerHooks;
import dev.wareworks.data.WareworksDatagen;
import dev.wareworks.network.WareworksNetwork;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksCapabilities;
import dev.wareworks.registry.WareworksCreativeTabs;
import dev.wareworks.registry.WareworksMenuTypes;
import dev.wareworks.util.GoggleObservers;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Common entry point of Create: Wareworks.
 * <p>
 * Everything registered here must be safe on a dedicated server; client-only setup lives in {@link WareworksClient}.
 */
@Mod(Wareworks.ID)
public final class Wareworks {
    public static final String ID = "wareworks";
    public static final String NAME = "Create: Wareworks";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Wareworks' own registrate instance. Addons must not use Create's internal instance.
     * The tooltip modifier gives our items Create-style item descriptions and kinetic stats.
     * <p>
     * Do not reference registry classes from static fields of this class: they must not be initialised before
     * {@code registerEventListeners} ran (see the constructor).
     */
    private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(ID)
            .setTooltipModifierFactory(item -> new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE)
                    .andThen(TooltipModifier.mapNull(KineticStats.create(item))));

    public Wareworks(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("{} {} initializing", NAME, modContainer.getModInfo().getVersion());

        // 1. Registrate needs the mod bus BEFORE any registry class builds an entry. Listeners queued earlier are never
        //    flushed, because Create's registrate already claimed the global "seen mod bus" flag.
        REGISTRATE.registerEventListeners(modEventBus);
        // 2. Registrate's default tab is the search tab, which would put our items in no category and crash with
        //    duplicate search entries. Every item built from here on lands exactly once in our own tab.
        REGISTRATE.defaultCreativeTab(WareworksCreativeTabs.BASE_KEY);

        WareworksConfig.register(modContainer);

        // 3. Registry classes, in dependency order (block entity types reference blocks).
        WareworksCreativeTabs.register(modEventBus);
        WareworksBlocks.register();
        WareworksBlockEntityTypes.register();
        WareworksMenuTypes.register();

        modEventBus.addListener(WareworksCapabilities::register);
        modEventBus.addListener(WareworksNetwork::register);
        modEventBus.addListener(EventPriority.HIGHEST, WareworksDatagen::gatherDataHighPriority);
        modEventBus.addListener(EventPriority.LOWEST, WareworksDatagen::gatherData);

        // 4. Game bus: server-side goggle observation (players look at our block entities through goggles) and the
        //    crane's per-server-run diagnostics, which must be reset when a new server starts in the same JVM.
        NeoForge.EVENT_BUS.addListener(GoggleObservers::onPlayerTick);
        CraneServerHooks.register(NeoForge.EVENT_BUS);
    }

    public static CreateRegistrate registrate() {
        return REGISTRATE;
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
