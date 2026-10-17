package dev.wareworks.content.item;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Immutable, count-less identity of an item: the item plus its data components.
 * <p>
 * Two keys are equal when {@link ItemStack#isSameItemSameComponents} says so — except for a component type with a
 * {@link ComponentMeanings meaning} (ADR-066, issue #29): Create's toolbox inventory, whose {@code equals} compares
 * its filter stacks by identity and never its contents, a sand paper's polished item, whose {@code equals} never
 * holds, and a symmetry wand's mirror, whose {@code equals} never compares the kind of mirror. A key whose own patch
 * holds such a value compares it by its meaning
 * ({@link ComponentMeanings#sameItemSameComponents}) and keeps a <b>private copy</b> of it: the value is detached when
 * the key is made, and every stack the key hands out gets a copy of its own, so nothing outside the key can change what
 * it compares. The hash is {@link ComponentHash#itemKey} (ADR-064, issue #27): the item and a contract-safe hash of
 * the component patch, which reads only what each value's {@code equals} is known to compare, so equal keys hash
 * equally whatever mod defined their components — also a key decoded from a request or a save, whose values are new
 * instances. It is <b>not</b> {@link ItemStack#hashItemAndComponents}: that one multiplies in every value's own
 * {@code hashCode}, and a value whose {@code hashCode} is not value-based (Quark's seed pouch contents hash an inner
 * stack by identity) gave two equal keys two hash codes, so no hash map found the second; an entry with a meaning is
 * hashed by its meaning. The wrapped stack (count 1) is never exposed, so the key cannot be mutated after construction
 * and is safe as a map key. Keys are never empty.
 * <p>
 * Persistence: {@link #save} and {@link #load} never throw. A key that cannot be encoded is saved as an empty tag, and
 * a tag that cannot be decoded (e.g. the item's mod was removed) loads as {@link Optional#empty()}.
 */
public final class ItemKey {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Codec over item id and components. The count is not part of the key. */
    public static final Codec<ItemKey> CODEC = ItemStack.SINGLE_ITEM_CODEC.xmap(ItemKey::new, key -> key.stack);

    /** Network codec (item, count 1, components). */
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemKey> STREAM_CODEC =
            ItemStack.STREAM_CODEC.map(ItemKey::new, key -> key.stack);

    /**
     * Total order of two keys that depends only on their values: the item id, then the key's text ({@link #toString()},
     * i.e. the item id plus the component patch). Used wherever a list of item types has to look the same after a
     * restart — the terminal's stock snapshot ({@code TerminalStockEntry.ORDER}) and the stock list display source.
     * <p>
     * <b>Not {@link #hashCode()}.</b> That hash ({@link ComponentHash#itemKey}) mixes in the JVM identity hash of the
     * item, of every component type and of every registry key it reads — the same within one run and different after
     * every restart ({@code Item} overrides neither {@code hashCode} nor {@code equals}). An order falling back to it
     * would swap two equally ranked keys of the same item (two named shulker boxes, two enchanted books) between
     * launches, which is exactly what such an order exists to prevent. The text is only rendered when two keys share
     * an item id, because {@link Comparator#thenComparing(java.util.function.Function)} evaluates its key extractor
     * only on a tie.
     */
    public static final Comparator<ItemKey> ORDER =
            Comparator.comparing((ItemKey key) -> ItemTypeSummaries.itemId(key.getItem()))
                    .thenComparing(ItemKey::toString);

    private final ItemStack stack;
    /** Whether the patch holds a value with a meaning (ADR-066): compared by meaning, handed out as copies. */
    private final boolean meaningful;
    private final int hash;

    private ItemKey(ItemStack source) {
        if (source.isEmpty())
            throw new IllegalArgumentException("An item key cannot be empty");
        this.stack = ComponentMeanings.detach(source.copyWithCount(1));
        this.meaningful = ComponentMeanings.present(this.stack);
        this.hash = ComponentHash.itemKey(this.stack);
    }

    /**
     * Creates the key of a non-empty stack. The stack is copied and not modified.
     *
     * @throws IllegalArgumentException if the stack is empty
     */
    public static ItemKey of(ItemStack stack) {
        return new ItemKey(Objects.requireNonNull(stack, "stack"));
    }

    /**
     * Creates the key of an item without component changes.
     *
     * @throws IllegalArgumentException if the item is air
     */
    public static ItemKey of(ItemLike item) {
        return new ItemKey(new ItemStack(Objects.requireNonNull(item, "item")));
    }

    /** The key of {@code stack}, or empty for an empty (or {@code null}) stack. */
    public static Optional<ItemKey> fromStack(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? Optional.empty() : Optional.of(new ItemKey(stack));
    }

    public Item getItem() {
        return stack.getItem();
    }

    /** Component-aware max stack size of this key (at least 1). */
    public int getMaxStackSize() {
        return stack.getMaxStackSize();
    }

    /**
     * A new stack of this key with count 1. A value with a meaning (ADR-066) is a copy of its own, shared with nothing
     * else.
     */
    public ItemStack toStack() {
        return meaningful ? ComponentMeanings.detach(stack.copy()) : stack.copy();
    }

    /**
     * A new stack of this key with the given count. Counts above the max stack size are allowed (callers must split
     * such stacks before handing them to code that expects valid stacks). A count of 0 or less yields
     * {@link ItemStack#EMPTY}. A value with a meaning (ADR-066) is a copy of its own, shared with nothing else.
     */
    public ItemStack toStack(int count) {
        if (count <= 0)
            return ItemStack.EMPTY;
        ItemStack copy = stack.copyWithCount(count);
        return meaningful ? ComponentMeanings.detach(copy) : copy;
    }

    /**
     * Whether {@code other} is a non-empty stack of this key (count ignored): as
     * {@link ItemStack#isSameItemSameComponents} compares, or for a key holding a value with a meaning as
     * {@link ComponentMeanings#sameItemSameComponents} does (ADR-066). What the crane checks on every slot it takes
     * from.
     */
    public boolean matches(@Nullable ItemStack other) {
        if (other == null || other.isEmpty())
            return false;
        return meaningful ? ComponentMeanings.sameItemSameComponents(stack, other)
                : ItemStack.isSameItemSameComponents(stack, other);
    }

    /**
     * Encodes this key to NBT. Never throws; on failure a warning is logged and an empty {@link CompoundTag} is
     * returned, which {@link #load} reads back as empty.
     */
    public Tag save(HolderLookup.Provider registries) {
        try {
            DataResult<Tag> result = CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), this);
            Optional<Tag> tag = result.result();
            if (tag.isPresent())
                return tag.get();
            result.error().ifPresent(error -> LOGGER.warn("Could not save item key {}: {}", this, error.message()));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not save item key {}", this, e);
        }
        return new CompoundTag();
    }

    /**
     * Decodes a key written by {@link #save}. Never throws; returns empty for {@code null}, empty or invalid tags
     * (invalid tags are logged as a warning).
     */
    public static Optional<ItemKey> load(HolderLookup.Provider registries, @Nullable Tag tag) {
        if (tag == null || tag instanceof CompoundTag compound && compound.isEmpty())
            return Optional.empty();
        try {
            DataResult<ItemKey> result = CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag);
            result.error().ifPresent(error -> LOGGER.warn("Could not load item key from {}: {}", tag, error.message()));
            return result.result();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not load item key from {}", tag, e);
            return Optional.empty();
        }
    }

    /** Writes this key under {@code name}; nothing is written if encoding fails. Never throws. */
    public void saveTo(CompoundTag parent, String name, HolderLookup.Provider registries) {
        Tag tag = save(registries);
        if (!(tag instanceof CompoundTag compound && compound.isEmpty()))
            parent.put(name, tag);
    }

    /** Reads a key written by {@link #saveTo}. Never throws. */
    public static Optional<ItemKey> loadFrom(CompoundTag parent, String name, HolderLookup.Provider registries) {
        return parent.contains(name) ? load(registries, parent.get(name)) : Optional.empty();
    }

    /**
     * Item and components, as {@link ItemStack#isSameItemSameComponents} compares them, a value with a meaning by its
     * meaning ({@link ComponentMeanings#sameItemSameComponents}, ADR-066). The hash is compared first: it is
     * contract-safe ({@link ComponentHash}), so unequal hashes prove unequal keys for every mod.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ItemKey other) || hash != other.hash)
            return false;
        return meaningful ? ComponentMeanings.sameItemSameComponents(stack, other.stack)
                : ItemStack.isSameItemSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * The item id, plus the component patch if there is one ({@link ComponentMeanings#patchText}, ADR-066): its entries
     * sorted by the component type's registered name, whatever order the components were added in (a toolbox's loot
     * drop sets its uuid first, the same toolbox loaded from a save its inventory), and a value with a meaning written
     * as its meaning (a toolbox inventory's own {@code toString} prints a hash built from identity hashes). Built from
     * the values alone and therefore the same after a restart, which is why {@link #ORDER} uses it rather than the
     * key's hash.
     */
    @Override
    public String toString() {
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return stack.isComponentsPatchEmpty() ? id : id + ComponentMeanings.patchText(stack.getComponentsPatch());
    }
}
