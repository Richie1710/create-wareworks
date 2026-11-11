package dev.wareworks.content.storage;

/**
 * A bay block entity that can stay <b>the same object</b> while its block becomes another tier of its family — the
 * whole mechanism of an upgrade kit (ADR-083, {@link BayUpgrades#swapInPlace}).
 * <p>
 * The flag is set only for the duration of the one {@code setBlock} an upgrade makes. While it is set, the bay's
 * {@code onRemove} does not run {@code IBE.onRemove}, so the block entity is neither destroyed (no pallet, no lost
 * fluid, no dropped filter item) nor removed, and {@code LevelChunk#setBlockState} then hands the <b>same</b> block
 * entity the new state, because its type is valid for every tier of its family. Every other route that changes a bay's
 * block — breaking it, {@code /setblock}, a schematic print — finds the flag clear and behaves exactly as before.
 * <p>
 * The flag is a plain transient field of one block entity: never saved, never static and never a thread-local, so it
 * cannot leak to another position or survive a crash in the middle of the write.
 */
interface TierChangeable {
    /** Whether this bay's block is being swapped for another tier right now. */
    boolean isChangingTier();

    /** Sets the flag; always paired with {@link #endTierChange()} in a {@code finally}. */
    void beginTierChange();

    /** Clears the flag. */
    void endTierChange();
}
