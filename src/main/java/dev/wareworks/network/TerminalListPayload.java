package dev.wareworks.network;

import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.TerminalListState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: how far a warehouse terminal's clipboard order has got (M23, issue #19,
 * {@code docs/warehouse-system.md} §3.4.4).
 * <p>
 * It is pushed with the terminal's other screen updates and only when it <b>changed</b>
 * ({@code WarehouseTerminalMenu#broadcastChanges}), so a terminal without a list order — and one whose order did not
 * move — costs no packet at all. Everything in it is a number or an enum ordinal plus at most one item: the entry the
 * order is working on, which is what makes the status line name it ({@link TerminalListState}).
 * <p>
 * The client never decides anything from it. What is fetched, in what portions and in what order is settled on the
 * server; this is the screen's reading of it.
 *
 * @param containerId the menu the state belongs to; the screen ignores one for another menu
 * @param state       the bounded list state, {@link TerminalListState#NONE} for a terminal without an order
 */
public record TerminalListPayload(int containerId, TerminalListState state) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalListPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalListPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalListPayload::write, TerminalListPayload::new);

    public TerminalListPayload {
        Objects.requireNonNull(state, "state");
    }

    private TerminalListPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), TerminalListState.read(buffer));
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
