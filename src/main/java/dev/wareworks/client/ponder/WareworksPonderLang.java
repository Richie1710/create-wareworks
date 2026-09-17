package dev.wareworks.client.ponder;

import java.util.function.BiConsumer;

import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.providers.RegistrateLangProvider;

import dev.wareworks.Wareworks;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * English lang of the Ponder scenes, tags and shared texts.
 * <p>
 * Ponder keeps its strings in its own registry, not in Registrate builders, so they are emitted by
 * {@code PonderLocalization#provideLang}, which runs every registered storyboard once with a <b>null level</b> to
 * collect the {@code text_n} keys. The plugin must be registered here because {@code FMLClientSetupEvent} does not
 * fire during {@code runData} (same as Create's {@code CreateDatagen#providePonderLang}).
 * <p>
 * The listener runs at {@code EventPriority.HIGHEST}, because Registrate refuses new data generators once its own
 * normal-priority {@code GatherDataEvent} listener has built the root provider. It lives in {@code client.ponder}
 * rather than in {@code data}, because it references client-only Ponder classes; the data run is {@code Dist.CLIENT},
 * so {@code WareworksClient} (and therefore this class) loads there.
 * <p>
 * Keys generated here must never also be added through {@code WareworksLangGen} or {@code addRawLang}:
 * {@code LanguageProvider.add} throws on duplicates. Every key must be translated in the hand-written
 * {@code de_de.json} ({@code LangConsistencyTest}).
 */
public final class WareworksPonderLang {
    private WareworksPonderLang() {
    }

    public static void gatherDataHighPriority(GatherDataEvent event) {
        if (!event.getMods().contains(Wareworks.ID))
            return;
        Wareworks.registrate().<RegistrateLangProvider>addDataGenerator(ProviderType.LANG, provider -> {
            BiConsumer<String, String> lang = provider::add;
            PonderIndex.addPlugin(new WareworksPonderPlugin());
            // Emits only keys whose namespace is wareworks: our scenes, our tag, our shared text.
            // Our blocks added to Create's tags emit nothing (Create already ships those strings).
            PonderIndex.getLangAccess().provideLang(Wareworks.ID, lang);
        });
    }
}
