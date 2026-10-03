package dev.wareworks.core.warehouse;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.StorageAddress;

/**
 * The names a warehouse's aisles carry, keyed by aisle <b>letter</b> (M25, issue #15). Owned by one warehouse
 * controller; server thread only, except for the copy a client reads out of an update tag.
 * <p>
 * <b>Why the letter is the key.</b> A name decorates an address, and an address names its aisle by letter
 * ({@link StorageAddress#aisle()}) — so a name keyed to the letter is exactly as correct as the address it decorates,
 * and wrong exactly when, and only when, the letter is wrong. Keying names to the branch index instead would be
 * <i>more</i> stable than the address beside it, which sounds better and is worse: during the window in which two
 * branches share a letter, every rack of both of them formats as {@code B-..}, so a player reading {@code B - Ores} on
 * one aisle and {@code B - Metals} on the other would have no way to tell which {@code B} the terminal means. Letter
 * keying also reaches the dock aisle, which a branch-keyed table structurally cannot: the branch table deliberately
 * does not remember branch 0.
 * <p>
 * <b>Bounded by its key space.</b> There are {@value StorageAddress#AISLE_COUNT} aisle letters and
 * {@link #set(char, String)} refuses everything else, so the table cannot hold more entries than that however
 * malformed its input was — the bound is not a cap that can be hit, it is the shape of the key. With
 * {@link AisleName#MAX_LENGTH} per name that is the whole worst case this costs a save and a packet.
 * <p>
 * <b>Nothing here throws and nothing here trusts.</b> Every name goes through {@link AisleName#sanitize(String)} on
 * the way in <i>and</i> on the way out, and every letter is checked, because the inputs are a saved tag, a block
 * entity update tag, the custom name of a held item and a hand-edited world. A blank name is not stored at all: an
 * absent entry is the single representation of "this aisle has no name", so no surface has to decide whether
 * {@code ""} means one thing or the other. Sanitising is idempotent and returns clean text unchanged, so the read-side
 * pass costs one scan of at most {@value AisleName#MAX_LENGTH} characters and never allocates.
 */
public final class AisleNames {
    private final SortedMap<Character, String> names = new TreeMap<>();

    /** A warehouse whose aisles are all unnamed. */
    public AisleNames() {
    }

    /**
     * The names in {@code entries}, letter by letter, as this table may hold them: an entry whose letter is not an
     * aisle letter and an entry whose name sanitises to blank are <b>skipped</b>, never rejected, because the caller
     * is a save or a packet and a single bad entry must not cost the good ones.
     */
    public AisleNames(@Nullable Map<Character, String> entries) {
        if (entries == null)
            return;
        for (Map.Entry<Character, String> entry : entries.entrySet()) {
            // Read as a Character and checked, not unboxed into set(char): a map read back from a tag may have lost
            // a key, and an unboxing NPE here would cost the whole controller its names rather than the one entry.
            Character aisle = entry.getKey();
            if (aisle != null)
                set(aisle, entry.getValue());
        }
    }

    /** The name of the aisle with this letter, or empty when it has none or the letter is not an aisle letter. */
    public Optional<String> nameOf(char aisle) {
        String name = AisleName.sanitize(names.get(aisle));
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** Whether the aisle with this letter has a name. */
    public boolean hasName(char aisle) {
        return nameOf(aisle).isPresent();
    }

    /**
     * Gives the aisle with this letter the sanitised form of {@code raw}, replacing any name it had. A {@code raw}
     * that sanitises to blank — {@code null}, spaces, or nothing but characters the game cannot draw — clears the
     * name instead of storing an invisible one.
     *
     * @return {@code false} if {@code aisle} is not an aisle letter, in which case nothing changed
     */
    public boolean set(char aisle, @Nullable String raw) {
        if (!StorageAddress.isValidAisle(aisle))
            return false;
        String name = AisleName.sanitize(raw);
        if (name.isEmpty())
            names.remove(aisle);
        else
            names.put(aisle, name);
        return true;
    }

    /**
     * Takes the name off the aisle with this letter.
     *
     * @return whether there was a name to take off, so a caller can tell the player what actually happened
     */
    public boolean clear(char aisle) {
        return names.remove(aisle) != null;
    }

    /**
     * Carries names with a letter that moved: what was called {@code from} is now called {@code to}, and if {@code to}
     * was already taken its name moves the other way.
     * <p>
     * <b>Why a swap and not an overwrite.</b> This is reached when a player scrolls a controller's aisle letter, which
     * is a reversible gesture — so the table's answer to it has to be reversible too: scrolling back restores exactly
     * what was there, and no name is ever destroyed by a letter change alone. An overwrite would silently delete a
     * name the player gave a different aisle, which is the one outcome a scroll must not have.
     * <p>
     * A letter that is not an aisle letter, and {@code from == to}, are no-ops.
     */
    public void rename(char from, char to) {
        if (from == to || !StorageAddress.isValidAisle(from) || !StorageAddress.isValidAisle(to))
            return;
        String moved = names.remove(from);
        String displaced = names.remove(to);
        if (moved != null)
            names.put(to, moved);
        if (displaced != null)
            names.put(from, displaced);
    }

    /** Whether no aisle of this warehouse has a name — the case every surface must cost nothing for. */
    public boolean isEmpty() {
        return names.isEmpty();
    }

    /** How many aisles have a name; never more than {@value StorageAddress#AISLE_COUNT}. */
    public int size() {
        return names.size();
    }

    /**
     * Every name, in letter order, for persistence, sync and the goggle lines. Unmodifiable, re-sanitised, and a
     * snapshot: later changes to this table do not show up in it.
     */
    public SortedMap<Character, String> entries() {
        SortedMap<Character, String> snapshot = new TreeMap<>();
        names.forEach((aisle, name) -> {
            String sanitized = AisleName.sanitize(name);
            if (!sanitized.isEmpty() && StorageAddress.isValidAisle(aisle))
                snapshot.put(aisle, sanitized);
        });
        return Collections.unmodifiableSortedMap(snapshot);
    }

    /** Forgets every name. */
    public void clearAll() {
        names.clear();
    }

    /** Replaces every name with the ones in {@code other}, which is left untouched. */
    public void copyFrom(@Nullable AisleNames other) {
        names.clear();
        if (other != null)
            names.putAll(other.names);
    }

    @Override
    public String toString() {
        return "AisleNames" + entries();
    }
}
