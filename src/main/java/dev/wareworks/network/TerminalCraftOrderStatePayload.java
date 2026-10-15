package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalCraftOrderState;
import dev.wareworks.content.station.TerminalPortionRefusal;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.terminal.ListOrderState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: how far a warehouse terminal's craft order has got, and the grid it was started for (issue #24,
 * slice 2, {@code docs/warehouse-system.md} §3.4.5, ADR-062).
 * <p>
 * It is pushed to <b>every</b> screen open at the terminal with the menu's other updates, and only when it changed
 * ({@code WarehouseTerminalMenu#broadcastChanges}), so a terminal without a craft order — and one whose order did not
 * move — costs no packet. That is also how the grid lock reaches everyone: while the order is open
 * ({@link TerminalCraftOrderState#locksGrid()}), each screen shows the order's cells instead of its own grid and
 * refuses to edit them.
 * <p>
 * Bounded: nine cells, two more items and a handful of numbers. The order's state travels by <b>name</b>, so the
 * declaration order of {@link ListOrderState} stays free, and a name this build does not know reads as running. The
 * client never decides anything from it.
 *
 * @param containerId the menu the state belongs to; the screen ignores one for another menu
 * @param state       the bounded craft order state, {@link TerminalCraftOrderState#NONE} for a terminal without one
 */
public record TerminalCraftOrderStatePayload(int containerId, TerminalCraftOrderState state)
        implements CustomPacketPayload {
    /** Longest state name a payload may claim; the bound keeps a hostile one from claiming a megabyte. */
    private static final int MAX_STATE_NAME = 16;

    public static final CustomPacketPayload.Type<TerminalCraftOrderStatePayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_craft_order_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftOrderStatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCraftOrderStatePayload::write, TerminalCraftOrderStatePayload::decode);

    public TerminalCraftOrderStatePayload {
        Objects.requireNonNull(state, "state");
    }

    private static TerminalCraftOrderStatePayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        if (!buffer.readBoolean())
            return new TerminalCraftOrderStatePayload(containerId, TerminalCraftOrderState.NONE);
        ListOrderState state = ListOrderState.byName(buffer.readUtf(MAX_STATE_NAME)).orElse(ListOrderState.RUNNING);
        // A grid of the wrong size is read in full and shown as no grid, rather than misread.
        CraftGrid<ItemKey> cells = TerminalCraftPayload.readCells(buffer).orElse(CraftGrid.empty());
        int crafts = buffer.readVarInt();
        long wanted = buffer.readVarLong();
        long delivered = buffer.readVarLong();
        long inFlight = buffer.readVarLong();
        Optional<ItemKey> current = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                : Optional.empty();
        Optional<TerminalPortionRefusal> refusal = TerminalPortionRefusal.read(buffer);
        return new TerminalCraftOrderStatePayload(containerId, new TerminalCraftOrderState(true, state, cells,
                crafts, wanted, delivered, inFlight, current, refusal));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBoolean(state.active());
        if (!state.active())
            return;
        buffer.writeUtf(state.state().name(), MAX_STATE_NAME);
        TerminalCraftPayload.writeCells(buffer, state.cells());
        buffer.writeVarInt(state.crafts());
        buffer.writeVarLong(state.wanted());
        buffer.writeVarLong(state.delivered());
        buffer.writeVarLong(state.inFlight());
        buffer.writeBoolean(state.current().isPresent());
        state.current().ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
        TerminalPortionRefusal.write(buffer, state.refusal());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
