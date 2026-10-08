package dev.wareworks.content.fluid;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Immutable, amount-less identity of a fluid: the fluid plus its data components. The {@link ItemKey} of fluids
 * (M30, issue #21).
 * <p>
 * Two keys are equal when {@link FluidStack#isSameFluidSameComponents} says so; the hash is
 * {@link FluidStack#hashFluidAndComponents}. The wrapped stack (amount 1) is never exposed, so the key cannot be
 * mutated after construction and is safe as a map key. Keys are never empty.
 * <p>
 * <b>Why a key at all, rather than a {@link FluidStack}.</b> A {@code FluidStack} overrides neither {@code equals} nor
 * {@code hashCode} — comparison is the static {@link FluidStack#isSameFluidSameComponents} — it is <b>mutable</b>, and
 * handlers hand out their internal instance: {@code IFluidHandler#getFluidInTank} says "SERIOUSLY: DO NOT MODIFY THE
 * RETURNED FLUIDSTACK" and {@code FluidTank.getFluidInTank} returns the live field. A stack used as a map key would
 * therefore compare by identity and could change its own identity behind the map's back. That is exactly the mistake
 * {@link ItemKey} exists to prevent, and a fluid census is the first place it would bite.
 * <p>
 * Persistence: {@link #save} and {@link #load} never throw, and in particular never go through
 * {@link FluidStack#save(HolderLookup.Provider)}, which <b>throws</b> {@code IllegalStateException} on an empty stack.
 * A key that cannot be encoded is saved as an empty {@link CompoundTag}, and a tag that cannot be decoded (e.g. the
 * fluid's mod was removed) loads as {@link Optional#empty()} — the rule {@link ItemKey} already follows.
 */
public final class FluidKey {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Codec over fluid id and components. The amount is not part of the key. */
    public static final Codec<FluidKey> CODEC =
            FluidStack.fixedAmountCodec(1).xmap(FluidKey::new, key -> key.stack);

    /** Network codec (fluid, amount 1, components). */
    public static final StreamCodec<RegistryFriendlyByteBuf, FluidKey> STREAM_CODEC =
            FluidStack.STREAM_CODEC.map(FluidKey::new, key -> key.stack);

    /**
     * Total order of two keys that depends only on their values: the fluid id, then the key's text
     * ({@link #toString()}, i.e. the fluid id plus the component patch). Used wherever a list of fluid types has to
     * look the same after a restart.
     * <p>
     * <b>Not {@link #hashCode()}.</b> That hash mixes in {@code Fluid#hashCode()}, and {@code Fluid} overrides neither
     * {@code hashCode} nor {@code equals}, so the term is the JVM identity hash — the same within one run and different
     * after every restart. An order falling back to it would swap two equally ranked keys of the same fluid (two
     * potions) between launches, which is exactly what such an order exists to prevent. The text is only rendered when
     * two keys share a fluid id, because {@link Comparator#thenComparing(java.util.function.Function)} evaluates its
     * key extractor only on a tie. This is {@link ItemKey#ORDER}'s argument, verbatim, for the same reason.
     */
    public static final Comparator<FluidKey> ORDER =
            Comparator.comparing((FluidKey key) -> FluidTypeSummaries.fluidId(key.getFluid()))
                    .thenComparing(FluidKey::toString);

    private final FluidStack stack;
    private final int hash;

    private FluidKey(FluidStack source) {
        if (source.isEmpty())
            throw new IllegalArgumentException("A fluid key cannot be empty");
        // copyWithAmount copies the component map, so a later mutation of the source cannot change this key.
        this.stack = source.copyWithAmount(1);
        this.hash = FluidStack.hashFluidAndComponents(this.stack);
    }

    /**
     * Creates the key of a non-empty stack. The stack is copied and not modified.
     *
     * @throws IllegalArgumentException if the stack is empty
     */
    public static FluidKey of(FluidStack stack) {
        return new FluidKey(Objects.requireNonNull(stack, "stack"));
    }

    /**
     * Creates the key of a fluid without component changes.
     *
     * @throws IllegalArgumentException if the fluid is {@code minecraft:empty}
     */
    public static FluidKey of(Fluid fluid) {
        return new FluidKey(new FluidStack(Objects.requireNonNull(fluid, "fluid"), 1));
    }

    /** The key of {@code stack}, or empty for an empty (or {@code null}) stack. */
    public static Optional<FluidKey> fromStack(@Nullable FluidStack stack) {
        return stack == null || stack.isEmpty() ? Optional.empty() : Optional.of(new FluidKey(stack));
    }

    public Fluid getFluid() {
        return stack.getFluid();
    }

    /** A new stack of this key holding 1 mB. */
    public FluidStack toStack() {
        return stack.copy();
    }

    /** A new stack of this key holding {@code millibuckets}; {@link FluidStack#EMPTY} for 0 or less. */
    public FluidStack toStack(int millibuckets) {
        return millibuckets <= 0 ? FluidStack.EMPTY : stack.copyWithAmount(millibuckets);
    }

    /** Whether {@code other} is a non-empty stack of this key (amount ignored). */
    public boolean matches(@Nullable FluidStack other) {
        return other != null && !other.isEmpty() && FluidStack.isSameFluidSameComponents(stack, other);
    }

    /**
     * The fluid's own display name ("Lava", "Water"), for goggle lines and screens: the fluid type's description, so it
     * is translated where it is rendered and safe to build on either side.
     */
    public Component hoverName() {
        return stack.getHoverName();
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
            result.error().ifPresent(error -> LOGGER.warn("Could not save fluid key {}: {}", this, error.message()));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not save fluid key {}", this, e);
        }
        return new CompoundTag();
    }

    /**
     * Decodes a key written by {@link #save}. Never throws; returns empty for {@code null}, empty or invalid tags
     * (invalid tags are logged as a warning).
     */
    public static Optional<FluidKey> load(HolderLookup.Provider registries, @Nullable Tag tag) {
        if (tag == null || tag instanceof CompoundTag compound && compound.isEmpty())
            return Optional.empty();
        try {
            DataResult<FluidKey> result = CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag);
            result.error().ifPresent(error -> LOGGER.warn("Could not load fluid key from {}: {}", tag, error.message()));
            return result.result();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not load fluid key from {}", tag, e);
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
    public static Optional<FluidKey> loadFrom(CompoundTag parent, String name, HolderLookup.Provider registries) {
        return parent.contains(name) ? load(registries, parent.get(name)) : Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof FluidKey other && hash == other.hash
                && FluidStack.isSameFluidSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * The fluid id, plus the component patch if there is one. Value-based and therefore the same after a restart, which
     * is why {@link #ORDER} uses it rather than the key's hash.
     */
    @Override
    public String toString() {
        String id = FluidTypeSummaries.fluidId(stack.getFluid());
        return stack.isComponentsPatchEmpty() ? id : id + stack.getComponentsPatch();
    }
}
