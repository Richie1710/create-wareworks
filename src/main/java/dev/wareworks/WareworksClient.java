package dev.wareworks;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.client.input.StorageFilterPressGuard;
import dev.wareworks.client.ponder.WareworksPonderLang;
import dev.wareworks.client.ponder.WareworksPonderPlugin;
import dev.wareworks.client.render.WareworksPartialModels;
import dev.wareworks.dev.VisualTestHarness;
import dev.wareworks.dev.WindowPlacement;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry point. This class is never loaded on a dedicated server,
 * so client classes (renderers, visuals, screens, Ponder) may be referenced from here.
 */
@Mod(value = Wareworks.ID, dist = Dist.CLIENT)
public final class WareworksClient {
    public WareworksClient(IEventBus modEventBus) {
        // Partial models must exist before model baking (ADR-013); the constructor runs before the first resource reload.
        WareworksPartialModels.init();
        modEventBus.addListener(WareworksClient::onClientSetup);
        // Ponder lang (ADR-016): FMLClientSetupEvent does not fire during runData, so the plugin is registered inside
        // the LANG generator there instead. HIGHEST priority, because Registrate refuses new data generators once its
        // own normal-priority listener has built the root provider. GatherDataEvent only fires during runData.
        modEventBus.addListener(EventPriority.HIGHEST, WareworksPonderLang::gatherDataHighPriority);
        // The crafting grids a player laid at terminals (M32, issue #24) belong to the connection they were laid under:
        // leaving the world or server forgets them, so none comes back in another world or reaches a connection whose
        // registries cannot write its item keys.
        NeoForge.EVENT_BUS.addListener(WareworksClient::onLoggingOut);
        // Issue #30: only a press sets a store filter, not a held button's repeat that slides onto a slot, and while
        // the button that set one stays down, its repeats do nothing on a storage filter slot, so a Create filter item
        // that left the hand is not cleared again by the empty hand.
        StorageFilterPressGuard.register(NeoForge.EVENT_BUS);
        // Dev tooling (ADR-014), like the harness below: open the window on another monitor when build.gradle passes
        // one (Gradle property wareworks.windowMonitor or the environment variable WAREWORKS_WINDOW_MONITOR).
        String windowMonitor = System.getProperty(WindowPlacement.PROPERTY);
        if (windowMonitor != null && !FMLLoader.isProduction())
            placeWindow(modEventBus, windowMonitor);
        // Dev tooling (ADR-014): the visual smoke test runs only in a development environment and only when the system
        // property names a scenario (Gradle runVisualTest). Release jars do not contain the harness (build.gradle).
        // VisualTestHarness.PROPERTY is a compile-time constant, so reading it does not load the harness.
        String visualTestScenario = System.getProperty(VisualTestHarness.PROPERTY);
        if (visualTestScenario == null)
            return;
        if (FMLLoader.isProduction())
            Wareworks.LOGGER.warn("Ignoring system property {}: the visual smoke test only runs in a development environment",
                    VisualTestHarness.PROPERTY);
        else
            startVisualTest(visualTestScenario);
    }

    /** Separate method: the harness classes are resolved only when this runs, i.e. only in a dev run with the property. */
    private static void startVisualTest(String scenario) {
        VisualTestHarness.install(scenario);
    }

    /** Separate method for the same reason as {@link #startVisualTest}. */
    private static void placeWindow(IEventBus modEventBus, String monitor) {
        WindowPlacement.install(modEventBus, monitor);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WarehouseTerminalScreen.forgetRememberedGrids();
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // Ponder collects plugins until FMLLoadCompleteEvent runs PonderIndex.registerAll(), so this must happen here
        // and exactly once: a second runtime registration would list every scene twice in the UI.
        PonderIndex.addPlugin(new WareworksPonderPlugin());
        Wareworks.LOGGER.info("{} client setup complete", Wareworks.NAME);
    }
}
