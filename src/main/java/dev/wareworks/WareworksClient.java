package dev.wareworks;

import dev.wareworks.client.ponder.WareworksPonderLang;
import dev.wareworks.client.ponder.WareworksPonderPlugin;
import dev.wareworks.client.render.WareworksPartialModels;
import dev.wareworks.dev.VisualTestHarness;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLLoader;

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

    private static void onClientSetup(FMLClientSetupEvent event) {
        // Ponder collects plugins until FMLLoadCompleteEvent runs PonderIndex.registerAll(), so this must happen here
        // and exactly once: a second runtime registration would list every scene twice in the UI.
        PonderIndex.addPlugin(new WareworksPonderPlugin());
        Wareworks.LOGGER.info("{} client setup complete", Wareworks.NAME);
    }
}
