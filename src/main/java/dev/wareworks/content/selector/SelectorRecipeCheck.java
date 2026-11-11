package dev.wareworks.content.selector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/**
 * The pattern check of a Recipe Selector station (ADR-085, design §10): would the machine, with its filter set to the
 * pattern's result item, make exactly the pattern's result from exactly the pattern's ingredients, every time?
 * <p>
 * <b>It only evaluates recipes, it runs none</b> (ADR-059, {@code RecipeExecutionBoundaryTest}): it looks recipes up
 * and reads what they make, the same lists the machine itself reads, and nothing else.
 * <ul>
 * <li>A recipe <b>passes</b> when it would run with the filter set: its result item is the pattern's result and its
 * ingredients are in the pattern (the saw: its first ingredient takes a pattern item, as {@code SawBlockEntity#getRecipes}
 * asks; a basin: every ingredient takes its own pattern item). The candidates are the machine's own recipe types,
 * mirrored from Create: the saw's cutting and (if the server allows it) stonecutting recipes; the Mixer's mixing and
 * shapeless crafting recipes of two or more ingredients; the Press's compacting and square crafting recipes; a basin
 * without an operator both. Recipes Create ignores in automation never pass where the machine ignores them: at the
 * saw all of them, at a basin only the crafting recipes it borrows (Create filters only those).</li>
 * <li>{@link Verdict#VALID} iff at least one recipe passes and every passing one has exactly one output, the result
 * item, with no chance and the pattern's per-run count, and consumes exactly the pattern's per-run items. Several
 * passing recipes must agree ({@link Verdict#AMBIGUOUS} otherwise), because the machine may take any of them.</li>
 * </ul>
 * Why it is part of the line's safety (I3): a wrong or uncertain yield would complete an order early, with items still
 * in the line, or never.
 */
public final class SelectorRecipeCheck {
    /** The outcome of a check. Saved nowhere; the screen shows it by {@link #langKey()}. */
    public enum Verdict {
        VALID,
        /** No recipe of the machine makes the result from the pattern's ingredients. */
        NO_RECIPE,
        /** The machine makes another amount, or from another amount, than the pattern says. */
        WRONG_COUNT,
        /** The machine makes the result only by chance, or with byproducts of chance. */
        CHANCE,
        /** Several recipes of the machine make the result with different amounts. */
        AMBIGUOUS,
        /** Only a stonecutting recipe would make it, and the server does not let saws stonecut. */
        STONECUTTING_OFF,
        /** The recipe needs or makes a fluid, which a selector station cannot handle yet. */
        FLUID;

