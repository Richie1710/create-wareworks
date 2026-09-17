package dev.wareworks.content.crane.head;

import java.util.function.Predicate;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * The exchangeable tool at the tip of a stacker crane's arm ({@code docs/stacker-crane.md} §6). It is the only thing that
 * ever moves items between locations: {@link #pick} really extracts from the source into the head, {@link #drop} really
 * inserts from the head into the target, and whatever does not fit stays held. The MVP implementation is the
 * {@link InventoryGrabber}; pallet, package and fluid handlers can implement this interface later.
 * <p>
 * <b>Deviation from the design signature</b> ({@code drop(TransferContext)}): {@link #drop} takes the key and the amount,
 * because the crane reports the result per job key to its state machine and must never deliver more than the job holds.
 * <p>
 * Server thread only. Implementations never throw from {@link #save} or {@link #load}.
 */
public interface HandlingHead {
    /** Maximum items of {@code key} one trip carries. */
    int carryLimit(ItemKey key);

    /**
     * Extracts up to {@code amount} items of exactly {@code key} from {@code source} into the head (real operation).
     *
     * @return the amount now additionally held, {@code 0..amount}
     */
    int pick(TransferContext source, ItemKey key, int amount);

    /**
     * Inserts up to {@code amount} held items of {@code key} into {@code target} (real operation); leftovers stay held.
     *
     * @return the delivered amount, {@code 0..min(amount, held)}
     */
    int drop(TransferContext target, ItemKey key, int amount);

    /** What the head holds right now. */
    HeldItems held();

    /** Held amount of {@code key}. */
    default int count(ItemKey key) {
        return held().count(key);
    }

    default boolean isEmpty() {
        return held().isEmpty();
    }

    /**
     * Drops the held items whose key matches {@code which} into the world at {@code pos} (block broken, unaccounted items
     * after loading) and removes them from the head. Ignores the {@code doTileDrops} game rule, so nothing is voided.
     *
     * @return the number of dropped items
     */
    long spill(Level level, BlockPos pos, Predicate<ItemKey> which);

    /** Empties the head without dropping anything ({@code Clearable}: commands and structures replace the block). */
    void clear();

    /** NBT form of the held items; never throws. */
    CompoundTag save(HolderLookup.Provider registries);

    /** Replaces the held items with a save written by {@link #save}; never throws, bounded against crafted data. */
    void load(CompoundTag tag, HolderLookup.Provider registries);
}
