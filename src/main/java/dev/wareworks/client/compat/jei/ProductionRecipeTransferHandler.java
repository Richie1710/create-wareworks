package dev.wareworks.client.compat.jei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import dev.wareworks.client.gui.ProductionRecipeLay;
import dev.wareworks.client.gui.TerminalRecipeLay;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.production.PatternLay;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.util.WareworksLang;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI's "+" on a recipe of <b>any</b> category, while a production station is open: it lays the recipe into the selected
 * pattern (ADR-076, {@code docs/warehouse-system.md} §3.5). Nothing moves and nothing is ordered: a pattern is ghost
 * items.
 * <p>
 * <b>A crafting recipe</b> is one JEI draws with nine input slots: slot {@code k} is grid cell {@code k}, which is also
 * Crafter slot {@code k} for a Crafter Feeder ({@link TerminalCraftTransferHandler} explains the slots). The server looks
 * the picked grid up in its own recipes, which say the result and its amount.
 * <p>
 * <b>Everything else</b>, a saw or a press, has no grid: {@link PatternLay} fills the cells in order, with the amounts
 * JEI shows, and the result is what the recipe makes. A recipe with several results makes one pattern for one of them:
 * for one of Create's processing recipes the first that is certain, a by-product with a chance never, and otherwise the
 * first JEI lists, which for Create's Block Cutting category is merely the first of its many. The result cell can be
 * changed by hand afterwards.
 * <p>
 * <b>The button.</b> JEI asks on every client tick a recipe is visible, with {@code doTransfer} false, so that path only
 * reads and stops early. A recipe with no item to name on either side gets JEI's internal error, which hides the "+".
 * One that a pattern cannot hold is greyed out and says why. The server's menu has no lock to ask about: a pattern may
 * be rewritten at any time, and the running orders carry their own copy of it.
 * <p>
 * <b>Shift on "+"</b> is ignored, for the reason {@link TerminalCraftTransferHandler} gives.
 */
final class ProductionRecipeTransferHandler implements IUniversalRecipeTransferHandler<ProductionMenu> {
    private final IRecipeTransferError hidden;
    private final IRecipeTransferError notOnlyItems;
    private final IRecipeTransferError tooManyInputs;
    private final IRecipeTransferError makesIngredient;

    ProductionRecipeTransferHandler(IRecipeTransferHandlerHelper helper) {
        Objects.requireNonNull(helper, "helper");
        hidden = helper.createInternalError();
        notOnlyItems = helper.createUserErrorWithTooltip(refusal(WareworksLang.PRODUCTION_LAY_NOT_ONLY_ITEMS));
        tooManyInputs = helper.createUserErrorWithTooltip(refusal(WareworksLang.PRODUCTION_LAY_TOO_MANY_INPUTS));
        makesIngredient = helper.createUserErrorWithTooltip(refusal(WareworksLang.PRODUCTION_LAY_MAKES_INGREDIENT));
    }

    private static Component refusal(String key) {
        return WareworksLang.translateDirect(key).withStyle(ChatFormatting.RED);
    }

    @Override
    public Class<? extends ProductionMenu> getContainerClass() {
        return ProductionMenu.class;
    }

    @Override
    public Optional<MenuType<ProductionMenu>> getMenuType() {
        return Optional.empty();
    }

    @Override
    @Nullable
    public IRecipeTransferError transferRecipe(ProductionMenu container, Object recipe, IRecipeSlotsView recipeSlots,
            Player player, boolean maxTransfer, boolean doTransfer) {
        if (!ProductionRecipeLay.canLay(container))
            return hidden;
        List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        if (inputs.stream().allMatch(IRecipeSlotView::isEmpty))
            return hidden;
        Object value = recipe instanceof RecipeHolder<?> holder ? holder.value() : recipe;
        if (value instanceof CraftingRecipe crafting && inputs.size() == CraftGrid.SIZE
                && crafting.canCraftInDimensions(CraftGrid.WIDTH, CraftGrid.WIDTH))
            return layCrafting(container, inputs, doTransfer);
        Optional<ProductionEntry<ItemKey>> result = result(value, recipeSlots);
        if (result.isEmpty())
            return hidden;
        List<PatternLay.Input<ItemKey>> laid = new ArrayList<>(inputs.size());
        for (IRecipeSlotView input : inputs) {
            if (input.isEmpty())
                continue;
            int count = input.getItemStacks().findFirst().map(ItemStack::getCount).orElse(1);
            laid.add(new PatternLay.Input<>(TerminalRecipeLay.candidates(input.getItemStacks()), count));
        }
        PatternLay.Layout<ItemKey> layout = PatternLay.layout(laid, result.get().key());
        if (layout.refusal().isPresent())
            return switch (layout.refusal().get()) {
                case NOT_ONLY_ITEMS -> notOnlyItems;
                case TOO_MANY_INPUTS -> tooManyInputs;
                case MAKES_ITS_INGREDIENT -> makesIngredient;
            };
        if (!doTransfer)
            return null;
        return ProductionRecipeLay.lay(container, false, layout.cells(), layout.counts(), result) ? null : hidden;
    }

    @Nullable
    private IRecipeTransferError layCrafting(ProductionMenu container, List<IRecipeSlotView> inputs,
            boolean doTransfer) {
        if (inputs.stream().noneMatch(input -> TerminalRecipeLay.namesAnItem(input.getItemStacks())))
            return hidden;
        if (!doTransfer)
            return null;
        List<List<ItemKey>> cells = new ArrayList<>(CraftGrid.SIZE);
        for (IRecipeSlotView input : inputs)
            cells.add(TerminalRecipeLay.candidates(input.getItemStacks()));
        List<Integer> counts = Collections.nCopies(CraftGrid.SIZE, ProductionEntry.MIN_COUNT);
        return ProductionRecipeLay.lay(container, true, cells, counts, Optional.empty()) ? null : hidden;
    }

    /** The one result a pattern for this recipe makes: see the class description. */
    private static Optional<ProductionEntry<ItemKey>> result(Object recipe, IRecipeSlotsView recipeSlots) {
        List<ItemStack> outputs = new ArrayList<>();
        for (IRecipeSlotView output : recipeSlots.getSlotViews(RecipeIngredientRole.OUTPUT))
            output.getItemStacks().findFirst().filter(stack -> !stack.isEmpty()).ifPresent(outputs::add);
        Optional<ItemStack> chosen = Optional.empty();
        if (recipe instanceof ProcessingRecipe<?, ?> processing) {
            chosen = processing.getRollableResults().stream().filter(output -> output.getChance() >= 1F)
                    .map(ProcessingOutput::getStack).filter(stack -> !stack.isEmpty()).findFirst();
        }
        return chosen.or(() -> outputs.stream().findFirst()).flatMap(stack -> ItemKey.fromStack(stack)
                .map(key -> new ProductionEntry<>(key, ProductionEntry.clampCellCount(stack.getCount()))));
    }
}