        /** The relative lang key, {@code gui.production.selector.check.<name>}. */
        public String langKey() {
            return "gui.production.selector.check." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** The three Create recipe switches a check mirrors; {@link #fromServer()} reads them from Create's config. */
    public record Options(boolean stonecuttingOnSaw, boolean shapelessInMixer, boolean squareInPress) {
        public static Options fromServer() {
            return new Options(AllConfigs.server().recipes.allowStonecuttingOnSaw.get(),
                    AllConfigs.server().recipes.allowShapelessInMixer.get(),
                    AllConfigs.server().recipes.allowShapedSquareInPress.get());
        }
    }

    /** One output of a recipe: the stack it makes and its chance (1 for every crafting result). */
    public record Output(ItemStack stack, float chance) {
        public Output {
            Objects.requireNonNull(stack, "stack");
        }
    }

    /**
     * One recipe as the check sees it: its item ingredients (one item each), its outputs, whether it needs or makes a
     * fluid, and whether it is a stonecutting recipe (which a saw takes only where the server allows it).
     */
    public record Candidate(List<Ingredient> ingredients, List<Output> outputs, boolean fluid, boolean stonecutting) {
        public Candidate {
            ingredients = List.copyOf(ingredients);
            outputs = List.copyOf(outputs);
        }

        /** The item the machine's filter is tested against: the first output's. */
        Item primary() {
            return outputs.isEmpty() ? null : outputs.get(0).stack().getItem();
        }
    }

    private SelectorRecipeCheck() {
    }

    /** The object a cached verdict depends on: a {@code /reload} replaces it. */
    public static Object recipeSet(Level level) {
        return Objects.requireNonNull(level, "level").getRecipeManager();
    }

    /** {@link #check(Level, SelectorMachine.Kind, ProductionPattern, Options)} with the server's own Create config. */
    public static Verdict check(Level level, SelectorMachine.Kind kind, ProductionPattern<ItemKey> pattern) {
        return check(level, kind, pattern, Options.fromServer());
    }

    /** Checks {@code pattern} against the recipes of the machine kind {@code kind}. */
    public static Verdict check(Level level, SelectorMachine.Kind kind, ProductionPattern<ItemKey> pattern,
            Options options) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(options, "options");
        if (pattern.hasChoices())
            return Verdict.NO_RECIPE;
        return judge(kind, candidatesFor(level, kind, pattern.result().key().getItem(), options), pattern, options);
    }

    /**
     * The verdict over a list of candidates (recipes of the machine whose output may be the result), pure apart from
     * the ingredients' item tests: what {@link #check} decides once it has looked the recipes up. Public for the
     * GameTests, which also feed it recipes no data pack has (a chance output).
     */
    public static Verdict judge(SelectorMachine.Kind kind, List<Candidate> candidates, ProductionPattern<ItemKey> pattern,
            Options options) {
        Item result = pattern.result().key().getItem();
        int perRun = pattern.result().count();
        Map<ItemKey, Integer> items = aggregate(pattern.ingredients());
        int patternItems = 0;
        for (int count : items.values())
            patternItems += count;
        List<Candidate> passing = new ArrayList<>();
        boolean stonecuttingWould = false;
        boolean fluidWould = false;
        for (Candidate candidate : candidates) {
            if (candidate.primary() != result || !ingredientsFit(kind, candidate, items))
                continue;
            if (candidate.stonecutting() && !options.stonecuttingOnSaw()) {
                stonecuttingWould = true;
                continue;
            }
            if (candidate.fluid()) {
                fluidWould = true;
                continue;
            }
            passing.add(candidate);
        }
        if (fluidWould)
            return Verdict.FLUID;
        if (passing.isEmpty())
            return stonecuttingWould ? Verdict.STONECUTTING_OFF : Verdict.NO_RECIPE;
        Set<List<Integer>> shapes = new LinkedHashSet<>();
        for (Candidate candidate : passing) {
            for (Output output : candidate.outputs()) {
                if (output.chance() < 1.0F)
                    return Verdict.CHANCE;
            }
            int consumed = kind == SelectorMachine.Kind.SAW ? 1 : candidate.ingredients().size();
            int made = candidate.outputs().size() == 1 ? candidate.outputs().get(0).stack().getCount() : -1;
            shapes.add(List.of(made, consumed));
        }
        if (shapes.size() > 1)
            return Verdict.AMBIGUOUS;
        List<Integer> shape = shapes.iterator().next();
        return shape.get(0) == perRun && shape.get(1) == patternItems ? Verdict.VALID : Verdict.WRONG_COUNT;
    }

    /**
     * The far factory's veto (design §7.6 step 7): whether the machine kind {@code kind} could make {@code result} from
     * {@code contents}, the items of one package. True when at least one recipe of the machine makes the result as a
     * pattern check could accept it (one output, no chance, no fluid, stonecutting only where the server allows it) and
     * <b>every</b> item kind in the package is an ingredient of such a recipe (the saw: its first ingredient). Counts
     * are not checked here: the warehouse's pattern check did that before the order was made, and a package may hold
     * any part of an order. Evaluates only.
     */
    public static boolean canMake(Level level, SelectorMachine.Kind kind, Item result, Collection<ItemStack> contents) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(contents, "contents");
        Options options = Options.fromServer();
        List<Candidate> usable = new ArrayList<>();
        for (Candidate candidate : candidatesFor(level, kind, result, options)) {
            if (candidate.fluid() || (candidate.stonecutting() && !options.stonecuttingOnSaw())
                    || candidate.outputs().size() != 1)
                continue;
            boolean chance = false;
            for (Output output : candidate.outputs())
                chance |= output.chance() < 1.0F;
            if (!chance && !candidate.ingredients().isEmpty())
                usable.add(candidate);
        }
        if (usable.isEmpty())
            return false;
        for (ItemStack stack : contents) {
            if (stack.isEmpty())
                continue;
            boolean used = false;
            for (Candidate candidate : usable) {
                List<Ingredient> ingredients = kind == SelectorMachine.Kind.SAW
                        ? List.of(candidate.ingredients().get(0)) : candidate.ingredients();
                for (Ingredient ingredient : ingredients)
                    used |= ingredient.test(stack);
                if (used)
                    break;
            }
            if (!used)
                return false;
        }
        return true;
    }

