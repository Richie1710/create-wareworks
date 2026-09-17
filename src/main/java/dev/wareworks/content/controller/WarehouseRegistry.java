package dev.wareworks.content.controller;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;
import net.createmod.catnip.data.WorldAttached;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Server-side, in-memory index of the aisles of a level: controller position → registered {@link AisleLayout}
 * ({@code docs/warehouse-system.md} §4, ADR-010).
 * <p>
 * Controllers register when their layout becomes known or changes (and again in {@code onLoad()}), and unregister in
 * {@code invalidate()}/{@code remove()}. Members report changes with {@link #memberChanged}, which marks only the affected
 * rack position dirty at every controller whose aisle contains it, and storage members report content changes of their
 * attached inventory with {@link #contentChanged}. Nothing is persisted: after a restart or chunk load,
 * controllers register again from their own saved layout, and members notify again from their {@code onLoad()}.
 * <p>
 * Per-level maps live in a catnip {@link WorldAttached}, which Create clears on level unload. Only
 * {@link ServerLevel}s are accepted; calls with client or Ponder levels do nothing. Cost of a notification or lookup:
 * one arithmetic containment test per registered controller of the level, plus one block entity lookup per containing
 * controller. Server thread only.
 */
public final class WarehouseRegistry {
    private static final WorldAttached<Map<BlockPos, AisleLayout>> CONTROLLERS =
            new WorldAttached<>(level -> new HashMap<>());

    private WarehouseRegistry() {
    }

    /** Registers or replaces the layout of the controller at {@code controller}. */
    public static void register(Level level, BlockPos controller, AisleLayout layout) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(layout, "layout");
        if (level instanceof ServerLevel)
            CONTROLLERS.get(level).put(controller.immutable(), layout);
    }

    /** Removes the controller at {@code controller}; does nothing if it is not registered. */
    public static void unregister(Level level, BlockPos controller) {
        Objects.requireNonNull(controller, "controller");
        if (level instanceof ServerLevel)
            CONTROLLERS.get(level).remove(controller);
    }

    /** The layout registered for the controller at {@code controller}. */
    public static Optional<AisleLayout> registeredLayout(Level level, BlockPos controller) {
        if (!(level instanceof ServerLevel))
            return Optional.empty();
        return Optional.ofNullable(CONTROLLERS.get(level).get(controller));
    }

    /**
     * A member at {@code member} was loaded, placed, rotated or removed: every loaded controller whose aisle contains
     * the position re-probes that position on its next tick. Entries of controllers that are loaded but gone are
     * dropped.
     *
     * @return the number of controllers notified
     */
    public static int memberChanged(Level level, BlockPos member) {
        return notifyContaining(level, member, WarehouseControllerBlockEntity::onMemberChanged);
    }

    /**
     * The inventory attached to the storage member at {@code member} changed (a hint from the member): every loaded
     * controller whose aisle contains the position re-reads that location within a few ticks (throttled and
     * de-duplicated by the controller). Entries of controllers that are loaded but gone are dropped.
     *
     * @return the number of controllers notified
     */
    public static int contentChanged(Level level, BlockPos member) {
        return notifyContaining(level, member, WarehouseControllerBlockEntity::onContentChanged);
    }

    /**
     * A player changed the store filter of the storage member at {@code member} ({@code docs/warehouse-system.md} §3.1,
     * ADR-021): every loaded controller whose aisle contains the position re-reads that one filter, so the next planning
     * run already honours it. Entries of controllers that are loaded but gone are dropped.
     *
     * @return the number of controllers notified
     */
    public static int filterChanged(Level level, BlockPos member) {
        return notifyContaining(level, member, WarehouseControllerBlockEntity::onStorageFilterChanged);
    }

    private static int notifyContaining(Level level, BlockPos member,
                                        BiConsumer<WarehouseControllerBlockEntity, RackPosition> notification) {
        Objects.requireNonNull(member, "member");
        if (!(level instanceof ServerLevel))
            return 0;
        Map<BlockPos, AisleLayout> controllers = CONTROLLERS.get(level);
        int notified = 0;
        Iterator<Map.Entry<BlockPos, AisleLayout>> iterator = controllers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, AisleLayout> entry = iterator.next();
            Optional<RackPosition> rack = entry.getValue().worldToLocal(member);
            if (rack.isEmpty())
                continue;
            WarehouseControllerBlockEntity controller = loadedController(level, entry.getKey());
            if (controller == null) {
                if (level.isLoaded(entry.getKey()))
                    iterator.remove();
                continue;
            }
            notification.accept(controller, rack.get());
            notified++;
        }
        return notified;
    }

    /**
     * The loaded controller whose aisle contains {@code member}. Several aisles can contain the same position
     * (neighbouring aisles sharing a rack plane, {@code docs/warehouse-system.md} §4), so the answer is picked by a
     * fixed rule rather than by map iteration order: an aisle in which the member there is aligned beats one in which
     * it is not, then the aisle whose <b>dock is closest</b> to the member wins, and an exact tie is broken by the
     * controller position. The same build therefore always resolves to the same controller.
     */
    public static Optional<WarehouseControllerBlockEntity> findController(Level level, BlockPos member) {
        Objects.requireNonNull(member, "member");
        if (!(level instanceof ServerLevel))
            return Optional.empty();
        // One block entity lookup for the member, not one per registered controller.
        WarehouseMember memberEntity = level.isLoaded(member)
                && level.getBlockEntity(member) instanceof WarehouseMember found ? found : null;
        WarehouseControllerBlockEntity best = null;
        AisleLayout bestLayout = null;
        boolean bestAligned = false;
        for (Map.Entry<BlockPos, AisleLayout> entry : CONTROLLERS.get(level).entrySet()) {
            AisleLayout layout = entry.getValue();
            Optional<RackPosition> rack = layout.worldToLocal(member);
            if (rack.isEmpty())
                continue;
            WarehouseControllerBlockEntity controller = loadedController(level, entry.getKey());
            if (controller == null)
                continue;
            boolean aligned = memberEntity != null && memberEntity.isAlignedWith(layout, rack.get().side());
            if (best != null && !isBetterCandidate(member, aligned, layout, controller, bestAligned, bestLayout, best))
                continue;
            best = controller;
            bestLayout = layout;
            bestAligned = aligned;
        }
        return Optional.ofNullable(best);
    }

    /**
     * Whether the controller at {@code controller} is the one allowed to write the block state of the member at
     * {@code member} ({@link WarehouseMember#alignToAisle}).
     * <p>
     * Several aisles can contain one position, and for two <b>parallel</b> aisles whose lines are two blocks apart the
     * rack plane between them belongs to both ({@code docs/warehouse-system.md} §4, §8) — with <b>opposite</b> side
     * directions, so the two layouts want opposite block states there. Exactly one of them may therefore write, and
     * the choice must not depend on the state itself: unlike {@link #findController} this rule <b>ignores
     * alignment</b>, because alignment is what such a write changes, so an alignment-first tie-break would hand
     * ownership to whichever controller wrote last and the two would rewrite the block on alternating ticks for ever
     * (M10 review finding). The owner is the aisle whose <b>dock is closest</b> to the member, then the lower
     * controller position — a fixed rule, so the same build always resolves to the same owner.
     * <p>
     * A member that no registered, loaded aisle contains has no owner, and the asking controller is then the only
     * authority there is (it can probe before its own layout is registered): the answer is {@code true}.
     * <p>
     * Cost: one arithmetic containment test per registered controller of the level plus one block entity lookup per
     * containing controller, asked only when a write would otherwise happen. Server thread only.
     */
    public static boolean ownsMemberState(Level level, BlockPos member, BlockPos controller) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(controller, "controller");
        if (!(level instanceof ServerLevel))
            return false;
        BlockPos owner = null;
        AisleLayout ownerLayout = null;
        for (Map.Entry<BlockPos, AisleLayout> entry : CONTROLLERS.get(level).entrySet()) {
            AisleLayout layout = entry.getValue();
            if (layout.worldToLocal(member).isEmpty() || loadedController(level, entry.getKey()) == null)
                continue;
            if (owner != null && !isNearerDock(member, layout, entry.getKey(), ownerLayout, owner))
                continue;
            owner = entry.getKey();
            ownerLayout = layout;
        }
        return owner == null || owner.equals(controller);
    }

    /** The tie-break of {@link #ownsMemberState}: dock distance, then controller position — never alignment. */
    private static boolean isNearerDock(BlockPos member, AisleLayout layout, BlockPos controller,
                                        AisleLayout bestLayout, BlockPos best) {
        double distance = layout.dock().distSqr(member);
        double bestDistance = bestLayout.dock().distSqr(member);
        if (distance != bestDistance)
            return distance < bestDistance;
        return controller.asLong() < best.asLong();
    }

    /** The tie-break of {@link #findController}: alignment first, then dock distance, then controller position. */
    private static boolean isBetterCandidate(BlockPos member, boolean aligned, AisleLayout layout,
                                             WarehouseControllerBlockEntity controller, boolean bestAligned,
                                             AisleLayout bestLayout, WarehouseControllerBlockEntity best) {
        if (aligned != bestAligned)
            return aligned;
        double distance = layout.dock().distSqr(member);
        double bestDistance = bestLayout.dock().distSqr(member);
        if (distance != bestDistance)
            return distance < bestDistance;
        return controller.getBlockPos().asLong() < best.getBlockPos().asLong();
    }

    /**
     * The goggle assignment of {@code member} at {@code pos}: its address in the first registered aisle where it is
     * aligned, {@link AisleAssignment#MISALIGNED} if it only stands at rack positions with the wrong facing, otherwise
     * {@link AisleAssignment#NONE}.
     */
    public static AisleAssignment assignmentOf(Level level, BlockPos pos, WarehouseMember member) {
        return assignment(scan(level, pos, member));
    }

    /**
     * The goggle data of the storage member at {@code pos} from one scan: its {@link #assignmentOf assignment} and the
     * reservations of its location in that aisle ({@link WarehouseControllerBlockEntity#reservationSummaryAt}), which are
     * {@link LocationReservationSummary#NONE} unless it is aligned at a rack position of a loaded controller.
     */
    public static StorageObservation observeStorage(Level level, BlockPos pos, WarehouseMember member) {
        MemberScan scan = scan(level, pos, member);
        AlignedMember aligned = scan.aligned();
        LocationReservationSummary reservations = aligned == null ? LocationReservationSummary.NONE
                : aligned.controller().reservationSummaryAt(aligned.rack());
        boolean filterShadowed = aligned != null
                && aligned.controller().isStorageFilterShadowed(aligned.rack());
        return new StorageObservation(assignment(scan), reservations, filterShadowed);
    }

    /**
     * What the goggles of a storage member show about its aisle, resolved by {@link #observeStorage} in one registry scan.
     *
     * @param assignment     address, misaligned or not part of an aisle
     * @param reservations   reservations of its location; {@link LocationReservationSummary#NONE} unless assigned
     * @param filterShadowed its store filter has no effect, because another storage location counts the inventory it
     *                       reads (double chest, item vault) and the planner only ever asks that one
     *                       ({@code docs/warehouse-system.md} §3.1.1, M8 review fix)
     */
    public record StorageObservation(AisleAssignment assignment, LocationReservationSummary reservations,
                                     boolean filterShadowed) {
        public StorageObservation {
            Objects.requireNonNull(assignment, "assignment");
            Objects.requireNonNull(reservations, "reservations");
        }
    }

    private static AisleAssignment assignment(MemberScan scan) {
        if (scan.aligned() != null)
            return AisleAssignment.assigned(scan.aligned().address());
        return scan.misaligned() ? AisleAssignment.MISALIGNED : AisleAssignment.NONE;
    }

    /** A member aligned at a rack position of a loaded controller's aisle. */
    private record AlignedMember(WarehouseControllerBlockEntity controller, RackPosition rack, StorageAddress address) {
    }

    /** The first aisle where the member is aligned (or none), and whether it stands misaligned in another one. */
    private record MemberScan(@Nullable AlignedMember aligned, boolean misaligned) {
        static final MemberScan NONE = new MemberScan(null, false);
    }

    private static MemberScan scan(Level level, BlockPos pos, WarehouseMember member) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(member, "member");
        if (!(level instanceof ServerLevel))
            return MemberScan.NONE;
        boolean containedMisaligned = false;
        for (Map.Entry<BlockPos, AisleLayout> entry : CONTROLLERS.get(level).entrySet()) {
            AisleLayout layout = entry.getValue();
            Optional<RackPosition> rack = layout.worldToLocal(pos);
            if (rack.isEmpty())
                continue;
            WarehouseControllerBlockEntity controller = loadedController(level, entry.getKey());
            if (controller == null)
                continue;
            if (!member.isAlignedWith(layout, rack.get().side())) {
                containedMisaligned = true;
                continue;
            }
            Optional<StorageAddress> address = layout.address(rack.get());
            if (address.isPresent())
                return new MemberScan(new AlignedMember(controller, rack.get(), address.get()), containedMisaligned);
        }
        return new MemberScan(null, containedMisaligned);
    }

    @Nullable
    private static WarehouseControllerBlockEntity loadedController(Level level, BlockPos pos) {
        if (!level.isLoaded(pos))
            return null;
        return level.getBlockEntity(pos) instanceof WarehouseControllerBlockEntity controller && !controller.isRemoved()
                ? controller : null;
    }
}
