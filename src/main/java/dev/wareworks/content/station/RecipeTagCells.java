package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.mojang.datafixers.util.Pair;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Which cells of a recipe a recipe viewer lays into a production pattern stand for an <b>item tag</b>, and what such a
 * cell becomes on the server (ADR-082, owner decision 3): an Attribute Filter "is tagged" ({@link PatternFilterCells#inTag})
 * instead of the one item the server picked. A cell whose ingredient is a list of items, a custom ingredient, or
 * anything else stays the exact item it was before.
 * <p>
 * <b>A client cannot see a recipe's tags.</b> A recipe reaches the client through the recipe sync, which writes every
 * ingredient as the plain list of its items ({@code Ingredient#CONTENTS_STREAM_CODEC}), so a chest's
 * {@code #minecraft:planks} arrives as eleven planks and NeoForge's coal-or-charcoal as two items, and the two look
 * alike. The client therefore only <b>proposes</b> a tag per cell ({@link #tagFor}: the tag whose members are exactly
 * the ingredient's items), and sends the recipe's id with it; the server, which holds the real recipe, writes a filter
 * only for a tag that recipe really names as an ingredient ({@link #recipeTags}) and that passes {@link #filterFor}.
 * <p>
 * Free of every recipe viewer's own types, so the rules are the same for any viewer and a GameTest can pin them; the
 * client half reads the recipe's {@link Ingredient}s ({@code ProductionRecipeTransferHandler}), the server half
 * ({@link #filterFor}, {@link #recipeTags}) trusts nothing the client named but the ids.
 */
public final class RecipeTagCells {
    /** Longest tag id a lay carries per cell; a real tag id is far shorter. */
    public static final int MAX_TAG_LENGTH = 256;

    private RecipeTagCells() {
    }

    /**
     * The tag {@code ingredient} stands for: exactly one tag value and nothing else, and not a custom ingredient (whose
     * values cannot be read). An ingredient of several items, such as NeoForge's coal-or-charcoal, names none.
     */
    public static Optional<TagKey<Item>> tagOf(Ingredient ingredient) {
        if (ingredient == null || ingredient.isCustom() || ingredient.isEmpty())
            return Optional.empty();
        Ingredient.Value[] values = ingredient.getValues();
        if (values.length == 1 && values[0] instanceof Ingredient.TagValue(TagKey<Item> tag))
            return Optional.of(tag);
        return Optional.empty();
    }

    /**
     * Client: the tag a viewer's cell proposes for {@code ingredient} — {@link #tagOf} where the ingredient still names
     * its tag, otherwise the item tag whose members are exactly the ingredient's items, which is what a synced recipe's
     * tag ingredient has become (see the class description). Of several such tags the {@code minecraft} one comes
     * first, then the lowest id. A list of items that happens to equal a tag is proposed too: the server tells the two
     * apart with the real recipe ({@link #recipeTags}).
     * <p>
     * It reads every item tag once, which is done for a click on "+" only.
     */
    public static Optional<TagKey<Item>> tagFor(Ingredient ingredient) {
        Optional<TagKey<Item>> named = tagOf(ingredient);
        if (named.isPresent() || ingredient == null || ingredient.isCustom() || ingredient.isEmpty())
            return named;
        Set<Item> items = new HashSet<>();
        for (ItemStack stack : ingredient.getItems())
            items.add(stack.getItem());
        return BuiltInRegistries.ITEM.getTags()
                .filter(pair -> pair.getSecond().size() == items.size()
                        && pair.getSecond().stream().allMatch(holder -> items.contains(holder.value())))
                .map(Pair::getFirst)
                .min(Comparator.comparing((TagKey<Item> tag) -> !tag.location().getNamespace().equals("minecraft"))
                        .thenComparing(tag -> tag.location().toString()));
    }

    /**
     * Server: the tags {@code ingredients}, the server's own copy of a recipe's ingredients, name as plain tag
     * ingredients ({@link #tagOf}). Only such a tag may become a filter cell: a list of items that equals a tag stays
     * exact (owner decision 3).
     */
    public static Set<TagKey<Item>> recipeTags(List<Ingredient> ingredients) {
        Objects.requireNonNull(ingredients, "ingredients");
        Set<TagKey<Item>> tags = new LinkedHashSet<>();
        for (Ingredient ingredient : ingredients)
            tagOf(ingredient).ifPresent(tags::add);
        return tags;
    }

    /**
     * The tag of a recipe slot that is no crafting grid cell (a saw's or a press's input): the one tag among
     * {@code ingredients} that is proposed for its ingredient ({@link #tagFor}) and which every item the viewer
     * {@code shown} for the slot carries. Two such tags, or none, give none.
     */
    public static Optional<TagKey<Item>> slotTag(List<Ingredient> ingredients, List<ItemStack> shown) {
        Objects.requireNonNull(ingredients, "ingredients");
        Objects.requireNonNull(shown, "shown");
        List<ItemStack> items = shown.stream().filter(stack -> stack != null && !stack.isEmpty()).toList();
        if (items.isEmpty())
            return Optional.empty();
        Set<TagKey<Item>> fitting = new LinkedHashSet<>();
        for (Ingredient ingredient : ingredients) {
            Optional<TagKey<Item>> tag = tagFor(ingredient);
            if (tag.isPresent() && items.stream().allMatch(stack -> stack.is(tag.get())))
                fitting.add(tag.get());
        }
        return fitting.size() == 1 ? Optional.of(fitting.iterator().next()) : Optional.empty();
    }

    /**
     * The tag of every laid cell of a recipe that is no crafting grid ({@code PatternLay}): a cell takes the tag of the
     * input whose candidates are the cell's candidates. {@code PatternLay} merges only inputs whose candidate lists are
     * equal, and drops the result from each, which is done here the same way. Two inputs with the same candidates and
     * different tags give their cells none.
     *
     * @param inputCandidates per non-empty input of the recipe, its candidates as laid
     * @param inputTags       per input, its tag ({@link #slotTag})
     * @param result          what the pattern makes
     * @param cells           the laid cells, each with its candidates; an empty list is an empty cell
     * @return one tag per cell, parallel to {@code cells}
     */
    public static List<Optional<ResourceLocation>> cellTags(List<List<ItemKey>> inputCandidates,
            List<Optional<TagKey<Item>>> inputTags, ItemKey result, List<List<ItemKey>> cells) {
        Objects.requireNonNull(inputCandidates, "inputCandidates");
        Objects.requireNonNull(inputTags, "inputTags");
        Objects.requireNonNull(cells, "cells");
        Map<List<ItemKey>, Optional<TagKey<Item>>> byCandidates = new HashMap<>();
        for (int input = 0; input < inputCandidates.size() && input < inputTags.size(); input++) {
            List<ItemKey> candidates = inputCandidates.get(input).stream()
                    .filter(candidate -> !candidate.equals(result)).toList();
            Optional<TagKey<Item>> tag = inputTags.get(input);
            byCandidates.merge(candidates, tag, (before, now) -> before.equals(now) ? before : Optional.empty());
        }
        List<Optional<ResourceLocation>> tags = new ArrayList<>(cells.size());
        for (List<ItemKey> cell : cells)
            tags.add(cell.isEmpty() ? Optional.empty()
                    : byCandidates.getOrDefault(cell, Optional.empty()).map(TagKey::location));
        return tags;
    }

    /** The tags of the cells of a crafting grid, parallel to the grid: each cell's own ingredient's ({@link #tagFor}). */
    public static List<Optional<ResourceLocation>> gridTags(Map<Integer, Ingredient> byCell, int cells) {
        Objects.requireNonNull(byCell, "byCell");
        List<Optional<ResourceLocation>> tags = new ArrayList<>(cells);
        for (int cell = 0; cell < cells; cell++)
            tags.add(tagFor(byCell.get(cell)).map(TagKey::location));
        return tags;
    }

    /**
     * Server: the filter cell a laid tag becomes, or empty when the cell stays the exact item the server picked. It
     * becomes the "is tagged" Attribute Filter only when the tag exists and is not empty, the picked item carries it,
     * and the pattern's result does not (D12: a tag that holds the result would make the pattern take its own
     * ingredient, so that one cell stays exact instead of the whole lay being refused).
     */
    public static Optional<ItemKey> filterFor(ResourceLocation id, ItemKey picked, ItemKey result) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(picked, "picked");
        Objects.requireNonNull(result, "result");
        TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
        Optional<HolderSet.Named<Item>> members = BuiltInRegistries.ITEM.getTag(tag);
        if (members.isEmpty() || members.get().size() == 0)
            return Optional.empty();
        if (!picked.toStack(1).is(tag) || result.toStack(1).is(tag))
            return Optional.empty();
        return Optional.of(PatternFilterCells.inTag(tag));
    }
}