    /**
     * Whether a candidate would run on the pattern's ingredients: the saw needs its first ingredient to take one of
     * them; a basin needs every ingredient to take a pattern item of its own (the pattern holds the recipe).
     */
    private static boolean ingredientsFit(SelectorMachine.Kind kind, Candidate candidate, Map<ItemKey, Integer> items) {
        List<Ingredient> ingredients = candidate.ingredients();
        if (ingredients.isEmpty())
            return false;
        if (kind == SelectorMachine.Kind.SAW) {
            for (ItemKey key : items.keySet()) {
                if (ingredients.get(0).test(key.toStack()))
                    return true;
            }
            return false;
        }
        List<ItemKey> keys = new ArrayList<>(items.keySet());
        int[] left = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++)
            left[i] = items.get(keys.get(i));
        int[] assigned = new int[ingredients.size()];
        java.util.Arrays.fill(assigned, -1);
        return assign(ingredients, keys, left, assigned, 0);
    }

    /** Backtracking: gives each ingredient from {@code index} on a pattern item with some left (at most 9 x few). */
    private static boolean assign(List<Ingredient> ingredients, List<ItemKey> keys, int[] left, int[] assigned,
            int index) {
        if (index == ingredients.size())
            return true;
        Ingredient ingredient = ingredients.get(index);
        for (int key = 0; key < keys.size(); key++) {
            if (left[key] == 0 || !ingredient.test(keys.get(key).toStack()))
                continue;
            left[key]--;
            assigned[index] = key;
            if (assign(ingredients, keys, left, assigned, index + 1))
                return true;
            left[key]++;
        }
        return false;
    }

    private static Map<ItemKey, Integer> aggregate(List<ProductionEntry<ItemKey>> entries) {
        Map<ItemKey, Integer> items = new LinkedHashMap<>();
        for (ProductionEntry<ItemKey> entry : entries)
            items.merge(entry.key(), entry.count(), Integer::sum);
        return items;
    }

    // --- the machine's own recipe lists --------------------------------------------------------------------------

    /**
     * The candidate lists of each recipe set (review fix, Create API 1): per {@link RecipeManager} instance, which a
     * {@code /reload} replaces, and per machine kind and {@link Options}, every candidate by its result item. One scan
     * of all recipes fills a kind's map; every later check, and every veto of a box a port offers again and again, is a
     * map lookup, the way Create's own {@code RecipeFinder} caches the saw's and the basins' searches. Weak keys, so an
     * old recipe set goes with its server.
     */
    private static final Map<RecipeManager, Map<IndexKey, Map<Item, List<Candidate>>>> INDEX = new WeakHashMap<>();

    private record IndexKey(SelectorMachine.Kind kind, Options options) {
    }

