package dev.wareworks.core.terminal;

/**
 * What a fluid cell of a warehouse terminal says about asking for that fluid (M31, issue #21,
 * {@code docs/warehouse-system.md} §3.9.6). It travels by <b>name</b>, so a state a build does not know reads as
 * {@link #OK} rather than as a shifted one.
 */
public enum FluidRowState {
    /** Empty containers are in stock and fluid is left to fill them with. */
    OK,
    /**
     * No usable empty container is in stock and no production pattern makes one: the cell is framed red and its
     * tooltip points at the pipe at the bay's back, which needs no container (the owner's decision 3).
     */
    NO_EMPTY_CONTAINERS,
    /** No usable empty container is in stock, but a production pattern of the aisle makes one: a click offers it. */
    NO_EMPTY_CONTAINERS_MAKEABLE,
    /** The bays hold the fluid, and open requests have promised all of it. */
    ALL_PROMISED;

    /** Whether the cell is framed red: no container to fill, made or not. */
    public boolean lacksContainers() {
        return this == NO_EMPTY_CONTAINERS || this == NO_EMPTY_CONTAINERS_MAKEABLE;
    }

    /** The state with the given name, or {@link #OK} for an unknown one. */
    public static FluidRowState byName(String name) {
        for (FluidRowState state : values()) {
            if (state.name().equals(name))
                return state;
        }
        return OK;
    }
}
