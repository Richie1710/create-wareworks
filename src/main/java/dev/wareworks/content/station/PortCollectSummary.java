package dev.wareworks.content.station;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.job.NoJobReason;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * What the goggle tooltip of a <b>collecting</b> warehouse port shows about the inventory it fetches from
 * ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13), as synced to clients.
 * <p>
 * Every one of these five values is server-only knowledge: goggles are built on the client, which can neither look into a
 * foreign inventory nor know what this aisle indexes. The direction, the filter and the redstone mode reach the client by
 * themselves (block state and behaviours), so they are deliberately <b>not</b> here.
 * <p>
 * A block id, an enum name and three numbers, so the synced size is bounded whatever stands behind the port;
 * {@link #NONE} is written as nothing at all, so no other station's and no requesting or accepting port's packet grows by
 * a byte. Records compare by value, so the server only syncs a visible change, and reading never throws.
 *
 * @param collected     items this port has fetched into the warehouse since it was built — the mirror of
 *                      {@code StationGoggleSummary#exportedItems()}, counted at the <b>pick</b>, because that is when the
 *                      items crossed the port's threshold
 * @param ready         items the port's last read of the attached inventory found, i.e. what the next collect job has to
 *                      work with. It is the cached snapshot's total, so it can lag by one poll interval, which is exactly
 *                      what a player should see: the warehouse plans from this number
 * @param attachedBlock registry id of the inventory block behind the port, or {@code null} when there is none — the one
 *                      mistake that would otherwise look like a working port doing nothing
 * @param ownStorage    whether that inventory is one this aisle already counts as a storage location. Collecting from it
 *                      is refused ({@code WarehouseControllerBlockEntity#collectSources}), because it would be an endless
 *                      crane shuffle inside the aisle, and this is the line that says so instead of leaving a player with
 *                      a port that never does anything
 * @param refusal       why the warehouse did not take what is waiting here, or {@code null} while it has no such answer
 *                      (M18 review). The two mistakes above are ones the port can see by itself; the one a player hits
 *                      most often — the warehouse is full, a stock rule is at its maximum, no storage filter accepts the
 *                      items — lives on the <b>controller</b> as the aisle's last planning reason, so without carrying it
 *                      here a player standing at their machine with goggles on would be left with a port that says
 *                      "Ready: 24" and nothing else. Only the reasons a collect plan can really produce are ever set
 *                      ({@link NoJobReason#refusesCollecting()})
 */
public record PortCollectSummary(long collected, long ready, @Nullable ResourceLocation attachedBlock,
                                 boolean ownStorage, @Nullable NoJobReason refusal) {
    /** Nothing to say: not a collecting port, or one that was never read. */
    public static final PortCollectSummary NONE = new PortCollectSummary(0L, 0L, null, false, null);

    private static final String COLLECTED = "Collected";
    private static final String READY = "Ready";
    private static final String BLOCK = "Block";
    private static final String OWN_STORAGE = "OwnStorage";
    private static final String REFUSAL = "Refusal";

    public PortCollectSummary {
        collected = Math.max(0L, collected);
        ready = Math.max(0L, ready);
        if (refusal != null && !refusal.refusesCollecting())
            refusal = null;
    }

    /** Whether an inventory was found behind the port at the last read. */
    public boolean hasInventory() {
        return attachedBlock != null;
    }

    /** Whether this summary says nothing at all, in which case it is left out of the packet entirely. */
    public boolean isEmpty() {
        return equals(NONE);
    }

    /** Why the warehouse did not take what is waiting here, if it said so. */
    public Optional<NoJobReason> refusalReason() {
        return Optional.ofNullable(refusal);
    }

    /** Display name of the attached block, if it is known in this game instance. */
    public Optional<Component> attachedBlockName() {
        if (attachedBlock == null)
            return Optional.empty();
        return BuiltInRegistries.BLOCK.getOptional(attachedBlock).map(Block::getName);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        if (collected > 0L)
            tag.putLong(COLLECTED, collected);
        if (ready > 0L)
            tag.putLong(READY, ready);
        if (attachedBlock != null)
            tag.putString(BLOCK, attachedBlock.toString());
        if (ownStorage)
            tag.putBoolean(OWN_STORAGE, true);
        if (refusal != null)
            tag.putString(REFUSAL, refusal.name());
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as {@link #NONE}. */
    public static PortCollectSummary read(CompoundTag tag) {
        ResourceLocation block = tag.contains(BLOCK, Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString(BLOCK))
                : null;
        NoJobReason refusal = null;
        if (tag.contains(REFUSAL, Tag.TAG_STRING)) {
            // A name from a newer or older version is simply no reason at all, never an exception on a client.
            for (NoJobReason candidate : NoJobReason.values())
                if (candidate.name().equals(tag.getString(REFUSAL)))
                    refusal = candidate;
        }
        return new PortCollectSummary(tag.getLong(COLLECTED), tag.getLong(READY), block, tag.getBoolean(OWN_STORAGE),
                refusal);
    }
}