    /**
     * Every recipe of the machine kind whose result item is {@code result}, as candidates: from the cache of the
     * level's current recipe set, filled on the first lookup of the kind and options. Public for the GameTests, which
     * check that a second lookup does not scan again.
     */
    public static List<Candidate> candidatesFor(Level level, SelectorMachine.Kind kind, Item result, Options options) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(options, "options");
        RecipeManager recipes = level.getRecipeManager();
        Map<IndexKey, Map<Item, List<Candidate>>> perSet;
        synchronized (INDEX) {
            perSet = INDEX.computeIfAbsent(recipes, ignored -> new ConcurrentHashMap<>());
        }
        Map<Item, List<Candidate>> byResult = perSet.computeIfAbsent(new IndexKey(kind, options),
                key -> index(kind, recipes.getRecipes(), options, level.registryAccess()));
        return byResult.getOrDefault(result, List.of());
    }

    /**
     * The candidates among {@code recipes} the machine kind {@code kind} would take, by result item; recipes Create
     * ignores in automation are left out exactly where the machine leaves them out (review fix, Create API 2): the saw
     * for every recipe ({@code SawBlockEntity#getRecipes}), the Mixer and the Press only for the crafting recipes they
     * borrow, never for their own mixing and compacting recipes ({@code #matchStaticFilters} of both). Public for the
     * GameTests, which feed it recipes no data pack has.
     */
    public static Map<Item, List<Candidate>> index(SelectorMachine.Kind kind, Iterable<? extends RecipeHolder<?>> recipes,
            Options options, HolderLookup.Provider registries) {
        Map<Item, List<Candidate>> found = new HashMap<>();
        for (RecipeHolder<?> holder : recipes) {
            if (!accepts(kind, holder, options))
                continue;
            Candidate candidate = candidateOf(holder.value(), registries);
            if (candidate != null)
                found.computeIfAbsent(candidate.primary(), ignored -> new ArrayList<>()).add(candidate);
        }
        Map<Item, List<Candidate>> frozen = new HashMap<>(found.size());
        found.forEach((item, list) -> frozen.put(item, List.copyOf(list)));
        return Map.copyOf(frozen);
    }

    /** Whether the machine kind would take {@code holder} at all, mirroring Create's static recipe filters. */
    private static boolean accepts(SelectorMachine.Kind kind, RecipeHolder<?> holder, Options options) {
        RecipeType<?> type = holder.value().getType();
        return switch (kind) {
            case SAW -> (type == AllRecipeTypes.CUTTING.getType() || type == RecipeType.STONECUTTING)
                    && !AllRecipeTypes.shouldIgnoreInAutomation(holder);
            case MIXER_BASIN -> mixerTakes(holder, options);
            case PRESS_BASIN -> pressTakes(holder, options);
            case BASIN_ALONE -> mixerTakes(holder, options) || pressTakes(holder, options);
        };
    }

    /** {@code MechanicalMixerBlockEntity#matchStaticFilters}: the automation filter only on the crafting branch. */
    private static boolean mixerTakes(RecipeHolder<?> holder, Options options) {
        Recipe<?> recipe = holder.value();
        if (recipe.getType() == AllRecipeTypes.MIXING.getType())
            return true;
        return recipe instanceof CraftingRecipe && !(recipe instanceof ShapedRecipe) && options.shapelessInMixer()
                && nonEmpty(recipe.getIngredients()).size() > 1 && !MechanicalPressBlockEntity.canCompress(recipe)
                && !AllRecipeTypes.shouldIgnoreInAutomation(holder);
    }

    /** {@code MechanicalPressBlockEntity#matchStaticFilters}: the automation filter only on the crafting branch. */
    private static boolean pressTakes(RecipeHolder<?> holder, Options options) {
        Recipe<?> recipe = holder.value();
        if (recipe.getType() == AllRecipeTypes.COMPACTING.getType())
            return true;
        return recipe instanceof CraftingRecipe && !(recipe instanceof MechanicalCraftingRecipe)
                && options.squareInPress() && MechanicalPressBlockEntity.canCompress(recipe)
                && !AllRecipeTypes.shouldIgnoreInAutomation(holder);
    }

    /** A recipe as a candidate, or {@code null} if it makes no item. */
    private static Candidate candidateOf(Recipe<?> recipe, HolderLookup.Provider registries) {
        List<Ingredient> ingredients = nonEmpty(recipe.getIngredients());
        if (recipe instanceof ProcessingRecipe<?, ?> processing) {
            List<Output> outputs = new ArrayList<>();
            for (ProcessingOutput output : processing.getRollableResults())
                outputs.add(new Output(output.getStack(), output.getChance()));
            if (outputs.isEmpty())
                return null;
            boolean fluid = !processing.getFluidIngredients().isEmpty() || !processing.getFluidResults().isEmpty();
            return new Candidate(ingredients, outputs, fluid, false);
        }
        ItemStack made = recipe.getResultItem(registries);
        if (made.isEmpty())
            return null;
        return new Candidate(ingredients, List.of(new Output(made, 1.0F)), false,
                recipe.getType() == RecipeType.STONECUTTING);
    }

    private static List<Ingredient> nonEmpty(List<Ingredient> ingredients) {
        List<Ingredient> kept = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            if (!ingredient.isEmpty())
                kept.add(ingredient);
        }
        return kept;
    }
}
