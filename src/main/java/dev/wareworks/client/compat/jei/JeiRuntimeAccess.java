package dev.wareworks.client.compat.jei;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * JEI's runtime for as long as JEI has one, behind methods that name no JEI type (issue #24, slice 4, ADR-063).
 * <p>
 * Wareworks itself asks nothing of the runtime: the "+" works through {@link TerminalCraftTransferHandler}, which JEI
 * calls. The visual harness does — its {@code jei} scenario opens JEI's recipe screen over a warehouse terminal the way
 * a player's R key does, and reads where JEI believes the terminal's window ends — and it may not name a JEI type
 * itself, so that it loads no JEI class in a run without JEI. This class is that door: JEI hands the runtime to
 * {@link WareworksJeiPlugin#onRuntimeAvailable} once it has started in a world and takes it back when it stops
 * ({@link WareworksJeiPlugin#onRuntimeUnavailable}), on leaving the world and before a restart after a resource reload.
 * <p>
 * Only JEI's public API is used here. Client thread, like everything JEI does.
 */
public final class JeiRuntimeAccess {
    /** The runtime JEI last handed over, or {@code null} while JEI has none (no world, or restarting). */
    @Nullable
    private static volatile IJeiRuntime runtime;

    private JeiRuntimeAccess() {
    }

    /** JEI started ({@code runtime}) or stopped ({@code null}). */
    static void runtime(@Nullable IJeiRuntime value) {
        runtime = value;
    }

    /** Whether JEI has started in the current world and not stopped since. */
    public static boolean available() {
        return runtime != null;
    }

    /**
     * Opens JEI's recipe screen on the recipes that make {@code output}, as JEI's "show recipes" key over the item
     * does, over the screen that is open now. JEI puts the recipe categories the open menu has a transfer handler for
     * first, so over a warehouse terminal the crafting recipes come first.
     *
     * @return whether JEI was asked; {@code false} while JEI has no runtime or for an empty stack
     */
    public static boolean showRecipesMaking(ItemStack output) {
        IJeiRuntime current = runtime;
        if (current == null || output.isEmpty())
            return false;
        IFocus<ItemStack> focus = current.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT,
                VanillaTypes.ITEM_STACK, output.copy());
        current.getRecipesGui().show(focus);
        return true;
    }

    /** Whether {@code screen} is JEI's recipe screen. */
    public static boolean isRecipeScreen(@Nullable Screen screen) {
        IJeiRuntime current = runtime;
        return current != null && screen != null && screen == current.getRecipesGui();
    }

    /** The screen JEI's recipe screen goes back to when it closes: the one it was opened over. */
    public static Optional<Screen> recipeScreenParent() {
        IJeiRuntime current = runtime;
        return current == null ? Optional.empty() : current.getRecipesGui().getParentScreen();
    }

    /**
     * Where JEI believes {@code screen}'s window is ({@code IScreenHelper#getGuiProperties}): JEI places its ingredient
     * list right of this rectangle. Empty while JEI has no runtime or does not handle the screen.
     */
    public static Optional<Rect2i> windowArea(Screen screen) {
        IJeiRuntime current = runtime;
        if (current == null)
            return Optional.empty();
        Optional<IGuiProperties> properties = current.getScreenHelper().getGuiProperties(screen);
        return properties.map(gui -> new Rect2i(gui.guiLeft(), gui.guiTop(), gui.guiXSize(), gui.guiYSize()));
    }

    /** The areas beside {@code screen}'s window that JEI keeps its overlays out of; empty while JEI has no runtime. */
    public static List<Rect2i> exclusionAreas(Screen screen) {
        IJeiRuntime current = runtime;
        return current == null ? List.of() : current.getScreenHelper().getGuiExclusionAreas(screen).toList();
    }
}
