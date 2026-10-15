package dev.wareworks.client.compat.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.client.gui.TerminalRecipeLay;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.crafting.CraftGrid;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI's "+" on a crafting recipe, while a warehouse terminal is open: it lays the recipe into the terminal's crafting
 * panel (issue #24, slice 4, ADR-063, {@code docs/warehouse-system.md} §3.4.5). Nothing moves and nothing is ordered —
 * the grid is a grid of ghosts, the ingredients stay in the warehouse until the player presses Fetch, and the player
 * crafts. Everything that is not JEI's own lives in {@link TerminalRecipeLay}, which this class only feeds.
 * <p>
 * <b>The cells.</b> JEI's crafting category always draws nine input slots, row by row, and shifts a recipe smaller
 * than 3 x 3 somewhere inside them — a recipe one column wide into the middle column, one a row high into the middle
 * row (JEI 19.21 and 19.57, {@code CraftingGridHelper}, read in their bytecode). Input slot {@code k} is therefore
 * grid cell {@code k} — row {@code k / 3}, column {@code k % 3} — and where in the grid a recipe sits does not matter:
 * the server trims the empty rows and columns before it looks the recipe up, as vanilla does. A slot's candidates are
 * every item JEI shows in it ({@link IRecipeSlotView#getItemStacks()}), in JEI's order, whichever of them JEI happens
 * to display right now and whatever the focus; JEI's own transfer into a crafting table chooses among all of them as
 * well. The server picks one per cell by what the terminal's buffer, the aisle's stock and its production stations
 * hold, and checks the picked grid against its own recipes.
 * <p>
 * <b>The button.</b> JEI asks with {@code doTransfer} false on every client tick a recipe is visible, so that path
 * only reads: a recipe that does not fit a 3 x 3 grid, or that another mod's crafting extension draws with other than
 * nine input slots, or that has no item at all — no slot names an item, the same test the click's cells pass
 * ({@link TerminalRecipeLay#namesAnItem}) — gets JEI's internal error, which hides the "+"; while the terminal's craft
 * order locks the grid the "+" is greyed out and says why, in the panel's own sentence. A missing ingredient never
 * blocks it: the panel shows what is missing, and Fetch or a production station can bring it. With {@code doTransfer}
 * true the cells go to the server and the method returns {@code null}, on which JEI closes its recipe screen and shows
 * the terminal's screen again — the same instance, laid out anew and already folded out, because the lay marked the
 * panel before JEI let go of the screen; the laid grid follows a moment later.
 * <p>
 * <b>Shift on "+"</b> ({@code maxTransfer}) is ignored on purpose. "As many as possible" is about items a transfer
 * moves, and laying moves none: the number of crafts is the terminal's amount field, which Fetch reads, and letting
 * JEI's Shift start a fetch would send the crane on trips from a click meant to look at a recipe.
 * <p>
 * Only the six-argument {@code transferRecipe} of JEI 19.21's API is implemented. JEI 19.52 deprecated it for removal
 * and added an overload taking a transfer context, whose default implementation calls this one, so the handler works
 * with every 1.21.1 JEI that still has it.
 */
final class TerminalCraftTransferHandler
        implements IRecipeTransferHandler<WarehouseTerminalMenu, RecipeHolder<CraftingRecipe>> {
    /** JEI's internal error: hides the "+", for recipes the panel cannot take. Created once, it never changes. */
    private final IRecipeTransferError cannotLay;
    /** The greyed-out "+" while the terminal's craft order locks the grid; its sentence is translated when drawn. */
    private final IRecipeTransferError gridLocked;

    TerminalCraftTransferHandler(IRecipeTransferHandlerHelper helper) {
        Objects.requireNonNull(helper, "helper");
        cannotLay = helper.createInternalError();
        gridLocked = helper.createUserErrorWithTooltip(TerminalRecipeLay.gridLockedLine());
    }

    /** The terminal's menu, matched by JEI against the exact class of the open menu. */
    @Override
    public Class<? extends WarehouseTerminalMenu> getContainerClass() {
        return WarehouseTerminalMenu.class;
    }

    /** No narrowing by menu type: the container class names exactly one. */
    @Override
    public Optional<MenuType<WarehouseTerminalMenu>> getMenuType() {
        return Optional.empty();
    }

    @Override
    public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    @Override
    @Nullable
    public IRecipeTransferError transferRecipe(WarehouseTerminalMenu container, RecipeHolder<CraftingRecipe> recipe,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {
        if (!recipe.value().canCraftInDimensions(CraftGrid.WIDTH, CraftGrid.WIDTH))
            return cannotLay;
        List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        if (inputs.size() != CraftGrid.SIZE
                || inputs.stream().noneMatch(input -> TerminalRecipeLay.namesAnItem(input.getItemStacks()))
                || !TerminalRecipeLay.canLay(container))
            return cannotLay;
        if (TerminalRecipeLay.gridLocked(container))
            return gridLocked;
        if (!doTransfer)
            return null;
        List<List<ItemKey>> cells = new ArrayList<>(CraftGrid.SIZE);
        for (IRecipeSlotView input : inputs)
            cells.add(TerminalRecipeLay.candidates(input.getItemStacks()));
        // The recipe's id only says which recipe JEI showed; JEI makes ids of its own for the special recipes it
        // draws, which the server does not know, and the server tries the id first and trusts the grid alone.
        return TerminalRecipeLay.lay(container, Optional.of(recipe.id()), cells) ? null : cannotLay;
    }
}
