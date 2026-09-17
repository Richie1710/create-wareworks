package dev.wareworks.network;

import java.util.Objects;
import java.util.UUID;

import dev.wareworks.Wareworks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "give up on this production order" ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * <b>Nothing in it is trusted.</b> The server resolves the payload against the production menu the sending player
 * really has open ({@code ProductionMenu#submitCancel}), checks their reach, and cancels the order only when it
 * belongs to <i>that</i> station — so a crafted payload cannot reach into another aisle. Cancelling moves no item: it
 * releases what the order still promised and stops there. Ingredients the crane already delivered stay in the station
 * or in the machine that took them, which is the boundary §3.5 documents.
 *
 * @param containerId the menu the player has open
 * @param orderId     the production order to cancel
 */
public record ProductionCancelPayload(int containerId, UUID orderId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProductionCancelPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("production_cancel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProductionCancelPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ProductionCancelPayload::write, ProductionCancelPayload::new);

    public ProductionCancelPayload {
        Objects.requireNonNull(orderId, "orderId");
    }

    private ProductionCancelPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUUID(orderId);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
