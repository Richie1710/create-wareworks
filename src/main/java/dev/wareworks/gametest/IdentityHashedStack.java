package dev.wareworks.gametest;

import java.util.Objects;

import com.mojang.serialization.Codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * A data component value shaped exactly like Quark 4.1's seed pouch contents ({@code quark:stored_item}, an
 * {@code ItemWrapperComponent}), so the GameTests reproduce issue #27 without Quark (ADR-064): a record around an
 * {@link ItemStack} whose {@code equals} compares by value ({@link ItemStack#matches}) and whose {@code hashCode} is
 * {@code Objects.hashCode(stack)} — the <b>identity</b> hash, because {@code ItemStack} overrides neither method. Two
 * equal values built apart, or one decoded from a save or a packet, therefore break the {@code hashCode} contract.
 * <p>
 * Its codecs go through {@link ItemStack#CODEC} and {@link ItemStack#STREAM_CODEC}, so it survives an NBT and a network
 * round trip as a new instance. Registered as {@code wareworks:identity_hashed_stack} by {@link GameTestComponents},
 * in dev runs only.
 *
 * @param stack the wrapped stack, never empty
 */
public record IdentityHashedStack(ItemStack stack) {
    public static final Codec<IdentityHashedStack> CODEC =
            ItemStack.CODEC.xmap(IdentityHashedStack::new, IdentityHashedStack::stack);
    public static final StreamCodec<RegistryFriendlyByteBuf, IdentityHashedStack> STREAM_CODEC =
            ItemStack.STREAM_CODEC.map(IdentityHashedStack::new, IdentityHashedStack::stack);

    @Override
    public boolean equals(Object other) {
        return other instanceof IdentityHashedStack wrapped && ItemStack.matches(stack, wrapped.stack);
    }

    /** Quark 4.1's own: the identity hash of the inner stack, which breaks the contract with {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hashCode(stack);
    }
}
