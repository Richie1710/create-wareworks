package dev.wareworks.network;

import dev.wareworks.Wareworks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "the machine behind this station is worth another batch" — the way back from the <b>safety stop</b>
 * from inside a production station's screen ({@code docs/warehouse-system.md} §3.5.4, M20, issue #4, ADR-032).
 * <p>
 * <b>It names nothing at all</b> but the menu it came from, which is the whole of its security: what may be resumed is
 * decided by the patterns of the station that menu belongs to ({@code ProductionMenu#submitResume}), so a crafted
 * payload cannot lift the stop of an item this machine does not make, cannot reach another aisle and cannot even name an
 * item. It moves no item either — a resume only lets the warehouse plan again.
 * <p>
 * The stop is deliberately lifted by a <b>player's</b> action and never by a timer: the warehouse cannot tell a fixed
 * machine from a broken one (ADR-027).
 *
 * @param containerId the menu the player has open
 */
public record ProductionResumePayload(int containerId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProductionResumePayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("production_resume"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProductionResumePayload> STREAM_CODEC =
            CustomPacketPayload.codec(ProductionResumePayload::write, ProductionResumePayload::new);

    private ProductionResumePayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
