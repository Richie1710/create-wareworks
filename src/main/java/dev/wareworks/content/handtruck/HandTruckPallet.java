package dev.wareworks.content.handtruck;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.handtruck.HandTruckLimits;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A pallet carried on a hand truck (ADR-084): the value of the item component {@code wareworks:hand_truck_pallet},
 * one item type and a count, exactly the shape of a {@code PalletEntity}'s load.
 * <p>
 * <b>A component of its own, not a second meaning of {@code hand_truck_load}.</b> That one is a container's block
 * entity data; a pallet has no block and no block entity. Keeping them apart leaves every chest path byte for byte as
 * it was, and an old truck loads unchanged.
 * <p>
 * The count is this record's own {@code int}, never an {@code ItemStack} count: {@code ItemStack.CODEC} bounds a count
 * at 99, and a pallet carries up to {@link HandTruckLimits#MAX_PALLET_COUNT}. The item is a count-less
 * {@link ItemKey} ({@code ItemStack.SINGLE_ITEM_CODEC}), components included.
 * <p>
 * <b>The decode never fails on the count</b>: a failed decode would make {@code ItemStack.parse} drop the truck out of
 * a player's inventory with the whole load on it. The constructor clamps the count into
 * {@code 1..}{@link HandTruckLimits#MAX_PALLET_COUNT} ({@link HandTruckLimits#palletCount}) and logs a clamp, which
 * from above is the item loss it is; only a creative client or an edited save can write such a count.
 * <p>
 * Synced to clients in full, for the reason {@link HandTruckLoad} is: a creative client sends whole stacks back.
 */
public record HandTruckPallet(ItemKey item, int count) {
    public static final Codec<HandTruckPallet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemKey.CODEC.fieldOf("item").forGetter(HandTruckPallet::item),
            Codec.INT.fieldOf("count").forGetter(HandTruckPallet::count)).apply(instance, HandTruckPallet::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, HandTruckPallet> STREAM_CODEC = StreamCodec.composite(
            ItemKey.STREAM_CODEC, HandTruckPallet::item,
            ByteBufCodecs.VAR_INT, HandTruckPallet::count,
            HandTruckPallet::new);

    /** Bytes a count adds to the encoded key: an NBT int. */
    private static final int COUNT_BYTES = Integer.BYTES;

    public HandTruckPallet {
        Objects.requireNonNull(item, "item");
        int clamped = HandTruckLimits.palletCount(count);
        if (clamped != count)
            Wareworks.LOGGER.warn("A hand truck's pallet said it carries {} x {}; it is read as {}{}", count, item,
                    clamped, count > clamped ? ", and " + ((long) count - clamped) + " items are lost" : "");
        count = clamped;
    }

    /** The name a player reads for the carried load: the item's own name, a custom name included. */
    public Component displayName() {
        return item.toStack().getHoverName();
    }

    /**
     * About how much this load adds to a player's save and to every inventory packet: the encoded key plus the count.
     * Only a key with large components (a renamed or written item) comes anywhere near the size budget.
     */
    public int sizeInBytes(HolderLookup.Provider registries) {
        return item.save(registries).sizeInBytes() + COUNT_BYTES;
    }
}
