package dev.wareworks.util;

import java.util.Optional;

import com.simibubi.create.content.equipment.goggles.GogglesItem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Server-side detection of players who look at a block through Engineer's Goggles.
 * <p>
 * Goggle tooltips are built on the client from synced block entity data. Instead of every block entity polling the
 * players (cost: block entities x players), this class runs per player: every {@link #SCAN_INTERVAL_TICKS} ticks, each
 * server player that wears goggles ray-picks the block it looks at (the same bounded ray as block interaction, plus
 * {@link #REACH_MARGIN}), and if that block's entity is {@link Observable}, it is notified. The cost is one short ray per
 * goggle-wearing player per interval, independent of the number of block entities, and block entities need no ticker
 * for it. Players without goggles and spectators cost one predicate check.
 * <p>
 * Registered on the game event bus in the {@code Wareworks} constructor. Server only: client players are ignored.
 */
public final class GoggleObservers {
    /** Ticks between two observation checks of the same player. Players are staggered by entity id. */
    public static final int SCAN_INTERVAL_TICKS = 5;
    /** Minimum game ticks between two goggle summary syncs of one block entity (see {@link SyncThrottle}). */
    public static final int SUMMARY_SYNC_MIN_INTERVAL_TICKS = 20;
    /** Extra reach beyond the player's block interaction range, to tolerate movement between checks. */
    private static final double REACH_MARGIN = 1.0;
    /** Use the current (not interpolated) eye position and rotation. */
    private static final float PARTIAL_TICK = 1.0f;

    /**
     * A block entity whose synced goggle data should be kept fresh while a player looks at it through goggles.
     * <p>
     * {@link #onGoggleObserved()} is called on the server thread, up to once per {@link #SCAN_INTERVAL_TICKS} per
     * observing player. Implementations throttle their own work.
     */
    public interface Observable {
        void onGoggleObserved();
    }

    private GoggleObservers() {
    }

    /** Game-bus listener: runs the observation check for server players at their staggered interval. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (Math.floorMod(player.level().getGameTime() + player.getId(), SCAN_INTERVAL_TICKS) != 0)
            return;
        notifyObserved(player);
    }

    /**
     * Notifies the {@link Observable} block entity the player looks at through goggles, if any.
     *
     * @return whether a block entity was notified
     */
    public static boolean notifyObserved(Player player) {
        Level level = player.level();
        if (level.isClientSide())
            return false;
        Optional<BlockPos> observed = observedBlock(player);
        if (observed.isEmpty() || !level.isLoaded(observed.get()))
            return false;
        if (!(level.getBlockEntity(observed.get()) instanceof Observable observable))
            return false;
        observable.onGoggleObserved();
        return true;
    }

    /**
     * The block a non-spectator player wearing goggles looks at within reach (block interaction range plus
     * {@link #REACH_MARGIN}; fluids are ignored, blocks occlude). Empty without goggles or when the ray hits nothing.
     */
    public static Optional<BlockPos> observedBlock(Player player) {
        if (player.isSpectator() || !GogglesItem.isWearingGoggles(player))
            return Optional.empty();
        HitResult hit = player.pick(player.blockInteractionRange() + REACH_MARGIN, PARTIAL_TICK, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit))
            return Optional.empty();
        return Optional.of(blockHit.getBlockPos());
    }
}
