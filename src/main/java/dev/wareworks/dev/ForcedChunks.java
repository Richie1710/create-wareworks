package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * The chunk columns of a scenario's far sites, force-loaded with {@link ServerLevel#setChunkForced} for the run and
 * released in the scenario's {@code finish} (dev tooling, ADR-014; shared by {@code robobee} and
 * {@code selector-remote}). Only the columns the boxes touch are forced; a site that must work without a player nearby
 * needs nothing else.
 */
final class ForcedChunks {
    private final String prefix;
    private volatile List<ChunkPos> chunks = List.of();

    ForcedChunks(String prefix) {
        this.prefix = prefix;
    }

    /** Forces every chunk column {@code boxes} touch, and remembers them for {@link #release}. */
    void force(ServerLevel level, List<AABB> boxes) {
        Set<ChunkPos> columns = new LinkedHashSet<>();
        for (AABB box : boxes)
            for (int x = (int) Math.floor(box.minX) >> 4; x <= ((int) Math.ceil(box.maxX) - 1) >> 4; x++)
                for (int z = (int) Math.floor(box.minZ) >> 4; z <= ((int) Math.ceil(box.maxZ) - 1) >> 4; z++)
                    columns.add(new ChunkPos(x, z));
        chunks = List.copyOf(columns);
        for (ChunkPos chunk : chunks)
            level.setChunkForced(chunk.x, chunk.z, true);
        LOGGER.info(PREFIX + "{}: chunks {} forced=true", prefix, chunks);
    }

    /** Releases what {@link #force} forced; nothing when nothing was forced. */
    void release(ServerLevel level) {
        for (ChunkPos chunk : chunks)
            level.setChunkForced(chunk.x, chunk.z, false);
        LOGGER.info(PREFIX + "{}: chunks {} forced=false", prefix, chunks);
    }

    List<ChunkPos> chunks() {
        return chunks;
    }

    /** Whether every forced column still has its ticket. */
    boolean allStillForced(ServerLevel level) {
        return chunks.stream().allMatch(chunk -> level.getForcedChunks().contains(chunk.toLong()));
    }
}
