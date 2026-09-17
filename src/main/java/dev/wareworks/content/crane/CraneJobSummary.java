package dev.wareworks.content.crane;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;

/**
 * What goggles show about a crane's current job (crane and controller tooltips), as synced to clients: the type, the item
 * <b>type</b> (never item components, so the size is bounded; {@code docs/architecture.md}, goggle data notes), the
 * amount and both rack positions. Reading never throws.
 *
 * @param type   store or retrieve
 * @param item   the item type
 * @param amount the held amount once picked, before that the planned amount
 * @param source aisle-local source position
 * @param target aisle-local target position (changes on a reroute)
 */
public record CraneJobSummary(JobType type, Item item, int amount, RackPosition source, RackPosition target) {
    private static final String TYPE = "Type";
    private static final String ITEM = "Item";
    private static final String AMOUNT = "Amount";
    private static final String SOURCE = "Source";
    private static final String TARGET = "Target";
    private static final String X = "X";
    private static final String Y = "Y";
    private static final String SIDE = "Side";

    public CraneJobSummary {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        amount = Math.max(0, amount);
    }

    /** The summary of {@code job}. */
    public static CraneJobSummary of(TransportJob<ItemKey, RackPosition> job) {
        return new CraneJobSummary(job.type(), job.key().getItem(), job.picked() ? job.heldAmount() : job.plannedAmount(),
                job.source(), job.target());
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putString(TYPE, type.name());
        tag.putString(ITEM, ItemTypeSummaries.itemId(item));
        tag.putInt(AMOUNT, amount);
        tag.put(SOURCE, writeRack(source));
        tag.put(TARGET, writeRack(target));
    }

    /** Reads a summary written by {@link #write}; empty for missing or invalid data. Never throws. */
    public static Optional<CraneJobSummary> read(CompoundTag tag) {
        Optional<JobType> type = JobType.byName(tag.getString(TYPE));
        Optional<Item> item = ItemTypeSummaries.itemById(tag.getString(ITEM));
        Optional<RackPosition> source = readRack(tag.getCompound(SOURCE));
        Optional<RackPosition> target = readRack(tag.getCompound(TARGET));
        if (type.isEmpty() || item.isEmpty() || source.isEmpty() || target.isEmpty())
            return Optional.empty();
        return Optional.of(new CraneJobSummary(type.get(), item.get(), tag.getInt(AMOUNT), source.get(), target.get()));
    }

    /** NBT form of a rack position: {@code {X: int, Y: int, Side: "L"|"R"}}. */
    static CompoundTag writeRack(RackPosition rack) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(X, rack.x());
        tag.putInt(Y, rack.y());
        tag.putString(SIDE, String.valueOf(rack.side().letter()));
        return tag;
    }

    /** Reads a rack position written by {@link #writeRack}; empty unless it lies within the address limits. */
    static Optional<RackPosition> readRack(CompoundTag tag) {
        if (!tag.contains(X, Tag.TAG_INT) || !tag.contains(Y, Tag.TAG_INT))
            return Optional.empty();
        String sideText = tag.getString(SIDE);
        Optional<Side> side = sideText.length() == 1 ? Side.fromLetter(sideText.charAt(0)) : Optional.empty();
        int x = tag.getInt(X);
        int y = tag.getInt(Y);
        if (side.isEmpty() || x < 0 || x > AisleGeometry.MAX_LENGTH || y < 0 || y >= AisleGeometry.MAX_HEIGHT)
            return Optional.empty();
        return Optional.of(new RackPosition(x, y, side.get()));
    }
}
