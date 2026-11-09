package dev.wareworks.content.station;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.filter.AttributeFilterItem;
import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.logistics.filter.ListFilterItem;
import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
import com.simibubi.create.content.logistics.item.filter.attribute.attributes.InTagAttribute;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

/**
 * Filter cells of production patterns (ADR-082): which keys are Create filters a pattern cell may hold as a choice,
 * the ghost copy a cell stores, and what a filter's own data says about the items it allows.
 * <p>
 * <b>The server decides from the item</b> (D2). A cell is a filter cell when its key is a Create List Filter with at least
 * one listed item, or an Attribute Filter with at least one attribute. A blank filter would only allow "the same item as
 * the filter" ({@code FilterItem#testDirect}), a Package Filter matches package addresses, and the result entry is never a
 * choice, so all of those stay exact. The client never sends a flag.
 * <p>
 * <b>Never consumed.</b> A filter cell is a ghost like every other cell: only the key is stored, and matching goes through
 * Create's own {@link FilterItemStack#test}, built from a fresh copy every time ({@link #resolve}), because
 * {@link FilterItemStack#of} trims the stack it is given in place.
 * <p>
 * Server- and client-safe: only Create's filter types and vanilla registries, no level state except where a
 * {@link Level} is passed for Create's test.
 */
public final class PatternFilterCells {
    /** Largest encoded filter a pattern cell may hold (D16): patterns are saved and synced, nested filters can grow. */
    public static final int MAX_FILTER_BYTES = 4096;
    /** At most this many example items a filter names ({@link #examples}). */
    public static final int MAX_EXAMPLES = 16;
    /**
     * Create tests one computation of {@link #examples} runs at most: a filter is bounded in bytes, not in what one of
     * its tests costs, so the walk over its tags is bounded in tests.
     */
    public static final int MAX_EXAMPLE_TESTS = 64;
    /** Filters whose examples are remembered at most. */
    static final int MAX_REMEMBERED_EXAMPLES = 512;

