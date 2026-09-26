package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperRules;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "put this item into that rule row" or "set that number of it" ({@code docs/warehouse-system.md}
 * §3.6, M15).
 * <p>
 * <b>Nothing in it is trusted, and nothing in it moves an item.</b> A rule row is a ghost item plus three numbers: the
 * server writes the {@link ItemKey} and the values into the keeper's row and never takes anything out of an inventory,
 * so a crafted payload cannot duplicate or swallow items — the worst it can do is write a rule the sending player
 * could have written by clicking. The server still resolves the payload against the menu that player really has open
 * ({@code StockKeeperMenu#submitRule}), checks their reach, bounds how many edits one tick may carry, and clamps every
 * number ({@code StockRule}'s canonical constructor), which also resolves "keep 64 but store at most 32" rather than
 * storing it.
 *
 * @param containerId the menu the player has open
 * @param row         the rule row
 * @param field       {@link StockKeeperRules#FIELD_ITEM}, {@link StockKeeperRules#FIELD_MINIMUM},
 *                    {@link StockKeeperRules#FIELD_MAXIMUM}, {@link StockKeeperRules#FIELD_RESERVE} or
 *                    {@link StockKeeperRules#FIELD_CLEAR_ROW}; anything else does nothing
 * @param key         the item to set, or empty to clear the row (only read for {@link StockKeeperRules#FIELD_ITEM})
 * @param value       the number to set (only read for the three number fields; the server clamps it)
 */
public record StockKeeperRulePayload(int containerId, int row, int field, Optional<ItemKey> key, long value)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StockKeeperRulePayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("stock_keeper_rule"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StockKeeperRulePayload> STREAM_CODEC =
            CustomPacketPayload.codec(StockKeeperRulePayload::write, StockKeeperRulePayload::new);

    public StockKeeperRulePayload {
        Objects.requireNonNull(key, "key");
    }

    /** Sets the item of a row; a row that had no rule starts one with all three numbers off. */
    public static StockKeeperRulePayload setItem(int containerId, int row, ItemKey key) {
        return new StockKeeperRulePayload(containerId, row, StockKeeperRules.FIELD_ITEM,
                Optional.of(Objects.requireNonNull(key, "key")), 0L);
    }

    /** Clears a whole row, item and numbers together. */
    public static StockKeeperRulePayload clearRow(int containerId, int row) {
        return new StockKeeperRulePayload(containerId, row, StockKeeperRules.FIELD_CLEAR_ROW, Optional.empty(), 0L);
    }

    /**
     * Lets a paused rule order again (M15 part 2, issue #3). It writes no number at all: the pause lives in the
     * controller, and the server only lifts it for the rule that row really holds.
     */
    public static StockKeeperRulePayload resume(int containerId, int row) {
        return new StockKeeperRulePayload(containerId, row, StockKeeperRules.FIELD_RESUME, Optional.empty(), 0L);
    }

    /** Sets one of the three numbers of a row ({@code StockRule.UNSET} switches it off). */
    public static StockKeeperRulePayload setNumber(int containerId, int row, int field, long value) {
        return new StockKeeperRulePayload(containerId, row, field, Optional.empty(), value);
    }

    private StockKeeperRulePayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer)) : Optional.empty(),
                buffer.readVarLong());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(row);
        buffer.writeVarInt(field);
        buffer.writeBoolean(key.isPresent());
        key.ifPresent(value -> ItemKey.STREAM_CODEC.encode(buffer, value));
        buffer.writeVarLong(value);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
