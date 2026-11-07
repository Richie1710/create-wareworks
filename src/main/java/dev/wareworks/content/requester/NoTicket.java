package dev.wareworks.content.requester;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reads of a block somewhere else that never load a chunk and never hold one (issue #45, ADR-080): what a Schematicannon
 * Requester polling every second, and a Warehouse Remote, use for every position that is not their own.
 * <p>
 * {@code Level#getBlockEntity} and {@code Level#getBlockState} go through {@code ServerChunkCache#getChunk} with a
 * required chunk, and every miss of its small four-entry cache adds a {@code TicketType.UNKNOWN} ticket there
 * ({@code ServerChunkCache#getChunkFutureMainThread}), even for a chunk that is loaded. The ticket lives two ticks: read
 * every tick it holds the chunk loaded (what the remote's open screen had to avoid, issue #44), read once a second it
 * holds it a moment past its release each time — either way a reader that has no business keeping the chunk renews a
 * ticket on it. Here the chunk is taken with {@code ServerChunkCache#getChunkNow}, which only answers a chunk that is
 * fully loaded, and the block entity with {@link LevelChunk.EntityCreationType#CHECK}, which creates none. The GameTest
 * {@code noticketreadsholdnochunk} measures the difference.
 * <p>
 * Server thread only: {@code getChunkNow} answers {@code null} on any other thread, and so do these.
 */
public final class NoTicket {
    private NoTicket() {
    }

    /** The fully loaded chunk at {@code pos}, or {@code null}; never loads or tickets one. */
    @Nullable
    public static LevelChunk chunk(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        if (!level.isLoaded(pos))
            return null;
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Whether the chunk at {@code pos} is loaded <b>and</b> ticks blocks: a block there can make progress. */
    public static boolean ticking(ServerLevel level, BlockPos pos) {
        return chunk(level, pos) != null && level.shouldTickBlocksAt(pos);
    }

    /** The block entity at {@code pos} if its chunk is loaded and it is not removed, else {@code null}. */
    @Nullable
    public static BlockEntity blockEntity(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = chunk(level, pos);
        if (chunk == null)
            return null;
        BlockEntity found = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        return found == null || found.isRemoved() ? null : found;
    }

    /** The block entity at {@code pos} if it is a non-removed {@code type}, else {@code null}; see {@link #blockEntity}. */
    @Nullable
    public static <T> T blockEntity(ServerLevel level, BlockPos pos, Class<T> type) {
        Objects.requireNonNull(type, "type");
        BlockEntity found = blockEntity(level, pos);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    /** The block state at {@code pos} if its chunk is loaded, else {@code null}. */
    @Nullable
    public static BlockState blockState(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = chunk(level, pos);
        return chunk == null ? null : chunk.getBlockState(pos);
    }
}
