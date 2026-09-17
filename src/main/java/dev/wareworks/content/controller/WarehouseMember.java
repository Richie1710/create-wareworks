package dev.wareworks.content.controller;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A block entity that can be a member of an aisle when it stands at a rack position: warehouse interface (storage
 * location), warehouse input and warehouse output ({@code docs/warehouse-system.md} §1, §4).
 * <p>
 * Contract for implementations (server side):
 * <ul>
 * <li>call {@link WarehouseRegistry#memberChanged} from {@code onLoad()} (covers placement and chunk loading), after
 * a facing change ({@code setBlockState}) and from {@code remove()} (real removal), so that controllers whose aisle
 * contains the position re-probe it;</li>
 * <li>storage members also implement {@link StorageMember}, which the controller snapshots.</li>
 * </ul>
 * The controller classifies a member by {@link #locationKind()} and {@link #isAlignedWith}; misaligned members are only
 * counted for goggles.
 */
public interface WarehouseMember {
    /** What this member provides to an aisle. */
    LocationKind locationKind();

    /** The member's horizontal facing ({@code FACING} block state property). */
    Direction facing();

    /**
     * Server: this member stands at a rack position of {@code layout} on {@code side}, whose controller sits at
     * {@code controller}. A member whose block state depends on <b>where the aisle is</b> — today only the warehouse
     * terminal, whose intake port has to face it ({@code docs/warehouse-system.md} §3.4.3, ADR-022) — adapts that
     * state here.
     * <p>
     * Contract for implementations:
     * <ul>
     * <li>write the block state <b>only when it really changes</b>, so the probe stays a read for every settled
     * member;</li>
     * <li><b>only one controller may write.</b> Several aisles can contain one position (neighbouring aisles sharing a
     * rack plane, {@code docs/warehouse-system.md} §4), and two <i>parallel</i> ones want opposite states there, so an
     * implementation must refuse unless {@code controller} is the one
     * {@link WarehouseRegistry#ownsMemberState(net.minecraft.world.level.Level, BlockPos, BlockPos) that owns the
     * member's block state}. Without that guard the two controllers rewrite the block on alternating ticks for ever
     * (M10 review finding).</li>
     * </ul>
     * The controller calls this from its membership probe, i.e. only while membership is dirty and never per tick, and
     * always immediately before {@link #isAlignedWith}, so the same probe already sees the adapted state. A write from
     * here re-enters {@link dev.wareworks.core.warehouse.AisleMembership#markDirty} for the position being probed,
     * which that class tolerates by design (it snapshots the dirty set before probing); the cost is one further
     * reconcile pass, in which nothing is written any more. The default does nothing.
     */
    default void alignToAisle(BlockPos controller, AisleLayout layout, Side side) {
    }

    /**
     * Whether this member satisfies the facing rule of its kind at a rack position on {@code side} of {@code layout}:
     * storage faces away from the aisle ({@code FACING == side direction}), stations face the aisle.
     */
    default boolean isAlignedWith(AisleLayout layout, Side side) {
        Direction awayFromAisle = layout.sideDirection(side);
        return facing() == (locationKind().facesAwayFromAisle() ? awayFromAisle : awayFromAisle.getOpposite());
    }
}
