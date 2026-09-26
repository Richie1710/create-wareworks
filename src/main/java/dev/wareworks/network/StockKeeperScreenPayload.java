package dev.wareworks.network;

import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.StockKeeperScreenState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the rules of a warehouse stock keeper and what the warehouse currently holds of them, for the one
 * player whose screen is open ({@code docs/warehouse-system.md} §3.6, M15).
 * <p>
 * A rule's item is an {@link dev.wareworks.content.item.ItemKey} with data components, which is exactly why it travels
 * here and not in the block entity's update tag: that tag is part of every chunk packet and is read with a 2 MB client
 * quota, so item components there are the bug class {@code docs/architecture.md} forbids. Goggles get numbers only.
 * <p>
 * Sent only when something changed, at most every {@code StockKeeperMenu#REFRESH_INTERVAL_TICKS} ticks, so a keeper
 * nobody is looking at costs no packet at all. Reading is bounded by {@link StockKeeperScreenState}.
 *
 * @param containerId the menu the payload belongs to; the screen ignores a payload for another menu
 * @param state       the rules and their state as the screen renders them
 */
public record StockKeeperScreenPayload(int containerId, StockKeeperScreenState state) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StockKeeperScreenPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("stock_keeper_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StockKeeperScreenPayload> STREAM_CODEC =
            CustomPacketPayload.codec(StockKeeperScreenPayload::write, StockKeeperScreenPayload::new);

    public StockKeeperScreenPayload {
        Objects.requireNonNull(state, "state");
    }

    private StockKeeperScreenPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), StockKeeperScreenState.read(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        state.write(buffer);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
