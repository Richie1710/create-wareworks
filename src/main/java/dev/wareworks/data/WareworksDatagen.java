package dev.wareworks.data;

import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.providers.RegistrateLangProvider;

import dev.wareworks.Wareworks;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Datagen entry, following Create's {@code CreateDatagen}.
 * <p>
 * Registrate adds its own {@code GatherDataEvent} listener (normal priority) that builds blockstates, models, loot
 * tables, tags and lang from the builders. Extra Registrate generators must be added before that listener runs, hence
 * {@link #gatherDataHighPriority} at {@code EventPriority.HIGHEST}. Providers that are not Registrate-based go into
 * {@link #gatherData} at {@code EventPriority.LOWEST}. Both listeners are added in the {@code Wareworks} constructor;
 * the event only fires during {@code runData}.
 */
public final class WareworksDatagen {
    private WareworksDatagen() {
    }

    public static void gatherDataHighPriority(GatherDataEvent event) {
        if (!event.getMods().contains(Wareworks.ID))
            return;
        Wareworks.registrate().<RegistrateLangProvider>addDataGenerator(ProviderType.LANG,
                provider -> WareworksLangGen.generate(provider::add));
    }

    public static void gatherData(GatherDataEvent event) {
        if (!event.getMods().contains(Wareworks.ID))
            return;
        // Non-Registrate providers: event.getGenerator().addProvider(event.includeServer(), new SomeProvider(...));
        // None are needed yet.
    }
}
