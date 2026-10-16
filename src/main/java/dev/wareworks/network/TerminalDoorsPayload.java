package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.TerminalDoors;
import dev.wareworks.core.port.DoorState;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the package doors a click at the open terminal screen may deliver to (M27, issue #18, ADR-065 D9),
 * in rack order, with how each stands and what the warehouse still owes it.
 * <p>
 * Sent with every full sync of a screen and whenever a door, its state or its counts changed, compared by value like
 * the production orders, so a warehouse without doors costs one empty payload per opened screen and nothing after it.
 * It carries no choice: the screen decides where its clicks go, and the server only ever acts on the door a request
 * names.
 * <p>
 * <b>Bounded both ways.</b> At most {@value TerminalDoors#MAX_ROWS} rows are written and a claim of more is refused
 * when reading; every string is cut by the row itself before it is written ({@link TerminalDoorRow}), because the string
 * writer refuses one past its bound. A door's state travels <b>by name</b>, and a name this build does not know reads as
 * {@link DoorState#NOT_LOADED} — a grey row nobody can choose, which is the safe misreading.
 *
 * @param containerId the menu the doors belong to; the screen ignores them for another menu
 * @param rows        the doors, at most {@value TerminalDoors#MAX_ROWS}
 * @param notListed   doors beyond those rows, which the screen only counts
 * @param unchecked   whether the server stopped looking before the last output, which the screen says
 */
public record TerminalDoorsPayload(int containerId, List<TerminalDoorRow> rows, int notListed, boolean unchecked)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalDoorsPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_doors"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalDoorsPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalDoorsPayload::write, TerminalDoorsPayload::decode);

    /** Longest state name a payload may claim; the longest constant has 16 characters. */
    private static final int MAX_STATE_NAME = 32;

    public TerminalDoorsPayload {
        Objects.requireNonNull(rows, "rows");
        // Through the bounded value, so a payload is never built with more rows than may be written.
        TerminalDoors doors = new TerminalDoors(rows, notListed, unchecked);
        rows = doors.rows();
        notListed = doors.notListed();
    }

    /** The doors a menu pushes, as it compares them. */
    public TerminalDoorsPayload(int containerId, TerminalDoors doors) {
        this(containerId, doors.rows(), doors.notListed(), doors.unchecked());
    }

    /** The doors as the menu compares them. */
    public TerminalDoors doors() {
        return new TerminalDoors(rows, notListed, unchecked);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(rows.size());
        for (TerminalDoorRow row : rows) {
            buffer.writeBlockPos(row.pos());
            buffer.writeUtf(row.rackAddress(), TerminalDoorRow.MAX_RACK_ADDRESS_LENGTH);
            buffer.writeUtf(row.label(), TerminalDoorRow.MAX_LABEL_LENGTH);
            buffer.writeBoolean(row.labelCut());
            buffer.writeUtf(row.state().name(), MAX_STATE_NAME);
            buffer.writeVarLong(row.waiting());
            buffer.writeVarLong(row.delivered());
        }
        buffer.writeVarInt(notListed);
        buffer.writeBoolean(unchecked);
    }

    private static TerminalDoorsPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > TerminalDoors.MAX_ROWS)
            throw new DecoderException("a door list of " + count + " rows, at most " + TerminalDoors.MAX_ROWS
                    + " allowed");
        List<TerminalDoorRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            rows.add(new TerminalDoorRow(buffer.readBlockPos(), buffer.readUtf(TerminalDoorRow.MAX_RACK_ADDRESS_LENGTH),
                    buffer.readUtf(TerminalDoorRow.MAX_LABEL_LENGTH), buffer.readBoolean(),
                    DoorState.byName(buffer.readUtf(MAX_STATE_NAME)), buffer.readVarLong(), buffer.readVarLong()));
        return new TerminalDoorsPayload(containerId, rows, buffer.readVarInt(), buffer.readBoolean());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
