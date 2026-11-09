package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.CrafterGrid;
import dev.wareworks.core.production.CrafterPatternCheck;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
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
 * <b>Filter cells (ADR-082)</b> are checked with concrete items: each filter group takes the first item its filter
 * names ({@link PatternFilterCells#examples}); when that grid is no match, one group at a time tries its further
 * examples (at most {@value #MAX_FILTER_EVALUATIONS} lookups in all), and the first grid that passes is the slot's
 * {@link Result#reference()}. A filter that names no item is {@link CrafterPatternCheck#FILTER_NOT_CHECKABLE}.
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
    public record Result(CrafterPatternCheck check, Optional<ItemStack> recipeResult,
                         Optional<CrafterGrid<ItemKey>> reference) {
        public static final Result NOT_CHECKED = new Result(CrafterPatternCheck.NOT_CHECKED, Optional.empty());
        public static final Result INCOMPLETE = new Result(CrafterPatternCheck.INCOMPLETE, Optional.empty());
        public static final Result VALID = new Result(CrafterPatternCheck.VALID, Optional.empty());

        public Result {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(recipeResult, "recipeResult");
            Objects.requireNonNull(reference, "reference");
        }

        /** A verdict without a reference grid. */
        public Result(CrafterPatternCheck check, Optional<ItemStack> recipeResult) {
            this(check, recipeResult, Optional.empty());
        }
    }

    /** Recipe lookups one check of a slot with filter cells may spend on other examples (§3.5). */
    public static final int MAX_FILTER_EVALUATIONS = 32;

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
        int resultCount = patterns.countAt(slot, ProductionPatterns.RESULT_ENTRY);
        Map<ItemKey, List<ItemKey>> groups = filterGroups(patterns, slot, level);
        if (groups.isEmpty())
            return verdict(level, grid, result.get(), resultCount, slot);
        for (List<ItemKey> examples : groups.values()) {
            if (examples.isEmpty())
                return new Result(CrafterPatternCheck.FILTER_NOT_CHECKABLE, Optional.empty());
        }
        // Each group at its first example; then one group at a time through its other examples, the rest kept.
        Map<ItemKey, ItemKey> picks = new LinkedHashMap<>();
        groups.forEach((filter, examples) -> picks.put(filter, examples.get(0)));
        Result last = verdict(level, substitute(grid, picks), result.get(), resultCount, slot);
        int evaluations = 1;
        for (Map.Entry<ItemKey, List<ItemKey>> group : groups.entrySet()) {
            if (last.check() == CrafterPatternCheck.VALID)
                break;
            ItemKey first = picks.get(group.getKey());
            for (int i = 1; i < group.getValue().size() && evaluations < MAX_FILTER_EVALUATIONS; i++) {
                picks.put(group.getKey(), group.getValue().get(i));
                last = verdict(level, substitute(grid, picks), result.get(), resultCount, slot);
                evaluations++;
                if (last.check() == CrafterPatternCheck.VALID)
                    break;
            }
            if (last.check() != CrafterPatternCheck.VALID)
                picks.put(group.getKey(), first);
        }
        return last;
    }

    /** The verdict of one concrete grid, with that grid as the reference when it passes. */
    private static Result verdict(Level level, CrafterGrid<ItemKey> grid, ItemKey result, int resultCount, int slot) {
        try {
            CraftingInput input = inputOf(grid);
            Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(level, input);
            if (recipe.isEmpty())
                return new Result(CrafterPatternCheck.NOT_A_RECIPE, Optional.empty());
            ItemStack made = recipe.get().value().assemble(input, level.registryAccess());
            if (made.isEmpty())
                return new Result(CrafterPatternCheck.NOT_A_RECIPE, Optional.empty());
            if (!result.matches(made) || made.getCount() != resultCount)
                return new Result(CrafterPatternCheck.RESULT_DIFFERS, Optional.of(made.copy()));
            return new Result(CrafterPatternCheck.VALID, Optional.empty(), Optional.of(grid));
        } catch (RuntimeException e) {
            // A recipe of another mod that throws on assemble must not take the station down with it.
            Wareworks.LOGGER.warn("Crafter recipe check of production pattern {} failed", slot, e);
            return new Result(CrafterPatternCheck.NOT_A_RECIPE, Optional.empty());
        }
    }

    /**
     * The filter groups of a slot (ADR-082) in grid order, each with the items its filter names
     * ({@link PatternFilterCells#examples}); empty for a slot without filter cells.
     */
    private static Map<ItemKey, List<ItemKey>> filterGroups(ProductionPatterns patterns, int slot, Level level) {
        Map<ItemKey, List<ItemKey>> groups = new LinkedHashMap<>();
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            if (!patterns.isFilterAt(slot, cell))
                continue;
            ItemKey filter = patterns.keyAt(slot, cell).orElseThrow();
            groups.computeIfAbsent(filter, key -> PatternFilterCells.examples(key, level));
        }
        return groups;
    }

    /** {@code grid} with every cell of a key in {@code picks} set to the item picked for it. */
    private static CrafterGrid<ItemKey> substitute(CrafterGrid<ItemKey> grid, Map<ItemKey, ItemKey> picks) {
        List<ItemKey> cells = new ArrayList<>(CrafterGrid.SLOTS);
        for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
            ItemKey key = grid.at(cell).orElse(null);
            cells.add(key == null ? null : picks.getOrDefault(key, key));
        }
        return CrafterGrid.ofNullable(cells);
    }

    /**
     * The concrete grid a slot with filter cells is read with where no recipe check ran (D13): every filter group at the
     * first item its filter names. Empty when a group names none. A slot without filter cells is its own layout.
     */
    public static Optional<CrafterGrid<ItemKey>> referenceLayout(ProductionPatterns patterns, int slot, Level level) {
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(level, "level");
        CrafterGrid<ItemKey> grid = layoutOf(patterns, slot);
        Map<ItemKey, List<ItemKey>> groups = filterGroups(patterns, slot, level);
        if (groups.isEmpty())
            return Optional.of(grid);
        Map<ItemKey, ItemKey> picks = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, List<ItemKey>> group : groups.entrySet()) {
            if (group.getValue().isEmpty())
                return Optional.empty();
            picks.put(group.getKey(), group.getValue().get(0));
        }
        return Optional.of(substitute(grid, picks));
    }

    /**
     * Whether a Crafter holding one item per filled cell of {@code grid} makes exactly {@code count} of {@code result}:
     * the Crafter Feeder's narrowing of a filter's candidates (ADR-082). Measured only, like {@link #makes}.
     */
    public static boolean makesExactly(Level level, CrafterGrid<ItemKey> grid, ItemKey result, int count) {
        Objects.requireNonNull(result, "result");
        return makes(level, grid).filter(result::matches).filter(made -> made.getCount() == count).isPresent();
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
     * What a vanilla Crafter holding one item per filled cell of {@code grid} would make, the result of a pattern the
     * player only named the ingredients of (ADR-076). Empty for an empty grid, without a recipe, for a recipe that
     * assembles nothing or when a recipe throws. Measured only, like {@link #output}.
     */
    public static Optional<ItemStack> makes(Level level, CrafterGrid<ItemKey> grid) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(grid, "grid");
        if (grid.isEmpty())
            return Optional.empty();
        try {
            CraftingInput input = inputOf(grid);
            Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(level, input);
            if (recipe.isEmpty())
                return Optional.empty();
            ItemStack made = recipe.get().value().assemble(input, level.registryAccess());
            return made.isEmpty() ? Optional.empty() : Optional.of(made.copy());
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Could not evaluate the recipe of a production pattern's grid", e);
            return Optional.empty();
        }
    }

    /**
     * The ingredients of the recipe {@code id} as the server holds them, tags included (a client's copy has lost them,
     * {@code RecipeTagCells}); empty when there is no such recipe. Only read, for the tag cells of a recipe viewer's
     * lay (ADR-082): nothing is matched, made or run.
     */
    public static Optional<List<Ingredient>> ingredientsOf(Level level, ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(level, "level").getRecipeManager().byKey(id)
                .map(holder -> List.copyOf(holder.value().getIngredients()));
    }

    /**
     * The recipe set the answers of {@link #check} hold for: the level's recipe manager, which a {@code /reload} replaces.
     * A cache keyed by its identity notices new or removed recipes without listening for anything, the approach of the
     * vanilla Crafter's own recipe cache.
     */
    public static Object recipeSet(Level level) {
        return Objects.requireNonNull(level, "level").getRecipeManager();
    }

    /**
     * The 3 x 3 layout of pattern slot {@code slot}: cell {@code i} is Crafter slot {@code i}. A filter cell (ADR-082)
     * holds the filter's own key here, which is what {@code CrafterFeedPlan#segment} replaces per batch.
     */
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
