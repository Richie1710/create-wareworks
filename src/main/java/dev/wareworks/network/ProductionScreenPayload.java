package dev.wareworks.network;

import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.ProductionScreenState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the patterns of a warehouse production station and the production orders running at it, for the one
 * player whose screen is open ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * Pattern entries are items with data components, which is exactly why they travel here and not in the block entity's
 * update tag: that tag is part of every chunk packet and is read with a 2 MB client quota, so item components there
 * are the bug class {@code docs/architecture.md} forbids. Goggles get numbers only.
 * <p>
 * Sent only when something changed, at most every {@code ProductionMenu#REFRESH_INTERVAL_TICKS} ticks, so a station
 * nobody edits costs no packet at all. Reading is bounded by {@link ProductionScreenState}.
 *
 * @param containerId the menu the payload belongs to; the screen ignores a payload for another menu
 * @param state       the patterns and orders the screen renders
 */
public record ProductionScreenPayload(int containerId, ProductionScreenState state) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProductionScreenPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("production_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProductionScreenPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ProductionScreenPayload::write, ProductionScreenPayload::new);

    public ProductionScreenPayload {
        Objects.requireNonNull(state, "state");
    }

    private ProductionScreenPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), ProductionScreenState.read(buffer));
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
