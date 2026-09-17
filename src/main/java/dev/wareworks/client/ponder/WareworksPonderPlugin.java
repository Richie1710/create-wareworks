package dev.wareworks.client.ponder;

import dev.wareworks.Wareworks;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Ponder plugin of Create: Wareworks (ADR-016).
 * <p>
 * It is handed to {@code PonderIndex.addPlugin} in exactly two places, and never anywhere else:
 * <ul>
 *   <li>{@code WareworksClient#onClientSetup} ({@code FMLClientSetupEvent}, before Ponder's
 *       {@code FMLLoadCompleteEvent} runs {@code PonderIndex.registerAll()}) — the runtime path;</li>
 *   <li>{@link WareworksPonderLang} inside the Registrate LANG generator — the datagen path, because
 *       {@code FMLClientSetupEvent} does not fire during {@code runData} (Create does the same in
 *       {@code CreateDatagen#providePonderLang}).</li>
 * </ul>
 * A second runtime registration would show every scene twice in the UI.
 * <p>
 * {@link #getModId()} is the namespace of everything the helpers create: schematic paths
 * ({@code assets/wareworks/ponder/<path>.nbt}), scene ids, String tag ids and shared text keys.
 * <p>
 * Every method here can run again at runtime ({@code /ponder reload}), so this class and everything it
 * calls stays stateless and re-entrant. It is client-only code and must never be referenced from
 * common classes.
 */
public final class WareworksPonderPlugin implements PonderPlugin {
    /** Shared text key; the lang key is {@code wareworks.ponder.shared.crane_needs_rotation}. */
    public static final String CRANE_NEEDS_ROTATION = "crane_needs_rotation";

    @Override
    public String getModId() {
        return Wareworks.ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        WareworksPonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        WareworksPonderTags.register(helper);
    }

    @Override
    public void registerSharedText(SharedTextRegistrationHelper helper) {
        helper.registerSharedText(CRANE_NEEDS_ROTATION, "Stacker Cranes need Rotational Force from below");
    }
}
