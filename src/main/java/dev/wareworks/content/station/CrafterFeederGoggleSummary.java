package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.production.FeederStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * What the goggle tooltip of a Warehouse Crafter Feeder shows (issue #34, ADR-067): its status, the item of the order it
 * is crafting and how many runs of it are left.
 * <p>
 * An enum name, an item <b>id</b> and a number, so the synced tag has a bounded size and carries no item components — a
 * block entity's update tag is part of every chunk packet ({@code docs/architecture.md}, goggle data notes). The goggles
 * name the item by its id's default stack, which is what a player needs to recognise the order.
 * <p>
 * Records compare by value, so the server syncs only visible changes. Reading never throws.
 *
 * @param status   what the feeder is doing or why it is not
 * @param result   the item the loaded order makes, empty without one
 * @param runsLeft runs of the loaded order still to craft: those not laid yet plus those lying in the grid
 */
public record CrafterFeederGoggleSummary(FeederStatus status, Optional<ResourceLocation> result, int runsLeft) {
    public static final CrafterFeederGoggleSummary NONE =
            new CrafterFeederGoggleSummary(FeederStatus.IDLE, Optional.empty(), 0);

    private static final String STATUS = "Status";
    private static final String RESULT = "Result";
    private static final String RUNS_LEFT = "RunsLeft";

    public CrafterFeederGoggleSummary {
        Objects.requireNonNull(status, "status");
        if (result == null)
            result = Optional.empty();
        runsLeft = Math.max(0, runsLeft);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putString(STATUS, status.name());
        result.ifPresent(id -> tag.putString(RESULT, id.toString()));
        if (runsLeft > 0)
            tag.putInt(RUNS_LEFT, runsLeft);
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static CrafterFeederGoggleSummary read(CompoundTag tag) {
        FeederStatus status = FeederStatus.byName(tag.getString(STATUS)).orElse(FeederStatus.IDLE);
        Optional<ResourceLocation> result = tag.contains(RESULT, Tag.TAG_STRING)
                ? Optional.ofNullable(ResourceLocation.tryParse(tag.getString(RESULT))) : Optional.empty();
        return new CrafterFeederGoggleSummary(status, result, tag.getInt(RUNS_LEFT));
    }
}
