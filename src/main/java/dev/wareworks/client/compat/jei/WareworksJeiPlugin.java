package dev.wareworks.client.compat.jei;

import dev.wareworks.Wareworks;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * The JEI plugin of Create: Wareworks: the mod's only entry point into JEI, an optional, client-only integration.
 * <p>
 * JEI finds this class by its {@link JeiPlugin} annotation in NeoForge's scan data and creates it through its public
 * no-argument constructor. Nothing in the mod references it, and no class outside {@code client.compat.jei} may
 * reference a JEI type, so the mod loads, runs and passes every check without JEI installed: the classes in this
 * package are only ever loaded by JEI itself, and by the visual harness once it has checked that JEI is loaded. The mod
 * compiles against JEI's API only ({@code compileOnly}); it never uses JEI's internals ({@code mezz.jei.common},
 * {@code mezz.jei.library}, {@code mezz.jei.gui}).
 * <p>
 * What it adds: JEI's "+" on a crafting recipe lays that recipe into the crafting panel of an open warehouse terminal
 * ({@link TerminalCraftTransferHandler}, issue #24, slice 4, ADR-063). It also keeps JEI's runtime while JEI has one
 * ({@link JeiRuntimeAccess}), which the visual harness opens JEI's recipe screen through.
 */
@JeiPlugin
public final class WareworksJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = Wareworks.asResource("jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    /** The terminal's crafting panel takes JEI's crafting recipes ({@link TerminalCraftTransferHandler}). */
    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new TerminalCraftTransferHandler(registration.getTransferHelper()),
                RecipeTypes.CRAFTING);
    }

    /** JEI has started in a world: its runtime is kept for {@link JeiRuntimeAccess}. */
    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeAccess.runtime(jeiRuntime);
    }

    /** JEI has stopped (the world was left, or JEI restarts): the runtime it handed over is no longer valid. */
    @Override
    public void onRuntimeUnavailable() {
        JeiRuntimeAccess.runtime(null);
    }
}
