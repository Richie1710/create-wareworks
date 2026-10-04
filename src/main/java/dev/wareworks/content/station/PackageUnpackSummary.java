package dev.wareworks.content.station;

import net.minecraft.nbt.CompoundTag;

/**
 * What the goggle tooltip of a <b>warehouse input</b> shows about Create packages taken apart at it
 * ({@code docs/warehouse-system.md} §3.2.5, M26, issue #18), as synced to clients.
 * <p>
 * All three values are server-only knowledge: {@link WarehouseInputUnpackingHandler} sees the unpack attempt, and the
 * refusal is a moment a client is never told about by any other channel — the package simply stays in the funnel and
 * every block in the game says nothing. Whether a Create Packager stands at the input is <b>not</b> here: that is a
 * block state the client already has, so the line is read off the world ({@code WarehouseInputBlockEntity}).
 * <p>
 * Three small numbers, so the synced size is bounded; {@link #NONE} is written as nothing at all, so no other
 * station's and no input without a Packager grows by a byte. Records compare by value, so the server only syncs a
 * visible change, and reading never throws.
 * <p>
 * <b>Not saved.</b> This is a diagnosis, not state the warehouse plans with: it starts empty after a load, like the
 * cold caches of {@code AislePorts}. An old save is unaffected and no NBT key is added anywhere.
 *
 * @param opened            packages taken apart at this input since it was loaded, counted on the <b>real</b> unpack,
 *                          because that is when the contents crossed into the buffer
 * @param refusedPackageStacks item stacks the last refused package held, counted <b>before</b>
 *                          {@code DefaultUnpackingHandler}'s simulate pass was let near the list. Zero means no
 *                          refusal is on record: only a package with something in it can be refused
 * @param freeSlotsAtRefusal buffer slots that were free at that moment. The pair is the all-or-nothing cliff in one
 *                          line — Create consumes a package whole or not at all, so a package of three stacks needs
 *                          room for all three at once, however much room the buffer has in total. The two numbers are
 *                          deliberately <b>commensurable</b>: what the package brought against what the buffer had
 *                          free, which is why they can be read side by side. The stacks the simulate pass could not
 *                          place would not be — Create places greedily first, so "4 stacks found no room" beside "5
 *                          slots free" reads as a contradiction although both numbers are right
 */
public record PackageUnpackSummary(long opened, int refusedPackageStacks, int freeSlotsAtRefusal) {
    /** Nothing to say: no package was ever handed to this input. */
    public static final PackageUnpackSummary NONE = new PackageUnpackSummary(0L, 0, 0);

    private static final String OPENED = "Opened";
    private static final String REFUSED_STACKS = "RefusedStacks";
    private static final String FREE_SLOTS = "FreeSlots";

    public PackageUnpackSummary {
        opened = Math.max(0L, opened);
        refusedPackageStacks = Math.max(0, refusedPackageStacks);
        // One representation per state, so that write/read is an identity over every value this record permits: the
        // free-slot count is only written beside a refusal, so without one it must already be zero here.
        freeSlotsAtRefusal = refusedPackageStacks == 0 ? 0 : Math.max(0, freeSlotsAtRefusal);
    }

    /** Whether a refused package is on record. */
    public boolean hasRefusal() {
        return refusedPackageStacks > 0;
    }

    /** Whether this summary says nothing at all, in which case it is left out of the packet entirely. */
    public boolean isEmpty() {
        return equals(NONE);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        if (opened > 0L)
            tag.putLong(OPENED, opened);
        if (refusedPackageStacks > 0) {
            tag.putInt(REFUSED_STACKS, refusedPackageStacks);
            // Only written beside the stack count: without a refusal the number would mean nothing, and zero free
            // slots is a perfectly ordinary refusal that must still read as one.
            tag.putInt(FREE_SLOTS, freeSlotsAtRefusal);
        }
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as {@link #NONE}. */
    public static PackageUnpackSummary read(CompoundTag tag) {
        return new PackageUnpackSummary(tag.getLong(OPENED), tag.getInt(REFUSED_STACKS), tag.getInt(FREE_SLOTS));
    }
}
