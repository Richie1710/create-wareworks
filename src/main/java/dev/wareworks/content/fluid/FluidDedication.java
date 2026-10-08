package dev.wareworks.content.fluid;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

/**
 * Which fluid a storage location takes <b>filled containers</b> of — the store gate's whole question about a fluid bay
 * ({@code docs/warehouse-system.md} §3.9, M30, issue #21, D6).
 * <p>
 * It exists because the answer has <b>three</b> states and an {@code Optional<FluidKey>} can only carry two:
 * <ul>
 * <li><b>this fluid</b> ({@link #of}) — the bay's filter names a fluid, or the bay already holds one;</li>
 * <li><b>whichever fluid arrives first</b> ({@link #ANY}) — an unfiltered bay that is empty, the state its goggles call
 * "Takes the first fluid that arrives";</li>
 * <li><b>not a fluid location at all</b> — an empty {@code Optional<FluidDedication>}, which is every other storage
 * location in the mod ({@code StorageMember#storeFluidFilter()}).</li>
 * </ul>
 * Collapsing the middle state into "no dedication" would make an empty unfiltered bay accept <b>items</b>, and
 * collapsing it into "accepts nothing" would mean a fresh tank wall could never learn its fluid at all.
 * <p>
 * <b>{@link #ANY} is a dedication, not an absence of one.</b> An unfiltered item location takes whatever a player
 * stores in it for the rest of its life; an unfiltered fluid bay takes exactly one fluid and has merely not been told
 * which. That is why both states rank as {@code FilterMatch#DEDICATED} and why neither ever accepts an item that
 * carries no fluid.
 *
 * @param fluid the fluid this location is dedicated to, or {@code null} for "whichever arrives first"
 */
public record FluidDedication(@Nullable FluidKey fluid) {
    /** A fluid location that has not decided yet: it accepts a container of any fluid, and one of them decides. */
    public static final FluidDedication ANY = new FluidDedication(null);

    /** A fluid location dedicated to exactly {@code fluid}. */
    public static FluidDedication of(FluidKey fluid) {
        return new FluidDedication(Objects.requireNonNull(fluid, "fluid"));
    }

    /** {@link #of(FluidKey)} for a present fluid, {@link #ANY} for an absent one. */
    public static FluidDedication from(Optional<FluidKey> fluid) {
        Objects.requireNonNull(fluid, "fluid");
        return fluid.map(FluidDedication::of).orElse(ANY);
    }

    /** Whether a container of {@code arriving} belongs here. {@link #ANY} accepts every fluid. */
    public boolean accepts(FluidKey arriving) {
        Objects.requireNonNull(arriving, "arriving");
        return fluid == null || fluid.equals(arriving);
    }

    /** Whether this location has not decided on a fluid yet ({@link #ANY}). */
    public boolean isAny() {
        return fluid == null;
    }

    /** The fluid this location is dedicated to, empty for {@link #ANY}. */
    public Optional<FluidKey> dedicated() {
        return Optional.ofNullable(fluid);
    }
}
