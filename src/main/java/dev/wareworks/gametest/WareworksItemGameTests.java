package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingInput;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.kinetics.crafter.RecipeGridHandler;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.util.entry.RegistryEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksCreativeTabs;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Item-level content checks (M4, ingredients pinned in M9): every Wareworks block item has its recipe loaded by the
 * server's recipe manager with exactly the ingredients it should have, the recipes cannot be confused with one another,
 * and the creative tab lists the items in building order with the stacker crane as icon.
 * <p>
 * Recipes are hand-written JSON in {@code data/wareworks/recipe/<item>.json} (1.21.1 format). A recipe whose JSON does
 * not parse, or that names an item or tag that does not exist, is skipped by the recipe manager with a "Parsing error
 * loading recipe" log line; {@link #recipesLoaded} turns that into a test failure, and also pins every ingredient, so a
 * later silent rebalance fails here instead of shipping.
 * <p>
 * Create's items are looked up by id inside the tests, never in a static initializer: this holder class is loaded while
 * NeoForge scans for {@link GameTestHolder} annotations, which need not be after Create has filled the item registry.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WareworksItemGameTests {
    /** Mechanical crafting yields one crane; shaped recipes as rebalanced in M9 (ADR-015). */
    private static final int ONE = 1;
    /** Rails per craft since the M9 rebalance (was 8): aisle length is capacity, so it carries a real cost. */
    private static final int RAILS_PER_CRAFT = 4;
    /** Width of the crane's mechanical crafting pattern (3 x 4, mirrored accepted). */
    private static final int CRANE_WIDTH = 3;

    private static final TagKey<Item> IRON_PLATES = itemTag("c", "plates/iron");
    private static final TagKey<Item> BRASS_NUGGETS = itemTag("c", "nuggets/brass");

    private static final String ANDESITE_ALLOY = "andesite_alloy";
    private static final String ANDESITE_CASING = "andesite_casing";
    private static final String ANDESITE_FUNNEL = "andesite_funnel";
    private static final String BRASS_CASING = "brass_casing";
    private static final String BRASS_FUNNEL = "brass_funnel";
    private static final String BRASS_HAND = "brass_hand";
    private static final String BRASS_NUGGET = "brass_nugget";
    private static final String COMPARATOR = "comparator";
    private static final String ELECTRON_TUBE = "electron_tube";
    private static final String INDUSTRIAL_IRON_BLOCK = "industrial_iron_block";
    private static final String IRON_SHEET = "iron_sheet";
    private static final String LARGE_COGWHEEL = "large_cogwheel";
    private static final String NIXIE_TUBE = "nixie_tube";
    private static final String PRECISION_MECHANISM = "precision_mechanism";
    private static final String SHAFT = "shaft";

    /** A recipe the mod must ship: recipe id = item id, recipe type, result count and the exact ingredients. */
    private record ExpectedRecipe(BlockEntry<?> block, RecipeType<?> type, int count, List<IngredientSpec> ingredients) {
    }

    /**
     * One ingredient slot as written in the recipe JSON: either an exact item ({@code "item": ...}) or an item tag
     * ({@code "tag": ...}). Matching is deliberately strict — a tag where an item is expected (or the other way round)
     * is a different recipe, even when both accept the same stack today.
     */
    private record IngredientSpec(ResourceLocation item, TagKey<Item> tag) {
        static IngredientSpec of(String createPath) {
            return new IngredientSpec(createId(createPath), null);
        }

        /** A vanilla item, for the one ingredient that is not Create's ({@code minecraft:comparator}). */
        static IngredientSpec ofVanilla(String path) {
            return new IngredientSpec(ResourceLocation.withDefaultNamespace(path), null);
        }

        static IngredientSpec ofTag(TagKey<Item> tag) {
            return new IngredientSpec(null, tag);
        }

        boolean matches(Ingredient ingredient) {
            if (ingredient.isCustom())
                return false;
            Ingredient.Value[] values = ingredient.getValues();
            if (values.length != 1)
                return false;
            if (tag != null)
                return values[0] instanceof Ingredient.TagValue tagValue && tagValue.tag().equals(tag);
            return values[0] instanceof Ingredient.ItemValue itemValue
                    && itemValue.item().is(BuiltInRegistries.ITEM.get(item));
        }

        @Override
        public String toString() {
            return tag != null ? "#" + tag.location() : item.toString();
        }
    }

    private WareworksItemGameTests() {
    }

    /**
     * Every Wareworks item has exactly the recipe it should have — type, result count, ingredients — and no other
     * Wareworks recipe exists.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void recipesLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecipeManager recipes = level.getRecipeManager();
        HolderLookup.Provider registries = level.registryAccess();
        List<ExpectedRecipe> expected = expectedRecipes();

        for (ExpectedRecipe recipe : expected) {
            Item item = recipe.block().asItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            Optional<RecipeHolder<?>> holder = recipes.byKey(id);
            helper.assertTrue(holder.isPresent(), "recipe " + id + " is not loaded (see 'Parsing error loading recipe' in the log)");
            helper.assertValueEqual(holder.get().value().getType(), recipe.type(), "recipe type of " + id);
            ItemStack result = holder.get().value().getResultItem(registries);
            helper.assertTrue(result.is(item), "recipe " + id + " makes " + result);
            helper.assertValueEqual(result.getCount(), recipe.count(), "result count of " + id);
            assertIngredients(helper, id, holder.get(), recipe.ingredients());
        }
        // Recipe-unlock advancements. Without one, vanilla never lists the recipe in the recipe book: RecipeCollection
        // only keeps recipes the player's RecipeBook contains, and nothing else puts them there. Create's own
        // mechanical crafting recipes ship no advancement either (mechanical crafting has no vanilla recipe-book
        // category), so the stacker crane has none and is found through JEI/EMI only.
        ServerAdvancementManager advancements = level.getServer().getAdvancements();
        for (ExpectedRecipe recipe : expected) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(recipe.block().asItem());
            ResourceLocation advancementId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                    "recipes/misc/" + id.getPath());
            AdvancementHolder holder = advancements.get(advancementId);
            if (recipe.type() != RecipeType.CRAFTING) {
                helper.assertTrue(holder == null, "mechanical crafting cannot appear in the recipe book, so "
                        + advancementId + " must not exist");
                continue;
            }
            helper.assertTrue(holder != null, "missing recipe unlock advancement " + advancementId
                    + ": the recipe would never show up in the vanilla recipe book");
            helper.assertValueEqual(holder.value().rewards().recipes(), List.of(id),
                    "recipes unlocked by " + advancementId);
        }

        for (RegistryEntry<Item, Item> entry : Wareworks.registrate().<Item, Item>getAll(Registries.ITEM))
            helper.assertTrue(recipes.byKey(entry.getId()).isPresent(), "item " + entry.getId() + " has no recipe");
        long wareworksRecipes = recipes.getRecipeIds().filter(id -> id.getNamespace().equals(Wareworks.ID)).count();
        helper.assertValueEqual(wareworksRecipes, (long) expected.size(), "number of wareworks recipes");
        helper.succeed();
    }

    /**
     * No two Wareworks recipes can be confused: each one's own grid crafts exactly its own block, and no two of them
     * share an ingredient set. The andesite family is the reason — input, output and interface are built from the same
     * andesite casing and funnel, so the output carries brass nuggets (M9) instead of differing from the input only by
     * arrangement, and none of the three shadows another in the crafting table.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void recipesAreUnambiguous(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecipeManager recipes = level.getRecipeManager();

        ItemStack casing = stack(ANDESITE_CASING);
        ItemStack funnel = stack(ANDESITE_FUNNEL);
        ItemStack nugget = stack(BRASS_NUGGET);
        ItemStack none = ItemStack.EMPTY;

        // Input: funnel over casing. Output: casing over funnel, flanked by brass nuggets. Interface: shapeless.
        CraftingInput input = grid(1, 2, funnel, casing);
        CraftingInput output = grid(3, 2, none, casing, none, nugget, funnel, nugget);
        CraftingInput anInterface = grid(3, 1, casing, funnel, nugget);
        assertCrafts(helper, recipes, level, input, WareworksBlocks.WAREHOUSE_INPUT.asItem(), ONE);
        assertCrafts(helper, recipes, level, output, WareworksBlocks.WAREHOUSE_OUTPUT.asItem(), ONE);
        assertCrafts(helper, recipes, level, anInterface, WareworksBlocks.WAREHOUSE_INTERFACE.asItem(), ONE);

        // The remaining shaped recipes, each on its own grid.
        ItemStack tube = stack(ELECTRON_TUBE);
        ItemStack nixie = stack(NIXIE_TUBE);
        ItemStack brass = stack(BRASS_CASING);
        ItemStack mechanism = stack(PRECISION_MECHANISM);
        ItemStack alloy = stack(ANDESITE_ALLOY);
        ItemStack iron = stack(INDUSTRIAL_IRON_BLOCK);
        CraftingInput controller = grid(3, 3, none, nixie, none, tube, brass, tube, none, mechanism, none);
        CraftingInput terminal = grid(3, 3, nixie, none, nixie, tube, brass, tube, none, mechanism, none);
        CraftingInput rail = grid(3, 3, none, iron, none, alloy, iron, alloy, none, stack(SHAFT), none);
        ItemStack brassFunnel = stack(BRASS_FUNNEL);
        CraftingInput production = grid(3, 3, none, brassFunnel, none, brassFunnel, brass, brassFunnel, none, alloy,
                none);
        assertCrafts(helper, recipes, level, controller, WareworksBlocks.WAREHOUSE_CONTROLLER.asItem(), ONE);
        assertCrafts(helper, recipes, level, terminal, WareworksBlocks.WAREHOUSE_TERMINAL.asItem(), ONE);
        assertCrafts(helper, recipes, level, rail, WareworksBlocks.WAREHOUSE_RAIL.asItem(), RAILS_PER_CRAFT);
        CraftingInput keeper = grid(3, 3, none, vanilla(COMPARATOR), none, tube, brass, tube, none, alloy, none);
        assertCrafts(helper, recipes, level, production, WareworksBlocks.WAREHOUSE_PRODUCTION.asItem(), ONE);
        assertCrafts(helper, recipes, level, keeper, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asItem(), ONE);

        // The crane is mechanical crafting. MechanicalCraftingRecipe#matches rejects every input that is not a
        // MechanicalCraftingInput, so the grid has to be built the way a crafter tower builds it.
        ItemStack[] cranePattern = {
                stack(IRON_SHEET), tube, none,
                stack(IRON_SHEET), mechanism, stack(BRASS_HAND),
                stack(IRON_SHEET), brass, none,
                alloy, stack(LARGE_COGWHEEL), alloy };
        ResourceLocation craneId = BuiltInRegistries.ITEM.getKey(WareworksBlocks.STACKER_CRANE.asItem());
        MechanicalCraftingInput crane = crafterGrid(level.registryAccess(), CRANE_WIDTH, cranePattern);
        RecipeType<MechanicalCraftingRecipe> mechanical = AllRecipeTypes.MECHANICAL_CRAFTING.getType();
        Optional<RecipeHolder<MechanicalCraftingRecipe>> craneRecipe = recipes.getRecipeFor(mechanical, crane, level);
        helper.assertTrue(craneRecipe.isPresent(), "the stacker crane pattern crafts nothing in a mechanical crafter");
        helper.assertValueEqual(craneRecipe.get().id(), craneId, "recipe found for the stacker crane pattern");

        // The recipe ships accept_mirrored, which is live behaviour and not decoration: MechanicalCraftingRecipe#matches
        // delegates to the vanilla pattern (which tries the horizontal mirror) only while acceptsMirrored(), and runs a
        // hand-rolled scan without any symmetry otherwise. The crane's pattern is not symmetrical, so the two branches
        // disagree about the mirror image — without this assertion a flipped flag would ship unnoticed.
        MechanicalCraftingInput mirroredCrane = crafterGrid(level.registryAccess(), CRANE_WIDTH,
                mirrorRows(CRANE_WIDTH, cranePattern));
        Optional<RecipeHolder<MechanicalCraftingRecipe>> mirrored = recipes.getRecipeFor(mechanical, mirroredCrane, level);
        helper.assertTrue(mirrored.isPresent(),
                "the mirrored stacker crane pattern crafts nothing: accept_mirrored is no longer in effect");
        helper.assertValueEqual(mirrored.get().id(), craneId, "recipe found for the mirrored stacker crane pattern");

        // ... and none of it makes a Wareworks block in a crafting table. The whole 3 x 4 pattern cannot even be laid
        // out in one (and is a different recipe type anyway), so what a player can really hit is a 3 x 3 window of it.
        assertNoWareworksRecipe(helper, recipes, level, grid(CRANE_WIDTH, 4, cranePattern), "the 3 x 4 crane pattern");
        for (int top = 0; top + 3 <= 4; top++) {
            ItemStack[] window = Arrays.copyOfRange(cranePattern, top * CRANE_WIDTH, (top + 3) * CRANE_WIDTH);
            assertNoWareworksRecipe(helper, recipes, level, grid(CRANE_WIDTH, 3, window),
                    "rows " + top + "-" + (top + 2) + " of the crane pattern");
        }

        // No two Wareworks recipes share an ingredient set (ADR-015): the hard rule is that none of them may be
        // reachable from another's grid, the house rule above it is that they differ by what they cost rather than by
        // arrangement alone. Signatures are the accepted item ids, so a tag and an item spelling of the same stack
        // collide instead of slipping through.
        Map<String, ResourceLocation> byIngredients = new HashMap<>();
        for (ExpectedRecipe recipe : expectedRecipes()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(recipe.block().asItem());
            String signature = signatureOf(recipes.byKey(id).orElseThrow());
            ResourceLocation clash = byIngredients.put(signature, id);
            helper.assertTrue(clash == null, id + " and " + clash + " are made from the same ingredients ["
                    + signature + "]: they would differ only by arrangement");
        }
        helper.succeed();
    }

    /** The creative tab lists the items in building order and shows the stacker crane as its icon. */
    @GameTest(template = EMPTY_7X5X7)
    public static void creativeTabOrderAndIcon(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), false, level.registryAccess());
        CreativeModeTab tab = WareworksCreativeTabs.BASE.get();
        List<Item> shown = tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        helper.assertValueEqual(shown, List.of(WareworksBlocks.STACKER_CRANE.asItem(), WareworksBlocks.WAREHOUSE_RAIL.asItem(),
                WareworksBlocks.WAREHOUSE_CONTROLLER.asItem(), WareworksBlocks.WAREHOUSE_INTERFACE.asItem(),
                WareworksBlocks.WAREHOUSE_INPUT.asItem(), WareworksBlocks.WAREHOUSE_OUTPUT.asItem(),
                WareworksBlocks.WAREHOUSE_TERMINAL.asItem(), WareworksBlocks.WAREHOUSE_PRODUCTION.asItem(),
                WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asItem()),
                "creative tab order");
        helper.assertTrue(tab.getIconItem().is(WareworksBlocks.STACKER_CRANE.asItem()), "creative tab icon: " + tab.getIconItem());
        helper.succeed();
    }

    /**
     * The recipes as they must be on disk (M9 rebalance): the crane shows its grabber (brass hand), the rail is a
     * mechanical track (shaft) at half the old yield, the terminal sits at controller tier (precision mechanism) and
     * the output is the brass member of the andesite family (brass nuggets).
     */
    private static List<ExpectedRecipe> expectedRecipes() {
        return List.of(
                new ExpectedRecipe(WareworksBlocks.STACKER_CRANE, AllRecipeTypes.MECHANICAL_CRAFTING.getType(), ONE,
                        List.of(IngredientSpec.ofTag(IRON_PLATES), IngredientSpec.ofTag(IRON_PLATES),
                                IngredientSpec.ofTag(IRON_PLATES), IngredientSpec.of(ELECTRON_TUBE),
                                IngredientSpec.of(PRECISION_MECHANISM), IngredientSpec.of(BRASS_HAND),
                                IngredientSpec.of(BRASS_CASING), IngredientSpec.of(ANDESITE_ALLOY),
                                IngredientSpec.of(LARGE_COGWHEEL), IngredientSpec.of(ANDESITE_ALLOY))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_RAIL, RecipeType.CRAFTING, RAILS_PER_CRAFT,
                        List.of(IngredientSpec.of(INDUSTRIAL_IRON_BLOCK), IngredientSpec.of(ANDESITE_ALLOY),
                                IngredientSpec.of(INDUSTRIAL_IRON_BLOCK), IngredientSpec.of(ANDESITE_ALLOY),
                                IngredientSpec.of(SHAFT))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_CONTROLLER, RecipeType.CRAFTING, ONE,
                        List.of(IngredientSpec.of(NIXIE_TUBE), IngredientSpec.of(ELECTRON_TUBE),
                                IngredientSpec.of(BRASS_CASING), IngredientSpec.of(ELECTRON_TUBE),
                                IngredientSpec.of(PRECISION_MECHANISM))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_INTERFACE, RecipeType.CRAFTING, ONE,
                        List.of(IngredientSpec.of(ANDESITE_CASING), IngredientSpec.of(ANDESITE_FUNNEL),
                                IngredientSpec.ofTag(BRASS_NUGGETS))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_INPUT, RecipeType.CRAFTING, ONE,
                        List.of(IngredientSpec.of(ANDESITE_FUNNEL), IngredientSpec.of(ANDESITE_CASING))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_OUTPUT, RecipeType.CRAFTING, ONE,
                        List.of(IngredientSpec.of(ANDESITE_CASING), IngredientSpec.ofTag(BRASS_NUGGETS),
                                IngredientSpec.of(ANDESITE_FUNNEL), IngredientSpec.ofTag(BRASS_NUGGETS))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_TERMINAL, RecipeType.CRAFTING, ONE,
                        List.of(IngredientSpec.of(NIXIE_TUBE), IngredientSpec.of(NIXIE_TUBE),
                                IngredientSpec.of(ELECTRON_TUBE), IngredientSpec.of(BRASS_CASING),
                                IngredientSpec.of(ELECTRON_TUBE), IngredientSpec.of(PRECISION_MECHANISM))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_PRODUCTION, RecipeType.CRAFTING, ONE,
                        List.of(IngredientSpec.of(BRASS_FUNNEL), IngredientSpec.of(BRASS_FUNNEL),
                                IngredientSpec.of(BRASS_CASING), IngredientSpec.of(BRASS_FUNNEL),
                                IngredientSpec.of(ANDESITE_ALLOY))),
                new ExpectedRecipe(WareworksBlocks.WAREHOUSE_STOCK_KEEPER, RecipeType.CRAFTING, ONE,
                        List.of(IngredientSpec.ofVanilla(COMPARATOR), IngredientSpec.of(ELECTRON_TUBE),
                                IngredientSpec.of(BRASS_CASING), IngredientSpec.of(ELECTRON_TUBE),
                                IngredientSpec.of(ANDESITE_ALLOY))));
    }

    /** Asserts that the recipe's filled slots are exactly the expected ingredients, in any order and with duplicates. */
    private static void assertIngredients(GameTestHelper helper, ResourceLocation id, RecipeHolder<?> holder,
            List<IngredientSpec> expected) {
        List<Ingredient> actual = holder.value().getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).toList();
        helper.assertValueEqual(actual.size(), expected.size(), "number of ingredients of " + id);
        List<IngredientSpec> open = new ArrayList<>(expected);
        for (Ingredient ingredient : actual) {
            boolean matched = false;
            for (Iterator<IngredientSpec> it = open.iterator(); it.hasNext();) {
                if (it.next().matches(ingredient)) {
                    it.remove();
                    matched = true;
                    break;
                }
            }
            helper.assertTrue(matched, "recipe " + id + " has an unexpected ingredient " + describe(ingredient)
                    + "; still expected: " + open);
        }
        helper.assertTrue(open.isEmpty(), "recipe " + id + " is missing the ingredients " + open);
    }

    /** Asserts that this grid crafts exactly that item in a crafting table, and nothing else. */
    private static void assertCrafts(GameTestHelper helper, RecipeManager recipes, ServerLevel level, CraftingInput input,
            Item expected, int count) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(expected);
        Optional<RecipeHolder<CraftingRecipe>> found = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(found.isPresent(), "the grid of " + id + " crafts nothing");
        helper.assertValueEqual(found.get().id(), id, "recipe found for the grid of " + id);
        ItemStack result = found.get().value().assemble(input, level.registryAccess());
        helper.assertTrue(result.is(expected), "the grid of " + id + " makes " + result);
        helper.assertValueEqual(result.getCount(), count, "result count for the grid of " + id);
    }

    /** Asserts that this grid makes no Wareworks block in a crafting table (other mods' recipes are their business). */
    private static void assertNoWareworksRecipe(GameTestHelper helper, RecipeManager recipes, ServerLevel level,
            CraftingInput input, String what) {
        Optional<RecipeHolder<CraftingRecipe>> found = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
        boolean ours = found.isPresent() && found.get().id().getNamespace().equals(Wareworks.ID);
        helper.assertTrue(!ours, what + " must not craft a Wareworks block in a crafting table, but makes "
                + found.map(RecipeHolder::id).orElse(null));
    }

    /** The same pattern with every row reversed, i.e. the horizontal mirror image a mechanical crafter also accepts. */
    private static ItemStack[] mirrorRows(int width, ItemStack... items) {
        ItemStack[] mirrored = new ItemStack[items.length];
        for (int slot = 0; slot < items.length; slot++) {
            int row = slot / width;
            int column = slot % width;
            mirrored[row * width + (width - 1 - column)] = items[slot];
        }
        return mirrored;
    }

    /**
     * A recipe's ingredients as one order-independent string, used to compare two recipes' costs. Each ingredient is
     * resolved to the item ids it actually accepts, not to the way the JSON spells it, so that {@code #c:nuggets/brass}
     * and {@code create:brass_nugget} — the same cost and the same grid to a player — collide here. The strict
     * item-vs-tag comparison stays where it belongs: in {@link IngredientSpec#matches}, which pins the JSON.
     */
    private static String signatureOf(RecipeHolder<?> holder) {
        return holder.value().getIngredients().stream().filter(ingredient -> !ingredient.isEmpty())
                .map(WareworksItemGameTests::acceptedItems).sorted().collect(Collectors.joining(", "));
    }

    /** The item ids an ingredient accepts, sorted — a tag resolved through the server's registry, an item as itself. */
    private static String acceptedItems(Ingredient ingredient) {
        return Arrays.stream(ingredient.getItems()).map(ItemStack::getItem).map(BuiltInRegistries.ITEM::getKey)
                .map(String::valueOf).distinct().sorted().collect(Collectors.joining("|"));
    }

    private static String describe(Ingredient ingredient) {
        if (ingredient.isCustom())
            return "custom ingredient";
        return Arrays.stream(ingredient.getValues()).map(value -> {
            if (value instanceof Ingredient.TagValue tag)
                return "#" + tag.tag().location();
            if (value instanceof Ingredient.ItemValue item)
                return String.valueOf(BuiltInRegistries.ITEM.getKey(item.item().getItem()));
            return value.toString();
        }).sorted().collect(Collectors.joining("|"));
    }

    private static CraftingInput grid(int width, int height, ItemStack... items) {
        return CraftingInput.of(width, height, List.of(items));
    }

    /**
     * The same grid as {@link #grid}, but as the {@link MechanicalCraftingInput} a crafter tower would hand to the
     * recipe manager. Create builds one only from a {@code GroupedItems}, whose grid is filled through its public NBT
     * form; its y axis points up, so pattern row {@code r} (0 = top) sits at {@code y = -r}.
     */
    private static MechanicalCraftingInput crafterGrid(HolderLookup.Provider registries, int width, ItemStack... items) {
        ListTag grid = new ListTag();
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot].isEmpty())
                continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", slot % width);
            entry.putInt("y", -(slot / width));
            entry.put("item", items[slot].saveOptional(registries));
            grid.add(entry);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Grid", grid);
        RecipeGridHandler.GroupedItems grouped = RecipeGridHandler.GroupedItems.read(tag, registries);
        grouped.calcStats();
        return MechanicalCraftingInput.of(grouped);
    }

    private static ItemStack stack(String createPath) {
        return new ItemStack(BuiltInRegistries.ITEM.get(createId(createPath)));
    }

    /** A vanilla item stack, for the one ingredient that is not Create's ({@code minecraft:comparator}). */
    private static ItemStack vanilla(String path) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(path)));
    }

    private static ResourceLocation createId(String path) {
        return ResourceLocation.fromNamespaceAndPath("create", path);
    }

    private static TagKey<Item> itemTag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
