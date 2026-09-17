package dev.wareworks.network;

import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.TerminalScreenStatus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the aisle and crane state of an open warehouse terminal screen (ADR-019).
 * <p>
 * Sent with the same throttle as {@link TerminalStockPayload} and only when it changed, so a crane standing still costs
 * nothing. Its size is fixed: {@link TerminalScreenStatus} holds only numbers and enum ordinals.
 *
 * @param containerId the menu the payload belongs to
 * @param status      the state the screen renders
 */
public record TerminalStatusPayload(int containerId, TerminalScreenStatus status) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalStatusPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalStatusPayload::write, TerminalStatusPayload::new);

    public TerminalStatusPayload {
        Objects.requireNonNull(status, "status");
    }

    private TerminalStatusPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), TerminalScreenStatus.read(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        status.write(buffer);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
