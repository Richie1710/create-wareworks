package dev.wareworks.content.station;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Why a Recipe Selector station is stopped until a player confirms (ADR-085, design I5, §8): an order ended with
 * ingredients still in the line, or something the station does not make came back through its return input.
 * <p>
 * While a station holds, it fetches nothing, no order of it times out and its line does not switch to another result.
 * Only a player releases it (a sneak-click on the station with an empty hand, or the station screen's Resume), and a
 * release keeps the station's selection, so whatever is still in the line becomes surplus of the old product and never
 * the next one.
 *
 * @param cause      what armed the hold
 * @param result     the result the line was making when it was armed
 * @param orderId    the order that ended ({@link Cause#LOST_INGREDIENTS}), empty otherwise
 * @param unexpected the item that came back ({@link Cause#UNEXPECTED_RETURN}), empty otherwise
 * @param since      game time the hold was armed at
 * @param lost       ingredient items the ended order had handed over (0 for an unexpected return)
 */
public record SelectorHold(Cause cause, ItemKey result, Optional<UUID> orderId, Optional<ItemKey> unexpected,
                           long since, int lost) {
    /** What armed a hold. Saved by {@link #name()}, so a constant is never renamed. */
    public enum Cause {
        /** An order of the station ended without completing after it had handed ingredients over. */
        LOST_INGREDIENTS,
        /** An item the station does not expect lies in its return input. */
        UNEXPECTED_RETURN;

        static Optional<Cause> byName(String name) {
            for (Cause cause : values()) {
                if (cause.name().equals(name))
                    return Optional.of(cause);
            }
            return Optional.empty();
        }
    }

    private static final String CAUSE = "Cause";
    private static final String RESULT = "Result";
    private static final String ORDER = "Order";
    private static final String UNEXPECTED = "Unexpected";
    private static final String SINCE = "Since";
    private static final String LOST = "Lost";

    public SelectorHold {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(unexpected, "unexpected");
        lost = Math.max(0, lost);
    }

    /** A hold for an order that ended with {@code lost} ingredient items in the line. */
    public static SelectorHold lostIngredients(ItemKey result, UUID orderId, long since, int lost) {
        return new SelectorHold(Cause.LOST_INGREDIENTS, result, Optional.of(orderId), Optional.empty(), since, lost);
    }

    /** A hold for {@code unexpected}, found in the return input while the line made {@code result}. */
    public static SelectorHold unexpectedReturn(ItemKey result, ItemKey unexpected, long since) {
        return new SelectorHold(Cause.UNEXPECTED_RETURN, result, Optional.empty(), Optional.of(unexpected), since, 0);
    }

    /** Writes this hold (server save only). */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(CAUSE, cause.name());
        tag.put(RESULT, result.save(registries));
        orderId.ifPresent(id -> tag.putUUID(ORDER, id));
        unexpected.ifPresent(key -> tag.put(UNEXPECTED, key.save(registries)));
        tag.putLong(SINCE, since);
        tag.putInt(LOST, lost);
        return tag;
    }

    /**
     * Reads a hold. Save data is untrusted: an unknown cause or an unreadable result reads back as a hold for lost
     * ingredients of the item that is there, or as no hold when nothing is readable at all, never as an exception. A
     * hold that cannot be read fails <b>closed</b> where it can (the station stays stopped).
     */
    public static Optional<SelectorHold> load(CompoundTag tag, HolderLookup.Provider registries) {
        Optional<ItemKey> result = ItemKey.load(registries, tag.get(RESULT));
        Optional<ItemKey> unexpected = tag.contains(UNEXPECTED) ? ItemKey.load(registries, tag.get(UNEXPECTED))
                : Optional.empty();
        if (result.isEmpty())
            result = unexpected;
        if (result.isEmpty())
            return Optional.empty();
        Cause cause = Cause.byName(tag.getString(CAUSE).toUpperCase(Locale.ROOT)).orElse(Cause.LOST_INGREDIENTS);
        Optional<UUID> order = tag.hasUUID(ORDER) ? Optional.of(tag.getUUID(ORDER)) : Optional.empty();
        return Optional.of(new SelectorHold(cause, result.get(), order, unexpected, tag.getLong(SINCE),
                tag.getInt(LOST)));
    }
}
