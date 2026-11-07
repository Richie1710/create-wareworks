package dev.wareworks.network;

import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.OptimizerScreenState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the rows of a Warehouse Optimizer, what the warehouse holds of each item by priority and how far the
 * optimizer's order has got, for the one player whose screen is open (issue #46, ADR-079).
 * <p>
 * A row's item is an {@link dev.wareworks.content.item.ItemKey} with data components, which is exactly why it travels
 * here and not in the block entity's update tag: that tag is part of every chunk packet. Goggles get numbers only.
 * <p>
 * Sent only when something changed, at most every {@code OptimizerMenu#REFRESH_INTERVAL_TICKS} ticks, so an optimizer
 * nobody is looking at costs no packet at all. Reading is bounded by {@link OptimizerScreenState}.
 *
 * @param containerId the menu the payload belongs to; the screen ignores a payload for another menu
 * @param state       the rows and the order as the screen renders them
 */
public record OptimizerScreenPayload(int containerId, OptimizerScreenState state) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OptimizerScreenPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("optimizer_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OptimizerScreenPayload> STREAM_CODEC =
            CustomPacketPayload.codec(OptimizerScreenPayload::write, OptimizerScreenPayload::new);

    public OptimizerScreenPayload {
        Objects.requireNonNull(state, "state");
    }

    private OptimizerScreenPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), OptimizerScreenState.read(buffer));
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