    /** Remembered examples per filter, for one recipe manager ({@link #examplesRecipes}); guarded by itself. */
    private static final Map<ItemKey, List<ItemKey>> EXAMPLES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ItemKey, List<ItemKey>> eldest) {
            return size() > MAX_REMEMBERED_EXAMPLES;
        }
    };
    private static WeakReference<Object> examplesRecipes = new WeakReference<>(null);
    /** Create tests the computation of examples has run; for the GameTest that bounds it. */
    private static final AtomicLong EXAMPLE_TESTS = new AtomicLong();

    private PatternFilterCells() {
    }

    /**
     * Whether {@code key} is a configured List or Attribute Filter (D2): a List Filter with a non-empty
     * {@code create:filter_items}, or an Attribute Filter with a non-empty
     * {@code create:attribute_filter_matched_attributes}. A blank filter and a Package Filter are not.
     */
    public static boolean isFilter(ItemKey key) {
        if (key == null)
            return false;
        Item item = key.getItem();
        if (item instanceof ListFilterItem) {
            ItemStack stack = key.toStack(1);
            ItemContainerContents contents = stack.getOrDefault(AllDataComponents.FILTER_ITEMS,
                    ItemContainerContents.EMPTY);
            return contents.nonEmptyItems().iterator().hasNext();
        }
        if (item instanceof AttributeFilterItem) {
            List<ItemAttribute.ItemAttributeEntry> attributes = key.toStack(1)
                    .get(AllDataComponents.ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES);
            return attributes != null && !attributes.isEmpty();
        }
        return false;
    }

    /**
     * The ghost a filter cell stores: count 1, with enchantments and attribute modifiers back at the item's defaults —
     * what {@link FilterItemStack#of} ignores when it tests, so two filters that test the same way are one key and merge.
     * The defaults are restored rather than removed: {@code ItemStack#remove} would record a removal in the patch for
     * a component every item has, and the ghost would then differ from the very filter a player configured.
     */
    public static ItemKey ghostOf(ItemKey key) {
        Objects.requireNonNull(key, "key");
        ItemStack stack = key.toStack(1);
        boolean changed = false;
        if (stack.getComponentsPatch().get(DataComponents.ENCHANTMENTS) != null) {
            stack.set(DataComponents.ENCHANTMENTS, stack.getPrototype().get(DataComponents.ENCHANTMENTS));
            changed = true;
        }
        if (stack.getComponentsPatch().get(DataComponents.ATTRIBUTE_MODIFIERS) != null) {
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, stack.getPrototype().get(DataComponents.ATTRIBUTE_MODIFIERS));
            changed = true;
        }
        return changed ? ItemKey.of(stack) : key;
    }

    /** Whether {@code key} encodes into at most {@value #MAX_FILTER_BYTES} bytes (D16). Never throws. */
    public static boolean fits(ItemKey key, RegistryAccess registries) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(registries, "registries");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ItemKey.STREAM_CODEC.encode(buffer, key);
            return buffer.readableBytes() <= MAX_FILTER_BYTES;
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Could not measure a filter for a production pattern", e);
            return false;
        } finally {
            buffer.release();
        }
    }

    /**
     * An Attribute Filter that allows any item in {@code tag}: allow-list "any of", one entry "is tagged", not inverted
     * (§6.3). The mode is written explicitly, as Create's own {@code AttributeFilterMenu#saveData} does, so a filter
     * laid by a recipe viewer is the very key a player configures by hand with the same attribute.
     */
    public static ItemKey inTag(TagKey<Item> tag) {
        Objects.requireNonNull(tag, "tag");
        ItemStack filter = AllItems.ATTRIBUTE_FILTER.asStack();
        filter.set(AllDataComponents.ATTRIBUTE_FILTER_WHITELIST_MODE, AttributeFilterWhitelistMode.WHITELIST_DISJ);
        filter.set(AllDataComponents.ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES,
                List.of(new ItemAttribute.ItemAttributeEntry(new InTagAttribute(tag), false)));
        return ItemKey.of(filter);
    }

    /**
     * Whether {@code filter} is a deny filter (D15): a List Filter in deny mode or an Attribute Filter in
     * {@code BLACKLIST} mode — the rule {@code AisleFilters#selects} reads, since such a filter allows everything it does
     * not name.
     */
    public static boolean denies(ItemKey filter) {
        Objects.requireNonNull(filter, "filter");
        ItemStack stack = filter.toStack(1);
        if (stack.getItem() instanceof ListFilterItem)
            return stack.getOrDefault(AllDataComponents.FILTER_ITEMS_BLACKLIST, false);
        if (stack.getItem() instanceof AttributeFilterItem)
            return stack.getOrDefault(AllDataComponents.ATTRIBUTE_FILTER_WHITELIST_MODE,
                    AttributeFilterWhitelistMode.WHITELIST_DISJ) == AttributeFilterWhitelistMode.BLACKLIST;
        return false;
    }

    /**
     * Items {@code filter} names by itself, at most {@value #MAX_EXAMPLES}, in a stable order (§3.3): what a crafting
     * grid is checked with when a filter group needs a concrete item (result following, the Crafter Feeder's check).
     * <ul>
     * <li>a List Filter in allow mode: its listed items in slot order, nested filters skipped;</li>
     * <li>an Attribute Filter in either allow mode: the members of every non-inverted "is tagged" entry, kept only when the
     * whole filter allows them, so a conjunction and inverted entries are honoured;</li>
     * <li>a deny filter, or one without an "is tagged" entry: none.</li>
     * </ul>
     * <b>Cost.</b> Remembered per filter until the level's recipe manager changes identity (a {@code /reload} replaces
     * recipes and tags together), so a screen push or a pattern edit asks Create nothing for a filter it has seen. The
     * computation itself walks each distinct tag once and runs at most {@value #MAX_EXAMPLE_TESTS} Create tests, stopping
     * with what it found.
     */
    public static List<ItemKey> examples(ItemKey filter, Level level) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(level, "level");
        Object recipes = CrafterRecipeCheck.recipeSet(level);
        synchronized (EXAMPLES) {
            if (examplesRecipes.get() != recipes) {
                EXAMPLES.clear();
                examplesRecipes = new WeakReference<>(recipes);
            }
            List<ItemKey> known = EXAMPLES.get(filter);
            if (known != null)
                return known;
        }
        List<ItemKey> computed = computeExamples(filter, level);
        synchronized (EXAMPLES) {
            if (examplesRecipes.get() == recipes)
                EXAMPLES.put(filter, computed);
        }
        return computed;
    }

    /** Create tests the computation of {@link #examples} has run so far; for the GameTest that bounds it. */
    public static long exampleTests() {
        return EXAMPLE_TESTS.get();
    }

    private static List<ItemKey> computeExamples(ItemKey filter, Level level) {
        if (denies(filter))
            return List.of();
        ItemStack stack = filter.toStack(1);
        Set<ItemKey> found = new LinkedHashSet<>();
        if (stack.getItem() instanceof ListFilterItem list) {
            var handler = list.getFilterItemHandler(stack);
            for (int slot = 0; slot < handler.getSlots() && found.size() < MAX_EXAMPLES; slot++) {
                ItemStack listed = handler.getStackInSlot(slot);
                if (!listed.isEmpty() && !(listed.getItem() instanceof FilterItem))
                    found.add(ItemKey.of(listed));
            }
            return List.copyOf(found);
        }
        if (!(stack.getItem() instanceof AttributeFilterItem))
            return List.of();
        List<ItemAttribute.ItemAttributeEntry> attributes = stack.get(AllDataComponents.ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES);
        if (attributes == null)
            return List.of();
        FilterItemStack resolved = resolve(filter);
        Set<TagKey<Item>> walked = new HashSet<>();
        int tests = 0;
        for (ItemAttribute.ItemAttributeEntry entry : attributes) {
            if (entry.inverted() || !(entry.attribute() instanceof InTagAttribute(TagKey<Item> tag)) || !walked.add(tag))
                continue;
            for (Holder<Item> member : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                if (found.size() >= MAX_EXAMPLES || tests >= MAX_EXAMPLE_TESTS)
                    break;
                ItemKey candidate = ItemKey.of(member.value());
                if (found.contains(candidate))
                    continue;
                tests++;
                EXAMPLE_TESTS.incrementAndGet();
                if (test(resolved, level, candidate))
                    found.add(candidate);
            }
        }
        return List.copyOf(new ArrayList<>(found));
    }

    /**
     * Create's matcher for {@code filter}, built from a fresh copy: {@link FilterItemStack#of} trims the stack it is
     * given in place, and the key's own stack must never change.
     */
    public static FilterItemStack resolve(ItemKey filter) {
        return FilterItemStack.of(Objects.requireNonNull(filter, "filter").toStack(1));
    }

    /** Whether {@code filter} allows {@code key}; a filter that throws allows nothing. */
    public static boolean test(FilterItemStack filter, Level level, ItemKey key) {
        try {
            return filter.test(level, key.toStack(1));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
