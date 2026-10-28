package dev.wareworks.content.crane;

import java.util.Optional;

import dev.wareworks.core.address.StorageAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Why a stacker crane dock that faces into a warehouse's rails is <b>not</b> a crane of that warehouse (issue #14,
 * ADR-075 §15.1), as its goggles say it: the server allows no more cranes, or the dock has a controller of its own and
 * is therefore a warehouse by itself — the <b>foreign-dock hint</b> — or it simply has not been picked up by the next
 * rail check yet.
 * <p>
 * Worked out only while a player looks at the dock through goggles ({@code StackerCraneBlockEntity#onGoggleObserved}),
 * never on a tick, and synced only while there is something to say, so every dock nobody looks at — and every dock
 * that is no such dock — sends exactly what it sent up to 0.17.1. Reading never throws.
 *
 * @param reason    why the dock is not a crane of the warehouse
 * @param letter    the aisle letter of that warehouse's own dock, the name a player reads on its controller
 * @param maxCranes the cranes the server allows per warehouse ({@code aisle.maxCranesPerNetwork})
 */
public record DockJoinStatus(Reason reason, char letter, int maxCranes) {
    private static final String REASON = "Reason";
    private static final String LETTER = "Letter";
    private static final String MAX = "Max";

    /** Why a dock is not a crane of the warehouse it faces. {@link #name()} is only used inside client packets. */
    public enum Reason {
        /** The server keeps every warehouse at one crane ({@code aisle.maxCranesPerNetwork = 1}). */
        ONE_CRANE,
        /** The warehouse already has as many cranes as the server allows. */
        MAX_CRANES,
        /** The dock has a controller of its own, so it is a warehouse by itself and a wall to the other one. */
        OWN_CONTROLLER,
        /** Nothing stands in the way: the warehouse's next rail check takes it in. */
        PENDING
    }

    public DockJoinStatus {
        if (reason == null)
            reason = Reason.PENDING;
        if (!StorageAddress.isValidAisle(letter))
            letter = StorageAddress.FIRST_AISLE;
        maxCranes = Math.max(1, maxCranes);
    }

    /** Writes this status into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putString(REASON, reason.name());
        tag.putString(LETTER, String.valueOf(letter));
        tag.putInt(MAX, maxCranes);
    }

    /** Reads a status written by {@link #write}; empty for a tag without one. Never throws. */
    public static Optional<DockJoinStatus> read(CompoundTag tag) {
        if (!tag.contains(REASON, Tag.TAG_STRING))
            return Optional.empty();
        Reason reason = null;
        for (Reason candidate : Reason.values()) {
            if (candidate.name().equals(tag.getString(REASON)))
                reason = candidate;
        }
        if (reason == null)
            return Optional.empty();
        String letter = tag.getString(LETTER);
        return Optional.of(new DockJoinStatus(reason, letter.isEmpty() ? StorageAddress.FIRST_AISLE : letter.charAt(0),
                tag.getInt(MAX)));
    }
}
