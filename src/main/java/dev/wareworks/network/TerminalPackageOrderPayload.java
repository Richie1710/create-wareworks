package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.TerminalPackageOrderState;
import dev.wareworks.core.terminal.ListOrderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: how far a package-mode terminal's <b>address order</b> has got (issue #40, owner decision E11), for
 * the line the screen draws at the top of its production section with a cancel mark.
 * <p>
 * Pushed to every screen open at the terminal with the menu's other updates, and only when it changed, so a terminal
 * without an address order — every terminal of a world without package mode — costs no packet after the first push.
 * Bounded: a handful of numbers, a door position and at most {@value TerminalPackageOrderState#MAX_ADDRESSES} short
 * addresses. The state travels by name, so the order of {@link ListOrderState} stays free.
 *
 * @param containerId the menu the state belongs to; the screen ignores one for another menu
 * @param state       the bounded order state, {@link TerminalPackageOrderState#NONE} for a terminal without one
 */
public record TerminalPackageOrderPayload(int containerId, TerminalPackageOrderState state)
        implements CustomPacketPayload {
    private static final int MAX_STATE_NAME = 16;

    public static final CustomPacketPayload.Type<TerminalPackageOrderPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_package_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalPackageOrderPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalPackageOrderPayload::write, TerminalPackageOrderPayload::decode);

    public TerminalPackageOrderPayload {
        Objects.requireNonNull(state, "state");
    }

    private static TerminalPackageOrderPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        if (!buffer.readBoolean())
            return new TerminalPackageOrderPayload(containerId, TerminalPackageOrderState.NONE);
        UUID id = UUIDUtil.STREAM_CODEC.decode(buffer);
        ListOrderState state = ListOrderState.byName(buffer.readUtf(MAX_STATE_NAME)).orElse(ListOrderState.RUNNING);
        long wanted = buffer.readVarLong();
        long delivered = buffer.readVarLong();
        long inFlight = buffer.readVarLong();
        long gone = buffer.readVarLong();
        Optional<BlockPos> door = buffer.readOptional(in -> in.readBlockPos());
        int count = Math.min(Math.max(0, buffer.readVarInt()), TerminalPackageOrderState.MAX_ADDRESSES);
        List<String> addresses = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            addresses.add(buffer.readUtf(TerminalPackageOrderState.MAX_ADDRESS_LENGTH));
        return new TerminalPackageOrderPayload(containerId, new TerminalPackageOrderState(true, id, state, wanted,
                delivered, inFlight, gone, door, addresses));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBoolean(state.active());
        if (!state.active())
            return;
        UUIDUtil.STREAM_CODEC.encode(buffer, state.id());
        buffer.writeUtf(state.state().name(), MAX_STATE_NAME);
        buffer.writeVarLong(state.wanted());
        buffer.writeVarLong(state.delivered());
        buffer.writeVarLong(state.inFlight());
        buffer.writeVarLong(state.gone());
        buffer.writeOptional(state.door(), (out, pos) -> out.writeBlockPos(pos));
        buffer.writeVarInt(state.addresses().size());
        for (String address : state.addresses())
            buffer.writeUtf(address, TerminalPackageOrderState.MAX_ADDRESS_LENGTH);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
