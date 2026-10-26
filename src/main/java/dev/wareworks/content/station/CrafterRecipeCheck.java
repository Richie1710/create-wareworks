package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.CrafterGrid;
import dev.wareworks.core.production.CrafterPatternCheck;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CrafterBlock;

/**
 * The crafting recipe check of a production pattern while a Warehouse Crafter Feeder is attached to its station
 * (issue #34, ADR-067 D7): would a vanilla Crafter holding this grid craft exactly the pattern's result?
 * <p>
 * It asks the very lookup the Crafter itself uses when it crafts, {@code CrafterBlock#getPotentialResults} with its own
 * recipe cache, on a {@link CraftingInput} built the way the Crafter builds its own ({@code CraftingInput.of} trims
 * empty rows and columns exactly like {@code CrafterBlockEntity#asCraftInput}). A grid that passes here is therefore a
 * grid the Crafter crafts, and one that fails is a grid it would only click at.
 * <p>
 * The rules, in this order: an incomplete slot is {@link CrafterPatternCheck#INCOMPLETE}; a filled cell that is not one
 * item is {@link CrafterPatternCheck#CELL_NOT_ONE} (decided without a lookup); no recipe, or one that assembles nothing,
 * is {@link CrafterPatternCheck#NOT_A_RECIPE}; a recipe that makes another item or another count than the pattern's
 * result is {@link CrafterPatternCheck#RESULT_DIFFERS}, with what it really makes. Server only; never throws.
 * <p>
 * <b>It evaluates recipes; it never runs one.</b> This is the one class besides {@code TerminalCrafting} that may look a
 * recipe up and ask it for its result and remainders (ADR-067, beside ADR-059; {@code RecipeExecutionBoundaryTest}),
 * and only to <i>measure</i> them: whether a pattern is a recipe ({@link #check}) and whether what a Crafter is about to
 * output fits in front of it ({@link #output}). Every stack made here is compared or counted and then dropped; the
 * vanilla Crafter does the crafting, with its own items.
 */
public final class CrafterRecipeCheck {
    /**
     * One slot's answer.
     *
     * @param check        the verdict
     * @param recipeResult what the recipe really makes, for {@link CrafterPatternCheck#RESULT_DIFFERS} only
     */
    public record Result(CrafterPatternCheck check, Optional<ItemStack> recipeResult) {
        public static final Result NOT_CHECKED = new Result(CrafterPatternCheck.NOT_CHECKED, Optional.empty());
        public static final Result INCOMPLETE = new Result(CrafterPatternCheck.INCOMPLETE, Optional.empty());
        public static final Result VALID = new Result(CrafterPatternCheck.VALID, Optional.empty());

        public Result {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(recipeResult, "recipeResult");
        }
    }

    private CrafterRecipeCheck() {
    }

    /** Checks pattern slot {@code slot} of {@code patterns} against the crafting recipes of {@code level}. */
    public static Result check(Level level, ProductionPatterns patterns, int slot) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(patterns, "patterns");
        if (patterns.patternAt(slot).isEmpty())
            return Result.INCOMPLETE;
        Optional<ItemKey> result = patterns.keyAt(slot, ProductionPatterns.RESULT_ENTRY);
        if (result.isEmpty())
            return Result.INCOMPLETE;
        CrafterGrid<ItemKey> grid = layoutOf(patterns, slot);
        int[] counts = new int[CrafterGrid.SLOTS];
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++)
            counts[cell] = patterns.countAt(slot, cell);
        if (!grid.allCellsSingle(counts))
            return new Result(CrafterPatternCheck.CELL_NOT_ONE, Optional.empty());
        try {
            CraftingInput input = inputOf(grid);
            Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(level, input);
            if (recipe.isEmpty())
                return new Result(CrafterPatternCheck.NOT_A_RECIPE, Optional.empty());
            ItemStack made = recipe.get().value().assemble(input, level.registryAccess());
            if (made.isEmpty())
                return new Result(CrafterPatternCheck.NOT_A_RECIPE, Optional.empty());
            if (!result.get().matches(made)
                    || made.getCount() != patterns.countAt(slot, ProductionPatterns.RESULT_ENTRY))
                return new Result(CrafterPatternCheck.RESULT_DIFFERS, Optional.of(made.copy()));
            return Result.VALID;
        } catch (RuntimeException e) {
            // A recipe of another mod that throws on assemble must not take the station down with it.
            Wareworks.LOGGER.warn("Crafter recipe check of production pattern {} failed", slot, e);
            return new Result(CrafterPatternCheck.NOT_A_RECIPE, Optional.empty());
        }
    }

    /**
     * What a vanilla Crafter holding {@code input} would output if it crafted now: the result first, then every
     * remainder that is not empty, in the order the Crafter dispenses them ({@code CrafterBlock#dispenseFrom}). Empty when
     * it would craft nothing — no recipe, or one that assembles nothing — or when a recipe throws. Measured only, for the
     * feeder's check that the output fits; nothing returned here is ever placed anywhere.
     */
    public static Optional<List<ItemStack>> output(Level level, CraftingInput input) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(input, "input");
        try {
            Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(level, input);
            if (recipe.isEmpty())
                return Optional.empty();
            ItemStack made = recipe.get().value().assemble(input, level.registryAccess());
            if (made.isEmpty())
                return Optional.empty();
            List<ItemStack> leaving = new ArrayList<>(2);
            leaving.add(made);
            for (ItemStack remainder : recipe.get().value().getRemainingItems(input)) {
                if (!remainder.isEmpty())
                    leaving.add(remainder);
            }
            return Optional.of(List.copyOf(leaving));
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Could not evaluate the recipe of a Crafter grid", e);
            return Optional.empty();
        }
    }

    /**
     * The recipe set the answers of {@link #check} hold for: the level's recipe manager, which a {@code /reload} replaces.
     * A cache keyed by its identity notices new or removed recipes without listening for anything, the approach of the
     * vanilla Crafter's own recipe cache.
     */
    public static Object recipeSet(Level level) {
        return Objects.requireNonNull(level, "level").getRecipeManager();
    }

    /** The 3 x 3 layout of pattern slot {@code slot}: cell {@code i} is Crafter slot {@code i}. */
    public static CrafterGrid<ItemKey> layoutOf(ProductionPatterns patterns, int slot) {
        List<ItemKey> cells = new ArrayList<>(CrafterGrid.SLOTS);
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++)
            cells.add(patterns.keyAt(slot, cell).orElse(null));
        return CrafterGrid.ofNullable(cells);
    }

    /** The crafting input one run of {@code grid} puts into a Crafter: one item per filled cell. */
    public static CraftingInput inputOf(CrafterGrid<ItemKey> grid) {
        List<ItemStack> stacks = new ArrayList<>(CrafterGrid.SLOTS);
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++)
            stacks.add(grid.at(cell).map(key -> key.toStack(1)).orElse(ItemStack.EMPTY));
        return CraftingInput.of(CrafterGrid.WIDTH, CrafterGrid.WIDTH, stacks);
    }
}
