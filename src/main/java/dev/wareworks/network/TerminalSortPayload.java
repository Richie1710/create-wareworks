package dev.wareworks.network;

import dev.wareworks.Wareworks;
import dev.wareworks.core.terminal.TerminalSort;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: the player pressed the sort button on the terminal they have open (M24, issue #17, ADR-037).
 * <p>
 * It is the one thing the screen <b>tells</b> the server about the order, and it is a statement of preference, not an
 * instruction: the server resolves the menu the player really has open, reads the name through
 * {@link TerminalSort#byName} (an order this build does not have reads as the default) and stores it on that player
 * ({@code content.station.TerminalPreferences}). Nothing is requested, nothing moves, no item is named, and the
 * payload cannot reach another player's preferences — it carries no player.
 * <p>
 * The screen applies the new order at once and does not wait for an answer, because the sorting itself is the
 * client's own work on data the server owns; the server's stored copy is what the <b>next</b> screen opens with, at
 * any terminal and after any reconnect ({@link TerminalUsagePayload}).
 * <p>
 * The order travels by <b>name</b>, so {@link TerminalSort}'s declaration order stays free to change.
 *
 * @param containerId the menu the player has open
 * @param sort        the order they chose
 */
public record TerminalSortPayload(int containerId, TerminalSort sort) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalSortPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_sort"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalSortPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalSortPayload::write, TerminalSortPayload::new);

    public TerminalSortPayload {
        if (sort == null)
            sort = TerminalSort.DEFAULT;
    }

    private TerminalSortPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), TerminalSort.byName(buffer.readUtf()));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUtf(sort.name());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
