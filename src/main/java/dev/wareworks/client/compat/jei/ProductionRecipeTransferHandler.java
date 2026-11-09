package dev.wareworks.client.compat.jei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import dev.wareworks.Wareworks;
import dev.wareworks.client.gui.ProductionRecipeLay;
import dev.wareworks.client.gui.TerminalRecipeLay;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.RecipeTagCells;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
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
 * <p>
 * <b>Tag ingredients become filters</b> (ADR-082, owner decision 3). A cell whose recipe ingredient is one plain item
 * tag, such as a chest's {@code #minecraft:planks}, is sent with that tag and the recipe's id, and the server writes it
 * as an "is tagged" Attribute Filter, so any plank in stock pays for it. The client's copy of a recipe has lost its
 * tags (every ingredient arrives as a list of items), so the tag sent is only proposed: the tag whose members are the
 * ingredient's items ({@link RecipeTagCells#tagFor}), and the server keeps it only where its own copy of the recipe
 * names that tag. For a crafting recipe the ingredient of a cell comes from JEI's own map of grid cells to ingredients
 * ({@link IRecipeTransferHandlerHelper#getGuiSlotIndexToIngredientMap}, since 19.16.1; an older JEI lays a crafting
 * recipe with exact cells); for any other recipe from the recipe's ingredients, matched to a slot by the items JEI
 * shows for it ({@link RecipeTagCells#slotTag}). An ingredient that lists items (NeoForge's coal or charcoal) or is
 * custom stays exact. {@code IIngredientHelper#getTagKeyEquivalent} is deliberately not used: its API default is empty,
 * and what overrides it is visible only in JEI's bytecode.
 */
final class ProductionRecipeTransferHandler implements IUniversalRecipeTransferHandler<ProductionMenu> {
    /** Whether the fallback for a JEI without the grid ingredient map was logged; once per game. */
    private static boolean exactCellsLogged;

    private final IRecipeTransferHandlerHelper helper;
    private final IRecipeTransferError hidden;
    private final IRecipeTransferError notOnlyItems;
    private final IRecipeTransferError tooManyInputs;
    private final IRecipeTransferError makesIngredient;

    ProductionRecipeTransferHandler(IRecipeTransferHandlerHelper helper) {
        this.helper = Objects.requireNonNull(helper, "helper");
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
            return layCrafting(container, recipe, inputs, doTransfer);
        Optional<ProductionEntry<ItemKey>> result = result(value, recipeSlots);
        if (result.isEmpty())
            return hidden;
        List<PatternLay.Input<ItemKey>> laid = new ArrayList<>(inputs.size());
        List<IRecipeSlotView> filled = new ArrayList<>(inputs.size());
        for (IRecipeSlotView input : inputs) {
            if (input.isEmpty())
                continue;
            int count = input.getItemStacks().findFirst().map(ItemStack::getCount).orElse(1);
            laid.add(new PatternLay.Input<>(TerminalRecipeLay.candidates(input.getItemStacks()), count));
            filled.add(input);
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
        List<Optional<ResourceLocation>> tags = machineTags(value, laid, filled, result.get().key(), layout.cells());
        return ProductionRecipeLay.lay(container, false, layout.cells(), layout.counts(), result, tags,
                recipeId(recipe)) ? null : hidden;
    }

    /**
     * The tag of each laid cell of a machine's recipe: each input takes the one plain tag of the recipe's ingredients
     * that every item JEI shows for it carries, and a cell takes the tag of the input it was laid from
     * ({@link RecipeTagCells#cellTags}). A recipe that is no {@link Recipe} has no tags.
     */
    private static List<Optional<ResourceLocation>> machineTags(Object recipe, List<PatternLay.Input<ItemKey>> laid,
            List<IRecipeSlotView> inputs, ItemKey result, List<List<ItemKey>> cells) {
        List<Ingredient> ingredients = recipe instanceof Recipe<?> known ? known.getIngredients() : List.of();
        List<List<ItemKey>> candidates = new ArrayList<>(laid.size());
        List<Optional<TagKey<Item>>> tags = new ArrayList<>(laid.size());
        for (int input = 0; input < laid.size(); input++) {
            candidates.add(laid.get(input).candidates());
            tags.add(RecipeTagCells.slotTag(ingredients, inputs.get(input).getItemStacks().toList()));
        }
        return RecipeTagCells.cellTags(candidates, tags, result, cells);
    }

    @Nullable
    private IRecipeTransferError layCrafting(ProductionMenu container, Object recipe, List<IRecipeSlotView> inputs,
            boolean doTransfer) {
        if (inputs.stream().noneMatch(input -> TerminalRecipeLay.namesAnItem(input.getItemStacks())))
            return hidden;
        if (!doTransfer)
            return null;
        List<List<ItemKey>> cells = new ArrayList<>(CraftGrid.SIZE);
        for (IRecipeSlotView input : inputs)
            cells.add(TerminalRecipeLay.candidates(input.getItemStacks()));
        List<Integer> counts = Collections.nCopies(CraftGrid.SIZE, ProductionEntry.MIN_COUNT);
        return ProductionRecipeLay.lay(container, true, cells, counts, Optional.empty(), gridTags(recipe),
                recipeId(recipe)) ? null : hidden;
    }

    /**
     * The id of the laid recipe, which the server reads the recipe's tags from: the client's copy of a recipe has lost
     * them ({@link RecipeTagCells}). A recipe that is no {@link RecipeHolder} has none, and is laid exact.
     */
    private static Optional<ResourceLocation> recipeId(Object recipe) {
        return recipe instanceof RecipeHolder<?> holder ? Optional.of(holder.id()) : Optional.empty();
    }

    /**
     * The tag of each grid cell of a crafting recipe, from JEI's own map of its grid cells to the recipe's ingredients,
     * which places a small recipe exactly in the slots JEI shows it in ({@link RecipeTagCells#gridTags}). A special
     * recipe's map is empty, and so is every answer if JEI cannot give one. The map exists since JEI 19.16.1; an older
     * JEI, which {@code jei_version_range} still admits, has no such method, and its crafting recipes are laid with
     * exact cells.
     */
    @SuppressWarnings("unchecked")
    private List<Optional<ResourceLocation>> gridTags(Object recipe) {
        if (recipe instanceof RecipeHolder<?> holder && holder.value() instanceof CraftingRecipe) {
            try {
                return RecipeTagCells.gridTags(
                        helper.getGuiSlotIndexToIngredientMap((RecipeHolder<CraftingRecipe>) holder), CraftGrid.SIZE);
            } catch (RuntimeException | LinkageError e) {
                if (e instanceof LinkageError && !exactCellsLogged) {
                    exactCellsLogged = true;
                    Wareworks.LOGGER.info("JEI before 19.16.1 has no grid ingredient map ({}): crafting recipes are"
                            + " laid into a production pattern with exact cells", e.toString());
                }
                return Collections.nCopies(CraftGrid.SIZE, Optional.empty());
            }
        }
        return Collections.nCopies(CraftGrid.SIZE, Optional.empty());
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
