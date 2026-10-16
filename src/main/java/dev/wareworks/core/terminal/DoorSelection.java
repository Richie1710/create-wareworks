package dev.wareworks.core.terminal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

/**
 * Where a click at a warehouse terminal delivers: <b>here</b>, or one of the warehouse's package doors (M27, issue #18,
 * ADR-065 D8).
 * <p>
 * <b>An instance, never shared, never saved.</b> A screen holds one for as long as it is open and starts at
 * {@link #isHere() here} every time it opens; the server holds none at all and only ever acts on the door a request
 * names. Two players at one terminal therefore choose without interfering, and nobody finds a door chosen that they did
 * not choose themselves.
 * <p>
 * The cycle is <b>here, then the doors in the order given</b> (the server sends them in rack order, so editing a sign
 * never makes the cycle jump), wrapping round to here. A door that cannot be chosen — one that ignores redstone or is
 * not loaded — is skipped, and a door that stops being choosable, or disappears, sends the selection back to here
 * ({@link #update}), which says so, so the screen can tell the player.
 * <p>
 * Pure Java, no Minecraft: the identity of a door is whatever {@code P} the caller uses — a block position on the
 * client.
 *
 * @param <P> what identifies a door
 */
public final class DoorSelection<P> {
    /**
     * One door as the selection sees it.
     *
     * @param id         what identifies it; equal ids are the same door
     * @param selectable whether a player may choose it now
     */
    public record Door<P>(P id, boolean selectable) {
        public Door {
            Objects.requireNonNull(id, "id");
        }
    }

    private List<Door<P>> doors = List.of();
    /** The chosen door, or {@code null} for here. */
    @Nullable
    private P selected;

    /** A selection at here, with no doors to choose from. */
    public DoorSelection() {
    }

    /** The chosen door; empty for here. */
    public Optional<P> selected() {
        return Optional.ofNullable(selected);
    }

    /** Whether the selection is here, which is where every screen starts. */
    public boolean isHere() {
        return selected == null;
    }

    /** Whether there is anything to choose at all; without a door the choice is not shown. */
    public boolean visible() {
        return !doors.isEmpty();
    }

    /** The doors in the order they are cycled through, unselectable ones included. */
    public List<Door<P>> doors() {
        return doors;
    }

    /**
     * Goes back to here.
     *
     * @return whether the selection changed
     */
    public boolean here() {
        boolean changed = selected != null;
        selected = null;
        return changed;
    }

    /**
     * Chooses {@code id} if it is one of the doors and may be chosen now; otherwise nothing changes.
     *
     * @return whether {@code id} is now the selection
     */
    public boolean select(P id) {
        if (id == null)
            return false;
        for (Door<P> door : doors) {
            if (door.id().equals(id) && door.selectable()) {
                selected = door.id();
                return true;
            }
        }
        return false;
    }

    /**
     * The next choosable entry after the current one: the first choosable door after here, the next one after a door,
     * and here again after the last.
     *
     * @return whether the selection changed
     */
    public boolean next() {
        return step(1);
    }

    /**
     * The choosable entry before the current one, the other way round through the same cycle.
     *
     * @return whether the selection changed
     */
    public boolean previous() {
        return step(-1);
    }

    /**
     * Takes the doors a push brought. The chosen door stays chosen when it is still there and still choosable, found by
     * its id; otherwise the selection goes back to here.
     *
     * @return whether a chosen door was lost, i.e. the selection had to go back to here
     */
    public boolean update(List<Door<P>> next) {
        Objects.requireNonNull(next, "next");
        doors = List.copyOf(next);
        if (selected == null)
            return false;
        for (Door<P> door : doors) {
            if (door.id().equals(selected) && door.selectable())
                return false;
        }
        selected = null;
        return true;
    }

    /** Index of the current entry in the cycle: 0 for here, {@code 1 + i} for door {@code i}. */
    private int currentIndex() {
        if (selected == null)
            return 0;
        for (int i = 0; i < doors.size(); i++) {
            if (doors.get(i).id().equals(selected))
                return i + 1;
        }
        return 0;
    }

    private boolean step(int direction) {
        int entries = doors.size() + 1;
        int from = currentIndex();
        for (int offset = 1; offset < entries; offset++) {
            int index = Math.floorMod(from + direction * offset, entries);
            if (index == 0)
                return here();
            Door<P> door = doors.get(index - 1);
            if (door.selectable()) {
                boolean changed = !door.id().equals(selected);
                selected = door.id();
                return changed;
            }
        }
        return false;
    }
}
