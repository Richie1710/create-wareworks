package dev.wareworks.content.station;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

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
 * @param heldLoose         package mode only (issue #40): loose items the controller's backstop keeps in this input
 *                          for a player's empty hand; 0 with package mode off
 * @param lastLooseRefused  package mode only: the id of the last loose item an entrance of this input refused, empty
 *                          when none (or with package mode off)
 * @param packagerRefused   package mode only: the Packager behind this input was refused a box because opening it
 *                          would put loose items into a package-mode warehouse
 * @param boxAtMaximum      package mode only (issue #40, S5): the address of the last box an entrance refused because
 *                          a package rule's maximum leaves its address no room, {@code null} when none was refused
 */
public record PackageUnpackSummary(long opened, int refusedPackageStacks, int freeSlotsAtRefusal, long heldLoose,
                                   String lastLooseRefused, boolean packagerRefused, @Nullable String boxAtMaximum,
                                   long heldBoxes) {
    /** Nothing to say: no package was ever handed to this input. */
    public static final PackageUnpackSummary NONE = new PackageUnpackSummary(0L, 0, 0);

    private static final String OPENED = "Opened";
    private static final String REFUSED_STACKS = "RefusedStacks";
    private static final String FREE_SLOTS = "FreeSlots";
    private static final String HELD_LOOSE = "HeldLoose";
    private static final String LOOSE_REFUSED = "LooseRefused";
    private static final String PACKAGER_REFUSED = "PackagerRefused";
    private static final String BOX_AT_MAXIMUM = "BoxAtMaximum";
    private static final String HELD_BOXES = "HeldBoxes";

    public PackageUnpackSummary {
        opened = Math.max(0L, opened);
        refusedPackageStacks = Math.max(0, refusedPackageStacks);
        // One representation per state, so that write/read is an identity over every value this record permits: the
        // free-slot count is only written beside a refusal, so without one it must already be zero here.
        freeSlotsAtRefusal = refusedPackageStacks == 0 ? 0 : Math.max(0, freeSlotsAtRefusal);
        heldLoose = Math.max(0L, heldLoose);
        heldBoxes = Math.max(0L, heldBoxes);
        if (lastLooseRefused == null)
            lastLooseRefused = "";
    }

    /** The summary of an input outside package mode (M26): nothing about loose items. */
    public PackageUnpackSummary(long opened, int refusedPackageStacks, int freeSlotsAtRefusal) {
        this(opened, refusedPackageStacks, freeSlotsAtRefusal, 0L, "", false, null, 0L);
    }

    /** The summary of an input in package mode without a box refused at a rule's maximum (issue #40, S1). */
    public PackageUnpackSummary(long opened, int refusedPackageStacks, int freeSlotsAtRefusal, long heldLoose,
            String lastLooseRefused, boolean packagerRefused) {
        this(opened, refusedPackageStacks, freeSlotsAtRefusal, heldLoose, lastLooseRefused, packagerRefused, null, 0L);
    }

    /** The summary of an input in package mode without boxes held over a maximum (issue #40, S5). */
    public PackageUnpackSummary(long opened, int refusedPackageStacks, int freeSlotsAtRefusal, long heldLoose,
            String lastLooseRefused, boolean packagerRefused, @Nullable String boxAtMaximum) {
        this(opened, refusedPackageStacks, freeSlotsAtRefusal, heldLoose, lastLooseRefused, packagerRefused,
                boxAtMaximum, 0L);
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
        // Package mode (issue #40): written only while set, so an input outside package mode sends what it always sent.
        if (heldLoose > 0L)
            tag.putLong(HELD_LOOSE, heldLoose);
        if (!lastLooseRefused.isEmpty())
            tag.putString(LOOSE_REFUSED, lastLooseRefused);
        if (packagerRefused)
            tag.putBoolean(PACKAGER_REFUSED, true);
        if (boxAtMaximum != null)
            tag.putString(BOX_AT_MAXIMUM, boxAtMaximum);
        if (heldBoxes > 0L)
            tag.putLong(HELD_BOXES, heldBoxes);
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as {@link #NONE}. */
    public static PackageUnpackSummary read(CompoundTag tag) {
        return new PackageUnpackSummary(tag.getLong(OPENED), tag.getInt(REFUSED_STACKS), tag.getInt(FREE_SLOTS),
                tag.getLong(HELD_LOOSE), tag.getString(LOOSE_REFUSED), tag.getBoolean(PACKAGER_REFUSED),
                tag.contains(BOX_AT_MAXIMUM, Tag.TAG_STRING) ? tag.getString(BOX_AT_MAXIMUM) : null,
                tag.getLong(HELD_BOXES));
    }
}
