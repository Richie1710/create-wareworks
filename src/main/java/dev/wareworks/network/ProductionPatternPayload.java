package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.core.production.FluidCell;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "put this item into that pattern entry" ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * <b>Nothing in it is trusted, and nothing in it moves an item.</b> A pattern entry is a ghost item: the server writes
 * the {@link ItemKey} and the count into the station's pattern slot and never takes anything out of an inventory, so a
 * crafted payload cannot duplicate or swallow items — the worst it can do is write a pattern the sending player could
 * have written by clicking. The server still resolves the payload against the menu that player really has open
 * ({@code ProductionMenu#submitPattern}), checks their reach and clamps the indices and the count.
 *
 * @param containerId the menu the player has open
 * @param pattern     the pattern slot
 * @param entry       the entry: a grid cell, {@value ProductionPatterns#RESULT_ENTRY} for the result, or
 *                    {@code ProductionMenu#CLEAR_WHOLE_PATTERN} to empty the slot
 * @param key         the item to set, or empty to clear the entry
 * @param count       items per run (the server clamps it); for a fluid cell the millibuckets per run
 * @param fluid       issue #55: for a <b>fluid cell</b> ({@code entry} is {@value ProductionPatterns#FLUID_ENTRY_BASE} or
 *                    the next one) the fluid's registry id to set, or empty to clear the cell; {@code key} is then
 *                    ignored. Empty for every other entry. The server resolves the id against its own registry and
 *                    ignores one it has no fluid for, and clamps the amount to {@code 1..1000}
 */
public record ProductionPatternPayload(int containerId, int pattern, int entry, Optional<ItemKey> key, int count,
                                       Optional<String> fluid) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ProductionPatternPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("production_pattern"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProductionPatternPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ProductionPatternPayload::write, ProductionPatternPayload::new);

    public ProductionPatternPayload {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fluid, "fluid");
    }

    /** An item entry edit: the shape every payload had before issue #55. */
    public ProductionPatternPayload(int containerId, int pattern, int entry, Optional<ItemKey> key, int count) {
        this(containerId, pattern, entry, key, count, Optional.empty());
    }

    /**
     * Sets fluid cell {@code cell} (0 or 1) to {@code fluid} with {@code millibuckets} per run (issue #55). It names the
     * fluid and moves none.
     */
    public static ProductionPatternPayload setFluid(int containerId, int pattern, int cell, String fluid,
            int millibuckets) {
        return new ProductionPatternPayload(containerId, pattern, ProductionPatterns.FLUID_ENTRY_BASE + cell,
                Optional.empty(), millibuckets, Optional.of(Objects.requireNonNull(fluid, "fluid")));
    }

    /** Clears fluid cell {@code cell} (0 or 1) (issue #55). */
    public static ProductionPatternPayload clearFluid(int containerId, int pattern, int cell) {
        return new ProductionPatternPayload(containerId, pattern, ProductionPatterns.FLUID_ENTRY_BASE + cell,
                Optional.empty(), 1, Optional.empty());
    }

    /** Whether this payload addresses a fluid cell rather than an item entry. */
    public boolean isFluidCell() {
        return entry >= ProductionPatterns.FLUID_ENTRY_BASE
                && entry < ProductionPatterns.FLUID_ENTRY_BASE + ProductionPatterns.FLUID_CELLS;
    }

    /** Sets an entry to {@code key}. */
    public static ProductionPatternPayload set(int containerId, int pattern, int entry, ItemKey key, int count) {
        return new ProductionPatternPayload(containerId, pattern, entry,
                Optional.of(Objects.requireNonNull(key, "key")), count);
    }

    /** Clears one entry. */
    public static ProductionPatternPayload clear(int containerId, int pattern, int entry) {
        return new ProductionPatternPayload(containerId, pattern, entry, Optional.empty(), 1);
    }

    /**
     * Clears a whole pattern slot, grid and result together ({@code ProductionMenu#CLEAR_WHOLE_PATTERN}). It is one
     * payload rather than ten, so emptying a slot is one edit against the menu's per-tick budget instead of a burst
     * that the budget would cut off half way through.
     */
    public static ProductionPatternPayload clearPattern(int containerId, int pattern) {
        return new ProductionPatternPayload(containerId, pattern, ProductionMenu.CLEAR_WHOLE_PATTERN,
                Optional.empty(), 1);
    }

    private ProductionPatternPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer)) : Optional.empty(),
                buffer.readVarInt(),
                buffer.readBoolean() ? Optional.of(buffer.readUtf(FluidCell.MAX_ID_LENGTH)) : Optional.empty());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(pattern);
        buffer.writeVarInt(entry);
        buffer.writeBoolean(key.isPresent());
        key.ifPresent(value -> ItemKey.STREAM_CODEC.encode(buffer, value));
        buffer.writeVarInt(count);
        // Appended after the count (issue #55): an item edit pays one zero byte for it.
        buffer.writeBoolean(fluid.isPresent());
        fluid.ifPresent(value -> buffer.writeUtf(value, FluidCell.MAX_ID_LENGTH));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
