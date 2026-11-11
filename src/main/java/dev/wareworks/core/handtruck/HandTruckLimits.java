package dev.wareworks.core.handtruck;

import dev.wareworks.core.storage.BayTier;

/**
 * How much a hand truck may carry (issue #37, ADR-070): the size budget of one carried container's save data.
 * <p>
 * A loaded truck carries its container's whole block entity data in an item component, and an item component travels
 * with every inventory packet and every save of the player. The budget keeps that bounded well below the 2 MiB a
 * compound tag may take on the network ({@code ByteBufCodecs#COMPOUND_TAG}): a chest of 27 full shulker boxes of
 * plain items is about 50 KiB, so only a chest stuffed with written books ever reaches the default.
 * <p>
 * The size is the one NBT itself counts ({@code CompoundTag#sizeInBytes}, the measure {@code NbtAccounter} uses). The
 * configured limit is clamped here as well, rather than trusting a file a player edits.
 * <p>
 * A carried <b>pallet</b> (ADR-084) is a key and a count, so its bound is a count: {@link #palletCount} keeps it inside
 * {@code 1..}{@link #MAX_PALLET_COUNT}, the most any bay and therefore any pallet can hold.
 */
public final class HandTruckLimits {
    /** The smallest budget the configuration may set, in KiB. */
    public static final int MIN_KIB = 16;
    /** The largest budget the configuration may set, in KiB: half of what one compound tag may take on the network. */
    public static final int MAX_KIB = 1024;
    /** The shipped budget, in KiB. */
    public static final int DEFAULT_KIB = 512;
    private static final int BYTES_PER_KIB = 1024;
    /** The most items a carried pallet may hold: what the largest bay any configuration allows can hold. */
    public static final int MAX_PALLET_COUNT = BayTier.MAX_CAPACITY_ITEMS;

    private HandTruckLimits() {
    }

    /** {@code maxKiB} clamped into {@value #MIN_KIB}..{@value #MAX_KIB}. */
    public static int clampKiB(int maxKiB) {
        return Math.max(MIN_KIB, Math.min(MAX_KIB, maxKiB));
    }

    /** Whether {@code bytes} of save data fit a budget of {@code maxKiB} (clamped): exactly the budget still fits. */
    public static boolean fits(long bytes, int maxKiB) {
        return bytes >= 0 && bytes <= (long) clampKiB(maxKiB) * BYTES_PER_KIB;
    }

    /** {@code bytes} in KiB, rounded up, so a refusal never reads "512 of 512 KiB". */
    public static long kib(long bytes) {
        if (bytes <= 0)
            return 0;
        return (bytes + BYTES_PER_KIB - 1) / BYTES_PER_KIB;
    }

    /**
     * {@code count} clamped into {@code 1..}{@value #MAX_PALLET_COUNT}: what a carried pallet's count is read as,
     * whatever a save or a creative client wrote. A decode never fails on it, so a truck is never dropped from an
     * inventory over its count; the caller logs a clamp, because a clamp from above is item loss.
     */
    public static int palletCount(int count) {
        return Math.max(1, Math.min(MAX_PALLET_COUNT, count));
    }
}
