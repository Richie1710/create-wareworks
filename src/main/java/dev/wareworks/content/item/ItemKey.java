package dev.wareworks.content.item;

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
 * Two keys are equal when {@link ItemStack#isSameItemSameComponents} says so; the hash is
 * {@link ItemStack#hashItemAndComponents}. The wrapped stack (count 1) is never exposed, so the key cannot be mutated
 * after construction and is safe as a map key. Keys are never empty.
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

    private final ItemStack stack;
    private final int hash;

    private ItemKey(ItemStack source) {
        if (source.isEmpty())
            throw new IllegalArgumentException("An item key cannot be empty");
        this.stack = source.copyWithCount(1);
        this.hash = ItemStack.hashItemAndComponents(this.stack);
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

    /** A new stack of this key with count 1. */
    public ItemStack toStack() {
        return stack.copy();
    }

    /**
     * A new stack of this key with the given count. Counts above the max stack size are allowed (callers must split
     * such stacks before handing them to code that expects valid stacks). A count of 0 or less yields
     * {@link ItemStack#EMPTY}.
     */
    public ItemStack toStack(int count) {
        return count <= 0 ? ItemStack.EMPTY : stack.copyWithCount(count);
    }

    /** Whether {@code other} is a non-empty stack of this key (count ignored). */
    public boolean matches(@Nullable ItemStack other) {
        return other != null && !other.isEmpty() && ItemStack.isSameItemSameComponents(stack, other);
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

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ItemKey other && hash == other.hash
                && ItemStack.isSameItemSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return stack.isComponentsPatchEmpty() ? id : id + stack.getComponentsPatch();
    }
}
