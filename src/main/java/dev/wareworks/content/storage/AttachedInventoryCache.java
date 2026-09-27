package dev.wareworks.content.storage;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The item handler of <b>one neighbouring inventory</b>, cached the way NeoForge wants it cached: a
 * {@link BlockCapabilityCache} that NeoForge invalidates when the inventory appears, disappears or is replaced, so
 * presence needs no polling at all ({@code docs/warehouse-system.md} §5, ADR-013).
 * <p>
 * Extracted from {@code WarehouseInterfaceBlockEntity} in M18 (issue #13), because a <b>collecting</b> warehouse port
 * reads its attached inventory in exactly the same way and with exactly the same lifecycle — the alternative, a second
 * copy of a capability-cache lifecycle, is the worse bug. The query side is a parameter rather than derived, because the
 * two blocks face opposite ways: a warehouse interface looks <b>at</b> its inventory ({@code pos + FACING}, queried from
 * {@code FACING.getOpposite()}), a port looks away from it ({@code pos − FACING}, queried from {@code FACING}). Both mean
 * "the attached block's face that touches me".
 * <p>
 * <b>Server only for caching.</b> On a client or inside a Ponder level there is no cache: the capability is queried
 * directly and only while the target position is loaded. Call {@link #handler} every time and never keep the handler
 * across ticks.
 * <p>
 * <b>Lifecycle.</b> The cache is created lazily, recreated whenever the target position or the query side changes
 * (rotation), and dropped by {@link #drop()} on removal or chunk unload — a cache whose owner was marked removed can be
 * permanently disabled, which {@link #handler} also recovers from. The invalidation listener runs while chunks unload, so
 * it must do nothing but set a flag: that is what the {@code onChanged} runnable is for, and it is called with no level
 * access of any kind.
 */
public final class AttachedInventoryCache {
    private final BooleanSupplier alive;
    private final Runnable onChanged;

    @Nullable
    private BlockCapabilityCache<IItemHandler, @Nullable Direction> cache;
    @Nullable
    private BlockPos cachedTarget;
    @Nullable
    private Direction cachedSide;

    /**
     * @param alive     whether the owning block entity still exists ({@code () -> !isRemoved()}); NeoForge stops
     *                  maintaining the cache once this answers false
     * @param onChanged called when NeoForge invalidates the capability — <b>only</b> set a flag here, the listener runs
     *                  while chunks unload
     */
    public AttachedInventoryCache(BooleanSupplier alive, Runnable onChanged) {
        this.alive = Objects.requireNonNull(alive, "alive");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    /**
     * The item handler of the block at {@code target}, queried from its {@code querySide} face.
     *
     * @return empty when nothing there offers one, or while {@code target} is not loaded
     */
    public Optional<IItemHandler> handler(Level level, BlockPos target, Direction querySide) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(querySide, "querySide");
        if (!(level instanceof ServerLevel serverLevel)) {
            if (!level.isLoaded(target))
                return Optional.empty();
            return Optional.ofNullable(level.getCapability(Capabilities.ItemHandler.BLOCK, target, querySide));
        }
        if (cache == null || !target.equals(cachedTarget) || cachedSide != querySide)
            create(serverLevel, target, querySide);
        try {
            return Optional.ofNullable(cache.getCapability());
        } catch (IllegalStateException e) {
            // The cache was disabled while its owner was marked removed (e.g. moved by a command); rebuild it.
            create(serverLevel, target, querySide);
            return Optional.ofNullable(cache.getCapability());
        }
    }

    /** Drops the cache: the owner was removed or unloaded, or its facing changed. The next {@link #handler} rebuilds it. */
    public void drop() {
        cache = null;
        cachedTarget = null;
        cachedSide = null;
    }

    private void create(ServerLevel level, BlockPos target, Direction querySide) {
        cachedTarget = target.immutable();
        cachedSide = querySide;
        // The listener may run while chunks unload: it only sets a flag (no level access, no getCapability()).
        cache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, cachedTarget, querySide, alive,
                onChanged);
    }
}
